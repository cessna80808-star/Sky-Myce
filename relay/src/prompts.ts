import type { AccountStore } from "./accounts";
import { deferInteraction } from "./discord-accounts";
import { AuthorizationError, deny } from "./drm";

export type PromptConfig = {
  DISCORD_GUILD_ID?: string; ACCOUNT_STORE?: DurableObjectNamespace<AccountStore>;
  DISCORD_BACKEND_BOT_TOKEN?: string; BACKEND_BOT_TOKEN?: string; PROMPT_CHANNEL_ID?: string;
  PROMPT_BRIDGE_SECRET?: string; PROMPT_SUBMISSIONS_ENABLED?: string; PROMPT_EXECUTION_VERIFIED?: string;
  PROMPT_PROVIDER_ACCOUNT_ID?: string; PROMPT_MAX_INPUT_BYTES?: string;
  PROMPT_BASELINE_REVISION?: string;
};
export type PromptActor = { id: string; guildId: string; interactionId: string };
type Source = { channelId?: string; interactionId?: string; messageId?: string; filename?: string };
type Capabilities = { model?: string; effort?: string; restricted?: boolean; sharedAdmissionGate?: boolean; safeRecovery?: boolean };
type Window = { id: string; usedPercent: number; resetsAt: number | null };
type Quota = { providerAccountId: string; refreshedAt: number; windows: Window[] };
type Pending = { id: string; threadId: string | null; kind: "question" | "approval" | "review" | "local";
  text: string; choices: string[]; response?: { id: string; actor: string; value: string; screening: "required" | "allow" | "review" | "reject" }; resolved?: boolean };
export type PromptJob = {
  id: string; submitter: string; guildId: string; interactionId: string; source: Source;
  prompt: string; status: string; createdAt: number; updatedAt: number; expiresAt: number; terminalAt?: number;
  revision: number; screening?: { verdict: string; reason: string; humanReview?: boolean }; lunaUsage?: Record<string, number | string | null>;
  claim?: { id: string; runnerId: string; expiresAt: number }; threadId?: string; worktree?: string; branch?: string;
  commandId?: string; dispatchIntentAt?: number; dispatchGrantedAt?: number; pending?: Pending;
  baselineRevision?: string;
  heldAnswer?: Pending; answerScreeningReason?: string;
  reconciliationReason?: string;
  interruptReason?: string; messageId?: string; result?: { summary: string; checks: string; unresolved: string; threadUrl?: string; preserved?: boolean };
};
type Control = { paused: boolean; cooldownUntil: number; activeJob: string | null; runner?: {
  id: string; heartbeatAt: number; availability: string; quota?: Quota; capabilities: Capabilities } };
type Delivery = { id: string; job_id: string; payload: string; attempts: number; next_at: number; lease_until: number };
type Result = { ok: boolean; error?: string; jobId?: string; duplicate?: boolean; replayed?: boolean; cooldownUntil?: number;
  job?: PromptJob | null; active?: PromptJob | null; response?: Pending["response"] | null; pending?: Pending;
  activeJob?: string | null; paused?: boolean; status?: string; state?: string; queueLength?: number; heartbeatAt?: number | null;
  quota?: Quota | null; executionPermitted?: boolean; permitted?: boolean; needsScreening?: boolean; reconciliationRequired?: boolean;
  blockingReason?: string | null; cancelRequested?: string | null; interruptReason?: string | null; lunaUsage?: LunaUsage };
