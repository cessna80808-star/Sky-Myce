# Sky-Myce Discord implementation report

Verified September 27, 2026. The real account/DRM backend, private loader and signed release are deployed separately as `sky-myce-backend`; `/authorise` DM delivery was confirmed by the user. Cosmetics now belong to MouseBot Frontend, with two appearance profiles and two ownership links copied and hash-verified. The user confirmed `/cosmetics show` displays saved settings correctly. See [cosmetics migration](cosmetics-migration.md) and [backend deployment](backend-deployment.md) for current evidence. IRC hosting was deferred by the user; no persistent Gateway runs. Existing production storage, tokens, applications, roles and channels were preserved. Earlier setup details below are retained as history where explicitly dated.

Latest administrator update: backend commands now require a persisted administrator account in the global `AccountStore`, with initial administrator `978376257534328883`. Existing administrators grant access with `/admin add user:@person`. Developer roles, developer allowlists and generic Discord Administrator permission do not grant backend authority. Both Workers are deployed, `/admin` is registered as the eighth Backend root, and the user confirmed a real `/admin add` targeting themselves returns already an administrator. Frontend commands and the deferred IRC status are unchanged.

## Cross-lobby cosmetics 2.4.3 — deployed

The follow-up addresses names appearing correctly only when both players share a lobby. The client now uses known friend/party identities and queues observed chat/menu player names instead of depending solely on the local tab list. The relay accepts up to 16 combined UUID/name selectors, resolves names only from authenticated Minecraft identities, and subscribes resolved players to existing live appearance updates. Unknown or ambiguous names are omitted; third-party shared stats cannot establish identity. Old UUID-only clients retain their existing response shape and new clients detect the `cosmeticsNames` capability. Client framing now accepts `cosmetics_result` within the existing 64 KiB message cap, including batches of styled names.

Mod 2.4.3 is live on account Worker version `9a66b1c6-a682-4e12-bd1e-2fac2e430715`. Its `ARTIFACT_MANIFEST_KEY` selects `releases/manifest-2.4.3.json`; the private artifact is `releases/Sky-Myce-2.4.3.jar`, SHA-256 `1109b8f391b5df754a9aed0513b970c7459a5730090335c0928d5f495fb2c18c`. Both objects were uploaded and downloaded for verification before the Worker switched version and manifest selection together. The 2.4.2/current-manifest objects and original loader 1.0.0 artifact remain intact; the legacy Backend Worker was not redeployed. The live manifest's version, signature and hash were verified. **Real Minecraft cross-lobby chat/menu rendering remains unverified.** See [backend deployment](backend-deployment.md) for the renewal procedure.

Main relay typecheck and the complete regression suite passed, including cross-lobby lookup/subscription, input bounds, ambiguous/missing identities, upload spoof rejection, room isolation, unauthorized and revoked sessions. The full Java 25 production build passed, including HUD/loader, cross-lobby cosmetics and large styled-packet checks. Evidence: `build/cosmetics-cross-lobby-check.log`, `build/cosmetics-cross-lobby-relay-tests.log`, `build/cosmetics-cross-lobby-build.log` and `build/cosmetics-2.4.3-live-verified.json`. Existing confirmed admin access, frontend cosmetics migration and saved ownership remain intact. No Discord registration, role/channel permission or Developer Portal change was made. Persistent IRC hosting remains deferred.

## Repository work

There were substantial pre-existing uncommitted changes in both repositories, including the main workspace's account/DRM implementation. They were retained. The lists below distinguish this task's work from those pre-existing changes; they are not an assertion that every untracked file was created by this task.

### `D:\Code\Cessna fork\relay`

Inspected: `AGENTS.md`, `README.md`, `package.json`, `wrangler.jsonc`, `.gitignore`, the `src` tree, `src/index.ts`, `src/discord.ts`, `src/cosmetics.ts`, `src/digest.ts`, `scripts/register-cosmetics.mjs`, and existing relay/digest/social/cosmetics tests and environment conventions.

Changed:

