import { parsedRngSubmission, rngAcquisitionDecision, type PublicRngEvent } from "./digest";
import { rngPresentation, type RngPresentation } from "../../shared/rng-presentation.mjs";
import { deliverRngPublication } from "../../shared/rng-delivery.mjs";
import { deferInteraction, type DiscordAccountsConfig } from "./discord-accounts";
import type { AccountStore } from "./accounts";

export type RngTestOptions = { mode: "dry-run" | "mock-delivery"; fixture?: "handle" | "warden-heart" | "duplicate" | "malformed"; input?: Record<string, unknown> };
export type RngTestResult = { ok: boolean; error?: string; version?: 1; test?: true; authorized?: true; mode?: RngTestOptions["mode"];
  cases?: { label: string; status: "accepted" | "duplicate" | "invalid" | "rate_limited"; event?: PublicRngEvent }[];
  downstream?: string[]; writes?: { personal: false; acquisitions: false; shared: false; leaderboards: false; productionDiscord: false };
  mock?: { posts: number; acknowledgements: number; retrySuppressed: boolean; presentation: RngPresentation } };
const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const fixtures = ["handle", "warden-heart", "duplicate", "malformed"] as const;
/** No destination, real-publication flag or arbitrary fixture payload is accepted. */
export function rngTestOptions(raw: Record<string, unknown>): RngTestOptions | null {
  if (Object.keys(raw).some(key => !["mode", "fixture", "input"].includes(key)) || typeof raw.mode !== "string" || !["dry-run", "mock-delivery"].includes(raw.mode) ||
    (raw.fixture === undefined) === (raw.input === undefined)) return null;
  if (raw.fixture !== undefined && !fixtures.includes(raw.fixture as typeof fixtures[number]) || raw.input !== undefined &&
    (!object(raw.input) || JSON.stringify(raw.input).length > 2000)) return null;
  return raw as RngTestOptions;
}

/** Disposable memory only. The caller must prove current persisted administrator authority. */
export async function runRngTest(options: RngTestOptions, now = Date.now()): Promise<RngTestResult> {
  const handle = { type: "rng_publish", showName: false, event: { id: "a".repeat(32), item: "NECRON_HANDLE", activity: "dungeon", occurredAt: now,
    rarity: "LEGENDARY", displayName: "Necron's Handle", floor: "F7" } };
  const selected: { label: string; input: Record<string, unknown> }[] = options.input ? [{ label: "Custom normalized report", input: options.input }] :
    options.fixture === "duplicate" ? [{ label: "First acquisition", input: handle }, { label: "Same acquisition retry", input: handle }] :
    options.fixture === "malformed" ? [{ label: "Invalid acquisition ID", input: { ...handle, event: { ...handle.event, id: "invalid" } } },
      { label: "Unsupported item", input: { ...handle, event: { ...handle.event, item: "FAKE_HANDLE" } } },
      { label: "Invalid observed floor", input: { ...handle, event: { ...handle.event, floor: "M8" } } }] :
    [{ label: options.fixture === "warden-heart" ? "Slayer acquisition" : "Dungeon acquisition", input: options.fixture === "warden-heart" ?
      { type: "rng_publish", showName: false, event: { id: "b".repeat(32), item: "WARDEN_HEART", activity: "slayer", occurredAt: now, rarity: "LEGENDARY", displayName: "Warden Heart" } } : handle }];
  const reports: { id: string; time: number }[] = [], cases: NonNullable<RngTestResult["cases"]> = [];
  for (const sample of selected) {
    const event = parsedRngSubmission(sample.input, now);
    if (!event) { cases.push({ label: sample.label, status: "invalid" }); continue; }
    const status = rngAcquisitionDecision(event.id, reports, false, now);
    if (status !== "accepted") { cases.push({ label: sample.label, status }); continue; }
    reports.push({ id: event.id, time: now });
    cases.push({ label: sample.label, status, event: { ...event, id: crypto.randomUUID().replaceAll("-", ""), player: "Anonymous", unverified: true } });
  }
  const result: RngTestResult = { ok: true, version: 1, test: true, authorized: true, mode: options.mode, cases,
    downstream: ["Local temporary parser/journal fixture (Minecraft only)", "Normal relay validation and acquisition duplicate decision", "Normal Discord presentation", "Isolated mock receipt/ACK retry (mock-delivery only)"],
    writes: { personal: false, acquisitions: false, shared: false, leaderboards: false, productionDiscord: false } };
  const event = cases.find(sample => sample.status === "accepted")?.event;
  if (options.mode === "mock-delivery" && event) {
    const presentation = rngPresentation(event, now, true)!;
    const receipts = new Map<string, string>(); let posts = 0, acknowledgements = 0;
    const hooks = { channelId: "10000000000000001", now: () => now, present: (value: unknown) => rngPresentation(value, now, true),
      receipts: { get: (id: string) => receipts.get(id), record: async (id: string, message: string) => { receipts.set(id, message); } },
      send: async () => { posts++; return { id: "10000000000000002" }; },
      ack: async () => { acknowledgements++; if (acknowledgements === 1) throw new Error("Simulated lost ACK"); return {}; },
      remove: async () => { throw new Error("Unexpected mock deletion"); } };
    const delivery = { id: event.id, claim: "c".repeat(43), operation: "publish", channelId: hooks.channelId, event };
    try { await deliverRngPublication(delivery, hooks); } catch { /* The injected first ACK fails after the mock receipt is durable. */ }
    await deliverRngPublication({ ...delivery, claim: "d".repeat(43) }, hooks);
    result.mock = { posts, acknowledgements, retrySuppressed: posts === 1 && acknowledgements === 2, presentation };
  }
  return result;
}

