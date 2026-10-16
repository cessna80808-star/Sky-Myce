import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { configureTrafficLimits } from './traffic-fixture.mjs';

const bundled = await build({ entryPoints: ['src/leap-pov.ts'], bundle: true, format: 'esm', platform: 'browser', write: false });
const { LeapPov } = await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'));
const aliceId = 'a'.repeat(32), bobId = 'b'.repeat(32), carolId = 'c'.repeat(32), party = 'd'.repeat(32);
const epoch = 'e'.repeat(32), otherEpoch = 'f'.repeat(32);
let packetSequence = 1;
const id = () => (packetSequence++).toString(16).padStart(32, '0');
const subscription = (changes = {}) => ({ type: 'leap_pov_subscribe', id: id(), epoch, enabled: true,
  server: 'mini123A', floor: 'F7', alpha: false, party, members: [aliceId, bobId], watching: [], ...changes });
const pose = (seq = 0, changes = {}) => ({ type: 'leap_pov_pose', epoch, seq, x: 0, y: 70, z: -25, yaw: 0, pitch: 0, ...changes });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function fixture() {
  let now = Date.now();
  class Socket {
    readyState = WebSocket.OPEN; sent = []; denied = false;
    constructor(uuid, name) { this.state = { uuid, name, accountId: name, discordId: '100000000000000001', room: 'friends',
      communitySessionId: name, connectionId: name, generation: 1, leaseExpiresAt: now + 60_000 }; }
    deserializeAttachment() { return structuredClone(this.state); }
    serializeAttachment(value) { this.state = structuredClone(value); }
    send(raw) { this.sent.push(JSON.parse(raw)); }
    close(code) { this.readyState = WebSocket.CLOSED; this.code = code; }
  }
  const alice = new Socket(aliceId, 'Alice'), bob = new Socket(bobId, 'Bob'), carol = new Socket(carolId, 'Carol');
  let authority = async ws => !ws.denied && ws.readyState === WebSocket.OPEN;
  const manager = new LeapPov({ getWebSockets: () => [alice, bob, carol] }, (...args) => authority(...args), () => now);
  const control = (ws, changes = {}) => {
    const input = subscription(changes), intent = manager.reserve(ws, ws.deserializeAttachment(), input);
    manager.subscribe(ws, ws.deserializeAttachment(), input, intent);
    return ws.sent.at(-1);
  };
  const send = (ws = alice, seq = 0, changes = {}, receivedAt = now) => manager.pose(ws, ws.deserializeAttachment(), pose(seq, changes), receivedAt);
  const frames = ws => ws.sent.filter(value => value.type === 'leap_pov_pose');
  const ready = () => { control(alice); control(bob, { epoch: otherEpoch, watching: [aliceId] }); };
  return { alice, bob, carol, manager, control, send, frames, ready, tick: millis => { now += millis; },
    setAuthority: next => { authority = next; }, now: () => now };
}