- `src/discord.ts`: exposes original request bytes only after successful existing signature/application verification.
- `src/index.ts`: dispatches verified administrative interactions through the shared forwarder; supports configured Backend application/public-key aliases and preserves HTTP transport. Cosmetics are frontend-owned after cutover.
- `package.json`: includes the new authorization regression test in the existing suite.
- `wrangler.jsonc`: configures guild/application identity and the real account-backend origin/service binding. The latest administrator update removes role/allowlist authorization from the forwarder; only the account Worker owns persisted administrator state.
- Historical service-unavailable follow-up: `src/discord-admin.ts` forwards via the `SKY_MYCE_BACKEND` HTTP service binding configured in `wrangler.jsonc`. Remote preview reproduced public Worker-to-Worker error 1042 and unsupported `redirect: error`. The forwarder and account Worker's Discord calls use `manual` and reject redirects. Safe failure categories are logged without request bodies or secrets. `test/discord-admin.mjs` includes signed two-Worker runtime coverage; that fix's Backend deployment was `1d71c395-e522-4d2b-86ea-5fac949f4290`.
- Latest administrator update: the forwarder validates signed caller/application/guild identity and forwards to the real account Worker without a developer-role gate. It forwards `/admin add` while preserving every existing administrative command. Backend unavailability still fails closed.
- `.gitignore`, `README.md`: safe example configuration and transition/deployment instructions.

Created: `src/discord-admin.ts`, `test/discord-admin.mjs`, `.dev.vars.example`.

The pre-existing `src/digest.ts`, `test/digest.mjs`, and news-channel `wrangler.jsonc` changes were retained. Comparing the production bundle with the tested candidate showed only the intended Discord handler changes. The Backend Bot source was not moved.

### `D:\Code\Sky-Myce`

Inspected: `docs/secure-system.md`, `docs/LOADER.md`, root configuration/ignore rules, the pre-existing `discord-bots` sources/package/tests/example configuration, operator credential document, `relay/AGENTS.md`, `relay/README.md`, `relay/package.json`, `relay/wrangler.jsonc`, `relay/src/index.ts`, `discord.ts`, `discord-accounts.ts`, `discord-download.ts`, `accounts.ts`, `licensing.ts`, `drm.ts`, `irc.ts`, `cosmetics.ts`, and the account/IRC/Discord and existing relay regression tests. Real configuration was inspected without printing credentials.

Changed existing files:

- `.gitignore`: excludes the supplied credential document explicitly.
- `discord-bots/src/backend-bot.mjs`: preserves administrative command names, makes authorise's target a Discord user option, adds self-service `/hwid reset` while retaining `/account reset`, and defines Backend-only `/admin add user` with disabled default command permissions. Frontend cosmetics remain separate.
- `discord-bots/src/register.mjs`: authenticated inventory/diff and safe individual registration.
- `discord-bots/src/user-bot.mjs`, `src/relay.mjs`: compatibility entry points to the single new User Bot implementation.
- `discord-bots/test/bridge.test.mjs`, `.env.example`, `.env.backend.example`, `README.md`: protocol tests, corrected configuration and current setup references.
- `relay/src/accounts.ts`: binds link challenges to Discord identity, rejects historical unbound challenges, preserves self-service cooldown during administrative overrides, and exposes activation completion separately from account policy status. The latest additive change stores administrator membership, seeds the configured initial administrator once, and authorizes/audits `/admin add` without granting player licenses or devices.
- `relay/src/licensing.ts`: caller/guild identity checks support persistent authorization; the previous developer-role/allowlist policy is superseded.
- `relay/src/discord-accounts.ts`: checks persistent administrator authority for backend commands, supports `/admin add`, the HWID alias and canonical configuration names, fails closed without the account store, shows pending activation accurately, and includes safe audit timestamps. Deployment follow-up adds an explicit disabled-loader mode and private installation instructions; live configuration enables loader delivery.
- `relay/src/index.ts`, `src/irc.ts`: trusted room/source/message metadata, one active bridge per room, authenticated identity binding, persistent bounded deduplication and quotas.
- Cross-lobby cosmetics follow-up: `relay/src/index.ts` adds indexed, bounded lookup by verified Minecraft name and capability negotiation; `relay/test/cosmetics.mjs` and `test/cosmetics-strict.mjs` cover compatibility, identity trust, subscriptions and access checks. Client `CosmeticsClient.kt` / `DungeonFriendRelay.kt` and `CosmeticsCheck.kt` extend identity gathering, refresh, packet handling and regression coverage. `gradle.properties` and `relay/wrangler.jsonc` select deployed 2.4.3 and its versioned manifest.
- `relay/test/accounts.mjs`, `test/irc.mjs`, `test/discord-bots.mjs`, `.dev.vars.example`: security/concurrency tests and configuration limits.
- `docs/secure-system.md`: a status/link addendum; the wider architecture was retained.
- Deployment follow-up: `relay/wrangler.jsonc` adds an explicit isolated `backend` environment, signing/public configuration and private R2 binding; `relay/.gitignore` ignores generated deployment output. Existing release/build scripts were reused. Client `DungeonFriendsConfig.kt` / `DungeonFriendRelay.kt` and `DungeonFriendsCheck.kt` migrate only the exact legacy relay default to the new strict backend and test preservation of custom/disabled endpoints. `relay/README.md` and `discord-user-bot/README.md` link the current deployment record.

