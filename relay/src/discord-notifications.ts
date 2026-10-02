import type { AccountAlert } from "./accounts";
import { sha256 } from "./drm";

export type AccountAlertConfig = { BACKEND_BOT_TOKEN?: string; DISCORD_BACKEND_BOT_TOKEN?: string };
const recipients = ["978376257534328883", "1250627753191931914"];
const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);
const playerUuid = (value: unknown): string | null => typeof value === "string" && /^[a-f0-9]{32}$/i.test(value.replaceAll("-", "")) ? value.replaceAll("-", "") : null;
const playerName = (value: unknown): string | null => typeof value === "string" && /^[A-Za-z0-9_]{1,16}$/.test(value) ? value : null;
const clean = (value: unknown, limit = 200): string => typeof value !== "string" ? "" : value
  .replace(/[\p{Cc}\p{Cf}]/gu, " ").replace(/-----BEGIN[^-]*PRIVATE KEY-----[\s\S]*/gi, "[redacted]")
  .replace(/(?:bearer\s+\S+|(?:token|key|password|secret|signature|hwid|hardware[_ -]?id|serial)\s*[:=]\s*\S+|[A-Za-z0-9+/_=-]{43,})/gi, "[redacted]")
  .replace(/[\\`*_~<>|]/g, "").slice(0, limit).trim();
export const auditReason = clean;
const titles: Record<string, string> = {
  authorise: "Account approved", activation_issued: "Activation key issued", minecraft_uuid_added: "Minecraft account linked",
  minecraft_uuid_removed: "Minecraft account removed", license_revoke: "License revoked", license_restore: "License restored",
  account_suspend: "Account suspended", account_unsuspend: "Account restored", admin_added: "Administrator access granted",
  hwid_reset: "Hardware registration reset", hwid_revoke: "Hardware authorization blocked", device_transferred: "Hardware transferred",
  build_revoke: "Build authorization revoked", build_restore: "Build authorization restored", session_kick: "Account signed out",
  activation_replay: "Used activation key presented again", activation_expired: "Expired activation key presented",
  challenge_replay: "Authorization challenge presented again",
  warning_issued: "Account warning recorded",
  activation_denied: "Activation denied", lease_denied: "Authorization denied", authentication_failure: "Connection denied",
  signing_configuration_failure: "Backend signing configuration unavailable",
};
const reasons: Record<string, string> = {
  HWID_MISMATCH: "A different device attempted to use the registered license. Access was denied.",
  installation_proof_invalid: "Device identity could not be verified. Access was denied.",
  invalid_signature: "The authorization signature could not be verified. Access was denied.",
  invalid_lease: "The authorization lease is invalid or expired. Access was denied.",
  challenge_replayed: "An authorization request was presented more than once. Access was denied.",
  invalid_activation_key: "The activation key was not accepted. Access was denied.",
  BUILD_REVOKED: "A blocked build attempted to authorize. An approved update is required.",
  license_revoked: "This license has been revoked. Access was denied.",
  account_suspended: "This account is suspended. Access was denied.",
};
const severities: Record<string, readonly [string, number]> = { information: ["✅ Information", 0x4ca36e], warning: ["⚠️ Warning", 0xe6aa36],
  security: ["🚨 Security", 0xdb6543], critical: ["🛑 Critical", 0xcf3c56] };

function alertMessage(alert: AccountAlert, nonce: string): Record<string, unknown> {
  const event = alert.event, metadata = event.metadata ?? {};
  const id = snowflake(metadata.discordId) ? metadata.discordId : null;
  const severity = severities[event.severity] ?? severities.information;
  const display = clean(metadata.discordName ?? metadata.displayName, 64), username = clean(metadata.discordUsername, 64);
  const fields = [{ name: "Discord", value: [id ? `<@${id}>` : "System event", display,
    username && username !== display ? `Username: ${username}` : "", id ? `Discord ID: ${id}` : ""].filter(Boolean).join("\n") }];
  const linked = Array.isArray(metadata.minecraftAccounts) ? metadata.minecraftAccounts.slice(0, 6) : [];
  const current = { name: playerName(metadata.minecraftName) ?? "", uuid: playerUuid(metadata.minecraftUuid ?? metadata.uuid) ?? "" };
  if (current.name || current.uuid) linked.unshift(current);
  const players = linked.flatMap(value => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return [];
    const player = value as Record<string, unknown>, name = playerName(player.name), uuid = playerUuid(player.uuid);
    return name || uuid ? [`${name ?? "Minecraft account"}${uuid ? `\nUUID: ${uuid}` : ""}`] : [];
  });
  if (players.length) fields.push({ name: "Minecraft", value: [...new Set(players)].join("\n").slice(0, 900) });
  if (metadata.error === "HWID_MISMATCH") fields.push({ name: "Device", value: "Different from the registered device — login denied" });
  else if (["hwid_reset", "hwid_revoke", "device_transferred"].includes(event.action)) fields.push({ name: "Device", value: "Registration deliberately changed" });
  for (const [label, value] of [["Mod build", metadata.buildId ?? metadata.version], ["Loader", metadata.loaderVersion]] as const) {
    if (typeof value === "string" && /^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(value)) fields.push({ name: label, value });
  }
  if (typeof metadata.sessionId === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(metadata.sessionId)) {
    fields.push({ name: "Session", value: metadata.sessionId });
  }
  const reason = reasons[String(metadata.error ?? "")] ?? clean(metadata.reason, 500);
  if (reason) fields.push({ name: "Reason", value: reason });
  if (snowflake(event.actor)) fields.push({ name: "Requested by", value: `<@${event.actor}>` });
  if (alert.repeats > 0) fields.push({ name: "Repeated events", value: String(Math.min(alert.repeats + 1, 1_000_000)) });
  const label = metadata.error === "HWID_MISMATCH" ? "Second-device login attempt" : titles[event.action] ?? "Account activity";
  return { embeds: [{ title: `Sky-Myce — ${severity[0]}`, description: label, color: severity[1], fields,
    timestamp: new Date(event.at).toISOString(), footer: { text: `Private administrator alert • Audit ${event.id}` } }],
    allowed_mentions: { parse: [] }, nonce, enforce_nonce: true,
    components: id ? [{ type: 1, components: [{ type: 2, style: 2, label: "View License", custom_id: `sky:admin:info:${id}` },
      { type: 2, style: 2, label: "View Audit", custom_id: `sky:admin:audit:${id}` }] }] : [] };
}

async function discordPost(path: string, token: string, body: unknown): Promise<Response> {
  return fetch(`https://discord.com/api/v10${path}`, { method: "POST", redirect: "manual", signal: AbortSignal.timeout(8000),
    headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

/** The durable account outbox owns retries; this helper sends only to fixed administrator DMs. */
export async function deliverAccountAlert(env: AccountAlertConfig, alert: AccountAlert): Promise<boolean> {
  try {
    const token = env.BACKEND_BOT_TOKEN ?? env.DISCORD_BACKEND_BOT_TOKEN;
    if (!token || !recipients.includes(alert.recipientId) || !Number.isSafeInteger(alert.auditId) || alert.auditId < 1 ||
        alert.event.id !== alert.auditId || !Number.isSafeInteger(alert.event.at) || alert.event.at <= 0) return false;
    const response = await discordPost("/users/@me/channels", token, { recipient_id: alert.recipientId });
    if (!response.ok) { await response.body?.cancel(); return false; }
    const reader = response.body?.getReader();
    if (!reader) return false;
    let text = "", size = 0;
    const decoder = new TextDecoder();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 16384) return false;
        text += decoder.decode(value, { stream: true });
      }
      text += decoder.decode();
    } finally { await reader.cancel().catch(() => {}); }
    const channel = JSON.parse(text) as Record<string, unknown>;
    if (!snowflake(channel.id)) return false;
    const nonce = (await sha256(`Sky-Myce audit alert\n${alert.id}\n${alert.recipientId}`)).slice(0, 24);
    const message = await discordPost(`/channels/${channel.id}/messages`, token, alertMessage(alert, nonce));
    await message.body?.cancel();
    return message.ok;
  } catch { return false; }
}
