# Sky-Myce accounts, loader, licenses and IRC

Deployment update (2026-09-27): the [User Bot setup guide](../discord-user-bot/README.md), [implementation report](discord-implementation-report.md), [backend deployment record](backend-deployment.md), and [cosmetics migration report](cosmetics-migration.md) supersede historical deployment status below. The real account backend and private R2 loader/signed release are deployed as `sky-myce-backend` using explicit Wrangler environment `backend`; the legacy Backend Bot remains separate. Cosmetics migrated to the frontend with saved data verified and confirmed by the user. `/authorise` DM delivery was also confirmed. Complete Minecraft activation/rendering checks remain pending; Gateway hosting was explicitly deferred. The architecture below remains the specification.

The original implementation was prepared for isolated staging. Subsequent user-authorized deployment actions are recorded separately above. Build/test results alone do not establish an end-to-end live Discord/game flow.

## Components and trust

`RelayRoom` remains the hibernating WebSocket relay, including private messages, LFG, stats, wealth, digest, RNG and cosmetics. A separate SQLite `AccountStore` instance named `global` owns Discord identity, Minecraft UUID associations, persistent device bindings, installations, licenses, hardware-transfer timestamps, challenge consumption and audit records. `friends` and `testing` share account rules within one deployment; their live traffic, caches and IRC channels remain separate. Staging has a different Worker, DO namespaces, signing keys, bot applications and private R2 bucket.

The loader's Minecraft certificate accessor obtains the existing UUID-bound Mojang account proof. The backend still verifies the certificate, its expiry, the challenge signature, the signed profile, name and UUID. No client-supplied UUID/name or later packet can replace that result. Enrollment also requires a fresh installation Ed25519 signature and the one-time activation code privately delivered to the target Discord account. The account is selected from the code's stored hash after both proofs, never from a caller-supplied Discord ID. This single-code flow implements the user's September 27 loader clarification. The older Discord-signed `/account approve` flow remains compatible; an approved challenge cannot be redirected with another account's code. Discord OAuth and its client secret are not required.

An admin uses `/authenticate user:@person` (or the retained `/authorise` alias) to issue a random 256-bit activation secret for one Discord account. Only SHA-256 and an opaque activation reference are stored. Keys expire exactly 24 hours after issuance and are consumed inside the same SQL transaction that binds the device, UUID and challenge. Reissuing requires explicit `reissue:true`; it revokes prior unused keys and does not reset the device. Unlimited practical UUID associations are supported without a hardcoded count cap. Each new UUID requires its own Minecraft proof and either a fresh activation code or the older explicit Discord approval flow. The DM code is an enrollment credential: the recipient must keep it private.

An account has one persistent device binding; a device binding belongs to one account. First activation enrolls the device once. Later launches verify that binding and issue a temporary lease without changing the binding or its timestamps. Closing Minecraft or the loader, OS restarts, shutdown, sleep, login/logout, network/IP/VPN changes, WebSocket reconnects, lease renewal and supported software updates do not reset the HWID. Linux, Windows and other supported platforms use the same rule; there is no Linux reauthorization-on-every-launch policy. The installation key remains the proof-of-possession anchor, with signed stable hardware evidence pinned separately from runtime/session state. Software identity cannot prove physical hardware against a fully compromised host; see [device storage and platform limitations](LOADER.md).

Different hardware returns `HWID_MISMATCH` and never silently replaces the binding. User `/hwid reset confirm:true` and the retained `/account reset confirm:true` prepare an intentional transfer with a private, 24-hour single-use activation code. Preparing or replacing that code preserves the existing device, leases and cooldown. An authenticated activation on a genuinely different device checks the seven-day policy again and atomically replaces the binding, revokes previous installations and lease generations, records the new binding/transfer timestamps and issues a new lease. Only this actual replacement consumes the transfer allowance; matching-device activation does not. First enrollment establishes the initial seven-day boundary. If no device is bound, these commands issue an enrollment code without consuming a transfer.

