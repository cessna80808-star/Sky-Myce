import assert from "node:assert/strict";

if (!process.argv[2]) { console.error("Usage: node test/live.mjs <https backend origin>"); process.exit(2); }
const endpoint = new URL(process.argv[2]);
assert.equal(endpoint.protocol, "https:");
assert.deepEqual(await (await fetch(new URL("/health", endpoint))).json(), { service: "Sky-Myce relay", version: 3 });

async function rejectUnauthenticated(authenticate) {
  const url = new URL("/websocket?room=testing", endpoint);
  url.protocol = "wss:";
  await new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const timer = setTimeout(() => { socket.close(); reject(new Error("Live relay timed out")); }, 15000);
    socket.addEventListener("message", event => {
      const packet = JSON.parse(event.data);
      assert.equal(packet.type, "challenge");
      assert.equal(packet.protocol, 3);
      socket.send(JSON.stringify(authenticate
        ? { type: "authenticate", name: "RelayCheck", uuid: "0".repeat(32) }
        : { type: "message", id: "0".repeat(32), to: "Nobody", text: "unauthenticated test" }));
    });
    socket.addEventListener("close", event => {
      clearTimeout(timer);
      try { assert.equal(event.code, authenticate ? 4004 : 1008); resolve(); } catch (error) { reject(error); }
    });
    socket.addEventListener("error", () => { clearTimeout(timer); reject(new Error("Live WebSocket connection failed")); });
  });
}
await rejectUnauthenticated(false);
await rejectUnauthenticated(true);
console.log("Live relay passed health, WebSocket upgrade, and unauthenticated-account rejection checks.");
