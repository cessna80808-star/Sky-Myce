import type { AccountStore } from "./accounts";
import type { RelayRoom } from "./index";
import { IrcLifecycle, IRC_MANAGEMENT_MS, ircMedia, ircMediaKey, ircMentionNames, ircOperation, ircPlainText, ircPreview, ircTextDigest,
  type IrcMedia, type IrcPublicSender, type IrcReply, type IrcRecord, type IrcOperation, type IrcUpdate } from "./irc-model";

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
  DISCORD_INTERACTIONS_ROOM?: string;
};
export type IrcSession = {
  bazaar?: import("./bazaar-notifications").BazaarSession;
  bridge?: boolean; room?: string; ircSubscribed?: boolean; globalChatSubscribed?: boolean; accountId?: string; discordId?: string;
  uuid?: string; name?: string; installationId?: string; communitySessionId?: string; generation?: number; leaseExpiresAt?: number; buildId?: string; loaderVersion?: string; connectionId?: string;
  richChatVersion?: number;
  partySubscribed?: boolean; partyId?: string; partySubscribeRevision?: number; partyConsentExpiresAt?: number;
};
type SubscriptionIntent = { field: "ircSubscribed" | "globalChatSubscribed" | "partySubscribed"; token: symbol; revision?: number };
const identityFields = ["bridge", "room", "accountId", "discordId", "uuid", "name", "installationId", "communitySessionId", "generation",
  "leaseExpiresAt", "buildId", "loaderVersion", "connectionId"] as const;

