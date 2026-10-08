# Mod 2.4.16 routing response and surface fixes — deployed October 8, 2026

Production `sky-myce-backend` (`--env backend`) selects mod 2.4.16 on Worker
version `b18c9415-b8e5-4007-8a4c-f79b05006ad1`, deployed at 10:45:31 UTC.
Supported loaders discover the release on the next Minecraft launch.

The room scan now prioritizes observed targets and publishes immutable checked
geometry while the rest of the room is scanned. Mob observations refresh every
100 ms, and irrelevant or repeated collision-equivalent block updates do not
restart the scan. Native collision surfaces supply actual carpet, slab and stair
heights; validated diagonals and physical movement costs avoid artificial
integer-height detours. Filled waypoints, thin beams and compact distance labels
replace the oversized text. An uncleared room with no registered targets keeps
the exit waypoint without showing an exit-only clear route.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.16 | `releases/Sky-Myce-2.4.16.jar` | `releases/manifest-2.4.16.json` | `301b0d330a2fefb088d1a04409a675c5024beb341303e2a78af0f1b4eb717f27` |

The mod manifest expires **October 15, 2026, at 10:43:50 UTC**. The published
loader remains 1.0.14 with its existing October 15, 07:39:02 UTC manifest expiry.
Minecraft 26.1.2, minimum loader 1.0.11 and the signing trust root are preserved.

Java 25 `check build`, `npm test`, `npm run check` and the backend packaging dry
run passed. Routing checks cover surface heights, safe diagonal movement,
physical costs, progressive immutable snapshots, target-prioritized scans,
geometry-update filtering and bounded waypoint text scale. Both private R2
readbacks match the checked files exactly. Live mod and loader manifests have
valid signatures and matching artifact hashes; health and anonymous access
denial checks passed.

Post-deployment bindings differ only in `SKY_MYCE_VERSION` and
`ARTIFACT_MANIFEST_KEY`. Runtime settings, other bindings, secret names/types
and empty schedules are preserved. Evidence in the external directory below
uses `release-2.4.16-*` filenames. No live Minecraft dungeon run was performed;
route timing and appearance still need gameplay verification.

# Mod 2.4.15 routing repair — deployed October 8, 2026

Production `sky-myce-backend` (`--env backend`) now selects mod 2.4.15 on
Worker version `d18da871-f552-449c-b7c5-4f9545135d29`, deployed at 09:46:49 UTC.
The existing published loader 1.0.14 remains selected. Supported loaders
discover the new mod on the next Minecraft launch.

The previous routing feature required a room catalog that was not shipped.
The repair adds automatic dungeon-map room detection, observed starred mobs,
incremental native collision geometry and a safe Blood/Wither-door approach.
Target probes share a bounded client-tick budget and reuse immutable geometry.
Whole-room completion requires the server's map checkmark; unobserved spawn
totals are not claimed. Optional measured room catalogs remain supported.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.15 | `releases/Sky-Myce-2.4.15.jar` | `releases/manifest-2.4.15.json` | `84128054b1b65bc2981ef837354d05e855f9b81578eaf0510f2804e54ff3960f` |

The mod manifest expires **October 15, 2026, at 09:45:19 UTC**. The retained
loader manifest still expires at 07:39:02 UTC that day. Minecraft 26.1.2,
minimum loader 1.0.11 and the existing signing trust root are preserved.

Java 25 `check build`, relay type generation/typecheck and the backend packaging
dry run passed. Routing checks include no-catalog discovery, map topology,
joined-room geometry, wall detours, closed Blood/Wither gates, observed target
states and resumable probe budgets. Private R2 readbacks match both release
files exactly. Live manifests have valid signatures and matching artifact
hashes; health and anonymous access-denial checks passed.

Post-deployment settings differ only in `SKY_MYCE_VERSION` and
`ARTIFACT_MANIFEST_KEY`, plus the deployment message. Secret names/types,
storage namespace IDs, runtime settings and other bindings are preserved;
schedules remain empty. Old artifacts are retained. Evidence under the same
external directory below uses the `release-2.4.15-*` filenames. No live
Minecraft dungeon run was performed, so gameplay still needs verification.

# Mod 2.4.14 / loader 1.0.14 — deployed October 8, 2026

