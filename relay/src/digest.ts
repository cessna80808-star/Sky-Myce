export type NewsSource = "game" | "alpha";
export type NewsItem = { id: string; title: string; content: string; publishedAt: number; url?: string; truncated?: true };
export type NewsResult = { source: NewsSource; items: NewsItem[]; fetchedAt: number; retryAt: number;
  status: "ready" | "stale" | "unavailable"; error?: string };
export type DigestConfig = { DISCORD_NEWS_TOKEN?: string; DIGEST_GAME_CHANNEL?: string; DIGEST_ALPHA_CHANNEL?: string };
type NewsRow = { items: string; fetched_at: number; retry_at: number; last_id: string; failures: number; error: string };
const snowflake = /^\d{17,20}$/;
const identifier = /^[a-f0-9]{32}$/;
const NEWS_TTL = 300_000;
const encoder = new TextEncoder();

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

/** Bounded upstream body; never buffer an arbitrary Discord response. */
async function readJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("invalid_response");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > 262144) throw new Error("invalid_response");
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); } catch { throw new Error("invalid_response"); }
}

function text(value: unknown): string {
  return typeof value === "string" ? value.replace(/[\x00-\x08\x0b-\x1f\x7f§]/g, "").trim() : "";
}

function parseNews(value: unknown, source: NewsSource, channel: string, now: number): { items: NewsItem[]; lastId: string } {
  if (!Array.isArray(value) || value.length > 100) throw new Error("invalid_response");
  const items: NewsItem[] = [];
  let lastId = "";
  for (const entry of value) {
    const data = record(entry);
    if (!data || typeof data.id !== "string" || !snowflake.test(data.id)) continue;
    const publishedAt = typeof data.timestamp === "string" ? Date.parse(data.timestamp) : NaN;
    if (!Number.isSafeInteger(publishedAt) || publishedAt < 1577836800000 || publishedAt > now + 300_000) continue;
    // Followed announcements have ordinary content/embeds; forwarded messages may use snapshots.
    const snapshot = Array.isArray(data.message_snapshots) ? record(record(data.message_snapshots[0])?.message) : null;
    const message = text(data.content) || (Array.isArray(data.embeds) && data.embeds.length) ? data : snapshot ?? data;
    const pieces = [text(message.content)];
    if (Array.isArray(message.embeds)) for (const rawEmbed of message.embeds.slice(0, 10)) {
      const embed = record(rawEmbed);
      if (!embed) continue;
      pieces.push(text(embed.title), text(embed.description));
      if (Array.isArray(embed.fields)) for (const field of embed.fields.slice(0, 25)) {
        const item = record(field);
        if (item) pieces.push(text(item.name), text(item.value));
      }
    }
    const full = pieces.filter(Boolean).join("\n\n");
    if (!full) continue;
    const reference = record(data.message_reference);
    const linked = [reference?.guild_id, reference?.channel_id, reference?.message_id];
    const own = [data.guild_id, channel, data.id];
    const target = linked.every(part => typeof part === "string" && snowflake.test(part)) ? linked : own;
    const url = target.every(part => typeof part === "string" && snowflake.test(part)) ? `https://discord.com/channels/${target.join("/")}` : undefined;
    const title = full.split("\n").find(line => /[\p{L}\p{N}]/u.test(line))?.replace(/^[#>*\s]+|[*_`]/g, "").trim() || "SkyBlock update";
    items.push({ id: `${source}:${data.id}`, title: title.slice(0, 160), content: full.slice(0, 2500), publishedAt,
      ...(url ? { url } : {}), ...(full.length > 2500 ? { truncated: true as const } : {}) });
    if (!lastId || BigInt(data.id) > BigInt(lastId)) lastId = data.id;
  }
  // Missing message-content access can return HTTP 200 with IDs but no text. Never replace a good cache with that.
  if (value.length && !items.length) throw new Error("invalid_response");
  return { items, lastId };
}

/** Shared per-room read-only cache. Only explicit requests refresh it; there is no polling timer. */
export class DigestNews {
  private readonly pending = new Map<NewsSource, Promise<NewsResult>>();
  constructor(private sql: SqlStorage, private config: DigestConfig) {
    sql.exec(`CREATE TABLE IF NOT EXISTS digest_news (
      source TEXT PRIMARY KEY, items TEXT NOT NULL, fetched_at INTEGER NOT NULL, retry_at INTEGER NOT NULL,
      last_id TEXT NOT NULL, failures INTEGER NOT NULL, error TEXT NOT NULL)`);
  }

  get(source: NewsSource): Promise<NewsResult> {
    const existing = this.pending.get(source);
    if (existing) return existing;
    const request = this.refresh(source).finally(() => this.pending.delete(source));
    this.pending.set(source, request);
    return request;
  }

  private async refresh(source: NewsSource): Promise<NewsResult> {
    const now = Date.now();
    const row = this.sql.exec<NewsRow>("SELECT * FROM digest_news WHERE source = ?", source).toArray()[0];
    let items: NewsItem[] = [];
    if (row) { try { items = JSON.parse(row.items); } catch { /* A damaged cache never prevents a refresh. */ } }
    const result: NewsResult = { source, items, fetchedAt: row?.fetched_at ?? 0, retryAt: row?.retry_at ?? 0,
      status: row?.fetched_at && row.fetched_at > now - NEWS_TTL && !row.error ? "ready" : items.length ? "stale" : "unavailable",
      ...(row?.error ? { error: row.error } : {}) };
    if (row && row.retry_at > now) return result;
    const channel = source === "game" ? this.config.DIGEST_GAME_CHANNEL : this.config.DIGEST_ALPHA_CHANNEL;
    if (!this.config.DISCORD_NEWS_TOKEN || !channel || !snowflake.test(channel)) {
      return { ...result, retryAt: now + NEWS_TTL, error: "not_configured" };
    }
    let lastId = row?.last_id ?? "";
    let failures = row?.failures ?? 0;
    let error = "";
    let retryAt = now + NEWS_TTL;
    let fetchedAt = row?.fetched_at ?? 0;
    try {
      const after = lastId ? `&after=${lastId}` : "";
      const response = await fetch(`https://discord.com/api/v10/channels/${channel}/messages?limit=8${after}`, {
        headers: { Authorization: `Bot ${this.config.DISCORD_NEWS_TOKEN}` }, signal: AbortSignal.timeout(8000), redirect: "manual",
      });
      if (!response.ok) {
        if (response.status === 429) {
          const seconds = Number(response.headers.get("Retry-After"));
          if (Number.isFinite(seconds) && seconds > 0) retryAt = now + Math.min(seconds * 1000, 86_400_000);
        }
        await response.body?.cancel();
        throw new Error(response.status === 429 ? "rate_limited" : response.status === 401 || response.status === 403 ? "authentication" : "offline");
      }
      const parsed = parseNews(await readJson(response), source, channel, now);
      const unique = new Map([...items, ...parsed.items].map(item => [item.id, item]));
      items = [...unique.values()].sort((a, b) => b.publishedAt - a.publishedAt).slice(0, 8);
      // Unicode escaping and embed text also count toward the wire limit.
      while (encoder.encode(JSON.stringify(items)).length > 31_000) items.pop();
      if (parsed.lastId && (!lastId || BigInt(parsed.lastId) > BigInt(lastId))) lastId = parsed.lastId;
      fetchedAt = now;
      failures = 0;
    } catch (cause) {
      error = cause instanceof Error && ["invalid_response", "authentication", "rate_limited", "offline"].includes(cause.message)
        ? cause.message : cause instanceof Error && ["TimeoutError", "AbortError"].includes(cause.name) ? "timeout" : "offline";
      failures = Math.min(failures + 1, 5);
      retryAt = Math.max(retryAt, now + Math.min(NEWS_TTL * 2 ** (failures - 1), 1_800_000));
    }
    this.sql.exec("INSERT OR REPLACE INTO digest_news VALUES (?, ?, ?, ?, ?, ?, ?)",
      source, JSON.stringify(items), fetchedAt, retryAt, lastId, failures, error);
    return { source, items, fetchedAt, retryAt, status: error ? items.length ? "stale" : "unavailable" : "ready",
      ...(error ? { error } : {}) };
  }
}

// This matches the client's conservative acquisition detector, not a general item/chat upload endpoint.
const rareDrops: Record<string, readonly string[]> = {
  dungeon: ["NECRON_HANDLE", "GIANTS_SWORD", "DARK_CLAYMORE", "SHADOW_FURY", "IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL"],
  slayer: ["WARDEN_HEART", "JUDGEMENT_CORE", "OVERFLUX_CAPACITOR"], kuudra: [], fishing: [],
};
const rngRarities = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "ULTIMATE", "SPECIAL", "VERY_SPECIAL", "ADMIN"] as const;
export type RngMetadata = { rarity?: typeof rngRarities[number]; displayName?: string; floor?: string | null };
export type PublicRngEvent = RngMetadata & { id: string; item: string; activity: string; occurredAt: number; player: string; unverified: true };
const rngEventKeys = ["id", "item", "activity", "occurredAt", "rarity", "displayName", "floor"];
/** Optional observed item details never infer rarity or floor from the drop catalog. */
function rngMetadata(event: Record<string, unknown>): RngMetadata | null {
  const { rarity, displayName, floor } = event;
  if (rarity !== undefined && rarity !== null && (typeof rarity !== "string" || !rngRarities.includes(rarity as typeof rngRarities[number])) ||
    displayName !== undefined && displayName !== null && (typeof displayName !== "string" || displayName.length < 1 || displayName.length > 120 || displayName.trim() !== displayName || /[\p{Cc}\p{Cf}\p{Cs}\u2028\u2029§]/u.test(displayName)) ||
    floor !== undefined && floor !== null && (event.activity !== "dungeon" || typeof floor !== "string" || !/^(?:E|[FM][1-7])$/.test(floor))) return null;
  return { ...(typeof rarity === "string" ? { rarity: rarity as typeof rngRarities[number] } : {}),
    ...(typeof displayName === "string" ? { displayName } : {}), ...(floor !== undefined ? { floor: floor as string | null } : {}) };
}
/** Trusted forwarding still validates its persisted/public boundary and projects only declared fields. */
export function validatedPublicRngEvent(raw: unknown): PublicRngEvent | null {
  const event = record(raw), metadata = event && rngMetadata(event);
  if (!event || !metadata || Object.keys(event).some(key => ![...rngEventKeys, "player", "unverified"].includes(key)) ||
    typeof event.id !== "string" || !identifier.test(event.id) || typeof event.item !== "string" ||
    typeof event.activity !== "string" || !Object.hasOwn(rareDrops, event.activity) || !rareDrops[event.activity].includes(event.item) ||
    !Number.isSafeInteger(event.occurredAt) || Number(event.occurredAt) <= 0 || event.unverified !== true ||
    typeof event.player !== "string" || !(event.player === "Anonymous" || /^[A-Za-z0-9_]{1,16}$/.test(event.player))) return null;
  return { id: event.id, item: event.item, activity: event.activity, occurredAt: Number(event.occurredAt), player: event.player, unverified: true, ...metadata };
}
/** Existing strict v3 parsers receive their original exact shape unless they negotiate metadata version 1. */
export function rngPayload(event: PublicRngEvent, metadataVersion?: number): PublicRngEvent {
  const { id, item, activity, occurredAt, player, unverified } = event;
  return { id, item, activity, occurredAt, player, unverified, ...(metadataVersion === 1 ? rngMetadata(event) ?? {} : {}) };
}

export class DigestRng {
  constructor(private sql: SqlStorage) {
    sql.exec(`CREATE TABLE IF NOT EXISTS digest_rng (
      id INTEGER PRIMARY KEY AUTOINCREMENT, author TEXT NOT NULL, source_id TEXT NOT NULL,
      received_at INTEGER NOT NULL, event TEXT NOT NULL, UNIQUE(author, source_id))`);
    sql.exec("CREATE INDEX IF NOT EXISTS digest_rng_author_time ON digest_rng(author, received_at)");
    sql.exec("CREATE TABLE IF NOT EXISTS digest_rng_limits (author TEXT PRIMARY KEY, reports TEXT NOT NULL, updated INTEGER NOT NULL)");
    sql.exec("CREATE INDEX IF NOT EXISTS digest_rng_limits_time ON digest_rng_limits(updated)");
  }

  recent(now: number): PublicRngEvent[] {
    return this.recentRecords(now).map(row => row.event);
  }

  /** Keep author identifiers internal so the relay can enforce persisted consent before returning public projections. */
  recentRecords(now: number): { author: string; event: PublicRngEvent; receivedAt: number }[] {
    this.sql.exec("DELETE FROM digest_rng WHERE received_at <= ?", now - 604_800_000);
    this.sql.exec("DELETE FROM digest_rng_limits WHERE updated <= ?", now - 360_000);
    return this.sql.exec<{ author: string; event: string; received_at: number }>("SELECT author,event,received_at FROM digest_rng WHERE received_at > ? ORDER BY id DESC LIMIT 30", now - 604_800_000)
      .toArray().map(row => ({ author: row.author, event: JSON.parse(row.event), receivedAt: row.received_at }));
  }

  deleteAuthors(uuids: string[]): string[] {
    const ids: string[] = [];
    for (const uuid of uuids) {
      this.sql.exec<{ event: string }>("SELECT event FROM digest_rng WHERE author=?", uuid).toArray()
        .forEach(row => ids.push((JSON.parse(row.event) as PublicRngEvent).id));
      this.sql.exec("DELETE FROM digest_rng WHERE author=?", uuid);
      // Retain the short rate-limit window. Privacy changes must not make repeated reports eligible again.
    }
    return ids;
  }

  publish(author: string, name: string, data: Record<string, unknown>, now: number): { stored: boolean; error?: string; event?: PublicRngEvent } {
    const event = record(data.event), metadata = event && rngMetadata(event);
    if (Object.keys(data).some(key => !["type", "id", "event", "showName"].includes(key)) || typeof data.showName !== "boolean" || !event ||
        !metadata || Object.keys(event).some(key => !rngEventKeys.includes(key)) ||
        typeof event.id !== "string" || !identifier.test(event.id) || typeof event.item !== "string" ||
        typeof event.activity !== "string" || !Object.hasOwn(rareDrops, event.activity) || !rareDrops[event.activity].includes(event.item) ||
        typeof event.occurredAt !== "number" || !Number.isSafeInteger(event.occurredAt) ||
        event.occurredAt <= now - 300_000 || event.occurredAt > now + 60_000) return { stored: false, error: "invalid_event" };
    const row = this.sql.exec<{ reports: string }>("SELECT reports FROM digest_rng_limits WHERE author = ?", author).toArray()[0];
    const reports: { id: string; time: number }[] = row ? JSON.parse(row.reports) : [];
    const recent = reports.filter(report => report.time > now - 360_000);
    if (recent.some(report => report.id === event.id) ||
        this.sql.exec("SELECT id FROM digest_rng WHERE author = ? AND source_id = ?", author, event.id).toArray().length) return { stored: false };
    if (recent.filter(report => report.time > now - 60_000).length >= 3) {
      return { stored: false, error: "rate_limited" };
    }
    const published: PublicRngEvent = { id: crypto.randomUUID().replaceAll("-", ""), item: event.item, activity: event.activity,
      occurredAt: event.occurredAt, player: data.showName ? name : "Anonymous", unverified: true, ...metadata };
    this.sql.exec("INSERT INTO digest_rng (author, source_id, received_at, event) VALUES (?, ?, ?, ?)", author, event.id, now, JSON.stringify(published));
    // Keep rate limits independent of the public history cap, so evictions/reconnects cannot reset them.
    this.sql.exec("INSERT OR REPLACE INTO digest_rng_limits VALUES (?, ?, ?)", author, JSON.stringify([...recent, { id: event.id, time: now }]), now);
    this.sql.exec("DELETE FROM digest_rng_limits WHERE updated <= ?", now - 360_000);
    this.sql.exec("DELETE FROM digest_rng WHERE id <= (SELECT MAX(id) FROM digest_rng) - 200 OR received_at <= ?", now - 604_800_000);
    return { stored: true, event: published };
  }
}
