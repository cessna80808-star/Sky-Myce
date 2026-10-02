/** Server policy. Timestamps in this protocol are Unix milliseconds. */
export const ACTIVATION_TTL = 24 * 60 * 60 * 1000;
export const DEVICE_RESET_COOLDOWN = 7 * ACTIVATION_TTL;
export type DeveloperActor = { id: string; guildId: string; roles: string[]; interactionId: string };
export type LicensePolicy = { DISCORD_GUILD_ID?: string; ADMIN_BOOTSTRAP_USER_ID?: string;
  MINECRAFT_VERSION?: string; SKY_MYCE_VERSION?: string; MINIMUM_LOADER_VERSION?: string };
export type LicenseState = { status: string; license_status: string; license_expires_at: number | null };
export function licenseDenial(account: LicenseState | null | undefined, now = Date.now()): string | null {
  if (!account) return "account_required";
  if (account.status !== "active") return "account_suspended";
  if (account.license_status !== "active") return account.license_status === "revoked" ? "license_revoked" : "license_required";
  if (account.license_expires_at !== null && account.license_expires_at <= now) return "license_expired";
  return null;
}
export function versionAllowed(version: string, minimum: string): boolean {
  if (!/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(version) || !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(minimum)) return false;
  const a = version.split(".").map(Number), b = minimum.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i];
  return true;
}
export function versionDenial(input: { minecraftVersion: string; modVersion: string; loaderVersion: string }, env: LicensePolicy): string | null {
  return input.minecraftVersion !== (env.MINECRAFT_VERSION ?? "26.1.2") ||
    input.modVersion !== (env.SKY_MYCE_VERSION ?? "2.4.2") ||
    !versionAllowed(input.loaderVersion, env.MINIMUM_LOADER_VERSION ?? "1.0.0") ? "version_denied" : null;
}
