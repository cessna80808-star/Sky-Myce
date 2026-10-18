import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createHash, randomBytes } from 'node:crypto';

const source = `import {DurableObject} from 'cloudflare:workers';
import {CommunityBazaar,bazaarPeriod,validBazaarEvent,redactBazaarReplay,validBazaarPreference} from './src/bazaar.ts';
import {BazaarMarket,normalizeBazaar,bazaarFreshness} from './src/bazaar-market.ts';
import {CommunitySharingStore,defaultSharingPolicy} from './src/community-sharing.ts';
import {CommunityLeaderboards} from './src/leaderboards.ts';
import {BazaarNotifications,validBazaarAlert} from './src/bazaar-notifications.ts';
import {handleBazaarRequest} from './src/bazaar-routes.ts';
import {AccountStore} from './src/accounts.ts';export{AccountStore};
export class Fixture extends DurableObject{
constructor(ctx,env){super(ctx,env);this.sql=ctx.storage.sql;this.sql.exec('CREATE TABLE IF NOT EXISTS sky_accounts(account_id TEXT PRIMARY KEY,discord_id TEXT);CREATE TABLE IF NOT EXISTS minecraft_accounts(uuid TEXT PRIMARY KEY,account_id TEXT,name TEXT);CREATE TABLE IF NOT EXISTS community_blacklist(kind TEXT,identity TEXT,removed_at INTEGER);');
this.sharing=new CommunitySharingStore(this.sql,{owner:uuid=>{const row=this.sql.exec('SELECT account_id FROM minecraft_accounts WHERE uuid=?',uuid).toArray()[0];return row?{accountId:row.account_id,uuids:this.sql.exec('SELECT uuid FROM minecraft_accounts WHERE account_id=?',row.account_id).toArray().map(r=>r.uuid)}:null},session:()=>({ok:false}),blacklisted:uuid=>this.sql.exec('SELECT identity FROM community_blacklist WHERE identity=?',uuid).toArray().length>0,purgeBazaar:(owner,uuid)=>this.bazaar.purge(owner,uuid)});
this.bazaar=new CommunityBazaar(this.sql,{check:(...a)=>this.sharing.check(...a)});this.boards=new CommunityLeaderboards(this.sql,{check:(...a)=>this.sharing.check(...a),record:(...a)=>this.sharing.recordContribution(...a),bazaar:(...a)=>this.bazaar.query(...a)});this.market=new BazaarMarket(this.sql);}
seed(uuid,owner,name){this.sql.exec('INSERT OR IGNORE INTO sky_accounts VALUES(?,?)',owner,'90000000000000001');this.sql.exec('INSERT OR REPLACE INTO minecraft_accounts VALUES(?,?,?)',uuid,owner,name)}
identity(uuid){const row=this.sql.exec('SELECT account_id,name FROM minecraft_accounts WHERE uuid=?',uuid).one();return{accountId:row.account_id,uuid,name:row.name}}
record(uuid,raw,now){return this.ctx.storage.transactionSync(()=>this.bazaar.record(this.identity(uuid),raw,now))}
remove(uuid,raw){return this.bazaar.remove(this.identity(uuid),raw)}
measure(uuid,raw,now){if(!this.measured){this.measuredCursors=[];const sql={exec:(...args)=>{const c=this.sql.exec(...args);this.measuredCursors.push(c);return c}};this.measured=new CommunityBazaar(sql,{check:(...args)=>this.sharing.check(...args)})}this.measuredCursors.length=0;const result=this.measured.record(this.identity(uuid),raw,now);return{result,queries:this.measuredCursors.length,rowsRead:this.measuredCursors.reduce((n,c)=>n+c.rowsRead,0),rowsWritten:this.measuredCursors.reduce((n,c)=>n+c.rowsWritten,0)}}
query(raw,now){return this.boards.query(raw,now)}
stats(uuid,raw){return this.bazaar.stats(this.identity(uuid),raw)}
policy(uuid,raw){const state=this.sharing.getVerified(uuid);return this.sharing.updateVerified(uuid,state.policy.revision,{...defaultSharingPolicy(),...raw})}
detach(uuid){const owner=this.identity(uuid).accountId;this.sharing.detachUuid(uuid,owner);this.sql.exec('DELETE FROM minecraft_accounts WHERE uuid=?',uuid)}
relink(uuid,owner,name){this.sharing.mergeUuid(uuid,owner);this.seed(uuid,owner,name)}
rows(){return this.sql.exec('SELECT * FROM community_bazaar_realizations').toArray()}
tombstones(){return this.sql.exec('SELECT * FROM community_bazaar_tombstones').toArray()}
expireTombstones(){this.sql.exec('UPDATE community_bazaar_tombstones SET expires_at=0')}
period(zone,now){return bazaarPeriod(zone,now)}
validate(event,now){return validBazaarEvent(event,now)}
preference(key,value){return validBazaarPreference(key,value)}
redact(raw,named=true){return redactBazaarReplay(raw,'SensitiveName',named)}
publish(uuid,raw){return this.bazaar.publish(this.identity(uuid),raw)}
revoke(uuid,raw,remove){return this.bazaar.revoke(this.identity(uuid),raw,remove)}
communityBazaarReplay(token){return this.bazaar.replay(token)}
communityBazaarGallery(){return this.bazaar.gallery()}
replayRows(){return this.sql.exec('SELECT data,revoked_at FROM community_bazaar_replays').toArray()}
settings(uuid,raw){return this.bazaar.settings(this.identity(uuid),raw)}
summary(uuid,raw){return this.bazaar.summary(this.identity(uuid),raw)}
communityBazaarMarket(active){return this.market.get(active)}
communityBazaarHistory(product,daily){return this.market.history(product,daily)}
marketGet(active,now){return this.market.get(active,now)}
marketProvider(provider){this.market=new BazaarMarket(this.sql,provider)}
normalize(raw,now){return normalizeBazaar(raw,now)}
fresh(at,now){return bazaarFreshness(at,now)}
alert(raw,now){return validBazaarAlert(raw,now)}
async notification(scenario){
const at=Date.now();let claims=0;let attachment={accountId:'owner',uuid:'a'.repeat(32),communitySessionId:'session',generation:0,leaseExpiresAt:at+100000,name:'A',connectionId:'connection',bazaar:{epoch:'e'.repeat(32),active:scenario!=='inactive',dmEnabled:scenario!=='disabled',revision:1}};
const ws={readyState:scenario==='offline'?3:1,deserializeAttachment:()=>structuredClone(attachment),serializeAttachment:s=>attachment=s,send:()=>{},close:()=>{}};
const api={communityBazaarMarket:async()=>({freshness:scenario==='stale'?'stale':'fresh',observedAt:at,products:[{id:'WHEAT',buyMovingWeek:1000000,sellMovingWeek:1000000}]}),claimCommunityBazaarAlert:async()=>{claims++;return scenario==='duplicate'?null:'90000000000000001'}};
const notifications=new BazaarNotifications({USER_BOT_TOKEN:'synthetic-test-only',ACCOUNT_STORE:{getByName:()=>api}},async()=>scenario!=='revoked');
await notifications.alert(ws,attachment,{type:'bazaar_alert',id:'c'.repeat(32),epoch:'e'.repeat(32),product:'WHEAT',netProfit:20000,roi:.2,confidence:.9,capital:100000,availableCapital:200000,observedAt:at,reason:'Observed depth supports the estimate'});return claims;}
async cancelledAlert(){let attachment={accountId:'owner',uuid:'a'.repeat(32),communitySessionId:'session',generation:0,leaseExpiresAt:Date.now()+100000,name:'A',connectionId:'connection',bazaar:{epoch:'e'.repeat(32),active:true,dmEnabled:true,revision:1}};const ws={readyState:1,deserializeAttachment:()=>structuredClone(attachment),serializeAttachment:s=>attachment=s,send:()=>{},close:()=>{}};let posts=0;const n=new BazaarNotifications({USER_BOT_TOKEN:'synthetic',ACCOUNT_STORE:{getByName:()=>({communityBazaarMarket:async()=>{attachment.bazaar.active=false;return{freshness:'fresh',observedAt:Date.now(),products:[]}},claimCommunityBazaarAlert:()=>{posts++;return'90000000000000001'}})}},async()=>true);await n.alert(ws,attachment,{type:'bazaar_alert',id:'c'.repeat(32),epoch:'e'.repeat(32),product:'WHEAT',netProfit:20000,roi:.2,confidence:.9,capital:100000,availableCapital:200000,observedAt:Date.now(),reason:'Observed depth supports the estimate'});return posts}
}
export default{async fetch(request,env){return await handleBazaarRequest(request,new URL(request.url).hostname==='auth.example'?{ACCOUNT_STORE:env.ACCOUNT_AUTH}:env)??new Response('not found',{status:404})}};`;
const bundle = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: 'bazaar-fixture.ts' }, bundle: true, format: 'esm', platform: 'browser', target: 'es2022', external: ['cloudflare:workers'], write: false });
let providerMode='normal', calls=0, electionCalls=0, discordMessages=0, discordCreates=0;
const product = (id='WHEAT') => ({ product_id:id, buy_summary:[{pricePerUnit:12,amount:2000,orders:3},{pricePerUnit:13,amount:500,orders:2}],
  sell_summary:[{pricePerUnit:10,amount:1000,orders:4},{pricePerUnit:9,amount:400,orders:1}],quick_status:{buyVolume:2500,sellVolume:1400,buyMovingWeek:700000,sellMovingWeek:600000,buyOrders:5,sellOrders:5} });