Created:

- `discord-user-bot/src/config.mjs`, `src/relay.mjs`, `src/user-bot.mjs`.
- `discord-user-bot/package.json`, `package-lock.json`, `tsconfig.json`, `.env.example`, `.env.setup.example`, `setup.ps1`, `README.md`, `test/core.test.mjs`.
- `discord-bots/src/discord-api.mjs`, `src/registration.mjs`, `src/provisioning.mjs`, `src/provision.mjs`.
- This report, `docs/discord-implementation-report.md`.
- Deployment follow-up: `docs/backend-deployment.md`; generated signed release/JARs and verification scripts/logs under ignored `build/`; ACL-protected signer and Worker secret files outside the repository.
- Ignored `discord-user-bot/.env`: user-only runtime credentials, with IRC explicitly disabled because hosting was deferred. No Backend token is in this file.

Generated/ignored evidence is under `build/discord-*` and `build/backend-*`: registration and infrastructure snapshots, permissions/Gateway checks, test/build/deployment logs and live artifact verification. No commit was made. Both the preserved Backend forwarder and the separate new account Worker were deployed.

## Existing Backend Bot

The installed Backend application is named **Mousebot**, with server role **SM Backend**. Its existing application, token, source location and interaction URL were preserved.

| Area | Finding/result |
| --- | --- |
| Interaction transport | Signed HTTP at the existing `/discord/interactions`; no Gateway added |
| Existing registration | `scripts/register-cosmetics.mjs` used GET then targeted create/edit; no bulk overwrite |
| Commands discovered live before changes | `/cosmetics link`, `set`, `show`, `reset`, `unlink` |
| Existing public commands | All cosmetics subcommands, subject to their existing guild/channel/ownership checks |
| Existing developer-only commands | None in the old bot's live inventory |
| Commands preserved | All seven existing administrative roots and news integration; cosmetics moved to the frontend |
| Privileged commands | Eight live roots: `/admin add user`, `/authorise`, `/license …`, `/account …`, `/hwid …`, `/session kick`, `/warning …`, `/audit` |
| Access policy | Persisted Sky-Myce administrator accounts only; no existing public command was converted |
| Authorization | Verified caller from configured guild AND persisted AccountStore administrator membership; target identity never substitutes for caller; roles/allowlists/Discord Administrator have no bypass |
| Handler changes | Verify original signed body/application/guild, forward those bytes to the real backend, verify again and check persisted administrator authority; no forwarder role gate |
| Backend availability | Real signed account handler is deployed and configured; failures still return an ephemeral service-unavailable response |
| Old Discord IRC listener | None found; legacy REST news reads are unrelated to IRC |
| Second implementation | Partial User Bot and account handlers already existed in the main workspace and were extended/reused |

Administrative definitions, signed forwarding and persisted-administrator checks are deployed live. The Backend application's HTTP endpoint URL remains unchanged. Signed requests are reverified by the separate account Worker; unsigned live requests returned HTTP 401. The user confirmed real `/authorise` key/loader delivery before this policy update and real `/admin add` authorization after it; deployment probes themselves issued no activation keys or DMs.

