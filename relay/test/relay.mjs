import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { generateKeyPairSync, sign } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { checkDigest } from "./digest.mjs";
import { checkSocial } from "./social.mjs";
import { checkCosmetics } from "./cosmetics.mjs";
import { checkIrc } from "./irc.mjs";
import { checkAccounts } from "./accounts.mjs";
import { checkStrictCosmetics } from "./cosmetics-strict.mjs";

// Replace public trust roots only in this in-memory test bundle. Production has no auth bypass.
const rsa = () => generateKeyPairSync("rsa", { modulusLength: 2048 });
const authority = rsa(), profileAuthority = rsa(), playerKey = rsa();
const publicDer = pair => pair.publicKey.export({ format: "der", type: "spki" });
const publicBase64 = pair => publicDer(pair).toString("base64");
const trusted = JSON.parse(await readFile("src/minecraft-keys.json", "utf8"));
let script = await readFile(".test-build/index.js", "utf8");
for (const [original, replacement] of [[trusted.playerCertificateKeys[0].publicKey, publicBase64(authority)],
  [trusted.profilePropertyKeys[1].publicKey, publicBase64(profileAuthority)]]) {
  assert.ok(script.includes(original));
  script = script.replaceAll(original, replacement);
}
const identities = new Map([["Alice", "a".repeat(32)], ["Bob", "b".repeat(32)], ["Carol", "c".repeat(32)], ["InvalidPolicy", "d".repeat(32)]]);
function proof(name, challenge, changes = {}) {
  const id = identities.get(name), expires = changes.expires ?? Date.now() + 3600000;
  const expiry = Buffer.alloc(8); expiry.writeBigInt64BE(BigInt(expires));
  const certificate = Buffer.concat([Buffer.from(id, "hex"), expiry, publicDer(playerKey)]);
  const profile = Buffer.from(JSON.stringify({ profileId: id, profileName: name,
    timestamp: changes.timestamp ?? Date.now(), textures: {} })).toString("base64");
  return { type: "authenticate", name: changes.name ?? name, uuid: id, expires, publicKey: publicBase64(playerKey),
    keySignature: sign("RSA-SHA1", certificate, authority.privateKey).toString("base64"),
    proof: sign("RSA-SHA256", Buffer.from(`SkyMyce relay v2\n${challenge}\n${id}\n${changes.name ?? name}`), playerKey.privateKey).toString("base64"),
    profile, profileSignature: sign("RSA-SHA1", Buffer.from(profile), profileAuthority.privateKey).toString("base64") };
}
const options = convertV4MiniflareOptions({
  name: "relay-check",
  modules: true, script, compatibilityDate: "2026-09-21",
  bindings: { ROOMS: "friends,testing", SKYMYCE_ENVIRONMENT: "development", ALLOW_LEGACY_RELAY: "true" },
  durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
  outboundService: () => { throw new Error("Account verification must not make outbound requests"); },
});
options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 20, period: 60 } };
options.unsafeInspectDurableObjects = true;
const mf = new Miniflare(options);
const sockets = [];
function inbox(ws) {
  const queued = [], waiting = [];
  ws.addEventListener("message", event => {
    const data = event.data === "pong" ? "pong" : JSON.parse(event.data);
    if (waiting.length) waiting.shift()(data); else queued.push(data);
  });
  return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Timed out waiting for WebSocket message")), 3000);
    waiting.push(data => { clearTimeout(timer); resolve(data); });
  });
}
async function connect(name, room = "friends") {
  const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, {
    headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.0.0.${sockets.length + 1}` },
  });
  assert.equal(response.status, 101);
  const ws = response.webSocket;
  sockets.push(ws);
  const next = inbox(ws);
  ws.accept();
  const challenge = await next();
  assert.equal(challenge.type, "challenge");
  assert.equal(challenge.protocol, 2);
  return { ws, next, challenge: challenge.serverId,
    authenticate(data = proof(name, challenge.serverId)) { ws.send(JSON.stringify(data)); } };
}
function closeEvent(ws) { return new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("Expected connection close")), 3000);
  ws.addEventListener("close", event => { clearTimeout(timer); resolve(event); }, { once: true });
}); }
try {
  assert.equal((await mf.dispatchFetch("http://localhost/health")).status, 200);
  assert.equal((await mf.dispatchFetch("http://localhost/websocket")).status, 426);
  assert.equal((await mf.dispatchFetch("http://localhost/websocket?room=unlisted", { headers: { Upgrade: "websocket" } })).status, 403);
  const alice = await connect("Alice"); alice.authenticate({ ...proof("Alice", alice.challenge), liveUpdates: true });
  assert.equal((await alice.next()).liveUpdates, true);
  alice.ws.send(JSON.stringify({ type: "digest_news_get", id: "f".repeat(32), source: "game" }));
  const unconfiguredNews = await alice.next();
  assert.equal(unconfiguredNews.error, "not_configured"); assert.deepEqual(unconfiguredNews.items, []);
  const bob = await connect("Bob"); bob.authenticate(); assert.equal((await bob.next()).type, "ready");
  const carol = await connect("Carol", "testing"); carol.authenticate({ ...proof("Carol", carol.challenge), liveUpdates: true }); await carol.next();
  const storage = await mf.unsafeGetDurableObjectStorage("relay-check", "RelayRoom", { name: "friends" });
  const users = () => storage.exec("SELECT * FROM mod_users ORDER BY name");
  const registered = await users();
  assert.deepEqual(registered.map(row => row.name), ["Alice", "Bob"]);
  assert.deepEqual(Object.keys(registered[0]).sort(), ["first_seen", "last_seen", "name", "uuid"]);
  assert.equal(registered[0].uuid, identities.get("Alice"));
  let cacheId = 100;
  async function cache(client, data) {
    client.ws.send(JSON.stringify({ id: (cacheId++).toString(16).padStart(32, "0"), ...data }));
    const response = await client.next();
    assert.equal(response.type, data.type.startsWith("wealth_") ? "wealth_result" : "stats_result");
    return response;
  }
  const stats = { state: "AVAILABLE", catacombs: 52, classes: { ARCHER: 52, MAGE: 2 },
    selectedClass: "MAGE", completionTimes: { F7: 300000 }, sPlusTimes: { F7: 310000 }, completedFloors: ["F7"] };
  const lookup = { type: "stats_get", name: "Bob", uuid: identities.get("Bob") };
  const first = await cache(alice, lookup), racing = await cache(bob, lookup);
  assert.equal(first.record, null); assert.match(first.upload, /^[a-f0-9]{32}$/);
  const entry = { type: "stats_put", name: "Bob", uuid: identities.get("Bob"), stats, fetchedAt: Date.now(), upload: first.upload };
  assert.equal((await cache(alice, { ...entry, upload: "0".repeat(32) })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, fetchedAt: Date.now() - 660000 })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, stats: { ...stats, sPlusTimes: { F7: -1 } } })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, stats: { ...stats, state: { toString: "AVAILABLE" } } })).error, "invalid_upload");
  assert.equal((await cache(alice, entry)).stored, true);
  assert.equal((await cache(bob, { ...entry, upload: racing.upload, stats: { ...stats, catacombs: 99 } })).stored, false);
  const cached = await cache(bob, lookup);
  assert.deepEqual(cached.record.stats, stats); assert.equal(cached.record.fetchedAt, entry.fetchedAt);
  assert.equal(cached.upload, undefined); // No upload needed and no duplicate row or renewed age.
  assert.equal((await cache(carol, lookup)).record, null); // Room isolation includes stats.
  assert.equal((await cache(bob, { ...lookup, uuid: identities.get("Alice") })).record, null);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.deepEqual((await cache(bob, lookup)).record.stats, stats); // SQLite survives hibernation.
  assert.deepEqual(await users(), registered); // Hibernation must neither lose users nor rewrite their timestamps.
  const wealthLookup = { type: "wealth_get", name: "Bob", uuid: identities.get("Bob") };
  const cacheOnly = await cache(bob, { ...wealthLookup, canFetch: false });
  assert.equal(cacheOnly.record, null);
  assert.equal(cacheOnly.upload, undefined); // A mod without the provider cannot monopolize an empty cache.
  const wealthGrant = await cache(alice, wealthLookup);
  assert.match(wealthGrant.upload, /^[a-f0-9]{32}$/);
  assert.ok((await cache(bob, { ...wealthLookup, name: "OldBobName" })).retryAt > Date.now()); // UUID aliases share one fetch.
  assert.equal((await cache(alice, wealthLookup)).upload, wealthGrant.upload); // Reuse a lease after local API backoff.
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.ok((await cache(bob, wealthLookup)).retryAt > Date.now());
  const wealth = { hasProfile: true, networth: 2000000000, purse: 1000000, bank: 5000000, wardrobe: 200000,
    profile: "Apple", status: "", inventory: "must not persist" };
  const wealthEntry = { type: "wealth_put", name: "Bob", uuid: identities.get("Bob"),
    wealth, fetchedAt: Date.now(), upload: wealthGrant.upload };
  assert.equal((await cache(alice, { ...wealthEntry, upload: "0".repeat(32) })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...wealthEntry, wealth: { ...wealth, purse: -1 } })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...wealthEntry, fetchedAt: Date.now() - 86400001 })).error, "invalid_upload");
  assert.equal((await cache(alice, wealthEntry)).stored, true);
  const wealthHit = await cache(bob, { ...wealthLookup, name: "OldBobName", canFetch: false });
  assert.equal(wealthHit.record.wealth.networth, wealth.networth);
  assert.equal(wealthHit.record.wealth.wardrobe, wealth.wardrobe);
  assert.equal(wealthHit.record.fetchedAt, wealthEntry.fetchedAt);
  assert.equal(wealthHit.record.wealth.inventory, undefined);
  assert.equal(wealthHit.upload, undefined);
  assert.ok(Buffer.byteLength(JSON.stringify(wealthHit.record)) < 1024);
  assert.equal((await cache(carol, wealthLookup)).record, null); // No wealth data crosses rooms.
  alice.ws.send("ping"); assert.equal(await alice.next(), "pong"); // No room-wide wealth broadcasts.
  await new Promise(resolve => setTimeout(resolve, 1100)); // Refill one credit after the extra cache-only lookup.
  const absentLookup = { type: "wealth_get", name: "NoProfile", uuid: "e".repeat(32) };
  const absentGrant = await cache(bob, absentLookup);
  assert.equal((await cache(bob, { ...absentLookup, type: "wealth_put", upload: absentGrant.upload,
    wealth: { hasProfile: false }, fetchedAt: Date.now() })).stored, true);
  assert.equal((await cache(bob, absentLookup)).record.wealth.hasProfile, false);
  assert.deepEqual((await users()).map(row => row.name), ["Alice", "Bob"]); // Looking up friends never registers them as mod users.
  const olderLookup = { type: "wealth_get", name: "Older", uuid: "f".repeat(32) };
  const olderGrant = await cache(carol, olderLookup);
  const olderFetchedAt = Date.now() - 120000;
  assert.equal((await cache(carol, { ...olderLookup, type: "wealth_put", upload: olderGrant.upload,
    wealth, fetchedAt: olderFetchedAt })).stored, true);
  assert.equal((await cache(carol, olderLookup)).record.wealth.networth, wealth.networth);
  const manualHit = await cache(carol, { ...olderLookup, refresh: true });
  assert.equal(manualHit.upload, undefined); // Even older clients requesting a forced refresh cannot bypass 24h.
  assert.equal(manualHit.record.fetchedAt, olderFetchedAt);
  assert.equal(manualHit.record.wealth.networth, wealth.networth);
  let wealthLimited = false;
  for (let i = 0; i < 8 && !wealthLimited; i++) wealthLimited = (await cache(alice, wealthLookup)).error === "rate_limited";
  assert.ok(wealthLimited);
  await new Promise(resolve => setTimeout(resolve, 1100));
  const freshManual = await cache(alice, { ...wealthLookup, refresh: true });
  assert.equal(freshManual.record.wealth.networth, wealth.networth);
  assert.equal(freshManual.upload, undefined); // Repeated Refresh clicks reuse very recent results.
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.equal((await cache(bob, wealthLookup)).record.wealth.networth, wealth.networth);
  async function policies(client, names) {
    client.ws.send(JSON.stringify({ type: "party_get", id: (cacheId++).toString(16).padStart(32, "0"), names }));
    const response = await client.next();
    assert.equal(response.type, "party_result");
    return response;
  }
  bob.ws.send(JSON.stringify({ type: "party_set", name: "Alice", floor: "F7", maxPbMillis: 420000, open: true }));
  bob.ws.send("ping"); await bob.next();
  assert.deepEqual(await alice.next(), { type: "party_update", name: "Bob", uuid: identities.get("Bob"), floor: "F7", maxPbMillis: 420000, open: true });
  alice.ws.send(JSON.stringify({ type: "party_set", floor: "F7", maxPbMillis: 400000, open: true }));
  alice.ws.send("ping"); assert.equal(await alice.next(), "pong");
  bob.ws.send("ping"); assert.equal(await bob.next(), "pong"); // Old clients receive no unsupported push packets.
  const advertised = await policies(alice, ["BOB", "Offline", "Alice", "__proto__"]);
  assert.deepEqual(advertised.parties.bob, { floor: "F7", maxPbMillis: 420000, open: true, uuid: identities.get("Bob") });
  assert.equal(advertised.parties.offline, null);
  assert.equal(advertised.parties.__proto__, null);
  assert.equal(advertised.parties.alice, null); // A forged name cannot publish another player's policy.
  assert.equal((await policies(carol, ["Bob"])).parties.bob, null); // Policies stay in their room.
  bob.ws.send(JSON.stringify({ type: "party_set", floor: "F7", maxPbMillis: 300000, open: false }));
  bob.ws.send("ping"); await bob.next();
  assert.equal((await alice.next()).maxPbMillis, 300000); // Changed requirements arrive without a lookup.
  bob.ws.send(JSON.stringify({ type: "party_set", floor: "F7", maxPbMillis: 300000, open: false }));
  bob.ws.send("ping"); await bob.next();
  alice.ws.send("ping"); assert.equal(await alice.next(), "pong"); // Unchanged requirements do not fan out.
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.deepEqual((await policies(alice, ["Bob"])).parties.bob,
    { floor: "F7", maxPbMillis: 300000, open: false, uuid: identities.get("Bob") });
  const id = "1".repeat(32);
  const lfg = "[SkyMyce LFG M7 Healer abcdef1234567890] Hey Bob, want to play Healer on M7?";
  alice.ws.send(JSON.stringify({ type: "message", id, to: "Bob", from: "Forged", text: lfg }));
  const received = await bob.next();
  assert.deepEqual(received, { type: "message", id, from: "Alice", uuid: identities.get("Alice"), text: lfg });
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  bob.ws.send(JSON.stringify({ type: "ack", to: "Alice", id }));
  assert.deepEqual(await alice.next(), { type: "ack", id, from: "Bob", uuid: identities.get("Bob") });
  const join = "Invite me for M7 as Healer [SkyMyce Join abcdef1234567890]";
  bob.ws.send(JSON.stringify({ type: "message", id: "7".repeat(32), to: "aLiCe", text: join }));
  assert.equal((await alice.next()).text, join);
  alice.ws.send(JSON.stringify({ type: "ack", id: "7".repeat(32), to: "bOb" }));
  assert.equal((await bob.next()).type, "ack");
  const ready = "Inviting you for M7 as Healer [SkyMyce Ready abcdef1234567890]";
  alice.ws.send(JSON.stringify({ type: "message", id: "8".repeat(32), to: "Bob", text: ready }));
  assert.equal((await bob.next()).text, ready);
  bob.ws.send(JSON.stringify({ type: "ack", id: "8".repeat(32), to: "Alice" }));
  assert.equal((await alice.next()).type, "ack");
  alice.ws.send(JSON.stringify({ type: "message", id: "2".repeat(32), to: "Carol", text: "must not cross rooms" }));
  assert.equal((await alice.next()).code, "offline");
  carol.ws.send("ping"); assert.equal(await carol.next(), "pong"); // No leaked message preceded the pong.
  for (const [index, mutate] of [
    (data, challenge) => ({ ...data, proof: Buffer.alloc(256).toString("base64") }),
    data => ({ ...data, uuid: identities.get("Bob") }),
    () => proof("Alice", alice.challenge), // Valid proof from another socket cannot be replayed.
    (data, challenge) => proof("Alice", challenge, { name: "Bob" }), // A verified UUID cannot impersonate a name.
    (data, challenge) => proof("Alice", challenge, { expires: Date.now() - 1 }),
    (data, challenge) => proof("Alice", challenge, { timestamp: Date.now() - 2 * 86400000 }),
    data => ({ ...data, keySignature: data.profileSignature }),
    data => ({ ...data, profileSignature: data.keySignature }),
  ].entries()) {
    const fake = await connect("Alice", "testing");
    const rejected = closeEvent(fake.ws);
    fake.authenticate(mutate(proof("Alice", fake.challenge), fake.challenge));
    const rejection = await rejected;
    assert.equal(rejection.code, index === 4 || index === 5 ? 4003 : 4004);
    assert.match(rejection.reason, /^(certificate|challenge|profile)_/);
  }
  const unauth = await connect("Bob", "testing");
  const blocked = closeEvent(unauth.ws);
  unauth.ws.send(JSON.stringify({ type: "message", id, to: "Carol", text: "not authenticated" }));
  assert.equal((await blocked).code, 1008);
  const testingStorage = await mf.unsafeGetDurableObjectStorage("relay-check", "RelayRoom", { name: "testing" });
  assert.deepEqual((await testingStorage.exec("SELECT name FROM mod_users")).map(row => row.name), ["Carol"]);
  const closed = closeEvent(bob.ws);
  bob.ws.send(JSON.stringify({ type: "message", id: "3".repeat(32), to: "Alice", text: "x".repeat(2050) }));
  assert.equal((await closed).code, 1009);
  assert.equal((await policies(alice, ["Bob"])).parties.bob, null); // Disconnected hosts are not advertised.
  const modernBob = await connect("Bob");
  modernBob.authenticate({ ...proof("Bob", modernBob.challenge), liveUpdates: true }); await modernBob.next();
  const returningBob = (await users()).find(row => row.uuid === identities.get("Bob"));
  assert.equal(returningBob.first_seen, registered[1].first_seen);
  assert.ok(returningBob.last_seen > registered[1].last_seen);
  const selfRefresh = await cache(modernBob, lookup);
  assert.deepEqual(selfRefresh.record.stats, stats); assert.match(selfRefresh.upload, /^[a-f0-9]{32}$/);
  const improved = { ...entry, stats: { ...stats, sPlusTimes: { F7: 290000 } }, fetchedAt: Date.now(), upload: selfRefresh.upload };
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.equal((await cache(modernBob, improved)).stored, true);
  const liveStats = await alice.next();
  assert.equal(liveStats.type, "stats_update"); assert.deepEqual(liveStats.record.stats, improved.stats);
  assert.equal(liveStats.record.uuid, identities.get("Bob"));
  const anotherLookup = await cache(alice, lookup);
  assert.equal(anotherLookup.upload, undefined); // A peer cannot overwrite somebody else's fresh PB.
  const oldRefresh = await cache(modernBob, lookup);
  assert.equal((await cache(modernBob, { ...entry, upload: oldRefresh.upload })).stored, false);
  alice.ws.send("ping"); assert.equal(await alice.next(), "pong"); // Stale uploads produce no update.
  modernBob.ws.send(JSON.stringify({ type: "party_set", name: "Alice", uuid: identities.get("Alice"),
    floor: "F7", open: true, selectedClass: "TANK" }));
  modernBob.ws.send("ping"); assert.equal(await modernBob.next(), "pong");
  assert.deepEqual(await alice.next(), { type: "party_update", name: "Bob", uuid: identities.get("Bob"),
    floor: "F7", maxPbMillis: null, open: true, selectedClass: "TANK" }); // Identity belongs to the authenticated player.
  const changedClass = (await cache(alice, lookup)).record;
  assert.equal(changedClass.stats.selectedClass, "TANK");
  assert.equal(changedClass.fetchedAt, liveStats.record.fetchedAt); // Class change does not renew PB freshness.
  assert.deepEqual(changedClass.stats.sPlusTimes, improved.stats.sPlusTimes);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.equal((await policies(alice, ["Bob"])).parties.bob.selectedClass, "TANK");
  const classRefresh = await cache(modernBob, lookup);
  assert.equal((await cache(modernBob, { ...improved, fetchedAt: Date.now(), upload: classRefresh.upload })).stored, true);
  assert.equal((await alice.next()).record.stats.selectedClass, "TANK"); // A later API upload cannot undo a live class.
  carol.ws.send("ping"); assert.equal(await carol.next(), "pong"); // Neither stats nor policy pushes cross rooms.
  const invalidPolicy = await connect("InvalidPolicy", "testing"); invalidPolicy.authenticate(); await invalidPolicy.next();
  const policyClosed = closeEvent(invalidPolicy.ws);
  invalidPolicy.ws.send(JSON.stringify({ type: "party_set", floor: "F7", maxPbMillis: -1, open: true }));
  assert.equal((await policyClosed).code, 1008);
  const invalidClass = await connect("InvalidPolicy", "testing"); invalidClass.authenticate(); await invalidClass.next();
  const classClosed = closeEvent(invalidClass.ws);
  invalidClass.ws.send(JSON.stringify({ type: "party_set", floor: "F7", open: true, selectedClass: "FARMER" }));
  assert.equal((await classClosed).code, 1008);
  const burstSender = await connect("Alice", "testing"); burstSender.authenticate(); await burstSender.next();
  const burstRecipient = await connect("Bob", "testing"); burstRecipient.authenticate(); await burstRecipient.next();
  const burstIds = Array.from({ length: 9 }, (_, i) => (200 + i).toString(16).padStart(32, "0"));
  for (const id of burstIds) burstSender.ws.send(JSON.stringify({ type: "message", id, to: "Bob", text: "burst" }));
  for (const id of burstIds) assert.equal((await burstRecipient.next()).id, id);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "testing", webSockets: "hibernate" });
  for (const id of burstIds) {
    burstRecipient.ws.send(JSON.stringify({ type: "ack", id, to: "Alice" }));
    assert.equal((await burstSender.next()).id, id);
  }
  const delayedId = "e".repeat(32);
  burstSender.ws.send(JSON.stringify({ type: "message", id: delayedId, to: "Bob", text: "delayed" }));
  await burstRecipient.next();
  burstRecipient.ws.send(JSON.stringify({ type: "ack", id: delayedId, to: "Carol" }));
  assert.equal((await burstRecipient.next()).code, "invalid_receipt"); // Wrong sender must never receive a receipt.
  carol.ws.send("ping"); assert.equal(await carol.next(), "pong");
  await new Promise(resolve => setTimeout(resolve, 10100));
  for (const id of [delayedId, "f".repeat(32), burstIds[0]]) {
    burstRecipient.ws.send(JSON.stringify({ type: "ack", id, to: "Alice" }));
    assert.equal((await burstRecipient.next()).code, "invalid_receipt"); // Expired, forged, and duplicate.
  }
  burstSender.ws.send("ping"); assert.equal(await burstSender.next(), "pong"); // None was forwarded.
  const displaced = closeEvent(burstRecipient.ws);
  const replacement = await connect("Bob", "testing"); replacement.authenticate(); await replacement.next();
  assert.equal((await displaced).code, 4001);
  const bobUsers = await testingStorage.exec("SELECT * FROM mod_users WHERE uuid = ?", identities.get("Bob"));
  assert.equal(bobUsers.length, 1); // Reconnect updates one row, never adds another user.
  assert.ok(bobUsers[0].last_seen >= bobUsers[0].first_seen);
  const renamedLookup = { type: "wealth_get", name: "OlderAlias" };
  const renamedGrant = await cache(replacement, renamedLookup);
  assert.match(renamedGrant.upload, /^[a-f0-9]{32}$/);
  assert.equal((await cache(replacement, { ...renamedLookup, type: "wealth_put", uuid: olderLookup.uuid,
    upload: renamedGrant.upload, wealth, fetchedAt: Date.now() })).stored, false); // A name-only lease cannot overwrite a fresh UUID record.
  assert.equal((await cache(replacement, olderLookup)).record.fetchedAt, olderFetchedAt);
  const releaseLookup = { type: "wealth_get", name: "RefreshTarget", uuid: "d".repeat(32) };
  const released = await cache(alice, releaseLookup);
  assert.match(released.upload, /^[a-f0-9]{32}$/);
  assert.equal((await cache(alice, { ...releaseLookup, canFetch: false })).upload, undefined);
  const reassigned = await cache(modernBob, releaseLookup);
  assert.match(reassigned.upload, /^[a-f0-9]{32}$/);
  assert.notEqual(reassigned.upload, released.upload); // Provider backoff releases the slot for another user.
  assert.equal((await cache(alice, { ...releaseLookup, type: "wealth_put", upload: released.upload,
    wealth, fetchedAt: Date.now() })).error, "invalid_upload");
  const sharedFetchedAt = Date.now() - 23 * 3600000;
  assert.equal((await cache(modernBob, { ...releaseLookup, type: "wealth_put", name: "RenamedTarget",
    upload: reassigned.upload, wealth, fetchedAt: sharedFetchedAt })).stored, true);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  const sharedDayHit = await cache(alice, { ...releaseLookup, refresh: true });
  assert.equal(sharedDayHit.record.wealth.networth, wealth.networth);
  assert.equal(sharedDayHit.record.wealth.wardrobe, wealth.wardrobe);
  assert.equal(sharedDayHit.record.fetchedAt, sharedFetchedAt); // Other players reuse 23-hour-old data without renewing its age.
  assert.equal(sharedDayHit.upload, undefined);
  await storage.exec("UPDATE player_wealth SET fetched_at = ? WHERE uuid = ?", Date.now() - 86400000, releaseLookup.uuid);
  const expiredWealth = await cache(modernBob, { ...releaseLookup, canFetch: false });
  assert.equal(expiredWealth.record, null); // An expired estimate must not be delivered as fresh data.
  assert.equal(expiredWealth.upload, undefined);
  const expiredGrant = await cache(modernBob, { ...releaseLookup, refresh: true });
  assert.match(expiredGrant.upload, /^[a-f0-9]{32}$/);
  const updatedAt = Date.now();
  assert.equal((await cache(modernBob, { ...releaseLookup, type: "wealth_put", upload: expiredGrant.upload,
    wealth: { ...wealth, wardrobe: 300000 }, fetchedAt: updatedAt })).stored, true);
  const updatedWealth = await cache(alice, { ...releaseLookup, canFetch: false });
  assert.equal(updatedWealth.record.wealth.wardrobe, 300000);
  assert.equal(updatedWealth.record.fetchedAt, updatedAt); // The next day's update and its timestamp reach other players together.
  // The 24h rule protects complete snapshots, but a manual refresh can repair missing values.
  const partialLookup = { type: "wealth_get", name: "Partial", uuid: "9".repeat(32), refresh: true };
  const partialWealth = { ...wealth, wardrobe: null, inventory: undefined };
  const partialAt = Date.now() - 120000;
  await testingStorage.exec("INSERT INTO player_wealth (uuid, name, wealth, fetched_at) VALUES (?, ?, ?, ?)",
    partialLookup.uuid, partialLookup.name, JSON.stringify(partialWealth), Date.now());
  assert.equal((await cache(replacement, partialLookup)).upload, undefined); // No immediate repeat API fetch.
  await testingStorage.exec("UPDATE player_wealth SET fetched_at = ? WHERE uuid = ?", partialAt, partialLookup.uuid);
  await new Promise(resolve => setTimeout(resolve, 1100));
  const savedPartial = await cache(replacement, { ...partialLookup, canFetch: false });
  assert.equal(savedPartial.record.fetchedAt, partialAt); // Cache-only clients keep the usable partial data.
  assert.equal(savedPartial.upload, undefined);
  const fillGrant = await cache(replacement, partialLookup);
  assert.match(fillGrant.upload, /^[a-f0-9]{32}$/);
  const contender = await cache(carol, partialLookup);
  assert.ok(contender.retryAt > Date.now()); // Only one client fills the missing value.
  assert.equal(contender.upload, undefined);
  assert.equal((await cache(replacement, { ...partialLookup, type: "wealth_put", upload: fillGrant.upload,
    wealth: { ...wealth, networth: null }, fetchedAt: Date.now() })).stored, false); // Filling wardrobe cannot drop networth.
  await new Promise(resolve => setTimeout(resolve, 2200));
  const retryFill = await cache(replacement, partialLookup);
  assert.match(retryFill.upload, /^[a-f0-9]{32}$/);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "testing", webSockets: "hibernate" });
  const filledAt = Date.now();
  assert.equal((await cache(replacement, { ...partialLookup, type: "wealth_put", upload: retryFill.upload,
    wealth, fetchedAt: filledAt })).stored, true);
  const filled = await cache(carol, { ...partialLookup, canFetch: false });
  assert.equal(filled.record.wealth.wardrobe, wealth.wardrobe);
  assert.equal(filled.record.fetchedAt, filledAt);
  assert.equal((await cache(carol, partialLookup)).upload, undefined); // Complete again: no bypass.
  const spam = closeEvent(alice.ws);
  for (let i = 0; i < 15; i++) alice.ws.send(JSON.stringify({ type: "message", id: i.toString(16).padStart(32, "0"), to: "Offline", text: "rate test" }));
  assert.equal((await spam).code, 1008);
  const cleanClose = closeEvent(carol.ws);
  carol.ws.close(1000, "Diagnostic complete");
  assert.equal((await cleanClose).code, 1000);
  identities.set("RenamedBob", identities.get("Bob"));
  const renamed = await connect("RenamedBob", "testing"); renamed.authenticate(); await renamed.next();
  const renamedUsers = await testingStorage.exec("SELECT * FROM mod_users WHERE uuid = ?", identities.get("Bob"));
  assert.equal(renamedUsers.length, 1);
  assert.equal(renamedUsers[0].name, "RenamedBob");
  assert.equal(renamedUsers[0].first_seen, bobUsers[0].first_seen);
  console.log("Relay checks passed: authenticated user registry, shared stats and wealth, refresh leases, bounded payloads, hibernation, receipts, isolation, and limits.");
} finally {
  for (const ws of sockets) { try { ws.close(); } catch {} }
  await mf.dispose();
}
await checkDigest(script, proof);
await checkSocial(script, proof);
await checkCosmetics(script, proof);
await checkAccounts(script, proof);
await checkStrictCosmetics(script, proof);
await checkIrc(script, proof);
