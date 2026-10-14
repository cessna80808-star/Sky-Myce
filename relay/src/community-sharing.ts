/** Community statistics consent is independent of chat, cosmetics, device metadata and service access. */
export const sharingCategories = ["dungeons", "economy", "rng", "activity", "leaderboards"] as const;
export type SharingCategory = typeof sharingCategories[number];
export type SharingPolicy = { version: 1; enabled: boolean; dungeons: boolean; economy: boolean; rng: boolean;
  activity: boolean; leaderboards: boolean; publicName: boolean };
export type SharingState = SharingPolicy & { revision: number; updatedAt: number; notBefore: Record<SharingCategory, number> };
export type SharingSession = { sessionId: string; accountId: string; uuid: string; generation: number };
export type SharingResult = { ok: boolean; error?: string; policy?: SharingState; affectedUuids?: string[]; suppressedCategories?: SharingCategory[] };
export type SharingDecision = { allowed: boolean; publicName: boolean; revision: number; notBefore: number };
export type SharingHooks = {
  owner(uuid: string): { accountId: string; uuids: string[] } | null;
  session(input: SharingSession): { ok: boolean; error?: string; accountId?: string; uuid?: string };
  blacklisted(uuid: string): boolean;
};
type PolicyRow = { owner: string; policy: string; revision: number; updated_at: number; cutoffs: string };
const uuidPattern = /^[a-f0-9]{32}$/;
const policyKeys = ["version", "enabled", ...sharingCategories, "publicName"];
export const defaultSharingPolicy = (): SharingPolicy => ({ version: 1, enabled: true, dungeons: true, economy: true,
  rng: true, activity: true, leaderboards: true, publicName: false });
const cutoffs = (): Record<SharingCategory, number> => ({ dungeons: 0, economy: 0, rng: 0, activity: 0, leaderboards: 0 });
export function parseSharingPolicy(raw: unknown): SharingPolicy | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const policy = raw as Record<string, unknown>;
  if (Object.keys(policy).length !== policyKeys.length || Object.keys(policy).some(key => !policyKeys.includes(key)) ||
    policy.version !== 1 || policyKeys.some(key => key !== "version" && typeof policy[key] !== "boolean")) return null;
  return { version: 1, enabled: policy.enabled as boolean, dungeons: policy.dungeons as boolean,
    economy: policy.economy as boolean, rng: policy.rng as boolean, activity: policy.activity as boolean,
    leaderboards: policy.leaderboards as boolean, publicName: policy.publicName as boolean };
}

