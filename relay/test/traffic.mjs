import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { configureTrafficLimits } from './traffic-fixture.mjs';

export async function checkTraffic(script, proof) {
  const pure = await build({ entryPoints: ['src/traffic.ts'], bundle: true, format: 'esm', platform: 'neutral', write: false });
  const { takeTrafficCredit } = await import(`data:text/javascript;base64,${Buffer.from(pure.outputFiles[0].text).toString('base64')}`);
  const credit = { credits: 2, updated: 1000 };
  assert.equal(takeTrafficCredit(credit, 2, 100, 1000), true);
  assert.equal(takeTrafficCredit(credit, 2, 100, 1000), true);
  assert.equal(takeTrafficCredit(credit, 2, 100, 1099), false);
  assert.equal(takeTrafficCredit(credit, 2, 100, 1100), true);
  assert.equal(takeTrafficCredit(credit, 2, 100, 1000), false, 'Clock regression cannot mint credits');
  assert.equal(takeTrafficCredit(credit, 2, 100, 999999), true);
  assert.equal(credit.credits, 1, 'Long idle periods cannot exceed the burst');

  const persistent = await build({ stdin: { contents: `import { DurableObject } from 'cloudflare:workers';
    import { CommunityTraffic } from './src/traffic';
    export class BudgetFixture extends DurableObject {
      run(account,kind,now) { return new CommunityTraffic(this.ctx.storage.sql,()=>{}).consume(account,kind,now); }
      global(now) { const traffic=new CommunityTraffic(this.ctx.storage.sql,()=>{}); let accepted=0;
        for(let i=0;i<2200;i++) { if(traffic.consume('actor-'+i,'request',now).ok) accepted++; } return accepted; }
    }
    export default { fetch() { return new Response('fixture'); } };`, resolveDir: process.cwd(), sourcefile: 'traffic-budget-fixture.ts' },
    bundle: true, format: 'esm', platform: 'browser', external: ['cloudflare:workers'], write: false });
  const budgetOptions = convertV4MiniflareOptions({ name: 'traffic-budget-check', modules: true, script: persistent.outputFiles[0].text,
    compatibilityDate: '2026-09-27', durableObjects: { BUDGET: { className: 'BudgetFixture', useSQLite: true } } });
  const budgetRuntime = new Miniflare(budgetOptions);
  try {
    const budgets = await budgetRuntime.getDurableObjectNamespace('BUDGET');
    const user = budgets.getByName('user'), at = Date.now();
    for (let i = 0; i < 120; i++) assert.equal((await user.run('alice', 'request', at)).ok, true);
    assert.equal((await user.run('alice', 'request', at)).error, 'rate_limited', 'Recreating the governor cannot reset persisted credits');
    assert.equal((await user.run('bob', 'request', at)).ok, true);
    assert.equal((await user.run('alice', 'request', at + 250)).ok, true, 'Credits refill at the configured boundary');
    assert.equal(await budgets.getByName('global').global(at), 2048, 'A persistent global cap bounds accepted work even with many accounts');
  } finally { await budgetRuntime.dispose(); }

  const guild = '90000000000000002', admin = '90000000000000001', other = '90000000000000003';
  const fixture = (name, bindings = {}, limits = {}) => {
    const options = convertV4MiniflareOptions({ name, modules: true, script, compatibilityDate: '2026-09-27',
      bindings: { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'production', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: admin, ...bindings },
      durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
      outboundService: () => { throw Error('Traffic checks must not contact live services'); } });
    options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112060', simple: { limit: 1000, period: 60 } };
    options.unsafeInspectDurableObjects = true;
    configureTrafficLimits(options, limits);
    return new Miniflare(options);
  };
  const mf = fixture('traffic-check'), sockets = [];
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    let interaction = 91000000000000000n, sequence = 0;
    const connect = async (name, discord) => {
      const c = await store.communityChallenge({ purpose: 'connect', clientNonce: randomBytes(32).toString('base64url') });
      assert.equal(c.ok, true);
      const pollSecret = randomBytes(32).toString('base64url');
      const flow = await store.communityConnectStart({ challengeId: c.challengeId, minecraftProof: proof(name, c.nonce), pollSecret, modVersion: '2.5.5' });
      assert.equal(flow.ok, true, JSON.stringify(flow));
      assert.equal((await store.communityUserCommand({ id: discord, guildId: guild, roles: [], interactionId: String(interaction++) }, 'connect', { code: flow.fallbackCode })).ok, true);
      const result = await store.communityConnectPoll({ flowId: flow.flowId, pollSecret });
      assert.equal(result.status, 'complete');
      return result;
    };
    const alice = await connect('Alice', admin), bob = await connect('Bob', admin), carol = await connect('Carol', other);
    const context = identity => ({ accountId: identity.identity.accountId, uuid: identity.identity.uuid,
      sessionId: identity.credentials.sessionId, generation: 1 });
    const sql = await mf.unsafeGetDurableObjectStorage('traffic-check', 'AccountStore', { name: 'global' });
    const aliceContext = { ...context(alice), generation: (await sql.exec('SELECT generation FROM community_sessions WHERE session_id=?', alice.credentials.sessionId))[0].generation };
    const bobContext = { ...context(bob), generation: aliceContext.generation };
    const carolContext = { ...context(carol), generation: (await sql.exec('SELECT generation FROM community_sessions WHERE session_id=?', carol.credentials.sessionId))[0].generation };
    const socket = async (name, identity, room = 'friends', expectReady = true) => {
      const response = await mf.dispatchFetch(`https://traffic.test/websocket?room=${room}`, { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': `socket-${sequence++}` } });
      assert.equal(response.status, 101);
      const ws = response.webSocket; sockets.push(ws);
      const queue = [], waits = [];
      ws.addEventListener('message', event => { const data = JSON.parse(event.data); const next = waits.shift(); if (next) next(data); else queue.push(data); });
      const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(Error('Traffic socket timeout')), 5000); waits.push(data => { clearTimeout(timer); resolve(data); });
      });
      const closed = new Promise(resolve => ws.addEventListener('close', event => resolve(event)));
      ws.accept(); const challenge = await next();
      ws.send(JSON.stringify({ ...proof(name, challenge.serverId), communityToken: identity.credentials.accessToken, cosmetics: true, cosmeticsVersion: 5 }));
      if (expectReady) assert.equal((await next()).type, 'ready');
      return { ws, next, closed };
    };
    const a = await socket('Alice', alice), b = await socket('Bob', bob, 'testing');
    a.ws.send(JSON.stringify({ type: 'cosmetics_get', id: '1'.repeat(32), uuid: alice.identity.uuid }));
    assert.equal((await a.next()).type, 'cosmetics_result');
    await mf.unsafeEvictDurableObject('traffic-check', 'RelayRoom', { name: 'friends', webSockets: 'hibernate' });
    a.ws.send(JSON.stringify({ type: 'cosmetics_get', id: '2'.repeat(32), uuid: alice.identity.uuid }));
    assert.equal((await a.next()).type, 'cosmetics_result', 'Normal requests survive hibernation');
    await sql.exec('UPDATE community_traffic SET credits=0,updated=? WHERE account_id=? AND kind=?', Date.now() + 60_000, aliceContext.accountId, 'request');
    assert.equal((await store.consumeCommunitySessionRequest(bobContext)).error, 'rate_limited', 'Linked UUIDs and rooms share an account budget');
    await mf.unsafeEvictDurableObject('traffic-check', 'RelayRoom', { name: 'testing', webSockets: 'hibernate' });
    assert.equal((await store.consumeCommunitySessionRequest(aliceContext)).error, 'rate_limited', 'Room hibernation cannot reset the shared quota');
    const reconnect = await socket('Alice', alice);
    reconnect.ws.send(JSON.stringify({ type: 'cosmetics_get', id: '3'.repeat(32), uuid: alice.identity.uuid }));
    assert.equal((await reconnect.closed).code, 1013, 'A fresh IP and connection cannot reset the account quota');
    assert.equal((await store.checkCommunitySession(bobContext)).error, 'traffic_cooldown');
    const denied = await socket('Bob', bob, 'testing', false);
    assert.equal((await denied.closed).code, 1013, 'Cooldowns apply to all linked sessions');
    assert.equal((await store.communitySessionForToken(alice.credentials.accessToken)).error, 'traffic_cooldown', 'HTTP cannot bypass a WebSocket suspension');
    assert.equal((await store.consumeCommunitySessionRequest(carolContext)).ok, true, 'An unrelated account remains usable');
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='traffic_cooldown' AND account_id=?", aliceContext.accountId))[0].n, 1, 'One audit event per cooldown');
    assert.equal((await store.reportCommunityTrafficViolation({ ...carolContext, sessionId: randomUUID(), accountId: aliceContext.accountId })).error, 'session_expired', 'A forged identity cannot suspend another account');
    await sql.exec('UPDATE community_traffic_abuse SET blocked_until=? WHERE account_id=?', Date.now() - 1, aliceContext.accountId);
    await sql.exec('UPDATE community_traffic SET credits=120,updated=? WHERE account_id=? AND kind=?', Date.now(), aliceContext.accountId, 'request');
    assert.equal((await store.consumeCommunitySessionRequest(aliceContext)).ok, true, 'Cooldown expires without deleting credentials or user data');

    const flood = await socket('Carol', carol);
    for (let i = 0; i < 150; i++) flood.ws.send(JSON.stringify({ type: 'cosmetics_get', id: i.toString(16).padStart(32, '0'), uuid: carol.identity.uuid }));
    assert.equal((await flood.closed).code, 1013, 'Rapid packets are disconnected before unlimited auth lookups');
    const remaining = (await sql.exec("SELECT credits FROM community_traffic WHERE account_id=? AND kind='request'", carolContext.accountId))[0].credits;
    assert.ok(remaining > 30, `The early gate must leave most of the 150 auth checks unexecuted (credits=${remaining})`);
  } finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }

  const http = fixture('traffic-http-check', {}, { requests: 4, downloads: 2, service: 20 });
  try {
    for (let i = 0; i < 4; i++) assert.equal((await http.dispatchFetch(`https://traffic.test/unknown-${i}?random=${i}`, { headers: { 'CF-Connecting-IP': 'one-ip' } })).status, 404);
    const denied = await http.dispatchFetch('https://traffic.test/health', { headers: { 'CF-Connecting-IP': 'one-ip' } });
    assert.equal(denied.status, 429); assert.ok(denied.headers.has('Retry-After'));
    assert.equal((await http.dispatchFetch('https://traffic.test/health', { headers: { 'CF-Connecting-IP': 'another-ip' } })).status, 200);
    for (let i = 0; i < 2; i++) assert.equal((await http.dispatchFetch(`https://traffic.test/community/artifact?version=2.5.5&random=${i}`, { headers: { 'CF-Connecting-IP': 'downloads' } })).status, 503);
    assert.equal((await http.dispatchFetch('https://traffic.test/community/artifact?version=2.5.5&random=new', { headers: { 'CF-Connecting-IP': 'downloads' } })).status, 429, 'Download queries cannot reset the budget');
  } finally { await http.dispose(); }
  const service = fixture('traffic-service-check', {}, { service: 2 });
  try {
    for (let i = 0; i < 2; i++) assert.equal((await service.dispatchFetch('https://traffic.test/community/unknown', { headers: { 'CF-Connecting-IP': `ip-${i}` } })).status, 404);
    const rejected = await service.dispatchFetch('https://traffic.test/community/unknown', { headers: { 'CF-Connecting-IP': 'ip-new' } });
    assert.equal(rejected.status, 503); assert.equal((await rejected.json()).error, 'service_busy');
  } finally { await service.dispose(); }
  const paused = fixture('traffic-paused-check', { COMMUNITY_SERVICES_ENABLED: 'false' });
  try {
    assert.equal((await paused.dispatchFetch('https://traffic.test/websocket?room=friends', { headers: { Upgrade: 'websocket' } })).status, 503);
    assert.equal((await paused.dispatchFetch('https://traffic.test/community/artifact?version=2.5.5')).status, 503);
    assert.equal((await paused.dispatchFetch('https://traffic.test/health')).status, 200);
  } finally { await paused.dispose(); }
  console.log('Traffic checks passed: early flood rejection, persistent account quotas across rooms/UUIDs/reconnects/restarts, cooldown isolation/expiry, HTTP/download/service budgets and emergency pause.');
}
