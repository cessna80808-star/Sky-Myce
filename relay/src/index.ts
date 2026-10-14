import { DurableObject } from "cloudflare:workers";
import { verifyAccount } from "./auth";
import { classes, SharedStats, STATS_TTL, validateStats, type StatsGrant } from "./stats";
import { SharedWealth, WEALTH_TTL, validateWealth } from "./wealth";
import { DigestNews, DigestRng, rngPayload, type DigestConfig, type NewsResult, type NewsSource } from "./digest";
import { PlayerCosmetics, codeHash, newLinkCode, normalizedCode, cosmeticPayload, watchCosmetics, watchesCosmetic, type CosmeticsConfig, type DiscordCommand, type CosmeticProfile } from "./cosmetics";
import { handleCosmeticAssets } from "./cosmetic-assets";
import { handleLeaderboardsRequest, handleCommunityFeed } from "./leaderboards";
import { handleRngTestRequest, handleRngTestInteraction } from "./rng-tester";
import { RngForwardQueue } from "./rng-forwarding";
import { verifiedInteraction, discordCommand } from "./discord";
import { deferInteraction, handleAccountInteraction, handleAccountAutocomplete, type DiscordAccountsConfig } from "./discord-accounts";
import type { DrmConfig } from "./drm";
import { handleCommunityRequest } from "./community";
import { handleCommunityRelease } from "./community-release";
import type { AccountsConfig, AccountStore } from "./accounts";
import { RoomIrc, currentIrcSession, verifyBridge, globalChatChannel, GLOBAL_CHAT_ROOM, PARTY_CHAT_UNAVAILABLE, type IrcConfig, type IrcSession, type GlobalChatSender, type GlobalChatSend, type GlobalChatResult, type GlobalChatMessage, type PartyChatIdentity, type PartyChatMessage } from "./irc";
import type { IrcUpdate } from "./irc-model";
import { handlePromptInteraction, handlePromptRunner } from "./prompts";
import type { SharingState, SharingDecision, SharingCategory } from "./community-sharing";
import { takeTrafficCredit, type TrafficCredit } from "./traffic";
export { AccountStore } from "./accounts";

type RelayEnv = Omit<Env, "SKYMYCE_ENVIRONMENT" | "ACCOUNT_STORE" | "RELAY_ROOMS" | "COMMUNITY_SERVICES_ENABLED" | "GLOBAL_CHAT_GATEWAY_CONTROL"> & DigestConfig & CosmeticsConfig & AccountsConfig & DrmConfig & IrcConfig & DiscordAccountsConfig & {
  SKYMYCE_ENVIRONMENT?: string; ALLOW_LEGACY_RELAY?: string; COSMETIC_ASSETS?: R2Bucket;
  COMMUNITY_SERVICES_ENABLED?: string;
  GLOBAL_CHAT_GATEWAY_CONTROL?: { wake(): Promise<void> };
  ACCOUNT_STORE: DurableObjectNamespace<AccountStore>; RELAY_ROOMS: DurableObjectNamespace<RelayRoom>;
};
const legacyRelay = (env: RelayEnv) => env.SKYMYCE_ENVIRONMENT === "development" && env.ALLOW_LEGACY_RELAY === "true";
const discordEnvironment = (env: RelayEnv): RelayEnv => ({ ...env,
  DISCORD_USER_APPLICATION_ID: env.USER_BOT_APPLICATION_ID ?? env.DISCORD_USER_APPLICATION_ID,
  DISCORD_USER_PUBLIC_KEY: env.USER_BOT_PUBLIC_KEY ?? env.DISCORD_USER_PUBLIC_KEY,
  DISCORD_BACKEND_APPLICATION_ID: env.BACKEND_BOT_APPLICATION_ID ?? env.DISCORD_BACKEND_APPLICATION_ID,
  DISCORD_BACKEND_PUBLIC_KEY: env.BACKEND_BOT_PUBLIC_KEY ?? env.DISCORD_BACKEND_PUBLIC_KEY,
  DISCORD_BACKEND_BOT_TOKEN: env.BACKEND_BOT_TOKEN ?? env.DISCORD_BACKEND_BOT_TOKEN });
type JoinPolicy = { floor: string; maxPbMillis: number | null; open: boolean; selectedClass?: string };
type Session = IrcSession & {
  incoming?: TrafficCredit;
  started?: number;
  challenge: string;
  expires: number;
  ip: string;
  name?: string;
  uuid?: string;
  registered?: boolean;
  liveUpdates?: boolean;
  userMessages?: boolean;
  cosmetics?: boolean;
  cosmeticsVersion?: number;
  cosmeticWatching?: string;
  cosmeticCredits?: number;
  cosmeticUpdated?: number;
  checking?: boolean;
  credits: number;
  updated: number;
  seen: string[];
  inbox: { id: string; from: string; expires: number; user?: boolean }[];
  uploads?: StatsGrant[];
  wealthUploads?: StatsGrant[];
  wealthCredits?: number;
  wealthUpdated?: number;
  cacheCredits?: number;
  cacheUpdated?: number;
  party?: JoinPolicy;
  partyCredits?: number;
  partyUpdated?: number;
  rngSubscribed?: boolean;
  rngMetadataVersion?: number;
  digestCredits?: number;
  digestUpdated?: number;
};
const username = /^[A-Za-z0-9_]{1,16}$/;
const uuid = /^[a-f0-9]{32}$/;
const encoder = new TextEncoder();

