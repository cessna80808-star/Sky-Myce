import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

export async function checkRichIrc(script, proof) {
  const secret = 'rich-irc-test-only-bridge-secret-32bytes';
  const people = ['Alice', 'Bob', 'Carol'].map((name, i) => ({ name, uuid: 'abc'[i].repeat(32),
    discordId: String(200000000000000000n + BigInt(i)), accountId: randomUUID(), sessionId: randomUUID(),
    accessToken: randomBytes(32).toString('base64url') }));
  const channels = { friends: '100000000000000001', testing: '100000000000000002', 'global-chat': '100000000000000003' };
  const options = convertV4MiniflareOptions({ name: 'rich-irc-check', modules: true, script, compatibilityDate: '2026-09-27',
    bindings: { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'staging', DISCORD_BRIDGE_SECRET: secret,
      GLOBAL_CHAT_CHANNEL_ID: channels['global-chat'], DISCORD_IRC_CHANNEL_ID: channels.friends, DISCORD_IRC_TEST_CHANNEL_ID: channels.testing,
      GLOBAL_CHAT_MIN_INTERVAL_MS: '0', GLOBAL_CHAT_BURST: '20', IRC_BURST: '12' },
    durableObjects: { RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true }, ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true } },
    outboundService: () => { throw new Error('Rich IRC fixtures never contact real external services'); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112033', simple: { limit: 1000, period: 60 } };
  const mf = new Miniflare(options), sockets = []; let sequence = 1, ip = 1, discordSequence = 900000000000000001n;
  const id = () => (sequence++).toString(16).padStart(32, '0');
  function inbox(ws) {
    const queued = [], waiting = [];
    ws.addEventListener('message', event => {
      const value = event.data === 'pong' ? 'pong' : JSON.parse(event.data);
      if (waiting.length) waiting.shift()(value); else queued.push(value);
    });
    return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Rich IRC response timed out')), 5000);
      waiting.push(value => { clearTimeout(timer); resolve(value); });
    });
  }
  async function connect(person, room, rich = true) {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': `rich-mc-${ip++}` } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws);
    const challenge = await next();
    ws.send(JSON.stringify({ ...proof(person.name, challenge.serverId), communityToken: person.accessToken, modVersion: '2.5.0' }));
    const ready = await next(); assert.equal(ready.richChatVersion, 1); assert.equal(ready.partyChat, true); assert.equal(ready.partyChatVersion, 1);
    return { ws, next, person, room, rich };
  }
  async function subscribe(client, scope, enabled = true) {
    client.ws.send(JSON.stringify({ type: 'irc_subscribe', id: id(), scope, enabled, ...(client.rich ? { richChatVersion: 1 } : {}) }));
    assert.equal((await client.next()).error, undefined);
  }
  async function bridge(room) {
    const time = String(Date.now()), nonce = randomUUID().replaceAll('-', '');
    const response = await mf.dispatchFetch(`http://localhost/discord/bridge?room=${room}`, { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': `rich-dc-${ip++}`,
      'X-SkyMyce-Timestamp': time, 'X-SkyMyce-Nonce': nonce,
      'X-SkyMyce-Signature': createHmac('sha256', secret).update(`sky-myce-bridge\n${room}\n${time}\n${nonce}`).digest('hex') } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws);
    assert.equal((await next()).richChatVersion, 1);
    ws.send(JSON.stringify({ type: 'bridge_capabilities', id: id(), richChatVersion: 1 }));
    assert.equal((await next()).error, undefined);
    return { ws, next, room };
  }
  async function ping(client) { client.ws.send('ping'); assert.equal(await client.next(), 'pong', 'No duplicate, legacy update or cross-scope message remains'); }
  const send = (client, scope, packet) => client.ws.send(JSON.stringify({ scope, room: scope === 'global' ? 'global-chat' : client.room, ...packet }));
  async function acknowledge(bot, packet, operation = false) {
    const dest = operation ? packet.discordMessageId : String(discordSequence++);
    send(bot, bot.room === 'global-chat' ? 'global' : 'room', { type: operation ? 'discord_operation_ack' : 'discord_ack', id: packet.id,
      channelId: channels[bot.room], message_id: dest, ...(operation ? { messageId: packet.messageId, revision: packet.revision } : {}) });
    assert.equal((await bot.next()).error, undefined); return dest;
  }
  try {
    const p = people[0];
    assert.equal((await mf.dispatchFetch('http://localhost/community/challenge', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ purpose: 'connect', clientNonce: randomBytes(32).toString('base64url') }) })).status, 200);
    const authority = await mf.unsafeGetDurableObjectStorage('rich-irc-check', 'AccountStore', { name: 'global' });
    for (const person of people) {
      const now = Date.now();
      await authority.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen) VALUES(?,?,?,?)', person.accountId, person.discordId, now, now);
      await authority.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', person.uuid, person.accountId, person.name, now, now);
      await authority.exec('INSERT INTO community_sessions VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,NULL,NULL)', person.sessionId, person.accountId, person.uuid, 0,
        createHash('sha256').update(randomBytes(32)).digest('hex'), now + 90 * 86400000,
        createHash('sha256').update(person.accessToken).digest('hex'), now + 300000, now, now, '2.5.0');
    }
    const alice = await connect(people[0], 'friends'), bob = await connect(people[1], 'friends'), isolated = await connect(people[1], 'testing'), legacy = await connect(people[2], 'testing', false);
    const roomBot = await bridge('friends'), globalBot = await bridge('global-chat');
    for (const client of [alice, bob]) { await subscribe(client, 'room'); await subscribe(client, 'global'); }
    await subscribe(isolated, 'global'); await subscribe(legacy, 'global');
    const friendsSql = await mf.unsafeGetDurableObjectStorage('rich-irc-check', 'RelayRoom', { name: 'friends' });
    await friendsSql.exec('INSERT INTO cosmetics_public(uuid,display_name,scale,revision,updated_at) VALUES(?,?,1,1,?)', p.uuid, 'My Cosmetic', Date.now());
    for (const scope of ['room', 'global']) {
      const bot = scope === 'global' ? globalBot : roomBot, clients = scope === 'global' ? [alice, bob, isolated, legacy] : [alice, bob];
      const room = scope === 'global' ? 'global-chat' : 'friends';
      const sql = scope === 'global' ? await mf.unsafeGetDurableObjectStorage('rich-irc-check', 'RelayRoom', { name: room }) : friendsSql;
      const initial = id();
      send(alice, scope, { type: 'irc_send', id: initial, text: `${scope} @Bob https://example.com/one.gif`, sender: { cosmeticName: 'Spoof', uuid: people[1].uuid } });
      const messages = [];
      for (const client of clients) messages.push(await client.next());
      assert.equal((await alice.next()).error, undefined);
      const message = messages[0]; assert.equal(message.model, 1); assert.equal(message.media[0].type, 'gif');
      assert.equal(message.sender.uuid, p.uuid); assert.equal(message.sender.cosmeticName, 'My Cosmetic'); assert.equal(message.sender.name, 'Alice');
      assert.equal(messages[1].mention, true); assert.equal(messages[0].mention, false);
      if (scope === 'global') { assert.equal(messages[3].model, undefined); assert.equal(messages[3].sender, undefined); }
      const delivered = await bot.next(); assert.equal(delivered.id, message.id);
      const discordId = await acknowledge(bot, delivered);
      await mf.unsafeEvictDurableObject('rich-irc-check', 'RelayRoom', { name: room, webSockets: 'hibernate' });
      send(bob, scope, { type: 'irc_operation', id: id(), messageId: message.id, operation: 'edit', text: 'forged' });
      assert.equal((await bob.next()).error, 'not_authorized');
      send(alice, scope, { type: 'irc_operation', id: id(), messageId: message.id, operation: 'reaction', emoji: 'not-allowed', active: true });
      assert.equal((await alice.next()).error, 'invalid_operation');
      const editId = id();
      send(alice, scope, { type: 'irc_operation', id: editId, messageId: message.id, operation: 'edit', text: `${scope} edited https://example.com/updated.png` });
      for (const client of clients.filter(c => c.rich)) { const update = await client.next(); assert.equal(update.operation, 'edit'); assert.equal(update.messageId, message.id); assert.equal(update.revision, 1); assert.equal(update.media[0].type, 'image'); }
      assert.equal((await alice.next()).error, undefined);
      const editDelivery = await bot.next(); assert.equal(editDelivery.type, 'irc_operation'); assert.equal(editDelivery.discordMessageId, discordId);
      const failedDelivery = { type: 'discord_operation_result', id: editDelivery.id, error: 'superseded' };
      send(bot, scope, { ...failedDelivery, channelId: channels[scope === 'global' ? 'friends' : 'global-chat'] });
      if (scope === 'global') assert.equal((await bot.next()).error, 'invalid_route');
      await ping(bot);
      assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM irc_operation_outbox WHERE id=?', editDelivery.id))[0].count, 1,
        'A result from another Discord channel cannot cancel this operation');
      send(bot, scope, { ...failedDelivery, channelId: channels[room] });
      await ping(bot);
      assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM irc_operation_outbox WHERE id=?', editDelivery.id))[0].count, 0,
        'A trusted superseded result stops retrying an obsolete operation');
      send(alice, scope, { type: 'irc_operation', id: editId, messageId: message.id, operation: 'edit', text: 'duplicate cannot alter content' });
      assert.equal((await alice.next()).error, undefined); await ping(alice); await ping(bob); await ping(bot);
      send(alice, scope, { type: 'irc_operation', id: id(), messageId: message.id, operation: 'reaction', emoji: '👍', active: true });
      for (const client of clients.filter(c => c.rich)) { const update = await client.next(); assert.deepEqual(update.reactions, [{ emoji: '👍', count: 1, mine: client.person.uuid === p.uuid }]); }
      assert.equal((await alice.next()).error, undefined); const reaction = await bot.next(); assert.equal(reaction.active, true); await acknowledge(bot, reaction, true);
      send(bot, scope, { type: 'discord_operation', id: id(), source: 'discord', channelId: channels[room], message_id: discordId,
        discordId: people[1].discordId, operation: 'reaction', emoji: '👍', active: true });
      for (const client of clients.filter(c => c.rich)) assert.equal((await client.next()).reactions[0].count, 2);
      assert.equal((await bot.next()).error, undefined); await ping(bot); // Native changes never echo back to Discord.
      const replyRequest = id();
      send(alice, scope, { type: 'irc_send', id: replyRequest, text: `${scope} answer`, replyTo: message.id });
      for (const client of clients) { const answer = await client.next(); if (client.rich) assert.equal(answer.reply.messageId, message.id); }
      assert.equal((await alice.next()).error, undefined); const answerDelivery = await bot.next(); assert.equal(answerDelivery.reply.discordMessageId, discordId); await acknowledge(bot, answerDelivery);
      send(alice, scope, { type: 'irc_operation', id: id(), messageId: message.id, operation: 'delete' });
      for (const client of clients.filter(c => c.rich)) assert.equal((await client.next()).operation, 'delete');
      assert.equal((await alice.next()).error, undefined); await acknowledge(bot, await bot.next(), true);
      send(alice, scope, { type: 'irc_operation', id: id(), messageId: message.id, operation: 'reaction', emoji: '🔥', active: true });
      assert.equal((await alice.next()).error, 'message_deleted');
      const stored = JSON.parse((await sql.exec('SELECT record FROM irc_metadata WHERE id=?', message.id))[0].record);
      assert.equal(stored.deleted, true); assert.equal(stored.preview, ''); assert.deepEqual(stored.media, []);
      assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM irc_operation_outbox'))[0].count, 0);
      for (const client of clients) await ping(client);
      if (scope === 'room') await ping(isolated);
      const sourceId = String(discordSequence++), media = [{ id: 'attachment1', type: 'video', url: 'https://cdn.discordapp.com/attachments/100000000000000001/100000000000000002/movie.mp4', mime: 'video/mp4' }];
      send(bot, scope, { type: 'discord_message', id: sourceId, message_id: sourceId, source: 'discord', channelId: channels[room], discordId: p.discordId, from: 'Discord Alice', text: `${scope} native video`, media, mentions: [] });
      let nativeMessage;
      for (const client of clients) { const incoming = await client.next(); if (client.rich) assert.equal(incoming.media[0].type, 'video'); nativeMessage ??= incoming; }
      assert.equal((await bot.next()).error, undefined);
      send(alice, scope, { type: 'irc_operation', id: id(), messageId: nativeMessage.id, operation: 'delete' });
      assert.equal((await alice.next()).error, 'not_authorized', 'A client never deletes a human Discord message');
      send(bot, scope, { type: 'discord_operation', id: id(), source: 'discord', channelId: channels[room], message_id: sourceId, discordId: p.discordId, operation: 'edit', editedAt: Date.now(), text: `${scope} changed native`, media: [] });
      for (const client of clients.filter(c => c.rich)) { const update = await client.next(); assert.equal(update.messageId, nativeMessage.id); assert.deepEqual(update.media, []); }
      assert.equal((await bot.next()).error, undefined);
      send(bot, scope, { type: 'discord_operation', id: id(), source: 'discord', channelId: channels[room], message_id: sourceId, discordId: p.discordId, operation: 'delete' });
      for (const client of clients.filter(c => c.rich)) assert.equal((await client.next()).operation, 'delete');
      assert.equal((await bot.next()).error, undefined);
      send(bot, scope, { type: 'discord_message', id: String(discordSequence++), message_id: String(discordSequence - 1n), source: 'discord', channelId: channels[room], discordId: p.discordId, from: 'Invalid', text: 'invalid URL', media: [{ id: 'bad', type: 'image', url: 'https://127.0.0.1/private.png' }] });
      assert.equal((await bot.next()).error, 'invalid_media');
      for (const client of clients) await ping(client);
    }
    const partyMembers = people.slice(0, 2).map(person => person.uuid).sort(), leader = people[0].uuid;
    const partyId = createHash('sha256').update(`sky-myce-party-v1\n${leader}\n${partyMembers.join('\n')}`).digest('hex').slice(0, 32);
    async function partySubscribe(client, enabled = true, members = partyMembers, partyLeader = leader) {
      client.ws.send(JSON.stringify({ type: 'irc_subscribe', id: id(), scope: 'party', enabled, richChatVersion: 1, members, leader: partyLeader }));
      return client.next();
    }
    const partySend = (client, packet) => client.ws.send(JSON.stringify({ scope: 'party', room: partyId, ...packet }));
    assert.equal((await partySubscribe(alice)).room, partyId); assert.equal((await partySubscribe(isolated)).room, partyId);
    const outsider = await connect(people[2], 'friends');
    assert.equal((await partySubscribe(outsider)).error, 'party_membership_unverified', 'Authenticated self must belong to the consent roster');
    const differentParty = await partySubscribe(outsider, true, people.map(person => person.uuid));
    assert.notEqual(differentParty.room, partyId, 'An invented larger roster cannot join the real party audience');
    partySend(alice, { type: 'irc_send', id: id(), text: 'Private party @Bob https://example.com/party.gif' });
    const partyMessage = await alice.next(), partyPeer = await isolated.next();
    assert.equal(partyMessage.scope, 'party'); assert.equal(partyMessage.media[0].type, 'gif'); assert.equal(partyPeer.mention, true);
    assert.equal((await alice.next()).error, undefined);
    for (const client of [bob, legacy, outsider, roomBot, globalBot]) await ping(client);
    partySend(outsider, { type: 'irc_operation', id: id(), messageId: partyMessage.id, operation: 'edit', text: 'cross-party forged edit' });
    assert.equal((await outsider.next()).error, 'invalid_route');
    partySend(isolated, { type: 'irc_operation', id: id(), messageId: partyMessage.id, operation: 'edit', text: 'different author' });
    assert.equal((await isolated.next()).error, 'not_authorized');
    const partyEdit = id();
    partySend(alice, { type: 'irc_operation', id: partyEdit, messageId: partyMessage.id, operation: 'edit', text: 'Private party edited' });
    for (const client of [alice, isolated]) { const update = await client.next(); assert.equal(update.scope, 'party'); assert.equal(update.revision, 1); }
    assert.equal((await alice.next()).error, undefined);
    partySend(alice, { type: 'irc_operation', id: partyEdit, messageId: partyMessage.id, operation: 'edit', text: 'ignored duplicate' });
    assert.equal((await alice.next()).error, undefined); await ping(isolated);
    partySend(isolated, { type: 'irc_operation', id: id(), messageId: partyMessage.id, operation: 'reaction', emoji: '👍', active: true });
    for (const client of [alice, isolated]) { const update = await client.next(); assert.equal(update.reactions[0].mine, client === isolated); }
    assert.equal((await isolated.next()).error, undefined);
    partySend(alice, { type: 'irc_send', id: id(), text: 'Party reply', replyTo: partyMessage.id });
    for (const client of [alice, isolated]) assert.equal((await client.next()).reply.messageId, partyMessage.id);
    assert.equal((await alice.next()).error, undefined);
    const partySql = await mf.unsafeGetDurableObjectStorage('rich-irc-check', 'RelayRoom', { name: 'party-chat' });
    assert.equal((await partySql.exec('SELECT COUNT(*) AS count FROM global_chat_outbox'))[0].count, 0);
    assert.equal((await partySql.exec('SELECT COUNT(*) AS count FROM irc_operation_outbox'))[0].count, 0);
    const obsoleteSubscribe = id(), currentSubscribe = id();
    alice.ws.send(JSON.stringify({ type: 'irc_subscribe', id: obsoleteSubscribe, scope: 'party', enabled: true, richChatVersion: 1,
      members: people.map(person => person.uuid), leader }));
    alice.ws.send(JSON.stringify({ type: 'irc_subscribe', id: currentSubscribe, scope: 'party', enabled: true, richChatVersion: 1, members: partyMembers, leader }));
    let latestSubscription;
    do { latestSubscription = await alice.next(); assert.equal(latestSubscription.type, 'irc_result'); }
    while (latestSubscription.id !== currentSubscribe);
    assert.equal(latestSubscription.room, partyId); assert.equal(latestSubscription.error, undefined); await ping(alice);
    assert.ok((await partySql.exec('SELECT party_id FROM party_chat_consents WHERE uuid=?', people[0].uuid)).every(row => row.party_id === partyId));
    outsider.ws.send(JSON.stringify({ type: 'irc_send', id: id(), scope: 'party', room: differentParty.room, text: 'Distinct roster stays distinct' }));
    assert.equal((await outsider.next()).room, differentParty.room); assert.equal((await outsider.next()).error, undefined);
    await ping(alice); await ping(isolated);
    assert.equal((await partySubscribe(isolated, false)).error, undefined);
    partySend(alice, { type: 'irc_operation', id: id(), messageId: partyMessage.id, operation: 'delete' });
    assert.equal((await alice.next()).operation, 'delete'); assert.equal((await alice.next()).error, undefined);
    for (const client of [isolated, outsider, bob, legacy, roomBot, globalBot]) await ping(client);
    partySend(isolated, { type: 'irc_send', id: id(), text: 'Revoked party consent' });
    assert.equal((await isolated.next()).error, 'not_subscribed');
    assert.equal((await partySubscribe(isolated)).room, partyId);
    await partySql.exec('UPDATE party_chat_consents SET expires=? WHERE uuid=?', Date.now() - 1, people[1].uuid);
    partySend(isolated, { type: 'irc_send', id: id(), text: 'Expired coordinator consent' });
    assert.equal((await isolated.next()).error, 'not_authorized');
    await mf.unsafeEvictDurableObject('rich-irc-check', 'RelayRoom', { name: 'party-chat', webSockets: 'hibernate' });
    assert.equal((await partySubscribe(alice)).room, partyId);
    partySend(alice, { type: 'irc_send', id: id(), text: 'No party history restored after restart' });
    assert.equal((await alice.next()).source, 'minecraft'); assert.equal((await alice.next()).error, undefined);
    assert.equal((await isolated.next()).scope, 'party'); // Refreshing Alice does not change Bob's local opt-in; delivery is live only.
    for (const client of [outsider, roomBot, globalBot]) await ping(client);
    const globalSql = await mf.unsafeGetDurableObjectStorage('rich-irc-check', 'RelayRoom', { name: 'global-chat' });
    const retained = (await globalSql.exec('SELECT id,record FROM irc_metadata'))[0];
    await globalSql.exec('UPDATE irc_metadata SET expires=? WHERE id=?', Date.now() - 1, retained.id);
    send(alice, 'global', { type: 'irc_operation', id: id(), messageId: retained.id, operation: 'delete' });
    assert.equal((await alice.next()).error, 'message_expired');
    send(alice, 'global', { type: 'irc_send', id: id(), text: 'Expiry pruning keeps live-only metadata bounded' });
    for (const client of [alice, bob, isolated, legacy]) assert.equal((await client.next()).type, 'irc_message');
    assert.equal((await alice.next()).error, undefined); await acknowledge(globalBot, await globalBot.next());
    assert.equal((await globalSql.exec('SELECT id FROM irc_metadata WHERE id=?', retained.id)).length, 0);
    const record = JSON.parse((await globalSql.exec('SELECT record FROM irc_metadata LIMIT 1'))[0].record);
    assert.ok(record.preview.length <= 256); for (const key of ['accountId', 'installationId', 'lease', 'authorization', 'communityToken', 'communitySessionId', 'text']) assert.equal(record[key], undefined);
    console.log('Rich IRC checks passed: Global/room/Party isolation, roster consent changes, verified cosmetics, media, replies, ownership, native lifecycle, reactions, dedup, tombstones, eviction and bounded expiry.');
  } finally { for (const ws of sockets) try { ws.close(1000, 'rich IRC tests complete'); } catch {} await mf.dispose(); }
}
