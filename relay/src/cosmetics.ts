/** Public rendering data is deliberately separate from private ownership and audit tables. */
import { cosmeticText, type CosmeticText } from "./cosmetic-style";
export type CosmeticProfile = { uuid: string; name: string | null; scale: number; revision: number; updatedAt: number;
  nameStyle: CosmeticText | null; scaleX: number; scaleY: number; scaleZ: number; nametagScale: number; capePreset: string | null; capeAsset: CapeAsset | null };
export type CapeAsset = { id: string; sha256: string; size: number; width: number; height: number };
export const CAPE_PRESETS = [{ id: "sky-myce", label: "Sky-Myce" }, { id: "forest", label: "Forest" }, { id: "sunset", label: "Sunset" }, { id: "void", label: "Void" }];
export type CosmeticsConfig = { DISCORD_APPLICATION_ID?: string; DISCORD_PUBLIC_KEY?: string; DISCORD_INTERACTIONS_ROOM?: string;
  COSMETICS_COMMAND_CHANNEL?: string };
export const COSMETICS_CHANNEL = "1552057526969835612";
export type DiscordCommand = { id: string; actor: string; channel: string;
  action: "link" | "set" | "show" | "reset" | "unlink"; options: Record<string, unknown> };
type CosmeticRow = { uuid: string; display_name: string | null; scale: number; revision: number; updated_at: number;
  name_json: string | null; scale_x: number | null; scale_y: number | null; scale_z: number | null; nametag_scale: number | null; cape_preset: string | null; cape_asset_json: string | null };
const encoder = new TextEncoder();
const ephemeral = (content: string) => ({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
class CosmeticValidation extends Error {}
function message(error: string): never { throw new CosmeticValidation(error); }
export function newLinkCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  // 32 symbols, twelve independent characters: 60 bits, without modulo bias.
  const code = Array.from(crypto.getRandomValues(new Uint8Array(12)), byte => alphabet[byte & 31]).join("");
  return code.match(/.{4}/g)!.join("-");
}
export function normalizedCode(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const code = value.toUpperCase().replaceAll("-", "");
  return /^[A-HJ-NP-Z2-9]{12}$/.test(code) ? code : null;
}
export function cosmeticScale(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0.1 || value > 3) message("Scale must be a number between 0.1 and 3.0.");
  return Math.round(value * 1000) / 1000;
}
export function nametagScale(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0.5 || value > 2) message("Nametag scale must be between 0.5 and 2.0.");
  return Math.round(value * 1000) / 1000;
}
export async function codeHash(code: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(code)))].map(it => it.toString(16).padStart(2, "0")).join("");
}
function publicRow(row: CosmeticRow): CosmeticProfile {
  return { uuid: row.uuid, name: row.display_name, scale: row.scale_y ?? row.scale, revision: row.revision, updatedAt: row.updated_at,
    nameStyle: row.name_json ? JSON.parse(row.name_json) : row.display_name ? { text: row.display_name } : null,
    scaleX: row.scale_x ?? row.scale, scaleY: row.scale_y ?? row.scale, scaleZ: row.scale_z ?? row.scale,
    nametagScale: row.nametag_scale ?? 1, capePreset: row.cape_preset ?? null, capeAsset: row.cape_asset_json ? JSON.parse(row.cape_asset_json) : null };
}
export function cosmeticPayload(profile: CosmeticProfile, version?: number): object {
  if (version === 4) return profile;
  const { nametagScale: _nametag, capePreset: _preset, capeAsset: _asset, ...previous } = profile;
  if (version === 3) return previous;
  if (version === 2) return { ...previous, scale: Math.max(0.5, Math.min(2, profile.scale)),
    scaleX: Math.max(0.5, Math.min(2, profile.scaleX)), scaleY: Math.max(0.5, Math.min(2, profile.scaleY)),
    scaleZ: Math.max(0.5, Math.min(2, profile.scaleZ)) };
  const { uuid, name, scale, revision, updatedAt } = profile;
  // Older clients reject extra fields and names over 24 characters. Fall back to their real IGN.
  return { uuid, name: name && name.length > 24 ? null : name, scale: Math.max(0.5, Math.min(2, scale)), revision, updatedAt };
}

