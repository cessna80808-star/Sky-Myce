import type { AccountStore, AccountsConfig } from "./accounts";
import type { RelayRoom } from "./index";
import type { CommunityIdentity as Identity, CommunityResult } from "./community";
import { auditReason } from "./discord-notifications";
import { discordLeaderboardMessage } from "./leaderboards";
import type { DeveloperActor as Actor } from "./licensing";

export type DiscordAccountsConfig = AccountsConfig & {
  ACCOUNT_STORE: DurableObjectNamespace<AccountStore>;
  DISCORD_USER_APPLICATION_ID?: string; DISCORD_USER_PUBLIC_KEY?: string;
  USER_BOT_TOKEN?: string; USER_BOT_APPLICATION_ID?: string; USER_BOT_PUBLIC_KEY?: string;
  BACKEND_BOT_TOKEN?: string; BACKEND_BOT_APPLICATION_ID?: string; BACKEND_BOT_PUBLIC_KEY?: string;
  DISCORD_BACKEND_APPLICATION_ID?: string; DISCORD_BACKEND_PUBLIC_KEY?: string; DISCORD_BACKEND_BOT_TOKEN?: string;
  DISCORD_LOADER_DELIVERY_ENABLED?: string; DISCORD_AUDIT_CHANNEL_ID?: string; DISCORD_SECURITY_CHANNEL_ID?: string; DISCORD_LICENSE_CHANNEL_ID?: string;
  ROOMS: string; RELAY_ROOMS: DurableObjectNamespace<RelayRoom>;
};
type ParsedCommand = { actor: Actor; action: string; target: string; options: Record<string, unknown>; identity?: Record<string, unknown> };
type Message = { content?: string; embeds?: Record<string, unknown>[]; components?: Record<string, unknown>[] };
const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);
const uuid = (value: unknown): value is string => typeof value === "string" && /^[a-f0-9]{32}$/i.test(value);
const ephemeral = (content: string) => Response.json({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
const reads = new Set(["account", "devices", "sessions", "audit", "blacklist", "search", "overview", "lookup"]);
const managed = new Set(["blacklist_add", "blacklist_remove", "session_kick", "admin_add", "disconnect", "unlink"]);

/** Only called after exact-body Discord signature and application verification. */
export function accountCommand(data: Record<string, unknown>, kind: "user" | "backend"): ParsedCommand | null {
  const command = object(data.data), member = object(data.member), caller = object(member?.user);
  if (data.type !== 2 || !command || !snowflake(data.id) || !snowflake(data.guild_id) || !snowflake(caller?.id) ||
    !Array.isArray(member?.roles) || member.roles.some(role => !snowflake(role)) || typeof command.name !== "string") return null;
  let action = command.name, raw = command.options ?? [];
  if (!Array.isArray(raw) || raw.length > 8) return null;
  if (kind === "backend" || action === "account") {
    const sub = object(raw[0]);
    if (raw.length !== 1 || sub?.type !== 1 || typeof sub.name !== "string") return null;
    const aliases: Record<string, string> = kind === "user" ? { account_status: "status", account_link: "connect", account_unlink: "unlink", account_disconnect: "disconnect" } : {
      account_overview: "account", account_devices: "devices", account_sessions: "sessions", account_audit: "audit",
      skyhawk_search: "search", skyhawk_lookup: "lookup", skyhawk_overview: "overview",
      blacklist_info: "blacklist", blacklist_list: "blacklist", blacklist_add: "blacklist_add", blacklist_remove: "blacklist_remove",
      session_kick: "session_kick", admin_add: "admin_add" };
    action = aliases[`${action}_${sub.name}`];
    if (!action) return null;
    raw = sub.options ?? [];
    if (!Array.isArray(raw) || raw.length > 8) return null;
  } else if (!["connect", "status", "disconnect", "help"].includes(action)) return null;
  const fields: Record<string, number> = kind === "user" ? action === "connect" ? { code: 3 } : action === "unlink" ? { uuid: 3 } : {} :
    action.startsWith("blacklist") ? { user: 6, uuid: 3, reason: 3, page: 4 } : action === "search" ? { query: 3 } : action === "lookup" ? { "discord-id": 3 } :
      action === "overview" ? {} : { user: 6, ...["devices", "sessions", "audit"].includes(action) ? { page: 4 } : {} };
  const options: Record<string, unknown> = {};
  for (const rawOption of raw) {
    const item = object(rawOption);
    if (!item || typeof item.name !== "string" || !Object.hasOwn(fields, item.name) || item.type !== fields[item.name] || Object.hasOwn(options, item.name)) return null;
    if (item.type === 4 ? !Number.isSafeInteger(item.value) || Number(item.value) < 1 || Number(item.value) > 100 : typeof item.value !== "string" || item.value.length > 500) return null;
    options[item.name] = item.value;
  }
  let target = caller.id;
  if (kind === "user") {
    if (action === "connect" && (typeof options.code !== "string" || !/^[A-HJ-NP-Z2-9]{12}$/i.test(options.code.replaceAll("-", "")))) return null;
    if (action === "unlink" && !uuid(options.uuid)) return null;
    if (uuid(options.uuid)) options.uuid = options.uuid.toLowerCase();
  } else if (action.startsWith("blacklist")) {
    const list = object((command.options as unknown[])[0])?.name === "list";
    if (list) { if (options.user !== undefined || options.uuid !== undefined || options.reason !== undefined) return null; target = ""; }
    else {
      if ((options.user !== undefined) === (options.uuid !== undefined) || options.user !== undefined && !snowflake(options.user) || options.uuid !== undefined && !uuid(options.uuid) || options.page !== undefined) return null;
      target = String(options.user ?? options.uuid).toLowerCase();
      options.kind = options.user === undefined ? "minecraft" : "discord"; options.identity = target;
      delete options.user; delete options.uuid;
      if (action !== "blacklist" && (typeof options.reason !== "string" || !options.reason.trim())) return null;
      if (action === "blacklist" && options.reason !== undefined) return null;
    }
  } else if (action === "search" || action === "overview") {
    target = "";
    if (action === "search" && (typeof options.query !== "string" || !options.query.trim() || options.query.length > 100)) return null;
  } else {
    const selected = action === "lookup" ? options["discord-id"] : options.user;
    if (!snowflake(selected)) return null;
    target = selected; delete options.user; delete options["discord-id"];
  }
  const resolved = object(command.resolved), users = object(resolved?.users), members = object(resolved?.members);
  const identity = object(users?.[target]) ?? (target === caller.id ? caller : undefined);
  return { actor: { id: caller.id, guildId: data.guild_id, roles: member.roles as string[], interactionId: data.id }, action, target, options,
    ...(identity ? { identity: { ...identity, nick: object(members?.[target])?.nick ?? (target === caller.id ? member.nick : undefined) } } : {}) };
}

async function discordRequest(path: string, body: unknown): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = await fetch(`https://discord.com/api/v10${path}`, { method: "PATCH", redirect: "manual", signal: AbortSignal.timeout(8000),
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (response.status === 429 && attempt < 2) {
      const retry = Number((await response.json() as { retry_after?: number }).retry_after);
      if (retry > 0 && retry <= 2) { await new Promise(resolve => setTimeout(resolve, retry * 1000)); continue; }
    }
    if (!response.ok) throw new Error("Discord completion unavailable");
    return;
  }
}
export function deferInteraction(data: Record<string, unknown>, ctx: ExecutionContext, operation: () => Promise<string | Message>): Response {
  if (typeof data.token !== "string" || !/^[A-Za-z0-9._-]{20,512}$/.test(data.token) || !snowflake(data.application_id)) return ephemeral("Invalid Sky-Myce interaction. Run the command again.");
  ctx.waitUntil(operation().catch(error => {
    console.error({ event: "discord_interaction_unavailable", error: error instanceof Error ? error.name : typeof error });
    return "This Sky-Myce service is currently unavailable.";
  }).then(async content => {
    await discordRequest(`/webhooks/${data.application_id}/${data.token}/messages/@original`, {
      ...(typeof content === "string" ? { content: content.slice(0, 1900), embeds: [], components: [] } : { ...content, content: content.content?.slice(0, 1900), components: content.components ?? [] }),
      allowed_mentions: { parse: [] } });
  }).catch(() => { console.warn("Sky-Myce Discord interaction completion unavailable."); }));
  return Response.json({ type: 5, data: { flags: 64 } });
}
const textName = (value: unknown) => String(value ?? "Not reported").replace(/[\x00-\x1f\x7f]/g, "").replace(/([\\`*_~|<>])/g, "\\$1").slice(0, 100);
const discordTime = (value: unknown) => typeof value === "number" && Number.isFinite(value) && value > 0 ? `<t:${Math.floor(value / 1000)}:f> · <t:${Math.floor(value / 1000)}:R>` : "Not reported";
const field = (name: string, value: string, inline = false) => ({ name, value: value.slice(0, 1024) || "None", inline });
const button = (label: string, id: string, style = 2) => ({ type: 2, style, label, custom_id: id });
const row = (...buttons: Record<string, unknown>[]) => ({ type: 1, components: buttons });
const userButtons = () => [row(button("Status", "sky:user:status"), button("Help", "sky:user:help"), button("Disconnect", "sky:user:disconnect"))];
function safeError(code: unknown): string {
  const messages: Record<string, string> = {
    admin_required: "Only persisted Sky-Myce administrators can use this Backend Bot command.", forbidden: "You do not have permission to use this private Sky-Myce control.",
    blacklisted: "This identity is restricted from Sky-Myce online services. Contact a community administrator for review. Local gameplay remains available.",
    identity_blacklisted: "This identity is restricted from Sky-Myce online services. Contact a community administrator for review. Local gameplay remains available.",
    discord_blacklisted: "This Discord account is restricted from online services. Contact a community administrator for review. Local gameplay remains available.",
    minecraft_blacklisted: "This Minecraft account is restricted from online services. Contact a community administrator for review. Local gameplay remains available.",
    account_required: "No Discord connection was found. In Minecraft, open Connect Discord. The browser is the primary connection method; use `/connect code:...` for its fallback code.",
    invalid_link: "That connection code is invalid, expired or already used. Open Connect Discord in Minecraft to generate a fresh request.",
    invalid_connect_code: "That connection code is invalid, expired or already used. Open Connect Discord in Minecraft to generate a fresh request.",
    connect_expired: "That connection request expired or was already used. Open Connect Discord in Minecraft to generate a fresh request.",
    session_expired: "This community session ended. Use Connect Discord in Minecraft to reconnect; local gameplay remains available.",
    invalid_blacklist: "Choose exactly one Discord user or Minecraft UUID and a private audit reason. Device identifiers cannot be blacklisted.",
    minecraft_account_owned: "That Minecraft account is already linked to another Discord identity. Disconnect that association before reconnecting; this does not restrict local gameplay.",
    minecraft_link_required: "Choose one of your linked Minecraft accounts. Use Connect Discord in Minecraft to add another account.",
    invalid_uuid: "Choose a valid Minecraft UUID belonging to the selected account.", invalid_request: "Check the selected identity and requested options.",
    invalid_action: "This older approval or device-locking workflow has been removed. Install the Fabric mod normally and use Connect Discord for community features.",
    confirmation_expired: "This confirmation expired or was already handled. Run the command again to review a fresh confirmation.",
    confirmation_stale: "The identity, blacklist or account associations changed after review. Run the command again to review their current state.",
    confirmation_required: "Review the private confirmation and use its Confirm control before changing this account.",
    interaction_replayed: "This request was already handled.", rate_limited: "Please wait before sending another Sky-Myce request.",
  };
  return typeof code === "string" && Object.hasOwn(messages, code) ? messages[code] : "Sky-Myce could not complete this request. Try again or ask an administrator to review the online service.";
}
function identityMessage(identity: Identity, admin: boolean): Message {
  const display = textName(identity.displayName ?? identity.username ?? "Discord user");
  const minecraft = identity.uuids.slice(0, 10).map(mc => textName(mc.name)).join("\n") || "None linked yet";
  return { content: admin ? `**Private account overview for <@${identity.discordId}>**` : "**Discord connected.** Community features use your linked Minecraft accounts. Local gameplay does not require Discord.",
    embeds: [{ title: "Sky-Myce community account", color: identity.blacklisted ? 0xe3ad40 : 0x35b779,
      fields: [field("Discord identity", `${display}${identity.username ? ` (@${textName(identity.username)})` : ""}\n<@${identity.discordId}>${admin ? `\nDiscord ID: \`${identity.discordId}\`` : ""}`),
        ...(admin ? identity.uuids.slice(0, 10).map(mc => field(`Minecraft · ${textName(mc.name)}`, `UUID: \`${mc.uuid}\`\nFirst seen: ${discordTime(mc.firstSeen)}\nLast seen: ${discordTime(mc.lastSeen)}`)) : [field("Minecraft associations", minecraft)]),
        field("Online blacklist", identity.blacklisted ? "Restricted · administrator review required" : "No Discord restriction"),
        ...(identity.uuids.length > 10 ? [field("More associations", `${identity.uuids.length - 10} more. All linked accounts remain usable; association history is available in the private device report.`)] : [])],
      footer: { text: admin ? "Private · shared devices and multiple computers never imply wrongdoing" : "Sharing defaults to On (anon); choose On, On (anon) or Off under Community data sharing without disconnecting chat or cosmetics" } }],
    components: admin ? [] : userButtons() };
}
function pageControls(action: string, target: string, page: number, count: number): Record<string, unknown>[] {
  const buttons = [];
  if (page > 1) buttons.push(button("Previous", `sky:page:${action}:${target || "all"}:${page - 1}`));
  if (count >= 8 && page < 100) buttons.push(button("Next", `sky:page:${action}:${target || "all"}:${page + 1}`));
  return buttons.length ? [row(...buttons)] : [];
}
function sessionState(session: Record<string, unknown>): string {
  const now = Date.now();
  if (session.revokedAt) return "Ended";
  if (Number(session.refreshExpiresAt) > 0 && Number(session.refreshExpiresAt) <= now) return "Expired";
  if (Number(session.expiresAt) > 0 && Number(session.expiresAt) <= now) return "Access token expired; refresh available";
  return session.connectedAt ? "Connection observed; see last activity" : "No active relay connection observed";
}
function reportMessage(command: ParsedCommand, result: CommunityResult): Message {
  const page = Number(command.options.page ?? 1);
  if (command.action === "account" || command.action === "lookup") return result.identity ? identityMessage(result.identity, true) : { content: safeError("account_required") };
  const entries = command.action === "devices" ? result.devices : command.action === "sessions" ? result.sessions : command.action === "audit" ? result.events : command.action === "blacklist" ? result.entries : result.identities;
  let fields: { name: string; value: string; inline: boolean }[] = [];
  if (command.action === "devices") fields = (result.devices ?? []).slice(0, 8).map((device, index) => field(`Device observation ${index + 1 + (page - 1) * 8}`,
    `Identifier: \`${textName(device.identifier)}\`\nSource: ${textName(device.source)} · Platform: ${textName(device.platform)}\nFirst seen: ${discordTime(device.firstSeen)}\nLast seen: ${discordTime(device.lastSeen)}\nMinecraft: ${device.minecraft.map(mc => `${textName(mc.name)} (${mc.uuid})`).join(", ") || "None"}\nAlso observed with: ${device.relatedAccounts.map(owner => `${textName(owner.displayName ?? owner.username)} · <@${owner.discordId}>`).join(", ") || "No other Discord associations observed"}`));
  else if (command.action === "sessions") fields = (result.sessions ?? []).slice(0, 8).map((session, index) => field(`Community connection ${index + 1 + (page - 1) * 8}`,
    `${textName(session.minecraftName ?? result.identity?.uuids.find(mc => mc.uuid === session.minecraftUuid)?.name)} · \`${textName(session.minecraftUuid)}\`\nStarted: ${discordTime(session.connectedAt ?? session.issuedAt)}\nLast activity: ${discordTime(session.lastSeen)}\nAccess expires: ${discordTime(session.expiresAt)}\nState: ${sessionState(session)}`));
  else if (command.action === "audit") fields = (result.events ?? []).slice(0, 8).map(event => { const reason = auditMetadata(event.metadata).reason; return field(`${textName(event.action ?? event.kind)} · ${discordTime(event.at ?? event.createdAt)}`,
    `By ${snowflake(event.actor) ? `<@${event.actor}>` : "System"}${reason ? `\nReason: ${auditReason(reason, 500)}` : ""}`); });
  else if (command.action === "blacklist") fields = (result.entries ?? []).slice(0, 8).map(entry => field(`${textName(entry.kind)} · ${textName(entry.identity)}`,
    `Status: ${entry.active === false || entry.removedAt ? "Removed" : "Restricted"}\nReason: ${auditReason(entry.reason, 500)}\nChanged: ${discordTime(entry.updatedAt ?? entry.createdAt)}`));
  else if (command.action === "search") fields = (result.identities ?? []).slice(0, 8).map(identity => field(textName(identity.displayName ?? identity.username),
    `<@${identity.discordId}>\nMinecraft: ${identity.uuids.map(mc => textName(mc.name)).join(", ") || "None"}`));
  else if (command.action === "overview") fields = Object.entries(result.overview ?? {}).slice(0, 20).map(([name, value]) => field(textName(name.replaceAll("_", " ")), String(value), true));
  // Keep the complete embed below Discord's 6,000-character aggregate limit even for long histories.
  fields = fields.map(value => ({ ...value, value: value.value.length > 600 ? `${value.value.slice(0, 568)}\nFurther associations omitted.` : value.value }));
  return { content: `**Private Sky-Myce ${command.action} report${snowflake(command.target) ? ` for <@${command.target}>` : ""}**\n${fields.length ? `Page ${page}` : "No matching records."}`,
    embeds: fields.length ? [{ title: command.action === "devices" ? "Informational device associations" : `Community ${command.action}`, color: 0x4d88c7, fields,
      footer: { text: command.action === "devices" ? "Administrative metadata only · shared/changed devices never authorize, punish or block users" : "Private administrator report" } }] : [],
    components: pageControls(command.action, command.target, page, entries?.length ?? 0) };
}
function auditMetadata(raw: unknown): Record<string, unknown> {
  if (typeof raw !== "string" || raw.length > 16384) return {};
  try { return object(JSON.parse(raw)) ?? {}; } catch { return {}; }
}
function confirmationMessage(result: CommunityResult): Message {
  const effects: Record<string, string> = {
    blacklist_add: "Restricts online services for this selected identity and ends affected community sessions. Local mod features remain available. Devices and other users sharing hardware are unaffected.",
    blacklist_remove: "Removes this identity's online restriction. The user can reconnect. Device observations and audit history remain informational.",
    session_kick: "Ends this Discord account's current community connections. It does not blacklist the user or restrict computers; they may reconnect.",
    admin_add: "Grants backend administrator access. This private security change is audited.",
    unlink: "Removes only the selected Minecraft association and ends its community sessions. Cosmetics/profile data and unrelated links are retained. Local gameplay keeps working.",
    disconnect: "Ends your current community sessions. Minecraft associations, cosmetics and sharing preferences are retained. Use Connect Discord in the mod to reconnect; local gameplay keeps working.",
  };
  const target = result.options?.kind === "minecraft" ? `Minecraft UUID \`${textName(result.target)}\`` : snowflake(result.target) ? `<@${result.target}> · Discord ID \`${result.target}\`` : textName(result.target);
  return { content: "Review this change, then choose Confirm or Cancel. Nothing has changed yet.", embeds: [{ title: "Confirm community account change", color: 0xe3ad40,
    fields: [field("Action", textName(result.action?.replaceAll("_", " "))), field("Identity", target),
      ...(result.options?.uuid ? [field("Minecraft association", textName(result.options.uuid))] : []), field("Effect", effects[result.action ?? ""] ?? "Review this account change."),
      ...(result.options?.reason ? [field("Reason", auditReason(result.options.reason, 500))] : []), field("Expires", discordTime(result.expiresAt))] }],
    components: [row(button("Confirm", `sky:confirm:${result.token}`, 4), button("Cancel", `sky:cancel:${result.token}`))] };
}
async function runCommand(command: ParsedCommand, env: DiscordAccountsConfig, kind: "user" | "backend", confirmed?: CommunityResult): Promise<string | Message> {
  const store = env.ACCOUNT_STORE.getByName("global");
  if (kind === "backend" && !await store.isAdmin(command.actor)) return safeError("admin_required");
  if (command.identity && typeof command.identity.username === "string" && !command.identity.bot && snowflake(command.target))
    await store.cacheDiscordIdentity(command.actor, command.target, { username: command.identity.username, displayName: String(command.identity.nick ?? command.identity.global_name ?? command.identity.username) });
  if (kind === "user" && command.action === "help") return { content: "**Sky-Myce community help**\nInstall the Fabric mod normally. In Minecraft, choose Connect Discord to open the browser connection. If the browser is unavailable, submit its short-lived code here with `/connect code:...`. No whitelist, activation key, custom Loader or hardware reset is needed.\n\nUse `/status` to review associations, `/account unlink` to remove one Minecraft association, or `/disconnect` to end current online sessions. Local gameplay remains available when offline or disconnected.\n\nCommunity data sharing starts as On (anon): reports are shared anonymously. Choose On, On (anon) or Off in Minecraft under Community data sharing; Off removes or suppresses earlier contributions, and chat and cosmetics remain connected. Device identifiers are private administrative observations and never restrict access.", components: userButtons() };
  if (!confirmed && managed.has(command.action)) {
    const prepared = await store.prepareCommunityManagement(command.actor, kind, command.action, command.target, command.options) as CommunityResult;
    return prepared.ok ? confirmationMessage(prepared) : safeError(prepared.error);
  }
  if (kind === "backend" && reads.has(command.action)) {
    const result = await store.communityRead(command.actor, command.action === "lookup" ? "account" : command.action, command.target,
      { ...command.options, limit: 8 }) as CommunityResult;
    return result.ok ? reportMessage(command, result) : safeError(result.error);
  }
  const result = confirmed ?? await (kind === "backend" ? store.communityManage(command.actor, command.action, command.target, command.options) :
    store.communityUserCommand(command.actor, command.action, command.options)) as CommunityResult;
  if (!result.ok) return safeError(result.error);
  await Promise.allSettled((result.invalidatedAccounts ?? []).flatMap(id => env.ROOMS.split(",").filter(Boolean).map(room => env.RELAY_ROOMS.getByName(room).invalidateAccount(id))));
  const outcomes: Record<string, string> = { connect: "**Discord connected.** Return to Minecraft to continue community features.",
    unlink: "**Minecraft association removed.** Unrelated links and local gameplay are unchanged.", disconnect: "**Community sessions disconnected.** Your Minecraft associations, cosmetics and preferences are retained. Reconnect through Connect Discord in the mod.",
    blacklist_add: "**Identity blacklisted for online services.** Affected community sessions ended. Local gameplay and unrelated users sharing hardware remain unaffected.",
    blacklist_remove: "**Online blacklist entry removed.** The user can reconnect; historical device observations do not restrict access.",
    session_kick: "**Community sessions ended.** The user can reconnect without approval or a hardware reset.", admin_add: "**Backend administrator access updated.** This permission does not control ordinary mod usage." };
  if (command.action === "status" || command.action === "connect") {
    if (!result.identity) return safeError("account_required");
    const message = identityMessage(result.identity, false);
    if (command.action === "connect") message.content = outcomes.connect;
    return message;
  }
  return { content: outcomes[command.action] ?? "Community account change completed.", components: kind === "user" ? userButtons() : [] };
}

