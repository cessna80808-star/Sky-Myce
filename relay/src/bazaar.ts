import type { SharingCategory, SharingDecision } from "./community-sharing";
import type { LeaderboardQuery, LeaderboardResult } from "./leaderboards";
import { bazaarProductId } from "./bazaar-market";
import { randomToken } from "./drm";

const object = (raw: unknown): raw is Record<string, unknown> => !!raw && typeof raw === "object" && !Array.isArray(raw);
const fields = (raw: Record<string, unknown>, allowed: string[]) => Object.keys(raw).every(key => allowed.includes(key));
const money = (raw: unknown): raw is number => typeof raw === "number" && Number.isFinite(raw) && Math.abs(raw) <= 1e12;
const hex = /^[a-f0-9]{32}$/, fingerprint = /^[a-f0-9]{64}$/;
export type BazaarEvent = { id: string; fingerprint: string; profit: number; cost: number; proceeds: number; fees: number;
  durationMs: number; completed: boolean; observedAt: number; quality: "observed" | "corroborated" };
type Hooks = { check(uuid: string, category: SharingCategory, at?: number): SharingDecision };
type Identity = { accountId: string; uuid: string; name: string };
type SessionSummary = { durationMs: number; realizedProfit: number; completedFlips: number; receivedAt: number };
export type BazaarStatsResult = { ok: false; error: string } | { ok: true; version: number; period: string; profit: number; cost: number; durationMs: number;
  completedFlips: number; realizations: number; roi: number | null; profitPerHour: number | null; coverage: string; verification: string;
  sessionSummary: SessionSummary | null; publications: { id: string; token: string; createdAt: number }[];
  sharing?: { enabled: boolean; economy: boolean; leaderboards: boolean; publicName: boolean } };
