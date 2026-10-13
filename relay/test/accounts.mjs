import assert from "node:assert/strict";
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { generateKeyPairSync, sign, verify, createHash, randomUUID } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { checkLaunchSessions } from "./launch-sessions.mjs";

export async function checkAccounts(script, minecraftProof) {
  const signer = generateKeyPairSync("ed25519"), install = generateKeyPairSync("ed25519");
  const publicKey = pair => pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
  const bindings = { ROOMS: "friends,testing", DISCORD_GUILD_ID: "11111111111111111", DEVELOPER_ROLE_ID: "22222222222222222",
    DEVELOPER_USER_IDS: "33333333333333333", ADMIN_BOOTSTRAP_USER_ID: "978376257534328883", DRM_KEY_ID: "test", DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signer) }),
    DRM_SIGNING_PRIVATE_KEY: signer.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"), SKY_MYCE_VERSION: "2.4.2" };
  const options = convertV4MiniflareOptions({ name: "accounts-check", modules: true, script, compatibilityDate: "2026-09-21", bindings,
    r2Buckets: ["PRIVATE_ARTIFACTS"], durableObjects: { ACCOUNT_STORE: { className: "AccountStore", useSQLite: true }, RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
    outboundService: () => { throw Error("Account authorization must not depend on unverified external identity"); } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 1000, period: 60 } };
  configureTrafficLimits(options);
  options.unsafeInspectDurableObjects = true;
  const mf = new Miniflare(options);
  try {
    const store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
    const sql = await mf.unsafeGetDurableObjectStorage("accounts-check", "AccountStore", { name: "global" });
    let interaction = 90000000000000000n, request = 1;
    const actor = (id = bindings.ADMIN_BOOTSTRAP_USER_ID, changes = {}) => ({ id, guildId: bindings.DISCORD_GUILD_ID, roles: [], interactionId: String(interaction++), ...changes });
    const target = "44444444444444444", other = "55555555555555555";
    const admin = (action, user = target, data = {}, who = actor()) => store.developerCommand(who, action, user, data);
    const user = (action, data = {}, id = target) => store.userCommand(actor(id), action, data);
    const post = async (path, body) => {
      const response = await mf.dispatchFetch(`https://example.test${path}`, { method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": `test-${request++}` }, body: JSON.stringify(body) });
      return { status: response.status, body: response.headers.get("Content-Type")?.includes("json") ? await response.json() : await response.arrayBuffer() };
    };
    const bootstrap = await store.getAccountByDiscordId(bindings.ADMIN_BOOTSTRAP_USER_ID);
    assert.equal(bootstrap.isAdmin, true); assert.equal(bootstrap.licenseStatus, "inactive");
    assert.equal(bootstrap.activated, false); assert.equal(bootstrap.deviceRegistered, false); assert.deepEqual(bootstrap.uuids, []);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM activation_keys"))[0].n, 0, "Seeding an admin grants no activation key");
    assert.equal(await store.isAdmin(actor()), true);
    for (const rejected of [actor("66666666666666666", { permissions: "8" }), actor("66666666666666666", { roles: [bindings.DEVELOPER_ROLE_ID] }),
      actor(bindings.DEVELOPER_USER_IDS), actor(other), actor(undefined, { guildId: "99999999999999999" })]) {
      assert.equal(await store.isAdmin(rejected), false);
      assert.equal((await admin("authorise", target, {}, rejected)).error, "admin_required");
      assert.equal((await admin("hwid_reset", target, {}, rejected)).error, "admin_required");
    }
    assert.equal((await admin("admin_add", "invalid")).error, "invalid_discord_id");
    assert.equal((await admin("admin_add", target, {}, actor(other))).error, "admin_required", "Target identity never authorizes the caller");
    assert.equal((await admin("admin_add", bindings.ADMIN_BOOTSTRAP_USER_ID, {}, actor(other))).error, "admin_required", "Selecting an administrator target grants no caller authority");
    const newAdmin = "77777777777777777";
    const grants = await Promise.all([admin("admin_add", newAdmin), admin("admin_add", newAdmin)]);
    assert.ok(grants.every(result => result.ok)); assert.equal(grants.filter(result => result.adminAdded).length, 1);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='admin_added'"))[0].n, 1);
    assert.equal(await store.isAdmin(actor(newAdmin)), true);
    const granted = await store.getAccountByDiscordId(newAdmin);
    assert.equal(granted.licenseStatus, "inactive"); assert.equal(granted.activated, false); assert.equal(granted.deviceRegistered, false);
    assert.equal((await admin("account_lookup", bindings.ADMIN_BOOTSTRAP_USER_ID, {}, actor(newAdmin))).ok, true, "New persisted authority applies immediately without a Discord role");
    const grantActor = actor(); assert.equal((await admin("admin_add", newAdmin, {}, grantActor)).adminAdded, false);
    assert.equal((await admin("admin_add", newAdmin, {}, grantActor)).error, "interaction_replayed");
    await sql.exec("UPDATE sky_accounts SET status='suspended' WHERE discord_id=?", newAdmin);
    assert.equal(await store.isAdmin(actor(newAdmin)), false);
    assert.equal((await admin("admin_add", other, {}, actor(newAdmin))).error, "admin_required");
    await sql.exec("UPDATE sky_accounts SET status='active' WHERE discord_id=?", newAdmin);
    await sql.exec("DELETE FROM account_limits");
    const issued = await admin("authorise"); assert.equal(issued.ok, true);
    assert.equal(issued.account.activated, false);
    assert.equal((await user("status", { isAdmin: true, is_admin: 1, admin: true })).account.isAdmin, false);
    assert.equal((await user("admin_add", { user: target })).error, "invalid_action");
    assert.equal(await store.isAdmin(actor(target)), false, "User command options cannot mutate admin authority");
    await sql.exec("DELETE FROM account_limits WHERE id=?", `user:${target}`);
    assert.match(issued.activationKey, /^[A-Za-z0-9_-]{43}$/);
    const keys = await sql.exec("SELECT * FROM activation_keys");
    assert.equal(keys.length, 1); assert.equal(keys[0].expires_at - keys[0].issued_at, 86400000);
    assert.equal(keys[0].key_hash, createHash("sha256").update(issued.activationKey).digest("hex"));
    assert.ok(!JSON.stringify(await sql.exec("SELECT * FROM account_audit")).includes(issued.activationKey));
    assert.equal((await admin("authorise")).error, "already_authorized");
    const duplicatedActor = actor(); assert.equal((await admin("license_status", target, {}, duplicatedActor)).ok, true);
    assert.equal((await admin("license_status", target, {}, duplicatedActor)).error, "interaction_replayed");
    assert.equal((await admin("license_status", target, {}, actor("66666666666666666", { roles: [bindings.DEVELOPER_ROLE_ID] }))).error, "admin_required");
    const input = { purpose: "activate", installationId: randomUUID(), publicKey: publicKey(install), deviceHash: "d".repeat(64),
      minecraftVersion: "26.1.2", loaderVersion: "1.0.0", modVersion: "2.4.2", clientNonce: randomUUID() };
    const challenge = async (changes = {}, signerPair = install) => {
      const info = { ...input, clientNonce: randomUUID(), ...changes }, result = await post("/auth/challenge", info); assert.equal(result.status, 200);
      const value = result.body;
      assert.equal(value.clientNonce, info.clientNonce);
      const signature = sign(null, Buffer.from(`Sky-Myce installation v1\n${info.purpose}\n${value.nonce}\n${info.installationId}\n${info.deviceHash}\n${info.minecraftVersion}\n${info.loaderVersion}\n${info.modVersion}\n${info.clientNonce}`), signerPair.privateKey).toString("base64");
      return { info, ...value, signature };
    };
    const body = (c, name = "Alice", activationKey) => ({ challengeId: c.challengeId, minecraftProof: minecraftProof(name, c.nonce), signature: c.signature,
      ...activationKey ? { activationKey } : {} });
    const pending = await challenge();
    assert.equal((await post("/auth/activate", { ...body(pending), discordId: target, accountId: issued.account.accountId })).body.error, "approval_required", "Claimed account IDs cannot replace a DM key or authenticated approval");
    const invalidProof = await challenge();
    assert.equal((await post("/auth/activate", { ...body(invalidProof, "Alice", issued.activationKey), signature: "invalid" })).body.error, "installation_proof_invalid");
    assert.equal((await post("/auth/activate", { ...body(invalidProof, "Alice", issued.activationKey), minecraftProof: { ...minecraftProof("Alice", invalidProof.nonce), proof: "invalid" } })).body.error, "minecraft_proof_invalid");
    for (const invalidKey of ["invalid", "x".repeat(43)]) {
      assert.equal((await post("/auth/activate", body(invalidProof, "Alice", invalidKey))).body.error, "invalid_activation_key");
    }
    for (let attempt = 4; attempt < 6; attempt++) assert.equal((await post("/auth/activate", body(invalidProof, "Alice", "x".repeat(43)))).body.error, "invalid_activation_key");
    assert.equal((await post("/auth/activate", body(invalidProof, "Alice", issued.activationKey))).body.error, "rate_limited", "A real key does not bypass the per-challenge proof limit");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", issued.activationId))[0].used_at, null);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM installations"))[0].n, 0);
    const concurrent = await challenge({ discordId: other, accountId: "untrusted", uuid: "b".repeat(32) });
    const race = await Promise.all([pending, concurrent].map(c => post("/auth/activate", {
      ...body(c, "Alice", issued.activationKey), discordId: other, accountId: "untrusted", uuid: "b".repeat(32), name: "Bob",
    })));
    assert.equal(race.filter(result => result.status === 200).length, 1);
    assert.equal(race.find(result => result.status !== 200).body.error, "invalid_activation_key", "Independent challenges cannot redeem the same DM key twice");
    const redeemed = race[0].status === 200 ? pending : concurrent;
    const lease = race.find(result => result.status === 200).body.lease;
    const parts = lease.split("."), claims = JSON.parse(Buffer.from(parts[1], "base64url"));
    assert.ok(verify(null, Buffer.from(`${parts[0]}.${parts[1]}`), signer.publicKey, Buffer.from(parts[2], "base64url")));
    assert.equal(claims.uuid, "a".repeat(32)); assert.equal(claims.exp - claims.iat, 300000); assert.equal(claims.offlineUntil - claims.exp, 900000);
    assert.equal(claims.discordId, target); assert.equal(claims.accountId, issued.account.accountId);
    assert.equal(claims.challengeId, redeemed.challengeId);
    assert.equal(claims.clientNonce, redeemed.info.clientNonce);
    const bound = (await sql.exec("SELECT discord_id,used_at FROM account_challenges WHERE id=?", redeemed.challengeId))[0];
    assert.equal(bound.discord_id, target); assert.notEqual(bound.used_at, null);
    assert.equal((await post("/auth/activate", body(redeemed, "Alice", issued.activationKey))).body.error, "invalid_challenge");
    assert.equal((await user("approve", { code: redeemed.approvalCode })).error, "invalid_approval");
    const freshRequest = await challenge({ purpose: "renew" });
    assert.notEqual(claims.clientNonce, freshRequest.info.clientNonce, "Replayed signed lease cannot match a fresh locally generated request nonce");
    const wrongNonceSignature = sign(null, Buffer.from(`Sky-Myce installation v1\nrenew\n${freshRequest.nonce}\n${input.installationId}\n${input.deviceHash}\n${input.minecraftVersion}\n${input.loaderVersion}\n${input.modVersion}\n${pending.info.clientNonce}`), install.privateKey).toString("base64");
    assert.equal((await post("/auth/renew", { ...body(freshRequest), signature: wrongNonceSignature })).body.error, "installation_proof_invalid");
    const renewed = await post("/auth/renew", body(freshRequest));
    assert.equal(renewed.status, 200, "Established installations renew without an activation key or another Discord approval");
    assert.equal(JSON.parse(Buffer.from(renewed.body.lease.split('.')[1], 'base64url')).discordId, target);
    assert.equal(claims.keyFingerprint, createHash("sha256").update(Buffer.from(input.publicKey, "base64")).digest("hex"));
    assert.equal((await sql.exec("SELECT used_at,used_installation FROM activation_keys"))[0].used_installation, input.installationId);
    const activationReplay = await challenge();
    assert.equal((await post("/auth/activate", body(activationReplay, "Alice", issued.activationKey))).body.error, "invalid_activation_key");
    const renewUnlinked = await challenge({ purpose: "renew" });
    assert.equal((await post("/auth/renew", body(renewUnlinked, "Bob"))).body.error, "minecraft_link_required");
    const secondUuid = await challenge();
    assert.equal((await post("/auth/activate", body(secondUuid, "Bob"))).body.error, "approval_required", "A registered installation alone cannot claim another Minecraft identity");
    assert.equal((await user("approve", { code: secondUuid.approvalCode })).ok, true);
    assert.equal((await user("approve", { code: secondUuid.approvalCode })).error, "invalid_approval");
    assert.equal((await post("/auth/activate", body(secondUuid, "Bob"))).status, 200);
    assert.equal((await store.getAccountByDiscordId(target)).uuids.length, 2);
    assert.equal((await store.canUseIrc(target)).ok, true);
    const link = await store.issueMinecraftLink('a'.repeat(32), 'Alice', target);
    assert.equal((await store.consumeMinecraftLink(other, link.code)).error, 'invalid_link');
    const linkRace = await Promise.all([store.consumeMinecraftLink(target, link.code), store.consumeMinecraftLink(target, link.code)]);
    assert.equal(linkRace.filter(result => result.ok).length, 1);
    assert.equal((await store.consumeMinecraftLink(target, link.code)).error, 'invalid_link');
    await assert.rejects(store.issueMinecraftLink('c'.repeat(32), 'Carol', target));
    await sql.exec("DELETE FROM account_limits WHERE id LIKE 'link:%'");
    const expiredLink = await store.issueMinecraftLink('a'.repeat(32), 'Alice', target);
    await sql.exec('UPDATE minecraft_links SET expires_at=0');
    assert.equal((await store.consumeMinecraftLink(target, expiredLink.code)).error, 'invalid_link');
    const otherIssued = await admin("authorise", other);
    const forwarded = await challenge(); await user("approve", { code: forwarded.approvalCode });
    assert.equal((await post("/auth/activate", body(forwarded, "Carol", otherIssued.activationKey))).body.error, "invalid_activation_key", "An unused key cannot override an already-approved Discord account");
    assert.equal((await sql.exec("SELECT discord_id FROM account_challenges WHERE id=?", forwarded.challengeId))[0].discord_id, target);
    const sameDevice = await challenge({ installationId: randomUUID() });
    assert.equal((await post("/auth/activate", body(sameDevice, "Carol", otherIssued.activationKey))).body.error, "device_owned");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", otherIssued.activationId))[0].used_at, null);
    assert.deepEqual((await sql.exec("SELECT discord_id,used_at FROM account_challenges WHERE id=?", sameDevice.challengeId))[0], { discord_id: null, used_at: null }, "Device failure rolls back account binding and challenge consumption");
    const otherInstall = generateKeyPairSync("ed25519");
    const uuidTheft = await challenge({ installationId: randomUUID(), deviceHash: "7".repeat(64), publicKey: publicKey(otherInstall) }, otherInstall);
    assert.equal((await post("/auth/activate", body(uuidTheft, "Alice", otherIssued.activationKey))).body.error, "minecraft_account_owned");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", otherIssued.activationId))[0].used_at, null);
    assert.equal(await store.getAccountByDevice("7".repeat(64)), null);
    await sql.exec("UPDATE activation_keys SET expires_at=? WHERE key_id=?", Date.now() - 1, otherIssued.activationId);
    const expired = await challenge({ installationId: randomUUID(), deviceHash: "7".repeat(64), publicKey: publicKey(otherInstall) }, otherInstall);
    assert.equal((await post("/auth/activate", body(expired, "Carol", otherIssued.activationKey))).body.error, "invalid_activation_key");
    const otherDevice = await challenge({ installationId: randomUUID(), deviceHash: "e".repeat(64) }); await user("approve", { code: otherDevice.approvalCode });
    assert.equal((await post("/auth/activate", body(otherDevice))).body.error, "HWID_MISMATCH");
    const tampered = `${parts[0]}.${Buffer.from(JSON.stringify({ ...claims, exp: claims.exp + 999999 })).toString("base64url")}.${parts[2]}`;
    const relayChallenge = "f".repeat(32), relaySignature = token => sign(null, Buffer.from(`Sky-Myce relay v3\n${relayChallenge}\n${token}`), install.privateKey).toString("base64");
    assert.equal((await store.authorizeSession({ uuid: claims.uuid, challenge: relayChallenge, lease: tampered, signature: relaySignature(tampered) })).ok, false);
    assert.equal((await store.authorizeSession({ uuid: "b".repeat(32), challenge: relayChallenge, lease, signature: relaySignature(lease) })).ok, false);
    assert.equal((await store.authorizeSession({ uuid: claims.uuid, challenge: relayChallenge, lease, signature: relaySignature(lease) })).ok, true);
    assert.equal((await store.authorizeSession({ uuid: claims.uuid, challenge: relayChallenge, lease, signature: relaySignature(lease) })).error, "challenge_replayed");
    const bucket = await mf.getR2Bucket("PRIVATE_ARTIFACTS"), artifact = Buffer.from("test-only jar bytes");
    const manifest = { product: "sky-myce", protocol: 3, minecraft: "26.1.2", version: "2.4.2", minimumLoaderVersion: "1.0.0",
      artifact: "releases/Sky-Myce-2.4.2.jar", sha256: createHash("sha256").update(artifact).digest("hex"), issuedAt: Date.now(), expiresAt: Date.now() + 86400000 };
    const payload = Buffer.from(JSON.stringify(manifest));
    const signed = { payload: payload.toString("base64url"), signature: sign(null, payload, signer.privateKey).toString("base64url"), kid: "test" };
    await bucket.put("releases/current.json", JSON.stringify(signed)); await bucket.put(manifest.artifact, artifact);
    const loaderBytes = Buffer.from("test-only bootstrap bytes");
    const loaderManifest = { product: "sky-myce-loader", protocol: 1, minecraft: "26.1.2", version: "1.0.5",
      artifact: "loaders/Sky-Myce-Loader-1.0.5.jar", size: loaderBytes.length, sha256: createHash("sha256").update(loaderBytes).digest("hex"),
      issuedAt: Date.now(), expiresAt: Date.now() + 86400000 };
    const loaderPayload = Buffer.from(JSON.stringify(loaderManifest));
    const loaderSigned = { payload: loaderPayload.toString("base64url"), signature: sign(null, loaderPayload, signer.privateKey).toString("base64url"), kid: "test" };
    await bucket.put("loaders/current.json", JSON.stringify(loaderSigned)); await bucket.put(loaderManifest.artifact, loaderBytes);
    assert.deepEqual(await (await mf.dispatchFetch("https://example.test/loader/bootstrap-release")).json(), loaderSigned);
    assert.equal((await mf.dispatchFetch("https://example.test/loader/bootstrap/renew")).status, 405);
    assert.equal((await post("/loader/bootstrap/renew", { version: "1.0.5" })).status, 403);
    const oldVersion = { purpose: "bootstrap-renew", loaderVersion: "0.9.0", modVersion: "0.1.0" };
    assert.equal((await post("/auth/challenge", { ...input, ...oldVersion, purpose: "renew" })).body.error, "version_denied");
    const loaderChallenge = await challenge(oldVersion);
    assert.equal((await post("/auth/renew", body(loaderChallenge))).body.error, "invalid_challenge", "Bootstrap proof cannot issue a feature lease");
    assert.equal((await post("/loader/bootstrap/renew", { ...body(loaderChallenge), version: "1.0.5", signature: "bad" })).body.error, "installation_proof_invalid");
    assert.equal((await post("/loader/bootstrap/renew", { ...body(loaderChallenge, "Carol"), version: "1.0.5" })).body.error, "minecraft_link_required");
    const updateCount = (await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='lease_issued'"))[0].n;
    const loaderBody = { ...body(loaderChallenge), version: "1.0.5" };
    const updates = await Promise.all([post("/loader/bootstrap/renew", loaderBody), post("/loader/bootstrap/renew", loaderBody)]);
    assert.equal(updates.filter(result => result.status === 200).length, 1);
    assert.deepEqual(Buffer.from(updates.find(result => result.status === 200).body), loaderBytes);
    assert.equal(updates.find(result => result.status !== 200).body.error, "invalid_challenge");
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='lease_issued'"))[0].n, updateCount);
    const loaderDenied = await challenge(oldVersion);
    await sql.exec("UPDATE sky_accounts SET status='suspended' WHERE discord_id=?", target);
    assert.equal((await post("/loader/bootstrap/renew", { ...body(loaderDenied), version: "1.0.5" })).body.error, "account_suspended");
    await sql.exec("UPDATE sky_accounts SET status='active' WHERE discord_id=?", target);
    assert.equal((await post("/loader/bootstrap/renew", { ...body(loaderDenied), version: "1.0.6" })).body.error, "release_changed");
    await bucket.put("loaders/current.json", JSON.stringify({ ...loaderSigned, signature: "invalid" }));
    assert.equal((await mf.dispatchFetch("https://example.test/loader/bootstrap-release")).status, 503);
    assert.equal((await post("/loader/bootstrap/renew", { ...body(loaderDenied), version: "1.0.5" })).status, 503);
    assert.equal((await sql.exec("SELECT used_at FROM account_challenges WHERE id=?", loaderDenied.challengeId))[0].used_at, null);
    await bucket.put("loaders/current.json", JSON.stringify(loaderSigned));
    const updateKey = await admin("authorise", target, { reissue: true });
    assert.equal(updateKey.ok, true);
    const updateActivation = await challenge({ ...oldVersion, purpose: "bootstrap-activate" });
    const updateActivationBody = { ...body(updateActivation, "Alice", updateKey.activationKey), version: "1.0.5" };
    const activationUpdates = await Promise.all([post("/loader/bootstrap/activate", updateActivationBody), post("/loader/bootstrap/activate", updateActivationBody)]);
    assert.equal(activationUpdates.filter(result => result.status === 200).length, 1, "Bootstrap enrollment consumes its activation key atomically");
    assert.equal((await sql.exec("SELECT used_installation FROM activation_keys WHERE key_id=?", updateKey.activationId))[0].used_installation, input.installationId);
    const afterUpdate = await challenge({ purpose: "renew" });
    assert.equal((await post("/auth/renew", body(afterUpdate))).status, 200, "The updated loader resumes without another activation code");
    console.log("Private loader update checks passed: old versions, signed manifest, caller proofs, replay/concurrency, revocation and code-free continuation without bootstrap feature leases.");
    assert.equal((await mf.dispatchFetch("https://example.test/loader/release")).status, 200);
    assert.equal((await mf.dispatchFetch("https://example.test/loader/artifact")).status, 405);
    const download = await challenge({ purpose: "manifest" });
    assert.equal((await post("/loader/manifest", { challengeId: download.challengeId, signature: download.signature, lease })).status, 200);
    assert.equal((await post("/loader/manifest", { challengeId: download.challengeId, signature: download.signature, lease })).status, 403);
    const jar = await challenge({ purpose: "artifact" });
    assert.deepEqual(Buffer.from((await post("/loader/artifact", { challengeId: jar.challengeId, signature: jar.signature, lease })).body), artifact);
    const badDownload = await challenge({ purpose: "manifest" });
    await bucket.put("releases/current.json", JSON.stringify({ ...signed, payload: Buffer.from(JSON.stringify({ ...manifest, sha256: "0".repeat(64) })).toString("base64url") }));
    assert.equal((await post("/loader/manifest", { challengeId: badDownload.challengeId, signature: badDownload.signature, lease })).status, 503);
    const session = { accountId: claims.accountId, uuid: claims.uuid, installationId: claims.installationId, generation: claims.generation };
    assert.equal((await store.checkSession(session)).ok, true);
    assert.equal((await admin("license_revoke")).ok, true);
    assert.equal((await store.checkSession(session)).error, "license_revoked");
    assert.equal((await store.canUseIrc(target)).error, "license_revoked");
    assert.equal((await admin("license_restore")).ok, true);
    assert.equal((await store.checkSession(session)).error, "authorization_revoked");
    assert.equal((await user("reset")).error, "device_reset_cooldown");
    await sql.exec("UPDATE sky_accounts SET hwid_reset_at=? WHERE discord_id=?", Date.now() - 7 * 86400000 - 1, target);
    const transferState = (await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0];
    await sql.exec("DELETE FROM account_limits WHERE id LIKE 'user:%'");
    const resets = await Promise.all([user('reset'), user('reset')]);
    assert.ok(resets.every(result => result.ok), "Preparing/reissuing a transfer code does not consume the hardware cooldown");
    for (const result of resets) {
      assert.match(result.activationKey, /^[A-Za-z0-9_-]{43}$/);
      assert.equal(result.account.deviceRegistered, true);
      assert.equal(result.invalidated, undefined, "Preparation keeps the existing session authorized");
    }
    const resetRows = await sql.exec("SELECT key_hash,issued_at,expires_at,hardware_transfer FROM activation_keys WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL", claims.accountId);
    assert.equal(resetRows.length, 1);
    const resetKey = resets.find(result => createHash('sha256').update(result.activationKey).digest('hex') === resetRows[0].key_hash);
    assert.ok(resetKey, "Only the latest issued transfer code remains active");
    assert.equal(resetRows[0].key_hash, createHash('sha256').update(resetKey.activationKey).digest('hex'));
    assert.equal(resetRows[0].hardware_transfer, 1);
    assert.equal(resetRows[0].expires_at - resetRows[0].issued_at, 86400000);
    assert.ok(!JSON.stringify(await sql.exec('SELECT * FROM account_audit')).includes(resetKey.activationKey));
    assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0], transferState);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM installations WHERE revoked_at IS NULL"))[0].n, 1);
    for (const purpose of ['renew', 'bootstrap-renew']) {
      const retrySaved = await challenge({ purpose });
      assert.equal((await store.authenticateInstallation(body(retrySaved), purpose)).ok, true, 'Preparing a transfer leaves the enrolled device usable');
    }
    const sameInstallation = await challenge();
    const transferAuditBefore = (await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='device_transferred'"))[0].n;
    assert.equal((await post('/auth/activate', body(sameInstallation, 'Alice', resetKey.activationKey))).status, 200, 'A transfer code also accepts the existing physical device');
    assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0], transferState, 'Same-device activation does not consume the hardware-transfer allowance');
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='device_transferred'"))[0].n, transferAuditBefore);
    const replayReset = await challenge();
    assert.equal((await post('/auth/activate', body(replayReset, 'Alice', resetKey.activationKey))).body.error, 'invalid_activation_key');
    const restoredRenew = await challenge({ purpose: 'renew' });
    assert.equal((await post('/auth/renew', body(restoredRenew))).status, 200);
    const moveReset = await admin('hwid_reset');
    const resetInstall = generateKeyPairSync("ed25519");
    const rebound = await challenge({ installationId: randomUUID(), publicKey: publicKey(resetInstall), deviceHash: "9".repeat(64) }, resetInstall);
    assert.equal((await post("/auth/activate", body(rebound, "Alice", moveReset.activationKey))).status, 200);
    const selfResetAt = (await store.getAccountByDiscordId(target)).hwidResetAt;
    const adminReset = await admin("hwid_reset");
    assert.equal(adminReset.ok, true);
    assert.match(adminReset.activationKey, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(adminReset.account.licenseStatus, resetKey.account.licenseStatus);
    assert.equal((await store.getAccountByDiscordId(target)).hwidResetAt, selfResetAt);
    const unboundState = (await sql.exec("SELECT device_hash,hwid_reset_at,generation FROM sky_accounts WHERE discord_id=?", target))[0];
    const unboundCode = await user("reset"); assert.equal(unboundCode.ok, true, "An administrator-cleared account can request enrollment without waiting");
    assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,generation FROM sky_accounts WHERE discord_id=?", target))[0], unboundState, 'Requesting enrollment does not consume a transfer');
    assert.equal((await sql.exec("SELECT hardware_transfer FROM activation_keys WHERE key_id=?", unboundCode.activationId))[0].hardware_transfer, 0);
    assert.notEqual((await sql.exec('SELECT revoked_at FROM activation_keys WHERE key_id=?', adminReset.activationId))[0].revoked_at, null);
    assert.equal((await admin("hwid_revoke")).ok, true);
    assert.equal((await user("reset")).error, "device_revoked");
    const afterRevoke = await challenge({ installationId: randomUUID(), publicKey: publicKey(resetInstall), deviceHash: "9".repeat(64) }, resetInstall);
    await user("approve", { code: afterRevoke.approvalCode });
    assert.equal((await post("/auth/activate", body(afterRevoke))).body.error, "device_revoked");
    const blockedDeviceKey = await admin("authorise", target, { reissue: true });
    const blockedDevice = await challenge({ installationId: randomUUID(), publicKey: publicKey(resetInstall), deviceHash: "9".repeat(64) }, resetInstall);
    assert.equal((await post("/auth/activate", body(blockedDevice, "Alice", blockedDeviceKey.activationKey))).body.error, "device_revoked");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", blockedDeviceKey.activationId))[0].used_at, null);
    assert.equal((await admin("hwid_reset")).ok, true);
    const migration = [{ discord: other, uuid: "8".repeat(32) }];
    await sql.exec('DELETE FROM account_limits');
    const revokedKey = await admin('authorise', other, { reissue: true });
    assert.equal((await admin('license_revoke', other)).ok, true);
    const revokedAttempt = await challenge({ installationId: randomUUID(), publicKey: publicKey(otherInstall), deviceHash: "7".repeat(64) }, otherInstall);
    assert.equal((await post('/auth/activate', body(revokedAttempt, 'Carol', revokedKey.activationKey))).body.error, 'invalid_activation_key');
    assert.equal((await admin('license_restore', other)).ok, true);
    const replacedKey = await admin('authorise', other, { reissue: true });
    const replacement = await admin('authorise', other, { reissue: true });
    assert.equal(replacement.ok, true);
    assert.notEqual(replacement.activationKey, otherIssued.activationKey);
    assert.notEqual((await sql.exec('SELECT revoked_at FROM activation_keys WHERE key_id=?', otherIssued.activationId))[0].revoked_at, null);
    const replacementAttempt = await challenge({ installationId: randomUUID(), publicKey: publicKey(otherInstall), deviceHash: "7".repeat(64) }, otherInstall);
    assert.equal((await post('/auth/activate', body(replacementAttempt, 'Carol', replacedKey.activationKey))).body.error, 'invalid_activation_key');
    assert.equal((await post('/auth/activate', body(replacementAttempt, 'Carol', replacement.activationKey))).status, 200, 'The replacement DM code activates without Discord approval');
    assert.equal((await store.getAccountByUuid('c'.repeat(32))).discordId, other);
    assert.ok(!JSON.stringify(await sql.exec('SELECT * FROM account_audit')).includes(replacement.activationKey));
    assert.equal((await store.importCosmeticLinks("friends", migration)).imported, 1);
    assert.equal((await store.unlinkMinecraftUuid(other, migration[0].uuid)).ok, true);
    assert.equal((await store.importCosmeticLinks("friends", migration)).imported, 0);
    assert.equal((await store.importCosmeticLinks("testing", migration)).imported, 0);
    assert.equal(await store.getAccountByUuid(migration[0].uuid), null);
    const transfer = await admin("hwid_bind", other, { deviceHash: input.deviceHash });
    assert.equal(transfer.ok, true); assert.ok(transfer.invalidatedAccounts.includes(claims.accountId));
    assert.equal((await store.getAccountByDevice(input.deviceHash)).discordId, other);
    assert.equal((await admin("account_unlink", other)).ok, true);
    assert.equal((await store.getAccountByDiscordId(other)).licenseStatus, "inactive");
    assert.equal((await post("/auth/challenge", { ...input, modVersion: "0.0.1" })).status, 400);
    assert.equal((await admin("audit", target, {}, actor(other))).error, "admin_required");
    console.log("Account/DRM checks passed: single DM-code activation without Discord approval, real proofs, bound legacy approvals, global ownership, single-use races, replay, reset, revocation, private signed artifacts");
  } finally { await mf.dispose(); }
  await checkAdminBootstrap(script);
  await checkLaunchSessions(script, minecraftProof);
}

async function checkAdminBootstrap(script) {
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
