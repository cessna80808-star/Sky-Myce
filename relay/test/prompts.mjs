import assert from "node:assert/strict";
import { createHmac, randomUUID } from "node:crypto";
import { readFile, mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

export async function checkPrompts(script) {
  const persist = resolve(".test-build", `prompt-state-${randomUUID()}`); await mkdir(persist, { recursive: true });
  const bindings = { ROOMS: "friends", DISCORD_GUILD_ID: "11111111111111111", PROMPT_SUBMISSIONS_ENABLED: "true",
    PROMPT_EXECUTION_VERIFIED: "true", PROMPT_PROVIDER_ACCOUNT_ID: "test-account", PROMPT_MAX_INPUT_BYTES: "73728",
    PROMPT_BASELINE_REVISION: "69542a8af0fda55ef70244110bde321bf800f022", PROMPT_BRIDGE_SECRET: "test-only-separate-job-credential-32-bytes",
    PROMPT_CHANNEL_ID: "22222222222222222", DISCORD_BACKEND_BOT_TOKEN: "test-only-backend-bot-token" };
  let notificationAttempts = 0, deliverNotification = () => new Response("Delivery unavailable", { status: 503 });
  const options = convertV4MiniflareOptions({ name: "prompt-check", modules: true, script, compatibilityDate: "2026-09-27", bindings,
    durableObjects: { ACCOUNT_STORE: { className: "AccountStore", useSQLite: true }, RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
    outboundService: request => { assert.equal(new URL(request.url).hostname, "discord.com", "Backend never calls models"); notificationAttempts++; return deliverNotification(request); } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112029", simple: { limit: 1000, period: 60 } };
  options.unsafeInspectDurableObjects = true;
  options.resourcePersistencePath = persist;
  let mf = new Miniflare(options);
  try {
    let store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    let sql = await mf.unsafeGetDurableObjectStorage("prompt-check", "AccountStore", { name: "global" });
    const restart = async () => {
      await mf.dispose(); mf = new Miniflare(options);
      store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
      sql = await mf.unsafeGetDurableObjectStorage("prompt-check", "AccountStore", { name: "global" });
    };
    let interaction = 90000000000000000n;
    const actor = (id = "1250627753191931914", changes = {}) => ({ id, guildId: bindings.DISCORD_GUILD_ID, interactionId: String(interaction++), ...changes });
    const nonce = () => randomUUID().replaceAll("-", "");
    const run = (op, fields = {}, requestId = nonce()) => store.promptRunner({ op, runnerId: "pc", requestId, ...fields }, nonce());
    const quota = (usedPercent = 80, changes = {}) => ({ providerAccountId: bindings.PROMPT_PROVIDER_ACCOUNT_ID, refreshedAt: Date.now(), windows: [
      { id: "codex:primary", usedPercent, resetsAt: Date.now() + 3_600_000 }, { id: "codex:secondary", usedPercent: 10, resetsAt: Date.now() + 86400_000 }], ...changes });
    const capabilities = { model: "gpt-6.1-sol", effort: "ultra", restricted: true, sharedAdmissionGate: true, safeRecovery: true };
    const heartbeat = q => run("heartbeat", { availability: "ready", capabilities, ...q ? { quota: q } : {} });
    const resetCooldown = () => sql.exec("UPDATE prompt_control SET data=json_set(data,'$.cooldownUntil',0) WHERE id=1");
    const fields = job => ({ jobId: job.id, claimId: job.claim.id });
    for (const rejected of [actor("33333333333333333", { permissions: "8" }), actor("33333333333333333", { roles: ["22222222222222222"] }),
      actor(undefined, { guildId: "99999999999999999" })]) {
      assert.equal((await store.promptSubmit(rejected, "Harmless prompt", {})).error, "prompt_forbidden");
      assert.equal((await store.promptCommand(rejected, "resume")).error, "prompt_forbidden");
      assert.equal((await store.promptInspect(rejected)).error, "prompt_forbidden");
    }
    assert.equal((await store.promptSubmit(actor(), "\0binary", {})).error, "invalid_prompt");
    assert.equal((await store.promptSubmit(actor(), "x".repeat(73729), {})).error, "invalid_prompt");
    const submitting = actor(), accepted = await store.promptSubmit(submitting, "Fix a harmless comment.\nKeep complete text intact.", { filename: "message.txt" });
    assert.equal(accepted.ok, true); assert.match(accepted.jobId, /^[a-f0-9]{32}$/);
    assert.equal((await store.getAccountByDiscordId(submitting.id)), null, "Prompt access does not grant or require license/account access");
    assert.equal((await store.promptSubmit(submitting, "different duplicate", {})).jobId, accepted.jobId);
    assert.equal((await store.promptSubmit(actor("978376257534328883"), "Other prompt", {})).error, "submission_cooldown");
    await restart();
    assert.equal((await store.promptSubmit(actor(), "After restart", {})).error, "submission_cooldown");
    assert.equal((await run("claim")).error, "paused");
    assert.equal((await store.promptCommand(actor(), "resume")).paused, false);
    assert.equal((await heartbeat()).ok, true);
    assert.equal((await run("claim")).error, "quota_unknown");
    assert.equal((await heartbeat(quota(80, { refreshedAt: Date.now() - 120001 }))).ok, true);
    assert.equal((await run("claim")).error, "quota_stale");
    await heartbeat(quota(80.01)); await resetCooldown();
    assert.equal((await store.promptSubmit(actor(), "Low quota", {})).error, "low_quota");
    assert.equal((await run("claim")).error, "low_quota");
    await heartbeat(quota());
    const claimed = await run("claim"); assert.equal(claimed.ok, true); const job = claimed.job;
    assert.equal(job.prompt, "Fix a harmless comment.\nKeep complete text intact.");
    assert.equal((await run("claim")).error, "project_busy");
    assert.equal((await run("screening-gate", fields(job))).permitted, true);
    assert.equal((await run("prepare", { ...fields(job), threadId: "too-early", worktree: "D:/safe", branch: "prompt/test", commandId: "test" })).error, "job_not_approved");
    assert.equal((await run("screening", { ...fields(job), verdict: "maybe", reason: "bad" })).error, "invalid_screening");
    assert.equal((await run("screening", { ...fields(job), verdict: "allow", reason: "Harmless project change", lunaUsage: { input_tokens: 10, output_tokens: 5 } })).job.status, "approved");
    assert.deepEqual((await store.promptInspect(actor())).lunaUsage, { inputTokens: 10, outputTokens: 5, requests: 1 });
    assert.equal((await run("prepare", { ...fields(job), threadId: "thread-test", worktree: "D:/isolated/prompt-test", branch: "prompt/test", commandId: "turn-test" })).job.status, "prepared");
    await heartbeat(quota(80, { windows: [{ id: "weekly", usedPercent: 80.01, resetsAt: Date.now() + 3600_000 }] }));
    assert.equal((await run("dispatch", { ...fields(job), commandId: "turn-test" })).error, "low_quota");
    await heartbeat(quota());
    const commandReceipt = nonce();
    const dispatch = await run("dispatch", { ...fields(job), commandId: "turn-test" }, commandReceipt);
    assert.equal(dispatch.permitted, true); assert.ok(dispatch.job.dispatchIntentAt); assert.equal(dispatch.job.threadId, "thread-test");
    assert.equal((await run("dispatch", { ...fields(job), commandId: "turn-test" }, commandReceipt)).replayed, true);
    assert.equal((await run("dispatch", { ...fields(job), commandId: "turn-test" })).error, "dispatch_already_attempted");
    assert.equal((await run("running", { ...fields(job), threadId: "thread-test", commandId: "turn-test" })).ok, true);
    const pendingId = nonce();
    assert.equal((await run("pending", { ...fields(job), threadId: "thread-other", pending: { id: pendingId, kind: "approval", text: "npm test", choices: [] } })).error, "pending_request_conflict");
    await run("pending", { ...fields(job), threadId: "thread-test", pending: { id: pendingId, kind: "question", text: "Which test?", choices: ["Unit", "Build"] } });
    assert.equal((await store.promptInspect(actor())).state, "waiting for input");
    assert.equal((await store.promptCommand(actor(), "answer", { jobId: job.id, pendingId: nonce(), value: "Unit" })).error, "stale_request");
    const replies = await Promise.all([actor(), actor("978376257534328883")].map(who => store.promptCommand(who, "answer", { jobId: job.id, pendingId, value: "Unit" })));
    assert.equal(replies.filter(reply => reply.ok).length, 1, "The first valid response wins atomically");
    const response = (await run("response", fields(job))).response;
    assert.equal((await run("continuation", { ...fields(job), threadId: "thread-test", responseId: response.id, commandId: "reply-test" })).error, "continuation_not_authorized");
    await run("screening", { ...fields(job), responseId: response.id, verdict: "allow", reason: "Harmless answer" });
    assert.equal((await run("continuation", { ...fields(job), threadId: "thread-test", responseId: response.id, commandId: "reply-test" })).permitted, true);
    assert.equal((await store.promptPending(actor(), job.id, pendingId)).error, "stale_request");
    const nextPending = nonce();
    await run("pending", { ...fields(job), threadId: "thread-test", pending: { id: nextPending, kind: "approval", text: "Run npm test in the worktree", choices: [] } });
    await run("resolved", { ...fields(job), threadId: "thread-test", pendingId: nextPending });
    assert.equal((await store.promptCommand(actor(), "approve", { jobId: job.id, pendingId: nextPending })).error, "stale_request");
    assert.equal((await run("pending", { ...fields(job), threadId: "thread-test", pending: { id: nextPending, kind: "question", text: "A late replay", choices: [] } })).error, "stale_request");
    const reviewQuestion = nonce();
    await run("pending", { ...fields(job), threadId: "thread-test", pending: { id: reviewQuestion, kind: "question", text: "What change next?", choices: [] } });
    await store.promptCommand(actor(), "answer", { jobId: job.id, pendingId: reviewQuestion, value: "Keep the requested comment change" });
    let reviewResponse = (await run("response", fields(job))).response;
    const reviewed = await run("screening", { ...fields(job), responseId: reviewResponse.id, verdict: "review", reason: "Please review this answer" });
    assert.equal(reviewed.job.pending.kind, "review"); assert.equal(reviewed.job.heldAnswer.response.value, "Keep the requested comment change");
    const approvalId = reviewed.job.pending.id;
    assert.equal((await store.promptCommand(actor(), "approve", { jobId: job.id, pendingId: approvalId })).status, "answer_review_approved");
    assert.equal((await run("response", fields(job))).response.value, "Keep the requested comment change");
    assert.equal((await run("continuation", { ...fields(job), threadId: "thread-test", responseId: reviewResponse.id, commandId: "review-reply" })).permitted, true);
    const nativeId = nonce(), nativeReceipt = nonce();
    const native = { ...fields(job), threadId: "thread-test", pendingId: nativeId, kind: "question", value: '{"answers":{"test":["Unit"]}}', verdict: "allow", nativeDisplay: { text: "Which test?", choices: ["Unit"] } };
    assert.equal((await run("native-response", native, nativeReceipt)).permitted, true);
    assert.equal((await run("native-response", native, nativeReceipt)).replayed, true);
    assert.equal((await store.promptCommand(actor(), "answer", { jobId: job.id, pendingId: nativeId, value: "Build" })).error, "stale_request");
    assert.equal((await run("native-response", native)).error, "stale_request");
    assert.equal((await store.promptCommand(actor(), "cancel", { jobId: job.id })).status, "cancelling");
    assert.equal((await run("claim")).error, "project_busy", "Cancellation retains the project slot until execution is confirmed stopped");
    assert.equal((await run("finish", { ...fields(job), status: "cancelled", stopped: false, result: { summary: "Cancelled", checks: "", unresolved: "", preserved: true } })).error, "stop_confirmation_required");
    assert.equal((await run("finish", { ...fields(job), status: "cancelled", stopped: true, result: { summary: "Cancelled", checks: "", unresolved: "" } })).error, "invalid_result");
    assert.equal((await run("finish", { ...fields(job), status: "cancelled", stopped: true, result: { summary: "Cancelled; branch preserved", checks: "Not run", unresolved: "", preserved: true } })).job.status, "cancelled");
    assert.equal((await store.promptInspect(actor())).activeJob, null);
    assert.ok((await sql.exec("SELECT * FROM prompt_deliveries")).length, "Execution result is stored independently of Discord delivery");
    await new Promise(resolve => setTimeout(resolve, 1200));
    assert.ok(notificationAttempts > 0); assert.equal((await store.promptInspect(actor(), job.id)).job.status, "cancelled", "Delivery failure does not replay execution");
    const waitFor = async predicate => {
      const deadline = Date.now() + 6000;
      while (!await predicate()) { assert.ok(Date.now() < deadline, "Durable notification retry completes"); await new Promise(resolve => setTimeout(resolve, 25)); }
    };
    const deletedMessage = "66666666666666666", replacementMessage = "77777777777777777";
    let deletedPatch = 0, replacementPost = 0;
    deliverNotification = request => {
      if (request.method === "PATCH") { deletedPatch++; return new Response("Unknown message", { status: 404 }); }
      assert.equal(request.method, "POST"); replacementPost++; return Response.json({ id: replacementMessage });
    };
    await sql.exec("UPDATE prompt_jobs SET data=json_set(data,'$.messageId',?) WHERE id=?", deletedMessage, job.id);
    await sql.exec("UPDATE prompt_deliveries SET next_at=0,lease_until=0,attempts=0 WHERE job_id=?", job.id);
    await heartbeat(quota());
    await waitFor(async () => (await store.promptInspect(actor(), job.id)).job.messageId === replacementMessage);
    assert.equal(deletedPatch, 1); assert.equal(replacementPost, 1, "A deleted Discord message is replaced without replaying execution");
    const finished = (await store.promptInspect(actor(), job.id)).job;
    const lateMessage = "88888888888888888"; let lateDeleted = false, releaseLateMessage;
    deliverNotification = request => {
      if (request.method === "POST") {
        return new Promise(resolve => { releaseLateMessage = () => resolve(Response.json({ id: lateMessage })); });
      }
      assert.equal(request.method, "DELETE"); assert.equal(new URL(request.url).pathname.split("/").at(-1), lateMessage);
      lateDeleted = true; return new Response(null, { status: 204 });
    };
    await sql.exec("UPDATE prompt_jobs SET data=json_remove(data,'$.messageId') WHERE id=?", job.id);
    await sql.exec("INSERT INTO prompt_deliveries(id,job_id,payload,next_at) VALUES(?,?,?,0)", job.id, job.id, JSON.stringify({ revision: finished.revision, content: "Final result", components: [] }));
    await heartbeat(quota());
    await waitFor(() => releaseLateMessage);
    // Simulate the retention transaction completing while the outbound POST remains in flight.
    await sql.exec("DELETE FROM prompt_deliveries WHERE job_id=?", job.id);
    await sql.exec("DELETE FROM prompt_jobs WHERE id=?", job.id);
    releaseLateMessage();
    await waitFor(() => lateDeleted);
    deliverNotification = () => new Response("Delivery unavailable", { status: 503 });
    assert.equal((await store.promptInspect(actor(), job.id)).error, "job_not_found", "An in-flight delivery cannot resurrect purged job content");
    assert.equal((await store.promptSubmit(submitting, "Old duplicate after purge", {})).jobId, job.id, "Content purge retains deduplication without executing old content");
    assert.equal((await store.promptInspect(actor(), job.id)).error, "job_not_found");
    await sql.exec("UPDATE prompt_operations SET created_at=? WHERE id=?", Date.now() - 7 * 86400_000 - 1, `pc:${commandReceipt}`);
    assert.equal((await run("dispatch", { ...fields(job), commandId: "turn-test" }, commandReceipt)).error, "runner_operation_expired", "Purged receipts retain deduplication without returning or re-executing old prompt content");
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM prompt_operations WHERE id=?", `pc:${commandReceipt}`))[0].n, 0);
    assert.equal(finished.result.preserved, true);
    await resetCooldown();
    const race = await Promise.all([actor(), actor("978376257534328883")].map(who => store.promptSubmit(who, "FIFO next", {})));
    assert.equal(race.filter(result => result.ok).length, 1, "The hourly cooldown is shared and atomic");
    const queuedId = race.find(result => result.ok).jobId;
    const nextJob = (await run("claim")).job;
    assert.equal((await run("uncertain", { ...fields(nextJob), reason: "Acknowledgement was lost" })).job.status, "needs_reconciliation");
    assert.equal((await run("screening-gate", fields(nextJob))).error, "needs_reconciliation");
    await sql.exec("UPDATE prompt_jobs SET data=json_set(data,'$.claim.expiresAt',?) WHERE id=?", Date.now() - 1, queuedId);
    assert.equal((await run("claim")).reconciliationRequired, true);
    assert.equal((await store.promptInspect(actor(), queuedId)).job.status, "needs_reconciliation");
    assert.equal((await run("screening-gate", fields(nextJob))).error, "needs_reconciliation");
    await restart();
    assert.equal((await run("claim")).error, "project_busy", "Expired claims never create a replacement execution");
    const time = String(Date.now()), n = nonce(), body = JSON.stringify({ op: "heartbeat", runnerId: "pc", requestId: nonce(), availability: "ready", capabilities });
    const signature = createHmac("sha256", bindings.PROMPT_BRIDGE_SECRET).update(`sky-myce-prompt-runner\nPOST\n/prompt/runner\n${time}\n${n}\n${body}`).digest("hex");
    const request = () => mf.dispatchFetch("https://example.test/prompt/runner", { method: "POST", headers: {
      "Content-Type": "application/json", "X-SkyMyce-Timestamp": time, "X-SkyMyce-Nonce": n, "X-SkyMyce-Signature": signature }, body });
    assert.equal((await request()).status, 200); assert.equal((await request()).status, 409);
    assert.equal((await mf.dispatchFetch("https://example.test/prompt/runner", { method: "POST", body: "{}" })).status, 401);
    assert.equal((await mf.dispatchFetch("https://example.test/auth/admin", { method: "POST", headers: { Authorization: `Bearer ${bindings.PROMPT_BRIDGE_SECRET}` }, body: "{}" })).status, 404);
    await run("finish", { ...fields(nextJob), status: "failed", stopped: true, result: { summary: "Stopped before execution", checks: "Not run", unresolved: "" } });
    await resetCooldown();
    const expiry = await store.promptSubmit(actor(), "Expire queued work", {});
    await sql.exec("UPDATE prompt_jobs SET data=json_set(data,'$.expiresAt',?) WHERE id=?", Date.now() - 1, expiry.jobId);
    assert.equal((await store.promptInspect(actor(), expiry.jobId)).job.status, "expired");
    assert.equal((await store.promptCommand(actor(), "cancel", { jobId: expiry.jobId })).error, "job_expired_or_finished");
    const disabledOptions = convertV4MiniflareOptions({ name: "prompt-disabled", modules: true, script, compatibilityDate: "2026-09-27", bindings: { ROOMS: "friends", DISCORD_GUILD_ID: bindings.DISCORD_GUILD_ID },
      durableObjects: { ACCOUNT_STORE: { className: "AccountStore", useSQLite: true }, RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } } });
    const disabled = new Miniflare(disabledOptions);
    try {
      const guarded = (await disabled.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
      assert.equal((await guarded.promptSubmit(actor(), "Unsafe configuration", {})).error, "submissions_disabled");
      await guarded.promptCommand(actor(), "resume");
      assert.equal((await guarded.promptRunner({ op: "claim", runnerId: "pc", requestId: nonce() }, nonce())).error, "execution_unverified");
    } finally { await disabled.dispose(); }
    console.log("Prompt checks passed: allowlist/guild, durable shared cooldown/FIFO/slot, 20% every-window quota, exact responses, dispatch receipts, cancellation preservation, notification separation, retention, and authenticated job-only transport.");
  } finally { await mf.dispose(); await rm(persist, { recursive: true, force: true }); }
}

async function checkAttachments() {
  const result = await build({ entryPoints: [fileURLToPath(new URL("../src/prompts.ts", import.meta.url))], bundle: true, format: "esm", platform: "browser", write: false });
  const { assembledPrompt, handlePromptInteraction } = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString("base64")}`);
  const originalFetch = globalThis.fetch;
  try {
    const env = { DISCORD_GUILD_ID: "11111111111111111", PROMPT_MAX_INPUT_BYTES: "73728", PROMPT_SUBMISSIONS_ENABLED: "true", ACCOUNT_STORE: { getByName() { return {}; } } };
    const attachment = { id: "44444444444444444", filename: "message.txt", size: 7, url: "https://cdn.discordapp.com/attachments/22222222222222222/44444444444444444/message.txt" };
    const data = { type: 2, id: "55555555555555555", guild_id: env.DISCORD_GUILD_ID, channel_id: "22222222222222222", application_id: "33333333333333333", token: "valid-interaction-token-long", member: { user: { id: "1250627753191931914" } },
      data: { name: "prompt", options: [{ type: 3, name: "text", value: "Instructions first" }, { type: 11, name: "file", value: attachment.id }], resolved: { attachments: { [attachment.id]: attachment } } } };
    let downloads = 0;
    globalThis.fetch = async () => { downloads++; return new Response("content"); };
    const assembled = await assembledPrompt(data, env);
    assert.equal(assembled.prompt, "Instructions first\n\n--- Attachment: message.txt ---\ncontent");
    const denied = await handlePromptInteraction({ ...data, member: { user: { id: "77777777777777777" }, permissions: "8", roles: ["owner"] } }, env, { waitUntil() { throw Error("No unauthorised work"); } }, true);
    assert.match((await denied.json()).data.content, /two permitted/); assert.equal(downloads, 1);
    for (const changed of [{ filename: "x.png" }, { size: 65537 }, { url: "http://cdn.discordapp.com/attachments/22222222222222222/44444444444444444/message.txt" },
      { url: "https://evil.test/message.txt" }, { url: "https://cdn.discordapp.com@evil.test/message.txt" }, { url: attachment.url.replace("44444444444444444", "99999999999999999") }]) {
      await assert.rejects(assembledPrompt({ ...data, data: { ...data.data, resolved: { attachments: { [attachment.id]: { ...attachment, ...changed } } } } }, env));
    }
    assert.equal(downloads, 1, "Metadata and URL validation happens before downloading");
    globalThis.fetch = async () => new Response(new Uint8Array([0xff, 0xfe, 1, 2, 3, 4, 5]));
    await assert.rejects(assembledPrompt(data, env), /invalid_utf8/);
    globalThis.fetch = async () => new Response("\0binary"); await assert.rejects(assembledPrompt(data, env), /invalid_attachment_text/);
    globalThis.fetch = async () => new Response("", { status: 302, headers: { Location: "https://evil.test" } });
    await assert.rejects(assembledPrompt(data, env), /attachment_download_failed/);
    const context = { ...data, data: { name: "Run prompt", type: 3, target_id: "66666666666666666", resolved: { messages: { "66666666666666666": {
      id: "66666666666666666", channel_id: data.channel_id, content: "Context instructions", attachments: [attachment] } } } } };
    globalThis.fetch = async () => new Response("content");
    assert.match((await assembledPrompt(context, env)).prompt, /^Context instructions\n\n--- Attachment: message.txt ---\ncontent$/);
    context.data.resolved.messages[context.data.target_id].attachments.push(attachment);
    await assert.rejects(assembledPrompt(context, env), /multiple_attachments/);
    globalThis.fetch = async () => new Response("x".repeat(65537)); await assert.rejects(assembledPrompt(data, env), /input_too_large/);
    globalThis.fetch = async () => new Response("content");
    await assert.rejects(assembledPrompt(data, { ...env, PROMPT_MAX_INPUT_BYTES: undefined }), /input_budget_unconfigured/);
    const modalData = { ...data, type: 3, data: { custom_id: `prompt:answer:${"a".repeat(32)}:${"b".repeat(32)}` } };
    const staleEnv = { ...env, ACCOUNT_STORE: { getByName() { return { promptPending: async () => ({ ok: false, error: "stale_request" }) }; } } };
    assert.equal((await (await handlePromptInteraction(modalData, staleEnv, { waitUntil() {} }, true)).json()).type, 4, "Stale controls cannot open a usable answer modal");
    const operations = [], stored = []; let releaseDownload;
    const download = new Promise(resolve => { releaseDownload = resolve; });
    globalThis.fetch = async url => String(url).startsWith('https://cdn.discordapp.com/') ? download : Response.json({});
    const submittingEnv = { ...env, ACCOUNT_STORE: { getByName() { return {
      promptIntake: async () => ({ ok: true }), promptSubmit: async (_actor, prompt) => { stored.push(prompt); return { ok: true, jobId: 'a'.repeat(32), cooldownUntil: Date.now() + 3600_000 }; },
    }; } } };
    const deferred = await handlePromptInteraction(data, submittingEnv, { waitUntil: operation => operations.push(operation) }, true);
    assert.equal((await deferred.json()).type, 5); assert.equal(stored.length, 0, 'Acknowledge before a delayed attachment download');
    releaseDownload(new Response('content')); await Promise.all(operations);
    assert.deepEqual(stored, ['Instructions first\n\n--- Attachment: message.txt ---\ncontent']);
    console.log("Prompt attachment checks passed: full UTF-8 text, message action, origin/id binding, byte limits, binary rejection, no redirects, and authorization before download.");
  } finally { globalThis.fetch = originalFetch; }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await checkPrompts(await readFile(new URL("../.test-build/index.js", import.meta.url), "utf8"));
  await checkAttachments();
}