The latest mod and loader are published with signed manifests in the existing
private `sky-myce-private-artifacts` R2 bucket. Production account Worker
`sky-myce-backend` (`--env backend`) selects both releases on version
`5630f7ff-d2ef-410b-9706-e9c29a26218d`, deployed at 07:41:13 UTC.
This release includes the menu, routing, chat, motion and dropdown fixes, plus
the loader hardware-identity normalization and persistent-home verification.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.14 | `releases/Sky-Myce-2.4.14.jar` | `releases/manifest-2.4.14.json` | `e5a5ee5b89a725a9953afb66fecf9862842af1b246eb003e457e20a6077e2863` |
| Loader 1.0.14 | `loaders/Sky-Myce-Loader-1.0.14.jar` | `loaders/manifest-1.0.14.json` | `66ee5c0c3e0892b3f11e423bc3520b1cf74afa6a0dcb208389100ab6af6c19b5` |

Minecraft 26.1.2, minimum loader 1.0.11 and the `production-20260927` signing
trust root are unchanged. Old release objects are retained. The new manifests
expire on **October 15, 2026, at 07:39:02 UTC** (09:39:02 Stockholm); re-sign
the matching artifacts before then. No renewal automation was installed.

Java 25 `check build`, relay type generation/typecheck and the production
backend packaging dry run passed. The full relay and User Bot suites passed
before this version-only release preparation. All four private R2 readbacks
exactly match the local release files. Live `/loader/release` and
`/loader/bootstrap-release` return the expected versions with valid Ed25519
signatures, matching artifact hashes and `Cache-Control: no-store`.
Health returns 200; anonymous artifact, manifest, bootstrap-renewal and Discord
loader requests remain denied, and both unsigned interaction routes return 401.
Post-deployment settings differ only in the four release selections; runtime
settings, all other variables, secret names/types, storage namespace IDs and
other bindings are preserved. Schedules remain empty.

Evidence is stored outside the repository under
`C:\Users\krister\.codex\visualizations\2026\10\07\01a114f9-ddd3-7bf1-8cef-6ccbf3d11de7`
as `release-2.4.14-before.json`, `release-2.4.14-r2-verified.json`,
`release-2.4.14-after.json` and `release-2.4.14-live-verified.json`.
No account/device reset, real Discord message or live Minecraft launch was
performed. The separate Discord Gateway service was not deployed.

Existing supported loaders discover the mod release; applying it requires a
Minecraft restart. Loader updates default to off: enable **Automatic loader
updates** and relaunch, or install the current loader, to receive the loader's
HWID fixes. Loader installation completes after Minecraft stops.

# Admin interaction forwarding repair — deployed October 2, 2026

The legacy `skyblock-relay` Admin interaction endpoint previously forwarded slash
commands but dropped the new `sky:confirm:*` and `sky:cancel:*` button interactions.
Its fallback produced **Unsupported command** before the account backend received
the click. The same filter dropped account-view buttons, autocomplete, and the
new `build` and `skyhawk` command roots.

The repaired forwarder retains exact Discord-signed bodies and headers, the
existing account Worker service binding, guild checks, and backend authorization.
It forwards bounded Sky-Myce buttons and modern command roots, accepts bounded
native autocomplete responses, and permits only authenticated DM self-service
Status/Get key/Loader/Help controls. Administrator controls remain guild-only.
The account backend still owns confirmation permissions, expiry and replay checks.

The separate legacy checkout contains unrelated work, so task-only source and
regression-test changes are recorded in
[`legacy-admin-ux.patch`](../relay/patches/legacy-admin-ux.patch).
They are already applied to `D:\Code\Cessna fork\relay`.
[`legacy-admin-ux.production.patch`](../relay/patches/legacy-admin-ux.production.patch)
repairs the freshly downloaded production bundle while preserving every byte
outside its forwarding module. It targets existing Worker version
`75e32bf6-21b5-4ca1-afeb-6490ea182ea7`, bundle SHA-256
`2cd8b7eb2b28fc656723f861389c50bb80b1122982cbcbebaa9b180de50c9bd5`.
Both patches use zero context; validate them with `git apply --check --unidiff-zero`
against their matching baseline. Git attributes retain patch line endings.

