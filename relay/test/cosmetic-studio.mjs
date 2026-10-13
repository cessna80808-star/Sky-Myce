import assert from 'node:assert/strict';
import { configureTrafficLimits } from './traffic-fixture.mjs';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { build } from 'esbuild';

function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let bit=0;bit<8;bit++) crc=(crc>>>1)^((crc&1)?0xedb88320:0); } return (crc^0xffffffff)>>>0; }
function chunk(type, data) { const bytes=Buffer.alloc(data.length+12);bytes.writeUInt32BE(data.length);bytes.write(type,4);data.copy(bytes,8);bytes.writeUInt32BE(crc32(bytes.subarray(4,data.length+8)),data.length+8);return bytes; }
function png({width=64,height=32,bomb=false,badFilter=false,metadata=false,compressedSuffix=Buffer.alloc(0),before=[]}={}) {
  const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=6;
  const pixels=Buffer.alloc(bomb?1000000:height*(1+width*4));for(let row=0;row<height;row++){pixels[row*(1+width*4)]=badFilter?9:0;for(let col=0;col<width;col++)pixels[row*(1+width*4)+1+col*4+3]=255;}
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),...before,...(metadata?[chunk('tEXt',Buffer.from('private-note\0discard this metadata'))]:[]),chunk('IDAT',Buffer.concat([deflateSync(pixels),compressedSuffix])),chunk('IEND',Buffer.alloc(0))]);
}
export async function checkCosmeticStudio(script, proof) {
  const first='92000000000000001',second='92000000000000002',guild='92000000000000003';let sequence=92000000000000100n;
  const options=convertV4MiniflareOptions({name:'studio-check',modules:true,script,compatibilityDate:'2026-09-27',
    bindings:{ROOMS:'friends,testing',SKYMYCE_ENVIRONMENT:'production',DISCORD_GUILD_ID:guild},r2Buckets:['COSMETIC_ASSETS','PRIVATE_ARTIFACTS'],
    durableObjects:{ACCOUNT_STORE:{className:'AccountStore',useSQLite:true},RELAY_ROOMS:{className:'RelayRoom',useSQLite:true}},outboundService:()=>{throw Error('Studio tests cannot call live services');}});
  options.unsafeInspectDurableObjects=true;options.workers[0].config.env.CONNECT_LIMIT={type:'rate-limit',namespace:'7112026',simple:{limit:1000,period:60}};
  configureTrafficLimits(options);
  const mf=new Miniflare(options),sockets=[];
  try {
    const store=(await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
    const call=async(path,{method='POST',raw,bytes,token,grant}={})=>{
      const response=await mf.dispatchFetch(`https://studio.test${path}`,{method,headers:{'CF-Connecting-IP':String(sequence++),...(token?{Authorization:`Bearer ${token}`}:{ }),...(grant?{'X-SkyMyce-Upload-Grant':grant}:{ }),...(raw?{'Content-Type':'application/json'}:bytes?{'Content-Type':'image/png'}:{})},body:raw?JSON.stringify(raw):bytes});
      return {status:response.status,body:response.headers.get('Content-Type')?.startsWith('image/png')?Buffer.from(await response.arrayBuffer()):await response.json(),headers:response.headers};
    };
    const connect=async(name,discord)=>{const c=(await call('/community/challenge',{raw:{purpose:'connect',clientNonce:randomBytes(32).toString('base64url')}})).body;const pollSecret=randomBytes(32).toString('base64url');
      const start=(await call('/community/connect/start',{raw:{challengeId:c.challengeId,minecraftProof:proof(name,c.nonce),pollSecret,modVersion:'0.1.0'}})).body;
      assert.equal((await store.communityUserCommand({id:discord,guildId:guild,roles:[],interactionId:String(sequence++)},'connect',{code:start.fallbackCode})).ok,true);
      return (await call('/community/connect/poll',{raw:{flowId:start.flowId,pollSecret}})).body;};
    const alice=await connect('Alice',first),bob=await connect('Bob',second),token=alice.credentials.accessToken;
    const owner={expectedAccountId:alice.identity.accountId,expectedUuid:alice.identity.uuid};
    const sql=await mf.unsafeGetDurableObjectStorage('studio-check','AccountStore',{name:'global'}),bucket=await mf.getR2Bucket('COSMETIC_ASSETS'),privateBucket=await mf.getR2Bucket('PRIVATE_ARTIFACTS');
    await privateBucket.put('releases/private.jar','synthetic private artifact');
    const socket=async(version,room='friends')=>{const response=await mf.dispatchFetch(`https://studio.test/websocket?room=${room}`,{headers:{Upgrade:'websocket','CF-Connecting-IP':String(sequence++)}});const ws=response.webSocket;assert.equal(response.status,101);sockets.push(ws);
      const queue=[],waits=[];ws.addEventListener('message',event=>{const data=JSON.parse(event.data),next=waits.shift();next?next(data):queue.push(data);});
      const next=()=>queue.length?Promise.resolve(queue.shift()):new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Studio socket timeout')),4000);waits.push(data=>{clearTimeout(timer);resolve(data);});});ws.accept();const c=await next();
      ws.send(JSON.stringify({...proof('Alice',c.serverId),communityToken:token,cosmetics:true,cosmeticsVersion:version}));assert.equal((await next()).cosmeticsVersion,5);
      const requestId=randomBytes(16).toString('hex');ws.send(JSON.stringify({type:'cosmetics_get',id:requestId,uuids:[alice.identity.uuid]}));assert.equal((await next()).id,requestId);return {ws,next};};
    const modern=await socket(4),old=await socket(3,'testing');
    assert.equal((await call('/community/cosmetics',{method:'GET'})).body.ok,false,'Missing credentials fail closed');
    const initial=(await call('/community/cosmetics',{method:'GET',token})).body;assert.equal(initial.profile.nametagScale,1);assert.equal(initial.profile.capeAsset,null);assert.equal(initial.presets.length,4);
    const profile={name:{text:'Studio',color:'gold'},scaleX:1.3,scaleY:1.4,scaleZ:0.8,nametagScale:0.75,capePreset:'forest',capeAsset:null};
    assert.equal((await call('/community/cosmetics',{token,raw:{...owner,expectedUuid:bob.identity.uuid,revision:0,profile}})).body.error,'session_identity_changed');
    const saved=(await call('/community/cosmetics',{token,raw:{...owner,revision:0,profile}})).body;assert.equal(saved.ok,true,JSON.stringify(saved));assert.equal(saved.profile.nametagScale,0.75);assert.equal(saved.profile.scaleY,1.4);
    const changed=await modern.next(),compatible=await old.next();assert.equal(changed.record.capePreset,'forest');assert.ok(!Object.hasOwn(compatible.record,'capePreset'));assert.ok(!Object.hasOwn(compatible.record,'nametagScale'),'Version3 exact old schema preserved');
    assert.equal((await call('/community/cosmetics',{token,raw:{...owner,revision:0,profile}})).body.error,'revision_conflict');
    for(const change of [{nametagScale:0.1},{scaleX:3.1},{capePreset:'https://attacker.test/cape.png'},{capePreset:'forest',capeAsset:'a'.repeat(32)}])assert.equal((await call('/community/cosmetics',{token,raw:{...owner,revision:1,profile:{...profile,...change}}})).body.ok,false);
    const grant=async()=>{const result=(await call('/community/cosmetics/grant',{token,raw:owner})).body;assert.equal(result.ok,true,JSON.stringify(result));return result;};
    const upload=async(g,bytes,as=token)=>call('/community/cosmetics/upload',{method:'PUT',token:as,grant:g.grant,bytes});
    const good=await grant();assert.match(good.grant,/^[A-Za-z0-9_-]{43}$/);assert.ok(!JSON.stringify(await sql.exec('SELECT * FROM cosmetic_asset_grants')).includes(good.grant),'Only grant hash stored');
    assert.equal((await upload(good,png(),bob.credentials.accessToken)).body.error,'invalid_upload_grant','Another linked user cannot consume the grant');
    const uploaded=(await upload(good,png({metadata:true}))).body;assert.equal(uploaded.ok,true,JSON.stringify(uploaded));assert.equal((await upload(good,png())).body.error,'invalid_upload_grant');
    const asset=uploaded.asset;assert.equal(asset.width,64);assert.equal(asset.height,32);assert.ok(!Buffer.from(await (await bucket.get(`capes/${asset.id}.png`)).arrayBuffer()).includes(Buffer.from('private-note')),'Ancillary metadata stripped');
    assert.equal((await call(`/community/cosmetics/assets/${asset.id}?uuid=${alice.identity.uuid}`,{method:'GET'})).status,404,'Unselected uploads cannot be publicly fetched');
    assert.equal((await call('/community/cosmetics',{token:bob.credentials.accessToken,raw:{expectedAccountId:bob.identity.accountId,expectedUuid:bob.identity.uuid,revision:0,profile:{...profile,capePreset:null,capeAsset:asset.id}}})).body.error,'asset_not_owned');
    const selected=(await call('/community/cosmetics',{token,raw:{...owner,revision:1,profile:{...profile,capePreset:null,capeAsset:asset.id}}})).body;assert.equal(selected.ok,true);assert.equal(selected.profile.capeAsset.sha256,asset.sha256);await modern.next();await old.next();
    assert.equal((await call('/community/cosmetics',{token,raw:{...owner,revision:2,profile:{...profile,capePreset:'none',capeAsset:asset.id}}})).body.error,'invalid_profile','No cape is mutually exclusive with an owned active upload');
    const served=await call(`/community/cosmetics/assets/${asset.id}?uuid=${alice.identity.uuid}`,{method:'GET'});assert.equal(served.status,200);assert.equal(createHash('sha256').update(served.body).digest('hex'),asset.sha256);assert.match(served.headers.get('Cache-Control'),/private, max-age=60/);
    assert.equal((await call(`/community/cosmetics/assets/${asset.id}?uuid=${bob.identity.uuid}`,{method:'GET'})).status,404);
    await bucket.put(`capes/${asset.id}.png`,Buffer.alloc(asset.size));assert.equal((await call(`/community/cosmetics/assets/${asset.id}?uuid=${alice.identity.uuid}`,{method:'GET'})).body.error,'asset_corrupt');await bucket.put(`capes/${asset.id}.png`,served.body);
    for(const bytes of [Buffer.from('not a png'),png({width:128,height:64}),png({bomb:true}),png({badFilter:true}),Buffer.alloc(262145),
      png().subarray(0,png().length-1),Buffer.concat([png(),Buffer.from([0])]),png({compressedSuffix:Buffer.from([0])}),
      png({compressedSuffix:deflateSync(Buffer.from('second stream'))}),png({before:[chunk('acTL',Buffer.alloc(8))]}),
      png({before:[chunk('ABCD',Buffer.alloc(0))]})]) {
      const g=await grant(), result=await upload(g,bytes);assert.equal(result.body.ok,false);assert.equal(await bucket.get(`capes/${g.assetId}.png`),null);assert.equal((await upload(g,png())).body.error,'invalid_upload_grant');
    }
    // Keep independent format/expiry checks below outside the grant burst tested by the quota fixture.
    await sql.exec("DELETE FROM account_limits WHERE id LIKE 'cape-grant:%'");
    const corrupt=png();corrupt[29]^=1;const bad=await grant();assert.equal((await upload(bad,corrupt)).body.error,'invalid_png');
    const expired=await grant();await sql.exec('UPDATE cosmetic_asset_grants SET expires_at=0 WHERE asset_id=?',expired.assetId);assert.equal((await upload(expired,png())).body.error,'invalid_upload_grant');
    await sql.exec("DELETE FROM account_limits WHERE id LIKE 'cape-grant:%'");
    await sql.exec("UPDATE cosmetic_assets SET status='deleted' WHERE status='reserved'");
    const reservations=[];for(let i=0;i<7;i++)reservations.push(await grant());assert.equal((await call('/community/cosmetics/grant',{token,raw:owner})).body.error,'asset_quota');
    await sql.exec("UPDATE cosmetic_assets SET expires_at=0 WHERE status='reserved'");const recycled=await grant();assert.equal(recycled.ok,true,'Interrupted expired reservations release quota');
    assert.equal((await call(`/community/cosmetics/assets/${asset.id}`,{method:'DELETE',token:bob.credentials.accessToken,raw:{expectedAccountId:bob.identity.accountId,expectedUuid:bob.identity.uuid}})).body.error,'asset_not_owned');
    assert.equal((await call(`/community/cosmetics/assets/${asset.id}`,{method:'DELETE',token,raw:owner})).body.ok,true);const removed=await modern.next();await old.next();assert.equal(removed.record.capeAsset,null);assert.equal(removed.record.revision,3);
    assert.equal(await bucket.get(`capes/${asset.id}.png`),null);assert.equal((await call(`/community/cosmetics/assets/${asset.id}?uuid=${alice.identity.uuid}`,{method:'GET'})).status,404);
    assert.equal(await (await privateBucket.get('releases/private.jar')).text(),'synthetic private artifact','Cape operations cannot touch PRIVATE_ARTIFACTS');
    const noCape=(await call('/community/cosmetics',{token,raw:{...owner,revision:3,profile:{...profile,capePreset:'none',capeAsset:null}}})).body;assert.equal(noCape.ok,true);assert.equal(noCape.profile.capePreset,'none');assert.equal(noCape.profile.capeAsset,null);
    assert.equal((await modern.next()).record.capePreset,'none');assert.ok(!Object.hasOwn((await old.next()).record,'capePreset'));
    const retainedNone=(await call('/community/cosmetics',{method:'GET',token})).body;assert.equal(retainedNone.profile.capePreset,'none');assert.equal(retainedNone.presets.length,4);assert.ok(!retainedNone.presets.some(preset=>preset.id==='none'),'None is a selection sentinel, not a texture preset');
    for(const invalid of['None','NONE',false,[]])assert.equal((await call('/community/cosmetics',{token,raw:{...owner,revision:4,profile:{...profile,capePreset:invalid,capeAsset:null}}})).body.error,'invalid_profile');
    const pending=await grant();const claim=await store.communityCosmeticClaim(token,pending.grant);assert.equal(claim.ok,true);await sql.exec('UPDATE community_sessions SET revoked_at=? WHERE session_id=?',Date.now(),alice.credentials.sessionId);
    assert.equal((await store.communityCosmeticFinish(token,pending.assetId,'a'.repeat(64),100)).ok,false,'Revocation during R2 write cannot finalize an upload');
    assert.equal((await sql.exec('SELECT status FROM cosmetic_assets WHERE id=?',pending.assetId))[0].status,'writing');
    await bucket.put(`capes/${pending.assetId}.png`,png());await sql.exec('UPDATE cosmetic_assets SET expires_at=0 WHERE id=?',pending.assetId);
    await store.cleanupCosmeticAssets();assert.equal(await bucket.get(`capes/${pending.assetId}.png`),null,'Alarm cleanup removes an interrupted R2 write');
    await checkPrivatePreviewBoundaries();
    console.log('Cosmetics Studio checks passed: verified ownership, independent scales, revision conflicts, persisted no-cape/v1-v4 compatibility, cross-room updates, isolated R2, PNG/CRC/inflate bounds, grants/replay/expiry, quotas, private unselected previews and post-R2 revocation, integrity and deletion.');
  } finally {for(const ws of sockets)try{ws.close();}catch{}await mf.dispose();}
}

/** Actual AccountStore and preview HTTP handler, with a controlled mutation during the R2 await. */
async function checkPrivatePreviewBoundaries() {
  const source=`import {AccountStore} from './src/accounts.ts';import {sha256} from './src/drm.ts';import {handleCosmeticAssets} from './src/cosmetic-assets.ts';
   export class PreviewFixture extends AccountStore {
    constructor(ctx,env){super(ctx,env);this.sql=ctx.storage.sql;this.reads=0}
    async setup(first,second,hash,size){const now=Date.now();for(const [owner,discord,uuid,token]of[['preview-owner','93000000000000001','a'.repeat(32),first],['other-owner','93000000000000002','b'.repeat(32),second]]){
     this.sql.exec('INSERT INTO sky_accounts(account_id,discord_id,created_at,last_seen)VALUES(?,?,?,?)',owner,discord,now,now);
     this.sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)',uuid,owner,'PreviewPlayer',now,now);
     this.sql.exec('INSERT INTO community_sessions(session_id,account_id,uuid,generation,refresh_hash,refresh_expires_at,access_hash,access_expires_at,created_at,last_seen,revoked_at,mod_version)VALUES(?,?,?,0,?,?,?,?,?,?,NULL,?)',owner,owner,uuid,await sha256('refresh:'+token),now+86400000,await sha256(token),now+600000,now,now,'synthetic');}
     this.sql.exec("INSERT INTO cosmetic_assets VALUES(?,?,?,'ready',?,?,?,?)",'c'.repeat(32),'preview-owner','a'.repeat(32),size,hash,now,now+120000);}
    reset(){this.reads=0;this.sql.exec('UPDATE community_sessions SET revoked_at=NULL');this.sql.exec("UPDATE minecraft_accounts SET account_id='preview-owner' WHERE uuid=?",'a'.repeat(32));this.sql.exec("UPDATE cosmetic_assets SET account_id='preview-owner',status='ready'");this.sql.exec('DELETE FROM community_blacklist')}
    status(value){this.sql.exec('UPDATE cosmetic_assets SET status=?',value)}
    count(){return this.reads}
    afterRead(mode){this.reads++;if(mode==='revoke')this.sql.exec("UPDATE community_sessions SET revoked_at=? WHERE account_id='preview-owner'",Date.now());
     if(mode==='blacklist')this.sql.exec("INSERT INTO community_blacklist VALUES('minecraft',?,'synthetic','synthetic',?,NULL,1)",'a'.repeat(32),Date.now());
     if(mode==='relink')this.sql.exec("UPDATE minecraft_accounts SET account_id='other-owner' WHERE uuid=?",'a'.repeat(32));
     if(mode==='owner')this.sql.exec("UPDATE cosmetic_assets SET account_id='other-owner'");if(mode==='delete')this.status('deleted');if(mode==='writing')this.status('writing');}
   }
   export default{async fetch(request,env){const store=env.ACCOUNT_STORE.getByName('global'),mode=request.headers.get('X-Preview-Fixture');const bucket={get:async key=>{const object=await env.COSMETIC_ASSETS.get(key);await store.afterRead(mode);return object}};return await handleCosmeticAssets(request,{...env,COSMETIC_ASSETS:bucket})??new Response('Not found',{status:404})}};`;
  const bundle=await build({stdin:{contents:source,resolveDir:process.cwd(),sourcefile:'preview-fixture.ts'},bundle:true,format:'esm',platform:'browser',target:'es2022',external:['cloudflare:workers'],write:false});
  const mf=new Miniflare(convertV4MiniflareOptions({name:'preview-boundary-check',modules:true,script:bundle.outputFiles[0].text,compatibilityDate:'2026-09-27',bindings:{ROOMS:'friends,testing'},r2Buckets:['COSMETIC_ASSETS'],durableObjects:{ACCOUNT_STORE:{className:'PreviewFixture',useSQLite:true}},outboundService:()=>{throw Error('Private preview fixtures cannot contact live services')}}));
  try {
    const store=(await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global'),bucket=await mf.getR2Bucket('COSMETIC_ASSETS'),first=randomBytes(32).toString('base64url'),second=randomBytes(32).toString('base64url'),uuid='a'.repeat(32),asset='c'.repeat(32),bytes=png(),hash=createHash('sha256').update(bytes).digest('hex');
    await store.setup(first,second,hash,bytes.length);await bucket.put(`capes/${asset}.png`,bytes);
    const preview=(token=first,selectedUuid=uuid,mode='',query='')=>mf.dispatchFetch(`https://preview.test/community/cosmetics/preview/${asset}?uuid=${selectedUuid}${query}`,{headers:{...(token?{Authorization:'Bearer '+token}:{}),'X-Preview-Fixture':mode}});
    const initial=await preview();assert.equal(initial.status,200);assert.equal(initial.headers.get('Cache-Control'),'private, no-store');assert.equal(initial.headers.get('Content-Type'),'image/png');assert.equal(createHash('sha256').update(Buffer.from(await initial.arrayBuffer())).digest('hex'),hash);
    assert.equal((await store.communityCosmeticOverview(first)).profile.capeAsset,null,'Preview never activates the upload');
    const publicDenied=await mf.dispatchFetch(`https://preview.test/community/cosmetics/assets/${asset}?uuid=${uuid}`);assert.equal(publicDenied.status,404,'Inactive uploads stay unavailable publicly');
    for(const [token,target,status]of[[null,uuid,401],[second,'b'.repeat(32),404],[first,'b'.repeat(32),400],[first,'bad-uuid',400]]){await store.reset();const denied=await preview(token,target);assert.equal(denied.status,status);assert.equal(await store.count(),0,'Authority failure occurs before R2 access')}
    for(const query of['&uuid='+uuid,'&accountId=preview-owner'])assert.equal((await preview(first,uuid,'',query)).status,400);
    for(const state of['reserved','writing','deleted']){await store.reset();await store.status(state);assert.equal((await preview()).status,404);assert.equal(await store.count(),0)}
    for(const mode of['revoke','blacklist','relink','owner','delete','writing']){await store.reset();const denied=await preview(first,uuid,mode);assert.equal(denied.status,404);assert.equal(await store.count(),1);assert.equal(denied.headers.get('Cache-Control'),'no-store');assert.ok(!denied.headers.get('Content-Type').startsWith('image/png'),'Post-R2 authority changes withhold private pixels')}
    await store.reset();await bucket.put(`capes/${asset}.png`,Buffer.alloc(bytes.length));assert.equal((await preview()).status,503,'Private preview still verifies stored integrity');
  } finally {await mf.dispose();}
}
