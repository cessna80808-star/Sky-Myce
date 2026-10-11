import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { bridgeHeaders, discordPacket, minecraftContent, validText, RelayBridge } from '../src/relay.mjs';
import { backendCommands, promptCommands, userCommands } from '../src/backend-bot.mjs';
import { registerCommands, registrationPlan } from '../src/registration.mjs';

test('bridge handshake signs room, timestamp and nonce independently of the Discord bot token', () => {
  const headers = bridgeHeaders('a'.repeat(32), 'testing', '1800000000000', 'b'.repeat(32));
  assert.equal(headers['X-SkyMyce-Signature'], createHmac('sha256', 'a'.repeat(32)).update(`sky-myce-bridge\ntesting\n1800000000000\n${'b'.repeat(32)}`).digest('hex'));
  assert.notEqual(headers['X-SkyMyce-Signature'], bridgeHeaders('a'.repeat(32), 'friends', '1800000000000', 'b'.repeat(32))['X-SkyMyce-Signature']);
  assert.throws(() => new RelayBridge({ url: 'http://example.com', room: 'testing', secret: 'x' }));
});
test('Gateway identity is authoritative; bots, webhooks, wrong guilds and other rooms are ignored', () => {
  const message = { id: '11111111111111111', guildId: 'guild', channelId: 'test', content: 'hello', author: { id: '22222222222222222', username: 'friend', bot: false } };
  assert.deepEqual(discordPacket(message, 'guild', 'test'), { type: 'discord_message', id: message.id, message_id: message.id, source: 'discord', room: 'friends', discordId: message.author.id, from: 'friend', text: 'hello' });
  for (const overrides of [{ guildId: 'other' }, { channelId: 'production' }, { webhookId: 'hook' }, { author: { ...message.author, bot: true } }, { content: '§cspoof' }, { content: 'x'.repeat(257) }, { id: 'invalid' }]) {
    assert.equal(discordPacket({ ...message, ...overrides }, 'guild', 'test'), null);
  }
});
test('Minecraft display cannot mention anyone or loop Discord messages back into the channel', () => {
  const packet = { type: 'irc_message', id: 'a'.repeat(32), message_id: 'a'.repeat(32), room: 'friends', source: 'minecraft', from: 'Steve', text: '@everyone **hello**' };
  const content = minecraftContent(packet);
  assert.equal(content.content, undefined);
  assert.equal(content.embeds[0].author.name, 'Steve');
  assert.equal(content.embeds[0].description, '@everyone \\*\\*hello\\*\\*');
  assert.deepEqual(content.allowedMentions, { parse: [], repliedUser: false });
  assert.equal(minecraftContent({ ...packet, source: 'discord' }), null);
  assert.equal(minecraftContent({ ...packet, from: '[MC] Other' }), null);
  assert.equal(minecraftContent({ ...packet, id: '' }), null);
  for (const text of ['', ' ', '\n', '\u007f', '\u202e', '§']) assert.equal(validText(text), false);
  assert.equal(validText('😀'.repeat(128)), true);
  assert.equal(validText('😀'.repeat(129)), false);
});
test('backend commands are disabled by default and cannot leak into the user command registry', () => {
  assert.ok(backendCommands.every(command => command.default_member_permissions === '0' && command.contexts.length === 1 && command.contexts[0] === 0));
  for (const command of backendCommands.find(command => command.name === 'hwid').options) {
    const reason = command.options.find(option => option.name === 'reason');
    assert.equal(reason.type, 3); assert.equal(reason.required, false); assert.equal(reason.max_length, 500);
  }
  assert.deepEqual(userCommands.map(command => command.name), ['status', 'key', 'loader', 'help', 'account', 'hwid', 'cosmetics']);
  assert.ok(userCommands.find(command => command.name === 'account').options.some(command => command.name === 'approve'));
  assert.ok(userCommands.find(command => command.name === 'hwid').options.every(command => !command.options.some(option => option.name === 'reason')));
});

