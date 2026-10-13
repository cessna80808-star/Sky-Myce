import assert from 'node:assert/strict';
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { randomBytes, randomUUID } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Real HTTP/SQLite request revocation, with synthetic identities and no external OAuth calls. */
export async function checkCommunityCancellation(script, proof) {
  const admin = '94000000000000001', guild = '94000000000000002', first = '94000000000000003', second = '94000000000000004';
  const options = convertV4MiniflareOptions({ name: 'community-cancel-check', modules: true, script, compatibilityDate: '2026-09-27',
    bindings: { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'production', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: admin,
      SKYMYCE_PUBLIC_URL: 'https://cancel.test', DISCORD_OAUTH_CLIENT_ID: '94000000000000005', DISCORD_OAUTH_CLIENT_SECRET: 'synthetic-test-secret',
      DISCORD_OAUTH_REDIRECT_URI: 'https://cancel.test/community/oauth/callback' },
    durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: () => { throw Error('Cancellation checks must never call external OAuth or live services'); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  configureTrafficLimits(options);
  const mf = new Miniflare(options);
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    let sequence = 94000000000000100n;
    const actor = (who = admin) => ({ id: who, guildId: guild, roles: [], interactionId: String(sequence++) });
    const secret = () => randomBytes(32).toString('base64url');
    const post = async (path, body) => {
      const response = await mf.dispatchFetch(`https://cancel.test${path}`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': String(sequence++) }, body: JSON.stringify(body) });
      return { status: response.status, body: await response.json() };
    };
    const challenge = async purpose => {
      const result = await post('/community/challenge', { purpose, clientNonce: secret() });
      assert.equal(result.body.ok, true); return result.body;
    };
    const start = async (name = 'Alice') => {
      const c = await challenge('connect'), pollSecret = secret();
      const result = await post('/community/connect/start', { challengeId: c.challengeId, minecraftProof: proof(name, c.nonce), pollSecret, modVersion: '0.1.0' });
      assert.equal(result.body.ok, true, result.body.error); return { ...result.body, pollSecret, name };
    };
    const poll = flow => post('/community/connect/poll', { flowId: flow.flowId, pollSecret: flow.pollSecret });
    const cancel = flow => post('/community/connect/cancel', { flowId: flow.flowId, pollSecret: flow.pollSecret });
    const approve = async (flow, who = first) => {
      assert.equal((await store.communityUserCommand(actor(who), 'connect', { code: flow.fallbackCode })).ok, true);
    };
    const connect = async (name, who = first) => {
      const flow = await start(name); await approve(flow, who);
      const result = await poll(flow); assert.equal(result.body.status, 'complete'); return { flow, ...result.body };
    };
    const manage = async (action, target, extra = {}, kind = 'backend') => {
      const who = kind === 'user' ? target : admin;
      const prepared = await store.prepareCommunityManagement(actor(who), kind, action, target, extra);
      assert.equal(prepared.ok, true, JSON.stringify(prepared));
      const result = await store.confirmCommunityManagement(actor(who), kind, prepared.token, true);
      assert.equal(result.ok, true, JSON.stringify(result)); return result;
    };
    const live = person => store.communityGetSharing(person.credentials.accessToken);
    await connect('Alice'); const sibling = await connect('Bob'), unrelated = await connect('Carol', second);
    const sql = await mf.unsafeGetDurableObjectStorage('community-cancel-check', 'AccountStore', { name: 'global' });
    // Independent recovery scenarios span separate rate windows; move only synthetic limiter timestamps.
    // Production limits remain unchanged rather than permitting dozens of connects within one minute.
    const nextRateWindow = () => sql.exec("UPDATE account_limits SET started=? WHERE id LIKE 'community-%'", Date.now() - 60001);

    const pending = await start(), otherPending = await start('Carol');
    for (const body of [{}, { flowId: pending.flowId, pollSecret: null }, { flowId: pending.flowId, pollSecret: secret().slice(1) },
      { flowId: [], pollSecret: secret() }, { flowId: 'not-a-flow', pollSecret: secret() }]) {
      const rejected = await post('/community/connect/cancel', body); assert.equal(rejected.status, 400); assert.equal(rejected.body.error, 'invalid_request');
    }
    for (const body of [{ flowId: pending.flowId, pollSecret: secret() }, { flowId: otherPending.flowId, pollSecret: pending.pollSecret },
      { flowId: randomUUID(), pollSecret: pending.pollSecret }]) {
      const rejected = await post('/community/connect/cancel', body); assert.equal(rejected.status, 400); assert.equal(rejected.body.error, 'connect_expired');
    }
    assert.equal((await poll(pending)).body.status, 'pending'); assert.equal((await poll(otherPending)).body.status, 'pending');

    const cookie = secret(), launch = new URL(pending.browserUrl).searchParams.get('flow');
    const oauth = await store.communityOAuthBegin(launch, cookie); assert.equal(oauth.ok, true);
    assert.equal((await store.communityOAuthClaim(oauth.state, cookie)).ok, true, 'OAuth is already awaiting its external response');
    const cancelled = await cancel(pending); assert.equal(cancelled.status, 200); assert.equal(cancelled.body.status, 'cancelled');
    assert.equal((await cancel(pending)).body.status, 'cancelled', 'A matching unexpired cancellation is idempotent');
    assert.equal((await store.communityOAuthFinish(pending.flowId, { id: second, username: 'SyntheticSecond', displayName: 'Second Test User' })).error, 'connect_expired', 'Cancellation wins even after OAuth claim');
    assert.equal((await mf.dispatchFetch(pending.browserUrl)).status, 400);
    assert.equal((await store.communityUserCommand(actor(second), 'connect', { code: pending.fallbackCode })).error, 'connect_expired');
    assert.equal((await poll(pending)).body.error, 'connect_expired');
    assert.equal((await store.getAccountByUuid('a'.repeat(32))).discordId, first, 'Cancelled requests never reassign the selected identity');
    assert.equal((await poll(otherPending)).body.status, 'pending', 'Another request is preserved');

    const expired = await start(); await sql.exec('UPDATE community_connect_flows SET expires_at=? WHERE flow_id=?', Date.now() - 1, expired.flowId);
    assert.equal((await cancel(expired)).body.error, 'connect_expired');
    const samePlayerOtherPc = await connect('Alice'), issued = await connect('Alice');
    assert.equal((await cancel(issued.flow)).body.status, 'cancelled');
    assert.equal((await live(issued)).error, 'session_expired');
    assert.equal((await live(samePlayerOtherPc)).ok, true, 'Exact-flow cancellation preserves independent sessions, including the same Minecraft account');
    assert.equal((await live(sibling)).ok, true);
    assert.equal((await store.getAccountByUuid('a'.repeat(32))).discordId, first, 'Completed associations are retained');

    const single = await connect('Alice'), singleChallenge = await challenge('disconnect');
    assert.equal((await post('/community/session/disconnect', { challengeId: singleChallenge.challengeId,
      minecraftProof: proof('Alice', singleChallenge.nonce), refreshToken: single.credentials.refreshToken })).body.ok, true);
    assert.equal((await poll(single.flow)).body.error, 'connect_expired');
    assert.equal((await live(samePlayerOtherPc)).ok, true, 'Minecraft-authenticated single-session disconnect does not revoke another computer');

    const disconnected = await start('Bob'); await approve(disconnected);
    const disconnectedPending = await start('Bob');
    await manage('disconnect', first, {}, 'user');
    assert.equal((await poll(disconnected)).body.error, 'connect_expired', 'Completed unpolled requests cannot recreate a session after reviewed disconnect');
    assert.equal((await poll(disconnectedPending)).body.error, 'connect_expired', 'Owned pending requests are cancelled with account disconnect');
    assert.equal((await store.communityUserCommand(actor(first), 'connect', { code: disconnectedPending.fallbackCode })).error, 'connect_expired');
    assert.equal((await live(unrelated)).ok, true); assert.equal((await poll(otherPending)).body.status, 'pending');

    await nextRateWindow();
    const kicked = await start('Alice'); await approve(kicked);
    const kickedPending = await start('Alice');
    await manage('session_kick', first);
    assert.equal((await poll(kicked)).body.error, 'connect_expired'); assert.equal((await poll(kickedPending)).body.error, 'connect_expired');
    assert.equal((await live(unrelated)).ok, true); assert.equal((await poll(otherPending)).body.status, 'pending');
    assert.equal((await live(await connect('Alice'))).ok, true, 'Session kick is revocation, not a permanent reconnect restriction');

    const unlinked = await start('Bob'); await approve(unlinked); const unlinkedPending = await start('Bob');
    await manage('unlink', first, { uuid: 'b'.repeat(32) }, 'user');
    assert.equal((await poll(unlinked)).body.error, 'connect_expired'); assert.equal((await poll(unlinkedPending)).body.error, 'connect_expired');
    assert.equal((await live(await connect('Bob', second))).ok, true, 'A fresh proved reconnect remains available after unlink');

    await nextRateWindow();
    const blocked = await start('Alice'); await approve(blocked); const anonymous = await start('Alice');
    await manage('blacklist_add', first, { kind: 'discord', identity: first });
    await manage('blacklist_remove', first, { kind: 'discord', identity: first });
    assert.equal((await poll(blocked)).body.error, 'connect_expired', 'Removing a blacklist does not resurrect an old completed flow');
    assert.equal((await poll(anonymous)).body.status, 'pending', 'Discord blacklist does not inherit onto an unknown future Discord identity');
    const mcBlocked = await start('Alice'); await approve(mcBlocked);
    await manage('blacklist_add', 'a'.repeat(32), { kind: 'minecraft', identity: 'a'.repeat(32) });
    await manage('blacklist_remove', 'a'.repeat(32), { kind: 'minecraft', identity: 'a'.repeat(32) });
    assert.equal((await poll(mcBlocked)).body.error, 'connect_expired'); assert.equal((await poll(anonymous)).body.error, 'connect_expired');
    assert.equal((await live(unrelated)).ok, true);
    console.log('Community cancellation checks passed: strict secret/flow binding, expiry/replay, claimed OAuth race, exact-session revocation, reviewed disconnect/kick/unlink/blacklist invalidation and independent identity preservation.');
  } finally { await mf.dispose(); }
}