/** External authorization may yield to other feature messages. Keep their current attachment fields. */
export function currentIrcSession<T extends IrcSession>(ws: WebSocket, checked: T): T | undefined {
  if (ws.readyState !== WebSocket.OPEN) return;
  const current = ws.deserializeAttachment() as T;
  if (!current || identityFields.some(field => current[field] !== checked[field])) return;
  if (checked.accountId && (!Number.isSafeInteger(current.leaseExpiresAt) || current.leaseExpiresAt! <= Date.now())) {
    ws.close(4006, "Authorization renewal required"); return;
  }
  return current;
}
const encoder = new TextEncoder();
const snowflake = /^\d{17,20}$/;
const packetId = /^[a-f0-9]{32}$/;
export const GLOBAL_CHAT_ROOM = "global-chat";
export const PARTY_CHAT_ROOM = "party-chat";
export const PARTY_CHAT_UNAVAILABLE = "party_membership_unverified";
export const globalChatChannel = (env: IrcConfig): string | undefined => {
  const channel = env.GLOBAL_CHAT_CHANNEL_ID ?? env.DISCORD_IRC_CHANNEL_ID;
  return channel && snowflake.test(channel) ? channel : undefined;
};
export type GlobalChatSender = Pick<IrcSession, "accountId" | "discordId" | "uuid" | "name" | "installationId" | "communitySessionId" | "generation" | "leaseExpiresAt" | "buildId" | "loaderVersion" | "connectionId">;
export type GlobalChatSend = { id: string; text: string; replyTo?: string };
export type GlobalChatResult = { ok: boolean; error?: string; id?: string; room?: string; expiresAt?: number };
export type PartyChatIdentity = GlobalChatSender & { room: string; revision: number };
export type PartyChatMessage = GlobalChatMessage & { room: string; scope: "party" };
type GlobalReply = IrcReply & { message_id?: string };
export type GlobalChatMessage = {
  id: string; source: "minecraft" | "discord"; from: string; text: string; timestamp: number; expiresAt: number;
  discordId: string; uuid?: string; mentions: string[]; reply?: GlobalReply; sender?: GlobalChatSender;
  sourceId?: string; media?: IrcMedia[]; identity?: IrcPublicSender; revision?: number;
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
  // Awaited handlers keep this instance active; restarts cancel them. Persist subscriptions, not in-flight ordering.
  private readonly subscriptionIntents = new WeakMap<WebSocket, Partial<Record<SubscriptionIntent["field"], symbol>>>();
  private readonly lifecycle: IrcLifecycle;
  constructor(private ctx: DurableObjectState, private env: IrcConfig,
      private authorized: (ws: WebSocket, session: IrcSession) => Promise<boolean>, private cosmeticName?: (uuid: string) => string | null) {
    this.lifecycle = new IrcLifecycle(ctx.storage);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS party_chat_consents (uuid TEXT NOT NULL, origin_room TEXT NOT NULL, connection_id TEXT NOT NULL,
      party_id TEXT NOT NULL, revision INTEGER NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(uuid,origin_room,connection_id))`);
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
  /** Record ordering before the caller's authorization RPC, without granting any subscription. */
  reserveSubscription(ws: WebSocket, session: IrcSession, data: Record<string, unknown>): SubscriptionIntent | undefined {
    if (session.bridge || !session.accountId || data.type !== "irc_subscribe" || typeof data.enabled !== "boolean" ||
        typeof data.id !== "string" || !packetId.test(data.id) || data.scope !== undefined && data.scope !== "global" && data.scope !== "room" && !(data.scope === "party" && data.richChatVersion === 1)) return;
    const intent: SubscriptionIntent = { field: data.scope === "global" ? "globalChatSubscribed" : data.scope === "party" ? "partySubscribed" : "ircSubscribed", token: Symbol() };
    if (intent.field === "partySubscribed") {
      const current = currentIrcSession(ws, session);
      if (!current) return;
      intent.revision = (current.partySubscribeRevision ?? 0) + 1;
      current.partySubscribeRevision = intent.revision;
      // Revoke first: an in-flight roster change can never retain its old audience.
      current.partySubscribed = false; delete current.partyId; delete current.partyConsentExpiresAt;
      ws.serializeAttachment(current);
    }
    const pending = this.subscriptionIntents.get(ws) ?? {};
    pending[intent.field] = intent.token;
    this.subscriptionIntents.set(ws, pending);
    return intent;
  }

  private async subscribe(ws: WebSocket, session: IrcSession, data: Record<string, unknown>, intent: SubscriptionIntent | undefined,
      field: "ircSubscribed" | "globalChatSubscribed", result: (error?: string) => void): Promise<void> {
    if (typeof data.enabled !== "boolean") { result("invalid_subscription"); return; }
    if (data.enabled && (!session.discordId || !(await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(session.discordId)).ok)) { result("not_authorized"); return; }
    const current = currentIrcSession(ws, session);
    if (!current) return;
    const pending = this.subscriptionIntents.get(ws);
    if (!intent || pending?.[intent.field] !== intent.token) { result("subscription_superseded"); return; }
    current[field] = data.enabled;
    if (data.richChatVersion === 1) current.richChatVersion = 1;
    delete pending[intent.field];
    ws.serializeAttachment(current); result();
  }

  async message(ws: WebSocket, session: IrcSession, data: Record<string, unknown>, intent?: SubscriptionIntent): Promise<void> {
    if (session.bridge && data.type === "bridge_capabilities") {
      if (typeof data.id !== "string" || !packetId.test(data.id) || data.richChatVersion !== 1) return;
      session.richChatVersion = 1; ws.serializeAttachment(session);
      ws.send(JSON.stringify({ type: "irc_result", id: data.id, ...(session.room === GLOBAL_CHAT_ROOM ? { scope: "global" } : { scope: "room" }), room: session.room }));
      await this.flushOperations(); return;
    }
    if (!session.bridge && data.scope === "party" && (data.type === "irc_subscribe" && data.richChatVersion === 1 || session.richChatVersion === 1)) {
      await this.partyMessage(ws, session, data, intent); return;
    }
    // Client-observed Hypixel rosters have no backend-verifiable membership proof.
    if (data.scope !== undefined && data.scope !== "global" && data.scope !== "room" || data.scope === "room" && session.room === GLOBAL_CHAT_ROOM) {
      if (typeof data.id !== "string" || !(session.bridge ? snowflake : packetId).test(data.id)) {
        ws.close(1008, "Invalid IRC packet"); return;
      }
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", id: data.id,
        ...(data.scope === "party" ? { scope: "party", error: PARTY_CHAT_UNAVAILABLE } : { error: "invalid_scope" }) }));
      return;
    }
    if (data.scope === "global" || session.bridge && session.room === GLOBAL_CHAT_ROOM) {
      await this.globalMessage(ws, session, data, intent); return;
    }
    const result = (error?: string) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", id: data.id,
      ...(data.scope === "room" ? { scope: "room", room: session.room } : {}), ...(error ? { error } : {}) })); };
    if (data.room !== undefined && data.room !== session.room) { result("invalid_route"); return; }
    if (data.type === "irc_operation" && !session.bridge || data.type === "discord_operation" && session.bridge) {
      const outcome = await this.operation(session, data, "room"); result(outcome.error); return;
    }
    if (session.bridge && data.type === "discord_operation_ack") { result(this.acknowledgeOperation(data, session.room!)); return; }
    if (session.bridge && data.type === "discord_operation_result") { if (data.channelId === this.roomChannel(session.room!)) this.failedOperation(data); return; }
    if (session.bridge && data.type === "discord_ack") {
      const record = typeof data.id === "string" ? this.lifecycle.get(data.id) : undefined;
      if (!record || record.source !== "minecraft" || data.channelId !== this.roomChannel(session.room!) || typeof data.message_id !== "string" || !snowflake.test(data.message_id)) { result("invalid_ack"); return; }
      this.lifecycle.receipt(record.id, data.message_id); result(); await this.flushOperations(); return;
    }
    if (typeof data.id !== "string" || !(session.bridge ? snowflake : packetId).test(data.id)) { ws.close(1008, "Invalid IRC packet"); return; }
    if (session.bridge && (data.room !== session.room || data.source !== "discord" || data.message_id !== data.id)) { result("invalid_route"); return; }
    if (!session.bridge && data.type === "irc_subscribe") {
      await this.subscribe(ws, session, data, intent, "ircSubscribed", result); return;
    }
    if (data.type !== (session.bridge ? "discord_message" : "irc_send") || !(session.bridge && session.richChatVersion === 1 ? ircPlainText(data.text, 4096) : validIrcText(data.text))) { result("invalid_message"); return; }
    if (!session.bridge && !session.ircSubscribed) { result("not_subscribed"); return; }
    const discordId = session.bridge ? data.discordId : session.discordId;
    if (typeof discordId !== "string" || !snowflake.test(discordId)) { result("not_authorized"); return; }
    const account = await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(discordId);
    if (!account.ok) { result("not_authorized"); return; }
    if (!session.bridge && !currentIrcSession(ws, session)?.ircSubscribed) return;
    const media = ircMedia(data.text as string, session.bridge ? data.media : undefined);
    if (!media || data.replyTo !== undefined && (typeof data.replyTo !== "string" || !packetId.test(data.replyTo))) { result("invalid_media"); return; }
    const error = this.accept(`${session.bridge ? "dc" : "mc"}:${discordId}`, data.id);
    if (error) { result(error); return; }
    // The bot's signed transport carries the Gateway identity. Minecraft names/UUIDs come from verifyAccount.
    const from = session.bridge ? (typeof data.from === "string" ? data.from.replace(/[\x00-\x1f\x7f\u00a7]/g, "").slice(0, 64) : "Discord user") : session.name;
    const messageId = crypto.randomUUID().replaceAll("-", "");
    const identity = await this.publicSender(session.bridge ? { name: displayName(from), discordId } : session);
    const bodyDigest = await ircTextDigest(data.text as string);
    if (!session.bridge && (!await this.authorized(ws, session) || !currentIrcSession(ws, session)?.ircSubscribed)) return;
    const reply = session.bridge ? this.discordReply(data.reply) : data.replyTo ? this.lifecycle.reply(data.replyTo as string) : undefined;
    const timestamp = Date.now();
    this.lifecycle.remember({ id: messageId, scope: "room", room: session.room!, source: session.bridge ? "discord" : "minecraft",
      sourceId: data.id, from: displayName(from), discordId, ...(session.uuid ? { uuid: session.uuid } : {}), sender: identity,
      preview: ircPreview(data.text as string), bodyDigest, media, ...(reply ? { reply } : {}), revision: 0, timestamp, expiresAt: timestamp + IRC_MANAGEMENT_MS,
      ...(session.bridge ? { discordMessageId: data.id } : {}) });
    const base = { type: "irc_message", id: messageId, message_id: messageId, room: session.room,
      source: session.bridge ? "discord" : "minecraft", from, text: data.text,
      ...(session.bridge ? { discordId } : { uuid: session.uuid }) };
    const packet = JSON.stringify(base);
    const rich = { ...base, scope: "room", model: 1, sourceId: data.id, timestamp, revision: 0, media, sender: identity,
      reactions: [], ...(reply ? { reply } : {}) };
    for (const peer of this.ctx.getWebSockets()) {
      if (peer.readyState !== WebSocket.OPEN) continue;
      const recipient = peer.deserializeAttachment() as IrcSession;
      if (recipient.bridge ? session.bridge : !recipient.ircSubscribed) continue;
      if (!recipient.bridge && !await this.authorized(peer, recipient)) continue;
      const current = currentIrcSession(peer, recipient);
      if (!current || !current.bridge && !current.ircSubscribed) continue;
      const mention = session.bridge ? Array.isArray(data.mentions) && !!current.discordId && data.mentions.includes(current.discordId) :
        !!current.name && ircMentionNames(data.text as string).includes(current.name.toLowerCase());
      try { peer.send(current.bridge || current.richChatVersion === 1 ? JSON.stringify({ ...rich, mention }) : packet); }
      catch { /* Live relay only: reconnect restores subscriptions, never message history. */ }
    }
    result();
    await this.armGlobal();
  }

  private coordinator(): boolean { return this.ctx.id.equals(this.env.RELAY_ROOMS.idFromName(GLOBAL_CHAT_ROOM)); }
  private partyCoordinator(): boolean { return this.ctx.id.equals(this.env.RELAY_ROOMS.idFromName(PARTY_CHAT_ROOM)); }
  private roomChannel(room: string): string | undefined {
    return room === GLOBAL_CHAT_ROOM ? globalChatChannel(this.env) : room === "testing" ? this.env.DISCORD_IRC_TEST_CHANNEL_ID : room === "friends" ? this.env.DISCORD_IRC_CHANNEL_ID : undefined;
  }
  private sender(session: IrcSession): GlobalChatSender {
    return { accountId: session.accountId, discordId: session.discordId, uuid: session.uuid, name: session.name,
      installationId: session.installationId, communitySessionId: session.communitySessionId, generation: session.generation, leaseExpiresAt: session.leaseExpiresAt, buildId: session.buildId, loaderVersion: session.loaderVersion, connectionId: session.connectionId };
  }
  private async publicSender(sender: Pick<IrcSession, "uuid" | "name" | "discordId">): Promise<IrcPublicSender> {
    const identity: IrcPublicSender = { name: displayName(sender.name), discordId: sender.discordId! };
    if (sender.uuid && packetId.test(sender.uuid)) {
      identity.uuid = sender.uuid;
      identity.avatarUrl = `https://mc-heads.net/avatar/${sender.uuid}/64`;
      try {
        const name = this.coordinator() || this.partyCoordinator() ? await this.env.RELAY_ROOMS.getByName(this.env.DISCORD_INTERACTIONS_ROOM ?? "friends").ircCosmeticName(sender.uuid) : this.cosmeticName?.(sender.uuid);
        if (name) identity.cosmeticName = ircPreview(name, 64);
      } catch { /* A real account identity remains usable if optional cosmetic lookup is unavailable. */ }
    }
    return identity;
  }
  private discordReply(candidate: unknown): IrcReply | undefined {
    if (!candidate || typeof candidate !== "object" || Array.isArray(candidate)) return;
    const reply = candidate as Record<string, unknown>;
    if (typeof reply.message_id !== "string" || !snowflake.test(reply.message_id)) return;
    const record = this.lifecycle.fromDiscord(reply.message_id);
    return record ? this.lifecycle.reply(record.id) : { discordMessageId: reply.message_id, from: displayName(reply.from), text: typeof reply.text === "string" ? ircPreview(reply.text) : "Unavailable message" };
  }
  private rooms(): string[] { return [...new Set(this.env.ROOMS.split(",").filter(room => /^[a-z0-9_-]{1,32}$/.test(room) && room !== GLOBAL_CHAT_ROOM && room !== PARTY_CHAT_ROOM))].slice(0, 64); }
  private textLimit(discord: boolean): number { return Math.floor(discord ? bounded(this.env.GLOBAL_CHAT_DISCORD_MAX_LENGTH, 4096, 1, 4096) : bounded(this.env.GLOBAL_CHAT_MC_MAX_LENGTH, 1024, 1, 1024)); }

  private async validSender(sender: GlobalChatSender): Promise<boolean> {
    if (!sender.accountId || !sender.communitySessionId || !sender.uuid || !packetId.test(sender.uuid) ||
        !sender.name || !/^[A-Za-z0-9_]{1,16}$/.test(sender.name) || !sender.discordId || !snowflake.test(sender.discordId) ||
        !Number.isSafeInteger(sender.generation) ||
        !Number.isSafeInteger(sender.leaseExpiresAt) || sender.leaseExpiresAt! <= Date.now()) return false;
    const state = await this.env.ACCOUNT_STORE.getByName("global").checkCommunitySession({ accountId: sender.accountId,
      uuid: sender.uuid, sessionId: sender.communitySessionId, generation: sender.generation! });
    return state.ok && state.discordId === sender.discordId && sender.leaseExpiresAt! > Date.now();
  }

  private partyIdentity(session: IrcSession): PartyChatIdentity {
    return { ...this.sender(session), room: session.room!, revision: session.partySubscribeRevision ?? 0 };
  }
  private async partyMessage(ws: WebSocket, session: IrcSession, data: Record<string, unknown>, intent?: SubscriptionIntent): Promise<void> {
    const result = (outcome: GlobalChatResult) => { const room = outcome.room ?? (data.type !== "irc_subscribe" ? session.partyId : undefined);
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({
      type: "irc_result", scope: "party", id: data.id, ...(outcome.error ? { error: outcome.error } : {}),
      ...(room ? { room, ...(outcome.expiresAt ? { expiresAt: outcome.expiresAt } : {}) } : {}) })); };
    if (typeof data.id !== "string" || !packetId.test(data.id)) { ws.close(1008, "Invalid party chat packet"); return; }
    const coordinator = this.env.RELAY_ROOMS.getByName(PARTY_CHAT_ROOM);
    if (data.type === "irc_subscribe") {
      const pending = this.subscriptionIntents.get(ws);
      if (!intent || pending?.partySubscribed !== intent.token || typeof data.enabled !== "boolean") { result({ ok: false, error: "subscription_superseded" }); return; }
      const identity = { ...this.partyIdentity(session), revision: intent.revision! };
      const outcome = await coordinator.partyChatRegister(identity, data);
      const current = currentIrcSession(ws, session);
      if (!current || pending.partySubscribed !== intent.token || current.partySubscribeRevision !== intent.revision) return;
      delete pending.partySubscribed;
      current.richChatVersion = 1;
      current.partySubscribed = data.enabled && outcome.ok;
      if (current.partySubscribed) { current.partyId = outcome.room; current.partyConsentExpiresAt = outcome.expiresAt; }
      ws.serializeAttachment(current); result(outcome); return;
    }
    if (!session.partySubscribed || !session.partyId || (session.partyConsentExpiresAt ?? 0) <= Date.now()) { result({ ok: false, error: "not_subscribed" }); return; }
    if (data.room !== session.partyId) { result({ ok: false, error: "invalid_route" }); return; }
    if (data.type === "irc_send") {
      if (!validGlobalText(data.text, 1024) || data.replyTo !== undefined && (typeof data.replyTo !== "string" || !packetId.test(data.replyTo))) { result({ ok: false, error: "invalid_message" }); return; }
      result(await coordinator.partyChatMinecraft(this.partyIdentity(session), session.partyId, { id: data.id, text: data.text,
        ...(data.replyTo ? { replyTo: data.replyTo as string } : {}) }));
    } else if (data.type === "irc_operation") result(await coordinator.partyChatOperation(this.partyIdentity(session), session.partyId, data));
    else result({ ok: false, error: "invalid_message" });
  }
  /** Each authenticated participant independently consents to exactly the same UUID roster and leader. */
  async partyRegister(sender: PartyChatIdentity, input: Record<string, unknown>): Promise<GlobalChatResult> {
    if (!this.partyCoordinator() || !this.rooms().includes(sender.room) || !sender.connectionId || sender.connectionId.length > 80 ||
        !Number.isSafeInteger(sender.revision) || sender.revision < 1 || typeof input.enabled !== "boolean" || !await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    let partyId = "";
    if (input.enabled) {
      if (!Array.isArray(input.members) || input.members.length < 2 || input.members.length > 20 ||
          input.members.some(value => typeof value !== "string" || !packetId.test(value)) || new Set(input.members).size !== input.members.length ||
          typeof input.leader !== "string" || !input.members.includes(input.leader) || !input.members.includes(sender.uuid) ||
          !(await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(sender.discordId!)).ok) return { ok: false, error: PARTY_CHAT_UNAVAILABLE };
      partyId = (await ircTextDigest(`sky-myce-party-v1\n${input.leader}\n${[...input.members].sort().join("\n")}`)).slice(0, 32);
    }
    if (!await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    const expiresAt = Math.min(sender.leaseExpiresAt!, Date.now() + 60_000), sql = this.ctx.storage.sql;
    const outcome = this.ctx.storage.transactionSync(() => {
      sql.exec("DELETE FROM party_chat_consents WHERE expires<=?", Date.now());
      const previous = sql.exec<{ revision: number; party_id: string }>("SELECT revision,party_id FROM party_chat_consents WHERE uuid=? AND origin_room=? AND connection_id=?", sender.uuid!, sender.room, sender.connectionId!).toArray()[0];
      if (previous && (previous.revision > sender.revision || previous.revision === sender.revision && previous.party_id !== partyId)) return { ok: false, error: "subscription_superseded" };
      if (!previous && sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM party_chat_consents").one().count >= 4096) return { ok: false, error: "temporarily_unavailable" };
      sql.exec("INSERT INTO party_chat_consents VALUES(?,?,?,?,?,?) ON CONFLICT(uuid,origin_room,connection_id) DO UPDATE SET party_id=excluded.party_id,revision=excluded.revision,expires=excluded.expires", sender.uuid!, sender.room, sender.connectionId!, partyId, sender.revision, expiresAt);
      return { ok: true, ...(partyId ? { room: partyId, expiresAt } : {}) };
    });
    await this.armGlobal(); return outcome;
  }
  private async partyAuthorized(sender: PartyChatIdentity, partyId: string): Promise<boolean> {
    if (!this.partyCoordinator() || !packetId.test(partyId) || !sender.uuid || !sender.connectionId || !this.rooms().includes(sender.room) ||
        !await this.validSender(sender)) return false;
    return this.ctx.storage.sql.exec("SELECT 1 FROM party_chat_consents WHERE uuid=? AND origin_room=? AND connection_id=? AND party_id=? AND revision=? AND expires>?",
      sender.uuid, sender.room, sender.connectionId, partyId, sender.revision, Date.now()).toArray().length === 1;
  }
  async partyMinecraft(sender: PartyChatIdentity, partyId: string, input: GlobalChatSend): Promise<GlobalChatResult> {
    if (!packetId.test(input.id) || !validGlobalText(input.text, 1024) || input.replyTo !== undefined && !packetId.test(input.replyTo)) return { ok: false, error: "invalid_message" };
    if (!await this.partyAuthorized(sender, partyId)) return { ok: false, error: "not_authorized" };
    const identity = await this.publicSender(sender), bodyDigest = await ircTextDigest(input.text);
    if (!await this.partyAuthorized(sender, partyId)) return { ok: false, error: "not_authorized" };
    const error = this.accept(`party:${sender.uuid}`, input.id); if (error) return { ok: false, error };
    const now = Date.now(), id = crypto.randomUUID().replaceAll("-", ""), target = input.replyTo ? this.lifecycle.get(input.replyTo) : undefined;
    const reply = input.replyTo ? target?.scope === "party" && target.room === partyId ? this.lifecycle.reply(input.replyTo) : { from: "Unavailable message", text: "" } : undefined;
    const message: PartyChatMessage = { id, scope: "party", room: partyId, source: "minecraft", sourceId: input.id, from: sender.name!, text: input.text,
      timestamp: now, expiresAt: Math.min(sender.leaseExpiresAt!, now + 10_000), discordId: sender.discordId!, uuid: sender.uuid,
      mentions: [], sender, identity, media: ircMedia(input.text)!, revision: 0, ...(reply ? { reply } : {}) };
    this.lifecycle.remember({ id, scope: "party", room: partyId, source: "minecraft", sourceId: input.id, from: sender.name!, discordId: sender.discordId!,
      uuid: sender.uuid, sender: identity, preview: ircPreview(input.text), bodyDigest, media: message.media!, revision: 0,
      timestamp: now, expiresAt: now + IRC_MANAGEMENT_MS, ...(reply ? { reply } : {}) });
    await this.armGlobal();
    await Promise.allSettled(this.rooms().map(room => this.env.RELAY_ROOMS.getByName(room).partyChatDeliver(message)));
    return { ok: true, id };
  }
  async partyOperation(sender: PartyChatIdentity, partyId: string, data: Record<string, unknown>): Promise<GlobalChatResult> {
    if (data.scope !== "party" || data.room !== partyId || !await this.partyAuthorized(sender, partyId)) return { ok: false, error: "not_authorized" };
    const record = typeof data.messageId === "string" ? this.lifecycle.get(data.messageId) : undefined;
    if (!record || record.scope !== "party" || record.room !== partyId) return { ok: false, error: "message_expired" };
    const input = ircOperation(data, 1024); if (!input) return { ok: false, error: "invalid_operation" };
    if (input.operation === "edit") input.bodyDigest = await ircTextDigest(input.text!);
    if (!await this.partyAuthorized(sender, partyId)) return { ok: false, error: "not_authorized" };
    // Party mutations are live Minecraft fan-out; they never enter the Discord outbox.
    const outcome = this.lifecycle.operate(`mc:${sender.uuid}`, input, { native: false, uuid: sender.uuid, discordId: sender.discordId });
    if (outcome.error) return { ok: false, error: outcome.error };
    if (outcome.update) await Promise.allSettled(this.rooms().map(room => this.env.RELAY_ROOMS.getByName(room).partyChatUpdate(outcome.update!, this.mineActors(record.id))));
    await this.armGlobal(); return { ok: true, id: input.id };
  }
  async deliverParty(message: PartyChatMessage): Promise<void> {
    if (message.scope !== "party" || message.source !== "minecraft" || !packetId.test(message.room) || !packetId.test(message.id) || message.expiresAt <= Date.now()) return;
    for (const peer of this.ctx.getWebSockets()) {
      const recipient = peer.deserializeAttachment() as IrcSession;
      if (peer.readyState !== WebSocket.OPEN || recipient.bridge || recipient.richChatVersion !== 1 || !recipient.partySubscribed ||
          recipient.partyId !== message.room || (recipient.partyConsentExpiresAt ?? 0) <= Date.now() || !await this.authorized(peer, recipient)) continue;
      const current = currentIrcSession(peer, recipient);
      if (!current?.partySubscribed || current.partyId !== message.room || (current.partyConsentExpiresAt ?? 0) <= Date.now() || message.expiresAt <= Date.now()) continue;
      try { peer.send(JSON.stringify({ type: "irc_message", model: 1, scope: "party", room: message.room, id: message.id,
        source: "minecraft", sourceId: message.sourceId, from: message.from, text: message.text, sender: message.identity,
        timestamp: message.timestamp, media: message.media ?? [], revision: 0, reactions: [], mention: !!current.name &&
        ircMentionNames(message.text).includes(current.name.toLowerCase()), ...(message.reply ? { reply: message.reply } : {}) })); }
      catch { /* Party chat is live-only. */ }
    }
  }

  /** Binding-only coordinator RPC; the original authenticated socket supplies this identity. */
  async globalMinecraft(sender: GlobalChatSender, input: GlobalChatSend): Promise<GlobalChatResult> {
    if (!this.coordinator() || !globalChatChannel(this.env)) return { ok: false, error: "not_configured" };
    if (!packetId.test(input.id) || !validGlobalText(input.text, this.textLimit(false)) || input.replyTo !== undefined && !packetId.test(input.replyTo)) return { ok: false, error: "invalid_message" };
    if (!await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    const identity = await this.publicSender(sender);
    const mentions = await this.env.ACCOUNT_STORE.getByName("global").resolveIrcMentions(input.text);
    if (!await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    return this.enqueue(`mc:${sender.accountId}`, input.id, { source: "minecraft", from: sender.name!, text: input.text,
      discordId: sender.discordId!, uuid: sender.uuid, mentions, sender, identity, media: ircMedia(input.text)!,
      ...(input.replyTo ? { reply: this.lifecycle.reply(input.replyTo) } : {}) });
  }

  private async globalMessage(ws: WebSocket, session: IrcSession, data: Record<string, unknown>, intent?: SubscriptionIntent): Promise<void> {
    const result = (error?: string) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", scope: "global", id: data.id,
      ...(data.part === "embeds" ? { part: "embeds" } : {}), ...(error ? { error } : {}) })); };
    if (!globalChatChannel(this.env)) { result("not_configured"); return; }
    if (session.bridge) {
      if (!this.coordinator() || session.room !== GLOBAL_CHAT_ROOM || data.scope !== "global" || data.room !== GLOBAL_CHAT_ROOM || data.channelId !== globalChatChannel(this.env)) { result("invalid_route"); return; }
      if (data.type === "discord_ack") { result(this.acknowledge(data)); await this.flushOperations(); return; }
      if (data.type === "discord_operation_ack") { result(this.acknowledgeOperation(data, GLOBAL_CHAT_ROOM)); return; }
      if (data.type === "discord_operation_result") { this.failedOperation(data); return; }
      if (data.type === "discord_operation") { const outcome = await this.operation(session, data, "global"); result(outcome.error); return; }
      if (data.type !== "discord_message" || data.source !== "discord" || typeof data.id !== "string" || !snowflake.test(data.id) || data.message_id !== data.id ||
          typeof data.discordId !== "string" || !snowflake.test(data.discordId) || !validGlobalText(data.text, this.textLimit(true))) { result("invalid_message"); return; }
      if (data.part !== undefined && data.part !== "embeds") { result("invalid_part"); return; }
      const media = ircMedia(data.text, data.media);
      if (!media) { result("invalid_media"); return; }
      const continuation = data.part === "embeds";
      if (data.replacement !== undefined && (!continuation || data.replacement !== true)) { result("invalid_part"); return; }
      if (data.mentions !== undefined && (!Array.isArray(data.mentions) || data.mentions.length > 100 || data.mentions.some(id => typeof id !== "string" || !snowflake.test(id)))) { result("invalid_mentions"); return; }
      if (continuation && (data.reply !== undefined || Array.isArray(data.mentions) && data.mentions.length > 0)) { result("invalid_part"); return; }
      if (continuation) {
        // Older Gateways sent only the delayed embed fragment. Enrich its media without replacing
        // the original body with a truncated reply preview. Current Gateways send the full snapshot.
        const record = this.lifecycle.fromDiscord(data.id);
        if (!record || record.source !== "discord" || record.scope !== "global" || record.room !== GLOBAL_CHAT_ROOM || record.discordId !== data.discordId) { result("unknown_message"); return; }
        const operationId = (await ircTextDigest(`${data.id}\n${data.replacement ? data.text : "media"}\n${JSON.stringify(media)}`)).slice(0, 32);
        if (data.replacement === true) {
          const outcome = await this.operation({ ...session, richChatVersion: 1 }, { ...data, type: "discord_operation", id: operationId,
            operation: "edit", message_id: data.id, media }, "global");
          result(outcome.error);
        } else {
          // The digest yields to other websocket events. Merge from current metadata, not
          // the snapshot captured before a full edit or deletion could have committed.
          const latest = this.lifecycle.get(record.id);
          if (!latest || latest.deleted) { result(latest ? "message_deleted" : "unknown_message"); return; }
          if (latest.bodyRevision !== undefined || latest.editedAt !== undefined) { result(); return; }
          const merged = new Map(latest.media.map(item => [ircMediaKey(item.url), item]));
          for (const item of media) {
            for (const [key, previous] of merged) if (previous.id === item.id) merged.delete(key);
            merged.set(ircMediaKey(item.url), item);
          }
          const outcome = this.lifecycle.operate(`dc:${data.discordId}`, { id: operationId, messageId: record.id, operation: "edit",
            media: [...merged.values()].slice(0, 4), mediaOnly: true }, { native: true, discordId: data.discordId });
          if (outcome.update && outcome.record) this.refreshQueuedGlobal(outcome.record, outcome.update);
          if (outcome.update) await Promise.allSettled(this.rooms().map(room => this.env.RELAY_ROOMS.getByName(room).globalChatUpdate(outcome.update!, this.mineActors(record.id))));
          result(outcome.error);
        }
        return;
      }
      let reply: GlobalReply | undefined;
      if (data.reply !== undefined) {
        if (!data.reply || typeof data.reply !== "object" || Array.isArray(data.reply)) { result("invalid_reply"); return; }
        const candidate = data.reply as Record<string, unknown>;
        if (typeof candidate.message_id !== "string" || !snowflake.test(candidate.message_id) || typeof candidate.from !== "string" || candidate.from.length > 64 ||
            candidate.text !== undefined && (typeof candidate.text !== "string" || candidate.text.length > 256 || !candidate.text.isWellFormed() || unsafeText.test(candidate.text))) { result("invalid_reply"); return; }
        const receipt = this.ctx.storage.sql.exec<{ from_name: string }>("SELECT from_name FROM global_chat_receipts WHERE message_id=? AND expires>?", candidate.message_id, Date.now()).toArray()[0];
        const canonical = this.lifecycle.fromDiscord(candidate.message_id);
        reply = canonical ? { ...this.lifecycle.reply(canonical.id), message_id: candidate.message_id }
          : { ...this.discordReply(candidate)!, message_id: candidate.message_id, from: receipt?.from_name ?? displayName(candidate.from), text: typeof candidate.text === "string" ? candidate.text : "" };
      }
      const outcome = await this.enqueue(`dc:${data.discordId}`, data.id, { source: "discord", from: displayName(data.from), text: data.text,
        discordId: data.discordId, identity: { name: displayName(data.from), discordId: data.discordId }, media,
        mentions: [...new Set((data.mentions as string[] | undefined) ?? [])], ...(reply ? { reply } : {}) });
      result(outcome.error); return;
    }
    if (typeof data.id !== "string" || !packetId.test(data.id)) { ws.close(1008, "Invalid global chat packet"); return; }
    if (data.type === "irc_subscribe") {
      await this.subscribe(ws, session, data, intent, "globalChatSubscribed", result); return;
    }
    if (data.type === "irc_operation") {
      if (!session.globalChatSubscribed || session.richChatVersion !== 1) { result("not_subscribed"); return; }
      const outcome = await this.env.RELAY_ROOMS.getByName(GLOBAL_CHAT_ROOM).globalChatOperation(this.sender(session), data);
      result(outcome.error); return;
    }
    if (data.type !== "irc_send" || !validGlobalText(data.text, this.textLimit(false))) { result("invalid_message"); return; }
    if (!session.globalChatSubscribed) { result("not_subscribed"); return; }
    if (data.replyTo !== undefined && (typeof data.replyTo !== "string" || !packetId.test(data.replyTo))) { result("invalid_reply"); return; }
    const outcome = await this.env.RELAY_ROOMS.getByName(GLOBAL_CHAT_ROOM).globalChatMinecraft(this.sender(session), { id: data.id, text: data.text, ...(data.replyTo ? { replyTo: data.replyTo as string } : {}) });
    result(outcome.error);
  }

  private prune(now: number): void {
    this.lifecycle.prune(now);
    const sql = this.ctx.storage.sql;
    sql.exec("DELETE FROM party_chat_consents WHERE expires<=?", now);
    sql.exec("DELETE FROM global_chat_messages WHERE expires<=?", now);
    sql.exec("DELETE FROM global_chat_outbox WHERE id NOT IN (SELECT id FROM global_chat_messages)");
    for (const table of ["global_chat_seen", "global_chat_repeats", "global_chat_delivered", "global_chat_receipts"]) sql.exec(`DELETE FROM ${table} WHERE expires<=?`, now);
    sql.exec("DELETE FROM global_chat_limits WHERE updated<?", now - 3_600_000);
  }

  private async enqueue(actor: string, sourceId: string, input: Omit<GlobalChatMessage, "id" | "timestamp" | "expiresAt">): Promise<GlobalChatResult> {
    const bodyDigest = await ircTextDigest(input.text);
    const digest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(input.text.trim().replace(/\s+/g, " "))))].map(byte => byte.toString(16).padStart(2, "0")).join("");
    const now = Date.now(), sql = this.ctx.storage.sql;
    const outcome = this.ctx.storage.transactionSync((): GlobalChatResult => {
      this.prune(now);
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
      if (available < 1 || credits < 1 || previous && now - previous.last_sent < bounded(this.env.GLOBAL_CHAT_MIN_INTERVAL_MS, 250, 0, 60_000)) return { ok: false, error: "rate_limited" };
      sql.exec("INSERT INTO global_chat_limits VALUES(?,?,?,?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated,last_sent=excluded.last_sent", actor, credits - 1, now, now);
      sql.exec("INSERT INTO global_chat_limits VALUES('global',?,?,?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated,last_sent=excluded.last_sent", available - 1, now, now);
      const id = crypto.randomUUID().replaceAll("-", ""), expiresAt = now + bounded(this.env.GLOBAL_CHAT_OUTBOX_TTL_MS, 120_000, 1000, 120_000);
      const packet: GlobalChatMessage = { ...input, id, sourceId, timestamp: now, expiresAt, revision: 0 };
      this.lifecycle.remember({ id, scope: "global", room: GLOBAL_CHAT_ROOM, source: input.source, sourceId,
        from: input.from, discordId: input.discordId, ...(input.uuid ? { uuid: input.uuid } : {}),
        sender: input.identity ?? { name: input.from, discordId: input.discordId }, preview: ircPreview(input.text), bodyDigest, media: input.media ?? [],
        ...(input.reply ? { reply: input.reply } : {}), revision: 0, timestamp: now, expiresAt: now + IRC_MANAGEMENT_MS,
        ...(input.source === "discord" ? { discordMessageId: sourceId } : {}) });
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
      let recipient = peer.deserializeAttachment() as IrcSession;
      if (recipient.bridge || !recipient.globalChatSubscribed || !await this.authorized(peer, recipient)) continue;
      const current = currentIrcSession(peer, recipient);
      if (!current || !current.globalChatSubscribed) continue;
      recipient = current;
      if (message.expiresAt <= Date.now()) break;
      const peerDelivery = `peer:${message.id}:${recipient.connectionId}`;
      if (sql.exec("SELECT 1 FROM global_chat_delivered WHERE id=?", peerDelivery).toArray().length) continue;
      const isMentioned = !!recipient.discordId && message.mentions.includes(recipient.discordId);
      try {
        peer.send(current.richChatVersion === 1 ? JSON.stringify({ ...base, model: 1, room: GLOBAL_CHAT_ROOM,
          sourceId: message.sourceId ?? message.id, sender: message.identity ?? { name: message.from, discordId: message.discordId, ...(message.uuid ? { uuid: message.uuid } : {}) },
          media: message.media ?? ircMedia(message.text) ?? [], revision: message.revision ?? 0, reactions: [], mention: isMentioned,
          ...(message.reply ? { reply: message.reply } : {}) }) : isMentioned ? mentioned : normal);
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
    this.lifecycle.receipt(data.id, data.message_id);
    sql.exec("DELETE FROM global_chat_receipts WHERE rowid NOT IN (SELECT rowid FROM global_chat_receipts ORDER BY rowid DESC LIMIT 10000)");
    sql.exec("DELETE FROM global_chat_outbox WHERE id=? AND destination='discord'", data.id);
    sql.exec("DELETE FROM global_chat_messages WHERE id NOT IN (SELECT id FROM global_chat_outbox)");
    return undefined;
  }

  async bridgeConnected(): Promise<void> {
    if (this.coordinator()) this.ctx.storage.sql.exec("UPDATE global_chat_outbox SET next_at=MIN(next_at,?) WHERE destination='discord' AND attempts<5", Date.now());
    this.ctx.storage.sql.exec("UPDATE irc_operation_outbox SET next_at=MIN(next_at,?) WHERE attempts<5", Date.now());
    await this.flushGlobal(); await this.armGlobal();
  }

  async flushGlobal(): Promise<void> {
    if (!this.coordinator()) { this.prune(Date.now()); await this.flushOperations(); return; }
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
      if (message.expiresAt <= Date.now()) return;
      if (row.destination !== "discord") {
        await this.env.RELAY_ROOMS.getByName(row.destination.slice("room:".length)).globalChatDeliver(message);
        sql.exec("DELETE FROM global_chat_outbox WHERE id=? AND destination=?", row.id, row.destination);
      } else {
        if (bridge?.readyState === WebSocket.OPEN) bridge.send(JSON.stringify({ type: "irc_message", scope: "global", room: GLOBAL_CHAT_ROOM, source: "minecraft", id: message.id, message_id: message.id,
          from: message.from, text: message.text, timestamp: message.timestamp, expiresAt: message.expiresAt, discordId: message.discordId, uuid: message.uuid,
          model: 1, sourceId: message.sourceId, media: message.media ?? [], sender: message.identity, mentions: message.mentions, revision: message.revision ?? 0, reactions: [], ...(message.reply ? { reply: message.reply } : {}) }));
      }
    }));
    sql.exec("DELETE FROM global_chat_messages WHERE id NOT IN (SELECT id FROM global_chat_outbox)");
    await this.flushOperations();
  }

  nextGlobalAlarm(): number | null {
    const row = this.ctx.storage.sql.exec<{ next_at: number }>("SELECT MIN(o.next_at) AS next_at FROM global_chat_outbox o JOIN global_chat_messages m ON m.id=o.id").toArray()[0];
    const operation = this.ctx.storage.sql.exec<{ next_at: number | null }>("SELECT MIN(next_at) AS next_at FROM irc_operation_outbox").toArray()[0]?.next_at;
    const metadata = this.ctx.storage.sql.exec<{ expires: number | null }>("SELECT MIN(expires) AS expires FROM irc_metadata").toArray()[0]?.expires;
    const seen = this.ctx.storage.sql.exec<{ expires: number | null }>("SELECT MIN(expires) AS expires FROM irc_operations_seen").toArray()[0]?.expires;
    const party = this.ctx.storage.sql.exec<{ expires: number | null }>("SELECT MIN(expires) AS expires FROM party_chat_consents").toArray()[0]?.expires;
    const next = Math.min(row?.next_at ?? Infinity, operation ?? Infinity, metadata ?? Infinity, seen ?? Infinity, party ?? Infinity);
    return Number.isFinite(next) ? Math.max(Date.now() + 100, next) : null;
  }
  /** Binding-only global mutation RPC. The authenticated room socket supplies the current account lease. */
  async globalOperation(sender: GlobalChatSender, data: Record<string, unknown>): Promise<GlobalChatResult> {
    if (!this.coordinator() || !globalChatChannel(this.env)) return { ok: false, error: "not_configured" };
    if (data.scope !== "global" || !await this.validSender(sender)) return { ok: false, error: "not_authorized" };
    return this.operation({ ...sender, room: GLOBAL_CHAT_ROOM, globalChatSubscribed: true, richChatVersion: 1 }, data, "global");
  }
  private async operation(session: IrcSession, data: Record<string, unknown>, scope: "room" | "global"): Promise<GlobalChatResult> {
    const native = session.bridge === true;
    if (data.scope !== scope || data.room !== undefined && data.room !== session.room ||
        !native && (session.richChatVersion !== 1 || !(scope === "global" ? session.globalChatSubscribed : session.ircSubscribed))) return { ok: false, error: "not_subscribed" };
    let record: IrcRecord | undefined;
    if (native) {
      if (session.richChatVersion !== 1 || data.source !== "discord" || data.channelId !== this.roomChannel(session.room!) ||
          typeof data.message_id !== "string" || !snowflake.test(data.message_id) || typeof data.discordId !== "string" || !snowflake.test(data.discordId)) return { ok: false, error: "invalid_route" };
      record = this.lifecycle.fromDiscord(data.message_id);
    } else if (typeof data.messageId === "string") record = this.lifecycle.get(data.messageId);
    if (!record || record.scope !== scope || record.room !== session.room) return { ok: false, error: "message_expired" };
    const input = ircOperation({ ...data, messageId: record.id }, native ? 4096 : scope === "global" ? this.textLimit(false) : 256, native);
    if (!input) return { ok: false, error: "invalid_operation" };
    const discordId = native ? data.discordId as string : session.discordId;
    // Discord already enforces deletion permissions. A tombstone must still remove previously accepted media after a license changes.
    if (!(native && (scope === "global" || input.operation === "delete")) && (!discordId || !(await this.env.ACCOUNT_STORE.getByName("global").canUseIrc(discordId)).ok)) return { ok: false, error: "not_authorized" };
    if (input.operation === "edit") input.bodyDigest = await ircTextDigest(input.text!);
    if (!native && !await this.validSender(this.sender(session))) return { ok: false, error: "not_authorized" };
    const actor = native ? `dc:${discordId}` : `mc:${session.uuid}`;
    const outcome = this.lifecycle.operate(actor, input, { native, uuid: session.uuid, discordId }, native ? undefined : (update, latest) => {
      const now = Date.now();
      const packet = { ...update, type: "irc_operation", id: input.id,
        scope, room: latest.room, messageId: latest.id, operation: input.operation, revision: latest.revision,
        ...(latest.discordMessageId ? { discordMessageId: latest.discordMessageId } : {}),
        ...(input.operation === "reaction" ? { emoji: input.emoji, active: this.lifecycle.minecraftReacted(latest.id, input.emoji!) } : {}),
        timestamp: now, expiresAt: now + 120_000, authorization: this.sender(session) };
      this.ctx.storage.sql.exec("INSERT INTO irc_operation_outbox VALUES(?,?,?,0,?)", input.id, JSON.stringify(packet), now, now + 120_000);
      // An edit before initial delivery updates its existing transient outbox, rather than publishing an outdated body.
      if (scope === "global" && input.operation === "edit") {
        const queued = this.ctx.storage.sql.exec<{ packet: string }>("SELECT packet FROM global_chat_messages WHERE id=?", latest.id).toArray()[0];
        if (queued) this.ctx.storage.sql.exec("UPDATE global_chat_messages SET packet=? WHERE id=?", JSON.stringify({ ...JSON.parse(queued.packet), text: input.text, media: input.media, revision: latest.revision }), latest.id);
      }
    });
    if (outcome.error) return { ok: false, error: outcome.error };
    if (outcome.update) {
      if (scope === "global" && outcome.record) this.refreshQueuedGlobal(outcome.record, outcome.update);
      const mines = this.mineActors(record.id);
      if (scope === "global") await Promise.allSettled(this.rooms().map(room => this.env.RELAY_ROOMS.getByName(room).globalChatUpdate(outcome.update!, mines)));
      else await this.deliverUpdate(outcome.update, mines);
      if (!native) { await this.armGlobal(); await this.flushOperations(); }
    }
    return { ok: true, id: input.id };
  }
  private refreshQueuedGlobal(record: IrcRecord, update: IrcUpdate): void {
    if (update.operation === "delete") {
      this.ctx.storage.sql.exec("DELETE FROM global_chat_outbox WHERE id=?", record.id);
      this.ctx.storage.sql.exec("DELETE FROM global_chat_messages WHERE id=?", record.id);
      return;
    }
    if (update.operation !== "edit") return;
    const queued = this.ctx.storage.sql.exec<{ packet: string }>("SELECT packet FROM global_chat_messages WHERE id=?", record.id).toArray()[0];
    if (queued) this.ctx.storage.sql.exec("UPDATE global_chat_messages SET packet=? WHERE id=?",
      JSON.stringify({ ...JSON.parse(queued.packet), ...(update.text !== undefined ? { text: update.text, mentions: [] } : {}), media: record.media, revision: record.revision }), record.id);
  }
  private mineActors(messageId: string): Record<string, string[]> {
    const result: Record<string, string[]> = {};
    for (const row of this.ctx.storage.sql.exec<{ emoji: string; actor: string }>("SELECT emoji,actor FROM irc_reactions WHERE message_id=? AND actor LIKE 'mc:%'", messageId).toArray()) (result[row.emoji] ??= []).push(row.actor.slice(3));
    return result;
  }
  async deliverUpdate(update: IrcUpdate, mines: Record<string, string[]>): Promise<void> {
    if (update.model !== 1 || !packetId.test(update.messageId) || !Number.isSafeInteger(update.revision) || update.revision < 1 ||
        update.scope === "room" && !this.ctx.id.equals(this.env.RELAY_ROOMS.idFromName(update.room))) return;
    for (const peer of this.ctx.getWebSockets()) {
      const recipient = peer.deserializeAttachment() as IrcSession;
      const field = update.scope === "global" ? "globalChatSubscribed" : update.scope === "party" ? "partySubscribed" : "ircSubscribed";
      if (peer.readyState !== WebSocket.OPEN || recipient.bridge || recipient.richChatVersion !== 1 || !recipient[field] || !await this.authorized(peer, recipient)) continue;
      const current = currentIrcSession(peer, recipient);
      if (!current || current.richChatVersion !== 1 || !current[field]) continue;
      if (update.scope === "party" && (current.partyId !== update.room || (current.partyConsentExpiresAt ?? 0) <= Date.now())) continue;
      try { peer.send(JSON.stringify({ ...update, reactions: update.reactions.map(reaction => ({ ...reaction, mine: !!current.uuid && (mines[reaction.emoji] ?? []).includes(current.uuid) })) })); }
      catch { /* Updates remain live-only; a reconnect does not restore chat history. */ }
    }
  }
  private acknowledgeOperation(data: Record<string, unknown>, room: string): string | undefined {
    if (data.channelId !== this.roomChannel(room) || typeof data.id !== "string" || !packetId.test(data.id) ||
        typeof data.message_id !== "string" || !snowflake.test(data.message_id)) return "invalid_ack";
    const row = this.ctx.storage.sql.exec<{ packet: string; attempts: number }>("SELECT packet,attempts FROM irc_operation_outbox WHERE id=? AND expires>?", data.id, Date.now()).toArray()[0];
    if (!row) return undefined; // A repeated receipt is harmless, and never creates a mapping.
    const packet = JSON.parse(row.packet), record = this.lifecycle.get(packet.messageId);
    if (!row.attempts || packet.room !== room || packet.messageId !== data.messageId || packet.revision !== data.revision || record?.discordMessageId !== data.message_id) return "invalid_ack";
    this.ctx.storage.sql.exec("DELETE FROM irc_operation_outbox WHERE id=?", data.id);
    return undefined;
  }
  private failedOperation(data: Record<string, unknown>): void {
    if (typeof data.id !== "string" || !packetId.test(data.id) || !["message_deleted", "message_expired", "not_authorized", "invalid_operation", "superseded"].includes(String(data.error))) return;
    this.ctx.storage.sql.exec("DELETE FROM irc_operation_outbox WHERE id=?", data.id);
  }
  async flushOperations(): Promise<void> {
    this.lifecycle.prune();
    const bridge = this.ctx.getWebSockets().find(ws => ws.readyState === WebSocket.OPEN &&
      (ws.deserializeAttachment() as IrcSession).bridge && (ws.deserializeAttachment() as IrcSession).richChatVersion === 1);
    const sql = this.ctx.storage.sql, now = Date.now();
    const rows = sql.exec<{ id: string; packet: string; attempts: number; expires: number }>("SELECT id,packet,attempts,expires FROM irc_operation_outbox WHERE next_at<=? ORDER BY rowid LIMIT 32", now).toArray();
    for (const row of rows) {
      if (row.attempts >= 5) { sql.exec("DELETE FROM irc_operation_outbox WHERE id=?", row.id); continue; }
      const packet = JSON.parse(row.packet), record = this.lifecycle.get(packet.messageId);
      if (!record) { sql.exec("DELETE FROM irc_operation_outbox WHERE id=?", row.id); continue; }
      if (!bridge || !record.discordMessageId) { sql.exec("UPDATE irc_operation_outbox SET next_at=? WHERE id=?", now + 5000, row.id); continue; }
      if (!await this.validSender(packet.authorization)) { sql.exec("DELETE FROM irc_operation_outbox WHERE id=?", row.id); continue; }
      if (row.expires <= Date.now() || bridge.readyState !== WebSocket.OPEN) continue;
      const { authorization: _privateLease, ...publicPacket } = packet;
      // Persist delivery intent before the native request can succeed without its receipt reaching us.
      sql.exec("UPDATE irc_operation_outbox SET attempts=attempts+1,next_at=? WHERE id=?", Date.now() + Math.min(40_000, 5000 * 2 ** row.attempts), row.id);
      try { bridge.send(JSON.stringify({ ...publicPacket, discordMessageId: record.discordMessageId })); } catch { /* The bounded outbox retries after reconnect. */ }
    }
    await this.armGlobal();
  }
  private async armGlobal(): Promise<void> {
    const next = this.nextGlobalAlarm(); if (next === null) return;
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }
}
