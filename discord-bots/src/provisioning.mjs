export const permissions = { view: 1024n, send: 2048n, embed: 16384n, history: 65536n, admin: 8n, manageChannels: 16n, manageRoles: 268435456n };
const access = permissions.view | permissions.send | permissions.embed | permissions.history;
const normalize = name => name.toLowerCase().replace(/[^a-z0-9]/g, '');
export function findObject(objects, id, names, type) {
  if (id) {
    const object = objects.find(value => value.id === id);
    if (!object || type !== undefined && object.type !== type) throw new Error('Configured Discord object is missing or has the wrong type');
    return object;
  }
  const matches = objects.filter(value => (type === undefined || value.type === type) && names.some(name => normalize(name) === normalize(value.name)));
  if (matches.length > 1) throw new Error('Ambiguous Discord objects; configure an explicit ID');
  return matches[0];
}
export function channelOverwrites(channel, env, mode, role) {
  const values = (channel.permission_overwrites ?? []).map(value => ({ ...value }));
  const developerIds = (env.DEVELOPER_USER_IDS ?? '').split(/[\s,]+/).filter(Boolean);
  const backend = env.BACKEND_BOT_APPLICATION_ID, user = env.USER_BOT_APPLICATION_ID;
  const trusted = new Set([role, backend, ...developerIds]);
  function set(id, type, allow, deny) {
    let entry = values.find(value => value.id === id && value.type === type);
    if (!entry) { entry = { id, type, allow: '0', deny: '0' }; values.push(entry); }
    entry.allow = ((BigInt(entry.allow) & ~deny) | allow).toString();
    entry.deny = ((BigInt(entry.deny) & ~allow) | deny).toString();
  }
  if (mode === 'staff') {
    for (const entry of values) if (!trusted.has(entry.id)) set(entry.id, entry.type, 0n, permissions.view);
    set(env.DISCORD_GUILD_ID, 0, 0n, permissions.view);
    set(role, 0, access, 0n); set(backend, 1, access, 0n); set(user, 1, 0n, permissions.view);
    for (const id of developerIds) set(id, 1, access, 0n);
  } else {
    set(user, 1, access, 0n);
    if (mode === 'production') set(env.DISCORD_GUILD_ID, 0, permissions.view | permissions.send, 0n);
    if (mode === 'testing') { set(env.DISCORD_GUILD_ID, 0, 0n, permissions.view); set(role, 0, access, 0n); }
  }
  return values;
}
export function effectivePermissions(guild, roles, member, channel) {
  let bits = roles.filter(role => role.id === guild || member.roles.includes(role.id)).reduce((sum, role) => sum | BigInt(role.permissions), 0n);
  if (bits & permissions.admin) return (1n << 64n) - 1n;
  const overwrites = channel.permission_overwrites ?? [];
  const apply = entries => { let allow = 0n, deny = 0n; for (const entry of entries) { allow |= BigInt(entry.allow); deny |= BigInt(entry.deny); } bits = (bits & ~deny) | allow; };
  apply(overwrites.filter(entry => entry.type === 0 && entry.id === guild));
  apply(overwrites.filter(entry => entry.type === 0 && member.roles.includes(entry.id) && entry.id !== guild));
  apply(overwrites.filter(entry => entry.type === 1 && entry.id === member.id));
  return bits;
}