{
  const f = fixture(); f.control(f.alice); await f.send();
  assert.equal(f.frames(f.bob).length, 0, 'One-sided opt-in cannot expose a pose');
  f.control(f.bob, { epoch: otherEpoch, watching: [aliceId] });
  f.tick(500); await f.send(f.alice, 1);
  const received = f.frames(f.bob).at(-1);
  assert.equal(received.epoch, otherEpoch); assert.equal(received.sourceEpoch, epoch);
  assert.equal(received.uuid, aliceId); assert.equal(received.name, 'Alice');
  assert.equal(received.x, 0); assert.equal(received.yaw, 0); assert.equal(received.pitch, 0);
  assert.equal(received.expiresAt - received.timestamp, 1500);
  assert.equal(f.frames(f.carol).length, 0);
  assert.ok(!/"(?:x|y|z|yaw|pitch)"\s*:/.test(JSON.stringify(f.alice.state)), 'Attachments contain no coordinates');
  f.control(f.alice);
  assert.equal(f.bob.sent.filter(value => value.type === 'leap_pov_remove').length, 0, 'Ordinary renewal does not flicker');
  f.tick(500); await f.send(f.alice, 1);
  assert.equal(f.frames(f.bob).length, 1, 'Renewal does not reset sequence protection');
  f.control(f.alice, { enabled: false });
  assert.deepEqual(f.bob.sent.at(-1), { type: 'leap_pov_remove', epoch: otherEpoch, uuid: aliceId, sourceEpoch: epoch });
  f.tick(500); await f.send(f.alice, 2); assert.equal(f.frames(f.bob).length, 1);
}
for (const changes of [{ server: 'other' }, { floor: 'M7' }, { alpha: true }, { party: '1'.repeat(32) },
  { members: [bobId, carolId] }, { watching: [] }]) {
  const f = fixture(); f.control(f.alice); f.control(f.bob, { epoch: otherEpoch, watching: [aliceId], ...changes }); await f.send();
  assert.equal(f.frames(f.bob).length, 0, 'Audience must match opt-in, reciprocal roster and exact instance context');
}
for (const changes of [{ x: NaN }, { y: Infinity }, { z: '0' }, { yaw: null }, { pitch: 91 }, { x: 30_000_001 },
  { y: -4097 }, { yaw: -181 }, { seq: -1 }, { seq: '1' }, { seq: Number.MAX_SAFE_INTEGER + 1 },
  { uuid: bobId }, { name: 'Bob' }, { epoch: otherEpoch }]) {
  const f = fixture(); f.ready(); await f.send(f.alice, 0, changes);
  assert.equal(f.frames(f.bob).length, 0, 'Malformed or forged poses are rejected');
}
for (const changes of [{ members: [aliceId, aliceId] }, { members: [bobId, carolId] }, { watching: [aliceId] },
  { watching: [carolId] }, { watching: [bobId, bobId] }, { floor: 'F8' }, { server: 'a/b' }, { alpha: 'false' },
  { members: [aliceId, bobId, carolId, '1'.repeat(32), '2'.repeat(32), '3'.repeat(32)] }, { uuid: aliceId }]) {
  const f = fixture(); f.ready(); assert.equal(f.control(f.alice, changes).error, 'invalid_subscription');
  assert.equal(f.alice.state.leapPov, undefined, 'Invalid replacement revokes old consent');
  assert.equal(f.bob.sent.at(-1).type, 'leap_pov_remove');
}
for (const changes of [{ bridge: true }, { accountId: undefined }, { communitySessionId: undefined }, { connectionId: undefined },
  { generation: undefined }, { leaseExpiresAt: 1 }]) {
  const f = fixture(); Object.assign(f.alice.state, changes); f.control(f.bob, { epoch: otherEpoch, watching: [aliceId] });
  f.control(f.alice); await f.send(); assert.equal(f.frames(f.bob).length, 0);
}
{
  const f = fixture(); f.ready(); await f.send(f.alice, 1); await f.send(f.alice, 2); await f.send(f.alice, 3);
  assert.equal(f.frames(f.bob).length, 2, 'The two-Hz budget permits only a bounded two-sample jitter burst');
  f.tick(500); await f.send(f.alice, 4); assert.equal(f.frames(f.bob).length, 3);
  f.tick(15_000); await f.send(f.alice, 5); assert.equal(f.frames(f.bob).length, 3, 'Expired consent stops delivery');
}
{
  const f = fixture(); f.ready(); await f.send(f.alice, 1);
  for (const [seq, elapsed] of [[2, 500], [3, 499], [4, 501]]) { f.tick(elapsed); await f.send(f.alice, seq); }
  assert.equal(f.frames(f.bob).length, 4, 'A legitimate two-Hz source tolerates normal one-millisecond arrival jitter');
}
{
  const f = fixture(); f.ready(); f.tick(1500); await f.send(f.alice, 0, {}, f.now() - 1500);
  assert.equal(f.frames(f.bob).length, 0, 'TTL starts at arrival before asynchronous authorization');
  const first = subscription(), second = subscription({ enabled: false });
  const old = f.manager.reserve(f.alice, f.alice.deserializeAttachment(), first);
  const latest = f.manager.reserve(f.alice, f.alice.deserializeAttachment(), second);
  f.manager.subscribe(f.alice, f.alice.deserializeAttachment(), second, latest);
  f.manager.subscribe(f.alice, f.alice.deserializeAttachment(), first, old);
  assert.equal(f.alice.state.leapPov, undefined, 'Late enable cannot override a newer disable');
}
for (const mutation of ['sender-disable', 'recipient-disable', 'sender-revoke', 'recipient-revoke', 'sender-close', 'expiry', 'newer-pose']) {
  const f = fixture(); f.ready(); const gate = deferred();
  f.setAuthority(async ws => { if (ws === f.bob) await gate.promise; return !ws.denied && ws.readyState === WebSocket.OPEN; });
  const pending = f.send(f.alice, 1);
  if (mutation === 'sender-disable') f.control(f.alice, { enabled: false });
  if (mutation === 'recipient-disable') f.control(f.bob, { epoch: otherEpoch, enabled: false });
  if (mutation === 'sender-revoke') f.alice.denied = true;
  if (mutation === 'recipient-revoke') f.bob.denied = true;
  if (mutation === 'sender-close') f.alice.close(1000);
  if (mutation === 'expiry') f.tick(1500);
  let newer;
  if (mutation === 'newer-pose') { newer = f.send(f.alice, 2); await f.send(f.alice, 3); }
  gate.resolve(); await pending; await newer;
  assert.equal(f.frames(f.bob).length, 0, `In-flight pose must stop after ${mutation}`);
}
{
  const f = fixture(); f.ready(); f.manager.disconnect(f.alice);
  assert.equal(f.bob.sent.at(-1).type, 'leap_pov_remove', 'Socket close invalidates the former source epoch');
}
{
  const f = fixture(); f.ready(); await f.send(f.alice, 0, { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 });
  assert.deepEqual(['x', 'y', 'z', 'yaw', 'pitch'].map(key => f.frames(f.bob)[0][key]), [0, 0, 0, 0, 0], 'Zero is a valid pose component');
  f.alice.state.leaseExpiresAt = f.now() + 5000;
  assert.equal(f.control(f.alice).expiresAt, f.alice.state.leaseExpiresAt, 'Consent cannot outlive account authorization');
}
for (const target of ['sender', 'recipient']) {
  const f = fixture(); f.ready(); const gate = deferred();
  const socket = target === 'sender' ? f.alice : f.bob;
  const stale = { ...socket.deserializeAttachment(), party: { floor: 'F7', open: false } };
  f.setAuthority(async ws => { if (ws === f.bob) await gate.promise; return true; });
  const pending = f.send(f.alice, 1);
  f.control(socket, { epoch: target === 'sender' ? epoch : otherEpoch, enabled: false });
  const revision = socket.state.leapPovRevision;
  f.manager.preserve(socket, stale);
  assert.equal(socket.state.leapPov, undefined, `A delayed ${target} party/statistics write cannot restore consent`);
  assert.equal(socket.state.leapPovRevision, revision);
  assert.equal(socket.state.party.floor, 'F7', 'The other feature still writes its own state');
  gate.resolve(); await pending;
  assert.equal(f.frames(f.bob).length, 0, `Old ${target} state cannot resume an in-flight pose after opt-out`);
}
{
  const f = fixture(), beforeConsent = f.alice.deserializeAttachment(); f.ready();
  const stale = f.alice.deserializeAttachment();
  await f.send(f.alice, 1);
  f.manager.preserve(f.alice, beforeConsent);
  assert.equal(f.alice.state.leapPov.epoch, epoch, 'A snapshot predating the feature cannot erase current consent');
  await f.send(f.alice, 2);
  const latest = structuredClone(f.alice.state);
  f.manager.preserve(f.alice, stale);
  assert.deepEqual(f.alice.state.leapPovSequence, latest.leapPovSequence, 'Delayed feature writes cannot rewind pose sequence');
  assert.deepEqual(f.alice.state.leapPovTraffic, latest.leapPovTraffic, 'Delayed feature writes cannot refill pose credits');
  await f.send(f.alice, 2); await f.send(f.alice, 3);
  assert.equal(f.frames(f.bob).length, 2, 'Cross-feature interleaving cannot replay poses or evade the two-Hz budget');
  f.control(f.alice, { epoch: '1'.repeat(32), server: 'new-instance' });
  f.manager.preserve(f.alice, stale);
  assert.equal(f.alice.state.leapPov.server, 'new-instance', 'Delayed feature writes cannot undo a roster or instance change');
}

