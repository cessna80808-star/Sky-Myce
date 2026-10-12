import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CommunityFeed, communityFeedHeaders, communityFeedReceipts, rngEmbed } from '../src/community-feed.mjs';
import { DeliveryReceipts, globalDiscordPacket, resolveDiscordReply } from '../src/global-chat.mjs';
import { configuration } from '../src/config.mjs';

const channelId = '22222222222222222', guild = '11111111111111111', ownBotId = '33333333333333333';
const event = () => ({ id: 'a'.repeat(32), item: 'NECRON_HANDLE', activity: 'dungeon', occurredAt: Date.now(), player: 'Anonymous', unverified: true });
const delivery = () => ({ id: 'a'.repeat(32), claim: 'c'.repeat(43), channelId, operation: 'publish', event: event() });
test('dedicated feed uses exact-body HMAC domain and cannot share chat channels', () => {
  const body = JSON.stringify({ action: 'claim', channelId }), stamp = String(Date.now()), nonce = 'f'.repeat(32), secret = 's'.repeat(32);
  const headers = communityFeedHeaders(secret, body, stamp, nonce);
  assert.equal(headers['X-SkyMyce-Signature'], createHmac('sha256', secret).update(`sky-myce-community-feed\nPOST\n/discord/community-feed\n${stamp}\n${nonce}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex'));
  const env = { DISCORD_GUILD_ID: guild, USER_BOT_APPLICATION_ID: ownBotId, USER_BOT_TOKEN: 'test-token', IRC_ROOMS: '', DISCORD_RNG_CHANNEL_ID: channelId,
    SKY_MYCE_RELAY_URL: 'https://relay.example', SKY_MYCE_RELAY_AUTH_SECRET: secret };
  assert.equal(configuration(env).rngChannel, channelId);
  assert.throws(() => configuration({ ...env, GLOBAL_CHAT_CHANNEL_ID: channelId }), /shared RNG/);
  assert.throws(() => configuration({ ...env, SKY_MYCE_RELAY_AUTH_SECRET: undefined }), /AUTH_SECRET/);
});
test('RNG embeds are structured, unverified, anonymous and reject malformed/stale fields', () => {
  const card = rngEmbed(event());
  assert.match(card.embeds[0].title, /Necron's Handle/); assert.match(card.embeds[0].description, /Anonymous/);
  assert.match(card.embeds[0].fields[1].value, /unverified/); assert.deepEqual(card.allowedMentions.parse, []); assert.equal(card.enforceNonce, true);
  assert.equal(rngEmbed({ ...event(), player: '<@22222222222222222>' }), null);
  assert.equal(rngEmbed({ ...event(), occurredAt: Date.now() - 3600000 }), null);
  assert.equal(rngEmbed({ ...event(), unverified: false }), null);
  assert.match(rngEmbed({ ...event(), player: 'Example_Player' }).embeds[0].description, /Example\\_Player/);
});
test('RNG presentation preserves actual optional rarity, name and floor without inventing old metadata', () => {
  const enriched = rngEmbed({ ...event(), rarity: 'MYTHIC', displayName: "Ancient Necron's Handle", floor: 'M7' });
  assert.equal(enriched.embeds[0].title, "Rare drop · Ancient Necron's Handle");
  assert.deepEqual(enriched.embeds[0].fields.slice(1, 3), [{ name: 'Observed rarity', value: 'mythic' }, { name: 'Observed floor', value: 'M7' }]);
  assert.match(enriched.embeds[0].fields.find(field => field.name === 'Item variant').value, /NECRON/);
  const historical = rngEmbed({ ...event(), rarity: null, displayName: null, floor: null });
  assert.equal(historical.embeds[0].title, "Rare drop · Necron's Handle");
  assert.equal(historical.embeds[0].fields.some(field => field.name.startsWith('Observed')), false);
  assert.equal(rngEmbed({ ...event(), item: 'IMPLOSION_SCROLL' }).embeds[0].title, 'Rare drop · Implosion');
  assert.equal(rngEmbed({ ...event(), item: 'FUTURE_SUPPORTED_ITEM' }).embeds[0].title, 'Rare drop · Future Supported Item');
  for (const rarity of ['COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC', 'DIVINE', 'ULTIMATE', 'SPECIAL', 'VERY_SPECIAL', 'ADMIN'])
    assert.ok(rngEmbed({ ...event(), rarity }));
  for (const floor of ['E', 'F1', 'F7', 'M1', 'M7']) assert.ok(rngEmbed({ ...event(), floor }));
  for (const metadata of [{ rarity: 'UNKNOWN' }, { rarity: 1 }, { displayName: '' }, { displayName: ' padded' }, { displayName: 'x'.repeat(121) },
    { displayName: 'line\nbreak' }, { displayName: '§cColored' }, { displayName: 'spoof\u202ename' }, { displayName: {} }, { floor: 'M8' },
    { floor: 'F0' }, { floor: ['F7'] }, { activity: 'slayer', floor: 'F7' }]) assert.equal(rngEmbed({ ...event(), ...metadata }), null);
});
test('observed item labels remain inert and named or anonymous attribution stays independent', () => {
  const malicious = '[Click](https://example.test) <@22222222222222222> **@everyone**';
  const anonymous = rngEmbed({ ...event(), displayName: malicious, rarity: 'LEGENDARY', floor: 'F7' });
  assert.match(anonymous.embeds[0].description, /Anonymous/);
  assert.match(anonymous.embeds[0].title, /\\\[Click\\\]\\\(/);
  assert.ok(!anonymous.embeds[0].title.includes('<@22222222222222222>'));
  assert.ok(!anonymous.embeds[0].title.includes('@everyone'));
  assert.ok(!anonymous.embeds[0].title.includes('https://'));
  assert.deepEqual(anonymous.allowedMentions, { parse: [], repliedUser: false });
  const named = rngEmbed({ ...event(), player: 'Example_Player', displayName: malicious });
  assert.match(named.embeds[0].description, /Example\\_Player/);
  assert.equal(named.embeds[0].title, anonymous.embeds[0].title);
});
test('claimed observed metadata reaches the post while later consent removal still retracts it', async () => {
  let posted, ack, removed;
  const enriched = { ...delivery(), event: { ...event(), displayName: "Necron's Handle", rarity: 'LEGENDARY', floor: 'M7' } };
  const feed = new CommunityFeed({ url: 'https://relay.example', secret: 's'.repeat(32), channelId,
    receipts: { get() {}, async record(id, messageId) { assert.equal(id, enriched.id); assert.equal(messageId, '44444444444444444'); } },
    channel: { async send(card) { posted = card; return { id: '44444444444444444' }; }, messages: { async delete(id) { removed = id; } } },
    fetcher: async (_url, options) => { const body = JSON.parse(options.body);
      if (body.action === 'claim') return Response.json({ ok: true, deliveries: [enriched] });
      ack = body; return Response.json({ ok: true, deleteRequired: true }); } });
  await feed.tick(); feed.stop();
  assert.match(posted.embeds[0].description, /Anonymous/);
  assert.deepEqual(posted.embeds[0].fields.slice(1, 3), [{ name: 'Observed rarity', value: 'legendary' }, { name: 'Observed floor', value: 'M7' }]);
  assert.equal(ack.id, enriched.id); assert.equal(ack.operation, 'publish'); assert.equal(removed, '44444444444444444');
});
test('durable delivery receipt prevents duplicates across failed ACK, restart and delayed retry', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'sky-myce-rng-'));
  t.after(async () => { assert.ok(directory.startsWith(join(tmpdir(), 'sky-myce-rng-'))); await rm(directory, { recursive: true, force: true }); });
  const path = join(directory, 'receipts.json'); let sends = 0, acks = 0;
  const fetcher = async (url, options) => {
    assert.equal(url.pathname, '/discord/community-feed'); assert.equal(options.redirect, 'error');
    const body = JSON.parse(options.body);
    if (body.action === 'claim') return Response.json({ ok: true, deliveries: [delivery()] });
    acks++; if (acks === 1) return new Response('', { status: 503 });
    assert.equal(body.messageId, '44444444444444444'); return Response.json({ ok: true });
  };
  const options = { url: 'https://relay.example', secret: 's'.repeat(32), channelId, fetcher, channel: { async send() { sends++; return { id: '44444444444444444' }; } } };
  const first = new CommunityFeed({ ...options, receipts: await communityFeedReceipts(path) });
  await first.tick(); first.stop(); assert.equal(sends, 1);
  const restored = await new DeliveryReceipts(path, () => Date.now() + 360000, 900000).load();
  assert.equal(restored.get('a'.repeat(32)), '44444444444444444');
  const second = new CommunityFeed({ ...options, receipts: restored }); await second.tick(); second.stop();
  assert.equal(sends, 1); assert.equal(acks, 2);
});
test('failed durable receipt retracts the post; later consent removal deletes only the acknowledged message', async () => {
  let removed = [], acked = [], publications = 0;
  const feed = new CommunityFeed({ url: 'https://relay.example', secret: 's'.repeat(32), channelId,
    receipts: { get() {}, async record() { throw Error('disk unavailable'); } },
    channel: { async send() { publications++; return { id: '44444444444444444' }; }, messages: { async delete(id) { removed.push(id); } } },
    fetcher: async (_url, options) => { const body = JSON.parse(options.body); if (body.action === 'ack') acked.push(body); return Response.json({ ok: true, deliveries: [delivery()] }); } });
  await feed.tick(); feed.stop(); assert.equal(publications, 1); assert.deepEqual(removed, ['44444444444444444']); assert.equal(acked.length, 0);
  const deletion = new CommunityFeed({ url: 'https://relay.example', secret: 's'.repeat(32), channelId, receipts: {}, channel: feed.channel,
    fetcher: async (_url, options) => { const body = JSON.parse(options.body); if (body.action === 'ack') { acked.push(body); return Response.json({ ok: true }); }
      return Response.json({ ok: true, deliveries: [{ ...delivery(), operation: 'delete', event: undefined, messageId: '44444444444444444' }] }); } });
  await deletion.tick(); deletion.stop(); assert.equal(acked[0].operation, 'delete'); assert.equal(publications, 1);
});
test('reply lookup fetches uncached parents after restart and extracts original Minecraft embed author/body', async () => {
  const parentId = '55555555555555555'; let fetched = 0;
  const message = { id: '66666666666666666', guildId: guild, channelId, content: 'Answer', author: { id: '77777777777777777', username: 'Human' },
    client: { user: { id: ownBotId } }, reference: { messageId: parentId, channelId },
    async fetchReference() { fetched++; return { id: parentId, guildId: guild, channelId, author: { id: ownBotId, username: 'Sky-Myce User Bot', bot: true },
      content: '', embeds: [{ author: { name: 'OriginalPlayer' }, description: 'original \\_message', fields: [{ name: 'Reply to someone', value: 'do not recursively quote' }], footer: { text: 'do not quote footer' } }] }; } };
  const resolved = await resolveDiscordReply(message, guild, channelId);
  const packet = globalDiscordPacket(resolved, guild, channelId);
  assert.equal(fetched, 1); assert.deepEqual(packet.reply, { message_id: parentId, from: 'OriginalPlayer', text: 'original _message' });
  assert.equal(message.referencedMessage, undefined, 'Shared Discord cache object remains unchanged');
  const missing = await resolveDiscordReply({ ...message, async fetchReference() { throw Error('deleted'); } }, guild, channelId);
  assert.match(globalDiscordPacket(missing, guild, channelId).reply.from, /Unavailable/);
  const crossChannel = await resolveDiscordReply({ ...message, reference: { messageId: parentId, channelId: '88888888888888888' } }, guild, channelId);
  assert.equal(globalDiscordPacket(crossChannel, guild, channelId).reply, undefined);
});

test('feed rejects oversized or malformed streaming responses before a Discord publication', async () => {
  let canceled = false, sends = 0;
  const oversized = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(16385)); }, cancel() { canceled = true; } });
  const options = { url: 'https://relay.example', secret: 's'.repeat(32), channelId, receipts: {},
    channel: { async send() { sends++; } }, fetcher: async () => new Response(oversized) };
  const feed = new CommunityFeed(options); await feed.tick(); feed.stop();
  assert.equal(canceled, true); assert.equal(sends, 0); assert.equal(feed.failures, 1);
  const malformed = new CommunityFeed({ ...options, fetcher: async () => new Response(new Uint8Array([0xff, 0xfe])) });
  await malformed.tick(); malformed.stop(); assert.equal(sends, 0); assert.equal(malformed.failures, 1);
});
