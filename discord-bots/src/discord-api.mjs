import { readFileSync } from 'node:fs';

export function operatorConfig(env = process.env) {
  const result = {};
  if (env.SKY_MYCE_DISCORD_CONFIG_PATH) {
    for (const line of readFileSync(env.SKY_MYCE_DISCORD_CONFIG_PATH, 'utf8').split(/\r?\n/)) {
      const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (match && match[2] && !['find it', 'unsure'].includes(match[2])) result[match[1]] = match[2];
    }
  }
  return { ...result, ...env };
}

export function discordApi(token, fetcher = fetch) {
  if (!token) throw new Error('Missing bot token for selected application');
  return async function request(path, method = 'GET', body) {
    if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid Discord API path');
    for (let attempt = 0; attempt < 3; attempt++) {
      const response = await fetcher(`https://discord.com/api/v10${path}`, { method, redirect: 'error', signal: AbortSignal.timeout(10000),
        headers: { Authorization: `Bot ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
      if (response.status === 429 && attempt < 2) {
        const wait = Number((await response.json()).retry_after);
        if (Number.isFinite(wait) && wait > 0 && wait <= 30) { await new Promise(resolve => setTimeout(resolve, wait * 1000)); continue; }
      }
      if (!response.ok) throw new Error(`Discord API ${method} failed (HTTP ${response.status})`);
      return response.status === 204 ? null : response.json();
    }
  };
}

export async function verifyApplication(api, application) {
  const actual = await api('/oauth2/applications/@me');
  if (actual.id !== application) throw new Error('Token does not belong to the configured application');
  return actual;
}
