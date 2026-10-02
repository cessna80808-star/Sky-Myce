import { operatorConfig, discordApi, verifyApplication } from './discord-api.mjs';
import { provision } from './provisioning.mjs';
try {
  const env = operatorConfig(), api = discordApi(env.BACKEND_BOT_TOKEN);
  await verifyApplication(api, env.BACKEND_BOT_APPLICATION_ID);
  console.log(JSON.stringify(await provision(api, env, process.argv.includes('--apply')), null, 2));
} catch { console.error('Server setup unavailable. Check configured IDs, guild membership, role hierarchy, Manage Channels and Manage Roles. Re-run the preview to inspect partial progress.'); process.exitCode = 1; }