test('licensing UX schemas use native users, scoped account autocomplete and useful bounded descriptions', () => {
  const license = backendCommands.find(command => command.name === 'license');
  for (const name of ['whitelist', 'key', 'info', 'debug', 'revoke', 'restore', 'extend', 'reset-hardware', 'force-logout', 'uuids', 'uuid-remove', 'audit', 'sessions']) {
    const sub = license.options.find(option => option.name === name);
    assert.ok(sub, `Missing /license ${name}`);
    const user = sub.options.find(option => option.name === 'user');
    assert.equal(user.type, 6); assert.equal(user.required, true, `/license ${name} selects its target through Discord`);
  }
  const account = license.options.find(option => option.name === 'uuid-remove').options.find(option => option.name === 'account');
  assert.equal(account.type, 3); assert.equal(account.required, true); assert.equal(account.autocomplete, true);
  assert.equal(account.max_length, 32);
  for (const name of ['revoke', 'reset-hardware', 'force-logout', 'uuid-remove']) {
    assert.ok(!license.options.find(option => option.name === name).options.some(option => option.name === 'confirm'), 'The native confirmation UI handles destructive approval');
  }
  const management = backendCommands.find(command => command.name === 'skyhawk');
  assert.ok(management.options.some(option => option.name === 'overview'));
  const eventId = management.options.find(option => option.name === 'event').options.find(option => option.name === 'id');
  assert.equal(eventId.type, 4); assert.equal(eventId.required, true); assert.equal(eventId.min_value, 1);
  assert.equal(management.options.find(option => option.name === 'search').options.find(option => option.name === 'query').autocomplete, true);
  const fallback = management.options.find(option => option.name === 'lookup').options.find(option => option.name === 'discord-id');
  assert.equal(fallback.type, 3); assert.equal(fallback.max_length, 20); assert.equal(fallback.min_length, 17);
  const builds = backendCommands.find(command => command.name === 'build');
  for (const name of ['revoke', 'restore']) assert.equal(builds.options.find(option => option.name === name).options.find(option => option.name === 'build').autocomplete, true);
  for (const name of ['status', 'key', 'loader', 'help']) {
    const user = userCommands.find(command => command.name === name);
    assert.ok(user); assert.ok(!user.options?.length); assert.equal(user.default_member_permissions, undefined);
  }
  const descriptions = entries => { for (const entry of entries) {
    if (entry.description !== undefined) assert.ok(entry.description.trim().length > 0 && entry.description.length <= 100);
    if (entry.options) descriptions(entry.options);
  } };
  descriptions([...backendCommands, ...userCommands]);
});

test('prompt commands accept inline text or an attachment and register individually without altering old commands', async () => {
  assert.deepEqual(promptCommands.map(({ name, type }) => ({ name, type })), [
    ...['prompt', 'prompt-status', 'prompt-cancel', 'prompt-usage', 'prompt-runner', 'prompt-pause', 'prompt-resume'].map(name => ({ name, type: 1 })),
    { name: 'Run prompt', type: 3 },
  ]);
  assert.deepEqual(promptCommands[0].options.map(({ name, type, required }) => ({ name, type, required })),
    [{ name: 'text', type: 3, required: false }, { name: 'file', type: 11, required: false }]);
  assert.ok(promptCommands.slice(1, 3).every(command => command.options.length === 1 && command.options[0].name === 'job' && command.options[0].required));
  assert.ok(!Object.hasOwn(promptCommands.at(-1), 'description') && !Object.hasOwn(promptCommands.at(-1), 'options'));
  assert.ok(!userCommands.some(command => command.name.startsWith('prompt') || command.name === 'Run prompt'));
  let current = backendCommands.filter(command => !promptCommands.includes(command)).map((command, index) => ({ ...structuredClone(command), id: String(index) }));
  current.push({ id: 'unrelated', type: 3, name: 'prompt', description: '' });
  const before = structuredClone(current), mutations = [];
  const api = async (path, method = 'GET', command) => {
    if (method !== 'GET') {
      mutations.push({ path, method, command });
      current.push({ ...structuredClone(command), id: `prompt-${mutations.length}`, ...(command.type === 3 ? { description: '' } : {}) });
    }
    return structuredClone(current);
  };
  const result = await registerCommands(api, 'backend', 'guild', backendCommands, true);
  assert.deepEqual(mutations.map(({ path, method }) => ({ path, method })), promptCommands.map(() => ({ path: '/applications/backend/guilds/guild/commands', method: 'POST' })));
  assert.deepEqual(current.slice(0, before.length), before);
  assert.equal(result.bulkOverwrite, false); assert.deepEqual(result.intentionallyRemoved, []);
  assert.ok(registrationPlan(current, backendCommands).every(item => item.state === 'unchanged'), 'Message commands with an empty Discord description must be idempotent');
});
test('admin add targets a Discord user and preserves existing backend registrations', () => {
  const admin = backendCommands.find(command => command.name === 'admin');
  assert.equal(admin.default_member_permissions, '0');
  assert.deepEqual(admin.options.map(({ name, type }) => ({ name, type })), [{ name: 'add', type: 1 }]);
  assert.deepEqual(admin.options[0].options.map(({ name, type, required }) => ({ name, type, required })), [{ name: 'user', type: 6, required: true }]);
  assert.ok(!userCommands.some(command => command.name === 'admin'));
  const current = backendCommands.filter(command => command.name !== 'admin').map((command, index) => ({ ...structuredClone(command), id: String(index) }));
  current.find(command => command.name === 'account').options.push({ type: 1, name: 'existing-feature', description: 'Preserve this existing feature' });
  current.push({ type: 1, id: 'other', name: 'other', description: 'Unrelated command' });
  const before = structuredClone(current), plan = registrationPlan(current, backendCommands);
  assert.deepEqual(plan.filter(item => item.state !== 'unchanged').map(item => ({ state: item.state, name: item.command.name })), [{ state: 'created', name: 'admin' }]);
  assert.deepEqual(current, before);
  assert.deepEqual(plan.find(item => item.command.name === 'account').command.options, current.find(command => command.name === 'account').options);
  assert.deepEqual(plan.find(item => item.command.name === 'other').command, current.find(command => command.name === 'other'));
});