const payload = () => ({success:true,lastUpdated:Date.now(),products:{WHEAT:product()}});
const options=convertV4MiniflareOptions({name:'bazaar-check',modules:true,script:bundle.outputFiles[0].text,compatibilityDate:'2026-09-27',
 durableObjects:{ACCOUNT_STORE:{className:'Fixture',useSQLite:true},ACCOUNT_AUTH:{className:'AccountStore',useSQLite:true}},
 outboundService:async request=>{const url=new URL(request.url);if(url.hostname==='discord.com'){assert.equal(request.headers.get('Authorization'),'Bot synthetic-test-only');const raw=await request.json();if(url.pathname==='/api/v10/users/@me/channels'){discordCreates++;assert.equal(raw.recipient_id,'90000000000000001');return Response.json({id:'91000000000000001'})}assert.equal(url.pathname,'/api/v10/channels/91000000000000001/messages');assert.equal(raw.enforce_nonce,true);assert.match(raw.nonce,/^[a-f0-9]{24}$/);assert.deepEqual(raw.allowed_mentions,{parse:[]});discordMessages++;return Response.json({id:'92000000000000001'})}assert.equal(url.hostname,'api.hypixel.net','No credentialed, paid or unrelated provider');assert.equal(request.headers.get('API-Key'),null);
 if(url.pathname.endsWith('/election')){electionCalls++;return Response.json({success:true,lastUpdated:Date.now(),mayor:{name:'Diana',perks:[{name:'Mythological Ritual'}],minister:{name:'Paul'}}})}
 calls++;if(providerMode==='rate')return new Response('',{status:429,headers:{'Retry-After':'120'}});if(providerMode==='invalid')return Response.json({success:true});if(providerMode==='stale')return Response.json({...payload(),lastUpdated:Date.now()-360000});return Response.json(payload());}});
