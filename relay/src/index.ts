import { DurableObject } from "cloudflare:workers";
import { verifyAccount } from "./auth";
import { classes, SharedStats, STATS_TTL, validateStats, type StatsGrant } from "./stats";
import { SharedWealth, WEALTH_TTL, validateWealth } from "./wealth";
import { DigestNews, DigestRng, type DigestConfig } from "./digest";
import { PlayerCosmetics, codeHash, newLinkCode, normalizedCode, cosmeticPayload, watchCosmetics, watchesCosmetic, type CosmeticsConfig, type DiscordCommand } from "./cosmetics";
import { verifiedInteraction, discordCommand } from "./discord";

type RelayEnv = Env & DigestConfig & CosmeticsConfig;
type JoinPolicy = { floor: string; maxPbMillis: number | null; open: boolean; selectedClass?: string };
type Session = {
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
  digestCredits?: number;
  digestUpdated?: number;
};
const username = /^[A-Za-z0-9_]{1,16}$/;
const uuid = /^[a-f0-9]{32}$/;
const encoder = new TextEncoder();

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/discord/interactions") {
      if (!env.DISCORD_PUBLIC_KEY || !env.DISCORD_APPLICATION_ID) return new Response("Interactions not configured", { status: 503 });
      if (!(await env.CONNECT_LIMIT.limit({ key: `discord:${request.headers.get("CF-Connecting-IP") ?? "local"}` })).success) return new Response("Rate limited", { status: 429 });
      const data = await verifiedInteraction(request, env);
      if (!data) return new Response("Invalid signature or interaction", { status: 401 });
      if (data.type === 1) return Response.json({ type: 1 });
      const command = discordCommand(data);
      if (!command) return Response.json({ type: 4, data: { content: "Unsupported command.", flags: 64, allowed_mentions: { parse: [] } } });
      const room = env.DISCORD_INTERACTIONS_ROOM ?? "friends";
      if (!env.ROOMS.split(",").includes(room)) return new Response("Interactions room unavailable", { status: 503 });
      return Response.json(await env.RELAY_ROOMS.getByName(room).discordInteraction(command));
    }
    if (url.pathname === "/health") return Response.json({ service: "SkyMyce relay", version: 2 });
    if (url.pathname !== "/websocket") return new Response("Not found", { status: 404 });
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const room = url.searchParams.get("room") ?? "friends";
    if (!/^[a-z0-9_-]{1,32}$/.test(room) || !env.ROOMS.split(",").includes(room)) {
      return new Response("Room not allowed", { status: 403 });
    }
    // Identity is not verified yet; bound attempts by the Cloudflare-provided IP.
    if (!(await env.CONNECT_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") ?? "local" })).success) {
      return new Response("Too many connection attempts", { status: 429 });
    }
    return env.RELAY_ROOMS.getByName(room).fetch(request);
  },
} satisfies ExportedHandler<RelayEnv>;

