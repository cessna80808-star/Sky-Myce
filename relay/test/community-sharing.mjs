import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

/** Run the production policy/cache helpers on actual Durable Object SQLite without any external service. */
export async function checkCommunitySharing() {
  const source = `
    import {DurableObject} from 'cloudflare:workers';
    import {CommunitySharingStore} from './src/community-sharing.ts';
    import {SharedStats} from './src/stats.ts';
    import {SharedWealth} from './src/wealth.ts';
    import {DigestRng} from './src/digest.ts';
    export class SharingFixture extends DurableObject {
      constructor(ctx,env) { super(ctx,env); this.sql=ctx.storage.sql;
        this.sql.exec('CREATE TABLE IF NOT EXISTS fixture_owners(uuid TEXT PRIMARY KEY,account_id TEXT); CREATE TABLE IF NOT EXISTS fixture_blacklist(uuid TEXT PRIMARY KEY)');
        const owner=uuid=>{const account=this.sql.exec('SELECT account_id FROM fixture_owners WHERE uuid=?',uuid).toArray()[0];return account?{accountId:account.account_id,uuids:this.sql.exec('SELECT uuid FROM fixture_owners WHERE account_id=?',account.account_id).toArray().map(r=>r.uuid)}:null};
        this.sharing=new CommunitySharingStore(this.sql,{owner,session:input=>({ok:input.sessionId==='fixture-session'&&input.generation===0&&owner(input.uuid)?.accountId===input.accountId,uuid:input.uuid,accountId:input.accountId}),blacklisted:uuid=>this.sql.exec('SELECT uuid FROM fixture_blacklist WHERE uuid=?',uuid).toArray().length>0});
        this.stats=new SharedStats(this.sql);this.wealth=new SharedWealth(this.sql);this.rng=new DigestRng(this.sql);
      }
      seed(uuid,account) {this.sql.exec('INSERT OR REPLACE INTO fixture_owners VALUES(?,?)',uuid,account)}
      blacklist(uuid){this.sql.exec('INSERT OR IGNORE INTO fixture_blacklist VALUES(?)',uuid)}
      get(input){return this.sharing.get(input)}
      getVerified(uuid){return this.sharing.getVerified(uuid)}
      decision(uuid,category,at){return this.sharing.check(uuid,category,at)}
      update(input,revision,policy){return this.ctx.storage.transactionSync(()=>{const r=this.sharing.update(input,revision,policy);if(r.ok)this.purge(r);return r})}
      updateVerified(uuid,revision,policy){return this.ctx.storage.transactionSync(()=>{const r=this.sharing.updateVerified(uuid,revision,policy);if(r.ok)this.purge(r);return r})}
      purge(r){for(const c of r.suppressedCategories){if(c==='dungeons')this.stats.deleteSubjects(r.affectedUuids);if(c==='economy')this.wealth.deleteSubjects(r.affectedUuids);if(c==='rng')this.rng.deleteAuthors(r.affectedUuids)}}
      merge(uuid,source,target){this.ctx.storage.transactionSync(()=>{if(source)this.sharing.mergeAccount(source,target);this.sharing.mergeUuid(uuid,target);this.seed(uuid,target)})}
      detach(uuid,source){this.ctx.storage.transactionSync(()=>{this.sharing.detachUuid(uuid,source);this.sql.exec('DELETE FROM fixture_owners WHERE uuid=? AND account_id=?',uuid,source)})}
      cache(uuid,name,publisher){const now=Date.now();this.stats.put({uuid,name,stats:{state:'AVAILABLE',classes:{},completionTimes:{},sPlusTimes:{},completedFloors:[]},fetchedAt:now},publisher,now);this.wealth.put({uuid,name,wealth:{hasProfile:true,networth:1},fetchedAt:now},now,publisher)}
      rngPublish(uuid,name,id,at,showName){return this.rng.publish(uuid,name,{type:'rng_publish',id:'1'.repeat(32),showName,event:{id,item:'NECRON_HANDLE',activity:'dungeon',occurredAt:at}},Date.now())}
      history(){return this.rng.recentRecords(Date.now()).filter(r=>this.sharing.check(r.author,'rng',r.event.occurredAt).allowed).map(r=>({...r.event,player:this.sharing.check(r.author,'rng').publicName?r.event.player:'Anonymous'}))}
      counts(uuid){return {stats:this.sql.exec('SELECT uuid FROM player_stats WHERE uuid=?',uuid).toArray().length,wealth:this.sql.exec('SELECT uuid FROM player_wealth WHERE uuid=?',uuid).toArray().length,contributions:this.sql.exec('SELECT uuid FROM community_contributions WHERE uuid=?',uuid).toArray().length}}
      contribute(uuid,value){return this.sharing.recordContribution(uuid,value,'Alice')}
      ageContributions(){this.sql.exec('UPDATE community_contributions SET received_at=?',Date.now()-40*86400000)}
    }
    export default {fetch(){return new Response('test fixture')}};
  `;
  const bundle = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: "sharing-fixture.ts" }, bundle: true,
    format: "esm", platform: "browser", external: ["cloudflare:workers"], tsconfigRaw: {}, write: false,
    plugins: [{ name: "fixture-sources", setup(build) {
      build.onResolve({ filter: /^\.\/src\/.*\.ts$/ }, args => ({ path: args.path.slice(6), namespace: "fixture" }));
      build.onLoad({ filter: /.*/, namespace: "fixture" }, async args => ({
        contents: await readFile(new URL(`../src/${args.path}`, import.meta.url), "utf8"), loader: "ts"
      }));
    } }] });
  const options = convertV4MiniflareOptions({ name: "sharing-check", modules: true, script: bundle.outputFiles[0].text,
    compatibilityDate: "2026-09-27", durableObjects: { SHARING_TEST: { className: "SharingFixture", useSQLite: true } },
    outboundService: () => { throw Error("Sharing checks must stay offline"); } });
  const mf = new Miniflare(options);
  try {
    const store = (await mf.getDurableObjectNamespace("SHARING_TEST")).getByName("global");
    const alice = "a".repeat(32), alt = "b".repeat(32), carol = "c".repeat(32), anonymous = "d".repeat(32);
    const session = (uuid = alice, accountId = "alice-account") => ({ sessionId: "fixture-session", accountId, uuid, generation: 0 });
    const defaults = { version: 1, enabled: true, dungeons: true, economy: true, rng: true, activity: true, leaderboards: true, publicName: false };
    await store.seed(alice, "alice-account"); await store.seed(alt, "alice-account"); await store.seed(carol, "carol-account");
    assert.deepEqual((await store.get(session())).policy, { ...defaults, revision: 0, updatedAt: 0, notBefore: { dungeons: 0, economy: 0, rng: 0, activity: 0, leaderboards: 0 } });
    assert.equal((await store.get({ ...session(), generation: 1 })).error, "not_authorized");
    assert.equal((await store.update({ ...session(), accountId: "carol-account" }, 0, defaults)).error, "not_authorized");
    for (const malformed of [{ ...defaults, rng: "false" }, { ...defaults, unknown: true }, { ...defaults, version: 2 },
      Object.fromEntries(Object.entries(defaults).filter(([key]) => key !== "enabled"))]) assert.equal((await store.update(session(), 0, malformed)).error, "invalid_sharing_policy");
    await store.cache(alice, "Alice", alice); await store.cache(alt, "Bob", alice); await store.cache(carol, "Carol", alice);
    const drop = await store.rngPublish(alice, "Alice", "e".repeat(32), Date.now() - 100, true);
    assert.ok(drop.stored); assert.equal((await store.history())[0].player, "Anonymous", "Server display consent overrides a client's showName");
    const contribution = { id: "f".repeat(32), metric: "dungeon_runs", value: 1, observedAt: Date.now(), complete: true, source: "client-observation", verification: "unverified" };
    assert.equal((await store.contribute(alice, contribution)).ok, true);
    await store.ageContributions();
    assert.equal((await store.contribute(alice, { ...contribution, id: "9".repeat(32) })).ok, true);
    assert.equal((await store.counts(alice)).contributions, 2, "All-time observations are retained beyond a month");
    assert.equal((await store.contribute(alice, contribution)).ok, true);
    assert.equal((await store.counts(alice)).contributions, 2, "Duplicate event IDs remain idempotent");
    await store.merge(alice, "alice-account", "alice-account");
    assert.equal((await store.counts(alice)).contributions, 2, "Refreshing/reconnecting the same owner preserves recorded contributions");
    for (const changes of [{ value: null }, { value: 0.5 }, { complete: false }, { metric: "__proto__" }, { verification: "verified" }, { source: "server" }]) {
      assert.equal((await store.contribute(alice, { ...contribution, ...changes })).error, "invalid_contribution");
    }
    const disabledRng = await store.update(session(), 0, { ...defaults, rng: false });
    assert.equal(disabledRng.ok, true); assert.deepEqual(new Set(disabledRng.affectedUuids), new Set([alice, alt]));
    assert.equal((await store.decision(alt, "rng")).allowed, false, "Preferences cover every MC identity on the Discord account");
    assert.equal((await store.decision(alice, "economy")).allowed, true); assert.equal((await store.history()).length, 0);
    assert.equal((await store.update(session(), 0, defaults)).error, "sharing_conflict");
    const reenabled = await store.update(session(), disabledRng.policy.revision, defaults);
    assert.equal((await store.decision(alice, "rng", drop.event.occurredAt)).allowed, false, "Reenable cannot redistribute prior history");
    assert.equal((await store.decision(alice, "rng", reenabled.policy.notBefore.rng + 1)).allowed, true);
    const stopped = await store.update(session(), reenabled.policy.revision, { ...defaults, enabled: false });
    assert.equal((await store.counts(alice)).stats, 0); assert.equal((await store.counts(alt)).wealth, 0);
    assert.equal((await store.counts(carol)).stats, 0, "Contributor removal also removes their third-party cache publications");
    assert.equal((await store.counts(alice)).contributions, 0);
    assert.equal((await store.contribute(alice, { ...contribution, observedAt: Date.now() + 1 })).error, "sharing_disabled");
    assert.equal((await store.decision(carol, "rng")).allowed, true, "No unrelated identity's settings changed");
    await store.merge(alice, "alice-account", "carol-account");
    assert.equal((await store.get(session(alice, "carol-account"))).policy.enabled, false, "Reassignment inherits explicit old opt-outs");
    assert.equal((await store.get(session(alt))).policy.enabled, false, "Source preferences remain for its other Minecraft accounts");
    const anon = await store.updateVerified(anonymous, 0, { ...defaults, economy: false });
    assert.equal(anon.ok, true);
    assert.equal((await store.contribute(anonymous, { ...contribution, id: "8".repeat(32), observedAt: Date.now() })).ok, true);
    await store.merge(anonymous, null, "new-account");
    assert.equal((await store.counts(anonymous)).contributions, 0, "First linking still clears contributions from the old UUID owner");
    assert.equal((await store.get(session(anonymous, "new-account"))).policy.economy, false, "A UUID opt-out survives first Discord linking");
    const transferred = "8".repeat(32);
    await store.seed(transferred, "transfer-source");
    assert.equal((await store.contribute(transferred, { ...contribution, id: "7".repeat(32), observedAt: Date.now() })).ok, true);
    await store.merge(transferred, "transfer-source", "transfer-destination");
    assert.equal((await store.counts(transferred)).contributions, 0, "Actual reassignment still removes old-owner contributions even when both owners consent");
    await store.blacklist(carol); assert.equal((await store.decision(carol, "rng")).allowed, false);
    assert.equal((await store.get(session(carol, "carol-account"))).error, "not_authorized");
    // The CAS is checked against persistent SQLite, including after a reconnect/reconstructed policy helper.
    const racers = await Promise.all([store.update(session(anonymous, "new-account"), 1, defaults), store.update(session(anonymous, "new-account"), 1, { ...defaults, rng: false })]);
    assert.equal(racers.filter(result => result.ok).length, 1); assert.equal(racers.filter(result => result.error === "sharing_conflict").length, 1);
    assert.equal(stopped.suppressedCategories.length, 5);
    const detached = "7".repeat(32), sibling = "6".repeat(32);
    await store.seed(detached, "detach-account"); await store.seed(sibling, "detach-account");
    const detachPolicy = await store.update(session(detached, "detach-account"), 0, { ...defaults, rng: false, economy: false });
    await store.detach(detached, "wrong-account");
    assert.equal((await store.get(session(detached, "detach-account"))).ok, true, "Wrong ownership cannot detach an unrelated identity");
    await store.detach(detached, "detach-account");
    const fallback = await store.getVerified(detached);
    assert.equal(fallback.policy.rng, false); assert.equal(fallback.policy.economy, false);
    assert.ok(fallback.policy.notBefore.rng >= detachPolicy.policy.notBefore.rng);
    assert.equal((await store.decision(detached, "rng")).allowed, false, "Unlink cannot regain enabled UUID defaults");
    assert.equal((await store.get(session(sibling, "detach-account"))).policy.rng, false, "Source sibling consent is preserved");
    await store.merge(detached, null, "detached-new-account");
    assert.equal((await store.get(session(detached, "detached-new-account"))).policy.rng, false, "A new owner inherits the detached UUID's explicit opt-out");
    assert.equal((await store.get(session(detached, "detached-new-account"))).policy.economy, false);
    console.info("Community sharing checks passed: defaults, identity authority, account inheritance, CAS, suppression, measured contributions and privacy deletion");
  } finally { await mf.dispose(); }
}

if (process.argv[1]?.endsWith("community-sharing.mjs")) await checkCommunitySharing();
