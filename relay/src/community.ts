import type { AccountStore } from "./accounts";
import { verifyAccount } from "./auth";
import { newLinkCode, normalizedCode } from "./cosmetics";
import { AuthorizationError, deny, encode64, randomToken, sha256 } from "./drm";
import type { DeveloperActor } from "./licensing";
import { CommunityTraffic } from "./traffic";

export type CommunityConfig = { DISCORD_OAUTH_CLIENT_ID?: string; DISCORD_OAUTH_CLIENT_SECRET?: string; DISCORD_OAUTH_REDIRECT_URI?: string; SKYMYCE_PUBLIC_URL?: string };
export type CommunityIdentity = { accountId: string; discordId: string; username: string | null; displayName: string | null;
  uuid?: string; uuids: { uuid: string; name: string; firstSeen: number; lastSeen: number }[]; blacklisted: boolean };
export type CommunityCredentials = { sessionId: string; accessToken: string; accessExpiresAt: number; refreshToken: string; refreshExpiresAt: number };
export type CommunitySessionCheck = { sessionId: string; accountId: string; uuid: string; generation: number; connectionId?: string; heartbeatAt?: number };
export type CommunitySessionResult = { ok: boolean; error?: string; accountId?: string; discordId?: string; uuid?: string; sessionId?: string;
  generation?: number; expiresAt?: number; connectionId?: string; retryAt?: number };
type CommunityOptions = Record<string, string | number | boolean>;
type PublicRow = Record<string, string | number | boolean | null>;
type SqlRow = Record<string, string | number | null>;
type DeviceReport = { identifier: string; source: string; platform: string; firstSeen: number; lastSeen: number;
  minecraft: { uuid: string; name: string }[]; relatedAccounts: { discordId: string; username: string | null; displayName: string | null }[] };
export type CommunityResult = { ok: boolean; error?: string; identity?: CommunityIdentity; credentials?: CommunityCredentials;
  invalidatedAccounts?: string[]; invalidatedMinecraft?: string[]; challengeId?: string; nonce?: string; expiresAt?: number;
  flowId?: string; browserUrl?: string | null; fallbackCode?: string; pollIntervalMs?: number; minecraftName?: string; state?: string;
  status?: string; token?: string; action?: string; target?: string; options?: CommunityOptions; identities?: CommunityIdentity[];
  entries?: PublicRow[]; sessions?: PublicRow[]; events?: (PublicRow & { metadata: string })[]; overview?: Record<string, number>; devices?: DeviceReport[]; retryAt?: number };
type Account = { account_id: string; discord_id: string; generation: number; status: string; is_admin: number };
type Flow = { flow_id: string; launch_hash: string; poll_hash: string; code_hash: string; uuid: string; name: string; device_json: string | null;
  mod_version: string; expires_at: number; account_id: string | null; session_id: string | null; cancelled_at: number | null;
  state_hash: string | null; cookie_hash: string | null; oauth_claimed_at: number | null };
type Session = { session_id: string; account_id: string; uuid: string; generation: number; refresh_hash: string; refresh_expires_at: number;
  access_hash: string; access_expires_at: number; created_at: number; last_seen: number; revoked_at: number | null; mod_version: string;
  connected_at: number | null; connection_id: string | null };
type Device = { identifier: string; source: string; platform: string };
type Hooks = { ensure(discordId: string): Account; account(id: string): Account | undefined; isAdmin(actor: DeveloperActor): boolean;
  interaction(actor: DeveloperActor): void; limit(key: string, count: number): void; audit(actor: string, account: string | null, action: string, metadata?: Record<string, unknown>): void;
  mergeAccount(source: string, target: string): void; mergeUuid(uuid: string, target: string): void; detachUuid(uuid: string, source: string): void };
const id = /^[0-9]{17,20}$/, uuid = /^[a-f0-9]{32}$/, token = /^[A-Za-z0-9_-]{43}$/;
const flowReference = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const FLOW_TTL = 10 * 60_000, ACCESS_TTL = 15 * 60_000, REFRESH_TTL = 90 * 86400_000;
const digestEncoder = new TextEncoder();
const sameDigest = (left: string, right: string) => crypto.subtle.timingSafeEqual(digestEncoder.encode(left), digestEncoder.encode(right));
const failure = (error: unknown): CommunityResult => ({ ok: false, error: error instanceof AuthorizationError ? error.code : "community_unavailable" });
const cleanName = (value: unknown) => typeof value === "string" && value.length <= 100 && !/[\x00-\x1f\x7f]/.test(value) ? value : "Discord user";
function device(raw: unknown): Device | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const value = raw as Record<string, unknown>;
  if (typeof value.identifier !== "string" || !/^[a-f0-9]{64}$/.test(value.identifier) ||
    !["windows-smbios", "linux-dmi", "linux-machine-id", "linux-dbus-machine-id", "macos-platform", "software-installation", "installation", "unavailable"].includes(String(value.source)) ||
    !["windows", "linux", "macos", "other"].includes(String(value.platform))) return null;
  return { identifier: value.identifier, source: String(value.source), platform: String(value.platform) };
}

