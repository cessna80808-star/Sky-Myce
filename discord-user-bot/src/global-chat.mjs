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
  if (typeof value !== 'string') return '';
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
export function discordBody(message, maximum = 4096) {
  const parts = [];
  const add = value => {
    if (typeof value !== 'string') return;
    const safe = readable(value, message).replace(/\r\n?/g, '\n').replace(/\t/g, ' ').replace(unsafeBody, '');
    if (safe.length) parts.push(safe);
  };
  add(message.content);
  for (const item of values(message.embeds).slice(0, 10)) {
    const embed = item?.data ?? item;
    if (!embed || typeof embed !== 'object') continue;
    add(embed.author?.name); add(embed.title); add(embed.description);
    for (const field of (Array.isArray(embed.fields) ? embed.fields : []).slice(0, 25)) { add(field?.name); add(field?.value); }
    add(embed.footer?.text);
    for (const url of [embed.url, embed.author?.url, embed.image?.url, embed.thumbnail?.url]) add(link(url));
  }
  for (const item of values(message.attachments).slice(0, 10)) {
    const url = link(item?.url);
    if (url) add(`${item.name ?? 'Attachment'}: ${url}`);
  }
  return bounded(parts.join('\n'), maximum, ' … [truncated]');
}
export function globalDiscordPacket(message, guild, channel, ownBotId = message.client?.user?.id) {
  if (message.guildId !== guild || message.channelId !== channel || message.webhookId ||
      message.author?.bot && (!snowflake.test(ownBotId ?? '') || message.author.id === ownBotId) ||
      !snowflake.test(message.id ?? '') || !snowflake.test(message.author?.id ?? '') || !snowflake.test(channel)) return null;
  const text = discordBody(message);
  if (!text.trim()) return null;
  const mentions = [...new Set(values(message.mentions?.users).map(user => user?.id).filter(id => snowflake.test(id ?? '')))].slice(0, 100);
  const packet = { type: 'discord_message', scope: 'global', room: GLOBAL_ROOM, source: 'discord', id: message.id,
    message_id: message.id, channelId: channel, discordId: message.author.id, from: display(message.author, message.member), text, mentions };
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
    content: '', embeds: message.embeds, mentions: message.mentions }, guild, channel, ownBotId);
  return packet ? { ...packet, part: 'embeds', mentions: [] } : null;
}
export function globalMinecraftContent(packet) {
  if (packet?.type !== 'irc_message' || packet.scope !== 'global' || packet.room !== GLOBAL_ROOM || packet.source !== 'minecraft' ||
      !packetId.test(packet.id ?? '') || packet.message_id !== packet.id || !/^[A-Za-z0-9_]{1,16}$/.test(packet.from ?? '') ||
      typeof packet.text !== 'string' || !packet.text.trim() || packet.text.length > 1024) return null;
  const plain = packet.text.replace(/[\\`*_~|>]/g, '\\$&');
  const content = `[SkyMyce] ${packet.from} » ${plain}`;
  // Escaping 1,024 markdown characters can exceed Discord's 2,000-character content limit.
  const body = content.length <= 2000 ? { content } : { content: `[SkyMyce] ${packet.from}`, embeds: [{ description: plain }] };
  return { ...body, allowedMentions: noMentions,
    nonce: createHash('sha256').update(packet.id).digest('hex').slice(0, 24), enforceNonce: true };
}

/** Only delivery IDs are persisted, never chat bodies, account IDs or tokens. */
export class DeliveryReceipts {
  constructor(path, now = Date.now) { this.path = path; this.now = now; this.entries = new Map(); this.writing = Promise.resolve(); }
  async load() {
    let handle, text;
    try {
      handle = await open(this.path, 'r');
      const bytes = Buffer.alloc(262145);
      let length = 0;
      while (length < bytes.length) {
        const { bytesRead } = await handle.read(bytes, length, bytes.length - length, null);
        if (!bytesRead) break;
        length += bytesRead;
      }
      if (length > 262144) throw new Error('Oversized receipts');
      text = bytes.subarray(0, length).toString('utf8');
    } catch (error) { if (error.code === 'ENOENT') return this; throw new Error('Global chat receipt storage unavailable'); }
    finally { await handle?.close(); }
    const data = JSON.parse(text);
    if (data?.schema !== 1 || !Array.isArray(data.entries) || data.entries.length > 1024) throw new Error('Global chat receipt storage invalid');
    for (const row of data.entries) {
      if (!Array.isArray(row) || row.length !== 3 || !packetId.test(row[0]) || !snowflake.test(row[1]) || !Number.isSafeInteger(row[2]) || row[2] > this.now() + 120000 || this.entries.has(row[0])) throw new Error('Global chat receipt storage invalid');
      if (row[2] > this.now()) this.entries.set(row[0], { discordId: row[1], expires: row[2] });
    }
    return this;
  }
  get(id) {
    const now = this.now();
    for (const [key, value] of this.entries) if (value.expires <= now) this.entries.delete(key);
    return this.entries.get(id)?.discordId;
  }
  async record(id, discordId) {
    if (!packetId.test(id) || !snowflake.test(discordId)) throw new Error('Invalid global chat receipt');
    this.get(id);
    this.entries.set(id, { discordId, expires: this.now() + 120000 });
    while (this.entries.size > 1024) this.entries.delete(this.entries.keys().next().value);
    this.writing = this.writing.catch(() => {}).then(async () => {
      await mkdir(dirname(this.path), { recursive: true });
      const temporary = `${this.path}.${process.pid}.${randomBytes(8).toString('hex')}.tmp`;
      try {
        await writeFile(temporary, JSON.stringify({ schema: 1, entries: [...this.entries].map(([id, value]) => [id, value.discordId, value.expires]) }), { mode: 0o600, flag: 'wx' });
        await rename(temporary, this.path);
      } finally { await unlink(temporary).catch(() => {}); }
    });
    return this.writing;
  }
}