export class PlayerCosmetics {
  constructor(private readonly storage: DurableObjectStorage) {
    const sql = storage.sql;
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_public (uuid TEXT PRIMARY KEY, display_name TEXT, scale REAL NOT NULL DEFAULT 1,
      revision INTEGER NOT NULL, updated_at INTEGER NOT NULL)`);
    const columns = new Set(sql.exec<{ name: string }>("PRAGMA table_info(cosmetics_public)").toArray().map(row => row.name));
    for (const [column, definition] of Object.entries({ name_json: "TEXT", scale_x: "REAL", scale_y: "REAL", scale_z: "REAL", nametag_scale: "REAL", cape_preset: "TEXT", cape_asset_json: "TEXT" })) {
      if (!columns.has(column)) sql.exec(`ALTER TABLE cosmetics_public ADD COLUMN ${column} ${definition}`);
    }
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_links (discord TEXT PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, linked_at INTEGER NOT NULL)`);
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_challenges (hash TEXT PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, expires INTEGER NOT NULL, issued INTEGER NOT NULL)`);
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_interactions (id TEXT PRIMARY KEY, expires INTEGER NOT NULL)`);
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_limits (actor TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL)`);
    sql.exec(`CREATE TABLE IF NOT EXISTS cosmetics_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, actor TEXT NOT NULL,
      action TEXT NOT NULL, revision INTEGER NOT NULL, at INTEGER NOT NULL)`);
  }
  get(uuid: string): CosmeticProfile {
    const row = this.storage.sql.exec<CosmeticRow>("SELECT * FROM cosmetics_public WHERE uuid = ?", uuid).toArray()[0];
    return row ? publicRow(row) : { uuid, name: null, scale: 1, revision: 0, updatedAt: 0, nameStyle: null, scaleX: 1, scaleY: 1, scaleZ: 1, nametagScale: 1, capePreset: null, capeAsset: null };
  }
  import(profile: CosmeticProfile): void {
    if (!/^[a-f0-9]{32}$/.test(profile.uuid) || !Number.isSafeInteger(profile.revision) || profile.revision < 0 || this.get(profile.uuid).revision > 0) return;
    this.storage.sql.exec("INSERT OR IGNORE INTO cosmetics_public(uuid,display_name,scale,revision,updated_at,name_json,scale_x,scale_y,scale_z,nametag_scale,cape_preset,cape_asset_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL)",
      profile.uuid, profile.name, profile.scale, profile.revision, profile.updatedAt, profile.nameStyle ? JSON.stringify(profile.nameStyle) : null, profile.scaleX, profile.scaleY, profile.scaleZ, profile.nametagScale ?? 1, profile.capePreset ?? null);
  }
  replicate(profile: CosmeticProfile): void {
    if (!/^[a-f0-9]{32}$/.test(profile.uuid) || !Number.isSafeInteger(profile.revision) || profile.revision < this.get(profile.uuid).revision) return;
    this.storage.sql.exec(`INSERT INTO cosmetics_public(uuid,display_name,scale,revision,updated_at,name_json,scale_x,scale_y,scale_z,nametag_scale,cape_preset,cape_asset_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(uuid) DO UPDATE SET display_name=excluded.display_name,scale=excluded.scale,revision=excluded.revision,updated_at=excluded.updated_at,name_json=excluded.name_json,
      scale_x=excluded.scale_x,scale_y=excluded.scale_y,scale_z=excluded.scale_z,nametag_scale=excluded.nametag_scale,cape_preset=excluded.cape_preset,cape_asset_json=excluded.cape_asset_json`,
      profile.uuid, profile.name, profile.scale, profile.revision, profile.updatedAt, profile.nameStyle ? JSON.stringify(profile.nameStyle) : null,
      profile.scaleX, profile.scaleY, profile.scaleZ, profile.nametagScale, profile.capePreset, profile.capeAsset ? JSON.stringify(profile.capeAsset) : null);
  }
  save(uuid: string, revision: number, raw: unknown, asset: CapeAsset | null, actor: string): { ok: boolean; error?: string; profile: CosmeticProfile } {
    return this.storage.transactionSync(() => {
      const current = this.get(uuid);
      if (!Number.isSafeInteger(revision) || revision < 0) return { ok: false, error: "invalid_revision", profile: current };
      if (revision !== current.revision) return { ok: false, error: "revision_conflict", profile: current };
      try {
        if (!raw || typeof raw !== "object" || Array.isArray(raw)) message("Invalid profile.");
        const value = raw as Record<string, unknown>, keys = ["name", "scaleX", "scaleY", "scaleZ", "nametagScale", "capePreset", "capeAsset"];
        if (keys.some(key => !Object.hasOwn(value, key)) || Object.keys(value).some(key => !keys.includes(key))) message("Invalid profile fields.");
        let text = { plain: null as string | null, component: null as CosmeticText | null };
        if (value.name !== null) { try { text = cosmeticText(typeof value.name === "string" ? value.name : JSON.stringify(value.name)); } catch { message("Invalid cosmetic name."); } }
        const x = cosmeticScale(value.scaleX), y = cosmeticScale(value.scaleY), z = cosmeticScale(value.scaleZ), tag = nametagScale(value.nametagScale);
        if (value.capePreset !== null && value.capePreset !== "none" && !CAPE_PRESETS.some(preset => preset.id === value.capePreset)) message("Invalid cape preset.");
        if (value.capeAsset !== null && (!asset || value.capeAsset !== asset.id) || value.capeAsset !== null && value.capePreset !== null) message("Invalid cape selection.");
        const now = Date.now();
        this.storage.sql.exec(`INSERT INTO cosmetics_public(uuid,display_name,scale,revision,updated_at,name_json,scale_x,scale_y,scale_z,nametag_scale,cape_preset,cape_asset_json) VALUES(?,?,?,1,?,?,?,?,?,?,?,?)
          ON CONFLICT(uuid) DO UPDATE SET display_name=excluded.display_name,scale=excluded.scale,revision=cosmetics_public.revision+1,updated_at=excluded.updated_at,name_json=excluded.name_json,
          scale_x=excluded.scale_x,scale_y=excluded.scale_y,scale_z=excluded.scale_z,nametag_scale=excluded.nametag_scale,cape_preset=excluded.cape_preset,cape_asset_json=excluded.cape_asset_json`,
          uuid, text.plain, y, now, text.component ? JSON.stringify(text.component) : null, x, y, z, tag, value.capePreset, asset ? JSON.stringify(asset) : null);
        const profile = this.get(uuid); this.audit(uuid, actor, "studio_save", profile.revision, now); return { ok: true, profile };
      } catch (error) { if (error instanceof CosmeticValidation) return { ok: false, error: "invalid_profile", profile: current }; throw error; }
    });
  }
  clearAsset(id: string): CosmeticProfile[] {
    return this.storage.transactionSync(() => {
      const rows = this.storage.sql.exec<{ uuid: string }>("SELECT uuid FROM cosmetics_public WHERE json_extract(cape_asset_json,'$.id')=?", id).toArray();
      this.storage.sql.exec("UPDATE cosmetics_public SET cape_asset_json=NULL,revision=revision+1,updated_at=? WHERE json_extract(cape_asset_json,'$.id')=?", Date.now(), id);
      return rows.map(row => this.get(row.uuid));
    });
  }
  issue(uuid: string, hash: string, now: number): { error?: string; expiresAt?: number } {
    return this.storage.transactionSync(() => {
      this.storage.sql.exec("DELETE FROM cosmetics_challenges WHERE expires <= ?", now);
      const previous = this.storage.sql.exec<{ issued: number }>("SELECT issued FROM cosmetics_challenges WHERE uuid = ?", uuid).toArray()[0];
      if (previous && now - previous.issued < 60000) return { error: "rate_limited" };
      const expiresAt = now + 300000;
      this.storage.sql.exec("INSERT INTO cosmetics_challenges (hash, uuid, expires, issued) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash, expires=excluded.expires, issued=excluded.issued",
        hash, uuid, expiresAt, now);
      return { expiresAt };
    });
  }
  async command(command: DiscordCommand, env: CosmeticsConfig, now: number, authority?: { uuid: string }): Promise<{ response: object; changed?: CosmeticProfile }> {
    if (command.channel !== (env.COSMETICS_COMMAND_CHANNEL ?? COSMETICS_CHANNEL)) return { response: ephemeral(`Use cosmetic commands in <#${env.COSMETICS_COMMAND_CHANNEL ?? COSMETICS_CHANNEL}>.`) };
    const code = normalizedCode(command.options.code);
    const hash = command.action === "link" && code ? await codeHash(code) : null;
    // All authorization, challenge consumption and revision changes are one synchronous transaction.
    try { return this.storage.transactionSync(() => {
      const sql = this.storage.sql;
      sql.exec("DELETE FROM cosmetics_interactions WHERE expires <= ?", now);
      sql.exec("DELETE FROM cosmetics_limits WHERE started < ?", now - 3600000);
      if (sql.exec("SELECT id FROM cosmetics_interactions WHERE id = ?", command.id).toArray().length) return { response: ephemeral("This interaction was already handled. Use /cosmetics show to check your settings.") };
      sql.exec("INSERT INTO cosmetics_interactions (id, expires) VALUES (?, ?)", command.id, now + 900000);
      const limit = sql.exec<{ started: number; count: number }>("SELECT started, count FROM cosmetics_limits WHERE actor = ?", command.actor).toArray()[0];
      const fresh = !limit || now - limit.started >= 60000;
      const count = fresh ? 1 : limit.count + 1;
      sql.exec("INSERT INTO cosmetics_limits (actor, started, count) VALUES (?, ?, ?) ON CONFLICT(actor) DO UPDATE SET started=excluded.started,count=excluded.count", command.actor, fresh ? now : limit.started, count);
      if (count > 6) return { response: ephemeral("Too many cosmetic commands. Wait a minute and try again.") };
      try {
        if (command.action === "link") {
          if (authority) message("Use the Sky-Myce account linking command.");
          if (!hash || Object.keys(command.options).some(it => it !== "code")) message("Use the one-time code shown by /sm cosmetics link in Minecraft.");
          const challenge = sql.exec<{ uuid: string; expires: number }>("SELECT uuid, expires FROM cosmetics_challenges WHERE hash = ?", hash).toArray()[0];
          if (!challenge || challenge.expires <= now) message("That link code is expired or was already used. Request a new one in Minecraft.");
          const existing = sql.exec<{ uuid: string }>("SELECT uuid FROM cosmetics_links WHERE discord = ?", command.actor).toArray()[0];
          if (existing && existing.uuid !== challenge.uuid) message("Unlink your current Minecraft account first. The new code has not been consumed.");
          // Retain issuance time after consumption so relinking cannot bypass the UUID cooldown.
          sql.exec("UPDATE cosmetics_challenges SET hash = ? WHERE hash = ?", `used:${hash}`, hash);
          // A new authenticated Minecraft challenge explicitly transfers this account's Discord link.
          sql.exec("DELETE FROM cosmetics_links WHERE uuid = ? OR discord = ?", challenge.uuid, command.actor);
          sql.exec("INSERT INTO cosmetics_links (discord, uuid, linked_at) VALUES (?, ?, ?)", command.actor, challenge.uuid, now);
          this.audit(challenge.uuid, command.actor, "link", this.get(challenge.uuid).revision, now);
          return { response: ephemeral("Minecraft account linked. Any previous Discord owner of this Minecraft account has lost access. Use /cosmetics set to customize your cosmetics.") };
        }
        const linked = authority?.uuid ?? sql.exec<{ uuid: string }>("SELECT uuid FROM cosmetics_links WHERE discord = ?", command.actor).toArray()[0]?.uuid;
        const target = linked;
        if (!target) message("Link your Minecraft account first with /sm cosmetics link, then /cosmetics link.");
        const allowed = command.action === "set" ? ["name", "x", "y", "z"] : command.action === "unlink" ? ["confirm"] : [];
        if (Object.keys(command.options).some(it => !allowed.includes(it))) message("Unsupported cosmetic option.");
        const current = this.get(target);
        if (command.action === "show") return { response: ephemeral(`Minecraft UUID: ${target}\nDisplay name: ${current.name ?? "real IGN"}\nSize X: ${current.scaleX}, Y: ${current.scaleY}, Z: ${current.scaleZ}\nRevision: ${current.revision}\nOnly supporting Sky-Myce clients see these settings.`) };
        if (command.action === "unlink" && command.options.confirm !== true) message("Use /cosmetics unlink confirm:true to remove the account link and reset cosmetics.");
        if (command.action === "set" && Object.keys(command.options).length === 0) message("Provide name, x, y or z, or use /cosmetics reset.");
        const reset = command.action === "reset" || command.action === "unlink";
        let name = { plain: current.name, component: current.nameStyle };
        if (reset) name = { plain: null, component: null };
        else if (command.options.name !== undefined) { try { name = cosmeticText(command.options.name); } catch (error) { message((error as Error).message); } }
        const axis = (key: string, previous: number) => reset ? 1 : command.options[key] === undefined ? previous : cosmeticScale(command.options[key]);
        const x = axis("x", current.scaleX), y = axis("y", current.scaleY), z = axis("z", current.scaleZ);
        // Keep defaults as revisioned tombstones: stale caches cannot resurrect a reset.
        sql.exec(`INSERT INTO cosmetics_public (uuid, display_name, scale, revision, updated_at, name_json, scale_x, scale_y, scale_z) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
          ON CONFLICT(uuid) DO UPDATE SET display_name=excluded.display_name,scale=excluded.scale,revision=cosmetics_public.revision+1,updated_at=excluded.updated_at,
          name_json=excluded.name_json,scale_x=excluded.scale_x,scale_y=excluded.scale_y,scale_z=excluded.scale_z`,
          target, name.plain, y, now, name.component ? JSON.stringify(name.component) : null, x, y, z);
        if (command.action === "unlink" && !authority) {
          sql.exec("DELETE FROM cosmetics_links WHERE discord = ?", command.actor);
          sql.exec("UPDATE cosmetics_challenges SET hash = 'revoked:' || hash WHERE uuid = ?", target);
        }
        if (reset) sql.exec("UPDATE cosmetics_public SET nametag_scale=1,cape_preset=NULL,cape_asset_json=NULL WHERE uuid=?", target);
        const changed = this.get(target);
        this.audit(target, command.actor, command.action, changed.revision, now);
        return { response: ephemeral(command.action === "unlink" ? "Account unlinked and cosmetics reset. Obtain a fresh in-game code to link again." :
          `Saved. Name: ${name.plain ?? "real IGN"}; X: ${x}, Y: ${y}, Z: ${z}. Supporting clients update automatically.`), changed };
      } catch (error) {
        // Only deliberate validation messages leave this method; SQL/runtime failures stay generic.
        if (error instanceof CosmeticValidation) return { response: ephemeral(error.message) };
        throw error; // Roll back every database mutation on an unexpected storage failure.
      }
    }); } catch { return { response: ephemeral("Cosmetic settings are temporarily unavailable. Please retry.") }; }
  }
  private audit(uuid: string, actor: string, action: string, revision: number, now: number): void {
    this.storage.sql.exec("INSERT INTO cosmetics_audit (uuid, actor, action, revision, at) VALUES (?, ?, ?, ?, ?)", uuid, actor, action, revision, now);
    this.storage.sql.exec("DELETE FROM cosmetics_audit WHERE id <= (SELECT MAX(id) - 5000 FROM cosmetics_audit)");
  }
}

/** A bounded subscription bloom filter fits hibernating attachments. False positives expose public profiles only. */
export function watchCosmetics(previous: string | undefined, ids: string[]): string {
  const bits = previous && /^[a-f0-9]{128}$/.test(previous) ? Uint8Array.from(previous.match(/../g)!, it => parseInt(it, 16)) : new Uint8Array(64);
  for (const id of ids) for (const bit of positions(id)) bits[bit >>> 3] |= 1 << (bit & 7);
  return [...bits].map(it => it.toString(16).padStart(2, "0")).join("");
}
export function watchesCosmetic(filter: string | undefined, id: string): boolean {
  return !!filter && positions(id).every(bit => (parseInt(filter.substring((bit >>> 3) * 2, (bit >>> 3) * 2 + 2), 16) & (1 << (bit & 7))) !== 0);
}
function positions(id: string): number[] {
  let a = 2166136261, b = 5381;
  for (const char of id) { a = Math.imul(a ^ char.charCodeAt(0), 16777619); b = Math.imul(b, 33) ^ char.charCodeAt(0); }
  return [a >>> 0 & 511, b >>> 0 & 511, (a + b) >>> 0 & 511];
}
