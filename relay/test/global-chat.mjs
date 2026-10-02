import assert from 'node:assert/strict';
import { createHash, createHmac, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

export async function checkGlobalChat(script, proof) {
  // Expose alarm state only in this in-memory fixture bundle, never in production code.
  const anchor = 'async globalChatMinecraft(sender, input) {';
  assert.equal(script.split(anchor).length, 2, 'Exactly one production coordinator method is instrumented');
  script = script.replace(anchor, `async testOnlyGlobalAlarm(remove = false) {
    if (remove) await this.ctx.storage.deleteAlarm();
    return await this.ctx.storage.getAlarm();
  }\n  ${anchor}`);
  const signing = generateKeyPairSync('ed25519'), secret = 'global-chat-test-only-bridge-secret-32bytes';
  const channelId = '100000000000000001';
  const publicKey = key => key.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const options = convertV4MiniflareOptions({ name: 'global-chat-check', modules: true, script, compatibilityDate: '2026-09-27',
    bindings: { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'staging', DISCORD_BRIDGE_SECRET: secret,
      GLOBAL_CHAT_CHANNEL_ID: channelId, GLOBAL_CHAT_BURST: '3', GLOBAL_CHAT_REFILL_SECONDS: '60', GLOBAL_CHAT_MIN_INTERVAL_MS: '0',
      GLOBAL_CHAT_GLOBAL_BURST: '200', GLOBAL_CHAT_GLOBAL_REFILL_MS: '10',
      DRM_KEY_ID: 'test', DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signing) }) },
    durableObjects: { RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true }, ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true } },
    outboundService: () => { throw new Error('Global chat fixtures never contact Discord or Minecraft services'); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112032', simple: { limit: 1000, period: 60 } };
  const mf = new Miniflare(options), sockets = [];
  let sequence = 100, discordSequence = 900000000000000000n, ip = 1;
  const id = () => (sequence++).toString(16).padStart(32, '0');
  const people = new Map(['Alice', 'Bob', 'Carol'].map((name, index) => [name, { name, uuid: 'abc'[index].repeat(32),
    discordId: String(200000000000000000n + BigInt(index)), accountId: randomUUID(), installationId: randomUUID(),
    device: String(index + 1).repeat(64), key: generateKeyPairSync('ed25519') }]));
  function inbox(ws) {
    const queued = [], waiting = [];
    ws.addEventListener('message', event => {
      const value = event.data === 'pong' ? 'pong' : JSON.parse(event.data);
      if (waiting.length) waiting.shift()(value); else queued.push(value);
    });
    return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Global chat response timed out')), 5000);
      waiting.push(value => { clearTimeout(timer); resolve(value); });
    });
  }
  const closeEvent = ws => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Expected global chat socket close')), 5000);
    ws.addEventListener('close', event => { clearTimeout(timer); resolve(event); }, { once: true });
  });
  function lease(person, changes = {}) {
    const now = Date.now(), claims = { product: 'sky-myce', protocol: 3, accountId: person.accountId, discordId: person.discordId,
      uuid: person.uuid, installationId: person.installationId, deviceHash: person.device,
      keyFingerprint: createHash('sha256').update(person.key.publicKey.export({ format: 'der', type: 'spki' })).digest('hex'),
      version: '2.4.2', loaderVersion: '1.0.0', minecraft: '26.1.2', iat: now, exp: now + 300000, offlineUntil: now + 1200000,
      generation: 0, jti: randomUUID(), challengeId: randomUUID(), clientNonce: randomUUID(), features: ['relay', 'irc'], ...changes };
    const body = `${b64({ alg: 'EdDSA', typ: 'JWT', kid: 'test' })}.${b64(claims)}`;
    return `${body}.${sign(null, Buffer.from(body), signing.privateKey).toString('base64url')}`;
  }
  async function connect(name, room = 'friends', changes = {}) {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': `global-mc-${ip++}` } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws);
    const challenge = await next(), person = people.get(name), token = lease(person, changes.claims);
    const closed = changes.denied ? closeEvent(ws) : undefined;
    ws.send(JSON.stringify({ ...proof(name, challenge.serverId), lease: token,
      installationSignature: sign(null, Buffer.from(`Sky-Myce relay v3\n${challenge.serverId}\n${token}`), person.key.privateKey).toString('base64'), ...changes.packet }));
    if (closed) { assert.equal((await closed).code, 4005); return; }
    const ready = await next(); assert.equal(ready.type, 'ready'); assert.equal(ready.globalChat, true);
    return { ws, next };
  }
  const bridgeHeaders = () => {
    const time = String(Date.now()), nonce = randomUUID().replaceAll('-', '');
    return { Upgrade: 'websocket', 'CF-Connecting-IP': `global-dc-${ip++}`, 'X-SkyMyce-Timestamp': time, 'X-SkyMyce-Nonce': nonce,
      'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-bridge\nglobal-chat\n${time}\n${nonce}`).digest('hex') };
  };
  async function bridge(headers = bridgeHeaders()) {
    const response = await mf.dispatchFetch('http://localhost/discord/bridge?room=global-chat', { headers }); assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws);
    const ready = await next(); assert.equal(ready.type, 'bridge_ready'); assert.equal(ready.globalChat, true);
    return { ws, next };
  }
  async function subscribe(client, enabled = true) {
    client.ws.send(JSON.stringify({ type: 'irc_subscribe', scope: 'global', id: id(), enabled }));
    const result = await client.next(); assert.equal(result.type, 'irc_result'); assert.equal(result.scope, 'global'); assert.equal(result.error, undefined);
  }
  async function ping(client) { client.ws.send('ping'); assert.equal(await client.next(), 'pong'); }
  async function ack(bot, message, discordId = String(discordSequence++)) {
    bot.ws.send(JSON.stringify({ type: 'discord_ack', scope: 'global', room: 'global-chat', channelId, id: message.id, message_id: discordId }));
    const result = await bot.next(); assert.equal(result.type, 'irc_result'); assert.equal(result.error, undefined); return discordId;
  }
  const clientPacket = (packet, text) => {
    assert.equal(packet.type, 'irc_message'); assert.equal(packet.scope, 'global'); assert.match(packet.id, /^[a-f0-9]{32}$/);
    assert.equal(packet.text, text); assert.equal(typeof packet.mention, 'boolean'); assert.equal(typeof packet.timestamp, 'number');
    for (const key of ['discordId', 'uuid', 'accountId', 'installationId', 'sender', 'mentions', 'message_id', 'expiresAt']) assert.equal(packet[key], undefined);
    if (packet.reply) assert.equal(packet.reply.message_id, undefined);
    return packet;
  };
  try {
    const alicePerson = people.get('Alice'), bobPerson = people.get('Bob');
    const init = await mf.dispatchFetch('http://localhost/auth/challenge', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ purpose: 'activate', clientNonce: randomUUID(), installationId: alicePerson.installationId, publicKey: publicKey(alicePerson.key),
        deviceHash: alicePerson.device, minecraftVersion: '26.1.2', loaderVersion: '1.0.0', modVersion: '2.4.2' }) });
    assert.equal(init.status, 200);
    const authority = await mf.unsafeGetDurableObjectStorage('global-chat-check', 'AccountStore', { name: 'global' });
    for (const person of people.values()) {
      const now = Date.now();
      await authority.exec('INSERT INTO sky_accounts(account_id,discord_id,license_status,device_hash,activated_at,created_at,last_seen) VALUES(?,?,?,?,?,?,?)',
        person.accountId, person.discordId, person.name === 'Carol' ? 'revoked' : 'active', person.device, now, now, now);
      await authority.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', person.uuid, person.accountId, person.name, now, now);
      await authority.exec('INSERT INTO account_devices VALUES(?,?,?,NULL)', person.device, person.accountId, now);
      await authority.exec('INSERT INTO installations(installation_id,account_id,device_hash,public_key,fingerprint,registered_at,revoked_at) VALUES(?,?,?,?,?,?,NULL)',
        person.installationId, person.accountId, person.device, publicKey(person.key), createHash('sha256').update(person.key.publicKey.export({ format: 'der', type: 'spki' })).digest('hex'), now);
    }
    const globalSql = await mf.unsafeGetDurableObjectStorage('global-chat-check', 'RelayRoom', { name: 'global-chat' });
    const coordinator = (await mf.getDurableObjectNamespace('RELAY_ROOMS')).getByName('global-chat');
    const clearQuota = () => globalSql.exec('DELETE FROM global_chat_limits');
    const sender = person => ({ accountId: person.accountId, discordId: person.discordId, uuid: person.uuid, name: person.name,
      installationId: person.installationId, generation: 0, leaseExpiresAt: Date.now() + 300000, buildId: '2.4.2', loaderVersion: '1.0.0' });
    await connect('Carol', 'friends', { denied: true });
    await connect('Alice', 'friends', { denied: true, claims: { exp: Date.now() - 1 } });
    await connect('Alice', 'friends', { denied: true, packet: { lease: undefined, installationSignature: undefined } });
    assert.equal((await mf.dispatchFetch('http://localhost/websocket?room=global-chat', { headers: { Upgrade: 'websocket' } })).status, 403);
    assert.equal((await mf.dispatchFetch('http://localhost/discord/bridge?room=global-chat', { headers: { Upgrade: 'websocket' } })).status, 401);
    const alice = await connect('Alice'), bob = await connect('Bob', 'testing'), otherAlice = await connect('Alice', 'testing');
    await subscribe(alice); await subscribe(bob); await subscribe(otherAlice);
    const headers = bridgeHeaders(); let bot = await bridge(headers);
    assert.equal((await mf.dispatchFetch('http://localhost/discord/bridge?room=global-chat', { headers })).status, 401);
    async function minecraft(client, text, recipients = [alice, bob, otherAlice], hasBridge = true) {
      const requestId = id(); client.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: requestId, text, from: 'Spoofed', discordId: '999999999999999999' }));
      const packets = [];
      for (const recipient of recipients) packets.push(clientPacket(await recipient.next(), text));
      const result = await client.next(); assert.equal(result.type, 'irc_result'); assert.equal(result.id, requestId); assert.equal(result.error, undefined);
      assert.ok(packets.every(packet => packet.id === packets[0].id));
      if (hasBridge) {
        const delivery = await bot.next(); assert.equal(delivery.id, packets[0].id); assert.equal(delivery.from, people.get(client === bob ? 'Bob' : 'Alice').name);
        assert.equal(delivery.source, 'minecraft'); assert.equal(delivery.room, 'global-chat');
        return { packet: packets[recipients.indexOf(client)], packets, delivery, requestId };
      }
      return { packet: packets[recipients.indexOf(client)], packets, requestId };
    }
    async function discord(text, changes = {}, recipients = [alice, bob, otherAlice]) {
      const requestId = String(discordSequence++);
      bot.ws.send(JSON.stringify({ type: 'discord_message', scope: 'global', source: 'discord', room: 'global-chat', channelId,
        id: requestId, message_id: requestId, discordId: '777777777777777777', from: 'Unlinked User', text, mentions: [], ...changes }));
      const packets = [];
      for (const recipient of recipients) packets.push(clientPacket(await recipient.next(), text));
      const result = await bot.next(); assert.equal(result.type, 'irc_result'); assert.equal(result.error, undefined);
      assert.ok(packets.every(packet => packet.id === packets[0].id)); return { requestId, packets };
    }
    const first = await minecraft(alice, 'Politics and profanity are normal conversation; anyone running F7?');
    assert.equal(first.delivery.discordId, alicePerson.discordId); assert.equal(first.delivery.uuid, alicePerson.uuid);
    const originalDiscordId = await ack(bot, first.delivery);
    assert.equal((await globalSql.exec('SELECT COUNT(*) AS count FROM global_chat_messages'))[0].count, 0);
    const reply = await discord('Yes — dungeon and Hub share this channel.', { mentions: [bobPerson.discordId, bobPerson.discordId],
      reply: { message_id: originalDiscordId, from: 'Spoofed preview', text: 'anyone running F7?' } });
    assert.deepEqual(reply.packets.map(packet => packet.mention), [false, true, false]);
    assert.deepEqual(reply.packets[0].reply, { from: 'Alice', text: 'anyone running F7?' });
    await ping(bot); // Discord-originated messages are never sent back to Discord.
    bot.ws.send(JSON.stringify({ type: 'discord_message', scope: 'global', source: 'discord', room: 'global-chat', channelId,
      id: reply.requestId, message_id: reply.requestId, discordId: '888888888888888888', from: 'Replayed author', text: 'changed metadata' }));
    assert.equal((await bot.next()).error, 'duplicate_message'); await ping(alice); await ping(bob);
    const creditsBeforePreview = (await globalSql.exec("SELECT credits,last_sent FROM global_chat_limits WHERE actor='dc:777777777777777777'"))[0];
    const latePreview = await discord('Late link preview\nhttps://a.test', { id: reply.requestId, message_id: reply.requestId, part: 'embeds' });
    assert.ok(latePreview.packets.every(packet => !packet.mention && !packet.reply));
    assert.deepEqual((await globalSql.exec("SELECT credits,last_sent FROM global_chat_limits WHERE actor='dc:777777777777777777'"))[0], creditsBeforePreview);
    for (const [part, messageId, error] of [['embeds', reply.requestId, 'duplicate_message'], ['embeds', String(discordSequence++), 'unknown_message'], ['unsupported', reply.requestId, 'invalid_part']]) {
      bot.ws.send(JSON.stringify({ type: 'discord_message', scope: 'global', source: 'discord', room: 'global-chat', channelId,
        id: messageId, message_id: messageId, part, discordId: '777777777777777777', from: 'Unlinked User', text: 'Another preview', mentions: [] }));
      const result = await bot.next(); assert.equal(result.error, error);
      if (part === 'embeds') assert.equal(result.part, 'embeds');
    }
    await ping(alice); await ping(bob);
    bot.ws.send(JSON.stringify({ type: 'discord_message', scope: 'global', source: 'discord', room: 'global-chat', channelId: '100000000000000009',
      id: String(discordSequence), message_id: String(discordSequence), discordId: '777777777777777777', from: 'Other channel', text: 'wrong channel' }));
    assert.equal((await bot.next()).error, 'invalid_route'); await ping(alice);
    const longDiscord = '😀'.repeat(2040) + '\nhttps://a.test';
    await discord(longDiscord, { discordId: '888888888888888888', from: 'Embed author' });
    await clearQuota();
    const mentioned = await minecraft(alice, '@Bob @Bob, hello @Bobby x@Bob @AnotherUser');
    assert.deepEqual(mentioned.packets.map(packet => packet.mention), [false, true, false]); await ack(bot, mentioned.delivery);
    const noMention = await minecraft(alice, '@Bobby x@Bob @AnotherUser @Bob_suffix @PrefixBob');
    assert.ok(noMention.packets.every(packet => !packet.mention)); await ack(bot, noMention.delivery);
    otherAlice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: id(), text: noMention.packet.text }));
    assert.equal((await otherAlice.next()).error, 'duplicate_text'); await ping(bob);
    alice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: id(), text: 'x'.repeat(1025) }));
    assert.equal((await alice.next()).error, 'invalid_message');
    for (const text of ['invalid\tcontrol', 'invalid\u0085control', 'broken\ud800']) {
      alice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: id(), text }));
      assert.equal((await alice.next()).error, 'invalid_message');
    }
    const unicode = await minecraft(alice, '😀'.repeat(512)); await ack(bot, unicode.delivery);
    otherAlice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: id(), text: 'account-wide quota' }));
    assert.equal((await otherAlice.next()).error, 'rate_limited');
    await mf.unsafeEvictDurableObject('global-chat-check', 'RelayRoom', { name: 'global-chat', webSockets: 'hibernate' });
    otherAlice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: id(), text: 'quota survives eviction' }));
    assert.equal((await otherAlice.next()).error, 'rate_limited');
    await clearQuota(); await subscribe(bob, false);
    const disabled = await minecraft(alice, 'Hidden locally without disconnecting authorization', [alice, otherAlice]); await ack(bot, disabled.delivery); await ping(bob);
    await subscribe(bob);
    const oldClose = closeEvent(bot.ws); bot.ws.close(); await oldClose;
    const offline = await minecraft(alice, 'Durable Discord outage delivery', [alice, bob, otherAlice], false);
    assert.ok((await globalSql.exec('SELECT * FROM global_chat_messages WHERE id=?', offline.packet.id)).length);
    assert.equal((await globalSql.exec("SELECT attempts FROM global_chat_outbox WHERE id=? AND destination='discord'", offline.packet.id))[0].attempts, 0);
    await mf.unsafeEvictDurableObject('global-chat-check', 'RelayRoom', { name: 'global-chat', webSockets: 'hibernate' });
    assert.equal(await coordinator.testOnlyGlobalAlarm(true), null);
    alice.ws.send(JSON.stringify({ type: 'irc_send', scope: 'global', id: offline.requestId, text: offline.packet.text }));
    const recovered = await alice.next(); assert.equal(recovered.error, 'duplicate_message'); assert.equal(recovered.scope, 'global');
    assert.ok(await coordinator.testOnlyGlobalAlarm(), 'Retry restores a missing alarm for already-persisted work');
    await ping(alice); await ping(bob); await ping(otherAlice);
    bot = await bridge(); const retry = await bot.next(); assert.equal(retry.id, offline.packet.id);
    await ping(alice); await ping(bob); // Retry reaches Discord only, not already-delivered clients.
    const unackedClose = closeEvent(bot.ws); bot.ws.close(); await unackedClose;
    bot = await bridge(); const repeatedDelivery = await bot.next(); assert.equal(repeatedDelivery.id, retry.id);
    const receiptId = await ack(bot, repeatedDelivery); await ack(bot, repeatedDelivery, receiptId);
    bot.ws.send(JSON.stringify({ type: 'discord_ack', scope: 'global', room: 'global-chat', channelId, id: 'f'.repeat(32), message_id: String(discordSequence++) }));
    assert.equal((await bot.next()).error, 'unknown_delivery');
    await discord('Reply with unavailable preview', { discordId: '888888888888888888', reply: { message_id: receiptId, from: 'Alice' } });
    assert.equal((await coordinator.globalChatMinecraft({ ...sender(alicePerson), discordId: bobPerson.discordId }, { id: id(), text: 'forged binding-only identity' })).error, 'not_authorized');
    assert.equal((await coordinator.globalChatMinecraft({ ...sender(alicePerson), leaseExpiresAt: Date.now() - 1 }, { id: id(), text: 'expired internal sender' })).error, 'not_authorized');
    await clearQuota();
    const closing = closeEvent(bot.ws); bot.ws.close(); await closing;
    const revokedPending = await minecraft(alice, 'Queued before revocation', [alice, bob, otherAlice], false);
    await authority.exec("UPDATE sky_accounts SET license_status='revoked',generation=generation+1 WHERE account_id=?", alicePerson.accountId);
    bot = await bridge(); await ping(bot);
    assert.equal((await globalSql.exec('SELECT * FROM global_chat_messages WHERE id=?', revokedPending.packet.id)).length, 0);
    const aliceClosed = closeEvent(alice.ws), otherClosed = closeEvent(otherAlice.ws);
    const stillAllowed = await discord('Discord follows channel permissions even for a revoked SkyMyce account', { discordId: alicePerson.discordId }, [bob]);
    assert.equal(stillAllowed.packets[0].from, 'Unlinked User');
    assert.equal((await alice.next()).type, 'authorization_revoked'); assert.equal((await otherAlice.next()).type, 'authorization_revoked');
    assert.equal((await aliceClosed).code, 4005); assert.equal((await otherClosed).code, 4005);
    assert.equal((await coordinator.globalChatMinecraft(sender(alicePerson), { id: id(), text: 'revoked after route' })).error, 'not_authorized');
    await clearQuota();
    const expiryClose = closeEvent(bob.ws);
    const shortBob = await connect('Bob', 'testing', { claims: { exp: Date.now() + 1000 } });
    assert.equal((await expiryClose).code, 4001); await subscribe(shortBob);
    await new Promise(resolve => setTimeout(resolve, 1100));
    const shortClosed = closeEvent(shortBob.ws);
    await discord('Expired clients cannot receive global chat', { discordId: '999999999999999999' }, []);
    assert.equal((await shortClosed).code, 4006);
    // Durable cleanup removes expired body rows and stops exhausted retry records.
    const pending = await coordinator.globalChatMinecraft(sender(bobPerson), { id: id(), text: 'bounded retry fixture' }); assert.equal(pending.ok, true);
    await bot.next();
    await globalSql.exec('UPDATE global_chat_messages SET expires=? WHERE id=?', Date.now() - 1, pending.id);
    const next = await coordinator.globalChatMinecraft(sender(bobPerson), { id: id(), text: 'cleanup fixture' }); assert.equal(next.ok, true);
    await bot.next();
    assert.equal((await globalSql.exec('SELECT * FROM global_chat_messages WHERE id=?', pending.id)).length, 0);
    await globalSql.exec('UPDATE global_chat_outbox SET attempts=5,next_at=? WHERE id=?', Date.now() - 1, next.id);
    const exhausted = await coordinator.globalChatMinecraft(sender(bobPerson), { id: id(), text: 'after exhausted retry' }); assert.equal(exhausted.ok, true);
    await bot.next();
    assert.equal((await globalSql.exec('SELECT * FROM global_chat_outbox WHERE id=?', next.id)).length, 0);
    assert.ok((await globalSql.exec('SELECT COUNT(*) AS count FROM global_chat_messages'))[0].count <= 256);
    console.log('Global chat checks passed: existing strict sockets, cross-room broadcast, trusted identities, unlinked Discord authors, replies, mentions, Unicode limits, opt-in, spam, hibernation, durable retry/ACK, revocation and expiry.');
  } finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }
}
