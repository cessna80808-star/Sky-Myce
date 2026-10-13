export type TrafficCredit = { credits: number; updated: number };
export type TrafficDecision = { ok: boolean; error?: string; retryAt?: number };

/** Charge before any await; WebSocket attachments preserve this budget through hibernation. */
export function takeTrafficCredit(credit: TrafficCredit, burst: number, refillMs: number, now: number): boolean {
  credit.credits = Math.min(burst, credit.credits + Math.max(0, now - credit.updated) / refillMs);
  credit.updated = Math.max(now, credit.updated);
  if (credit.credits < 1) return false;
  credit.credits--;
  return true;
}

const policies = { request: [120, 250], connect: [12, 5000], global: [2048, 10] } as const;
type Abuse = { violations: number; started: number; blocked_until: number };

/** Account keys come only from verified sessions, never client-supplied names, UUIDs or IPs. */
export class CommunityTraffic {
  private cleanupAt = 0;
  constructor(private sql: SqlStorage, private blocked: (accountId: string, retryAt: number) => void) {
    sql.exec(`CREATE TABLE IF NOT EXISTS community_traffic(account_id TEXT NOT NULL,kind TEXT NOT NULL,credits REAL NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(account_id,kind));
      CREATE INDEX IF NOT EXISTS community_traffic_updated ON community_traffic(updated);
      CREATE TABLE IF NOT EXISTS community_traffic_abuse(account_id TEXT PRIMARY KEY,violations INTEGER NOT NULL,started INTEGER NOT NULL,blocked_until INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS community_traffic_abuse_started ON community_traffic_abuse(started);`);
  }
  status(accountId: string, now = Date.now()): TrafficDecision {
    const row = this.sql.exec<Abuse>("SELECT * FROM community_traffic_abuse WHERE account_id=?", accountId).toArray()[0];
    return row && row.blocked_until > now ? { ok: false, error: "traffic_cooldown", retryAt: row.blocked_until } : { ok: true };
  }
  consume(accountId: string, kind: "request" | "connect", now = Date.now()): TrafficDecision {
    this.cleanup(now);
    const status = this.status(accountId, now);
    if (!status.ok) return status;
    if (!this.spend(accountId, kind, now)) {
      const violation = this.violation(accountId, now);
      return violation.ok ? { ok: false, error: "rate_limited", retryAt: now + policies[kind][1] } : violation;
    }
    // Bound accepted work across all accounts and rooms, including HTTP and WebSocket requests.
    if (!this.spend("*", "global", now)) return { ok: false, error: "service_busy", retryAt: now + 1000 };
    return { ok: true };
  }
  violation(accountId: string, now = Date.now()): TrafficDecision {
    this.cleanup(now);
    const row = this.sql.exec<Abuse>("SELECT * FROM community_traffic_abuse WHERE account_id=?", accountId).toArray()[0];
    if (row && row.blocked_until > now) return { ok: false, error: "traffic_cooldown", retryAt: row.blocked_until };
    const started = row && row.started > now - 300_000 ? row.started : now;
    const violations = started === row?.started ? row.violations + 1 : 1;
    const retryAt = violations >= 3 ? now + 60_000 : 0;
    this.sql.exec(`INSERT INTO community_traffic_abuse VALUES(?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET
      violations=excluded.violations,started=excluded.started,blocked_until=excluded.blocked_until`, accountId, retryAt ? 0 : violations, retryAt ? now : started, retryAt);
    if (retryAt) {
      this.blocked(accountId, retryAt);
      return { ok: false, error: "traffic_cooldown", retryAt };
    }
    return { ok: true };
  }
  private spend(accountId: string, kind: keyof typeof policies, now: number): boolean {
    const [burst, refillMs] = policies[kind];
    const credit = this.sql.exec<TrafficCredit>("SELECT credits,updated FROM community_traffic WHERE account_id=? AND kind=?", accountId, kind).toArray()[0] ?? { credits: burst, updated: now };
    const ok = takeTrafficCredit(credit, burst, refillMs, now);
    this.sql.exec(`INSERT INTO community_traffic VALUES(?,?,?,?) ON CONFLICT(account_id,kind) DO UPDATE SET
      credits=excluded.credits,updated=excluded.updated`, accountId, kind, credit.credits, credit.updated);
    return ok;
  }
  private cleanup(now: number): void {
    if (now < this.cleanupAt) return;
    this.sql.exec("DELETE FROM community_traffic WHERE updated<?", now - 3_600_000);
    this.sql.exec("DELETE FROM community_traffic_abuse WHERE started<? AND blocked_until<=?", now - 3_600_000, now);
    this.cleanupAt = now + 60_000;
  }
}