export function validBazaarEvent(raw: unknown, now = Date.now(), minimumObservedAt = now - 7 * 86400000): raw is BazaarEvent {
  if (!object(raw) || !fields(raw, ["id", "fingerprint", "profit", "cost", "proceeds", "fees", "durationMs", "completed", "observedAt", "quality"]) ||
    typeof raw.id !== "string" || !hex.test(raw.id) || typeof raw.fingerprint !== "string" || !fingerprint.test(raw.fingerprint) ||
    !money(raw.profit) || !money(raw.cost) || raw.cost <= 0 || !money(raw.proceeds) || raw.proceeds < 0 || !money(raw.fees) || raw.fees < 0 ||
    raw.proceeds + raw.fees > raw.cost * 100 || Math.abs(raw.profit - (raw.proceeds - raw.cost)) > .02 ||
    !Number.isSafeInteger(raw.durationMs) || Number(raw.durationMs) < 1 || Number(raw.durationMs) > 31 * 86400000 ||
    typeof raw.completed !== "boolean" || !["observed", "corroborated"].includes(String(raw.quality)) ||
    !Number.isSafeInteger(raw.observedAt) || Number(raw.observedAt) <= minimumObservedAt || Number(raw.observedAt) > now + 60000) return false;
  return true;
}
export function validBazaarTimezone(zone: unknown): zone is string {
  if (typeof zone !== "string" || zone.length > 80 || !/^[A-Za-z0-9_+\-]+(?:\/[A-Za-z0-9_+\-]+)*$/.test(zone)) return false;
  try { new Intl.DateTimeFormat("en-US", { timeZone: zone }).format(0); return true; } catch { return false; }
}
const preferenceNumbers: Record<string, readonly [number, number]> = {
  minimumProfit: [0, 100000000], minimumRoiBps: [0, 10000], minimumLiquidity: [0, 100000000], maximumFillMinutes: [0, 10080],
  minimumConfidence: [0, 100], maximumCapital: [1, 1000000000], concentration: [1, 100], sellTaxBps: [0, 2500], relistThreshold: [1, 100],
  alertProfit: [0, 1000000000], alertCooldownSeconds: [30, 3600], marketRetentionDays: [1, 31],
  inGameAlertProfit: [0, 1000000000], discordAlertProfit: [0, 1000000000], dashboardAlertProfit: [0, 1000000000], webhookAlertProfit: [0, 1000000000],
  inGameAlertCooldown: [30, 3600], discordAlertCooldown: [30, 3600], dashboardAlertCooldown: [30, 3600], webhookAlertCooldown: [30, 3600],
  hudX: [-32768, 32768], hudY: [-32768, 32768], hudScalePercent: [25, 300],
};
export function validBazaarPreference(key: string, value: unknown): boolean {
  if (Object.hasOwn(preferenceNumbers, key)) { const [lo, hi] = preferenceNumbers[key]; return typeof value === "number" && Number.isSafeInteger(value) && value >= lo && value <= hi; }
  if (["inGameAlerts", "discordAlerts", "dashboardAlerts", "inGameAlertSound", "dashboardAlertSound", "hudVisible"].includes(key)) return typeof value === "boolean";
  if (key === "risk") return typeof value === "string" && ["conservative", "balanced", "aggressive"].includes(value.toLowerCase());
  if (key === "timezone") return validBazaarTimezone(value);
  if (key === "hudAnchor") return typeof value === "string" && ["TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"].includes(value);
  return false;
}
/** Calendar boundaries, not elapsed seconds: a DST week may have 167 or 169 hours. */
export function bazaarPeriod(zone: string, now: number) {
  if (!validBazaarTimezone(zone)) throw Error("invalid_timezone");
  const formatter = new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" });
  const civil = (at: number) => { const parts = Object.fromEntries(formatter.formatToParts(at).map(p => [p.type, p.value]));
    return [Number(parts.year), Number(parts.month), Number(parts.day)] as const; };
  const midnight = (year: number, month: number, day: number) => {
    const targetDate = new Date(Date.UTC(year, month - 1, day)), target = targetDate.getUTCFullYear() * 10000 + (targetDate.getUTCMonth() + 1) * 100 + targetDate.getUTCDate();
    let lo = targetDate.getTime() - 36 * 3600000, hi = targetDate.getTime() + 36 * 3600000;
    while (hi - lo > 1) { const mid = Math.floor((hi + lo) / 2), [y, m, d] = civil(mid);
      if (y * 10000 + m * 100 + d < target) lo = mid; else hi = mid; }
    return hi;
  };
  const [year, month, day] = civil(now), weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return { timezone: zone, weekStart: midnight(year, month, day - weekday), weekEnd: midnight(year, month, day - weekday + 7),
    monthStart: midnight(year, month, 1), monthEnd: midnight(year, month + 1, 1) };
}
type Replay = { mode: "summary" | "full"; visibility: "unlisted" | "community"; durationMs: number; completedFlips: number;
  identity?: string; realizedProfit?: number; cost?: number; progress?: { at: number; profit: number }[];
  timeline: { at: number; profit?: number; item?: string; quantity?: number; buyPrice?: number; sellPrice?: number; fees?: number }[] };
