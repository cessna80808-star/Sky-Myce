import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

async function load(entry) {
  const result = await build({ entryPoints: [new URL(`../src/${entry}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')],
    bundle: true, format: 'esm', platform: 'browser', write: false });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
}
const { accountCommand, handleAccountInteraction, handleAccountAutocomplete } = await load('discord-accounts.ts');
const { verifiedInteraction } = await load('discord.ts');
const { deliverAccountAlert } = await load('discord-notifications.ts');
const { ircMedia } = await load('irc-model.ts');
const actor = { id: '11111111111111111', guildId: '22222222222222222', roles: [], interactionId: '33333333333333333' };
const config = { DISCORD_GUILD_ID: actor.guildId };
const target = '66666666666666666', targetOption = { type: 6, name: 'user', value: target };
const mcUuid = 'a'.repeat(32), device = 'd'.repeat(64);
const identity = { accountId: 'private-account-reference', discordId: target, username: 'example', displayName: 'Example member', blacklisted: false,
  uuids: [{ uuid: mcUuid, name: 'ExamplePlayer', firstSeen: 1800000000000, lastSeen: 1800001000000 }] };
const command = (name = 'status', sub = null, options = []) => ({ type: 2, id: actor.interactionId, guild_id: actor.guildId,
  application_id: '55555555555555555', token: 'interaction-token-never-log-this', member: { user: { id: actor.id }, roles: actor.roles },
  data: { name, options: sub ? [{ type: 1, name: sub, options }] : options } });

test('each bot accepts only its own Discord signature, exact body and fresh timestamp', async () => {
  const keys = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  const hex = bytes => Buffer.from(bytes).toString('hex');
  const publicKey = hex(await crypto.subtle.exportKey('raw', keys.publicKey));
  const stamp = String(Math.floor(Date.now() / 1000)), body = JSON.stringify(command());
  const signature = hex(await crypto.subtle.sign('Ed25519', keys.privateKey, new TextEncoder().encode(stamp + body)));
  const request = (text = body, timestamp = stamp) => new Request('https://worker.example/discord/interactions', { method: 'POST',
    headers: { 'X-Signature-Ed25519': signature, 'X-Signature-Timestamp': timestamp }, body: text });
  const env = { DISCORD_PUBLIC_KEY: publicKey, DISCORD_APPLICATION_ID: '55555555555555555' };
  assert.ok(await verifiedInteraction(request(), env));
  assert.equal(await verifiedInteraction(request(), { ...env, DISCORD_APPLICATION_ID: '66666666666666666' }), null);
  assert.equal(await verifiedInteraction(request(body + ' '), env), null);
  assert.equal(await verifiedInteraction(request(body, '1000000000'), env), null);
});

test('self-service connection belongs to the signed caller, has no approval/key workflow and accepts only a short-lived fallback code', () => {
  const code = { name: 'code', type: 3, value: 'ABCD-EFGH-JKLM' };
  assert.equal(accountCommand(command('connect', null, [code]), 'user').target, actor.id);
  assert.equal(accountCommand(command('account', 'link', [code]), 'user').action, 'connect');
  assert.equal(accountCommand(command(), 'user').target, actor.id);
  assert.equal(accountCommand(command('connect', null, [code]), 'backend'), null);
  for (const options of [[], [code, targetOption], [{ ...code, value: 'stolen-or-malformed-code' }], [{ ...code, type: 6 }], [code, code]])
    assert.equal(accountCommand(command('connect', null, options), 'user'), null);
  assert.equal(accountCommand(command('account', 'unlink', [{ name: 'uuid', type: 3, value: mcUuid }]), 'user').options.uuid, mcUuid);
  assert.equal(accountCommand(command('account', 'unlink', [{ name: 'uuid', type: 3, value: mcUuid }, targetOption]), 'user'), null);
  for (const data of [command('authorise', null, [targetOption]), command('license', 'whitelist', [targetOption]), command('license', 'key', [targetOption]),
    command('hwid', 'reset'), command('account', 'approve', [code]), command('account', 'reset'), command('key'), command('loader')])
    for (const kind of ['user', 'backend']) assert.equal(accountCommand(data, kind), null);
});

test('blacklist selections are exact identity-only admin operations and cannot target hardware', () => {
  const reason = { name: 'reason', type: 3, value: 'Reviewed abusive online activity' };
  const uuid = { name: 'uuid', type: 3, value: mcUuid };
  for (const sub of ['add', 'remove']) {
    const discord = accountCommand(command('blacklist', sub, [reason, targetOption]), 'backend');
    assert.equal(discord.action, `blacklist_${sub}`); assert.equal(discord.target, target);
    assert.deepEqual(discord.options, { reason: reason.value, kind: 'discord', identity: target });
    const minecraft = accountCommand(command('blacklist', sub, [reason, uuid]), 'backend');
    assert.deepEqual(minecraft.options, { reason: reason.value, kind: 'minecraft', identity: mcUuid });
    for (const options of [[reason], [reason, targetOption, uuid], [targetOption], [reason, { ...uuid, value: 'bad' }],
      [reason, { name: 'hwid', type: 3, value: device }], [reason, { name: 'device-hash', type: 3, value: device }],
      [reason, targetOption, { name: 'confirm', type: 5, value: true }], [{ ...reason, value: 'x'.repeat(501) }, targetOption]])
      assert.equal(accountCommand(command('blacklist', sub, options), 'backend'), null);
    assert.equal(accountCommand(command('blacklist', sub, [reason, targetOption]), 'user'), null);
  }
  assert.equal(accountCommand(command('blacklist', 'info', [uuid]), 'backend').options.kind, 'minecraft');
  assert.equal(accountCommand(command('blacklist', 'list', [{ name: 'page', type: 4, value: 2 }]), 'backend').options.page, 2);
  assert.equal(accountCommand(command('blacklist', 'info', []), 'backend'), null);
});

async function responseFor(data, result = { ok: true, identity }, kind = 'user', settings = {}) {
  const previous = globalThis.fetch, calls = [], pending = [], operations = [], invalidated = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json({}); };
  const store = {
    isAdmin: async caller => { operations.push(['isAdmin', caller.id]); return settings.admin !== false; },
    cacheDiscordIdentity: async () => ({ ok: true }),
    communityUserCommand: async (caller, action, options) => { operations.push(['user', caller.id, action, options]); return result; },
    discordLeaderboard: async (caller, options) => { operations.push(['leaderboard', caller, options]); return result; },
    discordBazaar: async (caller, action, period) => { operations.push(['bazaar', caller, action, period]); return result; },
    communityRead: async (caller, action, selected, options) => { operations.push(['read', caller.id, action, selected, options]); return result; },
    communityManage: async () => { throw Error('Native managed mutations must not bypass confirmation'); },
    prepareCommunityManagement: async (caller, selectedKind, action, selected, options) => {
      operations.push(['prepare', caller.id, selectedKind, action, selected, options]);
      return { ...result, token: 't'.repeat(43), expiresAt: Date.now() + 120000, action, target: selected, options };
    },
    confirmCommunityManagement: async (caller, selectedKind, token, confirm) => {
      operations.push(['confirm', caller.id, selectedKind, token, confirm]);
      if (settings.confirmError) return { ok: false, error: settings.confirmError };
      const prepared = operations.find(operation => operation[0] === 'prepare');
      return confirm ? { ...result, action: prepared[3], target: prepared[4], options: prepared[5] } : { ok: true, action: 'cancelled' };
    },
  };
  const env = { ...config, ROOMS: 'testing', ACCOUNT_STORE: { getByName: () => store },
    RELAY_ROOMS: { getByName: () => ({ invalidateAccount: async id => invalidated.push(id) }) } };
  try {
    const response = handleAccountInteraction(data, env, { waitUntil: promise => pending.push(promise) }, kind);
    assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } }); await Promise.all(pending);
    const initial = JSON.parse(calls.at(-1).options.body);
    const confirm = initial.components?.flatMap(row => row.components).find(button => button.custom_id?.startsWith('sky:confirm:'));
    if (confirm && settings.confirm !== false) {
      settings.review?.({ initial, operations, calls }); pending.length = 0;
      handleAccountInteraction({ ...data, id: '77777777777777777', type: 3,
        data: { component_type: 2, custom_id: settings.cancel ? confirm.custom_id.replace('sky:confirm:', 'sky:cancel:') : confirm.custom_id } },
      env, { waitUntil: promise => pending.push(promise) }, kind); await Promise.all(pending);
    }
    const reply = JSON.parse(calls.at(-1).options.body);
    assert.deepEqual(reply.allowed_mentions, { parse: [] });
    assert.ok((reply.content ?? '').length <= 1900);
    assert.ok(calls.every(call => call.options.method === 'PATCH'), 'Bot tokens, DMs and release artifacts are unnecessary for connection');
    return { reply, initial, operations, calls, invalidated, text: JSON.stringify(reply) };
  } finally { globalThis.fetch = previous; }
}

test('connection and status work without a bot token, license, activation key, loader artifact or hardware registration', async () => {
  const result = { ok: true, identity: { ...identity, discordId: actor.id }, devices: [{ identifier: device }], activationKey: 'never-show-this-secret' };
  for (const data of [command(), command('connect', null, [{ name: 'code', type: 3, value: 'ABCD-EFGH-JKLM' }])]) {
    const response = await responseFor(data, result);
    assert.match(response.text, /Discord connected/); assert.match(response.text, /ExamplePlayer/);
    assert.ok(!response.text.includes(device)); assert.ok(!response.text.includes(result.activationKey));
    assert.ok(!response.text.includes(identity.accountId)); assert.ok(!response.text.includes('hwidResetAt'));
    assert.deepEqual(response.operations.filter(operation => operation[0] === 'user').map(operation => operation[1]), [actor.id]);
  }
  const help = await responseFor(command('help'));
  assert.match(help.text, /No whitelist, activation key, custom Loader or hardware reset is needed/);
  assert.match(help.text, /sharing starts as On \(anon\).*Off removes.*chat and cosmetics/s);
});

test('admin checks occur before private identity lookups or device disclosure and roles cannot bypass them', async () => {
  for (const data of [command('account', 'devices', [targetOption]), command('blacklist', 'add', [targetOption, { name: 'reason', type: 3, value: 'review' }])]) {
    data.member.roles = ['88888888888888888']; data.member.permissions = '8';
    const response = await responseFor(data, { ok: true, identity, devices: [{ identifier: device }] }, 'backend', { admin: false });
    assert.deepEqual(response.operations.map(operation => operation[0]), ['isAdmin']);
    assert.match(response.text, /Only persisted Sky-Myce administrators/); assert.ok(!response.text.includes(device));
  }
});

test('private association reports identify multiple computers/shared accounts without treating observations as restrictions', async () => {
  const result = { ok: true, identity, devices: [
    { identifier: device, source: 'hardware_hash', platform: 'linux', firstSeen: 1800000000000, lastSeen: 1800001000000,
      minecraft: identity.uuids, relatedAccounts: [{ discordId: '99999999999999999', username: 'other', displayName: 'Shared computer member' }] },
    { identifier: 'e'.repeat(64), source: 'installation_hash', platform: 'windows', firstSeen: 1800000000000, lastSeen: 1800001000000,
      minecraft: identity.uuids, relatedAccounts: [] },
  ] };
  const report = await responseFor(command('account', 'devices', [targetOption]), result, 'backend');
  assert.ok(report.text.includes(device)); assert.ok(report.text.includes('e'.repeat(64)));
  assert.match(report.text, /First seen.*Last seen.*ExamplePlayer/s); assert.ok(report.text.includes(mcUuid));
  assert.match(report.text, /Shared computer member.*99999999999999999/);
  assert.match(report.text, /never authorize, punish or block/);
  assert.ok(!report.operations.some(operation => ['prepare', 'confirm', 'user'].includes(operation[0])));
  const overview = await responseFor(command('account', 'overview', [targetOption]), result, 'backend');
  assert.match(overview.text, /Example member.*66666666666666666.*UUID.*First seen.*Last seen/s);
  assert.ok(!overview.text.includes(device));
});

test('blacklist changes require opaque private review and invalidate only the returned affected account sessions', async () => {
  const data = command('blacklist', 'add', [targetOption, { name: 'reason', type: 3, value: 'Reviewed online abuse' }]);
  const response = await responseFor(data, { ok: true, invalidatedAccounts: ['affected-account'] }, 'backend', { review: ({ initial, operations }) => {
    assert.deepEqual(operations.map(operation => operation[0]), ['isAdmin', 'prepare']);
    assert.match(JSON.stringify(initial), /66666666666666666.*Local mod features.*other users sharing hardware are unaffected/s);
    assert.ok(initial.components[0].components.every(button => /^sky:(confirm|cancel):[A-Za-z0-9_-]{43}$/.test(button.custom_id)));
  } });
  assert.deepEqual(response.invalidated, ['affected-account']); assert.match(response.text, /Identity blacklisted for online services/);
  const cancel = await responseFor(data, { ok: true }, 'backend', { cancel: true });
  assert.match(cancel.text, /Cancelled/); assert.deepEqual(cancel.invalidated, []);
  const stale = await responseFor(data, { ok: true }, 'backend', { confirmError: 'confirmation_stale' });
  assert.match(stale.text, /changed after review/); assert.deepEqual(stale.invalidated, []);
});

test('disconnect and unlink are explicit reviewed changes, preserve unrelated features and need no hardware reset', async () => {
  for (const data of [command('disconnect'), command('account', 'unlink', [{ name: 'uuid', type: 3, value: mcUuid }])]) {
    const response = await responseFor(data, { ok: true, invalidatedAccounts: ['own-account'] });
    assert.ok(response.operations.some(operation => operation[0] === 'prepare' && operation[2] === 'user'));
    assert.deepEqual(response.invalidated, ['own-account']); assert.match(response.text, /retained|unchanged/);
    assert.ok(!response.calls.some(call => call.options.headers?.Authorization));
  }
});

test('device history is paginated, bounded to Discord limits and absent from ordinary-user autocomplete', async () => {
  const long = Array.from({ length: 8 }, (_, index) => ({ identifier: String(index).repeat(64), source: 'hardware_hash', platform: 'windows',
    firstSeen: 1800000000000, lastSeen: 1800001000000, minecraft: Array.from({ length: 20 }, () => identity.uuids[0]),
    relatedAccounts: Array.from({ length: 20 }, () => ({ discordId: target, username: 'member', displayName: 'Example member' })) }));
  const report = await responseFor(command('account', 'devices', [targetOption]), { ok: true, devices: long }, 'backend');
  const characters = report.reply.embeds.flatMap(embed => [embed.title ?? '', embed.description ?? '', embed.footer?.text ?? '',
    ...embed.fields.flatMap(field => [field.name, field.value])]).join('').length;
  assert.ok(characters <= 6000); assert.ok(report.reply.embeds[0].fields.every(field => field.value.length <= 1024));
  assert.ok(report.reply.components[0].components.some(button => button.custom_id === `sky:page:devices:${target}:2`));
  const reads = [], env = { ...config, ACCOUNT_STORE: { getByName: () => ({ communityUserCommand: async (caller, action) => {
    reads.push({ caller: caller.id, action }); return { ok: true, identity: { ...identity, discordId: actor.id } }; } }) } };
  const data = { ...command('account', 'unlink', [{ name: 'uuid', type: 3, value: 'Example', focused: true }]), type: 4 };
  assert.deepEqual(await (await handleAccountAutocomplete(data, env, 'user')).json(), { type: 8, data: { choices: [{ name: 'ExamplePlayer', value: mcUuid }] } });
  assert.deepEqual(reads, [{ caller: actor.id, action: 'status' }]);
});

test('admin autocomplete reveals no registered account to nonadmins or the User Bot', async () => {
  let admin = false, reads = 0;
  const env = { ...config, ACCOUNT_STORE: { getByName: () => ({ isAdmin: async () => admin,
    communityRead: async () => { reads++; return { ok: true, identities: [identity] }; } }) } };
  const data = { ...command('skyhawk', 'search', [{ type: 3, name: 'query', value: 'example', focused: true }]), type: 4 };
  assert.deepEqual(await (await handleAccountAutocomplete(data, env, 'backend')).json(), { type: 8, data: { choices: [] } });
  assert.deepEqual(await (await handleAccountAutocomplete(data, env, 'user')).json(), { type: 8, data: { choices: [] } });
  assert.equal(reads, 0); admin = true;
  const reply = await (await handleAccountAutocomplete(data, env, 'backend')).json();
  assert.equal(reply.data.choices[0].value, target); assert.equal(reads, 1);
});

test('blacklist errors explicitly preserve local gameplay and do not expose backend details', async () => {
  const blocked = await responseFor(command(), { ok: false, error: 'blacklisted' });
  assert.match(blocked.text, /restricted.*online services.*Local gameplay remains available/s);
  for (const error of ['private-secret', 'toString', '__proto__']) {
    const reply = await responseFor(command(), { ok: false, error });
    assert.ok(!reply.text.includes(error)); assert.match(reply.text, /could not complete this request/);
  }
});

test('administrator alerts stay private, redact credentials and retain historical account/audit controls', async () => {
  const previous = globalThis.fetch, calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return Response.json(url.endsWith('/users/@me/channels') ? { id: '77777777777777777' } : {}); };
  try {
    await deliverAccountAlert({ BACKEND_BOT_TOKEN: 'test-only' }, { id: 'test-alert', auditId: 1, recipientId: '978376257534328883', claimToken: 'test-claim', repeats: 0,
      event: { id: 1, at: Date.now(), actor: actor.id, action: 'blacklist_add', severity: 'security', metadata: { discordId: target,
        discordName: 'Example member', minecraftName: 'ExamplePlayer', minecraftUuid: mcUuid, reason: `token=private-token ${device}` } } });
    assert.equal(JSON.parse(calls[0].options.body).recipient_id, '978376257534328883');
    const message = JSON.parse(calls[1].options.body);
    assert.deepEqual(message.allowed_mentions, { parse: [] }); assert.equal(message.enforce_nonce, true);
    assert.ok(!JSON.stringify(message).includes('private-token')); assert.ok(!JSON.stringify(message).includes(device));
    assert.equal(message.components[0].components[0].label, 'View Account');
    assert.ok(message.components[0].components.every(button => /^sky:admin:(info|audit):\d{17,20}$/.test(button.custom_id)));
  } finally { globalThis.fetch = previous; }
});

const leaderboard = { ok: true, available: true, category: 'dungeons', metric: 'dungeon_profit', period: 'weekly',
  metricLabel: 'Estimated recorded dungeon profit', description: 'Newly recorded chest profits, including losses.', unit: 'coins',
  source: 'Explicitly shared client observations; estimates', coverage: 'Consenting participants only; each Minecraft identity separately',
  total: 23, page: 2, limit: 10, hasNext: true, updatedAt: 1800001000000,
  entries: [{ rank: 1, player: 'Example_Player', value: -123.5, sampleSize: 2, updatedAt: 1800001000000, source: 'client-observation', verification: 'estimated' }],
  accountId: identity.accountId, discordId: target, hardware: device, token: 'never-show-this-token' };

test('leaderboards use the signed user actor, the shared query and honest sources without disclosing private fields', async () => {
  const options = [{ name: 'category', type: 3, value: 'dungeons' }, { name: 'metric', type: 3, value: 'dungeon_profit' },
    { name: 'period', type: 3, value: 'weekly' }, { name: 'page', type: 4, value: 2 }];
  const reply = await responseFor(command('leaderboard', null, options), leaderboard);
  assert.deepEqual(reply.operations, [['leaderboard', { ...actor, roles: [] }, { category: 'dungeons', metric: 'dungeon_profit', period: 'weekly', page: 2 }]]);
  assert.match(reply.text, /UTC week/); assert.match(reply.text, /-123\.5 coins/); assert.match(reply.text, /Example\\\\_Player/);
  assert.match(reply.text, /each Minecraft identity separately/); assert.match(reply.text, /unverified/);
  for (const secret of [identity.accountId, target, device, leaderboard.token]) assert.ok(!reply.text.includes(secret));
  assert.deepEqual(reply.reply.components[0].components.map(button => button.custom_id),
    ['sky:leaderboard:dungeons:dungeon_profit:weekly:1', 'sky:leaderboard:dungeons:dungeon_profit:weekly:3']);
  const component = { ...command(), type: 3, data: { component_type: 2, custom_id: 'sky:leaderboard:dungeons:dungeon_profit:weekly:3' } };
  const paged = await responseFor(component, { ...leaderboard, page: 3 });
  assert.equal(paged.operations[0][2].page, 3); assert.equal(paged.operations[0][1].id, actor.id);
  const filtered = await responseFor(command('leaderboard', null, [...options, { name: 'search', type: 3, value: 'Example' }]), { ...leaderboard, search: 'Example' });
  assert.deepEqual(filtered.reply.components, []); assert.match(filtered.text, /same search and desired page/);
  const missing = await responseFor(command('leaderboard'), { ...leaderboard, available: false, reason: 'No reliable completed-task publisher exists.', entries: [] });
  assert.match(missing.text, /No reliable completed-task publisher exists/);
  const invalid = await responseFor(command('leaderboard'), { ok: false, error: 'invalid_leaderboard' });
  assert.match(invalid.text, /metric from the selected leaderboard category/);
});

test('Bazaar commands bind private summaries, statistics and replay links to the signed caller', async () => {
  const result={ok:true,version:1,period:'weekly',profit:200,cost:1000,durationMs:60000,completedFlips:1,realizations:2,roi:.2,profitPerHour:12000,
    coverage:'Only your eligible shared realized portions, not private local history.',verification:'Unverified client observations',
    sessionSummary:{durationMs:60000,realizedProfit:200,completedFlips:1},publications:[{token:'p'.repeat(43)}],sharing:{enabled:true,economy:true,leaderboards:true,publicName:false}};
  for(const action of ['stats','summary','replays','privacy']){
    const response=await responseFor(command('bazaar',action,action==='stats'?[{name:'period',type:3,value:'weekly'}]:[]),result);
    assert.deepEqual(response.operations,[['bazaar',{...actor,roles:[]},action,action==='stats'?'weekly':'all-time']]);
    if(action==='summary')assert.match(response.text,/explicitly shared session summary/);
    if(action==='replays')assert.match(response.text,/community\/bazaar\/replays\//);
    for(const secret of [identity.accountId,target,device])assert(!response.text.includes(secret));
  }
  const env={...config,ACCOUNT_STORE:{getByName(){throw Error('Untrusted user selection reached private storage')}}};
  for(const raw of [command('bazaar','stats',[{name:'user',type:6,value:target}]),{...command('bazaar','stats'),guild_id:'99999999999999999'}]){
    assert.equal((await handleAccountInteraction(raw,env,{waitUntil(){}},'user').json()).type,4);
  }
  const linked=await responseFor(command('leaderboard',null,[{name:'category',type:3,value:'economy'},{name:'metric',type:3,value:'bazaar_profit'},{name:'identity',type:3,value:'linked'}]),
    {...leaderboard,category:'economy',metric:'bazaar_profit',identity:'linked'});
  assert.match(linked.text,/Local Sunday week/);assert(linked.reply.components[0].components.every(control=>control.custom_id.endsWith(':linked')));
});

test('malformed leaderboard selections and wrong guild never reach storage', async () => {
  const env = { ...config, ACCOUNT_STORE: { getByName() { throw Error('Invalid interaction reached storage'); } } };
  const valid = { name: 'category', type: 3, value: 'economy' };
  for (const options of [[valid, valid], [{ name: 'user', type: 6, value: target }], [{ name: 'page', type: 3, value: '2' }],
    [{ name: 'search', type: 3, value: 'x'.repeat(101) }]]) {
    const result = handleAccountInteraction(command('leaderboard', null, options), env, { waitUntil() {} }, 'user');
    assert.equal((await result.json()).type, 4);
  }
  const wrongGuild = handleAccountInteraction({ ...command('leaderboard'), guild_id: '99999999999999999' }, env, { waitUntil() {} }, 'user');
  assert.equal((await wrongGuild.json()).type, 4);
  assert.equal((await handleAccountInteraction(command('leaderboard'), env, { waitUntil() {} }, 'backend').json()).type, 4);
});

test('private session reports distinguish ended, expired, refreshable and disconnected sessions', async () => {
  const now = Date.now(), base = { minecraftUuid: mcUuid, issuedAt: now - 60000, lastSeen: now - 1000, expiresAt: now + 60000, refreshExpiresAt: now + 86400000 };
  const result = await responseFor(command('account', 'sessions', [targetOption]), { ok: true, identity, sessions: [
    { ...base, revokedAt: now - 1 }, { ...base, refreshExpiresAt: now - 1 }, { ...base, expiresAt: now - 1 },
    { ...base, connectedAt: null }, { ...base, connectedAt: now - 60000 },
  ] }, 'backend');
  assert.deepEqual(result.reply.embeds[0].fields.map(field => field.value.split('State: ')[1]),
    ['Ended', 'Expired', 'Access token expired; refresh available', 'No active relay connection observed', 'Connection observed; see last activity']);
});

test('a bridged video preview thumbnail in the text is not a second media item', () => {
  const base = 'https://static2.klipy.com/ii/a/28/c9/', poster = `${base}poster.webp`;
  const video = { id: 'media-0', type: 'video', url: `${base}clip.mp4`, thumbnailUrl: poster };
  assert.deepEqual(ircMedia(`https://klipy.com/gifs/example-1\n${poster}`, [video]), [video]);
  assert.equal(ircMedia(poster).length, 1, 'a plain image link still becomes media');
});
