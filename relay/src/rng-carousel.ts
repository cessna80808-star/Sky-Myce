import { rngRecentPresentation } from "../../shared/rng-presentation.mjs";
import type { DiscordAccountsConfig } from "./discord-accounts";

const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);

/** Called only after exact-body User Bot signature verification; never edits a published acquisition. */
export async function handleRngCarouselInteraction(data: Record<string, unknown>, env: DiscordAccountsConfig & { COMMUNITY_SERVICES_ENABLED?: string }, backend: boolean): Promise<Response | null> {
  const command = object(data.data), customId = command?.custom_id;
  if (typeof customId !== "string" || !customId.startsWith("rng:browse:")) return null;
  const message = object(data.message), author = object(message?.author), member = object(data.member), caller = object(member?.user);
  const target = /^rng:browse:(latest|[a-f0-9]{32})$/.exec(customId)?.[1];
  const privatePage = typeof message?.flags === "number" && (message.flags & 64) !== 0;
  const unavailable = () => Response.json({ type: 4, data: { content: "Recent community drops are unavailable. Try Browse recent drops again.", flags: 64, allowed_mentions: { parse: [] } } });
  if (backend || env.COMMUNITY_SERVICES_ENABLED === "false" || data.type !== 3 || command?.component_type !== 2 || !target ||
    data.guild_id !== env.DISCORD_GUILD_ID || !snowflake(env.DISCORD_RNG_CHANNEL_ID) || data.channel_id !== env.DISCORD_RNG_CHANNEL_ID ||
    author?.id !== env.DISCORD_USER_APPLICATION_ID || !snowflake(caller?.id) || !snowflake(data.id) || target !== "latest" && !privatePage) return unavailable();
  try {
    const result = await env.ACCOUNT_STORE.getByName("global").discordRecentRng(
      { id: caller.id, guildId: data.guild_id as string, roles: [], interactionId: data.id }, env.DISCORD_RNG_CHANNEL_ID);
    if (!result.ok) return unavailable();
    const index = target === "latest" ? 0 : result.events.findIndex(event => event.id === target), event = result.events[index];
    const card = event && rngRecentPresentation(event);
    const content = card ? `Recent community drops · ${index + 1} / ${result.events.length}` :
      target === "latest" ? "No recent shared drops are available." : "This drop is no longer shared. Browse recent drops to refresh.";
    const button = (label: string, id: string, disabled = false) => ({ type: 2, style: 2, label, custom_id: `rng:browse:${id}`, disabled });
    const components = card ? [{ type: 1, components: [button("Previous", result.events[index - 1]?.id ?? event.id, index === 0),
      button("Next", result.events[index + 1]?.id ?? event.id, index === result.events.length - 1), button("Refresh", "latest")] }] :
      [{ type: 1, components: [button("Browse recent drops", "latest")] }];
    return Response.json({ type: privatePage ? 7 : 4, data: { content, embeds: card?.embeds ?? [], components,
      ...(privatePage ? {} : { flags: 64 }), allowed_mentions: { parse: [] } } });
  } catch { return unavailable(); }
}