export function redactBazaarReplay(raw: unknown, name: string, publicName: boolean): Replay | null {
  if (!object(raw) || !fields(raw, ["mode", "visibility", "durationMs", "completedFlips", "realizedProfit", "cost", "timeline", "progress", "redact"]) ||
    !["summary", "full"].includes(String(raw.mode)) || !["unlisted", "community"].includes(String(raw.visibility)) ||
    !Number.isSafeInteger(raw.durationMs) || Number(raw.durationMs) < 1 || Number(raw.durationMs) > 86400000 ||
    !Number.isSafeInteger(raw.completedFlips) || Number(raw.completedFlips) < 0 || Number(raw.completedFlips) > 10000 ||
    !money(raw.realizedProfit) || !money(raw.cost) || raw.cost < 0 || !Array.isArray(raw.timeline) || raw.timeline.length > 256 ||
    !object(raw.redact) || Object.keys(raw.redact).length !== 4 || !fields(raw.redact, ["items", "transactions", "values", "identity"]) ||
    Object.values(raw.redact).some(value => typeof value !== "boolean")) return null;
  const redaction = raw.redact, timeline: Replay["timeline"] = []; let previous = -1;
  const progress: { at: number; profit: number }[] = [];
  if (raw.progress !== undefined) {
    if (!Array.isArray(raw.progress) || raw.progress.length > 32) return null;
    for (const point of raw.progress) {
      if (!object(point) || Object.keys(point).length !== 2 || !fields(point, ["at", "profit"]) || !Number.isSafeInteger(point.at) ||
        Number(point.at) <= previous || Number(point.at) < 0 || Number(point.at) > Number(raw.durationMs) || !money(point.profit)) return null;
      previous = Number(point.at); if (!redaction.values) progress.push({ at: previous, profit: point.profit });
    }
  }
  previous = -1;
  for (const event of raw.timeline) {
    if (!object(event) || !fields(event, ["at", "profit", "item", "quantity", "buyPrice", "sellPrice", "fees"]) ||
      !Number.isSafeInteger(event.at) || Number(event.at) < previous || Number(event.at) > Number(raw.durationMs) || Number(event.at) < 0 ||
      !money(event.profit) || event.item !== undefined && !bazaarProductId(event.item) ||
      [event.quantity, event.buyPrice, event.sellPrice, event.fees].some(value => value !== undefined && (!money(value) || value < 0))) return null;
    previous = Number(event.at);
    // Summary and transaction redaction retain only aggregate totals; no hidden transaction payload survives.
    if (raw.mode === "summary" || redaction.transactions) continue;
    timeline.push({ at: Number(event.at), ...(!redaction.values ? { profit: event.profit, ...event.quantity !== undefined ? { quantity: Number(event.quantity) } : {},
      ...event.buyPrice !== undefined ? { buyPrice: Number(event.buyPrice) } : {}, ...event.sellPrice !== undefined ? { sellPrice: Number(event.sellPrice) } : {},
      ...event.fees !== undefined ? { fees: Number(event.fees) } : {} } : {}), ...(!redaction.items && event.item ? { item: String(event.item) } : {}) });
  }
  return { mode: raw.mode as Replay["mode"], visibility: raw.visibility as Replay["visibility"], durationMs: Number(raw.durationMs), completedFlips: Number(raw.completedFlips), timeline,
    ...(progress.length ? { progress } : {}),
    ...(!redaction.identity && publicName ? { identity: name } : {}), ...(!redaction.values ? { realizedProfit: raw.realizedProfit, cost: raw.cost } : {}) };
}