// Exercise the production WebSocket dispatcher and signed Minecraft authentication in Miniflare.
const rsa = () => generateKeyPairSync('rsa', { modulusLength: 2048 });
const authority = rsa(), profileAuthority = rsa(), player = rsa();
const der = key => key.publicKey.export({ format: 'der', type: 'spki' });
const trusted = JSON.parse(await readFile('src/minecraft-keys.json', 'utf8'));
let script = (await build({ entryPoints: ['src/index.ts'], bundle: true, format: 'esm', platform: 'browser',
  external: ['cloudflare:workers'], write: false })).outputFiles[0].text;
for (const [original, replacement] of [[trusted.playerCertificateKeys[0].publicKey, der(authority).toString('base64')],
  [trusted.profilePropertyKeys[1].publicKey, der(profileAuthority).toString('base64')]]) {
  assert.ok(script.includes(original)); script = script.replaceAll(original, replacement);
}
// Delay a real permission lookup only in this fixture, keeping production authentication and handlers intact.
script += `
const povOriginalPermissions = RelayRoom.prototype.sharingPermissions;
RelayRoom.prototype.sharingPermissions = async function(ws, session, requests) {
  const result = await povOriginalPermissions.call(this, ws, session, requests);
  ws.send(JSON.stringify({type: 'fixture_sharing_pending'}));
  await new Promise(resolve => setTimeout(resolve, 200));
  return result;
};
const povOriginalMessage = RelayRoom.prototype.webSocketMessage;
RelayRoom.prototype.webSocketMessage = async function(ws, raw) {
  await povOriginalMessage.call(this, ws, raw);
  if (typeof raw === 'string' && JSON.parse(raw).type === 'party_set') ws.send(JSON.stringify({type: 'fixture_party_done'}));
};`;
const people = ['Alice', 'Bob', 'Carol'].map((name, index) => ({ name, uuid: 'abc'[index].repeat(32),
  accountId: randomUUID(), discordId: String(200000000000000000n + BigInt(index)), sessionId: randomUUID(), token: randomBytes(32).toString('base64url') }));
