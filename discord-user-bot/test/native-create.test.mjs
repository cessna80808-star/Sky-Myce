import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { PendingDiscordCreates } from '../src/native-create.mjs';
import { DeliveryReceipts, GLOBAL_ROOM, resolveDiscordReply } from '../src/global-chat.mjs';
import { discordPacket } from '../src/relay.mjs';
import { messageSnapshot, nativeDiscordOperation } from '../src/chat-operations.mjs';

const guild = '11111111111111111', channelId = '22222222222222222', authorId = '33333333333333333';
const messageId = '44444444444444444', referenceId = '55555555555555555', botId = '66666666666666666';
const deferred = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; };
function message(content, fetchReference) {
  return { id: messageId, guildId: guild, channelId, content, author: { id: authorId, username: 'TestSender' },
    reference: { messageId: referenceId, channelId }, fetchReference, embeds: [], attachments: [],
    client: { user: { id: botId } } };
}
const reference = { id: referenceId, guildId: guild, channelId, content: 'parent', author: { id: authorId, username: 'TestSender' }, embeds: [], attachments: [] };
async function receipts(t) {
  const prefix = resolve(join(tmpdir(), 'sky-myce-native-create-'));
  const directory = await mkdtemp(prefix);
  t.after(async () => { assert.ok(resolve(directory).startsWith(prefix)); await rm(directory, { recursive: true, force: true }); });
  return new DeliveryReceipts(join(directory, 'receipts.json'), () => 1000).load();
}
function prepare(source, stored, published) {
  return {
    async prepare(current) {
      const packet = discordPacket(current, guild, channelId, GLOBAL_ROOM, 4096, true);
      assert.ok(packet);
      await stored.rememberSource(packet.id, { room: GLOBAL_ROOM, channelId, authorId, name: packet.from });
      const snapshot = messageSnapshot(current); snapshot.text = packet.text; snapshot.media = packet.media;
      return { packet, snapshot };
    },
    send(value) { published.push(value); return true; },
  };
}

test('native reply deletion during real reference lookup never publishes the old create or its queued mutation', async t => {
  const gate = new PendingDiscordCreates(), state = gate.begin(messageId, GLOBAL_ROOM, channelId);
  const held = deferred(), started = deferred(), published = [], stored = await receipts(t);
  const source = message('old reply', () => { started.release(); return held.promise; });
  const task = gate.publish(state, { resolve: () => resolveDiscordReply(source, guild, channelId), ...prepare(source, stored, published) });
  await started.promise;
  const queued = gate.observe(messageId, GLOBAL_ROOM, channelId, 'MESSAGE_DELETE');
  assert.equal(queued, state, 'Raw delete is retained before any durable source receipt exists');
  assert.equal(stored.source(messageId, GLOBAL_ROOM), undefined);
  held.release(reference);
  assert.equal(await task, false);
  assert.equal(state.status, 'cancelled');
  assert.equal(published.length, 0);
  assert.equal(stored.source(messageId, GLOBAL_ROOM), undefined);
  assert.equal(queued.status === 'published', false, 'The actual queued Raw operation guard cannot bridge a cancelled create');
  gate.finish(state); assert.equal(gate.entries.size, 0);
});

test('an update held behind reference lookup publishes only the freshly fetched body, then later deletion remains routable', async t => {
  const gate = new PendingDiscordCreates(), state = gate.begin(messageId, GLOBAL_ROOM, channelId);
  const held = deferred(), started = deferred(), published = [], stored = await receipts(t);
  const original = message('old reply', () => { started.release(); return held.promise; });
  const current = { ...message('edited reply', undefined), referencedMessage: reference };
  const resolutions = [];
  const task = gate.publish(state, {
    async resolve(changed) { resolutions.push(changed); return resolveDiscordReply(changed ? current : original, guild, channelId); },
    ...prepare(original, stored, published),
  });
  await started.promise;
  const queued = gate.observe(messageId, GLOBAL_ROOM, channelId, 'MESSAGE_UPDATE');
  assert.equal(stored.source(messageId, GLOBAL_ROOM), undefined);
  held.release(reference);
  assert.equal(await task, true); assert.deepEqual(resolutions, [false, true]);
  assert.equal(published.length, 1); assert.equal(published[0].packet.text, 'edited reply');
  assert.equal(published[0].packet.reply.text, 'parent'); assert.equal(queued.status, 'published');
  const routing = stored.source(messageId, GLOBAL_ROOM);
  assert.equal(routing.authorId, authorId); assert.equal(routing.channelId, channelId);
  // The queued edit already appears in the create; the production normalizer suppresses a redundant update.
  assert.equal(nativeDiscordOperation('MESSAGE_UPDATE', current, routing, published[0].snapshot, 'a'.repeat(32),
    { guild, channel: channelId, ownBotId: botId }), null);
  const deletion = nativeDiscordOperation('MESSAGE_DELETE', { id: messageId, guildId: guild, channelId }, routing,
    published[0].snapshot, 'b'.repeat(32), { guild, channel: channelId, ownBotId: botId });
  assert.equal(deletion.operation, 'delete'); assert.equal(deletion.discordId, authorId);
  gate.finish(state);
  assert.equal((await new DeliveryReceipts(stored.path, () => 1000).load()).source(messageId, GLOBAL_ROOM).authorId, authorId);
});

test('delete or update during durable source registration is checked again before synchronous publication', async t => {
  for (const event of ['MESSAGE_DELETE', 'MESSAGE_UPDATE']) {
    const gate = new PendingDiscordCreates(), state = gate.begin(messageId, GLOBAL_ROOM, channelId);
    const stored = await receipts(t), registered = deferred(), held = deferred(), published = [];
    const initial = message('old body', undefined), fresh = message('new body', undefined);
    const base = prepare(initial, stored, published); let preparations = 0;
    const task = gate.publish(state, {
      resolve: async changed => changed ? fresh : initial,
      async prepare(value) { const result = await base.prepare(value); if (++preparations === 1) { registered.release(); await held.promise; } return result; },
      send: base.send,
    });
    await registered.promise; gate.observe(messageId, GLOBAL_ROOM, channelId, event); held.release();
    assert.equal(await task, event === 'MESSAGE_UPDATE');
    assert.deepEqual(published.map(value => value.packet.text), event === 'MESSAGE_UPDATE' ? ['new body'] : []);
    gate.finish(state);
  }
});

test('pending creates are bounded, channel scoped, and retired on shutdown or continuous source changes', async () => {
  const gate = new PendingDiscordCreates(1, 4), state = gate.begin(messageId, GLOBAL_ROOM, channelId);
  assert.equal(gate.begin(referenceId, GLOBAL_ROOM, channelId), null);
  assert.equal(gate.begin('bad', GLOBAL_ROOM, channelId), null);
  assert.equal(gate.observe(messageId, 'testing', channelId, 'MESSAGE_DELETE'), null);
  assert.equal(gate.observe(messageId, GLOBAL_ROOM, botId, 'MESSAGE_DELETE'), null);
  assert.equal(state.deleted, false);
  let lookups = 0, sent = 0;
  assert.equal(await gate.publish(state, {
    async resolve() { lookups++; gate.observe(messageId, GLOBAL_ROOM, channelId, 'MESSAGE_UPDATE'); return {}; },
    prepare: async value => value,
    send() { sent++; return true; },
  }), false);
  assert.equal(lookups, 4); assert.equal(sent, 0); assert.equal(state.status, 'cancelled');
  gate.finish(state);
  const next = gate.begin(referenceId, GLOBAL_ROOM, channelId), held = deferred(), started = deferred();
  const task = gate.publish(next, { async resolve() { started.release(); return held.promise; }, prepare: async value => value,
    send() { sent++; return true; } });
  await started.promise; gate.clear(); held.release({});
  assert.equal(await task, false); assert.equal(sent, 0); assert.equal(gate.entries.size, 0);
});