Administrator `/hwid reset` immediately invalidates the binding and affected sessions, preserves the self-service transfer timestamp, and requires legitimate enrollment on next activation. `/hwid revoke` deliberately blocks enrollment until an administrator permits recovery. Administrator reset/revoke/bind commands accept an optional private `reason` of up to 500 characters for the audit; exclude credentials and raw hardware identifiers. Security/administrative changes and actual transfers write transactional audits with previous/new opaque references, reason, source, timestamps and available administrator/build/session context; raw hardware identifiers and credentials are excluded. Normal matching launches produce no HWID-reset event. Status reports the verified device without promoting routine resets or displaying a transfer countdown.

Signed leases bind product/protocol, account, Discord ID, verified UUID, installation, derived device hash, key fingerprint, supported version, generation, server challenge ID, a fresh client-generated nonce, issuance/expiry and offline boundary. The locally retained client nonce prevents replaying an old challenge/lease response pair to restart offline grace. Leases are not sufficient as copied bearer credentials: relay authentication and artifact retrieval require fresh installation signatures. Server generation checks reject revoked leases even before expiry. Every protected relay request consults current authority; a 30-second alarm checks otherwise-idle licensed sockets, and developer commands push invalidation to each room. Temporary backend failures close online access with a retryable response rather than claiming revocation.

Default leases last five minutes; default offline grace lasts at most fifteen additional minutes. Bounds are server configuration, never a local license setting. The client correlates a signed lease with its fresh request and uses a monotonic timer. Offline grace exists only in the current process after successful online authorization; restarting requires the backend. IRC and other online operations stop during outages. Explicit denial disables protected operation immediately. Cached bytes, a edited local JSON file, changing the clock, or a patched UI boolean cannot grant backend access. This does not make client code uncrackable.

## Loader and release lifecycle

The permanent `skymyce-loader` Fabric mod contains the small authorization/bootstrap UI and Minecraft certificate accessor. The full payload declares a dependency on it. Fabric discovers the full JAR and its mixins on the next startup; no classes/mixins are injected into an already initialized game. First activation installs a verified payload for the next launch. Updates are downloaded and verified, then applied with Minecraft stopped, including on Windows where loaded JARs can be locked.

Public `/loader/release` returns signed metadata only. The loader trusts only compiled public keys, authenticates against the current release, and downloads the JAR from protected POST endpoints. A signed manifest authenticates the exact artifact SHA-256; changed manifests or bytes are rejected before installation. Older loaded versions cannot use a current-version lease to enable old protected functionality. Minecraft/Fabric versions remain centralized in `gradle.properties`; update the Worker release policy and signed manifest with any future Minecraft upgrade. No speculative 26.3 dependency versions were introduced.

## Additive database changes

The `v2-accounts` migration adds the SQLite `AccountStore` class. It does not delete/recreate `RelayRoom` or its existing tables. Global tables include `sky_accounts`, `minecraft_accounts`, `account_devices`, `installations`, `activation_keys`, `account_challenges`, `minecraft_links`, `account_audit`, `account_warnings`, `account_requests`, `account_limits`, and `account_migrations`. Unique constraints enforce Discord identity, UUID ownership, installation fingerprints and one active device per account. Sensitive state changes and their audit inserts are transactional.

Room tables add `irc_nonces`, `irc_seen`, `irc_limits`, and `account_migration_state`; they store handshake replay protection, bounded short-lived message IDs, quotas and a migration cursor, never chat bodies. `mod_users`, cosmetics/public revisions, stats, wealth, digest and RNG stay intact.

On first use, each strict room imports legacy `cosmetics_links` in batches of 250. Ownership conflicts are audited for operator review instead of silently transferring a UUID. Imports create account associations without granting licenses/devices. Per-room/UUID migration markers prevent later unlink from being undone by a repeat import; the old room records remain available for verification. Accounts with ambiguous legacy links need a developer to resolve ownership. Back up/inspect production before any deployment, and compare imported counts/conflicts before onboarding users. The original implementation made no production database changes; the subsequent explicitly approved cross-namespace cosmetics copy is recorded in the migration report.