type Config = { ACCOUNT_STORE: DurableObjectNamespace<AccountStore> };
export async function handleRngTestRequest(request: Request, env: Config): Promise<Response | null> {
  const url = new URL(request.url); if (url.pathname !== "/community/admin/rng-test") return null;
  const reply = (result: RngTestResult, status = result.ok ? 200 : result.error === "admin_required" || result.error === "identity_blacklisted" ? 403 :
    result.error === "session_expired" ? 401 : result.error === "rate_limited" ? 429 : 400) => Response.json(result, { status, headers: { "Cache-Control": "no-store" } });
  if (request.method !== "POST") return reply({ ok: false, error: "method_not_allowed" }, 405);
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "")?.[1];
  if (!token) return reply({ ok: false, error: "session_expired" });
  if (url.search || !request.headers.get("Content-Type")?.startsWith("application/json") || !request.body || Number(request.headers.get("Content-Length")) > 4096) return reply({ ok: false, error: "invalid_rng_test" });
  const reader = request.body.getReader(), chunks: Uint8Array[] = []; let bytes = 0, timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; void reader.cancel(); }, 3000);
  try {
    while (true) { const next = await reader.read(); if (next.done) break; bytes += next.value.length; if (bytes > 4096) return reply({ ok: false, error: "invalid_rng_test" }); chunks.push(next.value); }
    if (timedOut) return reply({ ok: false, error: "invalid_rng_test" });
    const body = new Uint8Array(bytes); let position = 0; for (const chunk of chunks) { body.set(chunk, position); position += chunk.length; }
    let raw: Record<string, unknown> | null;
    try { raw = object(JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body))); }
    catch { return reply({ ok: false, error: "invalid_rng_test" }); }
    if (!raw) return reply({ ok: false, error: "invalid_rng_test" });
    return reply(await env.ACCOUNT_STORE.getByName("global").communityRngTest(token, raw));
  } catch { return reply({ ok: false, error: "rng_test_unavailable" }, 503); }
  finally { clearTimeout(timeout); await reader.cancel(); }
}

function testMessage(result: RngTestResult): { content: string; embeds?: Record<string, unknown>[] } {
  if (!result.ok) return { content: result.error === "admin_required" ? "Only a current Sky-Myce administrator can run RNG tests." :
    result.error === "invalid_rng_test" ? "Choose one fixture or a bounded normalized report. No production destination is supported." : `RNG test denied or unavailable (${result.error ?? "unavailable"}). No real report was created.` };
  return { content: `**TEST · ${result.mode}**\n${result.cases!.map(sample => `${sample.label}: **${sample.status}**`).join("\n")}\n` +
    (result.mock ? `Mock transport: ${result.mock.posts} post, ${result.mock.acknowledgements} ACK attempts; retry duplicate suppressed: ${result.mock.retrySuppressed}.\n` : "") +
    "Personal journal, acquisitions, shared statistics, rankings and production Discord were untouched. Minecraft `/sm rng-test` additionally exercises the real local chat parser and temporary journal.",
    ...(result.mock ? { embeds: result.mock.presentation.embeds } : {}) };
}
/** Entry is reached only after Discord exact-body signature/application verification. Roles are ignored. */
export function handleRngTestInteraction(data: Record<string, unknown>, env: DiscordAccountsConfig, ctx: ExecutionContext, backend: boolean): Response | null {
  const command = object(data.data); if (command?.name !== "rng-test") return null;
  const invalid = () => Response.json({ type: 4, data: { content: "Invalid or unauthorized RNG test command. Use the Backend Bot in its configured guild.", flags: 64, allowed_mentions: { parse: [] } } });
  const member = object(data.member), caller = object(member?.user), raw = command.options;
  if (!backend || data.type !== 2 || data.guild_id !== env.DISCORD_GUILD_ID || typeof caller?.id !== "string" || !/^\d{17,20}$/.test(caller.id) || typeof data.id !== "string" || !/^\d{17,20}$/.test(data.id) || !Array.isArray(raw) || raw.length !== 1) return invalid();
  const sub = object(raw[0]); if (sub?.type !== 1 || !["dry-run", "mock-delivery"].includes(String(sub.name)) || !Array.isArray(sub.options) || sub.options.length > 2) return invalid();
  const options: Record<string, unknown> = { mode: sub.name };
  for (const value of sub.options) {
    const field = object(value);
    if (!field || !["fixture", "input"].includes(String(field.name)) || field.type !== 3 || typeof field.value !== "string" || field.value.length > 2000 || Object.hasOwn(options, String(field.name))) return invalid();
    try { options[String(field.name)] = field.name === "input" ? JSON.parse(field.value) : field.value; } catch { return invalid(); }
  }
  if (!rngTestOptions(options)) return invalid();
  return deferInteraction(data, ctx, async () => testMessage(await env.ACCOUNT_STORE.getByName("global").discordRngTest(
    { id: caller!.id as string, guildId: data.guild_id as string, roles: [], interactionId: data.id as string }, options)));
}