/** Discord validates the original signed body; AccountStore rechecks actor and confirmation authority. */
export function handleAccountInteraction(data: Record<string, unknown>, env: DiscordAccountsConfig, ctx: ExecutionContext, kind: "user" | "backend"): Response | null {
  const member = object(data.member), caller = object(member?.user) ?? object(data.user);
  if (kind === "user" && data.type === 2 && object(data.data)?.name === "bazaar") {
    if (!snowflake(caller?.id) || !snowflake(data.id) || data.guild_id !== env.DISCORD_GUILD_ID || !env.ACCOUNT_STORE) return ephemeral(safeError("forbidden"));
    const subs = object(data.data)?.options;
    if (!Array.isArray(subs) || subs.length !== 1) return ephemeral("Choose a Bazaar command.");
    const sub = object(subs[0]), options = sub?.options ?? [];
    if (sub?.type !== 1 || !["stats", "summary", "replays", "privacy"].includes(String(sub.name)) || !Array.isArray(options) || options.length > 1) return ephemeral("Choose a Bazaar command.");
    let period = "all-time";
    if (options.length) { const value = object(options[0]); if (sub.name !== "stats" || value?.name !== "period" || value.type !== 3 || !["all-time", "weekly", "monthly"].includes(String(value.value))) return ephemeral("Choose a valid period."); period = String(value.value); }
    return deferInteraction(data, ctx, async () => {
      const result = await env.ACCOUNT_STORE.getByName("global").discordBazaar({ id: caller.id as string, guildId: env.DISCORD_GUILD_ID!, roles: [], interactionId: data.id as string }, String(sub.name), period);
      if (!result.ok) return safeError(result.error);
      if (sub.name === "privacy") return `Bazaar uses Economy and Leaderboards sharing. Current preferences: ${JSON.stringify(result.sharing)}. Change them in the mod Community Sharing screen. Replay publication and local history are separate.`;
      if (sub.name === "replays") return result.publications.length ? result.publications.map(publication => `${env.SKYMYCE_PUBLIC_URL ?? "https://sky-myce-backend.skyblock-relay.workers.dev"}/community/bazaar/replays/${publication.token}`).join("\n") : "No active explicitly published replays. Private recordings remain on your computer.";
      if (sub.name === "summary") return result.sessionSummary ? `**Your last explicitly shared session summary**\n${Math.round(result.sessionSummary.durationMs / 60000)} minutes · ${result.sessionSummary.completedFlips} completed flips · ${result.sessionSummary.realizedProfit.toLocaleString()} realized coins. Client-observed, not Hypixel-certified.` : "Session summaries stay local until you explicitly share one from Bazaar. No private journal was uploaded.";
      return `**Your linked Bazaar statistics · ${period}**\n${result.profit.toLocaleString()} realized coins · ${result.completedFlips} completed flips · ${result.realizations} supported portions.\nROI: ${result.roi === null ? "unknown" : (result.roi * 100).toFixed(2) + "%"}. Profit per supported flip-hour: ${result.profitPerHour === null ? "unknown" : Math.round(result.profitPerHour).toLocaleString()}.\n${result.coverage} ${result.verification}.`;
    });
  }
  const leaderboardControl = /^sky:leaderboard:(economy|dungeons|rng|other):([a-z_]{1,40}):(all-time|weekly|monthly):(\d{1,3})(?::(uuid|linked))?$/.exec(String(object(data.data)?.custom_id ?? ""));
  if (kind === "user" && (data.type === 2 && object(data.data)?.name === "leaderboard" || data.type === 3 && leaderboardControl)) {
    if (!snowflake(caller?.id) || !snowflake(data.id) || data.guild_id !== env.DISCORD_GUILD_ID || !env.ACCOUNT_STORE) return ephemeral(safeError("forbidden"));
    const options: Record<string, unknown> = leaderboardControl ? { category: leaderboardControl[1], metric: leaderboardControl[2], period: leaderboardControl[3], page: Number(leaderboardControl[4]), ...leaderboardControl[5] ? { identity: leaderboardControl[5] } : {} } : {};
    if (!leaderboardControl) {
      const raw = object(data.data)?.options ?? [];
      if (!Array.isArray(raw) || raw.length > 6) return ephemeral("Choose a valid leaderboard selection.");
      for (const entry of raw) {
        const option = object(entry);
        if (!option || typeof option.name !== "string" || !["category", "metric", "period", "page", "search", "identity"].includes(option.name) || Object.hasOwn(options, option.name) ||
          option.type !== (option.name === "page" ? 4 : 3) || (option.name === "page" ? !Number.isSafeInteger(option.value) : typeof option.value !== "string" || option.value.length > 100)) return ephemeral("Choose a valid leaderboard selection.");
        options[option.name] = option.value;
      }
    }
    return deferInteraction(data, ctx, async () => discordLeaderboardMessage(await env.ACCOUNT_STORE.getByName("global").discordLeaderboard({ id: caller.id as string, guildId: env.DISCORD_GUILD_ID!, roles: [], interactionId: data.id as string }, options)));
  }
  if (data.type === 3) {
    const id = object(data.data)?.custom_id;
    if (typeof id !== "string" || !id.startsWith("sky:")) return null;
    if (!snowflake(caller?.id) || !snowflake(data.id) || data.guild_id !== undefined && data.guild_id !== env.DISCORD_GUILD_ID || !env.ACCOUNT_STORE) return ephemeral(safeError("forbidden"));
    const actor: Actor = { id: caller.id, guildId: String(data.guild_id ?? env.DISCORD_GUILD_ID), roles: [], interactionId: data.id };
    return deferInteraction(data, ctx, async () => {
      const store = env.ACCOUNT_STORE.getByName("global");
      if (/^sky:(confirm|cancel):[A-Za-z0-9_-]{43}$/.test(id)) {
        const [, action, token] = id.split(":");
        const result = await store.confirmCommunityManagement(actor, kind, token, action === "confirm") as CommunityResult;
        if (!result.ok) return safeError(result.error);
        if (result.action === "cancelled") return { content: "Cancelled. No account or blacklist changes were made.", components: [] };
        return runCommand({ actor, action: result.action ?? "", target: result.target ?? actor.id, options: result.options ?? {} }, env, kind, result);
      }
      if (kind === "user" && /^sky:user:(status|help|disconnect)$/.test(id)) return runCommand({ actor, action: id.split(":")[2], target: actor.id, options: {}, identity: caller }, env, "user");
      const legacyReport = /^sky:admin:(info|audit):(\d{17,20})$/.exec(id);
      if (kind === "backend" && legacyReport) return runCommand({ actor, action: legacyReport[1] === "info" ? "account" : "audit", target: legacyReport[2], options: {} }, env, kind);
      const match = /^sky:page:(devices|sessions|audit|blacklist|search):(\d{17,20}|all):(\d{1,3})$/.exec(id);
      if (kind === "backend" && match && Number(match[3]) >= 1 && Number(match[3]) <= 100)
        return runCommand({ actor, action: match[1], target: match[2] === "all" ? "" : match[2], options: { page: Number(match[3]) } }, env, kind);
      return safeError("forbidden");
    });
  }
  if (kind === "user" && object(data.data)?.name === "cosmetics") return null;
  const command = accountCommand(data, kind);
  if (!command) return ephemeral("This command is unavailable. Install the Fabric mod normally and use Connect Discord; older activation and hardware-reset commands have been removed.");
  if (command.actor.guildId !== env.DISCORD_GUILD_ID) return ephemeral(safeError("forbidden"));
  if (!env.ACCOUNT_STORE) return ephemeral("This Sky-Myce service is currently unavailable.");
  return deferInteraction(data, ctx, () => runCommand(command, env, kind));
}