Strict deployments route cosmetics link/ownership through the global account store. Appearance records stay in the existing room database. If an account has multiple UUIDs, existing cosmetic target selection is preserved while it still belongs to that account, otherwise its first linked UUID is used. `/cosmetics unlink` directs users to the unified account unlink operation. Legacy tests exercise the old protocol only with both `SKYMYCE_ENVIRONMENT=development` and `ALLOW_LEGACY_RELAY=true`; missing configuration never enables that mode. Production defaults reject clients lacking a signed installation-bound lease.

## Discord applications and IRC

See [Discord setup and command registration](../discord-bots/README.md). The user bot's Node process connects to Discord Gateway and keeps one authenticated relay WebSocket per configured room. The separate backend bot is a Discord application whose signed HTTP interactions execute in the Worker; it needs no second permanent Gateway process. Each application has its own application ID, signing public key and bot token.

Backend commands require the configured guild and an active account with persisted `sky_accounts.is_admin=1`. Discord roles, Administrator permission and the former developer allowlist grant no backend authority. The HTTP handler defers privately, checks AccountStore before target lookups or side effects, and AccountStore rechecks inside each administrative transaction. The old Backend Bot forwards the original Discord-signed request to that handler; both services verify the signature. No public admin JSON endpoint accepts a claimed actor.

`ADMIN_BOOTSTRAP_USER_ID` seeds the first administrator once, with a transactional singleton marker and audit record. Production's initial administrator is `978376257534328883`. Restarting or changing bootstrap configuration cannot regrant authority or add another administrator after the marker exists. An existing administrator uses Backend `/admin add user:@person` to grant another active account administrative access. Grants preserve licenses, installations, devices and activation state; repeat grants are idempotent, replayed interactions fail, and the target is validated as a real human Discord user. Account status reports `Role: admin` or `Role: user`. Existing administrative operations and normal-user ownership rules otherwise remain unchanged.

Discord-to-Minecraft IRC requires a licensed, linked, activated account. Minecraft-to-Discord IRC requires a current authenticated installation session. Both use the same account permission method, separate IRC quotas, validation, source tagging and deduplication. Messages are capped at 256 characters; control characters, DEL and Minecraft formatting codes are rejected. The bot ignores bot/webhook messages and posts with mentions disabled. Bridge handshakes sign room, timestamp and a single-use nonce; signatures cannot be replayed across rooms. Quotas/subscriptions survive hibernation. No normal IRC chat archive is created.

The backend bot DMs the activation secret and private loader attachment. A loader too large for Discord uses a scoped HMAC download ticket in the URL fragment, valid for fifteen minutes. That ticket is a shareable bearer credential for the bootstrap only; it never authorizes the protected payload or an account. Activation remains bound to the intended Discord user. No token/activation/private-key values are written to logs or audit metadata. Audit delivery to private Discord channels is best effort; transactional database audits are authoritative.

## Operator configuration

Main implementation files:

| Area | Files |
| --- | --- |
| Global accounts and licensing | `relay/src/accounts.ts`, `relay/src/licensing.ts` |
| Lease/artifact cryptography and HTTP routes | `relay/src/drm.ts`, `relay/scripts/release.mjs` |
| Relay integration, migration and IRC | `relay/src/index.ts`, `relay/src/irc.ts`, `relay/src/cosmetics.ts` |
| Signed Discord commands and loader delivery | `relay/src/discord-accounts.ts`, `relay/src/discord-download.ts` |
| Gateway bridge and command registration | `discord-bots/src/user-bot.mjs`, `relay.mjs`, `backend-bot.mjs`, `register.mjs` |
| Bootstrap, local identity and authorization | `src/main/kotlin/me/mycellium/skymyce/loader/`, `src/main/java/me/mycellium/skymyce/loader/` |
| Minecraft integration | `SkyMyce.kt`, `SkyMyceModule.kt`, `SkyMyceCommands.kt`, `DungeonFriendRelay.kt`, `RelayMessages.kt`, `IrcMode.kt`, module/HUD callback guards |
| Packaging and configuration | `build.gradle.kts`, `gradle.properties`, Fabric/mixin metadata, `relay/wrangler.jsonc`, ignored operator values and committed example files |
| Verification | `relay/test/accounts.mjs`, `irc.mjs`, `discord-bots.mjs`, existing relay suites, `discord-bots/test/bridge.test.mjs`, `src/test/kotlin/me/mycellium/skymyce/loader/LoaderCheck.kt` |

