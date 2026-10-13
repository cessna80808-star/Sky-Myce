import { createHash, createHmac, randomBytes } from 'node:crypto';
import { DeliveryReceipts } from './global-chat.mjs';
import { rngPresentation } from '../../shared/rng-presentation.mjs';
import { deliverRngPublication } from '../../shared/rng-delivery.mjs';

const id = /^[a-f0-9]{32}$/, snowflake = /^\d{17,20}$/;
export function communityFeedHeaders(secret, body, timestamp = String(Date.now()), nonce = randomBytes(16).toString('hex')) {
  return { 'Content-Type': 'application/json', 'X-SkyMyce-Timestamp': timestamp, 'X-SkyMyce-Nonce': nonce,
    'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-community-feed\nPOST\n/discord/community-feed\n${timestamp}\n${nonce}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex') };
}
export function rngEmbed(event, now = Date.now()) {
  const card = rngPresentation(event, now);
  return card ? { ...card, nonce: createHash('sha256').update(`rng:${event.id}`).digest('hex').slice(0, 24), enforceNonce: true } : null;
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
      const result = await this.request({ action: 'claim' });
      if (!Array.isArray(result.deliveries) || result.deliveries.length > 3) throw new Error('Invalid RNG feed response');
      for (const delivery of result.deliveries) {
        if (this.stopped) break;
        if (!id.test(delivery?.id ?? '') || !/^[A-Za-z0-9_-]{43}$/.test(delivery.claim ?? '') || delivery.channelId !== this.channelId) throw new Error('Invalid RNG feed delivery');
        if (delivery.operation === 'delete') {
          if (!snowflake.test(delivery.messageId ?? '')) throw new Error('Invalid RNG deletion');
          await this.remove(delivery.messageId);
          await this.request({ action: 'ack', id: delivery.id, claim: delivery.claim, operation: 'delete' });
          continue;
        }
        await deliverRngPublication(delivery, { channelId: this.channelId, now: this.now, present: rngEmbed,
          receipts: this.receipts, send: card => this.channel.send(card), ack: raw => this.request(raw), remove: id => this.remove(id) });
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
export async function communityFeedReceipts(path) { return await new DeliveryReceipts(path, Date.now, 900000).load(); }