export async function handleAccountAutocomplete(data: Record<string, unknown>, env: DiscordAccountsConfig, kind: "user" | "backend"): Promise<Response> {
  const empty = () => Response.json({ type: 8, data: { choices: [] } });
  try {
    const command = object(data.data), member = object(data.member), caller = object(member?.user);
    if (data.type !== 4 || !command || !snowflake(data.id) || !snowflake(caller?.id) || data.guild_id !== env.DISCORD_GUILD_ID || !env.ACCOUNT_STORE ||
      !Array.isArray(command.options) || command.options.length !== 1) return empty();
    const actor: Actor = { id: caller.id, guildId: String(data.guild_id), roles: [], interactionId: data.id }, store = env.ACCOUNT_STORE.getByName("global");
    if (kind === "backend" && !await store.isAdmin(actor)) return empty();
    const sub = object(command.options[0]);
    if (sub?.type !== 1 || !Array.isArray(sub.options) || sub.options.length > 8) return empty();
    const values = sub.options.map(object), focused = values.filter(value => value?.focused === true);
    if (focused.length !== 1 || focused[0]?.type !== 3 || typeof focused[0].value !== "string" || focused[0].value.length > 100) return empty();
    const query = focused[0].value.toLowerCase();
    let choices: { name: string; value: string }[] = [];
    if (kind === "user" && command.name === "account" && sub.name === "unlink" && focused[0].name === "uuid") {
      const result = await store.communityUserCommand(actor, "status", {}) as CommunityResult;
      choices = result.identity?.uuids.filter(mc => mc.name.toLowerCase().includes(query) || mc.uuid.includes(query.replaceAll("-", "")))
        .map(mc => ({ name: mc.name.slice(0, 100), value: mc.uuid })) ?? [];
    } else if (kind === "backend" && command.name === "skyhawk" && sub.name === "search" && focused[0].name === "query") {
      if (!query.trim()) return empty();
      const result = await store.communityRead(actor, "search", "", { query, limit: 25 }) as CommunityResult;
      choices = result.identities?.map(identity => ({ name: `${identity.displayName ?? identity.username ?? "Discord user"} · ${identity.uuids.map(mc => mc.name).join(", ") || "no Minecraft links"}`.slice(0, 100), value: identity.discordId })) ?? [];
    }
    return Response.json({ type: 8, data: { choices: choices.slice(0, 25) } });
  } catch { return empty(); }
}