Cosmetics now runs through the frontend's signed HTTP endpoint against the strict backend database. The old Backend handler rejects cosmetic mutations and its exact command registration was removed after parity tests and verified data transfer. No public cosmetics command became developer-only. See the [migration report](cosmetics-migration.md) for this follow-up's inspected/changed/created files and validation.

## User Bot

- Folder: `D:\Code\Sky-Myce\discord-user-bot`.
- Prepare: `./setup.ps1`; start: `npm start` from that folder. Independently runnable; old compatibility imports do not create another implementation.
- Live guild roots: `/account`, `/hwid`, `/cosmetics`. Account subcommands are status/approve/link/unlink/reset; HWID has reset; cosmetics has link/set/show/reset/unlink. No bare account operation.
- Gateway intents: Guilds, Guild Messages, Message Content only. Message Content was enabled using the documented application API; Presence and Members remain disabled. A real connection with those intents passed and was then stopped.
- Relay: existing HMAC-authenticated `/discord/bridge` protocol, TLS, nonce replay checks, trusted room mapping, source/message-ID validation, one active server bridge per room, bounded retries/caches/quotas, no offline queue, mentions disabled. No administrative service credential is used.
- Accounts: existing signed HTTP interaction/account-store flow; Discord identity comes from verified interaction data. Five-minute challenges bind verified game/session identity and the intended Discord identity, and are atomically consumed.
- HWID: caller-only reset alias, atomic seven-day cooldown, administrative overrides leave the self-service timestamp unchanged. First binding starts the initial cooldown under the existing architecture.
- Notifications and authorise delivery reuse the existing backend implementation. After the final fix, the user confirmed a real authorise DM containing the key and loader.
- Runtime status: installed and Gateway-tested; no persistent service left running. IRC remains disabled in the prepared local environment. The User application's HTTP interaction URL is now the real account Worker's `/discord/interactions`; Discord accepted its signed PING. HTTP account commands operate independently of the Gateway process. A bounded authenticated testing-room relay handshake passed and closed without sending chat.

Exact bridge defaults: text 256 UTF-16 code units each direction, reject oversize; per-user burst 4/replenish 1 per 3 seconds; global burst 20/replenish 1 per second; Node dedup 1,024 IDs per direction/room with five-minute TTL; backend dedup 4,096 IDs per room with five-minute TTL. WebSocket payload cap 8 KiB, send-buffer cap 16 KiB, at most 128 pending delivery results with ten-second expiry. Reconnect delay is bounded exponential backoff with jitter capped at 60 seconds, reset after a stable connection/pong. No attachments are forwarded. The architecture supplied the text/user quota; added global/cache defaults are conservative and configurable.

## Discord infrastructure

Live guild: **Sky Myce**. Existing names were retained where equivalent. The only new server object was the IRC category.

| Object | ID | Result |
| --- | --- | --- |
| Developer role `SkyMyce Dev` | `1552053269000364032` | Reused; role permissions/name not changed |
| `📡 SKY-MYCE IRC` category | `1553628582499057698` | Created |
| Production `#irc` | `1553536927498506270` | Reused; moved into IRC category; ordinary read/send and User Bot access configured |
| Testing `#irc-backend` | `1553537591960412241` | Reused; moved into IRC category; User Bot access configured; existing frontend/developer role access retained |
| Staff `dev logs` category | `1553538004469940294` | Reused; staff overwrites tightened |
| `#audit-log` | `1553538991502925844` | Reused; private staff/Backend access configured |
| `#security-alerts` | `1553538215762206720` | Reused; private staff/Backend access configured |
| `#licenses` | `1553539130624053400` | Reused; private staff/Backend access configured |

No server/application names or existing channel names were changed. Existing unrelated channels and role memberships were retained. Normal roles and the User Bot cannot view the three staff channels; the Backend has View/Send/History/Embed permissions. Configured staff roles/users retain channel access. These channel permissions are separate from persisted administrator authority. Owner/Discord Administrator access is intrinsic to Discord and is not a Sky-Myce backend authorization bypass.