export async function provision(api, env, apply = false) {
  for (const key of ['DISCORD_GUILD_ID', 'BACKEND_BOT_APPLICATION_ID', 'USER_BOT_APPLICATION_ID']) {
    if (!/^\d{17,20}$/.test(env[key] ?? '')) throw new Error(`Missing or invalid ${key}`);
  }
  if (env.BACKEND_BOT_APPLICATION_ID === env.USER_BOT_APPLICATION_ID) throw new Error('Bot applications must differ');
  const base = `/guilds/${env.DISCORD_GUILD_ID}`;
  const [roles, channels] = await Promise.all([api(`${base}/roles`), api(`${base}/channels`)]);
  const report = { applied: apply, objects: [], changes: [], configuredIds: {} };
  let role = findObject(roles, env.DEVELOPER_ROLE_ID, ['Sky-Myce Developer', 'SkyMyce Dev']);
  const existingRole = !!role;
  if (!role && apply) { role = await api(`${base}/roles`, 'POST', { name: 'Sky-Myce Developer', permissions: '0', mentionable: false }); report.changes.push({ action: 'created', kind: 'developer role', id: role.id }); }
  report.objects.push({ kind: 'developer role', state: existingRole ? 'reused' : role ? 'created' : 'would create', id: role?.id, name: role?.name });
  const roleId = role?.id ?? 'planned-developer-role'; report.configuredIds.DEVELOPER_ROLE_ID = roleId;
  const staffIds = ['DISCORD_AUDIT_CHANNEL_ID', 'DISCORD_SECURITY_CHANNEL_ID', 'DISCORD_LICENSE_CHANNEL_ID'];
  const parents = new Set(staffIds.map(key => channels.find(channel => channel.id === env[key])?.parent_id).filter(Boolean));
  async function channel(key, names, type, parent, mode) {
    let value = findObject(channels, env[key], names, type), created = false;
    if (!value) {
      if (apply) {
        value = await api(`${base}/channels`, 'POST', { name: names[0], type, ...(parent ? { parent_id: parent } : {}),
          permission_overwrites: channelOverwrites({}, env, mode, roleId) });
        channels.push(value); created = true; report.changes.push({ action: 'created', kind: key, id: value.id });
      } else value = { id: `planned-${key}`, name: names[0], type, parent_id: parent, permission_overwrites: [] };
    }
    const state = created ? 'created' : value.id.startsWith('planned-') ? 'would create' : 'reused';
    report.objects.push({ kind: key, state, id: value.id, name: value.name });
    report.configuredIds[key] = value.id;
    if (parent && value.parent_id !== parent) {
      report.changes.push({ action: apply ? 'modified' : 'would modify', kind: key, id: value.id, field: 'parent_id' });
      if (apply) await api(`/channels/${value.id}`, 'PATCH', { parent_id: parent, lock_permissions: false });
    }
    // Keep the setup bot's access before tightening pre-existing role overwrites.
    const overwrites = channelOverwrites(value, env, mode, roleId).sort((a, b) =>
      Number(b.id === env.BACKEND_BOT_APPLICATION_ID && b.type === 1) - Number(a.id === env.BACKEND_BOT_APPLICATION_ID && a.type === 1));
    for (const overwrite of overwrites) {
      const old = value.permission_overwrites?.find(item => item.id === overwrite.id && item.type === overwrite.type);
      if (old?.allow === overwrite.allow && old?.deny === overwrite.deny) continue;
      report.changes.push({ action: apply ? 'modified' : 'would modify', kind: key, id: value.id, field: 'permissions', target: overwrite.id });
      if (apply) await api(`/channels/${value.id}/permissions/${overwrite.id}`, 'PUT', overwrite);
    }
    return value;
  }
  // Reuse the common existing staff parent even when its display name differs.
  const staffEnv = env.DISCORD_STAFF_CATEGORY_ID;
  if (!staffEnv && parents.size === 1) env = { ...env, DISCORD_STAFF_CATEGORY_ID: [...parents][0] };
  const staff = await channel('DISCORD_STAFF_CATEGORY_ID', ['🔐 SKY-MYCE STAFF', 'dev logs'], 4, null, 'staff');
  const irc = await channel('DISCORD_IRC_CATEGORY_ID', ['📡 SKY-MYCE IRC'], 4, null, 'production');
  await channel('DISCORD_IRC_CHANNEL_ID', ['sky-myce-irc', 'irc'], 0, irc.id, 'production');
  await channel('DISCORD_IRC_TEST_CHANNEL_ID', ['sky-myce-irc-test', 'irc-backend'], 0, irc.id, 'testing');
  await channel('DISCORD_AUDIT_CHANNEL_ID', ['audit-log'], 0, staff.id, 'staff');
  await channel('DISCORD_SECURITY_CHANNEL_ID', ['security-alerts'], 0, staff.id, 'staff');
  await channel('DISCORD_LICENSE_CHANNEL_ID', ['license-log', 'licenses'], 0, staff.id, 'staff');
  return report;
}
