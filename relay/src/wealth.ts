export const WEALTH_TTL = 86_400_000;
export const ABSENT_TTL = 604_800_000;
export type CachedWealth = { name: string; uuid: string; wealth: Record<string, unknown>; fetchedAt: number };
const totals = ["networth", "purse", "bank", "wardrobe"] as const;

function incomplete(wealth: Record<string, unknown>): boolean {
  return wealth.hasProfile === true && totals.some(key => wealth[key] == null);
}

/** Store totals only, never inventories, item NBT, account credentials, or the friend roster. */
export function validateWealth(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const data = value as Record<string, unknown>;
  if (typeof data.hasProfile !== "boolean") return null;
  const result: Record<string, unknown> = { hasProfile: data.hasProfile };
  for (const key of totals) {
    if (data[key] == null) continue;
    if (!data.hasProfile || typeof data[key] !== "number" || !Number.isFinite(data[key]) ||
        data[key] < 0 || data[key] > Number.MAX_SAFE_INTEGER) return null;
    result[key] = data[key];
  }
  for (const key of ["profile", "status"]) {
    const text = data[key] ?? "";
    if (typeof text !== "string" || text.length > (key === "profile" ? 64 : 128) || /[^\x20-\x7e]/.test(text)) return null;
    result[key] = text;
  }
  return result;
}

export class SharedWealth {
  constructor(private sql: SqlStorage) {
    sql.exec(`CREATE TABLE IF NOT EXISTS player_wealth (
      id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL COLLATE NOCASE UNIQUE, wealth TEXT NOT NULL, fetched_at INTEGER NOT NULL)`);
    if (!sql.exec<{ name: string }>("PRAGMA table_info(player_wealth)").toArray().some(row => row.name === "publisher")) {
      sql.exec("ALTER TABLE player_wealth ADD COLUMN publisher TEXT");
    }
  }

  deleteSubjects(uuids: string[]): string[] {
    const removed = new Set<string>();
    // Legacy rows had no contributor provenance. Suppressing them is safer than redistributing an unknown contributor's old upload.
    if (uuids.length) {
      this.sql.exec<{ uuid: string }>("SELECT uuid FROM player_wealth WHERE publisher IS NULL").toArray().forEach(row => removed.add(row.uuid));
      this.sql.exec("DELETE FROM player_wealth WHERE publisher IS NULL");
    }
    for (const uuid of uuids) {
      this.sql.exec<{ uuid: string }>("SELECT uuid FROM player_wealth WHERE uuid=? OR publisher=?", uuid, uuid).toArray().forEach(row => removed.add(row.uuid));
      this.sql.exec("DELETE FROM player_wealth WHERE uuid=? OR publisher=?", uuid, uuid);
    }
    return [...removed];
  }

  /** Private provenance for authoritative consent checks; never include contributor identity in cache DTOs. */
  publisher(uuid: string): string | null {
    return this.sql.exec<{ publisher: string | null }>("SELECT publisher FROM player_wealth WHERE uuid=?", uuid).toArray()[0]?.publisher ?? null;
  }

  get(name: string, uuid: string | undefined, now: number, refresh: boolean): CachedWealth | null {
    const row = this.sql.exec<{ uuid: string; name: string; wealth: string; fetched_at: number }>(
      `SELECT uuid, name, wealth, fetched_at FROM player_wealth WHERE ${uuid ? "uuid" : "name"} = ?`, uuid ?? name,
    ).toArray()[0];
    if (!row || (uuid && row.uuid !== uuid)) return null;
    const wealth = JSON.parse(row.wealth);
    const ttl = wealth.hasProfile || refresh ? WEALTH_TTL : ABSENT_TTL;
    // Manual retries may fill missing values, but clients share a one-minute minimum retry interval.
    if (refresh && incomplete(wealth) && row.fetched_at <= now - 60_000) return null;
    return row.fetched_at > now - ttl ? { name: row.name, uuid: row.uuid, wealth, fetchedAt: row.fetched_at } : null;
  }

  put(record: CachedWealth, now: number, publisher?: string): boolean {
    const existing = this.sql.exec<{ uuid: string; wealth: string; fetched_at: number }>(
      "SELECT uuid, wealth, fetched_at FROM player_wealth WHERE uuid = ? OR name = ?", record.uuid, record.name,
    ).toArray();
    if (existing.some(row => {
      if (row.fetched_at >= record.fetchedAt) return true;
      if (row.fetched_at <= now - WEALTH_TTL) return false;
      const previous = JSON.parse(row.wealth);
      // Within 24h accept only an improvement for the same player, without losing any known totals.
      return row.uuid !== record.uuid || !incomplete(previous) ||
        totals.some(key => previous[key] != null && record.wealth[key] == null) ||
        !totals.some(key => previous[key] == null && record.wealth[key] != null);
    })) return false;
    this.sql.exec("DELETE FROM player_wealth WHERE uuid = ? OR name = ?", record.uuid, record.name);
    this.sql.exec("INSERT INTO player_wealth (uuid, name, wealth, fetched_at, publisher) VALUES (?, ?, ?, ?, ?)",
      record.uuid, record.name, JSON.stringify(record.wealth), record.fetchedAt, publisher ?? null);
    // Bound storage to the latest 10,000 uploads without counting/scanning the whole table per write.
    this.sql.exec("DELETE FROM player_wealth WHERE id <= (SELECT MAX(id) FROM player_wealth) - 10000");
    return true;
  }
}