The original public Minecraft trust roots and authentication verification remain unchanged.

The supplied production guild, developer IDs/role, channels and frontend application public key are saved in ignored `relay/.dev.vars.production`. This is an operator reference, not a deployed configuration and not a staging credential file. The supplied OAuth client secret is unused and was not written to source or artifacts. Still required: separate backend application/public key, both bot tokens, staging bot/guild/channel settings, release signing keys, bridge secret, optional fallback download secret and a public HTTPS Worker URL.

Use `relay/.dev.vars.example` and `discord-bots/.env.example` as templates. Put staging Worker secrets in `.dev.vars.staging` for local tests; `.dev.vars.production` is not automatically applied to the default Wrangler environment. Do not copy production bot/channel credentials into staging. Configure actual nonsecret IDs through Wrangler environment variables or your deployment secret/configuration workflow; keep credentials in Worker secrets and the bot service's secret environment. Real tokens are not required to compile or run tests.

The following commands are operator actions, **not actions already performed**. Run from the indicated folder. Verify the target account and staging values before using remote commands.

```powershell
# Repository root: use a Java 25 JDK and an isolated directory outside this repo.
$env:JAVA_HOME = '<JDK_25_DIRECTORY>'
node relay/scripts/release.mjs keygen '<PRIVATE_RELEASE_DIRECTORY>' staging-1
$public = (Get-Content '<PRIVATE_RELEASE_DIRECTORY>/staging-1.public.json' -Raw | ConvertFrom-Json).'staging-1'
./gradlew.bat build "-PskyMyceBackend=https://<STAGING_WORKER_HOST>" '-PskyMyceSigningKeyId=staging-1' "-PskyMyceSigningPublicKey=$public"
# Full mod: build/libs/skymyce-2.4.3.jar; bootstrap: build/libs/Sky-Myce-Loader.jar.
node relay/scripts/release.mjs sign build/libs/skymyce-2.4.3.jar '<PRIVATE_RELEASE_DIRECTORY>/staging-1.private.pem' staging-1 26.1.2 2.4.3 1.0.0 '<RELEASE_DIRECTORY>/current.json'
```

On Windows explicitly restrict the private directory ACL to the release operator; POSIX mode `0600` alone does not set Windows ACLs. The signing tool refuses to create private keys inside this repository and never overwrites existing output files. Manifest validity is seven days; refresh/re-sign before expiry even if the artifact version stays the same. Store signing private material in an operator-controlled secret store. Do not put it in bot environments or JARs.

```powershell
# relay/: these remote commands create staging resources and deploy only when you run them.
npm ci
npm run check
npm test
npx wrangler r2 bucket create sky-myce-staging-private-artifacts
npx wrangler secret put USER_BOT_TOKEN --env staging
npx wrangler secret put DISCORD_BACKEND_BOT_TOKEN --env staging
npx wrangler secret put DISCORD_BRIDGE_SECRET --env staging
npx wrangler secret put DISCORD_LOADER_DOWNLOAD_SECRET --env staging
npx wrangler secret put DRM_SIGNING_PRIVATE_KEY --env staging
# DRM_SIGNING_PRIVATE_KEY is base64 PKCS8 DER, not the PEM text.
# Configure DRM_PUBLIC_KEYS (the public.json contents), DRM_KEY_ID and the other example variables too.
npx wrangler r2 object put sky-myce-staging-private-artifacts/loaders/Sky-Myce-Loader.jar --file ../build/libs/Sky-Myce-Loader.jar --remote
npx wrangler r2 object put sky-myce-staging-private-artifacts/releases/Sky-Myce-2.4.3.jar --file ../build/libs/skymyce-2.4.3.jar --remote
npx wrangler r2 object put sky-myce-staging-private-artifacts/releases/current.json --file '<RELEASE_DIRECTORY>/current.json' --remote
npx wrangler deploy --env staging
```

