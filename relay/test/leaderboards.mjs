import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createHash, createHmac, randomBytes } from 'node:crypto';

// Production leaderboard/consent/feed helpers on actual SQLite; no Discord calls or fabricated live records.
const source = `import {DurableObject} from 'cloudflare:workers';
 import {CommunitySharingStore,defaultSharingPolicy} from './src/community-sharing.ts';
 import {CommunityLeaderboards,utcPeriodStart,handleCommunityFeed,handleLeaderboardsRequest,discordLeaderboardMessage} from './src/leaderboards.ts';
 import {AccountStore} from './src/accounts.ts';
 import {validatedPublicRngEvent} from './src/digest.ts';
 export {AccountStore};
 export class LeaderboardFixture extends DurableObject {
  constructor(ctx,env){super(ctx,env);this.sql=ctx.storage.sql;this.sql.exec('CREATE TABLE IF NOT EXISTS sky_accounts(account_id TEXT PRIMARY KEY,discord_id TEXT);CREATE TABLE IF NOT EXISTS minecraft_accounts(uuid TEXT PRIMARY KEY,account_id TEXT,name TEXT);CREATE TABLE IF NOT EXISTS community_blacklist(kind TEXT,identity TEXT,removed_at INTEGER);');
   const owner=uuid=>{const a=this.sql.exec('SELECT account_id FROM minecraft_accounts WHERE uuid=?',uuid).toArray()[0];return a?{accountId:a.account_id,uuids:[uuid]}:null};
   this.sharing=new CommunitySharingStore(this.sql,{owner,session:()=>({ok:false}),blacklisted:uuid=>this.sql.exec("SELECT identity FROM community_blacklist WHERE kind='minecraft' AND identity=? AND removed_at IS NULL",uuid).toArray().length>0});
   this.boards=new CommunityLeaderboards(this.sql,{check:(...x)=>this.sharing.check(...x),record:(...x)=>this.sharing.recordContribution(...x)});
  }
  seed(uuid,name,owner='owner:'+uuid){this.sql.exec('INSERT OR IGNORE INTO sky_accounts VALUES(?,?)',owner,'91'+String(parseInt(uuid.slice(0,4),16)).padStart(15,'0'));this.sql.exec('INSERT OR REPLACE INTO minecraft_accounts VALUES(?,?,?)',uuid,owner,name)}
  contribution(uuid,id,metric,value,at,complete=true,extra={}){const a=this.sql.exec('SELECT account_id FROM minecraft_accounts WHERE uuid=?',uuid).one();const category=metric.startsWith('dungeon_')?'dungeons':metric==='rng_drops'?'rng':'economy';this.sql.exec('INSERT OR IGNORE INTO community_contributions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',id,extra.owner??a.account_id,uuid,extra.category??category,metric,value,extra.unit??(metric==='dungeon_runs'?'runs':metric==='dungeon_run_time'?'milliseconds':metric==='rng_drops'?'drops':'coins'),at,at,extra.source??'client-observation',extra.verification??'estimated',complete?1:0,0,extra.floor??null)}
  query(raw,now){return this.boards.query(raw,now)}
  message(result){return discordLeaderboardMessage(result)}
  comparison(uuid,raw,now){return this.boards.journalComparison(uuid,raw,now)}
  policyOwner(owner,policy,cutoffs={}){this.sql.exec('INSERT OR REPLACE INTO community_sharing VALUES(?,?,?,?,?)',owner,JSON.stringify({...defaultSharingPolicy(),...policy}),1,Date.now(),JSON.stringify(cutoffs))}
  blockDiscord(owner){this.sql.exec("INSERT INTO community_blacklist SELECT 'discord',discord_id,NULL FROM sky_accounts WHERE account_id=?",owner)}
  remount(){this.boards=new CommunityLeaderboards(this.sql,{check:(...x)=>this.sharing.check(...x),record:(...x)=>this.sharing.recordContribution(...x)})}
  boundary(period,now){return utcPeriodStart(period,now)}
  policy(uuid,raw){const before=this.sharing.getVerified(uuid);const r=this.sharing.updateVerified(uuid,before.policy.revision,{...defaultSharingPolicy(),...raw});if(r.suppressedCategories?.includes('rng'))this.boards.suppress(r.affectedUuids);return r}
  block(uuid){this.sql.exec('INSERT INTO community_blacklist VALUES(?,?,NULL)','minecraft',uuid)}
  accept(uuid,event,sourceId){return this.boards.acceptRng(uuid,event,sourceId,this.env.DISCORD_RNG_CHANNEL_ID)}
  validateEvent(event){return validatedPublicRngEvent(event)}
  pending(){return this.sql.exec('SELECT id FROM community_rng_delivery').toArray()}
  forceRetry(){this.sql.exec('UPDATE community_rng_delivery SET claim_until=0,next_at=0')}
  expirePending(){this.sql.exec('UPDATE community_rng_delivery SET received_at=?,claim_until=0,next_at=0 WHERE message_id IS NULL',Date.now()-600001)}
  attemptedBatch(uuid){for(let index=1;index<=500;index++){const id=index.toString(16).padStart(32,'0'),event={id,item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now(),player:'Anonymous',unverified:true};this.sql.exec('INSERT INTO community_rng_delivery VALUES(?,?,?,?,?,1,NULL,0,?,NULL,0)',id,uuid,JSON.stringify(event),Date.now()-600001,0,this.env.DISCORD_RNG_CHANNEL_ID)}}
  delivered(uuid,index,named=false){const id=index.toString(16).padStart(32,'0'),event={id,item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now()-1000,player:named?'OldestPlayer':'Anonymous',unverified:true};this.sql.exec('INSERT INTO community_rng_delivery VALUES(?,?,?,?,?,0,NULL,0,?,?,0)',id,uuid,JSON.stringify(event),Date.now()-300000+index,0,this.env.DISCORD_RNG_CHANNEL_ID,String(95000000000000000n+BigInt(index)))}
  deliveredBatch(oldest,others,named=false){for(let index=1;index<=205;index++)this.delivered(index===1?oldest:others,index,index===1&&named)}
  flagDelivery(index){this.sql.exec('UPDATE community_rng_delivery SET suppressed=1,next_at=0 WHERE id=?',index.toString(16).padStart(32,'0'))}
  async communityLeaderboard(token,query){return token==='t'.repeat(43)?this.boards.query(query):{ok:false,error:'session_expired'}}
  acceptCommunityFeedNonce(nonce){return this.boards.acceptNonce(nonce)}
  claimCommunityRng(channel,receipts){return this.boards.claim(channel,receipts)}
  acknowledgeCommunityRng(raw,channel){return this.boards.acknowledge(raw,channel)}
 }
 export default{async fetch(request,env){const url=new URL(request.url);return await handleCommunityFeed(request,env)??await handleLeaderboardsRequest(request,url.pathname==='/community/journal-comparison'||url.hostname==='auth.fixture.example'?{...env,ACCOUNT_STORE:env.ACCOUNT_AUTH}:env)??new Response('Not found',{status:404})}};`;
