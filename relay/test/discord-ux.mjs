import assert from 'node:assert/strict';
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Actual Worker + SQLite + Discord-signed HTTP interactions; no live Discord/Cloudflare calls. */
export async function checkDiscordUx(script, minecraftProof) {
  const guild = '11111111111111111', administrator = '978376257534328883', target = '44444444444444444', other = '55555555555555555';
  const apps = { backend: '22222222222222222', user: '33333333333333333' };
  const keys = { backend: generateKeyPairSync('ed25519'), user: generateKeyPairSync('ed25519') };
  const rawKey = pair => pair.publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('hex');
  const users = new Map([[target, { id: target, username: 'ExampleMember', global_name: 'Example display', bot: false }],
    [other, { id: other, username: 'SharedComputerMember', global_name: 'Other display', bot: false }],
    [administrator, { id: administrator, username: 'Administrator', global_name: 'Admin', bot: false }]]);
  const calls = [];
  const bindings = { ROOMS: 'testing', DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: administrator,
    DISCORD_BACKEND_APPLICATION_ID: apps.backend, DISCORD_BACKEND_PUBLIC_KEY: rawKey(keys.backend),
    DISCORD_USER_APPLICATION_ID: apps.user, DISCORD_USER_PUBLIC_KEY: rawKey(keys.user) };
  const options = convertV4MiniflareOptions({ name: 'discord-ux-check', modules: true, script, compatibilityDate: '2026-09-27', bindings,
    durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, RELAY_ROOMS: { className: 'RelayRoom', useSQLite: true } },
    outboundService: async request => {
      const url = new URL(request.url), body = await request.json();
      calls.push({ path: url.pathname, method: request.method, body, authorization: request.headers.get('Authorization') });
      assert.equal(url.hostname, 'discord.com'); assert.equal(request.method, 'PATCH', 'Native connection/reports require no bot token, activation DM or Loader download');
      return Response.json({});
    } });
  options.workers[0].config.env.CONNECT_LIMIT = { type: 'rate-limit', namespace: '7112026', simple: { limit: 1000, period: 60 } };
  configureTrafficLimits(options);
  options.unsafeInspectDurableObjects = true;
  const mf = new Miniflare(options);
  try {
    const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const sql = await mf.unsafeGetDurableObjectStorage('discord-ux-check', 'AccountStore', { name: 'global' });
    let sequence = 90000000000000000n;
    const command = (kind, name, sub = null, values = [], user = kind === 'backend' ? administrator : target) => ({ type: 2,
      id: String(sequence++), application_id: apps[kind], guild_id: guild, token: `private-interaction-${sequence}`,
      member: { user: users.get(user) ?? { id: user, username: 'Unprivileged' }, roles: [] },
      data: { name, options: sub ? [{ type: 1, name: sub, options: values }] : values } });
    const textOf = reply => [reply.content ?? '', ...(reply.embeds ?? []).flatMap(embed => [embed.title ?? '', embed.description ?? '',
      ...(embed.fields ?? []).map(field => `${field.name}\n${field.value}`), embed.footer?.text ?? ''])].join('\n');
    const components = reply => (reply.components ?? []).flatMap(row => row.components ?? []);
    const signed = async (kind, data, signingKind = kind) => {
      const body = JSON.stringify(data), stamp = String(Math.floor(Date.now() / 1000)), before = calls.length;
      const response = await mf.dispatchFetch(`https://worker.example/discord/${kind === 'backend' ? 'backend/' : ''}interactions`, {
        method: 'POST', headers: { 'X-Signature-Timestamp': stamp,
          'X-Signature-Ed25519': sign(null, Buffer.from(stamp + body), keys[signingKind].privateKey).toString('hex'),
          'CF-Connecting-IP': `ux-${sequence++}` }, body });
      const result = response.headers.get('Content-Type')?.includes('json') ? await response.json() : await response.text();
      if (response.status !== 200 || ![5, 6].includes(result.type)) return { status: response.status, body: result, reply: result.data };
      let edit;
      for (let attempt = 0; attempt < 100; attempt++) {
        edit = calls.slice(before).findLast(call => call.path.includes(`/${data.application_id}/${data.token}/`));
        if (edit) break;
        await new Promise(resolve => setTimeout(resolve, 10));
      }
      assert.ok(edit, 'Deferred private interaction completes');
      return { status: response.status, body: result, reply: edit.body };
    };
    const privateReply = response => {
      assert.equal(response.status, 200); assert.ok([4, 5, 6, 7].includes(response.body.type));
      if ([4, 5].includes(response.body.type)) assert.equal(response.body.data.flags & 64, 64);
      assert.deepEqual(response.reply.allowed_mentions, { parse: [] }); assert.ok(textOf(response.reply).length <= 6000);
    };
    const click = (kind, customId, user = administrator, changes = {}) => signed(kind, { ...command(kind, '', null, [], user),
      type: 3, data: { component_type: 2, custom_id: customId }, ...changes });
    const review = async (name, sub, values, kind = 'backend', user = administrator) => {
      const response = await signed(kind, command(kind, name, sub, values, user)); privateReply(response);
      const confirm = components(response.reply).find(button => button.custom_id?.startsWith('sky:confirm:'));
      const cancel = components(response.reply).find(button => button.custom_id?.startsWith('sky:cancel:'));
      assert.ok(confirm && cancel); assert.match(confirm.custom_id, /^sky:confirm:[A-Za-z0-9_-]{43}$/);
      return { response, confirm: confirm.custom_id, cancel: cancel.custom_id };
    };
    const selected = [{ type: 6, name: 'user', value: target }], reason = { type: 3, name: 'reason', value: 'Reviewed online abuse' };
    const secrets = [];
    const start = async (name, identifier = 'd'.repeat(64), source = 'windows-smbios', platform = 'windows') => {
      const challenge = await store.communityChallenge({ purpose: 'connect', clientNonce: randomBytes(32).toString('base64url') }); assert.equal(challenge.ok, true);
      const pollSecret = randomBytes(32).toString('base64url'); secrets.push(pollSecret);
      const flow = await store.communityConnectStart({ challengeId: challenge.challengeId, minecraftProof: minecraftProof(name, challenge.nonce), pollSecret,
        device: { identifier, source, platform }, modVersion: '2.4.2' });
      assert.equal(flow.ok, true); assert.equal(flow.browserUrl, null, 'Missing server OAuth settings provides fallback instead of approval');
      return { ...flow, pollSecret };
    };
    const finish = async (flow, user = target) => {
      const data = command('user', 'connect', null, [{ name: 'code', type: 3, value: flow.fallbackCode }], user);
      const response = await signed('user', data); privateReply(response); assert.match(textOf(response.reply), /Discord connected/);
      const session = await store.communityConnectPoll({ flowId: flow.flowId, pollSecret: flow.pollSecret });
      assert.equal(session.ok, true); assert.ok(session.credentials); secrets.push(session.credentials.accessToken, session.credentials.refreshToken);
      assert.equal(session.identity.discordId, user); return { ...session, data, flow };
    };
    const alice = await finish(await start('Alice'));
    const aliceSecondPc = await finish(await start('Alice', 'e'.repeat(64), 'linux-dmi', 'linux'));
    const bob = await finish(await start('Bob'));
    const carol = await finish(await start('Carol'), other);
    assert.equal((await store.communityGetSharing(alice.credentials.accessToken)).ok, true, 'Another PC does not revoke this session');
    assert.equal((await store.communityGetSharing(aliceSecondPc.credentials.accessToken)).ok, true);
    assert.equal((await store.communityGetSharing(carol.credentials.accessToken)).ok, true, 'A shared device never blocks another account');
    const replay = await signed('user', alice.data); privateReply(replay); assert.match(textOf(replay.reply), /already handled/);
    const codeReplay = await signed('user', command('user', 'connect', null, [{ name: 'code', type: 3, value: alice.flow.fallbackCode }]));
    privateReply(codeReplay); assert.doesNotMatch(textOf(codeReplay.reply), /Discord connected/);

    const status = await signed('user', command('user', 'status')); privateReply(status);
    assert.match(textOf(status.reply), /Alice.*Bob/s); assert.ok(!textOf(status.reply).includes('d'.repeat(64)));
    const overview = await signed('backend', command('backend', 'account', 'overview', selected)); privateReply(overview);
    assert.match(textOf(overview.reply), /Example display.*44444444444444444.*Alice.*UUID.*First seen.*Last seen/s);
    const devices = await signed('backend', command('backend', 'account', 'devices', selected)); privateReply(devices);
    assert.ok(textOf(devices.reply).includes('d'.repeat(64))); assert.ok(textOf(devices.reply).includes('e'.repeat(64)));
    assert.match(textOf(devices.reply), /windows-smbios.*Alice.*Bob.*55555555555555555/s);
    assert.match(textOf(devices.reply), /never authorize, punish or block/);
    const denied = await signed('backend', command('backend', 'account', 'devices', selected, other)); privateReply(denied);
    assert.match(textOf(denied.reply), /Only persisted Sky-Myce administrators/); assert.ok(!textOf(denied.reply).includes('d'.repeat(64)));
    const autocomplete = await signed('user', { ...command('user', 'account', 'unlink', [{ type: 3, name: 'uuid', value: '', focused: true }]), type: 4 });
    assert.deepEqual(new Set(autocomplete.body.data.choices.map(choice => choice.name)), new Set(['Alice', 'Bob']));
    const wrongSignature = await signed('backend', command('backend', 'account', 'overview', selected), 'user'); assert.equal(wrongSignature.status, 401);
    const wrongApplication = await signed('backend', { ...command('backend', 'account', 'overview', selected), application_id: apps.user }); assert.equal(wrongApplication.status, 401);
    for (const data of [command('user', 'hwid', 'reset'), command('backend', 'license', 'whitelist', selected), command('user', 'account', 'approve')]) {
      const response = await signed(data.application_id === apps.user ? 'user' : 'backend', data); privateReply(response);
      assert.match(textOf(response.reply), /older activation and hardware-reset commands have been removed/);
    }

    const preview = await review('blacklist', 'add', [...selected, reason]);
    assert.equal((await sql.exec("SELECT COUNT(*) n FROM community_blacklist WHERE removed_at IS NULL"))[0].n, 0, 'Review causes no moderation mutation');
    const wrongActor = await click('backend', preview.confirm, other); privateReply(wrongActor);
    assert.match(textOf(wrongActor.reply), /permission|expired|administrators/);
    const wrongKind = await click('user', preview.confirm, administrator); privateReply(wrongKind); assert.match(textOf(wrongKind.reply), /expired/);
    const wrongGuild = await click('backend', preview.confirm, administrator, { guild_id: '99999999999999999' }); privateReply(wrongGuild);
    assert.match(textOf(wrongGuild.reply), /permission/);
    const cancelled = await click('backend', preview.cancel); privateReply(cancelled); assert.match(textOf(cancelled.reply), /Cancelled/);
    assert.equal((await store.communityGetSharing(alice.credentials.accessToken)).ok, true);

    const expired = await review('blacklist', 'add', [...selected, reason]);
    await sql.exec('UPDATE community_management SET expires_at=0 WHERE token=?', expired.confirm.split(':')[2]);
    const expiry = await click('backend', expired.confirm); privateReply(expiry); assert.match(textOf(expiry.reply), /expired/);
    const stale = await review('blacklist', 'add', [...selected, reason]);
    await sql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,?,?)', 'discord', target, 'Previously reviewed', administrator, Date.now(), Date.now(), 1);
    const changed = await click('backend', stale.confirm); privateReply(changed); assert.match(textOf(changed.reply), /changed after review/);

    const add = await review('blacklist', 'add', [...selected, reason]);
    const added = await click('backend', add.confirm); privateReply(added); assert.match(textOf(added.reply), /Identity blacklisted/);
    assert.equal((await store.communityGetSharing(alice.credentials.accessToken)).error, 'session_expired');
    assert.equal((await store.communityGetSharing(aliceSecondPc.credentials.accessToken)).ok, false);
    assert.equal((await store.communityGetSharing(carol.credentials.accessToken)).ok, true, 'Other Discord users sharing this hash are unaffected');
    const replayConfirmation = await click('backend', add.confirm); privateReply(replayConfirmation); assert.match(textOf(replayConfirmation.reply), /expired|already/);
    const audit = await signed('backend', command('backend', 'account', 'audit', selected)); privateReply(audit); assert.ok(textOf(audit.reply).includes('blacklist\\_add'));
    const blacklist = await signed('backend', command('backend', 'blacklist', 'info', selected)); privateReply(blacklist); assert.match(textOf(blacklist.reply), /Restricted.*Reviewed online abuse/s);
    const remove = await review('blacklist', 'remove', [...selected, reason]);
    privateReply(await click('backend', remove.confirm));
    const aliceAgain = await finish(await start('Alice', 'f'.repeat(64)));
    const bobAgain = await finish(await start('Bob'));
    const blockMinecraft = await review('blacklist', 'add', [{ type: 3, name: 'uuid', value: 'b'.repeat(32) }, reason]);
    privateReply(await click('backend', blockMinecraft.confirm));
    assert.equal((await store.communityGetSharing(bobAgain.credentials.accessToken)).ok, false);
    assert.equal((await store.communityGetSharing(aliceAgain.credentials.accessToken)).ok, true, 'A Minecraft-specific blacklist does not revoke another UUID');
    const disconnected = await review('disconnect', null, [], 'user', target);
    privateReply(await click('user', disconnected.confirm, target));
    const own = await store.communityUserCommand({ id: target, guildId: guild, roles: [], interactionId: String(sequence++) }, 'status');
    assert.deepEqual(new Set(own.identity.uuids.map(mc => mc.name)), new Set(['Alice', 'Bob']), 'Disconnect preserves associations/profile');
    const unlink = await review('account', 'unlink', [{ type: 3, name: 'uuid', value: 'b'.repeat(32) }], 'user', target);
    privateReply(await click('user', unlink.confirm, target));
    const links = await sql.exec('SELECT name FROM minecraft_accounts WHERE account_id=?', own.identity.accountId);
    assert.deepEqual(links.map(link => link.name), ['Alice']);

    // Existing licensing/enrollment rows are history, not access requirements for the new community flow.
    await sql.exec("UPDATE sky_accounts SET license_status='revoked',enrollment_blocked=1,device_hash=?,hardware_hash=?,hwid_reset_at=? WHERE discord_id=?", '0'.repeat(64), '1'.repeat(64), Date.now(), target);
    const unrestricted = await finish(await start('Alice', '2'.repeat(64)));
    assert.equal((await store.communityGetSharing(unrestricted.credentials.accessToken)).ok, true);
    assert.ok(calls.every(call => !secrets.some(secret => JSON.stringify(call.body).includes(secret))), 'Discord output never exposes community access/refresh/poll credentials');
    assert.ok(calls.every(call => !call.authorization));
    console.log('Community Discord UX checks passed: signed self-service connect, private multi-account/device reports, no approval/HWID gating, confirmed identity blacklists, stale/expiry/replay/cross-kind/actor checks and retained disconnect associations.');
  } finally { await mf.dispose(); }
}
