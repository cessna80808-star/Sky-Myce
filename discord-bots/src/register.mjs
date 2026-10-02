import { backendCommands, userCommands } from './backend-bot.mjs';
import { operatorConfig, discordApi, verifyApplication } from './discord-api.mjs';
import { registerCommands } from './registration.mjs';
const kind = process.argv[2];
try {
  if (!['user', 'backend'].includes(kind)) throw new Error('Choose user or backend');
  const env = operatorConfig(), prefix = kind.toUpperCase();
  const app = env[prefix + '_BOT_APPLICATION_ID'] ?? env['DISCORD_' + prefix + '_APPLICATION_ID'];
  const token = env[prefix + '_BOT_TOKEN'], guild = env.DISCORD_GUILD_ID;
  const definitions = kind === 'user' ? userCommands : backendCommands;
  if (!token && !process.argv.includes('--apply')) {
    console.log(JSON.stringify({ mode: 'definitions only; live inventory unavailable', commands: definitions }, null, 2));
  } else {
    if (!/^\d{17,20}$/.test(app ?? '') || !/^\d{17,20}$/.test(guild ?? '')) throw new Error('Missing or invalid application/guild ID');
    const api = discordApi(token); await verifyApplication(api, app);
    const result = await registerCommands(api, app, guild, definitions, process.argv.includes('--apply'));
    console.log(JSON.stringify(result, null, 2));
  }
} catch { console.error('Command registration unavailable. Check application identity, guild membership, credentials and API permissions.'); process.exitCode = 1; }