type LunaUsage = { inputTokens: number; outputTokens: number; requests: number };
const HOUR = 3_600_000, WEEK = 7 * 24 * HOUR, FRESH = 120_000, FILE_LIMIT = 65_536;
const encoder = new TextEncoder(), snowflake = /^\d{17,20}$/, identifier = /^[A-Za-z0-9_-]{1,100}$/, jobId = /^[a-f0-9]{32}$/;
const commands = new Set(["prompt", "Run prompt", "prompt-status", "prompt-cancel", "prompt-usage", "prompt-runner", "prompt-pause", "prompt-resume"]);
const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const textValid = (value: unknown, maximum: number): value is string => typeof value === "string" && value.isWellFormed() && value.trim().length > 0 && value.length <= maximum && !/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value);
const validIdentifier = (value: unknown): value is string => typeof value === "string" && identifier.test(value);
const terminal = (job: PromptJob) => job.terminalAt !== undefined;
const safeFailure = (error: unknown): Result => ({ ok: false, error: error instanceof AuthorizationError ? error.code : "prompt_unavailable" });
const ephemeral = (content: string) => Response.json({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
export function promptAuthorized(actor: PromptActor, env: PromptConfig): boolean {
  return !!actor && ["1250627753191931914", "978376257534328883"].includes(actor.id) && !!env.DISCORD_GUILD_ID &&
    actor.guildId === env.DISCORD_GUILD_ID && snowflake.test(actor.interactionId);
}
function inputLimit(env: PromptConfig): number {
  const limit = Number(env.PROMPT_MAX_INPUT_BYTES);
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 73_728) deny("input_budget_unconfigured");
  return limit;
}
export function quotaBlock(quota: Quota | undefined, account: string | undefined, now = Date.now()): string | null {
  if (!quota || !account || quota.providerAccountId !== account || !quota.windows.length) return "quota_unknown";
  if (now - quota.refreshedAt > FRESH || quota.refreshedAt > now + 5000 || quota.windows.some(window => window.resetsAt !== null && window.resetsAt <= now)) return "quota_stale";
  return quota.windows.some(window => 100 - window.usedPercent < 20) ? "low_quota" : null;
}

/** One project and one hourly intake: the existing global SQL coordinator is the admission atom. */
export class PromptJobs {
  constructor(private storage: DurableObjectStorage, private env: PromptConfig) {
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS prompt_control (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS prompt_jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL, created_at INTEGER NOT NULL, data TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS prompt_fifo ON prompt_jobs(status,created_at,id);
      CREATE TABLE IF NOT EXISTS prompt_seen (id TEXT PRIMARY KEY, job_id TEXT);
      CREATE TABLE IF NOT EXISTS prompt_nonces (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS prompt_operations (id TEXT PRIMARY KEY, result TEXT NOT NULL, created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS prompt_operation_seen (id TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS prompt_pending_seen (id TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS prompt_deliveries (id TEXT PRIMARY KEY, job_id TEXT NOT NULL, payload TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL, lease_until INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS prompt_delivery_due ON prompt_deliveries(next_at);`);
    storage.sql.exec("INSERT OR IGNORE INTO prompt_control VALUES(1,?)", JSON.stringify({ paused: true, cooldownUntil: 0, activeJob: null }));
  }
  private control(): Control { return JSON.parse(this.storage.sql.exec<{ data: string }>("SELECT data FROM prompt_control WHERE id=1").one().data); }
  private putControl(control: Control): void { this.storage.sql.exec("UPDATE prompt_control SET data=? WHERE id=1", JSON.stringify(control)); }
  private get(id: unknown): PromptJob {
    if (typeof id !== "string" || !jobId.test(id)) deny("invalid_job");
    const row = this.storage.sql.exec<{ data: string }>("SELECT data FROM prompt_jobs WHERE id=?", id).toArray()[0];
    if (!row) deny("job_not_found"); return JSON.parse(row.data);
  }
  private save(job: PromptJob, notify = true): void {
    job.updatedAt = Date.now(); job.revision++;
    this.storage.sql.exec("INSERT INTO prompt_jobs VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,data=excluded.data", job.id, job.status, job.createdAt, JSON.stringify(job));
    if (notify) {
      // Coalesce unsent updates: a retry sends current state, never re-enters model execution.
      this.storage.sql.exec("INSERT INTO prompt_deliveries(id,job_id,payload,next_at) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,next_at=MIN(next_at,excluded.next_at)",
        job.id, job.id, JSON.stringify({ revision: job.revision, ...notification(job) }), Date.now());
    }
  }
  private finish(job: PromptJob, status: string, result?: PromptJob["result"]): void {
    job.status = status; job.terminalAt = Date.now(); job.pending = undefined; job.result = result ?? job.result;
    const control = this.control(); if (control.activeJob === job.id) { control.activeJob = null; this.putControl(control); }
    this.save(job);
  }
  private authorized(actor: PromptActor): void { if (!promptAuthorized(actor, this.env)) deny("prompt_forbidden"); }
  private executionBlock(control = this.control()): string | null {
    if (this.env.PROMPT_EXECUTION_VERIFIED !== "true") return "execution_unverified";
    if (!this.env.PROMPT_BASELINE_REVISION || !/^[a-f0-9]{40}$/.test(this.env.PROMPT_BASELINE_REVISION)) return "baseline_unconfigured";
    const runner = control.runner;
    if (!runner || Date.now() - runner.heartbeatAt > FRESH) return "offline";
    if (runner.capabilities.model !== "gpt-6.1-sol" || runner.capabilities.effort !== "ultra" ||
      runner.capabilities.restricted !== true || runner.capabilities.sharedAdmissionGate !== true || runner.capabilities.safeRecovery !== true) return "execution_unverified";
    return quotaBlock(runner.quota, this.env.PROMPT_PROVIDER_ACCOUNT_ID);
  }
  private replay(actor: PromptActor): void {
    if (this.storage.sql.exec("SELECT id FROM prompt_seen WHERE id=?", actor.interactionId).toArray().length) deny("interaction_replayed");
    this.storage.sql.exec("INSERT INTO prompt_seen VALUES(?,NULL)", actor.interactionId);
  }
  intake(actor: PromptActor): Result {
    try {
      this.authorized(actor);
      const previous = this.storage.sql.exec<{ job_id: string | null }>("SELECT job_id FROM prompt_seen WHERE id=?", actor.interactionId).toArray()[0];
      const control = this.control();
      if (previous) return previous.job_id ? { ok: true, duplicate: true, jobId: previous.job_id, cooldownUntil: control.cooldownUntil } : { ok: false, error: "interaction_replayed" };
      if (this.env.PROMPT_SUBMISSIONS_ENABLED !== "true") deny("submissions_disabled");
      inputLimit(this.env);
      const quota = control.runner?.quota;
      if (quota && quota.providerAccountId === this.env.PROMPT_PROVIDER_ACCOUNT_ID && quota.windows.some(window => 100 - window.usedPercent < 20)) deny("low_quota");
      if (control.cooldownUntil > Date.now()) return { ok: false, error: "submission_cooldown", cooldownUntil: control.cooldownUntil };
      return { ok: true };
    } catch (error) { return safeFailure(error); }
  }
  submit(actor: PromptActor, prompt: string, source: Record<string, unknown>): Result {
    try { return this.storage.transactionSync(() => {
      this.authorized(actor);
      const previous = this.storage.sql.exec<{ job_id: string | null }>("SELECT job_id FROM prompt_seen WHERE id=?", actor.interactionId).toArray()[0];
      if (previous) return previous.job_id ? { ok: true, jobId: previous.job_id, duplicate: true } : { ok: false, error: "interaction_replayed" };
      if (this.env.PROMPT_SUBMISSIONS_ENABLED !== "true") deny("submissions_disabled");
      if (!textValid(prompt, 120_000) || encoder.encode(prompt).length > inputLimit(this.env)) deny("invalid_prompt");
      const control = this.control(), now = Date.now();
      const quota = control.runner?.quota;
      if (quota && quota.providerAccountId === this.env.PROMPT_PROVIDER_ACCOUNT_ID && quota.windows.some(window => 100 - window.usedPercent < 20)) deny("low_quota");
      if (control.cooldownUntil > now) return { ok: false, error: "submission_cooldown", cooldownUntil: control.cooldownUntil };
      const id = crypto.randomUUID().replaceAll("-", "");
      const job: PromptJob = { id, submitter: actor.id, guildId: actor.guildId, interactionId: actor.interactionId, source,
        prompt, status: "queued", createdAt: now, updatedAt: now, expiresAt: now + WEEK, revision: 0 };
      this.save(job); this.storage.sql.exec("INSERT INTO prompt_seen VALUES(?,?)", actor.interactionId, id);
      control.cooldownUntil = now + HOUR; this.putControl(control);
      return { ok: true, jobId: id, cooldownUntil: control.cooldownUntil };
    }); } catch (error) { return safeFailure(error); }
  }
  inspect(actor: PromptActor, id?: string): Result {
    try {
      this.authorized(actor); this.cleanup(); const control = this.control();
      if (id) {
        const job = this.get(id); let blockingReason: string | null = job.interruptReason ?? null;
        if (!blockingReason && job.status === "needs_reconciliation") blockingReason = job.reconciliationReason ?? "needs_reconciliation";
        if (!blockingReason && job.pending && !job.pending.resolved) blockingReason = job.pending.response
          ? job.pending.response.screening === "required" ? "answer_screening" : "response_awaits_delivery"
          : job.pending.kind === "local" ? "local_action_required" : `awaiting_${job.pending.kind}`;
        if (!blockingReason && job.status === "queued") blockingReason = control.paused ? "paused" : this.executionBlock(control);
        return { ok: true, job, blockingReason };
      }
      const count = this.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM prompt_jobs WHERE status='queued'").one().n;
      const active = control.activeJob ? this.get(control.activeJob) : null;
      const lunaUsage = this.storage.sql.exec<LunaUsage>(`SELECT
        COALESCE(SUM(json_extract(data,'$.lunaUsage.input_tokens')),0) AS inputTokens,
        COALESCE(SUM(json_extract(data,'$.lunaUsage.output_tokens')),0) AS outputTokens,
        COALESCE(SUM(json_extract(data,'$.lunaUsage.requests')),0) AS requests FROM prompt_jobs`).one();
      const execution = this.executionBlock(control), offline = !control.runner || Date.now() - control.runner.heartbeatAt > FRESH;
      const state = offline ? "offline" : active?.pending && !active.pending.resolved ? "waiting for input" : active ? "busy" : control.paused ? "paused" : execution === "low_quota" ? "low quota" : execution ? execution : "ready";
      return { ok: true, state, paused: control.paused, cooldownUntil: control.cooldownUntil, heartbeatAt: control.runner?.heartbeatAt ?? null,
        activeJob: active?.id ?? null, queueLength: count, quota: control.runner?.quota ?? null,
        executionPermitted: !control.paused && !execution && !active, blockingReason: control.paused ? "paused" : execution ?? (active ? "busy" : null), lunaUsage };
    } catch (error) { return safeFailure(error); }
  }
  command(actor: PromptActor, action: string, input: Record<string, unknown> = {}): Result {
    try { return this.storage.transactionSync(() => {
      this.authorized(actor); this.cleanup(); this.replay(actor);
      const control = this.control();
      if (action === "pause" || action === "resume") { control.paused = action === "pause"; this.putControl(control); return { ok: true, paused: control.paused, blockingReason: this.executionBlock(control) }; }
      const job = this.get(input.jobId);
      if (terminal(job) || job.expiresAt <= Date.now()) deny("job_expired_or_finished");
      if (action === "cancel") {
        if (input.pendingId !== undefined && (job.pending?.id !== input.pendingId || job.pending.resolved || job.pending.response)) deny("stale_request");
        if (job.status === "queued" && !job.claim) this.finish(job, "cancelled");
        else { job.interruptReason = "cancel_requested"; job.status = "cancelling"; this.save(job); }
        return { ok: true, status: job.status };
      }
      const pending = job.pending;
      if (!pending || pending.id !== input.pendingId || pending.threadId !== (job.threadId ?? null) || pending.resolved || pending.response) deny("stale_request");
      if (action === "answer") {
        if (pending.kind !== "question" || !textValid(input.value, 4000)) deny("invalid_answer");
      } else if (action === "approve" || action === "decline") {
        if (!["approval", "review"].includes(pending.kind)) deny("invalid_approval");
      } else deny("invalid_command");
      if (pending.kind === "review" && job.heldAnswer) {
        const original = job.heldAnswer;
        if (action === "approve") original.response!.screening = "allow";
        else original.response = undefined;
        job.pending = original; job.heldAnswer = undefined; this.save(job);
        return { ok: true, status: action === "approve" ? "answer_review_approved" : "answer_review_declined" };
      }
      pending.response = { id: actor.interactionId, actor: actor.id, value: action === "answer" ? input.value as string : action,
        screening: action === "answer" ? "required" : "allow" };
      this.save(job); return { ok: true, status: "response_recorded", needsScreening: action === "answer" };
    }); } catch (error) { return safeFailure(error); }
  }
  pending(actor: PromptActor, id: string, request: string): Result {
    try { this.authorized(actor); const job = this.get(id);
      if (terminal(job) || job.expiresAt <= Date.now() || job.pending?.id !== request || job.pending.resolved || job.pending.response) deny("stale_request");
      return { ok: true, pending: job.pending };
    } catch (error) { return safeFailure(error); }
  }
  runner(input: Record<string, unknown>, nonce: string): Result {
    try { return this.storage.transactionSync(() => {
      const now = Date.now(), sql = this.storage.sql;
      if (!/^[a-f0-9]{32}$/.test(nonce) || !validIdentifier(input.runnerId) || !validIdentifier(input.requestId)) deny("invalid_runner_request");
      sql.exec("DELETE FROM prompt_nonces WHERE expires_at<=?", now);
      if (sql.exec("SELECT id FROM prompt_nonces WHERE id=?", nonce).toArray().length) deny("runner_replay");
      sql.exec("INSERT INTO prompt_nonces VALUES(?,?)", nonce, now + FRESH);
      this.cleanup();
      const operationId = `${input.runnerId}:${input.requestId}`;
      const previous = sql.exec<{ result: string }>("SELECT result FROM prompt_operations WHERE id=?", operationId).toArray()[0];
      if (previous) return { ...JSON.parse(previous.result), replayed: true };
      if (sql.exec("SELECT id FROM prompt_operation_seen WHERE id=?", operationId).toArray().length) return { ok: false, error: "runner_operation_expired" };
      const result = this.runOperation(input);
      if (!["heartbeat", "response", "inspect"].includes(String(input.op)) && result.ok) {
        sql.exec("INSERT INTO prompt_operations VALUES(?,?,?)", operationId, JSON.stringify(result), now);
        sql.exec("INSERT INTO prompt_operation_seen VALUES(?)", operationId);
      }
      return result;
    }); } catch (error) { return safeFailure(error); }
  }
  private runOperation(input: Record<string, unknown>): Result {
    const control = this.control(), now = Date.now(), runnerId = input.runnerId as string;
    if (input.op === "heartbeat") {
      const capabilities = object(input.capabilities), quota = object(input.quota);
      if (!capabilities || !["ready", "busy", "local_action"].includes(String(input.availability))) deny("invalid_heartbeat");
      let validated: Quota | undefined;
      if (quota) {
        if (quota.providerAccountId !== this.env.PROMPT_PROVIDER_ACCOUNT_ID || !Number.isSafeInteger(quota.refreshedAt) ||
            (quota.refreshedAt as number) > now + 5000 || !Array.isArray(quota.windows) || !quota.windows.length || quota.windows.length > 8) deny("invalid_quota");
        const ids = new Set<string>(), windows: Window[] = [];
        for (const raw of quota.windows) {
          const window = object(raw);
          if (!window || typeof window.id !== "string" || !/^[A-Za-z0-9_.:-]{1,100}$/.test(window.id) || ids.has(window.id) || typeof window.usedPercent !== "number" ||
            !Number.isFinite(window.usedPercent) || window.usedPercent < 0 || window.usedPercent > 100 ||
            !(window.resetsAt === null || Number.isSafeInteger(window.resetsAt))) deny("invalid_quota");
          ids.add(window.id as string); windows.push(window as Window);
        }
        validated = { providerAccountId: quota.providerAccountId as string, refreshedAt: quota.refreshedAt as number, windows };
      }
      if (control.runner && control.runner.id !== runnerId && now - control.runner.heartbeatAt <= FRESH) deny("runner_busy");
      control.runner = { id: runnerId, heartbeatAt: now, availability: input.availability as string, capabilities, quota: validated ?? control.runner?.quota };
      this.putControl(control);
      const active = control.activeJob ? this.get(control.activeJob) : null;
      if (active?.claim?.runnerId === runnerId && active.status !== "needs_reconciliation") {
        active.claim.expiresAt = now + FRESH;
        const block = this.executionBlock(control);
        if (block && active.dispatchGrantedAt) active.interruptReason ??= block;
        this.save(active, false);
      }
      return { ok: true, active, paused: control.paused, blockingReason: this.executionBlock(control), cancelRequested: active?.interruptReason ?? null };
    }
    if (input.op === "claim") {
      if (control.activeJob) return { ok: false, error: "project_busy", activeJob: control.activeJob, reconciliationRequired: this.get(control.activeJob).status === "needs_reconciliation" };
      const block = control.paused ? "paused" : this.executionBlock(control);
      if (block) return { ok: false, error: block };
      if (control.runner?.id !== runnerId || control.runner.availability !== "ready") return { ok: false, error: "runner_busy" };
      const row = this.storage.sql.exec<{ id: string }>("SELECT id FROM prompt_jobs WHERE status='queued' ORDER BY created_at,id LIMIT 1").toArray()[0];
      if (!row) return { ok: true, job: null };
      const job = this.get(row.id); job.claim = { id: crypto.randomUUID().replaceAll("-", ""), runnerId, expiresAt: now + FRESH };
      job.baselineRevision = this.env.PROMPT_BASELINE_REVISION;
      job.status = "screening"; control.activeJob = job.id; this.putControl(control); this.save(job); return { ok: true, job };
    }
    const job = this.get(input.jobId);
    if (!job.claim || job.claim.runnerId !== runnerId || job.claim.id !== input.claimId || control.activeJob !== job.id || terminal(job)) deny("claim_mismatch");
    if (input.op === "inspect" || input.op === "response") return { ok: true, job, response: job.pending?.response ?? null, interruptReason: job.interruptReason ?? null };
    if (input.op === "reconcile") {
      if (job.status !== "needs_reconciliation" || input.threadId !== (job.threadId ?? null) || input.commandId !== (job.commandId ?? null)) deny("reconciliation_mismatch");
      if (!["running", "waiting_for_input", "stopped"].includes(String(input.state))) deny("invalid_reconciliation");
      if (input.state === "stopped") { job.status = "held"; job.interruptReason = "needs_authorized_continuation"; }
      else job.status = input.state as string;
      job.claim.expiresAt = now + FRESH; this.save(job); return { ok: true, job };
    }
    if (input.op === "finish") {
      if (input.stopped !== true || !["complete", "failed", "cancelled", "expired", "rejected"].includes(String(input.status))) deny("stop_confirmation_required");
      const result = object(input.result);
      if (!result || !textValid(result.summary, 16_000) || typeof result.checks !== "string" || result.checks.length > 8000 ||
          typeof result.unresolved !== "string" || result.unresolved.length > 8000 || (job.worktree && result.preserved !== true)) deny("invalid_result");
      if (result.threadUrl !== undefined && (typeof result.threadUrl !== "string" || !/^https:\/\//.test(result.threadUrl) || result.threadUrl.length > 500)) deny("invalid_result");
      if (job.interruptReason === "cancel_requested" && input.status !== "cancelled" || job.interruptReason === "expired" && input.status !== "expired") deny("terminal_status_mismatch");
      this.finish(job, input.status as string, result as PromptJob["result"]); return { ok: true, job };
    }
    if (input.op === "uncertain") {
      if (!textValid(input.reason, 500)) deny("invalid_reconciliation_reason");
      job.status = "needs_reconciliation"; job.reconciliationReason = input.reason; this.save(job); return { ok: true, job };
    }
    if (job.status === "needs_reconciliation") deny("needs_reconciliation");
    if (input.op === "native-response") {
      const block = this.executionBlock(control); if (block) deny(block);
      if (job.interruptReason) deny(job.interruptReason);
      if (!job.dispatchGrantedAt || input.threadId !== job.threadId || !jobId.test(String(input.pendingId)) ||
          !["question", "approval"].includes(String(input.kind)) || input.verdict !== "allow") deny("invalid_native_response");
      let pending = job.pending;
      if (pending && (pending.id === input.pendingId ? pending.response || pending.resolved : !pending.resolved)) deny("stale_request");
      if (!pending || pending.id !== input.pendingId) {
        if (this.storage.sql.exec("SELECT id FROM prompt_pending_seen WHERE id=?", `${job.id}:${input.pendingId}`).toArray().length) deny("stale_request");
        const display = object(input.nativeDisplay);
        pending = validatePending({ id: input.pendingId, kind: input.kind, text: display?.text, choices: display?.choices ?? [] }, job.threadId!);
        this.storage.sql.exec("INSERT INTO prompt_pending_seen VALUES(?)", `${job.id}:${pending.id}`);
      }
      if (pending.kind !== input.kind || !textValid(input.value, 4000) || input.kind === "approval" && !["approve", "decline", "cancel"].includes(input.value)) deny("invalid_native_response");
      pending.response = { id: input.requestId as string, actor: "t3", value: input.value, screening: "allow" }; pending.resolved = true;
      job.pending = pending; job.status = "running";
      if (input.value === "cancel" && input.kind === "approval") { job.interruptReason = "cancel_requested"; job.status = "cancelling"; }
      this.save(job); return { ok: true, permitted: true, job };
    }
    if (input.op === "resolved") {
      if (!job.pending || job.pending.id !== input.pendingId || input.threadId !== job.pending.threadId) deny("stale_request");
      job.pending.resolved = true; this.save(job); return { ok: true };
    }
    if (input.op === "pending") {
      if (!job.dispatchGrantedAt || input.threadId !== job.threadId || job.pending && !job.pending.resolved) deny("pending_request_conflict");
      const pending = validatePending(input.pending, job.threadId!);
      if (this.storage.sql.exec("SELECT id FROM prompt_pending_seen WHERE id=?", `${job.id}:${pending.id}`).toArray().length) deny("stale_request");
      this.storage.sql.exec("INSERT INTO prompt_pending_seen VALUES(?)", `${job.id}:${pending.id}`);
      job.pending = pending; job.status = "waiting_for_input"; this.save(job); return { ok: true, job };
    }
    if (input.op === "screening") {
      const responseId = input.responseId;
      const pending = job.pending;
      if (responseId !== undefined && (!pending?.response || pending.response.id !== responseId || pending.response.screening !== "required")) deny("stale_request");
      if (responseId === undefined && (job.status !== "screening" || job.screening)) deny("screening_already_recorded");
      if (!["allow", "reject", "review"].includes(String(input.verdict)) || !textValid(input.reason, 1500)) deny("invalid_screening");
      if (this.executionBlock(control)) deny(this.executionBlock(control)!);
      const usage = object(input.lunaUsage);
      const measuredInput = usage?.input_tokens ?? usage?.inputTokens, measuredOutput = usage?.output_tokens ?? usage?.outputTokens;
      if (usage && [measuredInput, measuredOutput].every(value => Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) <= 1_000_000_000)) {
        const previous = job.lunaUsage;
        job.lunaUsage = { input_tokens: Number(previous?.input_tokens ?? 0) + Number(measuredInput),
          output_tokens: Number(previous?.output_tokens ?? 0) + Number(measuredOutput), requests: Number(previous?.requests ?? 0) + 1 };
      }
      if (responseId !== undefined) {
        job.answerScreeningReason = input.reason as string;
        if (input.verdict === "review") {
          pending!.response!.screening = "review"; job.heldAnswer = pending;
          job.pending = { id: crypto.randomUUID().replaceAll("-", ""), threadId: job.threadId!, kind: "review",
            text: `Review the submitted answer before continuation.\n${input.reason}`, choices: [] };
        } else if (input.verdict === "reject") pending!.response = undefined;
        else pending!.response!.screening = "allow";
        this.save(job); return { ok: true, job };
      }
      job.screening = { verdict: input.verdict as string, reason: input.reason as string };
      if (input.verdict === "reject") this.finish(job, "rejected", { summary: input.reason as string, checks: "", unresolved: "" });
      else if (input.verdict === "review") { job.status = "waiting_for_input"; job.pending = { id: crypto.randomUUID().replaceAll("-", ""), threadId: null, kind: "review", text: input.reason as string, choices: [] }; this.save(job); }
      else { job.status = "approved"; this.save(job); }
      return { ok: true, job };
    }
    if (input.op === "screening-gate" || input.op === "dispatch" || input.op === "continuation") {
      if (job.interruptReason) deny(job.interruptReason);
      const block = this.executionBlock(control); if (block) deny(block);
      if (input.op === "screening-gate") {
        if (job.status !== "screening" && job.pending?.response?.screening !== "required") deny("invalid_screening_state");
        return { ok: true, permitted: true, job };
      }
      if (input.op === "dispatch") {
        if (job.status !== "prepared" || job.commandId !== input.commandId || job.dispatchGrantedAt) deny("dispatch_already_attempted");
        job.dispatchGrantedAt = now; job.status = "dispatching"; this.save(job); return { ok: true, permitted: true, job };
      }
      if (!job.dispatchGrantedAt || input.threadId !== job.threadId || !job.pending?.response || job.pending.response.id !== input.responseId ||
          job.pending.resolved || job.pending.response.screening !== "allow") deny("continuation_not_authorized");
      // The bridge's stable command id identifies this reply; it must reconcile, never resend, after a lost acknowledgement.
      if (!validIdentifier(input.commandId)) deny("invalid_command_id");
      job.commandId = input.commandId as string; job.pending.resolved = true; job.status = "dispatching"; this.save(job);
      return { ok: true, permitted: true, job };
    }
    if (input.op === "prepare") {
      if (!job.threadId && job.pending?.kind === "review" && job.pending.response?.value === "approve") {
        job.screening = { ...job.screening!, verdict: "allow", humanReview: true }; job.pending.resolved = true; job.status = "approved";
      }
      if (job.status !== "approved" || job.screening?.verdict !== "allow" || job.threadId || job.interruptReason) deny("job_not_approved");
      const block = this.executionBlock(control); if (block) deny(block);
      if (!validIdentifier(input.threadId) || !validIdentifier(input.commandId) || !textValid(input.worktree, 500) || !textValid(input.branch, 200)) deny("invalid_execution_reference");
      job.threadId = input.threadId as string; job.worktree = input.worktree as string; job.branch = input.branch as string;
      job.commandId = input.commandId as string; job.dispatchIntentAt = now; job.status = "prepared"; this.save(job); return { ok: true, job };
    }
    if (input.op === "running") {
      if (!job.dispatchGrantedAt || input.threadId !== job.threadId || input.commandId !== job.commandId) deny("execution_reference_mismatch");
      job.status = "running"; this.save(job); return { ok: true, job };
    }
    deny("invalid_runner_operation");
  }
  cleanup(): void {
    const now = Date.now(), sql = this.storage.sql;
    // ponytail: hourly intake caps this scan at 168 live jobs; add a deadline index if intake expands.
    for (const row of sql.exec<{ data: string }>("SELECT data FROM prompt_jobs").toArray()) {
      const job: PromptJob = JSON.parse(row.data);
      if (terminal(job)) {
        if (now - job.terminalAt! >= WEEK) {
          sql.exec("DELETE FROM prompt_deliveries WHERE job_id=?", job.id); sql.exec("DELETE FROM prompt_jobs WHERE id=?", job.id);
          if (job.messageId) sql.exec("INSERT INTO prompt_deliveries(id,job_id,payload,next_at) VALUES(?,?,?,?)",
            `delete:${job.id}`, job.id, JSON.stringify({ deleteMessageId: job.messageId }), now);
        }
        continue;
      }
      if (job.expiresAt <= now) {
        if (!job.claim) this.finish(job, "expired");
        else if (job.interruptReason !== "expired") { job.interruptReason = "expired"; job.status = "expiring"; job.pending = undefined; this.save(job); }
      } else if (job.claim && job.claim.expiresAt <= now && job.status !== "needs_reconciliation") {
        job.status = "needs_reconciliation"; this.save(job);
      }
    }
    // Mutation receipts contain prompt/result snapshots; content disappears on the same seven-day clock.
    sql.exec("DELETE FROM prompt_operations WHERE created_at<=?", now - WEEK);
  }
  async arm(): Promise<void> {
    const exists = this.storage.sql.exec("SELECT id FROM prompt_jobs LIMIT 1").toArray().length || this.storage.sql.exec("SELECT id FROM prompt_deliveries LIMIT 1").toArray().length;
    if (!exists) return;
    const current = await this.storage.getAlarm();
    if (current === null || current > Date.now() + 1000) await this.storage.setAlarm(Date.now() + 1000);
  }
  async alarm(): Promise<void> {
    this.cleanup();
    for (let n = 0; n < 3; n++) {
      const delivery = this.storage.transactionSync(() => {
        const row = this.storage.sql.exec<Delivery>("SELECT * FROM prompt_deliveries WHERE next_at<=? AND lease_until<=? ORDER BY next_at LIMIT 1", Date.now(), Date.now()).toArray()[0];
        if (row) this.storage.sql.exec("UPDATE prompt_deliveries SET lease_until=? WHERE id=?", Date.now() + 30_000, row.id); return row;
      });
      if (!delivery) break;
      try {
        const payload = JSON.parse(delivery.payload), revision = payload.revision; delete payload.revision;
        const token = this.env.BACKEND_BOT_TOKEN ?? this.env.DISCORD_BACKEND_BOT_TOKEN, channel = this.env.PROMPT_CHANNEL_ID;
        if (!token || !channel || !snowflake.test(channel)) throw Error("Prompt delivery not configured");
        if (payload.deleteMessageId) {
          const response = await fetch(`https://discord.com/api/v10/channels/${channel}/messages/${payload.deleteMessageId}`, {
            method: "DELETE", redirect: "manual", signal: AbortSignal.timeout(8000), headers: { Authorization: `Bot ${token}` } });
          if (!response.ok && response.status !== 404) throw Error("Prompt retention delivery failed");
          this.storage.sql.exec("DELETE FROM prompt_deliveries WHERE id=?", delivery.id); continue;
        }
        const job = this.get(delivery.job_id);
        const reviewAnswer = payload.reviewAnswer; delete payload.reviewAnswer;
        const messagePayload = { ...payload, ...job.messageId ? {} : { nonce: job.id.slice(0, 24), enforce_nonce: true },
          attachments: typeof reviewAnswer === "string" ? [{ id: 0, filename: "proposed-answer.txt", description: "The complete answer awaiting human review" }] : [], allowed_mentions: { parse: [] } };
        let body: string | FormData = JSON.stringify(messagePayload);
        if (typeof reviewAnswer === "string") {
          body = new FormData(); body.set("payload_json", JSON.stringify(messagePayload));
          body.set("files[0]", new Blob([reviewAnswer], { type: "text/plain;charset=utf-8" }), "proposed-answer.txt");
        }
        const response = await fetch(`https://discord.com/api/v10/channels/${channel}/messages${job.messageId ? `/${job.messageId}` : ""}`, {
          method: job.messageId ? "PATCH" : "POST", redirect: "manual", signal: AbortSignal.timeout(8000),
          headers: { Authorization: `Bot ${token}`, ...typeof body === "string" ? { "Content-Type": "application/json" } : {} }, body });
        if (response.status === 404 && job.messageId) this.storage.transactionSync(() => {
          const row = this.storage.sql.exec<{ data: string }>("SELECT data FROM prompt_jobs WHERE id=?", job.id).toArray()[0];
          if (row) {
            const current: PromptJob = JSON.parse(row.data);
            if (current.messageId === job.messageId) { delete current.messageId; this.save(current, false); }
          }
        });
        if (!response.ok) throw Error("Prompt delivery failed");
        const bytes = await boundedBytes(response, 32_768);
        const message = JSON.parse(new TextDecoder().decode(bytes));
        if (!snowflake.test(String(message.id))) throw Error("Prompt delivery receipt missing");
        this.storage.transactionSync(() => {
          const currentRow = this.storage.sql.exec<{ data: string }>("SELECT data FROM prompt_jobs WHERE id=?", job.id).toArray()[0];
          if (!currentRow) {
            // Retention may have purged the job while Discord accepted this delivery.
            this.storage.sql.exec("INSERT INTO prompt_deliveries(id,job_id,payload,next_at) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,next_at=excluded.next_at",
              `delete:${job.id}:${message.id}`, job.id, JSON.stringify({ deleteMessageId: message.id }), Date.now());
            this.storage.sql.exec("DELETE FROM prompt_deliveries WHERE id=?", delivery.id); return;
          }
          const current: PromptJob = JSON.parse(currentRow.data); current.messageId = message.id; this.save(current, false);
          const row = this.storage.sql.exec<{ payload: string }>("SELECT payload FROM prompt_deliveries WHERE id=?", delivery.id).toArray()[0];
          if (row && JSON.parse(row.payload).revision === revision) this.storage.sql.exec("DELETE FROM prompt_deliveries WHERE id=?", delivery.id);
          else this.storage.sql.exec("UPDATE prompt_deliveries SET lease_until=0 WHERE id=?", delivery.id);
        });
      } catch {
        this.storage.sql.exec("UPDATE prompt_deliveries SET attempts=attempts+1,next_at=?,lease_until=0 WHERE id=?", Date.now() + Math.min(300_000, 1000 * 2 ** Math.min(delivery.attempts + 1, 9)), delivery.id);
      }
    }
    const next = this.storage.sql.exec<{ due: number | null }>("SELECT MIN(MAX(next_at,lease_until)) AS due FROM prompt_deliveries").one().due;
    if (next !== null || this.storage.sql.exec("SELECT id FROM prompt_jobs LIMIT 1").toArray().length) await this.storage.setAlarm(Math.max(Date.now() + 1000, Math.min(next ?? Infinity, Date.now() + 60_000)));
  }
}

function validatePending(raw: unknown, threadId: string): Pending {
  const pending = object(raw);
  if (!pending || !jobId.test(String(pending.id)) || !["question", "approval", "local"].includes(String(pending.kind)) ||
      !textValid(pending.text, 1400) || !Array.isArray(pending.choices) || pending.choices.length > 20 || pending.choices.some(choice => !textValid(choice, 80))) deny("invalid_pending_request");
  return { id: pending.id as string, threadId, kind: pending.kind as Pending["kind"], text: pending.text as string, choices: pending.choices as string[] };
}
function notification(job: PromptJob): Record<string, unknown> {
  const pending = job.pending;
  const livePending = pending && !pending.resolved && !pending.response && !terminal(job) && job.expiresAt > Date.now();
  const lines = [`Sky-Myce job \`${job.id}\` · ${job.status}`, `Submitter: <@${job.submitter}>`,
    ...!livePending && job.threadId ? [`T3: \`${job.threadId}\``] : [], ...!livePending && job.branch ? [`Branch: \`${job.branch}\``] : []];
  if (job.interruptReason) lines.push(`Blocking reason: ${job.interruptReason}`);
  const components: unknown[] = [];
  if (livePending) {
    lines.push(pending.text);
    if (job.heldAnswer) lines.push("The complete proposed answer is attached as proposed-answer.txt. Approval applies to that exact answer.");
    const button = (action: string, label: string, style = 2) => ({ type: 2, style, label, custom_id: `prompt:${action}:${job.id}:${pending.id}` });
    if (pending.kind === "question") {
      if (pending.choices.length) components.push({ type: 1, components: [{ type: 3, custom_id: `prompt:choice:${job.id}:${pending.id}`, placeholder: "Choose an answer", options: pending.choices.map((label, i) => ({ label, value: String(i) })) }] });
      components.push({ type: 1, components: [button("answer", "Answer with text"), button("cancel", "Cancel job", 4)] });
    } else if (pending.kind !== "local") components.push({ type: 1, components: [button("approve", "Approve once", 3), button("decline", "Decline", 4), button("cancel", "Cancel job", 4)] });
  }
  if (job.answerScreeningReason && !livePending) lines.push(`Answer screening: ${job.answerScreeningReason}`);
  if (job.result) lines.push(...resultLines(job.result));
  const complete = lines.join("\n");
  return { content: complete.length > 1900 ? `${complete.slice(0, 1750)}\nFull result is recorded; inspect the T3 thread.` : complete, components,
    ...livePending && job.heldAnswer?.response ? { reviewAnswer: job.heldAnswer.response.value } : {} };
}

async function boundedBytes(response: Response | Request, maximum: number): Promise<Uint8Array> {
  if (Number(response.headers.get("Content-Length") ?? 0) > maximum) deny("input_too_large");
  const reader = response.body?.getReader(); if (!reader) deny("empty_input");
  const chunks: Uint8Array[] = []; let size = 0, timedOut = false;
  const timer = setTimeout(() => { timedOut = true; void reader.cancel().catch(() => {}); }, 8000);
  try {
    while (true) { const { done, value } = await reader.read(); if (done) break;
      size += value.byteLength; if (size > maximum) deny("input_too_large"); chunks.push(value); }
    if (timedOut) deny("input_timeout");
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; } return bytes;
  } finally { clearTimeout(timer); await reader.cancel().catch(() => {}); }
}

export async function assembledPrompt(data: Record<string, unknown>, env: PromptConfig): Promise<{ prompt: string; source: Record<string, unknown> }> {
  const command = object(data.data)!; let inline: unknown = "", attachments: unknown[] = [];
  if (command.name === "Run prompt") {
    if (command.type !== 3 || !snowflake.test(String(command.target_id))) deny("invalid_prompt_command");
    const message = object(object(object(command.resolved)?.messages)?.[command.target_id as string]);
    if (!message || message.id !== command.target_id || message.channel_id !== data.channel_id || !Array.isArray(message.attachments)) deny("invalid_prompt_command");
    inline = message.content ?? ""; attachments = message.attachments;
  } else {
    if (command.type !== undefined && command.type !== 1 || !Array.isArray(command.options) || command.options.length > 2) deny("invalid_prompt_command");
    const seen = new Set<string>(); let attachmentId: string | undefined;
    for (const raw of command.options) {
      const option = object(raw);
      if (!option || !["text", "file"].includes(String(option.name)) || seen.has(String(option.name))) deny("invalid_prompt_command");
      seen.add(option.name as string);
      if (option.name === "text") { if (option.type !== 3 || typeof option.value !== "string") deny("invalid_prompt_command"); inline = option.value; }
      else { if (option.type !== 11 || !snowflake.test(String(option.value))) deny("invalid_prompt_command"); attachmentId = option.value as string; }
    }
    const resolved = object(object(command.resolved)?.attachments);
    if (resolved && Object.keys(resolved).length > 1) deny("multiple_attachments");
    if (attachmentId) { const attachment = object(resolved?.[attachmentId]); if (!attachment || attachment.id !== attachmentId) deny("invalid_attachment"); attachments = [attachment]; }
    else if (resolved && Object.keys(resolved).length) deny("invalid_attachment");
  }
  if (typeof inline !== "string" || (inline && !textValid(inline, 120_000))) deny("invalid_prompt");
  if (attachments.length > 1) deny("multiple_attachments");
  let prompt = inline;
  let filename: string | undefined;
  if (attachments.length) {
    const attachment = object(attachments[0]);
    if (!attachment || !snowflake.test(String(attachment.id)) || typeof attachment.filename !== "string" ||
      !/^[^/\\\x00-\x1f]{1,100}\.txt$/i.test(attachment.filename) || !Number.isSafeInteger(attachment.size) || (attachment.size as number) < 1 || (attachment.size as number) > FILE_LIMIT || typeof attachment.url !== "string") deny("invalid_attachment");
    let url: URL;
    try { url = new URL(attachment.url); } catch { return deny("invalid_attachment_url"); }
    const path = url.pathname.match(/^\/(attachments|ephemeral-attachments)\/(\d{17,20})\/(\d{17,20})\/([^/]+)$/);
    if (url.protocol !== "https:" || !["cdn.discordapp.com", "media.discordapp.net"].includes(url.hostname) || url.port || url.username || url.password || url.hash ||
      !path || path[3] !== attachment.id || path[2] !== (path[1] === "attachments" ? data.channel_id : data.application_id) || decodeURIComponent(path[4]) !== attachment.filename) deny("invalid_attachment_url");
    const response = await fetch(url.href, { redirect: "manual", signal: AbortSignal.timeout(8000) });
    if (!response.ok) deny("attachment_download_failed");
    const bytes = await boundedBytes(response, FILE_LIMIT);
    if (bytes.length !== attachment.size) deny("invalid_attachment");
    let extracted: string;
    try { extracted = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes); } catch { return deny("invalid_utf8"); }
    if (!textValid(extracted, 120_000)) deny("invalid_attachment_text");
    filename = attachment.filename;
    prompt = inline ? `${inline}\n\n--- Attachment: ${filename} ---\n${extracted}` : extracted;
  }
  if (!textValid(prompt, 120_000) || encoder.encode(prompt).length > inputLimit(env)) deny("invalid_prompt");
  return { prompt, source: { channelId: data.channel_id, interactionId: data.id, ...command.name === "Run prompt" ? { messageId: command.target_id } : {}, ...filename ? { filename } : {} } };
}