Legacy `npm run check` and full `npm test` passed, including 13 Node tests.
A two-Worker runtime check reproduces the original failure and verifies the
repaired production bundle with synthetic Ed25519-signed interactions: Confirm,
Cancel, account views, autocomplete and DM self-service reach the backend;
signature tampering, wrong applications, wrong guilds and backend authority
denials remain blocked. The production dry run and patch application checks pass.
No real Discord messages or account/device changes were issued during testing.
Prepared deployment config, bundles and runtime evidence are under
`build/admin-ux-before` and `build/admin-ux-prepared`.

The user approved production activation. `skyblock-relay` is deployed on version
`a115fa6d-966d-4815-bbe4-f41c69a060e7`, with bundle SHA-256
`94305c1d6075caddb6c7cb30c692023406aeca732a3d81cef9644f32dddf8598`.
The downloaded live bundle matches the exact tested replacement. Post-deployment
checks confirm all existing bindings, secret names/types, runtime settings,
routes, schedules and migration tag `v1` are preserved. Both Workers return
health 200 and reject unsigned interactions with 401. Mod 2.4.13, loader 1.0.13,
the account Worker version and both release manifests remain unchanged.
Live deployment evidence is under `build/admin-ux-after` and
`build/admin-ux-live-endpoints.json`.

A real Discord button click still needs user verification. Run a fresh command
to obtain new Confirm/Cancel buttons; previously displayed confirmations expire
after two minutes and cannot be reused.

# Mod 2.4.13 / loader 1.0.13 — deployed October 2, 2026

Both runtime JARs and their signed manifests are published in the existing private
`sky-myce-private-artifacts` R2 bucket. The account Worker
`sky-myce-backend` (`--env backend`) selects both releases together, on version
`b1410753-f299-4902-ad2e-4e9601ee84c0`. The current verified backend source includes
the global-chat handlers; the separate Discord Gateway service remains pending.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.13 | `releases/Sky-Myce-2.4.13.jar` | `releases/manifest-2.4.13.json` | `ea3912d88293fec65f221adf419b52a46b22d1677cd732d318699a3f646f2ce1` |
| Loader 1.0.13 | `loaders/Sky-Myce-Loader-1.0.13.jar` | `loaders/manifest-1.0.13.json` | `9e425c4dcf7e9fe54f9ada78d84d0679a0fabb9011a5e4960d0152d2f3f162c4` |

Minimum loader remains 1.0.11, Minecraft remains 26.1.2, and the existing
`production-20260927` signer and client trust root are unchanged. Previous release
objects are retained. Both manifests expire on **October 9, 2026, at 14:05:26 UTC**
(16:05:26 Stockholm); renew them before that deadline.

Java 25 `check build`, relay type generation/typecheck and the backend dry run
passed. All four private R2 readbacks match the exact local signed release bytes.
Both live manifests have valid signatures, matching versions and artifact hashes.
Live health returns 200; unauthenticated downloads and renewals remain blocked,
and unsigned Discord interactions return 401. The post-deployment snapshot
confirms preserved runtime settings, routing, existing bindings, storage namespace
IDs and secret names/types. No account/device reset or Discord message was issued.
Evidence is generated under `build/release-2.4.13-*.json`. This publication does
not establish a new live Minecraft launch or a running Discord Gateway service.

# Loader 1.0.12 protocol repair — deployed October 2, 2026

The previous account Worker rejected the current hardware-bound v3 installation proof
as `installation_proof_invalid`. An anonymous compatibility check with fresh keys
confirmed that the same endpoint accepts the older v2 transcript and proceeds to
`minecraft_proof_required`. No Minecraft credentials or account proofs were used.

The rollout targets only `sky-myce-backend` (`--env backend`), deploys
the repository's v3 verifier, and selects private
`loaders/Sky-Myce-Loader-1.0.12.jar` with `loaders/manifest-1.0.12.json`.
Minimum loader becomes 1.0.11. The signed protected mod remains the exact existing
2.4.12 bytes; its re-signed metadata at
`releases/manifest-2.4.12-loader-1.0.11.json` raises the matching minimum loader.
Installation proofs, lease verification and renewals are owned by the separate loader JAR.
Keep the existing signer, secret bindings, storage namespaces and account/device
records. Upload and verify the new loader first, then both signed manifests, before
deploying the configured Worker. The user approved publication and deployment.
Account Worker version: `b88b559f-b9ab-48bb-a613-2323075d72ea`.