function proof(person, challenge) {
  const expires = Date.now() + 3600000, expiry = Buffer.alloc(8); expiry.writeBigInt64BE(BigInt(expires));
  const profile = Buffer.from(JSON.stringify({ profileId: person.uuid, profileName: person.name, timestamp: Date.now(), textures: {} })).toString('base64');
  return { type: 'authenticate', uuid: person.uuid, name: person.name, expires, publicKey: der(player).toString('base64'),
    keySignature: sign('RSA-SHA1', Buffer.concat([Buffer.from(person.uuid, 'hex'), expiry, der(player)]), authority.privateKey).toString('base64'),
    proof: sign('RSA-SHA256', Buffer.from(`SkyMyce relay v2\n${challenge}\n${person.uuid}\n${person.name}`), player.privateKey).toString('base64'),
    profile, profileSignature: sign('RSA-SHA1', Buffer.from(profile), profileAuthority.privateKey).toString('base64'), communityToken: person.token };
}
const options = convertV4MiniflareOptions({ name: 'leap-pov-check', modules: true, script, compatibilityDate: '2026-09-27',
  bindings: { ROOMS: 'friends,testing', SKYMYCE_ENVIRONMENT: 'staging' },
  durableObjects: { RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true }, ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true } },
  outboundService: () => { throw new Error('POV checks never contact real services'); } });
configureTrafficLimits(options); options.unsafeInspectDurableObjects = true;
options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112071', simple: { limit: 1000, period: 60 } };
const mf = new Miniflare(options), sockets = [];
async function connect(person, room = 'friends') {
  const response = await mf.dispatchFetch('http://localhost/websocket?room=' + room,
    { headers: { Upgrade: 'websocket', 'CF-Connecting-IP': 'pov-fixture-' + sockets.length } });
  assert.equal(response.status, 101);
  const ws = response.webSocket, queued = [], waiting = []; sockets.push(ws);
  ws.addEventListener('message', event => { const message = event.data === 'pong' ? 'pong' : JSON.parse(event.data);
    if (waiting.length) waiting.shift()(message); else queued.push(message); });
  const next = () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('POV fixture response timed out')), 5000);
    waiting.push(value => { clearTimeout(timer); resolve(value); });
  });
  ws.accept(); const challenge = await next(); ws.send(JSON.stringify(proof(person, challenge.serverId)));
  assert.equal((await next()).leapPovVersion, 1);
  return { ws, next, queued };
}
async function control(client, changes) { client.ws.send(JSON.stringify(subscription(changes))); const result = await client.next();
  assert.equal(result.type, 'leap_pov_result'); assert.equal(result.error, undefined); return result; }