/** Compact integrity/period records in the existing AccountStore database; private journals never arrive here. */
export class CommunityBazaar {
  private nextTombstonePrune = 0;
  constructor(private sql: SqlStorage, private hooks: Hooks) {
    sql.exec(`CREATE TABLE IF NOT EXISTS community_bazaar_realizations(id TEXT NOT NULL,owner TEXT NOT NULL,uuid TEXT NOT NULL,fingerprint TEXT NOT NULL UNIQUE,
      profit REAL NOT NULL,cost REAL NOT NULL,proceeds REAL NOT NULL,fees REAL NOT NULL,duration INTEGER NOT NULL,completed INTEGER NOT NULL,
      observed_at INTEGER NOT NULL,received_at INTEGER NOT NULL,quality TEXT NOT NULL,timezone TEXT NOT NULL,
      week_start INTEGER NOT NULL,week_end INTEGER NOT NULL,month_start INTEGER NOT NULL,month_end INTEGER NOT NULL,PRIMARY KEY(uuid,id));
      CREATE INDEX IF NOT EXISTS community_bazaar_owner ON community_bazaar_realizations(owner,uuid);
      CREATE INDEX IF NOT EXISTS community_bazaar_week ON community_bazaar_realizations(week_end);
      CREATE INDEX IF NOT EXISTS community_bazaar_month ON community_bazaar_realizations(month_end);
      CREATE TABLE IF NOT EXISTS community_bazaar_tombstones(fingerprint TEXT PRIMARY KEY,expires_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS community_bazaar_tombstone_expiry ON community_bazaar_tombstones(expires_at);
      CREATE TABLE IF NOT EXISTS community_bazaar_replays(id TEXT PRIMARY KEY,token TEXT NOT NULL UNIQUE,owner TEXT NOT NULL,uuid TEXT NOT NULL,
        data TEXT NOT NULL,listed INTEGER NOT NULL,created_at INTEGER NOT NULL,revoked_at INTEGER);
      CREATE INDEX IF NOT EXISTS community_bazaar_replay_owner ON community_bazaar_replays(owner,created_at);
      CREATE TABLE IF NOT EXISTS community_bazaar_settings(owner TEXT PRIMARY KEY,data TEXT NOT NULL,revision INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS community_bazaar_summary(owner TEXT PRIMARY KEY,data TEXT NOT NULL,received_at INTEGER NOT NULL);`);
  }
  record(identity: Identity, raw: Record<string, unknown>, now = Date.now()) {
    if (raw.version !== 1 || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "timezone", "events"]) || !validBazaarTimezone(raw.timezone) ||
      !Array.isArray(raw.events) || raw.events.length < 1 || raw.events.length > 32 || raw.events.some(event => !validBazaarEvent(event, now, 0))) return { ok: false, error: "invalid_bazaar_contribution" };
    const events = raw.events as BazaarEvent[], accepted: string[] = [], duplicates: string[] = [];
    const oldestObservation = Math.min(...events.map(event => event.observedAt));
    if (!this.hooks.check(identity.uuid, "economy", oldestObservation).allowed || !this.hooks.check(identity.uuid, "leaderboards", oldestObservation).allowed) return { ok: false, error: "sharing_disabled" };
    const fresh: BazaarEvent[] = [], promoted: string[] = [], ids = new Set<string>(), fingerprints = new Set<string>();
    this.pruneTombstones(now);
    const placeholders = events.map(() => "?").join(",");
    // Three 32-item lists plus UUID/time use at most 98 bindings, below SQLite's 100-binding limit.
    const existing = this.sql.exec<Omit<BazaarEvent, "completed"> & { owner: string; uuid: string; completed: number }>(`SELECT id,owner,uuid,fingerprint,profit,cost,proceeds,fees,duration durationMs,
      completed,observed_at observedAt,quality FROM community_bazaar_realizations WHERE (uuid=? AND id IN (${placeholders})) OR fingerprint IN (${placeholders})
      UNION ALL SELECT '', '','',fingerprint,0,0,0,0,0,0,0,'revoked' FROM community_bazaar_tombstones WHERE expires_at>? AND fingerprint IN (${placeholders})`,
      identity.uuid, ...events.map(event => event.id), ...events.map(event => event.fingerprint), now, ...events.map(event => event.fingerprint)).toArray();
    const byId = new Map(existing.filter(row => row.uuid === identity.uuid).map(row => [row.id, row]));
    const byFingerprint = new Map(existing.map(row => [row.fingerprint, row]));
    for (const event of events) {
      const prior = byId.get(event.id) ?? byFingerprint.get(event.fingerprint);
      if (prior || ids.has(event.id) || fingerprints.has(event.fingerprint)) {
        if (prior && event.completed && !prior.completed && prior.owner === identity.accountId && prior.uuid === identity.uuid && prior.id === event.id &&
          prior.fingerprint === event.fingerprint && (["profit", "cost", "proceeds", "fees", "durationMs", "observedAt", "quality"] as const).every(key => event[key] === prior[key])) promoted.push(event.id);
        duplicates.push(event.id); continue;
      }
      if (event.observedAt <= now - 7 * 86400000) return { ok: false, error: "invalid_bazaar_contribution" };
      ids.add(event.id); fingerprints.add(event.fingerprint); fresh.push(event);
    }
    const complete = () => { for (const id of new Set(promoted)) this.sql.exec("UPDATE community_bazaar_realizations SET completed=1 WHERE owner=? AND uuid=? AND id=? AND completed=0", identity.accountId, identity.uuid, id); };
    if (!fresh.length) { complete(); return { ok: true, version: 1, accepted, duplicates, promoted }; }
    const available = 100000 - this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_bazaar_realizations").one().n;
    if (fresh.length > available) return { ok: false, error: "contribution_capacity_reached" };
    const totals = this.sql.exec<{ cost: number; profit: number }>("SELECT COALESCE(SUM(cost),0) cost,COALESCE(SUM(ABS(profit)),0) profit FROM community_bazaar_realizations WHERE owner=?", identity.accountId).one();
    if (totals.cost + fresh.reduce((sum, event) => sum + event.cost, 0) > 9e13 || totals.profit + fresh.reduce((sum, event) => sum + Math.abs(event.profit), 0) > 9e13) return { ok: false, error: "unsafe_aggregate" };
    const period = bazaarPeriod(raw.timezone, now);
    complete();
    for (const event of fresh) {
      this.sql.exec("INSERT INTO community_bazaar_realizations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", event.id, identity.accountId, identity.uuid, event.fingerprint,
        event.profit, event.cost, event.proceeds, event.fees, event.durationMs, event.completed ? 1 : 0, event.observedAt, now, event.quality,
        period.timezone, period.weekStart, period.weekEnd, period.monthStart, period.monthEnd);
      accepted.push(event.id);
    }
    return { ok: true, version: 1, accepted, duplicates, promoted, receivedAt: now, ...period,
      attribution: "Server receipt time; late/offline sales enter the current period. Timezone attribution is immutable.", verification: "Unverified client observations; not Hypixel-certified" };
  }
  remove(identity: Identity, raw: Record<string, unknown>) {
    if (raw.version !== 1 || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "ids"]) || !Array.isArray(raw.ids) || raw.ids.length < 1 || raw.ids.length > 32 ||
      raw.ids.some(value => typeof value !== "string" || !hex.test(value))) return { ok: false, error: "invalid_contribution_removal" };
    const ids = [...new Set(raw.ids as string[])], condition = `owner=? AND uuid=? AND id IN (${ids.map(() => "?").join(",")})`;
    const rows = this.sql.exec<{ n: number }>(`SELECT COUNT(*) n FROM community_bazaar_realizations WHERE ${condition}`, identity.accountId, identity.uuid, ...ids).one().n;
    if (rows) this.erase(condition, [identity.accountId, identity.uuid, ...ids]);
    return { ok: true, version: 1, removed: ids, count: rows };
  }
  /** Opt-out erases attributable profit; independent replay publications/settings remain explicit separate actions. */
  purge(owner: string | null, player?: string) {
    if (owner) this.erase("owner=?", [owner]);
    if (player) this.erase("uuid=?", [player]);
  }
  private erase(condition: string, bindings: SqlStorageValue[]) {
    const now = Date.now(); this.pruneTombstones(now);
    // Eight days cover the seven-day upload window; no financial or identity fields survive removal.
    this.sql.exec(`INSERT OR IGNORE INTO community_bazaar_tombstones SELECT fingerprint,? FROM community_bazaar_realizations WHERE ${condition}`, now + 8 * 86400000, ...bindings);
    this.sql.exec(`DELETE FROM community_bazaar_realizations WHERE ${condition}`, ...bindings);
  }
  private pruneTombstones(now: number) {
    if (now < this.nextTombstonePrune) return;
    this.sql.exec("DELETE FROM community_bazaar_tombstones WHERE expires_at<=?", now);
    this.nextTombstonePrune = now + 3600000;
  }
  private allowed(now: number, period: string) {
    return `WITH allowed AS (SELECT c.*,COALESCE(m.account_id,'uuid:'||c.uuid) identity,COALESCE(m.name,'Minecraft player') name,
      CASE WHEN COALESCE(json_extract(s.policy,'$.publicName'),0)=1 AND COALESCE(json_extract(p.policy,'$.publicName'),0)=1 THEN 1 ELSE 0 END named
      FROM community_bazaar_realizations c LEFT JOIN minecraft_accounts m ON m.uuid=c.uuid
      LEFT JOIN community_sharing s ON s.owner=c.owner LEFT JOIN community_sharing p ON p.owner=COALESCE(m.account_id,'uuid:'||c.uuid)
      WHERE c.received_at<=${now} ${period === "weekly" ? `AND c.week_end>${now}` : period === "monthly" ? `AND c.month_end>${now}` : ""}
      AND COALESCE(json_extract(s.policy,'$.enabled'),1)=1 AND COALESCE(json_extract(s.policy,'$.economy'),1)=1 AND COALESCE(json_extract(s.policy,'$.leaderboards'),1)=1
      AND COALESCE(json_extract(p.policy,'$.enabled'),1)=1 AND COALESCE(json_extract(p.policy,'$.economy'),1)=1 AND COALESCE(json_extract(p.policy,'$.leaderboards'),1)=1
      AND c.observed_at>COALESCE(json_extract(s.cutoffs,'$.economy'),0) AND c.observed_at>COALESCE(json_extract(s.cutoffs,'$.leaderboards'),0)
      AND c.observed_at>COALESCE(json_extract(p.cutoffs,'$.economy'),0) AND c.observed_at>COALESCE(json_extract(p.cutoffs,'$.leaderboards'),0)
      AND NOT EXISTS(SELECT 1 FROM community_blacklist b WHERE b.removed_at IS NULL AND (b.kind='minecraft' AND b.identity=c.uuid OR b.kind='discord' AND b.identity IN
        (SELECT discord_id FROM sky_accounts WHERE account_id=c.owner OR account_id=m.account_id))))`;
  }
  query(raw: LeaderboardQuery, now = Date.now()): LeaderboardResult {
    const period = raw.period ?? "all-time", identity = raw.identity ?? "uuid", page = Number(raw.page ?? 1), limit = Number(raw.limit ?? 10), search = raw.search ?? "";
    if (!["all-time", "weekly", "monthly"].includes(String(period)) || !["uuid", "linked"].includes(String(identity)) || !Number.isSafeInteger(now) || now < 1 ||
      !Number.isSafeInteger(page) || page < 1 || page > 100 || !Number.isSafeInteger(limit) || limit < 1 || limit > 25 || typeof search !== "string" || search.length > 100 || /[\x00-\x1f\x7f]/.test(search)) return { ok: false, error: "invalid_leaderboard" };
    const grouped = `${this.allowed(now, String(period))},scored AS(SELECT ${identity === "linked" ? "identity" : "uuid"} subject,
      CASE WHEN MIN(named)=1 THEN MIN(name)${identity === "linked" ? "||' · linked identity'" : ""} ELSE 'Anonymous player' END player,
      SUM(profit) value,COUNT(*) sampleSize,SUM(cost) cost,SUM(duration) durationMs,SUM(completed) completedFlips,MAX(received_at) updatedAt
      FROM allowed GROUP BY ${identity === "linked" ? "identity" : "uuid"}),ranked AS(SELECT *,RANK() OVER(ORDER BY value DESC) rank FROM scored) `;
    const total = this.sql.exec<{ n: number; at: number | null }>(`${grouped}SELECT COUNT(*) n,MAX(updatedAt) at FROM ranked WHERE INSTR(LOWER(player),?)>0`, search.toLowerCase()).one();
    const entries = this.sql.exec<{ player: string; value: number; sampleSize: number; rank: number; updatedAt: number; cost: number; durationMs: number; completedFlips: number }>(
      `${grouped}SELECT player,value,sampleSize,rank,updatedAt,cost,durationMs,completedFlips FROM ranked WHERE INSTR(LOWER(player),?)>0 ORDER BY value DESC,subject LIMIT ? OFFSET ?`, search.toLowerCase(), limit, (page - 1) * limit).toArray();
    return { ok: true, version: 1, category: "economy", metric: "bazaar_profit", period: String(period), identity: String(identity), startAt: null, endAt: now,
      metricLabel: "Observed realized Bazaar profit", description: "Actual net sale proceeds less allocated observed acquisition cost. Includes losses and supported partial sales.",
      unit: "coins", aggregation: "event-sum", available: true, total: total.n, updatedAt: total.at ?? 0, page, limit, search, hasNext: page * limit < total.n,
      source: "Unverified observed/corroborated client reconciliations; not Hypixel-certified", coverage: "Consenting Sky-Myce players. Sunday local weeks and local months use immutable receipt-time attribution; timezone changes affect future sales.",
      entries: entries.map(entry => ({ ...entry, roi: entry.cost > 0 ? entry.value / entry.cost : null, profitPerHour: entry.durationMs > 0 ? entry.value * 3600000 / entry.durationMs : null,
        source: "client-observation", verification: "unverified · observed/corroborated", durationDefinition: "Sum of supported realized-portion durations; overlapping flips are not wall-clock session hours" })) };
  }
  stats(identity: Identity, raw: Record<string, unknown>, now = Date.now()): BazaarStatsResult {
    const period = raw.period ?? "all-time";
    if (!fields(raw, ["period"]) || !["all-time", "weekly", "monthly"].includes(String(period))) return { ok: false, error: "invalid_period" };
    const value = this.sql.exec<{ profit: number; cost: number; durationMs: number; completedFlips: number; realizations: number }>(`${this.allowed(now, String(period))}
      SELECT COALESCE(SUM(profit),0) profit,COALESCE(SUM(cost),0) cost,COALESCE(SUM(duration),0) durationMs,COALESCE(SUM(completed),0) completedFlips,COUNT(*) realizations FROM allowed WHERE identity=?`, identity.accountId).one();
    return { ok: true, version: 1, period: String(period), ...value, roi: value.cost > 0 ? value.profit / value.cost : null,
      profitPerHour: value.durationMs > 0 ? value.profit * 3600000 / value.durationMs : null, coverage: "Only your eligible shared realized portions, not private local history.",
      verification: "Unverified client observations", sessionSummary: this.sql.exec<{ data: string; received_at: number }>("SELECT data,received_at FROM community_bazaar_summary WHERE owner=?", identity.accountId).toArray().map(row => ({ ...JSON.parse(row.data) as Omit<SessionSummary, "receivedAt">, receivedAt: row.received_at }))[0] ?? null,
      publications: this.sql.exec<{ id: string; token: string; createdAt: number }>("SELECT id,token,created_at createdAt FROM community_bazaar_replays WHERE owner=? AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 10", identity.accountId).toArray() };
  }
  summary(identity: Identity, raw: Record<string, unknown>) {
    const value = raw.summary;
    if (raw.version === 1 && raw.confirm === true && value === null && fields(raw, ["version", "expectedAccountId", "expectedUuid", "confirm", "summary"])) {
      this.sql.exec("DELETE FROM community_bazaar_summary WHERE owner=?", identity.accountId); return { ok: true };
    }
    if (raw.version !== 1 || raw.confirm !== true || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "confirm", "summary"]) ||
      !object(value) || Object.keys(value).length !== 3 || !fields(value, ["durationMs", "realizedProfit", "completedFlips"]) || !money(value.realizedProfit) ||
      !Number.isSafeInteger(value.durationMs) || Number(value.durationMs) <= 0 || Number(value.durationMs) > 86400000 ||
      !Number.isSafeInteger(value.completedFlips) || Number(value.completedFlips) < 0 || Number(value.completedFlips) > 10000) return { ok: false, error: "invalid_summary" };
    this.sql.exec("INSERT INTO community_bazaar_summary VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET data=excluded.data,received_at=excluded.received_at", identity.accountId, JSON.stringify(value), Date.now());
    return { ok: true };
  }
  settings(identity: Identity, raw?: Record<string, unknown>) {
    const previous = this.sql.exec<{ data: string; revision: number }>("SELECT data,revision FROM community_bazaar_settings WHERE owner=?", identity.accountId).toArray()[0];
    if (!raw) return { ok: true, version: 1, revision: previous?.revision ?? 0, preferences: previous ? JSON.parse(previous.data) : null };
    if (raw.version === 1 && raw.syncEnabled === false && fields(raw, ["version", "expectedAccountId", "expectedUuid", "syncEnabled"])) {
      if (previous?.data === "null") return { ok: true, version: 1, revision: previous.revision, preferences: null };
      // Keep only a revision fence: a delayed initial CAS(0) must not recreate a copy after explicit disable.
      const revision = (previous?.revision ?? 0) + 1;
      this.sql.exec("INSERT INTO community_bazaar_settings VALUES(?,'null',?) ON CONFLICT(owner) DO UPDATE SET data='null',revision=excluded.revision", identity.accountId, revision);
      return { ok: true, version: 1, revision, preferences: null };
    }
    const prefs = raw.preferences;
    if (raw.version !== 1 || raw.syncEnabled !== true || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "syncEnabled", "revision", "preferences"]) ||
      raw.revision !== (previous?.revision ?? 0) || !object(prefs) ||
      Object.entries(prefs).some(([key, value]) => !validBazaarPreference(key, value))) return { ok: false, error: "invalid_settings_or_revision" };
    const revision = (previous?.revision ?? 0) + 1;
    this.sql.exec("INSERT INTO community_bazaar_settings VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET data=excluded.data,revision=excluded.revision", identity.accountId, JSON.stringify(prefs), revision);
    return { ok: true, version: 1, revision, preferences: prefs };
  }
  publish(identity: Identity, raw: Record<string, unknown>, now = Date.now()) {
    if (raw.version !== 1 || raw.confirm !== true || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "confirm", "replay"])) return { ok: false, error: "explicit_publication_required" };
    const replay = redactBazaarReplay(raw.replay, identity.name, this.hooks.check(identity.uuid, "economy").publicName);
    if (!replay) return { ok: false, error: "invalid_replay" };
    this.pruneReplays(identity.accountId);
    if (this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_bazaar_replays WHERE owner=? AND revoked_at IS NULL", identity.accountId).one().n >= 10) return { ok: false, error: "replay_capacity_reached" };
    const id = crypto.randomUUID().replaceAll("-", ""), token = randomToken();
    this.sql.exec("INSERT INTO community_bazaar_replays VALUES(?,?,?,?,?,?,?,NULL)", id, token, identity.accountId, identity.uuid, JSON.stringify(replay), replay.visibility === "community" ? 1 : 0, now);
    return { ok: true, version: 1, id, token, path: `/community/bazaar/replays/${token}`, visibility: replay.visibility };
  }
  revoke(identity: Identity, raw: Record<string, unknown>, remove: boolean) {
    if (raw.version !== 1 || !fields(raw, ["version", "expectedAccountId", "expectedUuid", "id"]) || typeof raw.id !== "string" || !hex.test(raw.id)) return { ok: false, error: "invalid_replay" };
    if (remove) this.sql.exec("DELETE FROM community_bazaar_replays WHERE id=? AND owner=?", raw.id, identity.accountId);
    else this.sql.exec("UPDATE community_bazaar_replays SET revoked_at=?,listed=0,data='{}' WHERE id=? AND owner=? AND revoked_at IS NULL", Date.now(), raw.id, identity.accountId);
    this.pruneReplays(identity.accountId);
    return { ok: true };
  }
  private pruneReplays(owner: string) {
    this.sql.exec(`DELETE FROM community_bazaar_replays WHERE owner=? AND revoked_at IS NOT NULL AND id NOT IN
      (SELECT id FROM community_bazaar_replays WHERE owner=? AND revoked_at IS NOT NULL ORDER BY revoked_at DESC,id DESC LIMIT 10)`, owner, owner);
  }
  private replays(where: string, ...bindings: SqlStorageValue[]) {
    return this.sql.exec<{ token: string; data: string; named: number; currentOwner: string | null; owner: string }>(`SELECT r.token,r.data,r.owner,m.account_id currentOwner,
      COALESCE(json_extract(s.policy,'$.publicName'),0) named FROM community_bazaar_replays r LEFT JOIN community_sharing s ON s.owner=r.owner
      LEFT JOIN minecraft_accounts m ON m.uuid=r.uuid WHERE r.revoked_at IS NULL AND ${where}`, ...bindings).toArray().map(row => {
      const replay = JSON.parse(row.data) as Replay;
      // Name visibility can be withdrawn independently of publication. Do not resurrect a stored public name.
      if (!row.named || row.currentOwner !== row.owner) delete replay.identity;
      return { token: row.token, replay };
    });
  }
  replay(token: string) {
    const row = /^[A-Za-z0-9_-]{43}$/.test(token) ? this.replays("r.token=?", token)[0] : undefined;
    return row ? { ok: true, version: 1, replay: row.replay } : { ok: false, error: "replay_unavailable" };
  }
  gallery() {
    return { ok: true, version: 1, entries: this.replays("r.listed=1 ORDER BY r.created_at DESC LIMIT 25").map(({ token, replay }) => ({
      path: `/community/bazaar/replays/${token}`, durationMs: replay.durationMs, completedFlips: replay.completedFlips, ...replay.identity ? { identity: replay.identity } : {},
      ...replay.realizedProfit !== undefined ? { realizedProfit: replay.realizedProfit } : {} })) };
  }
}
