import type { AccountStore } from "./accounts";
import type { DeveloperActor } from "./licensing";
import type { SharingCategory, SharingDecision } from "./community-sharing";
import { validatedPublicRngEvent, type PublicRngEvent } from "./digest";
import { randomToken } from "./drm";

export type LeaderboardQuery = { category?: unknown; metric?: unknown; period?: unknown; page?: unknown; limit?: unknown; search?: unknown };
type Category = "economy" | "dungeons" | "rng" | "other";
type Metric = { category: Category; privacy: SharingCategory; label: string; description: string; unit: string; aggregation: "latest-snapshot" | "event-sum" | "event-average"; available?: false; reason?: string };
export const leaderboardMetrics: Record<string, Metric> = {
  networth: { category: "economy", privacy: "economy", label: "Estimated net worth", description: "Latest complete shared valuation observed in this period; not net-worth gain.", unit: "coins", aggregation: "latest-snapshot" },
  purse: { category: "economy", privacy: "economy", label: "Purse balance", description: "Latest complete shared purse snapshot observed in this period; not income.", unit: "coins", aggregation: "latest-snapshot" },
  bank: { category: "economy", privacy: "economy", label: "Bank balance", description: "Latest complete shared bank snapshot observed in this period; not income.", unit: "coins", aggregation: "latest-snapshot" },
  dungeon_runs: { category: "dungeons", privacy: "dungeons", label: "Recorded dungeon completions", description: "New timestamped completed-run records; legacy aggregate totals are excluded.", unit: "runs", aggregation: "event-sum" },
  dungeon_profit: { category: "dungeons", privacy: "dungeons", label: "Estimated recorded dungeon profit", description: "Sum of newly recorded chest profit estimates, including losses; legacy aggregate totals are excluded.", unit: "coins", aggregation: "event-sum" },
  dungeon_run_time: { category: "dungeons", privacy: "dungeons", label: "Average Run Time", description: "Average newly completed timed runs across all floors. Unknown start times and historical totals are excluded. Faster times rank first.", unit: "milliseconds", aggregation: "event-average" },
  rng_drops: { category: "rng", privacy: "rng", label: "Supported reported RNG drops", description: "Count of newly accepted supported community drop reports; not official drop probability or every Hypixel drop.", unit: "drops", aggregation: "event-sum" },
  completed_tasks: { category: "other", privacy: "activity", label: "Completed tasks", description: "Would require timestamped completed-task records.", unit: "tasks", aggregation: "event-sum", available: false, reason: "No reliable completed-task publisher exists. This metric is unavailable until genuine completion records are collected." },
};
export function utcPeriodStart(period: string, now: number): number | null {
  if (period === "all-time") return null;
  const date = new Date(now);
  if (period === "monthly") return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
  const midnight = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  return midnight - ((date.getUTCDay() + 6) % 7) * 86400000;
}
export type LeaderboardResult = { ok: boolean; error?: string; version?: 1; category?: string; metric?: string; period?: string; startAt?: number | null;
  endAt?: number; metricLabel?: string; description?: string; unit?: string; aggregation?: string; source?: string; coverage?: string;
  available?: boolean; reason?: string; updatedAt?: number; total?: number; page?: number; limit?: number; search?: string; hasNext?: boolean;
  entries?: { rank: number; player: string; value: number; sampleSize: number; updatedAt: number; source: string; verification: string }[] };
export type JournalComparisonQuery = { period?: unknown };
export type JournalComparisonResult = { ok: boolean; error?: string; version?: 1; metric?: "rng_drops"; period?: string; startAt?: number | null;
  endAt?: number; participantCount?: number; totalDrops?: number; meanPerIdentity?: number | null; ownSharedCount?: number | null;
  ownAvailable?: boolean; ownUnavailableReason?: "sharing_disabled"; updatedAt?: number; source?: string; coverage?: string };
const sourceDescription = "Explicitly shared client observations; API-derived client snapshots remain unverified; coin valuations are estimates";
const coverageDescription = "Consenting Sky-Myce participants only; each Minecraft identity is ranked separately; this is not all Hypixel players";
type FeedRow = { id: string; author: string; event: string; received_at: number; next_at: number; attempts: number; claim: string | null;
  claim_until: number; channel: string | null; message_id: string | null; suppressed: number };
export type FeedDelivery = { id: string; claim: string; operation: "publish" | "delete"; event?: PublicRngEvent; channelId?: string; messageId?: string };
export type FeedReceipt = { id: string; messageId: string };
type Hooks = { check(uuid: string, category: SharingCategory, at?: number): SharingDecision;
  record(uuid: string, raw: unknown, name: string): { ok: boolean; error?: string }; };

