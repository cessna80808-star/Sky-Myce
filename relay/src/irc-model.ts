/** Chat bodies are live-only; these bounded previews identify recent replies and mutations. */
export const IRC_MANAGEMENT_MS = 15 * 60_000;
export const IRC_EMOJI = ["👍", "❤️", "😂", "🎉", "👀", "🔥"] as const;
export type IrcScope = "room" | "global" | "party";
export type IrcMedia = { id: string; type: "image" | "gif" | "video"; url: string; thumbnailUrl?: string; sourceUrl?: string;
  name?: string; mime?: string; bytes?: number; width?: number; height?: number };
export type IrcPublicSender = { uuid?: string; name: string; cosmeticName?: string; discordId: string; avatarUrl?: string };
export type IrcReply = { messageId?: string; discordMessageId?: string; from: string; text: string };
export type IrcReaction = { emoji: string; count: number; mine?: boolean };
export type IrcRecord = { id: string; scope: IrcScope; room: string; source: "minecraft" | "discord"; sourceId: string;
  from: string; discordId: string; uuid?: string; sender: IrcPublicSender; preview: string; media: IrcMedia[]; reply?: IrcReply;
  revision: number; timestamp: number; expiresAt: number; discordMessageId?: string; editedAt?: number; deleted?: boolean; bodyDigest?: string; bodyRevision?: number;
  /** A complete Discord link-preview snapshot set the media; older fragments may no longer add to it. */
  mediaSnapshot?: true };
export type IrcOperation = { id: string; messageId: string; operation: "edit" | "delete" | "reaction"; text?: string;
  emoji?: string; active?: boolean; media?: IrcMedia[]; editedAt?: number; bodyDigest?: string; mediaOnly?: true; snapshot?: true };
export async function ircTextDigest(text: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)))].map(byte => byte.toString(16).padStart(2, "0")).join("");
}
export type IrcUpdate = { type: "irc_update"; model: 1; id: string; scope: IrcScope; room: string; messageId: string;
  operation: IrcOperation["operation"]; revision: number; text?: string; media?: IrcMedia[]; reactions: IrcReaction[]; sender: IrcPublicSender; reply?: IrcReply };
const id = /^[a-f0-9]{32}$/;
const controls = /[\x00-\x09\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/;
const graphemes = new Intl.Segmenter("en", { granularity: "grapheme" });
export const ircPlainText = (value: unknown, maximum: number): value is string => typeof value === "string" &&
  value.trim().length > 0 && value.length <= maximum && value.isWellFormed() && !controls.test(value);
export function ircMentionNames(text: string): string[] {
  const names = new Set<string>();
  // Only typed usernames are candidates; URLs, Discord IDs and mass mentions stay plain text.
  for (const match of text.replace(/https?:\/\/\S+/giu, "").matchAll(/(?:^|[^A-Za-z0-9_.@<])@([A-Za-z0-9_][A-Za-z0-9_.]{0,31})(?=$|[^A-Za-z0-9_.])/gu)) {
    const name = match[1].replace(/\.+$/, "").toLowerCase();
    if (name !== "everyone" && name !== "here") names.add(name);
    if (names.size >= 8) break;
  }
  return [...names];
}
export function ircPreview(value: string, maximum = 256): string {
  const safe = value.toWellFormed().replace(/[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/g, " ");
  let end = 0;
  for (const part of graphemes.segment(safe)) {
    if (part.index + part.segment.length > maximum) break;
    end = part.index + part.segment.length;
  }
  for (const match of safe.matchAll(/<a?:[A-Za-z0-9_]{1,32}:\d{17,20}>/gu))
    if (match.index < end && match.index + match[0].length > end) end = match.index;
  return safe.slice(0, end);
}
export function ircMediaUrl(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length > 2048 || controls.test(value)) return;
  try {
    const url = new URL(value), host = url.hostname.toLowerCase();
    if (url.protocol !== "https:" || url.username || url.password || url.port && url.port !== "443" ||
        !host.includes(".") || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal") ||
        host === "localhost" || host.startsWith("[") || /^\d+(?:\.\d+){3}$/.test(host)) return;
    return url.href;
  } catch { return; }
}
export function ircMediaKey(value: string): string {
  const url = new URL(value);
  if (["cdn.discordapp.com", "media.discordapp.net"].includes(url.hostname) && /^\/attachments\/\d{1,20}\/\d{1,20}\/[^/]+$/.test(url.pathname)) {
    url.hostname = "cdn.discordapp.com";
    for (const key of ["ex", "is", "hm"]) url.searchParams.delete(key);
  }
  return url.href;
}
function mediaType(url: string, mime?: string): IrcMedia["type"] | undefined {
  const path = new URL(url).pathname.toLowerCase();
  if (mime === "image/gif" || path.endsWith(".gif")) return "gif";
  if (mime?.startsWith("video/") || /\.(mp4|webm|mov|m4v)$/.test(path)) return "video";
  if (mime?.startsWith("image/") || /\.(png|jpe?g|webp|avif)$/.test(path)) return "image";
}
/** Metadata is a hint. The client still validates addresses, redirects, bytes and the actual decoder input. */
export function ircMedia(text: string, supplied?: unknown): IrcMedia[] | undefined {
  if (supplied !== undefined && (!Array.isArray(supplied) || supplied.length > 4)) return;
  const entries = new Map<string, IrcMedia>();
  for (const raw of (supplied as unknown[] | undefined) ?? []) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return;
    const candidate = raw as Record<string, unknown>, url = ircMediaUrl(candidate.url);
    if (!url || typeof candidate.id !== "string" || !/^[A-Za-z0-9_-]{1,80}$/.test(candidate.id) ||
        !["image", "gif", "video"].includes(String(candidate.type)) || candidate.thumbnailUrl !== undefined && !ircMediaUrl(candidate.thumbnailUrl) ||
        candidate.sourceUrl !== undefined && !ircMediaUrl(candidate.sourceUrl) ||
        candidate.name !== undefined && (typeof candidate.name !== "string" || candidate.name.length > 80 || controls.test(candidate.name)) ||
        candidate.mime !== undefined && (typeof candidate.mime !== "string" || candidate.mime.length > 96 || !/^(image|video)\/[A-Za-z0-9.+-]+$/.test(candidate.mime)) ||
        ["bytes", "width", "height"].some(key => candidate[key] !== undefined && (!Number.isSafeInteger(candidate[key]) || Number(candidate[key]) < 0 || Number(candidate[key]) > (key === "bytes" ? 100_000_000 : 32768)))) return;
    const media: IrcMedia = { id: candidate.id, type: candidate.type as IrcMedia["type"], url,
      ...(candidate.thumbnailUrl ? { thumbnailUrl: ircMediaUrl(candidate.thumbnailUrl) } : {}),
      ...(candidate.sourceUrl ? { sourceUrl: ircMediaUrl(candidate.sourceUrl) } : {}),
      ...(candidate.name !== undefined ? { name: ircPreview(candidate.name as string, 80) } : {}),
      ...(candidate.mime !== undefined ? { mime: candidate.mime as string } : {}),
      ...Object.fromEntries(["bytes", "width", "height"].filter(key => candidate[key] !== undefined).map(key => [key, candidate[key]])) };
    entries.set(ircMediaKey(url), media);
  }
  if (new Set([...entries.values()].map(item => item.id)).size !== entries.size) return;
  for (const match of text.matchAll(/https:\/\/[^\s<>"`]+/gu)) {
    const url = ircMediaUrl(match[0].replace(/[),.;\]]+$/, ""));
    if (!url || !mediaType(url)) continue;
    const key = ircMediaKey(url), previous = entries.get(key);
    // Preserve the actual supplied resource; its original link or preview thumbnail (e.g. Klipy's webp poster) is not a second item.
    if (previous || [...entries.values()].some(item => [item.sourceUrl, item.thumbnailUrl].some(other => other && ircMediaKey(other) === key))) continue;
    entries.set(key, { id: `link-${entries.size}`, type: mediaType(url)!, url });
    if (entries.size >= 4) break;
  }
  return [...entries.values()].slice(0, 4);
}
export function ircOperation(data: Record<string, unknown>, maximum: number, native = false): IrcOperation | undefined {
  if (typeof data.id !== "string" || !id.test(data.id) || typeof data.messageId !== "string" || !id.test(data.messageId) ||
      !["edit", "delete", "reaction"].includes(String(data.operation))) return;
  const operation = data.operation as IrcOperation["operation"];
  if (operation === "edit" && !ircPlainText(data.text, maximum) || operation === "reaction" &&
      (!(IRC_EMOJI as readonly string[]).includes(String(data.emoji)) || typeof data.active !== "boolean")) return;
  const media = operation === "edit" ? ircMedia(data.text as string, native ? data.media : undefined) : undefined;
  if (operation === "edit" && !media || native && data.editedAt !== undefined && (!Number.isSafeInteger(data.editedAt) || Number(data.editedAt) < 0 || Number(data.editedAt) > Date.now() + 60_000)) return;
  return { id: data.id, messageId: data.messageId, operation,
    ...(operation === "edit" ? { text: data.text as string, media, ...(data.editedAt !== undefined ? { editedAt: data.editedAt as number } : {}) } : {}),
    ...(operation === "reaction" ? { emoji: data.emoji as string, active: data.active as boolean } : {}) };
}

