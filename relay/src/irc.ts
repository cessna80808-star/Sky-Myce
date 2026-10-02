import type { AccountStore } from "./accounts";
import type { RelayRoom } from "./index";

export type IrcConfig = {
  ACCOUNT_STORE: DurableObjectNamespace<AccountStore>;
  RELAY_ROOMS: DurableObjectNamespace<RelayRoom>;
  ROOMS: string;
  DISCORD_BRIDGE_SECRET?: string;
  DISCORD_IRC_CHANNEL_ID?: string;
  DISCORD_IRC_TEST_CHANNEL_ID?: string;
  IRC_BURST?: string;
  IRC_REFILL_SECONDS?: string;
  IRC_GLOBAL_BURST?: string;
  IRC_GLOBAL_REFILL_MS?: string;
  IRC_DEDUP_SIZE?: string;
  IRC_DEDUP_TTL_MS?: string;
  GLOBAL_CHAT_CHANNEL_ID?: string;
  GLOBAL_CHAT_BURST?: string;
  GLOBAL_CHAT_REFILL_SECONDS?: string;
  GLOBAL_CHAT_MIN_INTERVAL_MS?: string;
  GLOBAL_CHAT_DUPLICATE_MS?: string;
  GLOBAL_CHAT_GLOBAL_BURST?: string;
  GLOBAL_CHAT_GLOBAL_REFILL_MS?: string;
  GLOBAL_CHAT_MAX_PENDING?: string;
  GLOBAL_CHAT_OUTBOX_TTL_MS?: string;
  GLOBAL_CHAT_MC_MAX_LENGTH?: string;
  GLOBAL_CHAT_DISCORD_MAX_LENGTH?: string;
};
export type IrcSession = {
  bridge?: boolean; room?: string; ircSubscribed?: boolean; globalChatSubscribed?: boolean; accountId?: string; discordId?: string;
  uuid?: string; name?: string; installationId?: string; generation?: number; leaseExpiresAt?: number; buildId?: string; loaderVersion?: string; connectionId?: string;
};
const encoder = new TextEncoder();
const snowflake = /^\d{17,20}$/;
const packetId = /^[a-f0-9]{32}$/;
export const GLOBAL_CHAT_ROOM = "global-chat";
export const globalChatChannel = (env: IrcConfig): string | undefined => {
  const channel = env.GLOBAL_CHAT_CHANNEL_ID ?? env.DISCORD_IRC_CHANNEL_ID;
  return channel && snowflake.test(channel) ? channel : undefined;
};
export type GlobalChatSender = Pick<IrcSession, "accountId" | "discordId" | "uuid" | "name" | "installationId" | "generation" | "leaseExpiresAt" | "buildId" | "loaderVersion">;
export type GlobalChatSend = { id: string; text: string };
export type GlobalChatResult = { ok: boolean; error?: string; id?: string };
type GlobalReply = { message_id?: string; from: string; text: string };
export type GlobalChatMessage = {
  id: string; source: "minecraft" | "discord"; from: string; text: string; timestamp: number; expiresAt: number;
  discordId: string; uuid?: string; mentions: string[]; reply?: GlobalReply; sender?: GlobalChatSender;
};
type PendingDelivery = { id: string; destination: string; attempts: number; packet: string; expires: number };
const bounded = (value: string | undefined, fallback: number, minimum: number, maximum: number) => {
  const parsed = Number(value);
  return value !== undefined && Number.isFinite(parsed) ? Math.max(minimum, Math.min(maximum, parsed)) : fallback;
};
const unsafeText = /[\x00-\x09\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/;
const validGlobalText = (value: unknown, maximum: number): value is string => typeof value === "string" &&
  value.trim().length > 0 && value.length <= maximum && value.isWellFormed() && !unsafeText.test(value);
const unicodeSlice = (value: string, maximum: number): string => {
  const sliced = value.toWellFormed().slice(0, maximum);
  return sliced.isWellFormed() ? sliced : sliced.slice(0, -1);
};
const displayName = (value: unknown): string => typeof value === "string"
  ? unicodeSlice(value.replace(/[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/g, "").trim(), 64) || "Discord user" : "Discord user";
export const validIrcText = (text: unknown): text is string => typeof text === "string" &&
  text.trim().length > 0 && text.length <= 256 && !/[\x00-\x1f\x7f\u00a7\u202a-\u202e\u2066-\u2069]/.test(text);

/** TLS protects transport; timestamp + a persisted nonce prevents replay of a captured handshake. */
export async function verifyBridge(request: Request, env: IrcConfig, room: string, now = Date.now()): Promise<string | null> {
  const time = request.headers.get("X-SkyMyce-Timestamp") ?? "";
  const nonce = request.headers.get("X-SkyMyce-Nonce") ?? "";
  const signature = request.headers.get("X-SkyMyce-Signature") ?? "";
  if (!env.DISCORD_BRIDGE_SECRET || env.DISCORD_BRIDGE_SECRET.length < 32 ||
      !/^\d{13}$/.test(time) || Math.abs(now - Number(time)) > 60_000 ||
      !packetId.test(nonce) || !/^[a-f0-9]{64}$/.test(signature)) return null;
  const key = await crypto.subtle.importKey("raw", encoder.encode(env.DISCORD_BRIDGE_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const bytes = Uint8Array.from(signature.match(/../g)!, value => parseInt(value, 16));
  return await crypto.subtle.verify("HMAC", key, bytes, encoder.encode(`sky-myce-bridge\n${room}\n${time}\n${nonce}`)) ? nonce : null;
}

/** Room IRC remains live-only. Global chat persists a bounded two-minute delivery outbox. */
export class RoomIrc {
  constructor(private ctx: DurableObjectState, private env: IrcConfig,
      private authorized: (ws: WebSocket, session: IrcSession) => Promise<boolean>) {
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_nonces (nonce TEXT PRIMARY KEY, expires INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_seen (id TEXT PRIMARY KEY, expires INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_limits (actor TEXT PRIMARY KEY, credits REAL NOT NULL, updated INTEGER NOT NULL)");
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS global_chat_messages (id TEXT PRIMARY KEY, packet TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS global_chat_outbox (id TEXT NOT NULL, destination TEXT NOT NULL, next_at INTEGER NOT NULL, attempts INTEGER NOT NULL, PRIMARY KEY(id,destination));
      CREATE INDEX IF NOT EXISTS global_chat_due ON global_chat_outbox(next_at);
      CREATE TABLE IF NOT EXISTS global_chat_seen (source_id TEXT PRIMARY KEY, id TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS global_chat_repeats (actor TEXT NOT NULL, digest TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(actor,digest));
      CREATE TABLE IF NOT EXISTS global_chat_limits (actor TEXT PRIMARY KEY, credits REAL NOT NULL, updated INTEGER NOT NULL, last_sent INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS global_chat_delivered (id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS global_chat_receipts (message_id TEXT PRIMARY KEY, id TEXT NOT NULL, discord_id TEXT NOT NULL, from_name TEXT NOT NULL, expires INTEGER NOT NULL)`);
  }
  acceptNonce(nonce: string): boolean {
    return this.ctx.storage.transactionSync(() => {
      const now = Date.now(), sql = this.ctx.storage.sql;
      sql.exec("DELETE FROM irc_nonces WHERE expires <= ?", now);
      if (sql.exec("SELECT nonce FROM irc_nonces WHERE nonce = ?", nonce).toArray().length) return false;
      sql.exec("INSERT INTO irc_nonces VALUES (?, ?)", nonce, now + 120_000);
      return true;
    });
  }
  private accept(actor: string, id: string): string | null {
    return this.ctx.storage.transactionSync(() => {
      const sql = this.ctx.storage.sql, now = Date.now();
      sql.exec("DELETE FROM irc_seen WHERE expires <= ?", now);
      sql.exec("DELETE FROM irc_limits WHERE updated < ?", now - 3_600_000);
      if (sql.exec("SELECT id FROM irc_seen WHERE id = ?", `${actor}:${id}`).toArray().length) return "duplicate_message";
      const burst = Math.max(1, Math.min(12, Number(this.env.IRC_BURST) || 4));
      const refill = Math.max(1, Math.min(60, Number(this.env.IRC_REFILL_SECONDS) || 3)) * 1000;
      const previous = sql.exec<{ credits: number; updated: number }>("SELECT * FROM irc_limits WHERE actor = ?", actor).toArray()[0];
      const credits = Math.min(burst, previous ? previous.credits + (now - previous.updated) / refill : burst);
      sql.exec("INSERT INTO irc_limits VALUES (?, ?, ?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated", actor, credits >= 1 ? credits - 1 : credits, now);
      if (credits < 1) return "rate_limited";
      const globalBurst = Math.max(1, Math.min(100, Number(this.env.IRC_GLOBAL_BURST) || 20));
      const globalRefill = Math.max(100, Math.min(60_000, Number(this.env.IRC_GLOBAL_REFILL_MS) || 1000));
      const global = sql.exec<{ credits: number; updated: number }>("SELECT * FROM irc_limits WHERE actor='global'").toArray()[0];
      const available = Math.min(globalBurst, global ? global.credits + (now - global.updated) / globalRefill : globalBurst);
      sql.exec("INSERT INTO irc_limits VALUES ('global', ?, ?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated", available >= 1 ? available - 1 : available, now);
      if (available < 1) return "rate_limited";
      const ttl = Math.max(1000, Math.min(3_600_000, Number(this.env.IRC_DEDUP_TTL_MS) || 300_000));
      const maximum = Math.max(1, Math.min(10000, Number(this.env.IRC_DEDUP_SIZE) || 4096));
      sql.exec("INSERT INTO irc_seen VALUES (?, ?)", `${actor}:${id}`, now + ttl);
      sql.exec("DELETE FROM irc_seen WHERE rowid NOT IN (SELECT rowid FROM irc_seen ORDER BY rowid DESC LIMIT ?)", maximum);
      return null;
    });
  }
  async message(ws: WebSocket, session: IrcSession, data: Record<string, unknown>): Promise<void> {
    if (data.scope === "global" || session.bridge && session.room === GLOBAL_CHAT_ROOM) {
      await this.globalMessage(ws, session, data); return;
    }
    const result = (error?: string) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", id: data.id, ...(error ? { error } : {}) })); };
    if (typeof data.id !== "string" || !(session.bridge ? snowflake : packetId).test(data.id)) { ws.close(1008, "Invalid IRC packet"); return; }
    if (session.bridge && (data.room !== session.room || data.source !== "discord" || data.message_id !== data.id)) { result("invalid_route"); return; }
    if (!session.bridge && data.type === "irc_subscribe") {
      if (typeof data.enabled !== "boolean") { result("invalid_subscription"); return; }
      if (data.enabled && (!session.discordId || !(await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(session.discordId)).ok)) { result("not_authorized"); return; }
      session.ircSubscribed = data.enabled;
      ws.serializeAttachment(session); result(); return;
    }
    if (data.type !== (session.bridge ? "discord_message" : "irc_send") || !validIrcText(data.text)) { result("invalid_message"); return; }
    if (!session.bridge && !session.ircSubscribed) { result("not_subscribed"); return; }
    const discordId = session.bridge ? data.discordId : session.discordId;
    if (typeof discordId !== "string" || !snowflake.test(discordId)) { result("not_authorized"); return; }
    const account = await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(discordId);
    if (!account.ok) { result("not_authorized"); return; }
    const error = this.accept(`${session.bridge ? "dc" : "mc"}:${discordId}`, data.id);
    if (error) { result(error); return; }
    // The bot's signed transport carries the Gateway identity. Minecraft names/UUIDs come from verifyAccount.
    const from = session.bridge ? (typeof data.from === "string" ? data.from.replace(/[\x00-\x1f\x7f\u00a7]/g, "").slice(0, 64) : "Discord user") : session.name;
    const messageId = crypto.randomUUID().replaceAll("-", "");
    const packet = JSON.stringify({ type: "irc_message", id: messageId, message_id: messageId, room: session.room,
      source: session.bridge ? "discord" : "minecraft", from, text: data.text,
      ...(session.bridge ? { discordId } : { uuid: session.uuid }) });
    for (const peer of this.ctx.getWebSockets()) {
      if (peer.readyState !== WebSocket.OPEN) continue;
      const recipient = peer.deserializeAttachment() as IrcSession;
      if (recipient.bridge ? session.bridge : !recipient.ircSubscribed) continue;
      if (!recipient.bridge && !await this.authorized(peer, recipient)) continue;
      try { peer.send(packet); } catch { /* Live relay only: reconnect restores subscriptions, never message history. */ }
    }
    result();
  }

  private coordinator(): boolean { return this.ctx.id.equals(this.env.RELAY_ROOMS.idFromName(GLOBAL_CHAT_ROOM)); }
  private rooms(): string[] { return [...new Set(this.env.ROOMS.split(",").filter(room => /^[a-z0-9_-]{1,32}$/.test(room) && room !== GLOBAL_CHAT_ROOM))].slice(0, 64); }
  private textLimit(discord: boolean): number { return Math.floor(discord ? bounded(this.env.GLOBAL_CHAT_DISCORD_MAX_LENGTH, 4096, 1, 4096) : bounded(this.env.GLOBAL_CHAT_MC_MAX_LENGTH, 1024, 1, 1024)); }

  private async validSender(sender: GlobalChatSender): Promise<boolean> {
    if (!sender.accountId || !sender.installationId || !sender.uuid || !packetId.test(sender.uuid) ||
        !sender.name || !/^[A-Za-z0-9_]{1,16}$/.test(sender.name) || !sender.discordId || !snowflake.test(sender.discordId) ||
        !Number.isSafeInteger(sender.generation) || !sender.buildId || !sender.loaderVersion ||
        !sender.leaseExpiresAt || sender.leaseExpiresAt <= Date.now()) return false;
    const state = await this.env.ACCOUNT_STORE.getByName("global").checkSession({ accountId: sender.accountId,
      uuid: sender.uuid, installationId: sender.installationId, generation: sender.generation!, buildId: sender.buildId, loaderVersion: sender.loaderVersion });
    return state.ok && state.discordId === sender.discordId;
  }

  /** Binding-only coordinator RPC; the original authenticated socket supplies this identity. */
  async globalMinecraft(sender: GlobalChatSender, input: GlobalChatSend): Promise<GlobalChatResult> {
    if (!this.coordinator() || !globalChatChannel(this.env)) return { ok: false, error: "not_configured" };
    if (!packetId.test(input.id) || !validGlobalText(input.text, this.textLimit(false))) return { ok: false, error: "invalid_message" };
    if (!await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    return this.enqueue(`mc:${sender.accountId}`, input.id, { source: "minecraft", from: sender.name!, text: input.text,
      discordId: sender.discordId!, uuid: sender.uuid, mentions: [], sender });
  }

  private async globalMessage(ws: WebSocket, session: IrcSession, data: Record<string, unknown>): Promise<void> {
    const result = (error?: string) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", scope: "global", id: data.id,
      ...(data.part === "embeds" ? { part: "embeds" } : {}), ...(error ? { error } : {}) })); };
    if (!globalChatChannel(this.env)) { result("not_configured"); return; }
    if (session.bridge) {
      if (!this.coordinator() || session.room !== GLOBAL_CHAT_ROOM || data.scope !== "global" || data.room !== GLOBAL_CHAT_ROOM || data.channelId !== globalChatChannel(this.env)) { result("invalid_route"); return; }
      if (data.type === "discord_ack") { result(this.acknowledge(data)); return; }
      if (data.type !== "discord_message" || data.source !== "discord" || typeof data.id !== "string" || !snowflake.test(data.id) || data.message_id !== data.id ||
          typeof data.discordId !== "string" || !snowflake.test(data.discordId) || !validGlobalText(data.text, this.textLimit(true))) { result("invalid_message"); return; }
      if (data.part !== undefined && data.part !== "embeds") { result("invalid_part"); return; }
      const continuation = data.part === "embeds";
      if (data.mentions !== undefined && (!Array.isArray(data.mentions) || data.mentions.length > 100 || data.mentions.some(id => typeof id !== "string" || !snowflake.test(id)))) { result("invalid_mentions"); return; }
      if (continuation && (data.reply !== undefined || Array.isArray(data.mentions) && data.mentions.length > 0)) { result("invalid_part"); return; }
      let reply: GlobalReply | undefined;
      if (data.reply !== undefined) {
        if (!data.reply || typeof data.reply !== "object" || Array.isArray(data.reply)) { result("invalid_reply"); return; }
        const candidate = data.reply as Record<string, unknown>;
        if (typeof candidate.message_id !== "string" || !snowflake.test(candidate.message_id) || typeof candidate.from !== "string" || candidate.from.length > 64 ||
            candidate.text !== undefined && (typeof candidate.text !== "string" || candidate.text.length > 256 || !candidate.text.isWellFormed() || unsafeText.test(candidate.text))) { result("invalid_reply"); return; }
        const receipt = this.ctx.storage.sql.exec<{ from_name: string }>("SELECT from_name FROM global_chat_receipts WHERE message_id=? AND expires>?", candidate.message_id, Date.now()).toArray()[0];
        reply = { message_id: candidate.message_id, from: receipt?.from_name ?? displayName(candidate.from), text: typeof candidate.text === "string" ? candidate.text : "" };
      }
      const outcome = await this.enqueue(`dc:${data.discordId}`, `${data.id}${continuation ? ":embeds" : ""}`, { source: "discord", from: displayName(data.from), text: data.text,
        discordId: data.discordId, mentions: [...new Set((data.mentions as string[] | undefined) ?? [])], ...(reply ? { reply } : {}) }, continuation ? data.id : undefined);
      result(outcome.error); return;
    }
    if (typeof data.id !== "string" || !packetId.test(data.id)) { ws.close(1008, "Invalid global chat packet"); return; }
    if (data.type === "irc_subscribe") {
      if (typeof data.enabled !== "boolean") { result("invalid_subscription"); return; }
      if (data.enabled && (!session.discordId || !(await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(session.discordId)).ok)) { result("not_authorized"); return; }
      session.globalChatSubscribed = data.enabled;
      ws.serializeAttachment(session); result(); return;
    }
    if (data.type !== "irc_send" || !validGlobalText(data.text, this.textLimit(false))) { result("invalid_message"); return; }
    if (!session.globalChatSubscribed) { result("not_subscribed"); return; }
    const sender: GlobalChatSender = { accountId: session.accountId, discordId: session.discordId, uuid: session.uuid, name: session.name,
      installationId: session.installationId, generation: session.generation, leaseExpiresAt: session.leaseExpiresAt, buildId: session.buildId, loaderVersion: session.loaderVersion };
    const outcome = await this.env.RELAY_ROOMS.getByName(GLOBAL_CHAT_ROOM).globalChatMinecraft(sender, { id: data.id, text: data.text });
    result(outcome.error);
  }

  private prune(now: number): void {
    const sql = this.ctx.storage.sql;
    sql.exec("DELETE FROM global_chat_messages WHERE expires<=?", now);
    sql.exec("DELETE FROM global_chat_outbox WHERE id NOT IN (SELECT id FROM global_chat_messages)");
    for (const table of ["global_chat_seen", "global_chat_repeats", "global_chat_delivered", "global_chat_receipts"]) sql.exec(`DELETE FROM ${table} WHERE expires<=?`, now);
    sql.exec("DELETE FROM global_chat_limits WHERE updated<?", now - 3_600_000);
  }

  private async enqueue(actor: string, sourceId: string, input: Omit<GlobalChatMessage, "id" | "timestamp" | "expiresAt">, continuationFor?: string): Promise<GlobalChatResult> {
    const digest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(input.text.trim().replace(/\s+/g, " "))))].map(byte => byte.toString(16).padStart(2, "0")).join("");
    const now = Date.now(), sql = this.ctx.storage.sql;
    const outcome = this.ctx.storage.transactionSync((): GlobalChatResult => {
      this.prune(now);
      if (continuationFor && !sql.exec("SELECT 1 FROM global_chat_seen WHERE source_id=?", `discord:${continuationFor}`).toArray().length) return { ok: false, error: "unknown_message" };
      const dedupId = input.source === "discord" ? `discord:${sourceId}` : `${actor}:${sourceId}`;
      const seen = sql.exec<{ id: string }>("SELECT id FROM global_chat_seen WHERE source_id=?", dedupId).toArray()[0];
      if (seen) return { ok: false, error: "duplicate_message", id: seen.id };
      if (sql.exec("SELECT 1 FROM global_chat_repeats WHERE actor=? AND digest=?", actor, digest).toArray().length) return { ok: false, error: "duplicate_text" };
      const maximum = Math.floor(bounded(this.env.GLOBAL_CHAT_MAX_PENDING, 256, 1, 1024));
      if (sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM global_chat_messages").one().count >= maximum) return { ok: false, error: "temporarily_unavailable" };
      const burst = bounded(this.env.GLOBAL_CHAT_BURST, 6, 1, 20), refill = bounded(this.env.GLOBAL_CHAT_REFILL_SECONDS, 2, 0.25, 60) * 1000;
      const globalBurst = bounded(this.env.GLOBAL_CHAT_GLOBAL_BURST, 40, 1, 200), globalRefill = bounded(this.env.GLOBAL_CHAT_GLOBAL_REFILL_MS, 250, 10, 60_000);
      const previous = sql.exec<{ credits: number; updated: number; last_sent: number }>("SELECT * FROM global_chat_limits WHERE actor=?", actor).toArray()[0];
      const global = sql.exec<{ credits: number; updated: number }>("SELECT * FROM global_chat_limits WHERE actor='global'").toArray()[0];
      const credits = Math.min(burst, previous ? previous.credits + Math.max(0, now - previous.updated) / refill : burst);
      const available = Math.min(globalBurst, global ? global.credits + Math.max(0, now - global.updated) / globalRefill : globalBurst);
      if (available < 1 || !continuationFor && (credits < 1 || previous && now - previous.last_sent < bounded(this.env.GLOBAL_CHAT_MIN_INTERVAL_MS, 250, 0, 60_000))) return { ok: false, error: "rate_limited" };
      if (!continuationFor) sql.exec("INSERT INTO global_chat_limits VALUES(?,?,?,?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated,last_sent=excluded.last_sent", actor, credits - 1, now, now);
      sql.exec("INSERT INTO global_chat_limits VALUES('global',?,?,?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated,last_sent=excluded.last_sent", available - 1, now, now);
      const id = crypto.randomUUID().replaceAll("-", ""), expiresAt = now + bounded(this.env.GLOBAL_CHAT_OUTBOX_TTL_MS, 120_000, 1000, 120_000);
      const packet: GlobalChatMessage = { ...input, id, timestamp: now, expiresAt };
      sql.exec("INSERT INTO global_chat_seen VALUES(?,?,?)", dedupId, id, now + 3_600_000);
      sql.exec("INSERT INTO global_chat_repeats VALUES(?,?,?)", actor, digest, now + bounded(this.env.GLOBAL_CHAT_DUPLICATE_MS, 15_000, 0, 120_000));
      sql.exec("INSERT INTO global_chat_messages VALUES(?,?,?)", id, JSON.stringify(packet), expiresAt);
      for (const destination of [...this.rooms().map(room => `room:${room}`), ...(input.source === "minecraft" ? ["discord"] : [])]) sql.exec("INSERT INTO global_chat_outbox VALUES(?,?,?,0)", id, destination, now);
      for (const table of ["global_chat_seen", "global_chat_repeats"]) sql.exec(`DELETE FROM ${table} WHERE rowid NOT IN (SELECT rowid FROM ${table} ORDER BY rowid DESC LIMIT 10000)`);
      return { ok: true, id };
    });
    if (outcome.ok) await this.flushGlobal();
    if (outcome.ok || outcome.error === "duplicate_message") await this.armGlobal();
    return outcome;
  }

  /** Binding-only fan-out; only opted-in authorized sockets receive the identity-free packet. */
  async deliverGlobal(message: GlobalChatMessage): Promise<void> {
    if (!globalChatChannel(this.env) || !packetId.test(message.id) || message.expiresAt <= Date.now()) return;
    if (message.source === "minecraft" && (!message.sender || !await this.validSender(message.sender))) return;
    this.prune(Date.now());
    const sql = this.ctx.storage.sql, roomDelivery = `room:${message.id}`;
    if (sql.exec("SELECT 1 FROM global_chat_delivered WHERE id=?", roomDelivery).toArray().length) return;
    const base = { type: "irc_message", scope: "global", id: message.id, source: message.source, from: message.from, text: message.text, timestamp: message.timestamp,
      ...(message.reply ? { reply: { from: message.reply.from, text: message.reply.text } } : {}) };
    const normal = JSON.stringify({ ...base, mention: false }), mentioned = JSON.stringify({ ...base, mention: true });
    for (const peer of this.ctx.getWebSockets()) {
      if (peer.readyState !== WebSocket.OPEN) continue;
      const recipient = peer.deserializeAttachment() as IrcSession;
      if (recipient.bridge || !recipient.globalChatSubscribed || !await this.authorized(peer, recipient)) continue;
      const peerDelivery = `peer:${message.id}:${recipient.connectionId}`;
      if (sql.exec("SELECT 1 FROM global_chat_delivered WHERE id=?", peerDelivery).toArray().length) continue;
      const isMentioned = message.source === "discord" ? !!recipient.discordId && message.mentions.includes(recipient.discordId) :
        !!recipient.name && new RegExp(`(^|[^A-Za-z0-9_])@${recipient.name}(?=$|[^A-Za-z0-9_])`, "i").test(message.text);
      try {
        peer.send(isMentioned ? mentioned : normal);
        sql.exec("INSERT OR IGNORE INTO global_chat_delivered VALUES(?,?)", peerDelivery, Date.now() + 300_000);
      } catch { /* Live clients reconnect without replaying history. */ }
    }
    sql.exec("INSERT OR IGNORE INTO global_chat_delivered VALUES(?,?)", roomDelivery, Date.now() + 300_000);
    sql.exec("DELETE FROM global_chat_delivered WHERE rowid NOT IN (SELECT rowid FROM global_chat_delivered ORDER BY rowid DESC LIMIT 10000)");
  }

  private acknowledge(data: Record<string, unknown>): string | undefined {
    if (typeof data.id !== "string" || !packetId.test(data.id) || typeof data.message_id !== "string" || !snowflake.test(data.message_id)) return "invalid_ack";
    const sql = this.ctx.storage.sql;
    const existing = sql.exec<{ id: string }>("SELECT id FROM global_chat_receipts WHERE message_id=? AND expires>?", data.message_id, Date.now()).toArray()[0];
    if (existing) return existing.id === data.id ? undefined : "invalid_ack";
    const row = sql.exec<{ packet: string }>(`SELECT m.packet FROM global_chat_messages m JOIN global_chat_outbox o ON o.id=m.id
      WHERE m.id=? AND m.expires>? AND o.destination='discord' AND o.attempts>0`, data.id, Date.now()).toArray()[0];
    if (!row) return "unknown_delivery";
    const message = JSON.parse(row.packet) as GlobalChatMessage;
    sql.exec("INSERT INTO global_chat_receipts VALUES(?,?,?,?,?)", data.message_id, data.id, message.discordId, message.from, Date.now() + 86_400_000);
    sql.exec("DELETE FROM global_chat_receipts WHERE rowid NOT IN (SELECT rowid FROM global_chat_receipts ORDER BY rowid DESC LIMIT 10000)");
    sql.exec("DELETE FROM global_chat_outbox WHERE id=? AND destination='discord'", data.id);
    sql.exec("DELETE FROM global_chat_messages WHERE id NOT IN (SELECT id FROM global_chat_outbox)");
    return undefined;
  }

  async bridgeConnected(): Promise<void> {
    if (!this.coordinator()) return;
    this.ctx.storage.sql.exec("UPDATE global_chat_outbox SET next_at=MIN(next_at,?) WHERE destination='discord' AND attempts<5", Date.now());
    await this.flushGlobal(); await this.armGlobal();
  }

  async flushGlobal(): Promise<void> {
    if (!this.coordinator()) return;
    const now = Date.now(), sql = this.ctx.storage.sql;
    const bridge = this.ctx.getWebSockets().find(ws => ws.readyState === WebSocket.OPEN && (ws.deserializeAttachment() as IrcSession).bridge);
    const rows = this.ctx.storage.transactionSync(() => {
      this.prune(now);
      sql.exec("DELETE FROM global_chat_outbox WHERE attempts>=5 AND next_at<=?", now);
      sql.exec("DELETE FROM global_chat_messages WHERE id NOT IN (SELECT id FROM global_chat_outbox)");
      const pending = sql.exec<PendingDelivery>(`SELECT o.id,o.destination,o.attempts,m.packet,m.expires FROM global_chat_outbox o
        JOIN global_chat_messages m ON m.id=o.id WHERE o.next_at<=? AND o.attempts<5 ORDER BY o.next_at,o.id LIMIT 32`, now).toArray();
      for (const row of pending) {
        if (row.destination === "discord" && !bridge) sql.exec("UPDATE global_chat_outbox SET next_at=? WHERE id=? AND destination='discord'", now + 5000, row.id);
        else sql.exec("UPDATE global_chat_outbox SET attempts=attempts+1,next_at=? WHERE id=? AND destination=?", now + Math.min(40_000, 5000 * 2 ** row.attempts), row.id, row.destination);
      }
      return pending.filter(row => row.destination !== "discord" || bridge !== undefined);
    });
    // Persist retry work before external RPCs can interrupt this delivery turn.
    await this.armGlobal();
    await Promise.allSettled(rows.map(async row => {
      const message = JSON.parse(row.packet) as GlobalChatMessage;
      if (message.expiresAt <= Date.now()) return;
      if (message.source === "minecraft" && (!message.sender || !await this.validSender(message.sender))) {
        sql.exec("DELETE FROM global_chat_outbox WHERE id=?", row.id); sql.exec("DELETE FROM global_chat_messages WHERE id=?", row.id); return;
      }
      if (row.destination !== "discord") {
        await this.env.RELAY_ROOMS.getByName(row.destination.slice("room:".length)).globalChatDeliver(message);
        sql.exec("DELETE FROM global_chat_outbox WHERE id=? AND destination=?", row.id, row.destination);
      } else {
        if (bridge?.readyState === WebSocket.OPEN) bridge.send(JSON.stringify({ type: "irc_message", scope: "global", room: GLOBAL_CHAT_ROOM, source: "minecraft", id: message.id, message_id: message.id,
          from: message.from, text: message.text, timestamp: message.timestamp, discordId: message.discordId, uuid: message.uuid }));
      }
    }));
    sql.exec("DELETE FROM global_chat_messages WHERE id NOT IN (SELECT id FROM global_chat_outbox)");
  }

  nextGlobalAlarm(): number | null {
    const row = this.ctx.storage.sql.exec<{ next_at: number }>("SELECT MIN(o.next_at) AS next_at FROM global_chat_outbox o JOIN global_chat_messages m ON m.id=o.id").toArray()[0];
    return row?.next_at === null || row?.next_at === undefined ? null : Math.max(Date.now() + 100, row.next_at);
  }
  private async armGlobal(): Promise<void> {
    const next = this.nextGlobalAlarm(); if (next === null) return;
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }
}
