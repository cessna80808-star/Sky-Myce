import { DurableObject } from "cloudflare:workers";
import { verifyAccount } from "./auth";
import { newLinkCode, normalizedCode, PlayerCosmetics, type CosmeticProfile, type DiscordCommand, type CosmeticsConfig } from "./cosmetics";
import { CosmeticAssets } from "./cosmetic-assets";
import { ACTIVATION_TTL, DEVICE_RESET_COOLDOWN, licenseDenial, type DeveloperActor, type LicensePolicy } from "./licensing";
import { AuthorizationError, bootstrapPurpose, createLease, decode64, deny, ensureSigningConfigured, installationKey, installationMessage, installationVersionDenial, randomToken,
  seconds, sha256, validateLaunchInput, validateLease, verifyInstallation, type AuthenticationPurpose, type ClientPlatform, type DrmConfig, type InstallationInput, type LeaseClaims } from "./drm";
import { PromptJobs, type PromptActor, type PromptConfig } from "./prompts";
import { deliverAccountAlert } from "./discord-notifications";
import { ircMentionNames, ircPlainText } from "./irc-model";
import { CommunityAccounts, type CommunityConfig, type CommunitySessionCheck } from "./community";
import { CommunitySharingStore, type SharingSession, type SharingCategory } from "./community-sharing";
import { CommunityLeaderboards, type LeaderboardQuery, type JournalComparisonQuery, type JournalComparisonResult, type FeedReceipt } from "./leaderboards";
import type { PublicRngEvent } from "./digest";
import { CommunityBazaar, type BazaarStatsResult } from "./bazaar";
import { BazaarMarket } from "./bazaar-market";

export type AccountsConfig = LicensePolicy & DrmConfig & PromptConfig & CommunityConfig & CosmeticsConfig & { ACCOUNT_STORE?: DurableObjectNamespace<AccountStore>; DISCORD_RNG_CHANNEL_ID?: string; COSMETIC_ASSETS?: R2Bucket; BAZAAR_MARKET_PROVIDER?: string };
type AccountRow = { account_id: string; discord_id: string; status: string; license_status: string; license_expires_at: number | null;
  device_hash: string | null; hardware_hash: string | null; hwid_reset_at: number | null; hwid_bound_at: number | null; generation: number; activated_at: number | null; created_at: number; last_seen: number; enrollment_blocked: number; is_admin: number };
type InstallationRow = { installation_id: string; account_id: string; device_hash: string; hardware_hash: string | null; public_key: string; fingerprint: string; revoked_at: number | null; platform: ClientPlatform | null };
type ChallengeRow = { id: string; nonce: string; input: string; approval_hash: string; discord_id: string | null; expires_at: number; used_at: number | null };
export type AccountSummary = { accountId: string; discordId: string; status: string; isAdmin: boolean; activated: boolean; licenseStatus: string; licenseExpiresAt: number | null;
  deviceRegistered: boolean; hwidResetAt: number | null; hwidBoundAt: number | null; nextHwidChangeAllowedAt: number | null; uuids: { uuid: string; name: string }[] };
export type AccountResult = { ok: boolean; error?: string; account?: AccountSummary; activationKey?: string; activationId?: string;
  expiresAt?: number; invalidated?: string; invalidatedAccounts?: string[]; audit?: Record<string, SqlStorageValue>[]; warningId?: string; adminAdded?: boolean; details?: AccountDetails };
export type SessionResult = { ok: boolean; error?: string; accountId?: string; discordId?: string; installationId?: string;
  deviceId?: string; generation?: number; expiresAt?: number; uuid?: string; buildId?: string; loaderVersion?: string; connectionId?: string };
export type AuditValue = string | number | boolean | null | { name: string; uuid: string }[];
export type ManagementOptions = Record<string, string | number | boolean>;
export type AuditEvent = { id: number; at: number; actor: string; account_id: string | null; action: string; override: number;
  severity: string; metadata: Record<string, AuditValue> };
export type AccountAlert = { id: string; auditId: number; recipientId: string; claimToken: string; attempts: number; repeats: number; event: AuditEvent };
export type AccountDetails = { account: AccountSummary; createdAt: number; lastSeen: number; discordUsername: string | null; displayName: string | null;
  buildId: string | null; loaderVersion: string | null; lease: { issuedAt: number; expiresAt: number } | null;
  session: { sessionId: string; minecraftUuid: string; minecraftName: string; buildId: string; connectedAt: number; lastHeartbeat: number; leaseExpiresAt: number; state: "online" | "offline" } | null;
  debug?: { deviceReference: string | null; generation: number; protocol: number; policyVersion: string; installationId: string | null } };
export type ManagementResult = AccountResult & { details?: AccountDetails; accounts?: AccountDetails[]; affectedAccounts?: AccountDetails[]; overview?: Record<string, number>;
  event?: AuditEvent; events?: AuditEvent[]; nextBefore?: number; builds?: Record<string, SqlStorageValue>[]; token?: string;
  action?: string; target?: string; options?: ManagementOptions };
type ManagementKind = "backend" | "user";
type ManagementConfirmation = { token: string; actor_id: string; guild_id: string; kind: ManagementKind; action: string; target: string;
  options: string; generation: number; expires_at: number; used_at: number | null; related_state: string };
type RuntimeRow = { installation_id: string; uuid: string; account_id: string; session_id: string; build_id: string; loader_version: string;
  generation: number; lease_issued_at: number; lease_expires_at: number; connected_at: number | null; last_heartbeat: number | null; connection_id: string | null };
type SessionCheck = { accountId: string; uuid: string; installationId: string; generation: number; buildId?: string; loaderVersion?: string; connectionId?: string; heartbeat?: boolean; heartbeatAt?: number };
const ADMIN_ALERT_IDS = ["978376257534328883", "1250627753191931914"];
const snowflake = /^[0-9]{17,20}$/, uuid = /^[a-f0-9]{32}$/, hash = /^[a-f0-9]{64}$/;
const failure = (error: unknown): { ok: false; error: string } => ({ ok: false, error: error instanceof AuthorizationError ? error.code : "authorization_unavailable" });

/** Identity uniqueness is one coordination atom, shared across all relay rooms.
 * ponytail: one SQL coordinator serializes global uniqueness; move to a global SQL service if identity throughput outgrows it. */
