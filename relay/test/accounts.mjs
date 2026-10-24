import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

export async function checkAdminBootstrap(script) {
  const initial = "978376257534328883", changed = "66666666666666666", guild = "11111111111111111";
  const options = (bootstrap, restart = 0) => {
    const value = convertV4MiniflareOptions({ name: "admin-bootstrap-check", modules: true, script, compatibilityDate: "2026-09-27",
      bindings: { DISCORD_GUILD_ID: guild, BOOTSTRAP_TEST_RESTART: String(restart), ...(bootstrap ? { ADMIN_BOOTSTRAP_USER_ID: bootstrap } : {}) },
      durableObjects: { ACCOUNT_STORE: { className: "AccountStore", useSQLite: true } } });
    value.unsafeInspectDurableObjects = true; return value;
  };
  const mf = new Miniflare(options());
  try {
    let store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    assert.equal(await store.isAdmin({ id: initial, guildId: guild }), false, "Missing bootstrap cannot grant access");
    let sql = await mf.unsafeGetDurableObjectStorage("admin-bootstrap-check", "AccountStore", { name: "global" });
    await sql.exec("INSERT INTO sky_accounts(account_id,discord_id,status,license_status,license_expires_at,device_hash,hwid_reset_at,generation,activated_at,created_at,last_seen,enrollment_blocked) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      randomUUID(), initial, "active", "revoked", 1700000000000, "f".repeat(64), 1600000000000, 7, 1500000000000, 1400000000000, 1500000000000, 1);
    const before = (await sql.exec("SELECT * FROM sky_accounts WHERE discord_id=?", initial))[0];
    await mf.setOptions(options(initial));
    store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    assert.equal(await store.isAdmin({ id: initial, guildId: guild }), true, "Administration is independent of the mod license/device");
    sql = await mf.unsafeGetDurableObjectStorage("admin-bootstrap-check", "AccountStore", { name: "global" });
    assert.deepEqual((await sql.exec("SELECT * FROM sky_accounts WHERE discord_id=?", initial))[0], { ...before, is_admin: 1 }, "Bootstrap changes only admin authority on an existing account");
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_admin_bootstrap"))[0].n, 1);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='admin_bootstrapped'"))[0].n, 1);
    await sql.exec("UPDATE sky_accounts SET is_admin=0 WHERE discord_id=?", initial);
    await mf.setOptions(options(initial, 1));
    store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    assert.equal(await store.isAdmin({ id: initial, guildId: guild }), false, "Restart must not undo a persisted revocation");
    await mf.setOptions(options(changed));
    store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    assert.equal(await store.isAdmin({ id: initial, guildId: guild }), false);
    assert.equal(await store.isAdmin({ id: changed, guildId: guild }), false, "Changing bootstrap configuration cannot seed another administrator");
    assert.equal(await store.getAccountByDiscordId(changed), null);
    sql = await mf.unsafeGetDurableObjectStorage("admin-bootstrap-check", "AccountStore", { name: "global" });
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='admin_bootstrapped'"))[0].n, 1);
    console.log("Persisted admin checks passed: role/allowlist denial, audited grants, concurrency/replay, one-time bootstrap, restarts and unchanged license/device state.");
  } finally { await mf.dispose(); }
}
