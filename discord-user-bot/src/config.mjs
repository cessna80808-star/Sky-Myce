export const snowflake = /^\d{17,20}$/;
export const unavailable = 'This Sky-Myce service is currently unavailable.';

function integer(env, key, fallback, min, max) {
  const value = env[key] === undefined || env[key] === '' ? fallback : Number(env[key]);
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Invalid ${key}`);
  return value;
}

export function limits(env = {}) {
  return {
    outboundLength: integer(env, 'IRC_OUTBOUND_LENGTH', 256, 1, 256),
    inboundLength: integer(env, 'IRC_INBOUND_LENGTH', 256, 1, 256),
    userBurst: integer(env, 'IRC_USER_BURST', 4, 1, 12),
    userRefillMs: integer(env, 'IRC_USER_REFILL_MS', 3000, 1000, 60000),
    globalBurst: integer(env, 'IRC_GLOBAL_BURST', 20, 1, 100),
    globalRefillMs: integer(env, 'IRC_GLOBAL_REFILL_MS', 1000, 100, 60000),
    dedupSize: integer(env, 'IRC_DEDUP_SIZE', 1024, 1, 10000),
    dedupTtlMs: integer(env, 'IRC_DEDUP_TTL_MS', 300000, 1000, 3600000),
  };
}

export function configuration(env) {
  const app = env.USER_BOT_APPLICATION_ID ?? env.DISCORD_USER_APPLICATION_ID;
  for (const [name, value] of [['DISCORD_GUILD_ID', env.DISCORD_GUILD_ID], ['USER_BOT_APPLICATION_ID', app]]) {
    if (!snowflake.test(value ?? '')) throw new Error(`Missing or invalid ${name}`);
  }
  if (!env.USER_BOT_TOKEN) throw new Error('Missing USER_BOT_TOKEN');
  const rooms = (env.IRC_ROOMS ?? 'testing').split(',').map(value => value.trim()).filter(Boolean);
  const channels = { friends: env.DISCORD_IRC_CHANNEL_ID, testing: env.DISCORD_IRC_TEST_CHANNEL_ID };
  if (new Set(rooms).size !== rooms.length || rooms.some(room => !Object.hasOwn(channels, room) || !snowflake.test(channels[room] ?? '')) ||
      channels.friends && channels.friends === channels.testing) throw new Error('Invalid or shared IRC room/channel configuration');
  const url = env.SKY_MYCE_RELAY_URL ?? env.RELAY_URL;
  const secret = env.SKY_MYCE_RELAY_AUTH_SECRET ?? env.DISCORD_BRIDGE_SECRET;
  if (rooms.length) {
    let parsed;
    try { parsed = new URL(url); } catch { throw new Error('Missing or invalid SKY_MYCE_RELAY_URL'); }
    if (!['https:', 'wss:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error('Invalid SKY_MYCE_RELAY_URL');
    if (!secret || secret.length < 32) throw new Error('Missing or invalid SKY_MYCE_RELAY_AUTH_SECRET');
    if (secret === env.USER_BOT_TOKEN) throw new Error('The relay credential must be separate from USER_BOT_TOKEN');
  }
  return { app, guild: env.DISCORD_GUILD_ID, token: env.USER_BOT_TOKEN, rooms, channels, url, secret, limits: limits(env) };
}

export class ExpiringIds {
  constructor(maximum, ttl, now = Date.now) { this.maximum = maximum; this.ttl = ttl; this.now = now; this.entries = new Map(); }
  prune() { const now = this.now(); for (const [id, expiry] of this.entries) { if (expiry > now) break; this.entries.delete(id); } }
  accept(id) {
    this.prune();
    if (this.entries.has(id)) return false;
    this.entries.set(id, this.now() + this.ttl);
    while (this.entries.size > this.maximum) this.entries.delete(this.entries.keys().next().value);
    return true;
  }
}

export class RateLimit {
  // ponytail: scan at most 4096 active callers; use an expiry queue if measured traffic warrants it.
  constructor(burst, refillMs, maximum = 4096, now = Date.now) { Object.assign(this, { burst, refillMs, maximum, now }); this.entries = new Map(); }
  allow(id) {
    const now = this.now();
    for (const [key, entry] of this.entries) if (now - entry.at >= this.burst * this.refillMs) this.entries.delete(key);
    const previous = this.entries.get(id);
    if (!previous && this.entries.size >= this.maximum) return false;
    const credits = Math.min(this.burst, previous ? previous.credits + (now - previous.at) / this.refillMs : this.burst);
    this.entries.set(id, { credits: credits >= 1 ? credits - 1 : credits, at: now });
    return credits >= 1;
  }
}