test('authorise remains the only authorization command and never enters the User Bot registry', () => {
  const command = backendCommands.find(command => command.name === 'authorise');
  assert.equal(command.default_member_permissions, '0');
  assert.ok(!backendCommands.some(command => command.name === 'authenticate'));
  assert.deepEqual(command.options.map(({ name, type, required }) => ({ name, type, required })),
    [{ name: 'user', type: 6, required: true }, { name: 'reissue', type: 5, required: false }]);
  assert.ok(!userCommands.some(command => ['authenticate', 'authorise'].includes(command.name)));
});
test('frontend cosmetics preserve existing syntax and targeted registration preserves unrelated commands', async () => {
  const cosmetics = userCommands.find(command => command.name === 'cosmetics');
  assert.equal(cosmetics.default_member_permissions, undefined);
  assert.ok(!backendCommands.some(command => command.name === 'cosmetics'));
  assert.deepEqual(cosmetics.options.map(command => command.name), ['link', 'set', 'show', 'reset', 'unlink']);
  const [link, set, show, reset, unlink] = cosmetics.options;
  assert.deepEqual(link.options.map(({ name, type, required, min_length, max_length }) => ({ name, type, required, min_length, max_length })),
    [{ name: 'code', type: 3, required: true, min_length: 12, max_length: 14 }]);
  assert.deepEqual(set.options.map(option => option.name), ['name', 'x', 'y', 'z']);
  assert.equal(set.options[0].max_length, 2048);
  assert.ok(set.options.every(option => option.required === false));
  assert.ok(set.options.slice(1).every(option => option.type === 10 && option.min_value === 0.1 && option.max_value === 3));
  assert.deepEqual(show.options, []); assert.deepEqual(reset.options, []);
  assert.deepEqual(unlink.options.map(({ name, type, required }) => ({ name, type, required })), [{ name: 'confirm', type: 5, required: true }]);
  const extra = { type: 1, name: 'existing-feature', description: 'Retain this unclassified feature' };
  let current = userCommands.filter(command => command.name !== 'cosmetics').map((command, index) => ({ ...structuredClone(command), id: String(index) }));
  current.find(command => command.name === 'account').options.push(extra);
  current.push({ id: 'other', type: 1, name: 'other', description: 'Unrelated existing command' });
  const before = structuredClone(current), calls = [];
  const api = async (path, method = 'GET', body) => {
    calls.push({ path, method });
    if (method === 'POST') current.push({ ...body, id: 'cosmetics-id' });
    return structuredClone(current);
  };
  const result = await registerCommands(api, 'frontend', 'guild', userCommands, true);
  assert.equal(calls[0].method, 'GET');
  assert.deepEqual(calls.filter(call => call.method !== 'GET'), [{ path: '/applications/frontend/guilds/guild/commands', method: 'POST' }]);
  assert.deepEqual(current.slice(0, before.length), before);
  assert.equal(result.bulkOverwrite, false); assert.deepEqual(result.intentionallyRemoved, []);
});
test('live bridge suppresses duplicate and Discord-origin events and never queues offline messages', async () => {
  const socket = new EventEmitter(), delivered = [], sent = [];
  socket.readyState = 0; socket.bufferedAmount = 0;
  socket.send = value => sent.push(value);
  socket.close = () => socket.emit('close');
  const bridge = new RelayBridge({ url: 'https://worker.example', room: 'testing', secret: 's'.repeat(32),
    onMessage: content => delivered.push(content), socketFactory: (url, options) => {
      assert.equal(url.pathname, '/discord/bridge'); assert.equal(url.searchParams.get('room'), 'testing');
      assert.equal(options.followRedirects, false); assert.equal(options.maxPayload, 8192); return socket;
    } });
  bridge.connect();
  try {
    assert.equal(bridge.send({ type: 'discord_message' }), false);
    socket.readyState = 1; socket.emit('open');
    socket.emit('message', Buffer.from(JSON.stringify({ type: 'bridge_ready', protocol: 3, room: 'testing' })), false);
    const packet = { type: 'irc_message', id: 'b'.repeat(32), message_id: 'b'.repeat(32), room: 'testing', source: 'minecraft', from: 'Steve', text: 'hello' };
    for (const message of [packet, packet, { ...packet, id: 'c'.repeat(32), source: 'discord' }]) socket.emit('message', Buffer.from(JSON.stringify(message)), false);
    await Promise.resolve();
    assert.equal(delivered.length, 1);
    assert.deepEqual(sent, []);
    assert.equal(bridge.send({ type: 'discord_message', id: '11111111111111111', message_id: '11111111111111111', source: 'discord', room: 'testing', text: 'hello' }), true);
    assert.equal(sent.length, 1);
  } finally { bridge.close(); }
});