After deployment, repeat the anonymous v3 check: it must pass installation proof
verification and stop at `minecraft_proof_required`. Verify the live loader
manifest against the pinned `production-20260927` public key and its artifact
hash, then verify startup and later renewal in the user's existing instance.
An unsupported loader, missing account proof or unauthenticated artifact request
must still be rejected. A new activation code or HWID reset cannot fix a protocol
mismatch.

Verified after deployment: both live manifests have valid signatures under the
existing pinned public key; private R2 readbacks match the prepared files. Loader
1.0.12 SHA-256: `98960fd754817e4504b88eb470fbacdee1b6ef9961562f1f221e96a4963cdea8`.
Protected mod 2.4.12 retains SHA-256
`85a5df8de2f4e5ccf3b10f24a0ef72f793059a52040e09a7a976c11754ea189d`.
The live v3 probe now reaches `minecraft_proof_required`; a v2 transcript for the
same hardware-bound input is rejected. Unauthenticated artifact requests remain
blocked. Full Java 25 `check build`, relay tests, typecheck and the backend dry run
passed. Loader 1.0.12 is installed in the user's existing Prism instance with a
backup. The verification launch passed startup, automatically installed the exact
signed production mod, and reached Minecraft's renderer. A read-only JVM check
confirmed startup was not skipped, local and online access were allowed, no restart
was pending, and an in-game renewal was accepted after the initial startup renewal
deadline. The diagnostic recorded only booleans, without credentials or identities.

# Loader 1.0.1 / mod 2.4.4 deployment — September 27, 2026

This section supersedes the older release status recorded below.

- Account Worker: sky-myce-backend, version 18b73f7f-9d59-4f0e-b7c7-4de4a0f5c305.
- Legacy Backend Bot Worker: skyblock-relay, version fbf5f962-1a94-4eea-839b-9146d7e5ff1e. Its only new forwarding root is authenticate.
- New guild command /authenticate user uses persisted admin authorization and the existing private delivery; /authorise is retained. Existing eight registrations unchanged; no bulk overwrite.
- Loader 1.0.1: private loaders/Sky-Myce-Loader-1.0.1.jar, SHA-256 6eddcf7490493988a90cf3a729ef75ab8138ed9e261217963b38696d8075daa1. SKYMYCE_LOADER_R2_KEY selects this object; old loader object retained.
- Mod 2.4.4: private releases/Sky-Myce-2.4.4.jar, SHA-256 6b613af3b02f7a80df3a4772676721b4b7adafd014c6540cf5d0b12b4a884fb6.
- ARTIFACT_MANIFEST_KEY selects releases/manifest-2.4.4.json. Signed manifest expiry: 2026-10-04T16:28:29.798Z. Minimum loader remains1.0.0 for compatibility; the new interface/restart needs loader1.0.1. Previous artifacts/manifests retained.

The requested flow is admin /authenticate → private code+loader → in-game code/Confirm → Restart. No extra Discord approval command is needed on loader1.0.1. DM code redemption still verifies the installation and Mojang proofs and consumes the key atomically with device/UUID binding. Older loaders retain their explicit approval path.

Build and validation: full production Java25 build; cosmetic cache bounds/expiry/reset checks; signature/tamper/downgrade/rollback/duplicate tests; real child-process exit/apply check; exact Prism command/profile/layout checks; account-switch cancellation guard; full relay/account/DRM/IRC/cosmetics suite;12 Discord tests;8 shared bot tests;6 legacy admin tests; both typechecks/dry runs. R2 readback hashes and signed live manifest verified; health200, unsigned interactions401, unauthenticated artifact403. Evidence: build/loader-*.log and build/loader-2.4.4-live-verified.json.

Still requires real-user verification: Minecraft screen rendering, activation from a live Mojang session, automatic Prism reopening, and F5 stability over multiple lease renewals. No production activation keys/accounts were generated for testing. The Gateway/IRC hosting remains deferred.

## Earlier deployment records

# Backend deployment — September 27, 2026

The account backend is deployed at **https://sky-myce-backend.skyblock-relay.workers.dev** using the existing architecture in `D:\Code\Sky-Myce\docs\secure-system.md`. Private R2 artifact delivery is enabled. The existing Backend Bot/legacy relay remains at `https://skyblock-relay.skyblock-relay.workers.dev`; its original storage is preserved. Cosmetics moved to MouseBot Frontend with saved data copied and verified; see the [migration report](cosmetics-migration.md).

## Cross-lobby cosmetics 2.4.3 — deployed

