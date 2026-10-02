import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

export async function checkDiscordUx(script, minecraftProof) {
  const guild = '11111111111111111', administrator = '978376257534328883', target = '44444444444444444', other = '55555555555555555';
  const apps = { backend: '22222222222222222', user: '33333333333333333' };
  const keys = { backend: generateKeyPairSync('ed25519'), user: generateKeyPairSync('ed25519') };
  const signer = generateKeyPairSync('ed25519'), installation = generateKeyPairSync('ed25519');
  const publicKey = pair => pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const rawKey = pair => pair.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('hex');
  const calls = [], recipients = new Map();
  const currentUsers = new Map([[target, { id: target, username: 'RenamedUser', global_name: 'Current Display Name', bot: false }],
    [other, { id: other, username: 'OtherUser', global_name: 'Other Display Name', bot: false }],
    [administrator, { id: administrator, username: 'Administrator', global_name: 'Admin', bot: false }]]);
  const bindings = { ROOMS: 'testing', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: administrator,
    DISCORD_BACKEND_APPLICATION_ID: apps.backend, DISCORD_BACKEND_PUBLIC_KEY: rawKey(keys.backend),
    DISCORD_USER_APPLICATION_ID: apps.user, DISCORD_USER_PUBLIC_KEY: rawKey(keys.user),
    DISCORD_BACKEND_BOT_TOKEN: 'private-backend-token', USER_BOT_TOKEN: 'private-user-token',
    DISCORD_LOADER_DELIVERY_ENABLED: 'true', DISCORD_LOADER_DOWNLOAD_SECRET: 's'.repeat(32), SKYMYCE_PUBLIC_URL: 'https://worker.example',
    DRM_KEY_ID: 'test', DRM_PUBLIC_KEYS: JSON.stringify({ test: publicKey(signer) }),
    DRM_SIGNING_PRIVATE_KEY: signer.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'), SKY_MYCE_VERSION: '2.4.2' };
  const options = convertV4MiniflareOptions({ name: 'discord-ux-check', modules: true, script, compatibilityDate: '2026-09-27', bindings,
    r2Buckets: ['PRIVATE_ARTIFACTS'], durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: async request => {
      const url = new URL(request.url);
      let body;
      if (request.headers.get('Content-Type')?.startsWith('multipart/form-data')) {
        const form = await request.formData(); body = JSON.parse(form.get('payload_json'));
      } else {
        const text = await request.text(); try { body = text ? JSON.parse(text) : undefined; } catch { body = text; }
      }
      calls.push({ path: url.pathname, method: request.method, body, authorization: request.headers.get('Authorization') });
      assert.equal(url.hostname, 'discord.com', 'Licensing UX only calls the mocked Discord API');
      if (url.pathname.endsWith('/users/@me/channels')) {
        const id = String(88000000000000000n + BigInt(recipients.size)); recipients.set(id, body.recipient_id); return Response.json({ id });
      }
      if (/\/users\/\d+$/.test(url.pathname)) return Response.json(currentUsers.get(url.pathname.split('/').at(-1)) ?? { message: 'Unknown User' });
      return Response.json({});
    } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  options.unsafeInspectDurableObjects = true;
  const mf = new Miniflare(options);
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const sql = await mf.unsafeGetDurableObjectStorage('discord-ux-check', 'AccountStore', { name: 'global' });
    let sequence = 90000000000000000n;
    const actor = (id = administrator, app = apps.backend) => ({ id, guildId: guild, roles: [], applicationId: app, interactionId: String(sequence++) });
    const admin = (action, id = target, data = {}) => store.developerCommand(actor(), action, id, data);
    const read = (action, id = target, data = {}) => store.managementRead(actor(), action, id, data);
    const accountState = async () => (await sql.exec('SELECT * FROM sky_accounts WHERE discord_id=?', target))[0];
    const command = (kind, name, sub, options = [], id = kind === 'backend' ? administrator : target) => {
      const identity = currentUsers.get(id) ?? { id, username: 'Unprivileged' };
      return { type: 2, id: String(sequence++), application_id: apps[kind], guild_id: guild,
        token: `private-interaction-${sequence}`, member: { user: identity, nick: identity.global_name, roles: [] },
        data: { name, options: sub ? [{ type: 1, name: sub, options }] : options } };
    };
    const selected = [{ type: 6, name: 'user', value: target }];
    const textOf = reply => [reply.content ?? '', ...(reply.embeds ?? []).flatMap(embed => [embed.title ?? '', embed.description ?? '', ...(embed.fields ?? []).map(field => `${field.name}\n${field.value}`)])].join('\n');
    const components = reply => (reply.components ?? []).flatMap(row => row.components ?? []);
    const signed = async (kind, data, signingKind = kind) => {
      const text = JSON.stringify(data), stamp = String(Math.floor(Date.now() / 1000));
      const response = await mf.dispatchFetch(`https://worker.example/discord/${kind === 'backend' ? 'backend/' : ''}interactions`, {
        method: 'POST', headers: { 'X-Signature-Timestamp': stamp,
          'X-Signature-Ed25519': sign(null, Buffer.from(stamp + text), keys[signingKind].privateKey).toString('hex'), 'CF-Connecting-IP': `ux-${sequence++}` }, body: text });
      const body = response.headers.get('Content-Type')?.includes('json') ? await response.json() : await response.text();
      if (response.status !== 200 || ![5, 6].includes(body.type)) return { status: response.status, body, reply: body.data };
      let edit;
      for (let attempt = 0; attempt < 100; attempt++) {
        edit = calls.findLast(call => call.method === 'PATCH' && call.path.includes(`/${data.application_id}/${data.token}/`));
        if (edit) break;
        await new Promise(resolve => setTimeout(resolve, 10));
      }
      assert.ok(edit, 'Deferred private Discord response completes');
      return { status: response.status, body, reply: edit.body };
    };
    const privateReply = result => {
      assert.equal(result.status, 200);
      assert.ok([4, 5, 6, 7].includes(result.body.type));
      if ([4, 5].includes(result.body.type)) assert.equal(result.body.data.flags & 64, 64);
      assert.deepEqual(result.reply.allowed_mentions, { parse: [] });
      assert.ok(textOf(result.reply).length <= 6000, 'Discord rendering is bounded');
    };
    const click = (kind, customId, id = administrator, changes = {}) => {
      const data = command(kind, '', null, [], id); return signed(kind, { ...data, type: 3, data: { component_type: 2, custom_id: customId }, ...changes });
    };
    const confirmation = async (sub, options = selected) => {
      const result = await signed('backend', command('backend', 'license', sub, options)); privateReply(result);
      const buttons = components(result.reply), confirm = buttons.find(button => button.custom_id?.startsWith('sky:confirm:'));
      const cancel = buttons.find(button => button.custom_id?.startsWith('sky:cancel:'));
      assert.ok(confirm && cancel, `/license ${sub} presents Confirm and Cancel`);
      assert.ok(textOf(result.reply).includes('Current Display Name') || textOf(result.reply).includes(`<@${target}>`));
      return { confirm: confirm.custom_id, cancel: cancel.custom_id, result };
    };

    const bucket = await mf.getR2Bucket('PRIVATE_ARTIFACTS'), loaderBytes = Buffer.from('test-only-loader');
    const loaderRelease = { product: 'sky-myce-loader', protocol: 1, minecraft: '26.1.2', version: '1.0.10',
      artifact: 'loaders/Sky-Myce-Loader-1.0.10.jar', size: loaderBytes.length,
      sha256: createHash('sha256').update(loaderBytes).digest('hex'), issuedAt: Date.now(), expiresAt: Date.now() + 86400000 };
    const loaderPayload = Buffer.from(JSON.stringify(loaderRelease));
    await bucket.put('loaders/current.json', JSON.stringify({ kid: 'test', payload: loaderPayload.toString('base64url'),
      signature: sign(null, loaderPayload, signer.privateKey).toString('base64url') }));
    await bucket.put(loaderRelease.artifact, loaderBytes);
    await bucket.put('loaders/Sky-Myce-Loader.jar', loaderBytes);
    const issued = await admin('authorise'); assert.equal(issued.ok, true);
    assert.equal((await admin('authorise', other)).ok, true);
    const installationId = randomUUID(), deviceHash = 'd'.repeat(64), hardwareHash = '1'.repeat(64);
    const input = { purpose: 'activate', installationId, publicKey: publicKey(installation), deviceHash, hardwareHash,
      minecraftVersion: '26.1.2', loaderVersion: '1.0.10', modVersion: '2.4.2', clientNonce: randomUUID() };
    const auth = async (path, body) => {
      const response = await mf.dispatchFetch(`https://worker.example${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': `ux-auth-${sequence++}` }, body: JSON.stringify(body) });
      return { status: response.status, body: await response.json() };
    };
    const challenge = await auth('/auth/challenge', input); assert.equal(challenge.status, 200);
    const c = challenge.body;
    const message = `Sky-Myce installation v3\nactivate\n${c.nonce}\n${installationId}\n${deviceHash}\n26.1.2\n1.0.10\n2.4.2\n${input.clientNonce}\n${hardwareHash}`;
    const enrolled = await auth('/auth/activate', { challengeId: c.challengeId, signature: sign(null, Buffer.from(message), installation.privateKey).toString('base64'),
      minecraftProof: minecraftProof('Alice', c.nonce), activationKey: issued.activationKey });
    assert.equal(enrolled.status, 200);
    const relayChallenge = createHash('sha256').update(randomUUID()).digest('hex').slice(0, 32);
    const openedSession = await store.authorizeSession({ uuid: 'a'.repeat(32), lease: enrolled.body.lease, challenge: relayChallenge,
      signature: sign(null, Buffer.from(`Sky-Myce relay v3\n${relayChallenge}\n${enrolled.body.lease}`), installation.privateKey).toString('base64') });
    assert.equal(openedSession.ok, true);
    await sql.exec('CREATE TABLE heartbeat_write_probe (id INTEGER PRIMARY KEY)');
    await sql.exec('CREATE TRIGGER count_heartbeat_writes AFTER UPDATE OF last_heartbeat ON account_runtime BEGIN INSERT INTO heartbeat_write_probe(id) VALUES(NULL); END');
    const observedHeartbeat = (await sql.exec('SELECT last_heartbeat FROM account_runtime WHERE account_id=?', openedSession.accountId))[0].last_heartbeat;
    const heartbeatCheck = { accountId: openedSession.accountId, uuid: openedSession.uuid, installationId: openedSession.installationId,
      generation: openedSession.generation, connectionId: openedSession.connectionId };
    for (let i = 0; i < 50; i++) assert.equal((await store.checkSession({ ...heartbeatCheck, heartbeatAt: observedHeartbeat })).ok, true);
    assert.equal((await sql.exec('SELECT COUNT(*) AS n FROM heartbeat_write_probe'))[0].n, 0, 'Repeated actual ping timestamps keep all authorization checks but produce no redundant writes');
    const newerHeartbeat = Date.now();
    assert.ok(newerHeartbeat > observedHeartbeat);
    assert.equal((await store.checkSession({ ...heartbeatCheck, heartbeatAt: newerHeartbeat })).ok, true);
    await store.checkSession({ ...heartbeatCheck, heartbeatAt: observedHeartbeat });
    await store.checkSession({ ...heartbeatCheck, connectionId: 'retired-connection', heartbeatAt: Date.now() });
    assert.equal((await sql.exec('SELECT COUNT(*) AS n FROM heartbeat_write_probe'))[0].n, 1, 'Only a newer observation from the current connection writes telemetry');
    assert.equal((await sql.exec('SELECT last_heartbeat FROM account_runtime WHERE account_id=?', openedSession.accountId))[0].last_heartbeat, newerHeartbeat);
    await sql.exec('DROP TRIGGER count_heartbeat_writes');
    await sql.exec('DROP TABLE heartbeat_write_probe');
    const installationRequest = async (purpose, changes = {}) => {
      const info = { ...input, purpose, clientNonce: randomUUID(), ...changes };
      const pending = await store.challenge(info, `ux-proof-${sequence++}`); assert.ok(pending.challengeId);
      const message = `Sky-Myce installation v3\n${purpose}\n${pending.nonce}\n${installationId}\n${deviceHash}\n${info.minecraftVersion}\n${info.loaderVersion}\n${info.modVersion}\n${info.clientNonce}\n${info.hardwareHash}`;
      return { challengeId: pending.challengeId, signature: sign(null, Buffer.from(message), installation.privateKey).toString('base64'), minecraftProof: minecraftProof('Alice', pending.nonce) };
    };
    const now = Date.now();
    await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', 'b'.repeat(32), issued.account.accountId, 'Bob', now, now);
    const otherAccount = await store.getAccountByDiscordId(other);
    await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', 'c'.repeat(32), otherAccount.accountId, 'Carol', now, now);
    await store.cacheDiscordIdentity(actor(), target, { username: 'OldUsername', displayName: 'Old Display Name' });
    await store.cacheDiscordIdentity(actor(), target, { username: 'RenamedUser', displayName: 'Current Display Name' });
    assert.equal((await store.getAccountByDiscordId(target)).accountId, issued.account.accountId, 'Username changes preserve the stable Discord license');

    assert.equal((await store.managementRead(actor(other), 'debug', target)).error, 'admin_required');
    assert.equal((await store.managementRead(actor(other), 'overview')).error, 'admin_required');
    const uuids = await read('uuids'); assert.equal(uuids.ok, true); assert.equal(uuids.account.uuids.length, 2);
    const search = await read('search', '', { query: 'Alice' }); assert.equal(search.ok, true);
    assert.ok(search.accounts.some(details => details.account.discordId === target));
    const renamedSearch = await read('search', '', { query: 'Current Display Name' });
    assert.ok(renamedSearch.accounts.some(details => details.account.discordId === target));
    const overview = await read('overview', ''); assert.equal(overview.ok, true); assert.equal(overview.overview.online, 1);
    const originalParts = enrolled.body.lease.split('.'), legacyClaims = JSON.parse(Buffer.from(originalParts[1], 'base64url'));
    delete legacyClaims.loaderVersion;
    const legacyUnsigned = `${originalParts[0]}.${Buffer.from(JSON.stringify(legacyClaims)).toString('base64url')}`;
    const legacyLease = `${legacyUnsigned}.${sign(null, Buffer.from(legacyUnsigned), signer.privateKey).toString('base64url')}`;
    await sql.exec('UPDATE account_runtime SET loader_version=? WHERE account_id=?', '1.0.11', issued.account.accountId);
    const beforeLegacyAccount = await accountState(), beforeLegacyRuntime = await sql.exec('SELECT * FROM account_runtime WHERE account_id=?', issued.account.accountId);
    const legacyChallenge = createHash('sha256').update(randomUUID()).digest('hex').slice(0, 32);
    const legacySession = await store.authorizeSession({ uuid: 'a'.repeat(32), lease: legacyLease, challenge: legacyChallenge,
      signature: sign(null, Buffer.from(`Sky-Myce relay v3\n${legacyChallenge}\n${legacyLease}`), installation.privateKey).toString('base64') });
    assert.equal(legacySession.error, 'authorization_renewal_required', 'A valid legacy signature cannot borrow an allowed Loader version from stored runtime metadata');
    assert.deepEqual(await accountState(), beforeLegacyAccount);
    assert.deepEqual(await sql.exec('SELECT * FROM account_runtime WHERE account_id=?', issued.account.accountId), beforeLegacyRuntime,
      'Legacy lease rejection does not mutate the hardware binding or observed connection');
    const refreshedLegacy = await auth('/auth/renew', await installationRequest('renew', { loaderVersion: '1.0.11' }));
    assert.equal(refreshedLegacy.status, 200);
    assert.equal(JSON.parse(Buffer.from(refreshedLegacy.body.lease.split('.')[1], 'base64url')).loaderVersion, '1.0.11');
    const afterLegacyRenew = await accountState();
    for (const key of ['device_hash', 'hardware_hash', 'generation', 'hwid_reset_at', 'hwid_bound_at']) assert.equal(afterLegacyRenew[key], beforeLegacyAccount[key],
      'Fresh installation proof renews a legacy client without changing its hardware binding or transfer cooldown');
    assert.equal((await store.authenticateInstallation(await installationRequest('renew'), 'renew')).ok, true);
    assert.equal((await read('overview', '')).overview.online, 1, 'Legacy clients renewing a lease preserve the observed connection until its actual close');
    const replacementChallenge = createHash('sha256').update(randomUUID()).digest('hex').slice(0, 32);
    const replacementSession = await store.authorizeSession({ uuid: 'a'.repeat(32), lease: enrolled.body.lease, challenge: replacementChallenge,
      signature: sign(null, Buffer.from(`Sky-Myce relay v3\n${replacementChallenge}\n${enrolled.body.lease}`), installation.privateKey).toString('base64') });
    assert.equal(replacementSession.ok, true);
    await store.markSessionOffline(openedSession.accountId, openedSession.uuid, openedSession.installationId, openedSession.generation, openedSession.connectionId);
    assert.equal((await read('overview', '')).overview.online, 1, 'A retired socket close cannot make the replacement connection appear offline');
    await store.markSessionOffline(replacementSession.accountId, replacementSession.uuid, replacementSession.installationId, replacementSession.generation, replacementSession.connectionId);
    assert.equal((await read('overview', '')).overview.online, 0, 'The current socket close immediately removes its online status');
    const reopenedChallenge = createHash('sha256').update(randomUUID()).digest('hex').slice(0, 32);
    assert.equal((await store.authorizeSession({ uuid: 'a'.repeat(32), lease: enrolled.body.lease, challenge: reopenedChallenge,
      signature: sign(null, Buffer.from(`Sky-Myce relay v3\n${reopenedChallenge}\n${enrolled.body.lease}`), installation.privateKey).toString('base64') })).ok, true);
    const audit = await read('audit', target, { limit: 2 }); assert.equal(audit.ok, true); assert.ok(audit.audit.length <= 2);
    const eventView = await signed('backend', command('backend', 'skyhawk', 'event', [{ type: 4, name: 'id', value: audit.audit[0].id }])); privateReply(eventView);
    const eventDenied = await signed('backend', command('backend', 'skyhawk', 'event', [{ type: 4, name: 'id', value: audit.audit[0].id }], other)); privateReply(eventDenied);
    assert.ok(!textOf(eventDenied.reply).includes('Alice'), 'Advanced audit lookup remains administrator-only');
    if (audit.nextBefore) {
      const next = await read('audit', target, { before: audit.nextBefore, limit: 2 });
      assert.ok(next.audit.every(row => !audit.audit.some(previous => previous.id === row.id)), 'Audit pages do not duplicate events');
    }
    const filter = await read('audit', target, { event: 'device_registered', since: now - 60000, limit: 25 });
    assert.ok(filter.audit.length > 0); assert.ok(filter.audit.every(row => row.action === 'device_registered' && row.at >= now - 60000));
    assert.equal((await read('audit', target, { limit: 26 })).error, 'invalid_request');
    assert.equal((await read('audit', target, { severity: 'private-internal-secret' })).error, 'invalid_request');
    assert.equal((await store.cacheDiscordIdentity(actor(other), target, { username: 'ForgedOwner', displayName: 'Forged Owner' })).error, 'forbidden');

    for (const sub of ['info', 'uuids', 'sessions']) {
      const result = await signed('backend', command('backend', 'license', sub, selected)); privateReply(result);
      assert.ok(result.reply.embeds?.length, 'Normal management views use Discord embeds');
      const text = textOf(result.reply);
      assert.match(text, /Alice|Bob/);
      assert.ok(!text.includes(issued.account.accountId)); assert.ok(!text.includes(deviceHash)); assert.ok(!text.includes(hardwareHash));
      assert.ok(!text.includes('a'.repeat(32)) && !text.includes('b'.repeat(32)), 'Normal screens show Minecraft names');
    }
    const info = await signed('backend', command('backend', 'license', 'info', selected));
    assert.match(textOf(info.reply), /Current Display Name/);
    const debug = await signed('backend', command('backend', 'license', 'debug', selected)); privateReply(debug);
    assert.ok(textOf(debug.reply).includes(issued.account.accountId), 'Technical identifiers are available in explicit administrator debug');
    for (let index = 0; index < 12; index++) await sql.exec('INSERT INTO account_audit(at,actor,account_id,action,override,metadata,severity) VALUES(?,?,?,?,?,?,?)',
      now - index * 1000, target, issued.account.accountId, 'lease_issued', 0, JSON.stringify({ minecraftName: 'Alice', buildId: '2.4.2', secret: 'private-metadata-marker' }), 'information');
    const auditView = await signed('backend', command('backend', 'license', 'audit', [...selected, { type: 3, name: 'event', value: 'lease_issued' }, { type: 3, name: 'severity', value: 'information' }])); privateReply(auditView);
    assert.ok(!textOf(auditView.reply).includes('private-metadata-marker'));
    const nextPage = components(auditView.reply).find(button => button.custom_id?.startsWith('sky:audit:'));
    assert.ok(nextPage, 'A bounded audit view offers the next page');
    assert.ok(nextPage.custom_id.length <= 100);
    const auditPage = await click('backend', nextPage.custom_id); privateReply(auditPage);
    assert.notEqual(textOf(auditPage.reply), textOf(auditView.reply), 'The next-page button advances the audit cursor');
    const deniedPage = await click('backend', nextPage.custom_id, other); privateReply(deniedPage);
    assert.ok(!textOf(deniedPage.reply).includes('Alice'), 'Audit buttons independently verify administrator authority');
    const globalView = await signed('backend', command('backend', 'skyhawk', 'overview')); privateReply(globalView);
    assert.ok(globalView.reply.embeds?.length); assert.ok(!textOf(globalView.reply).includes(issued.account.accountId));
    const searched = await signed('backend', command('backend', 'skyhawk', 'search', [{ type: 3, name: 'query', value: 'Current Display Name' }])); privateReply(searched);
    assert.match(textOf(searched.reply), /Current Display Name/);

    const focused = (selectedUser = target, requester = administrator) => ({ ...command('backend', 'license', 'uuid-remove', [], requester), type: 4,
      data: { name: 'license', options: [{ type: 1, name: 'uuid-remove', options: [{ type: 6, name: 'user', value: selectedUser }, { type: 3, name: 'account', value: '', focused: true }] }] } });
    const choices = await signed('backend', focused()); assert.equal(choices.body.type, 8);
    assert.deepEqual(new Set(choices.body.data.choices.map(choice => choice.name)), new Set(['Alice', 'Bob']));
    assert.ok(choices.body.data.choices.every(choice => ['a'.repeat(32), 'b'.repeat(32)].includes(choice.value)));
    const otherChoices = await signed('backend', focused(other));
    assert.ok(otherChoices.body.data.choices.every(choice => choice.value === 'c'.repeat(32)));
    const deniedChoices = await signed('backend', focused(target, other));
    assert.equal(deniedChoices.body.type, 8); assert.deepEqual(deniedChoices.body.data.choices, []);

    const beforeRevoke = await accountState(), revoke = await confirmation('revoke');
    assert.deepEqual(await accountState(), beforeRevoke, 'Destructive commands make no changes before confirmation');
    const outsider = await click('backend', revoke.confirm, other); privateReply(outsider);
    assert.deepEqual(await accountState(), beforeRevoke, 'Another actor cannot approve the prompt');
    const foreignApp = await signed('user', { ...command('user', '', null), type: 3, data: { component_type: 2, custom_id: revoke.confirm } });
    privateReply(foreignApp); assert.deepEqual(await accountState(), beforeRevoke);
    const foreignGuild = await click('backend', revoke.confirm, administrator, { guild_id: '66666666666666666' }); privateReply(foreignGuild);
    assert.deepEqual(await accountState(), beforeRevoke);
    const cancelled = await click('backend', revoke.cancel); privateReply(cancelled);
    assert.deepEqual(await accountState(), beforeRevoke);
    const cancelledReplay = await click('backend', revoke.confirm); privateReply(cancelledReplay);
    assert.deepEqual(await accountState(), beforeRevoke, 'Cancelled confirmation cannot be reused');
    const expiring = await confirmation('revoke');
    await sql.exec('UPDATE account_management_confirmations SET expires_at=0 WHERE token=?', expiring.confirm.slice('sky:confirm:'.length));
    privateReply(await click('backend', expiring.confirm));
    assert.deepEqual(await accountState(), beforeRevoke, 'Expired approval cannot mutate licensing');
    const authorityLost = await confirmation('revoke');
    await sql.exec('UPDATE sky_accounts SET is_admin=0 WHERE discord_id=?', administrator);
    privateReply(await click('backend', authorityLost.confirm));
    assert.deepEqual(await accountState(), beforeRevoke, 'Administrator authority is rechecked after the prompt');
    await sql.exec('UPDATE sky_accounts SET is_admin=1 WHERE discord_id=?', administrator);
    const actualRevoke = await confirmation('revoke'); privateReply(await click('backend', actualRevoke.confirm));
    assert.equal((await accountState()).license_status, 'revoked');
    const afterRevoke = await accountState(); privateReply(await click('backend', actualRevoke.confirm));
    assert.deepEqual(await accountState(), afterRevoke, 'Confirmation executes once');
    assert.equal((await admin('license_restore')).ok, true);
    const stale = await store.prepareManagement(actor(), 'backend', 'session_kick', target, {}); assert.equal(stale.ok, true);
    const wrongActionState = await accountState();
    const wrongAction = await store.developerCommand(actor(), 'hwid_reset', target, { managementToken: stale.token });
    assert.equal(wrongAction.error, 'confirmation_expired'); assert.deepEqual(await accountState(), wrongActionState, 'A confirmation cannot authorize another action');
    assert.equal((await admin('session_kick')).ok, true);
    const staleState = await accountState();
    assert.equal((await store.confirmManagement(actor(), 'backend', stale.token, true)).error, 'confirmation_stale');
    assert.deepEqual(await accountState(), staleState, 'Account changes invalidate an old confirmation');
    assert.equal((await read('overview', '')).overview.online, 0, 'Force logout immediately removes the runtime from the online overview');
    const unlinkAll = await store.prepareManagement(actor(), 'backend', 'account_unlink', target, {}); assert.equal(unlinkAll.ok, true);
    await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', 'e'.repeat(32), issued.account.accountId, 'Dave', now, now);
    const linksAfterNewAccount = await sql.exec('SELECT * FROM minecraft_accounts WHERE account_id=? ORDER BY uuid', issued.account.accountId);
    const beforeStaleUnlink = await accountState();
    assert.equal((await store.confirmManagement(actor(), 'backend', unlinkAll.token, true)).error, 'confirmation_stale');
    assert.deepEqual(await sql.exec('SELECT * FROM minecraft_accounts WHERE account_id=? ORDER BY uuid', issued.account.accountId), linksAfterNewAccount,
      'A legacy unlink-all prompt cannot remove Minecraft accounts linked after its preview');
    assert.deepEqual(await accountState(), beforeStaleUnlink);
    await sql.exec('DELETE FROM minecraft_accounts WHERE uuid=?', 'e'.repeat(32));

    const otherBeforeBind = (await sql.exec('SELECT * FROM sky_accounts WHERE discord_id=?', other))[0], contestedDevice = 'f'.repeat(64);
    await store.cacheDiscordIdentity(actor(), other, { username: 'OtherUser', displayName: 'Other Display Name' });
    await sql.exec('INSERT INTO account_devices VALUES(?,?,?,NULL)', contestedDevice, otherAccount.accountId, now);
    await sql.exec('UPDATE sky_accounts SET device_hash=? WHERE account_id=?', contestedDevice, otherAccount.accountId);
    const transferPrompt = await store.prepareManagement(actor(), 'backend', 'hwid_bind', target, { deviceHash: contestedDevice }); assert.equal(transferPrompt.ok, true);
    assert.deepEqual(new Set(transferPrompt.affectedAccounts.map(details => details.account.discordId)), new Set([target, other]));
    assert.deepEqual(new Set(transferPrompt.affectedAccounts.map(details => details.displayName)), new Set(['Current Display Name', 'Other Display Name']),
      'Cross-account hardware confirmations identify both affected owners');
    await sql.exec('UPDATE sky_accounts SET generation=generation+1 WHERE account_id=?', otherAccount.accountId);
    const accountsAfterOwnerChange = await sql.exec('SELECT * FROM sky_accounts WHERE discord_id IN (?,?) ORDER BY discord_id', target, other);
    const deviceAfterOwnerChange = await sql.exec('SELECT * FROM account_devices WHERE device_hash=?', contestedDevice);
    assert.equal((await store.confirmManagement(actor(), 'backend', transferPrompt.token, true)).error, 'confirmation_stale');
    assert.deepEqual(await sql.exec('SELECT * FROM sky_accounts WHERE discord_id IN (?,?) ORDER BY discord_id', target, other), accountsAfterOwnerChange,
      'Changes to a previous device owner invalidate a cross-account hardware confirmation without mutating either account');
    assert.deepEqual(await sql.exec('SELECT * FROM account_devices WHERE device_hash=?', contestedDevice), deviceAfterOwnerChange);
    await sql.exec('DELETE FROM account_devices WHERE device_hash=?', contestedDevice);
    await sql.exec('UPDATE sky_accounts SET device_hash=?,generation=? WHERE account_id=?', otherBeforeBind.device_hash, otherBeforeBind.generation, otherAccount.accountId);
    const ownedRemoval = await signed('backend', command('backend', 'license', 'uuid-remove', [...selected, { type: 3, name: 'account', value: 'c'.repeat(32) }]));
    privateReply(ownedRemoval);
    const removalConfirm = components(ownedRemoval.reply).find(button => button.custom_id?.startsWith('sky:confirm:'));
    if (removalConfirm) privateReply(await click('backend', removalConfirm.custom_id));
    assert.equal((await store.getAccountByUuid('c'.repeat(32))).discordId, other, 'UUID selection never bypasses target ownership');

    for (const name of ['status', 'help']) {
      const result = await signed('user', command('user', name, null)); privateReply(result);
      if (name === 'status') assert.ok(result.reply.embeds?.length);
      assert.ok(!textOf(result.reply).includes(issued.account.accountId));
      assert.ok(!textOf(result.reply).includes(deviceHash)); assert.ok(!textOf(result.reply).includes('a'.repeat(32)));
    }
    const unavailable = await signed('user', command('user', 'key', null, [], '77777777777777777')); privateReply(unavailable);
    const beforeKeys = (await sql.exec('SELECT COUNT(*) AS n FROM activation_keys WHERE account_id=?', issued.account.accountId))[0].n;
    const recovered = await signed('user', command('user', 'key', null)); privateReply(recovered);
    const keyRows = await sql.exec('SELECT * FROM activation_keys WHERE account_id=? AND used_at IS NULL AND revoked_at IS NULL', issued.account.accountId);
    assert.equal(keyRows.length, 1); assert.equal(keyRows[0].hardware_transfer, 0);
    const repeated = await signed('user', command('user', 'key', null)); privateReply(repeated);
    assert.equal((await sql.exec('SELECT COUNT(*) AS n FROM activation_keys WHERE account_id=?', issued.account.accountId))[0].n, beforeKeys + 1, 'Recovery requests are durably bounded');
    const activationDms = calls.filter(call => /\/channels\/\d+\/messages$/.test(call.path) && recipients.get(call.path.split('/').at(-2)) === target);
    assert.ok(activationDms.length > 0);
    const recoveredKey = activationDms.flatMap(call => [...textOf(call.body).matchAll(/`([A-Za-z0-9_-]{43})`/g)]).map(match => match[1])
      .find(value => createHash('sha256').update(value).digest('hex') === keyRows[0].key_hash);
    assert.ok(recoveredKey, 'The one-time activation credential is delivered privately to its owner');
    assert.ok(calls.filter(call => call.method === 'PATCH').every(call => !JSON.stringify(call.body).includes(recoveredKey)), 'Activation credentials remain outside channel replies');

    const loader = await signed('user', command('user', 'loader', null)); privateReply(loader);
    assert.match(textOf(loader.reply), /Loader|loader/);
    const loaderDm = calls.findLast(call => /\/channels\/\d+\/messages$/.test(call.path) && recipients.get(call.path.split('/').at(-2)) === target && /\/discord\/loader#/.test(textOf(call.body)));
    const downloadUrl = new URL(textOf(loaderDm.body).match(/https:\/\/worker\.example\/discord\/loader#[\w.-]+/)[0]);
    const downloadToken = downloadUrl.hash.slice(1);
    const allowedDownload = await mf.dispatchFetch('https://worker.example/discord/loader', { method: 'POST', headers: { Authorization: `Bearer ${downloadToken}` } });
    assert.equal(allowedDownload.status, 200); assert.deepEqual(Buffer.from(await allowedDownload.arrayBuffer()), loaderBytes);
    const oldBuildProof = await installationRequest('renew');
    const revokeBuild = await admin('build_revoke', '1.0.10', { kind: 'loader' }); assert.equal(revokeBuild.ok, true);
    assert.equal((await store.buildAllowed('loader', '1.0.10')).error, 'BUILD_REVOKED');
    await sql.exec('UPDATE account_loader_delivery SET last_requested_at=0 WHERE discord_id=?', target);
    const afterBuildRevoke = calls.length;
    privateReply(await signed('user', command('user', 'loader', null)));
    assert.ok(!calls.slice(afterBuildRevoke).some(call => /\/channels\/\d+\/messages$/.test(call.path) && recipients.get(call.path.split('/').at(-2)) === target && JSON.stringify(call.body).includes('/discord/loader#')), 'Revoked loaders are not delivered');
    const revokedDownload = await mf.dispatchFetch('https://worker.example/discord/loader', { method: 'POST', headers: { Authorization: `Bearer ${downloadToken}` } });
    assert.equal(revokedDownload.status, 403, 'Existing private download tickets cannot distribute a newly revoked current loader');
    assert.equal((await store.challenge({ ...input, purpose: 'renew', clientNonce: randomUUID() }, 'revoked-loader-challenge')).error, 'BUILD_REVOKED');
    const oldBuildRenew = await store.authenticateInstallation(oldBuildProof, 'renew');
    assert.equal(oldBuildRenew.error, 'BUILD_REVOKED');
    const upgradedRenew = await store.authenticateInstallation(await installationRequest('renew', { loaderVersion: '1.0.11' }), 'renew');
    assert.equal(upgradedRenew.ok, true, 'The updated Loader renews normally despite obsolete stored runtime version metadata');
    const bootstrapRecovery = await store.authenticateInstallation(await installationRequest('bootstrap-renew', { modVersion: '0.1.0' }), 'bootstrap-renew');
    assert.equal(bootstrapRecovery.ok, true); assert.equal(bootstrapRecovery.lease, undefined, 'A revoked running Loader receives bootstrap recovery without protected feature authorization');
    loaderRelease.version = '1.0.11'; loaderRelease.artifact = 'loaders/Sky-Myce-Loader-1.0.11.jar';
    const updatedLoaderPayload = Buffer.from(JSON.stringify(loaderRelease));
    await bucket.put('loaders/current.json', JSON.stringify({ kid: 'test', payload: updatedLoaderPayload.toString('base64url'), signature: sign(null, updatedLoaderPayload, signer.privateKey).toString('base64url') }));
    await bucket.put(loaderRelease.artifact, loaderBytes);
    const approvedRecovery = await mf.dispatchFetch('https://worker.example/loader/bootstrap/renew', { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': 'approved-bootstrap-recovery' },
      body: JSON.stringify({ ...await installationRequest('bootstrap-renew', { modVersion: '0.1.0' }), version: '1.0.11' }) });
    assert.equal(approvedRecovery.status, 200); assert.deepEqual(Buffer.from(await approvedRecovery.arrayBuffer()), loaderBytes, 'Old revoked loaders can recover only the signed approved replacement');
    assert.equal((await admin('build_restore', '1.0.10', { kind: 'loader' })).ok, true);
    assert.equal((await store.buildAllowed('loader', '1.0.10')).ok, true);
    assert.ok(calls.every(call => !JSON.stringify(call.body).includes('private-backend-token') && !JSON.stringify(call.body).includes('private-user-token')), 'Bot credentials never appear in Discord content');

    await sql.exec('DELETE FROM account_alerts');
    await sql.exec('DELETE FROM account_alert_dedup');
    for (let attempt = 0; attempt < 2; attempt++) {
      const denial = await store.authenticateInstallation(await installationRequest('renew', { hardwareHash: '2'.repeat(64) }), 'renew');
      assert.equal(denial.error, 'HWID_MISMATCH');
    }
    const warningRows = await read('audit', target, { severity: 'warning', event: 'lease_denied', limit: 25 });
    assert.ok(warningRows.events.length >= 2);
    assert.ok(warningRows.events.every(event => event.metadata.minecraftName === 'Alice' && event.metadata.discordName === 'Current Display Name'));
    assert.deepEqual(await store.claimAccountAlerts(11), [], 'Alert claims are bounded');
    const alerts = await store.claimAccountAlerts(5);
    assert.equal(alerts.length, 2, 'Repeated second-device attempts are grouped into one alert for each configured administrator');
    assert.deepEqual(new Set(alerts.map(alert => alert.recipientId)), new Set(['978376257534328883', '1250627753191931914']));
    assert.ok(alerts.every(alert => alert.repeats === 1 && alert.event.severity === 'warning'));
    assert.deepEqual(await store.claimAccountAlerts(5), [], 'A claimed outbox event cannot be delivered concurrently');
    await store.completeAccountAlert(alerts[0].id, 'forged-claim', true);
    assert.equal((await sql.exec('SELECT COUNT(*) AS n FROM account_alerts WHERE id=?', alerts[0].id))[0].n, 1, 'Claim completion requires the opaque matching claim token');
    await store.completeAccountAlert(alerts[0].id, alerts[0].claimToken, false, 3000);
    const retry = (await sql.exec('SELECT * FROM account_alerts WHERE id=?', alerts[0].id))[0];
    assert.equal(retry.attempts, 1); assert.ok(retry.next_at > Date.now());
    await store.completeAccountAlert(alerts[1].id, alerts[1].claimToken, true);
    assert.equal((await sql.exec('SELECT COUNT(*) AS n FROM account_alerts WHERE id=?', alerts[1].id))[0].n, 0);
    assert.ok(await store.nextAccountAlertAt());
    await sql.exec('UPDATE account_alerts SET next_at=0,lease_until=0 WHERE id=?', alerts[0].id);
    const deliveryStart = calls.length;
    await signed('backend', command('backend', 'license', 'info', selected));
    for (let attempt = 0; attempt < 150; attempt++) {
      if (calls.slice(deliveryStart).some(call => /\/channels\/\d+\/messages$/.test(call.path) && recipients.get(call.path.split('/').at(-2)) === administrator)) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    const warningDm = calls.slice(deliveryStart).find(call => /\/channels\/\d+\/messages$/.test(call.path) && recipients.get(call.path.split('/').at(-2)) === administrator);
    assert.ok(warningDm, 'A second-device warning reaches the configured administrator privately');
    assert.match(textOf(warningDm.body), /Current Display Name|RenamedUser/);
    assert.match(textOf(warningDm.body), /Alice/); assert.match(textOf(warningDm.body), /2\.4\.2/);
    assert.ok(!JSON.stringify(warningDm.body).includes(hardwareHash), 'Administrator alerts contain opaque context without raw physical hardware proof');
    assert.deepEqual(warningDm.body.allowed_mentions, { parse: [] });

    const wrongSignature = await signed('backend', command('backend', 'license', 'info', selected), 'user');
    assert.equal(wrongSignature.status, 401, 'Each application uses its own Discord signing key');
    console.log('Discord UX checks passed: signed native commands, private friendly views, stable identities, scoped autocomplete, confirmation binding/expiry/replay/revoked authority, UUID ownership, bounded private recovery and loader revocation.');
  } finally { await mf.dispose(); }
}
