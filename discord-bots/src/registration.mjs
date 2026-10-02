export function registrationPlan(current, desired) {
  const comparable = command => JSON.stringify({ type: command.type ?? 1, name: command.name, description: command.description ?? '',
    permissions: command.default_member_permissions ?? null, options: clean(command.options ?? []) });
  function clean(value) {
    if (Array.isArray(value)) return value.map(clean);
    if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort()
      .filter(key => !key.endsWith('_localizations') && value[key] !== false && !(Array.isArray(value[key]) && !value[key].length))
      .map(key => [key, clean(value[key])]));
    return value;
  }
  const plan = desired.map(({ contexts, integration_types, ...command }) => {
    const existing = current.find(item => item.name === command.name && item.type === command.type);
    // Preserve unclassified existing subcommands; an explicit migration owns their removal.
    if (existing?.options?.some(item => item.type === 1 || item.type === 2)) {
      command.options = [...(command.options ?? []), ...existing.options.filter(item => !command.options?.some(option => option.name === item.name))];
    }
    return { state: !existing ? 'created' : comparable(existing) === comparable(command) ? 'unchanged' : 'updated', id: existing?.id, command };
  });
  for (const command of current) if (!desired.some(item => item.type === command.type && item.name === command.name)) {
    plan.push({ state: 'unchanged', id: command.id, command });
  }
  return plan;
}

export async function registerCommands(api, application, guild, desired, apply = false) {
  const base = `/applications/${application}/guilds/${guild}/commands`;
  const before = await api(base);
  const plan = registrationPlan(before, desired);
  if (apply) for (const item of plan) {
    if (item.state !== 'unchanged') await api(item.id ? `${base}/${item.id}` : base, item.id ? 'PATCH' : 'POST', item.command);
  }
  const after = apply ? await api(base) : before;
  const describe = commands => commands.map(({ id, name, type, description, options, default_member_permissions }) => ({ id, name, type, description, options, default_member_permissions }));
  return { applied: apply, bulkOverwrite: false, before: describe(before), after: describe(after),
    changes: plan.map(item => ({ state: item.state, name: item.command.name, id: item.id })), intentionallyRemoved: [] };
}
