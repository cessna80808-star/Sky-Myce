import type { AccountStore } from "./accounts";

export type IrcConfig = {
  ACCOUNT_STORE: DurableObjectNamespace<AccountStore>;
  DISCORD_BRIDGE_SECRET?: string;
  DISCORD_IRC_CHANNEL_ID?: string;
  DISCORD_IRC_TEST_CHANNEL_ID?: string;
  IRC_BURST?: string;
  IRC_REFILL_SECONDS?: string;
  IRC_GLOBAL_BURST?: string;
  IRC_GLOBAL_REFILL_MS?: string;
  IRC_DEDUP_SIZE?: string;
  IRC_DEDUP_TTL_MS?: string;
};
export type IrcSession = {
  bridge?: boolean; room?: string; ircSubscribed?: boolean; accountId?: string; discordId?: string;
  uuid?: string; name?: string; installationId?: string; generation?: number; leaseExpiresAt?: number; buildId?: string; loaderVersion?: string; connectionId?: string;
};
const encoder = new TextEncoder();
const snowflake = /^\d{17,20}$/;
const packetId = /^[a-f0-9]{32}$/;
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

/** No chat bodies are stored. Only short-lived deduplication IDs and per-sender quotas survive eviction. */
export class RoomIrc {
  constructor(private ctx: DurableObjectState, private env: IrcConfig,
      private authorized: (ws: WebSocket, session: IrcSession) => Promise<boolean>) {
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_nonces (nonce TEXT PRIMARY KEY, expires INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_seen (id TEXT PRIMARY KEY, expires INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS irc_limits (actor TEXT PRIMARY KEY, credits REAL NOT NULL, updated INTEGER NOT NULL)");
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
}