options.unsafeInspectDurableObjects=true;
const mf=new Miniflare(options);
try{
 const ns=await mf.getDurableObjectNamespace('ACCOUNT_STORE'),s=ns.getByName('global'),a='a'.repeat(32),b='b'.repeat(32),c='c'.repeat(32);
 await s.seed(a,'shared-owner','Alpha');await s.seed(b,'shared-owner','Beta');await s.seed(c,'other-owner','HiddenName');
 const now=Date.now(),event=(n,extra={})=>({id:n.toString(16).padStart(32,'0'),fingerprint:n.toString(16).padStart(64,'0'),cost:1000,proceeds:1200,fees:20,profit:200,durationMs:60000,completed:true,observedAt:now,quality:'observed',...extra});
 const batch=(events,timezone='Europe/Stockholm')=>({version:1,timezone,events});
 assert.equal((await s.record(a,batch([event(1)]),now)).accepted.length,1);
 assert.equal((await s.record(a,batch([event(1)]),now+1)).duplicates.length,1);
 assert.equal((await s.record(b,batch([event(2,{fingerprint:event(1).fingerprint})]),now+2)).duplicates.length,1,'Same source realization across devices/accounts cannot count twice');
 assert.equal((await s.record(b,batch([event(2)]),now+3)).accepted.length,1,'Similar genuine sales remain distinct');
 await s.record(a,batch([event(3,{cost:500,proceeds:400,profit:-100,completed:false,quality:'corroborated'})]),now+4);
 const q={category:'economy',metric:'bazaar_profit'},uuid=await s.query(q,now+5),linked=await s.query({...q,identity:'linked'},now+5);
 assert.deepEqual(uuid.entries.map(e=>e.value),[200,100]);assert.equal(linked.entries[0].value,300);assert.equal(linked.entries[0].sampleSize,3);assert.equal(linked.entries[0].completedFlips,2);assert.equal(linked.entries[0].roi,.12);
 assert.equal((await s.query({...q,search:'Alpha'},now+5)).total,0,'Hidden usernames never become searchable');
 for(const invalid of [{profit:1200},{quality:'estimated'},{quality:'manual'},{quality:'forecast'},{cost:0},{proceeds:-1},{profit:Infinity},{durationMs:0},{observedAt:now+61000},{observedAt:now-8*86400000},{inventory:{WHEAT:1}}])assert.equal(await s.validate(event(9,invalid),now),false,JSON.stringify(invalid));
 const p=await s.period('America/New_York',Date.UTC(2026,2,8,12));assert.equal(p.weekStart,Date.UTC(2026,2,8,5));assert.equal(p.weekEnd-p.weekStart,167*3600000,'Spring DST week is not 168 hours');
 const fall=await s.period('America/New_York',Date.UTC(2026,10,1,12));assert.equal(fall.weekEnd-fall.weekStart,169*3600000);
 const edge=await s.period('Europe/Stockholm',Date.UTC(2026,9,10,22));assert.equal(edge.weekStart,Date.UTC(2026,9,10,22),'Sunday local midnight');assert.equal((await s.period('Europe/Stockholm',Date.UTC(2026,9,10,21,59,59))).weekEnd,edge.weekStart);
 const month=await s.period('Europe/Stockholm',Date.UTC(2026,9,31,23));assert.equal(month.monthStart,Date.UTC(2026,9,31,23));
 const oldRows=await s.rows();await s.record(a,batch([event(4)],'Pacific/Auckland'),now+6);assert.deepEqual((await s.rows()).slice(0,3),oldRows,'Timezone change does not rewrite old attribution');
 const completion=ns.getByName('completion');await completion.seed(a,'completion-owner','First');await completion.seed(b,'completion-owner','Second');
 const partials=[event(1001,{completed:false}),event(1002,{completed:false})];await completion.record(a,batch(partials),now);
 const beforePromotion=await completion.rows();assert.equal((await completion.query(q,now+1)).entries[0].completedFlips,0);
 const collision=await completion.record(b,batch([{...partials[0],completed:true}]),now+1);assert.deepEqual(collision.promoted,[],'A sibling UUID cannot promote a foreign completion');
 const changed=await completion.record(a,batch([{...partials[0],cost:1001,proceeds:1201,completed:true}]),now+2);assert.deepEqual(changed.promoted,[],'Completion cannot rewrite financial evidence');
 const promotion=await completion.record(a,batch([{...partials[1],completed:true}],'Pacific/Auckland'),now+8*86400000);assert.deepEqual(promotion.promoted,[partials[1].id],'Later cancellation can close an already accepted realized portion');
 const afterPromotion=await completion.rows();assert.deepEqual({...afterPromotion[1],completed:0},beforePromotion[1],'Promotion cannot change financial values, receipt time or timezone attribution');
 const completedBoard=await completion.query(q,now+8*86400000+1);assert.equal(completedBoard.entries[0].completedFlips,1);assert.equal(completedBoard.entries[0].value,400);
 const promotionRetry=await completion.measure(a,batch([{...partials[1],completed:true}]),now+8*86400000+2);assert.equal(promotionRetry.rowsWritten,0,'Repeated completion promotion is read-only');
 const delayed=await s.record(a,batch([event(5,{observedAt:now-86400000})]),now+7);assert.equal(delayed.receivedAt,now+7,'Offline batches are attributed to receipt, never backdated');
 assert.equal((await s.remove(b,{version:1,ids:[event(1).id]})).count,0,'Another linked UUID cannot remove a different UUID source realization');
 assert.equal((await s.remove(a,{version:1,ids:[event(1).id]})).count,1,'Correction retracts an eligible public realization');assert.equal((await s.remove(a,{version:1,ids:[event(1).id]})).count,0,'Retried correction performs no financial update');
 assert.equal((await s.record(a,batch([event(8)],'GMT+2'),now+7)).ok,false);
 await s.policy(a,{economy:false});assert.equal((await s.query({...q,identity:'linked'},now+10)).total,0);
 assert.equal((await s.rows()).length,0,'Opt-out removes the complete financial/identity rows');const tombstones=await s.tombstones();assert(tombstones.length>0);assert(tombstones.every(row=>Object.keys(row).sort().join(',')==='expires_at,fingerprint'),'Only compact expiring unassociated tokens remain');
 assert.equal((await s.record(c,batch([event(9,{fingerprint:event(1).fingerprint,observedAt:Date.now()+1})]),Date.now()+1)).duplicates.length,1,'Deleted sales cannot be resurrected through another account');
 await s.record(c,batch([event(10,{observedAt:Date.now()+1})]),Date.now()+1);await s.detach(c);assert.equal((await s.query({...q,identity:'linked'},Date.now()+10)).total,1,'Unlinked account retains only its individual score');
 await s.relink(c,'new-owner','OtherName');assert.equal((await s.query(q,Date.now()+10)).total,0,'Reassignment cannot carry old attributable records into another identity');
 const measuredRaw=batch([event(99,{observedAt:Date.now()})]);const measured=await s.measure(c,measuredRaw,Date.now());const duplicateMeasurement=await s.measure(c,measuredRaw,Date.now());assert.equal(duplicateMeasurement.rowsWritten,0);assert.equal(duplicateMeasurement.queries,1);console.log('Measured realization SQL only (session/consent overhead excluded): cold-new='+JSON.stringify({queries:measured.queries,rowsRead:measured.rowsRead,rowsWritten:measured.rowsWritten})+' warm-duplicate='+JSON.stringify({queries:duplicateMeasurement.queries,rowsRead:duplicateMeasurement.rowsRead,rowsWritten:duplicateMeasurement.rowsWritten}));
 const fullBatch=Array.from({length:32},(_,i)=>event(2000+i,{observedAt:Date.now()}));assert.equal((await s.record(c,batch(fullBatch),Date.now())).accepted.length,32,'Full batch remains under the platform binding limit');const repeatedBatch=await s.measure(c,batch(fullBatch),Date.now());assert.equal(repeatedBatch.queries,1);assert.equal(repeatedBatch.rowsWritten,0,'Duplicate-heavy batches perform one bounded lookup, no global capacity scan or writes');
 await s.expireTombstones();const expired=await s.record(c,batch([event(1)]),now+9*86400000);assert.equal(expired.ok,false,'After token expiry a genuine original source timestamp is beyond the seven-day new-upload window');
 const replay={mode:'full',visibility:'community',durationMs:60000,completedFlips:1,realizedProfit:12345,cost:5000,timeline:[{at:30000,profit:12345,item:'WHEAT',quantity:20,buyPrice:250,sellPrice:900,fees:655}],redact:{items:false,transactions:false,values:false,identity:false}};
 assert.equal((await s.redact({...replay,mode:'summary'})).timeline.length,0);
 const progressive=await s.redact({...replay,mode:'summary',progress:[{at:30000,profit:5000},{at:60000,profit:7345}]});assert.equal(progressive.timeline.length,0);assert.equal(progressive.progress.length,2,'Summary retains coarse progression without transaction details');assert.equal((await s.redact({...replay,progress:[{at:30000,profit:12345}],redact:{...replay.redact,values:true}})).progress,undefined,'Exact-value redaction also removes progress values');
 const hidden=await s.redact({...replay,redact:{items:true,transactions:false,values:true,identity:true}});assert(!JSON.stringify(hidden).includes('WHEAT'));assert(!JSON.stringify(hidden).includes('12345'));assert(!JSON.stringify(hidden).includes('SensitiveName'));assert.equal(hidden.timeline[0].quantity,undefined);
 assert.equal((await s.redact({...replay,redact:{items:false,transactions:true,values:false,identity:false}})).timeline.length,0);
 assert.equal((await s.redact(replay,false)).identity,undefined);
 const publication=await s.publish(a,{version:1,confirm:true,replay});assert.equal(publication.ok,true);assert.equal((await s.communityBazaarGallery()).entries.length,1);
 assert.equal((await mf.dispatchFetch('https://fixture.example'+publication.path)).status,200);
 await s.revoke(b,{version:1,id:publication.id},false);assert.equal((await s.communityBazaarReplay(publication.token)).ok,false,'Another linked UUID may manage the same canonical owner publication');assert.equal((await s.communityBazaarGallery()).entries.length,0);
 assert.equal((await mf.dispatchFetch('https://fixture.example'+publication.path,{headers:{Accept:'application/json'}})).status,404);
 const other=await s.publish(a,{version:1,confirm:true,replay:{...replay,visibility:'unlisted'}});await s.revoke(c,{version:1,id:other.id},true);assert.equal((await s.communityBazaarReplay(other.token)).ok,true,'Unrelated account cannot revoke publication');await s.revoke(a,{version:1,id:other.id},true);
 assert.equal((await s.publish(a,{version:1,replay})).ok,false,'No implicit publication consent');
 const repeated=ns.getByName('repeated-publications');await repeated.seed(a,'publication-owner','Publisher');for(let i=0;i<25;i++){const item=await repeated.publish(a,{version:1,confirm:true,replay});assert.equal(item.ok,true);await repeated.revoke(a,{version:1,id:item.id},false)}
 const compacted=await repeated.replayRows();assert(compacted.length<=10);assert(compacted.every(row=>row.data==='{}'&&row.revoked_at!==null),'Revocation erases financial payload and bounds old management metadata');assert.equal((await repeated.publish(a,{version:1,confirm:true,replay})).ok,true,'Ten revocations cannot permanently block publication');
 assert.equal((await s.settings(a,{version:1,syncEnabled:true,revision:0,preferences:{risk:'balanced',discordAlerts:false,timezone:'Europe/Stockholm'}})).ok,true);
 assert.equal((await s.settings(a,{version:1,syncEnabled:true,revision:1,preferences:{webhook:'https://discord.com/api/webhooks/private'}})).ok,false,'Webhook never syncs');
 assert.equal((await s.settings(a,{version:1,syncEnabled:false})).preferences,null);
 const disabledSettings=await s.settings(a);assert(disabledSettings.revision>0);assert.equal((await s.settings(a,{version:1,syncEnabled:true,revision:0,preferences:{risk:'balanced'}})).ok,false,'Delayed initial upload cannot resurrect a remotely disabled copy');
 assert.equal((await s.settings(a,{version:1,syncEnabled:false})).revision,disabledSettings.revision,'Retried disable is idempotent');
 for(const [key,value] of Object.entries({inGameAlertProfit:0,discordAlertProfit:1000000000,dashboardAlertProfit:2500,webhookAlertProfit:0,inGameAlertCooldown:30,discordAlertCooldown:3600,dashboardAlertCooldown:300,webhookAlertCooldown:300,inGameAlertSound:false,dashboardAlertSound:true,hudX:32768,hudY:-32768,hudScalePercent:300,hudVisible:true,hudAnchor:'BOTTOM_RIGHT'}))assert.equal(await s.preference(key,value),true,key);
 for(const [key,value]of[['hudX',32769],['hudScalePercent',24],['hudAnchor','unknown'],['discordAlertProfit','100'],['inGameAlertSound','false'],['webhookAlertCooldown',29],['webhookURL','https://discord.com/private'],['webhookEnabled',true],['settingsSync',true]])assert.equal(await s.preference(key,value),false,key);
 assert.equal((await s.summary(a,{version:1,confirm:true,summary:{durationMs:60000,completedFlips:1,realizedProfit:200}})).ok,true);assert.equal((await s.stats(b,{})).sessionSummary.realizedProfit,200);
 const snapshot=payload(),normalized=await s.normalize(snapshot,Date.now());assert.equal(normalized.products[0].buyPrice,10);assert.equal(normalized.products[0].sellPrice,12);assert.equal(normalized.products[0].buyMovingWeek,600000,'Bid/ask sides are asymmetric, matching first-party API examples');
 assert.equal(await s.normalize({...snapshot,products:{WHEAT:product('OTHER')}},Date.now()),null);assert.equal(await s.normalize({...snapshot,products:{WHEAT:{...product(),sell_summary:[{amount:-1,pricePerUnit:10,orders:1}]}}},Date.now()),null);
 assert.equal(await s.fresh(now,now+120000),'fresh');assert.equal(await s.fresh(now,now+120001),'aging');assert.equal(await s.fresh(now,now+300001),'stale');
 const before=calls;await Promise.all([s.marketGet(true,Date.now()),s.marketGet(true,Date.now()),s.marketGet(true,Date.now())]);assert.equal(calls-before,1,'Concurrent clients share one upstream fetch');assert.equal(electionCalls,1);assert.match((await s.marketGet(true,Date.now())).mayor,/Diana.*Paul/);
 const history=await s.communityBazaarHistory('WHEAT',false);assert.equal(history.points.length,1);assert.equal(history.points[0].buyPrice,10);
 await s.marketProvider('cofl-free');assert.equal((await s.marketGet(true,Date.now())).freshness,'unavailable');assert.equal(calls-before,1,'Non-permitted source never fetches');
 providerMode='rate';const rate=ns.getByName('rate');const rateResult=await rate.marketGet(false,Date.now());assert.equal(rateResult.freshness,'unavailable');assert(rateResult.retryAt>Date.now()+100000);const rateCalls=calls;await rate.marketGet(true,Date.now());assert.equal(calls,rateCalls);
 providerMode='invalid';assert.equal((await ns.getByName('invalid').marketGet(false,Date.now())).freshness,'unavailable');
 providerMode='stale';assert.equal((await ns.getByName('stale').marketGet(false,Date.now())).freshness,'stale');
 assert.equal(await s.cancelledAlert(),0,'Activity revoked during an awaited market lookup cannot deliver');
 for(const scenario of ['inactive','disabled','offline','stale','revoked']){assert.equal(await s.notification(scenario),0,scenario);assert.equal(discordMessages,0);}
 assert.equal(await s.notification('duplicate'),1);assert.equal(discordCreates,0,'Suppressed duplicates never open a DM');assert.equal(await s.notification('eligible'),1);assert.equal(discordCreates,1);assert.equal(discordMessages,1,'Only a current opted-in online active session receives the private forecast');
 const alert={type:'bazaar_alert',id:'1'.repeat(32),epoch:'2'.repeat(32),product:'WHEAT',netProfit:20000,roi:.2,confidence:.9,capital:100000,availableCapital:200000,observedAt:now,reason:'Depth supports this forecast'};
 assert.equal(await s.alert(alert,now),true);for(const extra of [{observedAt:now-121000},{confidence:.5},{capital:300000},{netProfit:100},{roi:0}])assert.equal(await s.alert({...alert,...extra},now),false);

 // Actual AccountStore session/identity checks, with only synthetic fixture data.
 const auth=(await mf.getDurableObjectNamespace('ACCOUNT_AUTH')).getByName('global');await auth.communityGetSharing('invalid');
 const sql=await mf.unsafeGetDurableObjectStorage('bazaar-check','AccountStore',{name:'global'}),t=Date.now(),owner='synthetic-owner',player='5'.repeat(32),access=randomBytes(32).toString('base64url'),hash=x=>createHash('sha256').update(x).digest('hex');
 await sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)',owner,'92000000000000001',t,t);await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)',player,owner,'AuthPlayer',t,t);
 await sql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version)VALUES(?,?,?,0,?,?,?,?,?,?,NULL,?)',hash(access).slice(0,32),owner,player,hash('refresh:'+access),t+86400000,hash(access),t+600000,t,t,'synthetic');
 const post=async(path,raw,token=access)=>{const response=await mf.dispatchFetch('https://auth.example/community/bazaar/'+path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify(raw)});return response.json()};
 const bound={...batch([event(100,{observedAt:Date.now()})]),expectedAccountId:owner,expectedUuid:player};assert.equal((await post('contributions',bound)).ok,true);
 assert.equal((await post('contributions',{...bound,expectedUuid:a})).error,'session_identity_changed');assert.equal((await post('contributions',bound,'x'.repeat(43))).ok,false);
 await auth.updateSharingVerified(player,0,{version:1,enabled:true,dungeons:true,economy:false,rng:true,activity:true,leaderboards:true,publicName:false});assert.equal((await auth.communityLeaderboard(access,q)).total,0);
 await sql.exec('UPDATE community_sessions SET revoked_at=?',Date.now());assert.equal((await auth.communityBazaar(access,'stats',{})).ok,false);
 console.log('Bazaar checks passed: accounting boundaries, dedup, local periods/DST, opt-out, replay redaction/revocation, shared provider cache, live-DM guards, actual session authority.');
 console.log('Measured compact upload bytes: '+Buffer.byteLength(JSON.stringify(bound))+' for one supported realization; duplicate upload retains exactly one integrity row and performs no realization write.');
}finally{await mf.dispose()}
