import assert from 'node:assert/strict';
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Synthetic HTTP/Mojang/Discord lifecycle: no live accounts, bot messages, operator secrets or deployments. */
export async function checkCommunity(script, proof) {
  const adminId = '90000000000000001', guild = '90000000000000002', first = '90000000000000003', second = '90000000000000004';
  const oldVersion = '2.5.11', newVersion = '2.5.13', signingKey = generateKeyPairSync('ed25519');
  const bindings = { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'production', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: adminId,
    SKYMYCE_PUBLIC_URL: 'https://community.test', DISCORD_OAUTH_CLIENT_ID: '90000000000000005', DISCORD_OAUTH_CLIENT_SECRET: 'synthetic-test-secret',
    DISCORD_OAUTH_REDIRECT_URI: 'https://community.test/community/oauth/callback', SKY_MYCE_VERSION: '2.4.31',
    DRM_PUBLIC_KEYS: JSON.stringify({ fixture: signingKey.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') }) };
  let oauthCalls = 0;
  const options = convertV4MiniflareOptions({ name: 'community-check', modules: true, script, compatibilityDate: '2026-09-27', bindings,
    r2Buckets: ['PRIVATE_ARTIFACTS'],
    durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: request => {
      oauthCalls++;
      if (request.url === 'https://discord.com/api/v10/oauth2/token') return Response.json({ access_token: 'synthetic-discord-token', token_type: 'Bearer', scope: 'identify' });
      if (request.url === 'https://discord.com/api/v10/users/@me') return Response.json({ id: second, username: 'SyntheticSecond', global_name: 'Second Test User' });
      throw Error('Community checks must not call live services');
    } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  configureTrafficLimits(options);
  options.unsafeInspectDurableObjects = true;
  const mf = new Miniflare(options), sockets = [];
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const bucket = await mf.getR2Bucket('PRIVATE_ARTIFACTS');
    const publish = async version => {
      const artifact = `releases/Sky-Myce-${version}.jar`, bytes = Buffer.from([80, 75, 3, 4]);
      const manifest = { product: 'sky-myce', protocol: 4, installation: 'fabric', minecraft: '26.1.2', version, artifact,
        size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), issuedAt: Date.now(), expiresAt: Date.now() + 86400000 };
      const payload = Buffer.from(JSON.stringify(manifest));
      await bucket.put(artifact, bytes);
      await bucket.put('releases/community-current.json', JSON.stringify({ kid: 'fixture', payload: payload.toString('base64url'),
        signature: sign(null, payload, signingKey.privateKey).toString('base64url') }));
      // Observe the newly selected release after its bounded metadata-cache lifetime.
      await (await mf.getCaches()).default.delete('https://community.test/community/cache/selection?pointer=releases%2Fcommunity-current.json');
      const response = await mf.dispatchFetch('https://community.test/community/release');
      assert.equal(response.status, 200);
      assert.equal(JSON.parse(Buffer.from((await response.json()).payload, 'base64url')).version, version);
    };
    await publish(oldVersion);
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
      const result = await post('/community/connect/start', { challengeId: c.challengeId, minecraftProof: proof(name, c.nonce), pollSecret, device, modVersion: oldVersion });
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
      ws.accept(); const c = await next(); ws.send(JSON.stringify({ ...proof(name, c.serverId), communityToken: credential.accessToken, modVersion: oldVersion, cosmetics: true, cosmeticsVersion: 5 }));
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
    const completion = { expectedAccountId: alice.identity.accountId, expectedUuid: alice.identity.uuid,
      contribution: { id: createHash('sha256').update('run-count/synthetic-run').digest('hex').slice(0, 32), metric: 'dungeon_runs',
        value: 1, observedAt: Date.now(), complete: true, source: 'client-observation', verification: 'unverified' } };
    assert.equal((await post('/community/contributions', completion, alice.credentials.accessToken)).body.ok, true);
    assert.equal((await post('/community/contributions', completion, alice.credentials.accessToken)).body.ok, true, 'Retrying a completed run is idempotent');
    const dungeonRanking = async (metric = 'dungeon_runs') => (await mf.dispatchFetch(`https://community.test/community/leaderboard?category=dungeons&metric=${metric}`,
      { headers: { Authorization: `Bearer ${alice.credentials.accessToken}` } })).json();
    const completedRanking = await dungeonRanking();
    assert.equal(completedRanking.total, 1); assert.equal(completedRanking.entries[0].value, 1); assert.equal(completedRanking.entries[0].sampleSize, 1);
    assert.equal(completedRanking.entries[0].player, 'Anonymous player', 'Recorded runs respect current public-name consent');
    const timing = { ...completion, contribution: { ...completion.contribution,
      id: createHash('sha256').update('run-time/synthetic-run').digest('hex').slice(0, 32), metric: 'dungeon_run_time', value: 300001 } };
    assert.equal((await post('/community/contributions', timing, alice.credentials.accessToken)).body.ok, true);
    assert.equal((await post('/community/contributions', timing, alice.credentials.accessToken)).body.ok, true);
    for (const value of [0, -1, 1.5, 86400001, null])
      assert.equal((await post('/community/contributions', { ...timing, contribution: { ...timing.contribution, value } }, alice.credentials.accessToken)).body.error, 'invalid_contribution');
    const timedRanking = await dungeonRanking('dungeon_run_time');
    assert.equal(timedRanking.entries[0].value, 300001); assert.equal(timedRanking.entries[0].sampleSize, 1);
    const untimed = { ...completion, contribution: { ...completion.contribution,
      id: createHash('sha256').update('run-count/synthetic-untimed-run').digest('hex').slice(0, 32) } };
    assert.equal((await post('/community/contributions', untimed, alice.credentials.accessToken)).body.ok, true);
    assert.equal((await dungeonRanking()).entries[0].value, 2, 'Unknown start times still count completed runs');
    assert.equal((await dungeonRanking('dungeon_run_time')).entries[0].sampleSize, 1, 'Untimed completions do not enter the average denominator');
    assert.equal((await post('/community/contributions', { ...completion, expectedUuid: bob.identity.uuid }, alice.credentials.accessToken)).body.error,
      'session_identity_changed', 'A session cannot submit another linked Minecraft identity\'s run');
    for (const [metric, value] of [['dungeon_profit', 12500], ['networth', 1200000]]) {
      const measurement = { ...completion, contribution: { ...completion.contribution,
        id: createHash('sha256').update(metric + '/synthetic-measurement').digest('hex').slice(0, 32), metric, value, verification: 'estimated' } };
      assert.equal((await post('/community/contributions', measurement, alice.credentials.accessToken)).body.ok, true);
    }
    const sharedBeforeRenewal = await sql.exec('SELECT * FROM community_contributions WHERE uuid=? ORDER BY id', alice.identity.uuid);
    const renewalChallenge = await challenge('refresh');
    const renewed = await post('/community/session/refresh', { challengeId: renewalChallenge.challengeId,
      minecraftProof: proof('Alice', renewalChallenge.nonce), refreshToken: alice.credentials.refreshToken, modVersion: oldVersion });
    assert.equal(renewed.body.ok, true);
    assert.notEqual(renewed.body.credentials.accessToken, alice.credentials.accessToken);
    alice.credentials = renewed.body.credentials;
    assert.deepEqual(await sql.exec('SELECT * FROM community_contributions WHERE uuid=? ORDER BY id', alice.identity.uuid), sharedBeforeRenewal,
      'Automatic token renewal preserves every run-count, duration, profit and wealth observation and its dedup ID');
    assert.equal((await dungeonRanking()).entries[0].value, 2);
    assert.equal((await dungeonRanking('dungeon_run_time')).entries[0].value, 300001);
    assert.equal((await dungeonRanking('dungeon_profit')).entries[0].value, 12500);
    const renewedWealth = await mf.dispatchFetch('https://community.test/community/leaderboard?category=economy&metric=networth',
      { headers: { Authorization: `Bearer ${alice.credentials.accessToken}` } });
    assert.equal((await renewedWealth.json()).entries[0].value, 1200000);
    await connect('Alice', first, physical);
    assert.deepEqual(await sql.exec('SELECT * FROM community_contributions WHERE uuid=? ORDER BY id', alice.identity.uuid), sharedBeforeRenewal,
      'Reconnecting the same Minecraft/Discord association preserves previously shared observations');
    const renewedPolicy = await mf.dispatchFetch('https://community.test/community/sharing', { headers: { Authorization: `Bearer ${alice.credentials.accessToken}` } });
    assert.deepEqual((await renewedPolicy.json()).policy, initialPolicy.policy, 'Same-owner refresh and reconnect preserve consent revision and historical cutoffs');
    const savedCosmetic = await post('/community/cosmetics', { expectedAccountId: alice.identity.accountId, expectedUuid: alice.identity.uuid,
      revision: 0, profile: { name: { text: 'Old client', color: 'gold' }, scaleX: 1.25, scaleY: 1.5, scaleZ: 0.75,
        nametagScale: 0.8, capePreset: 'forest', capeAsset: null } }, alice.credentials.accessToken);
    assert.equal(savedCosmetic.body.ok, true, JSON.stringify(savedCosmetic.body));
    const cosmetic = async client => {
      const id = randomBytes(16).toString('hex');
      client.ws.send(JSON.stringify({ type: 'cosmetics_get', id, uuids: [alice.identity.uuid] }));
      const result = await client.next(); assert.equal(result.id, id); return result.records[0];
    };
    const beforeRelease = await cosmetic(a1);
    assert.deepEqual(beforeRelease, savedCosmetic.body.profile, 'The older client receives its saved custom profile before publication');
    const sessionBefore = await sql.exec('SELECT session_id,uuid,generation,created_at,revoked_at,access_expires_at,refresh_expires_at,mod_version FROM community_sessions WHERE session_id=?', alice.credentials.sessionId);
    assert.equal(sessionBefore[0].mod_version, oldVersion);
    await publish(newVersion);
    assert.deepEqual(await sql.exec('SELECT session_id,uuid,generation,created_at,revoked_at,access_expires_at,refresh_expires_at,mod_version FROM community_sessions WHERE session_id=?', alice.credentials.sessionId),
      sessionBefore, 'Publishing a signed standalone update cannot revoke, expire or relabel the running older session');
    assert.deepEqual(await cosmetic(a1), beforeRelease, 'Existing older sockets retain the exact name, body scales, cape and cosmetic revision');
    a1.ws.send('ping'); assert.equal(await a1.next(), 'pong');
    const olderReconnect = await socket('Alice', alice.credentials);
    assert.deepEqual(await cosmetic(olderReconnect), beforeRelease, 'The same older build can reconnect after the newer release becomes current');
    const retainedPolicy = await mf.dispatchFetch('https://community.test/community/sharing', { headers: { Authorization: `Bearer ${alice.credentials.accessToken}` } });
    assert.deepEqual((await retainedPolicy.json()).policy, initialPolicy.policy, 'Release activation preserves account sharing settings');
    assert.equal((await mf.dispatchFetch(`https://community.test/community/artifact?version=${oldVersion}`)).status, 409,
      'Continuing older sessions does not expose an unselected private release artifact');
    const wrongOwner = await post('/community/sharing', { revision: 0, policy: { ...initialPolicy.policy, revision: undefined, updatedAt: undefined, notBefore: undefined }, expectedAccountId: randomUUID(), expectedUuid: alice.identity.uuid }, alice.credentials.accessToken);
    assert.equal(wrongOwner.body.error, 'session_identity_changed');
    const plain = { version: 1, enabled: true, dungeons: false, economy: false, rng: false, activity: true, leaderboards: false, publicName: false };
    const disabled = await post('/community/sharing', { revision: 0, policy: plain, expectedAccountId: alice.identity.accountId, expectedUuid: alice.identity.uuid }, alice.credentials.accessToken);
    assert.equal(disabled.body.ok, true); assert.equal(disabled.body.affectedUuids.length, 2);
    assert.equal((await dungeonRanking()).total, 0, 'Opt-out removes completed-run contributions from the live ranking');
    assert.equal((await dungeonRanking('dungeon_run_time')).total, 0, 'Dungeon opt-out also removes average-time samples');
    assert.equal((await post('/community/contributions', completion, alice.credentials.accessToken)).body.error, 'sharing_disabled');
    assert.equal((await post('/community/contributions', timing, alice.credentials.accessToken)).body.error, 'sharing_disabled');
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
    console.log('Community checks passed: signed update compatibility for older sessions/cosmetics/preferences, ordinary linking, OAuth state/cookie/replay, persistent rotating sessions, multiple PCs/Minecraft identities, admin-only device reports, explicit blacklist, opt-outs and legitimate reconnect.');
  } finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }
}
