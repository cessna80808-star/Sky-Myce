/** Both bot applications use the existing Discord-signed HTTP interaction endpoints. */
const user = { name: 'user', description: 'Choose the Discord account to review', type: 6, required: true };
const option = (name, description, type = 3, required = true, extra = {}) => ({ name, description, type, required, ...extra });
const sub = (name, description, options = [user]) => ({ type: 1, name, description, options });
const group = (name, options, description) => ({ type: 1, name, description,
  default_member_permissions: '0', contexts: [0], integration_types: [0], options });
const page = option('page', 'Page of private results to view', 4, false, { min_value: 1, max_value: 100 });
const reason = option('reason', 'Private audit reason; exclude credentials and raw hardware details', 3, true, { max_length: 500 });
const code = option('code', 'The short-lived Connect Discord code shown by Sky-Myce in Minecraft', 3, true, { min_length: 12, max_length: 14 });
const uuid = option('uuid', 'One Minecraft UUID without hyphens; choose a linked account when available', 3, true,
  { autocomplete: true, min_length: 32, max_length: 32 });
const blacklistTarget = [{ ...user, required: false }, { ...uuid, required: false, autocomplete: false }];
export const accountCommands = [
  sub('overview', 'View Discord identity, Minecraft associations and online-service blacklist status'),
  sub('devices', 'View private device association history; identifiers never restrict access', [user, page]),
  sub('sessions', 'View current community connections across Minecraft accounts and computers', [user, page]),
  sub('audit', 'View private account and blacklist changes', [user, page]),
];
export const skyhawkCommands = [
  sub('overview', 'View community account, connection and blacklist totals', []),
  sub('search', 'Find accounts by their Discord or Minecraft names', [
    option('query', 'Search names, then choose a registered Discord account', 3, true, { autocomplete: true, max_length: 100 })]),
  sub('lookup', 'Advanced private lookup by stable Discord ID', [
    option('discord-id', 'Stable Discord ID of the account to review', 3, true, { min_length: 17, max_length: 20 })]),
];
export const skyhawkActions = skyhawkCommands.map(command => command.name);
export const promptCommands = [
  { ...group('prompt', [option('text', 'Instructions to run for Sky-Myce', 3, false, { min_length: 1, max_length: 6000 }),
    option('file', 'One UTF-8 .txt attachment, at most 64 KiB', 11, false)], 'Queue a Sky-Myce coding prompt') },
  ...['status', 'cancel'].map(action => group(`prompt-${action}`, [option('job', 'Prompt job ID')],
    action === 'status' ? 'View prompt job progress and results' : 'Cancel or interrupt a prompt job')),
  ...[['usage', 'View provider quota and shared prompt cooldown'], ['runner', 'View prompt runner availability and queue'],
    ['pause', 'Pause new prompt jobs'], ['resume', 'Resume queued prompt jobs']]
    .map(([action, description]) => group(`prompt-${action}`, [], description)),
  { type: 3, name: 'Run prompt', default_member_permissions: '0', contexts: [0], integration_types: [0] },
];
export const backendCommands = [
  group('admin', [sub('add', 'Review granting this Discord user backend administrator access')], 'Manage Sky-Myce backend administrator accounts'),
  group('account', accountCommands, 'View private community account and device associations'),
  group('blacklist', [
    sub('add', 'Review restricting online services for exactly one Discord user or Minecraft UUID', [reason, ...blacklistTarget]),
    sub('remove', 'Review removing one Discord or Minecraft online-service blacklist entry', [reason, ...blacklistTarget]),
    sub('info', 'Review blacklist status for exactly one Discord user or Minecraft UUID', blacklistTarget),
    sub('list', 'View private online-service blacklist entries', [page]),
  ], 'Manage identity blacklists; devices are never blacklist targets'),
  group('session', [sub('kick', 'Review ending current community sessions; the user can reconnect')], 'Manage current community connections'),
  group('skyhawk', skyhawkCommands, 'Find community accounts and view private totals'),
  ...promptCommands,
];
export const userCommands = [
  { type: 1, name: 'leaderboard', description: 'Browse explicitly shared Sky-Myce statistics and UTC periods', contexts: [0], integration_types: [0], options: [
    option('category', 'Economy, Dungeons, RNG or other supported statistics', 3, false, { choices: ['economy', 'dungeons', 'rng', 'other'].map(value => ({ name: value, value })) }),
    option('metric', 'Choose a metric belonging to the selected category', 3, false, { choices: ['networth', 'purse', 'bank', 'dungeon_runs', 'dungeon_profit', 'rng_drops', 'completed_tasks'].map(value => ({ name: value.replaceAll('_', ' '), value })) }),
    option('period', 'All time, current UTC Monday week or UTC calendar month', 3, false, { choices: ['all-time', 'weekly', 'monthly'].map(value => ({ name: value, value })) }),
    option('page', 'Result page', 4, false, { min_value: 1, max_value: 100 }),
    option('search', 'Search public player names; private names are never searchable', 3, false, { max_length: 100 }),
  ] },
  ...[['status', 'Check your Discord connection, Minecraft associations and online services'],
    ['disconnect', 'Review disconnecting current community sessions; local gameplay keeps working'],
    ['help', 'Learn how to connect Discord and manage community sharing']]
    .map(([name, description]) => ({ type: 1, name, description, contexts: [0], integration_types: [0], options: [] })),
  { type: 1, name: 'connect', description: 'Connect Discord using the fallback code from Sky-Myce in Minecraft', contexts: [0], integration_types: [0], options: [code] },
  { type: 1, name: 'account', description: 'Manage your own Discord and Minecraft associations', contexts: [0], integration_types: [0], options: [
    sub('status', 'View your connected Discord and Minecraft accounts', []),
    sub('link', 'Connect another Minecraft account using its verified in-game code', [code]),
    sub('unlink', 'Review removing one of your Minecraft associations', [uuid]),
    sub('disconnect', 'Review ending community sessions while keeping your profile and associations', []),
  ] },
  { type: 1, name: 'cosmetics', description: 'Manage your Sky-Myce visual cosmetics', contexts: [0], integration_types: [0], options: [
    sub('link', 'Connect using the one-time code from Sky-Myce in Minecraft', [code]),
    sub('set', 'Change only the specified cosmetics on your linked account', [
      option('name', 'Name (32 visible characters), or safe Minecraft text-component JSON', 3, false, { max_length: 2048 }),
      ...['x', 'y', 'z'].map(name => option(name, `Visual ${name.toUpperCase()} size, 0.1–3.0; other axes stay unchanged`, 10, false, { min_value: 0.1, max_value: 3 })),
    ]),
    sub('show', "Show your linked account's active cosmetics", []),
    sub('reset', 'Restore real name and size 1/1/1', []),
    sub('unlink', 'Explain how to unlink your account or reset its appearance', [option('confirm', 'Confirm reviewing unlink instructions', 5)]),
  ] },
];

// An explicit migration retires only obsolete locking/approval workflows. Unknown commands remain intact.
export const retiredCommands = {
  user: { commands: ['key', 'loader', 'hwid'], subcommands: { account: ['approve', 'reset'] } },
  backend: { commands: ['authorise', 'authenticate', 'license', 'hwid', 'build', 'warning', 'audit'],
    subcommands: { account: ['lookup', 'unlink', 'suspend', 'unsuspend'] } },
};