The 2.4.3 follow-up removes the local tab-list dependency for cosmetic names in chat and menus. The client gathers known friend/party identities and queues observed player names; the relay resolves bounded name requests using its authenticated Minecraft identity registry. Missing or ambiguous names remain unresolved, and shared stats uploads cannot supply identity mappings. Existing UUID-only clients keep their response format. A name-only lookup also subscribes to later appearance updates. The client also accepts `cosmetics_result` through its existing 64 KiB framed-message limit, allowing batches of styled names without raising that cap.

The backend implementation passed `npm run check` and the full `npm test` suite, including name resolution, live updates, malformed selectors, upload spoof rejection, authorization/revocation and room isolation. The full Java 25 production build also passed, including HUD/loader checks, cross-lobby cosmetics and large styled-packet handling. Logs: `build/cosmetics-cross-lobby-check.log`, `build/cosmetics-cross-lobby-relay-tests.log` and `build/cosmetics-cross-lobby-build.log`. Production deployment, R2 readback and the live signed release were verified; **real Minecraft chat/menu rendering is still unverified**.

The deployed backend selects `SKY_MYCE_VERSION=2.4.3` and `ARTIFACT_MANIFEST_KEY=releases/manifest-2.4.3.json`. `releases/Sky-Myce-2.4.3.jar` and that signed manifest were uploaded and downloaded for verification before deploying the Worker, so its version and manifest selection changed together. The existing 2.4.2/current-manifest objects are retained; loader 1.0.0's original R2 artifact is unchanged. Only the account Worker was redeployed. This follow-up changes no Discord registrations, Portal settings, persisted administrator grants, cosmetics ownership or deferred IRC hosting. Live evidence: `build/cosmetics-2.4.3-live-verified.json`.

## Deployed resources and routing

| Resource | Verified state |
| --- | --- |
| Account Worker | `sky-myce-backend`, Wrangler environment `backend`, version `9a66b1c6-a682-4e12-bd1e-2fac2e430715` |
| AccountStore namespace | `c11e1863d6f94cd7b9d71b94a3a2baa5`; persistent SQLite, instance `global` |
| Strict RelayRoom namespace | `7896eebb41cd436f8a15bd95aa5ffa3b`; configured rooms `friends,testing` |
| Original Backend Worker | `skyblock-relay`, version `7ece67bc-8b3b-432c-9ac4-1cab65d244c0`; forwards verified administrative interactions through the `SKY_MYCE_BACKEND` service binding |
| Original RelayRoom namespace | `f6cf92b726e64c42a65ee466d61bfc58`; retained |
| Backend interaction URL | Existing `https://skyblock-relay.skyblock-relay.workers.dev/discord/interactions` retained |
| User interaction URL | Set to `https://sky-myce-backend.skyblock-relay.workers.dev/discord/interactions`; Discord accepted its signed PING validation |
| R2 bucket | Created `sky-myce-private-artifacts`; `PRIVATE_ARTIFACTS` binding; managed public access disabled, no custom domains |
| Server roles/channels/permissions | No further changes during backend deployment; see the Discord implementation report |
| Command registrations | Backend eight roots including `/admin add`, User three; seven existing Backend roots and all User commands preserved; no bulk overwrite |

`/authorise user:@person` reaches the real account store and DMs a one-time activation key, the private loader and installation instructions. After the runtime fixes, the user confirmed that **the key and loader arrived** on a real Discord retry. The key expires after 24 hours; issuance leaves the account pending activation. Blocked DMs return a private delivery failure. An explicit `/authorise user:@person reissue:true` replaces outstanding keys. Deployment probes did not create accounts or send DMs; the confirmed delivery followed the user's own command.

The User account/status/link/approval/HWID and cosmetic handlers run over signed HTTP, independently of Gateway. Cosmetic retrieval was confirmed live by the user after data migration. Both authenticated IRC room handshakes passed bounded probes. **No persistent User Gateway runs**; the user deferred hosting, so Discord IRC forwarding remains unavailable. The legacy service has no Discord IRC listener to retire. Temporary migration access was removed and its absence verified in both live Worker bundles.

## Persisted backend administrators

Backend commands now require the authenticated caller to belong to the configured guild and have an active Sky-Myce account with persisted `is_admin=1`. Developer roles, the former allowlist and Discord Administrator permissions grant no backend authority. The account store checks authority before an operation and again inside its mutation transaction. Frontend commands cannot grant admin status.

