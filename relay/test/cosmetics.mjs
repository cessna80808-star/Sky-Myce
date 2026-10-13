import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

export async function checkCosmetics(script, proof) {
  const keys = generateKeyPairSync("ed25519");
  const application = "123456789012345678", aliceActor = "223456789012345678", bobActor = "323456789012345678", adminActor = "423456789012345678";
  const channel = "1552057526969835612";
  const options = convertV4MiniflareOptions({ name: "cosmetics-check", modules: true, script, compatibilityDate: "2026-09-21",
    bindings: { ROOMS: "friends,testing", SKYMYCE_ENVIRONMENT: "development", ALLOW_LEGACY_RELAY: "true", DISCORD_APPLICATION_ID: application,
      DISCORD_PUBLIC_KEY: keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("hex"),
      COSMETICS_COMMAND_CHANNEL: channel },
    durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
    outboundService: () => { throw new Error("Discord interactions and cosmetics must not make outbound requests"); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 20, period: 60 } };
  const mf = new Miniflare(options), sockets = []; let sequence = 1000, httpSequence = 1;
  function inbox(ws) {
    const queued = [], waiting = [];
    ws.addEventListener("message", event => { const data = event.data === "pong" ? "pong" : JSON.parse(event.data); if (waiting.length) waiting.shift()(data); else queued.push(data); });
    return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Cosmetics packet timed out")), 3000);
      waiting.push(data => { clearTimeout(timer); resolve(data); });
    });
  }
  async function connect(name, capable = true, room = "friends", version = 3) {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.3.0.${sockets.length + 1}` } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); sockets.push(ws); ws.accept(); const challenge = await next();
    ws.send(JSON.stringify({ ...proof(name, challenge.serverId), cosmetics: capable, cosmeticsVersion: version }));
    const ready = await next(); assert.equal(ready.cosmetics, true); assert.equal(ready.cosmeticsNames, true);
    return { ws, next };
  }
  async function request(client, data) {
    const id = (sequence++).toString(16).padStart(32, "0"); client.ws.send(JSON.stringify({ id, ...data }));
    const result = await client.next(); assert.equal(result.id, id); assert.equal(result.type, "cosmetics_result"); return result;
  }
  function command(action, args = {}, actor = aliceActor, extra = {}) {
    return { type: 2, application_id: application, id: String(700000000000000000n + BigInt(sequence++)),
      channel_id: channel, user: { id: actor }, data: { name: "cosmetics", options: [{ type: 1, name: action,
        options: Object.entries(args).map(([name, value]) => ({ name, type: ["x", "y", "z"].includes(name) ? 10 : name === "confirm" ? 5 : 3, value })) }] }, ...extra };
  }
  async function interaction(data, changes = {}) {
    const body = JSON.stringify(data), timestamp = changes.timestamp ?? String(Math.floor(Date.now() / 1000));
    const signature = sign(null, Buffer.from(timestamp + body), keys.privateKey).toString("hex");
    return mf.dispatchFetch("http://localhost/discord/interactions", { method: "POST", body: changes.body ?? body,
      headers: { "X-Signature-Ed25519": signature, "X-Signature-Timestamp": timestamp, "CF-Connecting-IP": `192.0.2.${httpSequence++}`, ...changes.headers } });
  }
  async function call(data) {
    const response = await interaction(data);
    if (response.status !== 200) throw new Error((await response.text()).match(/(?:TypeError|Error):[^\n<]{0,300}/)?.[0] ?? `Interaction HTTP ${response.status}`);
    const result = await response.json(); assert.equal(result.data.flags, 64); assert.deepEqual(result.data.allowed_mentions.parse, []); return result.data.content;
  }
  const a = "a".repeat(32), b = "b".repeat(32);
  try {
    const alice = await connect("Alice"), bob = await connect("Bob"), old = await connect("Carol", false), isolated = await connect("Alice", true, "testing");
    const storage = await mf.unsafeGetDurableObjectStorage("cosmetics-check", "RelayRoom", { name: "friends" });
    const resetLimits = () => storage.exec("DELETE FROM cosmetics_limits");
    const ping = { type: 1, application_id: application };
    assert.deepEqual(await (await interaction(ping)).json(), { type: 1 });
    assert.equal((await interaction(ping, { headers: { "X-Signature-Ed25519": "0".repeat(128) } })).status, 401);
    assert.equal((await interaction(ping, { body: JSON.stringify({ ...ping, type: 2 }) })).status, 401);
    assert.equal((await interaction(ping, { timestamp: String(Math.floor(Date.now() / 1000) - 301) })).status, 401);
    assert.equal((await interaction(ping, { timestamp: String(Math.floor(Date.now() / 1000) + 62) })).status, 401);
    assert.equal((await interaction({ ...ping, application_id: "wrong" })).status, 401);
    assert.equal((await interaction({ ...ping, oversized: "x".repeat(96 * 1024 + 1) })).status, 401);
    assert.equal((await interaction(ping, { headers: { "X-Signature-Timestamp": "NaN" } })).status, 401);
    assert.match(await call(command("show", {}, aliceActor, { channel_id: "1552585927086440459" })), /Use cosmetic commands/);
    assert.match(await call(command("show", {}, aliceActor, { channel_id: undefined })), /Use cosmetic commands/);
    const initial = await request(bob, { type: "cosmetics_get", uuids: [a, b] });
    assert.ok(initial.records.every(it => it.revision === 0 && it.name === null && it.scale === 1));
    assert.equal((await request(alice, { type: "cosmetics_get", uuids: Array(17).fill(a) })).error, "invalid_lookup");
    const link = await request(alice, { type: "cosmetics_link", uuid: b }); // Supplied UUID cannot change challenge ownership.
    assert.match(link.code, /^[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}$/); assert.ok(link.expiresAt > Date.now());
    assert.equal((await request(alice, { type: "cosmetics_link" })).error, "rate_limited");
    const linkCommand = command("link", { code: link.code });
    assert.match(await call(linkCommand), /linked/);
    assert.equal((await request(alice, { type: "cosmetics_link" })).error, "rate_limited");
    assert.match(await call(linkCommand), /already handled/);
    assert.match(await call(command("link", { code: link.code }, bobActor)), /expired|already used/);
    const links = await storage.exec("SELECT * FROM cosmetics_links"); assert.equal(links.length, 1); assert.equal(links[0].uuid, a);
    assert.match(await call(command("set", { name: "Hawk", x: 1.5, y: 1.5, z: 1.5 })), /Saved/);
    let event = await bob.next(); assert.equal(event.type, "cosmetics_event"); assert.equal(event.record.uuid, a); assert.equal(event.record.name, "Hawk"); assert.equal(event.record.scale, 1.5);
    assert.deepEqual(Object.keys(event.record).sort(), ["name", "nameStyle", "revision", "scale", "scaleX", "scaleY", "scaleZ", "updatedAt", "uuid"]);
    old.ws.send("ping"); assert.equal(await old.next(), "pong"); // Older clients never receive new packet types.
    const legacyClient = await connect("Carol", true, "friends", 1);
    const legacyPayload = (await request(legacyClient, { type: "cosmetics_get", uuids: [a] })).records[0];
    assert.deepEqual(Object.keys(legacyPayload).sort(), ["name", "revision", "scale", "updatedAt", "uuid"]);
    assert.match(await call(command("set", { name: "Stolen", target: a }, bobActor)), /Unsupported/);
    assert.match(await call(command("set", { name: "Unlinked" }, bobActor)), /Link your/);
    assert.match(await call(command("show")), /Hawk/);
    for (const fields of [{ x: 0.09 }, { x: 3.01 }, { x: "NaN" }, { x: null }, { name: "a\nb" }, { name: "§aName" }, { name: "/op player" }, { name: "@everyone" }, { name: "<click:run>" }, { name: "x".repeat(33) }]) {
      await resetLimits(); assert.doesNotMatch(await call(command("set", fields)), /^Saved/);
    }
    await resetLimits();
    const expired = await request(bob, { type: "cosmetics_link" });
    await storage.exec("UPDATE cosmetics_challenges SET expires = ? WHERE uuid = ?", Date.now() - 1, b);
    assert.match(await call(command("link", { code: expired.code }, bobActor)), /expired/);
    const concurrentCode = await request(bob, { type: "cosmetics_link" });
    const linksRace = await Promise.all([call(command("link", { code: concurrentCode.code }, bobActor)), call(command("link", { code: concurrentCode.code }, adminActor))]);
    assert.equal(linksRace.filter(it => it.includes("Minecraft account linked")).length, 1);
    assert.equal((await storage.exec("SELECT * FROM cosmetics_links WHERE uuid = ?", b)).length, 1);
    await mf.unsafeEvictDurableObject("cosmetics-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    await resetLimits();
    assert.match(await call(command("reset")), /Saved/); event = await bob.next();
    assert.equal(event.record.name, null); assert.equal(event.record.scale, 1); assert.equal(event.record.revision, 2);
    const one = command("set", { name: "First", x: .5 }), two = command("set", { name: "Second", x: 2 });
    assert.ok((await Promise.all([call(one), call(two)])).every(it => it.startsWith("Saved")));
    const updates = [await bob.next(), await bob.next()]; assert.deepEqual(updates.map(it => it.record.revision), [3, 4]);
    assert.match(await call(command("reset")), /Saved/); const reset = (await bob.next()).record; assert.equal(reset.revision, 5);
    assert.match(await call(one), /already handled/); bob.ws.send("ping"); assert.equal(await bob.next(), "pong");
    assert.equal((await request(isolated, { type: "cosmetics_get", uuids: [a] })).records[0].revision, 0); // Room isolation.
    const late = await connect("Bob"); assert.deepEqual((await request(late, { type: "cosmetics_get", uuids: [a] })).records[0], reset);
    await resetLimits();
    assert.match(await call(command("set", { target: b, name: "Admin set" }, adminActor)), /Unsupported/); // No target bypass, including former administrators.
    const styled = JSON.stringify({ text: "✦ Sky", color: "gold", bold: true, extra: [{ text: " Hawk ☠", color: "#67ccf2", italic: true }] });
    assert.match(await call(command("set", { name: styled, x: 1.6 })), /Saved/);
    let update = (await late.next()).record;
    assert.equal(update.name, "✦ Sky Hawk ☠"); assert.equal(update.nameStyle.bold, true);
    assert.equal(update.scaleX, 1.6); assert.equal(update.scaleY, 1); assert.equal(update.scaleZ, 1);
    const pasted = String.raw`["",{color:"dark\_blue",text:"❻"},{color:"dark\_red",text:"❼ "},{color:"aqua",text:"MisterKrister "},{color:"dark\_red",text:"❻"},{color:"dark\_blue",text:"❼"}]`;
    assert.match(await call(command("set", { name: pasted })), /Saved/);
    update = (await late.next()).record;
    assert.equal(update.name, "❻❼ MisterKrister ❻❼");
    assert.deepEqual(update.nameStyle.extra.map(part => part.color), ["dark_blue", "dark_red", "aqua", "dark_red", "dark_blue"]);
    assert.match(await call(command("set", { y: .8 })), /Saved/);
    update = (await late.next()).record; assert.equal(update.scaleX, 1.6); assert.equal(update.scaleY, .8); assert.equal(update.scaleZ, 1);
    assert.match(await call(command("set", { name: "N".repeat(32) })), /Saved/);
    const longName = (await late.next()).record;
    assert.equal(longName.name.length, 32);
    let legacyUpdate;
    for (let i = 0; i < 10; i++) {
      legacyUpdate = (await legacyClient.next()).record;
      if (legacyUpdate.revision === longName.revision) break;
    }
    assert.equal(legacyUpdate.revision, longName.revision); assert.equal(legacyUpdate.name, null);
    assert.deepEqual(Object.keys(legacyUpdate).sort(), ["name", "revision", "scale", "updatedAt", "uuid"]);
    for (const name of ['{"text":"Hawk","clickEvent":{"action":"run_command","command":"/op"}}', '{"text":"Hawk","font":"evil:font"}', '{"translate":"evil"}', '{"text":"Hawk","bold":"true"}', '["",{text:"Hawk",font:"evil:font"}]', '　'.repeat(2800) + 'Hawk']) {
      await resetLimits(); assert.doesNotMatch(await call(command("set", { name })), /^Saved/);
    }
    await resetLimits();
    await request(late, { type: "cosmetics_subscribe", enabled: false });
    await resetLimits(); assert.match(await call(command("set", { name: "No push" })), /Saved/);
    late.ws.send("ping"); assert.equal(await late.next(), "pong");
    await resetLimits();
    for (let i = 0; i < 6; i++) assert.match(await call(command("show")), /Minecraft UUID/);
    assert.match(await call(command("show")), /Too many/);
    await resetLimits();
    await storage.exec("UPDATE cosmetics_challenges SET issued = 0");
    const pendingAlice = await request(alice, { type: "cosmetics_link" });
    const transferBob = await request(late, { type: "cosmetics_link" });
    assert.match(await call(command("link", { code: transferBob.code })), /Unlink your current/);
    assert.match(await call(command("unlink", { confirm: false })), /confirm:true/);
    assert.match(await call(command("unlink", { confirm: true })), /unlinked and cosmetics reset/);
    assert.equal((await storage.exec("SELECT * FROM cosmetics_links WHERE uuid = ?", a)).length, 0);
    assert.match(await call(command("link", { code: pendingAlice.code }, adminActor)), /expired|already used/);
    assert.match(await call(command("set", { x: 1.1 })), /Link your/);
    await resetLimits();
    assert.match(await call(command("link", { code: transferBob.code.toLowerCase().replaceAll("-", "") })), /linked/);
    assert.equal((await storage.exec("SELECT discord FROM cosmetics_links WHERE uuid = ?", b))[0].discord, aliceActor);
    assert.match(await call(command("show", {}, bobActor)), /Link your/);
    assert.match(await call(command("show", {}, adminActor)), /Link your/);
    const resetA = (await request(alice, { type: "cosmetics_get", uuids: [a] })).records[0];
    assert.equal(resetA.name, null); assert.equal(resetA.scaleX, 1); assert.equal(resetA.scaleY, 1); assert.equal(resetA.scaleZ, 1);
    assert.ok(resetA.revision > reset.revision);
    await resetLimits();
    assert.match(await call(command("set", { x: .1, y: 3, z: 2.5 })), /Saved/);
    const expanded = (await request(alice, { type: "cosmetics_get", uuids: [b] })).records[0];
    assert.equal(expanded.scaleX, .1); assert.equal(expanded.scaleY, 3); assert.equal(expanded.scaleZ, 2.5);
    const previousClient = await connect("Carol", true, "friends", 2);
    const compatible = (await request(previousClient, { type: "cosmetics_get", uuids: [b] })).records[0];
    assert.equal(compatible.scaleX, .5); assert.equal(compatible.scaleY, 2); assert.equal(compatible.scaleZ, 2);
    assert.equal(compatible.scale, 2);
    const oldestClient = await connect("Carol", true, "friends", 1);
    const oldest = (await request(oldestClient, { type: "cosmetics_get", uuids: [b] })).records[0];
    assert.equal(oldest.scale, 2);
    // Simulate the old durable schema in the isolated testing room, then wake/migrate it twice.
    const legacyStorage = await mf.unsafeGetDurableObjectStorage("cosmetics-check", "RelayRoom", { name: "testing" });
    await legacyStorage.exec("INSERT INTO cosmetics_public (uuid, display_name, scale, revision, updated_at) VALUES (?, 'Legacy', 1.3, 7, ?)", a, Date.now());
    for (const field of ["name_json", "scale_x", "scale_y", "scale_z"]) await legacyStorage.exec(`ALTER TABLE cosmetics_public DROP COLUMN ${field}`);
    for (let wake = 0; wake < 2; wake++) {
      await mf.unsafeEvictDurableObject("cosmetics-check", "RelayRoom", { name: "testing", webSockets: "hibernate" });
      const migrated = (await request(isolated, { type: "cosmetics_get", uuids: [a] })).records[0];
      assert.equal(migrated.revision, 7); assert.equal(migrated.scaleX, 1.3); assert.equal(migrated.scaleY, 1.3); assert.equal(migrated.scaleZ, 1.3);
      assert.deepEqual(migrated.nameStyle, { text: "Legacy" });
    }
    const publicRows = await storage.exec("SELECT * FROM cosmetics_public");
    assert.ok(publicRows.every(row => !Object.hasOwn(row, "discord") && !Object.hasOwn(row, "actor")));
    const challenges = await storage.exec("SELECT * FROM cosmetics_challenges"); assert.ok(challenges.every(row => !Object.hasOwn(row, "code")));
    // Resolve off-lobby names through authenticated relay identities, without a global registry dump.
    let namesPeer = await connect("InvalidPolicy"), nameRequests = 0;
    const namesRequest = async fields => {
      if (nameRequests === 6) { namesPeer = await connect("InvalidPolicy"); nameRequests = 0; }
      nameRequests++; return request(namesPeer, { type: "cosmetics_get", ...fields });
    };
    const named = await namesRequest({ names: ["bOb", "UnknownPlayer"] });
    assert.deepEqual(named.identities, [{ uuid: b, name: "Bob" }]); assert.equal(named.records.length, 1);
    assert.equal(named.records[0].uuid, b); assert.deepEqual(Object.keys(named.identities[0]).sort(), ["name", "uuid"]);
    await resetLimits(); assert.match(await call(command("set", { name: "Across lobbies" })), /Saved/);
    const namedEvent = await namesPeer.next(); assert.equal(namedEvent.type, "cosmetics_event");
    assert.equal(namedEvent.record.uuid, b); assert.equal(namedEvent.record.name, "Across lobbies", "A name-only lookup subscribes to live updates");
    const mixed = await namesRequest({ uuids: [b, b], names: ["Bob"] });
    assert.equal(mixed.records.length, 1); assert.deepEqual(mixed.identities, [{ uuid: b, name: "Bob" }]);
    assert.deepEqual((await namesRequest({ uuids: [b], names: [] })).identities, []);
    assert.equal(Object.hasOwn(await namesRequest({ uuids: [b] }), "identities"), false, "UUID-only clients retain their original response shape");
    assert.deepEqual((await request(isolated, { type: "cosmetics_get", names: ["Bob"] })).identities, [], "Other rooms cannot resolve this room's identities");
    for (const conflicting of ["e".repeat(32), "f".repeat(32)]) {
      await storage.exec("INSERT INTO mod_users VALUES(?,?,?,?)", conflicting, "Ambiguous", Date.now(), Date.now());
    }
    const ambiguous = await namesRequest({ names: ["AMBIGUOUS"] });
    assert.deepEqual(ambiguous.identities, []); assert.deepEqual(ambiguous.records, [], "An ambiguous historical name cannot pick an arbitrary UUID");
    // A legitimate shared-cache upload is public data, not a verified identity association.
    const uploadedUuid = "9".repeat(32), uploadRequest = (sequence++).toString(16).padStart(32, "0");
    namesPeer.ws.send(JSON.stringify({ type: "stats_get", id: uploadRequest, name: "OnlyUpload", uuid: uploadedUuid }));
    const grant = await namesPeer.next(); assert.equal(grant.type, "stats_result"); assert.ok(grant.upload);
    namesPeer.ws.send(JSON.stringify({ type: "stats_put", id: (sequence++).toString(16).padStart(32, "0"), name: "OnlyUpload", uuid: uploadedUuid,
      upload: grant.upload, fetchedAt: Date.now(), stats: { state: "HIDDEN", classes: {}, completionTimes: {}, sPlusTimes: {}, completedFloors: [] } }));
    assert.equal((await namesPeer.next()).stored, true);
    const untrusted = await namesRequest({ names: ["OnlyUpload"] }); assert.deepEqual(untrusted.identities, []); assert.deepEqual(untrusted.records, []);
    for (const malformed of [{}, { names: [] }, { names: null }, { names: "Bob" }, { names: ["Bob", "bOB"] },
      { names: [""] }, { names: ["Bob Space"] }, { names: ["@everyone"] }, { names: [17] }, { names: ["x".repeat(17)] },
      { uuids: null, names: ["Bob"] }, { uuids: [b], names: Array.from({ length: 16 }, (_, i) => `Player${i}`) },
      { names: Array.from({ length: 17 }, (_, i) => `Player${i}`) }]) {
      assert.equal((await namesRequest(malformed)).error, "invalid_lookup");
    }
    const unauthenticated = await mf.dispatchFetch("http://localhost/websocket?room=friends", { headers: { Upgrade: "websocket", "CF-Connecting-IP": "127.9.0.1" } });
    const unverified = unauthenticated.webSocket, unverifiedNext = inbox(unverified); sockets.push(unverified); unverified.accept(); await unverifiedNext();
    const rejected = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error("Unauthenticated name lookup was not rejected")), 3000);
      unverified.addEventListener("close", event => { clearTimeout(timer); resolve(event); }, { once: true });
    });
    unverified.send(JSON.stringify({ type: "cosmetics_get", id: "1".repeat(32), names: ["Bob"] }));
    assert.equal((await rejected).code, 1008, "Name lookup still requires authenticated Minecraft identity");
    console.log("Cosmetics checks passed: signed/channel-scoped commands, self-only ownership, short codes, expiry/replay/relink/unlink, axis preservation, safe styling, durable migration, reset, hibernation and limits.");
    console.log("Cosmetic name resolution checks passed: targeted case-insensitive identities, live updates without a lobby roster, input bounds, ambiguity, upload spoof rejection, protocol compatibility and room isolation.");
  } finally { for (const ws of sockets) { try { ws.close(); } catch {} } await mf.dispose(); }
}