/** Discord identity is shared with the existing account store. Device observations never participate in authorization. */
export class CommunityAccounts {
  private readonly traffic: CommunityTraffic;
  constructor(private sql: SqlStorage, private transaction: <T>(fn: () => T) => T, private env: CommunityConfig, private hooks: Hooks) {
    this.traffic = new CommunityTraffic(sql, (accountId, retryAt) => this.hooks.audit("system", accountId, "traffic_cooldown", { retryAt, reason: "Repeated incoming traffic limit violations", source: "backend" }));
    sql.exec(`CREATE TABLE IF NOT EXISTS community_challenges(id TEXT PRIMARY KEY,purpose TEXT NOT NULL,nonce TEXT NOT NULL,expires_at INTEGER NOT NULL,used_at INTEGER);
      CREATE TABLE IF NOT EXISTS community_connect_flows(flow_id TEXT PRIMARY KEY,launch_hash TEXT UNIQUE NOT NULL,poll_hash TEXT NOT NULL,
        code_hash TEXT UNIQUE NOT NULL,uuid TEXT NOT NULL,name TEXT NOT NULL,device_json TEXT,mod_version TEXT NOT NULL,expires_at INTEGER NOT NULL,
        account_id TEXT,session_id TEXT,cancelled_at INTEGER,state_hash TEXT UNIQUE,cookie_hash TEXT,oauth_claimed_at INTEGER);
      CREATE TABLE IF NOT EXISTS community_sessions(session_id TEXT PRIMARY KEY,account_id TEXT NOT NULL,uuid TEXT NOT NULL,generation INTEGER NOT NULL,
        refresh_hash TEXT UNIQUE NOT NULL,refresh_expires_at INTEGER NOT NULL,access_hash TEXT UNIQUE NOT NULL,access_expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,last_seen INTEGER NOT NULL,revoked_at INTEGER,mod_version TEXT NOT NULL,connected_at INTEGER,connection_id TEXT);
      CREATE INDEX IF NOT EXISTS community_session_owner ON community_sessions(account_id,uuid);
      CREATE TABLE IF NOT EXISTS community_blacklist(kind TEXT NOT NULL,identity TEXT NOT NULL,reason TEXT NOT NULL,actor TEXT NOT NULL,
        created_at INTEGER NOT NULL,removed_at INTEGER,revision INTEGER NOT NULL,PRIMARY KEY(kind,identity));
      CREATE TABLE IF NOT EXISTS community_device_observations(account_id TEXT NOT NULL,uuid TEXT NOT NULL,identifier TEXT NOT NULL,source TEXT NOT NULL,
        platform TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,PRIMARY KEY(account_id,uuid,identifier,source));
      CREATE INDEX IF NOT EXISTS community_device_reference ON community_device_observations(identifier);
      CREATE TABLE IF NOT EXISTS community_management(token TEXT PRIMARY KEY,actor TEXT NOT NULL,guild TEXT NOT NULL,kind TEXT NOT NULL,
        action TEXT NOT NULL,target TEXT NOT NULL,options TEXT NOT NULL,snapshot TEXT NOT NULL,expires_at INTEGER NOT NULL,used_at INTEGER);
      CREATE TABLE IF NOT EXISTS community_migrations(id TEXT PRIMARY KEY,at INTEGER NOT NULL);`);
    this.transaction(() => {
      if (sql.exec("SELECT id FROM community_migrations WHERE id='legacy-identities-v1'").toArray().length) return;
      // Deliberate account suspensions remain identity moderation; old licensing and HWID restrictions do not migrate.
      sql.exec(`INSERT OR IGNORE INTO community_blacklist SELECT 'discord',discord_id,'Migrated account suspension','migration',?,NULL,1 FROM sky_accounts WHERE status<>'active'`, Date.now());
      sql.exec(`INSERT OR IGNORE INTO community_device_observations SELECT i.account_id,m.uuid,COALESCE(i.hardware_hash,i.device_hash),
        CASE WHEN i.hardware_hash IS NULL THEN 'installation' WHEN i.platform='windows' THEN 'windows-smbios' WHEN i.platform='linux' THEN 'linux-machine-id' ELSE 'installation' END,
        COALESCE(i.platform,'other'),i.registered_at,MAX(i.registered_at,m.last_seen) FROM installations i JOIN minecraft_accounts m ON m.account_id=i.account_id`);
      sql.exec("INSERT INTO community_migrations VALUES('legacy-identities-v1',?)", Date.now());
    });
  }
  private profileOwner(player: string): Account | undefined {
    return this.sql.exec<Account>("SELECT a.* FROM sky_accounts a JOIN minecraft_accounts m ON m.account_id=a.account_id WHERE m.uuid=?", player).toArray()[0];
  }
  blacklisted(discordId: string | undefined, player: string | undefined): boolean {
    return !!this.sql.exec("SELECT identity FROM community_blacklist WHERE removed_at IS NULL AND ((kind='discord' AND identity=?) OR (kind='minecraft' AND identity=?))", discordId ?? "", player ?? "").toArray().length;
  }
  blacklistedUuid(player: string): boolean { return this.blacklisted(this.profileOwner(player)?.discord_id, player); }
  identity(accountId: string): CommunityIdentity {
    const account = this.hooks.account(accountId); if (!account) deny("account_required");
    const name = this.sql.exec<{ username: string; display_name: string }>("SELECT * FROM account_discord_names WHERE discord_id=?", account.discord_id).toArray()[0];
    return { accountId, discordId: account.discord_id, username: name?.username ?? null, displayName: name?.display_name ?? null,
      uuids: this.sql.exec<{ uuid: string; name: string; firstSeen: number; lastSeen: number }>("SELECT uuid,name,linked_at AS firstSeen,last_seen AS lastSeen FROM minecraft_accounts WHERE account_id=? ORDER BY linked_at,uuid", accountId).toArray(),
      blacklisted: this.blacklisted(account.discord_id, undefined) };
  }
  private observe(accountId: string, player: string, value: Device | null): void {
    if (!value) return;
    this.sql.exec(`INSERT INTO community_device_observations VALUES(?,?,?,?,?,?,?) ON CONFLICT(account_id,uuid,identifier,source)
      DO UPDATE SET platform=excluded.platform,last_seen=excluded.last_seen`, accountId, player, value.identifier, value.source, value.platform, Date.now(), Date.now());
  }
  private link(account: Account, player: string, name: string): string[] {
    if (this.blacklisted(account.discord_id, player)) deny("identity_blacklisted");
    const previous = this.profileOwner(player), invalidated: string[] = [];
    this.hooks.mergeUuid(player, account.account_id);
    if (previous && previous.account_id !== account.account_id) {
      this.sql.exec("DELETE FROM minecraft_links WHERE uuid=?", player);
      this.hooks.mergeAccount(previous.account_id, account.account_id);
      this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE uuid=? AND revoked_at IS NULL", Date.now(), player);
      invalidated.push(previous.account_id);
      this.hooks.audit(account.discord_id, previous.account_id, "community_uuid_reconnected", { uuid: player, destinationAccountId: account.account_id });
    }
    this.sql.exec(`INSERT INTO minecraft_accounts VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET account_id=excluded.account_id,name=excluded.name,last_seen=excluded.last_seen`, player, account.account_id, name, Date.now(), Date.now());
    this.sql.exec("INSERT OR IGNORE INTO account_migrations VALUES('*',?)", player);
    return invalidated;
  }
  challenge(raw: Record<string, unknown>): CommunityResult {
    try {
      if (!["connect", "refresh", "disconnect"].includes(String(raw.purpose)) || typeof raw.clientNonce !== "string" || !token.test(raw.clientNonce)) deny("invalid_request");
      const challengeId = crypto.randomUUID(), nonce = randomToken(), expiresAt = Date.now() + 300000;
      this.sql.exec("DELETE FROM community_challenges WHERE expires_at<=?", Date.now());
      this.sql.exec("INSERT INTO community_challenges VALUES(?,?,?,?,NULL)", challengeId, String(raw.purpose), nonce, expiresAt);
      return { ok: true, challengeId, nonce, expiresAt };
    } catch (error) { return failure(error); }
  }
  private async proof(raw: Record<string, unknown>, purpose: string) {
    const row = this.sql.exec<{ id: string; nonce: string; purpose: string; expires_at: number; used_at: number | null }>("SELECT * FROM community_challenges WHERE id=?", String(raw.challengeId)).toArray()[0];
    if (!row || row.purpose !== purpose || row.used_at !== null || row.expires_at <= Date.now()) deny("invalid_challenge");
    if (!raw.minecraftProof || typeof raw.minecraftProof !== "object" || Array.isArray(raw.minecraftProof)) deny("minecraft_proof_required");
    const profile = await verifyAccount(raw.minecraftProof as Record<string, unknown>, row.nonce);
    if ("error" in profile) deny(profile.retryable ? "minecraft_verification_unavailable" : "minecraft_proof_invalid");
    return { row, profile };
  }
  private consumeChallenge(challengeId: string): void {
    const row = this.sql.exec<{ used_at: number | null; expires_at: number }>("SELECT used_at,expires_at FROM community_challenges WHERE id=?", challengeId).toArray()[0];
    if (!row || row.used_at !== null || row.expires_at <= Date.now()) deny("invalid_challenge");
    this.sql.exec("UPDATE community_challenges SET used_at=? WHERE id=?", Date.now(), challengeId);
  }
  async start(raw: Record<string, unknown>): Promise<CommunityResult> {
    try {
      if (typeof raw.pollSecret !== "string" || !token.test(raw.pollSecret)) deny("invalid_request");
      const { row, profile } = await this.proof(raw, "connect"), launch = randomToken(), code = newLinkCode();
      const [launchHash, pollHash, codeHash] = await Promise.all([sha256(launch), sha256(raw.pollSecret), sha256(normalizedCode(code)!)]);
      return this.transaction(() => {
        this.consumeChallenge(row.id); if (this.blacklisted(undefined, profile.id)) deny("identity_blacklisted");
        this.hooks.limit(`community-connect:${profile.id}`, 8);
        const flowId = crypto.randomUUID(), expiresAt = Date.now() + FLOW_TTL;
        this.sql.exec("DELETE FROM community_connect_flows WHERE expires_at<=?", Date.now());
        this.sql.exec("INSERT INTO community_connect_flows VALUES(?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,NULL,NULL)", flowId, launchHash, pollHash, codeHash, profile.id, profile.name,
          JSON.stringify(device(raw.device)), cleanName(raw.modVersion), expiresAt);
        const browserUrl = this.env.DISCORD_OAUTH_CLIENT_ID && this.env.DISCORD_OAUTH_CLIENT_SECRET && this.env.DISCORD_OAUTH_REDIRECT_URI && this.env.SKYMYCE_PUBLIC_URL
          ? `${this.env.SKYMYCE_PUBLIC_URL}/community/oauth/start?flow=${encodeURIComponent(launch)}` : null;
        return { ok: true, flowId, browserUrl, fallbackCode: code, expiresAt, pollIntervalMs: 3000 };
      });
    } catch (error) { return failure(error); }
  }
  private finish(flow: Flow, discord: { id: string; username: string; displayName: string }): CommunityResult {
    if (flow.account_id !== null || flow.cancelled_at !== null || flow.expires_at <= Date.now()) deny("connect_expired");
    if (this.blacklisted(discord.id, flow.uuid)) deny("identity_blacklisted");
    const account = this.hooks.ensure(discord.id), invalidated = this.link(account, flow.uuid, flow.name);
    this.sql.exec("INSERT INTO account_discord_names VALUES(?,?,?,?) ON CONFLICT(discord_id) DO UPDATE SET username=excluded.username,display_name=excluded.display_name,updated_at=excluded.updated_at",
      discord.id, cleanName(discord.username), cleanName(discord.displayName || discord.username), Date.now());
    this.sql.exec("UPDATE community_connect_flows SET account_id=? WHERE flow_id=?", account.account_id, flow.flow_id);
    this.hooks.audit(discord.id, account.account_id, "community_connected", { uuid: flow.uuid });
    return { ok: true, identity: { ...this.identity(account.account_id), uuid: flow.uuid }, invalidatedAccounts: invalidated, invalidatedMinecraft: invalidated.length ? [flow.uuid] : [] };
  }
  async oauthPreview(launch: string): Promise<CommunityResult> {
    try { const hash = await sha256(launch), flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE launch_hash=?", hash).toArray()[0];
      if (!token.test(launch) || !flow || flow.expires_at <= Date.now() || flow.account_id || flow.cancelled_at) deny("connect_expired");
      return { ok: true, minecraftName: flow.name };
    } catch (error) { return failure(error); }
  }
  async beginOAuth(launch: string, cookie: string): Promise<CommunityResult> {
    try {
      const state = randomToken(), [launchHash, cookieHash, stateHash] = await Promise.all([sha256(launch), sha256(cookie), sha256(state)]);
      return this.transaction(() => {
        const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE launch_hash=?", launchHash).toArray()[0];
        if (!token.test(launch) || !token.test(cookie) || !flow || flow.expires_at <= Date.now() || flow.account_id || flow.cancelled_at || flow.oauth_claimed_at) deny("connect_expired");
        this.sql.exec("UPDATE community_connect_flows SET state_hash=?,cookie_hash=? WHERE flow_id=?", stateHash, cookieHash, flow.flow_id);
        return { ok: true, state };
      });
    } catch (error) { return failure(error); }
  }
  async claimOAuth(state: string, cookie: string): Promise<CommunityResult> {
    try { const [stateHash, cookieHash] = await Promise.all([sha256(state), sha256(cookie)]);
      return this.transaction(() => {
        const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE state_hash=?", stateHash).toArray()[0];
        if (!token.test(state) || !token.test(cookie) || !flow || flow.cookie_hash !== cookieHash || flow.oauth_claimed_at !== null || flow.expires_at <= Date.now() || flow.account_id || flow.cancelled_at) deny("connect_expired");
        this.sql.exec("UPDATE community_connect_flows SET oauth_claimed_at=? WHERE flow_id=?", Date.now(), flow.flow_id);
        return { ok: true, flowId: flow.flow_id };
      });
    } catch (error) { return failure(error); }
  }
  finishOAuth(flowId: string, user: { id: string; username: string; displayName: string }): CommunityResult {
    try { return this.transaction(() => {
      const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE flow_id=?", flowId).toArray()[0];
      if (!flow || !flow.oauth_claimed_at || !id.test(user.id)) deny("connect_expired");
      return this.finish(flow, user);
    }); } catch (error) { return failure(error); }
  }
  private async flowTokens(flowId: string, pollSecret: string): Promise<{ accessToken: string; refreshToken: string }> {
    // Separate random polling secret permits idempotent delivery without storing recoverable credentials.
    const derive = async (kind: string) => encode64(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`Sky-Myce community ${kind}\n${flowId}\n${pollSecret}`))));
    const [accessToken, refreshToken] = await Promise.all([derive("access"), derive("refresh")]); return { accessToken, refreshToken };
  }
  async poll(raw: Record<string, unknown>): Promise<CommunityResult> {
    try {
      if (typeof raw.pollSecret !== "string" || !token.test(raw.pollSecret)) deny("invalid_request");
      const flowId = String(raw.flowId), pollHash = await sha256(raw.pollSecret), tokens = await this.flowTokens(flowId, raw.pollSecret);
      const [accessHash, refreshHash] = await Promise.all([sha256(tokens.accessToken), sha256(tokens.refreshToken)]);
      return this.transaction(() => {
        const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE flow_id=?", flowId).toArray()[0];
        if (!flow || !sameDigest(flow.poll_hash, pollHash) || flow.expires_at <= Date.now() || flow.cancelled_at !== null) deny("connect_expired");
        if (!flow.account_id) return { ok: true, status: "pending" };
        const account = this.hooks.account(flow.account_id)!;
        if (this.blacklisted(account.discord_id, flow.uuid) || this.profileOwner(flow.uuid)?.account_id !== account.account_id) deny("identity_blacklisted");
        let session = flow.session_id ? this.sql.exec<Session>("SELECT * FROM community_sessions WHERE session_id=?", flow.session_id).toArray()[0] : undefined;
        if (!session) {
          const now = Date.now(), sessionId = crypto.randomUUID();
          this.sql.exec("INSERT INTO community_sessions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)", sessionId, account.account_id, flow.uuid, account.generation,
            refreshHash, now + REFRESH_TTL, accessHash, now + ACCESS_TTL, now, now, null, flow.mod_version);
          this.sql.exec("UPDATE community_connect_flows SET session_id=? WHERE flow_id=?", sessionId, flowId);
          this.observe(account.account_id, flow.uuid, JSON.parse(flow.device_json ?? "null"));
          session = this.sql.exec<Session>("SELECT * FROM community_sessions WHERE session_id=?", sessionId).one();
        }
        if (session.revoked_at !== null || session.access_hash !== accessHash || session.refresh_hash !== refreshHash) deny("connect_expired");
        return { ok: true, status: "complete", identity: { ...this.identity(account.account_id), uuid: flow.uuid }, credentials: { sessionId: session.session_id, ...tokens,
          accessExpiresAt: session.access_expires_at, refreshExpiresAt: session.refresh_expires_at } };
      });
    } catch (error) { return failure(error); }
  }
  /** Only the initiating client's private polling secret may cancel this one request. */
  async cancel(raw: Record<string, unknown>): Promise<CommunityResult> {
    try {
      if (typeof raw.flowId !== "string" || !flowReference.test(raw.flowId) || typeof raw.pollSecret !== "string" || !token.test(raw.pollSecret)) deny("invalid_request");
      const pollHash = await sha256(raw.pollSecret);
      return this.transaction(() => {
        const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE flow_id=?", String(raw.flowId)).toArray()[0];
        if (!flow || !sameDigest(flow.poll_hash, pollHash) || flow.expires_at <= Date.now()) deny("connect_expired");
        if (flow.cancelled_at === null) {
          const now = Date.now();
          this.sql.exec("UPDATE community_connect_flows SET cancelled_at=? WHERE flow_id=?", now, flow.flow_id);
          if (flow.session_id && flow.account_id) this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE session_id=? AND account_id=? AND uuid=?", now, flow.session_id, flow.account_id, flow.uuid);
          if (flow.account_id) this.hooks.audit(`minecraft:${flow.uuid}`, flow.account_id, "community_connect_cancelled", { uuid: flow.uuid });
        }
        // A completed association is deliberately retained; cancellation never reverses unrelated identity changes.
        return { ok: true, status: "cancelled", invalidatedAccounts: flow.account_id ? [flow.account_id] : [] };
      });
    } catch (error) { return failure(error); }
  }
  private cancelAccountFlows(accountId: string, now: number): void {
    this.sql.exec(`UPDATE community_connect_flows SET cancelled_at=? WHERE cancelled_at IS NULL AND expires_at>?
      AND (account_id=? OR (account_id IS NULL AND uuid IN (SELECT uuid FROM minecraft_accounts WHERE account_id=?)))`, now, now, accountId, accountId);
  }
  private cancelUuidFlows(player: string, now: number): void {
    this.sql.exec("UPDATE community_connect_flows SET cancelled_at=? WHERE cancelled_at IS NULL AND expires_at>? AND uuid=?", now, now, player);
  }
  async refresh(raw: Record<string, unknown>, disconnect = false): Promise<CommunityResult> {
    try {
      if (typeof raw.refreshToken !== "string" || !token.test(raw.refreshToken)) deny("session_expired");
      const { row, profile } = await this.proof(raw, disconnect ? "disconnect" : "refresh"), refreshHash = await sha256(raw.refreshToken);
      const accessToken = randomToken(), refreshToken = randomToken(), [nextAccess, nextRefresh] = await Promise.all([sha256(accessToken), sha256(refreshToken)]);
      return this.transaction(() => {
        this.consumeChallenge(row.id);
        const session = this.sql.exec<Session>("SELECT * FROM community_sessions WHERE refresh_hash=?", refreshHash).toArray()[0], account = session && this.hooks.account(session.account_id);
        if (!session || !account || session.revoked_at !== null || session.refresh_expires_at <= Date.now() || session.generation !== account.generation) deny("session_expired");
        if (disconnect) {
          if (profile.id !== session.uuid) deny("session_identity_changed");
          const now = Date.now();
          this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE session_id=?", now, session.session_id);
          this.sql.exec("UPDATE community_connect_flows SET cancelled_at=? WHERE session_id=? AND account_id=? AND uuid=?", now, session.session_id, session.account_id, session.uuid);
          return { ok: true, invalidatedAccounts: [account.account_id] };
        }
        if (this.blacklisted(account.discord_id, profile.id)) deny("identity_blacklisted");
        // Refresh can add another owned Minecraft account but cannot silently reassign somebody else's Discord association.
        const owner = this.profileOwner(profile.id); if (owner && owner.account_id !== account.account_id) deny("connect_required");
        this.link(account, profile.id, profile.name); this.observe(account.account_id, profile.id, device(raw.device));
        const now = Date.now(), accessExpiresAt = now + ACCESS_TTL, refreshExpiresAt = now + REFRESH_TTL;
        this.sql.exec("UPDATE community_sessions SET uuid=?,refresh_hash=?,refresh_expires_at=?,access_hash=?,access_expires_at=?,last_seen=?,mod_version=? WHERE session_id=?",
          profile.id, nextRefresh, refreshExpiresAt, nextAccess, accessExpiresAt, now, cleanName(raw.modVersion), session.session_id);
        return { ok: true, identity: { ...this.identity(account.account_id), uuid: profile.id }, credentials: { sessionId: session.session_id, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt } };
      });
    } catch (error) { return failure(error); }
  }
  checkSession(input: CommunitySessionCheck): CommunitySessionResult {
    const session = this.sql.exec<Session>("SELECT * FROM community_sessions WHERE session_id=?", input.sessionId).toArray()[0], account = session && this.hooks.account(session.account_id);
    if (!session || !account || session.revoked_at !== null || session.access_expires_at <= Date.now() || account.generation !== input.generation || session.generation !== input.generation || session.account_id !== input.accountId || session.uuid !== input.uuid || this.profileOwner(input.uuid)?.account_id !== account.account_id) return { ok: false, error: "session_expired" };
    if (this.blacklisted(account.discord_id, input.uuid)) return { ok: false, error: "identity_blacklisted" };
    const traffic = this.traffic.status(account.account_id);
    if (!traffic.ok) return traffic;
    if (input.connectionId && session.connection_id === input.connectionId && input.heartbeatAt && input.heartbeatAt <= Date.now()) this.sql.exec("UPDATE community_sessions SET last_seen=MAX(last_seen,?) WHERE session_id=?", input.heartbeatAt, session.session_id);
    return { ok: true, accountId: account.account_id, discordId: account.discord_id, uuid: input.uuid, sessionId: session.session_id,
      generation: session.generation, expiresAt: session.access_expires_at, ...(session.connection_id ? { connectionId: session.connection_id } : {}) };
  }
  consumeRequest(input: CommunitySessionCheck): CommunitySessionResult {
    const status = this.checkSession(input);
    if (!status.ok) return status;
    const traffic = this.traffic.consume(status.accountId!, "request");
    return traffic.ok ? status : traffic;
  }
  reportTrafficViolation(input: CommunitySessionCheck): CommunitySessionResult {
    const status = this.checkSession(input);
    return status.ok ? this.traffic.violation(status.accountId!) : status;
  }
  async authorize(input: { uuid: string; token: string; challenge: string; modVersion?: string }): Promise<CommunitySessionResult> {
    try {
      if (!uuid.test(input.uuid) || !token.test(input.token) || !/^[a-f0-9]{32}$/.test(input.challenge)) deny("invalid_request");
      const digest = await sha256(input.token);
      return this.transaction(() => {
        const session = this.sql.exec<Session>("SELECT * FROM community_sessions WHERE access_hash=?", digest).toArray()[0];
        if (!session) deny("session_expired");
        const status = this.checkSession({ sessionId: session.session_id, accountId: session.account_id, uuid: input.uuid, generation: session.generation });
        if (!status.ok) deny(status.error!);
        const traffic = this.traffic.consume(status.accountId!, "connect");
        if (!traffic.ok) return traffic;
        const nonce = `community-ws:${input.challenge}`;
        if (this.sql.exec("SELECT id FROM account_requests WHERE id=?", nonce).toArray().length) deny("challenge_replayed");
        this.sql.exec("INSERT INTO account_requests VALUES(?,?)", nonce, Date.now() + 300000);
        this.sql.exec("UPDATE community_sessions SET connected_at=COALESCE(connected_at,?),last_seen=?,connection_id=?,mod_version=? WHERE session_id=?", Date.now(), Date.now(), input.challenge, cleanName(input.modVersion), session.session_id);
        return { ...status, connectionId: input.challenge };
      });
    } catch (error) { return failure(error); }
  }
  async tokenSession(value: string): Promise<CommunitySessionResult> {
    try {
      if (!token.test(value)) deny("session_expired");
      const digest = await sha256(value), session = this.sql.exec<Session>("SELECT * FROM community_sessions WHERE access_hash=?", digest).toArray()[0];
      if (!session) deny("session_expired");
      return this.consumeRequest({ sessionId: session.session_id, accountId: session.account_id, uuid: session.uuid, generation: session.generation });
    } catch (error) { return failure(error); }
  }
  markOffline(sessionId: string, connectionId: string): void { this.sql.exec("UPDATE community_sessions SET connected_at=NULL,connection_id=NULL WHERE session_id=? AND connection_id=?", sessionId, connectionId); }
  user(actor: DeveloperActor, action: string, options: Record<string, unknown> = {}): Promise<CommunityResult> {
    return this.userAsync(actor, action, options);
  }
  private async userAsync(actor: DeveloperActor, action: string, options: Record<string, unknown>): Promise<CommunityResult> {
    try {
      this.hooks.interaction(actor); this.hooks.limit(`community-user:${actor.id}`, 20);
      if (action === "connect") {
        const code = normalizedCode(options.code); if (!code) deny("connect_expired");
        const digest = await sha256(code);
        return this.transaction(() => { const flow = this.sql.exec<Flow>("SELECT * FROM community_connect_flows WHERE code_hash=?", digest).toArray()[0];
          if (!flow) deny("connect_expired");
          const name = this.sql.exec<{ username: string; display_name: string }>("SELECT * FROM account_discord_names WHERE discord_id=?", actor.id).toArray()[0];
          return this.finish(flow, { id: actor.id, username: name?.username ?? "Discord user", displayName: name?.display_name ?? "Discord user" }); });
      }
      return this.transaction(() => {
        const account = this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", actor.id).toArray()[0]; if (!account) deny("account_required");
        if (action === "status") return { ok: true, identity: this.identity(account.account_id) };
        if (!["disconnect", "unlink"].includes(action)) deny("invalid_action");
        this.consumeManagement(actor, "user", action, actor.id, options);
        if (action === "unlink") {
          if (typeof options.uuid !== "string" || this.profileOwner(options.uuid)?.account_id !== account.account_id) deny("minecraft_link_required");
          this.cancelUuidFlows(options.uuid, Date.now());
          this.hooks.detachUuid(options.uuid, account.account_id);
          this.sql.exec("DELETE FROM minecraft_accounts WHERE uuid=?", options.uuid);
          this.sql.exec("DELETE FROM minecraft_links WHERE uuid=?", options.uuid);
          this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE account_id=? AND uuid=?", Date.now(), account.account_id, options.uuid);
          this.sql.exec("INSERT OR IGNORE INTO account_migrations VALUES('*',?)", options.uuid);
        } else {
          const now = Date.now();
          this.cancelAccountFlows(account.account_id, now);
          this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE account_id=?", now, account.account_id);
        }
        this.hooks.audit(actor.id, account.account_id, action === "unlink" ? "community_uuid_unlinked" : "community_disconnected", action === "unlink" ? { uuid: options.uuid } : {});
        return { ok: true, identity: this.identity(account.account_id), invalidatedAccounts: [account.account_id], ...(action === "unlink" ? { invalidatedMinecraft: [String(options.uuid)] } : {}) };
      });
    } catch (error) { return failure(error); }
  }
  read(actor: DeveloperActor, action: string, target = "", options: Record<string, unknown> = {}): CommunityResult {
    try {
      if (!this.hooks.isAdmin(actor)) deny("admin_required"); this.hooks.limit(`community-read:${actor.id}`, 120);
      const account = this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).toArray()[0];
      const page = Number(options.page ?? 1), limit = Number(options.limit ?? 8); if (!Number.isSafeInteger(page) || page < 1 || page > 100 || !Number.isSafeInteger(limit) || limit < 1 || limit > 25) deny("invalid_request");
      const offset = (page - 1) * limit;
      if (action === "blacklist") return { ok: true, entries: this.sql.exec<SqlRow>("SELECT * FROM community_blacklist WHERE (?='' OR identity=?) ORDER BY created_at DESC LIMIT ? OFFSET ?", target, target, limit, offset).toArray().map(row => ({ ...row, active: row.removed_at === null, createdAt: row.created_at, removedAt: row.removed_at })) };
      if (action === "search") {
        const query = String(options.query ?? "").trim().toLowerCase(); if (!query || query.length > 100) deny("invalid_request");
        const accounts = this.sql.exec<{ account_id: string }>(`SELECT DISTINCT a.account_id FROM sky_accounts a LEFT JOIN account_discord_names d ON d.discord_id=a.discord_id LEFT JOIN minecraft_accounts m ON m.account_id=a.account_id WHERE instr(lower(COALESCE(d.username,'')||COALESCE(d.display_name,'')||a.discord_id||COALESCE(m.name,'')),?)>0 LIMIT 25`, query).toArray();
        return { ok: true, identities: accounts.map(a => this.identity(a.account_id)) };
      }
      if (action === "overview") return { ok: true, overview: { accounts: this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM sky_accounts").one().n,
        sessions: this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_sessions WHERE revoked_at IS NULL AND access_expires_at>?", Date.now()).one().n,
        blacklisted: this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_blacklist WHERE removed_at IS NULL").one().n } };
      if (!account) deny("account_required");
      if (action === "account") return { ok: true, identity: this.identity(account.account_id) };
      if (action === "sessions") return { ok: true, identity: this.identity(account.account_id), sessions: this.sql.exec<SqlRow>("SELECT session_id AS sessionId,uuid AS minecraftUuid,created_at AS issuedAt,last_seen AS lastSeen,connected_at AS connectedAt,access_expires_at AS expiresAt,refresh_expires_at AS refreshExpiresAt,revoked_at AS revokedAt,mod_version AS modVersion FROM community_sessions WHERE account_id=? ORDER BY last_seen DESC LIMIT ? OFFSET ?", account.account_id, limit, offset).toArray() };
      if (action === "audit") return { ok: true, identity: this.identity(account.account_id), events: this.sql.exec<SqlRow & { metadata: string }>("SELECT id,at,actor,action,severity,metadata FROM account_audit WHERE account_id=? ORDER BY id DESC LIMIT ? OFFSET ?", account.account_id, limit, offset).toArray() };
      if (action !== "devices") deny("invalid_action");
      const rows = this.sql.exec<{ identifier: string; source: string; platform: string; firstSeen: number; lastSeen: number }>("SELECT identifier,source,platform,MIN(first_seen) firstSeen,MAX(last_seen) lastSeen FROM community_device_observations WHERE account_id=? GROUP BY identifier,source ORDER BY lastSeen DESC LIMIT ? OFFSET ?", account.account_id, limit, (page - 1) * limit).toArray();
      return { ok: true, identity: this.identity(account.account_id), devices: rows.map(row => ({ ...row,
        minecraft: this.sql.exec<{ uuid: string; name: string }>("SELECT DISTINCT d.uuid,COALESCE(m.name,'Minecraft account') name FROM community_device_observations d LEFT JOIN minecraft_accounts m ON m.uuid=d.uuid WHERE d.account_id=? AND d.identifier=?", account.account_id, row.identifier).toArray(),
        relatedAccounts: this.sql.exec<{ discordId: string; username: string | null; displayName: string | null }>("SELECT DISTINCT a.discord_id AS discordId,n.username,n.display_name AS displayName FROM community_device_observations d JOIN sky_accounts a ON a.account_id=d.account_id LEFT JOIN account_discord_names n ON n.discord_id=a.discord_id WHERE d.identifier=? LIMIT 25", row.identifier).toArray() })) };
    } catch (error) { return failure(error); }
  }
  private snapshot(action: string, target: string, options: Record<string, unknown>): string {
    if (action.startsWith("blacklist_")) return JSON.stringify(this.sql.exec("SELECT * FROM community_blacklist WHERE kind=? AND identity=?", String(options.kind), target).toArray());
    const account = this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).toArray()[0];
    return JSON.stringify(account ? { accountId: account.account_id, generation: account.generation, admin: account.is_admin,
      uuids: this.sql.exec("SELECT uuid FROM minecraft_accounts WHERE account_id=? ORDER BY uuid", account.account_id).toArray(),
      sessions: this.sql.exec("SELECT session_id,uuid,revoked_at FROM community_sessions WHERE account_id=? ORDER BY session_id", account.account_id).toArray() } : null);
  }
  private validateManagement(actor: DeveloperActor, kind: string, action: string, target: string, options: Record<string, unknown>): void {
    if (kind === "backend") { if (!this.hooks.isAdmin(actor)) deny("admin_required"); }
    else if (kind !== "user" || target !== actor.id || !id.test(actor.id)) deny("forbidden");
    if (kind === "user" && !["unlink", "disconnect"].includes(action) || kind === "backend" && !["blacklist_add", "blacklist_remove", "session_kick", "admin_add"].includes(action)) deny("invalid_action");
    if (action.startsWith("blacklist_")) {
      if (!["discord", "minecraft"].includes(String(options.kind)) || options.identity !== target || !(options.kind === "discord" ? id : uuid).test(target) ||
        options.reason !== undefined && (typeof options.reason !== "string" || !options.reason.trim() || options.reason.length > 500)) deny("invalid_blacklist");
    } else if (!id.test(target)) deny("invalid_discord_id");
    const permitted = action.startsWith("blacklist_") ? ["kind", "identity", "reason", "managementToken"] : action === "unlink" ? ["uuid", "managementToken"] : ["managementToken"];
    if (Object.keys(options).some(key => !permitted.includes(key))) deny("invalid_action");
  }
  prepare(actor: DeveloperActor, kind: string, action: string, target: string, options: Record<string, unknown>): CommunityResult {
    try {
      this.validateManagement(actor, kind, action, target, options); if (options.managementToken !== undefined) deny("invalid_action");
      if (action === "unlink" && (typeof options.uuid !== "string" || this.profileOwner(options.uuid)?.discord_id !== actor.id)) deny("minecraft_link_required");
      this.hooks.interaction(actor); this.hooks.limit(`community-prepare:${actor.id}`, 20);
      const value = randomToken(), expiresAt = Date.now() + 120000;
      this.sql.exec("DELETE FROM community_management WHERE expires_at<=?", Date.now());
      this.sql.exec("INSERT INTO community_management VALUES(?,?,?,?,?,?,?,?,?,NULL)", value, actor.id, actor.guildId, kind, action, target, JSON.stringify(options), this.snapshot(action, target, options), expiresAt);
      const account = this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).toArray()[0];
      return { ok: true, token: value, expiresAt, action, target, options: options as CommunityOptions, ...(account ? { identity: this.identity(account.account_id) } : {}) };
    } catch (error) { return failure(error); }
  }
  private consumeManagement(actor: DeveloperActor, kind: string, action: string, target: string, options: Record<string, unknown>): void {
    this.validateManagement(actor, kind, action, target, options);
    const row = this.sql.exec<{ actor: string; guild: string; kind: string; action: string; target: string; options: string; snapshot: string; expires_at: number; used_at: number | null }>("SELECT * FROM community_management WHERE token=?", String(options.managementToken)).toArray()[0];
    if (!row) deny("confirmation_required");
    if (row.actor !== actor.id || row.guild !== actor.guildId || row.kind !== kind || row.action !== action || row.target !== target || row.used_at !== null || row.expires_at <= Date.now()) deny("confirmation_expired");
    const actual = Object.entries(options).filter(([key]) => key !== "managementToken").sort(([a], [b]) => a.localeCompare(b));
    if (JSON.stringify(actual) !== JSON.stringify(Object.entries(JSON.parse(row.options)).sort(([a], [b]) => a.localeCompare(b))) || row.snapshot !== this.snapshot(action, target, options)) deny("confirmation_stale");
    this.sql.exec("UPDATE community_management SET used_at=? WHERE token=?", Date.now(), String(options.managementToken));
  }
  async confirm(actor: DeveloperActor, kind: string, value: string, confirm: boolean): Promise<CommunityResult> {
    try {
      const row = this.sql.exec<{ actor: string; guild: string; kind: string; action: string; target: string; options: string; used_at: number | null; expires_at: number }>("SELECT * FROM community_management WHERE token=?", value).toArray()[0];
      if (!token.test(value) || typeof confirm !== "boolean" || !row || row.actor !== actor.id || row.guild !== actor.guildId || row.kind !== kind || row.used_at !== null || row.expires_at <= Date.now()) deny("confirmation_expired");
      this.validateManagement(actor, kind, row.action, row.target, JSON.parse(row.options));
      if (!confirm) { this.hooks.interaction(actor); this.sql.exec("UPDATE community_management SET used_at=? WHERE token=?", Date.now(), value); return { ok: true, action: "cancelled" }; }
      const options = { ...JSON.parse(row.options), managementToken: value };
      const result = kind === "user" ? await this.user(actor, row.action, options) : this.manage(actor, row.action, row.target, options);
      return { ...result, action: row.action, target: row.target, options: JSON.parse(row.options) };
    } catch (error) { return failure(error); }
  }
  manage(actor: DeveloperActor, action: string, target: string, options: Record<string, unknown>): CommunityResult {
    try {
      if (!this.hooks.isAdmin(actor)) deny("admin_required"); this.hooks.interaction(actor); this.hooks.limit(`community-manage:${actor.id}`, 30);
      return this.transaction(() => {
        this.consumeManagement(actor, "backend", action, target, options);
        const accounts: string[] = [], players: string[] = [];
        if (action.startsWith("blacklist_")) {
          const now = Date.now(), reason = String(options.reason ?? (action === "blacklist_add" ? "Administrator moderation" : "Administrator review"));
          this.sql.exec(`INSERT INTO community_blacklist VALUES(?,?,?,?,?,?,1) ON CONFLICT(kind,identity) DO UPDATE SET reason=excluded.reason,actor=excluded.actor,
            removed_at=excluded.removed_at,revision=community_blacklist.revision+1`, String(options.kind), target, reason, actor.id, now, action === "blacklist_add" ? null : now);
          const rows = options.kind === "discord" ? this.sql.exec<{ account_id: string }>("SELECT account_id FROM sky_accounts WHERE discord_id=?", target).toArray() : this.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", target).toArray();
          accounts.push(...rows.map(row => row.account_id)); if (options.kind === "minecraft") players.push(target);
          if (action === "blacklist_add") {
            if (options.kind === "discord") {
              this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE account_id IN (SELECT account_id FROM sky_accounts WHERE discord_id=?)", now, target);
              this.sql.exec("UPDATE community_connect_flows SET cancelled_at=? WHERE account_id IN (SELECT account_id FROM sky_accounts WHERE discord_id=?)", now, target);
            } else {
              this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE uuid=?", now, target);
              this.cancelUuidFlows(target, now);
            }
          }
          this.hooks.audit(actor.id, rows[0]?.account_id ?? null, action, { kind: options.kind, identity: target, reason });
        } else {
          const account = action === "admin_add" ? this.hooks.ensure(target) : this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).toArray()[0]; if (!account) deny("account_required");
          if (action === "session_kick") {
            const now = Date.now();
            this.cancelAccountFlows(account.account_id, now);
            this.sql.exec("UPDATE community_sessions SET revoked_at=?,connected_at=NULL,connection_id=NULL WHERE account_id=?", now, account.account_id);
          }
          else this.sql.exec("UPDATE sky_accounts SET is_admin=1 WHERE account_id=?", account.account_id);
          accounts.push(account.account_id); this.hooks.audit(actor.id, account.account_id, action);
        }
        return { ok: true, invalidatedAccounts: accounts, invalidatedMinecraft: players,
          ...(id.test(target) && this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).toArray()[0] ? { identity: this.identity(this.sql.exec<Account>("SELECT * FROM sky_accounts WHERE discord_id=?", target).one().account_id) } : {}) };
      });
    } catch (error) { return failure(error); }
  }
}