Temporary Manage Channels/Manage Roles were granted by the operator. Discord initially rejected edits to channels the bot could not view; the operator supplied that access and setup resumed without duplicate creation. The Backend's original IRC role overwrites were restored. Discord rejected the bot editing its own managed role, so the operator removed both temporary management permissions; their removal and absence of Administrator were verified through the API. Final provisioning preview reports no remaining changes.

Room isolation is routing-based: `friends` only targets production and `testing` only the test channel. The test channel retains existing frontend-role visibility; this report does not claim only developers can view it.

## Configuration

Names only below; secret values are never included. The required subset depends on the process and enabled feature. Do not copy all configuration into the User process.

| Scope | Names |
| --- | --- |
| User runtime required | `DISCORD_GUILD_ID`, `USER_BOT_APPLICATION_ID`, `USER_BOT_TOKEN` |
| Enabled IRC | `IRC_ROOMS`, `DISCORD_IRC_CHANNEL_ID`, `DISCORD_IRC_TEST_CHANNEL_ID`, `SKY_MYCE_RELAY_URL`, `SKY_MYCE_RELAY_AUTH_SECRET` |
| User limits | `IRC_OUTBOUND_LENGTH`, `IRC_INBOUND_LENGTH`, `IRC_USER_BURST`, `IRC_USER_REFILL_MS`, `IRC_GLOBAL_BURST`, `IRC_GLOBAL_REFILL_MS`, `IRC_DEDUP_SIZE`, `IRC_DEDUP_TTL_MS` |
| Backend signed forwarding | `DISCORD_GUILD_ID`, `BACKEND_BOT_APPLICATION_ID`, `BACKEND_BOT_PUBLIC_KEY`, `SKY_MYCE_BACKEND_URL` |
| Operator registration/provisioning | `BACKEND_BOT_TOKEN`, `BACKEND_BOT_APPLICATION_ID`, `USER_BOT_APPLICATION_ID`, `DISCORD_GUILD_ID`, `DEVELOPER_ROLE_ID`, `DEVELOPER_USER_IDS`, `DISCORD_IRC_CATEGORY_ID`, `DISCORD_STAFF_CATEGORY_ID`, `DISCORD_IRC_CHANNEL_ID`, `DISCORD_IRC_TEST_CHANNEL_ID`, `DISCORD_AUDIT_CHANNEL_ID`, `DISCORD_SECURITY_CHANNEL_ID`, `DISCORD_LICENSE_CHANNEL_ID`, optional `SKY_MYCE_DISCORD_CONFIG_PATH` |
| Real account Worker | `USER_BOT_APPLICATION_ID`, `USER_BOT_PUBLIC_KEY`, `BACKEND_BOT_APPLICATION_ID`, `BACKEND_BOT_PUBLIC_KEY`, `USER_BOT_TOKEN`, `BACKEND_BOT_TOKEN`, `DISCORD_GUILD_ID`, `ADMIN_BOOTSTRAP_USER_ID`, `DISCORD_AUDIT_CHANNEL_ID`, `DISCORD_SECURITY_CHANNEL_ID`, `DISCORD_LICENSE_CHANNEL_ID`, `DISCORD_BRIDGE_SECRET`, `DISCORD_LOADER_DOWNLOAD_SECRET`, `SKYMYCE_PUBLIC_URL` |
| Established backend aliases/settings | `DISCORD_APPLICATION_ID`, `DISCORD_PUBLIC_KEY`, `DISCORD_USER_APPLICATION_ID`, `DISCORD_USER_PUBLIC_KEY`, `DISCORD_BACKEND_APPLICATION_ID`, `DISCORD_BACKEND_PUBLIC_KEY`, `DISCORD_BACKEND_BOT_TOKEN`, `RELAY_URL`, `IRC_BURST`, `IRC_REFILL_SECONDS`, `ROOMS`, `DISCORD_INTERACTIONS_ROOM`, `COSMETICS_COMMAND_CHANNEL` |
| Architecture/release configuration | `SKY_MYCE_ARCHITECTURE_SPEC_PATH`, `SKYMYCE_ENVIRONMENT`, `DRM_KEY_ID`, `DRM_PUBLIC_KEYS`, `DRM_SIGNING_PRIVATE_KEY`, `SKYMYCE_LOADER_R2_KEY`, `ARTIFACT_MANIFEST_KEY` |
| OAuth/installation placeholders, unused by current linking | `BACKEND_BOT_CLIENT_ID`, `USER_BOT_CLIENT_ID`, `USER_BOT_CLIENT_SECRET`, `DISCORD_OAUTH_REDIRECT_URI` |

