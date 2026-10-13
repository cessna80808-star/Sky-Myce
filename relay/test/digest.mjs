import assert from "node:assert/strict";
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

/** Exercise the production protocol and SQLite cache with signed accounts and bounded fake upstreams. */
export async function checkDigest(script, proof) {
  const channels = { game: "111111111111111111", alpha: "222222222222222222" };
  let mode = "success", calls = 0;
  const fetched = [];
  const options = convertV4MiniflareOptions({ name: "digest-check", modules: true, script, compatibilityDate: "2026-09-21",
    bindings: { ROOMS: "friends,testing", SKYMYCE_ENVIRONMENT: "development", ALLOW_LEGACY_RELAY: "true", DISCORD_NEWS_TOKEN: "test-only-bot-token",
      DIGEST_GAME_CHANNEL: channels.game, DIGEST_ALPHA_CHANNEL: channels.alpha },
    durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
    outboundService: async request => {
      calls++;
      const url = new URL(request.url);
      assert.equal(url.origin, "https://discord.com");
      assert.equal(request.headers.get("Authorization"), "Bot test-only-bot-token");
      assert.equal(request.method, "GET");
      fetched.push(url);
      if (mode === "offline") return new Response("unavailable", { status: 503 });
      if (mode === "auth") return new Response("unavailable", { status: 403 });
      if (mode === "rate") return new Response("unavailable", { status: 429, headers: { "Retry-After": "900" } });
      if (mode === "malformed") return new Response("not-json");
      if (mode === "empty_content") return Response.json([{ id: "999999999999999999", timestamp: new Date().toISOString(), content: "", embeds: [] }]);
      if (mode === "invalid_timestamp") return Response.json([{ id: "999999999999999999", timestamp: "not-a-time", content: "Not valid news" }]);
      if (mode === "oversize") return new Response("x".repeat(262145));
      if (mode === "timeout") { await new Promise(resolve => setTimeout(resolve, 8500)); return new Response("[]"); }
      if (mode === "empty") return Response.json([]);
      const alpha = url.pathname.includes(channels.alpha);
      const content = `${alpha ? "Alpha" : "Game"} news ${"long title ".repeat(30)}\n${"Detailed change. ".repeat(300)}`;
      const message = { id: "333333333333333333", timestamp: new Date().toISOString(), content,
        message_reference: { guild_id: "444444444444444444", channel_id: "555555555555555555", message_id: "666666666666666666" } };
      const snapshot = { id: "333333333333333334", timestamp: new Date().toISOString(), content: "",
        message_snapshots: [{ message: { content: "", embeds: [{ title: "Forwarded update", description: "Snapshot description",
          fields: [{ name: "Change", value: "Fixed an issue" }] }] } }] };
      return Response.json([message, message, snapshot, { id: "bad", content: "ignored" }]);
    },
  });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 20, period: 60 } };
  configureTrafficLimits(options);
  const mf = new Miniflare(options), sockets = [];
  let sequence = 1000;
  function inbox(ws) {
    const queue = [], waiters = [];
    ws.addEventListener("message", event => {
      const data = event.data === "pong" ? "pong" : JSON.parse(event.data);
      if (waiters.length) waiters.shift()(data); else queue.push(data);
    });
    return () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Digest packet timed out")), 12000);
      waiters.push(value => { clearTimeout(timer); resolve(value); });
    });
  }
  async function connect(name = "Alice", room = "friends", rngMetadataVersion) {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, {
      headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.1.0.${sockets.length + 1}` },
    });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws);
    sockets.push(ws); ws.accept();
    const challenge = await next();
    ws.send(JSON.stringify({ ...proof(name, challenge.serverId), ...(rngMetadataVersion === 1 ? { rngMetadataVersion } : {}) }));
    const ready = await next();
    assert.equal(ready.digestNews, true); assert.equal(ready.rngFeed, true);
    assert.equal(ready.rngMetadataVersion, 1);
    return { ws, next };
  }
  async function request(client, data) {
    const id = (sequence++).toString(16).padStart(32, "0");
    client.ws.send(JSON.stringify({ id, ...data }));
    const result = await client.next();
    assert.equal(result.id, id);
    return result;
  }
  const news = (client, source = "game") => request(client, { type: "digest_news_get", source });
  const drop = (id, item = "NECRON_HANDLE", activity = "dungeon", showName = false) => ({ type: "rng_publish", showName,
    event: { id: id.toString(16).padStart(32, "0"), item, activity, occurredAt: Date.now() } });
  try {
    let alice = await connect();
    const first = await news(alice);
    assert.equal(first.status, "ready", JSON.stringify({ ...first, calls, fetched: fetched.map(url => url.pathname) })); assert.equal(calls, 1); assert.equal(first.items.length, 2);
    assert.equal(first.items.filter(item => item.id === "game:333333333333333333").length, 1);
    assert.ok(first.items.every(item => item.title.length <= 160 && item.content.length <= 2500));
    assert.ok(first.items.some(item => item.truncated));
    assert.ok(first.items.some(item => item.content.includes("Snapshot description")));
    assert.ok(Buffer.byteLength(JSON.stringify(first)) < 32768);
    assert.equal(first.items.find(item => item.truncated).url,
      "https://discord.com/channels/444444444444444444/555555555555555555/666666666666666666");
    assert.deepEqual((await news(alice)).items, first.items); assert.equal(calls, 1);
    assert.equal((await news(alice, "alpha")).source, "alpha"); assert.equal(calls, 2);
    assert.ok((await news(alice, "alpha")).items.some(item => item.title.startsWith("Alpha")));
    await mf.unsafeEvictDurableObject("digest-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    assert.deepEqual((await news(alice)).items, first.items); assert.equal(calls, 2);
    const storage = await mf.unsafeGetDurableObjectStorage("digest-check", "RelayRoom", { name: "friends" });
    const expireNews = () => storage.exec("UPDATE digest_news SET retry_at = 0, fetched_at = ?", Date.now() - 600000);
    for (const [failure, code] of [["offline", "offline"], ["auth", "authentication"], ["malformed", "invalid_response"],
      ["empty_content", "invalid_response"], ["invalid_timestamp", "invalid_response"],
      ["oversize", "invalid_response"], ["rate", "rate_limited"], ["timeout", "timeout"]]) {
      await expireNews(); alice = await connect(); mode = failure;
      let pending;
      if (failure === "timeout") {
        const watcher = await connect("Bob");
        pending = news(alice);
        watcher.ws.send("ping"); assert.equal(await watcher.next(), "pong"); // Slow news never blocks existing sockets.
      }
      const stale = await (pending ?? news(alice));
      assert.equal(stale.status, "stale"); assert.equal(stale.error, code); assert.deepEqual(stale.items, first.items);
      assert.ok(stale.retryAt > Date.now());
      const before = calls; await news(alice); assert.equal(calls, before); // Backoff prevents reopening spam.
    }
    mode = "success"; await expireNews(); alice = await connect();
    assert.equal((await news(alice)).items.length, 2); // Duplicate upstream content does not grow local history.
    assert.equal(fetched.at(-1).searchParams.get("after"), "333333333333333334");
    mode = "empty";
    const isolated = await connect("Carol", "testing");
    assert.equal((await news(isolated)).items.length, 0);
    assert.equal((await news(isolated)).status, "ready");

    let bob = await connect("Bob");
    const subscribed = await request(bob, { type: "rng_subscribe", enabled: true }); assert.deepEqual(subscribed.events, []);
    const firstDrop = drop(1);firstDrop.event={...firstDrop.event,rarity:'LEGENDARY',displayName:"Necron's Handle",floor:'F7'};
    assert.equal((await request(alice, firstDrop)).stored, true);
    const pushed = await bob.next();
    assert.equal(pushed.type, "rng_event"); assert.equal(pushed.event.player, "Anonymous"); assert.equal(pushed.event.unverified, true);
    assert.notEqual(pushed.event.id, firstDrop.event.id);
    assert.deepEqual(Object.keys(pushed.event).sort(), ["activity", "id", "item", "occurredAt", "player", "unverified"]);
    assert.ok(!JSON.stringify(pushed).includes("Alice")); assert.ok(!JSON.stringify(pushed).includes("a".repeat(32)));
    assert.equal((await request(alice, firstDrop)).stored, false);
    bob.ws.send("ping"); assert.equal(await bob.next(), "pong"); // Duplicate publish does not fan out.
    isolated.ws.send("ping"); assert.equal(await isolated.next(), "pong"); // Room isolation and opt-out.
    bob=await connect('Bob','friends',1);
    const metadataHistory=await request(bob,{type:'rng_subscribe',enabled:true});assert.equal(metadataHistory.events[0].rarity,'LEGENDARY');assert.equal(metadataHistory.events[0].displayName,"Necron's Handle");assert.equal(metadataHistory.events[0].floor,'F7');assert.equal(metadataHistory.events[0].player,'Anonymous');
    await mf.unsafeEvictDurableObject("digest-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    assert.equal((await request(alice, drop(2, "WARDEN_HEART", "slayer", true))).stored, true);
    assert.equal((await bob.next()).event.player, "Alice"); // Subscription survives hibernation; identity is server-stamped.
    const detailed=drop(3);detailed.event={...detailed.event,rarity:'MYTHIC',displayName:'Observed Handle',floor:'M7'};
    assert.equal((await request(alice, detailed)).stored, true);const detailedLive=(await bob.next()).event;assert.equal(detailedLive.rarity,'MYTHIC');assert.equal(detailedLive.floor,'M7');assert.equal(detailedLive.displayName,'Observed Handle');assert.equal(detailedLive.player,'Anonymous');
    alice = await connect();
    assert.equal((await request(alice, drop(4))).error, "rate_limited"); // Account limit survives reconnect.
    assert.equal((await request(alice, firstDrop)).stored, false);
    assert.equal((await request(bob, { type: "rng_subscribe", enabled: false })).events.length, 0);
    const catalog = drop(5, "WARDEN_HEART", "slayer");
    for (const invalid of [
      { ...catalog, event: { ...catalog.event, item: "ROTTEN_FLESH" } },
      { ...catalog, event: { ...catalog.event, occurredAt: Date.now() - 300001 } },
      { ...catalog, event: { ...catalog.event, occurredAt: Date.now() + 120000 } },
      { ...catalog, event: { ...catalog.event, activity: "__proto__" } },
      { ...catalog, event: { ...catalog.event, coordinates: [1, 2, 3] } },
      ...[{rarity:'invented'},{rarity:1},{displayName:''},{displayName:'x'.repeat(121)},{displayName:' not trimmed'},{displayName:'bad\nname'},{displayName:'§6Handle'},{displayName:'bad\u202ename'},{floor:'F8'},{floor:'F7'},{floor:1}].map(metadata=>({...catalog,event:{...catalog.event,...metadata}})),
      { ...catalog, inventory: "must not upload" },
      { ...catalog, showName: "false" },
    ]) {
      alice = await connect(); assert.equal((await request(alice, invalid)).error, "invalid_event", JSON.stringify(invalid));
    }
    alice = await connect();
    let limited = false;
    for (let i = 0; i < 8 && !limited; i++) limited = (await request(alice, { type: "rng_subscribe", enabled: true })).error === "rate_limited";
    assert.ok(limited);
    await request(alice, { type: "rng_subscribe", enabled: false }); // Privacy opt-out is honored even when rate limited.
    bob = await connect("Bob");
    assert.equal((await request(bob, drop(10))).stored, true);
    alice.ws.send("ping"); assert.equal(await alice.next(), "pong");
    const history = await request(bob, { type: "rng_subscribe", enabled: true });
    assert.equal(history.events.length, 4);
    assert.equal(history.events[0].item, "NECRON_HANDLE");
    assert.ok(history.events.every(event => event.unverified));
    assert.ok(history.events.every(event=>Object.keys(event).length===6),'Older strict recipients still get the original exact history shape');
    const rateRows = await storage.exec("SELECT reports FROM digest_rng_limits WHERE author = ?", "a".repeat(32));
    assert.equal(JSON.parse(rateRows[0].reports).length, 3);
    const payload = await storage.exec("SELECT event FROM digest_rng ORDER BY id LIMIT 1");
    assert.ok(!payload[0].event.includes("Alice")); // Stored public payload also respects anonymity.
    await storage.exec(`WITH RECURSIVE counter(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM counter WHERE n < 201)
      INSERT INTO digest_rng (author, source_id, received_at, event) SELECT 'synthetic', CAST(n AS TEXT), ?, ? FROM counter`,
    Date.now(), payload[0].event);
    assert.equal((await request(bob, drop(11))).stored, true);
    assert.equal((await storage.exec("SELECT COUNT(*) AS count FROM digest_rng"))[0].count, 200);
    alice = await connect();
    assert.equal((await request(alice, drop(12))).error, "rate_limited"); // Public history eviction cannot reset the account limit.
    assert.equal((await request(alice, firstDrop)).stored, false); // Or bypass its recent deduplication.
    assert.equal((await request(alice, { type: "rng_subscribe", enabled: true })).events.length, 30);
    await storage.exec("UPDATE digest_rng SET received_at = ?", Date.now() - 604800001);
    assert.equal((await request(alice, { type: "rng_subscribe", enabled: true })).events.length, 0);
    await storage.exec("UPDATE digest_rng_limits SET reports = ?, updated = ? WHERE author = ?",
      JSON.stringify([{ id: firstDrop.event.id, time: Date.now() - 330000 }]), Date.now() - 330000, "a".repeat(32));
    assert.equal((await request(alice, { ...firstDrop, event: { ...firstDrop.event, occurredAt: Date.now() - 270000 } })).stored, false);
    // Six-minute dedup retention includes a report's allowed one-minute clock skew, even after history eviction.
    console.log("Digest relay checks passed: news parsing/cache/backoff/bounds, signed opt-in RNG, privacy, validation, reconnect limits, hibernation.");
  } finally {
    for (const ws of sockets) { try { ws.close(); } catch {} }
    await mf.dispose();
  }
}