The first administrator is **`978376257534328883`**. An additive database migration stores the flag and a singleton bootstrap marker. `ADMIN_BOOTSTRAP_USER_ID` seeds this grant once; later restarts or configuration changes cannot regrant it. Existing license, activation, session and device state is preserved. The production `admin_bootstrapped` audit event was observed at Unix millisecond `1790492141520` on account Worker version `7454f871-9307-420f-ab8c-1ce40e57b224`.

Existing admins can run **`/admin add user:@person`** with Mousebot. It validates a human Discord target, persists an audited grant, and replies privately; repeated grants are idempotent. The new guild command ID is `1553661667454226524`. The user confirmed a real `/admin add user:@self` returned that they were already an admin. `/account lookup` also displays the account role. Adding an admin does not issue a license or activation key.

Discord command visibility remains separate: `default_member_permissions=0` and integration overrides are defense in depth. The initial admin's visibility was checked. For a new admin who cannot see commands, use Server Settings → Integrations → Mousebot → Manage to allow that user; ordinary Discord administrators still fail the backend check unless granted Sky-Myce admin status.

## Artifacts

Built with Java 25 after configuring the deployed backend and public trust key. The 2.4.3 JAR and signed manifest were downloaded from R2 and verified against the uploaded release; the existing loader artifact was retained.

| Private object | Version / verification |
| --- | --- |
| `loaders/Sky-Myce-Loader.jar` | Loader 1.0.0, Minecraft 26.1.2; SHA-256 `b06d7f3a1222b1ff79cbb8e8815fa5989eeed328cdf3a9aad1a3b5f426821b23` |
| `releases/Sky-Myce-2.4.3.jar` | Current mod 2.4.3; SHA-256 `1109b8f391b5df754a9aed0513b970c7459a5730090335c0928d5f495fb2c18c` |
| `releases/manifest-2.4.3.json` | Current manifest; Ed25519 signature, version and artifact hash verified through the live endpoint; public key ID `production-20260927` |
| `releases/Sky-Myce-2.4.2.jar`, `releases/current.json` | Preceding release retained, no longer selected by the account Worker; 2.4.2 JAR SHA-256 `52adf0c7000d0a1054426b0dbc1913519ff9610b2c916bfd7dcc0c071f0114e7` |

The full mod now defaults to the strict backend relay. An exact saved legacy default migrates on load; custom endpoints and a deliberately blank/disabled relay are preserved. Existing legacy clients and their endpoint were not modified.

**Renew the signed manifest before October 4, 2026, 07:17:39.772 UTC (09:17:39.772 Stockholm).** Its exact expiry is `2026-10-04T07:17:39.772Z`. The existing architecture gives release manifests a seven-day lifetime, including unchanged releases. Expired manifests fail closed. No renewal automation was installed.

## Configuration and secret storage

Worker configuration names: `ROOMS`, `SKYMYCE_ENVIRONMENT`, `MINECRAFT_VERSION`, `SKY_MYCE_VERSION`, `MINIMUM_LOADER_VERSION`, `ARTIFACT_MANIFEST_KEY`, `DISCORD_GUILD_ID`, `ADMIN_BOOTSTRAP_USER_ID`, `BACKEND_BOT_APPLICATION_ID`, `BACKEND_BOT_PUBLIC_KEY`, `USER_BOT_APPLICATION_ID`, `USER_BOT_PUBLIC_KEY`, `SKYMYCE_PUBLIC_URL`, `DRM_KEY_ID`, `DRM_PUBLIC_KEYS`, `DISCORD_AUDIT_CHANNEL_ID`, `DISCORD_SECURITY_CHANNEL_ID`, `DISCORD_LICENSE_CHANNEL_ID`, `DISCORD_IRC_CHANNEL_ID`, `DISCORD_IRC_TEST_CHANNEL_ID`, `DISCORD_LOADER_DELIVERY_ENABLED`.

Worker secret names: `BACKEND_BOT_TOKEN`, `USER_BOT_TOKEN`, `DRM_SIGNING_PRIVATE_KEY`, `DISCORD_BRIDGE_SECRET`, `DISCORD_LOADER_DOWNLOAD_SECRET`. Verified as Cloudflare `secret_text` bindings, not plain-text variables. Existing bot tokens were reused, not rotated. Signing, bridge and download secrets were generated for the new backend.

