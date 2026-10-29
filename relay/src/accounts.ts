import { DurableObject } from "cloudflare:workers";
import { newLinkCode, normalizedCode, PlayerCosmetics, type CosmeticProfile, type DiscordCommand, type CosmeticsConfig } from "./cosmetics";
import { CosmeticAssets } from "./cosmetic-assets";
import { ACTIVATION_TTL, DEVICE_RESET_COOLDOWN, type DeveloperActor, type LicensePolicy } from "./licensing";
import { AuthorizationError, deny, randomToken, sha256 } from "./drm";
import { PromptJobs, type PromptActor, type PromptConfig } from "./prompts";
import { ADMIN_ALERT_IDS, deliverAccountAlert } from "./discord-notifications";
import { ircMentionNames, ircPlainText } from "./irc-model";
import { CommunityAccounts, type CommunityConfig, type CommunitySessionCheck } from "./community";
import { CommunitySharingStore, type SharingSession, type SharingCategory } from "./community-sharing";
import { CommunityLeaderboards, type LeaderboardQuery, type JournalComparisonQuery, type JournalComparisonResult, type FeedReceipt } from "./leaderboards";
import type { PublicRngEvent } from "./digest";
import { CommunityBazaar, type BazaarStatsResult } from "./bazaar";
import { BazaarMarket } from "./bazaar-market";
import { GreenhouseReminders, type GreenhouseUpload } from "./greenhouse-reminders";

export type AccountsConfig = LicensePolicy & PromptConfig & CommunityConfig & CosmeticsConfig & { ACCOUNT_STORE?: DurableObjectNamespace<AccountStore>; DISCORD_RNG_CHANNEL_ID?: string; COSMETIC_ASSETS?: R2Bucket; BAZAAR_MARKET_PROVIDER?: string; USER_BOT_TOKEN?: string };
type AccountRow = { account_id: string; discord_id: string; status: string; license_status: string; license_expires_at: number | null;
  device_hash: string | null; hardware_hash: string | null; hwid_reset_at: number | null; hwid_bound_at: number | null; generation: number; activated_at: number | null; created_at: number; last_seen: number; enrollment_blocked: number; is_admin: number };
export type AccountSummary = { accountId: string; discordId: string; status: string; isAdmin: boolean; activated: boolean; licenseStatus: string; licenseExpiresAt: number | null;
  deviceRegistered: boolean; hwidResetAt: number | null; hwidBoundAt: number | null; nextHwidChangeAllowedAt: number | null; uuids: { uuid: string; name: string }[] };
export type AccountResult = { ok: boolean; error?: string; account?: AccountSummary };
export type SessionResult = { ok: boolean; error?: string; accountId?: string; discordId?: string; installationId?: string;
  deviceId?: string; generation?: number; expiresAt?: number; uuid?: string; buildId?: string; loaderVersion?: string; connectionId?: string };
export type AuditValue = string | number | boolean | null | { name: string; uuid: string }[];
export type AuditEvent = { id: number; at: number; actor: string; account_id: string | null; action: string; override: number;
  severity: string; metadata: Record<string, AuditValue> };
export type AccountAlert = { id: string; auditId: number; recipientId: string; claimToken: string; attempts: number; repeats: number; event: AuditEvent };
type RuntimeRow = { installation_id: string; uuid: string; account_id: string; session_id: string; build_id: string; loader_version: string;
  generation: number; lease_issued_at: number; lease_expires_at: number; connected_at: number | null; last_heartbeat: number | null; connection_id: string | null };
const snowflake = /^[0-9]{17,20}$/, uuid = /^[a-f0-9]{32}$/;
function failure(error: unknown): { ok: false; error: string } {
  if (error instanceof AuthorizationError) return { ok: false, error: error.code };
  // Name only: messages can echo stored or client values.
  console.error({ event: "account_store_unavailable", error: error instanceof Error ? error.name : typeof error });
  return { ok: false, error: "authorization_unavailable" };
}

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
  private readonly greenhouse: GreenhouseReminders;
  constructor(ctx: DurableObjectState, env: AccountsConfig) {
    super(ctx, env);
    this.prompts = new PromptJobs(ctx.storage, env);
    this.greenhouse = new GreenhouseReminders(ctx.storage.sql, () => env.USER_BOT_TOKEN);
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
    const addColumn = (table: string, column: string, definition: string): boolean => {
      if (ctx.storage.sql.exec<{ name: string }>(`PRAGMA table_info(${table})`).toArray().some(existing => existing.name === column)) return false;
      ctx.storage.sql.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`); return true;
    };
    addColumn("minecraft_links", "discord_id", "TEXT");
    addColumn("sky_accounts", "is_admin", "INTEGER NOT NULL DEFAULT 0 CHECK(is_admin IN (0,1))");
    addColumn("installations", "platform", "TEXT");
    addColumn("installations", "hardware_hash", "TEXT");
    if (addColumn("sky_accounts", "hwid_bound_at", "INTEGER")) {
      ctx.storage.sql.exec("UPDATE sky_accounts SET hwid_bound_at=COALESCE((SELECT registered_at FROM account_devices WHERE account_devices.device_hash=sky_accounts.device_hash),activated_at) WHERE device_hash IS NOT NULL");
    }
    addColumn("sky_accounts", "hardware_hash", "TEXT");
    addColumn("activation_keys", "hardware_transfer", "INTEGER NOT NULL DEFAULT 0 CHECK(hardware_transfer IN (0,1))");
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
    addColumn("account_audit", "severity", "TEXT NOT NULL DEFAULT 'information'");
    addColumn("account_runtime", "connection_id", "TEXT");
    addColumn("account_management_confirmations", "related_state", "TEXT NOT NULL DEFAULT ''");
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
  /** Every client message already passes this check; a Greenhouse upload rides along instead of making its own call. */
  async consumeCommunitySessionRequest(input: CommunitySessionCheck, greenhouse?: GreenhouseUpload) {
    const state = this.community.consumeRequest(input);
    if (!state.ok || !greenhouse || !state.discordId) return state;
    const stored = this.greenhouse.store(state.discordId, greenhouse);
    if (stored.next !== null) await this.armGreenhouse(stored.next);
    return { ...state, greenhouse: stored.result };
  }
  greenhouseView(discordId: string) { return snowflake.test(discordId) ? this.greenhouse.view(discordId) : null; }
  private async armGreenhouse(due = this.greenhouse.nextDue()): Promise<void> {
    if (due === null) return;
    const current = await this.ctx.storage.getAlarm(), next = Math.max(Date.now() + 1000, due);
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }
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
    try { await this.greenhouse.deliver(); } catch (error) { console.warn({ event: "greenhouse_delivery_failed", error: String(error).slice(0, 200) }); }
    await this.armGreenhouse();
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
  private link(account: AccountRow, id: string, name: string): void {
    const existing = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", id).toArray()[0];
    if (existing && existing.account_id !== account.account_id) deny("minecraft_account_owned");
    this.ctx.storage.sql.exec(`INSERT INTO minecraft_accounts(uuid,account_id,name,linked_at,last_seen) VALUES(?,?,?,?,?)
      ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen`, id, account.account_id, name, Date.now(), Date.now());
    if (!existing) this.audit(account.discord_id, account.account_id, "minecraft_uuid_added", { uuid: id });
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
