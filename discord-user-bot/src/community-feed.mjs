import { createHash, createHmac, randomBytes } from 'node:crypto';
import { DeliveryReceipts } from './global-chat.mjs';

const id = /^[a-f0-9]{32}$/, snowflake = /^\d{17,20}$/;
const observedRarities = new Set(['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC', 'DIVINE', 'ULTIMATE', 'SPECIAL', 'VERY_SPECIAL', 'ADMIN']);
const itemNames = Object.freeze({ NECRON_HANDLE: "Necron's Handle", GIANTS_SWORD: "Giant's Sword", DARK_CLAYMORE: 'Dark Claymore',
  SHADOW_FURY: 'Shadow Fury', IMPLOSION_SCROLL: 'Implosion', WITHER_SHIELD_SCROLL: 'Wither Shield', SHADOW_WARP_SCROLL: 'Shadow Warp',
  WARDEN_HEART: 'Warden Heart', JUDGEMENT_CORE: 'Judgement Core', OVERFLUX_CAPACITOR: 'Overflux Capacitor' });
const unsafePresentation = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u;
function inert(value) { return value.replace(/[\\`*_~|><\[\]()]/g, '\\$&').replace(/@/g, '@\u200b').replace(/:/g, ':\u200b'); }
export function communityFeedHeaders(secret, body, timestamp = String(Date.now()), nonce = randomBytes(16).toString('hex')) {
  return { 'Content-Type': 'application/json', 'X-SkyMyce-Timestamp': timestamp, 'X-SkyMyce-Nonce': nonce,
    'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-community-feed\nPOST\n/discord/community-feed\n${timestamp}\n${nonce}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex') };
}
export function rngEmbed(event, now = Date.now()) {
  if (!event || !id.test(event.id ?? '') || !/^[A-Z0-9_]{1,80}$/.test(event.item ?? '') ||
    !['dungeon', 'slayer', 'kuudra', 'fishing'].includes(event.activity) || !Number.isSafeInteger(event.occurredAt) ||
    event.occurredAt > now + 60000 || event.occurredAt < now - 900000 || event.unverified !== true ||
    !(event.player === 'Anonymous' || /^[A-Za-z0-9_]{1,16}$/.test(event.player ?? ''))) return null;
  if (event.rarity != null && !observedRarities.has(event.rarity) ||
    event.displayName != null && (typeof event.displayName !== 'string' || event.displayName.length < 1 || event.displayName.length > 120 ||
      event.displayName !== event.displayName.trim() || unsafePresentation.test(event.displayName)) ||
    event.floor != null && (event.activity !== 'dungeon' || typeof event.floor !== 'string' || !/^(?:E|[FM][1-7])$/.test(event.floor))) return null;
  const display = event.displayName ?? itemNames[event.item] ?? event.item.toLowerCase().split('_').map(word => word[0].toUpperCase() + word.slice(1)).join(' ');
  const fields = [{ name: 'Activity', value: event.activity }];
  if (event.rarity != null) fields.push({ name: 'Observed rarity', value: event.rarity.toLowerCase().replace(/_/g, ' ') });
  if (event.floor != null) fields.push({ name: 'Observed floor', value: event.floor });
  fields.push({ name: 'Source', value: 'Community submitted · unverified client acquisition report' });
  fields.push({ name: 'Item variant', value: inert(event.item) });
  return { embeds: [{ title: `Rare drop · ${inert(display)}`, color: 0x8b61eb,
    description: `**${event.player.replace(/_/g, '\\_')}** reported a supported rare drop.`,
    fields,
    timestamp: new Date(event.occurredAt).toISOString(), footer: { text: 'Sky-Myce RNG · shared with consent; counts are not game drop probabilities' } }],
    allowedMentions: { parse: [], repliedUser: false }, nonce: createHash('sha256').update(`rng:${event.id}`).digest('hex').slice(0, 24), enforceNonce: true };
}

/** Runs within the existing User Bot. The Worker owns the durable, bounded outbox; no historical feed replay. */
export class CommunityFeed {
  constructor({ url, secret, channelId, channel, receipts, fetcher = fetch, now = Date.now, onDiagnostic = () => {} }) {
    this.url = new URL('/discord/community-feed', url);
    if (this.url.protocol !== 'https:' || this.url.username || this.url.password || !secret || secret.length < 32 || !snowflake.test(channelId ?? '')) throw new Error('Invalid RNG feed configuration');
    Object.assign(this, { secret, channelId, channel, receipts, fetcher, now, onDiagnostic }); this.stopped = false; this.failures = 0;
  }
  async request(raw) {
    const body = JSON.stringify({ ...raw, channelId: this.channelId });
    const response = await this.fetcher(this.url, { method: 'POST', headers: communityFeedHeaders(this.secret, body), body, redirect: 'error', signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error('RNG feed unavailable');
    const reader = response.body?.getReader(); if (!reader) throw new Error('Invalid RNG feed response');
    const chunks = []; let length = 0;
    try {
      while (true) {
        const part = await reader.read(); if (part.done) break;
        length += part.value.byteLength; if (length > 16384) throw new Error('Invalid RNG feed response');
        chunks.push(part.value);
      }
    } finally { await reader.cancel(); }
    const bytes = new Uint8Array(length); let position = 0;
    for (const chunk of chunks) { bytes.set(chunk, position); position += chunk.byteLength; }
    const result = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); if (result?.ok !== true) throw new Error('RNG feed unavailable'); return result;
  }
  start() { if (!this.stopped && !this.timer && !this.running) void this.tick(); }
  stop() { this.stopped = true; clearTimeout(this.timer); this.timer = undefined; }
  async tick() {
    if (this.stopped || this.running) return;
    this.running = true; this.timer = undefined;
    try {
      const receipts = this.receipts.pending?.() ?? [];
      const result = await this.request({ action: 'claim', ...(receipts.length ? { receipts } : {}) });
      if (!Array.isArray(result.deliveries) || result.deliveries.length > 3) throw new Error('Invalid RNG feed response');
      if (result.acknowledged !== undefined) {
        if (!Array.isArray(result.acknowledged) || result.acknowledged.length > receipts.length || new Set(result.acknowledged).size !== result.acknowledged.length || result.acknowledged.some(value => typeof value !== 'string' || !id.test(value) || !receipts.some(receipt => receipt.id === value))) throw new Error('Invalid RNG receipt confirmation');
        for (const value of result.acknowledged) await this.receipts.acknowledge(value);
      }
      for (const delivery of result.deliveries) {
        if (this.stopped) break;
        if (!id.test(delivery?.id ?? '') || !/^[A-Za-z0-9_-]{43}$/.test(delivery.claim ?? '') || delivery.channelId !== this.channelId) throw new Error('Invalid RNG feed delivery');
        if (delivery.operation === 'delete') {
          if (delivery.messageId !== undefined && !snowflake.test(delivery.messageId)) throw new Error('Invalid RNG deletion');
          const messageId = delivery.messageId ?? this.receipts.get?.(delivery.id);
          if (messageId) await this.remove(messageId);
          await this.receipts.forget?.(delivery.id);
          await this.request({ action: 'ack', id: delivery.id, claim: delivery.claim, operation: 'delete' });
          continue;
        }
        if (delivery.operation !== 'publish' || delivery.event?.id !== delivery.id) throw new Error('Invalid RNG publication');
        const card = rngEmbed(delivery.event, this.now()); if (!card) throw new Error('Invalid RNG publication');
        let messageId = this.receipts.get(delivery.id);
        if (!messageId) {
          this.receipts.assertCapacity?.(delivery.id);
          const message = await this.channel.send(card);
          if (!snowflake.test(message?.id ?? '')) throw new Error('RNG publication returned no message');
          messageId = message.id;
          try { await this.receipts.record(delivery.id, messageId); }
          catch (error) { await this.remove(messageId); throw error; }
        }
        const ack = await this.request({ action: 'ack', id: delivery.id, claim: delivery.claim, operation: 'publish', messageId });
        if (ack.deleteRequired) { await this.remove(messageId); await this.receipts.forget?.(delivery.id); }
        else await this.receipts.acknowledge?.(delivery.id);
      }
      this.failures = 0;
    } catch { this.failures = Math.min(this.failures + 1, 6); this.onDiagnostic('rng_feed_unavailable'); }
    finally {
      this.running = false;
      if (!this.stopped) { this.timer = setTimeout(() => { this.timer = undefined; void this.tick(); }, Math.min(60000, 10000 * 2 ** this.failures)); this.timer.unref?.(); }
    }
  }
  async remove(messageId) {
    try { await this.channel.messages.delete(messageId); }
    catch (error) { if (error?.code !== 10008 && error?.status !== 404) throw error; }
  }
}
export async function communityFeedReceipts(path, now = Date.now) { return await new DeliveryReceipts(path, now, 900000, true).load(); }
