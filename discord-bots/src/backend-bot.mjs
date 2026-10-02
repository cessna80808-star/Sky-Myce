/** The backend bot runs on the Worker's signed HTTP interaction endpoint, without a second Gateway connection. */
const user = { name: 'user', description: 'The Discord account whose Sky-Myce access you want to manage', type: 6, required: true };
const option = (name, description, type = 3, required = true, extra = {}) => ({ name, description, type, required, ...extra });
const sub = (name, description, options = [user]) => ({ type: 1, name, description, options });
const group = (name, options, description = `Sky-Myce developer ${name} commands`) => ({ type: 1, name, description,
  default_member_permissions: '0', contexts: [0], integration_types: [0], options });
const hardwareReason = option('reason', 'Private audit reason; exclude credentials and raw hardware identifiers', 3, false, { max_length: 500 });
const reissue = option('reissue', 'Replace earlier unused activation keys and privately deliver a fresh key', 5, false);
const buildKind = option('kind', 'Choose the mod build or loader release; defaults to the mod', 3, false,
  { choices: [{ name: 'Sky-Myce mod', value: 'mod' }, { name: 'Sky-Myce Loader', value: 'loader' }] });
const build = option('build', 'Select a known release version to manage', 3, true, { autocomplete: true, min_length: 1, max_length: 32 });
export const licenseCommands = [
  sub('whitelist', 'Approve this Discord account for Sky-Myce and send private onboarding'),
  sub('key', 'Privately deliver an eligible activation key for this account', [user, reissue]),
  sub('info', 'View license, linked Minecraft accounts, device and access status'),
  sub('debug', 'View private technical identifiers and authorization details'),
  sub('revoke', 'Review and confirm revoking this account\'s license and active sessions'),
  sub('restore', 'Restore this account\'s Sky-Myce license'),
  sub('extend', 'Extend this account\'s time-limited license', [user,
    option('days', 'Number of additional days of access', 4, true, { min_value: 1, max_value: 3650 })]),
  sub('reset-hardware', 'Review and confirm resetting the registered device and its sessions', [user, hardwareReason]),
  sub('force-logout', 'Review and confirm ending this account\'s current authorizations'),
  sub('uuids', 'View the Minecraft accounts linked to this Discord account'),
  sub('uuid-remove', 'Select and confirm removing one linked Minecraft account', [user,
    option('account', 'Choose one of this user\'s linked Minecraft accounts', 3, true, { autocomplete: true, min_length: 32, max_length: 32 })]),
  sub('audit', 'View recent private activity and security events for this account', [user,
    option('event', 'Show one event category; omit to show all events', 3, false, { choices: [
      ['Device enrolled', 'device_registered'], ['Hardware transferred', 'device_transferred'], ['Hardware reset', 'hwid_reset'],
      ['Activation key issued', 'activation_issued'], ['License revoked', 'license_revoke'], ['License restored', 'license_restore'],
      ['Authorization refreshed', 'lease_issued'], ['Connection denied', 'authentication_failure'], ['Minecraft account removed', 'minecraft_uuid_removed'],
    ].map(([name, value]) => ({ name, value })) }),
    option('severity', 'Show one severity; omit to show all severities', 3, false, { choices: [
      ['Information', 'information'], ['Warning', 'warning'], ['Security', 'security'], ['Critical', 'critical'],
    ].map(([name, value]) => ({ name, value })) }),
    option('days', 'Show activity from the last number of days', 4, false, { min_value: 1, max_value: 3650 }),
    option('page', 'Page of matching audit events to view', 4, false, { min_value: 1, max_value: 100 })]),
  sub('sessions', 'View current authorizations, builds and recent session activity'),
  sub('status', 'View license status; preserved alias for existing workflows'),
];
export const licenseActions = licenseCommands.map(command => command.name);
export const skyhawkCommands = [
  sub('overview', 'View private licensing totals and recent security activity', []),
  sub('search', 'Find a registered user by their Discord or Minecraft name', [
    option('query', 'Search names, then choose a matching registered account', 3, true, { autocomplete: true, min_length: 1, max_length: 100 })]),
  sub('lookup', 'Advanced lookup when a user is unavailable in the Discord picker', [
    option('discord-id', 'Advanced fallback: the stable Discord ID of the account to review', 3, true, { min_length: 17, max_length: 20 })]),
  sub('event', 'Investigate one private audit event by its reference', [
    option('id', 'Audit event reference from a private administrator alert', 4, true, { min_value: 1, max_value: 999999999999 })]),
];
export const skyhawkActions = skyhawkCommands.map(command => command.name);
export const buildCommands = [
  sub('list', 'View known Sky-Myce mod and loader releases and their access status', []),
  sub('revoke', 'Select and confirm blocking a known build from new authorizations', [build, buildKind]),
  sub('restore', 'Restore authorization for a known mod build or loader release', [build, buildKind]),
];
export const buildActions = buildCommands.map(command => command.name);
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
    options: [user,
      option('reissue', 'Explicitly replace all outstanding activation keys for an existing authorization', 5, false)] },
  group('license', licenseCommands, 'Manage a user\'s Sky-Myce license, device and access privately'),
  group('skyhawk', skyhawkCommands, 'View Sky-Myce licensing totals or find a registered account'),
  group('build', buildCommands, 'Review and manage authorized Sky-Myce mod and loader releases'),
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

export const userCommands = [
  ...[['status', 'Check your Sky-Myce license, linked Minecraft accounts and device'],
    ['key', 'Privately recover an eligible activation key'],
    ['loader', 'Get the current authorized Sky-Myce Loader privately'],
    ['help', 'Get setup guidance and help with your Sky-Myce access']]
    .map(([name, description]) => ({ type: 1, name, description, contexts: [0], integration_types: [0], options: [] })),
  { type: 1, name: 'account', description: 'Manage your own Sky-Myce account', contexts: [0], integration_types: [0], options: [
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
