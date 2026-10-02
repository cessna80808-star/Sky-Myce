import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify, createHash, randomUUID, randomBytes } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

/** Exercise persistent hardware policy with signed proofs and SQLite; OS lifecycle cases retain the same stored identity. */
export async function checkLaunchSessions(script, minecraftProof) {
  const signer = generateKeyPairSync("ed25519"), install = generateKeyPairSync("ed25519");
  const publicKey = pair => pair.publicKey.export({ type: "spki", format: "der" }).toString("base64");
  const target = "44444444444444444", windowsUser = "55555555555555555", guild = "11111111111111111", administrator = "978376257534328883";
  const bindings = { ROOMS: "friends,testing", DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: administrator,
    DRM_KEY_ID: "test", DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signer) }),
    DRM_SIGNING_PRIVATE_KEY: signer.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"),
    SKY_MYCE_VERSION: "2.4.2", MINIMUM_LOADER_VERSION: "1.0.10" };
  const options = (policy = "true", changes = {}) => {
    const value = convertV4MiniflareOptions({ name: "launch-session-check", modules: true, script, compatibilityDate: "2026-09-27",
      bindings: { ...bindings, ...(policy === null ? {} : { LINUX_REQUIRE_REAUTH_ON_EVERY_LAUNCH: policy }), ...changes },
      r2Buckets: ["PRIVATE_ARTIFACTS"], durableObjects: { ACCOUNT_STORE: { className: "AccountStore", useSQLite: true } },
      outboundService: () => { throw Error("Launch authorization must not trust external unverified identity"); } });
    value.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 1000, period: 60 } };
    value.unsafeInspectDurableObjects = true; return value;
  };
  const mf = new Miniflare(options());
  try {
    let store, sql;
    const connect = async () => {
      store = (await mf.getDurableObjectNamespace("ACCOUNT_STORE")).getByName("global");
      sql = await mf.unsafeGetDurableObjectStorage("launch-session-check", "AccountStore", { name: "global" });
    };
    await connect();
    let interaction = 90000000000000000n, request = 1;
    const actor = (id = administrator) => ({ id, guildId: guild, roles: [], interactionId: String(interaction++) });
    const admin = (action, user = target, data = {}) => store.developerCommand(actor(), action, user, data);
    const post = async (path, body, ip = `lifecycle-test-${request++}`) => {
      const response = await mf.dispatchFetch(`https://example.test${path}`, { method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip }, body: JSON.stringify(body) });
      return { status: response.status, body: response.headers.get("Content-Type")?.includes("json") ? await response.json() : await response.arrayBuffer() };
    };
    const firstSession = randomUUID();
    const input = { purpose: "renew", installationId: randomUUID(), publicKey: publicKey(install), deviceHash: "d".repeat(64),
      minecraftVersion: "26.1.2", loaderVersion: "1.0.10", modVersion: "2.4.2", clientNonce: randomUUID(),
      platform: "linux", launchSessionId: firstSession, startSession: true };
    const message = (info, nonce) => {
      const fields = `${info.purpose}\n${nonce}\n${info.installationId}\n${info.deviceHash}\n${info.minecraftVersion}\n${info.loaderVersion}\n${info.modVersion}\n${info.clientNonce}`;
      if (info.hardwareHash !== undefined) return `Sky-Myce installation v3\n${fields}\n${info.hardwareHash}${info.platform === undefined ? "" : `\n${info.platform}\n${info.launchSessionId}\n${info.startSession}`}`;
      return info.platform === undefined ? `Sky-Myce installation v1\n${fields}` :
        `Sky-Myce installation v2\n${fields}\n${info.platform}\n${info.launchSessionId}\n${info.startSession}`;
    };
    const challenge = async (changes = {}, key = install, ip) => {
      const info = { ...input, clientNonce: randomUUID(), ...changes }, result = await post("/auth/challenge", info, ip);
      assert.equal(result.status, 200, JSON.stringify(result.body));
      return { info, ...result.body, signature: sign(null, Buffer.from(message(info, result.body.nonce)), key.privateKey).toString("base64") };
    };
    const body = (c, name = "Alice", activationKey) => ({ challengeId: c.challengeId, signature: c.signature,
      minecraftProof: minecraftProof(name, c.nonce), ...(activationKey ? { activationKey } : {}) });
    const claims = token => {
      const parts = token.split(".");
      assert.ok(verify(null, Buffer.from(`${parts[0]}.${parts[1]}`), signer.publicKey, Buffer.from(parts[2], "base64url")));
      return JSON.parse(Buffer.from(parts[1], "base64url"));
    };
    const signedLease = value => {
      const header = Buffer.from(JSON.stringify({ alg: "EdDSA", typ: "JWT", kid: "test" })).toString("base64url");
      const payload = `${header}.${Buffer.from(JSON.stringify(value)).toString("base64url")}`;
      return `${payload}.${sign(null, Buffer.from(payload), signer.privateKey).toString("base64url")}`;
    };
    const hardware = async () => ({
      account: (await sql.exec("SELECT device_hash,hardware_hash,hwid_reset_at,hwid_bound_at,generation,activated_at FROM sky_accounts WHERE discord_id=?", target))[0],
      installations: await sql.exec("SELECT installation_id,device_hash,hardware_hash,fingerprint,public_key,registered_at,revoked_at FROM installations ORDER BY installation_id"),
      devices: await sql.exec("SELECT * FROM account_devices ORDER BY device_hash"),
      resetCount: (await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action IN ('device_reset','device_override','device_registered','device_transferred')"))[0].n,
    });
    const authorizeRelay = (token, key = install) => {
      const nonce = randomBytes(16).toString("hex"), value = claims(token);
      return store.authorizeSession({ uuid: value.uuid, lease: token, challenge: nonce,
        signature: sign(null, Buffer.from(`Sky-Myce relay v3\n${nonce}\n${token}`), key.privateKey).toString("base64") });
    };
    const restart = async (policy = "true", changes = {}) => { await mf.setOptions(options(policy, changes)); await connect(); };

    const issued = await admin("authorise"); assert.equal(issued.ok, true);
    const legacy = { platform: undefined, launchSessionId: undefined, startSession: undefined };
    assert.equal((await post("/auth/challenge", { ...input, ...legacy })).status, 200, "Old platform-free proofs remain compatible with persistent device policy");
    for (const malformed of [{ platform: ["linux"] }, { platform: null }, { platform: "Linux" }, { platform: "linux\nwindows" },
      { launchSessionId: undefined }, { launchSessionId: "invalid" }, { launchSessionId: "00000000-0000-1000-8000-000000000000" },
      { startSession: undefined }, { startSession: "true" }, { hardwareHash: null }, { hardwareHash: [] },
      { hardwareHash: "a".repeat(63) }, { hardwareHash: "A".repeat(64) }, { hardwareHash: `${"a".repeat(64)}\n` }]) {
      assert.equal((await post("/auth/challenge", { ...input, ...malformed })).body.error, "invalid_request");
    }
    const initial = await challenge({ purpose: "activate", startSession: false });
    const wrongDomain = sign(null, Buffer.from(message({ ...initial.info, platform: undefined }, initial.nonce)), install.privateKey).toString("base64");
    assert.equal((await post("/auth/activate", { ...body(initial, "Alice", issued.activationKey), signature: wrongDomain })).body.error, "installation_proof_invalid");
    const enrolled = await post("/auth/activate", body(initial, "Alice", issued.activationKey)); assert.equal(enrolled.status, 200);
    let lease = enrolled.body.lease;
    const initialClaims = claims(lease), stableHardware = await hardware();
    assert.equal(initialClaims.platform, "linux"); assert.equal(initialClaims.launchSessionId, firstSession);
    assert.equal(stableHardware.account.device_hash, input.deviceHash);
    assert.notEqual(stableHardware.account.activated_at, null);
    assert.ok(stableHardware.account.hwid_bound_at > 0, "First enrollment records its persistent binding timestamp");
    const enrolledAccount = await store.getAccountByDiscordId(target);
    assert.equal(enrolledAccount.hwidBoundAt, stableHardware.account.hwid_bound_at);
    assert.equal(enrolledAccount.nextHwidChangeAllowedAt, stableHardware.account.hwid_reset_at + 7 * 86400000);

    const labels = ["game close/relaunch", "loader close/relaunch", "reboot Linux", "shutdown/start", "suspend/resume", "OS logout/login",
      "Minecraft restart 1", "Minecraft restart 2", "loader restart 1", "loader restart 2", "Java restart", "Discord restart",
      "network/IP change", "VPN connection", "temporary network loss", "WebSocket reconnect", "periodic lease renewal"];
    for (const label of labels) {
      const previousLease = lease;
      const next = await challenge({ launchSessionId: randomUUID(), startSession: label !== "periodic lease renewal" }, install, `${label}-${request++}`);
      const result = await post("/auth/renew", body(next)); assert.equal(result.status, 200, label);
      lease = result.body.lease;
      assert.equal(claims(lease).generation, initialClaims.generation, `${label}: launch does not revoke persistent authorization`);
      assert.equal(claims(lease).deviceHash, input.deviceHash);
      assert.deepEqual(await hardware(), stableHardware, `${label}: binding and transfer allowance unchanged`);
      assert.equal((await authorizeRelay(previousLease)).ok, true, `${label}: a new runtime does not revoke a valid device lease`);
      assert.equal((await authorizeRelay(lease)).ok, true);
    }
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_launch_sessions"))[0].n, 0, "No Linux-specific launch enrollment is recorded");
    const oldSession = await challenge({ launchSessionId: firstSession, startSession: false });
    assert.equal((await post("/auth/renew", body(oldSession))).status, 200, "A runtime session ID never replaces hardware identity");
    const expired = signedLease({ ...claims(lease), iat: Date.now() - 600000, exp: Date.now() - 300000, offlineUntil: Date.now() - 1 });
    assert.equal((await authorizeRelay(expired)).error, "invalid_lease");
    const afterExpiry = await challenge({ launchSessionId: randomUUID(), startSession: false });
    const expiryResult = await post("/auth/renew", body(afterExpiry)); assert.equal(expiryResult.status, 200);
    lease = expiryResult.body.lease;
    assert.deepEqual(await hardware(), stableHardware, "Lease expiry/renewal does not alter the hardware binding");

    const bucket = await mf.getR2Bucket("PRIVATE_ARTIFACTS"), artifact = Buffer.from("persistent-binding private artifact bytes");
    const release = { product: "sky-myce", protocol: 3, minecraft: "26.1.2", version: "2.4.2", minimumLoaderVersion: "1.0.10",
      artifact: "releases/Sky-Myce-2.4.2.jar", sha256: createHash("sha256").update(artifact).digest("hex"), issuedAt: Date.now(), expiresAt: Date.now() + 86400000 };
    const payload = Buffer.from(JSON.stringify(release));
    await bucket.put("releases/current.json", JSON.stringify({ payload: payload.toString("base64url"), signature: sign(null, payload, signer.privateKey).toString("base64url"), kid: "test" }));
    await bucket.put(release.artifact, artifact);
    const leaseSession = claims(lease).launchSessionId;
    const artifactChallenge = await challenge({ purpose: "artifact", startSession: false, launchSessionId: leaseSession });
    assert.deepEqual(Buffer.from((await post("/loader/artifact", { challengeId: artifactChallenge.challengeId, signature: artifactChallenge.signature, lease })).body), artifact);
    const foreignSession = await challenge({ purpose: "artifact", startSession: false, launchSessionId: randomUUID() });
    assert.equal((await post("/loader/artifact", { challengeId: foreignSession.challengeId, signature: foreignSession.signature, lease })).body.error, "invalid_lease", "Artifact proof remains bound to its signed lease metadata");

    const mismatchedOs = await challenge({ platform: "windows" });
    assert.equal((await post("/auth/renew", body(mismatchedOs))).body.error, "platform_changed", "Signed OS metadata cannot downgrade an established Linux installation");
    const invalidProof = await challenge({ launchSessionId: randomUUID() });
    assert.equal((await post("/auth/renew", { ...body(invalidProof), signature: "invalid" })).body.error, "installation_proof_invalid");
    assert.equal((await post("/auth/renew", { ...body(invalidProof), minecraftProof: { ...minecraftProof("Alice", invalidProof.nonce), proof: "invalid" } })).body.error, "minecraft_proof_invalid");
    const unlinked = await challenge({ launchSessionId: randomUUID() });
    assert.equal((await post("/auth/renew", body(unlinked, "Carol"))).body.error, "minecraft_link_required");
    assert.deepEqual(await hardware(), stableHardware, "Proof/link failures do not reset a legitimate device");
    const duplicate = await challenge({ launchSessionId: randomUUID() });
    const race = await Promise.all([post("/auth/renew", body(duplicate)), post("/auth/renew", body(duplicate))]);
    assert.equal(race.filter(result => result.status === 200).length, 1);
    assert.equal(race.find(result => result.status !== 200).body.error, "invalid_challenge");
    const competing = await Promise.all([challenge({ launchSessionId: randomUUID() }), challenge({ launchSessionId: randomUUID() })]);
    const competingResults = await Promise.all(competing.map(c => post("/auth/renew", body(c))));
    assert.ok(competingResults.every(result => result.status === 200), "Independent same-device launches can renew without hardware transfer");
    for (const result of competingResults) assert.equal((await authorizeRelay(result.body.lease)).ok, true);
    assert.deepEqual(await hardware(), stableHardware);

    for (const policy of ["false", "true", "invalid", null]) {
      await restart(policy);
      const next = await challenge({ ...legacy });
      const result = await post("/auth/renew", body(next)); assert.equal(result.status, 200, "Obsolete Linux flag cannot require re-enrollment after restart");
      assert.equal(claims(result.body.lease).generation, initialClaims.generation);
      assert.deepEqual(await hardware(), stableHardware, "Durable Object restart retains enrolled identity, timestamp and transfer allowance");
    }
    const updates = [
      ["loader update", {}, { loaderVersion: "1.0.11" }],
      ["Fabric update", {}, { fabricVersion: "0.20.0" }],
      ["Sky-Myce update", { SKY_MYCE_VERSION: "2.4.3" }, { modVersion: "2.4.3" }],
      ["Minecraft update", { MINECRAFT_VERSION: "26.1.3", SKY_MYCE_VERSION: "2.4.3" }, { minecraftVersion: "26.1.3", modVersion: "2.4.3" }],
    ];
    for (const [label, config, client] of updates) {
      await restart("true", config);
      const next = await challenge({ ...client, launchSessionId: randomUUID() });
      const result = await post("/auth/renew", body(next)); assert.equal(result.status, 200, label);
      assert.equal(claims(result.body.lease).generation, initialClaims.generation);
      assert.deepEqual(await hardware(), stableHardware, `${label}: same hardware needs no enrollment`);
    }
    await restart();
    const bootstrap = await challenge({ purpose: "bootstrap-renew", loaderVersion: "1.0.9", modVersion: "0.0.1", ...legacy });
    assert.equal((await store.authenticateInstallation(body(bootstrap), "bootstrap-renew")).ok, true, "Old loader can obtain a bootstrap upgrade without device enrollment");
    assert.deepEqual(await hardware(), stableHardware);

    const windowsInstall = generateKeyPairSync("ed25519");
    const windowsInput = { installationId: randomUUID(), publicKey: publicKey(windowsInstall), deviceHash: "e".repeat(64), hardwareHash: "3".repeat(64), platform: "windows" };
    const windowsKey = await admin("authorise", windowsUser);
    const windowsInitial = await challenge({ ...windowsInput, purpose: "activate" }, windowsInstall);
    const windowsResult = await post("/auth/activate", body(windowsInitial, "Bob", windowsKey.activationKey)); assert.equal(windowsResult.status, 200);
    const windowsClaims = claims(windowsResult.body.lease);
    assert.equal(windowsClaims.hardwareHash, windowsInput.hardwareHash);
    assert.equal((await sql.exec("SELECT hardware_hash FROM installations WHERE installation_id=?", windowsInput.installationId))[0].hardware_hash, windowsInput.hardwareHash, "First-time signed activation pins the stable physical hardware reference");
    const windowsBinding = (await sql.exec("SELECT device_hash,hwid_reset_at,activated_at FROM sky_accounts WHERE discord_id=?", windowsUser))[0];
    for (const label of ["reboot Windows", "Windows update", "Windows shutdown/start"]) {
      const c = await challenge({ ...windowsInput, launchSessionId: randomUUID() }, windowsInstall);
      const result = await post("/auth/renew", body(c, "Bob")); assert.equal(result.status, 200, label);
      assert.equal(claims(result.body.lease).generation, windowsClaims.generation);
      assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,activated_at FROM sky_accounts WHERE discord_id=?", windowsUser))[0], windowsBinding, label);
    }

    const newDevice = generateKeyPairSync("ed25519"), otherDevice = { installationId: randomUUID(), publicKey: publicKey(newDevice), deviceHash: "f".repeat(64), launchSessionId: randomUUID() };
    const transferKey = await admin("authorise", target, { reissue: true });
    const transfer = await challenge({ ...otherDevice, purpose: "activate" }, newDevice);
    assert.equal((await post("/auth/activate", body(transfer, "Alice", transferKey.activationKey))).body.error, "HWID_MISMATCH", "Different physical device cannot replace a binding implicitly");
    const mismatchRenew = await challenge({ deviceHash: otherDevice.deviceHash });
    assert.equal((await post("/auth/renew", body(mismatchRenew))).body.error, "HWID_MISMATCH", "Renewal reports a known installation presenting another HWID");
    assert.equal((await store.userCommand(actor(target), "reset", {})).error, "device_reset_cooldown", "Physical transfer before seven days is denied");
    await sql.exec("UPDATE sky_accounts SET hwid_reset_at=? WHERE discord_id=?", Date.now() - 7 * 86400000 - 1, target);
    const beforeTransfer = (await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0];
    const afterCooldown = await challenge({ ...otherDevice, purpose: "activate" }, newDevice);
    assert.equal((await post("/auth/activate", body(afterCooldown, "Alice", transferKey.activationKey))).body.error, "HWID_MISMATCH", "Elapsed cooldown alone cannot silently transfer hardware");
    assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0], beforeTransfer);
    const realReset = await store.userCommand(actor(target), "reset", {}); assert.equal(realReset.ok, true);
    assert.equal(realReset.account.deviceRegistered, true, "Preparing a hardware transfer preserves the existing binding");
    assert.deepEqual((await sql.exec("SELECT device_hash,hwid_reset_at,hwid_bound_at,generation FROM sky_accounts WHERE discord_id=?", target))[0], beforeTransfer);
    assert.equal((await authorizeRelay(lease)).ok, true, "The old device stays usable until the transfer actually occurs");
    await sql.exec("UPDATE sky_accounts SET hwid_reset_at=? WHERE discord_id=?", Date.now(), target);
    const cooldownRace = await challenge({ ...otherDevice, purpose: "activate" }, newDevice);
    assert.equal((await post("/auth/activate", body(cooldownRace, "Alice", realReset.activationKey))).body.error, "device_reset_cooldown", "Activation rechecks the transfer cooldown atomically rather than trusting an earlier prepared code");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", realReset.activationId))[0].used_at, null, "Denied transfer does not consume the prepared code");
    assert.equal((await sql.exec("SELECT device_hash FROM sky_accounts WHERE discord_id=?", target))[0].device_hash, input.deviceHash);
    assert.equal((await authorizeRelay(lease)).ok, true);
    await sql.exec("UPDATE sky_accounts SET hwid_reset_at=? WHERE discord_id=?", beforeTransfer.hwid_reset_at, target);
    const permitted = await challenge({ ...otherDevice, purpose: "activate" }, newDevice);
    const moved = await post("/auth/activate", body(permitted, "Alice", realReset.activationKey)); assert.equal(moved.status, 200, "Explicit allowed transfer enrolls the replacement device");
    assert.equal((await sql.exec("SELECT device_hash FROM sky_accounts WHERE discord_id=?", target))[0].device_hash, otherDevice.deviceHash);
    const movedAccount = await store.getAccountByDiscordId(target);
    assert.ok(movedAccount.hwidBoundAt >= stableHardware.account.hwid_bound_at);
    assert.equal(movedAccount.nextHwidChangeAllowedAt, movedAccount.hwidResetAt + 7 * 86400000);
    assert.equal(claims(moved.body.lease).generation, beforeTransfer.generation + 1, "A real transfer invalidates the old generation once");
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_devices WHERE account_id=? AND revoked_at IS NULL", initialClaims.accountId))[0].n, 1);
    assert.equal((await authorizeRelay(lease)).ok, false, "Old physical device lease is unusable after transfer");
    const retired = await challenge({ launchSessionId: randomUUID() });
    assert.equal((await post("/auth/renew", body(retired))).body.error, "HWID_MISMATCH", "Former device cannot resume after transfer");
    assert.equal((await authorizeRelay(moved.body.lease, newDevice)).ok, true);
    const transferAudit = (await sql.exec("SELECT * FROM account_audit WHERE action='device_transferred' ORDER BY id DESC"))[0];
    const transferMetadata = JSON.parse(transferAudit.metadata);
    assert.equal(transferAudit.actor, target); assert.equal(transferAudit.account_id, initialClaims.accountId);
    assert.equal(transferAudit.override, 0); assert.ok(transferAudit.at > 0);
    assert.equal(transferMetadata.discordId, target);
    assert.equal(transferMetadata.previousHwid, input.deviceHash); assert.equal(transferMetadata.newHwid, otherDevice.deviceHash);
    assert.equal(transferMetadata.reason, 'hardware_transfer'); assert.equal(transferMetadata.source, 'activation');
    assert.equal(transferMetadata.minecraftUuid, 'a'.repeat(32)); assert.equal(transferMetadata.minecraftName, 'Alice');
    assert.equal(transferMetadata.buildId, input.modVersion); assert.equal(transferMetadata.sessionId, otherDevice.launchSessionId);
    assert.equal(transferMetadata.installationId, otherDevice.installationId);

    const resetAuditBefore = (await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='hwid_reset'"))[0].n;
    const emergency = await admin("hwid_reset"); assert.equal(emergency.ok, true);
    assert.equal(emergency.account.deviceRegistered, false);
    assert.equal((await authorizeRelay(moved.body.lease, newDevice)).ok, false, "Administrator reset invalidates the affected lease");
    const emergencyAudit = (await sql.exec("SELECT * FROM account_audit WHERE action='hwid_reset' ORDER BY id DESC"))[0];
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='hwid_reset'"))[0].n, resetAuditBefore + 1);
    assert.equal(emergencyAudit.actor, administrator); assert.equal(emergencyAudit.override, 1);
    assert.equal(emergencyAudit.account_id, initialClaims.accountId); assert.ok(emergencyAudit.at > 0);
    const emergencyMetadata = JSON.parse(emergencyAudit.metadata);
    assert.equal(emergencyMetadata.discordId, target); assert.equal(emergencyMetadata.administratorId, administrator);
    assert.equal(emergencyMetadata.previousHwid, otherDevice.deviceHash); assert.equal(emergencyMetadata.newHwid, null);
    assert.equal(emergencyMetadata.reason, 'administrator_reset'); assert.equal(emergencyMetadata.source, 'administrator');
    const recovery = await challenge({ purpose: "activate" });
    assert.equal((await post("/auth/activate", body(recovery, "Alice", emergency.activationKey))).status, 200, "Explicit administrator security reset requires legitimate activation and enrolls the next device");
    const hardwareHash = "1".repeat(64);
    const tamperedHardware = await challenge({ hardwareHash });
    const otherHardwareSignature = sign(null, Buffer.from(message({ ...tamperedHardware.info, hardwareHash: "2".repeat(64) }, tamperedHardware.nonce)), install.privateKey).toString("base64");
    assert.equal((await post("/auth/renew", { ...body(tamperedHardware), signature: otherHardwareSignature })).body.error, "installation_proof_invalid", "The physical HWID is covered by the installation signature");
    assert.equal((await sql.exec("SELECT hardware_hash FROM installations WHERE installation_id=?", input.installationId))[0].hardware_hash, null, "Invalid signed hardware cannot pin a legacy installation");
    const migrateHardware = await challenge({ hardwareHash });
    const migrated = await post("/auth/renew", body(migrateHardware)); assert.equal(migrated.status, 200, "First signed hardware proof pins the stable physical reference for legacy installations");
    assert.equal((await sql.exec("SELECT hardware_hash FROM installations WHERE installation_id=?", input.installationId))[0].hardware_hash, hardwareHash);
    assert.equal((await sql.exec("SELECT hardware_hash FROM sky_accounts WHERE discord_id=?", target))[0].hardware_hash, hardwareHash, "The license pins its physical device independently of installation files");
    const migratedState = await hardware();
    const copiedInstall = await challenge({ hardwareHash: "2".repeat(64) });
    assert.equal((await post("/auth/renew", body(copiedInstall))).body.error, "HWID_MISMATCH", "Copied installation/key/device ID cannot authenticate from another physical machine");
    const missingHardware = await challenge();
    assert.equal((await post("/auth/renew", body(missingHardware))).body.error, "hardware_proof_required", "A migrated installation cannot downgrade to a copied v1/v2 identity");
    const platformFreeDowngrade = await challenge({ ...legacy });
    assert.equal((await post("/auth/renew", body(platformFreeDowngrade))).body.error, "hardware_proof_required", "A v1 proof cannot bypass the pinned physical HWID");
    const legacyUpgrade = await challenge({ purpose: "bootstrap-renew", loaderVersion: "1.0.9", modVersion: "0.0.1", ...legacy });
    assert.equal((await store.authenticateInstallation(body(legacyUpgrade), "bootstrap-renew")).ok, true, "Legacy proof grants bootstrap recovery only after hardware migration");
    const recognizedHardware = await challenge({ hardwareHash, launchSessionId: randomUUID() });
    assert.equal((await post("/auth/renew", body(recognizedHardware))).status, 200);
    const platformFreeHardware = await challenge({ ...legacy, hardwareHash });
    const hardwareLease = await post("/auth/renew", body(platformFreeHardware));
    assert.equal(hardwareLease.status, 200, "Signed v3 hardware works without obsolete per-launch OS metadata");
    assert.equal((await authorizeRelay(hardwareLease.body.lease)).ok, true);
    const downgradedArtifact = await challenge({ ...legacy, purpose: "artifact" });
    assert.equal((await post("/loader/artifact", { challengeId: downgradedArtifact.challengeId, signature: downgradedArtifact.signature, lease: hardwareLease.body.lease })).body.error, "hardware_proof_required", "Pinned hardware proof is also required for protected artifact access");
    const validArtifact = await challenge({ ...legacy, purpose: "artifact", hardwareHash });
    assert.deepEqual(Buffer.from((await post("/loader/artifact", { challengeId: validArtifact.challengeId, signature: validArtifact.signature, lease: hardwareLease.body.lease })).body), artifact);
    const activationCode = await admin("authorise", target, { reissue: true });
    const downgradedActivation = await challenge({ purpose: "activate", ...legacy });
    assert.equal((await post("/auth/activate", body(downgradedActivation, "Alice", activationCode.activationKey))).body.error, "hardware_proof_required", "A replacement code cannot downgrade a hardware-pinned installation");
    assert.equal((await sql.exec("SELECT used_at FROM activation_keys WHERE key_id=?", activationCode.activationId))[0].used_at, null, "Rejected downgrade leaves the replacement code unconsumed");
    assert.deepEqual(await hardware(), migratedState, "Hardware proof verification never rotates the persistent binding");
    const copiedHardware = '2'.repeat(64);
    const implicitCopiedTransfer = await challenge({ purpose: 'activate', ...legacy, hardwareHash: copiedHardware });
    assert.equal((await post('/auth/activate', body(implicitCopiedTransfer, 'Alice', activationCode.activationKey))).body.error, 'HWID_MISMATCH', 'Copying a saved installation to another physical machine cannot silently replace the pinned hardware');
    assert.deepEqual(await hardware(), migratedState);
    const freshCopiedKey = generateKeyPairSync('ed25519');
    const freshCopiedInstallation = await challenge({ purpose: 'activate', ...legacy, installationId: randomUUID(), publicKey: publicKey(freshCopiedKey), hardwareHash: copiedHardware }, freshCopiedKey);
    assert.equal((await post('/auth/activate', body(freshCopiedInstallation, 'Alice', activationCode.activationKey))).body.error, 'HWID_MISMATCH', 'A fresh installation key cannot reuse an opaque device reference to bypass the license physical HWID');
    assert.equal((await sql.exec('SELECT used_at FROM activation_keys WHERE key_id=?', activationCode.activationId))[0].used_at, null);
    assert.deepEqual(await hardware(), migratedState, 'Rejected fresh-key activation cannot alter the physical binding or its authorization');
    assert.equal((await store.userCommand(actor(target), 'reset', {})).error, 'device_reset_cooldown');
    await sql.exec('UPDATE sky_accounts SET hwid_reset_at=? WHERE discord_id=?', Date.now() - 7 * 86400000 - 1, target);
    const copiedTransferState = await hardware();
    const copiedTransferCode = await store.userCommand(actor(target), 'reset', {});
    assert.equal(copiedTransferCode.ok, true);
    assert.deepEqual(await hardware(), copiedTransferState, 'Preparing transfer preserves the old physical HWID even when installation files were copied');
    assert.equal((await authorizeRelay(hardwareLease.body.lease)).ok, true);
    const copiedTransfer = await challenge({ purpose: 'activate', hardwareHash: copiedHardware, platform: 'windows', launchSessionId: randomUUID() });
    const copiedMoved = await post('/auth/activate', body(copiedTransfer, 'Alice', copiedTransferCode.activationKey));
    assert.equal(copiedMoved.status, 200, 'An explicit allowed physical transfer works with copied installation files');
    assert.equal(claims(copiedMoved.body.lease).platform, 'windows', 'An authorized physical transfer may change OS metadata');
    assert.equal((await sql.exec('SELECT platform FROM installations WHERE installation_id=?', input.installationId))[0].platform, 'windows');
    assert.equal((await sql.exec('SELECT hardware_hash FROM installations WHERE installation_id=?', input.installationId))[0].hardware_hash, copiedHardware);
    assert.equal((await sql.exec('SELECT hardware_hash FROM sky_accounts WHERE discord_id=?', target))[0].hardware_hash, copiedHardware);
    assert.equal(claims(copiedMoved.body.lease).generation, copiedTransferState.account.generation + 1);
    const copiedMovedAccount = await store.getAccountByDiscordId(target);
    assert.ok(copiedMovedAccount.hwidResetAt > copiedTransferState.account.hwid_reset_at);
    assert.ok(copiedMovedAccount.hwidBoundAt >= copiedTransferState.account.hwid_bound_at);
    assert.equal(copiedMovedAccount.nextHwidChangeAllowedAt, copiedMovedAccount.hwidResetAt + 7 * 86400000);
    assert.equal((await authorizeRelay(hardwareLease.body.lease)).ok, false, 'The prior physical device lease is invalidated even if the opaque installation reference stayed identical');
    assert.equal((await authorizeRelay(copiedMoved.body.lease)).ok, true);
    const previousPhysicalDevice = await challenge({ hardwareHash });
    assert.equal((await post('/auth/renew', body(previousPhysicalDevice))).body.error, 'HWID_MISMATCH');
    const newPhysicalDevice = await challenge({ hardwareHash: copiedHardware, platform: 'windows', launchSessionId: randomUUID() });
    assert.equal((await post('/auth/renew', body(newPhysicalDevice))).status, 200);
    assert.equal((await store.userCommand(actor(target), 'reset', {})).error, 'device_reset_cooldown', 'A physical HWID replacement consumes the seven-day transfer allowance');
    const copiedAudit = (await sql.exec("SELECT * FROM account_audit WHERE action='device_transferred' ORDER BY id DESC"))[0];
    const copiedMetadata = JSON.parse(copiedAudit.metadata);
    assert.equal(copiedMetadata.previousHwid, input.deviceHash); assert.equal(copiedMetadata.newHwid, input.deviceHash);
    assert.equal(copiedMetadata.previousHardware, hardwareHash); assert.equal(copiedMetadata.newHardware, copiedHardware);
    assert.equal(copiedMetadata.reason, 'hardware_transfer'); assert.equal(copiedMetadata.source, 'activation');
    assert.equal(copiedMetadata.minecraftUuid, 'a'.repeat(32)); assert.equal(copiedMetadata.installationId, input.installationId);
    assert.equal((await sql.exec("SELECT COUNT(*) AS n FROM account_audit WHERE action='device_reset'"))[0].n, 0, 'Normal lifecycle events and self-service code preparations never automatically reset hardware');
    const securityReason = 'confirmed_credential_compromise';
    const securityReset = await admin('hwid_revoke', target, { reason: securityReason });
    assert.equal(securityReset.ok, true); assert.equal(securityReset.account.deviceRegistered, false);
    assert.equal((await sql.exec('SELECT hardware_hash FROM sky_accounts WHERE discord_id=?', target))[0].hardware_hash, null, 'Deliberate security invalidation clears the license physical binding');
    assert.equal((await authorizeRelay(copiedMoved.body.lease)).ok, false, 'Deliberate security invalidation revokes the affected lease');
    const securityAudit = (await sql.exec("SELECT * FROM account_audit WHERE action='hwid_revoke' ORDER BY id DESC"))[0];
    const securityMetadata = JSON.parse(securityAudit.metadata);
    assert.equal(securityAudit.actor, administrator); assert.equal(securityAudit.override, 1);
    assert.equal(securityAudit.account_id, initialClaims.accountId); assert.ok(securityAudit.at > 0);
    assert.equal(securityMetadata.discordId, target); assert.equal(securityMetadata.administratorId, administrator);
    assert.equal(securityMetadata.previousHwid, input.deviceHash); assert.equal(securityMetadata.newHwid, null);
    assert.equal(securityMetadata.reason, securityReason); assert.equal(securityMetadata.source, 'administrator');
    console.log("Persistent HWID checks passed: lifecycle/reconnect/expired lease, supported updates, Linux and Windows, durable restarts, proof validation, mismatch, explicit seven-day transfer, retired device denial and audited administrator recovery.");
  } finally { await mf.dispose(); }
}
