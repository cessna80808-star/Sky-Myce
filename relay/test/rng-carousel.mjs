import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { rngPresentation, rngRecentPresentation } from '../../shared/rng-presentation.mjs';

const guild = '11111111111111111', viewer = '22222222222222222', bot = '33333333333333333', channel = '44444444444444444';
const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const source = `import {AccountStore} from './src/accounts'; import {verifiedInteraction} from './src/discord';
 import {handleRngCarouselInteraction} from './src/rng-carousel'; export {AccountStore};
 export default {async fetch(request,env){const data=await verifiedInteraction(request,{DISCORD_APPLICATION_ID:env.DISCORD_USER_APPLICATION_ID,DISCORD_PUBLIC_KEY:env.DISCORD_USER_PUBLIC_KEY});
 if(!data)return new Response('Denied',{status:401});return await handleRngCarouselInteraction(data,env,new URL(request.url).pathname==='/backend')??new Response('Unsupported',{status:400})}};`;
const bundled = await build({ stdin: { contents: source, resolveDir: process.cwd(), sourcefile: 'rng-carousel-fixture.ts' },
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022', external: ['cloudflare:workers'], write: false });
const options = convertV4MiniflareOptions({ name: 'rng-carousel-check', modules: true, script: bundled.outputFiles[0].text,
  compatibilityDate: '2026-09-27', bindings: { DISCORD_GUILD_ID: guild, ADMIN_BOOTSTRAP_USER_ID: viewer,
    DISCORD_RNG_CHANNEL_ID: channel, DISCORD_USER_APPLICATION_ID: bot,
    DISCORD_USER_PUBLIC_KEY: publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('hex') },
  durableObjects: { ACCOUNT_STORE: { className: 'AccountStore', useSQLite: true } },
  outboundService: () => { throw new Error('Carousel checks never contact Discord or image hosts'); } });
options.unsafeInspectDurableObjects = true;
const mf = new Miniflare(options);
try {
  const store = (await mf.getDurableObjectNamespace('ACCOUNT_STORE')).getByName('global');
  await store.communityGetSharing('invalid');
  const sql = await mf.unsafeGetDurableObjectStorage('rng-carousel-check', 'AccountStore', { name: 'global' });
  const owner = (await store.getAccountByDiscordId(viewer)).accountId, author = 'a'.repeat(32), now = Date.now();
  await sql.exec('INSERT INTO minecraft_accounts VALUES(?,?,?,?,?)', author, owner, 'ExamplePlayer', now, now);
  const first = { id: 'b'.repeat(32), item: 'NECRON_HANDLE', player: 'Anonymous', activity: 'dungeon', occurredAt: now, unverified: true };
  const second = { ...first, id: 'c'.repeat(32), item: 'WARDEN_HEART', activity: 'slayer', occurredAt: now - 3600000 };
  for (const [index, event] of [first, second].entries()) await sql.exec('INSERT INTO community_rng_delivery VALUES(?,?,?,?,?,0,NULL,0,?,?,0)',
    event.id, author, JSON.stringify(event), now - index * 3600000, 0, channel, String(50000000000000000n + BigInt(index)));
  let sequence = 60000000000000000n;
  const interaction = (target = 'latest', flags = 0) => ({ type: 3, id: String(sequence++), application_id: bot, guild_id: guild,
    channel_id: channel, member: { user: { id: viewer }, roles: [] }, message: { author: { id: bot }, flags },
    data: { component_type: 2, custom_id: 'rng:browse:' + target } });
  const call = async (data, path = '/', tamper = false) => {
    const timestamp = String(Math.floor(Date.now() / 1000)), body = JSON.stringify(data);
    const signature = sign(null, Buffer.from(timestamp + body), privateKey).toString('hex');
    const response = await mf.dispatchFetch('https://fixture.example' + path, { method: 'POST', body: tamper ? body + ' ' : body,
      headers: { 'Content-Type': 'application/json', 'X-Signature-Timestamp': timestamp, 'X-Signature-Ed25519': signature } });
    return { status: response.status, data: response.status === 200 ? await response.json() : null };
  };
  const initial = (await call(interaction())).data;
  assert.equal(initial.type, 4); assert.equal(initial.data.flags, 64, 'Browse opens privately without changing the published message');
  assert.equal(initial.data.embeds[0].title, "Necron's Handle");
  assert.match(initial.data.embeds[0].thumbnail.url, /^https:\/\/hypixelskyblock\.minecraft\.wiki\/images\//);
  assert.equal(initial.data.components[0].components[0].disabled, true);
  assert.equal(initial.data.components[0].components[1].custom_id, 'rng:browse:' + second.id);
  assert.ok(!JSON.stringify(initial).includes(author)); assert.ok(!JSON.stringify(initial).includes(owner));
  const next = (await call(interaction(second.id, 64))).data;
  assert.equal(next.type, 7); assert.equal(next.data.embeds[0].title, 'Warden Heart');
  assert.equal(next.data.components[0].components[1].disabled, true);
  const refreshed = (await call(interaction('latest', 64))).data;
  assert.equal(refreshed.type, 7, 'Refresh updates the existing private carousel');
  assert.equal(refreshed.data.embeds[0].title, "Necron's Handle");
  assert.equal(rngPresentation(second, now), null, 'Historical browsing cannot publish stale acquisitions');
  assert.ok(rngRecentPresentation(second, now));
  assert.ok(!rngPresentation(first, now, true).components, 'Administrator fixtures never expose production browsing');
  assert.equal(rngRecentPresentation({ ...second, occurredAt: now - 604800001 }, now), null);
  const unknown = rngPresentation({ ...first, item: 'FUTURE_SUPPORTED_ITEM' }, now);
  assert.match(unknown.embeds[0].thumbnail.url, /Enchanted_Diamond\.png/);
  assert.ok(!unknown.embeds[0].fields.some(field => field.name.startsWith('Observed')), 'Missing rarity or floor is not invented');
  assert.equal((await call(interaction(), '/', true)).status, 401);
  for (const data of [interaction(second.id), { ...interaction(), guild_id: '99999999999999999' },
    { ...interaction(), channel_id: '99999999999999999' }, { ...interaction(), message: { author: { id: viewer } } },
    { ...interaction(), member: null }]) assert.ok(!(await call(data)).data.data.embeds, 'Invalid audience never receives a carousel');
  assert.ok(!(await call(interaction(), '/backend')).data.data.embeds, 'Backend Bot cannot operate User Bot controls');
  const directActor = { id: viewer, guildId: guild, roles: [], interactionId: String(sequence++) };
  assert.equal((await store.discordRecentRng({ ...directActor, guildId: '99999999999999999' }, channel)).ok, false);
  assert.equal((await store.discordRecentRng(directActor, '99999999999999999')).ok, false);
  await sql.exec('UPDATE community_rng_delivery SET event=? WHERE id=?', JSON.stringify({ ...first, player: 'ExamplePlayer' }), first.id);
  assert.equal((await call(interaction())).data.data.embeds[0].title, 'Warden Heart', 'Current name privacy is rechecked on every page');
  await sql.exec('UPDATE community_rng_delivery SET suppressed=1 WHERE id=?', second.id);
  const removed = (await call(interaction(second.id, 64))).data;
  assert.equal(removed.type, 7); assert.deepEqual(removed.data.embeds, [], 'Retracted drops disappear from existing carousel pages');
  assert.match(removed.data.content, /no longer shared/);
  assert.equal((await sql.exec('SELECT COUNT(*) n FROM community_contributions'))[0].n, 0, 'Browsing creates no statistics or acquisitions');
  assert.equal((await sql.exec('SELECT COUNT(*) n FROM community_rng_delivery'))[0].n, 2, 'Browsing does not replace delivery/deletion receipts');
  console.log('RNG carousel checks passed: signed audience, private navigation, retention, privacy, retraction, artwork and publication isolation.');
} finally { await mf.dispose(); }
