import { DurableObject } from "cloudflare:workers";
import { verifyAccount } from "./auth";
import { newLinkCode, normalizedCode } from "./cosmetics";
import { ACTIVATION_TTL, DEVICE_RESET_COOLDOWN, licenseDenial, type DeveloperActor, type LicensePolicy } from "./licensing";
import { AuthorizationError, bootstrapPurpose, createLease, decode64, deny, ensureSigningConfigured, installationKey, installationMessage, installationVersionDenial, randomToken,
  seconds, sha256, validateLaunchInput, validateLease, verifyInstallation, type AuthenticationPurpose, type ClientPlatform, type DrmConfig, type InstallationInput, type LeaseClaims } from "./drm";
import { PromptJobs, type PromptActor, type PromptConfig } from "./prompts";

export type AccountsConfig = LicensePolicy & DrmConfig & PromptConfig & { ACCOUNT_STORE?: DurableObjectNamespace<AccountStore> };
type AccountRow = { account_id: string; discord_id: string; status: string; license_status: string; license_expires_at: number | null;
  device_hash: string | null; hardware_hash: string | null; hwid_reset_at: number | null; hwid_bound_at: number | null; generation: number; activated_at: number | null; created_at: number; last_seen: number; enrollment_blocked: number; is_admin: number };
type InstallationRow = { installation_id: string; account_id: string; device_hash: string; hardware_hash: string | null; public_key: string; fingerprint: string; revoked_at: number | null; platform: ClientPlatform | null };
type ChallengeRow = { id: string; nonce: string; input: string; approval_hash: string; discord_id: string | null; expires_at: number; used_at: number | null };
export type AccountSummary = { accountId: string; discordId: string; status: string; isAdmin: boolean; activated: boolean; licenseStatus: string; licenseExpiresAt: number | null;
  deviceRegistered: boolean; hwidResetAt: number | null; hwidBoundAt: number | null; nextHwidChangeAllowedAt: number | null; uuids: { uuid: string; name: string }[] };
export type AccountResult = { ok: boolean; error?: string; account?: AccountSummary; activationKey?: string; activationId?: string;
  expiresAt?: number; invalidated?: string; invalidatedAccounts?: string[]; audit?: Record<string, SqlStorageValue>[]; warningId?: string; adminAdded?: boolean };
export type SessionResult = { ok: boolean; error?: string; accountId?: string; discordId?: string; installationId?: string;
  deviceId?: string; generation?: number; expiresAt?: number; uuid?: string; loaderVersion?: string };
const snowflake = /^[0-9]{17,20}$/, uuid = /^[a-f0-9]{32}$/, hash = /^[a-f0-9]{64}$/;
const failure = (error: unknown): { ok: false; error: string } => ({ ok: false, error: error instanceof AuthorizationError ? error.code : "authorization_unavailable" });

/** Identity uniqueness is one coordination atom, shared across all relay rooms.
 * ponytail: one SQL coordinator serializes global uniqueness; move to a global SQL service if identity throughput outgrows it. */
