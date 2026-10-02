import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
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
    const issued = await admin('authorise'); assert.equal(issued.ok, true);
    const input = { purpose: 'activate', installationId: randomUUID(), publicKey: publicKey(installation), deviceHash: 'd'.repeat(64),
      minecraftVersion: '26.1.2', loaderVersion: '1.0.0', modVersion: '2.4.2', clientNonce: randomUUID() };
    const challenge = await post('/auth/challenge', input);
    assert.equal((await store.userCommand({ id: actor, guildId: env.DISCORD_GUILD_ID, roles: [], interactionId: nextId() }, 'approve', { code: challenge.approvalCode })).ok, true);
    const signature = sign(null, Buffer.from(`Sky-Myce installation v1\nactivate\n${challenge.nonce}\n${input.installationId}\n${input.deviceHash}\n26.1.2\n1.0.0\n2.4.2\n${input.clientNonce}`), installation.privateKey).toString('base64');
    const { lease } = await post('/auth/activate', { challengeId: challenge.challengeId, signature, activationKey: issued.activationKey, minecraftProof: minecraftProof('Alice', challenge.nonce) });
    async function connect(room) {
      const response = await mf.dispatchFetch(`https://example.test/websocket?room=${room}`, { headers: { Upgrade: 'websocket' } });
      assert.equal(response.status, 101); const ws = response.webSocket, next = inbox(ws); sockets.push(ws); ws.accept();
      const challenge = await next(); assert.equal(challenge.protocol, 3);
      ws.send(JSON.stringify({ ...minecraftProof('Alice', challenge.serverId), lease,
        installationSignature: sign(null, Buffer.from(`Sky-Myce relay v3\n${challenge.serverId}\n${lease}`), installation.privateKey).toString('base64'),
        cosmetics: true, cosmeticsVersion: 3 }));
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
    assert.equal((await packet(testing, { type: 'cosmetics_get', uuids: [id] })).records[0].revision, 0, 'Production change stays out of testing');
    assert.match(await interaction('show'), /Aster/);
    assert.match(await interaction('set', { name: 'Stolen' }, stranger), /not currently authorized/);
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
    assert.equal((await admin('license_revoke')).ok, true);
    assert.match(await interaction('set', { name: 'Revoked' }), /not currently authorized/);
    const revoked = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('Revoked name lookup was not rejected')), 3000);
      friends.ws.addEventListener('close', event => { clearTimeout(timer); resolve(event); }, { once: true });
    });
    friends.ws.send(JSON.stringify({ type: 'cosmetics_get', id: nextId().padStart(32, '0'), names: ['Alice'] }));
    assert.equal((await revoked).code, 4005, 'Name lookup cannot bypass revoked account authorization');
    const room = await mf.unsafeGetDurableObjectStorage('strict-cosmetics-check', 'RelayRoom', { name: 'friends' });
    assert.equal((await room.exec('SELECT display_name FROM cosmetics_public WHERE uuid=?', id))[0].display_name, 'Aster');
    assert.ok(edits.length >= 9);
    console.log('Strict cosmetics checks passed: canonical configuration, deferred private responses, real account proofs, appearance events, ownership, replay, expiry, license revocation and room isolation.');
  } finally { for (const ws of sockets) { try { ws.close(); } catch {} } await mf.dispose(); }
}
