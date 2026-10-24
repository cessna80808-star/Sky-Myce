/** Server policy. Timestamps in this protocol are Unix milliseconds. */
export const ACTIVATION_TTL = 24 * 60 * 60 * 1000;
export const DEVICE_RESET_COOLDOWN = 7 * ACTIVATION_TTL;
export type DeveloperActor = { id: string; guildId: string; roles: string[]; interactionId: string };
export type LicensePolicy = { DISCORD_GUILD_ID?: string; ADMIN_BOOTSTRAP_USER_ID?: string };
