import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { build } from 'esbuild';

async function load(entry) {
  const result = await build({ entryPoints: [new URL(`../src/${entry}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')],
    bundle: true, format: 'esm', platform: 'browser', write: false });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
}
const { validGreenhouseUpload, nextGreenhouseDm, unsentEvents, greenhouseView, greenhouseDmText, GreenhouseReminders, handleGreenhouseInteraction, GREENHOUSE_LIMITS, greenhouseGap } =
  await load('greenhouse-reminders.ts');

/** Durable Object SqlStorage over node:sqlite, with rows read/written counted like Cloudflare bills them. */
function sqlStorage() {
  const db = new DatabaseSync(':memory:'), usage = { read: 0, written: 0 };
  return { usage, exec(sql, ...params) {
    if (!params.length && sql.trim().split(';').filter(Boolean).length > 1) { db.exec(sql); return { toArray: () => [], one: () => undefined }; }
    const statement = db.prepare(sql);
    let rows = [];
    if (/^\s*SELECT/i.test(sql)) { rows = statement.all(...params).map(row => ({ ...row })); usage.read += Math.max(1, rows.length); }
    else usage.written += Number(statement.run(...params).changes);
    return { toArray: () => rows, one: () => rows[0] };
  } };
}

const now = 1_800_000_000_000, hour = 3_600_000, id = 'a'.repeat(32), discord = '12345678901234567';
const plot = { name: '2', tickAt: now - 600_000, cycleMs: 2 * hour, plants: ['COALROOT'], targets: [[22, 1, 0, 5, 8, now + 6 * hour, 0, 0], [26, 1, 0, 8, 8, now - hour, 0, 0]] };
const ready = { plot: '2', kind: 'ready', at: now + 6 * hour, count: 11, total: 22 };
const upload = (events, extra = {}) => ({ type: 'greenhouse_schedule', id, enabled: true, events, plots: [plot], ...extra });

test('quiet hours hold a DM until they end, also across midnight', () => {
  // now is 08:00 UTC.
  const due = [{ ...ready, at: now }];
  assert.equal(nextGreenhouseDm(due, [], now, [420, 600]).at, now + 2 * hour);
  assert.equal(nextGreenhouseDm(due, [], now, [1380, 540]).at, now + hour);
  assert.equal(nextGreenhouseDm(due, [], now, [600, 700]).at, now);
  assert.equal(nextGreenhouseDm(due, [], now).at, now);
  const later = { ...ready, kind: 'water', at: now + 90 * 60_000 };
  assert.deepEqual(nextGreenhouseDm([...due, later], [], now, [420, 600]).events.map(e => e.kind), ['ready', 'water'], 'events due meanwhile ride along');
  assert.deepEqual(validGreenhouseUpload(upload([ready], { quiet: [1380, 480] }), now).quiet, [1380, 480]);
  for (const quiet of [[0, 0], [0, 1440], [1], 'night']) assert.equal(validGreenhouseUpload(upload([ready], { quiet }), now), null);
});

test('uploads are checked strictly at the trust boundary', () => {
  assert.deepEqual(validGreenhouseUpload(upload([ready]), now), { enabled: true, events: [ready], plots: [plot] });
  assert.deepEqual(validGreenhouseUpload({ type: 'greenhouse_schedule', id, enabled: false }, now), { enabled: false });
  for (const bad of [upload([ready], { extra: 1 }), upload([ready, ready]), upload(Array(10).fill(ready)), upload([{ ...ready, kind: 'sleep' }]), upload([{ ...ready, at: now + 15 * 24 * hour }]),
    upload([{ ...ready, count: 23 }]), upload([{ ...ready, plot: 'a b' }]), upload([ready], { id: 'x' }),
    upload([ready], { plots: [{ ...plot, targets: [[22, 1, 1, 5, 8, 0, 0, 0]] }] }), upload([ready], { plots: [{ ...plot, cycleMs: 60_000 }] })])
    assert.equal(validGreenhouseUpload(bad, now), null);
});

test('DMs coalesce within 30 minutes, keep 2 hours apart and stop at 8 a day, and are never early', () => {
  const at = (minutes, kind = 'ready', plot = '1') => ({ plot, kind, at: now + minutes * 60_000, count: 1, total: 2 });
  assert.equal(nextGreenhouseDm([at(0), at(20, 'water')], [], now).at, now + 20 * 60_000, 'one DM, when the later one is due');
  assert.equal(nextGreenhouseDm([at(0), at(20, 'water')], [], now).events.length, 2);
  assert.deepEqual(nextGreenhouseDm([at(0), at(40, 'water')], [], now).events.map(e => e.kind), ['ready'], 'outside the window it waits');
  assert.equal(nextGreenhouseDm([at(0)], [now - hour], now).at, now + hour, '2 hours after the last DM');
  const eight = [23, 20, 17, 14, 11, 8, 5, 2].map(h => now - h * hour);
  assert.equal(nextGreenhouseDm([at(0)], eight, now).at, now + hour, 'the ninth waits a day after the oldest');
  assert.deepEqual(nextGreenhouseDm([at(0), at(100, 'water')], [now - 90 * 60_000], now).events.length, 1, 'held back DMs carry only what is due');
  assert.equal(nextGreenhouseDm([], [], now), null);
  assert.equal(greenhouseGap([plot]), 4 * hour, 'two growth ticks of 2 hours');
  assert.equal(greenhouseGap([]), 2 * hour);
  assert.equal(nextGreenhouseDm([at(0)], [now - hour], now, undefined, greenhouseGap([plot])).at, now + 3 * hour, 'the next DM waits two ticks after the last');
  assert.equal(GREENHOUSE_LIMITS.perDay, 8);
});

test('an event already sent is not sent again when the mod re-uploads it', () => {
  const fired = [{ plot: '2', kind: 'ready', at: now + 6 * hour }];
  assert.deepEqual(unsentEvents([{ ...ready, at: now + 7 * hour }], fired, now), []);
  assert.equal(unsentEvents([{ ...ready, at: now + 30 * hour }], fired, now).length, 1, 'the next growth is new');
  assert.equal(unsentEvents([{ ...ready, kind: 'water' }], fired, now).length, 1);
});

test('one row per account: unchanged uploads write nothing, a due DM is one read and one write', async () => {
  const sql = sqlStorage(), store = new GreenhouseReminders(sql, () => 'token');
  const parsed = validGreenhouseUpload(upload([ready]), now);
  sql.usage.read = sql.usage.written = 0;
  assert.deepEqual(store.store(discord, parsed, now), { result: 'stored', next: now }, 'turning reminders on sends a welcome first');
  assert.equal(sql.usage.written, 1);
  assert.equal(store.store(discord, parsed, now + 10 * 60_000).result, 'unchanged');
  assert.equal(sql.usage.written, 1, 'nothing written for the same schedule');
  assert.equal(store.store(discord, validGreenhouseUpload(upload([{ ...ready, at: ready.at + 1 }]), now), now + 30_000).result, 'throttled');
  assert.equal(store.nextDue(), now);

  const posts = [], deletes = [];
  globalThis.fetch = async (url, init) => {
    if (init.method === 'DELETE') { deletes.push(String(url)); return new Response(null, { status: 204 }); }
    posts.push({ url: String(url), body: JSON.parse(init.body) });
    return new Response(JSON.stringify({ id: String(url).endsWith('/users/@me/channels') ? '76543210987654321' : `9876543210987654${posts.length}` }), { status: 200 });
  };
  await store.deliver(now);
  assert.equal(posts.length, 2);
  assert.match(posts[1].body.content, /reminders are on/);
  assert.deepEqual(posts[1].body.components[0].components.map(c => c.custom_id), ['greenhouse:view', 'greenhouse:dismiss']);
  assert.equal(store.nextDue(), ready.at, 'the welcome is sent once');
  posts.length = 0;
  await store.deliver(ready.at - 1);
  assert.equal(posts.length, 0, 'nothing before it is due');
  sql.usage.read = sql.usage.written = 0;
  await store.deliver(ready.at);
  assert.equal(posts.length, 2);
  assert.match(posts[1].body.content, /Plot 2: 11 of 22 Coalroot mutations ready/);
  assert.equal(posts[1].body.components[0].components[0].custom_id, 'greenhouse:view');
  assert.deepEqual(posts[1].body.allowed_mentions, { parse: [] });
  assert.equal(sql.usage.written, 1);
  assert.equal(store.nextDue(), now + GREENHOUSE_LIMITS.messageTtlMs, 'next: deleting the welcome a day later');

  assert.equal(store.store(discord, validGreenhouseUpload(upload([{ ...ready, at: ready.at + hour }]), ready.at), ready.at + 2 * 60_000).result, 'unchanged',
    'the same ready event is not pinged twice, and its re-upload writes nothing');
  const water = { plot: '2', kind: 'water', at: ready.at + 30 * 60_000, count: 1, total: 22 };
  assert.equal(store.store(discord, validGreenhouseUpload(upload([water]), ready.at), ready.at + 4 * 60_000).next, ready.at + 4 * hour, 'the next DM waits two growth ticks');

  await store.deliver(now + GREENHOUSE_LIMITS.messageTtlMs);
  assert.deepEqual(deletes, ['https://discord.com/api/v10/channels/76543210987654321/messages/98765432109876542'], 'the welcome is deleted after a day, the reminder not yet');
  assert.equal(store.nextDue(), ready.at + GREENHOUSE_LIMITS.messageTtlMs);

  const view = store.view(discord, now);
  assert.match(view.embeds[0].description, /🟨/);
  assert.match(view.embeds[0].description, /🟩/);
  assert.equal(store.store(discord, { enabled: false }, now).result, 'deleted');
  assert.equal(store.view(discord, now), null);
});

test('a reminder due while the player is in game is left to the mod, not DMed', async () => {
  let online = true;
  const sql = sqlStorage(), store = new GreenhouseReminders(sql, () => 'token', () => online);
  store.store(discord, validGreenhouseUpload(upload([ready]), now), now);
  const posts = [];
  globalThis.fetch = async (url, init) => { posts.push(JSON.parse(init.body)); return new Response(JSON.stringify({ id: '76543210987654321' }), { status: 200 }); };
  await store.deliver(now);
  assert.match(posts.at(-1).content, /reminders are on/);
  posts.length = 0;
  await store.deliver(ready.at);
  assert.equal(posts.length, 0);
  online = false;
  await store.deliver(ready.at + hour);
  assert.equal(posts.length, 0, 'it counts as shown and is not sent later');
});

test('a failed DM is retried once, then dropped', async () => {
  const sql = sqlStorage(), store = new GreenhouseReminders(sql, () => 'token');
  store.store(discord, validGreenhouseUpload(upload([ready]), now), now);
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response('{}', { status: 500 }); };
  await store.deliver(ready.at);
  assert.equal(store.nextDue(), ready.at + GREENHOUSE_LIMITS.retryMs);
  await store.deliver(ready.at + GREENHOUSE_LIMITS.retryMs);
  assert.equal(store.nextDue(), null);
  assert.equal(calls, 3, 'the welcome and the reminder, then its one retry');
});

test('a wake reminder names the mutations that stop then', () => {
  const sleepy = { ...plot, plants: ['COALROOT', 'SNOOZLING'], targets: [...plot.targets, [44, 3, 1, 3, 20, 0, 0, now + 2 * hour]] };
  const parsed = validGreenhouseUpload(upload([{ plot: '2', kind: 'wake', at: now + 2 * hour, count: 1, total: 3 }], { plots: [sleepy] }), now);
  assert.ok(parsed);
  assert.match(greenhouseDmText(parsed.events, parsed.plots), /💤 Plot 2: a mutation stops growing until you act on it \(Snoozling\)/);
  assert.match(greenhouseView([sleepy], now, now + 3 * hour).embeds[0].description, /Snoozling\*\*: 1 need you/);
});

test('the view projects growth with the shared growth tick', () => {
  const view = greenhouseView([plot], now, now + 4 * hour);
  assert.match(view.embeds[0].description, /Coalroot\*\*: 1 ready · 1 growing \(stage ~7\/8\)/);
  assert.match(view.content, /ready/);
  const planned = { ...plot, targets: [...plot.targets, [55, 1, 0, -1, 8, 0, 0, 0]] };
  assert.ok(validGreenhouseUpload(upload([ready], { plots: [planned] }), now), 'a planned spot with nothing grown yet is accepted');
  assert.equal(validGreenhouseUpload(upload([ready], { plots: [{ ...plot, targets: [[55, 1, 0, -2, 8, 0, 0, 0]] }] }), now), null);
  // Row 1, column 2 is drawn like the in-game screen: rows left to right, so the third line, second square.
  const lines = greenhouseView([{ ...plot, targets: [[12, 1, 0, 5, 8, now + 6 * hour, 0, 0]] }], now, now).embeds[0].description.split('\n');
  assert.equal(Array.from(lines[2])[1], '🟨');
  assert.equal(Array.from(lines[1])[2], '⬛');
  const empty = greenhouseView([planned], now, now + 4 * hour).embeds[0].description;
  assert.match(empty, /🟫/);
  assert.match(empty, /1 not grown yet/);
});

test('the button only answers its own custom id and a real caller', async () => {
  assert.equal(await handleGreenhouseInteraction({ type: 3, data: { custom_id: 'rng:browse:latest' } }, {}, false), null);
  const env = { DISCORD_USER_APPLICATION_ID: '11111111111111111', ACCOUNT_STORE: { getByName: () => ({ greenhouseView: async () => greenhouseView([plot], now, now) }) } };
  const press = { type: 3, data: { custom_id: 'greenhouse:view', component_type: 2 }, user: { id: discord }, message: { author: { id: '11111111111111111' } } };
  const shown = await (await handleGreenhouseInteraction(press, env, false)).json();
  assert.equal(shown.type, 4);
  assert.equal(shown.data.flags, 64);
  assert.equal(shown.data.components[0].components[0].label, 'Refresh');
  const forged = await (await handleGreenhouseInteraction({ ...press, message: { author: { id: '22222222222222222' } } }, env, false)).json();
  assert.match(forged.data.content, /unavailable/);

  const deletes = [];
  globalThis.fetch = async (url, init) => { deletes.push([init.method, String(url)]); return new Response(null, { status: 204 }); };
  const dismiss = { ...press, data: { custom_id: 'greenhouse:dismiss', component_type: 2 }, channel_id: '76543210987654321', message: { ...press.message, id: '98765432109876543' } };
  assert.equal((await (await handleGreenhouseInteraction(dismiss, { ...env, USER_BOT_TOKEN: 'token' }, false)).json()).type, 6);
  assert.deepEqual(deletes, [['DELETE', 'https://discord.com/api/v10/channels/76543210987654321/messages/98765432109876543']]);
  assert.match((await (await handleGreenhouseInteraction({ ...dismiss, guild_id: '1' }, { ...env, USER_BOT_TOKEN: 'token' }, false)).json()).data.content, /unavailable/,
    'only a DM can be dismissed');
  assert.equal(deletes.length, 1);
});