export default {
  async fetch(request, env, ctx): Promise<Response> {
    env = discordEnvironment(env);
    const url = new URL(request.url);
    const ip = request.headers.get("CF-Connecting-IP") ?? "local";
    const limited = (status = 429) => Response.json({ ok: false, error: status === 429 ? "rate_limited" : "service_busy" },
      { status, headers: { "Cache-Control": "no-store", "Retry-After": "60" } });
    // One IP budget covers all paths and query strings, including unknown routes.
    if (!(await env.REQUEST_LIMIT.limit({ key: ip })).success) return limited();
    const costly = url.pathname.startsWith("/community/") || ["/websocket", "/discord/bridge", "/discord/interactions", "/discord/backend/interactions", "/discord/community-feed", "/prompt/runner"].includes(url.pathname);
    if (costly && !(await env.SERVICE_LIMIT.limit({ key: "public" })).success) return limited(503);
    if (env.COMMUNITY_SERVICES_ENABLED === "false" && (url.pathname.startsWith("/community/") || ["/websocket", "/discord/bridge", "/discord/community-feed"].includes(url.pathname))) return limited(503);
    if (url.pathname === "/community/artifact" && !(await env.DOWNLOAD_LIMIT.limit({ key: ip })).success) return limited();
    if (url.pathname === "/prompt/runner") {
      if (!(await env.CONNECT_LIMIT.limit({ key: `prompt-runner:${request.headers.get("CF-Connecting-IP") ?? "local"}` })).success) return new Response("Rate limited", { status: 429 });
      return await handlePromptRunner(request, env);
    }
    if (url.pathname.startsWith("/community/")) {
      if (!(await env.CONNECT_LIMIT.limit({ key: `community:${url.pathname}:${request.headers.get("CF-Connecting-IP") ?? "local"}` })).success)
        return url.pathname === "/community/news" ? limited() : new Response("Rate limited", { status: 429 });
      if (url.pathname === "/community/news") {
        if (request.method !== "GET") return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405, headers: { Allow: "GET" } });
        const source = url.searchParams.get("source");
        if (source !== "game") return Response.json({ ok: false, error: "invalid_source" }, { status: 400 });
        try {
          const result = await env.RELAY_ROOMS.getByName("public-news").publicNews(source);
          return Response.json(result, { headers: { "Cache-Control": "no-store" } });
        } catch { return Response.json({ ok: false, error: "news_unavailable" }, { status: 503 }); }
      }
      try { return await handleCommunityRelease(request, env, ctx) ?? await handleCosmeticAssets(request, env) ?? await handleLeaderboardsRequest(request, env) ?? await handleRngTestRequest(request, env) ?? await handleCommunityRequest(request, env) ?? new Response("Not found", { status: 404 }); }
      catch { console.error({ event: "community_unavailable" }); return Response.json({ ok: false, error: "community_unavailable" }, { status: 503 }); }
    }
    if (url.pathname.startsWith("/auth/") || url.pathname.startsWith("/loader/") || url.pathname === "/discord/loader") {
      return Response.json({ error: "loader_retired", message: "Install Sky-Myce normally with Fabric. Connect Discord from the mod." }, { status: 410 });
    }
    if (url.pathname === "/discord/community-feed") {
      if (!(await env.CONNECT_LIMIT.limit({ key: `community-feed:${request.headers.get("CF-Connecting-IP") ?? "local"}` })).success) return new Response("Rate limited", { status: 429 });
      try { return await handleCommunityFeed(request, env) ?? new Response("Not found", { status: 404 }); }
      catch { return Response.json({ ok: false, error: "feed_unavailable" }, { status: 503 }); }
    }
    if (url.pathname === "/discord/interactions" || url.pathname === "/discord/backend/interactions") {
      const backend = url.pathname === "/discord/backend/interactions";
      const discordEnv = { ...env, DISCORD_PUBLIC_KEY: backend ? env.DISCORD_BACKEND_PUBLIC_KEY : (env.DISCORD_USER_PUBLIC_KEY ?? env.DISCORD_PUBLIC_KEY),
        DISCORD_APPLICATION_ID: backend ? env.DISCORD_BACKEND_APPLICATION_ID : (env.DISCORD_USER_APPLICATION_ID ?? env.DISCORD_APPLICATION_ID) };
      if (!discordEnv.DISCORD_PUBLIC_KEY || !discordEnv.DISCORD_APPLICATION_ID) return new Response("Interactions not configured", { status: 503 });
      if (!(await env.CONNECT_LIMIT.limit({ key: `discord:${request.headers.get("CF-Connecting-IP") ?? "local"}` })).success) return new Response("Rate limited", { status: 429 });
      const data = await verifiedInteraction(request, discordEnv);
      if (!data) return new Response("Invalid signature or interaction", { status: 401 });
      if (data.type === 1) return Response.json({ type: 1 });
      if (data.type === 4) return handleAccountAutocomplete(data, env, backend ? "backend" : "user");
      const promptResponse = await handlePromptInteraction(data, env, ctx, backend);
      if (promptResponse) return promptResponse;
      const rngTestResponse = handleRngTestInteraction(data, env, ctx, backend);
      if (rngTestResponse) return rngTestResponse;
      const accountResponse = await handleAccountInteraction(data, env, ctx, backend ? "backend" : "user");
      if (accountResponse) return accountResponse;
      if (backend) return Response.json({ type: 4, data: { content: "Unsupported command.", flags: 64, allowed_mentions: { parse: [] } } });
      if (!legacyRelay(env) && data.guild_id !== env.DISCORD_GUILD_ID) return new Response("Guild not allowed", { status: 403 });
      const command = discordCommand(data);
      if (!command) return Response.json({ type: 4, data: { content: "Unsupported command.", flags: 64, allowed_mentions: { parse: [] } } });
      const room = env.DISCORD_INTERACTIONS_ROOM ?? "friends";
      if (!env.ROOMS.split(",").includes(room)) return new Response("Interactions room unavailable", { status: 503 });
      if (!legacyRelay(env)) return deferInteraction(data, ctx, async () => {
        const result = await env.RELAY_ROOMS.getByName(room).discordInteraction(command) as { data: { content: string } };
        return result.data.content;
      });
      try { return Response.json(await env.RELAY_ROOMS.getByName(room).discordInteraction(command)); }
      catch { return Response.json({ type: 4, data: { content: "Sky-Myce is temporarily unavailable.", flags: 64, allowed_mentions: { parse: [] } } }); }
    }
    if (url.pathname === "/health") return Response.json({ service: "Sky-Myce relay", version: 3 });
    if (url.pathname !== "/websocket" && url.pathname !== "/discord/bridge") return new Response("Not found", { status: 404 });
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const room = url.searchParams.get("room") ?? "friends";
    const globalBridge = url.pathname === "/discord/bridge" && room === GLOBAL_CHAT_ROOM;
    if (!/^[a-z0-9_-]{1,32}$/.test(room) || (!globalBridge && (!env.ROOMS.split(",").includes(room) || room === GLOBAL_CHAT_ROOM))) {
      return new Response("Room not allowed", { status: 403 });
    }
    // Identity is not verified yet; bound attempts by the Cloudflare-provided IP.
    if (!(await env.CONNECT_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") ?? "local" })).success) {
      return new Response("Too many connection attempts", { status: 429 });
    }
    try { return await env.RELAY_ROOMS.getByName(room).fetch(request); }
    catch { console.error({ event: "relay_unavailable" }); return new Response("Sky-Myce is temporarily unavailable", { status: 503 }); }
  },
} satisfies ExportedHandler<RelayEnv>;