export class RelayRoom extends DurableObject<RelayEnv> {
  private readonly stats: SharedStats;
  private readonly wealth: SharedWealth;
  private readonly news: DigestNews;
  private readonly rng: DigestRng;
  private readonly cosmetics: PlayerCosmetics;
  constructor(ctx: DurableObjectState, env: RelayEnv) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    this.stats = new SharedStats(ctx.storage.sql);
    this.wealth = new SharedWealth(ctx.storage.sql);
    this.news = new DigestNews(ctx.storage.sql, env);
    this.rng = new DigestRng(ctx.storage.sql);
    this.cosmetics = new PlayerCosmetics(ctx.storage);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS mod_users (
      uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)`);
    ctx.storage.sql.exec("CREATE INDEX IF NOT EXISTS mod_users_name ON mod_users(name COLLATE NOCASE)");
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

  /** Binding-only RPC. The public Worker verifies Discord's exact signed HTTP body first. */
  async discordInteraction(command: DiscordCommand): Promise<object> {
    const result = await this.cosmetics.command(command, this.env, Date.now());
    if (result.changed) {
      for (const ws of this.ctx.getWebSockets()) {
        const session = ws.deserializeAttachment() as Session;
        if (ws.readyState === WebSocket.OPEN && session.name && session.cosmetics && watchesCosmetic(session.cosmeticWatching, result.changed.uuid)) {
          try { ws.send(JSON.stringify({ type: "cosmetics_event", record: cosmeticPayload(result.changed, session.cosmeticsVersion) })); } catch { /* Reconnect lookup restores the newest revision. */ }
        }
      }
    }
    return result.response;
  }

  private registerUser(id: string, name: string): void {
    const now = Date.now();
    // Identity comes exclusively from verified account proofs, never wealth reports or friend lists.
    this.ctx.storage.sql.exec(`INSERT INTO mod_users (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
      ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen`, id, name, now, now);
  }

  async fetch(request: Request): Promise<Response> {
    const sockets = this.ctx.getWebSockets();
    const ip = request.headers.get("CF-Connecting-IP") ?? "local";
    if (sockets.length >= 128 || sockets.filter(ws => (ws.deserializeAttachment() as Session).ip === ip).length >= 8) {
      return new Response("Too many connections", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const now = Date.now();
    const session: Session = {
      challenge: crypto.randomUUID().replaceAll("-", ""), expires: now + 30000, started: now, ip,
      credits: 12, updated: now, seen: [], inbox: [],
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(session);
    server.send(JSON.stringify({ type: "challenge", serverId: session.challenge, protocol: 2 }));
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > session.expires) await this.ctx.storage.setAlarm(session.expires);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const session = ws.deserializeAttachment() as Session;
    if (typeof raw !== "string" || encoder.encode(raw).length > 8192) { ws.close(1009, "Message too large"); return; }
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(raw);
      if (!data || Array.isArray(data) || typeof data !== "object") throw new Error();
    } catch { ws.close(1008, "Invalid JSON message"); return; }

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
        this.registerUser(profile.id, profile.name);
        for (const other of this.ctx.getWebSockets()) {
          const previous = other.deserializeAttachment() as Session;
          if (other !== ws && (previous.uuid === profile.id || previous.name?.toLowerCase() === profile.name.toLowerCase())) {
            other.close(4001, "Account connected elsewhere");
          }
        }
        session.name = profile.name;
        session.uuid = profile.id;
        session.registered = true;
        session.liveUpdates = data.liveUpdates === true;
        session.userMessages = data.userMessages === true;
        session.cosmetics = data.cosmetics === true;
        session.cosmeticsVersion = data.cosmeticsVersion === 3 ? 3 : data.cosmeticsVersion === 2 ? 2 : 1;
        session.challenge = "";
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "ready", name: session.name, uuid: session.uuid, protocol: 2, statsCache: true, wealthCache: true, partyPolicies: true, liveUpdates: true, digestNews: true, rngFeed: true, userMessages: true, cosmetics: true, cosmeticsVersion: 3, cosmeticsNames: true }));
      } catch { console.error({ event: "verification_failed" }); ws.close(1013, "Minecraft verification unavailable; retry later"); }
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
        if (!this.env.DISCORD_PUBLIC_KEY || !this.env.DISCORD_APPLICATION_ID) { ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, error: "not_configured" })); return; }
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
      ws.send(JSON.stringify({ type: "cosmetics_result", id: data.id, records: ids.map(id => cosmeticPayload(this.cosmetics.get(id), session.cosmeticsVersion)),
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
        if (data.source !== "game" && data.source !== "alpha") { ws.send(JSON.stringify({ type, id: data.id, error: "invalid_source" })); return; }
        const result = await this.news.get(data.source);
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type, id: data.id, ...result }));
      } else if (data.type === "rng_subscribe") {
        if (typeof data.enabled !== "boolean") { ws.send(JSON.stringify({ type, id: data.id, error: "invalid_subscription" })); return; }
        session.rngSubscribed = data.enabled;
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type, id: data.id, events: data.enabled ? this.rng.recent(now) : [] }));
      } else {
        const result = this.rng.publish(session.uuid!, session.name, data, now);
        ws.send(JSON.stringify({ type, id: data.id, stored: result.stored, ...(result.error ? { error: result.error } : {}) }));
        if (result.event) {
          const packet = JSON.stringify({ type: "rng_event", event: result.event });
          for (const peer of this.ctx.getWebSockets()) {
            const recipient = peer.deserializeAttachment() as Session;
            if (peer !== ws && peer.readyState === WebSocket.OPEN && recipient.name && recipient.rngSubscribed) {
              try { peer.send(packet); } catch { /* Recipient disconnected during delivery. */ }
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
        const limit = data.maxPbMillis ?? null;
        const selectedClass = data.selectedClass ?? undefined;
        if (typeof data.floor !== "string" || !/^[FM][1-7]$/.test(data.floor) || typeof data.open !== "boolean" ||
            (selectedClass !== undefined && (typeof selectedClass !== "string" || !classes.includes(selectedClass))) ||
            (limit !== null && (typeof limit !== "number" || !Number.isSafeInteger(limit) || limit <= 0 || limit > 59999999))) {
          ws.close(1008, "Invalid party policy"); return;
        }
        // A policy belongs to the authenticated socket, never to a client-supplied name.
        const party = { floor: data.floor, maxPbMillis: limit, open: data.open, selectedClass: selectedClass as string | undefined };
        const changed = JSON.stringify(session.party) !== JSON.stringify(party);
        if (party.selectedClass && party.selectedClass !== session.party?.selectedClass) {
          this.stats.selectClass(session.name, session.uuid!, party.selectedClass);
        }
        session.party = party;
        ws.serializeAttachment(session);
        if (changed) this.broadcast(ws, { type: "party_update", name: session.name, uuid: session.uuid, ...party });
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
      ws.send(JSON.stringify({ type: "party_result", id: data.id, parties }));
      return;
    }

    if (data.type === "wealth_get" || data.type === "wealth_put") {
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
      if (data.type === "wealth_get") {
        const record = this.wealth.get(data.name, data.uuid as string | undefined, now, data.refresh === true && data.canFetch !== false);
        const matches = (grant: StatsGrant) => grant.name === (data.name as string).toLowerCase() ||
          (data.uuid !== undefined && grant.uuid === data.uuid);
        // Cache-only viewers and clients in provider backoff must not reserve work they cannot perform.
        if (data.canFetch === false) {
          session.wealthUploads = session.wealthUploads.filter(grant => !matches(grant));
          ws.serializeAttachment(session);
          ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record })); return;
        }
        if (record) { ws.send(JSON.stringify({ type: "wealth_result", id: data.id, record })); return; }
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
        const stored = this.wealth.put({ name: data.name, uuid: data.uuid, wealth, fetchedAt: Math.min(data.fetchedAt as number, now) }, now);
        // Only the requesting client receives totals; avoid broadcasting a wealth update to the entire room.
        ws.send(JSON.stringify({ type: "wealth_result", id: data.id, stored }));
      }
      return;
    }

    if (data.type === "stats_get" || data.type === "stats_put") {
      const now = Date.now();
      session.cacheCredits = Math.min(30, (session.cacheCredits ?? 30) + (now - (session.cacheUpdated ?? now)) / 1000);
      session.cacheUpdated = now;
      if (session.cacheCredits < 1) { ws.send(JSON.stringify({ type: "stats_result", id: data.id, error: "rate_limited" })); return; }
      session.cacheCredits--;
      session.uploads = (session.uploads ?? []).filter(it => it.expires > now);
      ws.serializeAttachment(session);
      if (typeof data.id !== "string" || !uuid.test(data.id) || typeof data.name !== "string" || !username.test(data.name) ||
          (data.uuid !== undefined && (typeof data.uuid !== "string" || !uuid.test(data.uuid)))) { ws.close(1008, "Invalid stats request"); return; }
      if (data.type === "stats_get") {
        const record = this.stats.get(data.name, data.uuid as string | undefined, now);
        const ownRefresh = session.liveUpdates && data.name.toLowerCase() === session.name.toLowerCase() && data.uuid === session.uuid;
        const grant: StatsGrant | undefined = record && !ownRefresh ? undefined : { name: data.name.toLowerCase(), uuid: data.uuid as string | undefined,
          token: crypto.randomUUID().replaceAll("-", ""), expires: now + 120_000 };
        if (grant) session.uploads = [...session.uploads.filter(it => it.name !== grant.name).slice(-3), grant];
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "stats_result", id: data.id, record, upload: grant?.token }));
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
        const stored = this.stats.put(record, session.uuid!, now, ownRefresh);
        ws.send(JSON.stringify({ type: "stats_result", id: data.id, stored }));
        if (stored) this.broadcast(ws, { type: "stats_update", record });
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

  private broadcast(sender: WebSocket, packet: object): void {
    const text = JSON.stringify(packet);
    for (const peer of this.ctx.getWebSockets()) {
      const session = peer.deserializeAttachment() as Session;
      // Older clients reject unknown packet types. Opt-in survives hibernation with the socket.
      if (peer !== sender && peer.readyState === WebSocket.OPEN && session.name && session.liveUpdates) {
        try { peer.send(text); } catch { /* The recipient disconnected during delivery. */ }
      }
    }
  }

  async alarm(): Promise<void> {
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (session.name) continue;
      if (session.expires <= Date.now()) {
        console.warn({ event: "authentication_rejected", category: "challenge_timeout", retryable: true });
        ws.close(1008, "Authentication timed out");
      }
      else next = Math.min(next, session.expires);
    }
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next);
  }

  webSocketClose(ws: WebSocket, code: number, _reason: string, wasClean: boolean): void {
    const session = ws.deserializeAttachment() as Session;
    console.info({ event: "websocket_close", code, clean: wasClean, authenticated: !!session.name,
      lifetimeMs: session.started === undefined ? undefined : Date.now() - session.started });
    // Safe with automatic close replies; also completes the handshake in local runtimes.
    ws.close(1000, "Client disconnected");
  }
  webSocketError(ws: WebSocket): void {
    console.error({ event: "websocket_error", authenticated: !!(ws.deserializeAttachment() as Session).name });
    ws.close(1011, "Connection error");
  }
}