export async function handlePromptInteraction(data: Record<string, unknown>, env: PromptConfig, ctx: ExecutionContext, backend: boolean): Promise<Response | null> {
  const command = object(data.data), name = String(command?.name ?? ""), custom = String(command?.custom_id ?? "");
  if (!commands.has(name) && !custom.startsWith("prompt:")) return null;
  const user = object(object(data.member)?.user);
  const actor: PromptActor = { id: String(user?.id ?? ""), guildId: String(data.guild_id ?? ""), interactionId: String(data.id ?? "") };
  if (!backend || !promptAuthorized(actor, env)) return ephemeral("Only the two permitted developers can manage prompt jobs in the configured guild.");
  if (!env.ACCOUNT_STORE) return ephemeral("Prompt storage is unavailable.");
  const store = env.ACCOUNT_STORE.getByName("global");
  if (custom) {
    const parts = custom.split(":"), [, action, id, pendingId] = parts;
    if (parts.length !== 4 || !jobId.test(id ?? "") || !jobId.test(pendingId ?? "") || !["answer", "choice", "approve", "decline", "cancel", "submit"].includes(action ?? "")) return ephemeral("Invalid prompt control.");
    if (data.type === 3 && action === "answer") {
      // Modal responses must be immediate; do only the exact durable pending-request check here.
      const current = await store.promptPending(actor, id, pendingId);
      if (!current.ok || current.pending?.kind !== "question") return ephemeral("This question is already resolved or expired.");
      return Response.json({ type: 9, data: { custom_id: `prompt:submit:${id}:${pendingId}`, title: "Answer agent question", components: [{ type: 1, components: [{ type: 4, custom_id: "answer", label: "Your answer", style: 2, required: true, max_length: 4000 }] }] } });
    }
    return deferInteraction(data, ctx, async () => {
      let value: unknown, operation = action;
      if (data.type === 3 && action === "choice") {
        const pending = await store.promptPending(actor, id, pendingId);
        const choices = object(pending.pending)?.choices;
        if (!pending.ok || !Array.isArray(command?.values) || command.values.length !== 1 || !/^\d{1,2}$/.test(String(command.values[0])) || !Array.isArray(choices)) return "This question is already resolved or expired.";
        value = choices[Number(command.values[0])]; operation = "answer";
      } else if (data.type === 5 && action === "submit") {
        const rows = command?.components;
        if (!Array.isArray(rows) || rows.length !== 1) return "Invalid answer.";
        const fields = object(rows[0])?.components;
        if (!Array.isArray(fields) || fields.length !== 1 || object(fields[0])?.custom_id !== "answer") return "Invalid answer.";
        value = object(fields[0])?.value; operation = "answer";
      } else if (data.type !== 3 || !["approve", "decline", "cancel"].includes(action)) return "Invalid prompt control.";
      const result = await store.promptCommand(actor, operation, { jobId: id, pendingId, value });
      return resultText(result);
    });
  }
  if (data.type !== 2 || !commands.has(name)) return ephemeral("Invalid prompt command.");
  return deferInteraction(data, ctx, async () => {
    if (name === "prompt" || name === "Run prompt") {
      if (env.PROMPT_SUBMISSIONS_ENABLED !== "true") return "Prompt submissions are disabled until runner verification is complete.";
      try {
        const eligibility = await store.promptIntake(actor);
        if (!eligibility.ok || eligibility.duplicate) return resultText(eligibility);
        const assembled = await assembledPrompt(data, env);
        return resultText(await store.promptSubmit(actor, assembled.prompt, assembled.source));
      } catch (error) { return resultText(safeFailure(error)); }
    }
    const options = command?.options ?? [];
    if (!Array.isArray(options) || options.length > 1) return "Invalid prompt command.";
    if (["prompt-status", "prompt-cancel"].includes(name)) {
      const option = object(options[0]);
      if (options.length !== 1 || option?.name !== "job" || option.type !== 3 || !jobId.test(String(option.value))) return "Choose a valid job ID.";
      return name === "prompt-cancel" ? resultText(await store.promptCommand(actor, "cancel", { jobId: option.value })) : statusText(await store.promptInspect(actor, option.value as string));
    }
    if (options.length) return "Invalid prompt command.";
    if (name === "prompt-pause" || name === "prompt-resume") return resultText(await store.promptCommand(actor, name === "prompt-pause" ? "pause" : "resume"));
    const result = await store.promptInspect(actor);
    if (!result.ok) return resultText(result);
    if (name === "prompt-runner") return `Runner: ${result.state}\nLast heartbeat: ${timestamp(result.heartbeatAt)}\nActive job: ${result.activeJob ?? "none"}\nQueue: ${result.queueLength}\nBlocking reason: ${result.blockingReason ?? "none"}`;
    const quota = object(result.quota), windows = Array.isArray(quota?.windows) ? quota.windows.map(raw => { const window = object(raw)!; return `${window.id}: ${100 - Number(window.usedPercent)}% remaining; resets ${timestamp(window.resetsAt)}`; }) : ["Sol quota is unknown."];
    return `${windows.join("\n")}\nLast quota refresh: ${timestamp(quota?.refreshedAt)}\nShared cooldown: ${timestamp(result.cooldownUntil)}\nExecution permitted: ${result.executionPermitted ? "yes" : "no"}\nBlocking reason: ${result.blockingReason ?? "none"}\nLuna API usage (separate, retained jobs): ${result.lunaUsage?.inputTokens ?? 0} input / ${result.lunaUsage?.outputTokens ?? 0} output tokens across ${result.lunaUsage?.requests ?? 0} measured requests.`;
  });
}
const timestamp = (value: unknown) => typeof value === "number" && value > 0 ? `<t:${Math.floor(value / 1000)}:f>` : "unknown";
const excerpt = (value: string, maximum: number) => value.length > maximum ? `${value.slice(0, maximum)}… (full text recorded)` : value;
function resultLines(result: NonNullable<PromptJob["result"]>): string[] {
  return [`Checks: ${excerpt(result.checks || "None reported", 350)}`, `Unresolved: ${excerpt(result.unresolved || "None reported", 350)}`,
    `Changes: ${excerpt(result.summary, 650)}`, ...result.threadUrl ? [`T3: ${result.threadUrl}`] : []];
}
function resultText(result: Result): string {
  if (!result.ok) return `Prompt request refused: ${result.error ?? "unavailable"}.${result.cooldownUntil ? ` Shared cooldown ends ${timestamp(result.cooldownUntil)}.` : ""}`;
  if (result.jobId) return `Prompt accepted as job \`${result.jobId}\`. Shared cooldown ends ${timestamp(result.cooldownUntil)}.`;
  if (result.paused !== undefined) return `Queued execution ${result.paused ? "paused" : "resumed"}.${result.blockingReason ? ` Blocking reason: ${result.blockingReason}.` : ""}`;
  return `Prompt job: ${result.status ?? "updated"}.${result.needsScreening ? " Your answer awaits Luna screening before continuation." : ""}`;
}
function statusText(result: Result): string {
  if (!result.ok) return resultText(result);
  const job = result.job as PromptJob;
  return [`Job \`${job.id}\`: ${job.status}`, `Blocking reason: ${result.blockingReason ?? "none"}`, ...job.threadId ? [`T3: ${job.threadId}`] : [],
    ...job.branch ? [`Branch: ${job.branch}`] : [], ...job.result ? resultLines(job.result) : [],
    ...job.screening ? [`Screening: ${job.screening.verdict} · ${excerpt(job.screening.reason, 250)}`] : []].join("\n");
}