Never enable public R2 access. The release object name and manifest path must match `ARTIFACT_MANIFEST_KEY` and the signed manifest. After deployment, configure the user and backend application interaction URLs, enable only needed user-bot intents, install the applications with minimal permissions, and explicitly run the documented command registration script. Start the Gateway bot with staging configuration. Use a nonproduction Minecraft account and testing channel for the complete activation/alt/reset/revoke/outage/IRC lifecycle. Only after that review should the equivalent default-environment production commands be run with production keys and IDs. `wrangler secret put` itself can create/deploy a Worker version; it is not a read-only operation.

## Rotation and rollback

Rotate bot tokens and the bridge/download secrets in their server environments, then restart the Gateway process. No client rebuild is needed for those credentials. For signing-key rotation, first distribute a loader that trusts the new public key, keep the old public key in the backend verification map during the overlap, and only then switch `DRM_KEY_ID`/private signer and release manifests. Retire the old key after the maximum lease/grace window and loader migration. A single-key old bootstrap cannot discover a new trust root from the network; plan and deliver its update before switching. An emergency compromised signer requires revoking affected generations and distributing a trusted loader update.

Do not roll back to a permissive pre-DRM Worker against the new production service. Worker code rollback does not undo storage mutations. Preserve additive tables and audit history; use explicit audited recovery/reset operations. Test restoring a backup in a separate environment rather than deleting production DO storage.

## Verification and remaining live checks

Completed locally: Java 25 `gradlew.bat build --offline`, relay `npm run check` and `npm test`, Discord bot `npm test`, and staging `wrangler deploy --dry-run --env staging` all passed. The relay check regenerated binding types; both existing regression checks and new security checks passed. The dry run packaged the Worker without deploying it.

Local verification uses generated fake Discord/Minecraft/signing identities. It covers existing relay/cosmetics/party/private-message regressions, real cryptographic activation/lease/artifact checks, one-winner consumption, device/UUID conflicts, cooldown and admin permissions, IRC both directions/isolation/deduplication/hibernation/rate limits, revocation, loader signing/integrity/clock behavior and bot packet handling. Run `npm test` in both `relay` and `discord-bots`, and `gradlew build` with Java 25. A staging `wrangler deploy --dry-run --env staging` verifies packaging without deployment.

Persistent HWID verification (2026-10-02): the complete Java 25 build, relay type checks and regression suite, backend packaging dry run, and both Discord bot suites passed. `InstallationCheck` verifies retained identity, stable hardware sources, legacy migration and temporary source loss; `launch-sessions.mjs` verifies lifecycle authorization, lease expiry, reconnects, updates, explicit seven-day transfers, copied identities and new-key bypass attempts, retired-device denial, proof downgrade rejection and administrator/security audits. Actual Windows hardware reads, DPAPI and file permissions were exercised. Linux/macOS and reboot, shutdown, suspend, logout, network and software-update scenarios use deterministic simulations; actual OS reboots and Minecraft/Fabric launches remain manual checks. These changes have not been deployed or published as a signed loader release.

No authenticated real Discord DM, live Gateway session, two-client Minecraft/Fabric launch or production migration was run automatically. Those need configured staging services, generated release keys, uploaded signed artifacts and actual game accounts. Offline hardware identity cannot resist a fully controlled operating system. Client-side local features can ultimately be modified; server-protected artifacts, sessions and features remain governed by current server authorization.

## Platform references checked

- [Cloudflare hibernating WebSockets and attachments](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [SQLite Durable Object transactions](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)
- [Durable Object class migrations](https://developers.cloudflare.com/durable-objects/reference/migrations/)
- [Worker environments](https://developers.cloudflare.com/workers/wrangler/environments/) and [secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
- [Worker limits](https://developers.cloudflare.com/workers/platform/limits/)
- [Discord Gateway](https://docs.discord.com/developers/events/gateway), [interactions](https://docs.discord.com/developers/interactions/receiving-and-responding), and [application commands](https://docs.discord.com/developers/interactions/application-commands)
- [Fabric Loader](https://wiki.fabricmc.net/documentation:fabric_loader) and [Fabric development setup](https://docs.fabricmc.net/develop/getting-started/setting-up-a-development-environment)
