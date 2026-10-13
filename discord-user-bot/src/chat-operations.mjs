import { createHash } from 'node:crypto';
import { discordOperation, discordMedia, minecraftCard, REACTION_EMOJI } from './global-chat.mjs';

export const descriptionFingerprint = text => createHash('sha256').update(typeof text === 'string' ? text : '').digest('hex');
export const discordEventId = (session, packet) => createHash('sha256').update(`${session}\n${packet.s}\n${packet.t}\n${JSON.stringify(packet.d)}`).digest('hex').slice(0, 32);
export function messageSnapshot(message, metadata = {}) {
  const values = value => value instanceof Map || typeof value?.values === 'function' ? [...value.values()] : Array.isArray(value) ? value : [];
  return { id: message.id, guildId: message.guildId, channelId: message.channelId, content: String(message.content ?? '').slice(0, 4096),
    author: { id: message.author?.id ?? metadata.authorId, username: message.author?.username ?? metadata.name ?? 'Discord user',
      globalName: message.author?.globalName, bot: message.author?.bot }, webhookId: message.webhookId,
    embeds: values(message.embeds).slice(0, 8).map(item => item.toJSON?.() ?? item),
    attachments: values(message.attachments).slice(0, 4).map(item => ({ id: item.id, url: item.url, proxyURL: item.proxyURL,
      name: item.name, contentType: item.contentType, size: item.size, width: item.width, height: item.height })),
    editedTimestamp: message.editedTimestamp, mapped: metadata.mapped === true };
}
export function gatewayMessage(data, guild, channel) {
  return { id: data.id ?? data.message_id, guildId: data.guild_id ?? guild, channelId: data.channel_id ?? channel,
    ...(data.content !== undefined ? { content: data.content } : {}), ...(data.author ? { author: data.author } : {}),
    ...(data.embeds ? { embeds: data.embeds } : {}), ...(data.attachments ? { attachments: data.attachments.map(item => ({ ...item,
      contentType: item.content_type, proxyURL: item.proxy_url })) } : {}),
    ...(data.edited_timestamp ? { editedTimestamp: Date.parse(data.edited_timestamp) } : {}),
    ...(data.user_id ? { userId: data.user_id } : {}), ...(data.emoji ? { emoji: data.emoji } : {}) };
}
function reactionFooter(packet) {
  const counts = (Array.isArray(packet.reactions) ? packet.reactions : []).filter(item => REACTION_EMOJI.includes(item?.emoji) && Number.isSafeInteger(item.count) && item.count > 0 && item.count <= 384).slice(0, 6);
  const summary = counts.map(item => `${item.emoji} ${item.count}`).join('  ');
  return `${packet.scope === 'global' ? 'Minecraft · Sky-Myce Global' : 'Minecraft · Sky-Myce IRC'}${summary ? ` · ${summary}` : ''}`;
}
/** Called only with a channel and routing identity already validated by the authenticated bridge. */
export async function applyDiscordOperation(packet, { channel, guild, ownBotId, authorId, owned, receipts, expectEdit }) {
  if (channel?.guildId !== guild || channel.id !== receipts.source(packet.discordMessageId, packet.room)?.channelId ||
      packet.operation !== 'reaction' && !owned) throw new Error('not_authorized');
  let message;
  try { message = await channel.messages.fetch({ message: packet.discordMessageId, force: true }); }
  catch (error) {
    if (error?.code === 10008 && packet.operation === 'delete') return;
    if (error?.code === 10008) throw new Error('message_deleted');
    throw error;
  }
  if (message?.id !== packet.discordMessageId || message.channelId !== channel.id || message.guildId !== guild || message.author?.id !== authorId ||
      owned && authorId !== ownBotId) throw new Error('not_authorized');
  if (packet.operation === 'delete') { await message.delete(); return; }
  const progress = receipts.metadata(packet.messageId) ?? receipts.source(packet.discordMessageId, packet.room);
  if (packet.operation === 'edit') {
    const identity = packet.sender;
    const next = { ...packet, type: 'irc_message', source: 'minecraft', id: packet.messageId, message_id: packet.messageId,
      from: identity?.name, sender: identity, mentions: [] };
    const card = minecraftCard(next, packet.scope, packet.room);
    if (!card) throw new Error('invalid_operation');
    const newerSummary = (progress?.reactionSummaryRevision ?? 0) > packet.revision;
    const currentFooter = message.embeds?.length === 1 ? message.embeds[0]?.footer?.text : undefined;
    card.embeds[0].footer = { text: newerSummary && typeof currentFooter === 'string' ? currentFooter : reactionFooter(packet) };
    expectEdit?.(message.id, card.embeds[0].description);
    await message.edit({ ...card, content: '', reply: undefined });
    receipts.setCard(packet.messageId, next);
    return;
  }
  if (!REACTION_EMOJI.includes(packet.emoji) || typeof packet.active !== 'boolean') throw new Error('invalid_operation');
  const route = `/channels/${channel.id}/messages/${message.id}/reactions/${encodeURIComponent(packet.emoji)}/@me`;
  if (packet.active) await channel.client.rest.put(route);
  else await channel.client.rest.delete(route);
  // One native bot reaction represents Minecraft actors. Own cards also show their logical totals.
  if (owned && message.embeds?.length === 1 && (progress?.reactionSummaryRevision ?? 0) <= packet.revision) {
    const embed = message.embeds[0].toJSON?.() ?? message.embeds[0];
    expectEdit?.(message.id, embed.description);
    await message.edit({ embeds: [{ ...embed, footer: { text: reactionFooter(packet) } }], allowedMentions: { parse: [], repliedUser: false } });
  }
}
export function nativeDiscordOperation(event, current, source, previous, operationId, { guild, channel, ownBotId, expectedDescription }) {
  if (!source || source.channelId !== channel || current.guildId !== guild || current.channelId !== channel) return null;
  const baseline = { ...(previous ?? {}), id: current.id, guildId: guild, channelId: channel, mapped: source.mapped,
    author: { ...(previous?.author ?? {}), id: source.authorId, username: source.name ?? previous?.author?.username ?? 'Discord user' } };
  if (event === 'MESSAGE_UPDATE' && source.mapped) {
    if (current.author?.id !== ownBotId || !Array.isArray(current.embeds) || current.embeds.length !== 1) return null;
    const description = current.embeds[0]?.description;
    if (typeof description !== 'string' || descriptionFingerprint(description) === expectedDescription) return null;
    const text = description.replace(/\\([\\`*_~|>])/g, '$1');
    if (!text.trim() || text.length > 1024) return null;
    const packet = discordOperation(event, { ...current, content: text }, baseline, operationId, guild, channel, ownBotId);
    if (!packet) return null;
    return { ...packet, text, media: discordMedia(current) };
  }
  const packet = discordOperation(event, current, baseline, operationId, guild, channel, ownBotId);
  return packet && (packet.operation !== 'edit' || packet.text?.trim()) ? packet : null;
}