/** One persistent global source serves the mod, Discord and future read-only consumers. */
export class CommunityLeaderboards {
  constructor(private sql: SqlStorage, private hooks: Hooks) {
    sql.exec(`CREATE TABLE IF NOT EXISTS community_rng_delivery(id TEXT PRIMARY KEY,author TEXT NOT NULL,event TEXT NOT NULL,
      received_at INTEGER NOT NULL,next_at INTEGER NOT NULL,attempts INTEGER NOT NULL,claim TEXT,claim_until INTEGER NOT NULL,
      channel TEXT,message_id TEXT,suppressed INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS community_rng_delivery_due ON community_rng_delivery(next_at,claim_until);
      CREATE INDEX IF NOT EXISTS community_rng_delivery_author ON community_rng_delivery(author);
      CREATE TABLE IF NOT EXISTS community_feed_nonces(nonce TEXT PRIMARY KEY,expires_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS community_rng_sources(author TEXT NOT NULL,source_id TEXT NOT NULL,event_id TEXT NOT NULL,
        received_at INTEGER NOT NULL,PRIMARY KEY(author,source_id));
      CREATE TABLE IF NOT EXISTS community_rng_scan(channel TEXT PRIMARY KEY,cursor TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS community_rng_scan_order ON community_rng_delivery(channel,id) WHERE message_id IS NOT NULL AND suppressed=0`);
  }
  /** Repair count rows erased by the old same-owner refresh bug; never enqueue or repost accepted reports. */
  restoreAcceptedRng(author: string, sessionId: string, now = Date.now()): number {
    if (!/^[a-f0-9]{32}$/.test(author)) return 0;
    // Refresh may change a session's UUID: prove its original link with the retained flow or durable connect audit.
    // Missing proof stays unavailable. The accepted source tuple supplies the private acquisition ID absent from public events.
    const rows = this.sql.exec<{ source_id: string; event_id: string; received_at: number; event: string; account_id: string }>(`
      WITH identity AS (SELECT c.account_id,c.uuid,c.created_at,COALESCE(
        (SELECT c.created_at FROM community_connect_flows f WHERE f.session_id=c.session_id AND f.account_id=c.account_id
          AND f.uuid=c.uuid AND f.cancelled_at IS NULL LIMIT 1),
        (SELECT MAX(p.at) FROM account_audit p WHERE p.account_id=c.account_id AND p.action='community_connected'
          AND json_extract(p.metadata,'$.uuid')=c.uuid AND p.at<=c.created_at)) proof_at
        FROM community_sessions c JOIN minecraft_accounts m ON m.uuid=c.uuid AND m.account_id=c.account_id
        JOIN sky_accounts a ON a.account_id=c.account_id AND a.generation=c.generation
        WHERE c.session_id=? AND c.uuid=? AND c.revoked_at IS NULL AND c.access_expires_at>? AND c.refresh_expires_at>?)
      SELECT s.source_id,s.event_id,s.received_at,d.event,c.account_id FROM identity c JOIN community_rng_sources s ON s.author=c.uuid
      JOIN community_rng_delivery d ON d.id=s.event_id AND d.author=s.author AND d.suppressed=0
      WHERE c.proof_at IS NOT NULL AND c.created_at<=s.received_at AND s.received_at<=?
      AND NOT EXISTS(SELECT 1 FROM account_audit x WHERE x.account_id=c.account_id AND x.at>=c.proof_at
        AND x.action IN ('community_uuid_reconnected','community_uuid_unlinked','minecraft_uuid_removed','account_unlink')
        AND (json_extract(x.metadata,'$.uuid')=s.author OR x.action='account_unlink' AND json_extract(x.metadata,'$.uuid') IS NULL))
      AND NOT EXISTS(SELECT 1 FROM community_contributions p WHERE p.uuid=s.author AND p.id=s.source_id)
      ORDER BY s.received_at DESC,s.source_id DESC LIMIT 100`, sessionId, author, now, now, now).toArray();
    if (!rows.length) return 0;
    let remaining = 100000 - this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_contributions").one().n;
    let restored = 0;
    for (const row of rows) {
      if (remaining <= 0) break;
      let event: PublicRngEvent | null;
      try { event = validatedPublicRngEvent(JSON.parse(row.event)); } catch { continue; }
      if (!event || event.id !== row.event_id || !/^[a-f0-9]{32}$/.test(row.source_id) ||
        !Number.isSafeInteger(row.received_at) || row.received_at <= 0 ||
        event.occurredAt <= row.received_at - 300000 || event.occurredAt > row.received_at + 60000) continue;
      const rng = this.hooks.check(author, "rng", event.occurredAt);
      if (!rng.allowed || !this.hooks.check(author, "rng", row.received_at).allowed ||
        !this.hooks.check(author, "leaderboards", event.occurredAt).allowed ||
        !this.hooks.check(author, "leaderboards", row.received_at).allowed) continue;
      this.sql.exec("INSERT OR IGNORE INTO community_contributions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", row.source_id, row.account_id,
        author, "rng", "rng_drops", 1, "drops", event.occurredAt, row.received_at, "client-observation", "unverified", 1, rng.revision);
      remaining--; restored++;
    }
    return restored;
  }
  query(raw: LeaderboardQuery, now = Date.now()): LeaderboardResult {
    const category = typeof raw.category === "string" ? raw.category : "economy";
    const metricId = typeof raw.metric === "string" ? raw.metric : ({ economy: "networth", dungeons: "dungeon_runs", rng: "rng_drops", other: "completed_tasks" } as Record<string, string>)[category];
    const metric = metricId && Object.hasOwn(leaderboardMetrics, metricId) ? leaderboardMetrics[metricId] : undefined;
    const period = raw.period ?? "all-time", page = Number(raw.page ?? 1), limit = Number(raw.limit ?? 10), search = raw.search ?? "";
    if (!metric || metric.category !== category || !["all-time", "weekly", "monthly"].includes(String(period)) || !Number.isSafeInteger(page) || page < 1 || page > 100 ||
      !Number.isSafeInteger(limit) || limit < 1 || limit > 25 || typeof search !== "string" || search.length > 100 || /[\x00-\x1f\x7f]/.test(search)) return { ok: false, error: "invalid_leaderboard" };
    const startAt = utcPeriodStart(String(period), now), base: LeaderboardResult = { ok: true, version: 1, category, metric: metricId, period: String(period), startAt, endAt: now,
      metricLabel: metric.label, description: metric.description, unit: metric.unit, aggregation: metric.aggregation, available: metric.available !== false,
      source: sourceDescription, coverage: coverageDescription,
      page, limit, search, updatedAt: 0, total: 0, hasNext: false, entries: [], ...(metric.reason ? { reason: metric.reason } : {}) };
    if (metric.available === false) return base;
    const ranked = this.rankedDataset(metric);
    const parameters = [metricId, startAt ?? 0, now, search.toLowerCase()];
    const totals = this.sql.exec<{ total: number; updatedAt: number | null }>(`${ranked}SELECT COUNT(*) total,MAX(updatedAt) updatedAt FROM ranked WHERE INSTR(LOWER(player),?)>0`, ...parameters).one();
    const entries = this.sql.exec<{ uuid: string; rank: number; player: string; value: number; sampleSize: number; updatedAt: number; source: string; verification: string }>(
      `${ranked}SELECT * FROM ranked WHERE INSTR(LOWER(player),?)>0 ORDER BY value ${metric.aggregation === "event-average" ? "ASC" : "DESC"},uuid ASC LIMIT ? OFFSET ?`, ...parameters, limit, (page - 1) * limit).toArray();
    const visible = entries.filter(entry => this.hooks.check(entry.uuid, metric.privacy, entry.updatedAt).allowed && this.hooks.check(entry.uuid, "leaderboards", entry.updatedAt).allowed)
      .map(({ uuid: playerId, ...entry }) => ({ ...entry, player: this.hooks.check(playerId, metric.privacy).publicName ? entry.player : "Anonymous player" }));
    return { ...base, total: totals.total, updatedAt: totals.updatedAt ?? 0, entries: visible, hasNext: page * limit < totals.total };
  }
  /** Aggregates exactly the ranking dataset, never client-supplied identities or private journal records. */
  journalComparison(player: string, raw: JournalComparisonQuery, now = Date.now()): JournalComparisonResult {
    if (!/^[a-f0-9]{32}$/.test(player) || !raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some(key => key !== "period") ||
      raw.period !== undefined && (typeof raw.period !== "string" || !["all-time", "weekly", "monthly"].includes(raw.period))) return { ok: false, error: "invalid_journal_comparison" };
    const period = raw.period ?? "all-time", startAt = utcPeriodStart(String(period), now);
    const values = this.sql.exec<{ participantCount: number; totalDrops: number; ownSharedCount: number; updatedAt: number | null }>(
      `${this.rankedDataset(leaderboardMetrics.rng_drops)}SELECT COUNT(*) participantCount,COALESCE(SUM(value),0) totalDrops,
       COALESCE(SUM(CASE WHEN uuid=? THEN value ELSE 0 END),0) ownSharedCount,MAX(updatedAt) updatedAt FROM ranked`, "rng_drops", startAt ?? 0, now, player).one();
    if (![values.participantCount, values.totalDrops, values.ownSharedCount, values.updatedAt ?? 0].every(value => Number.isSafeInteger(value) && value >= 0)) return { ok: false, error: "comparison_unavailable" };
    const ownAvailable = this.hooks.check(player, "rng").allowed && this.hooks.check(player, "leaderboards").allowed;
    return { ok: true, version: 1, metric: "rng_drops", period: String(period), startAt, endAt: now,
      participantCount: values.participantCount, totalDrops: values.totalDrops,
      meanPerIdentity: values.participantCount ? values.totalDrops / values.participantCount : null,
      ownSharedCount: ownAvailable ? values.ownSharedCount : null, ownAvailable,
      ...(ownAvailable ? {} : { ownUnavailableReason: "sharing_disabled" as const }), updatedAt: values.updatedAt ?? 0,
      source: sourceDescription, coverage: coverageDescription };
  }
  private rankedDataset(metric: Metric): string {
    // Policy is checked inside this same SQLite turn, before grouping/ranking. Anonymous names are never searchable by private username.
    const rngValidation = metric === leaderboardMetrics.rng_drops ? `AND c.category='rng' AND c.unit='drops' AND c.value>=0 AND c.value<=9007199254740991
      AND c.value=CAST(c.value AS INTEGER) AND c.source IN ('client-observation','public-api-client') AND c.verification IN ('unverified','estimated')` : "";
    const timeValidation = metric === leaderboardMetrics.dungeon_run_time ? `AND c.category='dungeons' AND c.unit='milliseconds' AND c.value>0 AND c.value<=86400000
      AND c.value=CAST(c.value AS INTEGER) AND c.source IN ('client-observation','public-api-client') AND c.verification IN ('unverified','estimated')` : "";
    const allowed = `WITH allowed AS (SELECT c.*,COALESCE(m.name,'Minecraft player') name,
      CASE WHEN COALESCE(json_extract(s.policy,'$.publicName'),0)=1 AND COALESCE(json_extract(p.policy,'$.publicName'),0)=1 THEN 1 ELSE 0 END public_name
      FROM community_contributions c LEFT JOIN community_sharing s ON s.owner=c.owner LEFT JOIN minecraft_accounts m ON m.uuid=c.uuid
      LEFT JOIN community_sharing p ON p.owner=COALESCE(m.account_id,'uuid:'||c.uuid)
      WHERE c.metric=? AND c.observed_at>=? AND c.observed_at<=? AND c.complete=1 ${rngValidation} ${timeValidation}
      AND COALESCE(json_extract(s.policy,'$.enabled'),1)=1 AND COALESCE(json_extract(s.policy,'$.leaderboards'),1)=1
      AND COALESCE(json_extract(s.policy,'$.${metric.privacy}'),1)=1
      AND c.observed_at>COALESCE(json_extract(s.cutoffs,'$.${metric.privacy}'),0) AND c.observed_at>COALESCE(json_extract(s.cutoffs,'$.leaderboards'),0)
      AND COALESCE(json_extract(p.policy,'$.enabled'),1)=1 AND COALESCE(json_extract(p.policy,'$.leaderboards'),1)=1
      AND COALESCE(json_extract(p.policy,'$.${metric.privacy}'),1)=1
      AND c.observed_at>COALESCE(json_extract(p.cutoffs,'$.${metric.privacy}'),0) AND c.observed_at>COALESCE(json_extract(p.cutoffs,'$.leaderboards'),0)
      AND NOT EXISTS(SELECT 1 FROM community_blacklist b WHERE b.removed_at IS NULL AND (b.kind='minecraft' AND b.identity=c.uuid OR b.kind='discord' AND b.identity IN
        (SELECT discord_id FROM sky_accounts WHERE account_id=c.owner OR account_id=m.account_id))))`;
    const grouped = metric.aggregation === "latest-snapshot" ? `, latest AS (SELECT *,ROW_NUMBER() OVER(PARTITION BY uuid ORDER BY observed_at DESC,received_at DESC,id DESC) row FROM allowed),
      scored AS (SELECT uuid,CASE WHEN public_name=1 THEN name ELSE 'Anonymous player' END player,value,1 sampleSize,observed_at updatedAt,source,verification FROM latest WHERE row=1)` :
      `, scored AS (SELECT uuid,CASE WHEN MAX(public_name)=1 THEN MAX(name) ELSE 'Anonymous player' END player,${metric.aggregation === "event-average" ? "AVG" : "SUM"}(value) value,COUNT(*) sampleSize,MAX(observed_at) updatedAt,
       CASE WHEN MIN(source)=MAX(source) THEN MIN(source) ELSE 'mixed-client-reports' END source,
       CASE WHEN MIN(verification)=MAX(verification) THEN MIN(verification) ELSE 'unverified / estimated' END verification FROM allowed GROUP BY uuid)`;
    return `${allowed}${grouped}, ranked AS (SELECT *,RANK() OVER(ORDER BY value ${metric.aggregation === "event-average" ? "ASC" : "DESC"}) rank FROM scored) `;
  }
  /** Called only after the existing RNG validator accepts a new report from a verified relay session. */
  acceptRng(author: string, event: PublicRngEvent, sourceId: string, channel: string | undefined): { ok: boolean; error?: string; warning?: string } {
    const publicEvent = validatedPublicRngEvent(event);
    if (!/^[a-f0-9]{32}$/.test(author) || !publicEvent ||
      !Number.isSafeInteger(event.occurredAt) || event.occurredAt <= Date.now() - 300000 || event.occurredAt > Date.now() + 60000 ||
      !/^[A-Z0-9_]{1,80}$/.test(event.item) || !/^[a-f0-9]{32}$/.test(sourceId) || !["dungeon", "slayer", "kuudra", "fishing"].includes(event.activity)) return { ok: false, error: "invalid_event" };
    event = publicEvent;
    const consent = this.hooks.check(author, "rng", event.occurredAt);
    if (!consent.allowed) return { ok: false, error: "sharing_disabled" };
    if (this.sql.exec("SELECT event_id FROM community_rng_sources WHERE author=? AND source_id=?", author, sourceId).toArray().length) return { ok: true };
    if (this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_rng_sources").one().n >= 100000) return { ok: false, error: "rng_archive_full" };
    const name = event.player !== "Anonymous" && consent.publicName && /^[A-Za-z0-9_]{1,16}$/.test(event.player) ? event.player : "Anonymous";
    const contribution = this.hooks.record(author, { id: sourceId, metric: "rng_drops", value: 1, observedAt: event.occurredAt, complete: true, source: "client-observation", verification: "unverified" },
      this.sql.exec<{ name: string }>("SELECT name FROM minecraft_accounts WHERE uuid=?", author).toArray()[0]?.name ?? "MinecraftPlayer");
    const warning = contribution.error === "contribution_capacity_reached" ? "contribution_capacity_reached" : undefined;
    if (!channel || !/^\d{17,20}$/.test(channel)) {
      this.sql.exec("INSERT INTO community_rng_sources VALUES(?,?,?,?)", author, sourceId, event.id, Date.now());
      return { ok: true, ...(warning ? { warning } : {}) };
    }
    this.prune();
    if (this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_rng_delivery WHERE message_id IS NULL").one().n >= 500) return { ok: false, error: "rng_feed_busy" };
    if (this.sql.exec<{ n: number }>("SELECT COUNT(*) n FROM community_rng_delivery").one().n >= 100000) return { ok: false, error: "rng_archive_full" };
    this.sql.exec("INSERT INTO community_rng_sources VALUES(?,?,?,?)", author, sourceId, event.id, Date.now());
    this.sql.exec("INSERT OR IGNORE INTO community_rng_delivery VALUES(?,?,?,?,?,0,NULL,0,?,NULL,0)", event.id, author,
      JSON.stringify({ ...event, player: name }), Date.now(), Date.now(), channel);
    return { ok: true, ...(warning ? { warning } : {}) };
  }
  suppress(uuids: string[]): void {
    for (const player of uuids.slice(0, 256)) if (/^[a-f0-9]{32}$/.test(player)) this.sql.exec("UPDATE community_rng_delivery SET suppressed=1,next_at=0 WHERE author=?", player);
  }
  private prune(): void {
    const now = Date.now();
    // A lost ACK may hide a posted message: expire publication, but retain attempted deliveries for cancellation.
    this.sql.exec("UPDATE community_rng_delivery SET suppressed=1,next_at=0 WHERE message_id IS NULL AND attempts>0 AND received_at<?", now - 600000);
    this.sql.exec("DELETE FROM community_rng_delivery WHERE message_id IS NULL AND attempts=0 AND (received_at<? OR suppressed=1) AND claim_until<=?", now - 600000, now);
    this.sql.exec("DELETE FROM community_feed_nonces WHERE expires_at<=?", now);
  }
  acceptNonce(nonce: string): boolean {
    this.prune(); if (!/^[a-f0-9]{32}$/.test(nonce) || this.sql.exec("SELECT nonce FROM community_feed_nonces WHERE nonce=?", nonce).toArray().length) return false;
    this.sql.exec("INSERT INTO community_feed_nonces VALUES(?,?)", nonce, Date.now() + 120000); return true;
  }
  private scanDelivered(channel: string): void {
    const cursor = this.sql.exec<{ cursor: string }>("SELECT cursor FROM community_rng_scan WHERE channel=?", channel).toArray()[0]?.cursor ?? "";
    const selection = "SELECT * FROM community_rng_delivery WHERE channel=? AND message_id IS NOT NULL AND suppressed=0";
    const rows = this.sql.exec<FeedRow>(`${selection} AND id>? ORDER BY id LIMIT 100`, channel, cursor).toArray();
    if (rows.length < 100 && cursor) rows.push(...this.sql.exec<FeedRow>(`${selection} AND id<=? ORDER BY id LIMIT ?`, channel, cursor, 100 - rows.length).toArray());
    if (!rows.length) { this.sql.exec("DELETE FROM community_rng_scan WHERE channel=?", channel); return; }
    // Persist progress across polls/restarts, independently of retrying deletion claims.
    this.sql.exec("INSERT INTO community_rng_scan VALUES(?,?) ON CONFLICT(channel) DO UPDATE SET cursor=excluded.cursor", channel, rows.at(-1)!.id);
    for (const row of rows) {
      const event = JSON.parse(row.event) as PublicRngEvent, decision = this.hooks.check(row.author, "rng", event.occurredAt);
      if (!decision.allowed || event.player !== "Anonymous" && !decision.publicName) this.sql.exec("UPDATE community_rng_delivery SET suppressed=1,next_at=0 WHERE id=?", row.id);
    }
  }
  claim(channel: string, receipts: FeedReceipt[] = []): { ok: boolean; deliveries: FeedDelivery[]; acknowledged?: string[] } {
    this.prune(); this.scanDelivered(channel); const now = Date.now(), deliveries: FeedDelivery[] = [];
    const rows = this.sql.exec<FeedRow>("SELECT * FROM community_rng_delivery WHERE channel=? AND claim_until<=? AND (message_id IS NULL AND next_at<=? OR message_id IS NOT NULL AND suppressed=1) ORDER BY CASE WHEN suppressed=1 THEN 0 ELSE 1 END,received_at DESC LIMIT 100", channel, now, now).toArray();
    for (const row of rows) {
      const event = JSON.parse(row.event) as PublicRngEvent, decision = this.hooks.check(row.author, "rng", event.occurredAt);
      const suppressed = row.suppressed === 1 || !decision.allowed || event.player !== "Anonymous" && !decision.publicName;
      if (suppressed && !row.message_id && row.attempts === 0) { this.sql.exec("DELETE FROM community_rng_delivery WHERE id=?", row.id); continue; }
      if (row.message_id && !suppressed) continue;
      if (deliveries.length >= 3) break;
      const claim = randomToken();
      this.sql.exec("UPDATE community_rng_delivery SET claim=?,claim_until=?,attempts=attempts+1,next_at=?,suppressed=? WHERE id=?", claim, now + 60000, now + Math.min(60000, 1000 * 2 ** Math.min(row.attempts, 6)), suppressed ? 1 : 0, row.id);
      deliveries.push({ id: row.id, claim, channelId: channel, operation: suppressed ? "delete" : "publish", ...(suppressed ? (row.message_id ? { messageId: row.message_id } : {}) : { event }) });
    }
    const acknowledged = receipts.filter(receipt => this.sql.exec("SELECT id FROM community_rng_delivery WHERE id=? AND channel=? AND message_id=?", receipt.id, channel, receipt.messageId).toArray().length > 0).map(receipt => receipt.id);
    return { ok: true, deliveries, ...(receipts.length ? { acknowledged } : {}) };
  }
  acknowledge(raw: Record<string, unknown>, channel: string): { ok: boolean; error?: string; deleteRequired?: boolean } {
    if (typeof raw.id !== "string" || typeof raw.claim !== "string") return { ok: false, error: "invalid_ack" };
    const row = this.sql.exec<FeedRow>("SELECT * FROM community_rng_delivery WHERE id=? AND claim=? AND channel=?", raw.id, raw.claim, channel).toArray()[0];
    if (!row || row.claim_until < Date.now()) return { ok: false, error: "claim_expired" };
    if (raw.operation === "delete" && (row.message_id || row.suppressed === 1)) { this.sql.exec("DELETE FROM community_rng_delivery WHERE id=?", row.id); return { ok: true }; }
    if (raw.operation !== "publish" || typeof raw.messageId !== "string" || !/^\d{17,20}$/.test(raw.messageId)) return { ok: false, error: "invalid_ack" };
    const event = JSON.parse(row.event) as PublicRngEvent, decision = this.hooks.check(row.author, "rng", event.occurredAt);
    const suppressed = row.suppressed === 1 || !decision.allowed || event.player !== "Anonymous" && !decision.publicName;
    this.sql.exec("UPDATE community_rng_delivery SET message_id=?,suppressed=?,claim=NULL,claim_until=0,next_at=0 WHERE id=?", raw.messageId, suppressed ? 1 : 0, row.id);
    return { ok: true, ...(suppressed ? { deleteRequired: true } : {}) };
  }
}

type Config = { ACCOUNT_STORE: DurableObjectNamespace<AccountStore>; DISCORD_GUILD_ID?: string; DISCORD_BRIDGE_SECRET?: string; DISCORD_RNG_CHANNEL_ID?: string };
export async function handleLeaderboardsRequest(request: Request, env: Config): Promise<Response | null> {
  const url = new URL(request.url), comparison = url.pathname === "/community/journal-comparison";
  if (url.pathname !== "/community/leaderboard" && !comparison) return null;
  if (request.method !== "GET") return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405 });
  const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("Authorization") ?? "")?.[1];
  if (!token) return Response.json({ ok: false, error: "session_expired" }, { status: 401 });
  if (comparison) {
    if ([...url.searchParams.keys()].some(key => key !== "period") || url.searchParams.getAll("period").length > 1) return Response.json({ ok: false, error: "invalid_journal_comparison" }, { status: 400, headers: { "Cache-Control": "no-store" } });
    const result = await env.ACCOUNT_STORE.getByName("global").communityJournalComparison(token, Object.fromEntries(url.searchParams));
    return Response.json(result, { status: result.ok ? 200 : result.error === "invalid_journal_comparison" ? 400 : result.error === "comparison_unavailable" ? 503 : result.error === "rate_limited" ? 429 : 401, headers: { "Cache-Control": "no-store" } });
  }
  if ([...url.searchParams.keys()].some(key => !["category", "metric", "period", "page", "limit", "search"].includes(key))) return Response.json({ ok: false, error: "invalid_leaderboard" }, { status: 400 });
  const result = await env.ACCOUNT_STORE.getByName("global").communityLeaderboard(token, Object.fromEntries(url.searchParams));
  return Response.json(result, { status: result.ok ? 200 : result.error === "invalid_leaderboard" ? 400 : 401, headers: { "Cache-Control": "no-store" } });
}
/** Same bridge credential, distinct domain separator, exact body, short timestamp and persisted nonce. No bot token in the Worker. */
export async function handleCommunityFeed(request: Request, env: Config): Promise<Response | null> {
  const url = new URL(request.url); if (url.pathname !== "/discord/community-feed") return null;
  if (request.method !== "POST") return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405 });
  if (!env.DISCORD_RNG_CHANNEL_ID || !/^\d{17,20}$/.test(env.DISCORD_RNG_CHANNEL_ID)) return Response.json({ ok: false, error: "rng_feed_not_configured" }, { status: 503 });
  const time = request.headers.get("X-SkyMyce-Timestamp") ?? "", nonce = request.headers.get("X-SkyMyce-Nonce") ?? "", signature = request.headers.get("X-SkyMyce-Signature") ?? "";
  const length = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(length) && length > 4096 || !request.body || !env.DISCORD_BRIDGE_SECRET || env.DISCORD_BRIDGE_SECRET.length < 32 ||
    !/^\d{13}$/.test(time) || Math.abs(Date.now() - Number(time)) > 60000 || !/^[a-f0-9]{32}$/.test(nonce) || !/^[a-f0-9]{64}$/.test(signature)) return Response.json({ ok: false, error: "not_authorized" }, { status: 401 });
  const reader = request.body.getReader(), chunks: Uint8Array[] = []; let bytes = 0;
  try { while (true) { const next = await reader.read(); if (next.done) break; bytes += next.value.length; if (bytes > 4096) return Response.json({ ok: false, error: "invalid_request" }, { status: 413 }); chunks.push(next.value); } }
  finally { await reader.cancel(); }
  const body = new Uint8Array(bytes); let position = 0; for (const chunk of chunks) { body.set(chunk, position); position += chunk.length; }
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", body)), byte => byte.toString(16).padStart(2, "0")).join("");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.DISCORD_BRIDGE_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  if (!await crypto.subtle.verify("HMAC", key, Uint8Array.from(signature.match(/../g)!, part => parseInt(part, 16)),
    new TextEncoder().encode(`sky-myce-community-feed\nPOST\n${url.pathname}\n${time}\n${nonce}\n${hash}`))) return Response.json({ ok: false, error: "not_authorized" }, { status: 401 });
  let raw: Record<string, unknown>; try { raw = JSON.parse(new TextDecoder().decode(body)); } catch { return Response.json({ ok: false, error: "invalid_request" }, { status: 400 }); }
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some(key => !["action", "channelId", "id", "claim", "operation", "messageId", "receipts"].includes(key)) || raw.channelId !== env.DISCORD_RNG_CHANNEL_ID) return Response.json({ ok: false, error: "invalid_request" }, { status: 400 });
  if (raw.receipts !== undefined && (raw.action !== "claim" || !Array.isArray(raw.receipts) || raw.receipts.length > 32 || raw.receipts.some(receipt =>
    !receipt || typeof receipt !== "object" || Array.isArray(receipt) || Object.keys(receipt).some(key => !["id", "messageId"].includes(key)) ||
    typeof receipt.id !== "string" || !/^[a-f0-9]{32}$/.test(receipt.id) || typeof receipt.messageId !== "string" || !/^\d{17,20}$/.test(receipt.messageId)) ||
    new Set(raw.receipts.map(receipt => receipt.id)).size !== raw.receipts.length)) return Response.json({ ok: false, error: "invalid_request" }, { status: 400 });
  const store = env.ACCOUNT_STORE.getByName("global");
  if (!await store.acceptCommunityFeedNonce(nonce)) return Response.json({ ok: false, error: "request_replayed" }, { status: 409 });
  const result = raw.action === "claim" ? await store.claimCommunityRng(env.DISCORD_RNG_CHANNEL_ID, raw.receipts as FeedReceipt[] | undefined) : raw.action === "ack" ? await store.acknowledgeCommunityRng(raw, env.DISCORD_RNG_CHANNEL_ID) : { ok: false, error: "invalid_request" };
  return Response.json(result, { status: result.ok ? 200 : 400, headers: { "Cache-Control": "no-store" } });
}

