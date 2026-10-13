import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { configuration, limits, RateLimit } from '../src/config.mjs';
import { DeliveryReceipts, GLOBAL_ROOM, discordBody, discordMedia, discordMediaKey, discordMessageLinks, discordOperation,
  minecraftCard, validBridgeOperation, safeMediaUrl, REACTION_EMOJI, globalDiscordPacket, globalEmbedPacket, globalMinecraftContent } from '../src/global-chat.mjs';
import { RelayBridge, discordPacket } from '../src/relay.mjs';

const guild = '11111111111111111', channel = '22222222222222222', author = '33333333333333333';
const discordId = '44444444444444444';
const mediaId = url => `media-${createHash('sha256').update(discordMediaKey(url)).digest('hex').slice(0, 32)}`;
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
  socket.emit('message', Buffer.from(JSON.stringify({ type: 'bridge_ready', protocol: 3, room: GLOBAL_ROOM, globalChat: true })), false);
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
    { message_id: original.reference.messageId, from: 'Unavailable message', text: 'The original message is no longer available.' });
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

test('late human link previews send a full canonical replacement without new replies or mentions', t => {
  const previous = { ...message(), content: 'https://example.com/page', embeds: [] };
  const next = { ...previous, embeds: [{ title: 'Preview title', description: 'Preview description', url: previous.content }],
    attachments: [{ name: 'Never repeat attachment', url: 'https://example.com/file' }],
    reference: { messageId: discordId, channelId: channel }, mentions: { users: new Map([[discordId, { id: discordId, username: 'Mentioned' }]]) } };
  const packet = globalEmbedPacket(previous, next, guild, channel);
  assert.equal(packet.id, previous.id); assert.equal(packet.message_id, previous.id); assert.equal(packet.part, 'embeds');
  assert.equal(packet.replacement, true);
  assert.equal(packet.text, 'https://example.com/page\nPreview title\nPreview description\nNever repeat attachment: https://example.com/file');
  assert.deepEqual(packet.mentions, []); assert.equal(packet.reply, undefined);
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
  assert.equal(content.content, undefined);
  assert.equal(content.embeds[0].author.name, 'Steve');
  assert.equal(content.embeds[0].description, 'hello');
  assert.equal(content.embeds[0].footer.text, 'Minecraft · Sky-Myce Global');
  assert.deepEqual(content.allowedMentions, { parse: [], repliedUser: false });
  assert.equal(content.enforceNonce, true); assert.equal(content.nonce.length, 24);
  assert.equal(globalMinecraftContent(packet).nonce, content.nonce);
  assert.notEqual(globalMinecraftContent(minecraft('b'.repeat(32))).nonce, content.nonce);
  assert.ok(!JSON.stringify(content).includes(packet.uuid) && !JSON.stringify(content).includes(author));
  const expanded = globalMinecraftContent({ ...packet, text: '*'.repeat(1024) });
  assert.equal(expanded.content, undefined);
  assert.ok(expanded.embeds[0].description.length <= 4096);
  for (const extra of [{ source: 'discord' }, { scope: 'friends' }, { room: 'testing' }, { from: '[MC] Other' },
    { message_id: 'b'.repeat(32) }, { text: 'x'.repeat(1025) }]) assert.equal(globalMinecraftContent({ ...packet, ...extra }), null);
});

