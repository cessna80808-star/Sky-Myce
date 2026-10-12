# Sky-Myce Discord operator tooling

The User Bot runtime is now in [discord-user-bot](../discord-user-bot/README.md). Follow that README for installation, configuration, server setup, limits and the Developer Portal checklist. Existing imports/launches through src/user-bot.mjs and src/relay.mjs delegate to that one implementation; do not start both entry points.

This directory retains the existing command definitions, targeted registration and server-provisioning tools. The real Backend Bot stays in D:\Code\Cessna fork\relay; no replacement Backend Gateway process was created.

Backend access comes from persisted administrator accounts in the account Worker's global `AccountStore`, plus the configured guild and signed Discord identity. The initial administrator is `978376257534328883`; existing administrators use `/admin add user:@person` to grant access. `ADMIN_BOOTSTRAP_USER_ID` belongs only to the account Worker. Discord roles, generic Administrator permission, and developer allowlists do not grant backend command access. `DEVELOPER_ROLE_ID` and `DEVELOPER_USER_IDS` remain operator provisioning inputs for staff-channel visibility only; command visibility settings are also separate from authorization.

Prompt commands are a separate capability: only Discord users `1250627753191931914` and `978376257534328883` in the configured guild can submit or manage prompt jobs. Account administrators and Discord roles do not bypass that check. The [staged prompt runner guide](../prompt-bridge/README.md) records the approved release baseline, disabled execution, runtime blockers, and local checks.

- src/backend-bot.mjs: community user/admin command definitions and the explicit legacy-workflow retirement manifest.
- src/register.mjs and src/registration.mjs: inventory, diff and targeted POST/PATCH/DELETE; no bulk overwrite or deletion of unrelated commands.
- src/provision.mjs and src/provisioning.mjs: discover/reuse roles/channels and apply reviewed overwrites.
- src/discord-api.mjs: bounded Discord API requests and operator-only configuration loading.
- test/bridge.test.mjs: bridge and command boundary regressions, also run by the new package.

The local Bot Documentation.md is an ignored operator credential file. Never commit, print, or load it into the User Bot process. Shared account operations continue to use the existing signed Worker endpoints, not an invented bot-authenticated admin API.

See [the precise implementation report](../docs/discord-implementation-report.md) for live registrations, infrastructure changes, local tests, migration status and remaining deployment dependencies.

The current source replaces license approval, activation keys, Loader delivery and
HWID-reset workflows with self-service Discord connection, private account/device
association reports and Discord/Minecraft identity blacklist management. Hardware
identifiers are informational metadata, never blacklist targets or permission to
use the mod. The new `/connect`, `/account` and `/blacklist` schemas require their
matching community Worker implementation before live registration. Historical
deployment reports describe the earlier deployment; local changes do not update
that live service. See [current command and migration instructions](../docs/discord-ux.md).

The same targeted User registration adds one `/leaderboard` command with category,
metric, UTC period, search and page options. RNG posts run in the existing User Bot
Gateway through a dedicated signed outbox, rather than another application or
command family. The [main-machine handoff](../docs/main-machine-community-rollout.md)
lists required permissions, OAuth variables, dedicated feed channel, cape bucket
bindings and release/deployment order; it does not claim these live steps ran here.
