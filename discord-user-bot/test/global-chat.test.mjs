import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { configuration, limits, RateLimit } from '../src/config.mjs';
import { DeliveryReceipts, GLOBAL_ROOM, discordBody, globalDiscordPacket, globalEmbedPacket, globalMinecraftContent } from '../src/global-chat.mjs';
import { RelayBridge, discordPacket } from '../src/relay.mjs';

const guild = '11111111111111111', channel = '22222222222222222', author = '33333333333333333';
const discordId = '44444444444444444';
const message = () => ({ id: '55555555555555555', guildId: guild, channelId: channel, content: 'hello',
  author: { id: author, username: 'Unlinked user', bot: false } });
const minecraft = (id = 'a'.repeat(32), timestamp = 1000) => ({ type: 'irc_message', scope: 'global', room: GLOBAL_ROOM,
  source: 'minecraft', id, message_id: id, from: 'Steve', text: 'hello', timestamp, uuid: 'private-uuid', discordId: author });

async function temporary(t) {
  const directory = await mkdtemp(join(tmpdir(), 'sky-myce-chat-'));
  t.after(async () => { assert.ok(directory.startsWith(join(tmpdir(), 'sky-myce-chat-'))); await rm(directory, { recursive: true, force: true }); });
  return join(directory, 'receipts.json');
}
function memoryReceipts() {
  const map = new Map();
  return { get: id => map.get(id), async record(id, value) { map.set(id, value); } };
}
function fakeBridge({ onMessage, onResult = () => {}, receipts = memoryReceipts(), now = () => 1000 } = {}) {
  const socket = new EventEmitter(), sent = [];
  Object.assign(socket, { readyState: 1, bufferedAmount: 0, send: value => sent.push(JSON.parse(value)),
    close() { this.emit('close'); }, terminate() { this.emit('close'); } });
  const bridge = new RelayBridge({ url: 'https://relay.example', room: GLOBAL_ROOM, channelId: channel, secret: 's'.repeat(32),
    receipts, now, onResult, globalRate: new RateLimit(1, 60000), policy: limits(), onMessage: onMessage ?? (async () => ({ id: discordId })),
    socketFactory(url, options) {
      assert.equal(url.pathname, '/discord/bridge'); assert.equal(url.searchParams.get('room'), GLOBAL_ROOM);
      assert.equal(options.maxPayload, 32768); assert.equal(options.followRedirects, false); return socket;
    } });
  bridge.connect(); socket.emit('open');
  socket.emit('message', Buffer.from(JSON.stringify({ type: 'bridge_ready', protocol: 3, room: GLOBAL_ROOM })), false);
  return { bridge, socket, sent, receive: packet => socket.emit('message', Buffer.from(JSON.stringify(packet)), false) };
}
const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };

test('one configured dedicated global channel joins the existing authenticated bridge without changing legacy rooms', () => {
  const env = { DISCORD_GUILD_ID: guild, USER_BOT_APPLICATION_ID: author, USER_BOT_TOKEN: 'test-token',
    GLOBAL_CHAT_CHANNEL_ID: channel, SKY_MYCE_RELAY_URL: 'https://relay.example', SKY_MYCE_RELAY_AUTH_SECRET: 's'.repeat(32) };
  assert.deepEqual(configuration(env).rooms, [GLOBAL_ROOM]);
  assert.deepEqual(configuration({ ...env, IRC_ROOMS: '' }).rooms, [GLOBAL_ROOM]);
  assert.equal(configuration(env).channels[GLOBAL_ROOM], channel);
  assert.equal(configuration(env).receiptsPath, '.data/global-chat-receipts.json');
  assert.throws(() => configuration({ ...env, IRC_ROOMS: 'testing', DISCORD_IRC_TEST_CHANNEL_ID: channel }), /shared/);
  assert.throws(() => configuration({ ...env, GLOBAL_CHAT_CHANNEL_ID: 'invalid' }), /configuration/);
  assert.throws(() => configuration({ ...env, SKY_MYCE_RELAY_AUTH_SECRET: 'test-token' }), /SKY_MYCE_RELAY_AUTH_SECRET/);
  const fallback = { ...env, GLOBAL_CHAT_CHANNEL_ID: undefined, IRC_ROOMS: GLOBAL_ROOM, DISCORD_IRC_CHANNEL_ID: channel };
  assert.deepEqual(configuration(fallback).rooms, [GLOBAL_ROOM]);
  assert.deepEqual(configuration({ ...fallback, GLOBAL_CHAT_CHANNEL_ID: '' }).rooms, [GLOBAL_ROOM]);
  assert.deepEqual(configuration({ ...fallback, IRC_ROOMS: 'friends' }).rooms, ['friends']);
  assert.deepEqual(configuration({ ...fallback, GLOBAL_CHAT_CHANNEL_ID: '', IRC_ROOMS: undefined, DISCORD_IRC_TEST_CHANNEL_ID: discordId }).rooms, ['testing']);
  assert.deepEqual(configuration({ ...fallback, GLOBAL_CHAT_CHANNEL_ID: '', IRC_ROOMS: '' }).rooms, []);
});

