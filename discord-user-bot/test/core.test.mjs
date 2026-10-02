import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { configuration, limits, ExpiringIds, RateLimit } from '../src/config.mjs';
import { minecraftContent, RelayBridge, reconnectDelay } from '../src/relay.mjs';
import { registrationPlan, registerCommands } from '../../discord-bots/src/registration.mjs';
import { channelOverwrites, effectivePermissions, permissions, findObject } from '../../discord-bots/src/provisioning.mjs';

test('staff overwrites hide ordinary users and User Bot while preserving backend/developer access', () => {
  const env = { DISCORD_GUILD_ID: 'guild', BACKEND_BOT_APPLICATION_ID: 'backend', USER_BOT_APPLICATION_ID: 'user', DEVELOPER_USER_IDS: 'allowlisted' };
  const roles = [{ id: 'guild', permissions: '3072' }, { id: 'developer', permissions: '0' }, { id: 'ordinary', permissions: '0' }, { id: 'admin', permissions: '8' }];
  const channel = { permission_overwrites: [{ id: 'ordinary', type: 0, allow: '1024', deny: '0' }] };
  channel.permission_overwrites = channelOverwrites(channel, env, 'staff', 'developer');
  for (const member of [{ id: 'normal', roles: [] }, { id: 'normal', roles: ['ordinary'] }, { id: 'user', roles: [] }]) {
    assert.equal(effectivePermissions('guild', roles, member, channel) & permissions.view, 0n);
  }
  for (const member of [{ id: 'backend', roles: [] }, { id: 'dev', roles: ['developer'] }, { id: 'allowlisted', roles: [] }]) {
    assert.equal(effectivePermissions('guild', roles, member, channel) & (permissions.view | permissions.send), permissions.view | permissions.send);
  }
  assert.ok(effectivePermissions('guild', roles, { id: 'admin-user', roles: ['admin'] }, channel) & permissions.view);
  assert.deepEqual(channelOverwrites(channel, env, 'staff', 'developer'), channel.permission_overwrites);
  assert.throws(() => findObject([{ id: 'a', name: 'audit-log', type: 0 }, { id: 'b', name: 'audit log', type: 0 }], null, ['audit-log'], 0), /Ambiguous/);
});

test('configuration is user-only, feature-scoped, secret-safe, and isolates rooms', () => {
  const env = { DISCORD_GUILD_ID: '11111111111111111', USER_BOT_APPLICATION_ID: '22222222222222222', USER_BOT_TOKEN: 'test-secret', IRC_ROOMS: '' };
  assert.deepEqual(configuration(env).rooms, []);
  assert.throws(() => configuration({ ...env, USER_BOT_TOKEN: '' }), /Missing USER_BOT_TOKEN/);
  assert.throws(() => configuration({ ...env, IRC_ROOMS: 'testing' }), /IRC/);
  const bridge = { ...env, IRC_ROOMS: 'testing', DISCORD_IRC_TEST_CHANNEL_ID: '33333333333333333', SKY_MYCE_RELAY_URL: 'https://relay.example', SKY_MYCE_RELAY_AUTH_SECRET: 's'.repeat(32) };
  assert.equal(configuration(bridge).rooms[0], 'testing');
  assert.throws(() => configuration({ ...bridge, DISCORD_IRC_CHANNEL_ID: bridge.DISCORD_IRC_TEST_CHANNEL_ID }), /shared/);
  assert.throws(() => configuration({ ...bridge, SKY_MYCE_RELAY_URL: 'https://user:secret@relay.example' }), /^Error: Invalid SKY_MYCE_RELAY_URL$/);
  assert.throws(() => limits({ IRC_OUTBOUND_LENGTH: '257' }), /IRC_OUTBOUND_LENGTH/);
});
test('duplicate cache expires, stays bounded, and quotas recover without growing indefinitely', () => {
  let now = 0; const ids = new ExpiringIds(2, 1000, () => now);
  assert.equal(ids.accept('a'), true); assert.equal(ids.accept('a'), false);
  ids.accept('b'); ids.accept('c'); assert.equal(ids.entries.size, 2);
  now = 1000; ids.prune(); assert.equal(ids.entries.size, 0); assert.equal(ids.accept('a'), true);
  const rate = new RateLimit(1, 1000, 2, () => now);
  assert.equal(rate.allow('a'), true); assert.equal(rate.allow('a'), false);
  now += 500; assert.equal(rate.allow('a'), false);
  now += 500; assert.equal(rate.allow('a'), true);
  rate.allow('b'); assert.equal(rate.allow('c'), false); assert.equal(rate.entries.size, 2);
});
test('room/source/ID validation prevents cross-room relay injection and forged display names', () => {
  const packet = { type: 'irc_message', room: 'testing', source: 'minecraft', id: 'a'.repeat(32), message_id: 'a'.repeat(32), from: 'Steve', text: '<@11111111111111111> @everyone' };
  assert.equal(minecraftContent(packet, 'friends'), null);
  assert.deepEqual(minecraftContent(packet, 'testing').allowedMentions, { parse: [], repliedUser: false });
  assert.equal(minecraftContent({ ...packet, room: undefined }, 'testing'), null);
  assert.equal(minecraftContent({ ...packet, message_id: 'b'.repeat(32) }, 'testing'), null);
});
test('one connection/reconnect loop, handshake-gated sends, and bounded retry delay', () => {
  let made = 0;
  const socket = new EventEmitter(); Object.assign(socket, { readyState: 0, bufferedAmount: 0, close() { this.emit('close'); }, terminate() { this.emit('close'); } });
  const bridge = new RelayBridge({ url: 'https://relay.example', room: 'testing', secret: 's'.repeat(32), onMessage() {}, socketFactory() { made++; return socket; } });
  bridge.connect(); bridge.connect(); assert.equal(made, 1);
  assert.equal(bridge.send({}), false);
  socket.emit('close'); const timer = bridge.reconnect;
  socket.emit('close'); bridge.connect(); assert.equal(bridge.reconnect, timer); assert.equal(made, 1);
  for (let i = 0; i < 100; i++) assert.ok(reconnectDelay(i, () => .999) <= 60000);
  bridge.close();
});
test('registration inventories first, preserves cosmetics, and uses no bulk overwrite', async () => {
  const old = [{ id: 'old', name: 'cosmetics', type: 1, description: 'Preserved', options: [] }];
  const newCommand = { name: 'account', type: 1, description: 'My account', options: [] };
  const calls = []; let current = [...old];
  const api = async (path, method = 'GET', body) => { calls.push({ path, method }); if (method === 'POST') current.push({ ...body, id: 'new' }); return structuredClone(current); };
  const result = await registerCommands(api, 'app', 'guild', [newCommand], true);
  assert.equal(calls[0].method, 'GET'); assert.equal(calls.filter(call => call.method === 'POST').length, 1);
  assert.ok(calls.every(call => !['PUT', 'DELETE'].includes(call.method)));
  assert.equal(result.after.length, 2); assert.equal(result.intentionallyRemoved.length, 0);
  assert.ok(registrationPlan(current, [newCommand]).every(item => item.state === 'unchanged'));
});