/** All methods are synchronous: the owning AccountStore composes them into its identity/storage transactions. */
export class CommunitySharingStore {
  constructor(private sql: SqlStorage, private hooks: SharingHooks) {
    sql.exec(`CREATE TABLE IF NOT EXISTS community_sharing (
      owner TEXT PRIMARY KEY, policy TEXT NOT NULL, revision INTEGER NOT NULL, updated_at INTEGER NOT NULL, cutoffs TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS community_contributions (
        id TEXT NOT NULL, owner TEXT NOT NULL, uuid TEXT NOT NULL, category TEXT NOT NULL, metric TEXT NOT NULL,
        value REAL NOT NULL, unit TEXT NOT NULL, observed_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
        source TEXT NOT NULL, verification TEXT NOT NULL, complete INTEGER NOT NULL, policy_revision INTEGER NOT NULL, PRIMARY KEY(uuid,id));
      CREATE INDEX IF NOT EXISTS community_contributions_subject ON community_contributions(uuid, category);
      CREATE INDEX IF NOT EXISTS community_contributions_owner ON community_contributions(owner, category);
      CREATE INDEX IF NOT EXISTS community_contributions_age ON community_contributions(received_at)`);
  }
  private owner(uuid: string): string { return this.hooks.owner(uuid)?.accountId ?? `uuid:${uuid}`; }
  private state(owner: string): SharingState {
    const row = this.sql.exec<PolicyRow>("SELECT * FROM community_sharing WHERE owner=?", owner).toArray()[0];
    if (!row) return { ...defaultSharingPolicy(), revision: 0, updatedAt: 0, notBefore: cutoffs() };
    return { ...JSON.parse(row.policy) as SharingPolicy, revision: row.revision, updatedAt: row.updated_at,
      notBefore: { ...cutoffs(), ...JSON.parse(row.cutoffs) } };
  }
  private save(owner: string, policy: SharingPolicy, revision: number, now: number, notBefore: Record<SharingCategory, number>): SharingState {
    this.sql.exec("INSERT INTO community_sharing VALUES(?,?,?,?,?) ON CONFLICT(owner) DO UPDATE SET policy=excluded.policy,revision=excluded.revision,updated_at=excluded.updated_at,cutoffs=excluded.cutoffs",
      owner, JSON.stringify(policy), revision, now, JSON.stringify(notBefore));
    return { ...policy, revision, updatedAt: now, notBefore };
  }
  private authenticated(input: SharingSession): string | null {
    if (!input || typeof input.uuid !== "string" || !uuidPattern.test(input.uuid)) return null;
    const session = this.hooks.session(input);
    return session.ok && session.uuid === input.uuid && session.accountId === input.accountId &&
      session.accountId === this.hooks.owner(input.uuid)?.accountId ? input.uuid : null;
  }
  get(input: SharingSession): SharingResult {
    const uuid = this.authenticated(input);
    return uuid ? this.getVerified(uuid) : { ok: false, error: "not_authorized" };
  }
  update(input: SharingSession, expectedRevision: number, rawPolicy: unknown): SharingResult {
    const uuid = this.authenticated(input);
    return uuid ? this.updateVerified(uuid, expectedRevision, rawPolicy) : { ok: false, error: "not_authorized" };
  }
  /** Internal RPC callers must obtain this UUID from a verified Minecraft session, never an HTTP request field. */
  getVerified(uuid: string): SharingResult {
    if (!uuidPattern.test(uuid) || this.hooks.blacklisted(uuid)) return { ok: false, error: "not_authorized" };
    return { ok: true, policy: this.state(this.owner(uuid)) };
  }
  updateVerified(uuid: string, expectedRevision: number, rawPolicy: unknown): SharingResult {
    if (!uuidPattern.test(uuid) || this.hooks.blacklisted(uuid)) return { ok: false, error: "not_authorized" };
    const policy = parseSharingPolicy(rawPolicy);
    if (!policy || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0) return { ok: false, error: "invalid_sharing_policy" };
    const owner = this.owner(uuid), previous = this.state(owner);
    if (previous.revision !== expectedRevision) return { ok: false, error: "sharing_conflict", policy: previous };
    const now = Date.now(), notBefore = { ...previous.notBefore };
    // An opt-out removes prior contributions. Re-enabling starts at the present, never replays disabled-period history.
    const hidePreviousNames = previous.publicName && !policy.publicName;
    const suppressed = sharingCategories.filter(category => !policy.enabled || !policy[category] || hidePreviousNames && (category === "rng" || category === "leaderboards"));
    for (const category of sharingCategories) if (suppressed.includes(category) || !previous.enabled || !previous[category]) notBefore[category] = Math.max(notBefore[category], now);
    const state = this.save(owner, policy, previous.revision + 1, now, notBefore);
    const affected = this.hooks.owner(uuid)?.uuids ?? [uuid];
    if (suppressed.length) this.sql.exec(`DELETE FROM community_contributions WHERE owner=? AND category IN (${suppressed.map(() => "?").join(",")})`, owner, ...suppressed);
    if (!policy.enabled || !policy.leaderboards || hidePreviousNames) this.sql.exec("DELETE FROM community_contributions WHERE owner=?", owner);
    return { ok: true, policy: state, affectedUuids: [...new Set([...affected, uuid])], suppressedCategories: suppressed };
  }
  check(uuid: string, category: SharingCategory, recordedAt?: number): SharingDecision {
    if (!uuidPattern.test(uuid) || !sharingCategories.includes(category)) return { allowed: false, publicName: false, revision: 0, notBefore: 0 };
    const state = this.state(this.owner(uuid)), notBefore = state.notBefore[category];
    const historical = recordedAt !== undefined && (!Number.isSafeInteger(recordedAt) || recordedAt <= notBefore);
    return { allowed: !this.hooks.blacklisted(uuid) && state.enabled && state[category] && !historical,
      publicName: state.publicName, revision: state.revision, notBefore };
  }
  checkMany(requests: { uuid: string; category: SharingCategory; recordedAt?: number }[]): SharingDecision[] {
    if (!Array.isArray(requests) || requests.length > 256) return [];
    return requests.map(request => this.check(request.uuid, request.category, request.recordedAt));
  }
  /** Linking does not resurrect any old opt-out. Preserve the source account's remaining Minecraft identities. */
  mergeAccount(sourceId: string, targetId: string): void {
    if (sourceId === targetId) return;
    this.merge(sourceId, targetId);
  }
  mergeUuid(uuid: string, targetAccountId: string): void {
    if (uuidPattern.test(uuid)) {
      this.merge(`uuid:${uuid}`, targetAccountId);
      this.sql.exec("DELETE FROM community_contributions WHERE uuid=?", uuid);
    }
  }
  /** Snapshot consent before ownership is removed; an unlinked identity must not fall back to enabled defaults. */
  detachUuid(uuid: string, sourceAccountId: string): void {
    if (!uuidPattern.test(uuid) || this.hooks.owner(uuid)?.accountId !== sourceAccountId) return;
    const from = this.state(sourceAccountId), owner = `uuid:${uuid}`, previous = this.state(owner), policy = defaultSharingPolicy(), notBefore = cutoffs();
    for (const key of ["enabled", ...sharingCategories, "publicName"] as const) policy[key] = from[key] && previous[key];
    if (previous.revision === 0) policy.publicName = from.publicName;
    const now = Date.now(), hidePreviousNames = from.publicName && !policy.publicName;
    for (const category of sharingCategories) notBefore[category] = Math.max(from.notBefore[category], previous.notBefore[category],
      !policy.enabled || !policy[category] || hidePreviousNames && (category === "rng" || category === "leaderboards") ? now : 0);
    this.save(owner, policy, previous.revision + 1, now, notBefore);
    const disabled = sharingCategories.filter(category => !policy.enabled || !policy[category]);
    if (disabled.length) this.sql.exec(`DELETE FROM community_contributions WHERE uuid=? AND category IN (${disabled.map(() => "?").join(",")})`, uuid, ...disabled);
    if (!policy.enabled || !policy.leaderboards || hidePreviousNames) this.sql.exec("DELETE FROM community_contributions WHERE uuid=?", uuid);
  }
  private merge(source: string, target: string): void {
    const from = this.state(source), to = this.state(target), merged = defaultSharingPolicy(), notBefore = cutoffs();
    if (from.revision === 0) return; // An absent default is not an explicit anonymous-name preference or opt-out.
    for (const key of ["enabled", ...sharingCategories, "publicName"] as const) merged[key] = from[key] && to[key];
    if (to.revision === 0) merged.publicName = from.publicName;
    for (const category of sharingCategories) notBefore[category] = Math.max(from.notBefore[category], to.notBefore[category]);
    this.save(target, merged, to.revision + 1, Date.now(), notBefore);
    const disabled = sharingCategories.filter(category => !merged.enabled || !merged[category]);
    if (disabled.length) this.sql.exec(`DELETE FROM community_contributions WHERE owner=? AND category IN (${disabled.map(() => "?").join(",")})`, target, ...disabled);
    if (!merged.enabled || !merged.leaderboards) this.sql.exec("DELETE FROM community_contributions WHERE owner=?", target);
  }
  recordContribution(uuid: string, raw: unknown, name: string): { ok: boolean; error?: string } {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, error: "invalid_contribution" };
    const value = raw as Record<string, unknown>, metric = typeof value.metric === "string" && Object.hasOwn(contributionMetrics, value.metric) ? contributionMetrics[value.metric] : undefined;
    if (!metric || Object.keys(value).some(key => !["id", "metric", "value", "observedAt", "complete", "source", "verification"].includes(key)) ||
      typeof value.id !== "string" || !/^(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$/.test(value.id) || typeof value.value !== "number" || !Number.isFinite(value.value) ||
      Math.abs(value.value) > Number.MAX_SAFE_INTEGER || value.value < 0 && !metric.negative || value.complete !== true ||
      metric.integer && !Number.isSafeInteger(value.value) ||
      value.metric === "dungeon_run_time" && (value.value <= 0 || value.value > 86_400_000) ||
      !Number.isSafeInteger(value.observedAt) || Number(value.observedAt) <= Date.now() - 300_000 || Number(value.observedAt) > Date.now() + 60_000 ||
      !["client-observation", "public-api-client"].includes(String(value.source)) || !["unverified", "estimated"].includes(String(value.verification)) ||
      !/^[A-Za-z0-9_]{1,16}$/.test(name)) return { ok: false, error: "invalid_contribution" };
    const policy = this.check(uuid, metric.category, Number(value.observedAt)), leaderboard = this.check(uuid, "leaderboards", Number(value.observedAt));
    if (!policy.allowed || !leaderboard.allowed) return { ok: false, error: "sharing_disabled" };
    if (this.sql.exec("SELECT 1 FROM community_contributions WHERE uuid=? AND id=?", uuid, value.id).toArray().length) return { ok: true };
    // All-time means retained observations, not a silently rolling month. Preserve old rows when bounded storage fills.
    if (this.sql.exec<{ total: number }>("SELECT COUNT(*) AS total FROM community_contributions").one().total >= 100000) return { ok: false, error: "contribution_capacity_reached" };
    this.sql.exec("INSERT OR IGNORE INTO community_contributions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", value.id, this.owner(uuid), uuid, metric.category,
      value.metric as string, value.value, metric.unit, value.observedAt as number, Date.now(), value.source as string, value.verification as string, 1, policy.revision);
    return { ok: true };
  }
}

/** New metrics declare their privacy category and units. Client observations never become verified leaderboard scores. */
export const contributionMetrics: Record<string, { category: SharingCategory; unit: string; negative?: boolean; integer?: boolean }> = {
  dungeon_runs: { category: "dungeons", unit: "runs", integer: true }, dungeon_profit: { category: "dungeons", unit: "coins", negative: true },
  dungeon_run_time: { category: "dungeons", unit: "milliseconds", integer: true },
  networth: { category: "economy", unit: "coins" }, purse: { category: "economy", unit: "coins" }, bank: { category: "economy", unit: "coins" },
  rng_drops: { category: "rng", unit: "drops", integer: true }, completed_tasks: { category: "activity", unit: "tasks", integer: true },
};
