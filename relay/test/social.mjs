import assert from "node:assert/strict";
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

export async function checkSocial(script, proof) {
  const options = convertV4MiniflareOptions({ name: "social-check", modules: true, script, compatibilityDate: "2026-09-21",
    bindings: { ROOMS: "friends,testing", SKYMYCE_ENVIRONMENT: "development", ALLOW_LEGACY_RELAY: "true" }, durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
    outboundService: () => { throw new Error("Social protocol must not make unexpected outbound requests"); } });
  options.unsafeInspectDurableObjects = true;
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 20, period: 60 } };
  configureTrafficLimits(options);
  const mf = new Miniflare(options), sockets = [];
  function inbox(ws) {
    const queue = [], waiting = [];
    ws.addEventListener("message", event => { const value = event.data === "pong" ? "pong" : JSON.parse(event.data); if (waiting.length) waiting.shift()(value); else queue.push(value); });
    return () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Social packet timed out")), 3000);
      waiting.push(value => { clearTimeout(timer); resolve(value); });
    });
  }
  async function connect(name, capable = true, room = "friends") {
    const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.2.0.${sockets.length + 1}` } });
    assert.equal(response.status, 101);
    const ws = response.webSocket, next = inbox(ws); sockets.push(ws); ws.accept(); const challenge = await next();
    ws.send(JSON.stringify({ ...proof(name, challenge.serverId), userMessages: capable }));
    assert.equal((await next()).userMessages, true);
    return { ws, next };
  }
  try {
    const alice = await connect("Alice"), bob = await connect("Bob"), old = await connect("Carol", false);
    const id = "1".repeat(32);
    alice.ws.send(JSON.stringify({ type: "user_message", id, to: "Bob", text: "yes", from: "Forged" }));
    assert.deepEqual(await bob.next(), { type: "user_message", id, from: "Alice", uuid: "a".repeat(32), text: "yes" });
    alice.ws.send("ping"); assert.equal(await alice.next(), "pong"); // No false delivery acknowledgement from forwarding.
    await mf.unsafeEvictDurableObject("social-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
    bob.ws.send(JSON.stringify({ type: "ack", id, to: "Alice" })); assert.equal((await alice.next()).type, "ack");
    alice.ws.send(JSON.stringify({ type: "user_message", id: "2".repeat(32), to: "Carol", text: "hello" }));
    assert.equal((await alice.next()).code, "recipient_unsupported"); old.ws.send("ping"); assert.equal(await old.next(), "pong");
    alice.ws.send(JSON.stringify({ type: "user_message", id: "3".repeat(32), to: "Bob", text: "no" })); await bob.next();
    bob.ws.send(JSON.stringify({ type: "reject", id: "3".repeat(32), to: "Alice" })); assert.equal((await alice.next()).code, "recipient_disallowed");
    bob.ws.send(JSON.stringify({ type: "ack", id: "3".repeat(32), to: "Alice" })); assert.equal((await bob.next()).code, "invalid_receipt");
    alice.ws.send(JSON.stringify({ type: "user_message", id: "4".repeat(32), to: "Offline", text: "private" })); assert.equal((await alice.next()).code, "offline");
    let limited = false;
    for (let i = 10; i < 24; i++) {
      alice.ws.send(JSON.stringify({ type: "user_message", id: i.toString(16).padStart(32, "0"), to: "Offline", text: "private" }));
      const result = await alice.next(); limited ||= result.code === "rate_limited";
    }
    assert.ok(limited); alice.ws.send("ping"); assert.equal(await alice.next(), "pong");
    const storage = await mf.unsafeGetDurableObjectStorage("social-check", "RelayRoom", { name: "friends" });
    const tables = await storage.exec("SELECT name FROM sqlite_master WHERE type = 'table'");
    assert.ok(!tables.some(row => /message|chat/i.test(row.name) && !row.name.startsWith("global_chat_") && row.name !== "party_chat_consents"));
    for (const table of ["global_chat_messages", "global_chat_outbox"]) {
      assert.equal((await storage.exec(`SELECT COUNT(*) AS count FROM ${table}`))[0].count, 0);
    } // Private messages never enter the separate temporary global delivery outbox.
    console.log("Social checks passed: dedicated private messages, capability negotiation, receipts, rejection, rate limits, hibernation and no chat history.");
  } finally { for (const ws of sockets) { try { ws.close(); } catch {} } await mf.dispose(); }
}
