import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Synthetic HTTP/Mojang/Discord lifecycle: no live accounts, bot messages, operator secrets or deployments. */
export async function checkCommunity(script, proof) {
  const adminId = '90000000000000001', guild = '90000000000000002', first = '90000000000000003', second = '90000000000000004';
  const bindings = { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'production', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: adminId,
    SKYMYCE_PUBLIC_URL: 'https://community.test', DISCORD_OAUTH_CLIENT_ID: '90000000000000005', DISCORD_OAUTH_CLIENT_SECRET: 'synthetic-test-secret',
    DISCORD_OAUTH_REDIRECT_URI: 'https://community.test/community/oauth/callback' };
  let oauthCalls = 0;
  const options = convertV4MiniflareOptions({ name: 'community-check', modules: true, script, compatibilityDate: '2026-09-27', bindings,
    durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: request => {
      oauthCalls++;
      if (request.url === 'https://discord.com/api/v10/oauth2/token') return Response.json({ access_token: 'synthetic-discord-token', token_type: 'Bearer', scope: 'identify' });
      if (request.url === 'https://discord.com/api/v10/users/@me') return Response.json({ id: second, username: 'SyntheticSecond', global_name: 'Second Test User' });
      throw Error('Community checks must not call live services');
    } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  options.unsafeInspectDurableObjects = true;
  const mf = new Miniflare(options), sockets = [];
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    let sql, interaction = 91000000000000000n, sequence = 0;
    const actor = (who = adminId, changes = {}) => ({ id: who, guildId: guild, roles: [], interactionId: String(interaction++), ...changes });
    const post = async (path, body, bearer) => {
      const response = await mf.dispatchFetch(`https://community.test${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `community-${sequence++}`, ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) }, body: JSON.stringify(body) });
      return { status: response.status, body: await response.json() };
    };
    // CommunityClient.communityNonce(): 32 secure random bytes, unpadded base64url (43 characters).
    const clientNonce = () => randomBytes(32).toString('base64url');
    const challenge = async purpose => { const result = await post('/community/challenge', { purpose, clientNonce: clientNonce() }); assert.equal(result.body.ok, true); return result.body; };
    const start = async (name = 'Alice', device) => {
      const c = await challenge('connect'), pollSecret = randomBytes(32).toString('base64url');
      const result = await post('/community/connect/start', { challengeId: c.challengeId, minecraftProof: proof(name, c.nonce), pollSecret, device, modVersion: '0.1.0' });
      assert.equal(result.body.ok, true, JSON.stringify(result.body)); return { ...result.body, pollSecret, name };
    };
    const poll = flow => post('/community/connect/poll', { flowId: flow.flowId, pollSecret: flow.pollSecret });
    const connect = async (name, who, device) => {
      const flow = await start(name, device);
      assert.equal((await poll(flow)).body.status, 'pending');
      const accepted = await store.communityUserCommand(actor(who), 'connect', { code: flow.fallbackCode }); assert.equal(accepted.ok, true, JSON.stringify(accepted));
      assert.equal((await store.communityUserCommand(actor(who), 'connect', { code: flow.fallbackCode })).error, 'connect_expired');
      const result = await poll(flow); assert.equal(result.body.status, 'complete', JSON.stringify(result.body));
      assert.deepEqual((await poll(flow)).body.credentials, result.body.credentials, 'Polling delivery is idempotent until tokens rotate');
      return result.body;
    };
    const managed = async (action, target, changes = {}, who = adminId) => {
      const prepared = await store.prepareCommunityManagement(actor(who), 'backend', action, target, changes); assert.equal(prepared.ok, true, JSON.stringify(prepared));
      return store.confirmCommunityManagement(actor(who), 'backend', prepared.token, true);
    };
    const socket = async (name, credential) => {
      const response = await mf.dispatchFetch('https://community.test/websocket?room=friends', { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': `socket-${sequence++}` } });
      assert.equal(response.status, 101); const ws = response.webSocket; sockets.push(ws);
      const queue = [], waits = []; ws.addEventListener('message', event => { const data = event.data === 'pong' ? 'pong' : JSON.parse(event.data); const next = waits.shift(); if (next) next(data); else queue.push(data); });
      const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => { const timer = setTimeout(() => reject(Error('Community socket timeout')), 5000); waits.push(data => { clearTimeout(timer); resolve(data); }); });
      ws.accept(); const c = await next(); ws.send(JSON.stringify({ ...proof(name, c.serverId), communityToken: credential.accessToken, modVersion: '0.1.0', cosmetics: true }));
      assert.equal((await next()).type, 'ready'); return { ws, next };
    };
    for (const purpose of ['connect', 'refresh', 'disconnect']) {
      const sent = clientNonce(), accepted = await post('/community/challenge', { purpose, clientNonce: sent });
      assert.equal(accepted.status, 200, `The mod's actual nonce format must work for ${purpose}`);
      assert.equal(accepted.body.ok, true); assert.match(accepted.body.nonce, /^[A-Za-z0-9_-]{43}$/);
      assert.notEqual(accepted.body.nonce, sent, 'Minecraft proof still signs an independently generated server challenge');
    }
    for (const value of [undefined, null, false, 123, {}, [], '', randomUUID(), 'a'.repeat(42), 'a'.repeat(44),
      `${'a'.repeat(42)}+`, `${'a'.repeat(42)}/`, `${'a'.repeat(42)}=`, `${'a'.repeat(42)} `, `${'a'.repeat(43)}\n`]) {
      const rejected = await post('/community/challenge', { purpose: 'connect', clientNonce: value });
      assert.equal(rejected.status, 400); assert.equal(rejected.body.error, 'invalid_request', 'Malformed or differently shaped client nonces fail closed');
    }
    assert.equal((await post('/community/challenge', { purpose: 'renew', clientNonce: clientNonce() })).body.error, 'invalid_request');
    const physical = { identifier: '1'.repeat(64), source: 'windows-smbios', platform: 'windows' };
    const alice = await connect('Alice', first, physical);
    sql = await mf.unsafeGetDurableObjectStorage('community-check', 'AccountStore', { name: 'global' });
    assert.equal((await sql.exec('SELECT license_status,device_hash FROM sky_accounts WHERE discord_id=?', first))[0].license_status, 'inactive', 'Community identity never grants or requires old licenses');
    assert.equal(alice.identity.uuid, 'a'.repeat(32)); assert.match(alice.credentials.refreshToken, /^[A-Za-z0-9_-]{43}$/);
    assert.ok(!JSON.stringify(await sql.exec('SELECT * FROM community_sessions')).includes(alice.credentials.refreshToken), 'Only token digests are stored');
    const aliceOtherPc = await connect('Alice', first, { identifier: '2'.repeat(64), source: 'linux-dmi', platform: 'linux' });
    const bob = await connect('Bob', first, undefined);
    assert.equal(bob.identity.uuids.length, 2, 'One Discord identity owns multiple Minecraft accounts');
    const carol = await connect('Carol', second, physical);
    assert.equal((await store.communityRead(actor(), 'devices', first)).devices.find(row => row.identifier === physical.identifier).relatedAccounts.length, 2, 'Shared hardware is report metadata only');
    assert.equal((await store.communityRead(actor(first), 'devices', first)).error, 'admin_required');
    const a1 = await socket('Alice', alice.credentials), a2 = await socket('Alice', aliceOtherPc.credentials);
    a1.ws.send('ping'); assert.equal(await a1.next(), 'pong'); a2.ws.send('ping'); assert.equal(await a2.next(), 'pong');
    const policy = await mf.dispatchFetch('https://community.test/community/sharing', { headers: { Authorization: `Bearer ${alice.credentials.accessToken}` } });
    const initialPolicy = await policy.json(); assert.equal(initialPolicy.policy.enabled, true); assert.equal(initialPolicy.policy.dungeons, true);
    const wrongOwner = await post('/community/sharing', { revision: 0, policy: { ...initialPolicy.policy, revision: undefined, updatedAt: undefined, notBefore: undefined }, expectedAccountId: randomUUID(), expectedUuid: alice.identity.uuid }, alice.credentials.accessToken);
    assert.equal(wrongOwner.body.error, 'session_identity_changed');
    const plain = { version: 1, enabled: true, dungeons: false, economy: false, rng: false, activity: true, leaderboards: false, publicName: false };
    const disabled = await post('/community/sharing', { revision: 0, policy: plain, expectedAccountId: alice.identity.accountId, expectedUuid: alice.identity.uuid }, alice.credentials.accessToken);
    assert.equal(disabled.body.ok, true); assert.equal(disabled.body.affectedUuids.length, 2);
    assert.equal((await store.checkSharing('b'.repeat(32), 'rng')).allowed, false, 'Preferences cover all linked UUIDs');
    a1.ws.send(JSON.stringify({ type: 'stats_get', id: randomBytes(16).toString('hex'), name: 'Bob', uuid: 'b'.repeat(32) }));
    assert.equal((await a1.next()).error, 'sharing_disabled');
    a1.ws.send(JSON.stringify({ type: 'cosmetics_get', id: randomBytes(16).toString('hex'), uuids: ['a'.repeat(32)] }));
    assert.equal((await a1.next()).records[0].uuid, 'a'.repeat(32), 'Statistics opt-out leaves identity/cosmetics connected');
    const refreshChallenge = await challenge('refresh');
    const rotated = await post('/community/session/refresh', { challengeId: refreshChallenge.challengeId, minecraftProof: proof('Alice', refreshChallenge.nonce), refreshToken: alice.credentials.refreshToken, device: { identifier: 'bad' }, modVersion: '99.0.0' });
    assert.equal(rotated.body.ok, true); assert.notEqual(rotated.body.credentials.refreshToken, alice.credentials.refreshToken);
    const replay = await challenge('refresh'); assert.equal((await post('/community/session/refresh', { challengeId: replay.challengeId, minecraftProof: proof('Alice', replay.nonce), refreshToken: alice.credentials.refreshToken })).body.error, 'session_expired');
    assert.equal((await store.communityManage(actor(first, { roles: ['administrator'] }), 'blacklist_add', first, { kind: 'discord', identity: first })).error, 'admin_required');
    assert.equal((await store.communityManage(actor(), 'blacklist_add', first, { kind: 'discord', identity: first })).error, 'confirmation_required');
    const staged = await store.prepareCommunityManagement(actor(), 'backend', 'blacklist_add', first, { kind: 'discord', identity: first, reason: 'Synthetic moderation' });
    assert.equal((await store.confirmCommunityManagement(actor(first), 'backend', staged.token, true)).error, 'confirmation_expired');
    assert.equal((await store.confirmCommunityManagement(actor(), 'backend', staged.token, false)).ok, true);
    assert.equal((await store.confirmCommunityManagement(actor(), 'backend', staged.token, true)).error, 'confirmation_expired');
    const blocked = await managed('blacklist_add', first, { kind: 'discord', identity: first, reason: 'Synthetic moderation' }); assert.equal(blocked.ok, true);
    assert.equal((await store.checkCommunitySession({ sessionId: rotated.body.credentials.sessionId, accountId: alice.identity.accountId, uuid: alice.identity.uuid, generation: 0 })).ok, false);
    assert.equal((await store.canUseIrc(first)).error, 'identity_blacklisted');
    assert.equal((await store.canUseIrc(second)).ok, true, 'A shared device does not spread an identity blacklist');
    assert.equal((await managed('blacklist_remove', first, { kind: 'discord', identity: first })).ok, true);
    const oauth = await start('Alice', physical);
    const browser = await mf.dispatchFetch(oauth.browserUrl); assert.equal(browser.status, 200); assert.match(await browser.text(), /Alice/);
    const launch = new URL(oauth.browserUrl).searchParams.get('flow');
    const redirect = await mf.dispatchFetch(`https://community.test/community/oauth/authorize?flow=${launch}`, { redirect: 'manual' }); assert.equal(redirect.status, 302);
    const state = new URL(redirect.headers.get('Location')).searchParams.get('state'), cookie = redirect.headers.get('Set-Cookie').split(';')[0];
    const callbackUrl = `https://community.test/community/oauth/callback?code=synthetic-code&state=${state}`;
    assert.equal((await mf.dispatchFetch(callbackUrl)).status, 400); assert.equal(oauthCalls, 0, 'Unbound OAuth state never reaches token exchange');
    const callback = await mf.dispatchFetch(callbackUrl, { headers: { Cookie: cookie } }); assert.equal(callback.status, 200); assert.equal(oauthCalls, 2);
    assert.equal((await mf.dispatchFetch(callbackUrl, { headers: { Cookie: cookie } })).status, 400); assert.equal(oauthCalls, 2, 'OAuth state is one-time');
    const moved = (await poll(oauth)).body; assert.equal(moved.identity.discordId, second); assert.equal(moved.identity.uuids.length, 2);
    assert.equal((await store.getAccountByDiscordId(first)).uuids[0].uuid, 'b'.repeat(32), 'Reconnect moves just proved UUID');
    assert.equal((await store.checkSharing('a'.repeat(32), 'rng')).allowed, false, 'Reconnect preserves explicit old opt-outs');
    assert.equal((await store.communityRead(actor(), 'devices', second)).ok, true);
    assert.equal((await post('/auth/challenge', {})).status, 410, 'Custom loader authorization is retired');
    assert.equal((await mf.dispatchFetch('https://community.test/discord/loader')).status, 410);
    assert.equal((await store.communityRead(actor(), 'sessions', second, { page: 2, limit: 1 })).sessions.length, 1);
    const detachedUuid = 'a'.repeat(32), beforeUnlink = await store.checkSharing(detachedUuid, 'rng');
    const unlink = await store.prepareCommunityManagement(actor(second), 'user', 'unlink', second, { uuid: detachedUuid });
    assert.equal(unlink.ok, true, JSON.stringify(unlink));
    assert.equal((await store.confirmCommunityManagement(actor(second), 'user', unlink.token, true)).ok, true);
    assert.ok(!(await store.getAccountByDiscordId(second)).uuids.some(player => player.uuid === detachedUuid));
    const detached = await store.checkSharing(detachedUuid, 'rng');
    assert.equal(detached.allowed, false, 'Unlink preserves the detached Minecraft identity opt-out');
    assert.ok(detached.notBefore >= beforeUnlink.notBefore, 'Unlink never lowers suppression cutoffs');
    assert.equal((await store.checkSharing('c'.repeat(32), 'rng')).allowed, false, 'Unrelated source associations retain account preferences');
    const rebound = await connect('Alice', first);
    assert.equal(rebound.identity.uuid, detachedUuid);
    assert.equal((await store.checkSharing(detachedUuid, 'rng')).allowed, false, 'A later proof-based reconnect inherits detached consent');
    console.log('Community checks passed: ordinary linking, OAuth state/cookie/replay, persistent rotating sessions, multiple PCs/Minecraft identities, admin-only device reports, explicit blacklist, opt-outs and legitimate reconnect.');
  } finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }
}