const noFrames = async client => { client.ws.send('ping'); assert.equal(await client.next(), 'pong'); };
try {
  await mf.dispatchFetch('http://localhost/community/challenge', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ purpose: 'connect', clientNonce: randomBytes(32).toString('base64url') }) });
  const sql = await mf.unsafeGetDurableObjectStorage('leap-pov-check', 'AccountStore', { name: 'global' });
  for (const person of people) {
    const now = Date.now();
    await sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen) VALUES(?,?,?,?)', person.accountId, person.discordId, now, now);
    await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', person.uuid, person.accountId, person.name, now, now);
    await sql.exec('INSERT INTO community_sessions VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,NULL,NULL)', person.sessionId, person.accountId, person.uuid, 0,
      createHash('sha256').update(randomBytes(32)).digest('hex'), now + 86400000, createHash('sha256').update(person.token).digest('hex'), now + 300000, now, now, '2.6.18');
  }
  const alice = await connect(people[0]), bob = await connect(people[1]), isolated = await connect(people[2], 'testing');
  const grant = await control(alice, {}); assert.ok(grant.expiresAt - grant.serverTime <= 15_000);
  await control(bob, { epoch: otherEpoch, watching: [aliceId] });
  await control(isolated, { epoch: otherEpoch, members: [aliceId, carolId], watching: [aliceId] });
  alice.ws.send(JSON.stringify(pose(1))); const frame = await bob.next();
  assert.equal(frame.uuid, aliceId); assert.equal(frame.name, 'Alice'); assert.equal(frame.epoch, otherEpoch);
  await noFrames(alice); await noFrames(isolated);
  await mf.unsafeEvictDurableObject('leap-pov-check', 'RelayRoom', { name: 'friends', webSockets: 'hibernate' });
  assert.equal(bob.queued.length, 0, 'Hibernation never replays positions');
  alice.ws.send(JSON.stringify({ type: 'party_set', floor: 'F7', maxPbMillis: null, open: false }));
  assert.equal((await alice.next()).type, 'fixture_sharing_pending');
  await control(alice, { enabled: false }); assert.equal((await bob.next()).type, 'leap_pov_remove');
  assert.equal((await alice.next()).type, 'fixture_party_done');
  alice.ws.send(JSON.stringify(pose(2)));
  await new Promise(resolve => setTimeout(resolve, 100));
  await noFrames(bob);
  const roomSql = await mf.unsafeGetDurableObjectStorage('leap-pov-check', 'RelayRoom', { name: 'friends' });
  const tables = await roomSql.exec("SELECT name FROM sqlite_master WHERE type='table'");
  for (const { name } of tables) {
    if (name.startsWith('_cf_') || name.startsWith('sqlite_')) continue;
    const rows = await roomSql.exec('SELECT * FROM "' + name.replaceAll('"', '""') + '" LIMIT 100');
    assert.ok(!/leap_pov_pose|sourceEpoch/.test(JSON.stringify(rows)), 'Pose envelopes never enter SQL or chat history');
  }
  const binary = await connect(people[0]);
  const closed = new Promise(resolve => binary.ws.addEventListener('close', resolve, { once: true }));
  binary.ws.send(new Uint8Array([1, 2, 3]).buffer); assert.equal((await closed).code, 1009);
  const oversized = await connect(people[0]);
  const tooLarge = new Promise(resolve => oversized.ws.addEventListener('close', resolve, { once: true }));
  oversized.ws.send(JSON.stringify({ ...pose(1), forged: 'x'.repeat(2100) })); assert.equal((await tooLarge).code, 1009);
  const requestBudget = (await sql.exec("SELECT credits FROM community_traffic WHERE account_id=? AND kind='request'", people[0].accountId))[0];
  assert.ok(requestBudget.credits < 120, 'POV controls and poses retain the existing account request budget');
  console.log('Leap POV checks passed: mutual consent, instance isolation, trusted identity, bounded ephemeral poses, revocation races, hibernation and production authentication/traffic.');
} finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }
