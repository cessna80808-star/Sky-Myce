import { createHash, randomBytes } from 'node:crypto';
import { open, writeFile, rename, mkdir, unlink } from 'node:fs/promises';
import { dirname } from 'node:path';
import { snowflake } from './config.mjs';

export const GLOBAL_ROOM = 'global-chat';
const packetId = /^[a-f0-9]{32}$/;
const unsafeDisplay = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/gu;
const unsafeBody = /[\x00-\x08\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/gu;
const noMentions = { parse: [], repliedUser: false };

function bounded(value, maximum, suffix = '') {
  if (value.length <= maximum) return value;
  const prefix = value.slice(0, maximum - suffix.length);
  return prefix.replace(/[\ud800-\udbff]$/u, '') + suffix;
}
function display(user, member) {
  const value = member?.displayName ?? user?.globalName ?? user?.username ?? 'Discord user';
  return bounded(String(value).replace(unsafeDisplay, ''), 64) || 'Discord user';
}
function values(collection) { return typeof collection?.values === 'function' ? [...collection.values()] : Array.isArray(collection) ? collection : []; }
function link(value) {
  if (typeof value !== 'string' || value.length > 8192) return '';
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''; }
  catch { return ''; }
}
function readable(value, message) {
  return String(value).replace(/<(@!?|@&|#)(\d{17,20})>/g, (_, kind, id) => {
    if (kind === '#') return `#${message.guild?.channels?.cache?.get(id)?.name ?? 'channel'}`;
    if (kind === '@&') return `@${message.guild?.roles?.cache?.get(id)?.name ?? 'role'}`;
    return `@${display(message.mentions?.users?.get?.(id), message.guild?.members?.cache?.get(id))}`;
  }).replace(/<a?:([A-Za-z0-9_]{1,32}):\d{17,20}>/g, ':$1:');
}
const bodyUrl = /https?:\/\/[^\s<>"`]+/gu;
function textLink(value, offset, text) {
  const wrapper = text[offset - 1];
  const suffix = wrapper === '(' && value.endsWith(')') ? ')' : wrapper === '[' && value.endsWith(']') ? ']' : '';
  return { url: link(suffix ? value.slice(0, -1) : value), suffix };
}
function attachmentUrl(url) {
  return url.protocol === 'https:' && !url.port && ['cdn.discordapp.com', 'media.discordapp.net'].includes(url.hostname) &&
    /^\/attachments\/\d{1,20}\/\d{1,20}\/[^/]+$/u.test(url.pathname);
}
export function discordMediaKey(value) {
  const valid = link(value); if (!valid) return '';
  const url = new URL(valid);
  if (attachmentUrl(url)) {
    url.hostname = 'cdn.discordapp.com';
    for (const parameter of ['ex', 'is', 'hm']) url.searchParams.delete(parameter);
  }
  return url.href;
}
function signedAttachment(value) {
  const url = new URL(value);
  return attachmentUrl(url) && ['ex', 'is', 'hm'].every(parameter => !!url.searchParams.get(parameter));
}
export function discordBody(message, maximum = 4096, previousLinks = []) {
  const parts = [];
  const add = (value, url) => {
    if (typeof value !== 'string') return;
    const safe = readable(value, message).replace(/\r\n?/g, '\n').replace(/\t/g, ' ').replace(unsafeBody, '');
    if (safe.length) parts.push({ text: safe, url });
  };
  add(message.content);
  for (const item of values(message.embeds).slice(0, 10)) {
    const embed = item?.data ?? item;
    if (!embed || typeof embed !== 'object') continue;
    add(embed.author?.name); add(embed.title); add(embed.description);
    for (const field of (Array.isArray(embed.fields) ? embed.fields : []).slice(0, 25)) { add(field?.name); add(field?.value); }
    add(embed.footer?.text);
    for (const value of [embed.url, embed.author?.url, embed.image?.url, embed.thumbnail?.url]) {
      const url = link(value); if (url) add(url, url);
    }
  }
  for (const item of values(message.attachments).slice(0, 10)) {
    const url = link(item?.url);
    if (url) add(`${item.name ?? 'Attachment'}: ${url}`, url);
  }
  const preferred = new Map();
  for (const part of parts) {
    const urls = part.url ? [part.url] : [...part.text.matchAll(bodyUrl)].map(match => textLink(match[0], match.index, part.text).url).filter(Boolean);
    for (const url of urls) {
      const key = discordMediaKey(url), previous = preferred.get(key);
      if (!previous || signedAttachment(url) && !signedAttachment(previous)) preferred.set(key, url);
    }
  }
  const seen = new Set(previousLinks.map(discordMediaKey).filter(Boolean));
  const render = url => {
    const key = discordMediaKey(url); if (seen.has(key)) return '';
    seen.add(key); return preferred.get(key) ?? url;
  };
  const rendered = [];
  for (const part of parts) {
    if (part.url) {
      const url = render(part.url); if (url) rendered.push(part.text.replace(part.url, url));
    } else {
      const text = part.text.replace(bodyUrl, (value, offset) => {
        const candidate = textLink(value, offset, part.text);
        return candidate.url ? render(candidate.url) + candidate.suffix : value;
      });
      if (text.trim()) rendered.push(text);
    }
  }
  return bounded(rendered.join('\n'), maximum, ' … [truncated]');
}
export function discordMessageLinks(message) {
  const text = discordBody(message);
  return [...new Set([...text.matchAll(bodyUrl)].map(match => discordMediaKey(textLink(match[0], match.index, text).url)).filter(Boolean))];
}
export function globalDiscordPacket(message, guild, channel, ownBotId = message.client?.user?.id, previousLinks = []) {
  if (message.guildId !== guild || message.channelId !== channel || message.webhookId ||
      message.author?.bot && (!snowflake.test(ownBotId ?? '') || message.author.id === ownBotId) ||
      !snowflake.test(message.id ?? '') || !snowflake.test(message.author?.id ?? '') || !snowflake.test(channel)) return null;
  const text = discordBody(message, 4096, previousLinks);
  if (!text.trim()) return null;
  const mentions = [...new Set(values(message.mentions?.users).map(user => user?.id).filter(id => snowflake.test(id ?? '')))].slice(0, 100);
  const packet = { type: 'discord_message', scope: 'global', room: GLOBAL_ROOM, source: 'discord', id: message.id,
    message_id: message.id, channelId: channel, discordId: message.author.id, from: display(message.author, message.member), text, mentions,
    model: 1, sourceId: message.id, sender: discordSender(message), media: discordMedia(message), revision: 0, reactions: [] };
  const referenceId = message.reference?.messageId ?? message.referencedMessage?.id;
  if (snowflake.test(referenceId ?? '') && (message.reference?.channelId ?? message.channelId) === channel) {
    const reference = message.referencedMessage ?? message.channel?.messages?.cache?.get(referenceId);
    if (!reference || reference.channelId === channel) {
      packet.reply = { message_id: referenceId, from: display(reference?.author, reference?.member), text: reference ? discordBody(reference, 256) : '' };
    }
  }
  return packet;
}
/** Discord commonly fills a human message's link preview after MessageCreate. */
export function globalEmbedPacket(previous, message, guild, channel, ownBotId = message.client?.user?.id) {
  if (!previous || previous.id !== message.id || previous.guildId !== guild || previous.channelId !== channel ||
      typeof previous.content !== 'string' || previous.content !== message.content || values(previous.embeds).length || !values(message.embeds).length) return null;
  const packet = globalDiscordPacket({ id: message.id, guildId: message.guildId, channelId: message.channelId,
    author: message.author, webhookId: message.webhookId, member: message.member, guild: message.guild,
    content: '', embeds: message.embeds, mentions: message.mentions }, guild, channel, ownBotId,
    Array.isArray(previous.links) ? previous.links : discordMessageLinks(previous));
  return packet ? { ...packet, part: 'embeds', mentions: [] } : null;
}
export function globalMinecraftContent(packet) {
  if (packet?.type !== 'irc_message' || packet.scope !== 'global' || packet.room !== GLOBAL_ROOM || packet.source !== 'minecraft' ||
      !packetId.test(packet.id ?? '') || packet.message_id !== packet.id || !/^[A-Za-z0-9_]{1,16}$/.test(packet.from ?? '') ||
      typeof packet.text !== 'string' || !packet.text.trim() || packet.text.length > 1024) return null;
  const body = minecraftCard(packet, 'global', GLOBAL_ROOM);
  if (!body) return null;
  return { ...body,
    nonce: createHash('sha256').update(packet.id).digest('hex').slice(0, 24), enforceNonce: true };
}

export const REACTION_EMOJI = ['👍', '❤️', '😂', '🎉', '👀', '🔥'];
export function safeMediaUrl(value) {
  if (typeof value !== 'string' || value.length > 2048) return '';
  try {
    const url = new URL(value), host = url.hostname.toLowerCase();
    if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443' ||
        host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || !host.includes('.') ||
        host.includes(':') || /^\d+(?:\.\d+){3}$/u.test(host)) return '';
    if (attachmentUrl(url) && url.searchParams.has('ex')) {
      const expiry = url.searchParams.get('ex');
      if (!/^[a-f0-9]{1,16}$/iu.test(expiry) || BigInt(`0x${expiry}`) <= BigInt(Math.floor(Date.now() / 1000))) return '';
    }
    return url.href;
  } catch { return ''; }
}
function mediaType(value, url) {
  const mime = typeof value === 'string' ? value.toLowerCase() : '', path = new URL(url).pathname;
  if (mime === 'image/gif' || /\.gif$/iu.test(path)) return 'gif';
  if (['video/mp4', 'video/webm'].includes(mime) || /\.(mp4|webm)$/iu.test(path)) return 'video';
  if (['image/png', 'image/jpeg', 'image/webp'].includes(mime) || /\.(png|jpe?g|webp)$/iu.test(path)) return 'image';
}
export function discordMedia(message) {
  const chosen = new Map();
  const add = (value, raw = {}) => {
    const url = safeMediaUrl(value); if (!url) return;
    const type = mediaType(raw.contentType ?? raw.content_type ?? raw.mime, url); if (!type) return;
    const key = discordMediaKey(url), previous = chosen.get(key);
    if (!previous && chosen.size >= 4) return;
    const preferredUrl = previous && (!signedAttachment(url) || signedAttachment(previous.url)) ? previous.url : url;
    const item = { ...previous, id: snowflake.test(raw.id ?? '') ? raw.id : previous?.id ?? `media-${chosen.size}`, type, url: preferredUrl };
    const thumbnail = safeMediaUrl(raw.thumbnailUrl);
    if (thumbnail && type === 'video' && mediaType(undefined, thumbnail) !== 'video') item.thumbnailUrl = thumbnail;
    const name = raw.name ?? raw.filename;
    if (typeof name === 'string') item.name = bounded(name.replace(unsafeDisplay, ''), 80);
    const mime = raw.contentType ?? raw.content_type ?? raw.mime;
    if (typeof mime === 'string' && /^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm))$/u.test(mime)) item.mime = mime;
    for (const [input, output, maximum] of [['size', 'bytes', 25_000_000], ['width', 'width', 16384], ['height', 'height', 16384]])
      if (Number.isSafeInteger(raw[input]) && raw[input] >= 0 && raw[input] <= maximum) item[output] = raw[input];
    chosen.set(key, item);
  };
  for (const item of values(message.attachments).slice(0, 10)) add(item?.url, item ?? {});
  const content = typeof message.content === 'string' ? message.content : '';
  for (const match of content.matchAll(bodyUrl)) add(textLink(match[0], match.index, content).url);
  for (const item of values(message.embeds).slice(0, 10)) {
    const embed = item?.data ?? item;
    if (!embed || typeof embed !== 'object') continue;
    add(embed.video?.url, { ...embed.video, thumbnailUrl: embed.thumbnail?.url });
    add(embed.image?.url, embed.image);
    if (chosen.get(discordMediaKey(embed.video?.url))?.type !== 'video') add(embed.thumbnail?.url, embed.thumbnail);
    add(embed.url);
  }
  return [...chosen.values()];
}
export function discordSender(message) {
  const author = message.author ?? {}, sender = { name: display(author, message.member), discordId: author.id };
  if (snowflake.test(author.id ?? '') && /^[a-f0-9]{32}$/u.test(author.avatar ?? ''))
    sender.avatarUrl = `https://cdn.discordapp.com/avatars/${author.id}/${author.avatar}.png?size=64`;
  return sender;
}
/** Only the authenticated backend may supply Minecraft identity and cosmetics metadata. */
export function minecraftCard(packet, scope, room) {
  const maximum = scope === 'global' ? 1024 : 256;
  if (!(scope === 'global' && room === GLOBAL_ROOM || scope === 'room' && ['friends', 'testing'].includes(room)) ||
      packet?.type !== 'irc_message' || packet.source !== 'minecraft' || packet.scope !== scope && !(scope === 'room' && packet.scope === undefined) ||
      packet.room !== room || !packetId.test(packet.id ?? '') || packet.message_id !== packet.id ||
      !/^[A-Za-z0-9_]{1,16}$/u.test(packet.from ?? '') || typeof packet.text !== 'string' ||
      !packet.text.trim() || packet.text.length > maximum || /[\x00-\x08\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u.test(packet.text)) return null;
  const escape = value => value.replace(/[\\`*_~|>]/g, '\\$&');
  const metadata = packet.sender?.name === packet.from ? packet.sender : {};
  const cosmetic = typeof metadata.cosmeticName === 'string' ? bounded(metadata.cosmeticName.replace(unsafeDisplay, ''), 64).trim() : '';
  const author = { name: bounded(cosmetic && cosmetic !== packet.from ? `${cosmetic} (${packet.from})` : packet.from, 128) };
  const verifiedUuid = typeof metadata.uuid === 'string' ? metadata.uuid.replaceAll('-', '').toLowerCase() : '';
  if (packetId.test(verifiedUuid)) author.iconURL = `https://mc-heads.net/avatar/${verifiedUuid}/64`;
  let description = escape(packet.text);
  const fields = [];
  if (packet.reply && typeof packet.reply.from === 'string' && typeof packet.reply.text === 'string') {
    fields.push({ name: `Reply to ${bounded(packet.reply.from.replace(unsafeDisplay, ''), 64)}`,
      value: escape(bounded(packet.reply.text.replace(unsafeBody, ''), 256)) || 'Original message unavailable' });
  }
  const embed = { author, description: bounded(description, 4096), color: 0x3d9df5,
    footer: { text: scope === 'global' ? 'Minecraft · Sky-Myce Global' : 'Minecraft · Sky-Myce IRC' } };
  const links = [], seen = new Set();
  for (const item of (Array.isArray(packet.media) ? packet.media : []).slice(0, 4)) {
    const url = safeMediaUrl(item?.url); if (!url || !['image', 'gif', 'video'].includes(item.type)) continue;
    const key = discordMediaKey(url); if (seen.has(key)) continue; seen.add(key);
    if ((item.type === 'image' || item.type === 'gif') && !embed.image) embed.image = { url };
    else if (item.type === 'video' && !embed.thumbnail && safeMediaUrl(item.thumbnailUrl)) embed.thumbnail = { url: safeMediaUrl(item.thumbnailUrl) };
    if (!packet.text.includes(url)) links.push(`[${item.type === 'video' ? 'Video' : 'Media'}](${url})`);
  }
  if (links.length) fields.push({ name: 'Media', value: bounded(links.join(' · '), 1024) });
  if (fields.length) embed.fields = fields;
  const body = { embeds: [embed], allowedMentions: noMentions };
  if (snowflake.test(packet.reply?.discordMessageId ?? '')) body.reply = { messageReference: packet.reply.discordMessageId, failIfNotExists: false };
  return body;
}
export function validBridgeOperation(packet, scope, room, now = Date.now()) {
  return (scope === 'global' && room === GLOBAL_ROOM || scope === 'room' && ['friends', 'testing'].includes(room)) &&
    packet?.type === 'irc_operation' && packet.scope === scope && packet.room === room && packetId.test(packet.id ?? '') &&
    packetId.test(packet.messageId ?? '') && snowflake.test(packet.discordMessageId ?? '') &&
    Number.isSafeInteger(packet.revision) && packet.revision >= 1 && Number.isSafeInteger(packet.timestamp) &&
    packet.timestamp <= now + 30000 && packet.timestamp > now - 120000 && Number.isSafeInteger(packet.expiresAt) &&
    packet.expiresAt > now && packet.expiresAt <= packet.timestamp + 120000 && ['edit', 'delete', 'reaction'].includes(packet.operation) &&
    (packet.operation !== 'reaction' || REACTION_EMOJI.includes(packet.emoji) && typeof packet.active === 'boolean');
}
/** Event identity must use the Discord session/sequence, or an otherwise stable source revision. */
export function discordOperation(event, message, previous, id, guild, channel, ownBot) {
  if (message?.guildId !== guild || message.channelId !== channel || !snowflake.test(message.id ?? message.messageId ?? '') || !packetId.test(id)) return null;
  const reaction = event === 'MESSAGE_REACTION_ADD' || event === 'MESSAGE_REACTION_REMOVE';
  const actor = reaction ? message.userId : previous?.author?.id ?? message.author?.id;
  if (!snowflake.test(actor ?? '') || reaction && (actor === ownBot || message.emoji?.id || !REACTION_EMOJI.includes(message.emoji?.name))) return null;
  const base = { type: 'discord_operation', id, source: 'discord', scope: 'global', room: GLOBAL_ROOM,
    message_id: message.id ?? message.messageId, channelId: channel, discordId: actor };
  if (reaction) return { ...base, operation: 'reaction', emoji: message.emoji.name, active: event === 'MESSAGE_REACTION_ADD' };
  if (!previous || previous.webhookId || message.author && message.author.id !== previous.author?.id) return null;
  if (event === 'MESSAGE_DELETE') return { ...base, operation: 'delete' };
  if (event !== 'MESSAGE_UPDATE') return null;
  const merged = { ...previous, ...message }, own = previous.author?.id === ownBot;
  const description = values(merged.embeds)[0]?.data?.description ?? values(merged.embeds)[0]?.description;
  if (own && typeof description !== 'string') return null;
  const text = own ? bounded(description.replace(/\\([\\`*_~|>])/gu, '$1').replace(unsafeBody, ''), 1024) : discordBody(merged);
  const media = discordMedia(merged);
  if (previous.author?.id === ownBot && previous.mapped !== true) return null;
  if (text === previous.text && JSON.stringify(media) === JSON.stringify(previous.media)) return null;
  const editedAt = message.editedTimestamp;
  return { ...base, operation: 'edit', text, media, ...(Number.isSafeInteger(editedAt) ? { editedAt } : {}) };
}

/** Bounded delivery mappings and receipts; never persist chat bodies or tokens. */
export class DeliveryReceipts {
  constructor(path, now = Date.now) {
    Object.assign(this, { path, now }); this.entries = new Map(); this.sources = new Map(); this.operations = new Map(); this.cards = new Map();
    this.writing = Promise.resolve();
  }
  async load() {
    let handle, text;
    try {
      handle = await open(this.path, 'r');
      const bytes = Buffer.alloc(1_048_577); let length = 0;
      while (length < bytes.length) { const part = await handle.read(bytes, length, bytes.length - length, null); if (!part.bytesRead) break; length += part.bytesRead; }
      if (length > 1_048_576) throw new Error('Oversized receipts');
      text = bytes.subarray(0, length).toString('utf8');
    } catch (error) { if (error.code === 'ENOENT') return this; throw new Error('Global chat receipt storage unavailable'); }
    finally { await handle?.close(); }
    const data = JSON.parse(text);
    if (![1, 2].includes(data?.schema) || !Array.isArray(data.entries) || data.entries.length > 1024 ||
        data.schema === 2 && (!Array.isArray(data.sources) || data.sources.length > 1024 || !Array.isArray(data.operations) || data.operations.length > 4096)) throw new Error('Global chat receipt storage invalid');
    for (const row of data.entries) {
      const rich = data.schema === 2 && row?.length === 4;
      if (!Array.isArray(row) || row.length !== (rich ? 4 : 3) || !packetId.test(row[0]) || !snowflake.test(row[1]) ||
          !Number.isSafeInteger(row[2]) || row[2] > this.now() + (rich ? 900000 : 120000) || this.entries.has(row[0]) || rich && !this.validMetadata(row[3])) throw new Error('Global chat receipt storage invalid');
      if (row[2] > this.now()) this.entries.set(row[0], { discordId: row[1], expires: row[2], ...(rich ? row[3] : {}) });
    }
    for (const row of data.sources ?? []) {
      if (!Array.isArray(row) || row.length !== 3 || !snowflake.test(row[0]) || !this.validMetadata(row[1]) ||
          !Number.isSafeInteger(row[2]) || row[2] > this.now() + 900000 || this.sources.has(row[0])) throw new Error('Global chat receipt storage invalid');
      if (row[2] > this.now()) this.sources.set(row[0], { ...row[1], expires: row[2] });
    }
    for (const row of data.operations ?? []) {
      if (!Array.isArray(row) || row.length !== 5 || !packetId.test(row[0]) || !packetId.test(row[1]) || !Number.isSafeInteger(row[2]) || row[2] < 1 ||
          !snowflake.test(row[3]) || !Number.isSafeInteger(row[4]) || row[4] > this.now() + 900000 || this.operations.has(row[0])) throw new Error('Global chat receipt storage invalid');
      if (row[4] > this.now()) this.operations.set(row[0], { messageId: row[1], revision: row[2], discordId: row[3], expires: row[4] });
    }
    return this;
  }
  validMetadata(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value) && ['friends', 'testing', GLOBAL_ROOM].includes(value.room) &&
      snowflake.test(value.channelId ?? '') && snowflake.test(value.authorId ?? '') &&
      (value.revision === undefined || Number.isSafeInteger(value.revision) && value.revision >= 0) &&
      (value.name === undefined || typeof value.name === 'string' && value.name.length <= 64 && !/[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u.test(value.name));
  }
  prune(maps = this) {
    const now = this.now();
    for (const [map, maximum] of [[maps.entries, 1024], [maps.sources, 1024], [maps.operations, 4096], [this.cards, 1024]]) {
      for (const [id, row] of map) if (row.expires <= now) map.delete(id);
      while (map.size > maximum) map.delete(map.keys().next().value);
    }
  }
  get(id) { this.prune(); return this.entries.get(id)?.discordId; }
  metadata(id) { this.prune(); return this.entries.get(id); }
  source(id, room) {
    this.prune(); const source = this.sources.get(id);
    if (source?.room === room) return { ...source, sourceId: id, mapped: false };
    for (const [canonicalId, item] of this.entries) if (item.discordId === id && item.room === room) return { ...item, canonicalId, sourceId: id, mapped: true };
  }
  operation(id) { this.prune(); return this.operations.get(id); }
  async record(id, discordId, metadata) {
    if (!packetId.test(id) || !snowflake.test(discordId) || metadata !== undefined && !this.validMetadata(metadata)) throw new Error('Invalid global chat receipt');
    return this.persist(maps => maps.entries.set(id, { discordId, expires: this.now() + (metadata ? 900000 : 120000), ...(metadata ? this.cleanMetadata(metadata) : {}) }));
  }
  cleanMetadata(value) {
    return { room: value.room, channelId: value.channelId, authorId: value.authorId, revision: value.revision ?? 0, ...(value.name ? { name: value.name } : {}) };
  }
  async rememberSource(id, metadata) {
    if (!snowflake.test(id) || !this.validMetadata(metadata)) throw new Error('Invalid Discord source metadata');
    return this.persist(maps => maps.sources.set(id, { ...this.cleanMetadata(metadata), expires: this.now() + 900000 }));
  }
  async recordOperation(id, messageId, revision, discordId) {
    if (!packetId.test(id) || !packetId.test(messageId) || !Number.isSafeInteger(revision) || revision < 1 || !snowflake.test(discordId)) throw new Error('Invalid operation receipt');
    return this.persist(maps => {
      maps.operations.set(id, { messageId, revision, discordId, expires: this.now() + 900000 });
      const message = maps.entries.get(messageId); if (message?.discordId === discordId) message.revision = Math.max(message.revision ?? 0, revision);
      const source = maps.sources.get(discordId); if (source) source.revision = Math.max(source.revision ?? 0, revision);
    });
  }
  setCard(id, packet) { if (!packetId.test(id)) return; this.cards.set(id, { packet, expires: this.now() + 900000 }); this.prune(); }
  getCard(id) { this.prune(); return this.cards.get(id)?.packet; }
  persist(change) {
    this.writing = this.writing.catch(() => {}).then(async () => {
      // Readers may ACK these records, so publish only after the serialized atomic write succeeds.
      const maps = Object.fromEntries(['entries', 'sources', 'operations'].map(name => [name, new Map([...this[name]].map(([id, value]) => [id, { ...value }]))]));
      change?.(maps);
      await mkdir(dirname(this.path), { recursive: true });
      this.prune(maps);
      const entries = [...maps.entries].map(([id, value]) => [id, value.discordId, value.expires, ...(value.room ? [this.cleanMetadata(value)] : [])]);
      const rich = maps.sources.size || maps.operations.size || entries.some(row => row.length === 4);
      const data = { schema: rich ? 2 : 1, entries, ...(rich ? {
        sources: [...maps.sources].map(([id, value]) => [id, this.cleanMetadata(value), value.expires]),
        operations: [...maps.operations].map(([id, value]) => [id, value.messageId, value.revision, value.discordId, value.expires]) } : {}) };
      const text = JSON.stringify(data); if (Buffer.byteLength(text) > 1_048_576) throw new Error('Receipt capacity exceeded');
      const temporary = `${this.path}.${process.pid}.${randomBytes(8).toString('hex')}.tmp`;
      try { await writeFile(temporary, text, { mode: 0o600, flag: 'wx' }); await rename(temporary, this.path); }
      finally { await unlink(temporary).catch(() => {}); }
      Object.assign(this, maps);
    });
    return this.writing;
  }
}
