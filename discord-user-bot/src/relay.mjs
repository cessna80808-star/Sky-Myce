import { createHash, createHmac, randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { ExpiringIds, RateLimit, limits } from './config.mjs';
import { GLOBAL_ROOM, globalDiscordPacket, globalMinecraftContent, minecraftCard, validBridgeOperation, REACTION_EMOJI } from './global-chat.mjs';

export function bridgeHeaders(secret, room, timestamp = String(Date.now()), nonce = randomBytes(16).toString('hex')) {
  return { 'X-SkyMyce-Timestamp': timestamp, 'X-SkyMyce-Nonce': nonce,
    'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-bridge\n${room}\n${timestamp}\n${nonce}`).digest('hex') };
}
export function validText(value, maximum = 256) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximum &&
    !/[\x00-\x1f\x7f\u00a7\u202a-\u202e\u2066-\u2069]/u.test(value);
}
export function discordPacket(message, guild, channel, room = 'friends', maximum = 256, rich = false) {
  if (room === GLOBAL_ROOM) return globalDiscordPacket(message, guild, channel);
  if (rich && ['friends', 'testing'].includes(room) && !message.author?.bot) {
    const packet = globalDiscordPacket(message, guild, channel);
    return packet ? { ...packet, scope: 'room', room } : null;
  }
  if (!['friends', 'testing'].includes(room) || message.guildId !== guild || message.channelId !== channel || message.author?.bot || message.webhookId ||
      !/^\d{17,20}$/.test(message.id ?? '') || !/^\d{17,20}$/.test(message.author?.id ?? '') || !validText(message.content, maximum)) return null;
  const display = message.member?.displayName ?? message.author.globalName ?? message.author.username ?? 'Discord user';
  const from = [...display.replace(/[\x00-\x1f\x7f\u00a7\u202a-\u202e\u2066-\u2069]/gu, '')].slice(0, 32).join('') || 'Discord user';
  return { type: 'discord_message', id: message.id, message_id: message.id, source: 'discord', room, discordId: message.author.id, from, text: message.content };
}
export function minecraftContent(packet, room = 'friends', maximum = 256) {
  if (room === GLOBAL_ROOM) return globalMinecraftContent(packet);
  if (packet?.type !== 'irc_message' || packet.source !== 'minecraft' || packet.room !== room || packet.message_id !== packet.id ||
      !/^[a-f0-9]{32}$/.test(packet.id ?? '') || !/^[A-Za-z0-9_]{1,16}$/.test(packet.from ?? '') || !validText(packet.text, maximum)) return null;
  const card = minecraftCard(packet, 'room', room);
  return card ? { ...card, nonce: createHash('sha256').update(packet.id).digest('hex').slice(0, 24), enforceNonce: true } : null;
}
export function reconnectDelay(attempt, random = Math.random) {
  const base = Math.min(60000, 1000 * 2 ** Math.min(attempt, 6));
  return Math.min(60000, base + Math.floor(random() * 1000));
}
function globalDeliveryLive(packet, now) {
  if (!Number.isSafeInteger(packet.timestamp) || now - packet.timestamp >= 120000 || packet.timestamp - now > 30000) return false;
  return packet.expiresAt === undefined || Number.isSafeInteger(packet.expiresAt) &&
    packet.expiresAt > packet.timestamp && packet.expiresAt <= packet.timestamp + 120000 && packet.expiresAt > now;
}
export class RelayBridge {
  constructor({ url, room, secret, onMessage, onResult = () => {}, policy = limits(), globalRate,
      channelId, receipts, ownBotId, onOperation, now = Date.now, socketFactory = (url, options) => new WebSocket(url, options) }) {
    this.url = new URL('/discord/bridge', url);
    if (!['https:', 'wss:'].includes(this.url.protocol) || this.url.username || this.url.password || !['friends', 'testing', GLOBAL_ROOM].includes(room) ||
        room === GLOBAL_ROOM && (!/^\d{17,20}$/.test(channelId ?? '') || !receipts)) throw new Error('Invalid Sky-Myce relay configuration');
    this.url.protocol = 'wss:'; this.url.searchParams.set('room', room);
    Object.assign(this, { room, secret, onMessage, onResult, policy, now, socketFactory, channelId, receipts, ownBotId, onOperation });
    this.inFlight = new Set();
    this.messageTasks = new Map(); this.richChatVersion = 0;
    this.seen = new ExpiringIds(policy.dedupSize, policy.dedupTtlMs, now);
    this.outboundSeen = new ExpiringIds(policy.dedupSize, policy.dedupTtlMs, now);
    this.globalRate = globalRate ?? new RateLimit(policy.globalBurst, policy.globalRefillMs, 1, now);
    this.attempt = 0; this.stopped = false; this.ready = false; this.deliveries = 0;
  }
  connect() {
    if (this.stopped || this.socket || this.reconnect) return;
    let socket;
    try { socket = this.socketFactory(this.url, { headers: bridgeHeaders(this.secret, this.room), maxPayload: this.room === GLOBAL_ROOM || this.receipts?.metadata ? 32768 : 8192, handshakeTimeout: 10000, followRedirects: false }); }
    catch { this.scheduleReconnect(); return; }
    this.socket = socket;
    const pendingDeliveries = []; let pendingDeliveryBytes = 0;
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
      if (this.socket !== socket || binary || raw.length > 32768) return;
      const text = raw.toString();
      if (text === 'pong') { this.alive = true; if (this.ready && this.now() - this.openedAt >= 30000) this.attempt = 0; return; }
      let packet;
      try { packet = JSON.parse(text); } catch { return; }
      if (packet?.type === 'bridge_ready' && packet.protocol === 3 && packet.room === this.room &&
          (this.room !== GLOBAL_ROOM || packet.globalChat === true)) {
        this.ready = true; clearTimeout(this.handshake);
        if (packet.richChatVersion === 1 && typeof this.receipts?.metadata === 'function' && /^\d{17,20}$/.test(this.ownBotId ?? '')) {
          this.richPending = randomBytes(16).toString('hex');
          this.capabilityWait = new Promise(resolve => { this.capabilityResolve = resolve; });
          this.handshake = setTimeout(() => socket.terminate(), 15000);
          socket.send(JSON.stringify({ type: 'bridge_capabilities', id: this.richPending, richChatVersion: 1 }));
        }
        console.info(JSON.stringify({ event: 'relay_connected', room: this.room })); return;
      }
      if (!this.ready) return;
      if (packet?.type === 'irc_result' && packet.id === this.richPending && packet.scope === this.scope && packet.room === this.room) {
        if (!packet.error) this.richChatVersion = 1;
        this.richPending = undefined; clearTimeout(this.handshake);
        this.capabilityResolve?.(!packet.error); this.capabilityResolve = undefined;
        const buffered = pendingDeliveries.splice(0); pendingDeliveryBytes = 0;
        if (!packet.error) for (const raw of buffered) socket.emit('message', raw, false);
        return;
      }
      if (packet?.type === 'irc_operation') { this.receiveOperation(packet); return; }
      if (this.richPending && packet?.type === 'irc_message') {
        if (pendingDeliveries.length < 64 && pendingDeliveryBytes + raw.length <= 1_048_576) {
          pendingDeliveries.push(Buffer.from(raw)); pendingDeliveryBytes += raw.length;
        }
        return;
      }
      if (packet?.type === 'irc_result' && /^\d{17,20}$/.test(packet.id ?? '')) {
        if (this.room === GLOBAL_ROOM && packet.scope !== 'global') return;
        if (packet.scope !== undefined && packet.scope !== this.scope || packet.room !== undefined && packet.room !== this.room) return;
        if (this.room === GLOBAL_ROOM && packet.part === 'embeds') return;
        try { this.onResult(packet.id, typeof packet.error === 'string' ? packet.error : null); } catch { /* Caller owns UI failures. */ }
        return;
      }
      const content = minecraftContent(packet, this.room, this.policy.inboundLength);
      if (this.room === GLOBAL_ROOM || this.richChatVersion === 1 && this.receipts) {
        if (!content || this.room === GLOBAL_ROOM && !globalDeliveryLive(packet, this.now()) ||
            this.inFlight.has(packet.id) || this.inFlight.size >= 128) return;
        this.inFlight.add(packet.id);
        Promise.resolve().then(async () => {
          if (this.stopped || this.room === GLOBAL_ROOM && !globalDeliveryLive(packet, this.now())) return;
          const known = this.receipts.get(packet.id);
          if (known) { this.acknowledge(packet.id, known); return; }
          const message = await this.onMessage(content);
          if (!/^\d{17,20}$/.test(message?.id ?? '')) throw new Error('Discord delivery did not return its ID');
          await this.receipts.record(packet.id, message.id, this.richChatVersion === 1 ? { room: this.room, channelId: this.channelId, authorId: this.ownBotId, name: packet.from, revision: packet.revision ?? 0 } : undefined);
          this.receipts.setCard?.(packet.id, packet);
          this.acknowledge(packet.id, message.id);
        }).catch(() => console.warn(JSON.stringify({ event: 'discord_delivery_unavailable', room: this.room })))
          .finally(() => this.inFlight.delete(packet.id));
        return;
      }
      if (!content || !this.seen.accept(packet.id) || !this.globalRate.allow('global') || this.deliveries >= this.policy.globalBurst) return;
      this.deliveries++;
      Promise.resolve().then(() => this.onMessage(content)).catch(() => {
        console.warn(JSON.stringify({ event: 'discord_delivery_unavailable', room: this.room }));
      }).finally(() => this.deliveries--);
    });
    socket.on('error', () => {});
    socket.once('close', () => {
      if (this.socket !== socket) return;
      this.socket = undefined; this.ready = false; this.richChatVersion = 0; this.richPending = undefined;
      pendingDeliveries.length = 0; pendingDeliveryBytes = 0;
      this.capabilityResolve?.(false); this.capabilityResolve = undefined; this.capabilityWait = undefined;
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
    const global = this.room === GLOBAL_ROOM;
    if (!this.ready || this.richPending || this.socket?.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384 ||
        packet.room !== this.room || packet.source !== 'discord' || packet.message_id !== packet.id ||
        !(global ? packet.scope === 'global' && packet.channelId === this.channelId && (packet.part === undefined || packet.part === 'embeds') && typeof packet.text === 'string' && packet.text.trim() && packet.text.length <= 4096
          : (this.richChatVersion === 1 ? typeof packet.text === 'string' && packet.text.trim() && packet.text.length <= 4096 && packet.scope === 'room' && packet.channelId === this.channelId : validText(packet.text, this.policy.outboundLength)) && this.globalRate.allow('global')) || !this.outboundSeen.accept(global ? `${packet.id}:${packet.part ?? 'message'}` : packet.id)) return false;
    try { this.socket.send(JSON.stringify(packet)); return true; } catch { return false; }
  }
  acknowledge(id, message_id) {
    if (!this.ready || this.socket?.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384) return false;
    try { this.socket.send(JSON.stringify({ type: 'discord_ack', scope: this.scope, room: this.room, id, message_id, channelId: this.channelId })); return true; }
    catch { return false; }
  }
  get scope() { return this.room === GLOBAL_ROOM ? 'global' : 'room'; }
  async whenCapabilitiesReady() { return this.richPending ? await this.capabilityWait : this.ready; }
  sendOperation(packet) {
    if (this.richChatVersion !== 1 || !this.ready || this.socket?.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384 ||
        packet.type !== 'discord_operation' || packet.scope !== this.scope || packet.room !== this.room || packet.channelId !== this.channelId ||
        packet.source !== 'discord' || !/^[a-f0-9]{32}$/.test(packet.id ?? '') || !/^\d{17,20}$/.test(packet.message_id ?? '') ||
        !/^\d{17,20}$/.test(packet.discordId ?? '') || !['edit', 'delete', 'reaction'].includes(packet.operation) ||
        packet.operation === 'reaction' && (!REACTION_EMOJI.includes(packet.emoji) || typeof packet.active !== 'boolean') ||
        packet.operation === 'edit' && (typeof packet.text !== 'string' || !packet.text.trim() || packet.text.length > 4096) ||
        !this.receipts.source(packet.message_id, this.room) || !this.outboundSeen.accept(`operation:${packet.id}`)) return false;
    try { this.socket.send(JSON.stringify(packet)); return true; } catch { return false; }
  }
  receiptOperation(packet) {
    if (!this.ready || this.socket?.readyState !== WebSocket.OPEN || this.socket.bufferedAmount > 16384) return false;
    try { this.socket.send(JSON.stringify({ type: 'discord_operation_ack', id: packet.id, scope: this.scope, room: this.room,
      channelId: this.channelId, messageId: packet.messageId, message_id: packet.discordMessageId, revision: packet.revision })); return true; } catch { return false; }
  }
  receiveOperation(packet) {
    if (this.richChatVersion !== 1 || !validBridgeOperation(packet, this.scope, this.room, this.now()) || typeof this.onOperation !== 'function' ||
        this.inFlight.has(`op:${packet.id}`) || this.inFlight.size >= 128) return;
    const mapping = this.receipts.metadata(packet.messageId), source = this.receipts.source(packet.discordMessageId, this.room);
    const owned = mapping?.discordId === packet.discordMessageId && mapping.room === this.room && mapping.channelId === this.channelId && mapping.authorId === this.ownBotId;
    if (packet.operation !== 'reaction' ? !owned : !owned && (!source || source.channelId !== this.channelId)) return;
    this.inFlight.add(`op:${packet.id}`);
    const before = this.messageTasks.get(packet.messageId) ?? Promise.resolve();
    const task = before.catch(() => {}).then(async () => {
      if (this.stopped || !validBridgeOperation(packet, this.scope, this.room, this.now())) return;
      const prior = this.receipts.operation(packet.id);
      if (prior) {
        if (prior.messageId === packet.messageId && prior.discordId === packet.discordMessageId && prior.revision === packet.revision) this.receiptOperation(packet);
        return;
      }
      const latest = this.receipts.metadata(packet.messageId) ?? this.receipts.source(packet.discordMessageId, this.room);
      if (!latest || (latest.revision ?? 0) < packet.revision) await this.onOperation(packet, { owned, authorId: owned ? this.ownBotId : source.authorId });
      await this.receipts.recordOperation(packet.id, packet.messageId, packet.revision, packet.discordMessageId);
      this.receiptOperation(packet);
    }).catch(error => {
      if (['message_deleted', 'message_expired', 'not_authorized', 'invalid_operation'].includes(error?.message) && this.ready && this.socket?.readyState === WebSocket.OPEN) {
        try { this.socket.send(JSON.stringify({ type: 'discord_operation_result', id: packet.id, scope: this.scope, room: this.room, channelId: this.channelId, error: error.message })); }
        catch { /* An unavailable socket does not acknowledge the bounded backend retry. */ }
      } else console.warn(JSON.stringify({ event: 'discord_operation_unavailable', room: this.room }));
    }).finally(() => { this.inFlight.delete(`op:${packet.id}`); if (this.messageTasks.get(packet.messageId) === task) this.messageTasks.delete(packet.messageId); });
    this.messageTasks.set(packet.messageId, task);
  }
  close() {
    this.stopped = true; this.ready = false;
    this.capabilityResolve?.(false); this.capabilityResolve = undefined;
    clearTimeout(this.reconnect); clearTimeout(this.handshake); clearInterval(this.heartbeat);
    const socket = this.socket;
    if (socket) {
      const timer = setTimeout(() => socket.terminate(), 2000); timer.unref?.();
      socket.once('close', () => clearTimeout(timer));
      socket.close(1000, 'Sky-Myce shutdown');
    }
  }
}