test('only backend-resolved individual mentions notify; mass words and raw Discord tags stay inert', () => {
  const packet = { ...minecraft(), text: `@everyone @here @Linked <@${author}> <@&${author}>`, mentions: [discordId, discordId] };
  const card = globalMinecraftContent(packet);
  assert.equal(card.content, `<@${discordId}>`);
  assert.deepEqual(card.allowedMentions, { parse: [], users: [discordId], repliedUser: false });
  assert.ok(card.embeds[0].description.includes('@everyone'));
  assert.equal(globalMinecraftContent({ ...packet, mentions: [] }).content, undefined);
  assert.deepEqual(globalMinecraftContent({ ...packet, mentions: [] }).allowedMentions, { parse: [], repliedUser: false });
  assert.equal(minecraftCard({ ...packet, scope: 'room', room: 'testing' }, 'room', 'testing').content, undefined);
  for (const mentions of ['everyone', ['everyone'], [null], Array(9).fill(discordId)])
    assert.equal(globalMinecraftContent({ ...packet, mentions }), null);
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

test('global bridge requires advertised capability and results from its own scope', t => {
  const results = [];
  const f = fakeBridge({ onResult: (...args) => results.push(args) });
  t.after(() => f.bridge.close());
  f.bridge.ready = false;
  f.receive({ type: 'bridge_ready', protocol: 3, room: GLOBAL_ROOM });
  assert.equal(f.bridge.ready, false);
  assert.equal(f.bridge.send(globalDiscordPacket(message(), guild, channel)), false);
  f.receive({ type: 'bridge_ready', protocol: 3, room: GLOBAL_ROOM, globalChat: true });
  assert.equal(f.bridge.ready, true);
  f.receive({ type: 'irc_result', id: message().id });
  f.receive({ type: 'irc_result', scope: 'friends', id: message().id });
  assert.equal(results.length, 0);
  f.receive({ type: 'irc_result', scope: 'global', id: message().id });
  assert.deepEqual(results, [[message().id, null]]);
});

test('queued Discord delivery respects the backend deadline and shutdown before posting', async t => {
  let now = 1000, calls = 0;
  const f = fakeBridge({ now: () => now, onMessage: async () => { calls++; return { id: discordId }; } });
  t.after(() => f.bridge.close());
  f.receive({ ...minecraft(), expiresAt: 1500 });
  now = 1500;
  await settle();
  assert.equal(calls, 0); assert.equal(f.sent.length, 0); assert.equal(f.bridge.inFlight.size, 0);
  for (const expiry of [null, '2000', 1000, 121001]) f.receive({ ...minecraft(), expiresAt: expiry });
  f.receive(minecraft('b'.repeat(32), now - 120000));
  await settle(); assert.equal(calls, 0);
  f.receive({ ...minecraft('c'.repeat(32)), expiresAt: 2000 });
  await settle(); assert.equal(calls, 1); assert.equal(f.sent.length, 1);
  f.receive({ ...minecraft('d'.repeat(32)), expiresAt: 2000 });
  f.bridge.close();
  await settle(); assert.equal(calls, 1); assert.equal(f.sent.length, 1);
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


test('signed attachment identity produces one structured GIF while preserving unrelated query semantics', () => {
  const unsigned = `https://cdn.discordapp.com/attachments/${channel}/100000000000000001/dance.gif`;
  const signed = `${unsigned}?ex=7fffffff&is=1&hm=${'a'.repeat(64)}`;
  const input = { ...message(), content: unsigned,
    attachments: new Map([['a', { id: '100000000000000001', name: 'dance.gif', url: signed, contentType: 'image/gif', size: 1234, width: 64, height: 32 }]]),
    embeds: [{ url: unsigned, image: { url: signed }, thumbnail: { url: signed.replace('cdn.discordapp.com', 'media.discordapp.net') } }] };
  assert.equal(discordBody(input), signed);
  assert.deepEqual(discordMessageLinks(input), [unsigned]);
  assert.deepEqual(discordMedia(input), [{ id: '100000000000000001', type: 'gif', url: signed, name: 'dance.gif', mime: 'image/gif', bytes: 1234, width: 64, height: 32 }]);
  assert.equal(discordMediaKey(signed), discordMediaKey(signed.replace('cdn.discordapp.com', 'media.discordapp.net')));
  assert.notEqual(discordMediaKey('https://example.com/a.gif?ex=1'), discordMediaKey('https://example.com/a.gif?ex=2'));
  assert.equal(safeMediaUrl(`${unsigned}?ex=1&is=1&hm=invalid`), '');
  assert.ok(discordBody({ ...message(), content: `${unsigned}?ex=1&is=1&hm=invalid` }).includes('?ex=1'), 'expired media retains its ordinary link fallback');
  const previous = { ...input, embeds: [] };
  assert.equal(globalEmbedPacket(previous, input, guild, channel), null);
  assert.equal(globalEmbedPacket(previous, { ...input, embeds: [{ title: 'New caption', image: { url: signed } }] }, guild, channel).text, `${signed}\nNew caption`);
});

test('gifv previews select the supplied animated GIF once from the original message and delayed embeds', () => {
  const page = 'https://tenor.com/view/sample-animation-gif-123456789';
  const gif = 'https://media1.tenor.com/m/sampleAAAAC/sample.gif';
  const video = 'https://media.tenor.com/sampleAAAPo/sample.mp4';
  const embed = { type: 'gifv', url: page, video: { url: video, width: 400, height: 300 },
    thumbnail: { url: gif, width: 400, height: 300 } };
  const expected = [{ id: mediaId(gif), type: 'gif', url: gif, sourceUrl: page, width: 400, height: 300 }];
  const previous = { ...message(), content: page, embeds: [] };
  for (const preview of [embed, { data: embed }]) {
    const input = { ...previous, embeds: [preview] };
    const packet = globalDiscordPacket(input, guild, channel);
    assert.equal(packet.text, `${page}\n${gif}`, 'original content contains only the view page; embed supplies the GIF');
    assert.deepEqual(packet.media, expected);
    const continuation = globalEmbedPacket(previous, input, guild, channel);
    assert.equal(continuation.text, `${page}\n${gif}`);
    assert.deepEqual(continuation.media, expected);
    assert.deepEqual(discordMedia({ ...input, content: gif }), expected, 'a direct GIF link and its embed remain one item');
  }
  assert.deepEqual(discordMedia({ embeds: [{ ...embed, type: 'video' }] }),
    [{ id: mediaId(video), type: 'video', url: video, thumbnailUrl: gif, width: 400, height: 300 }],
    'ordinary videos keep their video URL and poster even when the poster is a GIF');
  for (const invalid of ['http://media1.tenor.com/sample.gif', 'https://localhost/sample.gif',
    'https://user:password@media1.tenor.com/sample.gif'])
    assert.deepEqual(discordMedia({ embeds: [{ ...embed, thumbnail: { url: invalid } }] }),
      [{ id: mediaId(video), type: 'video', url: video, sourceUrl: page, width: 400, height: 300 }], 'unsafe GIF thumbnails cannot replace a video');
  const poster = 'https://media1.tenor.com/sample.png';
  assert.deepEqual(discordMedia({ embeds: [{ ...embed, thumbnail: { url: poster } }] }),
    [{ id: mediaId(video), type: 'video', url: video, thumbnailUrl: poster, sourceUrl: page, width: 400, height: 300 }],
    'gifv without an actual GIF retains browser playback and its poster');
});

test('video previews carry one poster and preserve bounded attachment metadata', () => {
  const video = 'https://example.com/movie.mp4', poster = 'https://example.com/poster.png';
  assert.deepEqual(discordMedia({ content: video, embeds: [{ video: { url: video, width: 1280, height: 720 }, thumbnail: { url: poster } }] }),
    [{ id: mediaId(video), type: 'video', url: video, thumbnailUrl: poster, width: 1280, height: 720 }]);
  const withAttachment = discordMedia({ attachments: [{ id: author, url: video, name: 'a'.repeat(200), contentType: 'video/mp4' }],
    embeds: [{ video: { url: video }, thumbnail: { url: poster } }] });
  assert.equal(withAttachment.length, 1); assert.equal(withAttachment[0].name.length, 80);
  assert.equal(withAttachment[0].thumbnailUrl, poster);
  assert.deepEqual(discordMedia({ embeds: [{ thumbnail: { url: poster } }] }), [{ id: mediaId(poster), type: 'image', url: poster }], 'a separate image thumbnail is preserved');
});

test('Unicode joined sequences and custom emoji IDs survive creation, edits and reply snapshots', () => {
  const text = '👩🏽‍💻 👨‍👩‍👧‍👦 🇸🇪 ❤️ <:blob:123456789012345678> <a:dance:234567890123456789>';
  const original = { ...message(), content: text };
  assert.equal(globalDiscordPacket(original, guild, channel).text, text);
  const edited = { ...original, content: `${text} changed` };
  assert.equal(discordOperation('MESSAGE_UPDATE', edited, original, 'b'.repeat(32), guild, channel, discordId).text, edited.content);
  const answer = { ...message(), id: '66666666666666666', content: 'answer', reference: { messageId: original.id, channelId: channel },
    referencedMessage: original };
  const reply = globalDiscordPacket(answer, guild, channel);
  assert.equal(reply.reply.message_id, original.id); assert.equal(reply.reply.text, text);
  for (const cluster of ['👩🏽‍💻', '👨‍👩‍👧‍👦', '🇸🇪', '❤️', '<a:dance:234567890123456789>']) {
    const suffix = ' … [truncated]', prefix = 'x'.repeat(4096 - suffix.length - 1);
    const truncated = discordBody({ ...message(), content: `${prefix}${cluster}${'y'.repeat(40)}` });
    assert.equal(truncated, prefix + suffix, 'truncation omits the complete boundary cluster/token rather than a broken fragment');
  }
});

test('media identity remains stable when late embeds reorder resources or renew Discord signatures', () => {
  const first = 'https://example.com/one.gif', second = 'https://example.com/two.png';
  const before = discordMedia({ content: `${first} ${second}` });
  const after = discordMedia({ embeds: [{ image: { url: second } }, { image: { url: first } }] });
  assert.deepEqual(new Map(before.map(item => [item.url, item.id])), new Map(after.map(item => [item.url, item.id])));
  const base = `https://cdn.discordapp.com/attachments/${channel}/${author}/one.gif`;
  const original = discordMedia({ content: `${base}?ex=7fffffff&is=1&hm=a` });
  const refreshed = discordMedia({ content: `${base.replace('cdn.discordapp.com', 'media.discordapp.net')}?ex=7fffffff&is=2&hm=b` });
  assert.equal(original[0].id, refreshed[0].id); assert.notEqual(original[0].url, refreshed[0].url);
  assert.equal(discordMedia({ content: base, attachments: [{ id: author, url: refreshed[0].url, contentType: 'image/gif' }],
    embeds: [{ image: { url: original[0].url } }] }).length, 1);
});

test('rich cards keep trusted sender identity, compact replies and media in both scopes with no fabricated presence', () => {
  const packet = { ...minecraft(), sender: { name: 'Steve', uuid: 'a'.repeat(32), cosmeticName: '§aChampion\u202e', avatarUrl: 'https://untrusted.example/avatar' },
    reply: { from: 'Earlier', text: 'A bounded reply', discordMessageId: discordId },
    media: [{ type: 'gif', url: 'https://example.com/dance.gif' }, { type: 'video', url: 'https://example.com/movie.mp4', thumbnailUrl: 'https://example.com/poster.png' }] };
  const card = globalMinecraftContent(packet), embed = card.embeds[0];
  assert.equal(embed.author.name, 'aChampion (Steve)');
  assert.equal(embed.author.iconURL, `https://mc-heads.net/avatar/${'a'.repeat(32)}/64`);
  assert.ok(!JSON.stringify(card).includes('IN-GAME') && !JSON.stringify(card).includes('untrusted.example'));
  assert.equal(embed.description, 'hello');
  assert.deepEqual(embed.fields[0], { name: '↳ Reply to Earlier', value: 'A bounded reply' });
  assert.deepEqual(embed.image, { url: 'https://example.com/dance.gif' });
  assert.deepEqual(embed.thumbnail, { url: 'https://example.com/poster.png' });
  assert.deepEqual(card.reply, { messageReference: discordId, failIfNotExists: false });
  assert.deepEqual(card.allowedMentions, { parse: [], repliedUser: false });
  const room = minecraftCard({ ...packet, scope: 'room', room: 'testing' }, 'room', 'testing');
  assert.equal(room.embeds[0].footer.text, 'Minecraft · Sky-Myce IRC');
  assert.equal(minecraftCard({ ...packet, scope: 'global', room: 'friends' }, 'room', 'friends'), null);
  assert.equal(minecraftCard({ ...packet, scope: 'party', room: 'party' }, 'party', 'party'), null, 'party never creates Discord cards');
  assert.equal(globalMinecraftContent({ ...packet, sender: { ...packet.sender, name: 'Other' } }).embeds[0].author.name, 'Steve');
  for (const url of ['http://example.com/a.png', 'https://localhost/a.png', 'https://127.0.0.1/a.png', 'https://[::1]/a.png', 'https://user:pass@example.com/a.png']) assert.equal(safeMediaUrl(url), '');
  assert.equal(discordMedia({ ...message(), content: Array.from({ length: 10 }, (_, i) => `https://example.com/${i}.png`).join(' ') }).length, 4);
});

test('native lifecycle envelopes validate actors, source channels, bounded emoji and exact operation deadlines', () => {
  const previous = { ...message(), text: 'hello', media: [] }, id = 'b'.repeat(32);
  const edit = discordOperation('MESSAGE_UPDATE', { ...previous, content: 'Edited', editedTimestamp: 1234 }, previous, id, guild, channel, discordId);
  assert.equal(edit.operation, 'edit'); assert.equal(edit.text, 'Edited'); assert.equal(edit.editedAt, 1234);
  const deletion = discordOperation('MESSAGE_DELETE', { id: previous.id, guildId: guild, channelId: channel }, previous, id, guild, channel, discordId);
  assert.equal(deletion.discordId, author);
  assert.equal(discordOperation('MESSAGE_DELETE', { id: previous.id, guildId: guild, channelId: channel }, undefined, id, guild, channel, discordId), null);
  const reaction = { guildId: guild, channelId: channel, messageId: previous.id, userId: author, emoji: { name: '🔥' } };
  assert.equal(discordOperation('MESSAGE_REACTION_ADD', reaction, previous, id, guild, channel, discordId).active, true);
  assert.equal(discordOperation('MESSAGE_REACTION_REMOVE', reaction, previous, id, guild, channel, discordId).active, false);
  for (const change of [{ userId: discordId }, { emoji: { id: author, name: '🔥' } }, { emoji: { name: '☠' } }, { channelId: author }])
    assert.equal(discordOperation('MESSAGE_REACTION_ADD', { ...reaction, ...change }, previous, id, guild, channel, discordId), null);
  assert.deepEqual(REACTION_EMOJI, ['👍', '❤️', '😂', '🎉', '👀', '🔥']);
  const outgoing = { type: 'irc_operation', id, scope: 'global', room: GLOBAL_ROOM, messageId: 'c'.repeat(32), discordMessageId: discordId,
    operation: 'reaction', emoji: '👍', active: true, revision: 1, timestamp: 1000, expiresAt: 2000 };
  assert.equal(validBridgeOperation(outgoing, 'global', GLOBAL_ROOM, 1000), true);
  for (const change of [{ scope: 'room' }, { messageId: author }, { revision: 0 }, { expiresAt: 1000 }, { expiresAt: 121001 }, { active: undefined }, { emoji: '☠' }])
    assert.equal(validBridgeOperation({ ...outgoing, ...change }, 'global', GLOBAL_ROOM, 1000), false);
});

test('rich routing and operation receipts restore without persistent text or media and never cross scopes', async t => {
  const path = await temporary(t); let now = 1000;
  const receipts = await new DeliveryReceipts(path, () => now).load();
  const metadata = { room: GLOBAL_ROOM, channelId: channel, authorId: discordId, name: 'User Bot', revision: 0 };
  await receipts.record('a'.repeat(32), discordId, metadata);
  await receipts.rememberSource(author, { ...metadata, authorId: author, name: 'Human' });
  receipts.setCard('a'.repeat(32), { text: 'PRIVATE BODY', media: [{ url: 'PRIVATE SIGNATURE' }] });
  await receipts.recordOperation('b'.repeat(32), 'a'.repeat(32), 1, discordId);
  const text = await readFile(path, 'utf8');
  assert.ok(!text.includes('PRIVATE BODY') && !text.includes('PRIVATE SIGNATURE'));
  const restored = await new DeliveryReceipts(path, () => now).load();
  assert.equal(restored.get('a'.repeat(32)), discordId);
  assert.equal(restored.source(discordId, GLOBAL_ROOM).mapped, true);
  assert.equal(restored.source(author, GLOBAL_ROOM).authorId, author);
  assert.equal(restored.source(author, 'friends'), undefined);
  assert.equal(restored.operation('b'.repeat(32)).revision, 1);
  assert.equal(restored.metadata('a'.repeat(32)).revision, 1);
  assert.equal(restored.getCard('a'.repeat(32)), undefined);
  await assert.rejects(restored.record('c'.repeat(32), discordId, { ...metadata, room: 'party' }));
  now = 901000; assert.equal(restored.source(author, GLOBAL_ROOM), undefined); assert.equal(restored.operation('b'.repeat(32)), undefined);
});
