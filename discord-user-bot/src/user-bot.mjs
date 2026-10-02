import { Client, Events, GatewayIntentBits, MessageFlags, PermissionFlagsBits } from 'discord.js';
import { configuration, RateLimit, unavailable } from './config.mjs';
import { RelayBridge, discordPacket } from './relay.mjs';
import { DeliveryReceipts, GLOBAL_ROOM, globalEmbedPacket } from './global-chat.mjs';

let config;
try { config = configuration(process.env); }
catch (error) { console.error(error.message); process.exit(1); }
const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent],
  allowedMentions: { parse: [], repliedUser: false } });
const bridges = new Map(), pending = new Map();
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
  if (error) void notice(item.message, error === 'not_authorized' ? 'This message could not be bridged. Use /account status to check your Sky-Myce access.' : unavailable);
}
let starting = false;
async function startBridges() {
  if (starting || bridges.size || !client.isReady()) return;
  starting = true;
  try {
    if (client.user.id !== config.app || !client.guilds.cache.has(config.guild)) throw new Error('Application or guild mismatch');
    const receipts = config.rooms.includes(GLOBAL_ROOM) ? await new DeliveryReceipts(config.receiptsPath).load() : null;
    for (const room of config.rooms) {
      const channel = await client.channels.fetch(config.channels[room]);
      if (!channel?.isSendable() || channel.guildId !== config.guild ||
          !channel.permissionsFor(client.user)?.has([PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages,
            ...(room === GLOBAL_ROOM ? [PermissionFlagsBits.EmbedLinks, PermissionFlagsBits.ReadMessageHistory] : [])])) throw new Error('Channel unavailable');
      const bridge = new RelayBridge({ url: config.url, room, secret: config.secret, policy: config.limits, globalRate,
        channelId: config.channels[room], receipts, onMessage: content => channel.send(content), onResult: result });
      bridges.set(room, bridge); bridge.connect();
    }
    console.info(JSON.stringify({ event: 'discord_connected', name: 'Sky-Myce User Bot', applicationId: config.app,
      guildId: config.guild, transport: 'Gateway messages; signed HTTP account interactions', rooms: config.rooms, relayConfigured: !!config.url }));
  } finally { starting = false; }
}
function shutdown() {
  for (const bridge of bridges.values()) bridge.close();
  bridges.clear();
  for (const item of pending.values()) clearTimeout(item.timer);
  pending.clear(); client.destroy();
}
function start() { void startBridges().catch(() => { console.error('Sky-Myce startup failed. Check application, guild, channel permissions and relay configuration.'); shutdown(); process.exitCode = 1; }); }
client.once(Events.ClientReady, start);
client.on(Events.MessageCreate, message => {
  if (message.guildId !== config.guild || message.webhookId || message.author?.id === config.app) return;
  for (const [room, bridge] of bridges) {
    if (message.channelId !== config.channels[room]) continue;
    if (room !== GLOBAL_ROOM && message.author?.bot) return;
    if (room !== GLOBAL_ROOM && !userRate.allow(message.author.id)) { void notice(message, 'Please wait before sending another bridge message.'); return; }
    const packet = discordPacket(message, config.guild, config.channels[room], room, config.limits.outboundLength);
    if (!packet) {
      if (room !== GLOBAL_ROOM) void notice(message, `Bridge messages must be plain text of 1–${config.limits.outboundLength} characters without control/formatting codes.`);
      return;
    }
    if (pending.has(packet.id)) return;
    if (pending.size >= 128 || !bridge.send(packet)) { void notice(message); return; }
    const timer = setTimeout(() => result(packet.id, 'timeout'), 10000);
    pending.set(packet.id, { message, timer });
  }
});
client.on(Events.MessageUpdate, (previous, message) => {
  const bridge = bridges.get(GLOBAL_ROOM);
  if (!bridge) return;
  const packet = globalEmbedPacket(previous, message, config.guild, config.channels[GLOBAL_ROOM]);
  if (packet) bridge.send(packet);
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
