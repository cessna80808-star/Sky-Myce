import { validatedPublicRngEvent, type PublicRngEvent } from "./digest";
import { randomToken } from "./drm";

export type RngForwardRecord = { uuid: string; accountId: string; sourceId: string; event: PublicRngEvent };
type Result = { ok: boolean; error?: string; warning?: string };
type Row = { uuid: string; source_id: string; account_id: string; event: string; expires_at: number; next_at: number;
  attempts: number; claim: string | null; claim_until: number };
type Hooks = { deliver(record: RngForwardRecord): Promise<Result>; diagnostic(kind: "capacity" | "unavailable"): void };

/** Room-local accepted events survive global RPC failure, lost acknowledgements and hibernation. */
export class RngForwardQueue {
  private running = false;
  constructor(private storage: DurableObjectStorage, private hooks: Hooks) {
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS room_rng_forward(
      uuid TEXT NOT NULL,source_id TEXT NOT NULL,account_id TEXT NOT NULL,event TEXT NOT NULL,
      expires_at INTEGER NOT NULL,next_at INTEGER NOT NULL,attempts INTEGER NOT NULL,claim TEXT,claim_until INTEGER NOT NULL,
      PRIMARY KEY(uuid,source_id));
      CREATE INDEX IF NOT EXISTS room_rng_forward_due ON room_rng_forward(next_at,claim_until)`);
  }
  /** Caller commits this together with its new Digest record before acknowledging local storage. */
  enqueue(record: RngForwardRecord, now = Date.now()): Result {
    const { uuid, accountId, sourceId, event } = record;
    const publicEvent = validatedPublicRngEvent(event);
    if (!/^[a-f0-9]{32}$/.test(uuid) || !/^[a-f0-9]{32}$/.test(sourceId) || typeof accountId !== "string" || accountId.length < 1 || accountId.length > 100 ||
      !publicEvent || JSON.stringify(publicEvent).length > 512) return { ok: false, error: "invalid_event" };
    const expires = Math.min(now + 240000, event.occurredAt + 299000);
    if (expires <= now) return { ok: false, error: "invalid_event" };
    this.prune(now);
    if (this.storage.sql.exec("SELECT 1 FROM room_rng_forward WHERE uuid=? AND source_id=?", uuid, sourceId).toArray().length) return { ok: true };
    if (this.storage.sql.exec<{ total: number }>("SELECT COUNT(*) total FROM room_rng_forward").one().total >= 256) {
      this.hooks.diagnostic("capacity"); return { ok: false, error: "rng_forward_capacity" };
    }
    this.storage.sql.exec("INSERT INTO room_rng_forward VALUES(?,?,?,?,?,?,0,NULL,0)", uuid, sourceId, accountId, JSON.stringify(publicEvent), expires, now);
    return { ok: true };
  }
  suppress(uuids: string[]): void {
    for (const uuid of uuids.slice(0, 256)) if (/^[a-f0-9]{32}$/.test(uuid)) this.storage.sql.exec("DELETE FROM room_rng_forward WHERE uuid=?", uuid);
  }
  private prune(now = Date.now()): void { this.storage.sql.exec("DELETE FROM room_rng_forward WHERE expires_at<=?", now); }
  nextAlarm(): number | null {
    this.prune();
    return this.storage.sql.exec<{ at: number | null }>("SELECT MIN(MIN(expires_at,MAX(next_at,claim_until))) at FROM room_rng_forward").one().at;
  }
  async arm(): Promise<void> {
    const next = this.nextAlarm(); if (next === null) return;
    const current = await this.storage.getAlarm();
    if (current === null || current > next) await this.storage.setAlarm(Math.max(Date.now() + 1, next));
  }
  async drain(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      this.prune();
      const rows = this.storage.sql.exec<Row>("SELECT * FROM room_rng_forward WHERE next_at<=? AND claim_until<=? ORDER BY expires_at,uuid,source_id LIMIT 3", Date.now(), Date.now()).toArray();
      for (const row of rows) {
        // Re-read after awaited RPCs: a privacy callback may already have removed this row.
        const current = this.storage.sql.exec<Row>("SELECT * FROM room_rng_forward WHERE uuid=? AND source_id=?", row.uuid, row.source_id).toArray()[0];
        if (!current || current.expires_at <= Date.now() || current.claim_until > Date.now()) continue;
        const claim = randomToken();
        this.storage.sql.exec("UPDATE room_rng_forward SET claim=?,claim_until=?,attempts=attempts+1 WHERE uuid=? AND source_id=?", claim, Date.now() + 15000, row.uuid, row.source_id);
        let result: Result;
        let timeout: ReturnType<typeof setTimeout> | undefined;
        try {
          result = await Promise.race([this.hooks.deliver({ uuid: current.uuid, accountId: current.account_id, sourceId: current.source_id, event: JSON.parse(current.event) as PublicRngEvent }),
            new Promise<Result>((_, reject) => { timeout = setTimeout(() => reject(new Error("Forwarding deadline")), 5000); })]);
        } catch { result = { ok: false, error: "community_unavailable" }; }
        finally { if (timeout !== undefined) clearTimeout(timeout); }
        const terminal = result.ok || ["sharing_disabled", "stale_author", "invalid_event", "blacklisted", "rng_archive_full"].includes(result.error ?? "");
        if (result.error === "rng_feed_busy" || result.error === "rng_archive_full" || result.warning === "contribution_capacity_reached") this.hooks.diagnostic("capacity");
        else if (!terminal) this.hooks.diagnostic("unavailable");
        if (terminal) this.storage.sql.exec("DELETE FROM room_rng_forward WHERE uuid=? AND source_id=? AND claim=?", row.uuid, row.source_id, claim);
        else this.storage.sql.exec("UPDATE room_rng_forward SET claim=NULL,claim_until=0,next_at=? WHERE uuid=? AND source_id=? AND claim=?",
          Date.now() + Math.min(60000, 2000 * 2 ** Math.min(current.attempts, 5)), row.uuid, row.source_id, claim);
      }
    } finally { this.running = false; await this.arm(); }
  }
}