export class AccountStore extends DurableObject<AccountsConfig> {
  private readonly prompts: PromptJobs;
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
  }
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
  async alarm(): Promise<void> { await this.prompts.alarm(); }
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
    this.ctx.storage.sql.exec("INSERT INTO account_audit(at,actor,account_id,action,override,metadata) VALUES(?,?,?,?,?,?)",
      Date.now(), actor, accountId, action, override ? 1 : 0, JSON.stringify(metadata));
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
    this.ctx.storage.sql.exec("INSERT INTO minecraft_links(hash,uuid,name,expires_at,discord_id) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash,name=excluded.name,expires_at=excluded.expires_at,discord_id=excluded.discord_id", digest, id, name, expiresAt, discordId);
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
      this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations VALUES('*',?)", id);
      this.ctx.storage.sql.exec("DELETE FROM minecraft_accounts WHERE uuid=?", id);
      this.invalidate(account.account_id); this.audit(discordId, account.account_id, "minecraft_uuid_removed", { uuid: id });
      return { ok: true, invalidated: account.account_id, account: this.summary(this.byId(account.account_id)!) };
    }); } catch (error) { return failure(error); }
  }
  async userCommand(actor: DeveloperActor, action: string, options: Record<string, unknown> = {}): Promise<AccountResult> {
    try {
      this.interaction(actor); this.limit(`user:${actor.id}`, 12);
      if (action === "link") return this.consumeMinecraftLink(actor.id, String(options.code ?? ""));
      if (action === "unlink") return this.unlinkMinecraftUuid(actor.id, String(options.uuid ?? ""));
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
      const activationKey = action === "reset" ? randomToken() : undefined;
      const activationHash = activationKey ? await sha256(activationKey) : undefined;
      return this.ctx.storage.transactionSync(() => {
        const account = this.byDiscord(actor.id); if (!account) deny("account_required");
        if (action === "reset") this.transferAllowed(account); else if (action !== "status") deny("invalid_action");
        return { ok: true, account: this.summary(this.byId(account.account_id)!), ...(action === "reset" ? {
          ...this.issueActivation(account, actor.id, activationKey!, activationHash!, account.device_hash !== null) } : {}) };
      });
    } catch (error) { return failure(error); }
  }
  async developerCommand(actor: DeveloperActor, action: string, target: string, options: Record<string, unknown> = {}): Promise<AccountResult> {
    try {
      if (!this.isAdmin(actor)) { this.audit(actor.id, null, "admin_denied"); deny("admin_required"); }
      if (!snowflake.test(target)) deny("invalid_discord_id");
      this.interaction(actor); this.limit(`developer:${actor.id}`, 30);
      const activationKey = action === "authorise" || action === "hwid_reset" ? randomToken() : undefined;
      const activationHash = activationKey ? await sha256(activationKey) : undefined;
      return this.ctx.storage.transactionSync(() => {
        if (!this.isAdmin(actor)) deny("admin_required"); // Recheck durable authority after any cryptographic await.
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
            this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations SELECT '*',uuid FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, options.uuid as string);
            this.ctx.storage.sql.exec("DELETE FROM minecraft_accounts WHERE account_id=? AND uuid=?", account.account_id, options.uuid as string);
          }
          else {
            this.ctx.storage.sql.exec("INSERT OR IGNORE INTO account_migrations SELECT '*',uuid FROM minecraft_accounts WHERE account_id=?", account.account_id);
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
          this.audit(actor.id, account.account_id, "warning_issued", { warningId }); return { ok: true, warningId };
        } else if (action === "warning_remove") {
          if (typeof options.warningId !== "string") deny("invalid_warning");
          this.ctx.storage.sql.exec("UPDATE account_warnings SET removed_at=? WHERE id=? AND account_id=?", Date.now(), options.warningId, account.account_id);
        } else if (action !== "session_kick") deny("invalid_action");
        if (action !== "warning_remove") { this.invalidate(account.account_id); invalidated = account.account_id; }
        this.audit(actor.id, account.account_id, action, action.startsWith("hwid_") ? { discordId: account.discord_id,
          previousHwid: account.device_hash, newHwid: action === "hwid_bind" ? options.deviceHash : null,
          reason: typeof options.reason === "string" ? options.reason.slice(0, 500) : action === "hwid_reset" ? "administrator_reset" : action,
          source: "administrator", administratorId: actor.id, requestId: actor.interactionId } : {}, action.startsWith("hwid_"));
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
  async authenticateInstallation(body: Record<string, unknown>, requestedPurpose: AuthenticationPurpose): Promise<{ ok: boolean; error?: string; lease?: string; expiresAt?: number; serverTime?: number }> {
    const bootstrap = bootstrapPurpose(requestedPurpose), purpose = requestedPurpose.endsWith("activate") ? "activate" : "renew";
    let auditAccount: string | null = null;
    let auditAction = purpose === "activate" ? "activation_denied" : "lease_denied";
    try {
      await ensureSigningConfigured(this.env);
      const { row, input } = this.pending(body.challengeId, requestedPurpose);
      auditAccount = row.discord_id ? this.byDiscord(row.discord_id)?.account_id ?? null :
        this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM installations WHERE installation_id=?", input.installationId).toArray()[0]?.account_id ?? null;
      this.limit(`proof:${row.id}`, 6);
      if (!await verifyInstallation(input.publicKey, body.signature, installationMessage(input, row.nonce))) deny("installation_proof_invalid");
      if (!body.minecraftProof || typeof body.minecraftProof !== "object" || Array.isArray(body.minecraftProof)) deny("minecraft_proof_required");
      const profile = await verifyAccount(body.minecraftProof as Record<string, unknown>, row.nonce);
      if ("error" in profile) deny(profile.retryable ? "minecraft_verification_unavailable" : "minecraft_proof_invalid");
      const activationHash = typeof body.activationKey === "string" && /^[A-Za-z0-9_-]{43}$/.test(body.activationKey) ? await sha256(body.activationKey) : null;
      if (purpose === "activate" && body.activationKey !== undefined && !activationHash) deny("invalid_activation_key");
      const fingerprint = await sha256(decode64(input.publicKey, 256));
      const claims = this.ctx.storage.transactionSync((): LeaseClaims => {
        const current = this.pending(row.id, requestedPurpose); // Recheck after cryptographic awaits, including concurrent consumption.
        const invalidVersion = installationVersionDenial(current.input, this.env); if (invalidVersion) deny(invalidVersion);
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
          const status = this.checkSession({ accountId: account.account_id, uuid: profile.id, installationId: input.installationId, generation: account.generation });
          if (!status.ok) deny(status.error!); this.link(account, profile.id, profile.name);
        }
        this.verifyHardware(input, bootstrap);
        this.ctx.storage.sql.exec("UPDATE account_challenges SET used_at=? WHERE id=?", Date.now(), row.id);
        this.ctx.storage.sql.exec("UPDATE sky_accounts SET last_seen=? WHERE account_id=?", Date.now(), account.account_id);
        const now = Date.now(), exp = Math.min(now + seconds(this.env.LEASE_TTL_SECONDS, 300, 30, 900), account.license_expires_at ?? Infinity);
        const offlineUntil = Math.min(exp + seconds(this.env.OFFLINE_GRACE_SECONDS, 900, 0, 3600), account.license_expires_at ?? Infinity);
        this.audit(account.discord_id, account.account_id, bootstrap ? "loader_update_authorized" : "lease_issued", { uuid: profile.id, installationId: input.installationId, expiresAt: exp, hardwareState: "HWID_MATCH" });
        return { product: "sky-myce", protocol: 3, accountId: account.account_id, discordId: account.discord_id, uuid: profile.id,
          installationId: input.installationId, deviceHash: input.deviceHash, keyFingerprint: fingerprint, version: input.modVersion,
          minecraft: input.minecraftVersion, iat: now, exp, offlineUntil, generation: account.generation, jti: crypto.randomUUID(),
          challengeId: row.id, clientNonce: input.clientNonce, features: ["relay", "irc", "cosmetics"],
          ...(input.hardwareHash !== undefined ? { hardwareHash: input.hardwareHash } : {}),
          ...(input.platform !== undefined ? { platform: input.platform, launchSessionId: input.launchSessionId } : {}) };
      });
      if (bootstrap) return { ok: true }; // No lease, protected artifact or feature authorization for outdated clients.
      const lease = await createLease(this.env, claims);
      const current = this.currentLease(claims);
      if (!current.ok) deny(current.error!); // Revocation while signing cannot issue a usable new lease.
      return { ok: true, lease, expiresAt: claims.exp, serverTime: claims.iat };
    } catch (error) {
      const result = failure(error);
      if (result.error !== "approval_required") this.audit("installation", auditAccount, auditAction, { error: result.error });
      return result;
    }
  }
  checkSession(input: { accountId: string; uuid: string; installationId: string; generation: number }): SessionResult {
    const account = this.byId(input.accountId), denial = licenseDenial(account); if (denial) return { ok: false, error: denial };
    if (account!.generation !== input.generation) return { ok: false, error: "authorization_revoked" };
    const install = this.ctx.storage.sql.exec<InstallationRow>("SELECT * FROM installations WHERE installation_id=?", input.installationId).toArray()[0];
    if (!install || install.account_id !== account!.account_id || install.revoked_at !== null || install.device_hash !== account!.device_hash) return { ok: false, error: "device_revoked" };
    const linked = this.ctx.storage.sql.exec<{ account_id: string }>("SELECT account_id FROM minecraft_accounts WHERE uuid=?", input.uuid).toArray()[0];
    if (!linked || linked.account_id !== account!.account_id) return { ok: false, error: "minecraft_link_required" };
    return { ok: true, accountId: account!.account_id, discordId: account!.discord_id, installationId: install.installation_id,
      deviceId: install.fingerprint, generation: account!.generation, uuid: input.uuid };
  }
  private currentLease(claims: LeaseClaims): SessionResult {
    const status = this.checkSession({ accountId: claims.accountId, uuid: claims.uuid, installationId: claims.installationId, generation: claims.generation });
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
        this.audit("installation", auditAccount, "authentication_success", { uuid: claims.uuid, installationId: claims.installationId });
        return status;
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
    } catch (error) { const result = failure(error); this.audit("installation", auditAccount, "artifact_download_denied", { error: result.error }); return result; }
  }
  canUseIrc(discordId: string): SessionResult {
    const account = this.byDiscord(discordId), denial = licenseDenial(account); if (denial) return { ok: false, error: denial };
    if (!account!.activated_at || !account!.device_hash || !this.ctx.storage.sql.exec("SELECT uuid FROM minecraft_accounts WHERE account_id=? LIMIT 1", account!.account_id).toArray().length) return { ok: false, error: "account_required" };
    return { ok: true, accountId: account!.account_id, discordId };
  }
}
