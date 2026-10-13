import { Client, Events, GatewayIntentBits, MessageFlags, PermissionFlagsBits } from 'discord.js';
import { configuration, RateLimit, unavailable } from './config.mjs';
import { RelayBridge, discordPacket } from './relay.mjs';
import { DeliveryReceipts, GLOBAL_ROOM, globalEmbedPacket, resolveDiscordReply } from './global-chat.mjs';
import { applyDiscordOperation, descriptionFingerprint, discordEventId, gatewayMessage, messageSnapshot, nativeDiscordOperation } from './chat-operations.mjs';
import { CommunityFeed, communityFeedReceipts } from './community-feed.mjs';
import { PendingDiscordCreates } from './native-create.mjs';

let config;
try { config = configuration(process.env); }
catch (error) { console.error(error.message); process.exit(1); }
const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.GuildMessageReactions, GatewayIntentBits.MessageContent],
  allowedMentions: { parse: [], repliedUser: false } });
const bridges = new Map(), pending = new Map();
const nativeTasks = new Map(), sourceMessages = new Map(), expectedEdits = new Map(), gatewaySessions = new Map();
const pendingCreates = new PendingDiscordCreates();
let nativeJobs = 0;
const userRate = new RateLimit(config.limits.userBurst, config.limits.userRefillMs);
const globalRate = new RateLimit(config.limits.globalBurst, config.limits.globalRefillMs, 1);
const noticeRate = new RateLimit(1, 30000);
async function notice(message, text = unavailable) {
  if (!noticeRate.allow(message.author.id)) return;
  await message.reply({ content: text, allowedMentions: { parse: [], repliedUser: false } }).catch(() => {});
}
function result(id, error) {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id); clearTimeout(item.timer);
  item.resolve?.(!error);
  if (error) void notice(item.message, error === 'not_authorized' ? 'This message could not be bridged. Use /status to review your Discord connection and online-service restrictions.' : unavailable);
}
let starting = false;
let rngFeed;
async function startBridges() {
  if (starting || bridges.size || !client.isReady()) return;
  starting = true;
  try {
    if (client.user.id !== config.app || !client.guilds.cache.has(config.guild)) throw new Error('Application or guild mismatch');
    const receipts = await new DeliveryReceipts(config.receiptsPath).load();
    for (const room of config.rooms) {
      const channel = await client.channels.fetch(config.channels[room]);
      if (!channel?.isSendable() || channel.guildId !== config.guild ||
          !channel.permissionsFor(client.user)?.has([PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages,
            PermissionFlagsBits.EmbedLinks, PermissionFlagsBits.ReadMessageHistory, PermissionFlagsBits.AddReactions])) throw new Error('Channel unavailable');
      const bridge = new RelayBridge({ url: config.url, room, secret: config.secret, policy: config.limits, globalRate,
        channelId: config.channels[room], receipts, ownBotId: client.user.id, onMessage: content => channel.send(content), onResult: result,
        onOperation: (packet, routing) => applyDiscordOperation(packet, { ...routing, channel, guild: config.guild, ownBotId: client.user.id, receipts,
          expectEdit(id, description) {
            const now = Date.now(), values = expectedEdits.get(id)?.values ?? new Map();
            values.set(descriptionFingerprint(description), now + 15000);
            while (values.size > 8) values.delete(values.keys().next().value);
            expectedEdits.set(id, { values, expires: now + 15000 }); pruneNative();
          } }) });
      bridges.set(room, bridge); bridge.connect();
    }
    if (config.rngChannel) {
      try {
        const channel = await client.channels.fetch(config.rngChannel);
        if (!channel?.isSendable() || channel.guildId !== config.guild || !channel.permissionsFor(client.user)?.has([
          PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages, PermissionFlagsBits.EmbedLinks, PermissionFlagsBits.ReadMessageHistory])) throw new Error('RNG channel unavailable');
        rngFeed = new CommunityFeed({ url: config.url.replace(/^wss:/, 'https:'), secret: config.secret, channelId: config.rngChannel, channel,
          receipts: await communityFeedReceipts(config.rngReceiptsPath), onDiagnostic: event => console.warn(JSON.stringify({ event })) });
        rngFeed.start();
      } catch { console.warn(JSON.stringify({ event: 'rng_feed_configuration_unavailable' })); }
    }
    console.info(JSON.stringify({ event: 'discord_connected', name: 'Sky-Myce User Bot', applicationId: config.app,
      guildId: config.guild, transport: 'Gateway messages; signed HTTP account interactions', rooms: config.rooms, relayConfigured: !!config.url }));
  } finally { starting = false; }
}
function shutdown() {
  rngFeed?.stop(); rngFeed = undefined;
  for (const bridge of bridges.values()) bridge.close();
  bridges.clear();
  for (const item of pending.values()) { clearTimeout(item.timer); item.resolve?.(false); }
  pending.clear(); nativeTasks.clear(); pendingCreates.clear(); sourceMessages.clear(); expectedEdits.clear(); gatewaySessions.clear(); client.destroy();
}
function start() { void startBridges().catch(() => { console.error('Sky-Myce startup failed. Check application, guild, channel permissions and relay configuration.'); shutdown(); process.exitCode = 1; }); }
client.once(Events.ClientReady, start);
function pruneNative() {
  const now = Date.now();
  for (const [map, maximum] of [[sourceMessages, 256], [expectedEdits, 1024]]) {
    for (const [id, value] of map) if (value.expires <= now) map.delete(id);
    while (map.size > maximum) map.delete(map.keys().next().value);
  }
}
function nativeTask(id, work) {
  if (nativeJobs >= 128) return false;
  nativeJobs++;
  const before = nativeTasks.get(id) ?? Promise.resolve();
  const task = before.catch(() => {}).then(work).catch(() => console.warn(JSON.stringify({ event: 'discord_source_update_unavailable' })))
    .finally(() => { nativeJobs--; if (nativeTasks.get(id) === task) nativeTasks.delete(id); });
  nativeTasks.set(id, task); return true;
}
client.on(Events.MessageCreate, message => {
  if (message.guildId !== config.guild || message.webhookId || message.author?.id === config.app) return;
  for (const [room, bridge] of bridges) {
    if (message.channelId !== config.channels[room]) continue;
    if (room !== GLOBAL_ROOM && message.author?.bot) return;
    if (room !== GLOBAL_ROOM && !userRate.allow(message.author.id)) { void notice(message, 'Please wait before sending another bridge message.'); return; }
    if (pending.has(message.id) || nativeTasks.has(message.id)) return;
    const creation = pending.size < 128 ? pendingCreates.begin(message.id, room, config.channels[room]) : null;
    if (!creation) { void notice(message); return; }
    if (!nativeTask(message.id, async () => {
      try {
        if (!await bridge.whenCapabilitiesReady()) { await notice(message); return; }
        const published = await pendingCreates.publish(creation, {
          async resolve(changed) {
            let current = message;
            if (changed) {
              const channel = await client.channels.fetch(config.channels[room]);
              if (channel?.guildId !== config.guild || !channel.isSendable()) throw new Error('Channel unavailable');
              current = await channel.messages.fetch({ message: message.id, force: true });
            }
            return resolveDiscordReply(current, config.guild, config.channels[room]);
          },
          async prepare(withReply) {
            if (withReply?.id !== message.id || withReply?.author?.id !== message.author.id) return null;
            const packet = discordPacket(withReply, config.guild, config.channels[room], room, config.limits.outboundLength, bridge.richChatVersion === 1);
            if (!packet) {
              if (room !== GLOBAL_ROOM) await notice(message, `Bridge messages must be plain text of 1–${config.limits.outboundLength} characters without control/formatting codes.`);
              return null;
            }
            const snapshot = messageSnapshot(withReply); snapshot.text = packet.text; snapshot.media = packet.media;
            if (bridge.richChatVersion === 1) await bridge.receipts.rememberSource(packet.id, { room, channelId: config.channels[room], authorId: message.author.id, name: packet.from });
            return { packet, snapshot };
          },
          send({ packet, snapshot }) {
            sourceMessages.set(packet.id, { snapshot, expires: Date.now() + 120000 }); pruneNative();
            if (!bridge.send(packet)) { void notice(message); return false; }
            return true;
          },
        });
        if (!published) return;
        await new Promise(resolve => {
          const timer = setTimeout(() => result(message.id, 'timeout'), 10000);
          pending.set(message.id, { message, timer, resolve });
        });
      } finally { pendingCreates.finish(creation); }
    })) { pendingCreates.finish(creation); void notice(message); return; }
  }
});
client.on(Events.MessageUpdate, (previous, message) => {
  const bridge = bridges.get(GLOBAL_ROOM);
  if (!bridge || bridge.richPending || bridge.richChatVersion === 1) return;
  const packet = globalEmbedPacket(previous, message, config.guild, config.channels[GLOBAL_ROOM]);
  if (packet) bridge.send(packet);
});
client.on(Events.Raw, (dispatch, shardId) => {
  if (dispatch.t === 'READY') { if (typeof dispatch.d?.session_id === 'string') gatewaySessions.set(shardId, dispatch.d.session_id); return; }
  if (!['MESSAGE_UPDATE', 'MESSAGE_DELETE', 'MESSAGE_DELETE_BULK', 'MESSAGE_REACTION_ADD', 'MESSAGE_REACTION_REMOVE'].includes(dispatch.t) || !Number.isSafeInteger(dispatch.s)) return;
  const raw = dispatch.d;
  if (raw?.guild_id !== config.guild || !/^\d{17,20}$/.test(raw.channel_id ?? '')) return;
  const entry = [...bridges].find(([room]) => config.channels[room] === raw.channel_id);
  if (!entry || entry[1].richChatVersion !== 1 && !entry[1].richPending) return;
  const [room, bridge] = entry, event = dispatch.t === 'MESSAGE_DELETE_BULK' ? 'MESSAGE_DELETE' : dispatch.t;
  const ids = dispatch.t === 'MESSAGE_DELETE_BULK' ? (Array.isArray(raw.ids) ? raw.ids : []).slice(0, 100) : [raw.id ?? raw.message_id];
  for (const messageId of ids) {
    if (!/^\d{17,20}$/.test(messageId ?? '')) continue;
    const creation = pendingCreates.observe(messageId, room, raw.channel_id, event);
    if (!bridge.receipts.source(messageId, room) && !creation) continue;
    const data = { ...raw, id: messageId }; delete data.ids;
    const operationId = discordEventId(gatewaySessions.get(shardId) ?? `shard-${shardId}`, { ...dispatch, t: event, d: data });
    nativeTask(messageId, async () => {
      if (creation && creation.status !== 'published') return;
      if (!await bridge.whenCapabilitiesReady() || bridge.richChatVersion !== 1) return;
      pruneNative();
      const source = bridge.receipts.source(messageId, room);
      if (!source || source.channelId !== raw.channel_id) return;
      const previous = sourceMessages.get(messageId)?.snapshot;
      let current = gatewayMessage(data, config.guild, raw.channel_id);
      if (event === 'MESSAGE_UPDATE') {
        const channel = await client.channels.fetch(raw.channel_id);
        if (channel?.guildId !== config.guild || !channel.isSendable()) return;
        try { current = messageSnapshot(await channel.messages.fetch({ message: messageId, force: true }), source); }
        catch { return; }
      }
      const description = current.embeds?.[0]?.description;
      const expected = expectedEdits.get(messageId)?.values;
      const fingerprint = descriptionFingerprint(description);
      const packet = nativeDiscordOperation(event, current, source, previous, operationId, { guild: config.guild, channel: raw.channel_id,
        ownBotId: client.user.id, expectedDescription: expected?.get(fingerprint) > Date.now() ? fingerprint : undefined });
      if (!packet) return;
      packet.scope = bridge.scope; packet.room = room;
      if (bridge.sendOperation(packet) && event === 'MESSAGE_UPDATE') sourceMessages.set(messageId, { snapshot: { ...current, text: packet.text, media: packet.media }, expires: Date.now() + 120000 });
      if (event === 'MESSAGE_DELETE') { sourceMessages.delete(messageId); expectedEdits.delete(messageId); }
    });
  }
});
// Only delivered when no HTTP endpoint is configured. Never invent an unsigned backend API.
client.on(Events.InteractionCreate, interaction => {
  if (!interaction.isChatInputCommand()) return;
  void interaction.reply({ content: unavailable, flags: MessageFlags.Ephemeral, allowedMentions: { parse: [] } }).catch(() => {});
});
client.on(Events.Error, () => console.warn('Sky-Myce Discord connection unavailable.'));
client.on(Events.ShardDisconnect, () => console.warn('Sky-Myce Discord disconnected; Gateway will reconnect.'));
client.on(Events.ShardResume, () => console.info('Sky-Myce Discord resumed.'));
for (const event of ['SIGINT', 'SIGTERM']) process.once(event, shutdown);
try { await client.login(config.token); }
catch { console.error('Sky-Myce Discord login failed. Check USER_BOT_TOKEN and enabled intents.'); shutdown(); process.exitCode = 1; }