Operator files are outside the repository at `C:\Users\krister\AppData\Local\Sky-Myce\secrets`: `backend.json`, `production-20260927.private.pem`, and its public JSON. The directory ACL has inheritance disabled and permits only the current operator and SYSTEM. Back up the signer in the developer's protected secret-storage system. Keep it out of bot runtime environments and source control. The User Gateway environment receives only its own token and the separate bridge secret.

The original Backend Worker also has `SKY_MYCE_BACKEND_URL` and `COSMETICS_FRONTEND_OWNED`. The User Gateway uses `USER_BOT_TOKEN`, `USER_BOT_APPLICATION_ID`, `DISCORD_GUILD_ID`, `IRC_ROOMS`, `DISCORD_IRC_CHANNEL_ID`, `DISCORD_IRC_TEST_CHANNEL_ID`, `SKY_MYCE_RELAY_URL`, `SKY_MYCE_RELAY_AUTH_SECRET`. OAuth identity linking is not used by this architecture.

## Subsequent deployments and manifest renewal

Always select **`--env backend`** from `D:\Code\Sky-Myce\relay`. The main repository's unnamed default still names the legacy Worker; deploying that default would replace it. Keep legacy Backend changes in `D:\Code\Cessna fork\relay`.

```powershell
# D:\Code\Sky-Myce\relay
npm run check
npm test
npx wrangler deploy --env backend --secrets-file "$env:LOCALAPPDATA\Sky-Myce\secrets\backend.json" --strict
```

For manifest renewal, retain the existing signer and verified matching artifact. Use a new output filename because the signing tool refuses to overwrite files. The deployed 2.4.3 configuration uses `ARTIFACT_MANIFEST_KEY=releases/manifest-2.4.3.json`; updating the retained `releases/current.json` would not renew the live release:

```powershell
# D:\Code\Sky-Myce; use the exact currently published JAR.
$manifestOutput = "build\release-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()).json"
node relay/scripts/release.mjs sign build/libs/skymyce-2.4.3.jar "$env:LOCALAPPDATA\Sky-Myce\secrets\production-20260927.private.pem" production-20260927 26.1.2 2.4.3 1.0.0 $manifestOutput
# Compare its signed artifact hash to the published object before uploading.
# From relay/: publish the verified manifest only after its corresponding JAR is present.
npx wrangler r2 object put sky-myce-private-artifacts/releases/manifest-2.4.3.json --file "<VERIFIED_MANIFEST_PATH>" --content-type application/json --cache-control no-store --remote
```

Do not generate a replacement trust key as routine renewal. A changed signer requires a deliberate loader trust migration. For new builds, use the existing Gradle deployment properties and release procedure in `secure-system.md`; upload artifact first, manifest last, and verify the live signature/hash before distributing.

## Actual validation

The 2.4.3 rollout passed the full Java 25 production build and complete relay suite described above. Live health returned HTTP 200, unsigned interactions returned HTTP 401 on both application paths, and the full artifact rejected unauthenticated access with HTTP 403. The public manifest returned 2.4.3 with a valid signature and the exact R2-verified artifact hash. Evidence: `build/cosmetics-2.4.3-live-verified.json`. The legacy Backend Worker was not redeployed. These checks do not substitute for testing chat/menu appearance in a real Minecraft client.

The persisted-admin rollout passed the full relay/account/DRM/bootstrap/cosmetics/IRC regression suite, both Worker typechecks and deployment dry runs, **10/10** account Worker Discord tests, **7/7** legacy administrative/cosmetics-retirement tests, and **13/13** User Bot/shared command tests. Coverage includes role/allowlist/Administrator rejection, wrong-guild rejection, frontend escalation attempts, concurrent/replayed grants, suspended admins, bootstrap restart/reconfiguration and unchanged licensing/device state. Production readback confirmed eight Backend guild commands, three unchanged User commands, no global commands, both health endpoints HTTP 200 and unsigned interactions HTTP 401. The actual Discord self-grant check was confirmed by the user. Evidence: `build/persisted-admin-relay-tests.log`, `build/backend-admin-registration.json`, `build/backend-admin-live.json`.

The earlier deployment and runtime-fix evidence below predates the persisted-admin rollout; current authorization and versions are described above.

