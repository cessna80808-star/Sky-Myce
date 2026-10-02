import { createLoaderDownload, approvedLoader, type DiscordDownloadConfig } from "./discord-download";
import type { AccountStore, AccountsConfig, AccountSummary, AccountDetails, ManagementResult } from "./accounts";
import type { RelayRoom } from "./index";
import { AuthorizationError, releaseManifest } from "./drm";
import { auditReason } from "./discord-notifications";

export type DiscordAccountsConfig = AccountsConfig & DiscordDownloadConfig & {
  ACCOUNT_STORE: DurableObjectNamespace<AccountStore>;
  DISCORD_USER_APPLICATION_ID?: string;
  DISCORD_USER_PUBLIC_KEY?: string;
  USER_BOT_TOKEN?: string;
  USER_BOT_APPLICATION_ID?: string;
  USER_BOT_PUBLIC_KEY?: string;
  BACKEND_BOT_TOKEN?: string;
  BACKEND_BOT_APPLICATION_ID?: string;
  BACKEND_BOT_PUBLIC_KEY?: string;
  DISCORD_BACKEND_APPLICATION_ID?: string;
  DISCORD_BACKEND_PUBLIC_KEY?: string;
  DISCORD_BACKEND_BOT_TOKEN?: string;
  DISCORD_LOADER_DELIVERY_ENABLED?: string;
  DISCORD_AUDIT_CHANNEL_ID?: string;
  DISCORD_SECURITY_CHANNEL_ID?: string;
  DISCORD_LICENSE_CHANNEL_ID?: string;
  ROOMS: string;
  RELAY_ROOMS: DurableObjectNamespace<RelayRoom>;
};
type Actor = { id: string; guildId: string; roles: string[]; interactionId: string };
type ParsedCommand = { actor: Actor; action: string; target: string; options: Record<string, unknown>; identity?: Record<string, unknown> };
type Message = { content?: string; embeds?: Record<string, unknown>[]; components?: Record<string, unknown>[] };
const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);
const ephemeral = (content: string) => Response.json({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
const userActions = new Set(["status", "key", "loader", "help", "approve", "link", "unlink", "reset"]);
const backendActions = new Set(["admin_add", "authorise", "license_status", "license_revoke", "license_restore", "license_extend", "account_lookup",
  "account_unlink", "account_suspend", "account_unsuspend", "hwid_reset", "hwid_revoke", "hwid_bind", "session_kick", "warning_add", "warning_remove", "audit",
  "info", "debug", "sessions", "uuids", "overview", "search", "lookup", "event", "builds", "build_revoke", "build_restore"]);
const destructive = new Set(["license_revoke", "account_suspend", "account_unlink", "hwid_reset", "hwid_revoke", "hwid_bind", "session_kick", "build_revoke", "admin_add", "unlink", "reset"]);

/** Only called after exact-body Discord signature verification, never from a bot-supplied actor JSON endpoint. */
export function accountCommand(data: Record<string, unknown>, kind: "user" | "backend"): ParsedCommand | null {
  const command = object(data.data), member = object(data.member), user = object(member?.user);
  if (data.type !== 2 || !command || !snowflake(data.id) || !snowflake(data.guild_id) || !snowflake(user?.id) ||
      !Array.isArray(member?.roles) || member.roles.some(role => !snowflake(role)) || typeof command.name !== "string") return null;
  let action = command.name;
  let raw = command.options ?? [];
  if (!Array.isArray(raw) || raw.length > 8) return null;
  if (!["authorise", "audit", ...(kind === "user" ? ["status", "key", "loader", "help"] : [])].includes(action)) {
    const sub = object(raw[0]);
    if (raw.length !== 1 || sub?.type !== 1 || typeof sub.name !== "string") return null;
    action = kind === "user" && (action === "account" || action === "hwid" && sub.name === "reset") ? sub.name : `${action}_${sub.name}`;
    raw = sub.options ?? [];
    if (!Array.isArray(raw) || raw.length > 8) return null;
  }
  const aliases: Record<string, string> = { license_whitelist: "authorise", license_key: "authorise", license_info: "info", license_debug: "debug",
    license_status: "info", license_sessions: "sessions", license_uuids: "uuids", license_audit: "audit", license_reset_hardware: "hwid_reset",
    "license_reset-hardware": "hwid_reset", "license_force-logout": "session_kick", "license_uuid-remove": "account_unlink",
    account_lookup: "info", skyhawk_overview: "overview", skyhawk_search: "search", skyhawk_lookup: "lookup", skyhawk_event: "event", build_list: "builds" };
  const keyReplacement = action === "license_key";
  action = aliases[action] ?? action;
  if (!(kind === "user" ? userActions : backendActions).has(action)) return null;
  const options: Record<string, unknown> = {};
  const permitted: Record<string, number> = kind === "user" ?
    action === "reset" ? { confirm: 5 } : action === "unlink" ? { uuid: 3, confirm: 5 } : ["link", "approve"].includes(action) ? { code: 3 } : {} :
    { user: 6, ...action === "authorise" ? { reissue: 5 } : action === "license_extend" ? { days: 4 } : action === "account_unlink" ? { uuid: 3, account: 3, confirm: 5 } :
      action.startsWith("hwid_") ? { reason: 3, ...action === "hwid_bind" ? { "device-hash": 3 } : {} } : action === "warning_add" ? { reason: 3 } : action === "warning_remove" ? { "warning-id": 3 } : {} };
  if (kind === "backend" && action === "audit") Object.assign(permitted, { event: 3, severity: 3, days: 4, page: 4 });
  if (kind === "backend" && action === "search") Object.assign(permitted, { query: 3 });
  if (kind === "backend" && action === "lookup") Object.assign(permitted, { "discord-id": 3 });
  if (kind === "backend" && action === "event") Object.assign(permitted, { id: 4 });
  if (kind === "backend" && action.startsWith("build_")) Object.assign(permitted, { build: 3, kind: 3 });
  for (const rawOption of raw) {
    const item = object(rawOption);
    if (!item || typeof item.name !== "string" || !Object.hasOwn(permitted, item.name) ||
        (item.type !== permitted[item.name] && !(action === "authorise" && item.name === "user" && item.type === 3)) || Object.hasOwn(options, item.name)) return null;
    if (item.type === 5 ? typeof item.value !== "boolean" : item.type === 4 ? !Number.isSafeInteger(item.value) : typeof item.value !== "string" || item.value.length > 500) return null;
    options[item.name] = item.value;
  }
  if (kind === "user" && (action === "reset" ? options.confirm !== true : ["link", "approve"].includes(action) && !options.code)) return null;
  if (action === "unlink" && (options.confirm !== true || typeof options.uuid !== "string" || !/^[a-f0-9]{32}$/i.test(options.uuid))) return null;
  if (options.account !== undefined) { options.uuid = options.account; delete options.account; options.confirm = true; }
  if (keyReplacement && options.reissue === undefined) options.reissue = true;
  const target = kind === "user" ? user.id : action.startsWith("build_") ? options.build : action === "lookup" ? options["discord-id"] : options.user;
  if (kind === "backend" && !["overview", "search", "event", "builds"].includes(action) &&
    (action.startsWith("build_") ? typeof target !== "string" || !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(target) : !snowflake(target))) return null;
  delete options.user;
  delete options.build; delete options["discord-id"];
  if (options["device-hash"] !== undefined) { options.deviceHash = options["device-hash"]; delete options["device-hash"]; }
  if (options["warning-id"] !== undefined) { options.warningId = options["warning-id"]; delete options["warning-id"]; }
  const resolved = object(command.resolved), users = object(resolved?.users), members = object(resolved?.members);
  const identity = object(users?.[String(target)]) ?? (target === user.id ? user : undefined);
  const selectedMember = object(members?.[String(target)]);
  return { actor: { id: user.id, guildId: data.guild_id, roles: member.roles as string[], interactionId: data.id }, action,
    target: typeof target === "string" ? target : "", options, ...(identity ? { identity: { ...identity, nick: selectedMember?.nick ?? (target === user.id ? member.nick : undefined) } } : {}) };
}

class DiscordDeliveryError extends Error { constructor(readonly status: number) { super("Discord delivery unavailable"); } }
async function discordRequest(path: string, token: string | undefined, body?: unknown, method = "POST"): Promise<Record<string, unknown>> {
  if (token !== undefined && !token) throw new DiscordDeliveryError(503);
  for (let attempt = 0; attempt < 3; attempt++) {
    const form = body instanceof FormData;
    const response = await fetch(`https://discord.com/api/v10${path}`, { method, redirect: "manual", signal: AbortSignal.timeout(8000),
      headers: { ...token ? { Authorization: `Bot ${token}` } : {}, ...form ? {} : { "Content-Type": "application/json" } },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body) });
    if (response.status === 429 && attempt < 2) {
      const retry = Number((await response.json() as { retry_after?: number }).retry_after);
      if (retry > 0 && retry <= 2) { await new Promise(resolve => setTimeout(resolve, retry * 1000)); continue; }
    }
    if (!response.ok) throw new DiscordDeliveryError(response.status);
    return response.status === 204 ? {} : await response.json() as Record<string, unknown>;
  }
  throw new DiscordDeliveryError(429);
}
async function sendDm(token: string | undefined, user: string, content: string, interactionId?: string): Promise<void> {
  if (!token) throw new DiscordDeliveryError(503);
  const channel = await discordRequest("/users/@me/channels", token, { recipient_id: user });
  if (!snowflake(channel.id)) throw new DiscordDeliveryError(502);
  await discordRequest(`/channels/${channel.id}/messages`, token, { content, components: userButtons(), allowed_mentions: { parse: [] },
    ...(interactionId ? { nonce: interactionId, enforce_nonce: true } : {}) });
}
async function authorizationDm(env: DiscordAccountsConfig, target: string, activation: string, interactionId: string, account?: AccountSummary, expiresAt?: number): Promise<void> {
  env = { ...env, DISCORD_BACKEND_BOT_TOKEN: env.DISCORD_BACKEND_BOT_TOKEN ?? env.USER_BOT_TOKEN };
  if (!env.DISCORD_BACKEND_BOT_TOKEN) throw new DiscordDeliveryError(503);
  const channel = await discordRequest("/users/@me/channels", env.DISCORD_BACKEND_BOT_TOKEN, { recipient_id: target });
  if (!snowflake(channel.id)) throw new DiscordDeliveryError(502);
  const state = account?.activated ? "Your account has already been activated. This replacement code is for setting up the Loader again or adding another Minecraft account."
    : "Your account is pending activation.";
  const expiry = expiresAt === undefined ? "in 24 hours" : discordTime(expiresAt);
  if (env.DISCORD_LOADER_DELIVERY_ENABLED === "false") {
    await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, {
      content: `**Your Sky-Myce activation code**\n\n${state}\n\n\`${activation}\`\n\nExpires ${expiry}. Keep this code private; never enter it in chat.\n\nLoader downloads are currently unavailable. Wait for the approved loader before using this code. Receiving a code does not complete activation.`,
      components: userButtons(), nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] } });
    return;
  }
  const release = await approvedLoader(env);
  const object = await env.PRIVATE_ARTIFACTS?.get(release.key);
  if (!object) throw new DiscordDeliveryError(503);
  const content = `**Your Sky-Myce activation code**\n\n${state}\n\n\`${activation}\`\n\nExpires ${expiry}. Keep this code private; never enter it in chat.\n\nYour secure Sky-Myce Loader is attached below.\nLoader ${release.version} · Minecraft ${release.minecraft}\n\n1. Use Java 25, Fabric, Fabric API and Fabric Language Kotlin. Close Minecraft, run the loader JAR and choose your Minecraft instance folder.\n2. Start your instance normally. Enter this code in the Sky-Myce startup window and press Confirm and start.\n3. The loader verifies and installs Sky-Myce, then starts Minecraft automatically. No manual restart is needed.\n\nUpdates are checked before startup. Use Status to check access, Get key to recover a lost code, or Help for setup guidance.`;
  if (object.size <= 10 * 1024 * 1024) {
    const form = new FormData();
    form.set("payload_json", JSON.stringify({ content, components: userButtons(), nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] },
      attachments: [{ id: 0, filename: "Sky-Myce-Loader.jar" }] }));
    form.set("files[0]", new Blob([await object.arrayBuffer()], { type: "application/java-archive" }), "Sky-Myce-Loader.jar");
    try { await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, form); return; }
    catch (error) { if (!(error instanceof DiscordDeliveryError) || ![400, 413].includes(error.status)) throw error; }
  }
  const link = await createLoaderDownload(env, target);
  await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, {
    content: content.replace("Your secure Sky-Myce Loader is attached below.", `Download your secure Sky-Myce Loader (expires in 15 minutes):\n${link}`),
    components: userButtons(), nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] } });
}