const htmlEscape = (value: string) => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
async function boundedJson(request: Request, maximum: number): Promise<Record<string, unknown>> {
  if (!request.body || Number(request.headers.get("Content-Length") ?? 0) > maximum) deny("invalid_request");
  const reader = request.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) { const next = await reader.read(); if (next.done) break; size += next.value.byteLength; if (size > maximum) { await reader.cancel(); deny("invalid_request"); } chunks.push(next.value); }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
  if (!value || typeof value !== "object" || Array.isArray(value)) deny("invalid_request");
  return value as Record<string, unknown>;
}
function html(content: string, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Connect Sky-Myce</title><main>${content}</main>`,
    { status, headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'", ...extra } });
}
export async function handleCommunityRequest(request: Request, env: CommunityConfig & { ACCOUNT_STORE: DurableObjectNamespace<AccountStore>; ROOMS: string; RELAY_ROOMS: DurableObjectNamespace<import("./index").RelayRoom> }): Promise<Response | null> {
  const url = new URL(request.url), store = env.ACCOUNT_STORE.getByName("global");
  const json = (result: CommunityResult) => Response.json(result, { status: result.ok ? 200 : result.error === "identity_blacklisted" ? 403 : ["rate_limited", "traffic_cooldown"].includes(result.error ?? "") ? 429 : result.error === "service_busy" ? 503 : 400,
    headers: { "Cache-Control": "no-store", ...(result.retryAt ? { "Retry-After": String(Math.max(1, Math.ceil((result.retryAt - Date.now()) / 1000))) } : {}) } });
  if (url.pathname === "/community/sharing") {
    const credential = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1] ?? "";
    if (request.method === "GET") return json(await store.communityGetSharing(credential));
    if (request.method !== "POST" || !request.headers.get("Content-Type")?.startsWith("application/json")) return json({ ok: false, error: "invalid_request" });
    let raw: Record<string, unknown>; try { raw = await boundedJson(request, 4096); } catch { return json({ ok: false, error: "invalid_request" }); }
    const result = await store.communityUpdateSharing(credential, raw);
    if (result.ok && result.affectedUuids?.length && result.policy) {
      for (let offset = 0; offset < result.affectedUuids.length; offset += 256) await Promise.allSettled(env.ROOMS.split(",").map(room => env.RELAY_ROOMS.getByName(room).applySharing(result.affectedUuids!.slice(offset, offset + 256), result.policy!, result.suppressedCategories ?? [])));
    }
    return json(result);
  }
  const routes: Record<string, (raw: Record<string, unknown>) => Promise<CommunityResult> | CommunityResult> = {
    "/community/challenge": raw => store.communityChallenge(raw), "/community/connect/start": raw => store.communityConnectStart(raw),
    "/community/connect/poll": raw => store.communityConnectPoll(raw), "/community/connect/cancel": raw => store.communityConnectCancel(raw), "/community/session/refresh": raw => store.communityRefresh(raw),
    "/community/session/disconnect": raw => store.communityDisconnect(raw),
    "/community/contributions": raw => store.communityContribute(request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1] ?? "", raw),
  };
  if (routes[url.pathname]) {
    if (request.method !== "POST" || !request.headers.get("Content-Type")?.startsWith("application/json")) return json({ ok: false, error: "invalid_request" });
    let raw: Record<string, unknown>; try { raw = await boundedJson(request, 32768); } catch { return json({ ok: false, error: "invalid_request" }); }
    const result = await routes[url.pathname](raw);
    if (result.ok && result.invalidatedAccounts?.length) await Promise.allSettled(result.invalidatedAccounts.flatMap(account => env.ROOMS.split(",").filter(Boolean).map(room => env.RELAY_ROOMS.getByName(room).invalidateAccount(account))));
    return json(result);
  }
  if (!url.pathname.startsWith("/community/oauth/")) return null;
  if (request.method !== "GET") return html("<p>This connection could not be completed. Start again in Minecraft.</p>", 400);
  if (!env.DISCORD_OAUTH_CLIENT_ID || !env.DISCORD_OAUTH_CLIENT_SECRET || !env.DISCORD_OAUTH_REDIRECT_URI) return html("<p>Browser connection is unavailable. Use the one-time code with the Sky-Myce Discord bot.</p>", 503);
  if (url.pathname === "/community/oauth/start") {
    const flow = url.searchParams.get("flow") ?? "", result = await store.communityOAuthPreview(flow);
    return result.ok ? html(`<h1>Connect Discord</h1><p>Connect your Discord identity to Minecraft account <strong>${htmlEscape(String(result.minecraftName))}</strong>. Only continue if you started this request in Sky-Myce.</p><a href="/community/oauth/authorize?flow=${htmlEscape(encodeURIComponent(flow))}">Continue with Discord</a>`) : html("<p>This connection expired. Start again in Minecraft.</p>", 400);
  }
  if (url.pathname === "/community/oauth/authorize") {
    const cookie = randomToken(), result = await store.communityOAuthBegin(url.searchParams.get("flow") ?? "", cookie);
    if (!result.ok) return html("<p>This connection expired. Start again in Minecraft.</p>", 400);
    const authorization = new URL("https://discord.com/oauth2/authorize");
    for (const [key, value] of Object.entries({ client_id: env.DISCORD_OAUTH_CLIENT_ID, response_type: "code", redirect_uri: env.DISCORD_OAUTH_REDIRECT_URI, scope: "identify", state: String(result.state), prompt: "consent" })) authorization.searchParams.set(key, value);
    return new Response(null, { status: 302, headers: { Location: authorization.toString(), "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
      "Set-Cookie": `__Host-SkyMyceOAuth=${cookie}; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=600` } });
  }
  if (url.pathname !== "/community/oauth/callback" || new URL(env.DISCORD_OAUTH_REDIRECT_URI).origin !== url.origin || new URL(env.DISCORD_OAUTH_REDIRECT_URI).pathname !== url.pathname) return html("<p>Invalid connection response.</p>", 400);
  const cookie = request.headers.get("Cookie")?.split(";").map(value => value.trim()).find(value => value.startsWith("__Host-SkyMyceOAuth="))?.slice("__Host-SkyMyceOAuth=".length) ?? "";
  const claim = await store.communityOAuthClaim(url.searchParams.get("state") ?? "", cookie), code = url.searchParams.get("code");
  if (!claim.ok || !code || code.length > 1024 || url.searchParams.has("error")) return html("<p>Connection cancelled or expired. Start again in Minecraft.</p>", 400);
  try {
    const response = await fetch("https://discord.com/api/v10/oauth2/token", { method: "POST", redirect: "manual", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: env.DISCORD_OAUTH_CLIENT_ID, client_secret: env.DISCORD_OAUTH_CLIENT_SECRET, grant_type: "authorization_code", code, redirect_uri: env.DISCORD_OAUTH_REDIRECT_URI }), signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error();
    const access = await response.json() as { access_token?: unknown; token_type?: unknown; scope?: unknown };
    if (typeof access.access_token !== "string" || access.token_type !== "Bearer" || typeof access.scope !== "string" || !access.scope.split(" ").includes("identify")) throw new Error();
    const userResponse = await fetch("https://discord.com/api/v10/users/@me", { redirect: "manual", headers: { Authorization: `Bearer ${access.access_token}` }, signal: AbortSignal.timeout(10000) });
    if (!userResponse.ok) throw new Error();
    const user = await userResponse.json() as { id?: unknown; username?: unknown; global_name?: unknown; bot?: unknown };
    if (typeof user.id !== "string" || !id.test(user.id) || typeof user.username !== "string" || user.bot) throw new Error();
    const result = await store.communityOAuthFinish(String(claim.flowId), { id: user.id, username: user.username, displayName: cleanName(user.global_name ?? user.username) });
    return html(result.ok ? "<h1>Discord connected</h1><p>Return to Minecraft. You can close this tab.</p>" : "<p>This identity cannot connect to community services. Return to Minecraft for details.</p>", result.ok ? 200 : 403,
      { "Set-Cookie": "__Host-SkyMyceOAuth=; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=0" });
  } catch { return html("<p>Discord is temporarily unavailable. Return to Minecraft and use the bot fallback code or start again.</p>", 503); }
}
