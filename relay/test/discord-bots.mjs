import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

async function load(entry) {
  const result = await build({ entryPoints: [new URL(`../src/${entry}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')],
    bundle: true, format: 'esm', platform: 'browser', write: false });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
}
const { accountCommand, handleAccountInteraction } = await load('discord-accounts.ts');
const { createLoaderDownload, validLoaderDownload, handleLoaderDownload } = await load('discord-download.ts');
const { verifiedInteraction } = await load('discord.ts');
const actor = { id: '11111111111111111', guildId: '22222222222222222', roles: [], interactionId: '33333333333333333' };
const config = { DISCORD_GUILD_ID: actor.guildId, DEVELOPER_ROLE_ID: '44444444444444444', DEVELOPER_USER_IDS: actor.id };
const command = (name = 'account', sub = 'status', options = []) => ({ type: 2, id: actor.interactionId, guild_id: actor.guildId,
  application_id: '55555555555555555', token: 'interaction-token-never-log-this', member: { user: { id: actor.id }, roles: actor.roles },
  data: { name, options: sub ? [{ type: 1, name: sub, options }] : options } });

test('each bot accepts only its own Discord application signature and fresh signed body', async () => {
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

test('user commands cannot choose a different owner or access developer functions', () => {
  assert.equal(accountCommand(command(), 'user').target, actor.id);
  assert.equal(accountCommand(command('account', 'status', [{ type: 6, name: 'user', value: '66666666666666666' }]), 'user'), null);
  assert.equal(accountCommand(command('license', 'revoke'), 'user'), null);
  assert.equal(accountCommand(command('account', 'reset', [{ type: 5, name: 'confirm', value: false }]), 'user'), null);
  assert.equal(accountCommand(command('account', 'reset', [{ type: 5, name: 'confirm', value: true }]), 'user').target, actor.id);
  assert.equal(accountCommand(command('account', 'unlink', [{ type: 3, name: 'uuid', value: 'a'.repeat(32) }, { type: 5, name: 'confirm', value: true }]), 'user').action, 'unlink');
  assert.equal(accountCommand({ ...command(), guild_id: undefined }, 'user'), null);
  assert.equal(accountCommand(command('hwid', 'reset', [{ type: 5, name: 'confirm', value: true }]), 'user').target, actor.id);
  assert.equal(accountCommand(command('hwid', 'reset', [{ type: 6, name: 'user', value: '66666666666666666' }, { type: 5, name: 'confirm', value: true }]), 'user'), null);
  assert.equal(accountCommand(command('hwid', 'reset', [{ type: 5, name: 'confirm', value: true }, { type: 3, name: 'reason', value: 'administrator_reset' }]), 'user'), null);
});

test('administrator hardware commands propagate an optional security reason', () => {
  const user = { type: 6, name: 'user', value: '66666666666666666' };
  const reason = { type: 3, name: 'reason', value: 'Confirmed credential compromise' };
  for (const sub of ['reset', 'revoke', 'bind']) {
    const device = sub === 'bind' ? [{ type: 3, name: 'device-hash', value: 'd'.repeat(64) }] : [];
    const parsed = accountCommand(command('hwid', sub, [user, ...device, reason]), 'backend');
    assert.equal(parsed.action, `hwid_${sub}`); assert.equal(parsed.target, user.value);
    assert.deepEqual(parsed.options, { ...sub === 'bind' ? { deviceHash: device[0].value } : {}, reason: reason.value });
    assert.ok(accountCommand(command('hwid', sub, [user, ...device]), 'backend'), 'The security reason remains optional');
  }
});

test('authorise preserves caller and target identity while the removed authenticate alias is rejected', async () => {
  const target = '66666666666666666';
  const data = command('authorise', null, [{ type: 6, name: 'user', value: target }, { type: 5, name: 'reissue', value: true }]);
  const parsed = accountCommand(data, 'backend');
  assert.equal(parsed.action, 'authorise'); assert.equal(parsed.actor.id, actor.id); assert.equal(parsed.target, target);
  assert.deepEqual(parsed.options, { reissue: true });
  assert.equal(accountCommand(data, 'user'), null);
  const removed = command('authenticate', null, [{ type: 6, name: 'user', value: target }]);
  for (const kind of ['backend', 'user']) assert.equal(accountCommand(removed, kind), null);
  const response = handleAccountInteraction(removed, config, { waitUntil() { throw Error('Removed commands must not execute'); } }, 'backend');
  assert.deepEqual(await response.json(), { type: 4, data: { content: 'Invalid Sky-Myce command. Confirm the requested action and try again.', flags: 64, allowed_mentions: { parse: [] } } });
});

test('persisted admin check happens after private defer and before any target lookup or write; roles and allowlists cannot bypass it', async () => {
  const originalFetch=globalThis.fetch;
  try {
    for (const member of [{user:{id:actor.id},roles:[]}, {user:{id:actor.id},roles:[config.DEVELOPER_ROLE_ID]},
      {user:{id:actor.id},roles:[],permissions:'8'}]) {
      const pending=[],calls=[]; let writes=0,checks=0;
      globalThis.fetch=async (url,options)=>{calls.push({url,options});assert.equal(options.method,'PATCH');return Response.json({});};
      const env={...config,DISCORD_BACKEND_BOT_TOKEN:'test-only',ACCOUNT_STORE:{getByName:()=>({
        isAdmin:async caller=>{checks++;assert.equal(caller.id,actor.id);return false;},
        developerCommand:async ()=>{writes++;throw Error('Nonadmin must never execute');}})}};
      const response=handleAccountInteraction({...command('authorise',null,[{name:'user',type:6,value:'66666666666666666'}]),member},env,
        {waitUntil:p=>pending.push(p)},'backend');
      assert.deepEqual(await response.json(),{type:5,data:{flags:64}});await Promise.all(pending);
      assert.equal(checks,1);assert.equal(writes,0);assert.equal(calls.length,1);
      assert.match(JSON.parse(calls[0].options.body).content,/Only Sky-Myce admin accounts/);
      const wrongGuild=handleAccountInteraction({...command('audit',null,[{name:'user',type:6,value:actor.id}]),guild_id:'99999999999999999'},env,
        {waitUntil(){throw Error('Wrong guild must not access the backend');}},'backend');
      assert.match((await wrongGuild.json()).data.content,/permission/);
    }
  } finally {globalThis.fetch=originalFetch;}
});

test('admin add binds authorization to caller, validates a human target and stays private', async () => {
  const target='66666666666666666', data=command('admin','add',[{type:6,name:'user',value:target}]);
  assert.equal(accountCommand(data,'backend').action,'admin_add');
  assert.equal(accountCommand(data,'backend').actor.id,actor.id);
  assert.equal(accountCommand(data,'backend').target,target);
  assert.equal(accountCommand(data,'user'),null);
  assert.equal(accountCommand(command('admin','add',[{type:3,name:'user',value:target}]),'backend'),null);
  const originalFetch=globalThis.fetch;
  try {
    for(const bot of [true,false]) {
      const pending=[],calls=[];let writes=0;
      globalThis.fetch=async(url,options)=>{calls.push({url,options});return Response.json(url.endsWith('/users/'+target)?{id:target,bot}:{});};
      const env={...config,ROOMS:'friends',DISCORD_BACKEND_BOT_TOKEN:'test-only',DISCORD_AUDIT_CHANNEL_ID:'99999999999999999',
        ACCOUNT_STORE:{getByName:()=>({isAdmin:async caller=>caller.id===actor.id,developerCommand:async(caller,action,id)=>{
          writes++;assert.equal(caller.id,actor.id);assert.equal(action,'admin_add');assert.equal(id,target);return{ok:true,adminAdded:true};}})}};
      assert.deepEqual(await handleAccountInteraction(data,env,{waitUntil:p=>pending.push(p)},'backend').json(),{type:5,data:{flags:64}});
      await Promise.all(pending);assert.equal(writes,bot?0:1);
      const edit=JSON.parse(calls.find(call=>call.options.method==='PATCH').options.body);
      assert.match(edit.content,bot?/human Discord account/:/now a Sky-Myce admin/);assert.deepEqual(edit.allowed_mentions,{parse:[]});
      assert.ok(calls.every(call=>!call.url.includes('/users/@me/channels')));
    }
  } finally {globalThis.fetch=originalFetch;}
});

test('missing backend capability fails closed without acknowledging fake success', async () => {
  const response = handleAccountInteraction(command(), config, { waitUntil() { throw new Error('No backend work allowed'); } }, 'user');
  assert.match((await response.json()).data.content, /currently unavailable/);
});

test('status remains pending until activation; invalid authorise targets cannot issue keys', async () => {
  const originalFetch = globalThis.fetch, pending = [], edits = [];
  let writes = 0;
  globalThis.fetch = async (url, options) => {
    if (options.method === 'PATCH') edits.push(JSON.parse(options.body).content);
    if (url.endsWith('/users/66666666666666666')) return Response.json({ id: '66666666666666666', bot: true });
    return Response.json({});
  };
  const env = { ...config, ROOMS: 'testing', DISCORD_BACKEND_BOT_TOKEN: 'test-only',
    PRIVATE_ARTIFACTS: { head: async () => ({ size: 1 }) },
    ACCOUNT_STORE: { getByName: () => ({
      isAdmin: async () => true,
      userCommand: async () => ({ ok: true, account: { status: 'active', activated: false, licenseStatus: 'active',
        licenseExpiresAt: null, deviceRegistered: false, hwidResetAt: null, uuids: [] } }),
      developerCommand: async () => { writes++; throw new Error('Must not issue activation'); },
    }) } };
  try {
    const ctx = { waitUntil: promise => pending.push(promise) };
    handleAccountInteraction(command(), env, ctx, 'user');
    handleAccountInteraction(command('authorise', null, [{ type: 6, name: 'user', value: '66666666666666666' }]), env, ctx, 'backend');
    await Promise.all(pending);
    assert.ok(edits.some(value => /pending activation/i.test(value)));
    assert.ok(edits.some(value => value.includes('human Discord account')));
    assert.equal(writes, 0);
  } finally { globalThis.fetch = originalFetch; }
});

test('blocked activation DM reports failure privately and never posts the activation key', async () => {
  const calls = [], pending = [], originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/users/66666666666666666')) return Response.json({ id: '66666666666666666' });
    if (url.endsWith('/users/@me/channels')) return Response.json({}, { status: 403 });
    return Response.json({});
  };
  try {
    const env = { ...config, ROOMS: 'testing', DISCORD_BACKEND_BOT_TOKEN: 'test-only',
      ACCOUNT_STORE: { getByName: () => ({ isAdmin: async () => true, developerCommand: async () => ({ ok: true, activationKey: 'never-public-test-key' }) }) },
      PRIVATE_ARTIFACTS: { head: async () => ({ size: 1 }) } };
    const response = handleAccountInteraction(command('authorise', null, [{ type: 6, name: 'user', value: '66666666666666666' }]), env,
      { waitUntil: promise => pending.push(promise) }, 'backend');
    assert.equal((await response.json()).type, 5); await Promise.all(pending);
    assert.ok(calls.some(call => call.options.method === 'PATCH' && call.options.body.includes('private delivery failed')));
    assert.ok(calls.every(call => !String(call.options.body).includes('never-public-test-key')));
  } finally { globalThis.fetch = originalFetch; }
});

test('private loader ticket expires, rejects tampering, and keeps credentials outside the request URL', async () => {
  const env = { SKYMYCE_PUBLIC_URL: 'https://worker.example', DISCORD_LOADER_DOWNLOAD_SECRET: 's'.repeat(32) };
  const link = new URL(await createLoaderDownload(env, actor.id, 1_800_000_000_000));
  assert.equal(link.search, '');
  const token = link.hash.slice(1);
  assert.ok(await validLoaderDownload(env, token, 1_800_000_000_001));
  assert.equal(await validLoaderDownload(env, token, 1_800_000_900_000), false);
  assert.equal(await validLoaderDownload(env, token + 'x', 1_800_000_000_001), false);
  assert.equal(await validLoaderDownload({ ...env, DISCORD_LOADER_DOWNLOAD_SECRET: 'x'.repeat(32) }, token, 1_800_000_000_001), false);
  const get = await handleLoaderDownload(new Request('https://worker.example/discord/loader'), env);
  assert.equal(get.headers.get('Cache-Control'), 'no-store');
  assert.match(await get.text(), /history.replaceState/);
  const post = await handleLoaderDownload(new Request('https://worker.example/discord/loader', { method: 'POST' }), env);
  assert.equal(post.status, 403);
});

test('authorise defers promptly, privately delivers code and loader instructions, and omits the code from replies and audit', async () => {
  const calls = [], pending = [], key = 'one-time-key-only-in-authorised-dm';
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(options.redirect, 'manual'); // Workers supports manual/follow; redirects must never carry credentials elsewhere.
    calls.push({ url, options });
    if (url.endsWith('/users/66666666666666666')) return Response.json({ id: '66666666666666666', bot: false });
    if (url.endsWith('/users/@me/channels')) return Response.json({ id: '77777777777777777' });
    return Response.json({ id: '88888888888888888' });
  };
  try {
    const data = command('authorise', null, [{ type: 3, name: 'user', value: '66666666666666666' }]);
    const env = { ...config, ROOMS: 'testing', DISCORD_BACKEND_BOT_TOKEN: 'backend-token', DISCORD_AUDIT_CHANNEL_ID: '99999999999999999',
      ACCOUNT_STORE: { getByName: () => ({ isAdmin: async () => true, developerCommand: async (signedActor, action, target) => {
        assert.equal(signedActor.id, actor.id); assert.equal(action, 'authorise'); assert.equal(target, '66666666666666666');
        return { ok: true, activationKey: key };
      } }) },
      PRIVATE_ARTIFACTS: { head: async () => ({ size: 3 }), get: async () => ({ size: 3, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer }) } };
    const response = handleAccountInteraction(data, env, { waitUntil: promise => pending.push(promise) }, 'backend');
    assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } });
    await Promise.all(pending);
    const dm = calls.find(call => call.options.body instanceof FormData);
    assert.ok(dm); assert.match(dm.options.body.get('payload_json'), new RegExp(key));
    assert.match(dm.options.body.get('payload_json'), /pending activation/);
    assert.match(dm.options.body.get('payload_json'), /Minecraft 26.1.2.*Java 25/);
    assert.match(dm.options.body.get('payload_json'), /Enter this code and press Confirm/);
    assert.match(dm.options.body.get('payload_json'), /starts Minecraft automatically/);
    assert.match(dm.options.body.get('payload_json'), /Future updates are checked before startup/);
    assert.match(dm.options.body.get('payload_json'), /Loader button.*main menu.*available/);
    assert.doesNotMatch(dm.options.body.get('payload_json'), /account approve|request Discord approval/);
    assert.match(dm.url, /77777777777777777/);
    assert.ok(calls.filter(call => !(call.options.body instanceof FormData)).every(call => !String(call.options.body).includes(key)));
    assert.ok(calls.some(call => call.options.method === 'PATCH' && call.options.body.includes('delivered by DM')));
  } finally { globalThis.fetch = originalFetch; }
});

test('explicit key-only delivery needs no R2 and clearly leaves activation pending', async () => {
  const originalFetch = globalThis.fetch, calls = [], pending = [], key = 'key-only-private-test-value';
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/users/66666666666666666')) return Response.json({ id: '66666666666666666', bot: false });
    if (url.endsWith('/users/@me/channels')) return Response.json({ id: '77777777777777777' });
    return Response.json({});
  };
  try {
    const env = { ...config, ROOMS: 'testing', DISCORD_BACKEND_BOT_TOKEN: 'test-only', DISCORD_LOADER_DELIVERY_ENABLED: 'false',
      DISCORD_LICENSE_CHANNEL_ID: '88888888888888888',
      ACCOUNT_STORE: { getByName: () => ({ isAdmin: async () => true, developerCommand: async () => ({ ok: true, activationKey: key }) }) } };
    const response = handleAccountInteraction(command('authorise', null, [{ type: 6, name: 'user', value: '66666666666666666' }]), env,
      { waitUntil: promise => pending.push(promise) }, 'backend');
    assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } });
    await Promise.all(pending);
    const dm = calls.find(call => call.url.endsWith('/channels/77777777777777777/messages'));
    const payload = JSON.parse(dm.options.body);
    assert.ok(payload.content.includes(key));
    assert.match(payload.content, /pending activation/);
    assert.equal(payload.enforce_nonce, true);
    assert.deepEqual(payload.allowed_mentions, { parse: [] });
    assert.ok(calls.filter(call => call !== dm).every(call => !String(call.options.body).includes(key)));
    const edit = JSON.parse(calls.find(call => call.options.method === 'PATCH').options.body);
    assert.match(edit.content, /pending activation/);
    assert.match(edit.content, /Loader delivery is currently unavailable/);
    assert.ok(!edit.content.includes('Loader were delivered'));
  } finally { globalThis.fetch = originalFetch; }
});

async function responseFor(data, result, kind = 'backend', dmBlocked = false) {
  const originalFetch = globalThis.fetch, calls = [], pending = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/users/66666666666666666')) return Response.json({ id: '66666666666666666', bot: false });
    if (url.endsWith('/users/@me/channels')) return Response.json({ id: '77777777777777777' });
    if (dmBlocked && url.endsWith('/channels/77777777777777777/messages')) return Response.json({}, { status: 403 });
    return Response.json({});
  };
  try {
    const store = { isAdmin: async () => true, developerCommand: async () => result, userCommand: async () => result };
    const env = { ...config, ROOMS: 'testing', DISCORD_BACKEND_BOT_TOKEN: 'test-only', USER_BOT_TOKEN: 'test-only-user', DISCORD_SECURITY_CHANNEL_ID: '88888888888888888',
      ACCOUNT_STORE: { getByName: () => store }, RELAY_ROOMS: { getByName: () => ({ invalidateAccount: async () => {} }) },
      PRIVATE_ARTIFACTS: { head: async () => ({ size: 3 }), get: async () => ({ size: 3, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer }) } };
    const response = handleAccountInteraction(data, env, { waitUntil: promise => pending.push(promise) }, kind);
    assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } });
    await Promise.all(pending);
    const reply = JSON.parse(calls.find(call => call.options.method === 'PATCH').options.body);
    assert.deepEqual(reply.allowed_mentions, { parse: [] });
    assert.ok(reply.content.length <= 1900);
    return { content: reply.content, calls };
  } finally { globalThis.fetch = originalFetch; }
}
const accountSummary = { accountId: 'account-reference', discordId: '66666666666666666', status: 'active', isAdmin: false,
  activated: true, licenseStatus: 'active', licenseExpiresAt: null, deviceRegistered: true, hwidResetAt: null, uuids: [] };
const targetOption = { type: 6, name: 'user', value: '66666666666666666' };

test('admin reset and self-service transfer preparation DM a key only to the target and report blocked delivery privately', async () => {
  const key = 'reset-key-private-test', expiresAt = Date.now() + 86400000;
  for (const kind of ['backend', 'user']) {
    const data = command('hwid', 'reset', kind === 'backend' ? [targetOption] : [{ type: 5, name: 'confirm', value: true }]);
    const result = { ok: true, account: accountSummary, activationKey: key, expiresAt };
    for (const blocked of [false, true]) {
      const { content, calls } = await responseFor(data, result, kind, blocked);
      const createDm = calls.find(call => call.url.endsWith('/users/@me/channels'));
      assert.equal(JSON.parse(createDm.options.body).recipient_id, kind === 'backend' ? targetOption.value : actor.id);
      const dms = calls.filter(call => call.url.endsWith('/channels/77777777777777777/messages'));
      assert.equal(dms.length, 1);
      const dm = JSON.parse(dms[0].options.body);
      assert.ok(dm.content.includes(key)); assert.match(dm.content, /Earlier unused codes are invalid/);
      assert.equal(dm.nonce, actor.interactionId); assert.equal(dm.enforce_nonce, true);
      assert.deepEqual(dm.allowed_mentions, { parse: [] });
      assert.equal(dms[0].options.headers.Authorization, kind === 'backend' ? 'Bot test-only' : 'Bot test-only-user');
      assert.ok(calls.filter(call => !dms.includes(call)).every(call => !String(call.options.body).includes(key)));
      assert.ok(!content.includes(key));
      if (blocked) {
        assert.match(content, kind === 'backend' ? /reset completed.*DM delivery failed.*reissue:true/s : /code prepared.*DM delivery failed.*registration and transfer allowance are unchanged.*repeat.*hwid reset/s);
        if (kind === 'backend') assert.match(content, /Do not reset again/);
      } else {
        assert.match(content, /fresh activation code was sent/i);
        if (kind === 'user') {
          assert.match(content, /Hardware transfer prepared.*current device remains authorized.*only an actual device change uses the seven-day allowance/s);
          assert.match(dm.content, /current device stays authorized.*Preparing this code does not use your transfer allowance/s);
          assert.doesNotMatch(content, /Device reset\./);
        }
      }
    }
    const denied = await responseFor(data, { ok: false, error: kind === 'user' ? 'device_reset_cooldown' : 'admin_required' }, kind);
    assert.ok(!denied.calls.some(call => call.url.endsWith('/users/@me/channels')));
    if (kind === 'user') assert.match(denied.content, /actual hardware transfer.*every seven days.*registered device continues working normally/s);
  }
});

test('account summaries distinguish activation and expiry while a registered device needs no reset countdown', async () => {
  const now = Date.now(), expiresAt = now + 86_400_000, nextReset = now + 7 * 86_400_000;
  const pending = await responseFor(command(), { ok: true, account: { ...accountSummary, activated: false } }, 'user');
  assert.match(pending.content, /Status: Pending activation\n/);
  assert.match(pending.content, /License: Active/);
  assert.match(pending.content, /DM code.*Confirm and start.*Minecraft opens automatically/);
  const active = await responseFor(command(), { ok: true, account: { ...accountSummary, licenseExpiresAt: expiresAt, hwidResetAt: now, hwidBoundAt: now, nextHwidChangeAllowedAt: nextReset } }, 'user');
  assert.match(active.content, /Status: Activated/);
  assert.ok(active.content.includes(`<t:${Math.floor(expiresAt / 1000)}:R> (<t:${Math.floor(expiresAt / 1000)}:f>)`));
  assert.match(active.content, /Device: Verified/);
  assert.match(active.content, /registration is persistent.*restarts, reconnects and updates need no HWID reset/s);
  assert.ok(!active.content.includes(`<t:${Math.floor(nextReset / 1000)}`));
  assert.doesNotMatch(active.content, /account-reference|66666666666666666|raw HWID|activationKey/);
  const expired = await responseFor(command(), { ok: true, account: { ...accountSummary, licenseExpiresAt: now - 1000 } }, 'user');
  assert.match(expired.content, /License: Expired/);
  assert.match(expired.content, /admin to review/);
  const suspended = await responseFor(command(), { ok: true, account: { ...accountSummary, status: 'suspended' } }, 'user');
  assert.match(suspended.content, /Status: Suspended/);
  assert.doesNotMatch(suspended.content, /Available now/);
});

test('administrative outcomes describe the action and preserve the target and cooldown distinction', async () => {
  for (const [name, sub, options, expected] of [
    ['license', 'extend', [{ type: 4, name: 'days', value: 30 }], /License extended by 30 days/],
    ['account', 'suspend', [], /Account suspended/],
    ['hwid', 'reset', [], /Device reset.*previous binding and sessions were invalidated.*self-service cooldown is unchanged/s],
    ['hwid', 'revoke', [], /Device access revoked.*\/hwid reset/s],
    ['session', 'kick', [], /Sessions invalidated/],
    ['warning', 'remove', [{ type: 3, name: 'warning-id', value: 'warning-reference' }], /Any matching warning.*inactive/],
  ]) {
    const reply = await responseFor(command(name, sub, [targetOption, ...options]), { ok: true, account: accountSummary,
      ...(name === 'hwid' && sub === 'reset' ? { activationKey: 'test-reset-key', expiresAt: Date.now() + 86400000 } : {}) });
    assert.match(reply.content, expected);
    assert.ok(reply.content.includes('<@66666666666666666>'));
  }
});

test('already authorized never claims completed activation, and errors never echo backend details', async () => {
  const data = command('authorise', null, [targetOption]);
  const authorized = await responseFor(data, { ok: false, error: 'already_authorized' });
  assert.match(authorized.content, /already has a Sky-Myce license/);
  assert.match(authorized.content, /No new activation code was issued/);
  assert.match(authorized.content, /account lookup.*whether activation is complete/s);
  assert.match(authorized.content, /reissue:true.*invalidates earlier unused codes/s);
  assert.doesNotMatch(authorized.content, /already activated|fully active/);
  for (const error of ['private-backend-secret', 'toString', '__proto__']) {
    const reply = await responseFor(command('account', 'lookup', [targetOption]), { ok: false, error });
    assert.match(reply.content, /could not complete this request/);
    assert.ok(!reply.content.includes(error));
  }
  const expired = await responseFor(command('account', 'lookup', [targetOption]), { ok: false, error: 'license_expired' });
  assert.match(expired.content, /license has expired.*review or extend/s);
});

test('replacement activation preserves activated wording and sends the expiring code only in its DM', async () => {
  const key = 'replacement-code-private-test', expiresAt = Date.now() + 86_400_000;
  const { content, calls } = await responseFor(command('authorise', null, [targetOption, { type: 5, name: 'reissue', value: true }]),
    { ok: true, activationKey: key, expiresAt, account: accountSummary });
  const dm = calls.find(call => call.options.body instanceof FormData);
  const payload = JSON.parse(dm.options.body.get('payload_json'));
  assert.ok(payload.content.includes(key));
  assert.match(payload.content, /account has already been activated/);
  assert.match(payload.content, /replacement code/);
  assert.match(content, /already been activated.*does not change its current access/s);
  assert.doesNotMatch(content + payload.content, /pending activation/);
  assert.ok(content.includes(`<t:${Math.floor(expiresAt / 1000)}:R>`));
  assert.deepEqual(payload.allowed_mentions, { parse: [] });
  assert.ok(calls.filter(call => call !== dm).every(call => !String(call.options.body).includes(key)));
});

test('private audit responses show bounded readable events without raw metadata', async () => {
  const audit = Array.from({ length: 20 }, (_, index) => ({ id: 100 - index, at: Date.now() - index * 1000,
    actor: actor.id, action: 'license_revoke', override: index === 0 ? 1 : 0, metadata: '{"private":"do-not-render-metadata"}' }));
  const { content } = await responseFor(command('audit', null, [targetOption]), { ok: true, audit });
  assert.match(content, /Latest 8 events/);
  assert.match(content, /license revoke.*\n.*Ref 100.*Admin override/);
  assert.match(content, /<t:\d+:R>/);
  assert.doesNotMatch(content, /Ref 92|do-not-render-metadata|"actor"/);
  const empty = await responseFor(command('audit', null, [targetOption]), { ok: true, audit: [] });
  assert.match(empty.content, /No account events/);
});
