import { deny, randomToken, sha256, AuthorizationError } from "./drm";
import { type CapeAsset, type CosmeticProfile, CAPE_PRESETS, PlayerCosmetics } from "./cosmetics";
import type { CommunitySessionResult } from "./community";
import type { AccountStore } from "./accounts";

export const CAPE_QUOTA = { maxAssets: 8, maxBytes: 2097152, maxUploadBytes: 262144 };
type Session = CommunitySessionResult & { sessionId: string; accountId: string; uuid: string; generation: number };
type AssetRow = { id: string; account_id: string; uuid: string; status: string; size: number; sha256: string | null; created_at: number; expires_at: number };
type GrantRow = { hash: string; asset_id: string; session_id: string; account_id: string; uuid: string; generation: number; expires_at: number; used_at: number | null };
type AssetResult = { ok: boolean; error?: string; asset?: CapeAsset; assets?: CapeAsset[]; profile?: CosmeticProfile; profiles?: CosmeticProfile[]; grant?: string; assetId?: string; expiresAt?: number; uploadPath?: string; key?: string };
type Hooks = { token(value: string): Promise<CommunitySessionResult>; current(session: Session): CommunitySessionResult; owner(uuid: string): string | null; allowed(uuid: string): boolean; limit(id: string, count: number): void };
const id = /^[a-f0-9]{32}$/;
const failure = (error: unknown): AssetResult => ({ ok: false, error: error instanceof AuthorizationError ? error.code : "cosmetics_unavailable" });
const descriptor = (row: AssetRow): CapeAsset => ({ id: row.id, sha256: row.sha256!, size: row.size, width: 64, height: 32 });

