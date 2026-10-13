import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';

const { publicKey, privateKey } = generateKeyPairSync('ed25519');
const guild = '11111111111111111', admin = '22222222222222222', member = '33333333333333333', app = '44444444444444444';
const source = `import {AccountStore} from './src/accounts.ts';
 import {handleRngTestRequest,handleRngTestInteraction,rngTestOptions,runRngTest} from './src/rng-tester.ts';
 import {verifiedInteraction} from './src/discord.ts';
 import {DigestRng} from './src/digest.ts';import {DurableObject} from 'cloudflare:workers';
 export {AccountStore};export class DigestFixture extends DurableObject{constructor(ctx,env){super(ctx,env);this.rng=new DigestRng(ctx.storage.sql)}publish(input,now){return this.rng.publish('a'.repeat(32),'TestPlayer',input,now)}}
 export default{async fetch(request,env,ctx){const url=new URL(request.url);
  if(url.pathname==='/pure'){const raw=await request.json();const options=rngTestOptions(raw);return Response.json(options?await runRngTest(options):{ok:false,error:'invalid_rng_test'})}
  if(url.pathname.startsWith('/discord/')){const data=await verifiedInteraction(request,{DISCORD_APPLICATION_ID:env.DISCORD_BACKEND_APPLICATION_ID,DISCORD_PUBLIC_KEY:env.DISCORD_BACKEND_PUBLIC_KEY});
   if(!data)return new Response('Denied',{status:401});const pending=[];const response=handleRngTestInteraction(data,env,{waitUntil:p=>pending.push(p)},url.pathname==='/discord/backend/interactions');await Promise.all(pending);return response??new Response('Unsupported',{status:400})}
  return await handleRngTestRequest(request,env)??new Response('Missing',{status:404})}};`;
const bundle = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: 'rng-tester-fixture.ts' }, bundle: true,
  format: 'esm', platform: 'browser', target: 'es2022', external: ['cloudflare:workers'], write: false });
const completions = [], options = convertV4MiniflareOptions({ name: 'rng-tester-check', modules: true, script: bundle.outputFiles[0].text,
  compatibilityDate: '2026-09-27', bindings: { DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: admin,
    DISCORD_BACKEND_APPLICATION_ID: app, DISCORD_BACKEND_PUBLIC_KEY: publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('hex') },
  durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true }, DIGEST: { className: 'DigestFixture', useSQLite: true } },
  outboundService: async request => {
    const url = new URL(request.url);
    assert.equal(url.origin, 'https://discord.com'); assert.match(url.pathname, new RegExp(`^/api/v10/webhooks/${app}/[A-Za-z0-9._-]+/messages/@original$`));
    assert.equal(request.method, 'PATCH', 'Only the private test interaction reply may use the mock transport');
    completions.push(await request.json()); return new Response('{}', { headers: { 'Content-Type': 'application/json' } });
  } });
