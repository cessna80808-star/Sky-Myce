import assert from "node:assert/strict";
import { generateKeyPairSync, sign, createHash, createHmac, randomUUID } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

export async function checkIrc(script, proof) {
  const signing = generateKeyPairSync("ed25519"), secret = "test-only-bridge-secret-with-256-bit-length";
  const publicKey = key => key.publicKey.export({ format: "der", type: "spki" }).toString("base64");
  const b64 = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  const options = convertV4MiniflareOptions({ name: "irc-check", modules: true, script, compatibilityDate: "2026-09-21",
    bindings: { ROOMS: "friends,testing", SKYMYCE_ENVIRONMENT: "staging", DISCORD_BRIDGE_SECRET: secret,
      DISCORD_IRC_CHANNEL_ID: "100000000000000001", DISCORD_IRC_TEST_CHANNEL_ID: "100000000000000002",
      DRM_KEY_ID: "test", DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signing) }) },
    durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true }, ACCOUNT_STORE: { className: "AccountStore", useSQLite: true } },
    outboundService: () => { throw new Error("IRC tests must not contact real services"); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112028", simple: { limit: 20, period: 60 } };
  const mf = new Miniflare(options), sockets = []; let sequence = 100, ip = 1;
  const id = () => (sequence++).toString(16).padStart(32, "0");
  const people = new Map(["Alice", "Bob", "Carol"].map((name, i) => [name, { name, uuid: "abc"[i].repeat(32),
    discordId: String(200000000000000000n + BigInt(i)), accountId: randomUUID(), installationId: randomUUID(),
    device: String(i + 1).repeat(64), key: generateKeyPairSync("ed25519") }]));
  function inbox(ws) {
    const queued = [], waiting = [];
    ws.addEventListener("message", event => { const data = event.data === "pong" ? "pong" : JSON.parse(event.data);
      if (waiting.length) waiting.shift()(data); else queued.push(data); });
    return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("IRC response timed out")), 5000);
      waiting.push(data => { clearTimeout(timer); resolve(data); }); });
  }
  function closeEvent(ws) { return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Expected authorization close")), 5000);
    ws.addEventListener("close", event => { clearTimeout(timer); resolve(event); }, { once: true }); }); }
  function lease(person, changes = {}) {
    const now = Date.now(), claims = { product: "sky-myce", protocol: 3, accountId: person.accountId, discordId: person.discordId,
      uuid: person.uuid, installationId: person.installationId, deviceHash: person.device,
      keyFingerprint: createHash("sha256").update(person.key.publicKey.export({ format: "der", type: "spki" })).digest("hex"),
      version: "2.4.2", loaderVersion: "1.0.0", minecraft: "26.1.2", iat: now, exp: now + 300000, offlineUntil: now + 1200000,
      generation: 0, jti: randomUUID(), challengeId: randomUUID(), clientNonce: randomUUID(), features: ["relay", "irc", "cosmetics"], ...changes };
    const body = `${b64({ alg: "EdDSA", typ: "JWT", kid: "test" })}.${b64(claims)}`;
    return `${body}.${sign(null, Buffer.from(body), signing.privateKey).toString("base64url")}`;
  }
  async function connect(name, room = "friends", change = {}) {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.8.0.${ip++}` } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws);
    const challenge = await next(); assert.equal(challenge.protocol, 3);
    const person = people.get(name), token = lease(person, change.claims);
    const data = { ...proof(name, challenge.serverId), lease: token, userMessages: true, cosmetics: true,
      installationSignature: sign(null, Buffer.from(`Sky-Myce relay v3\n${challenge.serverId}\n${token}`), person.key.privateKey).toString("base64"), ...change.packet };
    const closed = change.denied ? closeEvent(ws) : undefined;
    ws.send(JSON.stringify(data));
    if (closed) { assert.equal((await closed).code, 4005); return; }
    const ready = await next(); assert.equal(ready.type, "ready"); assert.equal(ready.irc, true);
    return { ws, next };
  }
  const bridgeHeaders = (room, timestamp = String(Date.now())) => {
    const nonce = randomUUID().replaceAll("-", "");
    return { Upgrade: "websocket", "CF-Connecting-IP": `127.9.0.${ip++}`, "X-SkyMyce-Timestamp": timestamp, "X-SkyMyce-Nonce": nonce,
      "X-SkyMyce-Signature": createHmac("sha256", secret).update(`sky-myce-bridge\n${room}\n${timestamp}\n${nonce}`).digest("hex") };
  };
  async function bridge(room = "friends", headers = bridgeHeaders(room)) {
    const response = await mf.dispatchFetch(`http://localhost/discord/bridge?room=${room}`, { headers }); assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); ws.accept(); sockets.push(ws); assert.equal((await next()).type, "bridge_ready");
    const send = ws.send.bind(ws);
    ws.send = text => {
      if (text === 'ping') return send(text);
      const packet = JSON.parse(text);
      return send(JSON.stringify({ room, source: 'discord', message_id: packet.id, ...packet }));
    };
    return { ws, next };
  }
  async function subscribe(client, enabled = true) { client.ws.send(JSON.stringify({ type: "irc_subscribe", id: id(), enabled }));
    assert.equal((await client.next()).error, undefined); }
  async function ping(client) { client.ws.send("ping"); assert.equal(await client.next(), "pong"); }
  try {
    const alicePerson = people.get("Alice");
    // Initialize the real AccountStore, then seed authority for focused relay tests. Account lifecycle has its own HTTP tests.
    const init = await mf.dispatchFetch("http://localhost/auth/challenge", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ purpose: "activate", clientNonce: randomUUID(), installationId: alicePerson.installationId, publicKey: publicKey(alicePerson.key),
        deviceHash: alicePerson.device, minecraftVersion: "26.1.2", loaderVersion: "1.0.0", modVersion: "2.4.2" }) });
    assert.equal(init.status, 200);
    const store = await mf.unsafeGetDurableObjectStorage("irc-check", "AccountStore", { name: "global" });
    for (const person of people.values()) {
      const now = Date.now();
      await store.exec("INSERT INTO sky_accounts(account_id,discord_id,license_status,device_hash,activated_at,created_at,last_seen) VALUES(?,?,?,?,?,?,?)",
        person.accountId, person.discordId, person.name === "Carol" ? "revoked" : "active", person.device, now, now, now);
      await store.exec("INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)", person.uuid, person.accountId, person.name, now, now);
      await store.exec("INSERT INTO account_devices VALUES(?,?,?,NULL)", person.device, person.accountId, now);
      await store.exec("INSERT INTO installations(installation_id,account_id,device_hash,public_key,fingerprint,registered_at,revoked_at) VALUES(?,?,?,?,?,?,NULL)", person.installationId, person.accountId, person.device,
        publicKey(person.key), createHash("sha256").update(person.key.publicKey.export({ format: "der", type: "spki" })).digest("hex"), now);
    }
    await connect("Carol", "friends", { denied: true });
    await connect("Alice", "friends", { denied: true, packet: { lease: undefined, installationSignature: undefined } });
    await connect("Alice", "friends", { denied: true, packet: { installationSignature: Buffer.alloc(64).toString("base64") } });
    await connect("Alice", "friends", { denied: true, claims: { version: "1.0.0" } });
    const alice = await connect("Alice"), bob = await connect("Bob"), isolated = await connect("Alice", "testing");
    // Existing handlers work behind strict authorization as well as in the original protocol suite.
    alice.ws.send(JSON.stringify({ type: "cosmetics_get", id: id(), uuids: [alicePerson.uuid] }));
    assert.equal((await alice.next()).records[0].uuid, alicePerson.uuid);
    alice.ws.send(JSON.stringify({ type: "stats_get", id: id(), name: "Bob", uuid: people.get("Bob").uuid }));
    assert.equal((await alice.next()).type, "stats_result");
    alice.ws.send(JSON.stringify({ type: "wealth_get", id: id(), name: "Bob", uuid: people.get("Bob").uuid, canFetch: false }));
    assert.equal((await alice.next()).type, "wealth_result");
    bob.ws.send(JSON.stringify({ type: "party_set", floor: "F7", maxPbMillis: null, open: true })); await ping(bob);
    alice.ws.send(JSON.stringify({ type: "party_get", id: id(), names: ["Bob"] }));
    assert.equal((await alice.next()).parties.bob.floor, "F7");
    const privateId = id();
    alice.ws.send(JSON.stringify({ type: "user_message", id: privateId, to: "Bob", text: "private regression" }));
    assert.equal((await bob.next()).from, "Alice");
    bob.ws.send(JSON.stringify({ type: "ack", id: privateId, to: "Alice" }));
    assert.equal((await alice.next()).type, "ack");
    alice.ws.send(JSON.stringify({ type: "digest_news_get", id: id(), source: "game" }));
    assert.equal((await alice.next()).error, "not_configured");
    await subscribe(alice); await subscribe(bob); await subscribe(isolated);
    const headers = bridgeHeaders("friends"), bot = await bridge("friends", headers), testBot = await bridge("testing");
    assert.equal((await mf.dispatchFetch('http://localhost/discord/bridge?room=friends', { headers: bridgeHeaders('friends') })).status, 409);
    assert.equal((await mf.dispatchFetch("http://localhost/discord/bridge?room=friends", { headers })).status, 401);
    assert.equal((await mf.dispatchFetch("http://localhost/discord/bridge?room=friends", { headers: bridgeHeaders("friends", String(Date.now() - 120000)) })).status, 401);
    assert.equal((await mf.dispatchFetch("http://localhost/discord/bridge?room=testing", { headers: bridgeHeaders("friends") })).status, 401);
    const messageId = id();
    alice.ws.send(JSON.stringify({ type: "irc_send", id: messageId, text: "hello @everyone", from: "Forged", uuid: people.get("Bob").uuid }));
    const received = await bot.next(); assert.equal(received.from, "Alice"); assert.equal(received.uuid, alicePerson.uuid); assert.equal(received.source, "minecraft");
    assert.equal(received.room, 'friends'); assert.equal(received.message_id, received.id);
    assert.equal((await alice.next()).id, received.id); assert.equal((await alice.next()).type, "irc_result");
    assert.equal((await bob.next()).id, received.id); await ping(isolated); await ping(testBot);
    await mf.unsafeEvictDurableObject("irc-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    alice.ws.send(JSON.stringify({ type: "irc_send", id: messageId, text: "replayed" })); assert.equal((await alice.next()).error, "duplicate_message");
    const discordId = "900000000000000001";
    bot.ws.send(JSON.stringify({ type: "discord_message", id: discordId, discordId: alicePerson.discordId, from: "Discord Alice", text: "hello Minecraft" }));
    assert.equal((await alice.next()).source, "discord"); assert.equal((await bob.next()).from, "Discord Alice"); assert.equal((await bot.next()).type, "irc_result");
    await ping(bot); await ping(isolated); // No loop and no room crossing.
    bot.ws.send(JSON.stringify({ type: "discord_message", id: "900000000000000002", discordId: "999999999999999999", from: "Unlinked", text: "no" }));
    assert.equal((await bot.next()).error, "not_authorized");
    bot.ws.send(JSON.stringify({ type: 'discord_message', id: '900000000000000004', discordId: alicePerson.discordId, room: 'testing', from: 'Alice', text: 'wrong room' }));
    assert.equal((await bot.next()).error, 'invalid_route');
    bot.ws.send(JSON.stringify({ type: "discord_message", id: discordId, discordId: alicePerson.discordId, from: "Discord Alice", text: "replay" }));
    assert.equal((await bot.next()).error, "duplicate_message");
    for (const text of ["", " ", "a".repeat(257), "bad\nmessage", "bad\u007f", "bad\u00a7a"]) {
      alice.ws.send(JSON.stringify({ type: "irc_send", id: id(), text })); assert.equal((await alice.next()).error, "invalid_message");
    }
    for (let i = 0; i < 3; i++) {
      alice.ws.send(JSON.stringify({ type: "irc_send", id: id(), text: "quota" })); await alice.next(); await alice.next(); await bob.next(); await bot.next();
    }
    await mf.unsafeEvictDurableObject("irc-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    alice.ws.send(JSON.stringify({ type: "irc_send", id: id(), text: "over quota" })); assert.equal((await alice.next()).error, "rate_limited");
    await subscribe(bob, false); await subscribe(bob, true); // Subscription opt-in survives hibernation.
    await store.exec("UPDATE sky_accounts SET license_status='revoked',generation=generation+1 WHERE account_id=?", alicePerson.accountId);
    const closed = closeEvent(alice.ws);
    alice.ws.send(JSON.stringify({ type: "irc_send", id: id(), text: "after revoke" }));
    assert.equal((await alice.next()).type, "authorization_revoked"); assert.equal((await closed).code, 4005);
    bot.ws.send(JSON.stringify({ type: "discord_message", id: "900000000000000003", discordId: alicePerson.discordId, from: "Alice", text: "after revoke" }));
    assert.equal((await bot.next()).error, "not_authorized"); await ping(bob);
    const roomStorage = await mf.unsafeGetDurableObjectStorage("irc-check", "RelayRoom", { name: "friends" });
    const tables = await roomStorage.exec("SELECT name FROM sqlite_master WHERE type='table'");
    assert.ok(!tables.some(table => /chat_history|irc_messages/.test(table.name)));
    console.log("IRC checks passed: strict leases, installation proof, bridge replay, sender identity, both directions, hibernation, deduplication, quotas, isolation, revocation.");
  } finally { for (const ws of sockets) try { ws.close(); } catch {} await mf.dispose(); }
}