test('Discord normalization preserves human text, embeds, attachments, replies and readable mentions without licensing its author', () => {
  const original = message();
  original.content = 'hello <@44444444444444444>\r\n§ text\tand normal conversation\u0000\u202e';
  original.mentions = { users: new Map([[discordId, { id: discordId, username: 'Mentioned' }]]) };
  original.guild = { members: { cache: new Map([[discordId, { displayName: 'Mentioned display' }]]) } };
  original.embeds = [{ author: { name: 'Embed author', url: 'https://example.com/author' }, title: 'Title',
    description: 'Description', fields: [{ name: 'Field', value: 'Value' }], footer: { text: 'Footer' },
    url: 'https://example.com/page', image: { url: 'https://example.com/image.png' } }];
  original.attachments = new Map([['attachment', { name: 'report.txt', url: 'https://example.com/report.txt' }]]);
  original.reference = { messageId: '66666666666666666' };
  original.channel = { messages: { cache: new Map([[original.reference.messageId, {
    channelId: channel, author: { username: 'Earlier' }, content: 'Previous message', embeds: [], attachments: [] }]]) } };
  const packet = globalDiscordPacket(original, guild, channel);
  assert.equal(packet.scope, 'global'); assert.equal(packet.room, GLOBAL_ROOM); assert.equal(packet.channelId, channel);
  assert.equal(packet.discordId, author); assert.deepEqual(packet.mentions, [discordId]);
  for (const part of ['@Mentioned display', 'text and normal conversation', 'Embed author', 'Title', 'Description', 'Field', 'Value', 'Footer',
    'https://example.com/page', 'https://example.com/author', 'https://example.com/image.png', 'report.txt: https://example.com/report.txt']) assert.ok(packet.text.includes(part), part);
  assert.ok(!packet.text.includes(discordId));
  assert.ok(!/[\r\t\x00\u00a7\u202e]/u.test(packet.text));
  assert.deepEqual(packet.reply, { message_id: original.reference.messageId, from: 'Earlier', text: 'Previous message' });
  assert.deepEqual(discordPacket(original, guild, channel, GLOBAL_ROOM), packet);
  for (const extra of [{ channelId: author }, { guildId: author }, { author: { ...original.author, bot: true } }, { webhookId: 'webhook' }, { id: 'invalid' }]) {
    assert.equal(globalDiscordPacket({ ...original, ...extra }, guild, channel), null);
  }
  assert.equal(globalDiscordPacket({ ...message(), content: '', embeds: [{ title: 'Embed only' }] }, guild, channel).text, 'Embed only');
  assert.equal(globalDiscordPacket({ ...message(), content: '', embeds: [], attachments: [] }, guild, channel), null);
  assert.ok(!discordBody({ ...message(), embeds: [{ url: 'javascript:alert(1)' }] }).includes('javascript:'));
  assert.equal(globalDiscordPacket({ ...original, reference: { ...original.reference, channelId: author } }, guild, channel).reply, undefined);
  assert.equal(globalDiscordPacket({ ...original, referencedMessage: { channelId: author, content: 'Private cross-channel text' } }, guild, channel).reply, undefined);
  assert.deepEqual(globalDiscordPacket({ ...original, channel: undefined }, guild, channel).reply,
    { message_id: original.reference.messageId, from: 'Discord user', text: '' });
  assert.equal(discordBody({ ...message(), embeds: [null, 1, { fields: [null, {}, { name: 'Safe field' }],
    url: 'https://user:password@example.com/private' }], attachments: [null, { url: 'https://token@example.com/private' }] }), 'hello\nSafe field');
  const controls = globalDiscordPacket({ ...message(), content: 'normal\u0085 conversation',
    member: { displayName: 'Display\u009f' }, embeds: [{ description: 'Embed\u0080 text' }] }, guild, channel);
  assert.equal(controls.text, 'normal conversation\nEmbed text'); assert.equal(controls.from, 'Display');
  const foreignBot = { ...message(), author: { id: author, username: 'Publisher bot', bot: true },
    client: { user: { id: discordId } }, embeds: [{ title: 'Bot announcement' }] };
  assert.ok(globalDiscordPacket(foreignBot, guild, channel).text.includes('Bot announcement'));
  assert.equal(globalDiscordPacket({ ...foreignBot, author: { ...foreignBot.author, id: discordId } }, guild, channel), null);
  assert.equal(globalDiscordPacket({ ...foreignBot, webhookId: author }, guild, channel), null);
  assert.equal(discordPacket(foreignBot, guild, channel, 'friends'), null);
});

test('long Discord content truncates clearly without breaking a surrogate pair; names and mention counts remain bounded', () => {
  const original = { ...message(), content: '😀'.repeat(3000), member: { displayName: '😀'.repeat(100) },
    mentions: { users: new Map(Array.from({ length: 120 }, (_, i) => [String(70000000000000000n + BigInt(i)), { id: String(70000000000000000n + BigInt(i)) }])) } };
  const packet = globalDiscordPacket(original, guild, channel);
  assert.ok(packet.text.length <= 4096 && packet.text.endsWith(' … [truncated]'));
  assert.ok(!/[\ud800-\udbff](?![\udc00-\udfff])/u.test(packet.text));
  assert.ok(packet.from.length <= 64); assert.equal(packet.mentions.length, 100);
});

test('late human link previews send one embed continuation without replaying text, attachments, replies or mentions', t => {
  const previous = { ...message(), content: 'https://example.com/page', embeds: [] };
  const next = { ...previous, embeds: [{ title: 'Preview title', description: 'Preview description', url: previous.content }],
    attachments: [{ name: 'Never repeat attachment', url: 'https://example.com/file' }],
    reference: { messageId: discordId, channelId: channel }, mentions: { users: new Map([[discordId, { id: discordId, username: 'Mentioned' }]]) } };
  const packet = globalEmbedPacket(previous, next, guild, channel);
  assert.equal(packet.id, previous.id); assert.equal(packet.message_id, previous.id); assert.equal(packet.part, 'embeds');
  assert.equal(packet.text, 'Preview title\nPreview description\nhttps://example.com/page');
  assert.deepEqual(packet.mentions, []); assert.equal(packet.reply, undefined); assert.ok(!packet.text.includes('attachment'));
  const results = [];
  const f = fakeBridge({ onResult: (...args) => results.push(args) }); t.after(() => f.bridge.close());
  assert.equal(f.bridge.send(globalDiscordPacket(previous, guild, channel)), true);
  assert.equal(f.bridge.send(packet), true); assert.equal(f.bridge.send(packet), false);
  assert.equal(f.bridge.send({ ...packet, part: 'text-edit' }), false);
  f.receive({ type: 'irc_result', scope: 'global', id: previous.id, part: 'embeds', error: 'duplicate_message' });
  assert.equal(results.length, 0);
  f.receive({ type: 'irc_result', scope: 'global', id: previous.id });
  assert.deepEqual(results, [[previous.id, null]]);
  for (const [before, after] of [[next, next], [undefined, next], [previous, { ...next, content: 'Edited text' }],
    [previous, { ...next, embeds: [] }], [previous, { ...next, channelId: author }],
    [previous, { ...next, author: { ...next.author, bot: true } }], [previous, { ...next, webhookId: 'webhook' }]]) {
    assert.equal(globalEmbedPacket(before, after, guild, channel), null);
  }
});

test('Minecraft sender identity stays primary, internal IDs stay private and native Discord nonce prevents retry duplicates', () => {
  const packet = minecraft();
  const content = globalMinecraftContent(packet);
  assert.equal(content.content, '[SkyMyce] Steve » hello');
  assert.deepEqual(content.allowedMentions, { parse: [], repliedUser: false });
  assert.equal(content.enforceNonce, true); assert.equal(content.nonce.length, 24);
  assert.equal(globalMinecraftContent(packet).nonce, content.nonce);
  assert.notEqual(globalMinecraftContent(minecraft('b'.repeat(32))).nonce, content.nonce);
  assert.ok(!JSON.stringify(content).includes(packet.uuid) && !JSON.stringify(content).includes(author));
  const expanded = globalMinecraftContent({ ...packet, text: '*'.repeat(1024) });
  assert.ok(expanded.content.length <= 2000 && expanded.embeds[0].description.length <= 4096);
  for (const extra of [{ source: 'discord' }, { scope: 'friends' }, { room: 'testing' }, { from: '[MC] Other' },
    { message_id: 'b'.repeat(32) }, { text: 'x'.repeat(1025) }]) assert.equal(globalMinecraftContent({ ...packet, ...extra }), null);
});

test('delivery receipts persist only IDs, restore after bot restart, expire and remain bounded', async t => {
  const path = await temporary(t); let now = 1000;
  const receipts = await new DeliveryReceipts(path, () => now).load();
  await receipts.record('a'.repeat(32), discordId);
  const data = JSON.parse(await readFile(path, 'utf8'));
  assert.deepEqual(data, { schema: 1, entries: [['a'.repeat(32), discordId, 121000]] });
  assert.equal((await new DeliveryReceipts(path, () => now).load()).get('a'.repeat(32)), discordId);
  now = 121000; assert.equal(receipts.get('a'.repeat(32)), undefined);
  const entries = Array.from({ length: 1024 }, (_, i) => [i.toString(16).padStart(32, '0'), discordId, now + 120000]);
  await writeFile(path, JSON.stringify({ schema: 1, entries }));
  const full = await new DeliveryReceipts(path, () => now).load();
  await full.record('f'.repeat(32), discordId);
  assert.equal(full.entries.size, 1024); assert.equal(full.get('0'.repeat(32)), undefined);
  for (const invalid of [{ schema: 2, entries: [] }, { schema: 1, entries: [['bad', discordId, now]] },
    { schema: 1, entries: [['a'.repeat(32), discordId, now + 120001]] }]) {
    await writeFile(path, JSON.stringify(invalid));
    await assert.rejects(new DeliveryReceipts(path, () => now).load());
  }
  await writeFile(path, 'x'.repeat(262145)); await assert.rejects(new DeliveryReceipts(path).load());
});

test('global bridge ACKs successful sends and cached retries but never claims failed Discord delivery', async t => {
  let calls = 0;
  const f = fakeBridge({ onMessage: async content => { calls++; assert.equal(content.enforceNonce, true); return { id: discordId }; } });
  t.after(() => f.bridge.close());
  f.receive(minecraft()); f.receive(minecraft()); await settle();
  assert.equal(calls, 1);
  assert.deepEqual(f.sent[0], { type: 'discord_ack', scope: 'global', room: GLOBAL_ROOM,
    id: 'a'.repeat(32), message_id: discordId, channelId: channel });
  f.receive(minecraft()); await settle(); assert.equal(calls, 1); assert.equal(f.sent.length, 2);
  const restarted = fakeBridge({ receipts: f.bridge.receipts, onMessage: async () => { throw new Error('Must not resend'); } });
  t.after(() => restarted.bridge.close()); restarted.receive(minecraft()); await settle();
  assert.equal(restarted.sent.length, 1);
  const failed = fakeBridge({ onMessage: async () => { throw new Error('Offline'); } });
  t.after(() => failed.bridge.close()); failed.receive(minecraft()); await settle(); assert.equal(failed.sent.length, 0);
});

test('a restored native receipt ACKs the same outbox ID after a lost connection without sending another Discord message', async t => {
  const path = await temporary(t);
  const receipts = await new DeliveryReceipts(path, () => 1000).load();
  const first = fakeBridge({ receipts });
  t.after(() => first.bridge.close());
  first.receive(minecraft()); first.bridge.ready = false;
  await settle(); await receipts.writing; await settle();
  assert.equal(first.sent.length, 0);
  const restored = await new DeliveryReceipts(path, () => 1000).load();
  const next = fakeBridge({ receipts: restored, onMessage: async () => { assert.fail('Stored receipt must prevent resending'); } });
  t.after(() => next.bridge.close());
  next.receive(minecraft()); await settle();
  assert.equal(next.sent.length, 1); assert.equal(next.sent[0].message_id, discordId);
});

test('global quota belongs to backend; frontend still rejects loops, wrong channels and stale retry windows', async t => {
  let calls = 0;
  const f = fakeBridge({ onMessage: async () => ({ id: String(80000000000000000n + BigInt(++calls)) }) });
  t.after(() => f.bridge.close());
  for (let i = 0; i < 8; i++) f.receive(minecraft(i.toString(16).padStart(32, '0')));
  await settle(); assert.equal(calls, 8); assert.equal(f.sent.length, 8);
  f.receive({ ...minecraft(), source: 'discord' }); f.receive(minecraft('a'.repeat(32), -120000));
  f.receive(minecraft('b'.repeat(32), 31001)); await settle(); assert.equal(calls, 8);
  const packet = globalDiscordPacket({ ...message(), content: '§ Unicode\n😀' }, guild, channel);
  assert.equal(f.bridge.send(packet), true); assert.equal(f.bridge.send(packet), false);
  assert.equal(f.bridge.send({ ...packet, id: '99999999999999999', message_id: '99999999999999999', channelId: author }), false);
  f.bridge.ready = false; assert.equal(f.bridge.send(packet), false);
});