export class IrcLifecycle {
  constructor(private storage: DurableObjectStorage) {
    storage.sql.exec(`CREATE TABLE IF NOT EXISTS irc_metadata (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, discord_message_id TEXT, record TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS irc_metadata_source ON irc_metadata(source_id);
      CREATE INDEX IF NOT EXISTS irc_metadata_discord ON irc_metadata(discord_message_id);
      CREATE TABLE IF NOT EXISTS irc_reactions (message_id TEXT NOT NULL, emoji TEXT NOT NULL, actor TEXT NOT NULL, PRIMARY KEY(message_id,emoji,actor));
      CREATE TABLE IF NOT EXISTS irc_operations_seen (actor TEXT NOT NULL, id TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(actor,id));
      CREATE TABLE IF NOT EXISTS irc_operation_limits (actor TEXT PRIMARY KEY, credits REAL NOT NULL, updated INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS irc_operation_outbox (id TEXT PRIMARY KEY, packet TEXT NOT NULL, next_at INTEGER NOT NULL, attempts INTEGER NOT NULL, expires INTEGER NOT NULL)`);
  }
  prune(now = Date.now()): void {
    const sql = this.storage.sql;
    sql.exec("DELETE FROM irc_metadata WHERE expires<=?", now);
    sql.exec("DELETE FROM irc_metadata WHERE rowid NOT IN (SELECT rowid FROM irc_metadata ORDER BY rowid DESC LIMIT 1024)");
    sql.exec("DELETE FROM irc_reactions WHERE message_id NOT IN (SELECT id FROM irc_metadata)");
    sql.exec("DELETE FROM irc_operations_seen WHERE expires<=?", now);
    sql.exec("DELETE FROM irc_operations_seen WHERE rowid NOT IN (SELECT rowid FROM irc_operations_seen ORDER BY rowid DESC LIMIT 4096)");
    sql.exec("DELETE FROM irc_operation_outbox WHERE expires<=?", now);
    sql.exec("DELETE FROM irc_operation_limits WHERE updated<?", now - IRC_MANAGEMENT_MS);
  }
  remember(record: IrcRecord): void {
    this.storage.sql.exec("INSERT OR IGNORE INTO irc_metadata VALUES(?,?,?,?,?)", record.id, record.sourceId,
      record.discordMessageId ?? null, JSON.stringify(record), record.expiresAt);
    this.prune();
  }
  get(messageId: string): IrcRecord | undefined {
    const row = this.storage.sql.exec<{ record: string }>("SELECT record FROM irc_metadata WHERE id=? AND expires>?", messageId, Date.now()).toArray()[0];
    return row ? JSON.parse(row.record) as IrcRecord : undefined;
  }
  fromDiscord(messageId: string): IrcRecord | undefined {
    const row = this.storage.sql.exec<{ record: string }>("SELECT record FROM irc_metadata WHERE (source_id=? OR discord_message_id=?) AND expires>? ORDER BY CASE WHEN source_id=? THEN 0 ELSE 1 END LIMIT 1", messageId, messageId, Date.now(), messageId).toArray()[0];
    return row ? JSON.parse(row.record) as IrcRecord : undefined;
  }
  receipt(messageId: string, discordMessageId: string): void {
    const record = this.get(messageId);
    if (!record || record.discordMessageId && record.discordMessageId !== discordMessageId) return;
    record.discordMessageId = discordMessageId;
    this.save(record);
  }
  private save(record: IrcRecord): void {
    this.storage.sql.exec("UPDATE irc_metadata SET record=?,discord_message_id=? WHERE id=?", JSON.stringify(record), record.discordMessageId ?? null, record.id);
  }
  reply(messageId: string): IrcReply {
    const record = this.get(messageId);
    return record && !record.deleted ? { messageId: record.id, ...(record.discordMessageId ? { discordMessageId: record.discordMessageId } : {}), from: record.from, text: record.preview } :
      { messageId, from: "Unavailable message", text: "The original message is no longer available." };
  }
  reactions(messageId: string): IrcReaction[] {
    return this.storage.sql.exec<{ emoji: string; count: number }>("SELECT emoji,COUNT(*) AS count FROM irc_reactions WHERE message_id=? GROUP BY emoji ORDER BY emoji", messageId).toArray();
  }
  minecraftReacted(messageId: string, emoji: string): boolean {
    return !!this.storage.sql.exec("SELECT 1 FROM irc_reactions WHERE message_id=? AND emoji=? AND actor LIKE 'mc:%' LIMIT 1", messageId, emoji).toArray().length;
  }
  operate(actor: string, operation: IrcOperation, owner: { uuid?: string; discordId?: string; native?: boolean },
      queue?: (update: IrcUpdate, record: IrcRecord) => void): { error?: string; update?: IrcUpdate; record?: IrcRecord } {
    return this.storage.transactionSync(() => {
      this.prune();
      const sql = this.storage.sql, now = Date.now(), record = this.get(operation.messageId);
      if (sql.exec("SELECT 1 FROM irc_operations_seen WHERE actor=? AND id=?", actor, operation.id).toArray().length) return {};
      if (!record) return { error: "message_expired" };
      if (record.deleted) return operation.operation === "delete" ? {} : { error: "message_deleted" };
      if (operation.operation !== "reaction" && (owner.native ? record.source === "discord" && record.discordId !== owner.discordId :
          record.source !== "minecraft" || record.uuid !== owner.uuid || record.discordId !== owner.discordId)) return { error: "not_authorized" };
      const limit = sql.exec<{ credits: number; updated: number }>("SELECT credits,updated FROM irc_operation_limits WHERE actor=?", actor).toArray()[0];
      const credits = Math.min(12, limit ? limit.credits + (now - limit.updated) / 2000 : 12);
      if (credits < 1) return { error: "rate_limited" };
      // Unversioned legacy embed fragments cannot prove they belong to an already edited body.
      if (operation.mediaOnly && (record.bodyRevision !== undefined || record.editedAt !== undefined || !operation.snapshot && record.mediaSnapshot)) return {};
      // Discord can enrich embeds without changing the actual edit timestamp.
      if (operation.operation === "edit" && operation.editedAt !== undefined && (record.editedAt ?? 0) > operation.editedAt) return {};
      sql.exec("INSERT INTO irc_operation_limits VALUES(?,?,?) ON CONFLICT(actor) DO UPDATE SET credits=excluded.credits,updated=excluded.updated", actor, credits - 1, now);
      sql.exec("INSERT INTO irc_operations_seen VALUES(?,?,?)", actor, operation.id, now + IRC_MANAGEMENT_MS);
      let changed = true;
      if (operation.operation === "reaction") {
        const active = !!sql.exec("SELECT 1 FROM irc_reactions WHERE message_id=? AND emoji=? AND actor=?", record.id, operation.emoji!, actor).toArray().length;
        changed = active !== operation.active;
        if (operation.active) {
          const count = sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM irc_reactions WHERE message_id=?", record.id).one().count;
          if (!active && count >= 384) return { error: "reaction_limit" };
          sql.exec("INSERT OR IGNORE INTO irc_reactions VALUES(?,?,?)", record.id, operation.emoji!, actor);
        } else sql.exec("DELETE FROM irc_reactions WHERE message_id=? AND emoji=? AND actor=?", record.id, operation.emoji!, actor);
      } else if (operation.operation === "delete") {
        record.deleted = true; record.preview = ""; record.media = []; delete record.reply; delete record.bodyDigest;
        sql.exec("DELETE FROM irc_reactions WHERE message_id=?", record.id);
      } else {
        changed = !operation.mediaOnly && record.bodyDigest !== operation.bodyDigest || JSON.stringify(record.media) !== JSON.stringify(operation.media);
        record.media = operation.media!;
        if (operation.snapshot) record.mediaSnapshot = true;
        if (!operation.mediaOnly) {
          record.preview = ircPreview(operation.text!); record.bodyDigest = operation.bodyDigest;
          record.bodyRevision = record.revision + (changed ? 1 : 0);
          // An absent native timestamp is not a later Discord edit. Do not invent one that
          // would reject a genuine timestamped update queued before this server received it.
          if (operation.editedAt !== undefined || !owner.native) record.editedAt = operation.editedAt ?? now;
        }
      }
      if (!changed) { if (operation.operation === "edit") this.save(record); return {}; }
      record.revision++;
      this.save(record);
      const update: IrcUpdate = { type: "irc_update", model: 1, id: operation.id, scope: record.scope, room: record.room,
        messageId: record.id, operation: operation.operation, revision: record.revision, sender: record.sender,
        reactions: this.reactions(record.id), ...(record.reply ? { reply: record.reply } : {}),
        ...(operation.operation === "edit" ? { ...(!operation.mediaOnly ? { text: operation.text! } : {}), media: record.media } : {}) };
      queue?.(update, record);
      return { update, record };
    });
  }
}
