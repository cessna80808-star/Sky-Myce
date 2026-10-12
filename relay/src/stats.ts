export const classes = ["HEALER", "MAGE", "BERSERKER", "ARCHER", "TANK"];
const floors = [...Array(7)].flatMap((_, i) => [`F${i + 1}`, `M${i + 1}`]);
export const STATS_TTL = 600_000;
export type CachedStats = { uuid: string; name: string; stats: Record<string, unknown>; fetchedAt: number };
export type StatsGrant = { name: string; uuid?: string; token: string; expires: number };

function object(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/** Only normalized public dungeon stats cross this boundary; never store an arbitrary client object. */
export function validateStats(value: unknown): Record<string, unknown> | null {
  if (!object(value) || typeof value.state !== "string" || !["AVAILABLE", "HIDDEN", "NO_PROFILE"].includes(value.state)) return null;
  const result: Record<string, unknown> = { state: value.state };
  if (value.catacombs != null) {
    if (!Number.isInteger(value.catacombs) || (value.catacombs as number) < 0 || (value.catacombs as number) > 10_000) return null;
    result.catacombs = value.catacombs;
  }
  for (const key of ["classes", "completionTimes", "sPlusTimes"]) {
    const map = value[key];
    if (!object(map)) return null;
    const entries = Object.entries(map), allowed = key === "classes" ? classes : floors;
    if (entries.length > allowed.length || entries.some(([k, v]) => !allowed.includes(k) || !Number.isInteger(v) ||
      (v as number) < (key === "classes" ? 0 : 1) || (v as number) > (key === "classes" ? 10_000 : 86_400_000))) return null;
    result[key] = Object.fromEntries(entries);
  }
  if (!Array.isArray(value.completedFloors) || value.completedFloors.length > 14 || value.completedFloors.some(f => !floors.includes(f))) return null;
  result.completedFloors = [...new Set(value.completedFloors)];
  if (value.selectedClass != null) {
    if (typeof value.selectedClass !== "string" || !classes.includes(value.selectedClass)) return null;
    result.selectedClass = value.selectedClass;
  }
  return result;
}

export class SharedStats {
  constructor(private sql: SqlStorage) {
    sql.exec(`CREATE TABLE IF NOT EXISTS player_stats (
      uuid TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
      stats TEXT NOT NULL, fetched_at INTEGER NOT NULL, publisher TEXT NOT NULL)`);
    sql.exec("CREATE INDEX IF NOT EXISTS player_stats_age ON player_stats(fetched_at)");
  }

  deleteSubjects(uuids: string[]): string[] {
    const removed = new Set<string>();
    for (const uuid of uuids) {
      this.sql.exec<{ uuid: string }>("SELECT uuid FROM player_stats WHERE uuid=? OR publisher=?", uuid, uuid).toArray().forEach(row => removed.add(row.uuid));
      this.sql.exec("DELETE FROM player_stats WHERE uuid=? OR publisher=?", uuid, uuid);
    }
    return [...removed];
  }

  /** Private provenance for authoritative consent checks; never include contributor identity in cache DTOs. */
  publisher(uuid: string): string | null {
    return this.sql.exec<{ publisher: string }>("SELECT publisher FROM player_stats WHERE uuid=?", uuid).toArray()[0]?.publisher ?? null;
  }

  get(name: string, uuid: string | undefined, now: number): CachedStats | null {
    const row = this.sql.exec<{ uuid: string; name: string; stats: string; fetched_at: number }>(
      "SELECT uuid, name, stats, fetched_at FROM player_stats WHERE name = ? AND fetched_at > ?", name, now - STATS_TTL,
    ).toArray()[0];
    if (!row || (uuid && row.uuid !== uuid)) return null;
    return { uuid: row.uuid, name: row.name, stats: JSON.parse(row.stats), fetchedAt: row.fetched_at };
  }

  selectClass(name: string, uuid: string, selectedClass: string): void {
    // Class changes do not renew the age of PBs or any other cached stats.
    this.sql.exec("UPDATE player_stats SET stats = json_set(stats, '$.selectedClass', ?) WHERE uuid = ? AND name = ?",
      selectedClass, uuid, name);
  }

  put(record: CachedStats, publisher: string, now: number, ownRefresh = false): boolean {
    // Only a verified player's newer self-report may replace their fresh record.
    const existing = this.sql.exec<{ uuid: string; fetched_at: number }>(
      "SELECT uuid, fetched_at FROM player_stats WHERE (uuid = ? OR name = ?) AND fetched_at > ?", record.uuid, record.name, now - STATS_TTL,
    ).toArray();
    if (existing.some(row => !ownRefresh || publisher !== record.uuid || row.uuid !== record.uuid || row.fetched_at >= record.fetchedAt)) return false;
    this.sql.exec("DELETE FROM player_stats WHERE uuid = ? OR name = ?", record.uuid, record.name);
    // ponytail: bounded room cache; shard by UUID if the 128-player room limit is raised.
    if (this.sql.exec<{ total: number }>("SELECT COUNT(*) AS total FROM player_stats").one().total >= 10_000) {
      this.sql.exec("DELETE FROM player_stats WHERE uuid IN (SELECT uuid FROM player_stats ORDER BY fetched_at LIMIT 100)");
    }
    this.sql.exec("INSERT INTO player_stats VALUES (?, ?, ?, ?, ?)", record.uuid, record.name, JSON.stringify(record.stats), record.fetchedAt, publisher);
    return true;
  }
}
