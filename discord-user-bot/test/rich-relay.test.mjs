import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { DeliveryReceipts, GLOBAL_ROOM, minecraftCard } from '../src/global-chat.mjs';
import { RelayBridge, discordPacket, minecraftContent } from '../src/relay.mjs';
import { applyDiscordOperation, descriptionFingerprint, discordEventId, gatewayMessage, messageSnapshot, nativeDiscordOperation } from '../src/chat-operations.mjs';

const guild = '11111111111111111', channelId = '22222222222222222', human = '33333333333333333';
const bot = '44444444444444444', destination = '55555555555555555', stranger = '66666666666666666';
const canonical = 'a'.repeat(32), uuid = 'c'.repeat(32);
const operationId = n => n.toString(16).padStart(32, '0');
const scopeFor = room => room === GLOBAL_ROOM ? 'global' : 'room';
const sender = { name: 'Steve', uuid, cosmeticName: 'Champion', discordId: human };
const minecraft = (room = GLOBAL_ROOM, extra = {}) => ({ type: 'irc_message', scope: scopeFor(room), room,
  source: 'minecraft', id: canonical, message_id: canonical, from: 'Steve', sender, text: 'hello',
  model: 1, media: [], revision: 0, reactions: [], timestamp: 1000, expiresAt: 121000, ...extra });
const operation = (room = GLOBAL_ROOM, extra = {}) => ({ type: 'irc_operation', scope: scopeFor(room), room,
  id: operationId(1), messageId: canonical, discordMessageId: destination, operation: 'edit', text: 'new body',
  sender, media: [], reactions: [], revision: 1, timestamp: 1000, expiresAt: 121000, ...extra });