export class RelayRoom extends DurableObject<RelayEnv> {
  private readonly stats: SharedStats;
  private readonly wealth: SharedWealth;
  private readonly news: DigestNews;
  private readonly rng: DigestRng;
  private readonly rngForward: RngForwardQueue;
  private rngCapacityDiagnosticAt = 0;
  private trafficDiagnosticAt = 0;
  private gatewaySignalAt = 0;
  private readonly incomingBudgets = new WeakMap<WebSocket, TrafficCredit>();
  private readonly cosmetics: PlayerCosmetics;
  private readonly irc: RoomIrc;
  constructor(ctx: DurableObjectState, env: RelayEnv) {
    env = discordEnvironment(env);
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    this.stats = new SharedStats(ctx.storage.sql);
    this.wealth = new SharedWealth(ctx.storage.sql);
    this.news = new DigestNews(ctx.storage.sql, env);
    this.rng = new DigestRng(ctx.storage.sql);
    this.rngForward = new RngForwardQueue(ctx.storage, {
      deliver: record => this.env.ACCOUNT_STORE.getByName("global").recordAcceptedCommunityRng(record.uuid, record.event, record.sourceId, record.accountId),
      diagnostic: kind => {
        if (Date.now() - this.rngCapacityDiagnosticAt < 60000) return;
        this.rngCapacityDiagnosticAt = Date.now();
        console.warn({ event: kind === "capacity" ? "community_rng_capacity_reached" : "community_rng_forwarding_unavailable" });
      },
    });
    ctx.waitUntil(this.rngForward.arm());
    this.cosmetics = new PlayerCosmetics(ctx.storage);
    this.irc = new RoomIrc(ctx, env, (ws, session) => this.authorized(ws, session), uuid => this.cosmetics.get(uuid).name);
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS account_migration_state (id INTEGER PRIMARY KEY CHECK(id=1), cursor TEXT NOT NULL, done INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS cosmetic_migration_state (id INTEGER PRIMARY KEY CHECK(id=1),cursor TEXT NOT NULL,done INTEGER NOT NULL)");
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS mod_users (
      uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)`);
    ctx.storage.sql.exec("CREATE INDEX IF NOT EXISTS mod_users_name ON mod_users(name COLLATE NOCASE)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS room_sharing_cutoffs(uuid TEXT NOT NULL,category TEXT NOT NULL,not_before INTEGER NOT NULL,PRIMARY KEY(uuid,category))");
    // Register already-authenticated sockets on the first wake after upgrading the relay.
    for (const ws of ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (session.name && session.uuid && !session.registered) {
        this.registerUser(session.uuid, session.name);
        session.registered = true;
        ws.serializeAttachment(session);
      }
    }
  }

  /** Public announcements only; callers cannot select arbitrary Discord channels or access room/user state. */
  publicNews(source: NewsSource): Promise<NewsResult> {
    if (source !== "game") throw Error("invalid_source");
    if (this.env.COMMUNITY_SERVICES_ENABLED === "false") throw Error("news_unavailable");
    return this.news.get(source);
  }

  /** Binding-only RPC. The public Worker verifies Discord's exact signed HTTP body first. */
  async discordInteraction(command: DiscordCommand): Promise<object> {
    const reply = (content: string) => ({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
    let authority: { uuid: string } | undefined;
    if (!legacyRelay(this.env)) {
      if (command.channel !== (this.env.COSMETICS_COMMAND_CHANNEL ?? "1552057526969835612")) return reply("Use cosmetic commands in the configured cosmetics channel.");
      try {
        if (!await this.migrateLegacyAccounts(this.env.DISCORD_INTERACTIONS_ROOM ?? "friends")) return reply("Sky-Myce account migration is in progress. Please retry.");
        const store = this.env.ACCOUNT_STORE.getByName("global");
        if (command.action === "link") {
          const linked = await store.consumeMinecraftLink(command.actor, command.options.code as string);
          return reply(linked.ok ? "Minecraft account linked to your Sky-Myce account." : "The account link could not be completed. Request a fresh code in Minecraft.");
        }
        const account = await store.getAccountByDiscordId(command.actor);
        if (!account || !await store.communityIdentityAllowed(command.actor)) return reply("This identity cannot use community services.");
        // Keep the previous cosmetic target when it still belongs to the global account.
        const oldTarget = this.ctx.storage.sql.exec<{ uuid: string }>("SELECT uuid FROM cosmetics_links WHERE discord = ?", command.actor).toArray()[0]?.uuid;
        const target = account.uuids.find(value => value.uuid === oldTarget)?.uuid ?? account.uuids[0]?.uuid;
        if (!target) return reply("Connect Discord from Sky-Myce in Minecraft first.");
        if (!await store.communityIdentityAllowed(command.actor, target)) return reply("This Minecraft identity cannot use community services.");
        if (command.action === "unlink") return reply("Use /account unlink to remove a Minecraft account from Sky-Myce. Use /cosmetics reset to clear its appearance.");
        authority = { uuid: target };
      } catch { return reply("Sky-Myce account services are temporarily unavailable."); }
    }
    const result = authority ? await this.env.ACCOUNT_STORE.getByName("global").communityCosmeticCommand(command, { COSMETICS_COMMAND_CHANNEL: this.env.COSMETICS_COMMAND_CHANNEL }, authority.uuid) : await this.cosmetics.command(command, this.env, Date.now());
    if (result.changed) {
      if (!legacyRelay(this.env)) await Promise.allSettled(this.env.ROOMS.split(",").map(room => this.env.RELAY_ROOMS.getByName(room).cosmeticChanged([result.changed!])));
      else await this.cosmeticChanged([result.changed]);
    }
    return result.response;
  }
  async cosmeticChanged(profiles: CosmeticProfile[]): Promise<void> {
    if (profiles.length > 128) return;
    for (const profile of profiles) {
      // The room replica supplies synchronous IRC presentation only; edits/reads remain globally authoritative.
      const latest = legacyRelay(this.env) ? this.cosmetics.get(profile.uuid) : (await this.env.ACCOUNT_STORE.getByName("global").communityCosmeticProfiles([profile.uuid]))[0];
      if (!legacyRelay(this.env)) this.cosmetics.replicate(latest);
      for (const ws of this.ctx.getWebSockets()) {
        const session = ws.deserializeAttachment() as Session;
        if (ws.readyState === WebSocket.OPEN && session.name && session.cosmetics && watchesCosmetic(session.cosmeticWatching, profile.uuid)) {
          if (!await this.authorized(ws, session)) continue;
          // A later save may interleave with peer authentication. Always publish the authoritative latest revision.
          const current = legacyRelay(this.env) ? this.cosmetics.get(profile.uuid) : (await this.env.ACCOUNT_STORE.getByName("global").communityCosmeticProfiles([profile.uuid]))[0];
          if (!await this.authorized(ws, session)) continue;
          try { ws.send(JSON.stringify({ type: "cosmetics_event", record: cosmeticPayload(current, session.cosmeticsVersion) })); } catch { /* Reconnect lookup restores the newest revision. */ }
        }
      }
    }
  }

  private registerUser(id: string, name: string): void {
    const now = Date.now();
    // Identity comes exclusively from verified account proofs, never wealth reports or friend lists.
    this.ctx.storage.sql.exec(`INSERT INTO mod_users (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
      ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen`, id, name, now, now);
  }

  async fetch(request: Request): Promise<Response> {
    if (this.env.COMMUNITY_SERVICES_ENABLED === "false") return new Response("Community services temporarily paused", { status: 503 });
    const url = new URL(request.url);
    if (!legacyRelay(this.env) && !await this.migrateLegacyAccounts(url.searchParams.get("room") ?? "friends")) return new Response("Account migration in progress; retry", { status: 503 });
    const bridge = url.pathname === "/discord/bridge";
    if (bridge) {
      const room = url.searchParams.get("room") ?? "friends";
      const configured = room === GLOBAL_CHAT_ROOM ? globalChatChannel(this.env) : room === "testing" ? this.env.DISCORD_IRC_TEST_CHANNEL_ID : room === "friends" ? this.env.DISCORD_IRC_CHANNEL_ID : undefined;
      if (!configured) return new Response("Bridge unavailable", { status: 503 });
      const nonce = await verifyBridge(request, this.env, room);
      if (!nonce || !this.irc.acceptNonce(nonce)) return new Response("Bridge not authorized", { status: 401 });
    }
    const sockets = this.ctx.getWebSockets();
    if (bridge && sockets.some(ws => ws.readyState === WebSocket.OPEN && (ws.deserializeAttachment() as Session).bridge)) {
      return new Response("A bridge already owns this room", { status: 409 });
    }
    const ip = request.headers.get("CF-Connecting-IP") ?? "local";
    if (sockets.length >= 128 || sockets.filter(ws => (ws.deserializeAttachment() as Session).ip === ip).length >= 8) {
      return new Response("Too many connections", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const now = Date.now();
    const session: Session = {
      challenge: crypto.randomUUID().replaceAll("-", ""), expires: now + 30000, started: now, ip,
      credits: 12, updated: now, seen: [], inbox: [], room: url.searchParams.get("room") ?? "friends", ...(bridge ? { bridge: true } : {}),
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(session);
    server.send(JSON.stringify(bridge ? { type: "bridge_ready", protocol: 3, richChatVersion: 1, room: session.room, ...(session.room === GLOBAL_CHAT_ROOM ? { globalChat: true } : {}) } : { type: "challenge", serverId: session.challenge, protocol: legacyRelay(this.env) ? 2 : 3 }));
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > session.expires) await this.ctx.storage.setAlarm(session.expires);
    if (bridge && session.room === GLOBAL_CHAT_ROOM) await this.irc.bridgeConnected();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    if (ws.readyState !== WebSocket.OPEN) return;
    if (this.env.COMMUNITY_SERVICES_ENABLED === "false") { ws.close(1013, "Community services temporarily paused"); return; }
    let session = ws.deserializeAttachment() as Session;
    const burst = session.bridge ? 256 : 64, incomingAt = Date.now();
    // Older asynchronous handlers may serialize a snapshot; keep the live budget authoritative.
    session.incoming = this.incomingBudgets.get(ws) ?? session.incoming ?? { credits: burst, updated: incomingAt };
    this.incomingBudgets.set(ws, session.incoming);
    const accepted = takeTrafficCredit(session.incoming, burst, session.bridge ? 25 : 125, incomingAt);
    ws.serializeAttachment(session);
    if (!accepted) {
      this.trafficDiagnostic("incoming_message_rate_exceeded");
      ws.close(1013, "Incoming message rate exceeded");
      if (!legacyRelay(this.env) && !session.bridge && session.accountId && session.uuid && session.communitySessionId && session.generation !== undefined) {
        this.ctx.waitUntil(this.env.ACCOUNT_STORE.getByName("global").reportCommunityTrafficViolation({ accountId: session.accountId,
          uuid: session.uuid, sessionId: session.communitySessionId, generation: session.generation }).catch(() => this.trafficDiagnostic("traffic_report_unavailable")));
      }
      return;
    }
    const maximum = session.bridge ? 32768 : 8192;
    if (typeof raw !== "string" || raw.length > maximum || encoder.encode(raw).length > maximum) { ws.close(1009, "Message too large"); return; }
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(raw);
      if (!data || Array.isArray(data) || typeof data !== "object") throw new Error();
    } catch { ws.close(1008, "Invalid JSON message"); return; }

    if (session.bridge) {
      if (session.room !== GLOBAL_CHAT_ROOM && session.richChatVersion !== 1 && data.type !== "bridge_capabilities" && encoder.encode(raw).length > 2048) { ws.close(1009, "Message too large"); return; }
      try { await this.irc.message(ws, session, data); }
      catch { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", id: data.id, error: "temporarily_unavailable", ...(data.scope === "global" || data.scope === "party" ? { scope: data.scope } : {}), ...(data.part === "embeds" ? { part: "embeds" } : {}) })); }
      return;
    }

    if (!session.name) {
      if (session.checking || Date.now() > session.expires || data.type !== "authenticate" ||
          typeof data.name !== "string" || !username.test(data.name) || typeof data.uuid !== "string" || !uuid.test(data.uuid)) {
        console.warn({ event: "authentication_rejected", category: Date.now() > session.expires ? "challenge_expired" : "invalid_authentication", retryable: true });
        ws.close(1008, "Authentication required"); return;
      }
      session.checking = true;
      ws.serializeAttachment(session);
      try {
        const profile = await verifyAccount(data, session.challenge);
        if ("error" in profile || Date.now() > session.expires) {
          const rejection = "error" in profile ? profile : { error: "challenge_expired", retryable: true };
          console.warn({ event: "authentication_rejected", category: rejection.error, retryable: rejection.retryable });
          ws.close(rejection.retryable ? 4003 : 4004, rejection.error); return;
        }
        if (ws.readyState !== WebSocket.OPEN) return;
        if (!legacyRelay(this.env)) {
          if (typeof data.communityToken !== "string") { ws.close(4007, "Connect Discord in Sky-Myce"); return; }
          const authorization = await this.env.ACCOUNT_STORE.getByName("global").authorizeCommunitySession({ uuid: profile.id,
            token: data.communityToken, challenge: session.challenge, modVersion: typeof data.modVersion === "string" ? data.modVersion : undefined });
          if (!authorization.ok) {
            if (["rate_limited", "traffic_cooldown", "service_busy"].includes(authorization.error ?? "")) ws.close(1013, authorization.error!);
            else if (authorization.error?.endsWith("unavailable")) ws.close(1013, "Authorization service unavailable");
            else ws.close(authorization.error === "session_expired" ? 4006 : 4005, authorization.error ?? "not_authorized");
            return;
          }
          Object.assign(session, { accountId: authorization.accountId, discordId: authorization.discordId,
            communitySessionId: authorization.sessionId, generation: authorization.generation, leaseExpiresAt: authorization.expiresAt,
            buildId: typeof data.modVersion === "string" ? data.modVersion : undefined, connectionId: authorization.connectionId });
        }
        if (ws.readyState !== WebSocket.OPEN) return;
        this.registerUser(profile.id, profile.name);
        for (const other of this.ctx.getWebSockets()) {
          const previous = other.deserializeAttachment() as Session;
          if (legacyRelay(this.env) && other !== ws && (previous.uuid === profile.id || previous.name?.toLowerCase() === profile.name.toLowerCase())) {
            other.close(4001, "Account connected elsewhere");
          }
        }
        session.name = profile.name;
        session.uuid = profile.id;
        session.registered = true;
        session.liveUpdates = data.liveUpdates === true;
        session.userMessages = data.userMessages === true;
        session.cosmetics = data.cosmetics === true;
        session.cosmeticsVersion = data.cosmeticsVersion === 5 ? 5 : data.cosmeticsVersion === 4 ? 4 : data.cosmeticsVersion === 3 ? 3 : data.cosmeticsVersion === 2 ? 2 : 1;
        session.rngMetadataVersion = data.rngMetadataVersion === 1 ? 1 : undefined;
        session.challenge = "";
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "ready", name: session.name, uuid: session.uuid, protocol: legacyRelay(this.env) ? 2 : 3, statsCache: true, wealthCache: true, partyPolicies: true, liveUpdates: true, digestNews: true, rngFeed: true, rngMetadataVersion: 1, userMessages: true, cosmetics: true, cosmeticsVersion: 5, cosmeticsNames: true,
          irc: !!session.accountId, globalChat: !!session.accountId && !!globalChatChannel(this.env), richChatVersion: 1, partyChat: !!session.accountId, partyChatVersion: 1, partyChatError: PARTY_CHAT_UNAVAILABLE,
          account: !!session.accountId, license: !!session.accountId, drm: !!session.accountId, leaseExpiresAt: session.leaseExpiresAt }));
      } catch { console.error({ event: "verification_failed" }); ws.close(1013, "Minecraft verification unavailable; retry later"); }
      return;
    }

    const subscription = data.type === "irc_subscribe" && encoder.encode(raw).length <= (data.scope === "global" || data.scope === "party" ? 8192 : 2048)
      ? this.irc.reserveSubscription(ws, session, data) : undefined;
    if (!await this.authorized(ws, session, true)) return;
    const current = currentIrcSession(ws, session);
    if (!current) return;
    session = current;
    if (!session.name) return;
    if (data.type === "community_contribution") {
      if (encoder.encode(raw).length > 2048 || typeof data.id !== "string" || !uuid.test(data.id)) { ws.close(1008, "Invalid contribution"); return; }
      const result = await this.env.ACCOUNT_STORE.getByName("global").recordCommunitySessionContribution({ sessionId: session.communitySessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! }, data.contribution);
      if (await this.authorized(ws, session)) ws.send(JSON.stringify({ type: "community_contribution_result", id: data.id, ...result }));
      return;
    }
    if (data.type === "irc_send" || data.type === "irc_subscribe" || data.type === "irc_operation") {
      if (!session.accountId || encoder.encode(raw).length > (data.scope === "global" || data.scope === "party" ? 8192 : 2048)) { ws.send(JSON.stringify({ type: "irc_result", id: data.id, error: "not_authorized", ...(data.scope === "global" || data.scope === "party" ? { scope: data.scope } : {}) })); return; }
      try {
        await this.irc.message(ws, session, data, subscription);
        if (!session.bridge && data.type === "irc_subscribe" && data.scope === "global" &&
            currentIrcSession(ws, session)?.globalChatSubscribed) this.signalGateway();
      }
      catch { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "irc_result", id: data.id, error: "temporarily_unavailable", ...(data.scope === "global" || data.scope === "party" ? { scope: data.scope } : {}), ...(data.part === "embeds" ? { part: "embeds" } : {}) })); }
      return;
    }

    if (data.type === "cosmetics_get" || data.type === "cosmetics_link" || data.type === "cosmetics_subscribe") {
      const now = Date.now();
      if (!session.cosmetics || typeof data.id !== "string" || !uuid.test(data.id) || encoder.encode(raw).length > 2048) { ws.close(1008, "Invalid cosmetics request"); return; }
      if (data.type === "cosmetics_subscribe" && data.enabled === false) {
        session.cosmeticWatching = undefined; ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, disabled: true })); return;
      }
      session.cosmeticCredits = Math.min(8, (session.cosmeticCredits ?? 8) + (now - (session.cosmeticUpdated ?? now)) / 1000);
      session.cosmeticUpdated = now;
      if (session.cosmeticCredits < 1) { ws.serializeAttachment(session); ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, error: "rate_limited" })); return; }
      session.cosmeticCredits--; ws.serializeAttachment(session);
      if (data.type === "cosmetics_link") {
        if (!(this.env.DISCORD_USER_PUBLIC_KEY ?? this.env.DISCORD_PUBLIC_KEY) || !(this.env.DISCORD_USER_APPLICATION_ID ?? this.env.DISCORD_APPLICATION_ID)) { ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, error: "not_configured" })); return; }
        if (!legacyRelay(this.env)) {
          const result = await this.env.ACCOUNT_STORE.getByName("global").issueMinecraftLink(session.uuid!, session.name, session.discordId!);
          if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, ...result }));
          return;
        }
        const code = newLinkCode();
        const result = this.cosmetics.issue(session.uuid!, await codeHash(normalizedCode(code)!), now);
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, ...result, ...(result.error ? {} : { code }) }));
        return;
      }
      const requestedIds = data.uuids === undefined ? [] : data.uuids, requestedNames = data.names === undefined ? [] : data.names;
      if (data.type !== "cosmetics_get" || !Array.isArray(requestedIds) || !Array.isArray(requestedNames) ||
          requestedIds.length + requestedNames.length < 1 || requestedIds.length + requestedNames.length > 16 ||
          requestedIds.some(it => typeof it !== "string" || !uuid.test(it)) || requestedNames.some(it => typeof it !== "string" || !username.test(it)) ||
          new Set(requestedNames.map(name => name.toLowerCase())).size !== requestedNames.length) {
        ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, error: "invalid_lookup" })); return;
      }
      const identities = (requestedNames as string[]).flatMap(name => {
        // Only verified relay identities may resolve names. Stats/friend uploads are not identity proofs.
        const matches = this.ctx.storage.sql.exec<{ uuid: string; name: string }>(
          "SELECT uuid,name FROM mod_users WHERE name = ? COLLATE NOCASE LIMIT 2", name).toArray();
        return matches.length === 1 ? matches : []; // A renamed/reassigned name must never pick an ambiguous owner.
      });
      const ids = [...new Set([...(requestedIds as string[]), ...identities.map(value => value.uuid)])];
      session.cosmeticWatching = watchCosmetics(session.cosmeticWatching, ids); ws.serializeAttachment(session);
      const profiles = legacyRelay(this.env) ? ids.map(id => this.cosmetics.get(id)) : await this.env.ACCOUNT_STORE.getByName("global").communityCosmeticProfiles(ids);
      if (!await this.authorized(ws, session)) return;
      ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, records: profiles.map(profile => cosmeticPayload(profile, session.cosmeticsVersion)),
        ...(data.names === undefined ? {} : { identities }) }));
      return;
    }

    if (data.type === "digest_news_get" || data.type === "rng_subscribe" || data.type === "rng_publish") {
      const now = Date.now();
      if (encoder.encode(raw).length > 1024 || typeof data.id !== "string" || !uuid.test(data.id)) {
        ws.close(1008, "Invalid digest request"); return;
      }
      const type = data.type === "digest_news_get" ? "digest_news_result" : "rng_result";
      session.digestCredits = Math.min(6, (session.digestCredits ?? 6) + (now - (session.digestUpdated ?? now)) / 5000);
      session.digestUpdated = now;
      const allowed = session.digestCredits >= 1;
      if (allowed) session.digestCredits--;
      // Turning receiving off is always honored, even after a burst of requests.
      if (data.type === "rng_subscribe" && data.enabled === false) session.rngSubscribed = false;
      ws.serializeAttachment(session);
      if (!allowed) { ws.send(JSON.stringify({ type, id: data.id, error: "rate_limited", retryAt: now + 5000 })); return; }
      if (data.type === "digest_news_get") {
        if (data.source !== "game") { ws.send(JSON.stringify({ type, id: data.id, error: "invalid_source" })); return; }
        const result = await this.news.get(data.source);
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type, id: data.id, ...result }));
      } else if (data.type === "rng_subscribe") {
        if (typeof data.enabled !== "boolean") { ws.send(JSON.stringify({ type, id: data.id, error: "invalid_subscription" })); return; }
        session.rngSubscribed = data.enabled;
        ws.serializeAttachment(session);
        const rows = data.enabled ? this.rng.recentRecords(now) : [];
        const policies = await this.sharingPermissions(ws, session, rows.map(row => ({ uuid: row.author, category: "rng", recordedAt: row.event.occurredAt })));
        if (ws.readyState !== WebSocket.OPEN) return;
        ws.send(JSON.stringify({ type, id: data.id, events: rows.flatMap((row, index) => this.sharingCurrent(row.author, "rng", policies[index]) ? [{ ...rngPayload(row.event, session.rngMetadataVersion), player: policies[index].publicName ? row.event.player : "Anonymous" }] : []) }));
      } else {
        const occurredAt = (data.event as { occurredAt?: unknown } | undefined)?.occurredAt;
        const consent = (await this.sharingPermissions(ws, session, [{ uuid: session.uuid!, category: "rng", recordedAt: typeof occurredAt === "number" ? occurredAt : undefined }]))[0];
        if (ws.readyState !== WebSocket.OPEN) return;
        if (!consent.allowed) { ws.send(JSON.stringify({ type, id: data.id, stored: false, error: "sharing_disabled" })); return; }
        if (!this.sharingCurrent(session.uuid!, "rng", consent)) return;
        // Privacy projection must not coerce malformed caller input into a valid report.
        const showName = typeof data.showName === "boolean" ? data.showName && consent.publicName : data.showName;
        const { result, forwarding } = this.ctx.storage.transactionSync(() => {
          const result = this.rng.publish(session.uuid!, session.name!, { ...data, showName }, now);
          const forwarding = result.event && !legacyRelay(this.env) ? this.rngForward.enqueue({ uuid: session.uuid!, accountId: session.accountId!, sourceId: (data.event as { id: string }).id, event: result.event }, now) : null;
          return { result, forwarding };
        });
        if (forwarding?.ok) await this.rngForward.arm();
        ws.send(JSON.stringify({ type, id: data.id, stored: result.stored, ...(result.error || forwarding?.error ? { error: result.error ?? forwarding?.error } : {}),
          ...(forwarding?.ok ? { delivery: "queued" } : {}) }));
        if (forwarding?.ok) this.ctx.waitUntil(this.rngForward.drain());
        if (result.event) {
          for (const peer of this.ctx.getWebSockets()) {
            const recipient = peer.deserializeAttachment() as Session;
            if (peer !== ws && peer.readyState === WebSocket.OPEN && recipient.name && recipient.rngSubscribed) {
              const current = (await this.sharingPermissions(peer, recipient, [{ uuid: session.uuid!, category: "rng", recordedAt: result.event.occurredAt }]))[0];
              if (peer.readyState === WebSocket.OPEN && this.sharingCurrent(session.uuid!, "rng", current)) try { peer.send(JSON.stringify({ type: "rng_event", event: { ...rngPayload(result.event, recipient.rngMetadataVersion), player: current.publicName ? result.event.player : "Anonymous" } })); } catch { /* Recipient disconnected during delivery. */ }
            }
          }
        }
      }
      return;
    }

    if (data.type === "party_set" || data.type === "party_get") {
      const now = Date.now();
      session.partyCredits = Math.min(6, (session.partyCredits ?? 6) + (now - (session.partyUpdated ?? now)) / 1000);
      session.partyUpdated = now;
      if (session.partyCredits < 1) { ws.send(JSON.stringify({ type: "party_result", id: data.id, error: "rate_limited" })); return; }
      session.partyCredits--;
      if (data.type === "party_set") {
        const consent = (await this.sharingPermissions(ws, session, [{ uuid: session.uuid!, category: "dungeons" }]))[0];
        if (ws.readyState !== WebSocket.OPEN) return;
        const limit = data.maxPbMillis ?? null;
        const selectedClass = data.selectedClass ?? undefined;
        if (typeof data.floor !== "string" || !/^[FM][1-7]$/.test(data.floor) || typeof data.open !== "boolean" ||
            (selectedClass !== undefined && (typeof selectedClass !== "string" || !classes.includes(selectedClass))) ||
            (limit !== null && (typeof limit !== "number" || !Number.isSafeInteger(limit) || limit <= 0 || limit > 59999999))) {
          ws.close(1008, "Invalid party policy"); return;
        }
        // A policy belongs to the authenticated socket, never to a client-supplied name.
        const party = { floor: data.floor, maxPbMillis: limit, open: data.open, selectedClass: this.sharingCurrent(session.uuid!, "dungeons", consent) ? selectedClass as string | undefined : undefined };
        const changed = JSON.stringify(session.party) !== JSON.stringify(party);
        if (party.selectedClass && party.selectedClass !== session.party?.selectedClass) {
          this.stats.selectClass(session.name, session.uuid!, party.selectedClass);
        }
        session.party = party;
        ws.serializeAttachment(session);
        if (changed) await this.broadcast(ws, { type: "party_update", name: session.name, uuid: session.uuid, ...party }, party.selectedClass ? [{ uuid: session.uuid!, category: "dungeons" }] : undefined);
        return;
      }
      if (typeof data.id !== "string" || !uuid.test(data.id) || !Array.isArray(data.names) || data.names.length > 32 ||
          data.names.some(name => typeof name !== "string" || !username.test(name))) {
        ws.close(1008, "Invalid party lookup"); return;
      }
      ws.serializeAttachment(session);
      const parties: Record<string, (JoinPolicy & { uuid: string }) | null> = Object.create(null);
      for (const name of data.names as string[]) parties[name.toLowerCase()] = null;
      for (const other of this.ctx.getWebSockets()) {
        if (other === ws || other.readyState !== WebSocket.OPEN) continue;
        const peer = other.deserializeAttachment() as Session;
        const name = peer.name?.toLowerCase();
        if (name && Object.hasOwn(parties, name) && peer.party && peer.uuid) parties[name] = { ...peer.party, uuid: peer.uuid };
      }
      const listed = Object.values(parties).filter((party): party is JoinPolicy & { uuid: string } => party !== null);
      const policies = await this.sharingPermissions(ws, session, listed.map(party => ({ uuid: party.uuid, category: "dungeons" })));
      if (ws.readyState !== WebSocket.OPEN) return;
      listed.forEach((party, index) => { if (!this.sharingCurrent(party.uuid, "dungeons", policies[index])) delete party.selectedClass; });
      ws.send(JSON.stringify({ type: "party_result", id: data.id, parties }));
      return;
    }

    if (data.type === "wealth_get" || data.type === "wealth_put") {
      let permissions: { uuid: string; decision: SharingDecision }[] = [];
      const now = Date.now();
      if (encoder.encode(raw).length > 2048 || typeof data.id !== "string" || !uuid.test(data.id) ||
          typeof data.name !== "string" || !username.test(data.name) ||
          (data.uuid !== undefined && (typeof data.uuid !== "string" || !uuid.test(data.uuid))) ||
          (data.canFetch !== undefined && typeof data.canFetch !== "boolean")) {
        ws.close(1008, "Invalid wealth request"); return;
      }
      session.wealthCredits = Math.min(6, (session.wealthCredits ?? 6) + (now - (session.wealthUpdated ?? now)) / 1000);
      session.wealthUpdated = now;
      session.wealthUploads = (session.wealthUploads ?? []).filter(it => it.expires > now);
      const allowed = session.wealthCredits >= 1;
      if (allowed) session.wealthCredits--;
      ws.serializeAttachment(session);
      if (!allowed) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, error: "rate_limited", retryAt: now + 5000 })); return; }
      if (!legacyRelay(this.env)) {
        const requests = [{ uuid: session.uuid!, category: "economy" as const }, ...(typeof data.uuid === "string" ? [{ uuid: data.uuid, category: "economy" as const, ...(data.type === "wealth_put" && typeof data.fetchedAt === "number" ? { recordedAt: data.fetchedAt } : {}) }] : [])];
        const decisions = await this.sharingPermissions(ws, session, requests); if (ws.readyState !== WebSocket.OPEN) return;
        permissions = requests.map((request, index) => ({ uuid: request.uuid, decision: decisions[index] }));
        if (data.type === "wealth_put" && permissions.some(value => !this.sharingCurrent(value.uuid, "economy", value.decision))) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record: null, stored: false, error: "sharing_disabled" })); return; }
      }
      if (data.type === "wealth_get") {
        const record = this.wealth.get(data.name, data.uuid as string | undefined, now, data.refresh === true && data.canFetch !== false);
        if (record && !await this.visibleRecord(ws, session, record, "economy", this.wealth.publisher(record.uuid))) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record: null, error: "sharing_disabled" })); return; }
        const matches = (grant: StatsGrant) => grant.name === (data.name as string).toLowerCase() ||
          (data.uuid !== undefined && grant.uuid === data.uuid);
        // Cache-only viewers and clients in provider backoff must not reserve work they cannot perform.
        if (data.canFetch === false) {
          session.wealthUploads = session.wealthUploads.filter(grant => !matches(grant));
          ws.serializeAttachment(session);
          ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record })); return;
        }
        if (record) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record })); return; }
        if (permissions.some(value => !this.sharingCurrent(value.uuid, "economy", value.decision))) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record: null, error: "sharing_disabled" })); return; }
        const own = session.wealthUploads.find(grant => matches(grant) &&
          (!grant.uuid || grant.uuid === data.uuid));
        if (own) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record: null, upload: own.token })); return; }
        // Socket attachments retain leases during hibernation. One client fetches each missing player.
        const owner = this.ctx.getWebSockets().filter(other => other.readyState === WebSocket.OPEN)
          .flatMap(other => ((other.deserializeAttachment() as Session).wealthUploads ?? []))
          .find(grant => matches(grant) && grant.expires > now);
        if (owner) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, retryAt: Math.min(owner.expires, now + 5000) })); return; }
        const grant = { name: data.name.toLowerCase(), uuid: data.uuid as string | undefined,
          token: crypto.randomUUID().replaceAll("-", ""), expires: now + 120_000 };
        session.wealthUploads = [...session.wealthUploads.slice(-3), grant];
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record: null, upload: grant.token }));
      } else {
        const grant = session.wealthUploads.find(it => (it.name === (data.name as string).toLowerCase() ||
          (it.uuid !== undefined && it.uuid === data.uuid)) &&
          it.token === data.upload && (!it.uuid || it.uuid === data.uuid));
        const wealth = validateWealth(data.wealth);
        if (!grant || !wealth || typeof data.uuid !== "string" || !uuid.test(data.uuid) ||
            !Number.isSafeInteger(data.fetchedAt) || (data.fetchedAt as number) > now + 60_000 || (data.fetchedAt as number) <= now - WEALTH_TTL) {
          ws.send(JSON.stringify({ type: "wealth_result", id: data.id, error: "invalid_upload" })); return;
        }
        session.wealthUploads = session.wealthUploads.filter(it => it !== grant);
        ws.serializeAttachment(session);
        if (permissions.some(value => !this.sharingCurrent(value.uuid, "economy", value.decision))) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, stored: false, error: "sharing_disabled" })); return; }
        const stored = this.wealth.put({ name: data.name, uuid: data.uuid, wealth, fetchedAt: Math.min(data.fetchedAt as number, now) }, now, session.uuid);
        // Only the requesting client receives totals; avoid broadcasting a wealth update to the entire room.
        ws.send(JSON.stringify({ type: "wealth_result", id: data.id, stored }));
      }
      return;
    }

    if (data.type === "stats_get" || data.type === "stats_put") {
      let permissions: { uuid: string; decision: SharingDecision }[] = [];
      const now = Date.now();
      session.cacheCredits = Math.min(30, (session.cacheCredits ?? 30) + (now - (session.cacheUpdated ?? now)) / 1000);
      session.cacheUpdated = now;
      if (session.cacheCredits < 1) { ws.send(JSON.stringify({ type: "stats_result", id: data.id, error: "rate_limited" })); return; }
      session.cacheCredits--;
      session.uploads = (session.uploads ?? []).filter(it => it.expires > now);
      ws.serializeAttachment(session);
      if (typeof data.id !== "string" || !uuid.test(data.id) || typeof data.name !== "string" || !username.test(data.name) ||
          (data.uuid !== undefined && (typeof data.uuid !== "string" || !uuid.test(data.uuid)))) { ws.close(1008, "Invalid stats request"); return; }
      if (!legacyRelay(this.env)) {
        const requests = [{ uuid: session.uuid!, category: "dungeons" as const }, ...(typeof data.uuid === "string" ? [{ uuid: data.uuid, category: "dungeons" as const, ...(data.type === "stats_put" && typeof data.fetchedAt === "number" ? { recordedAt: data.fetchedAt } : {}) }] : [])];
        const decisions = await this.sharingPermissions(ws, session, requests); if (ws.readyState !== WebSocket.OPEN) return;
        permissions = requests.map((request, index) => ({ uuid: request.uuid, decision: decisions[index] }));
        if (data.type === "stats_put" && permissions.some(value => !this.sharingCurrent(value.uuid, "dungeons", value.decision))) { ws.send(JSON.stringify({ type: "stats_result", id: data.id, record: null, stored: false, error: "sharing_disabled" })); return; }
      }
      if (data.type === "stats_get") {
        const record = this.stats.get(data.name, data.uuid as string | undefined, now);
        if (record && !await this.visibleRecord(ws, session, record, "dungeons", this.stats.publisher(record.uuid))) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "stats_result", id: data.id, record: null, error: "sharing_disabled" })); return; }
        const ownRefresh = session.liveUpdates && data.name.toLowerCase() === session.name.toLowerCase() && data.uuid === session.uuid;
        const grant: StatsGrant | undefined = record && !ownRefresh || permissions.some(value => !this.sharingCurrent(value.uuid, "dungeons", value.decision)) ? undefined : { name: data.name.toLowerCase(), uuid: data.uuid as string | undefined,
          token: crypto.randomUUID().replaceAll("-", ""), expires: now + 120_000 };
        if (grant) session.uploads = [...session.uploads.filter(it => it.name !== grant.name).slice(-3), grant];
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "stats_result", id: data.id, record, upload: grant?.token, ...(!record && !grant ? { error: "sharing_disabled" } : {}) }));
      } else {
        const grant = session.uploads.find(it => it.name === (data.name as string).toLowerCase() && it.token === data.upload && (!it.uuid || it.uuid === data.uuid));
        const stats = validateStats(data.stats);
        if (!grant || !stats || typeof data.uuid !== "string" || !Number.isSafeInteger(data.fetchedAt) ||
            (data.fetchedAt as number) > now + 60_000 || (data.fetchedAt as number) <= now - STATS_TTL) {
          ws.send(JSON.stringify({ type: "stats_result", id: data.id, error: "invalid_upload" })); return;
        }
        session.uploads = session.uploads.filter(it => it !== grant);
        ws.serializeAttachment(session);
        const record = { name: data.name, uuid: data.uuid, stats, fetchedAt: Math.min(data.fetchedAt as number, now) };
        const live = this.ctx.getWebSockets().find(other => other.readyState === WebSocket.OPEN &&
          (other.deserializeAttachment() as Session).uuid === record.uuid)?.deserializeAttachment() as Session | undefined;
        if (live?.name?.toLowerCase() === record.name.toLowerCase() && live.party?.selectedClass) {
          record.stats.selectedClass = live.party.selectedClass;
        }
        const ownRefresh = session.liveUpdates === true && data.uuid === session.uuid && data.name.toLowerCase() === session.name.toLowerCase();
        if (permissions.some(value => !this.sharingCurrent(value.uuid, "dungeons", value.decision))) { ws.send(JSON.stringify({ type: "stats_result", id: data.id, stored: false, error: "sharing_disabled" })); return; }
        const stored = this.stats.put(record, session.uuid!, now, ownRefresh);
        ws.send(JSON.stringify({ type: "stats_result", id: data.id, stored }));
        if (stored) await this.broadcast(ws, { type: "stats_update", record }, [{ uuid: session.uuid!, category: "dungeons", recordedAt: record.fetchedAt }, { uuid: record.uuid, category: "dungeons", recordedAt: record.fetchedAt }]);
      }
      return;
    }

    if (encoder.encode(raw).length > 2048) { ws.close(1009, "Message too large"); return; }

    const ordinary = data.type === "user_message";
    const receiptMessage = data.type === "ack" || data.type === "reject";
    if ((data.type !== "message" && !ordinary && !receiptMessage) || typeof data.id !== "string" || !/^[a-f0-9]{32}$/.test(data.id) ||
        (ordinary && !session.userMessages) || (data.type === "reject" && !session.userMessages) ||
        typeof data.to !== "string" || !username.test(data.to) || (!receiptMessage && (typeof data.text !== "string" ||
        data.text.length < 1 || data.text.length > 256 || /[\x00-\x1f\x7f§]/.test(data.text)))) {
      ws.close(1008, "Invalid relay message"); return;
    }
    const now = Date.now();
    session.credits = Math.min(12, session.credits + (now - session.updated) / 2500);
    session.updated = now;
    if (session.credits < 1) {
      if (ordinary) { ws.serializeAttachment(session); ws.send(JSON.stringify({ type: "error", id: data.id, code: "rate_limited" })); }
      else ws.close(1008, "Message rate exceeded");
      return;
    }
    session.credits--;
    if (receiptMessage) {
      const receipt = session.inbox.find(it => it.id === data.id && it.from.toLowerCase() === (data.to as string).toLowerCase());
      if (!receipt || receipt.expires <= now || (data.type === "reject" && !receipt.user)) {
        // Late/duplicate receipts cannot be distinguished from invented IDs. Never forward either.
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "error", id: data.id, code: "invalid_receipt" })); return;
      }
      session.inbox = session.inbox.filter(it => it !== receipt);
    } else {
      if (session.seen.includes(data.id)) { ws.close(1008, "Duplicate message"); return; }
      session.seen = [...session.seen.slice(-7), data.id];
    }
    ws.serializeAttachment(session);

    // ponytail: scan at most 128 sockets per room; add a recipient index if rooms grow.
    const target = this.ctx.getWebSockets().find(other => other !== ws && other.readyState === WebSocket.OPEN &&
      (other.deserializeAttachment() as Session).name?.toLowerCase() === (data.to as string).toLowerCase());
    if (!target) {
      console.info({ event: "relay_delivery", id: data.id, outcome: "offline" });
      ws.send(JSON.stringify({ type: "error", id: data.id, code: "offline" })); return;
    }
    try {
      if (!await this.authorized(target, target.deserializeAttachment() as Session)) {
        ws.send(JSON.stringify({ type: "error", id: data.id, code: "offline" })); return;
      }
      if (!receiptMessage) {
        const recipient = target.deserializeAttachment() as Session;
        if (ordinary && !recipient.userMessages) { ws.send(JSON.stringify({ type: "error", id: data.id, code: "recipient_unsupported" })); return; }
        recipient.inbox = [...recipient.inbox.filter(it => it.expires > now), { id: data.id, from: session.name, expires: now + 10000, ...(ordinary ? { user: true } : {}) }];
        // Reject before delivery rather than evicting a still-valid receipt. Attachments are limited to 2 KiB.
        if (recipient.inbox.length > 16 || encoder.encode(JSON.stringify(recipient)).length > 2048) {
          ws.send(JSON.stringify({ type: "error", id: data.id, code: "recipient_busy" })); return;
        }
        target.serializeAttachment(recipient);
      }
      target.send(JSON.stringify(data.type === "reject" ? { type: "error", id: data.id, code: "recipient_disallowed" } :
        { type: data.type, id: data.id, from: session.name, uuid: session.uuid, text: data.text }));
      // Correlate delivery failures without logging names, private messages, or account proof.
      console.info({ event: "relay_delivery", id: data.id, outcome: data.type === "ack" ? "acknowledged" : "forwarded" });
    } catch {
      console.info({ event: "relay_delivery", id: data.id, outcome: "disconnected" });
      ws.send(JSON.stringify({ type: "error", id: data.id, code: "offline" }));
    }
  }

  private async migrateLegacyAccounts(room: string): Promise<boolean> {
    const sql = this.ctx.storage.sql;
    const cosmetics = sql.exec<{cursor: string; done: number}>("SELECT cursor,done FROM cosmetic_migration_state WHERE id=1").toArray()[0];
    if (!cosmetics?.done) {
      const players = sql.exec<{uuid: string}>("SELECT uuid FROM cosmetics_public WHERE uuid>? ORDER BY uuid LIMIT 128", cosmetics?.cursor ?? "").toArray();
      await this.env.ACCOUNT_STORE.getByName("global").importCommunityCosmetics(room, players.map(player => this.cosmetics.get(player.uuid)));
      sql.exec("INSERT INTO cosmetic_migration_state VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET cursor=MAX(cursor,excluded.cursor),done=MAX(done,excluded.done)", players.at(-1)?.uuid ?? cosmetics?.cursor ?? "", players.length < 128 ? 1 : 0);
      if (players.length === 128) return false;
    }
    const previous = sql.exec<{ cursor: string; done: number }>("SELECT cursor,done FROM account_migration_state WHERE id=1").toArray()[0];
    if (previous?.done) return true;
    const rows = sql.exec<{ discord: string; uuid: string; linked_at: number }>(
      "SELECT discord,uuid,linked_at FROM cosmetics_links WHERE discord>? ORDER BY discord LIMIT 250", previous?.cursor ?? "").toArray();
    if (rows.length) {
      const result = await this.env.ACCOUNT_STORE.getByName("global").importCosmeticLinks(room, rows);
      if (result.conflicts) console.warn({ event: "legacy_account_conflict", count: result.conflicts, room });
    }
    // Global import is idempotent, so a crash between stores cannot transfer ownership or duplicate a grant.
    sql.exec("INSERT INTO account_migration_state VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET cursor=MAX(cursor,excluded.cursor),done=MAX(done,excluded.done)",
      rows.at(-1)?.discord ?? previous?.cursor ?? "", rows.length < 250 ? 1 : 0);
    return rows.length < 250;
  }

  private async authorized(ws: WebSocket, session: IrcSession, heartbeat = false): Promise<boolean> {
    if (session.bridge) return true;
    if (legacyRelay(this.env)) return true;
    if (ws.readyState !== WebSocket.OPEN) return false;
    try {
      if (!session.accountId || !session.uuid || !session.communitySessionId || session.generation === undefined) {
        ws.close(4007, "Connect Discord in Sky-Myce"); return false;
      }
      if (!Number.isSafeInteger(session.leaseExpiresAt) || session.leaseExpiresAt! <= Date.now()) {
        ws.close(4006, "Authorization renewal required"); return false;
      }
      const input = { accountId: session.accountId,
        uuid: session.uuid, sessionId: session.communitySessionId, generation: session.generation,
        heartbeatAt: this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime(), connectionId: session.connectionId };
      const store = this.env.ACCOUNT_STORE.getByName("global");
      const state = heartbeat ? await store.consumeCommunitySessionRequest(input) : await store.checkCommunitySession(input);
      if (!state.ok) {
        if (ws.readyState === WebSocket.OPEN) {
          if (["rate_limited", "traffic_cooldown", "service_busy"].includes(state.error ?? "")) {
            ws.close(1013, state.error!); return false;
          }
          ws.send(JSON.stringify({ type: "authorization_revoked", reason: state.error ?? "session_expired" }));
          ws.close(state.error === "identity_blacklisted" ? 4005 : 4006, state.error ?? "session_expired");
        }
        return false;
      }
      if (session.leaseExpiresAt! <= Date.now()) {
        if (ws.readyState === WebSocket.OPEN) ws.close(4006, "Authorization renewal required");
        return false;
      }
      return ws.readyState === WebSocket.OPEN;
    } catch {
      // An unavailable authority cannot grant online access, but it is not a revocation.
      if (ws.readyState === WebSocket.OPEN) ws.close(1013, "Authorization service unavailable");
      return false;
    }
  }

  private trafficDiagnostic(event: string): void {
    if (Date.now() < this.trafficDiagnosticAt) return;
    this.trafficDiagnosticAt = Date.now() + 60_000;
    console.warn({ event });
  }

  private signalGateway(): void {
    if (!this.env.GLOBAL_CHAT_GATEWAY_CONTROL || Date.now() < this.gatewaySignalAt) return;
    this.gatewaySignalAt = Date.now() + 25_000;
    this.ctx.waitUntil(this.env.GLOBAL_CHAT_GATEWAY_CONTROL.wake().catch(() => console.warn({ event: "gateway_wake_unavailable" })));
  }

  /** Binding-only revocation push. Current state is still checked on every protected request. */
  async invalidateAccount(accountId: string): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (session.accountId === accountId && ws.readyState === WebSocket.OPEN) await this.authorized(ws, session);
    }
  }
  private sharingCurrent(player: string, category: SharingCategory, decision: Pick<SharingDecision, "allowed"> & Partial<SharingDecision>): boolean {
    if (!decision?.allowed) return false;
    const cutoff = this.ctx.storage.sql.exec<{ not_before: number }>("SELECT not_before FROM room_sharing_cutoffs WHERE uuid=? AND category=?", player, category).toArray()[0]?.not_before ?? 0;
    return cutoff <= (decision.notBefore ?? 0);
  }
  private async sharingPermissions(ws: WebSocket, session: Session, requests: {uuid: string; category: SharingCategory; recordedAt?: number}[]): Promise<SharingDecision[]> {
    if (legacyRelay(this.env)) return requests.map(() => ({ allowed: true, publicName: true, revision: 0, notBefore: 0 }));
    const denied = () => requests.map(() => ({ allowed: false, publicName: false, revision: 0, notBefore: 0 }));
    if (!session.accountId || !session.communitySessionId || !session.uuid || session.generation === undefined || !session.leaseExpiresAt || session.leaseExpiresAt <= Date.now()) { if (ws.readyState === WebSocket.OPEN) ws.close(4006, "Community renewal required"); return denied(); }
    try {
      // One authority snapshot checks the recipient's current session and all subject/contributor policies.
      const result = await this.env.ACCOUNT_STORE.getByName("global").checkCommunitySharing({ sessionId: session.communitySessionId, accountId: session.accountId, uuid: session.uuid, generation: session.generation }, requests);
      if (!result.ok) { if (ws.readyState === WebSocket.OPEN) ws.close(result.error === "session_expired" ? 4006 : 4005, "Community authorization required"); return denied(); }
      return result.decisions;
    } catch { if (ws.readyState === WebSocket.OPEN) ws.close(1013, "Community service unavailable"); return denied(); }
  }
  private async visibleRecord(ws: WebSocket, session: Session, record: { uuid: string; fetchedAt: number }, category: SharingCategory, publisher: string | null): Promise<boolean> {
    if (legacyRelay(this.env)) return true;
    const players = [...new Set([record.uuid, ...(publisher ? [publisher] : [])])];
    const policies = await this.sharingPermissions(ws, session, players.map(player => ({ uuid: player, category, recordedAt: record.fetchedAt })));
    if (ws.readyState !== WebSocket.OPEN) return false;
    return players.every((player, index) => this.sharingCurrent(player, category, policies[index]));
  }
  async applySharing(players: string[], policy: SharingState, suppressed: string[]): Promise<void> {
    if (players.length > 256 || players.some(player => !uuid.test(player))) return;
    for (const player of players) for (const category of ["dungeons", "economy", "rng", "activity", "leaderboards"] as const) {
      this.ctx.storage.sql.exec("INSERT INTO room_sharing_cutoffs VALUES(?,?,?) ON CONFLICT(uuid,category) DO UPDATE SET not_before=MAX(not_before,excluded.not_before)", player, category, policy.notBefore[category]);
    }
    await this.suppressSharing(players, suppressed);
  }
  /** Privacy changes remove persisted contributions and outstanding uploads across every room. */
  async suppressSharing(players: string[], categories: string[]): Promise<void> {
    if (players.length > 256 || players.some(player => !uuid.test(player))) return;
    if (categories.includes("rng")) this.rngForward.suppress(players);
    const rng = categories.includes("rng") ? this.rng.deleteAuthors(players) : [];
    const stats = categories.includes("dungeons") ? this.stats.deleteSubjects(players) : [];
    const wealth = categories.includes("economy") ? this.wealth.deleteSubjects(players) : [];
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (categories.includes("dungeons") && players.includes(session.uuid ?? "") && session.party) delete session.party.selectedClass;
      if (categories.includes("dungeons")) session.uploads = players.includes(session.uuid ?? "") ? [] : session.uploads?.filter(grant => !grant.uuid || !players.includes(grant.uuid));
      if (categories.includes("economy")) session.wealthUploads = players.includes(session.uuid ?? "") ? [] : session.wealthUploads?.filter(grant => !grant.uuid || !players.includes(grant.uuid));
      ws.serializeAttachment(session);
      if (ws.readyState !== WebSocket.OPEN || !session.name || !await this.authorized(ws, session)) continue;
      for (let index = 0; index < rng.length; index += 128) ws.send(JSON.stringify({ type: "rng_removed", ids: rng.slice(index, index + 128) }));
      for (let index = 0; index < stats.length; index += 128) ws.send(JSON.stringify({ type: "stats_removed", uuids: stats.slice(index, index + 128) }));
      for (let index = 0; index < wealth.length; index += 128) ws.send(JSON.stringify({ type: "wealth_removed", uuids: wealth.slice(index, index + 128) }));
    }
  }

  /** Binding-only global coordination; public socket handlers supply the verified sender. */
  async globalChatMinecraft(sender: GlobalChatSender, input: GlobalChatSend): Promise<GlobalChatResult> {
    return this.irc.globalMinecraft(sender, input);
  }
  async globalChatDeliver(message: GlobalChatMessage): Promise<void> { await this.irc.deliverGlobal(message); }
  async globalChatOperation(sender: GlobalChatSender, input: Record<string, unknown>): Promise<GlobalChatResult> { return this.irc.globalOperation(sender, input); }
  async globalChatUpdate(update: IrcUpdate, mines: Record<string, string[]>): Promise<void> { await this.irc.deliverUpdate(update, mines); }
  async partyChatRegister(sender: PartyChatIdentity, input: Record<string, unknown>): Promise<GlobalChatResult> { return this.irc.partyRegister(sender, input); }
  async partyChatMinecraft(sender: PartyChatIdentity, partyId: string, input: GlobalChatSend): Promise<GlobalChatResult> { return this.irc.partyMinecraft(sender, partyId, input); }
  async partyChatOperation(sender: PartyChatIdentity, partyId: string, input: Record<string, unknown>): Promise<GlobalChatResult> { return this.irc.partyOperation(sender, partyId, input); }
  async partyChatDeliver(message: PartyChatMessage): Promise<void> { await this.irc.deliverParty(message); }
  async partyChatUpdate(update: IrcUpdate, mines: Record<string, string[]>): Promise<void> { if (update.scope === "party") await this.irc.deliverUpdate(update, mines); }
  /** Internal lookup only; chat identity always retains the independently verified Minecraft name. */
  ircCosmeticName(uuid: string): string | null { return /^[a-f0-9]{32}$/.test(uuid) ? this.cosmetics.get(uuid).name : null; }

  private async broadcast(sender: WebSocket, packet: object, privacy?: {uuid: string; category: SharingCategory; recordedAt?: number}[]): Promise<void> {
    const text = JSON.stringify(packet);
    for (const peer of this.ctx.getWebSockets()) {
      const session = peer.deserializeAttachment() as Session;
      // Older clients reject unknown packet types. Opt-in survives hibernation with the socket.
      if (peer !== sender && peer.readyState === WebSocket.OPEN && session.name && session.liveUpdates) {
        if (privacy) {
          const policies = await this.sharingPermissions(peer, session, privacy);
          if (peer.readyState !== WebSocket.OPEN || privacy.some((request, index) => !this.sharingCurrent(request.uuid, request.category, policies[index]))) continue;
        } else if (!await this.authorized(peer, session)) continue;
        try { peer.send(text); } catch { /* The recipient disconnected during delivery. */ }
      }
    }
  }

  async alarm(): Promise<void> {
    if (this.env.COMMUNITY_SERVICES_ENABLED === "false") {
      for (const ws of this.ctx.getWebSockets()) if (ws.readyState === WebSocket.OPEN) ws.close(1013, "Community services temporarily paused");
      return;
    }
    await this.irc.flushGlobal();
    await this.rngForward.drain();
    let next = Infinity;
    let globalListener = false;
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (session.bridge || ws.readyState !== WebSocket.OPEN) continue;
      if (session.name) {
        if (!legacyRelay(this.env) && await this.authorized(ws, session)) {
          next = Math.min(next, Date.now() + 30_000, session.leaseExpiresAt!);
          if (currentIrcSession(ws, session)?.globalChatSubscribed) globalListener = true;
        }
        continue;
      }
      if (session.expires <= Date.now()) {
        console.warn({ event: "authentication_rejected", category: "challenge_timeout", retryable: true });
        ws.close(1008, "Authentication timed out");
      }
      else next = Math.min(next, session.expires);
    }
    if (globalListener) this.signalGateway();
    next = Math.min(next, this.irc.nextGlobalAlarm() ?? Infinity);
    next = Math.min(next, this.rngForward.nextAlarm() ?? Infinity);
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next);
  }

  webSocketClose(ws: WebSocket, code: number, _reason: string, wasClean: boolean): void {
    const session = ws.deserializeAttachment() as Session;
    this.recordDisconnect(session);
    console.info({ event: "websocket_close", code, clean: wasClean, authenticated: !!session.name,
      lifetimeMs: session.started === undefined ? undefined : Date.now() - session.started });
    // Safe with automatic close replies; also completes the handshake in local runtimes.
    ws.close(1000, "Client disconnected");
  }
  webSocketError(ws: WebSocket): void {
    this.recordDisconnect(ws.deserializeAttachment() as Session);
    console.error({ event: "websocket_error", authenticated: !!(ws.deserializeAttachment() as Session).name });
    ws.close(1011, "Connection error");
  }
  private recordDisconnect(session: Session): void {
    if (session.communitySessionId && session.connectionId) {
      this.ctx.waitUntil(this.env.ACCOUNT_STORE.getByName("global").markCommunitySessionOffline(session.communitySessionId, session.connectionId).catch(() => {}));
    }
  }
}
