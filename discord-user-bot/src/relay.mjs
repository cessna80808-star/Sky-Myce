import { createHmac, randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { ExpiringIds, RateLimit, limits } from './config.mjs';

export function bridgeHeaders(secret, room, timestamp = String(Date.now()), nonce = randomBytes(16).toString('hex')) {
  return { 'X-SkyMyce-Timestamp': timestamp, 'X-SkyMyce-Nonce': nonce,
    'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-bridge\n${room}\n${timestamp}\n${nonce}`).digest('hex') };
}
export function validText(value, maximum = 256) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximum &&
    !/[\x00-\x1f\x7f\u00a7\u202a-\u202e\u2066-\u2069]/u.test(value);
}
export function discordPacket(message, guild, channel, room = 'friends', maximum = 256) {
  if (!['friends', 'testing'].includes(room) || message.guildId !== guild || message.channelId !== channel || message.author?.bot || message.webhookId ||
      !/^\d{17,20}$/.test(message.id ?? '') || !/^\d{17,20}$/.test(message.author?.id ?? '') || !validText(message.content, maximum)) return null;
  const display = message.member?.displayName ?? message.author.globalName ?? message.author.username ?? 'Discord user';
  const from = [...display.replace(/[\x00-\x1f\x7f\u00a7\u202a-\u202e\u2066-\u2069]/gu, '')].slice(0, 32).join('') || 'Discord user';
  return { type: 'discord_message', id: message.id, message_id: message.id, source: 'discord', room, discordId: message.author.id, from, text: message.content };
}
export function minecraftContent(packet, room = 'friends', maximum = 256) {
  if (packet?.type !== 'irc_message' || packet.source !== 'minecraft' || packet.room !== room || packet.message_id !== packet.id ||
      !/^[a-f0-9]{32}$/.test(packet.id ?? '') || !/^[A-Za-z0-9_]{1,16}$/.test(packet.from ?? '') || !validText(packet.text, maximum)) return null;
  const plain = packet.text.replace(/[\\`*_~|>]/g, '\\$&');
  return { content: `[MC] ${packet.from} » ${plain}`, allowedMentions: { parse: [], repliedUser: false } };
}
export function reconnectDelay(attempt, random = Math.random) {
  const base = Math.min(60000, 1000 * 2 ** Math.min(attempt, 6));
  return Math.min(60000, base + Math.floor(random() * 1000));
}
export class RelayBridge {
  constructor({ url, room, secret, onMessage, onResult = () => {}, policy = limits(), globalRate,
      now = Date.now, socketFactory = (url, options) => new WebSocket(url, options) }) {
    this.url = new URL('/discord/bridge', url);
    if (!['https:', 'wss:'].includes(this.url.protocol) || this.url.username || this.url.password || !['friends', 'testing'].includes(room)) throw new Error('Invalid Sky-Myce relay configuration');
    this.url.protocol = 'wss:'; this.url.searchParams.set('room', room);
    Object.assign(this, { room, secret, onMessage, onResult, policy, now, socketFactory });
    this.seen = new ExpiringIds(policy.dedupSize, policy.dedupTtlMs, now);
    this.outboundSeen = new ExpiringIds(policy.dedupSize, policy.dedupTtlMs, now);
    this.globalRate = globalRate ?? new RateLimit(policy.globalBurst, policy.globalRefillMs, 1, now);
    this.attempt = 0; this.stopped = false; this.ready = false; this.deliveries = 0;
  }
  connect() {
    if (this.stopped || this.socket || this.reconnect) return;
    let socket;
    try { socket = this.socketFactory(this.url, { headers: bridgeHeaders(this.secret, this.room), maxPayload: 8192, handshakeTimeout: 10000, followRedirects: false }); }
    catch { this.scheduleReconnect(); return; }
    this.socket = socket;
    this.handshake = setTimeout(() => socket.terminate(), 12000);
    socket.on('open', () => {
      this.alive = true; this.openedAt = this.now();
      this.heartbeat = setInterval(() => {
        this.seen.prune(); this.outboundSeen.prune();
        if (!this.alive || socket.readyState !== WebSocket.OPEN) { socket.terminate(); return; }
        this.alive = false;
        try { socket.send('ping'); } catch { socket.terminate(); }
      }, 25000);
    });
    socket.on('message', (raw, binary) => {
      if (this.socket !== socket || binary || raw.length > 8192) return;
      const text = raw.toString();
      if (text === 'pong') { this.alive = true; if (this.ready && this.now() - this.openedAt >= 30000) this.attempt = 0; return; }
      let packet;
      try { packet = JSON.parse(text); } catch { return; }
      if (packet?.type === 'bridge_ready' && packet.protocol === 3 && packet.room === this.room) {
        this.ready = true; clearTimeout(this.handshake);
        console.info(JSON.stringify({ event: 'relay_connected', room: this.room })); return;
      }
      if (!this.ready) return;
      if (packet?.type === 'irc_result' && /^\d{17,20}$/.test(packet.id ?? '')) {
        try { this.onResult(packet.id, typeof packet.error === 'string' ? packet.error : null); } catch { /* Caller owns UI failures. */ }
        return;
      }
      const content = minecraftContent(packet, this.room, this.policy.inboundLength);
      if (!content || !this.seen.accept(packet.id) || !this.globalRate.allow('global') || this.deliveries >= this.policy.globalBurst) return;
      this.deliveries++;
      Promise.resolve().then(() => this.onMessage(content)).catch(() => {
        console.warn(JSON.stringify({ event: 'discord_delivery_unavailable', room: this.room }));
      }).finally(() => this.deliveries--);
    });
    socket.on('error', () => {});
    socket.once('close', () => {
      if (this.socket !== socket) return;
      this.socket = undefined; this.ready = false;
      clearTimeout(this.handshake); clearInterval(this.heartbeat); this.scheduleReconnect();
    });
  }
  scheduleReconnect() {
    if (this.stopped || this.reconnect) return;
    const delay = reconnectDelay(this.attempt++);
    if (this.attempt === 1 || this.attempt % 10 === 0) console.warn(JSON.stringify({ event: 'relay_unavailable', room: this.room, retryMs: delay }));
    this.reconnect = setTimeout(() => { this.reconnect = undefined; this.connect(); }, delay);
  }
  send(packet) {
    if (!this.ready || this.socket?.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384 ||
        packet.room !== this.room || packet.source !== 'discord' || packet.message_id !== packet.id ||
        !validText(packet.text, this.policy.outboundLength) || !this.globalRate.allow('global') || !this.outboundSeen.accept(packet.id)) return false;
    try { this.socket.send(JSON.stringify(packet)); return true; } catch { return false; }
  }
  close() {
    this.stopped = true; this.ready = false;
    clearTimeout(this.reconnect); clearTimeout(this.handshake); clearInterval(this.heartbeat);
    const socket = this.socket;
    if (socket) {
      const timer = setTimeout(() => socket.terminate(), 2000); timer.unref?.();
      socket.once('close', () => clearTimeout(timer));
      socket.close(1000, 'Sky-Myce shutdown');
    }
  }
}