Worker bindings such as AccountStore/private R2 stay in the established Worker configuration. The Node process requires neither Backend secrets nor disabled OAuth configuration. Credentials supplied in the operator document and the prepared User `.env` are ignored by Git. A comparison against the supplied secret values found none in tracked/nonignored source files. Existing ignored production reference files were not reset or silently repurposed; their older User application references must not override the newly verified application's configuration.

`ADMIN_BOOTSTRAP_USER_ID` belongs only to the account Worker and seeds persisted membership once; it is not an always-on authorization bypass. `DEVELOPER_ROLE_ID` and `DEVELOPER_USER_IDS` remain staff-channel provisioning inputs only. They no longer authorize backend requests. The User Bot never receives an administrator credential or bootstrap configuration.

## Command registration

Before: Backend had one guild root, cosmetics; User had none. Both had zero global commands.

After the cosmetics follow-up: Backend has seven guild roots; User has three. The latest change created frontend cosmetics and intentionally deleted only the old Backend cosmetics registration; account/HWID and all administrative definitions were verified unchanged. Globals remain empty. **Bulk overwrite was not used.** Every mutation followed authenticated GET inventory.

Latest administrator update: `/admin add user` was created as the eighth Backend guild root with ID `1553661667454226524`. The seven existing Backend roots and all three frontend roots were verified unchanged. Both applications still have zero global commands. This addition used authenticated inventory followed by targeted creation; no command deletion, migration or bulk overwrite was used. Evidence: ignored `build/backend-admin-registration.json`.

All commands are type 1 (chat input). The complete descriptions/options are retained in the local registration inventory; concise inventory follows.

| Application | Command ID | Root | Subcommands/operation | Classification |
| --- | --- | --- | --- | --- |
| User | `1553653358743785573` | cosmetics | link, set, show, reset, unlink | Caller-only; replaces Backend ID `1553072445403963472` |
| Backend | `1553661667454226524` | admin | add user | Persisted administrator |
| Backend | `1553626188721692692` | authorise | user target, optional explicit reissue | Persisted administrator |
| Backend | `1553626189485182987` | license | status, revoke, restore, extend | Persisted administrator |
| Backend | `1553626190210928713` | account | lookup, unlink, suspend, unsuspend | Persisted administrator |
| Backend | `1553626191607636008` | hwid | reset, revoke, bind | Persisted administrator |
| Backend | `1553626192626843678` | session | kick | Persisted administrator |
| Backend | `1553626206237364325` | warning | add, remove | Persisted administrator |
| Backend | `1553626223005933598` | audit | target account audit | Persisted administrator |
| User | `1553626228018249743` | account | status, approve, link, unlink, reset | Caller-only |
| User | `1553626232002977873` | hwid | reset | Caller-only |

Backend commands have default member permissions disabled. Role/user-specific integration overrides were **not** configured with a bot token. They remain a manual owner action or require separately authorized OAuth Bearer credentials. Visibility does not grant backend authority: the account store checks persisted membership even when Discord permits the interaction. The Server Settings → Integrations path is in the checklist.

## Backend dependencies

Exact architecture specification used: **`D:\Code\Sky-Myce\docs\secure-system.md`**. No architecture path override was configured. This clearly identifiable in-repository document was found before introducing account integration changes.

Available and deployed: real persistent account store, signed Discord interaction handlers, Minecraft/install proofs, activation issuance/redemption, license/session authorization, account linking, reset transactions, audit records, private loader delivery, authenticated IRC relay. These implementations were reused and tested, not replaced with in-memory production state.

Configured live: AccountStore and strict RelayRoom namespaces, separate signing/bridge/download secrets, private R2 loader and signed full-mod release, original Backend forwarding origin, both application identities, and the User HTTP interaction URL. The backend follows the existing specification. OAuth is not used and no callback was invented.

