import { createLoaderDownload, loaderObjectKey, type DiscordDownloadConfig } from "./discord-download";
import type { AccountStore, AccountsConfig, AccountSummary } from "./accounts";
import type { RelayRoom } from "./index";

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
type ParsedCommand = { actor: Actor; action: string; target: string; options: Record<string, unknown> };
const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);
const ephemeral = (content: string) => Response.json({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } });
const userActions = new Set(["status", "approve", "link", "unlink", "reset"]);
const backendActions = new Set(["admin_add", "authorise", "license_status", "license_revoke", "license_restore", "license_extend", "account_lookup",
  "account_unlink", "account_suspend", "account_unsuspend", "hwid_reset", "hwid_revoke", "hwid_bind", "session_kick", "warning_add", "warning_remove", "audit"]);

/** Only called after exact-body Discord signature verification, never from a bot-supplied actor JSON endpoint. */
export function accountCommand(data: Record<string, unknown>, kind: "user" | "backend"): ParsedCommand | null {
  const command = object(data.data), member = object(data.member), user = object(member?.user);
  if (data.type !== 2 || !command || !snowflake(data.id) || !snowflake(data.guild_id) || !snowflake(user?.id) ||
      !Array.isArray(member?.roles) || member.roles.some(role => !snowflake(role)) || typeof command.name !== "string") return null;
  let action = command.name;
  let raw = command.options ?? [];
  if (!Array.isArray(raw) || raw.length > 5) return null;
  if (!["authorise", "audit"].includes(action)) {
    const sub = object(raw[0]);
    if (raw.length !== 1 || sub?.type !== 1 || typeof sub.name !== "string") return null;
    action = kind === "user" && (action === "account" || action === "hwid" && sub.name === "reset") ? sub.name : `${action}_${sub.name}`;
    raw = sub.options ?? [];
    if (!Array.isArray(raw) || raw.length > 5) return null;
  }
  if (!(kind === "user" ? userActions : backendActions).has(action)) return null;
  const options: Record<string, unknown> = {};
  const permitted: Record<string, number> = kind === "user" ?
    action === "reset" ? { confirm: 5 } : action === "unlink" ? { uuid: 3, confirm: 5 } : ["link", "approve"].includes(action) ? { code: 3 } : {} :
    { user: action === "authorise" ? 3 : 6, ...action === "authorise" ? { reissue: 5 } : action === "license_extend" ? { days: 4 } : action === "account_unlink" ? { uuid: 3, confirm: 5 } :
      action.startsWith("hwid_") ? { reason: 3, ...action === "hwid_bind" ? { "device-hash": 3 } : {} } : action === "warning_add" ? { reason: 3 } : action === "warning_remove" ? { "warning-id": 3 } : {} };
  for (const rawOption of raw) {
    const item = object(rawOption);
    if (!item || typeof item.name !== "string" || !Object.hasOwn(permitted, item.name) ||
        (item.type !== permitted[item.name] && !(action === "authorise" && item.name === "user" && item.type === 6)) || Object.hasOwn(options, item.name)) return null;
    if (item.type === 5 ? typeof item.value !== "boolean" : item.type === 4 ? !Number.isSafeInteger(item.value) : typeof item.value !== "string" || item.value.length > 500) return null;
    options[item.name] = item.value;
  }
  if (kind === "user" && (action === "reset" ? options.confirm !== true : ["link", "approve"].includes(action) && !options.code)) return null;
  if (action === "unlink" && (options.confirm !== true || typeof options.uuid !== "string" || !/^[a-f0-9]{32}$/i.test(options.uuid))) return null;
  if (kind === "backend" && !snowflake(options.user)) return null;
  if (action === "account_unlink" && options.confirm !== true) return null;
  const target = kind === "user" ? user.id : options.user as string;
  delete options.user;
  if (options["device-hash"] !== undefined) { options.deviceHash = options["device-hash"]; delete options["device-hash"]; }
  if (options["warning-id"] !== undefined) { options.warningId = options["warning-id"]; delete options["warning-id"]; }
  return { actor: { id: user.id, guildId: data.guild_id, roles: member.roles as string[], interactionId: data.id }, action, target, options };
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
  await discordRequest(`/channels/${channel.id}/messages`, token, { content, allowed_mentions: { parse: [] },
    ...(interactionId ? { nonce: interactionId, enforce_nonce: true } : {}) });
}
async function authorizationDm(env: DiscordAccountsConfig, target: string, activation: string, interactionId: string, account?: AccountSummary, expiresAt?: number): Promise<void> {
  if (!env.DISCORD_BACKEND_BOT_TOKEN) throw new DiscordDeliveryError(503);
  const channel = await discordRequest("/users/@me/channels", env.DISCORD_BACKEND_BOT_TOKEN, { recipient_id: target });
  if (!snowflake(channel.id)) throw new DiscordDeliveryError(502);
  const state = account?.activated ? "Your account has already been activated. This replacement code is for setting up the Loader again or adding another Minecraft account."
    : "Your account is pending activation.";
  const expiry = expiresAt === undefined ? "in 24 hours" : discordTime(expiresAt);
  if (env.DISCORD_LOADER_DELIVERY_ENABLED === "false") {
    await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, {
      content: `**Your Sky-Myce activation code**\n\n${state}\n\n\`${activation}\`\n\nExpires ${expiry}. Keep this code private; never enter it in chat.\n\nLoader downloads are currently unavailable. Wait for the approved loader before using this code. Receiving a code does not complete activation.`,
      nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] } });
    return;
  }
  const object = await env.PRIVATE_ARTIFACTS?.get(loaderObjectKey(env));
  if (!object) throw new DiscordDeliveryError(503);
  const content = `**Your Sky-Myce activation code**\n\n${state}\n\n\`${activation}\`\n\nExpires ${expiry}. Keep this code private; never enter it in chat.\n\nYour secure Sky-Myce Loader is attached below.\n\n1. Use Minecraft 26.1.2 with Java 25, Fabric, Fabric API and Fabric Language Kotlin. Close Minecraft, run the loader JAR and choose your Minecraft instance folder.\n2. Start your instance normally. A Sky-Myce window opens before Minecraft loads. Enter this code and press Confirm and start.\n3. The loader verifies and installs Sky-Myce, then starts Minecraft automatically. No manual restart is needed.\n\nFuture updates are checked before startup. The Loader button on Minecraft's main menu remains available.`;
  if (object.size <= 10 * 1024 * 1024) {
    const form = new FormData();
    form.set("payload_json", JSON.stringify({ content, nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] },
      attachments: [{ id: 0, filename: "Sky-Myce-Loader.jar" }] }));
    form.set("files[0]", new Blob([await object.arrayBuffer()], { type: "application/java-archive" }), "Sky-Myce-Loader.jar");
    try { await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, form); return; }
    catch (error) { if (!(error instanceof DiscordDeliveryError) || ![400, 413].includes(error.status)) throw error; }
  }
  const link = await createLoaderDownload(env, target);
  await discordRequest(`/channels/${channel.id}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, {
    content: content.replace("Your secure Sky-Myce Loader is attached below.", `Download your secure Sky-Myce Loader (expires in 15 minutes):\n${link}`),
    nonce: interactionId, enforce_nonce: true, allowed_mentions: { parse: [] } });
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
    invalid_uuid: "Enter the 32-character Minecraft UUID shown in the account lookup, without hyphens.",
    invalid_device: "That device reference is invalid. Check the approved device reference and try again.",
    invalid_warning: "Check the warning reason or reference and try again.",
    minecraft_link_required: "That Minecraft account is not linked to this Sky-Myce account. Check the account status before retrying.",
    rate_limited: "Please wait before sending another Sky-Myce request.",
    license_revoked: "This Sky-Myce license has been revoked. Contact a Sky-Myce admin if this was a mistake.",
    license_required: "This account needs a Sky-Myce license. Ask a Sky-Myce admin for authorization.",
    license_expired: "This Sky-Myce license has expired. Ask a Sky-Myce admin to review or extend it.",
    account_suspended: "This Sky-Myce account is suspended. Contact a Sky-Myce admin to review it.",
    authorization_unavailable: "This Sky-Myce service is currently unavailable." };
  return typeof code === "string" && Object.hasOwn(messages, code) ? messages[code] : "Sky-Myce could not complete this request. Try again, or ask a Sky-Myce admin to check the account.";
}

/** Receives Discord-signed data; the AccountStore performs the independent final authorization and replay check. */
export function handleAccountInteraction(data: Record<string, unknown>, env: DiscordAccountsConfig, ctx: ExecutionContext, kind: "user" | "backend"): Response | null {
  if (kind === "user" && !["account", "hwid"].includes(String(object(data.data)?.name))) return null;
  const command = accountCommand(data, kind);
  if (!command || typeof data.token !== "string" || !/^[A-Za-z0-9._-]{20,512}$/.test(data.token) || !snowflake(data.application_id)) return ephemeral("Invalid Sky-Myce command. Confirm the requested action and try again.");
  if (command.actor.guildId !== env.DISCORD_GUILD_ID) return ephemeral("You do not have permission to use this Sky-Myce command here.");
  if (!env.ACCOUNT_STORE) return ephemeral("This Sky-Myce service is currently unavailable.");
  return deferInteraction(data, ctx, () => runCommand(command, env, kind));
}

/** Acknowledge before slow storage/relay operations, then finish the private response. */
export function deferInteraction(data: Record<string, unknown>, ctx: ExecutionContext, operation: () => Promise<string>): Response {
  if (typeof data.token !== "string" || !/^[A-Za-z0-9._-]{20,512}$/.test(data.token) || !snowflake(data.application_id)) return ephemeral("Invalid Sky-Myce command. Confirm the requested action and try again.");
  ctx.waitUntil(operation().catch(() => "This Sky-Myce service is currently unavailable.").then(async content => {
    await discordRequest(`/webhooks/${data.application_id}/${data.token}/messages/@original`, undefined,
      { content: content.slice(0, 1900), allowed_mentions: { parse: [] } }, "PATCH");
  }).catch(() => { console.warn("Sky-Myce Discord interaction completion unavailable."); }));
  return Response.json({ type: 5, data: { flags: 64 } });
}

async function runCommand(command: ParsedCommand, env: DiscordAccountsConfig, kind: "user" | "backend"): Promise<string> {
  try {
    const store = env.ACCOUNT_STORE.getByName("global");
    // Authorization happens before target lookups, artifact access, messages or mutations.
    // AccountStore repeats the same persisted check atomically with each administrative action.
    if (kind === "backend" && !await store.isAdmin(command.actor)) return safeError("admin_required");
    const reset = command.action === "hwid_reset" || command.action === "reset";
    const resetToken = kind === "backend" ? env.DISCORD_BACKEND_BOT_TOKEN : env.USER_BOT_TOKEN;
    if (reset && !resetToken) return "Sky-Myce device-code delivery is not configured. No device changes were performed.";
    if (command.action === "authorise" || command.action === "admin_add") {
      if (!env.DISCORD_BACKEND_BOT_TOKEN) return "Sky-Myce authorization delivery is not configured.";
      if (command.action === "authorise" && env.DISCORD_LOADER_DELIVERY_ENABLED !== "false") {
        if (!env.PRIVATE_ARTIFACTS) return "Sky-Myce authorization delivery is not configured.";
        const loader = await env.PRIVATE_ARTIFACTS.head(loaderObjectKey(env));
        if (!loader) return "The private Sky-Myce Loader has not been published. No activation was issued.";
        if (loader.size > 10 * 1024 * 1024) await createLoaderDownload(env, command.target);
      }
      // Check that Discord recognizes the target before creating its activation. Do not accept arbitrary snowflakes alone.
      const target = await discordRequest(`/users/${command.target}`, env.DISCORD_BACKEND_BOT_TOKEN, undefined, "GET");
      if (target.id !== command.target || target.bot) return "Choose an existing human Discord account.";
    }
    const result = kind === "backend" ? await store.developerCommand(command.actor, command.action, command.target, command.options) :
      await store.userCommand(command.actor, command.action as "status" | "reset" | "approve" | "link" | "unlink", command.options);
    if (!result.ok) return safeError(result.error);
    const invalidated = result.invalidatedAccounts ?? (result.invalidated ? [result.invalidated] : []);
    await Promise.allSettled(invalidated.flatMap(accountId => env.ROOMS.split(",").map(room => env.RELAY_ROOMS.getByName(room).invalidateAccount(accountId))));
    if (!reset && "activationKey" in result && typeof result.activationKey === "string") {
      try { await authorizationDm(env, command.target, result.activationKey, command.actor.interactionId, result.account, result.expiresAt); }
      catch { return `**Activation issued, but private delivery failed.**\nAsk <@${command.target}> to allow DMs from this server, then run \`/authorise\` with \`reissue:true\` to send a replacement. Earlier unused codes will be invalidated.\n\n${result.account?.activated ? "Existing activation is unchanged." : "The account remains pending activation."} No code was posted here.`; }
    }
    // Stored transactional audits remain the source of truth even if Discord delivery is unavailable.
    if (kind === "backend") {
      const channel = command.action.startsWith("license") || command.action === "authorise" ? env.DISCORD_LICENSE_CHANNEL_ID :
        command.action.startsWith("hwid") || command.action.startsWith("warning") ? env.DISCORD_SECURITY_CHANNEL_ID : env.DISCORD_AUDIT_CHANNEL_ID;
      const destination = channel ?? env.DISCORD_AUDIT_CHANNEL_ID;
      if (destination && snowflake(destination) && env.DISCORD_BACKEND_BOT_TOKEN) {
        await discordRequest(`/channels/${destination}/messages`, env.DISCORD_BACKEND_BOT_TOKEN, { content:
          `Sky-Myce audit: ${command.action}\nActor: ${command.actor.id}\nTarget: ${command.target}\nRequest: ${command.actor.interactionId}\nAt: ${new Date().toISOString()}\nResult: accepted${command.action.startsWith("hwid") ? "\nOverride: true" : ""}`,
          allowed_mentions: { parse: [] } }).catch(() => {});
      }
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
    if (!reset && ((kind === "user" && command.action !== "status") || ["license_revoke", "license_restore", "account_suspend", "account_unsuspend", "hwid_revoke"].includes(command.action))) {
      const notice = command.action === "license_revoke" ? "Your Sky-Myce license has been revoked. Sky-Myce has been disabled. Please contact the Sky-Myce owners if you believe this was a mistake." :
        command.action === "reset" || command.action.startsWith("hwid_") ? "Your Sky-Myce device registration changed. Sign in using your Sky-Myce Loader to continue." :
          "Your Sky-Myce account was updated. Use /account status to view your current access.";
      await sendDm(env.USER_BOT_TOKEN, command.target, notice).catch(() => {});
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
    if (command.action === "audit" && result.audit) {
      const rows = result.audit.slice(0, 8).map(row => {
        const action = typeof row.action === "string" && /^[a-z_]{1,64}$/.test(row.action) ? row.action.replace(/_/g, " ") : "account update";
        return `• ${typeof row.at === "number" ? `<t:${Math.floor(row.at / 1000)}:R>` : "Time unavailable"} — **${action}**` +
          `\n  By ${snowflake(row.actor) ? `<@${row.actor}>` : "System"} · Ref ${typeof row.id === "number" ? row.id : "unavailable"}${row.override === 1 ? " · Admin override" : ""}`;
      });
      return `**Private audit for <@${command.target}>**\n` + (rows.length ? `Latest ${rows.length} events:\n\n${rows.join("\n")}` : "No account events have been recorded.");
    }
    if (command.action === "account_lookup" && result.account) return `${accountText(result.account, command.target)}\n\nAccount reference: \`${result.account.accountId}\`` +
      (result.account.uuids.length ? "\n**Linked Minecraft accounts**\n" + result.account.uuids.map(value => `\`${value.name}\` — \`${value.uuid}\``).join("\n").slice(0, 1100) : "");
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
      warning_remove: "**Warning removal processed.** Any matching warning for this account is now inactive.",
      approve: "**Installation approved.** Return to the Loader to continue activation.",
      link: "**Minecraft account linked.**",
      unlink: "**Minecraft account unlinked.**",
      reset: result.account?.deviceRegistered ? "**Hardware transfer prepared.** A fresh activation code was sent to your DMs. Use it in the Loader on the new computer. Your current device remains authorized until the transfer completes; only an actual device change uses the seven-day allowance."
        : "**Device enrollment ready.** A fresh activation code was sent to your DMs. Enter it in the Loader to register your device once.",
    };
    const outcome = outcomes[command.action];
    if (result.account) return (outcome ? `${outcome}\n\n` : "") + accountText(result.account, kind === "backend" ? command.target : undefined);
    return outcome ?? "Sky-Myce request completed.";
  } catch { return "This Sky-Myce service is currently unavailable."; }
}
