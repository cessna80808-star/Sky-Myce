/** The backend bot runs on the Worker's signed HTTP interaction endpoint, without a second Gateway connection. */
const user = { name: 'user', description: 'Target Discord user', type: 6, required: true };
const option = (name, description, type = 3, required = true, extra = {}) => ({ name, description, type, required, ...extra });
const sub = (name, description, options = [user]) => ({ type: 1, name, description, options });
const group = (name, options) => ({ type: 1, name, description: `Sky-Myce developer ${name} commands`,
  default_member_permissions: '0', contexts: [0], integration_types: [0], options });
const hardwareReason = option('reason', 'Private audit reason; exclude credentials and raw hardware identifiers', 3, false, { max_length: 500 });
export const promptCommands = [
  { ...group('prompt', [option('text', 'Instructions to run for Sky-Myce', 3, false, { min_length: 1, max_length: 6000 }),
    option('file', 'One UTF-8 .txt attachment, at most 64 KiB', 11, false)]), description: 'Queue a Sky-Myce coding prompt' },
  ...['status', 'cancel'].map(action => ({ ...group(`prompt-${action}`, [option('job', 'Prompt job ID')]),
    description: action === 'status' ? 'View prompt job progress and results' : 'Cancel or interrupt a prompt job' })),
  ...[['usage', 'View provider quota and shared prompt cooldown'], ['runner', 'View prompt runner availability and queue'],
    ['pause', 'Pause new prompt jobs'], ['resume', 'Resume queued prompt jobs']]
    .map(([action, description]) => ({ ...group(`prompt-${action}`, []), description })),
  { type: 3, name: 'Run prompt', default_member_permissions: '0', contexts: [0], integration_types: [0] },
];
export const backendCommands = [
  { type: 1, name: 'admin', description: 'Manage Sky-Myce backend administrator accounts',
    default_member_permissions: '0', contexts: [0], integration_types: [0],
    options: [sub('add', 'Grant a Discord user backend administrator access')] },
  { type: 1, name: 'authorise', description: 'Authorize a Discord account and privately deliver its Sky-Myce activation key',
    default_member_permissions: '0', contexts: [0], integration_types: [0],
    options: [option('user', 'Target Discord user', 6),
      option('reissue', 'Explicitly replace all outstanding activation keys for an existing authorization', 5, false)] },
  group('license', [sub('status', 'View license state'), sub('revoke', 'Revoke the license and active sessions'),
    sub('restore', 'Restore the license'), sub('extend', 'Extend a time-limited license', [user, option('days', 'Number of additional days', 4, true, { min_value: 1, max_value: 3650 })])]),
  group('account', [sub('lookup', 'View account state'), sub('unlink', 'Unlink one or all Minecraft accounts', [user,
    option('confirm', 'Confirm unlinking', 5), option('uuid', 'Minecraft UUID; omit to unlink every UUID', 3, false)]),
    sub('suspend', 'Suspend the account'), sub('unsuspend', 'Unsuspend the account')]),
  group('hwid', [sub('reset', 'Audited administrator reset of the device binding and active sessions', [user, hardwareReason]),
    sub('revoke', 'Audited security revocation of the device; blocks enrollment', [user, hardwareReason]),
    sub('bind', 'Audited device override; affects previous owner if any', [user, option('device-hash', 'Privacy-preserving device hash; never a raw hardware identifier', 3, true, { min_length: 64, max_length: 64 }), hardwareReason])]),
  group('session', [sub('kick', 'Invalidate all active sessions for this account')]),
  group('warning', [sub('add', 'Record a private developer warning', [user, option('reason', 'Private reason without keys, tokens, passwords or raw hardware IDs', 3, true, { max_length: 500 })]),
    sub('remove', 'Remove a warning', [user, option('warning-id', 'Warning reference')])]),
  { type: 1, name: 'audit', description: 'View private Sky-Myce audit events', default_member_permissions: '0', contexts: [0], integration_types: [0], options: [user] },
  ...promptCommands,
];

export const userCommands = [{ type: 1, name: 'account', description: 'Manage your own Sky-Myce account', contexts: [0], integration_types: [0], options: [
  sub('status', 'View your account, license and device status', []),
  sub('approve', 'Approve your pending Sky-Myce Loader sign-in', [option('code', 'Private code shown by your Sky-Myce Loader', 3, true, { max_length: 100 })]),
  sub('link', 'Link a Minecraft account using its verified in-game challenge', [option('code', 'Private code shown by Sky-Myce in Minecraft', 3, true, { max_length: 100 })]),
  sub('unlink', 'Unlink one of your own Minecraft accounts', [option('uuid', 'Minecraft UUID without hyphens', 3, true, { min_length: 32, max_length: 32 }), option('confirm', 'Confirm unlinking this Minecraft account', 5)]),
  sub('reset', 'Prepare an intentional hardware transfer; seven-day policy applies', [option('confirm', 'Confirm preparing a device transfer; current device stays authorized', 5)]),
] }, { type: 1, name: 'hwid', description: 'Manage your own Sky-Myce device', contexts: [0], integration_types: [0], options: [
  sub('reset', 'Prepare an intentional hardware transfer; seven-day policy applies', [option('confirm', 'Confirm preparing a device transfer; current device stays authorized', 5)]),
] }, { type: 1, name: 'cosmetics', description: 'Manage your Sky-Myce visual cosmetics', contexts: [0], integration_types: [0], options: [
  sub('link', 'Link using the one-time code from /sm cosmetics link', [option('code', 'Your private 12-character code; hyphens are optional', 3, true, { min_length: 12, max_length: 14 })]),
  sub('set', 'Change only the specified cosmetics on your linked account', [
    option('name', 'Name (32 visible characters), or safe Minecraft text-component JSON', 3, false, { max_length: 2048 }),
    ...['x', 'y', 'z'].map(name => option(name, `Visual ${name.toUpperCase()} size, 0.1–3.0; other axes stay unchanged`, 10, false, { min_value: 0.1, max_value: 3 })),
  ]),
  sub('show', "Show your linked account's active cosmetics", []),
  sub('reset', 'Restore real name and size 1/1/1', []),
  sub('unlink', 'Explain how to unlink your account or reset its appearance', [option('confirm', 'Confirm reviewing unlink instructions', 5)]),
] }];