/** Global ownership/quota/grant authority. This module never receives a private-artifact binding. */
export class CosmeticAssets {
  constructor(private storage: DurableObjectStorage, private profiles: PlayerCosmetics, private hooks: Hooks) {
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS cosmetic_assets(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,uuid TEXT NOT NULL,status TEXT NOT NULL,
      size INTEGER NOT NULL,sha256 TEXT,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS cosmetic_asset_owner ON cosmetic_assets(account_id,status);
      CREATE TABLE IF NOT EXISTS cosmetic_asset_grants(hash TEXT PRIMARY KEY,asset_id TEXT UNIQUE NOT NULL,session_id TEXT NOT NULL,account_id TEXT NOT NULL,
      uuid TEXT NOT NULL,generation INTEGER NOT NULL,expires_at INTEGER NOT NULL,used_at INTEGER);
      CREATE TABLE IF NOT EXISTS cosmetic_asset_cleanup(id TEXT PRIMARY KEY);`);
  }
  private session(value: CommunitySessionResult, raw?: Record<string, unknown>): Session {
    if (!value.ok || !value.sessionId || !value.accountId || !value.uuid || value.generation === undefined) deny(value.error ?? "session_expired");
    const result = value as Session;
    if (!this.hooks.current(result).ok || this.hooks.owner(result.uuid) !== result.accountId || !this.hooks.allowed(result.uuid)) deny("session_expired");
    if (raw && (raw.expectedAccountId !== result.accountId || raw.expectedUuid !== result.uuid)) deny("session_identity_changed");
    return result;
  }
  private asset(assetId: string): AssetRow | undefined { return this.storage.sql.exec<AssetRow>("SELECT * FROM cosmetic_assets WHERE id=?", assetId).toArray()[0]; }
  publicProfile(player: string): CosmeticProfile {
    const profile = this.profiles.get(player);
    if (profile.capeAsset && !this.active(profile.capeAsset.id, player).ok) return { ...profile, capeAsset: null };
    return profile;
  }
  async overview(token: string): Promise<AssetResult & { presets?: typeof CAPE_PRESETS; quota?: typeof CAPE_QUOTA }> {
    try { const authenticated = await this.hooks.token(token); return this.storage.transactionSync(() => {
      const session = this.session(authenticated);
      return { ok: true, profile: this.publicProfile(session.uuid), assets: this.storage.sql.exec<AssetRow>("SELECT * FROM cosmetic_assets WHERE account_id=? AND status='ready' ORDER BY created_at DESC LIMIT 8", session.accountId).toArray().map(descriptor), presets: CAPE_PRESETS, quota: CAPE_QUOTA };
    }); } catch (error) { return failure(error); }
  }
  async save(token: string, raw: Record<string, unknown>): Promise<AssetResult> {
    try { const authenticated = await this.hooks.token(token); return this.storage.transactionSync(() => {
      const session = this.session(authenticated, raw); this.hooks.limit(`studio-save:${session.accountId}`, 30);
      const value = raw.profile as { capeAsset?: unknown } | undefined;
      const asset = typeof value?.capeAsset === "string" ? this.asset(value.capeAsset) : undefined;
      if (value?.capeAsset !== null && (!asset || asset.account_id !== session.accountId || asset.status !== "ready")) deny("asset_not_owned");
      return this.profiles.save(session.uuid, typeof raw.revision === "number" ? raw.revision : NaN, raw.profile, asset ? descriptor(asset) : null, session.accountId);
    }); } catch (error) { return failure(error); }
  }
  async grant(token: string, raw: Record<string, unknown>): Promise<AssetResult> {
    try {
      const authenticated = await this.hooks.token(token), secret = randomToken(), hash = await sha256(secret);
      return this.storage.transactionSync(() => {
        const session = this.session(authenticated, raw), now = Date.now(); this.hooks.limit(`cape-grant:${session.accountId}`, 12);
        // Expired reservations contain no valid asset, and cannot consume quota forever after interruption.
        this.cleanupKeys();
        const usage = this.storage.sql.exec<{ count: number; bytes: number }>("SELECT COUNT(*) count,COALESCE(SUM(size),0) bytes FROM cosmetic_assets WHERE account_id=? AND status<>'deleted'", session.accountId).one();
        if (usage.count >= CAPE_QUOTA.maxAssets || usage.bytes + CAPE_QUOTA.maxUploadBytes > CAPE_QUOTA.maxBytes) deny("asset_quota");
        const assetId = crypto.randomUUID().replaceAll("-", ""), expiresAt = now + 120000;
        this.storage.sql.exec("INSERT INTO cosmetic_assets VALUES(?,?,?,'reserved',?,NULL,?,?)", assetId, session.accountId, session.uuid, CAPE_QUOTA.maxUploadBytes, now, expiresAt);
        this.storage.sql.exec("INSERT INTO cosmetic_asset_grants VALUES(?,?,?,?,?,?,?,NULL)", hash, assetId, session.sessionId, session.accountId, session.uuid, session.generation, expiresAt);
        return { ok: true, grant: secret, assetId, expiresAt, uploadPath: "/community/cosmetics/upload" };
      });
    } catch (error) { return failure(error); }
  }
  async claim(token: string, secret: string): Promise<AssetResult> {
    try {
      if (!/^[A-Za-z0-9_-]{43}$/.test(secret)) deny("invalid_upload_grant");
      const authenticated = await this.hooks.token(token), hash = await sha256(secret);
      return this.storage.transactionSync(() => {
        const session = this.session(authenticated), row = this.storage.sql.exec<GrantRow>("SELECT * FROM cosmetic_asset_grants WHERE hash=?", hash).toArray()[0];
        if (!row || row.used_at !== null || row.expires_at <= Date.now() || row.session_id !== session.sessionId || row.account_id !== session.accountId || row.uuid !== session.uuid || row.generation !== session.generation || this.asset(row.asset_id)?.status !== "reserved") deny("invalid_upload_grant");
        this.storage.sql.exec("UPDATE cosmetic_asset_grants SET used_at=? WHERE hash=? AND used_at IS NULL", Date.now(), hash);
        this.storage.sql.exec("UPDATE cosmetic_assets SET status='writing' WHERE id=?", row.asset_id);
        return { ok: true, assetId: row.asset_id, key: `capes/${row.asset_id}.png` };
      });
    } catch (error) { return failure(error); }
  }
  async finish(token: string, assetId: string, digest: string, size: number): Promise<AssetResult> {
    try { const authenticated = await this.hooks.token(token); return this.storage.transactionSync(() => {
      const session = this.session(authenticated), row = this.asset(assetId);
      if (!row || row.status !== "writing" || row.expires_at <= Date.now() || row.account_id !== session.accountId || row.uuid !== session.uuid || !/^[a-f0-9]{64}$/.test(digest) || !Number.isSafeInteger(size) || size <= 0 || size > CAPE_QUOTA.maxUploadBytes) deny("invalid_upload_grant");
      const grant = this.storage.sql.exec<GrantRow>("SELECT * FROM cosmetic_asset_grants WHERE asset_id=?", assetId).one();
      if (grant.session_id !== session.sessionId || grant.generation !== session.generation || grant.used_at === null) deny("invalid_upload_grant");
      this.storage.sql.exec("UPDATE cosmetic_assets SET status='ready',size=?,sha256=? WHERE id=?", size, digest, assetId);
      return { ok: true, asset: descriptor(this.asset(assetId)!) };
    }); } catch (error) { return failure(error); }
  }
  fail(assetId: string): void { if (id.test(assetId)) this.storage.transactionSync(() => {
    this.storage.sql.exec("INSERT OR IGNORE INTO cosmetic_asset_cleanup SELECT id FROM cosmetic_assets WHERE id=? AND status IN ('reserved','writing')", assetId);
    this.storage.sql.exec("UPDATE cosmetic_assets SET status='deleted' WHERE id=? AND status IN ('reserved','writing')", assetId);
  }); }
  cleanupKeys(): string[] {
    return this.storage.transactionSync(() => {
      this.storage.sql.exec("INSERT OR IGNORE INTO cosmetic_asset_cleanup SELECT id FROM cosmetic_assets WHERE status IN ('reserved','writing') AND expires_at<=?", Date.now());
      this.storage.sql.exec("UPDATE cosmetic_assets SET status='deleted' WHERE status IN ('reserved','writing') AND expires_at<=?", Date.now());
      this.storage.sql.exec("DELETE FROM cosmetic_asset_grants WHERE expires_at<=?", Date.now());
      this.storage.sql.exec("DELETE FROM cosmetic_assets WHERE status='deleted' AND created_at<? AND id NOT IN (SELECT id FROM cosmetic_asset_cleanup)", Date.now() - 30 * 86400000);
      return this.storage.sql.exec<{id: string}>("SELECT id FROM cosmetic_asset_cleanup LIMIT 128").toArray().map(row => `capes/${row.id}.png`);
    });
  }
  cleanupComplete(keys: string[]): void { for (const key of keys) { const assetId = key.match(/^capes\/([a-f0-9]{32})\.png$/)?.[1]; if (assetId) this.storage.sql.exec("DELETE FROM cosmetic_asset_cleanup WHERE id=?", assetId); } }
  nextCleanup(): number | null {
    if (this.storage.sql.exec("SELECT id FROM cosmetic_asset_cleanup LIMIT 1").toArray().length) return Date.now() + 60000;
    return this.storage.sql.exec<{at: number|null}>("SELECT MIN(expires_at) at FROM cosmetic_assets WHERE status IN ('reserved','writing')").one().at;
  }
  active(assetId: string, player: string): AssetResult {
    if (!id.test(assetId) || !id.test(player) || !this.hooks.allowed(player)) return { ok: false, error: "asset_unavailable" };
    const row = this.asset(assetId), selected = this.profiles.get(player).capeAsset;
    return row?.status === "ready" && selected?.id === assetId && row.account_id === this.hooks.owner(player) ? { ok: true, asset: descriptor(row), key: `capes/${assetId}.png` } : { ok: false, error: "asset_unavailable" };
  }
  /** Private Studio previews allow owned ready uploads without making them publicly active. */
  async preview(token: string, assetId: string, player: string): Promise<AssetResult> {
    try { const authenticated = await this.hooks.token(token); return this.storage.transactionSync(() => {
      const session = this.session(authenticated);
      if (!id.test(player) || player !== session.uuid) deny("session_identity_changed");
      const row = id.test(assetId) ? this.asset(assetId) : undefined;
      if (!row || row.status !== "ready" || row.account_id !== session.accountId) deny("asset_unavailable");
      return { ok: true, asset: descriptor(row), key: `capes/${assetId}.png` };
    }); } catch (error) { return failure(error); }
  }
  async remove(token: string, assetId: string, raw: Record<string, unknown>): Promise<AssetResult> {
    try { const authenticated = await this.hooks.token(token); return this.storage.transactionSync(() => {
      const session = this.session(authenticated, raw), row = this.asset(assetId); this.hooks.limit(`cape-delete:${session.accountId}`, 30);
      if (!id.test(assetId) || !row || row.account_id !== session.accountId) deny("asset_not_owned");
      this.storage.sql.exec("UPDATE cosmetic_assets SET status='deleted' WHERE id=?", assetId);
      this.storage.sql.exec("INSERT OR IGNORE INTO cosmetic_asset_cleanup VALUES(?)", assetId);
      this.storage.sql.exec("DELETE FROM cosmetic_asset_grants WHERE asset_id=?", assetId);
      return { ok: true, key: `capes/${assetId}.png`, profiles: this.profiles.clearAsset(assetId) };
    }); } catch (error) { return failure(error); }
  }
}

/** Validate actual PNG structure, CRCs and a bounded zlib stream; keep only trusted structural chunks. */
export async function validateCapePng(bytes: Uint8Array): Promise<Uint8Array> {
  const signature = [137,80,78,71,13,10,26,10];
  if (bytes.length < 57 || bytes.length > CAPE_QUOTA.maxUploadBytes || signature.some((byte, index) => bytes[index] !== byte)) deny("invalid_png");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), decoder = new TextDecoder();
  let offset = 8, channels = 0, ended = false, idatEnded = false, sawIdat = false; const compressed: Uint8Array[] = [], canonical: Uint8Array[] = [];
  while (offset < bytes.length) {
    if (offset + 12 > bytes.length) deny("invalid_png");
    const length = view.getUint32(offset), end = offset + 12 + length;
    if (length > CAPE_QUOTA.maxUploadBytes || end > bytes.length) deny("invalid_png");
    const type = decoder.decode(bytes.subarray(offset + 4, offset + 8));
    if (!/^[A-Za-z]{4}$/.test(type) || crc32(bytes.subarray(offset + 4, offset + 8 + length)) !== view.getUint32(offset + 8 + length)) deny("invalid_png");
    if (offset === 8) {
      if (type !== "IHDR" || length !== 13 || view.getUint32(offset + 8) !== 64 || view.getUint32(offset + 12) !== 32 || bytes[offset + 16] !== 8 || ![2,6].includes(bytes[offset + 17]) || bytes[offset + 18] !== 0 || bytes[offset + 19] !== 0 || bytes[offset + 20] !== 0) deny("invalid_png_dimensions");
      channels = bytes[offset + 17] === 6 ? 4 : 3; canonical.push(bytes.slice(offset, end));
    } else if (type === "IDAT") { if (idatEnded) deny("invalid_png"); sawIdat = true; compressed.push(bytes.slice(offset + 8, offset + 8 + length)); }
    else if (type === "IEND") { if (length !== 0 || !sawIdat || end !== bytes.length) deny("invalid_png"); ended = true; canonical.push(bytes.slice(offset, end)); }
    else { if (sawIdat) idatEnded = true; if (type === "IHDR" || type === "PLTE" || type[0] === type[0].toUpperCase() || type === "acTL" || type === "fcTL" || type === "fdAT") deny("invalid_png"); }
    offset = end;
  }
  if (!ended || !channels) deny("invalid_png");
  const zip = join(compressed), expected = 32 * (1 + 64 * channels);
  let inflated: Uint8Array; try {
    const readable = new Blob([zip]).stream().pipeThrough(new DecompressionStream("deflate"));
    inflated = await boundedBytes(readable, expected);
  } catch { deny("invalid_png"); }
  if (inflated.length !== expected || Array.from({ length: 32 }, (_, row) => inflated[row * (1 + 64 * channels)]).some(filter => filter > 4)) deny("invalid_png");
  return join([new Uint8Array(signature), canonical[0], pngChunk("IDAT", zip), canonical[1]]);
}
function join(chunks: Uint8Array[]): Uint8Array { const result = new Uint8Array(chunks.reduce((size, chunk) => size + chunk.length, 0)); let offset = 0; for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; } return result; }
export function crc32(bytes: Uint8Array): number { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); } return (crc ^ 0xffffffff) >>> 0; }
function pngChunk(type: string, bytes: Uint8Array): Uint8Array { const result = new Uint8Array(bytes.length + 12), view = new DataView(result.buffer); view.setUint32(0, bytes.length); result.set(new TextEncoder().encode(type), 4); result.set(bytes, 8); view.setUint32(bytes.length + 8, crc32(result.subarray(4, bytes.length + 8))); return result; }
export async function boundedBytes(readable: ReadableStream<Uint8Array>, maximum: number): Promise<Uint8Array> {
  const reader = readable.getReader(), chunks: Uint8Array[] = []; let size = 0;
  try { while (true) { const next = await reader.read(); if (next.done) break; size += next.value.byteLength; if (size > maximum) { await reader.cancel(); deny("upload_too_large"); } chunks.push(next.value); } } finally { reader.releaseLock(); }
  return join(chunks);
}
async function digest(bytes: Uint8Array): Promise<string> { return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), byte => byte.toString(16).padStart(2,"0")).join(""); }

export async function handleCosmeticAssets(request: Request, env: { ACCOUNT_STORE: DurableObjectNamespace<AccountStore>; RELAY_ROOMS: DurableObjectNamespace<import("./index").RelayRoom>; ROOMS: string; COSMETIC_ASSETS?: R2Bucket }): Promise<Response | null> {
  const url = new URL(request.url); if (!url.pathname.startsWith("/community/cosmetics")) return null;
  const store = env.ACCOUNT_STORE.getByName("global"), token = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1] ?? "";
  const json = (result: AssetResult | object, status?: number) => Response.json(result, { status: status ?? ((result as AssetResult).ok ? 200 : 400), headers: { "Cache-Control": "no-store" } });
  const notify = async (profiles: CosmeticProfile[]) => { for (let offset = 0; offset < profiles.length; offset += 128) await Promise.allSettled(env.ROOMS.split(",").map(room => env.RELAY_ROOMS.getByName(room).cosmeticChanged(profiles.slice(offset, offset + 128)))); };
  try {
    if (url.pathname === "/community/cosmetics" && request.method === "GET") return json(await store.communityCosmeticOverview(token));
    const previewId = url.pathname.match(/^\/community\/cosmetics\/preview\/([a-f0-9]{32})$/)?.[1];
    if (previewId && request.method === "GET") {
      if (!env.COSMETIC_ASSETS) return json({ ok: false, error: "assets_unavailable" }, 503);
      if ([...url.searchParams.keys()].some(key => key !== "uuid") || url.searchParams.getAll("uuid").length !== 1) return json({ ok: false, error: "invalid_request" }, 400);
      const player = url.searchParams.get("uuid")!;
      const allowed = await store.communityCosmeticPreviewAsset(token, previewId, player);
      if (!allowed.ok) return json(allowed, allowed.error === "asset_unavailable" ? 404 : allowed.error === "session_identity_changed" ? 400 : 401);
      const object = await env.COSMETIC_ASSETS.get(allowed.key!);
      if (!object || object.size !== allowed.asset!.size || object.size > CAPE_QUOTA.maxUploadBytes) return json({ ok: false, error: "asset_unavailable" }, 404);
      const bytes = await boundedBytes(object.body, CAPE_QUOTA.maxUploadBytes);
      if (await digest(bytes) !== allowed.asset!.sha256) return json({ ok: false, error: "asset_corrupt" }, 503);
      await validateCapePng(bytes);
      const current = await store.communityCosmeticPreviewAsset(token, previewId, player);
      if (!current.ok || current.asset!.sha256 !== allowed.asset!.sha256 || current.asset!.size !== allowed.asset!.size) return json({ ok: false, error: "asset_unavailable" }, 404);
      return new Response(bytes, { headers: { "Content-Type": "image/png", "Content-Length": String(bytes.length), "Cache-Control": "private, no-store", "ETag": `"${allowed.asset!.sha256}"`, "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'none'" } });
    }
    const assetId = url.pathname.match(/^\/community\/cosmetics\/assets\/([a-f0-9]{32})$/)?.[1];
    if (assetId && request.method === "GET") {
      if (!env.COSMETIC_ASSETS) return json({ ok: false, error: "assets_unavailable" }, 503);
      const allowed = await store.communityCosmeticActiveAsset(assetId, url.searchParams.get("uuid") ?? ""); if (!allowed.ok) return json({ ok: false, error: "asset_unavailable" }, 404);
      const object = await env.COSMETIC_ASSETS.get(allowed.key!); if (!object || object.size !== allowed.asset!.size || object.size > CAPE_QUOTA.maxUploadBytes) return json({ ok: false, error: "asset_unavailable" }, 404);
      const bytes = await boundedBytes(object.body, CAPE_QUOTA.maxUploadBytes); if (await digest(bytes) !== allowed.asset!.sha256) return json({ ok: false, error: "asset_corrupt" }, 503);
      await validateCapePng(bytes);
      const current = await store.communityCosmeticActiveAsset(assetId, url.searchParams.get("uuid") ?? ""); if (!current.ok) return json({ ok: false, error: "asset_unavailable" }, 404);
      return new Response(bytes, { headers: { "Content-Type": "image/png", "Content-Length": String(bytes.length), "Cache-Control": "private, max-age=60", "ETag": `"${allowed.asset!.sha256}"`, "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'none'" } });
    }
    if (url.pathname === "/community/cosmetics/upload" && request.method === "PUT") {
      if (!env.COSMETIC_ASSETS) return json({ ok: false, error: "assets_unavailable" }, 503);
      if (request.headers.get("Content-Type") !== "image/png" || request.headers.has("Content-Encoding") || !request.body) return json({ ok: false, error: "invalid_png" });
      const claim = await store.communityCosmeticClaim(token, request.headers.get("X-SkyMyce-Upload-Grant") ?? ""); if (!claim.ok) return json(claim);
      try {
        if (Number(request.headers.get("Content-Length") ?? 0) > CAPE_QUOTA.maxUploadBytes) deny("upload_too_large");
        const bytes = await validateCapePng(await boundedBytes(request.body, CAPE_QUOTA.maxUploadBytes)), hash = await digest(bytes);
        await env.COSMETIC_ASSETS.put(claim.key!, bytes, { httpMetadata: { contentType: "image/png", cacheControl: "private, max-age=60" }, customMetadata: { sha256: hash } });
        const result = await store.communityCosmeticFinish(token, claim.assetId!, hash, bytes.length);
        if (!result.ok) { await env.COSMETIC_ASSETS.delete(claim.key!); await store.communityCosmeticFail(claim.assetId!); }
        return json(result);
      } catch (error) { await store.communityCosmeticFail(claim.assetId!); await env.COSMETIC_ASSETS.delete(claim.key!).catch(() => {}); return json(failure(error)); }
    }
    if (request.method !== "POST" && !(assetId && request.method === "DELETE") || !request.headers.get("Content-Type")?.startsWith("application/json") || !request.body) return json({ ok: false, error: "invalid_request" });
    const raw: unknown = JSON.parse(new TextDecoder().decode(await boundedBytes(request.body, 8192))); if (!raw || typeof raw !== "object" || Array.isArray(raw)) return json({ ok: false, error: "invalid_request" });
    const input = raw as Record<string, unknown>;
    if (url.pathname === "/community/cosmetics") { const result = await store.communityCosmeticSave(token, input); if (result.ok && result.profile) await notify([result.profile]); return json(result); }
    if (url.pathname === "/community/cosmetics/grant") return env.COSMETIC_ASSETS ? json(await store.communityCosmeticGrant(token, input)) : json({ ok: false, error: "assets_unavailable" }, 503);
    if (assetId) {
      const result = await store.communityCosmeticDelete(token, assetId, input); if (!result.ok) return json(result);
      await notify(result.profiles ?? []); if (env.COSMETIC_ASSETS) await env.COSMETIC_ASSETS.delete(result.key!); return json({ ok: true });
    }
    return json({ ok: false, error: "invalid_request" }, 404);
  } catch (error) { return json(failure(error)); }
}
