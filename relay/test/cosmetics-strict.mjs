import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Production variable names and real account/installation proofs, not the legacy cosmetics fixture. */
export async function checkStrictCosmetics(script, minecraftProof) {
  const discord = generateKeyPairSync('ed25519'), signer = generateKeyPairSync('ed25519'), installation = generateKeyPairSync('ed25519');
  const publicKey = pair => pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const actor = '44444444444444444', stranger = '55555555555555555', id = 'a'.repeat(32);
  const env = { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'production', DISCORD_GUILD_ID: '11111111111111111',
    ADMIN_BOOTSTRAP_USER_ID: '33333333333333333', USER_BOT_APPLICATION_ID: '66666666666666666',
    USER_BOT_PUBLIC_KEY: discord.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('hex'),
    COSMETICS_COMMAND_CHANNEL: '1552057526969835612', DRM_KEY_ID: 'test', DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signer) }),
    DRM_SIGNING_PRIVATE_KEY: signer.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'), SKY_MYCE_VERSION: '2.4.2' };
  const completions = new Map(), edits = [], sockets = [];
  const options = convertV4MiniflareOptions({ name: 'strict-cosmetics-check', modules: true, script, compatibilityDate: '2026-09-27', bindings: env,
    durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: async request => {
      const match = new URL(request.url).pathname.match(new RegExp(`^/api/v10/webhooks/${env.USER_BOT_APPLICATION_ID}/([A-Za-z0-9_-]+)/messages/@original$`));
      assert.equal(request.method, 'PATCH'); assert.ok(match, 'Only the signed application interaction may be completed');
      assert.equal(request.headers.get('Authorization'), null, 'Cosmetics does not require the backend bot credential');
      const body = await request.json(); assert.deepEqual(body.allowed_mentions, { parse: [] });
      edits.push(body); completions.get(match[1])?.(body.content); return Response.json({});
    } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  const mf = new Miniflare(options);
  let sequence = 90000000000000000n;
  const nextId = () => String(sequence++);
  const post = async (path, body) => {
    const response = await mf.dispatchFetch(`https://example.test${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    assert.equal(response.status, 200); return response.json();
  };
  function inbox(ws) {
    const queued = [], waiting = [];
    ws.addEventListener('message', event => { const data = JSON.parse(event.data); if (waiting.length) waiting.shift()(data); else queued.push(data); });
    return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('Strict cosmetics packet timed out')), 3000);
      waiting.push(data => { clearTimeout(timer); resolve(data); });
    });
  }
  async function packet(client, body) {
    const messageId = nextId().padStart(32, '0'); client.ws.send(JSON.stringify({ id: messageId, ...body }));
    const result = await client.next(); assert.equal(result.id, messageId); return result;
  }
  async function interaction(action, args = {}, user = actor, extra = {}) {
    const token = `cosmetics_test_${nextId()}`;
    const body = JSON.stringify({ type: 2, application_id: env.USER_BOT_APPLICATION_ID, id: nextId(), token,
      guild_id: env.DISCORD_GUILD_ID, channel_id: env.COSMETICS_COMMAND_CHANNEL, member: { user: { id: user }, roles: [] },
      data: { name: 'cosmetics', options: [{ type: 1, name: action,
        options: Object.entries(args).map(([name, value]) => ({ name, value, type: ['x', 'y', 'z'].includes(name) ? 10 : name === 'confirm' ? 5 : 3 })) }] }, ...extra });
    const timestamp = String(Math.floor(Date.now() / 1000)), signature = sign(null, Buffer.from(timestamp + body), discord.privateKey).toString('hex');
    let finish, timer;
    const completed = new Promise((resolve, reject) => { finish = resolve; timer = setTimeout(() => reject(Error('Deferred cosmetics reply timed out')), 5000); });
    completions.set(token, finish);
    try {
      const response = await mf.dispatchFetch('https://example.test/discord/interactions', { method: 'POST', body,
        headers: { 'X-Signature-Ed25519': signature, 'X-Signature-Timestamp': timestamp } });
      if (extra.guild_id) { assert.equal(response.status, 403); finish(null); return null; }
      assert.equal(response.status, 200); assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } });
      return await completed;
    } finally { clearTimeout(timer); completions.delete(token); }
  }
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const admin = action => store.developerCommand({ id: env.ADMIN_BOOTSTRAP_USER_ID, guildId: env.DISCORD_GUILD_ID, roles: [], interactionId: nextId() }, action, actor, {});
    const challenge = await post('/community/challenge', { purpose: 'connect', clientNonce: randomBytes(32).toString('base64url') });
    const pollSecret = 'x'.repeat(43), flow = await post('/community/connect/start', { challengeId: challenge.challengeId, minecraftProof: minecraftProof('Alice', challenge.nonce), pollSecret, modVersion: '0.1.0' });
    assert.equal((await store.communityUserCommand({ id: actor, guildId: env.DISCORD_GUILD_ID, roles: [], interactionId: nextId() }, 'connect', { code: flow.fallbackCode })).ok, true);
    const { credentials } = await post('/community/connect/poll', { flowId: flow.flowId, pollSecret });
    async function connect(room, version = 5) {
      const response = await mf.dispatchFetch(`https://example.test/websocket?room=${room}`, { headers: { Upgrade: 'websocket' } });
      assert.equal(response.status, 101); const ws = response.webSocket, next = inbox(ws); sockets.push(ws); ws.accept();
      const challenge = await next(); assert.equal(challenge.protocol, 3);
      ws.send(JSON.stringify({ ...minecraftProof('Alice', challenge.serverId), communityToken: credentials.accessToken,
        cosmetics: true, cosmeticsVersion: version }));
      const ready = await next(); assert.equal(ready.cosmetics, true); assert.equal(ready.cosmeticsNames, true); return { ws, next };
    }
    const friends = await connect('friends'), testing = await connect('testing');
    const initial = await packet(friends, { type: 'cosmetics_get', names: ['aLiCe'] });
    assert.deepEqual(initial.identities, [{ name: 'Alice', uuid: id }]); assert.equal(initial.records[0].revision, 0);
    assert.equal((await packet(testing, { type: 'cosmetics_get', uuids: [id] })).records[0].revision, 0);
    assert.match(await interaction('set', { name: '{"text":"Aster","color":"gold"}', x: 1.25, y: 1.5, z: 0.75 }), /Saved/);
    const event = await friends.next(); assert.equal(event.type, 'cosmetics_event'); assert.equal(event.record.name, 'Aster');
    assert.deepEqual([event.record.scaleX, event.record.scaleY, event.record.scaleZ], [1.25, 1.5, 0.75]);
    assert.equal(event.record.nameStyle.color, 'gold');
    const shared = await testing.next(); assert.equal(shared.type, 'cosmetics_event'); assert.equal(shared.record.revision, event.record.revision);
    assert.equal((await packet(testing, { type: 'cosmetics_get', uuids: [id] })).records[0].revision, 1, 'All rooms in one deployment share one cosmetic authority');
    assert.match(await interaction('show'), /Aster/);
    const cosmeticSql = await mf.unsafeGetDurableObjectStorage('strict-cosmetics-check', 'AccountStore', { name: 'global' });
    const gradient = JSON.stringify([{ text: 'A', color: '#112233', bold: true }, [{ text: 's', color: '#223344' }, 't'], { text: '', extra: [{ text: 'e', color: '#334455' }, { text: 'r', color: '#445566' }] }]);
    assert.match(await interaction('set', { name: gradient }), /Saved/);
    const gradientEvent = (await friends.next()).record; await testing.next();
    assert.equal(gradientEvent.name, 'Aster'); assert.equal(gradientEvent.nameStyle.color, '#112233');
    assert.equal(gradientEvent.nameStyle.extra[0].color, '#223344');
    assert.deepEqual(gradientEvent.nameStyle.extra[0].extra, [{ text: 't' }], 'Nested native arrays preserve inherited styles instead of flattening characters');
    assert.equal(gradientEvent.nameStyle.extra[1].extra[1].color, '#445566');
    assert.match(await interaction('set', { y: 1.4 }), /Saved/);
    const untouched = (await friends.next()).record; await testing.next();
    assert.deepEqual(untouched.nameStyle, gradientEvent.nameStyle, 'A body-only edit preserves the structured name exactly');
    await cosmeticSql.exec('DELETE FROM cosmetics_limits');
    const expanded = JSON.stringify(Array.from({ length: 32 }, (_, i) => ({ text: 'A', color: i % 2 ? '#223344' : '#112233' })));
    assert.match(await interaction('set', { name: expanded }), /Saved/);
    const native = (await friends.next()).record; await testing.next();
    assert.equal(native.nameStyle.extra.length, 31, 'Version5 receives all actual character components');
    for (const version of [2, 3, 4]) {
      const older = await connect('testing', version);
      const record = (await packet(older, { type: 'cosmetics_get', uuids: [id] })).records[0];
      assert.equal(record.name, 'A'.repeat(32)); assert.deepEqual(record.nameStyle, { text: record.name }, `Version${version} cannot receive a tree rejected by its old strict parser`);
      assert.equal(Object.hasOwn(record, 'capeAsset'), version === 4, 'Previous profile shapes remain exact');
    }
    for (const name of ['[]', '["", {"text":"Aster","clickEvent":{"action":"run_command","command":"/op"}}]', JSON.stringify(Array.from({ length: 65 }, () => '')), JSON.stringify('x'.repeat(33)), JSON.stringify({ text: 'Aster', extra: [[{ text: '@everyone' }]] })]) {
      await cosmeticSql.exec('DELETE FROM cosmetics_limits');
      assert.match(await interaction('set', { name }), /Name|name|text|components|characters/, 'Malformed, over-complex, executable and unsafe components fail closed instead of hitting rate limits');
    }
    await cosmeticSql.exec('DELETE FROM cosmetics_limits');
    assert.match(await interaction('set', { name: 'Stolen' }, stranger), /cannot use community services/);
    await interaction('set', { name: 'WrongGuild' }, actor, { guild_id: '99999999999999999' });
    const link = await packet(friends, { type: 'cosmetics_link', uuid: 'b'.repeat(32) });
    assert.match(link.code, /^[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}$/, 'Canonical USER_BOT_* works inside the Durable Object');
    assert.match(await interaction('link', { code: link.code }, stranger), /could not be completed/);
    assert.match(await interaction('link', { code: link.code }), /linked to your Sky-Myce account/);
    assert.match(await interaction('link', { code: link.code }), /could not be completed/);
    assert.deepEqual((await store.getAccountByDiscordId(actor)).uuids.map(value => value.uuid), [id], 'Client-supplied target UUID is ignored');
    assert.equal(await store.getAccountByDiscordId(stranger), null, 'Wrong caller never gains an account or ownership');
    const sql = await mf.unsafeGetDurableObjectStorage('strict-cosmetics-check', 'AccountStore', { name: 'global' });
    await sql.exec("DELETE FROM account_limits WHERE id LIKE 'link:%'");
    const expired = await packet(friends, { type: 'cosmetics_link' }); await sql.exec('UPDATE minecraft_links SET expires_at=0');
    assert.match(await interaction('link', { code: expired.code }), /could not be completed/);
    assert.match(await interaction('unlink', { confirm: true }), /Use \/account unlink/);
    await sql.exec("INSERT INTO community_blacklist VALUES('discord',?,'Synthetic abuse fixture','test',?,NULL,1)", actor, Date.now());
    assert.match(await interaction('set', { name: 'Revoked' }), /cannot use community services/);
    const revoked = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('Revoked name lookup was not rejected')), 3000);
      friends.ws.addEventListener('close', event => { clearTimeout(timer); resolve(event); }, { once: true });
    });
    friends.ws.send(JSON.stringify({ type: 'cosmetics_get', id: nextId().padStart(32, '0'), names: ['Alice'] }));
    assert.equal((await revoked).code, 4005, 'Name lookup cannot bypass revoked account authorization');
    assert.equal((await sql.exec('SELECT display_name FROM cosmetics_public WHERE uuid=?', id))[0].display_name, native.name, 'A revoked mutation cannot replace the last accepted expanded name');
    assert.ok(edits.length >= 9);
    console.log('Strict cosmetics checks passed: canonical configuration, private responses, community proofs, cross-room authoritative appearance, ownership, replay, expiry and identity blacklist.');
  } finally { for (const ws of sockets) { try { ws.close(); } catch {} } await mf.dispose(); }
}