async function storage(t, now = () => 1000) {
  const prefix = resolve(join(tmpdir(), 'sky-myce-rich-relay-'));
  const directory = await mkdtemp(prefix);
  t.after(async () => {
    assert.ok(resolve(directory).startsWith(prefix));
    await rm(directory, { recursive: true, force: true });
  });
  const path = join(directory, 'receipts.json');
  return { path, receipts: await new DeliveryReceipts(path, now).load() };
}
async function owned(receipts, room = GLOBAL_ROOM, extra = {}) {
  await receipts.record(canonical, destination, { room, channelId, authorId: bot, name: 'Steve', revision: 0, ...extra });
}
function bridgeFixture(t, { room = GLOBAL_ROOM, receipts, onOperation, onMessage = async () => ({ id: destination }), rich = true,
    now = () => 1000, ready = true, onResult = () => {} } = {}) {
  const socket = new EventEmitter(), sent = [];
  Object.assign(socket, { readyState: 1, bufferedAmount: 0, send(value) { sent.push(JSON.parse(value)); },
    close() { this.emit('close'); }, terminate() { this.emit('close'); } });
  const bridge = new RelayBridge({ url: 'https://relay.example', room, channelId, secret: 's'.repeat(32),
    ownBotId: bot, receipts, now, onMessage, onOperation, onResult, socketFactory: () => socket });
  const receive = packet => socket.emit('message', Buffer.from(JSON.stringify(packet)), false);
  const readyPacket = { type: 'bridge_ready', protocol: 3, room, ...(room === GLOBAL_ROOM ? { globalChat: true } : {}),
    ...(rich ? { richChatVersion: 1 } : {}) };
  bridge.connect(); socket.emit('open');
  if (ready) receive(readyPacket);
  t.after(() => bridge.close());
  return { bridge, socket, sent, receive, readyPacket,
    capabilities() {
      const request = sent.find(item => item.type === 'bridge_capabilities');
      assert.ok(request);
      receive({ type: 'irc_result', id: request.id, scope: scopeFor(room), room });
      assert.equal(bridge.richChatVersion, 1);
    },
    packets(type) { return sent.filter(item => item.type === type); } };
}
async function drain(bridge) {
  const deadline = Date.now() + 3000;
  while (bridge.inFlight.size && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(bridge.inFlight.size, 0, 'adapter work did not settle');
  await bridge.receipts?.writing;
}
function discordFixture({ authorId = bot, messageExtra = {}, channelExtra = {}, fetchError } = {}) {
  const calls = [], expected = [];
  const message = { id: destination, channelId, guildId: guild, author: { id: authorId },
    embeds: [{ description: 'old body', footer: { text: 'Minecraft · Sky-Myce Global' } }],
    async edit(body) { calls.push({ kind: 'edit', body }); this.embeds = body.embeds; return this; },
    async delete() { calls.push({ kind: 'delete' }); }, ...messageExtra };
  const channel = { id: channelId, guildId: guild, client: { rest: {
    async put(route) { calls.push({ kind: 'put', route }); }, async delete(route) { calls.push({ kind: 'remove', route }); } } },
    messages: { async fetch(options) { calls.push({ kind: 'fetch', options }); if (fetchError) throw fetchError; return message; } }, ...channelExtra };
  return { message, channel, calls, expected,
    context(receipts, owned = true) { return { channel, guild, ownBotId: bot, authorId, owned, receipts,
      expectEdit(id, description) { expected.push({ id, hash: descriptionFingerprint(description) }); } }; } };
}

for (const room of [GLOBAL_ROOM, 'testing']) {
  test(`${room}: Minecraft delivery waits for capability ACK and pending packets cannot cross a failed or replaced transport`, async t => {
    const { receipts } = await storage(t);
    let posted = 0;
    const f = bridgeFixture(t, { room, receipts, onMessage: async () => { posted++; return { id: destination }; } });
    await receipts.rememberSource(stranger, { room, channelId, authorId: human });
    const native = { type: 'discord_operation', id: operationId(1), scope: scopeFor(room), room, source: 'discord',
      message_id: stranger, channelId, discordId: human, operation: 'reaction', emoji: '👍', active: true };
    assert.equal(f.bridge.sendOperation(native), false);
    f.receive(minecraft(room)); f.receive(minecraft(room)); await drain(f.bridge);
    assert.equal(posted, 0); assert.equal(receipts.metadata(canonical), undefined);
    assert.equal(f.packets('discord_ack').length, 0);
    f.capabilities(); await drain(f.bridge);
    assert.equal(posted, 1); assert.equal(f.packets('discord_ack').length, 1);
    assert.deepEqual(receipts.metadata(canonical), { discordId: destination, room, channelId, authorId: bot,
      name: 'Steve', revision: 0, expires: 901000 });

    let failedPosts = 0;
    const failedId = operationId(100);
    const failed = bridgeFixture(t, { room, receipts, onMessage: async () => { failedPosts++; return { id: destination }; } });
    failed.receive(minecraft(room, { id: failedId, message_id: failedId }));
    const failedCapability = failed.packets('bridge_capabilities')[0];
    const failedWait = failed.bridge.whenCapabilitiesReady();
    failed.receive({ type: 'irc_result', id: failedCapability.id, scope: scopeFor(room), room, error: 'not_authorized' });
    assert.equal(await failedWait, false); await drain(failed.bridge);
    assert.equal(failedPosts, 0); assert.equal(receipts.metadata(failedId), undefined);
    assert.equal(failed.packets('discord_ack').length, 0);

    let disconnectedPosts = 0;
    const disconnectedId = operationId(101);
    const replaced = bridgeFixture(t, { room, receipts, onMessage: async () => { disconnectedPosts++; return { id: destination }; } });
    replaced.receive(minecraft(room, { id: disconnectedId, message_id: disconnectedId }));
    const oldCapability = replaced.packets('bridge_capabilities')[0];
    const oldWait = replaced.bridge.whenCapabilitiesReady();
    replaced.socket.emit('close');
    assert.equal(await oldWait, false);
    clearTimeout(replaced.bridge.reconnect); replaced.bridge.reconnect = undefined;
    const nextSocket = new EventEmitter();
    Object.assign(nextSocket, { readyState: 1, bufferedAmount: 0, send(value) { replaced.sent.push(JSON.parse(value)); },
      close() { this.emit('close'); }, terminate() { this.emit('close'); } });
    replaced.bridge.socketFactory = () => nextSocket;
    replaced.bridge.connect(); nextSocket.emit('open');
    const nextReceive = packet => nextSocket.emit('message', Buffer.from(JSON.stringify(packet)), false);
    nextReceive(replaced.readyPacket);
    const newCapability = replaced.packets('bridge_capabilities').at(-1);
    assert.notEqual(newCapability.id, oldCapability.id);
    replaced.receive({ type: 'irc_result', id: oldCapability.id, scope: scopeFor(room), room });
    assert.equal(replaced.bridge.richChatVersion, 0);
    nextReceive({ type: 'irc_result', id: newCapability.id, scope: scopeFor(room), room });
    await drain(replaced.bridge);
    assert.equal(replaced.bridge.richChatVersion, 1);
    assert.equal(disconnectedPosts, 0); assert.equal(receipts.metadata(disconnectedId), undefined);
    assert.equal(replaced.packets('discord_ack').length, 0);
  });

  test(`${room}: rich startup waits for the exact capability receipt and preserves legacy fallback`, async t => {
    const { receipts } = await storage(t); await owned(receipts, room);
    let applied = 0;
    const f = bridgeFixture(t, { room, receipts, onOperation: async () => applied++ });
    const request = f.packets('bridge_capabilities')[0];
    const outbound = discordPacket({ id: destination, guildId: guild, channelId, content: 'hi', author: { id: human, username: 'Human' } },
      guild, channelId, room, 256, true);
    assert.equal(f.bridge.send(outbound), false);
    f.receive(operation(room)); await drain(f.bridge); assert.equal(applied, 0);
    f.receive({ type: 'irc_result', id: request.id, scope: 'party', room });
    f.receive({ type: 'irc_result', id: request.id, scope: scopeFor(room), room: 'other' });
    assert.equal(f.bridge.richChatVersion, 0);
    const waiting = f.bridge.whenCapabilitiesReady();
    f.capabilities(); assert.equal(await waiting, true);
    assert.equal(f.bridge.send(outbound), true);
    f.receive(operation(room)); await drain(f.bridge); assert.equal(applied, 1);
    const legacy = bridgeFixture(t, { room, receipts, rich: false });
    assert.equal(await legacy.bridge.whenCapabilitiesReady(), true);
    assert.equal(legacy.bridge.richChatVersion, 0);
    assert.equal(legacy.packets('bridge_capabilities').length, 0);
    legacy.receive(operation(room)); await drain(legacy.bridge);
    assert.equal(legacy.packets('discord_operation_ack').length, 0);
  });

  test(`${room}: edits and deletes fetch the real Discord owner before changing a card`, async t => {
    const { receipts } = await storage(t); await owned(receipts, room);
    const d = discordFixture();
    const edit = operation(room, { text: '*body*', mentions: [stranger], reply: { from: 'Earlier', text: 'reply preview', discordMessageId: stranger },
      media: [{ type: 'gif', url: 'https://example.com/dance.gif' }] });
    await applyDiscordOperation(edit, d.context(receipts));
    assert.deepEqual(d.calls[0], { kind: 'fetch', options: { message: destination, force: true } });
    const body = d.calls[1].body;
    assert.equal(body.embeds[0].description, '\\*body\\*');
    assert.deepEqual(body.embeds[0].image, { url: 'https://example.com/dance.gif' });
    assert.deepEqual(body.allowedMentions, { parse: [], repliedUser: false });
    assert.equal(body.content, '', 'Edits clear notification tags without notifying again');
    assert.equal(body.reply, undefined);
    assert.deepEqual(d.expected, [{ id: destination, hash: descriptionFingerprint(body.embeds[0].description) }]);
    assert.equal(receipts.getCard(canonical).text, '*body*');
    await applyDiscordOperation(operation(room, { operation: 'delete' }), d.context(receipts));
    assert.deepEqual(d.calls.slice(2).map(item => item.kind), ['fetch', 'delete']);
    for (const variant of [{ messageExtra: { author: { id: human } } }, { messageExtra: { channelId: stranger } },
      { messageExtra: { guildId: stranger } }, { messageExtra: { id: stranger } }, { channelExtra: { guildId: stranger } }]) {
      const bad = discordFixture(variant);
      await assert.rejects(applyDiscordOperation(edit, bad.context(receipts)), /not_authorized/);
      assert.ok(bad.calls.every(item => item.kind === 'fetch'));
    }
  });

  test(`${room}: persisted operation receipts survive a dropped ACK and restart without another REST mutation`, async t => {
    const { receipts, path } = await storage(t); await owned(receipts, room);
    const d = discordFixture();
    let first;
    first = bridgeFixture(t, { room, receipts, onOperation: async packet => {
      await applyDiscordOperation(packet, d.context(receipts)); first.bridge.ready = false;
    } });
    first.capabilities();
    first.receive(operation(room)); first.receive(operation(room));
    await drain(first.bridge);
    assert.equal(d.calls.filter(item => item.kind === 'edit').length, 1);
    assert.equal(first.packets('discord_operation_ack').length, 0);
    const json = await readFile(path, 'utf8');
    assert.ok(!json.includes('new body') && !json.includes('Champion'));
    const restored = await new DeliveryReceipts(path, () => 1000).load();
    const next = bridgeFixture(t, { room, receipts: restored, onOperation: async () => assert.fail('Durable operation receipt must skip Discord') });
    next.capabilities(); next.receive(operation(room)); await drain(next.bridge);
    assert.deepEqual(next.packets('discord_operation_ack'), [{ type: 'discord_operation_ack', id: operationId(1),
      scope: scopeFor(room), room, channelId, messageId: canonical, message_id: destination, revision: 1 }]);
    assert.equal(restored.metadata(canonical).revision, 1);
  });
}

test('human Discord messages allow only known, scoped native reactions and cannot be edited or deleted by Minecraft', async t => {
  const { receipts } = await storage(t);
  await receipts.rememberSource(destination, { room: GLOBAL_ROOM, channelId, authorId: human, name: 'Human' });
  const d = discordFixture({ authorId: human });
  const reaction = operation(GLOBAL_ROOM, { operation: 'reaction', emoji: '❤️', active: true });
  await applyDiscordOperation(reaction, d.context(receipts, false));
  await applyDiscordOperation({ ...reaction, active: false }, d.context(receipts, false));
  assert.deepEqual(d.calls.filter(item => item.kind !== 'fetch'), [
    { kind: 'put', route: `/channels/${channelId}/messages/${destination}/reactions/${encodeURIComponent('❤️')}/@me` },
    { kind: 'remove', route: `/channels/${channelId}/messages/${destination}/reactions/${encodeURIComponent('❤️')}/@me` }]);
  for (const kind of ['edit', 'delete']) await assert.rejects(applyDiscordOperation({ ...reaction, operation: kind }, d.context(receipts, false)), /not_authorized/);
  await assert.rejects(applyDiscordOperation({ ...reaction, room: 'testing' }, d.context(receipts, false)), /not_authorized/);
  await assert.rejects(applyDiscordOperation({ ...reaction, emoji: '☠' }, d.context(receipts, false)), /invalid_operation/);
  const bad = discordFixture({ authorId: stranger });
  await assert.rejects(applyDiscordOperation(reaction, { ...bad.context(receipts, false), authorId: human }), /not_authorized/);
  assert.ok(bad.calls.every(item => item.kind === 'fetch'));
});

test('native card edits suppress own PATCH echoes and contain only the actual body plus structured media', () => {
  const source = { channelId, authorId: bot, name: 'Steve', mapped: true };
  const current = { id: destination, guildId: guild, channelId, author: { id: bot }, content: '', editedTimestamp: 1234,
    embeds: [{ description: 'Hello \\*world\\* and \\`code\\`', image: { url: 'https://example.com/dance.gif' },
      fields: [{ name: 'Reply to Earlier', value: 'THIS IS NOT THE BODY' }, { name: 'Media', value: '[Video](https://example.com/movie.mp4)' }],
      footer: { text: 'Minecraft · Sky-Myce Global · 👍 2' } }] };
  const options = { guild, channel: channelId, ownBotId: bot };
  assert.equal(nativeDiscordOperation('MESSAGE_UPDATE', current, source, undefined, operationId(1), {
    ...options, expectedDescription: descriptionFingerprint(current.embeds[0].description) }), null);
  const packet = nativeDiscordOperation('MESSAGE_UPDATE', current, source, undefined, operationId(2), options);
  assert.ok(packet, 'A genuine owned card update must not be discarded');
  assert.equal(packet.text, 'Hello *world* and `code`');
  assert.equal(packet.editedAt, 1234);
  assert.equal(packet.media[0].type, 'gif'); assert.equal(packet.media[0].url, 'https://example.com/dance.gif');
  assert.ok(!packet.text.includes('THIS IS NOT') && !packet.text.includes('Minecraft') && !packet.text.includes('Video'));
  for (const change of [{ author: { id: human } }, { channelId: stranger }, { guildId: stranger }, { embeds: [] }])
    assert.equal(nativeDiscordOperation('MESSAGE_UPDATE', { ...current, ...change }, source, undefined, operationId(3), options), null);
});

test('native Discord event normalization preserves source identity, deterministic IDs and rejects bot reaction loops', () => {
  const source = { channelId, authorId: human, name: 'Human', mapped: false };
  const options = { guild, channel: channelId, ownBotId: bot };
  const dispatch = { s: 42, t: 'MESSAGE_UPDATE', d: { id: destination, guild_id: guild, channel_id: channelId, content: 'edited' } };
  const id = discordEventId('session-1', dispatch);
  assert.equal(discordEventId('session-1', structuredClone(dispatch)), id);
  assert.notEqual(discordEventId('session-2', dispatch), id);
  assert.notEqual(discordEventId('session-1', { ...dispatch, s: 43 }), id);
  const current = gatewayMessage({ ...dispatch.d, author: { id: human, username: 'Human' },
    edited_timestamp: '2026-10-11T12:00:00.000Z', attachments: [{ id: stranger, url: 'https://example.com/dance.gif', content_type: 'image/gif' }] }, guild, channelId);
  const packet = nativeDiscordOperation('MESSAGE_UPDATE', current, source, undefined, id, options);
  assert.equal(packet.discordId, human); assert.equal(packet.media[0].mime, 'image/gif');
  assert.equal(packet.editedAt, Date.parse('2026-10-11T12:00:00.000Z'));
  const deletion = nativeDiscordOperation('MESSAGE_DELETE', { id: destination, guildId: guild, channelId }, source, undefined, id, options);
  assert.equal(deletion.operation, 'delete'); assert.equal(deletion.discordId, human);
  const reaction = gatewayMessage({ message_id: destination, guild_id: guild, channel_id: channelId, user_id: human, emoji: { name: '🔥' } }, guild, channelId);
  assert.equal(nativeDiscordOperation('MESSAGE_REACTION_ADD', reaction, source, undefined, id, options).active, true);
  assert.equal(nativeDiscordOperation('MESSAGE_REACTION_REMOVE', reaction, source, undefined, id, options).active, false);
  assert.equal(nativeDiscordOperation('MESSAGE_REACTION_ADD', { ...reaction, userId: bot }, source, undefined, id, options), null);
  assert.equal(nativeDiscordOperation('MESSAGE_UPDATE', { ...current, author: { id: stranger } }, source, undefined, id, options), null);
  assert.equal(nativeDiscordOperation('MESSAGE_DELETE', { ...current, channelId: stranger }, source, undefined, id, options), null);
  const snapshot = messageSnapshot({ ...current, content: 'x'.repeat(5000), embeds: Array(12).fill({ title: 'embed' }), attachments: Array(10).fill({ url: 'https://example.com/a.png' }) });
  assert.equal(snapshot.content.length, 4096); assert.equal(snapshot.embeds.length, 8); assert.equal(snapshot.attachments.length, 4);
});

test('outage and HTTP 429 do not acknowledge a mutation; a successful retry commits exactly one receipt', async t => {
  const { receipts } = await storage(t); await owned(receipts);
  let calls = 0;
  const failures = [Object.assign(new Error('Offline'), { code: 'ECONNRESET' }), Object.assign(new Error('Slow down'), { status: 429 })];
  const f = bridgeFixture(t, { receipts, onOperation: async () => { calls++; if (failures.length) throw failures.shift(); } });
  f.capabilities();
  for (let i = 0; i < 2; i++) {
    f.receive(operation()); await drain(f.bridge);
    assert.equal(receipts.operation(operationId(1)), undefined);
    assert.equal(f.packets('discord_operation_ack').length, 0);
    assert.equal(f.packets('discord_operation_result').length, 0);
  }
  f.receive(operation()); await drain(f.bridge);
  assert.equal(calls, 3); assert.equal(f.packets('discord_operation_ack').length, 1);
  f.receive(operation()); await drain(f.bridge);
  assert.equal(calls, 3); assert.equal(f.packets('discord_operation_ack').length, 2);
});

test('per-message mutations remain ordered and permanent permission failures return a result without a receipt', async t => {
  const { receipts } = await storage(t); await owned(receipts);
  const calls = []; let release;
  const gate = new Promise(resolve => { release = resolve; });
  const f = bridgeFixture(t, { receipts, onOperation: async packet => { calls.push(packet.revision); if (packet.revision === 1) await gate; } });
  f.capabilities(); f.receive(operation());
  f.receive(operation(GLOBAL_ROOM, { id: operationId(2), revision: 2, text: 'second' }));
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(calls, [1]);
  release(); await drain(f.bridge);
  assert.deepEqual(calls, [1, 2]); assert.deepEqual(f.packets('discord_operation_ack').map(item => item.revision), [1, 2]);
  const failed = bridgeFixture(t, { receipts, onOperation: async () => { throw new Error('not_authorized'); } });
  failed.capabilities(); failed.receive(operation(GLOBAL_ROOM, { id: operationId(3), revision: 3 })); await drain(failed.bridge);
  assert.equal(receipts.operation(operationId(3)), undefined);
  assert.deepEqual(failed.packets('discord_operation_result'), [{ type: 'discord_operation_result', id: operationId(3), scope: 'global', room: GLOBAL_ROOM, channelId, error: 'not_authorized' }]);
});

test('a delayed older human-message reaction cannot undo the newer Discord state', async t => {
  const { receipts } = await storage(t);
  await receipts.rememberSource(destination, { room: GLOBAL_ROOM, channelId, authorId: human, name: 'Human', revision: 0 });
  const d = discordFixture({ authorId: human });
  const f = bridgeFixture(t, { receipts, onOperation: packet => applyDiscordOperation(packet, d.context(receipts, false)) });
  f.capabilities();
  f.receive(operation(GLOBAL_ROOM, { id: operationId(2), operation: 'reaction', emoji: '👍', active: false, revision: 2 })); await drain(f.bridge);
  f.receive(operation(GLOBAL_ROOM, { id: operationId(1), operation: 'reaction', emoji: '👍', active: true, revision: 1 })); await drain(f.bridge);
  assert.deepEqual(d.calls.filter(item => ['put', 'remove'].includes(item.kind)).map(item => item.kind), ['remove']);
  assert.equal(receipts.source(destination, GLOBAL_ROOM).revision, 2);
  assert.equal(f.packets('discord_operation_ack').length, 2);
});

test('failed receipt writes expose no successful delivery or operation until durable retry succeeds', async t => {
  const { receipts, path } = await storage(t); await owned(receipts);
  receipts.path = join(path, 'file-is-not-a-directory', 'receipts.json');
  await assert.rejects(receipts.recordOperation(operationId(1), canonical, 1, destination));
  assert.equal(receipts.operation(operationId(1)), undefined);
  assert.equal(receipts.metadata(canonical).revision, 0);
  const other = 'd'.repeat(32);
  await assert.rejects(receipts.record(other, stranger, { room: GLOBAL_ROOM, channelId, authorId: bot, revision: 0 }));
  assert.equal(receipts.get(other), undefined);
  await assert.rejects(receipts.rememberSource(stranger, { room: GLOBAL_ROOM, channelId, authorId: human }));
  assert.equal(receipts.source(stranger, GLOBAL_ROOM), undefined);
  receipts.path = path;
  await receipts.recordOperation(operationId(1), canonical, 1, destination);
  assert.equal(receipts.operation(operationId(1)).revision, 1);
});

test('concurrent durable writes preserve all routing maps and the largest applied revision', async t => {
  const { receipts, path } = await storage(t); await owned(receipts);
  await Promise.all([
    receipts.recordOperation(operationId(1), canonical, 1, destination),
    receipts.recordOperation(operationId(2), canonical, 2, destination),
    receipts.rememberSource(stranger, { room: GLOBAL_ROOM, channelId, authorId: human }),
    receipts.record('d'.repeat(32), '77777777777777777', { room: 'testing', channelId: stranger, authorId: bot })
  ]);
  const restored = await new DeliveryReceipts(path, () => 1000).load();
  assert.equal(restored.metadata(canonical).revision, 2);
  assert.equal(restored.source(stranger, GLOBAL_ROOM).authorId, human);
  assert.equal(restored.get('d'.repeat(32)), '77777777777777777');
  assert.equal(restored.operation(operationId(1)).revision, 1);
  assert.equal(restored.operation(operationId(2)).revision, 2);
});

test('a missing Discord message is an idempotent delete and a terminal error for edits or reactions', async t => {
  const { receipts } = await storage(t); await owned(receipts);
  const d = discordFixture({ fetchError: Object.assign(new Error('Unknown message'), { code: 10008 }) });
  await applyDiscordOperation(operation(GLOBAL_ROOM, { operation: 'delete' }), d.context(receipts));
  await assert.rejects(applyDiscordOperation(operation(), d.context(receipts)), /message_deleted/);
  await assert.rejects(applyDiscordOperation(operation(GLOBAL_ROOM, { operation: 'reaction', emoji: '👍', active: true }), d.context(receipts)), /message_deleted/);
  assert.ok(d.calls.every(item => item.kind === 'fetch'));
});

test('explicit mismatched delivery result scopes cannot acknowledge another channel, while legacy room receipts still work', async t => {
  const { receipts } = await storage(t), roomResults = [], globalResults = [];
  const room = bridgeFixture(t, { room: 'testing', receipts, rich: false, onResult: (...args) => roomResults.push(args) });
  const global = bridgeFixture(t, { receipts, rich: false, onResult: (...args) => globalResults.push(args) });
  for (const scope of ['global', 'party']) room.receive({ type: 'irc_result', id: destination, scope });
  room.receive({ type: 'irc_result', id: destination, scope: 'room', room: 'friends' });
  global.receive({ type: 'irc_result', id: destination, scope: 'global', room: 'testing' });
  assert.deepEqual(roomResults, []); assert.deepEqual(globalResults, []);
  room.receive({ type: 'irc_result', id: destination });
  room.receive({ type: 'irc_result', id: stranger, scope: 'room', room: 'testing' });
  global.receive({ type: 'irc_result', id: destination, scope: 'global' });
  assert.deepEqual(roomResults, [[destination, null], [stranger, null]]);
  assert.deepEqual(globalResults, [[destination, null]]);
});

test('a socket send failure while reporting a permanent operation error never becomes an unhandled task', async t => {
  const { receipts } = await storage(t); await owned(receipts);
  const f = bridgeFixture(t, { receipts, onOperation: async () => { throw new Error('not_authorized'); } });
  f.capabilities();
  const send = f.socket.send;
  f.socket.send = value => {
    if (JSON.parse(value).type === 'discord_operation_result') throw new Error('socket closed');
    send.call(f.socket, value);
  };
  f.receive(operation()); await drain(f.bridge);
  assert.equal(receipts.operation(operationId(1)), undefined);
  assert.equal(f.packets('discord_operation_ack').length, 0);
});

test('adapter receipt write failure withholds ACK and leaves the operation eligible for retry', async t => {
  const { receipts, path } = await storage(t); await owned(receipts);
  let applied = 0;
  const f = bridgeFixture(t, { receipts, onOperation: async () => applied++ }); f.capabilities();
  receipts.path = join(path, 'file-is-not-a-directory', 'receipts.json');
  f.receive(operation());
  const deadline = Date.now() + 3000;
  while (f.bridge.inFlight.size && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5));
  await assert.rejects(receipts.writing);
  assert.equal(receipts.operation(operationId(1)), undefined);
  assert.equal(f.packets('discord_operation_ack').length, 0);
  receipts.path = path; f.receive(operation()); await drain(f.bridge);
  assert.equal(applied, 2); assert.equal(f.packets('discord_operation_ack').length, 1);
});