export class AccountStore extends DurableObject<AccountsConfig> {
  private readonly prompts: PromptJobs;
  private readonly community: CommunityAccounts;
  private readonly sharing: CommunitySharingStore;
  private readonly cosmetics: PlayerCosmetics;
  private readonly cosmeticAssets: CosmeticAssets;
  private readonly leaderboards: CommunityLeaderboards;
  private readonly bazaar: CommunityBazaar;
  private readonly bazaarMarket: BazaarMarket;
  private readonly bazaarAlertCooldowns = new Map<string, number>();
  constructor(ctx: DurableObjectState, env: AccountsConfig) {
    super(ctx, env);
    this.prompts = new PromptJobs(ctx.storage, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS sky_accounts (account_id TEXT PRIMARY KEY, discord_id TEXT UNIQUE NOT NULL,
        status TEXT NOT NULL DEFAULT 'active', license_status TEXT NOT NULL DEFAULT 'inactive', license_expires_at INTEGER,
        device_hash TEXT, hwid_reset_at INTEGER, generation INTEGER NOT NULL DEFAULT 0, activated_at INTEGER,
        created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, enrollment_blocked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS minecraft_accounts (uuid TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES sky_accounts(account_id),
        name TEXT NOT NULL, linked_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS minecraft_account_owner ON minecraft_accounts(account_id);
      CREATE TABLE IF NOT EXISTS account_devices (device_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES sky_accounts(account_id),
        registered_at INTEGER NOT NULL, revoked_at INTEGER);
      CREATE UNIQUE INDEX IF NOT EXISTS one_active_device_per_account ON account_devices(account_id) WHERE revoked_at IS NULL;
      CREATE TABLE IF NOT EXISTS installations (installation_id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES sky_accounts(account_id),
        device_hash TEXT NOT NULL, public_key TEXT NOT NULL, fingerprint TEXT UNIQUE NOT NULL, registered_at INTEGER NOT NULL, revoked_at INTEGER);
      CREATE INDEX IF NOT EXISTS installations_account ON installations(account_id);
      CREATE TABLE IF NOT EXISTS account_launch_sessions (session_id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES sky_accounts(account_id),
        installation_id TEXT NOT NULL REFERENCES installations(installation_id), platform TEXT NOT NULL,
        authorized_at INTEGER NOT NULL, generation INTEGER NOT NULL, revoked_at INTEGER, previous_session_id TEXT);
      CREATE UNIQUE INDEX IF NOT EXISTS one_active_launch_per_account ON account_launch_sessions(account_id) WHERE revoked_at IS NULL;
      CREATE INDEX IF NOT EXISTS account_launch_history ON account_launch_sessions(account_id, authorized_at);
      CREATE TABLE IF NOT EXISTS activation_keys (key_id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES sky_accounts(account_id),
        key_hash TEXT UNIQUE NOT NULL, issued_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, used_at INTEGER,
        used_installation TEXT, issued_by TEXT NOT NULL, revoked_at INTEGER);
      CREATE INDEX IF NOT EXISTS activation_account ON activation_keys(account_id);
      CREATE TABLE IF NOT EXISTS account_challenges (id TEXT PRIMARY KEY, nonce TEXT NOT NULL, input TEXT NOT NULL,
        approval_hash TEXT UNIQUE NOT NULL, discord_id TEXT, expires_at INTEGER NOT NULL, used_at INTEGER);
      CREATE INDEX IF NOT EXISTS account_challenges_expiry ON account_challenges(expires_at);
      CREATE TABLE IF NOT EXISTS minecraft_links (hash TEXT PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, name TEXT NOT NULL, expires_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS account_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL,
        account_id TEXT, action TEXT NOT NULL, override INTEGER NOT NULL DEFAULT 0, metadata TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS account_audit_owner ON account_audit(account_id, id);
      CREATE TABLE IF NOT EXISTS account_warnings (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, actor TEXT NOT NULL,
        reason TEXT NOT NULL, at INTEGER NOT NULL, removed_at INTEGER);
      CREATE TABLE IF NOT EXISTS account_requests (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS account_requests_expiry ON account_requests(expires_at);
      CREATE TABLE IF NOT EXISTS account_limits (id TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS account_limits_age ON account_limits(started);
      CREATE TABLE IF NOT EXISTS account_migrations (room TEXT NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(room, uuid));
      CREATE TABLE IF NOT EXISTS account_admin_bootstrap (id INTEGER PRIMARY KEY CHECK(id=1), discord_id TEXT NOT NULL, created_at INTEGER NOT NULL);
    `);
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(minecraft_links)").toArray().some(column => column.name === "discord_id")) {
      ctx.storage.sql.exec("ALTER TABLE minecraft_links ADD COLUMN discord_id TEXT");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(sky_accounts)").toArray().some(column => column.name === "is_admin")) {
      ctx.storage.sql.exec("ALTER TABLE sky_accounts ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_admin IN (0,1))");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(installations)").toArray().some(column => column.name === "platform")) {
      ctx.storage.sql.exec("ALTER TABLE installations ADD COLUMN platform TEXT");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(installations)").toArray().some(column => column.name === "hardware_hash")) {
      ctx.storage.sql.exec("ALTER TABLE installations ADD COLUMN hardware_hash TEXT");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(sky_accounts)").toArray().some(column => column.name === "hwid_bound_at")) {
      ctx.storage.sql.exec("ALTER TABLE sky_accounts ADD COLUMN hwid_bound_at INTEGER");
      ctx.storage.sql.exec("UPDATE sky_accounts SET hwid_bound_at=COALESCE((SELECT registered_at FROM account_devices WHERE account_devices.device_hash=sky_accounts.device_hash),activated_at) WHERE device_hash IS NOT NULL");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(sky_accounts)").toArray().some(column => column.name === "hardware_hash")) {
      ctx.storage.sql.exec("ALTER TABLE sky_accounts ADD COLUMN hardware_hash TEXT");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(activation_keys)").toArray().some(column => column.name === "hardware_transfer")) {
      ctx.storage.sql.exec("ALTER TABLE activation_keys ADD COLUMN hardware_transfer INTEGER NOT NULL DEFAULT 0 CHECK(hardware_transfer IN (0,1))");
    }
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS account_discord_names (discord_id TEXT PRIMARY KEY, username TEXT NOT NULL, display_name TEXT NOT NULL, updated_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS account_runtime (installation_id TEXT NOT NULL, uuid TEXT NOT NULL, account_id TEXT NOT NULL,
        session_id TEXT NOT NULL, build_id TEXT NOT NULL, loader_version TEXT NOT NULL, generation INTEGER NOT NULL,
        lease_issued_at INTEGER NOT NULL, lease_expires_at INTEGER NOT NULL, connected_at INTEGER, last_heartbeat INTEGER, connection_id TEXT,
        PRIMARY KEY(installation_id,uuid));
      CREATE INDEX IF NOT EXISTS account_runtime_owner ON account_runtime(account_id,lease_issued_at);
      CREATE TABLE IF NOT EXISTS account_management_confirmations (token TEXT PRIMARY KEY, actor_id TEXT NOT NULL, guild_id TEXT NOT NULL,
        kind TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, options TEXT NOT NULL, generation INTEGER NOT NULL,
        expires_at INTEGER NOT NULL, used_at INTEGER, related_state TEXT NOT NULL DEFAULT '');
      CREATE INDEX IF NOT EXISTS account_confirmation_expiry ON account_management_confirmations(expires_at);
      CREATE TABLE IF NOT EXISTS account_key_recovery (discord_id TEXT PRIMARY KEY,last_issued_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS account_loader_delivery (discord_id TEXT PRIMARY KEY,last_requested_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS account_build_policy (kind TEXT NOT NULL,version TEXT NOT NULL,revoked_at INTEGER,actor TEXT NOT NULL,
        reason TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,version));
      CREATE TABLE IF NOT EXISTS account_alerts (id TEXT PRIMARY KEY,audit_id INTEGER NOT NULL,recipient_id TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0,repeats INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL,lease_until INTEGER NOT NULL DEFAULT 0,claim_token TEXT);
      CREATE INDEX IF NOT EXISTS account_alert_due ON account_alerts(next_at);
      CREATE TABLE IF NOT EXISTS account_alert_dedup (id TEXT PRIMARY KEY,last_at INTEGER NOT NULL,audit_id INTEGER NOT NULL);
    `);
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(account_audit)").toArray().some(column => column.name === "severity")) {
      ctx.storage.sql.exec("ALTER TABLE account_audit ADD COLUMN severity TEXT NOT NULL DEFAULT 'information'");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(account_runtime)").toArray().some(column => column.name === "connection_id")) {
      ctx.storage.sql.exec("ALTER TABLE account_runtime ADD COLUMN connection_id TEXT");
    }
    if (!ctx.storage.sql.exec<{ name: string }>("PRAGMA table_info(account_management_confirmations)").toArray().some(column => column.name === "related_state")) {
      ctx.storage.sql.exec("ALTER TABLE account_management_confirmations ADD COLUMN related_state TEXT NOT NULL DEFAULT ''");
    }
    if (env.ADMIN_BOOTSTRAP_USER_ID !== undefined) {
      if (typeof env.ADMIN_BOOTSTRAP_USER_ID !== "string" || !snowflake.test(env.ADMIN_BOOTSTRAP_USER_ID)) throw new Error("Invalid ADMIN_BOOTSTRAP_USER_ID");
      const bootstrapped = ctx.storage.transactionSync(() => {
        if (ctx.storage.sql.exec("SELECT id FROM account_admin_bootstrap WHERE id=1").toArray().length) return;
        const account = this.ensureAccount(env.ADMIN_BOOTSTRAP_USER_ID!);
        ctx.storage.sql.exec("UPDATE sky_accounts SET is_admin=1 WHERE account_id=?", account.account_id);
        ctx.storage.sql.exec("INSERT INTO account_admin_bootstrap VALUES(1,?,?)", account.discord_id, Date.now());
        this.audit("bootstrap", account.account_id, "admin_bootstrapped", { target: account.discord_id });
        return account.discord_id;
      });
      if (bootstrapped) console.info({ event: "admin_bootstrapped", discordId: bootstrapped });
    }
    this.community = new CommunityAccounts(ctx.storage.sql, fn => ctx.storage.transactionSync(fn), env, {
      ensure: id => this.ensureAccount(id), account: id => this.byId(id), isAdmin: actor => this.isAdmin(actor),
      interaction: actor => this.interaction(actor), limit: (key, count) => this.limit(key, count),
      audit: (actor, account, action, metadata) => this.audit(actor, account, action, metadata),
      mergeAccount: (source, target) => this.sharing.mergeAccount(source, target), mergeUuid: (uuid, target) => this.sharing.mergeUuid(uuid, target),
      detachUuid: (uuid, source) => this.sharing.detachUuid(uuid, source),
    });
    this.sharing = new CommunitySharingStore(ctx.storage.sql, {
      owner: player => { const account = this.getAccountByUuid(player); return account ? { accountId: account.accountId, uuids: account.uuids.map(p => p.uuid) } : null; },
      session: input => this.community.checkSession(input), blacklisted: player => this.community.blacklistedUuid(player),
      purgeBazaar: (owner, player) => this.bazaar?.purge(owner, player),
    });
    this.cosmetics = new PlayerCosmetics(ctx.storage);
    this.cosmeticAssets = new CosmeticAssets(ctx.storage, this.cosmetics, {
      token: value => this.community.tokenSession(value), current: session => this.community.checkSession(session),
      owner: player => this.getAccountByUuid(player)?.accountId ?? null, allowed: player => !this.community.blacklistedUuid(player),
      limit: (key, count) => this.limit(key, count),
    });
    this.bazaar = new CommunityBazaar(ctx.storage.sql, { check: (player, category, at) => this.sharing.check(player, category, at) });
    this.bazaarMarket = new BazaarMarket(ctx.storage.sql, env.BAZAAR_MARKET_PROVIDER);
    this.leaderboards = new CommunityLeaderboards(ctx.storage.sql, {
      check: (player, category, at) => this.sharing.check(player, category, at),
      record: (player, raw, name) => this.sharing.recordContribution(player, raw, name),
      bazaar: (query, now) => this.bazaar.query(query, now),
    });
  }
  communityChallenge(raw: Record<string, unknown>) { return this.community.challenge(raw); }
  communitySessionForToken(token: string) { return this.community.tokenSession(token); }
  communityBazaarMarket(active: boolean) { return this.bazaarMarket.get(active); }
  communityBazaarHistory(product: string, daily: boolean) { return this.bazaarMarket.history(product, daily); }
  communityBazaarReplay(token: string) { return this.bazaar.replay(token); }
  communityBazaarGallery() { return this.bazaar.gallery(); }
  claimCommunityBazaarAlert(session: CommunitySessionCheck, eventId: string, product: string): string | null {
    const current = this.community.checkSession(session), now = Date.now();
    if (!current.ok || !session.connectionId || current.connectionId !== session.connectionId || !/^[a-f0-9]{32}$/.test(eventId) || !/^[A-Z0-9_:;.\-]{1,100}$/.test(product)) return null;
    for (const [key, until] of this.bazaarAlertCooldowns) if (until <= now) this.bazaarAlertCooldowns.delete(key);
    const owner = session.accountId, keys = [`event:${owner}:${eventId}`, `product:${owner}:${product}`, `owner:${owner}`];
    if (keys.some(key => (this.bazaarAlertCooldowns.get(key) ?? 0) > now) || this.bazaarAlertCooldowns.size >= 10000) return null;
    for (const key of keys) this.bazaarAlertCooldowns.set(key, now + (key.startsWith("owner:") ? 60000 : 600000));
    return current.discordId ?? null;
  }
  async communityBazaar(token: string, action: string, raw: Record<string, unknown>) {
    try {
      const session = await this.community.tokenSession(token); if (!session.ok) return { ok: false, error: session.error };
      return this.ctx.storage.transactionSync(() => {
        const current = this.community.checkSession({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! });
        if (!current.ok) return { ok: false, error: current.error };
        const account = this.getAccountByUuid(session.uuid!), player = account?.uuids.find(player => player.uuid === session.uuid);
        if (!account || !player || account.accountId !== session.accountId) return { ok: false, error: "session_identity_changed" };
        const identity = { accountId: account.accountId, uuid: player.uuid, name: player.name };
        this.limit(`bazaar:${action}:${account.accountId}`, action === "publish" ? 5 : 30);
        if (!["stats", "settings-get"].includes(action) && (raw.expectedAccountId !== account.accountId || raw.expectedUuid !== player.uuid)) return { ok: false, error: "session_identity_changed" };
        switch (action) {
          case "contributions": return this.bazaar.record(identity, raw);
          case "contributions-remove": return this.bazaar.remove(identity, raw);
          case "stats": return this.bazaar.stats(identity, raw);
          case "summary": return this.bazaar.summary(identity, raw);
          case "settings-get": return this.bazaar.settings(identity);
          case "settings": return this.bazaar.settings(identity, raw);
          case "publish": return this.bazaar.publish(identity, raw);
          case "revoke": return this.bazaar.revoke(identity, raw, false);
          case "delete": return this.bazaar.revoke(identity, raw, true);
          default: return { ok: false, error: "invalid_action" };
        }
      });
    } catch (error) { return failure(error); }
  }
  discordBazaar(actor: DeveloperActor, action: string, period: string): BazaarStatsResult {
    try {
      if (actor.guildId !== this.env.DISCORD_GUILD_ID || !snowflake.test(actor.id) || !snowflake.test(actor.interactionId) || !this.communityIdentityAllowed(actor.id)) deny("not_authorized");
      this.interaction(actor); this.limit(`bazaar-discord:${actor.id}`, 30);
      const account = this.getAccountByDiscordId(actor.id), player = account?.uuids[0];
      if (!account || !player) return { ok: false, error: "account_required" };
      const stats = this.bazaar.stats({ accountId: account.accountId, uuid: player.uuid, name: player.name }, { period });
      const policy = this.sharing.getVerified(player.uuid).policy;
      return stats.ok && policy ? { ...stats, sharing: { enabled: policy.enabled, economy: policy.economy, leaderboards: policy.leaderboards, publicName: policy.publicName } } : stats;
    } catch (error) { return failure(error); }
  }
  communityCosmeticOverview(token: string) { return this.cosmeticAssets.overview(token); }
  communityCosmeticSave(token: string, raw: Record<string, unknown>) { return this.cosmeticAssets.save(token, raw); }
  async communityCosmeticGrant(token: string, raw: Record<string, unknown>) { const result = await this.cosmeticAssets.grant(token, raw); await this.armCosmeticCleanup(); return result; }
  communityCosmeticClaim(token: string, grant: string) { return this.cosmeticAssets.claim(token, grant); }
  communityCosmeticFinish(token: string, assetId: string, digest: string, size: number) { return this.cosmeticAssets.finish(token, assetId, digest, size); }
  async communityCosmeticFail(assetId: string) { this.cosmeticAssets.fail(assetId); await this.armCosmeticCleanup(); }
  communityCosmeticActiveAsset(assetId: string, player: string) { return this.cosmeticAssets.active(assetId, player); }
  communityCosmeticPreviewAsset(token: string, assetId: string, player: string) { return this.cosmeticAssets.preview(token, assetId, player); }
  async communityCosmeticDelete(token: string, assetId: string, raw: Record<string, unknown>) { const result = await this.cosmeticAssets.remove(token, assetId, raw); await this.armCosmeticCleanup(); return result; }
  private async armCosmeticCleanup(): Promise<void> {
    const due = this.cosmeticAssets.nextCleanup(), existing = await this.ctx.storage.getAlarm();
    if (due !== null && (existing === null || due < existing)) await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, due));
  }
  async cleanupCosmeticAssets(): Promise<void> {
    const keys = this.cosmeticAssets.cleanupKeys();
    if (keys.length && this.env.COSMETIC_ASSETS) {
      try { await this.env.COSMETIC_ASSETS.delete(keys); this.cosmeticAssets.cleanupComplete(keys); }
      catch { console.warn({ event: "cosmetic_asset_cleanup_retry" }); }
    }
    await this.armCosmeticCleanup();
  }
  communityCosmeticProfiles(players: string[]) { return players.length <= 16 && players.every(player => uuid.test(player)) ? players.map(player => this.cosmeticAssets.publicProfile(player)) : []; }
  importCommunityCosmetics(room: string, profiles: CosmeticProfile[]) {
    if (room !== (this.env.DISCORD_INTERACTIONS_ROOM ?? "friends") || profiles.length > 128) return;
    this.ctx.storage.transactionSync(() => profiles.forEach(profile => this.cosmetics.import(profile)));
  }
  communityCosmeticCommand(command: DiscordCommand, config: CosmeticsConfig, player: string): Promise<{response: object; changed?: CosmeticProfile}> {
    if (this.getAccountByUuid(player)?.discordId !== command.actor || !this.communityIdentityAllowed(command.actor, player)) return Promise.resolve({ response: { type: 4, data: { content: "Connect your Minecraft account first.", flags: 64, allowed_mentions: { parse: [] } } } });
    return this.cosmetics.command(command, config, Date.now(), { uuid: player });
  }
  communityConnectStart(raw: Record<string, unknown>) { return this.community.start(raw); }
  communityConnectPoll(raw: Record<string, unknown>) { return this.community.poll(raw); }
  communityConnectCancel(raw: Record<string, unknown>) { return this.community.cancel(raw); }
  communityRefresh(raw: Record<string, unknown>) { return this.community.refresh(raw); }
  communityDisconnect(raw: Record<string, unknown>) { return this.community.refresh(raw, true); }
  communityOAuthPreview(launch: string) { return this.community.oauthPreview(launch); }
  communityOAuthBegin(launch: string, cookie: string) { return this.community.beginOAuth(launch, cookie); }
  communityOAuthClaim(state: string, cookie: string) { return this.community.claimOAuth(state, cookie); }
  communityOAuthFinish(flow: string, user: { id: string; username: string; displayName: string }) { return this.community.finishOAuth(flow, user); }
  authorizeCommunitySession(input: { uuid: string; token: string; challenge: string; modVersion?: string }) { return this.community.authorize(input); }
  checkCommunitySession(input: CommunitySessionCheck) { return this.community.checkSession(input); }
  consumeCommunitySessionRequest(input: CommunitySessionCheck) { return this.community.consumeRequest(input); }
  reportCommunityTrafficViolation(input: CommunitySessionCheck) { return this.community.reportTrafficViolation(input); }
  markCommunitySessionOffline(sessionId: string, connectionId: string) { this.community.markOffline(sessionId, connectionId); }
  communityUserCommand(actor: DeveloperActor, action: string, options: Record<string, unknown> = {}) { return this.community.user(actor, action, options); }
  communityRead(actor: DeveloperActor, action: string, target = "", options: Record<string, unknown> = {}) { return this.community.read(actor, action, target, options); }
  communityManage(actor: DeveloperActor, action: string, target: string, options: Record<string, unknown> = {}) { return this.community.manage(actor, action, target, options); }
  prepareCommunityManagement(actor: DeveloperActor, kind: string, action: string, target: string, options: Record<string, unknown> = {}) { return this.community.prepare(actor, kind, action, target, options); }
  confirmCommunityManagement(actor: DeveloperActor, kind: string, token: string, confirm: boolean) { return this.community.confirm(actor, kind, token, confirm); }
  communityIdentityAllowed(discordId: string, player?: string) { return !this.community.blacklisted(discordId, player); }
  getSharing(input: SharingSession) { return this.sharing.get(input); }
  updateSharing(input: SharingSession, revision: number, policy: unknown) { return this.ctx.storage.transactionSync(() => this.sharing.update(input, revision, policy)); }
  checkSharing(player: string, category: SharingCategory, recordedAt?: number) { return this.sharing.check(player, category, recordedAt); }
  checkSharingMany(input: { uuid: string; category: SharingCategory; recordedAt?: number }[]) { return this.sharing.checkMany(input); }
  checkCommunitySharing(session: CommunitySessionCheck, input: { uuid: string; category: SharingCategory; recordedAt?: number }[]) {
    const current = this.community.checkSession(session);
    if (!current.ok) return { ok: false, error: current.error, decisions: [] };
    return { ok: true, decisions: this.sharing.checkMany(input) };
  }
  getSharingVerified(player: string) { return this.sharing.getVerified(player); }
  updateSharingVerified(player: string, revision: number, policy: unknown) { return this.ctx.storage.transactionSync(() => this.sharing.updateVerified(player, revision, policy)); }
  async communityGetSharing(token: string) {
    const session = await this.community.tokenSession(token);
    return session.ok ? this.sharing.get({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! }) : { ok: false, error: session.error };
  }
  async communityUpdateSharing(token: string, raw: Record<string, unknown>) {
    const session = await this.community.tokenSession(token);
    if (!session.ok) return { ok: false, error: session.error };
    if (!raw || raw.expectedAccountId !== session.accountId || raw.expectedUuid !== session.uuid) return { ok: false, error: "session_identity_changed" };
    return this.ctx.storage.transactionSync(() => {
      const result = this.sharing.update({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! }, Number(raw.revision), raw.policy);
      if (result.ok && result.suppressedCategories?.includes("rng")) this.leaderboards.suppress(result.affectedUuids ?? []);
      return result;
    });
  }
  recordCommunityContribution(player: string, raw: unknown, name: string) { return this.ctx.storage.transactionSync(() => this.sharing.recordContribution(player, raw, name)); }
  recordCommunitySessionContribution(session: CommunitySessionCheck, raw: unknown) {
    return this.ctx.storage.transactionSync(() => {
      const current = this.community.checkSession(session); if (!current.ok) return { ok: false, error: current.error };
      const owner = this.getAccountByUuid(session.uuid); if (!owner || owner.accountId !== session.accountId) return { ok: false, error: "session_identity_changed" };
      return this.sharing.recordContribution(session.uuid, raw, owner.uuids.find(player => player.uuid === session.uuid)!.name);
    });
  }
  async communityContribute(token: string, raw: Record<string, unknown>) {
    const session = await this.community.tokenSession(token);
    if (!session.ok) return { ok: false, error: session.error };
    return this.ctx.storage.transactionSync(() => {
      const current = this.community.checkSession({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! });
      if (!current.ok) return { ok: false, error: current.error };
      if (raw.expectedAccountId !== session.accountId || raw.expectedUuid !== session.uuid) return { ok: false, error: "session_identity_changed" };
      const owner = this.getAccountByUuid(session.uuid!); if (!owner || owner.accountId !== session.accountId) return { ok: false, error: "session_identity_changed" };
      return this.sharing.recordContribution(session.uuid!, raw.contribution, owner.uuids.find(player => player.uuid === session.uuid)!.name);
    });
  }
  async communityLeaderboard(token: string, query: LeaderboardQuery) {
    const session = await this.community.tokenSession(token);
    if (!session.ok) return { ok: false, error: session.error };
    return this.ctx.storage.transactionSync(() => {
      const current = this.community.checkSession({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! });
      if (!current.ok) return { ok: false, error: current.error };
      this.limit(`leaderboards:${session.accountId}`, 60);
      if (query.category === "rng" && (query.metric === undefined || query.metric === "rng_drops")) this.leaderboards.restoreAcceptedRng(session.uuid!, session.sessionId!);
      return this.leaderboards.query(query);
    });
  }
  async communityJournalComparison(token: string, query: JournalComparisonQuery): Promise<JournalComparisonResult> {
    const session = await this.community.tokenSession(token);
    if (!session.ok) return { ok: false, error: session.error };
    return this.ctx.storage.transactionSync(() => {
      const current = this.community.checkSession({ sessionId: session.sessionId!, accountId: session.accountId!, uuid: session.uuid!, generation: session.generation! });
      if (!current.ok) return { ok: false, error: current.error };
      try { this.limit(`journal-comparison:${session.accountId}`, 60); }
      catch (error) { return { ok: false, error: error instanceof AuthorizationError ? error.code : "comparison_unavailable" }; }
      this.leaderboards.restoreAcceptedRng(session.uuid!, session.sessionId!);
      return this.leaderboards.journalComparison(session.uuid!, query);
    });
  }
  discordLeaderboard(actor: DeveloperActor, query: LeaderboardQuery) {
    if (actor.guildId !== this.env.DISCORD_GUILD_ID || !snowflake.test(actor.id) || !snowflake.test(actor.interactionId) || !this.communityIdentityAllowed(actor.id)) return { ok: false, error: "not_authorized" };
    this.interaction(actor); this.limit(`leaderboards-discord:${actor.id}`, 60);
    return this.leaderboards.query(query);
  }
  recordAcceptedCommunityRng(player: string, event: PublicRngEvent, sourceId: string, expectedAccountId: string) {
    return this.ctx.storage.transactionSync(() => {
      if (typeof expectedAccountId !== "string" || this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", player).toArray()[0]?.account_id !== expectedAccountId) return { ok: false, error: "stale_author" };
      return this.leaderboards.acceptRng(player, event, sourceId, this.env.DISCORD_RNG_CHANNEL_ID);
    });
  }
  suppressCommunityRng(players: string[]) { this.ctx.storage.transactionSync(() => this.leaderboards.suppress(players)); }
  acceptCommunityFeedNonce(nonce: string) { return this.ctx.storage.transactionSync(() => this.leaderboards.acceptNonce(nonce)); }
  claimCommunityRng(channel: string, receipts?: FeedReceipt[]) { return this.ctx.storage.transactionSync(() => this.leaderboards.claim(channel, receipts)); }
  discordRecentRng(actor: DeveloperActor, channel: string) {
    if (actor.guildId !== this.env.DISCORD_GUILD_ID || channel !== this.env.DISCORD_RNG_CHANNEL_ID || !snowflake.test(actor.id) ||
      !snowflake.test(actor.interactionId) || !this.communityIdentityAllowed(actor.id)) return { ok: false, events: [] };
    return this.ctx.storage.transactionSync(() => {
      this.interaction(actor); this.limit(`rng-carousel:${actor.id}`, 60);
      return { ok: true, events: this.leaderboards.recentRng(channel) };
    });
  }
  acknowledgeCommunityRng(raw: Record<string, unknown>, channel: string) { return this.ctx.storage.transactionSync(() => this.leaderboards.acknowledge(raw, channel)); }
  async promptSubmit(actor: PromptActor, prompt: string, source: Record<string, unknown>) {
    const result = this.prompts.submit(actor, prompt, source); await this.prompts.arm(); return result;
  }
  promptIntake(actor: PromptActor) { return this.prompts.intake(actor); }
  async promptCommand(actor: PromptActor, action: string, input: Record<string, unknown> = {}) {
    const result = this.prompts.command(actor, action, input); await this.prompts.arm(); return result;
  }
  promptInspect(actor: PromptActor, id?: string) { return this.prompts.inspect(actor, id); }
  promptPending(actor: PromptActor, id: string, request: string) { return this.prompts.pending(actor, id, request); }
  async promptRunner(input: Record<string, unknown>, nonce: string) {
    const result = this.prompts.runner(input, nonce); await this.prompts.arm(); return result;
  }
  async alarm(): Promise<void> {
    await this.prompts.alarm();
    await Promise.all(this.claimAccountAlerts(4).map(async alert => {
      this.completeAccountAlert(alert.id, alert.claimToken, await deliverAccountAlert(this.env, alert));
    }));
    const due = this.nextAccountAlertAt(), current = await this.ctx.storage.getAlarm();
    if (due !== null && (current === null || due < current)) await this.ctx.storage.setAlarm(Math.max(Date.now() + 1000, due));
    await this.cleanupCosmeticAssets();
  }
  private byDiscord(discordId: string): AccountRow | undefined {
    return this.ctx.storage.sql.exec<AccountRow>("SELECT * FROM sky_accounts WHERE discord_id=?", discordId).toArray()[0];
  }
  private byId(accountId: string): AccountRow | undefined {
    return this.ctx.storage.sql.exec<AccountRow>("SELECT * FROM sky_accounts WHERE account_id=?", accountId).toArray()[0];
  }
  private summary(account: AccountRow): AccountSummary {
    return { accountId: account.account_id, discordId: account.discord_id, status: account.status, isAdmin: account.is_admin === 1, activated: account.activated_at !== null, licenseStatus: account.license_status,
      licenseExpiresAt: account.license_expires_at, deviceRegistered: account.device_hash !== null, hwidResetAt: account.hwid_reset_at,
      hwidBoundAt: account.hwid_bound_at, nextHwidChangeAllowedAt: account.hwid_reset_at === null ? null : account.hwid_reset_at + DEVICE_RESET_COOLDOWN,
      uuids: this.ctx.storage.sql.exec<{ uuid: string; name: string }>("SELECT uuid,name FROM minecraft_accounts WHERE account_id=? ORDER BY linked_at,uuid", account.account_id).toArray() };
  }
  getAccountByDiscordId(discordId: string): AccountSummary | null { const account = this.byDiscord(discordId); return account ? this.summary(account) : null; }
  /** Authority comes only from durable account state. Discord roles and permissions grant nothing. */
  isAdmin(actor: DeveloperActor): boolean {
    if (!actor || typeof actor.id !== "string" || !snowflake.test(actor.id) || !this.env.DISCORD_GUILD_ID || actor.guildId !== this.env.DISCORD_GUILD_ID) return false;
    const account = this.byDiscord(actor.id);
    return account?.is_admin === 1 && account.status === "active";
  }
  getAccountByUuid(id: string): AccountSummary | null {
    const account = this.ctx.storage.sql.exec<AccountRow>("SELECT a.* FROM sky_accounts a JOIN minecraft_accounts m ON a.account_id=m.account_id WHERE m.uuid=?", id).toArray()[0];
    return account ? this.summary(account) : null;
  }
  getAccountByDevice(deviceHash: string): AccountSummary | null {
    const account = this.ctx.storage.sql.exec<AccountRow>("SELECT a.* FROM sky_accounts a JOIN account_devices d ON a.account_id=d.account_id WHERE d.device_hash=? AND d.revoked_at IS NULL", deviceHash).toArray()[0];
    return account ? this.summary(account) : null;
  }
  cacheDiscordIdentity(actor: DeveloperActor, id: string, identity: { username: string; displayName: string }): AccountResult {
    try {
      if (!snowflake.test(id) || actor.guildId !== this.env.DISCORD_GUILD_ID || (actor.id !== id && !this.isAdmin(actor))) deny("forbidden");
      if (typeof identity?.username !== "string" || typeof identity.displayName !== "string" ||
        !identity.username.trim() || identity.username.length > 100 || identity.displayName.length > 100 ||
        /[\x00-\x1f\x7f]/.test(identity.username + identity.displayName)) deny("invalid_request");
      this.ctx.storage.sql.exec("INSERT INTO account_discord_names VALUES(?,?,?,?) ON CONFLICT(discord_id) DO UPDATE SET username=excluded.username,display_name=excluded.display_name,updated_at=excluded.updated_at",
        id, identity.username, identity.displayName || identity.username, Date.now());
      return { ok: true };
    } catch (error) { return failure(error); }
  }
  private details(account: AccountRow, debug = false): AccountDetails {
    const name = this.ctx.storage.sql.exec<{ username: string; display_name: string }>("SELECT username,display_name FROM account_discord_names WHERE discord_id=?", account.discord_id).toArray()[0];
    const runtime = this.ctx.storage.sql.exec<RuntimeRow>("SELECT * FROM account_runtime WHERE account_id=? ORDER BY lease_issued_at DESC LIMIT 1", account.account_id).toArray()[0];
    const player = runtime && this.ctx.storage.sql.exec<{ name: string }>("SELECT name FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, runtime.uuid).toArray()[0];
    const online = runtime && runtime.connected_at !== null && runtime.last_heartbeat !== null && runtime.last_heartbeat > Date.now() - 90_000 &&
      runtime.lease_expires_at > Date.now() && runtime.generation === account.generation && !licenseDenial(account) &&
      this.buildAllowed("mod", runtime.build_id).ok && this.buildAllowed("loader", runtime.loader_version).ok;
    return { account: this.summary(account), createdAt: account.created_at, lastSeen: account.last_seen,
      discordUsername: name?.username ?? null, displayName: name?.display_name ?? null,
      buildId: runtime?.build_id ?? null, loaderVersion: runtime?.loader_version ?? null,
      lease: runtime ? { issuedAt: runtime.lease_issued_at, expiresAt: runtime.lease_expires_at } : null,
      session: runtime?.connected_at !== null && runtime?.connected_at !== undefined ? { sessionId: runtime.session_id,
        minecraftUuid: runtime.uuid, minecraftName: player?.name ?? "Minecraft account", buildId: runtime.build_id,
        connectedAt: runtime.connected_at, lastHeartbeat: runtime.last_heartbeat ?? runtime.connected_at,
        leaseExpiresAt: runtime.lease_expires_at, state: online ? "online" : "offline" } : null,
      ...(debug ? { debug: { deviceReference: account.device_hash, generation: account.generation, protocol: 3,
        policyVersion: "persistent-hwid-v1", installationId: runtime?.installation_id ?? null } } : {}) };
  }
  managementRead(actor: DeveloperActor, action: string, target = "", options: Record<string, unknown> = {}): ManagementResult {
    try {
      if (!this.isAdmin(actor)) deny("admin_required");
      this.limit(`management-read:${actor.id}`, 120);
      if (action === "search") {
        if (typeof options.query !== "string" || !options.query.trim() || options.query.length > 100) deny("invalid_request");
        const query = options.query.trim().toLowerCase(), compact = query.replaceAll("-", "");
        const rows = this.ctx.storage.sql.exec<AccountRow>(`SELECT DISTINCT a.* FROM sky_accounts a LEFT JOIN account_discord_names d ON a.discord_id=d.discord_id
          LEFT JOIN minecraft_accounts m ON a.account_id=m.account_id WHERE instr(lower(d.username),?)>0 OR instr(lower(d.display_name),?)>0 OR
          instr(lower(m.name),?)>0 OR m.uuid=? OR a.account_id=? OR a.discord_id=? ORDER BY a.last_seen DESC LIMIT 25`,
          query, query, query, compact, query, query).toArray();
        return { ok: true, accounts: rows.map(row => this.details(row)) };
      }
      if (action === "overview") {
        const now = Date.now();
        const count = (query: string, ...values: SqlStorageValue[]) => this.ctx.storage.sql.exec<{ n: number }>(query, ...values).one().n;
        return { ok: true, overview: {
          active: count("SELECT COUNT(*) AS n FROM sky_accounts WHERE status='active' AND license_status='active' AND (license_expires_at IS NULL OR license_expires_at>?)", now),
          revoked: count("SELECT COUNT(*) AS n FROM sky_accounts WHERE license_status='revoked'"),
          pending: count("SELECT COUNT(*) AS n FROM sky_accounts WHERE status='active' AND license_status='active' AND activated_at IS NULL AND (license_expires_at IS NULL OR license_expires_at>?)", now),
          online: count(`SELECT COUNT(DISTINCT a.account_id) AS n FROM sky_accounts a JOIN account_runtime r ON a.account_id=r.account_id
            WHERE a.status='active' AND a.license_status='active' AND (a.license_expires_at IS NULL OR a.license_expires_at>?)
            AND r.generation=a.generation AND r.connected_at IS NOT NULL AND r.last_heartbeat>? AND r.lease_expires_at>?
            AND NOT EXISTS (SELECT 1 FROM account_build_policy b WHERE b.revoked_at IS NOT NULL AND ((b.kind='mod' AND b.version=r.build_id) OR (b.kind='loader' AND b.version=r.loader_version)))`, now, now - 90_000, now),
          transfersThisWeek: count("SELECT COUNT(*) AS n FROM account_audit WHERE action='device_transferred' AND override=0 AND at>=?", now - DEVICE_RESET_COOLDOWN),
          securityWarnings: count("SELECT COUNT(*) AS n FROM account_audit WHERE severity IN ('warning','security','critical') AND at>=?", now - ACTIVATION_TTL),
        } };
      }
      if (action === "builds") return { ok: true, builds: this.ctx.storage.sql.exec("SELECT * FROM account_build_policy ORDER BY kind,version LIMIT 100").toArray() };
      if (action === "event") {
        if (!Number.isSafeInteger(options.id) || Number(options.id) < 1) deny("invalid_request");
        const row = this.ctx.storage.sql.exec("SELECT * FROM account_audit WHERE id=?", options.id as number).toArray()[0];
        if (!row) deny("event_not_found"); return { ok: true, event: this.auditEvent(row) };
      }
      const account = target ? this.byDiscord(target) : undefined;
      if (target && (!snowflake.test(target) || !account)) deny("account_required");
      if (action === "audit") {
        const where: string[] = [], values: SqlStorageValue[] = [];
        if (account) { where.push("account_id=?"); values.push(account.account_id); }
        if (options.event !== undefined) { if (typeof options.event !== "string" || !/^[a-z_]{1,64}$/.test(options.event)) deny("invalid_request"); where.push("action=?"); values.push(options.event); }
        if (options.severity !== undefined) { if (!["information", "warning", "security", "critical"].includes(String(options.severity))) deny("invalid_request"); where.push("severity=?"); values.push(String(options.severity)); }
        for (const [key, column, sign] of [["before", "id", "<"], ["since", "at", ">="]] as const) {
          if (options[key] !== undefined) { if (!Number.isSafeInteger(options[key]) || Number(options[key]) < 0) deny("invalid_request"); where.push(`${column}${sign}?`); values.push(options[key] as number); }
        }
        const limit = options.limit ?? 10; if (!Number.isSafeInteger(limit) || Number(limit) < 1 || Number(limit) > 25) deny("invalid_request");
        const rows = this.ctx.storage.sql.exec(`SELECT * FROM account_audit${where.length ? ` WHERE ${where.join(" AND ")}` : ""} ORDER BY id DESC LIMIT ?`, ...values, Number(limit) + 1).toArray();
        const events = rows.slice(0, Number(limit)).map(row => this.auditEvent(row));
        return { ok: true, ...(account ? { account: this.summary(account) } : {}), events,
          audit: rows.slice(0, Number(limit)), ...(rows.length > Number(limit) ? { nextBefore: events.at(-1)!.id } : {}) };
      }
      if (!["info", "debug", "sessions", "uuids"].includes(action)) deny("invalid_action");
      if (!account) deny("account_required");
      return { ok: true, account: this.summary(account), details: this.details(account, action === "debug") };
    } catch (error) { return failure(error); }
  }
  private managementGeneration(action: string, target: string, options: Record<string, unknown>): number {
    if (action.startsWith("build_")) return this.ctx.storage.sql.exec<{ revision: number }>("SELECT revision FROM account_build_policy WHERE kind=? AND version=?", String(options.kind ?? "mod"), target).toArray()[0]?.revision ?? 0;
    return this.byDiscord(target)?.generation ?? -1;
  }
  private managementRelatedState(action: string, target: string, options: Record<string, unknown>): string {
    if (action === "hwid_bind") {
      const device = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM account_devices WHERE device_hash=?", String(options.deviceHash)).toArray()[0];
      const owner = device && this.byId(device.account_id);
      return JSON.stringify(owner ? { accountId: owner.account_id, generation: owner.generation } : null);
    }
    if (action === "account_unlink" && options.uuid === undefined) {
      const account = this.byDiscord(target);
      return JSON.stringify(account ? this.ctx.storage.sql.exec<{ uuid: string }>("SELECT uuid FROM minecraft_accounts WHERE account_id=? ORDER BY uuid", account.account_id).toArray().map(player => player.uuid) : []);
    }
    return "";
  }
  prepareManagement(actor: DeveloperActor, kind: ManagementKind, action: string, target: string, options: Record<string, unknown> = {}): ManagementResult {
    try {
      if (actor.guildId !== this.env.DISCORD_GUILD_ID || !snowflake.test(actor.id) || !snowflake.test(actor.interactionId)) deny("discord_authorization_required");
      if (kind === "backend" ? !this.isAdmin(actor) : kind !== "user" || actor.id !== target) deny(kind === "backend" ? "admin_required" : "forbidden");
      const allowed = kind === "backend" ? ["license_revoke", "account_suspend", "account_unlink", "hwid_reset", "hwid_revoke", "hwid_bind", "session_kick", "build_revoke", "admin_add"] : ["unlink", "reset"];
      if (!allowed.includes(action) || Object.keys(options).length > 8 || Object.entries(options).some(([key, value]) =>
        key === "managementToken" || !/^[a-zA-Z_-]{1,40}$/.test(key) || !["string", "number", "boolean"].includes(typeof value) ||
        typeof value === "number" && !Number.isFinite(value) || typeof value === "string" && value.length > 500)) deny("invalid_action");
      if (action.startsWith("build_")) { if (!/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(target) || !["mod", "loader"].includes(String(options.kind ?? "mod"))) deny("invalid_build"); }
      else if (!snowflake.test(target)) deny("invalid_discord_id");
      const account = this.byDiscord(target);
      if (!action.startsWith("build_") && !account && action !== "admin_add") deny("account_required");
      if (action === "hwid_bind" && (typeof options.deviceHash !== "string" || !hash.test(options.deviceHash))) deny("invalid_device");
      if (options.uuid !== undefined && (!account || typeof options.uuid !== "string" ||
        !this.ctx.storage.sql.exec("SELECT uuid FROM minecraft_accounts WHERE uuid=? AND account_id=?", options.uuid, account.account_id).toArray().length)) deny("minecraft_link_required");
      this.interaction(actor); this.limit(`management-prepare:${actor.id}`, 20);
      const token = randomToken(), expiresAt = Date.now() + 120_000;
      this.ctx.storage.sql.exec("DELETE FROM account_management_confirmations WHERE expires_at<=?", Date.now());
      this.ctx.storage.sql.exec(`INSERT INTO account_management_confirmations(token,actor_id,guild_id,kind,action,target,options,generation,expires_at,used_at,related_state)
        VALUES(?,?,?,?,?,?,?,?,?,NULL,?)`, token, actor.id, actor.guildId, kind, action, target,
        JSON.stringify(options), this.managementGeneration(action, target, options), expiresAt, this.managementRelatedState(action, target, options));
      const affected = kind === "backend" && account ? [account] : [];
      if (action === "hwid_bind") {
        const device = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM account_devices WHERE device_hash=?", options.deviceHash as string).toArray()[0];
        const owner = device && this.byId(device.account_id);
        if (owner && owner.account_id !== account!.account_id) affected.push(owner);
      }
      return { ok: true, token, expiresAt, action, target, options: options as ManagementOptions, ...(account ? { account: this.summary(account), details: this.details(account) } : {}),
        ...(kind === "backend" ? { affectedAccounts: affected.map(owner => this.details(owner)) } : {}) };
    } catch (error) { return failure(error); }
  }
  private consumeManagement(actor: DeveloperActor, kind: ManagementKind, action: string, target: string, options: Record<string, unknown>): void {
    if (options.managementToken === undefined) return;
    const row = this.ctx.storage.sql.exec<ManagementConfirmation>("SELECT * FROM account_management_confirmations WHERE token=?", String(options.managementToken)).toArray()[0];
    if (!row || row.used_at !== null || row.expires_at <= Date.now() || row.actor_id !== actor.id || row.guild_id !== actor.guildId || row.kind !== kind || row.action !== action || row.target !== target) deny("confirmation_expired");
    const actual = Object.entries(options).filter(([key]) => key !== "managementToken").sort(([a], [b]) => a.localeCompare(b));
    if (JSON.stringify(actual) !== JSON.stringify(Object.entries(JSON.parse(row.options)).sort(([a], [b]) => a.localeCompare(b))) ||
        row.generation !== this.managementGeneration(action, target, options) || row.related_state !== this.managementRelatedState(action, target, options)) deny("confirmation_stale");
    this.ctx.storage.sql.exec("UPDATE account_management_confirmations SET used_at=? WHERE token=?", Date.now(), row.token);
  }
  async confirmManagement(actor: DeveloperActor, kind: ManagementKind, token: string, confirm: boolean): Promise<ManagementResult> {
    try {
      if (actor.guildId !== this.env.DISCORD_GUILD_ID || !snowflake.test(actor.id) || !snowflake.test(actor.interactionId) || typeof token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(token) || typeof confirm !== "boolean") deny("confirmation_expired");
      if (kind === "backend" ? !this.isAdmin(actor) : kind !== "user") deny(kind === "backend" ? "admin_required" : "forbidden");
      const row = this.ctx.storage.sql.exec<ManagementConfirmation>("SELECT * FROM account_management_confirmations WHERE token=?", token).toArray()[0];
      if (!row || row.used_at !== null || row.expires_at <= Date.now() || row.actor_id !== actor.id || row.guild_id !== actor.guildId || row.kind !== kind || kind === "user" && row.target !== actor.id) deny("confirmation_expired");
      if (!confirm) { this.interaction(actor); this.ctx.storage.sql.exec("UPDATE account_management_confirmations SET used_at=? WHERE token=?", Date.now(), token); return { ok: true, action: "cancelled" }; }
      const options = { ...JSON.parse(row.options), managementToken: token };
      const result = kind === "backend" ? await this.developerCommand(actor, row.action, row.target, options) : await this.userCommand(actor, row.action, options);
      return { ...result, action: row.action, target: row.target, options: JSON.parse(row.options) };
    } catch (error) { return failure(error); }
  }
  buildAllowed(kind: string, version: string): AccountResult {
    if (!["mod", "loader"].includes(kind) || !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(version)) return { ok: false, error: "invalid_build" };
    return this.ctx.storage.sql.exec("SELECT version FROM account_build_policy WHERE kind=? AND version=? AND revoked_at IS NOT NULL", kind, version).toArray().length ? { ok: false, error: "BUILD_REVOKED" } : { ok: true };
  }
  loaderEligibility(discordId: string): AccountResult {
    if (typeof discordId !== "string" || !snowflake.test(discordId)) return { ok: false, error: "invalid_discord_id" };
    const account = this.byDiscord(discordId), denial = licenseDenial(account);
    if (denial) return { ok: false, error: denial };
    return account!.enrollment_blocked ? { ok: false, error: "device_revoked" } : { ok: true };
  }
  private requireBuilds(input: InstallationInput, bootstrap = false): void {
    // Bootstrap recovery grants loader bytes only; normal authorization independently checks both running builds.
    if (bootstrap) return;
    for (const [kind, version] of [["mod", input.modVersion], ["loader", input.loaderVersion]]) {
      const result = this.buildAllowed(kind, version); if (!result.ok) deny(result.error!);
    }
  }
  private ensureAccount(discordId: string): AccountRow {
    if (!snowflake.test(discordId)) deny("invalid_discord_id");
    let account = this.byDiscord(discordId);
    if (!account) {
      this.ctx.storage.sql.exec("INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen) VALUES(?,?,?,?)", crypto.randomUUID(), discordId, Date.now(), Date.now());
      account = this.byDiscord(discordId)!; this.audit(discordId, account.account_id, "account_created");
    }
    return account;
  }
  private audit(actor: string, accountId: string | null, action: string, metadata: Record<string, unknown> = {}, override = false): void {
    // Callers supply fixed safe fields only; never serialize request bodies, keys, signatures, or credentials.
    const account = accountId ? this.byId(accountId) : undefined;
    const identity = account && this.ctx.storage.sql.exec<{ username: string; display_name: string }>("SELECT username,display_name FROM account_discord_names WHERE discord_id=?", account.discord_id).toArray()[0];
    const minecraftAccounts = account ? this.ctx.storage.sql.exec<{ name: string; uuid: string }>("SELECT name,uuid FROM minecraft_accounts WHERE account_id=? ORDER BY linked_at,uuid LIMIT 6", account.account_id).toArray() : [];
    const runtime = accountId ? this.ctx.storage.sql.exec<RuntimeRow>("SELECT * FROM account_runtime WHERE account_id=? ORDER BY lease_issued_at DESC LIMIT 1", accountId).toArray()[0] : undefined;
    const player = runtime && this.ctx.storage.sql.exec<{ name: string }>("SELECT name FROM minecraft_accounts WHERE account_id=? AND uuid=?", accountId!, runtime.uuid).toArray()[0];
    const context = { ...(account ? { discordId: account.discord_id, discordUsername: identity?.username ?? null,
      discordName: identity?.display_name ?? identity?.username ?? null, deviceReference: account.device_hash, minecraftAccounts } : {}),
      ...(runtime ? { minecraftUuid: runtime.uuid, minecraftName: player?.name ?? null, buildId: runtime.build_id, loaderVersion: runtime.loader_version, sessionId: runtime.session_id } : {}), serverTime: Date.now(), ...metadata };
    const error = String(metadata.error ?? "");
    const severity = action === "signing_configuration_failure" ? "critical" :
      ["activation_replay", "challenge_replay", "hwid_reset", "hwid_revoke", "build_revoke", "traffic_cooldown"].includes(action) || ["invalid_signature", "invalid_lease", "installation_proof_invalid", "challenge_replayed"].includes(error) ? "security" :
        ["device_transferred", "warning_issued"].includes(action) || ["HWID_MISMATCH", "invalid_activation_key", "BUILD_REVOKED"].includes(error) ? "warning" : "information";
    const now = Date.now(), id = this.ctx.storage.sql.exec<{ id: number }>("INSERT INTO account_audit(at,actor,account_id,action,override,metadata,severity) VALUES(?,?,?,?,?,?,?) RETURNING id",
      now, actor, accountId, action, override ? 1 : 0, JSON.stringify(context), severity).one().id;
    if (severity === "information" && !["activation_issued", "minecraft_uuid_added", "license_restore", "admin_added", "license_revoke", "account_suspend"].includes(action)) return;
    const key = `${accountId ?? "system"}:${action}:${error}`;
    this.ctx.storage.sql.exec("DELETE FROM account_alert_dedup WHERE last_at<?", now - ACTIVATION_TTL);
    const previous = this.ctx.storage.sql.exec<{ last_at: number; audit_id: number }>("SELECT last_at,audit_id FROM account_alert_dedup WHERE id=?", key).toArray()[0];
    if (previous && previous.last_at > now - 60_000) {
      this.ctx.storage.sql.exec("UPDATE account_alerts SET repeats=repeats+1 WHERE audit_id=?", previous.audit_id); return;
    }
    // ponytail: bounded best-effort alert queue; the durable audit remains authoritative if delivery capacity is exhausted.
    if (this.ctx.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM account_alerts").one().n + ADMIN_ALERT_IDS.length > 500) return;
    this.ctx.storage.sql.exec("INSERT INTO account_alert_dedup VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET last_at=excluded.last_at,audit_id=excluded.audit_id", key, now, id);
    for (const recipient of ADMIN_ALERT_IDS) this.ctx.storage.sql.exec("INSERT INTO account_alerts(id,audit_id,recipient_id,repeats,next_at) VALUES(?,?,?,0,?)", `${id}:${recipient}`, id, recipient, now);
    this.ctx.waitUntil(Promise.resolve().then(() => this.armAccountAlerts()));
  }
  private async armAccountAlerts(): Promise<void> {
    const due = this.nextAccountAlertAt(); if (due === null) return;
    const alarm = await this.ctx.storage.getAlarm(), next = Math.max(Date.now() + 1000, due);
    if (alarm === null || alarm > next) await this.ctx.storage.setAlarm(next);
  }
  private auditEvent(row: Record<string, SqlStorageValue>): AuditEvent {
    return { id: Number(row.id), at: Number(row.at), actor: String(row.actor), account_id: row.account_id === null ? null : String(row.account_id),
      action: String(row.action), override: Number(row.override), severity: String(row.severity ?? "information"), metadata: JSON.parse(String(row.metadata)) };
  }
  claimAccountAlerts(limit = 5): AccountAlert[] {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10) return [];
    return this.ctx.storage.transactionSync(() => {
      const now = Date.now();
      this.ctx.storage.sql.exec("DELETE FROM account_alerts WHERE audit_id IN (SELECT id FROM account_audit WHERE at<?)", now - 7 * ACTIVATION_TTL);
      const rows = this.ctx.storage.sql.exec<{ id: string; audit_id: number; recipient_id: string; attempts: number; repeats: number }>(
        "SELECT * FROM account_alerts WHERE next_at<=? AND lease_until<=? ORDER BY next_at LIMIT ?", now, now, limit).toArray();
      return rows.flatMap(row => {
        const event = this.ctx.storage.sql.exec("SELECT * FROM account_audit WHERE id=?", row.audit_id).toArray()[0];
        if (!event) { this.ctx.storage.sql.exec("DELETE FROM account_alerts WHERE id=?", row.id); return []; }
        const claimToken = randomToken();
        this.ctx.storage.sql.exec("UPDATE account_alerts SET lease_until=?,claim_token=? WHERE id=?", now + 30_000, claimToken, row.id);
        return [{ id: row.id, auditId: row.audit_id, recipientId: row.recipient_id, claimToken, attempts: row.attempts, repeats: row.repeats, event: this.auditEvent(event) }];
      });
    });
  }
  completeAccountAlert(id: string, claimToken: string, delivered: boolean, retryAfterMs = 0): void {
    const row = this.ctx.storage.sql.exec<{ attempts: number; claim_token: string; audit_id: number; recipient_id: string }>("SELECT attempts,claim_token,audit_id,recipient_id FROM account_alerts WHERE id=?", id).toArray()[0];
    if (!row || row.claim_token !== claimToken) return;
    if (delivered || row.attempts >= 4) {
      this.ctx.storage.sql.exec("DELETE FROM account_alerts WHERE id=?", id);
      if (!delivered) this.audit("system", null, "notification_delivery_failed", { auditId: row.audit_id, recipientId: row.recipient_id,
        attempts: row.attempts + 1, reason: "Administrator DM delivery failed after bounded retries", source: "notification" });
      return;
    }
    const delay = Math.max(Number.isFinite(retryAfterMs) ? Math.min(Math.max(0, retryAfterMs), ACTIVATION_TTL) : 0,
      Math.min(300_000, 1000 * 2 ** Math.min(row.attempts + 1, 9)));
    this.ctx.storage.sql.exec("UPDATE account_alerts SET attempts=attempts+1,next_at=?,lease_until=0,claim_token=NULL WHERE id=?", Date.now() + delay, id);
    this.ctx.waitUntil(this.armAccountAlerts());
  }
  nextAccountAlertAt(): number | null {
    return this.ctx.storage.sql.exec<{ due: number | null }>("SELECT MIN(MAX(next_at,lease_until)) AS due FROM account_alerts").one().due;
  }
  private limit(id: string, maximum: number, interval = 60000): void {
    const now = Date.now();
    this.ctx.storage.sql.exec("DELETE FROM account_limits WHERE started<?", now - 3600000);
    const row = this.ctx.storage.sql.exec<{ started: number; count: number }>("SELECT * FROM account_limits WHERE id=?", id).toArray()[0];
    const started = row && row.started > now - interval ? row.started : now;
    const count = started === row?.started ? row.count + 1 : 1;
    this.ctx.storage.sql.exec("INSERT INTO account_limits VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET started=excluded.started,count=excluded.count", id, started, count);
    if (count > maximum) deny("rate_limited");
  }
  private interaction(actor: DeveloperActor): void {
    if (!snowflake.test(actor.id) || actor.guildId !== this.env.DISCORD_GUILD_ID || !snowflake.test(actor.interactionId)) deny("discord_authorization_required");
    const now = Date.now(); this.ctx.storage.sql.exec("DELETE FROM account_requests WHERE expires_at<=?", now);
    if (this.ctx.storage.sql.exec("SELECT id FROM account_requests WHERE id=?", `discord:${actor.interactionId}`).toArray().length) deny("interaction_replayed");
    this.ctx.storage.sql.exec("INSERT INTO account_requests VALUES(?,?)", `discord:${actor.interactionId}`, now + ACTIVATION_TTL);
  }
  private invalidate(accountId: string): void {
    this.ctx.storage.sql.exec("UPDATE sky_accounts SET generation=generation+1 WHERE account_id=?", accountId);
    this.ctx.storage.sql.exec("UPDATE account_launch_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", Date.now(), accountId);
  }
  private transferAllowed(account: AccountRow): void {
    const denial = licenseDenial(account); if (denial) deny(denial);
    if (account.enrollment_blocked) deny("device_revoked");
    if (account.device_hash !== null && account.hwid_reset_at !== null && Date.now() - account.hwid_reset_at < DEVICE_RESET_COOLDOWN) deny("device_reset_cooldown");
  }
  private reset(account: AccountRow, actor: string, override: boolean, metadata: Record<string, unknown> = {}): void {
    const now = Date.now();
    if (!override) this.transferAllowed(account);
    this.ctx.storage.sql.exec("UPDATE installations SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", now, account.account_id);
    this.ctx.storage.sql.exec("UPDATE account_devices SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", now, account.account_id);
    this.ctx.storage.sql.exec("UPDATE account_launch_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", now, account.account_id);
    this.ctx.storage.sql.exec("UPDATE sky_accounts SET device_hash=NULL,hardware_hash=NULL,hwid_bound_at=NULL,hwid_reset_at=?,generation=generation+1,enrollment_blocked=0 WHERE account_id=?", override ? account.hwid_reset_at : now, account.account_id);
    this.ctx.storage.sql.exec("UPDATE activation_keys SET revoked_at=? WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL", now, account.account_id);
    this.audit(actor, account.account_id, override ? "device_override" : "device_transferred", {
      discordId: account.discord_id, previousHwid: account.device_hash, newHwid: null,
      reason: override ? "administrator_reset" : "hardware_transfer", source: override ? "administrator" : "activation",
      ...(override ? { administratorId: actor } : {}), ...metadata }, override);
  }
  private issueActivation(account: AccountRow, actor: string, activationKey: string, activationHash: string, hardwareTransfer = false) {
    const activationId = crypto.randomUUID(), now = Date.now(), expiresAt = now + ACTIVATION_TTL;
    this.ctx.storage.sql.exec("UPDATE activation_keys SET revoked_at=? WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL", now, account.account_id);
    this.ctx.storage.sql.exec("INSERT INTO activation_keys(key_id,account_id,key_hash,issued_at,expires_at,used_at,used_installation,issued_by,revoked_at,hardware_transfer) VALUES(?,?,?,?,?,NULL,NULL,?,NULL,?)", activationId, account.account_id, activationHash, now, expiresAt, actor, hardwareTransfer ? 1 : 0);
    this.audit(actor, account.account_id, "activation_issued", { activationId, expiresAt });
    return { activationKey, activationId, expiresAt };
  }
  private link(account: AccountRow, id: string, name: string): void {
    const existing = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", id).toArray()[0];
    if (existing && existing.account_id !== account.account_id) deny("minecraft_account_owned");
    this.ctx.storage.sql.exec(`INSERT INTO minecraft_accounts(uuid,account_id,name,linked_at,last_seen) VALUES(?,?,?,?,?)
      ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen`, id, account.account_id, name, Date.now(), Date.now());
    if (!existing) this.audit(account.discord_id, account.account_id, "minecraft_uuid_added", { uuid: id });
  }
  private register(account: AccountRow, input: InstallationInput, fingerprint: string, resetKey: boolean, hardwareTransfer: boolean, profile: { id: string; name: string }): void {
    if (account.enrollment_blocked) deny("device_revoked");
    const changedHardware = account.hardware_hash !== null && input.hardwareHash !== undefined && account.hardware_hash !== input.hardwareHash;
    const transfer = account.device_hash !== null && (account.device_hash !== input.deviceHash || changedHardware);
    if (transfer) {
      if (!hardwareTransfer) deny("HWID_MISMATCH");
      this.reset(account, account.discord_id, false, { newHwid: input.deviceHash, minecraftUuid: profile.id, minecraftName: profile.name,
        buildId: input.modVersion, sessionId: input.launchSessionId ?? null, installationId: input.installationId,
        ...(changedHardware ? { previousHardware: account.hardware_hash, newHardware: input.hardwareHash } : {}) });
      account = this.byId(account.account_id)!;
    }
    const device = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM account_devices WHERE device_hash=?", input.deviceHash).toArray()[0];
    if (device && device.account_id !== account.account_id) deny("device_owned");
    const installation = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=? OR fingerprint=?", input.installationId, fingerprint).toArray()[0];
    if (installation && (installation.installation_id !== input.installationId || installation.account_id !== account.account_id ||
      installation.device_hash !== input.deviceHash || installation.fingerprint !== fingerprint ||
      (installation.revoked_at !== null && !(resetKey && account.device_hash === null)))) deny("installation_revoked");
    const now = Date.now();
    this.ctx.storage.sql.exec(`INSERT INTO account_devices VALUES(?,?,?,NULL) ON CONFLICT(device_hash) DO UPDATE SET revoked_at=NULL`, input.deviceHash, account.account_id, now);
    if (account.device_hash === null) {
      this.ctx.storage.sql.exec("UPDATE sky_accounts SET device_hash=?,hwid_bound_at=?,activated_at=COALESCE(activated_at,?),hwid_reset_at=COALESCE(hwid_reset_at,?),last_seen=? WHERE account_id=?", input.deviceHash, now, now, now, now, account.account_id);
      if (!transfer) this.audit(account.discord_id, account.account_id, "device_registered", { discordId: account.discord_id, previousHwid: null,
        newHwid: input.deviceHash, reason: "device_enrollment", source: "activation", minecraftUuid: profile.id, minecraftName: profile.name,
        buildId: input.modVersion, sessionId: input.launchSessionId ?? null, installationId: input.installationId });
    }
    if (installation?.revoked_at != null) {
      this.ctx.storage.sql.exec("UPDATE installations SET revoked_at=NULL,hardware_hash=?,platform=? WHERE installation_id=?", input.hardwareHash ?? installation.hardware_hash, input.platform ?? installation.platform, input.installationId);
    }
    if (!installation) { this.ctx.storage.sql.exec("INSERT INTO installations(installation_id,account_id,device_hash,public_key,fingerprint,registered_at,revoked_at) VALUES(?,?,?,?,?,?,NULL)", input.installationId, account.account_id,
      input.deviceHash, input.publicKey, fingerprint, now); }
  }
  private verifyHardware(input: InstallationInput, allowLegacyBootstrap = false): void {
    const installation = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", input.installationId).one();
    const account = this.byId(installation.account_id)!;
    if (account.hardware_hash !== null) {
      if (input.hardwareHash === undefined && !allowLegacyBootstrap) deny("hardware_proof_required");
      if (input.hardwareHash !== undefined && account.hardware_hash !== input.hardwareHash) deny("HWID_MISMATCH");
    }
    if (installation.hardware_hash !== null) {
      if (input.hardwareHash === undefined && !allowLegacyBootstrap) deny("hardware_proof_required");
      if (input.hardwareHash !== undefined && installation.hardware_hash !== input.hardwareHash) deny("HWID_MISMATCH");
    } else if (input.hardwareHash !== undefined) {
      this.ctx.storage.sql.exec("UPDATE installations SET hardware_hash=? WHERE installation_id=?", input.hardwareHash, input.installationId);
    }
    if (account.hardware_hash === null && input.hardwareHash !== undefined) {
      this.ctx.storage.sql.exec("UPDATE sky_accounts SET hardware_hash=? WHERE account_id=?", input.hardwareHash, account.account_id);
    }
    if (input.platform !== undefined) {
      if (installation.platform !== null && installation.platform !== input.platform) deny("platform_changed");
      this.ctx.storage.sql.exec("UPDATE installations SET platform=COALESCE(platform,?) WHERE installation_id=?", input.platform, input.installationId);
    }
  }
  /** Binding-only migration: never upgrades license/device state and never steals an existing UUID. */
  importCosmeticLinks(room: string, rows: { discord: string; uuid: string; linked_at?: number }[]): { imported: number; conflicts: number } {
    if (!["friends", "testing"].includes(room) || rows.length > 500) deny("invalid_migration");
    return this.ctx.storage.transactionSync(() => {
      let imported = 0, conflicts = 0;
      for (const row of rows) {
        if (!snowflake.test(row.discord) || !uuid.test(row.uuid)) { conflicts++; continue; }
        if (this.ctx.storage.sql.exec("SELECT uuid FROM account_migrations WHERE room IN (?, '*') AND uuid=?", room, row.uuid).toArray().length) continue;
        const account = this.ensureAccount(row.discord), owner = this.getAccountByUuid(row.uuid);
        if (owner && owner.accountId !== account.account_id) { conflicts++; this.audit("migration", account.account_id, "legacy_link_conflict", { room, uuid: row.uuid }); }
        else { this.link(account, row.uuid, ""); imported++; }
        this.ctx.storage.sql.exec("INSERT INTO account_migrations VALUES(?,?)", room, row.uuid);
      }
      return { imported, conflicts };
    });
  }
  async issueMinecraftLink(id: string, name: string, discordId: string): Promise<{ code: string; expiresAt: number }> {
    if (!uuid.test(id) || !/^[A-Za-z0-9_]{1,16}$/.test(name)) deny("invalid_minecraft_identity");
    if (!snowflake.test(discordId) || this.getAccountByUuid(id)?.discordId !== discordId) deny("minecraft_link_required");
    this.limit(`link:${id}`, 1);
    const code = newLinkCode(), digest = await sha256(normalizedCode(code)!); const expiresAt = Date.now() + 300000;
    this.ctx.storage.transactionSync(() => {
      // Ownership can change while hashing. A stale source cannot mint a code after a verified reconnect.
      if (this.getAccountByUuid(id)?.discordId !== discordId) deny("minecraft_link_required");
      this.ctx.storage.sql.exec("INSERT INTO minecraft_links(hash,uuid,name,expires_at,discord_id) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash,name=excluded.name,expires_at=excluded.expires_at,discord_id=excluded.discord_id", digest, id, name, expiresAt, discordId);
    });
    return { code, expiresAt };
  }
  async consumeMinecraftLink(discordId: string, code: string): Promise<AccountResult> {
    try { this.limit(`link-discord:${discordId}`, 6); const normalized = normalizedCode(code); if (!normalized) deny("invalid_link");
      const digest = await sha256(normalized);
      return this.ctx.storage.transactionSync(() => {
        const row = this.ctx.storage.sql.exec<{ uuid: string; name: string; expires_at: number; discord_id: string | null }>("SELECT * FROM minecraft_links WHERE hash=?", digest).toArray()[0];
        if (!row || row.expires_at <= Date.now() || row.discord_id !== discordId) deny("invalid_link");
        const account = this.ensureAccount(discordId); this.link(account, row.uuid, row.name);
        this.ctx.storage.sql.exec("DELETE FROM minecraft_links WHERE hash=?", digest);
        return { ok: true, account: this.summary(account) };
      });
    } catch (error) { return failure(error); }
  }
  unlinkMinecraftUuid(discordId: string, id: string): AccountResult {
    try { return this.ctx.storage.transactionSync(() => {
      if (!uuid.test(id)) deny("invalid_uuid");
      const account = this.byDiscord(discordId); if (!account) deny("account_required");
      const owner = this.getAccountByUuid(id); if (owner?.accountId !== account.account_id) deny("minecraft_link_required");
      const name = owner.uuids.find(player => player.uuid === id)!.name;
      this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations VALUES('*',?)", id);
      this.sharing.detachUuid(id, account.account_id);
      this.ctx.storage.sql.exec("DELETE FROM minecraft_accounts WHERE uuid=?", id);
      this.invalidate(account.account_id); this.audit(discordId, account.account_id, "minecraft_uuid_removed", { uuid: id, minecraftUuid: id, minecraftName: name });
      return { ok: true, invalidated: account.account_id, account: this.summary(this.byId(account.account_id)!) };
    }); } catch (error) { return failure(error); }
  }
  async userCommand(actor: DeveloperActor, action: string, options: Record<string, unknown> = {}): Promise<AccountResult> {
    try {
      this.interaction(actor); this.limit(`user:${actor.id}`, 12);
      if (action === "link") return this.consumeMinecraftLink(actor.id, String(options.code ?? ""));
      if (action === "unlink") return this.ctx.storage.transactionSync(() => {
        this.consumeManagement(actor, "user", action, actor.id, options);
        return this.unlinkMinecraftUuid(actor.id, String(options.uuid ?? ""));
      });
      if (action === "approve") {
        const code = normalizedCode(options.code); if (!code) deny("invalid_approval"); const digest = await sha256(code);
        return this.ctx.storage.transactionSync(() => {
          const challenge = this.ctx.storage.sql.exec<ChallengeRow>("SELECT * FROM account_challenges WHERE approval_hash=?", digest).toArray()[0];
          if (!challenge || challenge.expires_at <= Date.now() || challenge.used_at !== null || challenge.discord_id !== null ||
            JSON.parse(challenge.input).purpose !== "activate") deny("invalid_approval");
          const account = this.byDiscord(actor.id); const denial = licenseDenial(account); if (denial) deny(denial);
          this.ctx.storage.sql.exec("UPDATE account_challenges SET discord_id=? WHERE id=?", actor.id, challenge.id);
          this.audit(actor.id, account!.account_id, "installation_approved"); return { ok: true };
        });
      }
      const activationKey = action === "reset" || action === "key" ? randomToken() : undefined;
      const activationHash = activationKey ? await sha256(activationKey) : undefined;
      return this.ctx.storage.transactionSync(() => {
        const account = this.byDiscord(actor.id); if (!account) deny("account_required");
        this.consumeManagement(actor, "user", action, actor.id, options);
        if (action === "key") {
          const denial = licenseDenial(account); if (denial) deny(denial); if (account.enrollment_blocked) deny("device_revoked");
          const previous = this.ctx.storage.sql.exec<{ last_issued_at: number }>("SELECT last_issued_at FROM account_key_recovery WHERE discord_id=?", actor.id).toArray()[0];
          if (previous && Date.now() - previous.last_issued_at < 60_000) deny("key_reissue_cooldown");
          this.ctx.storage.sql.exec("INSERT INTO account_key_recovery VALUES(?,?) ON CONFLICT(discord_id) DO UPDATE SET last_issued_at=excluded.last_issued_at", actor.id, Date.now());
          return { ok: true, account: this.summary(account), ...this.issueActivation(account, actor.id, activationKey!, activationHash!) };
        }
        if (action === "reset") this.transferAllowed(account);
        else if (action === "loader") {
          const denial = licenseDenial(account); if (denial) deny(denial); if (account.enrollment_blocked) deny("device_revoked");
          const previous = this.ctx.storage.sql.exec<{ last_requested_at: number }>("SELECT last_requested_at FROM account_loader_delivery WHERE discord_id=?", actor.id).toArray()[0];
          if (previous && Date.now() - previous.last_requested_at < 60_000) deny("loader_delivery_cooldown");
          this.ctx.storage.sql.exec("INSERT INTO account_loader_delivery VALUES(?,?) ON CONFLICT(discord_id) DO UPDATE SET last_requested_at=excluded.last_requested_at", actor.id, Date.now());
        }
        else if (action !== "status") deny("invalid_action");
        return { ok: true, account: this.summary(this.byId(account.account_id)!), ...(action === "status" ? { details: this.details(account) } : {}), ...(action === "reset" ? {
          ...this.issueActivation(account, actor.id, activationKey!, activationHash!, account.device_hash !== null) } : {}) };
      });
    } catch (error) { return failure(error); }
  }
  async developerCommand(actor: DeveloperActor, action: string, target: string, options: Record<string, unknown> = {}): Promise<AccountResult> {
    try {
      if (!this.isAdmin(actor)) { this.audit(actor.id, null, "admin_denied"); deny("admin_required"); }
      if (action === "build_revoke" || action === "build_restore") {
        if (!/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(target) || !["mod", "loader"].includes(String(options.kind ?? "mod"))) deny("invalid_build");
      } else if (!snowflake.test(target)) deny("invalid_discord_id");
      this.interaction(actor); this.limit(`developer:${actor.id}`, 30);
      const activationKey = action === "authorise" || action === "hwid_reset" ? randomToken() : undefined;
      const activationHash = activationKey ? await sha256(activationKey) : undefined;
      return this.ctx.storage.transactionSync(() => {
        if (!this.isAdmin(actor)) deny("admin_required"); // Recheck durable authority after any cryptographic await.
        this.consumeManagement(actor, "backend", action, target, options);
        if (action === "build_revoke" || action === "build_restore") {
          const kind = String(options.kind ?? "mod"), now = Date.now(), reason = typeof options.reason === "string" ? options.reason.slice(0, 500) : action;
          this.ctx.storage.sql.exec(`INSERT INTO account_build_policy VALUES(?,?,?,?,?,1) ON CONFLICT(kind,version) DO UPDATE SET
            revoked_at=excluded.revoked_at,actor=excluded.actor,reason=excluded.reason,revision=account_build_policy.revision+1`, kind, target, action === "build_revoke" ? now : null, actor.id, reason);
          this.audit(actor.id, null, action, { buildId: target, buildKind: kind, reason, source: "administrator", administratorId: actor.id });
          return { ok: true, invalidatedAccounts: this.ctx.storage.sql.exec<{ account_id: string }>(`SELECT DISTINCT account_id FROM account_runtime WHERE ${kind === "mod" ? "build_id" : "loader_version"}=?`, target).toArray().map(row => row.account_id) };
        }
        let account = this.byDiscord(target);
        if (action === "admin_add") {
          account = account ?? this.ensureAccount(target);
          if (account.status !== "active") deny("account_ineligible");
          const adminAdded = account.is_admin !== 1;
          if (adminAdded) this.ctx.storage.sql.exec("UPDATE sky_accounts SET is_admin=1 WHERE account_id=?", account.account_id);
          this.audit(actor.id, account.account_id, adminAdded ? "admin_added" : "admin_add_unchanged", { target, requestId: actor.interactionId });
          return { ok: true, adminAdded, account: this.summary(this.byId(account.account_id)!) };
        }
        if (action === "authorise") {
          account = account ?? this.ensureAccount(target);
          if (account.status !== "active" || account.license_status === "revoked") deny("account_ineligible");
          if (account.license_status === "active" && options.reissue !== true) deny("already_authorized");
          this.ctx.storage.sql.exec("UPDATE sky_accounts SET license_status='active' WHERE account_id=?", account.account_id);
          return { ok: true, ...this.issueActivation(account, actor.id, activationKey!, activationHash!), account: this.summary(this.byId(account.account_id)!) };
        }
        if (!account) deny("account_required");
        let invalidated: string | undefined; const invalidatedAccounts: string[] = [];
        let auditMetadata: Record<string, unknown> = { source: "administrator", administratorId: actor.id, requestId: actor.interactionId };
        if (action === "audit") return { ok: true, audit: this.ctx.storage.sql.exec("SELECT * FROM account_audit WHERE account_id=? ORDER BY id DESC LIMIT 100", account.account_id).toArray() };
        if (action === "license_status" || action === "account_lookup") return { ok: true, account: this.summary(account) };
        if (action === "license_revoke" || action === "license_restore") {
          this.ctx.storage.sql.exec("UPDATE sky_accounts SET license_status=? WHERE account_id=?", action === "license_revoke" ? "revoked" : "active", account.account_id);
          if (action === "license_revoke") this.ctx.storage.sql.exec("UPDATE activation_keys SET revoked_at=? WHERE account_id=? AND used_at IS NULL", Date.now(), account.account_id);
        } else if (action === "license_extend") {
          const days = options.days; if (typeof days !== "number" || !Number.isSafeInteger(days) || days < 1 || days > 3650) deny("invalid_duration");
          this.ctx.storage.sql.exec("UPDATE sky_accounts SET license_expires_at=? WHERE account_id=?", Math.max(Date.now(), account.license_expires_at ?? Date.now()) + days * ACTIVATION_TTL, account.account_id);
        } else if (action === "account_suspend" || action === "account_unsuspend") {
          this.ctx.storage.sql.exec("UPDATE sky_accounts SET status=? WHERE account_id=?", action === "account_suspend" ? "suspended" : "active", account.account_id);
        } else if (action === "account_unlink") {
          if (options.uuid !== undefined && (typeof options.uuid !== "string" || !uuid.test(options.uuid))) deny("invalid_uuid");
          if (options.uuid) {
            const player = this.ctx.storage.sql.exec<{ name: string }>("SELECT name FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, options.uuid as string).toArray()[0];
            if (!player) deny("minecraft_link_required");
            auditMetadata = { ...auditMetadata, uuid: options.uuid, minecraftUuid: options.uuid, minecraftName: player.name };
            this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations SELECT '*',uuid FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, options.uuid as string);
            this.sharing.detachUuid(options.uuid as string, account.account_id);
            this.ctx.storage.sql.exec("DELETE FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, options.uuid as string);
          }
          else {
            auditMetadata = { ...auditMetadata, minecraftAccounts: this.summary(account).uuids };
            this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations SELECT '*',uuid FROM minecraft_accounts WHERE account_id=?", account.account_id);
            for (const player of this.summary(account).uuids) this.sharing.detachUuid(player.uuid, account.account_id);
            this.ctx.storage.sql.exec("DELETE FROM minecraft_accounts WHERE account_id=?", account.account_id);
            this.reset(account, actor.id, true);
            this.ctx.storage.sql.exec("UPDATE sky_accounts SET license_status='inactive',enrollment_blocked=1 WHERE account_id=?", account.account_id);
            this.ctx.storage.sql.exec("UPDATE activation_keys SET revoked_at=? WHERE account_id=? AND used_at IS NULL", Date.now(), account.account_id);
          }
        } else if (["hwid_reset", "hwid_revoke", "hwid_bind"].includes(action)) {
          this.reset(account, actor.id, true);
          if (action === "hwid_revoke") this.ctx.storage.sql.exec("UPDATE sky_accounts SET enrollment_blocked=1 WHERE account_id=?", account.account_id);
          if (action === "hwid_bind") {
            if (typeof options.deviceHash !== "string" || !hash.test(options.deviceHash)) deny("invalid_device");
            const previous = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM account_devices WHERE device_hash=?", options.deviceHash).toArray()[0];
            if (previous && previous.account_id !== account.account_id) {
              this.reset(this.byId(previous.account_id)!, actor.id, true);
              invalidatedAccounts.push(previous.account_id);
              this.audit(actor.id, previous.account_id, "device_transferred", { target: account.account_id }, true);
            }
            this.ctx.storage.sql.exec("INSERT INTO account_devices VALUES(?,?,?,NULL) ON CONFLICT(device_hash) DO UPDATE SET account_id=excluded.account_id,revoked_at=NULL", options.deviceHash, account.account_id, Date.now());
            this.ctx.storage.sql.exec("UPDATE sky_accounts SET device_hash=?,hwid_bound_at=? WHERE account_id=?", options.deviceHash, Date.now(), account.account_id);
          }
        } else if (action === "warning_add") {
          if (typeof options.reason !== "string" || !options.reason.trim() || options.reason.length > 500) deny("invalid_warning");
          const warningId = crypto.randomUUID();
          this.ctx.storage.sql.exec("INSERT INTO account_warnings VALUES(?,?,?,?,?,NULL)", warningId, account.account_id, actor.id, options.reason, Date.now());
          this.audit(actor.id, account.account_id, "warning_issued", { warningId, reason: options.reason, ...auditMetadata }); return { ok: true, warningId };
        } else if (action === "warning_remove") {
          if (typeof options.warningId !== "string") deny("invalid_warning");
          this.ctx.storage.sql.exec("UPDATE account_warnings SET removed_at=? WHERE id=? AND account_id=?", Date.now(), options.warningId, account.account_id);
        } else if (action !== "session_kick") deny("invalid_action");
        if (action !== "warning_remove") { this.invalidate(account.account_id); invalidated = account.account_id; }
        this.audit(actor.id, account.account_id, action, action.startsWith("hwid_") ? { discordId: account.discord_id,
          previousHwid: account.device_hash, newHwid: action === "hwid_bind" ? options.deviceHash : null,
          reason: typeof options.reason === "string" ? options.reason.slice(0, 500) : action === "hwid_reset" ? "administrator_reset" : action,
          source: "administrator", administratorId: actor.id, requestId: actor.interactionId } : auditMetadata, action.startsWith("hwid_"));
        return { ok: true, account: this.summary(this.byId(account.account_id)!), invalidated, invalidatedAccounts: [...invalidatedAccounts, ...invalidated ? [invalidated] : []],
          ...(action === "hwid_reset" ? this.issueActivation(account, actor.id, activationKey!, activationHash!) : {}) };
      });
    } catch (error) { return failure(error); }
  }
  async challenge(raw: Record<string, unknown>, ip: string): Promise<{ challengeId: string; nonce: string; clientNonce: string; approvalCode: string; expiresAt: number } | { ok: false; error: string }> {
    try {
    this.limit(`ip:${await sha256(ip)}`, 20);
    if (!["activate", "renew", "manifest", "artifact", "bootstrap-activate", "bootstrap-renew"].includes(String(raw.purpose)) || typeof raw.installationId !== "string" ||
      !/^[a-f0-9-]{36}$/.test(raw.installationId) || typeof raw.deviceHash !== "string" || !hash.test(raw.deviceHash) ||
      typeof raw.publicKey !== "string" || typeof raw.minecraftVersion !== "string" || typeof raw.loaderVersion !== "string" || typeof raw.modVersion !== "string" ||
      typeof raw.clientNonce !== "string" || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(raw.clientNonce)) deny("invalid_request");
    const input = raw as InstallationInput; validateLaunchInput(input);
    const invalidVersion = installationVersionDenial(input, this.env); if (invalidVersion) deny(invalidVersion);
    this.requireBuilds(input, bootstrapPurpose(input.purpose));
    await installationKey(input.publicKey); const approvalCode = newLinkCode(), approvalHash = await sha256(normalizedCode(approvalCode)!);
    const challengeId = crypto.randomUUID(), nonce = randomToken(), now = Date.now(), expiresAt = now + 300000;
    this.ctx.storage.sql.exec("DELETE FROM account_challenges WHERE expires_at<=?", now);
    this.ctx.storage.sql.exec("INSERT INTO account_challenges VALUES(?,?,?,?,NULL,?,NULL)", challengeId, nonce,
      JSON.stringify({ purpose: input.purpose, installationId: input.installationId, publicKey: input.publicKey, deviceHash: input.deviceHash,
        minecraftVersion: input.minecraftVersion, loaderVersion: input.loaderVersion, modVersion: input.modVersion, clientNonce: input.clientNonce,
        ...(input.hardwareHash !== undefined ? { hardwareHash: input.hardwareHash } : {}),
        ...(input.platform !== undefined ? { platform: input.platform, launchSessionId: input.launchSessionId, startSession: input.startSession } : {}) }), approvalHash, expiresAt);
    return { challengeId, nonce, clientNonce: input.clientNonce, approvalCode, expiresAt };
    } catch (error) { return failure(error); }
  }
  private pending(id: unknown, purpose: string): { row: ChallengeRow; input: InstallationInput } {
    if (typeof id !== "string" || id.length > 64) deny("invalid_challenge");
    const row = this.ctx.storage.sql.exec<ChallengeRow>("SELECT * FROM account_challenges WHERE id=?", id).toArray()[0];
    if (!row || row.used_at !== null || row.expires_at <= Date.now()) deny("invalid_challenge");
    const input: InstallationInput = JSON.parse(row.input); if (input.purpose !== purpose) deny("invalid_challenge");
    return { row, input };
  }
  private replayedChallenge(id: unknown): { accountId: string | null; metadata: Record<string, unknown> } | null {
    if (typeof id !== "string" || id.length > 64) return null;
    const row = this.ctx.storage.sql.exec<ChallengeRow>("SELECT * FROM account_challenges WHERE id=? AND used_at IS NOT NULL", id).toArray()[0];
    if (!row) return null; // Missing or expired anonymous requests do not create administrator notifications.
    const input: InstallationInput = JSON.parse(row.input);
    const accountId = row.discord_id ? this.byDiscord(row.discord_id)?.account_id :
      this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM installations WHERE installation_id=?", input.installationId).toArray()[0]?.account_id;
    return { accountId: accountId ?? null, metadata: { buildId: input.modVersion, loaderVersion: input.loaderVersion,
      sessionId: input.launchSessionId ?? null, installationId: input.installationId, reason: "A consumed authorization challenge was presented again", source: "authentication" } };
  }
  async authenticateInstallation(body: Record<string, unknown>, requestedPurpose: AuthenticationPurpose): Promise<{ ok: boolean; error?: string; lease?: string; expiresAt?: number; serverTime?: number }> {
    const bootstrap = bootstrapPurpose(requestedPurpose), purpose = requestedPurpose.endsWith("activate") ? "activate" : "renew";
    let auditAccount: string | null = null;
    let auditContext: Record<string, unknown> = {};
    let auditAction = purpose === "activate" ? "activation_denied" : "lease_denied";
    try {
      try { await ensureSigningConfigured(this.env); }
      catch (error) { this.audit("system", null, "signing_configuration_failure", { reason: "Release signing configuration could not be verified", source: "backend" }); throw error; }
      const { row, input } = this.pending(body.challengeId, requestedPurpose);
      auditContext = { buildId: input.modVersion, loaderVersion: input.loaderVersion, sessionId: input.launchSessionId ?? null,
        installationId: input.installationId, attemptedDeviceReference: input.deviceHash };
      auditAccount = row.discord_id ? this.byDiscord(row.discord_id)?.account_id ?? null :
        this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM installations WHERE installation_id=?", input.installationId).toArray()[0]?.account_id ?? null;
      this.limit(`proof:${row.id}`, 6);
      if (!await verifyInstallation(input.publicKey, body.signature, installationMessage(input, row.nonce))) deny("installation_proof_invalid");
      if (!body.minecraftProof || typeof body.minecraftProof !== "object" || Array.isArray(body.minecraftProof)) deny("minecraft_proof_required");
      const profile = await verifyAccount(body.minecraftProof as Record<string, unknown>, row.nonce);
      if ("error" in profile) deny(profile.retryable ? "minecraft_verification_unavailable" : "minecraft_proof_invalid");
      auditContext = { ...auditContext, minecraftUuid: profile.id, minecraftName: profile.name };
      const activationHash = typeof body.activationKey === "string" && /^[A-Za-z0-9_-]{43}$/.test(body.activationKey) ? await sha256(body.activationKey) : null;
      if (purpose === "activate" && body.activationKey !== undefined && !activationHash) deny("invalid_activation_key");
      const fingerprint = await sha256(decode64(input.publicKey, 256));
      const claims = this.ctx.storage.transactionSync((): LeaseClaims => {
        const current = this.pending(row.id, requestedPurpose); // Recheck after cryptographic awaits, including concurrent consumption.
        const invalidVersion = installationVersionDenial(current.input, this.env); if (invalidVersion) deny(invalidVersion);
        this.requireBuilds(current.input, bootstrap);
        let account: AccountRow;
        if (purpose === "activate") {
          const key = activationHash && this.ctx.storage.sql.exec<{ key_id: string; account_id: string; expires_at: number; used_at: number | null; revoked_at: number | null; hardware_transfer: number }>("SELECT * FROM activation_keys WHERE key_hash=?", activationHash).toArray()[0];
          if (current.row.discord_id === null && !activationHash) deny("approval_required");
          // A DM key selects its account only after both proofs; an existing approval cannot be redirected.
          const selected = current.row.discord_id !== null ? this.byDiscord(current.row.discord_id) : key ? this.byId(key.account_id) : undefined;
          if (!selected) deny(current.row.discord_id !== null ? "account_required" : "invalid_activation_key");
          account = selected; auditAccount = account.account_id;
          if (activationHash || account.activated_at === null) {
            if (!key || key.account_id !== account.account_id || key.used_at !== null || key.revoked_at !== null || key.expires_at <= Date.now()) {
              if (key && key.account_id === account.account_id) auditAction = key.used_at !== null ? "activation_replay" : key.expires_at <= Date.now() ? "activation_expired" : "activation_denied";
              deny("invalid_activation_key");
            }
          }
          const denial = licenseDenial(account); if (denial) deny(denial);
          if (key) {
            this.ctx.storage.sql.exec("UPDATE activation_keys SET used_at=?,used_installation=? WHERE key_id=?", Date.now(), input.installationId, key.key_id);
            this.audit(account.discord_id, account.account_id, "activation_consumed", { activationId: key.key_id });
          }
          this.ctx.storage.sql.exec("UPDATE account_challenges SET discord_id=? WHERE id=?", account.discord_id, row.id);
          this.register(account, input, fingerprint, !!key, !!key && key.hardware_transfer === 1, profile); this.link(account, profile.id, profile.name); account = this.byId(account.account_id)!;
        } else {
          const install = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", input.installationId).toArray()[0];
          if (!install || install.fingerprint !== fingerprint) deny("installation_required");
          account = this.byId(install.account_id)!;
          const denial = licenseDenial(account); if (denial) deny(denial);
          if (account.device_hash !== null && (install.device_hash !== input.deviceHash || account.device_hash !== input.deviceHash)) deny("HWID_MISMATCH");
          // A reset needs a fresh DM key, including when the user keeps the same installation.
          // Use the existing loader prompt response; this never grants a lease on renewal.
          if (install.revoked_at !== null && account.device_hash === null && !account.enrollment_blocked) deny("installation_required");
          const status = this.sessionState({ accountId: account.account_id, uuid: profile.id, installationId: input.installationId,
            generation: account.generation, buildId: input.modVersion, loaderVersion: input.loaderVersion }, !bootstrap);
          if (!status.ok) deny(status.error!); this.link(account, profile.id, profile.name);
        }
        this.verifyHardware(input, bootstrap);
        this.ctx.storage.sql.exec("UPDATE account_challenges SET used_at=? WHERE id=?", Date.now(), row.id);
        this.ctx.storage.sql.exec("UPDATE sky_accounts SET last_seen=? WHERE account_id=?", Date.now(), account.account_id);
        const now = Date.now(), exp = Math.min(now + seconds(this.env.LEASE_TTL_SECONDS, 300, 30, 900), account.license_expires_at ?? Infinity);
        const offlineUntil = Math.min(exp + seconds(this.env.OFFLINE_GRACE_SECONDS, 900, 0, 3600), account.license_expires_at ?? Infinity);
        this.audit(account.discord_id, account.account_id, bootstrap ? "loader_update_authorized" : "lease_issued", { uuid: profile.id, installationId: input.installationId, expiresAt: exp, hardwareState: "HWID_MATCH" });
        return { product: "sky-myce", protocol: 3, accountId: account.account_id, discordId: account.discord_id, uuid: profile.id,
          installationId: input.installationId, deviceHash: input.deviceHash, keyFingerprint: fingerprint, version: input.modVersion, loaderVersion: input.loaderVersion,
          minecraft: input.minecraftVersion, iat: now, exp, offlineUntil, generation: account.generation, jti: crypto.randomUUID(),
          challengeId: row.id, clientNonce: input.clientNonce, features: ["relay", "irc", "cosmetics"],
          ...(input.hardwareHash !== undefined ? { hardwareHash: input.hardwareHash } : {}),
          ...(input.platform !== undefined ? { platform: input.platform, launchSessionId: input.launchSessionId } : {}) };
      });
      if (bootstrap) return { ok: true }; // No lease, protected artifact or feature authorization for outdated clients.
      const lease = await createLease(this.env, claims);
      const current = this.currentLease(claims);
      if (!current.ok) deny(current.error!); // Revocation while signing cannot issue a usable new lease.
      const previousRuntime = this.ctx.storage.sql.exec<RuntimeRow>("SELECT * FROM account_runtime WHERE installation_id=? AND uuid=?", claims.installationId, claims.uuid).toArray()[0];
      const sessionId = claims.launchSessionId ?? (previousRuntime?.generation === claims.generation ? previousRuntime.session_id : claims.jti);
      this.ctx.storage.sql.exec(`INSERT INTO account_runtime(installation_id,uuid,account_id,session_id,build_id,loader_version,generation,lease_issued_at,lease_expires_at,connected_at,last_heartbeat,connection_id)
        VALUES(?,?,?,?,?,?,?,?,?,NULL,NULL,NULL) ON CONFLICT(installation_id,uuid) DO UPDATE SET
        account_id=excluded.account_id,session_id=excluded.session_id,build_id=excluded.build_id,loader_version=excluded.loader_version,generation=excluded.generation,
        lease_issued_at=excluded.lease_issued_at,lease_expires_at=excluded.lease_expires_at,
        connected_at=CASE WHEN account_runtime.session_id=excluded.session_id AND account_runtime.generation=excluded.generation THEN account_runtime.connected_at ELSE NULL END,
        last_heartbeat=CASE WHEN account_runtime.session_id=excluded.session_id AND account_runtime.generation=excluded.generation THEN account_runtime.last_heartbeat ELSE NULL END,
        connection_id=CASE WHEN account_runtime.session_id=excluded.session_id AND account_runtime.generation=excluded.generation THEN account_runtime.connection_id ELSE NULL END`,
        claims.installationId, claims.uuid, claims.accountId, sessionId, claims.version,
        input.loaderVersion, claims.generation, claims.iat, claims.exp);
      return { ok: true, lease, expiresAt: claims.exp, serverTime: claims.iat };
    } catch (error) {
      const result = failure(error);
      const replay = result.error === "invalid_challenge" ? this.replayedChallenge(body.challengeId) : null;
      if (result.error !== "approval_required") this.audit("installation", replay?.accountId ?? auditAccount, replay ? "challenge_replay" : auditAction, { ...auditContext, ...replay?.metadata, error: result.error,
        ...(result.error === "HWID_MISMATCH" ? { reason: "Second-device authorization attempt denied" } : {}) });
      return result;
    }
  }
  checkSession(input: SessionCheck): SessionResult { return this.sessionState(input); }
  private sessionState(input: SessionCheck, checkBuilds = true): SessionResult {
    const account = this.byId(input.accountId), denial = licenseDenial(account); if (denial) return { ok: false, error: denial };
    if (account!.generation !== input.generation) return { ok: false, error: "authorization_revoked" };
    const install = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", input.installationId).toArray()[0];
    if (!install || install.account_id !== account!.account_id || install.revoked_at !== null || install.device_hash !== account!.device_hash) return { ok: false, error: "device_revoked" };
    const linked = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", input.uuid).toArray()[0];
    if (!linked || linked.account_id !== account!.account_id) return { ok: false, error: "minecraft_link_required" };
    const runtime = this.ctx.storage.sql.exec<RuntimeRow>("SELECT * FROM account_runtime WHERE installation_id=? AND uuid=?", input.installationId, input.uuid).toArray()[0];
    const buildId = input.buildId ?? runtime?.build_id, loaderVersion = input.loaderVersion ?? runtime?.loader_version;
    if (checkBuilds) for (const [kind, version] of [["mod", buildId], ["loader", loaderVersion]]) {
      if (version) { const allowed = this.buildAllowed(kind!, version); if (!allowed.ok) return { ok: false, error: allowed.error }; }
    }
    if (runtime?.connected_at !== null && runtime?.connected_at !== undefined && runtime.generation === input.generation &&
        runtime.connection_id !== null && input.connectionId === runtime.connection_id) {
      const heartbeatAt = input.heartbeat ? Date.now() : input.heartbeatAt;
      if (heartbeatAt !== undefined && Number.isSafeInteger(heartbeatAt) && heartbeatAt <= Date.now() && heartbeatAt >= runtime.connected_at && heartbeatAt > (runtime.last_heartbeat ?? 0)) {
        this.ctx.storage.sql.exec("UPDATE account_runtime SET last_heartbeat=MAX(COALESCE(last_heartbeat,0),?) WHERE installation_id=? AND uuid=?", heartbeatAt, input.installationId, input.uuid);
      }
    }
    return { ok: true, accountId: account!.account_id, discordId: account!.discord_id, installationId: install.installation_id,
      deviceId: install.fingerprint, generation: account!.generation, uuid: input.uuid, ...(buildId ? { buildId } : {}), ...(loaderVersion ? { loaderVersion } : {}) };
  }
  private currentLease(claims: LeaseClaims): SessionResult {
    if (claims.loaderVersion === undefined) return { ok: false, error: "authorization_renewal_required" };
    const status = this.checkSession({ accountId: claims.accountId, uuid: claims.uuid, installationId: claims.installationId, generation: claims.generation, buildId: claims.version, loaderVersion: claims.loaderVersion });
    if (!status.ok) return status;
    const installation = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", claims.installationId).one();
    if (installation.fingerprint !== claims.keyFingerprint || installation.device_hash !== claims.deviceHash || status.discordId !== claims.discordId ||
      claims.version !== (this.env.SKY_MYCE_VERSION ?? "2.4.2") || claims.minecraft !== (this.env.MINECRAFT_VERSION ?? "26.1.2")) return { ok: false, error: "invalid_lease" };
    if (claims.platform !== undefined && installation.platform !== claims.platform) return { ok: false, error: "invalid_lease" };
    const hardwareHash = this.byId(claims.accountId)!.hardware_hash;
    if (hardwareHash !== null && hardwareHash !== claims.hardwareHash) return { ok: false, error: "invalid_lease" };
    if (installation.hardware_hash !== null && installation.hardware_hash !== claims.hardwareHash) return { ok: false, error: "invalid_lease" };
    return { ...status, expiresAt: claims.exp };
  }
  markSessionOffline(accountId: string, playerUuid: string, installationId: string, generation: number, connectionId: string): void {
    this.ctx.storage.sql.exec("UPDATE account_runtime SET connected_at=NULL,last_heartbeat=NULL WHERE account_id=? AND uuid=? AND installation_id=? AND generation=? AND connection_id=?",
      accountId, playerUuid, installationId, generation, connectionId);
  }
  async authorizeSession(input: { uuid: string; lease: string; challenge: string; signature: string }): Promise<SessionResult> {
    let auditAccount: string | null = null;
    try {
      const claims = await validateLease(this.env, input.lease);
      auditAccount = claims.accountId;
      if (claims.uuid !== input.uuid || !/^[a-f0-9]{32}$/.test(input.challenge)) deny("invalid_lease");
      const installation = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", claims.installationId).toArray()[0];
      if (!installation || !await verifyInstallation(installation.public_key, input.signature, `Sky-Myce relay v3\n${input.challenge}\n${input.lease}`)) deny("installation_proof_invalid");
      return this.ctx.storage.transactionSync(() => {
        const status = this.currentLease(claims); if (!status.ok) deny(status.error!);
        this.ctx.storage.sql.exec("DELETE FROM account_requests WHERE expires_at<=?", Date.now());
        const nonce = `relay:${input.challenge}`;
        if (this.ctx.storage.sql.exec("SELECT id FROM account_requests WHERE id=?", nonce).toArray().length) deny("challenge_replayed");
        this.ctx.storage.sql.exec("INSERT INTO account_requests VALUES(?,?)", nonce, Date.now() + 300000);
        this.ctx.storage.sql.exec(`INSERT INTO account_runtime(installation_id,uuid,account_id,session_id,build_id,loader_version,generation,lease_issued_at,lease_expires_at,connected_at,last_heartbeat,connection_id)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(installation_id,uuid) DO UPDATE SET
          connected_at=COALESCE(account_runtime.connected_at,excluded.connected_at),last_heartbeat=excluded.last_heartbeat,connection_id=excluded.connection_id`,
          claims.installationId, claims.uuid, claims.accountId, claims.launchSessionId ?? claims.jti, claims.version,
          claims.loaderVersion!, claims.generation, claims.iat, claims.exp, Date.now(), Date.now(), input.challenge);
        this.audit("installation", auditAccount, "authentication_success", { uuid: claims.uuid, installationId: claims.installationId });
        return { ...status, connectionId: input.challenge };
      });
    } catch (error) { const result = failure(error); this.audit("installation", auditAccount, "authentication_failure", { error: result.error }); return result; }
  }
  async authorizeArtifact(body: Record<string, unknown>, purpose: "manifest" | "artifact"): Promise<SessionResult> {
    let auditAccount: string | null = null;
    try {
      const { row, input } = this.pending(body.challengeId, purpose), claims = await validateLease(this.env, body.lease);
      auditAccount = claims.accountId;
      if (claims.installationId !== input.installationId || claims.deviceHash !== input.deviceHash || claims.keyFingerprint !== await sha256(decode64(input.publicKey, 256)) ||
        claims.version !== input.modVersion || claims.minecraft !== input.minecraftVersion || claims.platform !== input.platform || claims.launchSessionId !== input.launchSessionId) deny("invalid_lease");
      if (!await verifyInstallation(input.publicKey, body.signature, installationMessage(input, row.nonce))) deny("installation_proof_invalid");
      return this.ctx.storage.transactionSync(() => {
        this.pending(row.id, purpose); const status = this.currentLease(claims); if (!status.ok) deny(status.error!);
        this.verifyHardware(input);
        this.ctx.storage.sql.exec("UPDATE account_challenges SET used_at=? WHERE id=?", Date.now(), row.id); return { ...status, loaderVersion: input.loaderVersion };
      });
    } catch (error) {
      const result = failure(error), replay = result.error === "invalid_challenge" ? this.replayedChallenge(body.challengeId) : null;
      this.audit("installation", replay?.accountId ?? auditAccount, replay ? "challenge_replay" : "artifact_download_denied", { ...replay?.metadata, error: result.error }); return result;
    }
  }
  canUseIrc(discordId: string): SessionResult {
    const account = this.byDiscord(discordId);
    if (!account || !this.ctx.storage.sql.exec("SELECT uuid FROM minecraft_accounts WHERE account_id=? LIMIT 1", account.account_id).toArray().length) return { ok: false, error: "account_required" };
    if (this.community.blacklisted(discordId, undefined)) return { ok: false, error: "identity_blacklisted" };
    return { ok: true, accountId: account.account_id, discordId };
  }
  resolveIrcMentions(text: string): string[] {
    if (!ircPlainText(text, 1024)) return [];
    const mentions = new Set<string>();
    for (const name of ircMentionNames(text)) {
      const matches = this.ctx.storage.sql.exec<{ discord_id: string }>(`SELECT DISTINCT a.discord_id FROM sky_accounts a
        JOIN minecraft_accounts m ON a.account_id=m.account_id LEFT JOIN account_discord_names d ON a.discord_id=d.discord_id
        WHERE lower(m.name)=? OR lower(d.username)=? LIMIT 2`, name, name).toArray();
      if (matches.length === 1 && this.canUseIrc(matches[0].discord_id).ok) mentions.add(matches[0].discord_id);
    }
    return [...mentions];
  }
}
