import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

// The production queue, consent and global ledger on real persisted SQLite. No public trust/auth bypass.
const source = `import {DurableObject} from 'cloudflare:workers';
 import {RngForwardQueue} from './src/rng-forwarding.ts';
 import {CommunityLeaderboards} from './src/leaderboards.ts';
 import {CommunitySharingStore,defaultSharingPolicy} from './src/community-sharing.ts';
 export class ForwardFixture extends DurableObject {
  constructor(ctx,env){super(ctx,env);this.sql=ctx.storage.sql;
   this.sql.exec('CREATE TABLE IF NOT EXISTS sky_accounts(account_id TEXT PRIMARY KEY,discord_id TEXT);CREATE TABLE IF NOT EXISTS minecraft_accounts(uuid TEXT PRIMARY KEY,account_id TEXT,name TEXT);CREATE TABLE IF NOT EXISTS community_blacklist(kind TEXT,identity TEXT,removed_at INTEGER);CREATE TABLE IF NOT EXISTS fixture_control(key TEXT PRIMARY KEY,value TEXT);');
   this.sharing=new CommunitySharingStore(this.sql,{owner:u=>{const a=this.sql.exec('SELECT account_id FROM minecraft_accounts WHERE uuid=?',u).toArray()[0];return a?{accountId:a.account_id,uuids:[u]}:null},session:()=>({ok:false}),blacklisted:u=>this.sql.exec("SELECT 1 FROM community_blacklist WHERE kind='minecraft' AND identity=? AND removed_at IS NULL",u).toArray().length>0});
   this.boards=new CommunityLeaderboards(this.sql,{check:(...x)=>this.sharing.check(...x),record:(...x)=>this.sharing.recordContribution(...x)});
   this.queue=new RngForwardQueue(ctx.storage,{deliver:r=>this.accept(r),diagnostic:k=>this.set('diagnostic',k)});
  }
  setup(){this.sql.exec('INSERT OR IGNORE INTO sky_accounts VALUES(?,?)','original-account','11111111111111111');this.sql.exec('INSERT OR IGNORE INTO minecraft_accounts VALUES(?,?,?)','a'.repeat(32),'original-account','ExamplePlayer')}
  set(k,v){this.sql.exec('INSERT OR REPLACE INTO fixture_control VALUES(?,?)',k,String(v))}
  get(k){return this.sql.exec('SELECT value FROM fixture_control WHERE key=?',k).toArray()[0]?.value}
  record(i,occurredAt=Date.now()){const id=i.toString(16).padStart(32,'0');return {uuid:'a'.repeat(32),accountId:'original-account',sourceId:id,event:{id,item:'NECRON_HANDLE',activity:'dungeon',occurredAt,player:'Anonymous',unverified:true}}}
  enqueue(i,occurredAt){return this.ctx.storage.transactionSync(()=>this.queue.enqueue(this.record(i,occurredAt)))}
  enqueueMetadata(i,metadata){const record=this.record(i);record.event={...record.event,...metadata};return this.ctx.storage.transactionSync(()=>this.queue.enqueue(record))}
  deliveredEvent(){return JSON.parse(this.sql.exec('SELECT event FROM community_rng_delivery LIMIT 1').one().event)}
  async accept(r){const calls=Number(this.get('calls')??0)+1;this.set('calls',calls);const mode=this.get('mode');
   if(mode==='fail-before')throw Error('Synthetic global outage');
   if(this.sql.exec('SELECT account_id FROM minecraft_accounts WHERE uuid=?',r.uuid).one().account_id!==r.accountId)return {ok:false,error:'stale_author'};
   const result=this.ctx.storage.transactionSync(()=>this.boards.acceptRng(r.uuid,r.event,r.sourceId,this.get('channel')==='off'?undefined:'22222222222222222'));
   if(mode==='lose-ack'&&!this.get('lost')){this.set('lost','yes');throw Error('Commit succeeded; synthetic acknowledgement lost')};return result;
  }
  async drain(){await this.queue.drain();return this.state()}
  async postpone(){this.sql.exec('UPDATE room_rng_forward SET next_at=?',Date.now()+60000);await this.ctx.storage.setAlarm(Date.now()+60000)}
  retry(){this.sql.exec('UPDATE room_rng_forward SET next_at=0,claim_until=0')}
  expire(){this.sql.exec('UPDATE room_rng_forward SET expires_at=?',Date.now()-1)}
  claim(){this.sql.exec('UPDATE room_rng_forward SET claim=?,claim_until=?','c'.repeat(43),Date.now()+15000);return this.queue.nextAlarm()}
  policy(enabled,suppress=false){const u='a'.repeat(32),before=this.sharing.getVerified(u);const result=this.sharing.updateVerified(u,before.policy.revision,{...defaultSharingPolicy(),rng:enabled});if(suppress)this.queue.suppress([u]);return result}
  move(){this.sql.exec('UPDATE minecraft_accounts SET account_id=?','new-account')}
  block(){this.sql.exec('INSERT INTO community_blacklist VALUES(?,?,NULL)','minecraft','a'.repeat(32))}
  capacity(full){if(!full){this.sql.exec('DELETE FROM community_rng_delivery WHERE author=?','b'.repeat(32));return}const event=JSON.stringify(this.record(1).event);const count=500-this.sql.exec('SELECT COUNT(*) n FROM community_rng_delivery').one().n;for(let i=0;i<count;i++)this.sql.exec('INSERT INTO community_rng_delivery VALUES(?,?,?,?,?,0,NULL,0,?,NULL,0)',('f'+i.toString(16).padStart(31,'0')),'b'.repeat(32),event,Date.now(),Date.now(),'22222222222222222')}
  fill(){let last;for(let i=1000;i<1257;i++)last=this.enqueue(i);return last}
  state(){return {pending:this.sql.exec('SELECT COUNT(*) n FROM room_rng_forward').one().n,deliveries:this.sql.exec('SELECT COUNT(*) n FROM community_rng_delivery').one().n,sources:this.sql.exec('SELECT COUNT(*) n FROM community_rng_sources').one().n,score:this.sql.exec('SELECT COALESCE(SUM(value),0) n FROM community_contributions').one().n,calls:Number(this.get('calls')??0),diagnostic:this.get('diagnostic'),next:this.queue.nextAlarm()}}
  async alarm(){await this.queue.drain()}
 }
 export default{fetch(){return new Response('Fixture only')}};`;