export async function handlePromptRunner(request: Request, env: PromptConfig): Promise<Response> {
  if (request.method !== "POST" || new URL(request.url).protocol !== "https:") return new Response("HTTPS POST required", { status: 405 });
  const time = request.headers.get("X-SkyMyce-Timestamp") ?? "", nonce = request.headers.get("X-SkyMyce-Nonce") ?? "", signature = request.headers.get("X-SkyMyce-Signature") ?? "";
  if (!env.ACCOUNT_STORE || !env.PROMPT_BRIDGE_SECRET || env.PROMPT_BRIDGE_SECRET.length < 32) return new Response("Prompt runner unavailable", { status: 503 });
  if (!/^\d{13}$/.test(time) || Math.abs(Date.now() - Number(time)) > 60_000 || !jobId.test(nonce) || !/^[a-f0-9]{64}$/.test(signature)) return new Response("Invalid authentication", { status: 401 });
  try {
    const bytes = await boundedBytes(request, 98_304), body = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
    const key = await crypto.subtle.importKey("raw", encoder.encode(env.PROMPT_BRIDGE_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const signed = encoder.encode(`sky-myce-prompt-runner\nPOST\n/prompt/runner\n${time}\n${nonce}\n${body}`);
    if (!await crypto.subtle.verify("HMAC", key, Uint8Array.from(signature.match(/../g)!, value => parseInt(value, 16)), signed)) return new Response("Invalid authentication", { status: 401 });
    const input = object(JSON.parse(body)); if (!input) return Response.json({ ok: false, error: "invalid_runner_request" }, { status: 400 });
    const result = await env.ACCOUNT_STORE.getByName("global").promptRunner(input, nonce);
    return Response.json(result, { status: result.ok ? 200 : result.error === "runner_replay" ? 409 : 400, headers: { "Cache-Control": "no-store" } });
  } catch (error) { return Response.json(safeFailure(error), { status: 400 }); }
}