const discordTime = (milliseconds: number) => `<t:${Math.floor(milliseconds / 1000)}:R> (<t:${Math.floor(milliseconds / 1000)}:f>)`;

function accountText(account: AccountSummary, target?: string): string {
  const now = Date.now();
  const expired = account.licenseExpiresAt !== null && account.licenseExpiresAt <= now;
  const licensed = account.licenseStatus === "active" && !expired;
  const state = account.status !== "active" ? "Suspended" : !account.activated ? "Pending activation" : "Activated";
  const license = account.licenseStatus === "revoked" ? "Revoked" : account.licenseStatus !== "active" ? "Not authorized" : expired ? "Expired" : "Active";
  const next = account.status !== "active" || !licensed ? "Ask a Sky-Myce admin to review this account's access."
    : !account.activated ? "Start your instance, enter the DM code in the Sky-Myce startup window, and press Confirm and start. Minecraft opens automatically after setup."
      : !account.deviceRegistered ? "Open Sky-Myce Loader to check access on the device you want to use."
        : "Your device registration is persistent. Start Minecraft normally; restarts, reconnects and updates need no HWID reset.";
  return `**Sky-Myce account${target ? ` for <@${target}>` : ""}**\nStatus: ${state}\nRole: ${account.isAdmin ? "Admin" : "User"}\nLicense: ${license}` +
    (account.licenseExpiresAt === null ? "" : `\nLicense expiry: ${discordTime(account.licenseExpiresAt)}`) +
    `\nMinecraft accounts: ${account.uuids.length} linked\nDevice: ${account.deviceRegistered ? "Verified" : "Not registered"}` +
    `\n\n${next}`;
}
function safeError(code: unknown): string {
  const messages: Record<string, string> = { forbidden: "You do not have permission to use this Sky-Myce command.",
    admin_required: "Only Sky-Myce admin accounts can use Backend Bot commands. Ask an existing Sky-Myce admin if you need access.",
    device_reset_cooldown: "An actual hardware transfer is allowed once every seven days. Your registered device continues working normally; restarts and reconnects do not use this allowance.",
    HWID_MISMATCH: "This license is already bound to another device. To intentionally move to another computer, use `/hwid reset confirm:true` on the User Bot; the seven-day hardware-transfer policy applies.",
    hardware_proof_required: "Update your Sky-Myce Loader to verify this device's persistent hardware identity. No HWID reset is needed.",
    account_required: "No Sky-Myce account was found. Ask a Sky-Myce admin to authorize this Discord account.",
    already_authorized: "This account already has a Sky-Myce license. No new activation code was issued.\n\nUse `/account lookup` to check whether activation is complete. To send a replacement code and loader, use `/authorise` with `reissue:true`; this invalidates earlier unused codes.",
    account_ineligible: "This account cannot receive that change while suspended or revoked. Use `/account lookup` to review it, then resolve the restriction before retrying.",
    interaction_replayed: "This Sky-Myce request has already been handled.",
    invalid_link: "That linking code is invalid or expired. Generate a fresh code in Sky-Myce and use it with the same Discord account.",
    invalid_approval: "That approval code is invalid or expired. Return to the Loader and start a fresh approval request.",
    invalid_discord_id: "Choose an existing human Discord account and try again.",
    invalid_duration: "Choose a license extension between 1 and 3,650 days.",
    invalid_uuid: "Choose a Minecraft account linked to the selected Discord user and try again.",
    invalid_device: "That device reference is invalid. Check the approved device reference and try again.",
    invalid_warning: "Check the warning reason or reference and try again.",
    minecraft_link_required: "That Minecraft account is not linked to this Sky-Myce account. Check the account status before retrying.",
    rate_limited: "Please wait before sending another Sky-Myce request.",
    license_revoked: "This Sky-Myce license has been revoked. Contact a Sky-Myce admin if this was a mistake.",
    license_required: "This account needs a Sky-Myce license. Ask a Sky-Myce admin for authorization.",
    license_expired: "This Sky-Myce license has expired. Ask a Sky-Myce admin to review or extend it.",
    account_suspended: "This Sky-Myce account is suspended. Contact a Sky-Myce admin to review it.",
    device_revoked: "Device enrollment is blocked. Ask a Sky-Myce admin to review your access.",
    key_reissue_cooldown: "A replacement code was requested recently. Wait one minute, then try Get key again.",
    loader_delivery_cooldown: "A private Loader download was requested recently. Wait one minute and try again.",
    confirmation_expired: "This confirmation has expired or was already handled. Run the command again to review a fresh confirmation.",
    confirmation_stale: "The account changed after this confirmation was opened. Run the command again and review its current state.",
    BUILD_REVOKED: "This Sky-Myce build has been revoked. Get the current approved Loader or ask an admin for help.",
    invalid_build: "Choose a Sky-Myce release version from the suggestions.",
    invalid_request: "Check the requested options and try again.",
    event_not_found: "That audit event could not be found.",
    authorization_renewal_required: "Open the Sky-Myce Loader to refresh your authorization. Your registered device remains unchanged.",
    authorization_unavailable: "This Sky-Myce service is currently unavailable." };
  return typeof code === "string" && Object.hasOwn(messages, code) ? messages[code] : "Sky-Myce could not complete this request. Try again, or ask a Sky-Myce admin to check the account.";
}

const textName = (value: unknown) => String(value ?? "Discord user").replace(/[\x00-\x1f\x7f]/g, "").replace(/([\\`*_~|<>])/g, "\\$1").slice(0, 100);
const button = (label: string, id: string, style = 2) => ({ type: 2, style, label, custom_id: id });
const row = (...buttons: Record<string, unknown>[]) => ({ type: 1, components: buttons });
const userButtons = () => [row(button("Status", "sky:user:status"), button("Get key", "sky:user:key"), button("Loader", "sky:user:loader"), button("Help", "sky:user:help"))];
const field = (name: string, value: string, inline = true) => ({ name, value: value.slice(0, 1024) || "Unavailable", inline });
function infoMessage(account: AccountSummary, details?: AccountDetails, identity?: Record<string, unknown>, debug = false): Message {
  const now = Date.now(), expired = account.licenseExpiresAt !== null && account.licenseExpiresAt <= now;
  const state = account.status !== "active" ? "Suspended" : account.licenseStatus === "revoked" ? "Revoked" : expired ? "Expired" : !account.activated ? "Pending activation" : "Activated";
  const display = identity?.nick ?? identity?.global_name ?? details?.displayName ?? identity?.username ?? details?.discordUsername;
  const username = identity?.username ?? details?.discordUsername;
  const fields = [field("Discord user", `${textName(display)}${username ? ` (@${textName(username)})` : ""}\n<@${account.discordId}>`, false),
    field("Status", state), field("License", account.licenseStatus === "active" && !expired ? "Active" : expired ? "Expired" : textName(account.licenseStatus)),
    field("Minecraft accounts", account.uuids.map(mc => textName(mc.name)).join(", ") || "None linked", false),
    field("Device bound", account.deviceRegistered ? "Yes" : "No"), field("Bound date", account.hwidBoundAt ? discordTime(account.hwidBoundAt) : "Not registered"),
    field("Hardware transfer", !account.deviceRegistered || !account.nextHwidChangeAllowedAt || account.nextHwidChangeAllowedAt <= now ? "Available" : `Available ${discordTime(account.nextHwidChangeAllowedAt)}`),
    field("License expiry", account.licenseExpiresAt ? discordTime(account.licenseExpiresAt) : "No expiry")];
  if (details) fields.push(field("Created", discordTime(details.createdAt)), field("Build", details.buildId ?? "No verified launch yet"),
    field("Loader version", details.loaderVersion ?? "No verified launch yet"), field("Lease expiry", details.lease ? discordTime(details.lease.expiresAt) : "No active lease"),
    field("Session", details.session ? `${details.session.state === "online" ? "Online" : "Offline"} · ${textName(details.session.minecraftName)}` : "Offline"),
    field("Connected", details.session ? discordTime(details.session.connectedAt) : "No relay connection"),
    field("Heartbeat", details.session?.state === "online" ? "Healthy" : "Offline or stale"),
    field("Last heartbeat", details.session ? discordTime(details.session.lastHeartbeat) : "No relay connection"));
  if (debug && details?.debug) fields.push(field("Technical account reference", account.accountId, false), field("Protocol / generation", `${details.debug.protocol} / ${details.debug.generation}`),
    field("Policy", details.debug.policyVersion), field("Device reference", details.debug.deviceReference ?? "None", false),
    field("Installation reference", details.debug.installationId ?? "None", false), field("Minecraft UUIDs", account.uuids.map(mc => `${textName(mc.name)}: ${mc.uuid}`).join("\n") || "None", false),
    field("Session reference", details.session?.sessionId ?? "None", false));
  const avatar = typeof identity?.avatar === "string" && /^(a_)?[a-f0-9]{32}$/.test(identity.avatar) ? `https://cdn.discordapp.com/avatars/${account.discordId}/${identity.avatar}.png` : undefined;
  return { content: accountText(account), embeds: [{ title: debug ? "Sky-Myce license · admin debug" : "Sky-Myce license", color: state === "Activated" ? 0x35b779 : 0xe3ad40,
    fields, ...(avatar ? { thumbnail: { url: avatar } } : {}), footer: { text: "Private · device binding persists across restarts and updates" } }] };
}
function confirmationMessage(result: ManagementResult, identity?: Record<string, unknown>): Message {
  const effects: Record<string, string> = { license_revoke: "Revokes the license and invalidates current sessions. An admin can restore the license.",
    account_suspend: "Suspends this account and invalidates current sessions. An admin can remove the suspension.",
    account_unlink: result.options?.uuid ? "Removes the selected Minecraft account and invalidates its access. Linking it again requires a verified Minecraft proof." : "Unlinks every Minecraft account and clears device access. Fresh authorization is required.",
    unlink: "Removes this Minecraft link. Linking it again requires a verified in-game proof.",
    hwid_reset: "Clears the current device binding and invalidates sessions. Sends a new private enrollment code. The transfer cooldown remains unchanged.",
    hwid_revoke: "Revokes device access and blocks enrollment. An admin reset is required before enrolling again.",
    hwid_bind: "Overrides the device assignment and invalidates affected sessions, including any previous owner. This is an audited administrator override.",
    session_kick: "Invalidates current sessions. The Loader can sign in again if the account remains eligible.",
    reset: "Prepares a private hardware-transfer code. The current device remains bound until an actual transfer; the seven-day policy applies.",
    build_revoke: "Blocks this build from new authorization and private distribution. Existing sessions on the build lose access. An admin can restore it.",
    admin_add: "Grants backend administrator access. This security change is audited; removing access requires an administrator review." };
  const selected = result.account?.uuids.filter(mc => !result.options?.uuid || mc.uuid === result.options.uuid).map(mc => textName(mc.name)).join(", ") || "None linked";
  return { content: "Review this change, then choose Confirm or Cancel. Nothing has changed yet.", embeds: [{ title: "Confirm Sky-Myce change", color: 0xe3ad40,
    fields: [field("Action", textName(result.action?.replaceAll("_", " "))), field("Target", snowflake(result.target) ? `${textName(identity?.nick ?? identity?.global_name ?? result.details?.displayName ?? result.details?.discordUsername ?? identity?.username)} · <@${result.target}>` : `${textName(result.options?.kind ?? "mod")} ${textName(result.target)}`, false),
      field("Minecraft accounts affected", selected, false), field("Effect and recovery", effects[result.action ?? ""] ?? "Review this security-sensitive change before continuing.", false),
      ...(result.affectedAccounts?.filter(details => details.account.discordId !== result.target).map(details => field("Previous device owner affected",
        `${textName(details.displayName ?? details.discordUsername)} · <@${details.account.discordId}>\nMinecraft: ${details.account.uuids.map(mc => textName(mc.name)).join(", ") || "None linked"}\nTheir device binding and current sessions will be invalidated.`, false)) ?? []),
      ...(result.options?.reason ? [field("Reason", auditReason(result.options.reason, 500), false)] : []), field("Confirmation expires", discordTime(result.expiresAt ?? Date.now()), false)] }],
    components: [row(button("Confirm", `sky:confirm:${result.token}`, 4), button("Cancel", `sky:cancel:${result.token}`))] };
}

/** Receives Discord-signed data; the AccountStore performs the independent final authorization and replay check. */
export function handleAccountInteraction(data: Record<string, unknown>, env: DiscordAccountsConfig, ctx: ExecutionContext, kind: "user" | "backend"): Response | null {
  if (data.type === 3) {
    const id = object(data.data)?.custom_id;
    if (typeof id !== "string" || !id.startsWith("sky:")) return null;
    const member = object(data.member), user = object(member?.user) ?? object(data.user);
    if (!snowflake(user?.id) || !snowflake(data.id) || data.guild_id !== undefined && data.guild_id !== env.DISCORD_GUILD_ID) return ephemeral(safeError("forbidden"));
    const actor: Actor = { id: user.id, guildId: String(data.guild_id ?? env.DISCORD_GUILD_ID), roles: [], interactionId: data.id };
    return deferInteraction(data, ctx, async () => {
      const store = env.ACCOUNT_STORE.getByName("global");
      if (/^sky:(confirm|cancel):[A-Za-z0-9_-]{43}$/.test(id)) {
        const [, action, token] = id.split(":");
        const result = await store.confirmManagement(actor, kind, token, action === "confirm");
        if (!result.ok) return safeError(result.error);
        if (result.action === "cancelled") return { content: "Cancelled. No account, license or device changes were made.", components: [] };
        return runCommand({ actor, action: result.action ?? "", target: result.target ?? actor.id, options: result.options ?? {} }, env, kind, result);
      }
      if (/^sky:user:(status|key|loader|help)$/.test(id)) return runCommand({ actor, action: id.split(":")[2], target: actor.id, options: {}, identity: user }, env, "user");
      if (kind !== "backend" || !await store.isAdmin(actor)) return safeError("admin_required");
      if (/^sky:admin:(info|audit):\d{17,20}$/.test(id)) {
        const [, , action, target] = id.split(":");
        return readMessage({ actor, action, target, options: {} }, env);
      }
      const page = /^sky:audit:(\d{17,20}|all):(\d{1,12}):(\d{1,4}):(all|information|warning|security|critical):([a-z_]{1,64}|all)$/.exec(id);
      if (page && id.length <= 100) return readMessage({ actor, action: "audit", target: page[1] === "all" ? "" : page[1],
        options: { before: Number(page[2]), days: Number(page[3]), ...(page[4] !== "all" ? { severity: page[4] } : {}), ...(page[5] !== "all" ? { event: page[5] } : {}) } }, env);
      return "This Sky-Myce control is unavailable. Run the command again.";
    });
  }
  if (kind === "user" && !["account", "hwid", "status", "key", "loader", "help"].includes(String(object(data.data)?.name))) return null;
  const command = accountCommand(data, kind);
  if (!command || typeof data.token !== "string" || !/^[A-Za-z0-9._-]{20,512}$/.test(data.token) || !snowflake(data.application_id)) return ephemeral("Invalid Sky-Myce command. Confirm the requested action and try again.");
  if (command.actor.guildId !== env.DISCORD_GUILD_ID) return ephemeral("You do not have permission to use this Sky-Myce command here.");
  if (!env.ACCOUNT_STORE) return ephemeral("This Sky-Myce service is currently unavailable.");
  return deferInteraction(data, ctx, () => runCommand(command, env, kind));
}

/** Acknowledge before slow storage/relay operations, then finish the private response. */
export function deferInteraction(data: Record<string, unknown>, ctx: ExecutionContext, operation: () => Promise<string | Message>): Response {
  if (typeof data.token !== "string" || !/^[A-Za-z0-9._-]{20,512}$/.test(data.token) || !snowflake(data.application_id)) return ephemeral("Invalid Sky-Myce command. Confirm the requested action and try again.");
  ctx.waitUntil(operation().catch(() => "This Sky-Myce service is currently unavailable.").then(async content => {
    await discordRequest(`/webhooks/${data.application_id}/${data.token}/messages/@original`, undefined,
      { ...(typeof content === "string" ? { content: content.slice(0, 1900), embeds: [], components: [] } : { ...content, content: content.content?.slice(0, 1900), components: content.components ?? [] }), allowed_mentions: { parse: [] } }, "PATCH");
  }).catch(() => { console.warn("Sky-Myce Discord interaction completion unavailable."); }));
  return Response.json({ type: 5, data: { flags: 64 } });
}

async function readMessage(command: ParsedCommand, env: DiscordAccountsConfig): Promise<string | Message> {
  const store = env.ACCOUNT_STORE.getByName("global"), action = command.action === "lookup" ? "info" : command.action;
  const options = { ...command.options };
  if (action === "audit") {
    const days = Number(options.days ?? 30), page = Number(options.page ?? 1);
    if (!Number.isSafeInteger(days) || days < 1 || days > 3650 || !Number.isSafeInteger(page) || page < 1 || page > 100) return safeError("invalid_request");
    options.since = Date.now() - days * 86400000; options.limit = 8;
    // Cursor queries keep each response bounded even when an explicit page is requested.
    for (let i = 1; i < page; i++) {
      const previous = await store.managementRead(command.actor, "audit", command.target, options);
      if (!previous.ok) return safeError(previous.error);
      if (!previous.nextBefore) return "No audit events on that page. Use an earlier page or broaden the filters.";
      options.before = previous.nextBefore;
    }
  }
  const result = await store.managementRead(command.actor, action, command.target, options);
  if (!result.ok) return safeError(result.error);
  if (result.event) return { content: `**Private audit event ${result.event.id}**`, embeds: [{ title: textName(result.event.action.replaceAll("_", " ")),
    fields: [field("Severity", result.event.severity.toUpperCase()), field("Server time", discordTime(result.event.at)),
      field("Actor", snowflake(result.event.actor) ? `<@${result.event.actor}>` : "System"),
      ...["discordName", "minecraftName", "minecraftUuid", "buildId", "loaderVersion", "sessionId", "reason", "error"].filter(key => result.event!.metadata[key] !== undefined)
        .map(key => field(key.replace(/([A-Z])/g, " $1"), key === "reason" ? auditReason(result.event!.metadata[key], 500) : textName(result.event!.metadata[key]), false))] }] };
  if (result.account && ["info", "debug", "sessions", "uuids"].includes(action)) return infoMessage(result.account, result.details, command.identity, action === "debug");
  if (action === "audit") {
    const events = result.events ?? [];
    const fields = events.map(event => field(`${event.severity.toUpperCase()} · ${textName(event.action.replaceAll("_", " "))}`,
      `${discordTime(event.at)}\nBy ${snowflake(event.actor) ? `<@${event.actor}>` : "System"}${event.override ? " · Admin override" : ""}` +
      (event.metadata.minecraftName ? `\nMinecraft: ${textName(event.metadata.minecraftName)}` : "") +
      (event.metadata.reason ? `\nReason: ${auditReason(event.metadata.reason, 500)}` : ""), false));
    const next = `sky:audit:${command.target || "all"}:${result.nextBefore}:${options.days ?? 30}:${options.severity ?? "all"}:${options.event ?? "all"}`;
    return { content: `**Private audit${command.target ? ` for <@${command.target}>` : ""}**\n${events.length ? `${events.length} events · newest first` : "No events match these filters."}`,
      embeds: [{ title: "Sky-Myce audit", description: `Last ${options.days ?? 30} days · ${textName(options.severity ?? "all severities")} · ${textName(options.event ?? "all events")}`, fields }],
      components: result.nextBefore && next.length <= 100 ? [row(button("Older events", next))] : [] };
  }
  if (result.overview) return { content: "**Sky-Myce overview**", embeds: [{ title: "Sky-Myce licensing overview", fields: Object.entries(result.overview).map(([key, value]) => field(key.replace(/([A-Z])/g, " $1"), String(value))) }] };
  if (result.accounts) return { content: result.accounts.length ? "**Matching Sky-Myce accounts**" : "No registered accounts match that search.",
    embeds: [{ title: "Account search", fields: result.accounts.slice(0, 20).map(details => field(textName(details.displayName ?? details.discordUsername),
      `<@${details.account.discordId}> · ${details.account.activated ? "Activated" : "Pending"}\n${details.account.uuids.map(mc => textName(mc.name)).join(", ").slice(0, 120) || "No linked Minecraft accounts"}`, false)) }] };
  if (result.builds) {
    const releases = [...result.builds];
    for (const kind of ["loader", "mod"] as const) {
      const version = kind === "loader" ? (await approvedLoader(env).catch(() => null))?.version : (await releaseManifest(env).catch(() => null))?.manifest.version;
      if (version && !releases.some(build => build.kind === kind && build.version === version)) releases.unshift({ kind, version, revoked_at: null });
    }
    return { content: "**Sky-Myce builds**", embeds: [{ title: "Build authorization", fields: releases.slice(0, 25).map(build => field(`${textName(build.kind)} ${textName(build.version)}`, build.revoked_at ? "Revoked" : "Allowed")), footer: { text: "Mod and loader authorization is checked by the backend on every protected route." } }] };
  }
  return "Sky-Myce request completed.";
}

/** Autocomplete returns names with stable identifiers only after backend authorization. */
export async function handleAccountAutocomplete(data: Record<string, unknown>, env: DiscordAccountsConfig, kind: "user" | "backend"): Promise<Response> {
  const empty = () => Response.json({ type: 8, data: { choices: [] } });
  try {
    const command = object(data.data), member = object(data.member), user = object(member?.user);
    if (data.type !== 4 || kind !== "backend" || !command || !snowflake(data.id) || !snowflake(user?.id) || data.guild_id !== env.DISCORD_GUILD_ID || !env.ACCOUNT_STORE) return empty();
    const actor: Actor = { id: user.id, guildId: String(data.guild_id), roles: [], interactionId: data.id };
    const store = env.ACCOUNT_STORE.getByName("global");
    if (!await store.isAdmin(actor) || !Array.isArray(command.options) || command.options.length !== 1) return empty();
    const sub = object(command.options[0]);
    if (sub?.type !== 1 || !Array.isArray(sub.options) || sub.options.length > 8) return empty();
    const values = sub.options.map(object), focused = values.filter(value => value?.focused === true);
    if (focused.length !== 1 || focused[0]?.type !== 3 || typeof focused[0].value !== "string" || focused[0].value.length > 100) return empty();
    const query = focused[0].value.toLowerCase();
    let choices: { name: string; value: string }[] = [];
    if (command.name === "license" && sub.name === "uuid-remove" && focused[0].name === "account") {
      const selected = values.find(value => value?.name === "user" && value.type === 6);
      if (!snowflake(selected?.value)) return empty();
      const result = await store.managementRead(actor, "uuids", selected.value);
      choices = result.account?.uuids.filter(mc => mc.name.toLowerCase().includes(query) || mc.uuid.includes(query.replaceAll("-", "")))
        .map(mc => ({ name: mc.name.slice(0, 100), value: mc.uuid })) ?? [];
    } else if (command.name === "skyhawk" && sub.name === "search" && focused[0].name === "query") {
      if (!query.trim()) return empty();
      const result = await store.managementRead(actor, "search", "", { query });
      choices = result.accounts?.map(details => ({ name: `${details.displayName ?? details.discordUsername ?? "Discord user"} · ${details.account.uuids.map(mc => mc.name).join(", ") || "no Minecraft links"}`.slice(0, 100), value: details.account.discordId })) ?? [];
    } else if (command.name === "build" && ["revoke", "restore"].includes(String(sub.name)) && focused[0].name === "build") {
      const kindOption = values.find(value => value?.name === "kind"), selectedKind = kindOption?.value ?? "mod";
      if (!["mod", "loader"].includes(String(selectedKind))) return empty();
      const result = await store.managementRead(actor, "builds");
      const versions = new Set(result.builds?.filter(build => build.kind === selectedKind).map(build => String(build.version)) ?? []);
      const version = selectedKind === "loader" ? (await approvedLoader(env).catch(() => null))?.version : (await releaseManifest(env).catch(() => null))?.manifest.version;
      if (version) versions.add(version);
      choices = [...versions].filter(value => value.includes(query)).map(value => ({ name: `${selectedKind} ${value}`, value }));
    }
    return Response.json({ type: 8, data: { choices: choices.slice(0, 25) } });
  } catch { return empty(); }
}

async function runCommand(command: ParsedCommand, env: DiscordAccountsConfig, kind: "user" | "backend", confirmed?: ManagementResult): Promise<string | Message> {
  try {
    const store = env.ACCOUNT_STORE.getByName("global");
    // Authorization happens before target lookups, artifact access, messages or mutations.
    // AccountStore repeats the same persisted check atomically with each administrative action.
    if (kind === "backend" && !await store.isAdmin(command.actor)) return safeError("admin_required");
    if (command.identity && typeof command.identity.username === "string" && !command.identity.bot) await store.cacheDiscordIdentity(command.actor, command.target,
      { username: command.identity.username, displayName: String(command.identity.nick ?? command.identity.global_name ?? command.identity.username) });
    if (kind === "backend" && command.action === "lookup") {
      const user = await discordRequest(`/users/${command.target}`, env.DISCORD_BACKEND_BOT_TOKEN, undefined, "GET");
      if (user.id !== command.target || user.bot) return safeError("invalid_discord_id");
      command.identity = user;
      if (typeof user.username === "string") await store.cacheDiscordIdentity(command.actor, command.target, { username: user.username, displayName: String(user.global_name ?? user.username) });
    }
    if (kind === "backend" && ["info", "debug", "sessions", "uuids", "audit", "overview", "search", "lookup", "event", "builds"].includes(command.action)) return readMessage(command, env);
    if (kind === "user" && command.action === "help") return { content: "**Sky-Myce help**\nUse Status to review your license and Minecraft accounts. Get key privately replaces a lost or expired unused code; it never grants a new license. Loader sends the approved version to your DMs.\n\nClose Minecraft, run the Loader JAR and select your Fabric instance. Start Minecraft, enter your private code in the startup window, and choose Confirm and start.\n\nIf DMs are blocked, allow direct messages from this server and retry. Contact a Sky-Myce admin for revoked or expired licenses. A device stays bound across restarts and updates; `/hwid reset confirm:true` prepares an intentional transfer subject to the seven-day policy.", components: userButtons() };
    if (kind === "user" && command.action === "loader") {
      if (env.DISCORD_LOADER_DELIVERY_ENABLED === "false") return { content: "Approved Loader delivery is currently unavailable. Use Status to review your access or contact a Sky-Myce admin.", components: userButtons() };
      const status = await store.userCommand(command.actor, "loader");
      if (!status.ok) return safeError(status.error);
      const release = await approvedLoader(env);
      const link = await createLoaderDownload(env, command.actor.id);
      try { await sendDm(env.USER_BOT_TOKEN ?? env.DISCORD_BACKEND_BOT_TOKEN, command.actor.id, `**Your Sky-Myce Loader**\nLoader ${release.version} · Minecraft ${release.minecraft}\n\n${link}\n\nThis private download expires in 15 minutes. Run the JAR with Java 25 and choose your Fabric instance. Use Get key if you need an activation code. Your license, Minecraft proof and registered device are still checked by the backend.`, command.actor.interactionId); }
      catch { return { content: "Private Loader delivery failed. Allow DMs from this server, then retry in one minute. Your license and registered device are unchanged.", components: userButtons() }; }
      return { content: "Your approved Loader was sent privately by DM. If it is missing, allow DMs from this server and retry in one minute.", components: userButtons() };
    }
    const reset = command.action === "hwid_reset" || command.action === "reset";
    const resetToken = kind === "backend" ? env.DISCORD_BACKEND_BOT_TOKEN : env.USER_BOT_TOKEN;
    if (reset && !resetToken) return "Sky-Myce device-code delivery is not configured. No device changes were performed.";
    if (command.action === "authorise" || command.action === "admin_add") {
      if (!env.DISCORD_BACKEND_BOT_TOKEN) return "Sky-Myce authorization delivery is not configured.";
      if (command.action === "authorise" && env.DISCORD_LOADER_DELIVERY_ENABLED !== "false") {
        if (!env.PRIVATE_ARTIFACTS) return "Sky-Myce authorization delivery is not configured.";
        const loader = await env.PRIVATE_ARTIFACTS.head((await approvedLoader(env)).key);
        if (!loader) return "The private Sky-Myce Loader has not been published. No activation was issued.";
        if (loader.size > 10 * 1024 * 1024) await createLoaderDownload(env, command.target);
      }
      // Check that Discord recognizes the target before creating its activation. Do not accept arbitrary snowflakes alone.
      const target = await discordRequest(`/users/${command.target}`, env.DISCORD_BACKEND_BOT_TOKEN, undefined, "GET");
      if (target.id !== command.target || target.bot) return "Choose an existing human Discord account.";
      command.identity = { ...target, nick: command.identity?.nick };
      if (typeof target.username === "string") await store.cacheDiscordIdentity(command.actor, command.target, { username: target.username, displayName: String(command.identity.nick ?? target.global_name ?? target.username) });
    }
    if (!confirmed && destructive.has(command.action)) {
      const prepared = await store.prepareManagement(command.actor, kind, command.action, command.target, command.options);
      return prepared.ok ? confirmationMessage(prepared, command.identity) : safeError(prepared.error);
    }
    if (kind === "user" && command.action === "key" && !env.USER_BOT_TOKEN && !env.DISCORD_BACKEND_BOT_TOKEN) return "Private activation-code delivery is currently unavailable. Try again later.";
    if (kind === "user" && command.action === "key" && env.DISCORD_LOADER_DELIVERY_ENABLED !== "false") await approvedLoader(env);
    const result = confirmed ?? (kind === "backend" ? await store.developerCommand(command.actor, command.action, command.target, command.options) :
      await store.userCommand(command.actor, command.action, command.options));
    if (!result.ok) return safeError(result.error);
    const invalidated = result.invalidatedAccounts ?? (result.invalidated ? [result.invalidated] : []);
    await Promise.allSettled(invalidated.flatMap(accountId => env.ROOMS.split(",").map(room => env.RELAY_ROOMS.getByName(room).invalidateAccount(accountId))));
    if (!reset && "activationKey" in result && typeof result.activationKey === "string") {
      try { await authorizationDm(env, command.target, result.activationKey, command.actor.interactionId, result.account, result.expiresAt); }
      catch { return kind === "user" ? "**Activation prepared, but DM delivery failed.** Allow DMs from this server, wait one minute, then use Get key again. Earlier unused codes will be replaced; your existing device binding is unchanged. No code was posted here."
        : `**Activation issued, but private delivery failed.**\nAsk <@${command.target}> to allow DMs from this server, then use \`/license key\` to send a replacement. Earlier unused codes will be invalidated.\n\n${result.account?.activated ? "Existing activation is unchanged." : "The account remains pending activation."} No code was posted here.`; }
    }
    if (reset) {
      try {
        if (typeof result.activationKey !== "string" || !result.expiresAt) throw new DiscordDeliveryError(503);
        const state = command.action === "hwid_reset" ? "**Your Sky-Myce device was reset**\nYour administrator invalidated the previous device and its sessions."
          : result.account?.deviceRegistered ? "**Your Sky-Myce hardware transfer is ready**\nYour current device stays authorized until you activate a different device. Preparing this code does not use your transfer allowance."
            : "**Register your Sky-Myce device**\nEnroll once, then start Minecraft normally on this device.";
        await sendDm(resetToken, command.target, `${state}\n\nYour one-time activation code:\n\`${result.activationKey}\`\n\nExpires ${discordTime(result.expiresAt)}. Earlier unused codes are invalid. Keep this code private.\n\nStart the Loader on the device you want to use, enter this code and press Confirm and start. Your license and account restrictions still apply.`, command.actor.interactionId);
      } catch {
        return command.action === "hwid_reset" ? `**Device reset completed, but activation-code DM delivery failed.**\nAllow DMs from this server, then ask a Sky-Myce admin to use \`/authorise\` with \`reissue:true\` for <@${command.target}>. Do not reset again just to resend the code. No code was posted here.`
          : "**Device code prepared, but DM delivery failed.**\nYour current device registration and transfer allowance are unchanged. Allow DMs from this server, then repeat `/hwid reset confirm:true` to replace the undelivered code. No code was posted here.";
      }
    }
    if (!reset && ((kind === "user" && ["approve", "link", "unlink"].includes(command.action)) || ["license_revoke", "license_restore", "account_suspend", "account_unsuspend", "hwid_revoke"].includes(command.action))) {
      const notice = command.action === "license_revoke" ? "Your Sky-Myce license has been revoked. Sky-Myce has been disabled. Please contact the Sky-Myce owners if you believe this was a mistake." :
        command.action === "reset" || command.action.startsWith("hwid_") ? "Your Sky-Myce device registration changed. Sign in using your Sky-Myce Loader to continue." :
          "Your Sky-Myce account was updated. Use /account status to view your current access.";
      await sendDm(env.USER_BOT_TOKEN ?? env.DISCORD_BACKEND_BOT_TOKEN, command.target, notice).catch(() => {});
    }
    if (command.action === "admin_add") return result.adminAdded
      ? `Discord user <@${command.target}> is now a Sky-Myce admin.`
      : `Discord user <@${command.target}> is already a Sky-Myce admin.`;
    if (command.action === "authorise") return `**Activation sent to <@${command.target}>.**\n` +
      (env.DISCORD_LOADER_DELIVERY_ENABLED === "false" ? "Activation code delivered by DM. Loader delivery is currently unavailable."
        : "Activation code and private Loader delivered by DM.") +
      `\nCode expires ${result.expiresAt === undefined ? "in 24 hours" : discordTime(result.expiresAt)}.\n\n` +
      (result.account?.activated ? "This account has already been activated. The replacement code does not change its current access."
        : "The account remains pending activation. The user must start their instance and enter the code in the startup window. Confirm and start finishes setup before Minecraft loads.");
    if (result.warningId) return `**Warning recorded for <@${command.target}>.**\nReference: \`${result.warningId}\`\nUse this reference with \`/warning remove\` if it needs to be withdrawn.`;
    const outcomes: Record<string, string> = {
      license_revoke: "**License revoked.** Existing Sky-Myce sessions are invalidated.",
      license_restore: "**License restored.** Check the account status and expiry below before retrying the Loader.",
      license_extend: `**License extended by ${command.options.days} days.**`,
      account_suspend: "**Account suspended.** Existing Sky-Myce sessions are invalidated.",
      account_unsuspend: "**Suspension removed.** The account still needs a valid license to use Sky-Myce.",
      account_unlink: command.options.uuid ? "**Minecraft unlink processed.** Any matching link was removed from this account."
        : "**All Minecraft accounts unlinked.** Device access was reset. Fresh authorization is required before using Sky-Myce again.",
      hwid_reset: "**Device reset.** The previous binding and sessions were invalidated by this administrator action. A fresh activation code was sent to the user's DMs for device enrollment. The self-service cooldown is unchanged.",
      hwid_revoke: "**Device access revoked.** New device registration is blocked. Use `/hwid reset` when enrollment should be allowed again.",
      hwid_bind: "**Device assigned.** Existing sessions are invalidated. The user's self-service hardware-transfer cooldown is unchanged.",
      session_kick: "**Sessions invalidated.** The Loader can authorize a new session if the account remains eligible.",
      build_revoke: `**${textName(command.options.kind ?? "mod")} ${textName(command.target)} revoked.** Authorization and private distribution are blocked. Use \`/build restore\` if access should be allowed again.`,
      build_restore: `**${textName(command.options.kind ?? "mod")} ${textName(command.target)} restored.** Other license and supported-version checks still apply.`,
      warning_remove: "**Warning removal processed.** Any matching warning for this account is now inactive.",
      approve: "**Installation approved.** Return to the Loader to continue activation.",
      link: "**Minecraft account linked.**",
      unlink: "**Minecraft account unlinked.**",
      reset: result.account?.deviceRegistered ? "**Hardware transfer prepared.** A fresh activation code was sent to your DMs. Use it in the Loader on the new computer. Your current device remains authorized until the transfer completes; only an actual device change uses the seven-day allowance."
        : "**Device enrollment ready.** A fresh activation code was sent to your DMs. Enter it in the Loader to register your device once.",
    };
    const outcome = outcomes[command.action];
    if (result.account) {
      const message = infoMessage(result.account, result.details, command.identity);
      message.content = (outcome ? `${outcome}\n\n` : "") + message.content;
      message.components = kind === "user" ? userButtons() : [];
      if (command.action === "key") message.content = `A fresh activation code${env.DISCORD_LOADER_DELIVERY_ENABLED === "false" ? "" : " and approved Loader"} was sent privately by DM. Earlier unused codes are invalid; your license and registered device are unchanged.`;
      return message;
    }
    return outcome ?? "Sky-Myce request completed.";
  } catch (error) { return error instanceof AuthorizationError ? safeError(error.code) : "This Sky-Myce service is currently unavailable."; }
}