Account operations and loader delivery are configured; DM delivery and frontend cosmetic retrieval were confirmed by the user, while the complete game lifecycle remains unverified. Discord IRC forwarding is **UNAVAILABLE — persistent User Gateway service not running**, explicitly deferred by the user. Cosmetics migration is complete; original legacy data remains intact. Missing dependencies still fail closed.

The main workspace's default Worker name still collides with the legacy production Worker. Deployment explicitly used **`--env backend`**, creating the separate `sky-myce-backend` instead of replacing the legacy service. Use that explicit environment for subsequent account-backend deployments; staging remains separate.

## Manual actions

The [User Bot README](../discord-user-bot/README.md#developer-portal-and-deployment-checklist) contains the exact Portal/environment checklist. Remaining actions:

1. Complete Minecraft enrollment and account-operation checks. The user's real `/authorise` retry already confirmed private key/loader delivery; deployment probes themselves issued no activation keys or DMs.
2. Renew the deployed 2.4.3 signed release manifest before October 4, 2026, 07:17:39.772 UTC (09:17:39.772 Stockholm), exact expiry `2026-10-04T07:17:39.772Z`. Publish to the configured `releases/manifest-2.4.3.json` key; follow the deployment runbook. Back up the existing signer in protected secret storage.
3. IRC hosting is deferred. When requested, install one supervised User Gateway with user-only credentials and verify both directions before enabling production. HTTP account and cosmetic interactions already operate independently.
4. Both application endpoints, public keys, Worker secrets and private R2 objects are already configured; no further Portal endpoint change is required for this deployment.
5. Owner: inspect the User application's privileged-intent review/renewal notices. Message Content is already enabled and Gateway acceptance passed; approval status beyond the toggle was not inspected through an owner Portal session. Current policy uses 10,000 unique accessible users and annual reapplication after approval. [Official policy](https://support-dev.discord.com/hc/en-us/articles/40281523410967-Changes-to-Privileged-Intent-Access-for-Discord-Apps).
6. Server Settings → Integrations → Backend application → Manage: grant command visibility to persisted Sky-Myce administrators, including the initial account and new accounts added through `/admin add`. Cosmetic commands belong to the User application. Discord roles/visibility never substitute for backend administrator membership. No OAuth Bearer permission authorization was available for this operation.
7. Start one supervised User Bot process after configuring the real service. Use testing first and verify actual private replies, activation/DMed loader, blocked-DM recovery, concurrent resets and bidirectional IRC/mention safety with test accounts. Then enable production forwarding. No persistent process is currently left running.
8. Cosmetics migration and cutover are complete; no old Discord IRC listener needed retirement. Further live Minecraft rendering checks can be performed with an activated client.

Already completed: both bot installations, limited Message Content toggle, guild registrations, role/channel discovery/setup, staff permission changes and removal of temporary management permissions. OAuth client secret/redirect are unused by the implemented signed linking flow; there is no pending OAuth feature to enable by adding credentials alone.

## Validation

Passed locally:

- Original Backend current validation: `npm run check` and **7/7 combined administrative/cosmetics-retirement tests**, including signed two-Worker forwarding, `/admin` dispatch, removal of role/allowlist authorization and failure handling. Its full relay/digest/social/cosmetics suite passed earlier; the latest administrator update did not rerun that full suite.
- User Bot `npm run check` and **13/13** Node tests, including Backend-only `/admin add` shape and targeted registration preserving unrelated roots/subcommands. Historical setup: `setup.ps1 -SkipInstall` completed successfully; locked dependency installation reported no vulnerabilities.
- Main account relay `npm run check` and full `npm test`: relay/digest/social/cosmetics/account-DRM/IRC/bootstrap checks, persistent administrator membership and release signing checks. Current Discord handler suite: **10/10**.
- The 2.4.3 cross-lobby cosmetics source passed the same full relay suite and a fresh typecheck after the versioned manifest configuration changed. Tests verify name-only lookups receive later cosmetic changes without a shared lobby roster, while case handling, ambiguity, malformed requests, untrusted stats uploads, room isolation and revoked access remain safe. The full Java 25 production build includes cross-lobby rendering data and large styled-packet regression checks; actual Minecraft UI verification remains outstanding.
- Production-configured Java 25 `gradlew.bat build --offline`, including client/loader/HUD checks; R2 readback hash comparisons and live signed-manifest validation passed.
- Staging `wrangler deploy --dry-run --env staging`: packaging passed; nothing deployed.
- `git diff --check` for both repositories.

Current authorization cases covered: persisted administrator allowed without a developer role; ordinary, developer-role-only, old-allowlist-only and generic-Administrator-only callers rejected; wrong guild rejected; one-time bootstrap and `/admin add` preserve administrator membership without licensing/device grants; caller identity remains separate from target. The earlier role/allowlist-allowing matrix is historical and superseded. Other security coverage: exact-body signature tamper/application mismatch/expiry; user-supplied self-service target rejected; pending activation shown correctly; link validity/expiry/replay/wrong Discord and Minecraft identity; replacement/expired activation and previous-key invalidation; concurrent self-reset yields one winner; administrative override preserves cooldown; blocked/successful DM delivery behavior with intercepted HTTP; IRC unlinked/unauthorized rejection, authorized fan-out, bot/webhook suppression, duplicates, room isolation, single-bridge ownership, bounded rate/reconnect/cache behavior, and mention parsing disabled.

Previously verified live: both application token identities/guild membership, command inventories and post-registration diff, User Message Content flag with Members/Presence disabled, bounded real Gateway startup/shutdown, API channel/role discovery, final idempotent provisioning preview, staff effective permissions, User Bot HTTP 403 when reading each staff channel, Backend channel access plus View/Send/History/Embed permission calculation, and removal of temporary management permissions. Historical Backend-fix evidence includes deployed bundle equality, preserved original secret bindings/relay namespace, the then-current developer guard, relay health/WebSocket/unauthenticated-account rejection smoke checks, and unsigned administrative HTTP 401. The latest administrator update uses additive persistent state rather than retaining that historical role-based authority.

Latest administrator rollout verified live: account Worker version `7454f871-9307-420f-ab8c-1ce40e57b224`, legacy forwarding Worker version `7ece67bc-8b3b-432c-9ac4-1cab65d244c0`. The account Worker's `admin_bootstrapped` audit event records target `978376257534328883` at Unix-millisecond timestamp `1790492141520`, confirming the persisted initial grant. Both health endpoints returned HTTP 200 and unsigned interaction requests returned HTTP 401. Command inventories confirm Backend eight/User three roots, all prior definitions unchanged and no globals. The user then invoked real Backend `/admin add user:@self` and confirmed the already-administrator response. Evidence: ignored `build/backend-admin-registration.json` and `build/backend-admin-live.json`.

Additional deployment verification: both Worker health endpoints; live signed release and authenticated private-loader download with matching hashes; unauthenticated artifact/loader HTTP 403; unsigned HTTP interactions 401; authenticated testing bridge protocol/room handshake; User endpoint signed-PING acceptance; current application inventories; private R2 access and Cloudflare secret binding types. See the deployment record for exact evidence.

The 2.4.3 follow-up deployed account Worker `9a66b1c6-a682-4e12-bd1e-2fac2e430715` and verified health HTTP 200, unsigned interactions HTTP 401 on both application paths, unauthenticated artifact HTTP 403, and the live public manifest's version/signature/hash. The legacy Backend Worker remains at its previously verified version. R2 artifact/manifest readback matched the release. Evidence: `build/cosmetics-2.4.3-live-verified.json`.

The user confirmed `/authorise` delivered the key and loader, then confirmed frontend `/cosmetics show` displays saved settings correctly. Both saved profiles and ownership links were transferred and hash-verified. Remaining live gaps: complete Minecraft activation/rendering lifecycle, real self-service resets, blocked-DM recovery, IRC delivery/mention notifications, and owner-only privileged-intent review. Backend posting permission was verified without a test message. Fixture identities/keys were used only in local tests. See the [cosmetics report](cosmetics-migration.md) for follow-up tests and removed temporary migration access.