const bundle = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: 'leaderboard-fixture.ts' }, bundle: true, format: 'esm', platform: 'browser', target: 'es2022', external: ['cloudflare:workers'], write: false });
const channel = '22222222222222222', secret = 's'.repeat(32), name = 'leaderboard-check';
const options = convertV4MiniflareOptions({ name, modules: true, script: bundle.outputFiles[0].text, compatibilityDate: '2026-09-27',
 bindings: { DISCORD_RNG_CHANNEL_ID: channel, DISCORD_BRIDGE_SECRET: secret }, durableObjects: { ACCOUNT_STORE: { className: 'LeaderboardFixture', useSQLite: true }, ACCOUNT_AUTH: {className:'AccountStore',useSQLite:true} },
 outboundService: () => { throw Error('Leaderboard fixtures do not contact external services'); } });
options.unsafeInspectDurableObjects = true;
const mf = new Miniflare(options);
try {
 const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global'), a='a'.repeat(32),b='b'.repeat(32),c='c'.repeat(32);
 await store.seed(a,'ExampleOne');await store.seed(b,'ExampleTwo');await store.seed(c,'ExampleThree');
 await store.policy(a,{publicName:true});await store.policy(b,{publicName:true});
 const now=Date.now(), week=await store.boundary('weekly',now), month=await store.boundary('monthly',now);
 assert.equal(await store.boundary('weekly',Date.UTC(2026,9,12,12)),Date.UTC(2026,9,12));
 assert.equal(await store.boundary('weekly',Date.UTC(2026,9,11,23,59)),Date.UTC(2026,9,5));
 assert.equal(await store.boundary('monthly',Date.UTC(2026,0,1)),Date.UTC(2026,0,1));
 assert.equal(await store.boundary('all-time',now),null);
 await store.contribution(a,'1'.repeat(32),'dungeon_runs',2,week-1);
 await store.contribution(a,'2'.repeat(32),'dungeon_runs',3,week);
 await store.contribution(b,'3'.repeat(32),'dungeon_runs',5,now-1000);
 await store.contribution(c,'4'.repeat(32),'dungeon_runs',1,now-1000);
 await store.contribution(c,'5'.repeat(32),'dungeon_runs',100,now-1000,false);
 // Duplicate source IDs cannot add a second score; different Minecraft accounts remain distinct rows.
 await store.contribution(a,'2'.repeat(32),'dungeon_runs',9999,week);
 const all=await store.query({category:'dungeons',metric:'dungeon_runs'});
 assert.deepEqual(all.entries.map(e=>[e.rank,e.value]),[[1,5],[1,5],[3,1]]);assert.equal(all.total,3);
 assert.equal(all.entries[2].player,'Anonymous player');assert.ok(!JSON.stringify(all).includes(a));assert.ok(!JSON.stringify(all).includes('owner:'));
 const weekly=await store.query({category:'dungeons',metric:'dungeon_runs',period:'weekly'});
 assert.deepEqual(weekly.entries.map(e=>e.value),[5,3,1]);
 const page=await store.query({category:'dungeons',metric:'dungeon_runs',limit:1,page:2});assert.equal(page.entries.length,1);assert.equal(page.entries[0].rank,1);assert.equal(page.hasNext,true);
 assert.equal((await store.query({category:'dungeons',metric:'dungeon_runs',search:'ExampleThree'})).total,0,'Private names cannot be searched');
 assert.equal((await store.query({category:'dungeons',metric:'dungeon_runs',search:'ExampleOne'})).entries[0].rank,1,'Search preserves global rank');
 const averageQuery={category:'dungeons',metric:'dungeon_run_time',period:'weekly'};
 await store.contribution(a,'d0'.repeat(16),'dungeon_run_time',1000,week-1);
 await store.contribution(a,'d1'.repeat(16),'dungeon_run_time',300001,now-2000);
 await store.contribution(a,'d2'.repeat(16),'dungeon_run_time',300002,now-1000);
 await store.contribution(b,'d3'.repeat(16),'dungeon_run_time',300001,now-1000);
 await store.contribution(a,'d1'.repeat(16),'dungeon_run_time',999999,now-2000);
 for(const [index,value,complete,extra]of[[0,0,true,{}],[1,-1,true,{}],[2,86400001,true,{}],[3,1.5,true,{}],[4,300000,false,{}],[5,300000,true,{unit:'coins'}],[6,300000,true,{category:'economy'}]])
  await store.contribution(c,(20+index).toString(16).padStart(32,'0'),'dungeon_run_time',value,now-1000,complete,extra);
 const averages=await store.query(averageQuery,now);
 assert.equal(averages.unit,'milliseconds');assert.equal(averages.aggregation,'event-average');assert.match(averages.description,/across all floors/);
 assert.deepEqual(averages.entries.map(e=>[e.rank,e.player,e.value,e.sampleSize]),[[1,'ExampleTwo',300001,1],[2,'ExampleOne',300001.5,2]],'Only valid timed completions count; fastest averages rank first, duplicates and malformed/untimed runs do not dilute the mean');
 assert.match((await store.message(averages)).embeds[0].description,/\*\*5:00\.002\*\*/);
 assert.match((await store.message({...averages,entries:[{...averages.entries[0],value:3599999.5}]})).embeds[0].description,/\*\*1:00:00\.000\*\*/);
 assert.match((await store.message({...averages,entries:[{...averages.entries[0],value:86400000}]})).embeds[0].description,/\*\*24:00:00\.000\*\*/);
 const timePage=await store.query({...averageQuery,limit:1,page:2},now);assert.equal(timePage.entries[0].rank,2);assert.equal(timePage.entries[0].value,300001.5);
 const allTimes=await store.query({...averageQuery,period:'all-time'},now);assert.equal(allTimes.entries[0].sampleSize,3);assert.equal(allTimes.entries[0].value,(1000+300001+300002)/3);
 assert.deepEqual((await store.query({category:'dungeons',metric:'dungeon_runs'},now)).entries.map(e=>e.value),[5,5,1],'Timing samples do not change completed-run totals');
 await store.contribution(a,'e0'.repeat(16),'dungeon_run_time',200000,now-1000,true,{floor:'F7'});
 await store.contribution(b,'e1'.repeat(16),'dungeon_run_time',400000,now-1000,true,{floor:'M7'});
 const f7=await store.query({...averageQuery,floor:'F7'},now);
 assert.equal(f7.floor,'F7');assert.match(f7.description,/timed F7 runs/);assert.deepEqual(f7.entries.map(e=>[e.rank,e.player,e.value,e.sampleSize]),[[1,'ExampleOne',200000,1]],'A floor board ranks only that floor');
 assert.deepEqual((await store.query({...averageQuery,floor:'M7'},now)).entries.map(e=>[e.player,e.value]),[['ExampleTwo',400000]]);
 assert.equal((await store.query({...averageQuery,floor:'M1'},now)).total,0,'Legacy rows without a floor never enter a floor board');
 const allFloors=await store.query(averageQuery,now);
 assert.ok(!Object.hasOwn(allFloors,'floor'));assert.match(allFloors.description,/across all floors/);assert.equal(allFloors.entries.find(e=>e.player==='ExampleOne').sampleSize,3,'Omitting floor keeps the all-floors average, including floor-tagged runs');
 for(const invalid of[{...averageQuery,floor:'F8'},{...averageQuery,floor:'f7'},{...averageQuery,floor:''},{...averageQuery,floor:7},{category:'dungeons',metric:'dungeon_runs',floor:'F7'},{category:'dungeons',floor:'F7'},{category:'economy',metric:'bazaar_profit',floor:'F7'}])
  assert.equal((await store.query(invalid,now)).error,'invalid_leaderboard');
 await store.contribution(a,'6'.repeat(32),'networth',100,month-1);await store.contribution(a,'7'.repeat(32),'networth',90,now-2000);await store.contribution(a,'8'.repeat(32),'networth',80,now-1000);
 const snapshot=await store.query({category:'economy',metric:'networth',period:'monthly'});assert.equal(snapshot.entries[0].value,80);assert.equal(snapshot.entries[0].sampleSize,1);assert.match(snapshot.description,/not net-worth gain/);
 assert.equal((await store.query({category:'other',metric:'completed_tasks'})).available,false);
 assert.equal((await store.query({category:'rng',metric:'networth'})).error,'invalid_leaderboard');assert.equal((await store.query({page:NaN})).ok,false);
 await store.policy(a,{dungeons:false,publicName:true});assert.equal((await store.query({category:'dungeons',metric:'dungeon_runs'})).total,2);assert.equal((await store.query(averageQuery)).total,1);
 await store.block(b);assert.equal((await store.query({category:'dungeons',metric:'dungeon_runs'})).total,1);assert.equal((await store.query(averageQuery)).total,0);
 await store.remount();assert.equal((await store.query({category:'economy',metric:'networth'})).entries[0].value,80,'Persistent snapshots survive store reconstruction');

 const event={id:'d'.repeat(32),item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now(),player:'ExampleThree',unverified:true,rarity:'LEGENDARY',displayName:"Necron's Handle",floor:'F7'};
 for(const rarity of['COMMON','UNCOMMON','RARE','EPIC','LEGENDARY','MYTHIC','DIVINE','ULTIMATE','SPECIAL','VERY_SPECIAL','ADMIN'])assert.equal((await store.validateEvent({...event,rarity})).rarity,rarity);
 const unknownMetadata=await store.validateEvent({...event,rarity:null,displayName:null,floor:null});assert.ok(!Object.hasOwn(unknownMetadata,'rarity'));assert.ok(!Object.hasOwn(unknownMetadata,'displayName'));assert.equal(unknownMetadata.floor,null);
 for(const metadata of[{rarity:'UNKNOWN'},{displayName:'bad\u202ename'},{floor:'M8'},{privateUuid:c}])assert.equal((await store.accept(c,{...event,...metadata},'f'.repeat(32))).error,'invalid_event');
 assert.equal((await store.accept(c,event,'e'.repeat(32))).ok,true);
 assert.equal((await store.accept(c,{...event,id:'f'.repeat(32)},'e'.repeat(32))).ok,true);
 assert.equal((await store.pending()).length,1,'Cross-room replay has one global outbox and one RNG contribution');
 assert.equal((await store.query({category:'rng',metric:'rng_drops'})).entries[0].value,1);
 const signBody=body=>{const stamp=String(Date.now()),nonce=randomBytes(16).toString('hex');return {'Content-Type':'application/json','X-SkyMyce-Timestamp':stamp,'X-SkyMyce-Nonce':nonce,'X-SkyMyce-Signature':createHmac('sha256',secret).update('sky-myce-community-feed\nPOST\n/discord/community-feed\n'+stamp+'\n'+nonce+'\n'+createHash('sha256').update(body).digest('hex')).digest('hex')}};
 const request=async raw=>{const body=JSON.stringify({...raw,channelId:channel}),headers=signBody(body);return {response:await mf.dispatchFetch('https://fixture.example/discord/community-feed',{method:'POST',body,headers}),body,headers}};
 const claimed=await request({action:'claim'});assert.equal(claimed.response.status,200);const deliveries=(await claimed.response.json()).deliveries;assert.equal(deliveries.length,1);assert.equal(deliveries[0].event.player,'Anonymous');
 assert.equal(deliveries[0].event.rarity,'LEGENDARY');assert.equal(deliveries[0].event.displayName,"Necron's Handle");assert.equal(deliveries[0].event.floor,'F7');
 assert.ok(!JSON.stringify(deliveries).includes(c),'Private UUID never leaves the feed bridge');
 const replay=await mf.dispatchFetch('https://fixture.example/discord/community-feed',{method:'POST',body:claimed.body,headers:claimed.headers});assert.equal(replay.status,409);
 const tampered=await mf.dispatchFetch('https://fixture.example/discord/community-feed',{method:'POST',body:claimed.body.replace('claim','ack'),headers:claimed.headers});assert.equal(tampered.status,401);
 await store.policy(c,{rng:false});
 const ack=await request({action:'ack',id:deliveries[0].id,claim:deliveries[0].claim,operation:'publish',messageId:'44444444444444444'});assert.equal((await ack.response.json()).deleteRequired,true,'Consent changed in flight triggers removal');
 const retract=await request({action:'claim'}), removal=(await retract.response.json()).deliveries[0];assert.equal(removal.operation,'delete');assert.equal(removal.messageId,'44444444444444444');
 const removed=await request({action:'ack',id:removal.id,claim:removal.claim,operation:'delete'});assert.equal((await removed.response.json()).ok,true);assert.equal((await store.pending()).length,0);
 // A successful Discord send with a lost ACK must remain retractable after consent changes or expiry.
 const feedNamespace=await mf.getDurableObjectNamespace('ACCOUNT_STORE');
 for(const reason of['consent','expiry']){
  const lost=feedNamespace.getByName('lost-ack-'+reason),author='6'.repeat(32);await lost.seed(author,'LostAckPlayer');
  assert.equal((await lost.accept(author,{...event,id:'7'.repeat(32),player:'Anonymous',occurredAt:Date.now()},'8'.repeat(32))).ok,true);
  const original=(await lost.claimCommunityRng(channel)).deliveries[0];assert.equal(original.operation,'publish');
  if(reason==='consent'){await lost.policy(author,{rng:false});await lost.forceRetry()}else await lost.expirePending();
  await lost.remount();
  const cancellation=(await lost.claimCommunityRng(channel)).deliveries[0];assert.equal(cancellation.operation,'delete');
  assert.equal(cancellation.messageId,undefined);assert.equal(cancellation.event,undefined);assert.equal((await lost.pending()).length,1);
  const removalAck={id:cancellation.id,claim:cancellation.claim,operation:'delete'};
  assert.equal((await lost.acknowledgeCommunityRng(removalAck,'33333333333333333')).ok,false);
  assert.equal((await lost.acknowledgeCommunityRng({...removalAck,claim:original.claim},channel)).ok,false);
  assert.equal((await lost.acknowledgeCommunityRng(removalAck,channel)).ok,true);assert.equal((await lost.pending()).length,0);
 }
 const neverClaimed=feedNamespace.getByName('expired-unclaimed'),unclaimedAuthor='9'.repeat(32);await neverClaimed.seed(unclaimedAuthor,'UnclaimedPlayer');
 await neverClaimed.accept(unclaimedAuthor,{...event,id:'a'.repeat(32),player:'Anonymous',occurredAt:Date.now()},'b'.repeat(32));await neverClaimed.expirePending();
 assert.deepEqual((await neverClaimed.claimCommunityRng(channel)).deliveries,[]);assert.equal((await neverClaimed.pending()).length,0);
 const capacity=feedNamespace.getByName('cancel-capacity');await capacity.seed(unclaimedAuthor,'CapacityPlayer');await capacity.attemptedBatch(unclaimedAuthor);await capacity.remount();
 assert.equal((await capacity.accept(unclaimedAuthor,{...event,id:'c'.repeat(32),player:'Anonymous',occurredAt:Date.now()},'d'.repeat(32))).error,'rng_feed_busy');
 assert.equal((await capacity.pending()).length,500,'Unacknowledged posts are retained under the existing pending cap');
 assert.ok((await capacity.claimCommunityRng(channel)).deliveries.every(delivery=>delivery.operation==='delete'&&!delivery.event));
 const confirmed=feedNamespace.getByName('accepted-ack-lost');await confirmed.seed(unclaimedAuthor,'ConfirmedPlayer');
 const confirmedId='e'.repeat(32),confirmedMessage='55555555555555555';await confirmed.accept(unclaimedAuthor,{...event,id:confirmedId,player:'Anonymous',occurredAt:Date.now()},'f'.repeat(32));
 const sent=(await confirmed.claimCommunityRng(channel)).deliveries[0];await confirmed.acknowledgeCommunityRng({id:sent.id,claim:sent.claim,operation:'publish',messageId:confirmedMessage},channel);await confirmed.remount();
 const confirmation=await confirmed.claimCommunityRng(channel,[{id:confirmedId,messageId:confirmedMessage},{id:confirmedId,messageId:'66666666666666666'},{id:'f'.repeat(32),messageId:confirmedMessage}]);
 assert.deepEqual(confirmation.deliveries,[]);assert.deepEqual(confirmation.acknowledged,[confirmedId],'Accepted publish ACK can be confirmed without another publication');
 assert.deepEqual((await confirmed.claimCommunityRng('33333333333333333',[{id:confirmedId,messageId:confirmedMessage}])).acknowledged,[],'Receipt confirmation remains channel-bound');
 for(const receipts of[null,{},[{id:confirmedId,messageId:123}],[{id:confirmedId,messageId:Number(confirmedMessage)}],Array(2).fill({id:confirmedId,messageId:confirmedMessage}),Array(33).fill({id:confirmedId,messageId:confirmedMessage}),[{id:confirmedId,messageId:confirmedMessage,privateUuid:c}]])assert.equal((await request({action:'claim',receipts})).response.status,400);
 const denied=await mf.dispatchFetch('https://fixture.example/community/leaderboard');assert.equal(denied.status,401);
 const http=await mf.dispatchFetch('https://fixture.example/community/leaderboard?category=economy&metric=networth',{headers:{Authorization:'Bearer '+'t'.repeat(43)}});assert.equal(http.status,200);assert.equal((await http.json()).entries[0].value,80);
 const floorHttp=await mf.dispatchFetch('https://fixture.example/community/leaderboard?category=dungeons&metric=dungeon_run_time&floor=M7',{headers:{Authorization:'Bearer '+'t'.repeat(43)}});assert.equal(floorHttp.status,200);assert.equal((await floorHttp.json()).floor,'M7');
 assert.equal((await mf.dispatchFetch('https://fixture.example/community/leaderboard?category=dungeons&metric=dungeon_run_time&floor=M9',{headers:{Authorization:'Bearer '+'t'.repeat(43)}})).status,400);

 // Both projections use the same persisted RNG dataset, UTC window and current/ledger-owner privacy rules.
 const comparisonStore=(await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('comparison');
 const x='1'.repeat(32),y='2'.repeat(32),z='3'.repeat(32), empty='4'.repeat(32),comparisonNow=Date.UTC(2026,9,12,12);
 for(const [uuid,label]of[[x,'SampleOne'],[y,'SampleTwo'],[z,'SampleThree'],[empty,'NoReports']])await comparisonStore.seed(uuid,label);
 const monday=Date.UTC(2026,9,12),monthStart=Date.UTC(2026,9,1);let reportId=100;
 const report=(uuid,value,at,complete=true,extra={})=>comparisonStore.contribution(uuid,(reportId++).toString(16).padStart(32,'0'),'rng_drops',value,at,complete,extra);
 await report(x,1,monthStart-1);await report(x,1,monthStart);await report(x,1,monday-1);await report(x,1,monday);await report(x,1,comparisonNow);await report(y,1,comparisonNow-1);
 await report(x,100,comparisonNow+1);await report(x,100,comparisonNow,false);
 for(const [value,extra]of[[-1,{}],[0.5,{}],[1,{category:'economy'}],[1,{unit:'coins'}],[1,{source:'invented-source'}],[1,{verification:'verified'}]])await report(z,value,comparisonNow,true,extra);
 for(const [period,own]of[['all-time',5],['monthly',4],['weekly',2]]){
  const result=await comparisonStore.comparison(x,{period},comparisonNow),ranking=await comparisonStore.query({category:'rng',metric:'rng_drops',period},comparisonNow);
  assert.equal(result.ok,true);assert.equal(result.participantCount,ranking.total);assert.equal(result.totalDrops,ranking.entries.reduce((total,entry)=>total+entry.value,0));
  assert.equal(result.ownSharedCount,own);assert.equal(result.totalDrops,own+1);assert.equal(result.meanPerIdentity,(own+1)/2);assert.equal(result.updatedAt,comparisonNow);assert.equal(result.endAt,comparisonNow);
  assert.equal(result.source,ranking.source);assert.equal(result.coverage,ranking.coverage);assert.equal(result.startAt,period==='all-time'?null:period==='weekly'?monday:monthStart);
  assert.ok(!JSON.stringify(result).includes(x));assert.ok(!JSON.stringify(result).includes('SampleOne'));assert.ok(!JSON.stringify(result).includes('owner:'));
 }
 assert.equal((await comparisonStore.comparison(empty,{},comparisonNow)).ownSharedCount,0,'Eligible identity without shared records has a real zero');
 const noRecords=await comparisonStore.comparison(x,{period:'weekly'},Date.UTC(2026,10,2));assert.equal(noRecords.participantCount,0);assert.equal(noRecords.totalDrops,0);assert.equal(noRecords.meanPerIdentity,null);assert.equal(noRecords.updatedAt,0);
 for(const raw of[{period:'yearly'},{period:''},{period:null},{period:[]},{period:1},{uuid:y},[]])assert.equal((await comparisonStore.comparison(x,raw,comparisonNow)).error,'invalid_journal_comparison');
 await comparisonStore.policyOwner('owner:'+x,{rng:false});let hidden=await comparisonStore.comparison(x,{},comparisonNow);assert.equal(hidden.ownAvailable,false);assert.equal(hidden.ownSharedCount,null);assert.equal(hidden.ownUnavailableReason,'sharing_disabled');assert.equal(hidden.totalDrops,1);
 await comparisonStore.policyOwner('owner:'+x,{leaderboards:false});assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).ownAvailable,false);
 await comparisonStore.policyOwner('owner:'+x,{enabled:false});assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).totalDrops,1);
 await comparisonStore.policyOwner('owner:'+x,{}, {rng:monday});hidden=await comparisonStore.comparison(x,{},comparisonNow);assert.equal(hidden.ownAvailable,true);assert.equal(hidden.ownSharedCount,1,'Historical cutoff excludes the boundary itself even after sharing resumes');
 // Stale ledger rows cannot regain consent through detaching/reassigning an identity or reveal its old public name.
 await comparisonStore.policyOwner('historical-owner',{publicName:true});await report(z,1,comparisonNow,true,{owner:'historical-owner'});
 await comparisonStore.policyOwner('owner:'+z,{rng:false});assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).participantCount,2);
 await comparisonStore.policyOwner('owner:'+z,{});assert.equal((await comparisonStore.query({category:'rng',search:'SampleThree'},comparisonNow)).total,0);
 await comparisonStore.policyOwner('historical-owner',{rng:false});assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).participantCount,2);
 await comparisonStore.blockDiscord('owner:'+y);assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).totalDrops,1);
 await comparisonStore.policyOwner('owner:'+x,{});await report(x,Number.MAX_SAFE_INTEGER,comparisonNow);assert.equal((await comparisonStore.comparison(x,{},comparisonNow)).error,'comparison_unavailable','Unsafe totals fail instead of rounding, clamping or inventing a mean');

 // Real AccountStore HTTP authority, rather than fixture bearer shortcuts, binds own count to the current session UUID.
 const auth=(await mf.getDurableObjectNamespace('ACCOUNT_AUTH')).getByName('global');await auth.communityGetSharing('invalid');
 const authSql=await mf.unsafeGetDurableObjectStorage(name,'AccountStore',{name:'global'});
 const hash=value=>createHash('sha256').update(value).digest('hex');const authNow=Date.now();
 const firstToken=randomBytes(32).toString('base64url'),siblingToken=randomBytes(32).toString('base64url'),revokedToken=randomBytes(32).toString('base64url'),expiredToken=randomBytes(32).toString('base64url');
 const owner='synthetic-journal-owner',discord='92000000000000001',firstPlayer='5'.repeat(32),siblingPlayer='6'.repeat(32);
 await authSql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)',owner,discord,authNow,authNow);
 for(const [uuid,label]of[[firstPlayer,'CurrentPlayer'],[siblingPlayer,'OtherProfileOwner']])await authSql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)',uuid,owner,label,authNow,authNow);
 for(const [access,uuid,expires,revoked]of[[firstToken,firstPlayer,authNow+600000,null],[siblingToken,siblingPlayer,authNow+600000,null],[revokedToken,firstPlayer,authNow+600000,authNow],[expiredToken,firstPlayer,authNow-1,null]]){
  await authSql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version)VALUES(?,?,?,0,?,?,?,?,?,?,?,?)',hash(access).slice(0,32),owner,uuid,hash('refresh:'+access),authNow+86400000,hash(access),expires,authNow,authNow,revoked,'synthetic-test');
 }
 for(const [uuid,count]of[[firstPlayer,2],[siblingPlayer,1]])for(let i=0;i<count;i++)await authSql.exec('INSERT INTO community_contributions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)',(500+reportId++).toString(16).padStart(32,'0'),owner,uuid,'rng','rng_drops',1,'drops',authNow-1000,authNow-1000,'client-observation','unverified',1,0);
 const getComparison=async(token=firstToken,query='')=>{const response=await mf.dispatchFetch('https://fixture.example/community/journal-comparison'+query,{headers:token?{Authorization:'Bearer '+token}:{}});return{status:response.status,cache:response.headers.get('Cache-Control'),body:await response.json()}};
 const actual=await getComparison();assert.equal(actual.status,200);assert.equal(actual.cache,'no-store');assert.equal(actual.body.participantCount,2);assert.equal(actual.body.totalDrops,3);assert.equal(actual.body.meanPerIdentity,1.5);assert.equal(actual.body.ownSharedCount,2);
 assert.equal((await getComparison(siblingToken)).body.ownSharedCount,1,'Multiple MC identities on one Discord account remain distinct');
 const actualRanking=await auth.communityLeaderboard(firstToken,{category:'rng',metric:'rng_drops'});assert.equal(actual.body.totalDrops,actualRanking.entries.reduce((sum,row)=>sum+row.value,0));assert.equal(actual.body.participantCount,actualRanking.total);
 assert.deepEqual(Object.keys(actual.body).sort(),['ok','version','metric','period','startAt','endAt','participantCount','totalDrops','meanPerIdentity','ownSharedCount','ownAvailable','updatedAt','source','coverage'].sort());
 for(const token of[null,'invalid',randomBytes(32).toString('base64url'),revokedToken,expiredToken])assert.equal((await getComparison(token)).status,401);
 for(const query of['?period=yearly','?period=','?period=weekly&period=monthly','?uuid='+siblingPlayer,'?accountId='+owner,'?period=weekly&search=CurrentPlayer']){const denied=await getComparison(firstToken,query);assert.equal(denied.status,400);assert.equal(denied.body.error,'invalid_journal_comparison')}
 const method=await mf.dispatchFetch('https://fixture.example/community/journal-comparison',{method:'POST',headers:{Authorization:'Bearer '+firstToken}});assert.equal(method.status,405);
 await authSql.exec('UPDATE sky_accounts SET generation=1 WHERE account_id=?',owner);assert.equal((await getComparison()).body.error,'session_expired');await authSql.exec('UPDATE sky_accounts SET generation=0 WHERE account_id=?',owner);
 const replacement='synthetic-new-owner';await authSql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)',replacement,'92000000000000002',authNow,authNow);
 await authSql.exec('UPDATE minecraft_accounts SET account_id=? WHERE uuid=?',replacement,firstPlayer);assert.equal((await getComparison()).body.error,'session_expired','Old bearer cannot read its own former identity after reassignment');await authSql.exec('UPDATE minecraft_accounts SET account_id=? WHERE uuid=?',owner,firstPlayer);
 await authSql.exec('UPDATE account_limits SET count=60 WHERE id=?','journal-comparison:'+owner);assert.equal((await getComparison()).status,429);await authSql.exec('UPDATE account_limits SET count=0 WHERE id=?','journal-comparison:'+owner);
 await auth.updateSharingVerified(firstPlayer,0,{version:1,enabled:true,dungeons:true,economy:true,rng:false,activity:true,leaderboards:true,publicName:false});
 const optedOut=await getComparison();assert.equal(optedOut.status,200);assert.equal(optedOut.body.ownAvailable,false);assert.equal(optedOut.body.ownSharedCount,null);assert.equal(optedOut.body.participantCount,0);assert.equal(optedOut.body.meanPerIdentity,null,'Opt-out preserves access to aggregate reads while omitting all opted-out account rows');
 await authSql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,NULL,1)','minecraft',firstPlayer,'synthetic reason','synthetic actor',authNow);let blocked=await getComparison();assert.equal(blocked.status,401);assert.equal(blocked.body.error,'identity_blacklisted');
 await authSql.exec('UPDATE community_blacklist SET removed_at=? WHERE identity=?',authNow,firstPlayer);await authSql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,NULL,1)','discord',discord,'synthetic reason','synthetic actor',authNow);assert.equal((await getComparison()).body.error,'identity_blacklisted');
 await authSql.exec('UPDATE community_blacklist SET removed_at=? WHERE identity=?',authNow,discord);await authSql.exec('UPDATE community_sessions SET revoked_at=? WHERE access_hash=?',authNow,hash(firstToken));assert.equal((await getComparison()).body.error,'session_expired','Fresh authority is checked on every comparison read');

 // The old same-owner refresh deleted only count rows. Repair uses retained accepted server proof, never new ingestion or publication.
 const rngAuth=await mf.getDurableObjectNamespace('ACCOUNT_AUTH');
 const recovery=async(label,global=false)=>{
  const objectName=global?'global':'rng-recovery-'+label,account=rngAuth.getByName(objectName);await account.communityGetSharing('invalid');
  const sql=await mf.unsafeGetDurableObjectStorage(name,'AccountStore',{name:objectName}),owner='synthetic-recovery-'+label,uuid=hash('uuid:'+label).slice(0,32),access=randomBytes(32).toString('base64url'),sessionId=hash(access).slice(0,32);
  const now=Date.now(),acceptedAt=now-7200000,createdAt=acceptedAt-3600000,sourceId=hash('source:'+label).slice(0,32),id=hash('event:'+label).slice(0,32);
  const original={id,item:'IMPLOSION_SCROLL',activity:'dungeon',occurredAt:now-1000,player:'Anonymous',unverified:true,rarity:'EPIC',displayName:'Implosion',floor:'F7'};
  await sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)',owner,'93'+String(parseInt(uuid.slice(0,10),16)).padStart(15,'0'),createdAt,now);
  await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)',uuid,owner,'RecoveryPlayer',createdAt,now);
  await sql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version)VALUES(?,?,?,0,?,?,?,?,?,?,NULL,?)',sessionId,owner,uuid,hash('refresh:'+access),now+86400000,hash(access),now+600000,createdAt,now,'synthetic-test');
  await sql.exec('INSERT INTO community_connect_flows(flow_id,launch_hash,poll_hash,code_hash,uuid,name,mod_version,expires_at,account_id,session_id)VALUES(?,?,?,?,?,?,?,?,?,?)','flow:'+label,hash('launch:'+label),hash('poll:'+label),hash('code:'+label),uuid,'RecoveryPlayer','synthetic-test',createdAt+300000,owner,sessionId);
  assert.equal((await account.recordAcceptedCommunityRng(uuid,original,sourceId,owner)).ok,true);
  const delivery=(await account.claimCommunityRng(channel)).deliveries.find(row=>row.id===id);assert.ok(delivery);
  assert.equal((await account.acknowledgeCommunityRng({id,claim:delivery.claim,operation:'publish',messageId:'96000000000000001'},channel)).ok,true);
  const event={...original,occurredAt:acceptedAt-1000};
  await sql.exec('UPDATE community_rng_sources SET received_at=? WHERE author=?',acceptedAt,uuid);
  await sql.exec('UPDATE community_rng_delivery SET received_at=?,event=? WHERE author=?',acceptedAt,JSON.stringify(event),uuid);
  await sql.exec('UPDATE community_contributions SET observed_at=?,received_at=? WHERE uuid=?',event.occurredAt,acceptedAt,uuid);
  const before=await sql.exec('SELECT * FROM community_contributions WHERE uuid=?',uuid);
  await sql.exec('DELETE FROM community_contributions WHERE uuid=?',uuid); // Exact old refresh mutation.
  return{account,sql,owner,uuid,access,sessionId,createdAt,acceptedAt,sourceId,id,event,before,
   query:()=>account.communityLeaderboard(access,{category:'rng',metric:'rng_drops'}),count:async()=>(await sql.exec('SELECT COUNT(*) n FROM community_contributions WHERE uuid=?',uuid))[0].n,
   retained:async()=>({source:await sql.exec('SELECT * FROM community_rng_sources WHERE author=? ORDER BY source_id',uuid),delivery:await sql.exec('SELECT * FROM community_rng_delivery WHERE author=? ORDER BY id',uuid)})};
 };
 const repaired=await recovery('http',true),retained=await repaired.retained();
 const restoredHttp=await mf.dispatchFetch('https://auth.fixture.example/community/leaderboard?category=rng&metric=rng_drops',{headers:{Authorization:'Bearer '+repaired.access}});
 assert.equal(restoredHttp.status,200);const restoredResult=await restoredHttp.json();assert.equal(restoredResult.total,1);assert.equal(restoredResult.entries[0].value,1);assert.equal(restoredResult.entries[0].player,'Anonymous player');
 assert.deepEqual(await repaired.sql.exec('SELECT * FROM community_contributions WHERE uuid=?',repaired.uuid),repaired.before,'Restore the exact original source ID, observed time, accepted time and unverified one-drop units');
 for(let repeat=0;repeat<2;repeat++)assert.equal((await repaired.query()).entries[0].value,1);
 const comparisonHttp=await mf.dispatchFetch('https://auth.fixture.example/community/journal-comparison',{headers:{Authorization:'Bearer '+repaired.access}});assert.equal((await comparisonHttp.json()).ownSharedCount,1);
 assert.deepEqual(await repaired.retained(),retained,'Reads never enqueue, claim, repost or rewrite accepted Discord delivery');
 assert.equal((await repaired.account.recordAcceptedCommunityRng(repaired.uuid,repaired.event,hash('old-ingestion').slice(0,32),repaired.owner)).error,'invalid_event','The normal five-minute ingestion boundary is unchanged');

 const auditProof=await recovery('audit-proof');await auditProof.sql.exec('DELETE FROM community_connect_flows');
 await auditProof.sql.exec('INSERT INTO account_audit(at,actor,account_id,action,override,metadata,severity)VALUES(?,?,?,?,0,?,?)',auditProof.createdAt-1000,'synthetic',auditProof.owner,'community_connected',JSON.stringify({uuid:auditProof.uuid}),'information');
 assert.equal((await auditProof.query()).entries[0].value,1,'A durable original owner/UUID connect audit survives connect-flow expiry cleanup');

 const rejected=[
  ['no-proof',async r=>r.sql.exec('DELETE FROM community_connect_flows')],
  ['cancelled-flow',async r=>r.sql.exec('UPDATE community_connect_flows SET cancelled_at=?',Date.now())],
  ['late-session',async r=>r.sql.exec('UPDATE community_sessions SET created_at=?',r.acceptedAt+1)],
  ['revoked',async r=>r.sql.exec('UPDATE community_sessions SET revoked_at=?',Date.now())],
  ['expired',async r=>r.sql.exec('UPDATE community_sessions SET access_expires_at=0')],
  ['expired-refresh',async r=>r.sql.exec('UPDATE community_sessions SET refresh_expires_at=0')],
  ['generation',async r=>r.sql.exec('UPDATE sky_accounts SET generation=1')],
  ['other-owner',async r=>{await r.sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)','different-owner','94000000000000001',Date.now(),Date.now());await r.sql.exec('UPDATE minecraft_accounts SET account_id=?','different-owner')}],
  ['original-other-profile',async r=>r.sql.exec('UPDATE community_connect_flows SET uuid=?','f'.repeat(32))],
  ['suppressed',async r=>r.sql.exec('UPDATE community_rng_delivery SET suppressed=1')],
  ['missing-delivery',async r=>r.sql.exec('DELETE FROM community_rng_delivery')],
  ['missing-source',async r=>r.sql.exec('DELETE FROM community_rng_sources')],
  ['invalid-source-id',async r=>r.sql.exec("UPDATE community_rng_sources SET source_id='invalid'")],
  ['other-delivery-author',async r=>r.sql.exec('UPDATE community_rng_delivery SET author=?','f'.repeat(32))],
  ['unsupported-drop',async r=>r.sql.exec('UPDATE community_rng_delivery SET event=?',JSON.stringify({...r.event,item:'DIRT'}))],
  ['event-id-mismatch',async r=>r.sql.exec('UPDATE community_rng_delivery SET event=?',JSON.stringify({...r.event,id:'f'.repeat(32)}))],
  ['private-payload',async r=>r.sql.exec('UPDATE community_rng_delivery SET event=?',JSON.stringify({...r.event,privateUuid:r.uuid}))],
  ['bad-json',async r=>r.sql.exec("UPDATE community_rng_delivery SET event='{' ")],
  ['acceptance-age',async r=>r.sql.exec('UPDATE community_rng_delivery SET event=?',JSON.stringify({...r.event,occurredAt:r.acceptedAt-300000}))],
  ['future-acceptance',async r=>r.sql.exec('UPDATE community_rng_sources SET received_at=?',Date.now()+60000)],
 ];
 for(const [label,mutate]of rejected){const r=await recovery(label);await mutate(r);await r.query();assert.equal(await r.count(),0,label+' cannot reconstruct an unverifiable original count')}
 for(const action of['community_uuid_reconnected','community_uuid_unlinked','minecraft_uuid_removed','account_unlink']){
  const r=await recovery(action);await r.sql.exec('INSERT INTO account_audit(at,actor,account_id,action,override,metadata,severity)VALUES(?,?,?,?,0,?,?)',r.acceptedAt+1000,'synthetic',r.owner,action,JSON.stringify({uuid:r.uuid}),'information');
  await r.query();assert.equal(await r.count(),0,'Switching a session away and back cannot erase the '+action+' ownership boundary');
 }
 const interruptedAudit=await recovery('interrupted-audit');await interruptedAudit.sql.exec('DELETE FROM community_connect_flows');
 for(const [action,at]of[['community_connected',interruptedAudit.createdAt-2000],['community_uuid_reconnected',interruptedAudit.createdAt-1000]])await interruptedAudit.sql.exec('INSERT INTO account_audit(at,actor,account_id,action,override,metadata,severity)VALUES(?,?,?,?,0,?,?)',at,'synthetic',interruptedAudit.owner,action,JSON.stringify({uuid:interruptedAudit.uuid}),'information');
 await interruptedAudit.query();assert.equal(await interruptedAudit.count(),0,'Audit fallback requires continuous ownership since that proof, including changes before session creation');
 for(const privacy of['enabled','rng','leaderboards','publicName']){
  const r=await recovery('privacy-'+privacy),policy={version:1,enabled:true,dungeons:true,economy:true,rng:true,activity:true,leaderboards:true,publicName:false};
  let revision=0;
  if(privacy==='publicName'){assert.equal((await r.account.updateSharingVerified(r.uuid,revision++,{...policy,publicName:true})).ok,true);assert.equal((await r.account.updateSharingVerified(r.uuid,revision++,policy)).ok,true)}
  else assert.equal((await r.account.updateSharingVerified(r.uuid,revision++,{...policy,[privacy]:false})).ok,true);
  assert.equal((await r.account.updateSharingVerified(r.uuid,revision,{...policy,publicName:privacy==='publicName'})).ok,true);
  await r.query();assert.equal(await r.count(),0,privacy+' withdrawal and re-enable never resurrect historical counts');
 }
 for(const category of['rng','leaderboards'])for(const boundary of['observed','accepted']){
  const r=await recovery('cutoff-'+category+'-'+boundary),occurredAt=r.acceptedAt+(boundary==='accepted'?1000:-1000),cutoff=r.acceptedAt+(boundary==='accepted'?500:-500);
  await r.sql.exec('UPDATE community_rng_delivery SET event=?',JSON.stringify({...r.event,occurredAt}));
  await r.sql.exec('INSERT INTO community_sharing VALUES(?,?,?,?,?)',r.owner,JSON.stringify({version:1,enabled:true,dungeons:true,economy:true,rng:true,activity:true,leaderboards:true,publicName:false}),1,Date.now(),JSON.stringify({[category]:cutoff}));
  await r.query();assert.equal(await r.count(),0,category+' cutoff checks both observed and accepted time');
 }
 for(const kind of['minecraft','discord']){const r=await recovery('blocked-'+kind),identity=kind==='minecraft'?r.uuid:(await r.sql.exec('SELECT discord_id FROM sky_accounts WHERE account_id=?',r.owner))[0].discord_id;
  await r.sql.exec('INSERT INTO community_blacklist VALUES(?,?,?,?,?,NULL,1)',kind,identity,'synthetic','synthetic',Date.now());await r.query();assert.equal(await r.count(),0,kind+' blacklist prevents repair')}

 const scoped=await recovery('requester-scope');
 await scoped.account.communityLeaderboard(scoped.access,{category:'dungeons',metric:'dungeon_runs'});assert.equal(await scoped.count(),0,'Other feature reads do not repair RNG');
 const sibling=randomBytes(16).toString('hex'),siblingAccess=randomBytes(32).toString('base64url');await scoped.sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)',sibling,scoped.owner,'SiblingPlayer',scoped.createdAt,Date.now());
 await scoped.sql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version)VALUES(?,?,?,0,?,?,?,?,?,?,NULL,?)',hash(siblingAccess).slice(0,32),scoped.owner,sibling,hash('refresh:'+siblingAccess),Date.now()+86400000,hash(siblingAccess),Date.now()+600000,scoped.createdAt,Date.now(),'synthetic-test');
 await scoped.account.communityLeaderboard(siblingAccess,{category:'rng',metric:'rng_drops'});assert.equal(await scoped.count(),0,'A sibling UUID on the same account cannot repair another UUID');
 assert.equal((await scoped.account.communityJournalComparison(scoped.access,{})).ownSharedCount,1,'Own journal comparison also repairs the same original count');

 for(const existing of[true,false]){
  const r=await recovery('budget-'+existing),accepted=r.acceptedAt+10000;
  await r.sql.exec("WITH RECURSIVE n(x) AS(SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<100) INSERT INTO community_rng_sources SELECT ?,printf('%032x',1000+x),printf('%032x',2000+x),? FROM n",r.uuid,accepted);
  await r.sql.exec("WITH RECURSIVE n(x) AS(SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<100) INSERT INTO community_rng_delivery SELECT printf('%032x',2000+x),?,json_set(?,'$.id',printf('%032x',2000+x),'$.occurredAt',?),?,0,0,NULL,0,?,'96000000000000002',0 FROM n",r.uuid,JSON.stringify(r.event),accepted-1000,accepted,channel);
  if(existing)await r.sql.exec("WITH RECURSIVE n(x) AS(SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<100) INSERT INTO community_contributions SELECT printf('%032x',1000+x),?,?,'rng','rng_drops',1,'drops',?,?, 'client-observation','unverified',1,0,NULL FROM n",r.owner,r.uuid,accepted-1000,accepted);
  const unchanged=await r.retained();await r.query();
  assert.equal(await r.count(),existing?101:100,existing?'Already-counted newer reports do not starve the older missing row':'Each read repairs at most 100 missing rows');
  assert.equal((await r.sql.exec('SELECT id FROM community_contributions WHERE uuid=? AND id=?',r.uuid,r.sourceId)).length,existing?1:0,'The bounded repair visits newest missing rows first');
  await r.query();assert.equal(await r.count(),101,'Following reads finish without duplicates');assert.deepEqual(await r.retained(),unchanged);
 }
 // Hundreds of allowed receipts must not permanently hide an older now-private post.
 const namespace=await mf.getDurableObjectNamespace('ACCOUNT_STORE');
 for(const change of['blacklist','relink','name','global']){
  const scan=namespace.getByName('scan-'+change),oldest='7'.repeat(32),others='8'.repeat(32);
  await scan.seed(oldest,'OldestPlayer');await scan.seed(others,'CurrentPlayer');await scan.policyOwner('owner:'+oldest,{publicName:true});
  await scan.deliveredBatch(oldest,others,change==='name');
  assert.deepEqual((await scan.claimCommunityRng(channel)).deliveries,[]); // Cursor moves past the oldest before its authority changes.
  await scan.remount();
  if(change==='blacklist')await scan.block(oldest);
  if(change==='relink'){await scan.policyOwner('replacement-owner',{rng:false});await scan.seed(oldest,'OldestPlayer','replacement-owner')}
  if(change==='name')await scan.policyOwner('owner:'+oldest,{publicName:false});
  if(change==='global')await scan.policyOwner('owner:'+oldest,{enabled:false,publicName:true});
  let removal;for(let poll=0;poll<3&&!removal;poll++)removal=(await scan.claimCommunityRng(channel)).deliveries.find(delivery=>delivery.id==='1'.padStart(32,'0'));
  assert.ok(removal,change+' on the oldest receipt is found by the persisted fair scan');assert.equal(removal.operation,'delete');assert.equal(removal.messageId,'95000000000000001');
 }
 const priority=namespace.getByName('scan-priority'),priorityAuthor='9'.repeat(32);await priority.seed(priorityAuthor,'PriorityPlayer');
 await priority.deliveredBatch(priorityAuthor,priorityAuthor);await priority.claimCommunityRng(channel);await priority.flagDelivery(1);
 assert.equal((await priority.accept(priorityAuthor,{id:'e'.repeat(32),item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now(),player:'Anonymous',unverified:true},'f'.repeat(32))).ok,true);
 assert.deepEqual((await priority.claimCommunityRng(channel)).deliveries.map(delivery=>delivery.operation),['delete','publish'],'Explicit suppression stays ahead of pending publication independently of the scan cursor');
 console.log('Leaderboard/feed/journal comparison SQLite checks passed: shared UTC ranking parity, empty and own availability semantics, malformed/unsafe record exclusion, current and historical privacy, real session/blacklist authority, strict HTTP contracts, bounded idempotent accepted-RNG count recovery without feed changes, persisted global dedup and fair delivered-receipt consent retraction.');
} finally {await mf.dispose();}