Follow-up to the reported `/authorise` service-unavailable response found two runtime issues. A remote Worker probe reproduced Cloudflare error **1042** (HTTP 404) when using public `fetch` to the account Worker's `workers.dev` URL. It also reproduced a `TypeError` before forwarding because Workers supports only `manual`/`follow` redirect modes, whereas the forwarding and account Discord API calls used `error`. Both now use `manual` and reject non-success responses, preserving redirect refusal. The Backend Bot uses a service binding, preserves original signed bytes/headers and keeps both authorization/signature checks. Safe failure categories/statuses are logged without request content. The corrected probe reached the account backend and rejected unsigned interactions with HTTP 401. Legacy storage and secrets were preserved. [Cloudflare error reference](https://developers.cloudflare.com/workers/observability/errors/), [HTTP service bindings](https://developers.cloudflare.com/workers/runtime-apis/bindings/service-bindings/http/).

The original Backend typecheck and full regression suite passed after adding the binding; the final redirect change passed the typecheck, dry run and **6/6** administrative tests. The added test executes a signed interaction through two real local Workers, verifying the signature at both ends. The account Worker typecheck and **9/9** Discord tests passed with Workers-compatible redirect handling asserted. Remote probes were stopped afterward; they sent no chat and created no account. Evidence: `build/backend-forward-*` and `build/backend-discord-redirect-*`. A fresh real Discord `/authorise` retry was requested to verify delivery through the corrected path.

- Java 25 `gradlew.bat build --offline` passed, including loader signing/binding/tamper/grace, client/HUD and relay URL migration checks.
- Backend `npm run check` and full `npm test` passed: relay/digest/social/cosmetics/account/DRM/IRC checks, **9/9** Discord handler tests, release signing self-check. Delivery tests cover the attachment/instructions/pending response and the earlier explicitly disabled-loader mode.
- Both Worker health endpoints returned 200. Signed release endpoint returned 200 with valid signature and matching artifact hash.
- Missing authorization on protected full artifact and loader downloads returned 403; unsigned interactions returned 401 on both new application endpoints and the retained legacy endpoint.
- A short-lived, operator-generated bootstrap-only ticket downloaded the actual loader through the live Worker; its SHA-256 matched. It did not create an account or activation key.
- HMAC-authenticated testing bridge returned protocol 3 with room `testing`; connection closed afterward, no chat sent.
- Live application endpoints and command inventories were reread. Bucket privacy and secret binding types were verified through Cloudflare API.

Evidence is ignored under `build/backend-*`: build/test/deployment logs, uploaded/downloaded artifact verification, endpoint and live smoke results. Earlier failed probes are not counted as passing checks; the final successful run is `backend-r2-live-verified.json`.

Confirmed live by the user after the final fix: `/authorise` delivered the activation key and loader by DM. Still unverified live: blocked-DM recovery, Minecraft enrollment/redemption, real self-service resets, two-player IRC and notification behavior. Unit tests cover these rules; they do not substitute for a real client test. No production account/data was fabricated or reset. Server command-visibility overrides and owner-only privileged-intent review remain the manual Portal items in the Discord report. Configure one supervised User Gateway process and test IRC before enabling production forwarding.

HWID reset key delivery: successful admin `/hwid reset user:…` and user self-service
resets now issue a fresh 24-hour, single-use activation key in the same transaction
as the reset, invalidating earlier unused keys. Only a hash is stored. The invoking
bot DMs the target with the code and Loader instructions; replies and staff audit
messages never contain the code. License restrictions and reset cooldown rules are
preserved. If delivery fails, the reset remains completed and the private reply
instructs the user to enable DMs and ask an admin for `/authorise reissue:true`,
rather than repeating a self-service reset. Regression tests cover delivery,
blocked DMs, target identity, key redemption/replacement and reset concurrency.
Evidence: `build/hwid-reset-dm-*.log`. No production reset or test DM was sent.

Reset recovery on the same installation: renewal after an allowed HWID reset now
returns `installation_required`, which existing loaders recognize as a request
to show the DM-code field. Renewal still cannot grant access. A valid fresh key
can restore the matching revoked installation only while the account has no
registered device and enrollment is not blocked. Identity mismatches, license
restrictions, used keys and explicit device revocation remain rejected. This
fix needs no loader update. Same-installation recovery, key replay, renewal after
recovery and moving to a different installation are covered by the account tests.
Evidence: `build/hwid-reset-reenroll-*.log`.
