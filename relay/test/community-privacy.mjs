import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

/** Delay a genuine captured authority result in this in-memory fixture only, then change consent. */
export async function checkCommunityPrivacy(script, proof) {
  const anchor='checkCommunitySharing(session, input) {';assert.ok(script.includes(anchor));
  script=script.replace(anchor,'async privacyFixture(session, input) {');
  const method='getSharingVerified(player) {';assert.ok(script.includes(method));
  script=script.replace(method,`async checkCommunitySharing(session,input) {
    const verified=await this.privacyFixture(session,input); if(!verified.ok)return verified;
    const result=verified.decisions;
    this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS privacy_race(enabled INTEGER,captured INTEGER)');
    const gate=this.ctx.storage.sql.exec('SELECT * FROM privacy_race').toArray()[0];
    if(gate?.enabled && input.some(row=>row.recordedAt!==undefined)) {
      this.ctx.storage.sql.exec('UPDATE privacy_race SET captured=1');
      await new Promise(resolve=>setTimeout(resolve,250));
    }
    return {...verified,decisions:result};
  }
  ${method}`);
  const guild='93000000000000001',options=convertV4MiniflareOptions({name:'privacy-check',modules:true,script,compatibilityDate:'2026-09-27',bindings:{ROOMS:'friends',SKYMYCE_ENVIRONMENT:'production',DISCORD_GUILD_ID:guild},
    durableObjects:{ACCOUNT_STORE:{className:'AccountStore',useSQLite:true},RELAY_ROOMS:{className:'RelayRoom',useSQLite:true}},outboundService:()=>{throw Error('Privacy tests cannot call live services');}});
  options.unsafeInspectDurableObjects=true;options.workers[0].config.env.CONNECT_LIMIT={type:'rate-limit',namespace:'7112026',simple:{limit:1000,period:60}};
  const mf=new Miniflare(options),sockets=[];let sequence=93000000000000100n;
  try {
    const store=(await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const post=async(path,body,token)=>{const response=await mf.dispatchFetch(`https://privacy.test${path}`,{method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP':String(sequence++),...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});return response.json();};
    const connect=async(name,who)=>{const c=await post('/community/challenge',{purpose:'connect',clientNonce:randomBytes(32).toString('base64url')}),pollSecret=randomBytes(32).toString('base64url');const f=await post('/community/connect/start',{challengeId:c.challengeId,minecraftProof:proof(name,c.nonce),pollSecret,modVersion:'0.1.0'});
      assert.equal((await store.communityUserCommand({id:who,guildId:guild,roles:[],interactionId:String(sequence++)},'connect',{code:f.fallbackCode})).ok,true);return post('/community/connect/poll',{flowId:f.flowId,pollSecret});};
    const alice=await connect('Alice','93000000000000002'),bob=await connect('Bob','93000000000000003'),carol=await connect('Carol','93000000000000004');
    const policy={version:1,enabled:true,dungeons:true,economy:true,rng:true,activity:true,leaderboards:true,publicName:false};
    const update=async(person,revision,change)=>{const result=await post('/community/sharing',{expectedAccountId:person.identity.accountId,expectedUuid:person.identity.uuid,revision,policy:{...policy,...change}},person.credentials.accessToken);assert.equal(result.ok,true,JSON.stringify(result));return result;};
    const socket=async(person,name)=>{const response=await mf.dispatchFetch('https://privacy.test/websocket?room=friends',{headers:{Upgrade:'websocket','CF-Connecting-IP':String(sequence++)}});assert.equal(response.status,101);const ws=response.webSocket,queue=[],waits=[];sockets.push(ws);
      ws.addEventListener('message',event=>{const value=JSON.parse(event.data),waiting=waits.shift();waiting?waiting(value):queue.push(value);});const next=()=>queue.length?Promise.resolve(queue.shift()):new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Privacy socket timeout')),5000);waits.push(value=>{clearTimeout(timer);resolve(value);});});
      ws.accept();const c=await next();ws.send(JSON.stringify({...proof(name,c.serverId),communityToken:person.credentials.accessToken,cosmetics:true}));assert.equal((await next()).type,'ready');return {ws,next};};
    const viewer=await socket(alice,'Alice');
    const sql=await mf.unsafeGetDurableObjectStorage('privacy-check','AccountStore',{name:'global'}),room=await mf.unsafeGetDurableObjectStorage('privacy-check','RelayRoom',{name:'friends'});
    const stats={state:'AVAILABLE',catacombs:40,classes:{},completionTimes:{},sPlusTimes:{},completedFloors:[]};
    const seed=async()=>{await room.exec('INSERT OR REPLACE INTO player_stats VALUES(?,?,?,?,?)',bob.identity.uuid,'Bob',JSON.stringify(stats),Date.now(),carol.identity.uuid);await room.exec('INSERT INTO player_wealth(uuid,name,wealth,fetched_at,publisher) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET fetched_at=excluded.fetched_at',bob.identity.uuid,'Bob',JSON.stringify({hasProfile:true,networth:100}),Date.now(),carol.identity.uuid);};
    await update(alice,0,{dungeons:false,economy:false});await seed();
    const request=async(type)=>{const id=randomBytes(16).toString('hex');viewer.ws.send(JSON.stringify({type,id,name:'Bob',uuid:bob.identity.uuid}));let result;do{result=await viewer.next();}while(result.id!==id);return result;};
    assert.equal((await request('stats_get')).record.stats.catacombs,40,'Opting out does not disable receiving another consenting player cache');
    assert.equal((await request('wealth_get')).record.wealth.networth,100);
    await sql.exec('DELETE FROM privacy_race');await sql.exec('INSERT INTO privacy_race VALUES(1,0)');
    const waiting=request('stats_get'),deadline=Date.now()+2000;while(!(await sql.exec('SELECT captured FROM privacy_race'))[0]?.captured){assert.ok(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,10));}
    await update(carol,0,{dungeons:false,economy:false,rng:false});
    const result=await waiting;assert.equal(result.record,null);assert.equal(result.error,'sharing_disabled','Captured pre-opt-out authority cannot leak a deleted cache record after an await');
    assert.equal((await room.exec('SELECT * FROM player_stats')).length,0);assert.equal((await room.exec('SELECT * FROM player_wealth')).length,0,'Suppressing contributor deletes third-party cached subject rows');
    await seed();await sql.exec('UPDATE privacy_race SET enabled=0');
    assert.equal((await request('wealth_get')).record,null,'Contributor consent is checked even if the subject still shares');
    viewer.ws.send(JSON.stringify({type:'party_set',floor:'F7',open:true,selectedClass:'MAGE'}));
    viewer.ws.send(JSON.stringify({type:'party_get',id:randomBytes(16).toString('hex'),names:['Bob']}));const party=await viewer.next();assert.equal(party.type,'party_result');
    assert.equal((await room.exec('SELECT not_before FROM room_sharing_cutoffs WHERE uuid=? AND category=?',carol.identity.uuid,'dungeons')).length,1);
    console.log('Community privacy checks passed: contributor+subject consent, opted-out reception, deletion and captured-result races on actual SQLite/socket handlers.');
  } finally {for(const ws of sockets)try{ws.close();}catch{}await mf.dispose();}
}
