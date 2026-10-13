# Cosmetics frontend migration — September 27, 2026

October 13 source update: the current Studio also equips and persists a UUID-scoped local appearance without Discord. Presets and validated local PNGs render on the actual local player in any world; None unequips and persists independently of deleting a stored PNG. Explicit **Publish appearance to community** retains current session ownership and revision checks and leaves the local preference equipped. Native bounded Minecraft name JSON preserves component arrays and per-character styling; already decorated nametags no longer undergo recursive username substitution. See [the current Studio contract](COSMETICS_STUDIO.md) for storage, limits and public/private boundaries. This paragraph describes the current source; the migration, production observations and old license/HWID flows below are historical September/October records and do not establish deployment of this release.

October 10 update: the IRC hosting deferral recorded below has ended. The persistent global User Bot Gateway now runs on Cloudflare, with a real Minecraft ↔ Discord round trip verified. See the [current implementation report](implementation-overhaul-2026-10.md). This does not alter the cosmetics migration or its ownership checks.

Cosmetics now belong to **MouseBot Frontend**. Use its `/cosmetics` command in `#bot-commands`. The normal account, license and Minecraft identity checks still apply. A migrated appearance does not activate an account or grant a license.

## Fixes

The Backend application's old command wrote the legacy `skyblock-relay` room database, while the current Minecraft client connects to the separate `sky-myce-backend` database. The new Durable Object also checked legacy Discord configuration aliases that were normalized only in the HTTP Worker, so in-game linking could return `not_configured` despite valid `USER_BOT_APPLICATION_ID` and `USER_BOT_PUBLIC_KEY` settings.

Both runtime entry points now normalize the existing configuration names. The frontend command uses the same persistent room database as the current client, and acknowledges slow operations immediately with an ephemeral deferred response. Existing account authorization, identity verification, styling validation and mention suppression remain in place. No client rebuild was needed.

Architecture used: `D:\Code\Sky-Myce\docs\secure-system.md`.

## Data and registration

- `friends`: copied **2 appearance profiles and 2 ownership links**; destination previously had none. Exact before/after hashes match, including names, JSON styles, X/Y/Z sizes, revisions and timestamps. Both ownership imports succeeded without conflicts. The existing importer does not alter licenses or device state.
- `testing`: both databases were empty; remained empty.
- Original database retained. Backup was written and read back before any copy. Existing destination records would have been preserved. Copy was bounded and retry-safe.
- Frontend guild command created: `cosmetics`, ID `1553653358743785573`, with `link`, `set`, `show`, `reset`, `unlink`. Its existing `account` and `hwid` registrations were unchanged.
- Backend guild command intentionally removed: only `cosmetics`, ID `1553072445403963472`. `authorise`, `license`, `account`, `hwid`, `session`, `warning`, `audit` were verified unchanged.
- No bulk overwrite or global registration used. Old cosmetic writes are disabled by `COSMETICS_FRONTEND_OWNED`; stale command selections direct users to the frontend. Old in-game link requests require updating to the current client.
- `/cosmetics unlink` explains `/account unlink` and `/cosmetics reset`, preserving the existing secure-account architecture. It does not silently unlink the wider account. Old link codes are not copied; request a fresh code from the current authenticated Minecraft session.

The user explicitly approved the one-time private migration after automatic approval review rejected temporary operator tools. The approved transfer used private Cloudflare service bindings, with no public migration HTTP endpoint and no generic account-data reader. Temporary operator entrypoints, Durable Object migration methods and unused helpers were removed and both Workers redeployed afterward.

Final account Worker version: `19b0faaa-6841-4dc5-b7b1-066e2d8a5323`. Final legacy/Backend Worker version: `84dffd49-3e25-4c06-89e3-7c4d711e0f4e`.

Local evidence (ignored by Git): `build/cosmetics-migration-before.json`, `build/frontend-cosmetics-registration.json`, `build/cosmetics-cutover-verified.json`, and `build/cosmetics-backup-2026-09-27T06-27-31.166Z-728d6403-6e07-4e48-bc62-1f85734c8137.json` with its `.verified.json` report. Backups contain account references and should stay private. Tested one-time migration source is archived under `build/cosmetics-migration-support-archive`.

## Repository changes

`D:\Code\Cessna fork\relay`: inspected existing handlers, registration, configuration and tests. Changed `src/index.ts`, `wrangler.jsonc`, `package.json`, `scripts/register-cosmetics.mjs` and documentation; created `test/cosmetics-retired.mjs`. Backend HTTP transport, administrative forwarding, legacy data and unrelated features remain intact. Temporary export additions to `src/cosmetics.ts` were removed after copying.

`D:\Code\Sky-Myce`: inspected both relay/client paths, `docs/secure-system.md`, registration helpers, User Bot runtime and configuration. Changed `relay/src/index.ts`, `relay/src/discord-accounts.ts`, `relay/test/relay.mjs`, `discord-bots/src/backend-bot.mjs`, `discord-bots/test/bridge.test.mjs`, the duplicate legacy registration script and setup documentation. Created `relay/test/cosmetics-strict.mjs` and this report. Temporary migration tests/source were tested, then archived outside the production sources.

No new credentials or bot permissions are required. Configuration names relevant to the change: `USER_BOT_APPLICATION_ID`, `USER_BOT_PUBLIC_KEY`, `DISCORD_GUILD_ID`, `DISCORD_INTERACTIONS_ROOM`, `COSMETICS_COMMAND_CHANNEL`, `COSMETICS_FRONTEND_OWNED`. Existing secret storage remains unchanged. No server roles, channels or permission overwrites changed during this task.

## Validation and remaining live checks

The strict integration test uses real test activation, installation and Minecraft identity proofs in Workerd. It covers canonical deployed variable names, signed frontend commands, deferred private replies, self-only ownership, names/axes, live cosmetic events, fresh/expired/replayed/wrong-caller linking, license revocation, and production/test room separation. Existing relay/account/Discord regression tests also passed before cutover.

The one-time migration tests passed in Workerd, including destination conflict preservation, unchanged licenses, unlink markers, atomic batch rollback, bounded pagination, retries and private service-binding access. Actual production data was then copied and hash-verified. Frontend registration tests passed, including preserved account/HWID roots and targeted registration without overwrite. Final post-cleanup test/deployment results are recorded below.

The user confirmed a real frontend `/cosmetics show`: **saved cosmetics appear correctly**. Live Minecraft rendering must not be inferred from that retrieval or local tests alone.

Final validation: main Worker typecheck and full suite passed, including strict cosmetics, account/DRM, IRC, nine Discord tests and release signing. Legacy typecheck and relay/digest/social/cosmetics regressions passed; a test assertion for the removed RPC was corrected, then all seven administrative/retirement tests passed. The combined User Bot/registration suite passed 12/12; the shared registration/bridge subset was rerun and passed 6/6. Both repositories passed whitespace checks. Live endpoint identities, command inventories, empty global registries, health responses, unsigned-interaction rejection and absent migration routes were verified in `build/cosmetics-final-live-verified.json`. Both live bundles were downloaded through the authenticated Cloudflare API and contain no migration entrypoint or RPC symbols. No secrets were printed or rotated.

IRC hosting was deferred at the user's request. Before stopping, bounded live probes verified the User Bot Gateway identity/intents, channel access, and authenticated protocol-3 handshakes for `friends` and `testing`. No chat was sent. All probe connections closed; `IRC_ROOMS` remains empty and there is no persistent Gateway process, so Discord IRC forwarding is not live.