export function discordLeaderboardMessage(result: LeaderboardResult): { content: string; embeds?: Record<string, unknown>[]; components?: Record<string, unknown>[] } {
  const escape = (value: string) => value.replace(/[\\`*_~|<>]/g, "\\$&");
  if (!result.ok) return { content: result.error === "invalid_leaderboard" ? "Choose a metric from the selected leaderboard category, a valid period and a page from 1 to 100." : "Leaderboard unavailable. Retry later; your local mod remains available." };
  const format = (value: number): string => {
    if (result.unit !== "milliseconds") return `${new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 }).format(value)} ${result.unit}`;
    const millis = Math.round(value), minutes = Math.floor(millis / 60000);
    const prefix = millis < 3600000 ? String(minutes) : `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, "0")}`;
    return `${prefix}:${String(Math.floor(millis / 1000) % 60).padStart(2, "0")}.${String(millis % 1000).padStart(3, "0")}`;
  };
  const entries = result.entries ?? [], lines = entries.map(entry => `**${entry.rank}.** ${escape(entry.player)} · **${format(entry.value)}** · ${entry.sampleSize} record${entry.sampleSize === 1 ? "" : "s"}`);
  return { content: result.available === false ? result.reason ?? "This metric is unavailable." : "", embeds: [{ title: `Sky-Myce · ${result.metricLabel} · ${result.period === "weekly" ? "UTC week" : result.period === "monthly" ? "UTC month" : "All time"}`,
    color: 0x7c61e8, description: `${result.description}\n\n${lines.join("\n") || "No complete shared records for this selection."}`.slice(0, 4000),
    fields: [{ name: "Source and coverage", value: `${result.source}\n${result.coverage}` }, { name: "Updated / page", value: `${result.updatedAt ? `<t:${Math.floor(result.updatedAt / 1000)}:R>` : "No observations yet"} · page ${result.page} · ${result.total} Minecraft identities` }],
    footer: { text: result.search ? "Filtered results: repeat /leaderboard with the same search and desired page. Community reports remain unverified." : "Community reports remain unverified · anonymous names honor sharing preferences" } }],
    components: !result.search && (result.hasNext || (result.page ?? 1) > 1) ? [{ type: 1, components: [
      ...((result.page ?? 1) > 1 ? [{ type: 2, style: 2, label: "Previous", custom_id: `sky:leaderboard:${result.category}:${result.metric}:${result.period}:${(result.page ?? 1) - 1}` }] : []),
      ...(result.hasNext ? [{ type: 2, style: 2, label: "Next", custom_id: `sky:leaderboard:${result.category}:${result.metric}:${result.period}:${(result.page ?? 1) + 1}` }] : [])] }] : [] };
}