test('wrong scopes, channels, expired operations and every Party packet stay out of Discord', async t => {
  const { receipts } = await storage(t); await owned(receipts);
  let applied = 0;
  const f = bridgeFixture(t, { receipts, onOperation: async () => applied++ }); f.capabilities();
  for (const extra of [{ scope: 'party' }, { room: 'testing' }, { discordMessageId: stranger }, { revision: 0 }, { expiresAt: 1000 },
    { timestamp: 31001 }, { operation: 'reaction', emoji: '☠', active: true }]) f.receive(operation(GLOBAL_ROOM, extra));
  await drain(f.bridge); assert.equal(applied, 0); assert.equal(f.packets('discord_operation_ack').length, 0);
  await receipts.rememberSource(destination, { room: GLOBAL_ROOM, channelId, authorId: human });
  const native = { type: 'discord_operation', id: operationId(1), scope: 'global', room: GLOBAL_ROOM, source: 'discord',
    message_id: destination, channelId, discordId: human, operation: 'reaction', emoji: '👍', active: true };
  for (const extra of [{ scope: 'party' }, { room: 'testing' }, { channelId: stranger }, { message_id: stranger }, { emoji: '☠' }])
    assert.equal(f.bridge.sendOperation({ ...native, ...extra }), false);
  assert.equal(f.bridge.sendOperation(native), true); assert.equal(f.bridge.sendOperation(native), false);
  assert.throws(() => new RelayBridge({ url: 'https://relay.example', room: 'party-chat', secret: 's'.repeat(32), onMessage() {} }), /configuration/);
  assert.equal(minecraftCard(minecraft(GLOBAL_ROOM, { scope: 'party', room: 'party-chat' }), 'party', 'party-chat'), null);
  assert.equal(minecraftContent(minecraft(GLOBAL_ROOM, { scope: 'party', room: GLOBAL_ROOM }), GLOBAL_ROOM), null);
  f.receive(minecraft(GLOBAL_ROOM, { scope: 'party' })); await drain(f.bridge);
  assert.equal(f.packets('discord_ack').length, 0);
});

test('all Discord-bound room cards carry a stable nonce for retries before receipt persistence', () => {
  const card = minecraftContent(minecraft('testing'), 'testing');
  assert.equal(card.enforceNonce, true);
  assert.match(card.nonce, /^[a-f0-9]{24}$/);
  assert.equal(minecraftContent(minecraft('testing'), 'testing').nonce, card.nonce);
  assert.notEqual(minecraftContent(minecraft('testing', { id: 'd'.repeat(32), message_id: 'd'.repeat(32) }), 'testing').nonce, card.nonce);
});