const bundle = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: 'rng-forward-fixture.ts' },
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022', external: ['cloudflare:workers'], write: false });
const directory = await mkdtemp(join(tmpdir(), 'sky-myce-forward-'));
const options = () => convertV4MiniflareOptions({ name: 'forward-check', modules: true, script: bundle.outputFiles[0].text,
  compatibilityDate: '2026-09-27', resourcePersistencePath: directory, durableObjects: { QUEUES: { className: 'ForwardFixture', useSQLite: true } },
  outboundService: () => { throw Error('Forwarding tests cannot contact live services'); } });
let mf = new Miniflare(options());
const fixture = async name => (await mf.getDurableObjectNamespace('QUEUES')).getByName(name);
try {
  let queue = await fixture('restart'); await queue.setup(); await queue.set('mode', 'lose-ack');
  const metadata={rarity:'LEGENDARY',displayName:"Necron's Handle",floor:'F7'};
  for(const bad of[{rarity:'INVENTED'},{displayName:'bad\nname'},{floor:'F8'},{privateAccount:'should never forward'}])assert.equal((await queue.enqueueMetadata(20,bad)).error,'invalid_event');
  assert.equal((await queue.enqueueMetadata(1,metadata)).ok, true);
  let state = await queue.drain(); assert.equal(state.pending, 1); assert.equal(state.deliveries, 1); assert.equal(state.score, 1);
  await queue.postpone(); await mf.dispose(); mf = new Miniflare(options()); queue = await fixture('restart');
  state = await queue.state(); assert.equal(state.pending, 1, 'Accepted forward intent survives actual local runtime restart');
  assert.equal(state.deliveries, 1); await queue.retry(); state = await queue.drain();
  assert.equal(state.pending, 0); assert.equal(state.deliveries, 1); assert.equal(state.score, 1); assert.equal(state.sources, 1);
  const persisted=await queue.deliveredEvent();for(const field of Object.keys(metadata))assert.equal(persisted[field],metadata[field],'Observed metadata survives actual runtime restart and lost acknowledgement');assert.equal(persisted.player,'Anonymous');

  const before = await fixture('before-commit'); await before.setup(); await before.set('mode', 'fail-before'); await before.enqueue(2);
  state = await before.drain(); assert.equal(state.pending, 1); assert.equal(state.deliveries, 0);
  await before.set('mode', 'healthy'); await before.retry(); state = await before.drain(); assert.equal(state.pending, 0); assert.equal(state.deliveries, 1);
  await before.capacity(true); await before.enqueue(3); state = await before.drain(); assert.equal(state.pending, 1); assert.equal(state.sources, 1);
  assert.equal(state.diagnostic, 'capacity'); await before.capacity(false); await before.retry(); state = await before.drain();
  assert.equal(state.pending, 0); assert.equal(state.sources, 2); assert.equal(state.score, 2, 'Full global outbox retry does not count twice');

  for (const action of ['policy', 'move', 'block']) {
    const privateQueue = await fixture('privacy-' + action); await privateQueue.setup(); await privateQueue.set('mode', 'fail-before'); await privateQueue.enqueue(4);
    assert.equal((await privateQueue.drain()).pending, 1); await privateQueue.set('mode', 'healthy');
    if (action === 'policy') await privateQueue.policy(false); else await privateQueue[action]();
    await privateQueue.retry(); state = await privateQueue.drain(); assert.equal(state.pending, 0); assert.equal(state.deliveries, 0); assert.equal(state.score, 0);
  }
  const suppression = await fixture('suppression'); await suppression.setup(); await suppression.enqueue(5); await suppression.policy(false, true);
  assert.equal((await suppression.state()).pending, 0, 'Room opt-out deletes pending accepted events before retry');
  const bounded = await fixture('bounded'); await bounded.setup(); assert.equal((await bounded.fill()).error, 'rng_forward_capacity');
  assert.equal((await bounded.state()).pending, 256); await bounded.expire(); assert.equal((await bounded.drain()).pending, 0);
  assert.equal((await bounded.state()).calls, 0, 'Expired forwards are not backfilled');
  const claimed = await fixture('claim'); await claimed.setup(); await claimed.enqueue(6); const at = await claimed.claim();
  assert.ok(at > Date.now()); assert.equal((await claimed.drain()).calls, 0, 'Live claims prevent concurrent replay');
  await claimed.retry(); assert.equal((await claimed.drain()).deliveries, 1);
  console.log('Persistent RNG forwarding checks passed: pre-commit outage, lost ACK/global exact-once across actual runtime restart, busy retry, owner/blacklist/consent recheck, opt-out suppression, capacity/expiry and claims.');
} finally {
  await mf.dispose();
  const target = resolve(directory), base = resolve(tmpdir());
  assert.ok(target.startsWith(base + '\\') || target.startsWith(base + '/')); assert.ok(target.split(/[\\/]/).at(-1).startsWith('sky-myce-forward-'));
  await rm(target, { recursive: true, force: true });
}