options.unsafeInspectDurableObjects = true;
const mf = new Miniflare(options);
const hash = value => createHash('sha256').update(value).digest('hex');
try {
  const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
  await store.communityGetSharing('invalid');
  const sql = await mf.unsafeGetDurableObjectStorage('rng-tester-check', 'AccountStore', { name: 'global' });
  const now = Date.now(), a = 'a'.repeat(32), b = 'b'.repeat(32), token = randomBytes(32).toString('base64url'), otherToken = randomBytes(32).toString('base64url');
  const owner = (await store.getAccountByDiscordId(admin)).accountId, other = 'synthetic-other';
  await sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen) VALUES(?,?,?,?)', other, member, now, now);
  for (const [uuid, account, access, suffix] of [[a,owner,token,'admin'],[b,other,otherToken,'member']]) {
    await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', uuid, account, 'TestPlayer', now, now);
    await sql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version) VALUES(?,?,?,0,?,?,?,?,?,?,NULL,?)',
      'test-session-'+suffix, account, uuid, hash('refresh-'+suffix), now+600000, hash(access), now+600000, now, now, 'test');
  }
  const body = { expectedAccountId: owner, expectedUuid: a, mode: 'dry-run', fixture: 'handle' };
  const http = async (raw=body, access=token, extra={}) => {
    const response=await mf.dispatchFetch('https://fixture.example/community/admin/rng-test', {method:'POST',headers:{Authorization:'Bearer '+access,'Content-Type':'application/json'},body:JSON.stringify(raw),...extra});
    return {status:response.status,body:await response.json(),cache:response.headers.get('Cache-Control')};
  };
  const counts = async () => { const result={};for(const table of ['community_contributions','community_rng_sources','community_rng_delivery','minecraft_accounts','sky_accounts','community_sessions']) result[table]=(await sql.exec('SELECT COUNT(*) n FROM '+table))[0].n;
    result.digestTables=(await sql.exec("SELECT name FROM sqlite_master WHERE name IN ('digest_rng','digest_rng_limits')")).length;return result; };
  const initial = await counts();
  let tested=await http(); assert.equal(tested.status,200);assert.equal(tested.cache,'no-store');assert.deepEqual(tested.body.cases.map(c=>c.status),['accepted']);assert.equal(tested.body.authorized,true);
  assert.deepEqual(tested.body.writes,{personal:false,acquisitions:false,shared:false,leaderboards:false,productionDiscord:false});
  assert.equal(tested.body.cases[0].event.player,'Anonymous');assert.equal(tested.body.cases[0].event.floor,'F7');
  tested=await http({...body,fixture:'warden-heart'});assert.equal(tested.body.cases[0].event.item,'WARDEN_HEART');assert.ok(!Object.hasOwn(tested.body.cases[0].event,'floor'));
  tested=await http({...body,fixture:'duplicate',mode:'mock-delivery'});assert.deepEqual(tested.body.cases.map(c=>c.status),['accepted','duplicate']);
  assert.equal(tested.body.mock.posts,1);assert.equal(tested.body.mock.acknowledgements,2);assert.equal(tested.body.mock.retrySuppressed,true);assert.match(tested.body.mock.presentation.embeds[0].title,/^TEST ·/);
  assert.deepEqual(tested.body.mock.presentation.allowedMentions.parse,[]);
  tested=await http({...body,fixture:'malformed',mode:'mock-delivery'});assert.deepEqual(tested.body.cases.map(c=>c.status),['invalid','invalid','invalid']);assert.ok(!tested.body.mock);
  assert.equal((await http({...body,expectedAccountId:other,expectedUuid:b},otherToken)).body.error,'admin_required');
  assert.equal((await http({...body,expectedUuid:b})).body.error,'session_identity_changed');
  assert.equal((await http({...body,production:true})).body.error,'invalid_rng_test');assert.equal((await http({...body,channelId:'55555555555555555'})).body.error,'invalid_rng_test');
  assert.equal((await http({...body,mode:'production'})).body.error,'invalid_rng_test');assert.equal((await http({...body,input:{}})).body.error,'invalid_rng_test');
  const invalid=await mf.dispatchFetch('https://fixture.example/community/admin/rng-test',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:'{bad'});assert.equal(invalid.status,400);
  assert.equal((await mf.dispatchFetch('https://fixture.example/community/admin/rng-test',{headers:{Authorization:'Bearer '+token}})).status,405);
  assert.equal((await mf.dispatchFetch('https://fixture.example/community/admin/rng-test',{method:'POST',body:'{}',headers:{'Content-Type':'application/json'}})).status,401);
  const input={type:'rng_publish',showName:false,event:{id:'c'.repeat(32),item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now()}};
  tested=await http({expectedAccountId:owner,expectedUuid:a,mode:'dry-run',input});assert.equal(tested.body.cases[0].status,'accepted');
  tested=await http({expectedAccountId:owner,expectedUuid:a,mode:'dry-run',input:{...input,event:{...input.event,displayName:'evil\u202ename'}}});assert.equal(tested.body.cases[0].status,'invalid');
  tested=await http({expectedAccountId:owner,expectedUuid:a,mode:'dry-run',input:{...input,type:'other'}});assert.equal(tested.body.cases[0].status,'invalid');
  await sql.exec('UPDATE account_limits SET count=12 WHERE id=?','rng-tester:'+owner);assert.equal((await http()).status,429);await sql.exec('UPDATE account_limits SET count=0 WHERE id=?','rng-tester:'+owner);
  await sql.exec('UPDATE sky_accounts SET is_admin=0 WHERE account_id=?',owner);assert.equal((await http()).body.error,'admin_required');await sql.exec('UPDATE sky_accounts SET is_admin=1 WHERE account_id=?',owner);
  await sql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,NULL,1)','minecraft',a,'synthetic test','synthetic admin',now);assert.equal((await http()).body.error,'identity_blacklisted');await sql.exec('DELETE FROM community_blacklist');
  await sql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,NULL,1)','discord',admin,'synthetic test','synthetic admin',now);assert.equal((await http()).body.error,'identity_blacklisted');await sql.exec('DELETE FROM community_blacklist');
  await sql.exec('UPDATE minecraft_accounts SET account_id=? WHERE uuid=?',other,a);assert.equal((await http()).body.error,'session_expired');await sql.exec('UPDATE minecraft_accounts SET account_id=? WHERE uuid=?',owner,a);
  await sql.exec('UPDATE community_sessions SET revoked_at=? WHERE access_hash=?',now,hash(token));assert.equal((await http()).body.error,'session_expired');await sql.exec('UPDATE community_sessions SET revoked_at=NULL WHERE access_hash=?',hash(token));
  await sql.exec('UPDATE community_sessions SET access_expires_at=? WHERE access_hash=?',now-1,hash(token));assert.equal((await http()).body.error,'session_expired');await sql.exec('UPDATE community_sessions SET access_expires_at=? WHERE access_hash=?',now+600000,hash(token));
  const actor={id:admin,guildId:guild,roles:[],interactionId:'60000000000000001'};
  assert.equal((await store.discordRngTest({...actor,id:member,roles:['99999999999999999']},{mode:'dry-run',fixture:'handle'})).error,'admin_required');
  assert.equal((await store.discordRngTest({...actor,guildId:'99999999999999999'},{mode:'dry-run',fixture:'handle'})).error,'admin_required');
  assert.equal((await store.discordRngTest({...actor,interactionId:''},{mode:'dry-run',fixture:'handle'})).error,'discord_authorization_required');
  assert.equal((await store.discordRngTest(actor,{mode:'dry-run',fixture:'handle'})).ok,true);assert.equal((await store.discordRngTest(actor,{mode:'dry-run',fixture:'handle'})).error,'interaction_replayed');
  let interactionId=70000000000000000n;
  const native = async (extra={},path='/discord/backend/interactions',tamper=false) => {
    const raw={id:String(++interactionId),application_id:app,type:2,guild_id:guild,token:'t'.repeat(24),member:{user:{id:admin},roles:[]},data:{name:'rng-test',options:[{type:1,name:'mock-delivery',options:[{type:3,name:'fixture',value:'duplicate'}]}]},...extra};
    const content=JSON.stringify(raw),timestamp=String(Math.floor(Date.now()/1000)),signature=sign(null,Buffer.from(timestamp+content),privateKey).toString('hex');
    return mf.dispatchFetch('https://fixture.example'+path,{method:'POST',headers:{'Content-Type':'application/json','X-Signature-Timestamp':timestamp,'X-Signature-Ed25519':signature},body:tamper?content.replace('duplicate','handle'):content});
  };
  assert.equal((await native({},undefined,true)).status,401);
  assert.equal((await native({application_id:'99999999999999999'})).status,401);
  assert.equal((await (await native({},'/discord/interactions')).json()).type,4,'User Bot cannot expose the admin tester');
  assert.equal((await (await native({guild_id:'99999999999999999'})).json()).type,4);
  assert.equal((await (await native()).json()).type,5);
  assert.equal(completions.length,1);assert.match(completions[0].content,/TEST · mock-delivery/);assert.match(completions[0].embeds[0].title,/^TEST ·/);assert.deepEqual(completions[0].allowed_mentions.parse,[]);
  assert.deepEqual(await counts(),initial,'Tester cannot change any real account associations/sessions, acquisition table, shared contribution, outbox or ranking record');
  // Exact normal production validation and duplicate parity on real SQLite (a separate disposable fixture).
  const digest=(await mf.getDurableObjectNamespace('DIGEST')).getByName('parity');
  const at=Date.now(),normal={...input,event:{...input.event,occurredAt:at}};
  assert.equal((await digest.publish(normal,at)).stored,true);assert.equal((await digest.publish(normal,at)).stored,false);
  const malformed={...normal,event:{...normal.event,floor:'M8'}};assert.equal((await digest.publish(malformed,at)).error,'invalid_event');
  assert.equal((await digest.publish({...normal,type:'other'},at)).error,'invalid_event');
  console.log('RNG tester checks passed: actual admin/session/guild/signature/replay/blacklist authority, strict input, real SQLite validation/dedup parity, closed mock receipt retry, test labeling and zero production records.');
} finally {await mf.dispose();}
