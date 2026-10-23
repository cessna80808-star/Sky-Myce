/** One demand-driven, keyless public-market cache for Sky-Myce; never a general Hypixel API proxy. */
export type BazaarProduct = { id: string; buyPrice: number; sellPrice: number; buyVolume: number; sellVolume: number;
  buyMovingWeek: number; sellMovingWeek: number; buyOrders: number; sellOrders: number;
  buyDepth: { price: number; amount: number; orders: number }[]; sellDepth: { price: number; amount: number; orders: number }[] };
export type BazaarSnapshot = { version: 1; provider: "hypixel-public"; source: string; observedAt: number; retrievedAt: number; products: BazaarProduct[] };
export const BAZAAR_SOURCE = "Hypixel public Bazaar";
export const bazaarProductId = (raw: unknown): raw is string => typeof raw === "string" && /^[A-Z0-9_:;.\-]{1,100}$/.test(raw);
const number = (raw: unknown): raw is number => typeof raw === "number" && Number.isFinite(raw) && raw >= 0 && raw <= 1e15;
const object = (raw: unknown): raw is Record<string, unknown> => !!raw && typeof raw === "object" && !Array.isArray(raw);
function depth(raw: unknown) {
  if (!Array.isArray(raw) || raw.length > 1000) return null;
  const result: { price: number; amount: number; orders: number }[] = [];
  for (const entry of raw.slice(0, 5)) {
    if (!object(entry) || !number(entry.pricePerUnit) || !Number.isSafeInteger(entry.amount) || Number(entry.amount) < 0 ||
      !Number.isSafeInteger(entry.orders) || Number(entry.orders) < 0) return null;
    result.push({ price: entry.pricePerUnit, amount: Number(entry.amount), orders: Number(entry.orders) });
  }
  return result;
}
export function normalizeBazaar(raw: unknown, now = Date.now()): BazaarSnapshot | null {
  if (!object(raw) || raw.success !== true || !Number.isSafeInteger(raw.lastUpdated) || Number(raw.lastUpdated) > now + 60000 ||
    Number(raw.lastUpdated) < now - 86400000 || !object(raw.products) || Object.keys(raw.products).length > 3000) return null;
  const products: BazaarProduct[] = [], seen = new Set<string>();
  for (const [id, value] of Object.entries(raw.products)) {
    if (!bazaarProductId(id) || seen.has(id) || !object(value) || value.product_id !== id || !object(value.quick_status)) return null;
    seen.add(id);
    // Hypixel names books by the instant action: sell_summary contains bids; buy_summary contains asks.
    const buyDepth = depth(value.sell_summary), sellDepth = depth(value.buy_summary), q = value.quick_status;
    if (!buyDepth || !sellDepth || ![q.buyVolume, q.sellVolume, q.buyMovingWeek, q.sellMovingWeek, q.buyOrders, q.sellOrders].every(number)) return null;
    // Empty sides are untradeable and omitted, never assigned a fabricated zero price.
    if (!buyDepth.length || !sellDepth.length || buyDepth[0].price <= 0 || sellDepth[0].price <= 0) continue;
    buyDepth.sort((a, b) => b.price - a.price); sellDepth.sort((a, b) => a.price - b.price);
    products.push({ id, buyPrice: buyDepth[0].price, sellPrice: sellDepth[0].price, buyDepth, sellDepth,
      buyVolume: Number(q.sellVolume), sellVolume: Number(q.buyVolume), buyMovingWeek: Number(q.sellMovingWeek), sellMovingWeek: Number(q.buyMovingWeek),
      buyOrders: Number(q.sellOrders), sellOrders: Number(q.buyOrders) });
  }
  return products.length ? { version: 1, provider: "hypixel-public", source: BAZAAR_SOURCE, observedAt: Number(raw.lastUpdated), retrievedAt: now, products } : null;
}
/** Shared with the mod: a 30-second cache polled every 45 seconds stays inside the live window. */
export const BAZAAR_FRESH_MILLIS = 120000, BAZAAR_STALE_MILLIS = 300000;
export function bazaarFreshness(observedAt: number, now = Date.now()): "fresh" | "aging" | "stale" | "unavailable" {
  if (!Number.isSafeInteger(observedAt) || observedAt <= 0 || observedAt > now + 60000) return "unavailable";
  return now - observedAt <= BAZAAR_FRESH_MILLIS ? "fresh" : now - observedAt <= BAZAAR_STALE_MILLIS ? "aging" : "stale";
}
async function providerJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader(); if (!reader) throw Error("empty_provider_response");
  const parts: Uint8Array[] = []; let size = 0;
  try { while (true) { const { done, value } = await reader.read(); if (done) break; size += value.byteLength;
    if (size > 8 * 1024 * 1024) throw Error("provider_response_too_large"); parts.push(value); }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size); let offset = 0; for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
}
export class BazaarMarket {
  private pending?: Promise<void>;
  private snapshot: BazaarSnapshot | null;
  private retryAt = 0;
  private failures = 0;
  private mayor: { value: string; observedAt: number; retrievedAt: number } | null = null;
  private mayorRetryAt = 0;
  private mayorPending?: Promise<void>;
  constructor(private sql: SqlStorage, private provider = "hypixel-public") {
    sql.exec(`CREATE TABLE IF NOT EXISTS community_bazaar_market(id INTEGER PRIMARY KEY,data TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS community_bazaar_history(bucket TEXT NOT NULL,at INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(bucket,at));`);
    const saved = sql.exec<{ data: string }>("SELECT data FROM community_bazaar_market WHERE id=1").toArray()[0];
    this.snapshot = saved ? JSON.parse(saved.data) : null;
    const mayor = sql.exec<{ data: string }>("SELECT data FROM community_bazaar_market WHERE id=2").toArray()[0];
    this.mayor = mayor ? JSON.parse(mayor.data) : null;
  }
  async get(active: boolean, now = Date.now()) {
    if (this.provider !== "hypixel-public") return { ok: true, version: 1, provider: this.provider, source: BAZAAR_SOURCE, observedAt: 0, retrievedAt: 0,
      freshness: "unavailable", products: [], mayor: null, retryAt: 0, limitation: "This source is disabled or its redistribution permission has not been verified." };
    let mayorRefresh: Promise<void> | undefined;
    if (active && now >= this.mayorRetryAt && (!this.mayor || now - this.mayor.retrievedAt >= 3600000)) {
      if (!this.mayorPending) this.mayorPending = this.refreshMayor(now).finally(() => { this.mayorPending = undefined; });
      mayorRefresh = this.mayorPending;
    }
    if (now >= this.retryAt && (!this.snapshot || now - this.snapshot.retrievedAt >= (active ? 30000 : 300000))) {
      if (!this.pending) this.pending = this.refresh(now).finally(() => { this.pending = undefined; });
      await this.pending;
    }
    await mayorRefresh;
    return { ok: true, ...(this.snapshot ?? { version: 1, provider: "hypixel-public", source: BAZAAR_SOURCE, products: [], observedAt: 0, retrievedAt: 0 }),
      freshness: bazaarFreshness(this.snapshot?.observedAt ?? 0), retryAt: this.retryAt,
      mayor: this.mayor && now - this.mayor.retrievedAt <= 7200000 ? this.mayor.value : null,
      mayorObservedAt: this.mayor?.observedAt ?? 0, mayorRetrievedAt: this.mayor?.retrievedAt ?? 0,
      limitation: "Estimates only; no Hypixel endorsement. Mayor context does not establish causality. Cached history is collected on demand." };
  }
  private async refreshMayor(now: number): Promise<void> {
    this.mayorRetryAt = now + 3600000;
    try {
      const response = await fetch("https://api.hypixel.net/v2/resources/skyblock/election", { redirect: "manual", signal: AbortSignal.timeout(10000), headers: { Accept: "application/json" } });
      if (!response.ok) { await response.body?.cancel(); return; }
      const raw = await providerJson(response);
      if (!object(raw) || raw.success !== true || !object(raw.mayor) || typeof raw.mayor.name !== "string" ||
        !Number.isSafeInteger(raw.lastUpdated) || Number(raw.lastUpdated) > now + 60000 || Number(raw.lastUpdated) < now - 86400000) return;
      const clean = (value: unknown) => typeof value === "string" ? value.replace(/[\p{Cc}\p{Cf}<>]/gu, "").slice(0, 100) : "";
      const perks = Array.isArray(raw.mayor.perks) ? raw.mayor.perks.slice(0, 8).flatMap(perk => object(perk) ? [clean(perk.name)] : []) : [];
      const minister = object(raw.mayor.minister) ? clean(raw.mayor.minister.name) : "";
      const value = `${clean(raw.mayor.name)}${perks.length ? ` · ${perks.join(", ")}` : ""}${minister ? ` · Minister: ${minister}` : ""}`;
      this.mayor = { value, observedAt: Number(raw.lastUpdated), retrievedAt: now };
      this.sql.exec("INSERT INTO community_bazaar_market VALUES(2,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", JSON.stringify(this.mayor));
    } catch { /* Unknown context reduces confidence; it never supplies a fabricated market effect. */ }
  }
  private async refresh(now: number): Promise<void> {
    try {
      const response = await fetch("https://api.hypixel.net/v2/skyblock/bazaar", { redirect: "manual", signal: AbortSignal.timeout(10000),
        headers: { Accept: "application/json", "User-Agent": "Sky-Myce Bazaar/1 (shared keyless public-market cache)" } });
      if (!response.ok) {
        const header = response.headers.get("Retry-After") ?? "", seconds = Number(header);
        const requested = header ? Number.isFinite(seconds) ? Math.max(0, seconds * 1000) : Math.max(0, Date.parse(header) - now) : 0;
        const retry = Math.max(30000 * 2 ** Math.min(this.failures++, 6), Number.isFinite(requested) ? requested : 0);
        this.retryAt = now + retry; await response.body?.cancel(); return;
      }
      const snapshot = normalizeBazaar(await providerJson(response), now); if (!snapshot) throw Error("invalid_provider_response");
      const serialized = JSON.stringify(snapshot); if (new TextEncoder().encode(serialized).byteLength > 1800000) throw Error("normalized_snapshot_too_large");
      this.retryAt = now + 30000; this.failures = 0;
      // Identical upstream timestamps do not generate another snapshot or history write.
      if (snapshot.observedAt === this.snapshot?.observedAt) { this.snapshot.retrievedAt = now; return; }
      const compact = JSON.stringify({ observedAt: snapshot.observedAt, prices: Object.fromEntries(snapshot.products.map(p => [p.id, [p.buyPrice, p.sellPrice]])), mayor: this.mayor?.value ?? null, mayorObservedAt: this.mayor?.observedAt ?? 0 });
      this.sql.exec("INSERT INTO community_bazaar_market VALUES(1,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data", serialized);
      // At most 72 hourly and 366 daily closing observations, shared by every player; no personal history.
      for (const [bucket, interval, count] of [["hour", 3600000, 72], ["day", 86400000, 366]] as const) {
        const at = Math.floor(snapshot.observedAt / interval) * interval;
        this.sql.exec("INSERT INTO community_bazaar_history VALUES(?,?,?) ON CONFLICT(bucket,at) DO UPDATE SET data=excluded.data", bucket, at, compact);
        this.sql.exec("DELETE FROM community_bazaar_history WHERE bucket=? AND at<?", bucket, at - (count - 1) * interval);
      }
      this.snapshot = snapshot;
    } catch { this.retryAt = now + Math.min(1800000, 30000 * 2 ** Math.min(this.failures++, 6)); }
  }
  history(product: string, daily: boolean) {
    if (!bazaarProductId(product)) return { ok: false, error: "invalid_product" };
    const path = `$.prices."${product}"`;
    // SQLite projects only one product's bounded scalar series; never materialize hundreds of full snapshots in JS.
    const points = this.sql.exec<{ at: number; buyPrice: number; sellPrice: number; mayor: string | null; mayorObservedAt: number }>(`SELECT json_extract(data,'$.observedAt') at,
      json_extract(data,?) buyPrice,json_extract(data,?) sellPrice,json_extract(data,'$.mayor') mayor,json_extract(data,'$.mayorObservedAt') mayorObservedAt
      FROM community_bazaar_history WHERE bucket=? AND json_type(data,?) IS NOT NULL ORDER BY community_bazaar_history.at DESC LIMIT ?`,
      `${path}[0]`, `${path}[1]`, daily ? "day" : "hour", path, daily ? 366 : 72).toArray().reverse();
    return { ok: true, version: 1, source: BAZAAR_SOURCE, mayor: null, points };
  }
}
