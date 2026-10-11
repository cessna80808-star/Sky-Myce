# Mod 2.4.18 mob detection and Fairy/Blood routing — deployed October 8, 2026

> Latest release: **2.4.28**, deployed October 10, 2026. See the deployment record below.

Production `sky-myce-backend` (`--env backend`) selects mod 2.4.18 on Worker
version `5334c19b-d325-4b32-8b03-5a24b34ae160`, deployed at 12:06:42 UTC.
Supported loaders discover the release on the next Minecraft launch.

Starred health labels resolve to actual mob entities using the NoammAddons
StarMobESP ID offsets and spatial fallback, verified against installed 1.2.9.
Player-shaped miniboss profiles are included, and unresolved labels are retried
without waiting for nametag attachment. Fairy passage colors are recognized;
loaded gate blocks override stale map colors when its entrance opens. Final
Blood gates are eligible endpoints, and Blood-open suppression is retained.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.18 | `releases/Sky-Myce-2.4.18.jar` | `releases/manifest-2.4.18.json` | `e5b5ae1702dccfeea874f3de22c6b1d9fdb98b49b1ba4cad90e583c0b476ae71` |

The mod manifest expires **October 15, 2026, at 12:04:51 UTC**. Loader 1.0.14,
Minecraft 26.1.2, minimum loader 1.0.11 and the signing trust root are retained.

Java 25 `check build`, `npm test`, `npm run check` and the backend packaging
dry run passed. Regressions cover mob ID/spatial association, miniboss/player
classification, metadata-before-body retry, pink Fairy passages, a collision
detour around its tree, stale gate colors and final Blood gates in all four
directions. Private R2 readbacks match the checked build and signed manifest
exactly. Live mod/loader signatures, hashes, health and anonymous access checks
passed. The deployment is at 100%; only the two mod release pointers changed.
Runtime settings, other bindings, secret names/types and empty schedules are
preserved. External evidence uses `release-2.4.18-*` filenames. Live dungeon
timing, miniboss detection and Fairy traversal still need gameplay verification.

# Mod 2.4.17 Fairy routing and live updates — deployed October 8, 2026

Production `sky-myce-backend` (`--env backend`) selects mod 2.4.17 on Worker
version `cf4b44ea-f622-475c-afe6-d262bfb52b09`, deployed at 11:30:11 UTC.
Supported loaders discover the release on the next Minecraft launch.

Automatic routing now selects closed Wither gates rather than ordinary doors
along the Blood route. An adjoining Fairy room's open passage extends the
checked graph to its Wither gate, and crossing into Fairy retains that graph.
Rooms without an established Wither destination and all routing after Blood
opens are hidden. Paths and filled markers float 1.2 blocks above their checked
feet positions; beacon beams are removed.

Movement requests replan after 100 ms, player connections refresh each client
tick, and graph workers can start planning immediately. Valid remaining paths
survive ordinary walking/jumping while replacements are calculated. Changed
edges publish even when the standing-cell count is unchanged. The initial scan
can advance between ticks with a half-millisecond render scheduling budget;
individual native collision callbacks can exceed that budget.

| Release | Private artifact | Signed manifest | SHA-256 |
| --- | --- | --- | --- |
| Mod 2.4.17 | `releases/Sky-Myce-2.4.17.jar` | `releases/manifest-2.4.17.json` | `f35445aeccfd93f6870300c31655a59f7087a4d72959da9b8103e7bc02a0dd86` |

The mod manifest expires **October 15, 2026, at 11:28:23 UTC**. Loader 1.0.14,
Minecraft 26.1.2, minimum loader 1.0.11 and the signing trust root are retained.

Java 25 `check build`, `npm test`, `npm run check` and the backend packaging
dry run passed. Regressions cover Fairy traversal and stable context, closed
gate selection, Blood-open suppression, 100 ms movement deadlines, bounded
retries and new edges without new nodes. Private R2 readbacks match both checked
files exactly. Live mod/loader signatures, hashes, health and anonymous access
checks passed. Only the two mod release pointers changed; runtime settings,
other bindings, secret names/types and empty schedules are preserved.
External evidence uses `release-2.4.17-*` filenames. Live dungeon timing and
appearance still need gameplay verification.

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

## Settings command center release (2026-10-09)

Mod **2.4.19** is published in the private production R2 bucket and selected by
`sky-myce-backend` (`--env backend`). Settings now use inline category controls,
global fuzzy search, eased click feedback and smooth scrolling. Loader 1.0.14,
minimum loader 1.0.11 and the existing production signing key remain in use.

- Artifact: `releases/Sky-Myce-2.4.19.jar`.
- SHA-256: `df3feda7e160b8be315092984ee43aa25483b6f8fc6470884362b6cf8cc790af`.
- Manifest: `releases/manifest-2.4.19.json`.
- Worker version: `8a75c162-3473-4c14-a3ed-63b3385cce38`.
- Renew the manifest before **October 16, 2026, 10:35:07.644 Stockholm**
  (`2026-10-16T08:35:07.644Z`), retaining these exact artifact bytes.

Java 25 `build -x loaderCheck`, relay `npm run check`, `npm test` and the backend
deployment dry run passed. `loaderCheck` was excluded after its native-library
restart fixture failed with `AccessDeniedException`; no in-game launch was run.
The uploaded JAR was downloaded and hash-checked. Live signature, release version
and artifact hash were verified; health returned 200 and unauthenticated artifact
access returned 403. Existing dashboard variables and secrets were retained with
`--keep-vars --strict`. Evidence: `build/release-2.4.19-live-verified.json`.

## Continuous dungeon routing release (2026-10-09)

Mod **2.4.20** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`7eeb2217-f920-40b2-8003-49d959ce1ed7`. It includes the continuous Blood route,
closed-gate and pre-entry Fairy planning, collision-aware simplification and
cached asynchronous onward-room searches described in `dungeon-routing.md`.

- Artifact: `releases/Sky-Myce-2.4.20.jar`.
- SHA-256: `606eac7876f2e08327a02591d3934b6fd1fddc34fef741e052807fc87615ac1c`.
- Signed manifest: `releases/manifest-2.4.20.json`.
- Manifest expiry: `2026-10-16T09:44:07.199Z`.

Java 25 `build -x loaderCheck` passed; the existing native restart fixture's
`AccessDeniedException` remains excluded. Backend packaging dry-run passed.
The uploaded artifact was downloaded and hash-checked. Live manifest signature,
version and artifact hash passed verification; health returned 200 and anonymous
artifact access returned 403. Deployment retained dashboard variables and secrets
using `--keep-vars --strict`; prior releases remain available for later cleanup.
Evidence: `build/release-2.4.20-live-verified.json`. Live gameplay remains unverified.

The existing operator signing key is available at
`D:\Sky-Myce-Secrets\production-20260927.private.pem`; the older AppData path
above is stale in this execution environment. The user authorized deployment
as part of every completed mod release build; this preference is recorded in
`AGENTS.md`.

## Noamm full-map, Fairy handoff and route visibility release (2026-10-09)

Mod **2.4.21** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`031d2f35-4515-4124-ba9c-d54ff5dfb330`. Routing reads NoammAddons 1.2.9's
scanned room/door topology, including unopened rooms missing from the native
filled map. Discovered-room brightness does not mark a room cleared. It also
fixes native floor calibration, half-block room boundaries and cached Fairy
handoff while the current graph is still partial. Routing remains visible after
Blood opens elsewhere and hides on entering Blood or the boss phase. Brighter,
wider outlined paths and larger repeated arrows improve readability.

- Artifact: `releases/Sky-Myce-2.4.21.jar`.
- SHA-256: `8768200a21796f36a8b2b89a2bf6d2e5666707e5c39818ca3dd58e5136250c51`.
- Signed manifest: `releases/manifest-2.4.21.json`.
- Manifest expiry: `2026-10-16T10:23:40.509Z`.

Java 25 `build -x loaderCheck` passed all included checks. An attempted full
`check` reproduced the existing native restart fixture's `AccessDeniedException`,
which remains excluded. Noamm's reflection getters were checked against the
installed 1.2.9 jar; pure fixtures cover full scanned Fairy/Blood topology despite
missing native pixels, brightness versus clear states, mandatory doors and broad
separators, loaded gate precedence, invalid snapshots, calibration and safe
cached player joins. Backend packaging dry-run passed. Live version, pinned
signature and artifact hashes passed verification; health returned 200 and
anonymous artifact access returned 403. Existing variables and secrets were
retained with `--keep-vars --strict`; prior releases remain for later cleanup.
Evidence: `build/release-2.4.21-live-verified.json`. Live Minecraft rendering,
actual Fairy traversal and FPS remain unverified.

## Stair ramps, curved routes and earlier interior checks (2026-10-09)

Mod **2.4.22** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`515a1fc0-f5e2-448c-875a-c59a9021b384`. It separates cached display paths from
physical movement checks, draws monotone stair flights as ramps and rounds
corners only after native collision/support validation. Fixed spans of at most
64 raw edges bound repeated smoothing. Overlapping dark route strokes and arrows
are removed. Checked Fairy handoffs retain priority during partial local scans.
Onward interiors publish a checked provisional route before full flood completion,
then refine it. Nearby doorway anchors prefer reachable floor over pillar tops.
Unverified map hints remain dashed amber and say **Planned room connection**.

- Artifact: `releases/Sky-Myce-2.4.22.jar`.
- SHA-256: `ab0783b4abf3e22527850112cb2720727d8e0510baff86fa9aa00434596f84e1`.
- Signed manifest: `releases/manifest-2.4.22.json`.
- Manifest expiry: `2026-10-16T12:42:02.629Z`.

Java 25 `dungeonRoutingCheck` and `build -x loaderCheck` passed. Coverage includes
safe curves, stair ramps, mandatory boundaries and backward display cursors,
early publication and final refinement, delayed worker callbacks through flood
completion, entry during scanning, pillar anchors and raw/physical stair paths.
The existing Windows native restart fixture's `AccessDeniedException` remains
excluded. Backend packaging dry-run passed. Private R2 readback hash and live
pinned Ed25519 signature, selected version and artifact hash passed verification;
health returned 200 and unauthenticated artifact access returned 403. Deployment
retained existing variables and secrets with `--keep-vars --strict` and retained
previous builds. Evidence: `build/release-2.4.22-live-verified.json`.

The supplied 70-second replay was inspected using 140 full-resolution frames
at half-second intervals and seven contact sheets. This verifies the observed
old rendering defects; live Minecraft visuals, Fairy traversal and FPS after
the update remain unverified.

## Pre-start routing, stable approach checks and simpler settings (2026-10-09)

Mod **2.4.23** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`22ffba4d-132b-4626-8dc6-f31165f47f3b`. Routing starts when dungeon floor and
map connections are available before the start announcement; that announcement
keeps the warmed geometry and planning work. Growing checked graphs retain
approach-probe progress instead of repeatedly testing the same blocked cells.
Door approaches accept a nearby checked, supported endpoint even when the
canonical anchor lacks floor, while crossing and gate validation remain required.

The Dungeon Routing menu groups controls under Route, Mob markers, Appearance
and a collapsed Advanced section. **Route through starred mobs** defaults on;
turning it off replans toward Blood without mob stops. Observed mob markers
remain independently configurable, and reenabling the option restores mob stops.
Existing setting paths and defaults are preserved.

- Artifact: `releases/Sky-Myce-2.4.23.jar`.
- SHA-256: `d81017e2ad6082b98c7236e0e6a68b42a1301cff9ce089b3fcfe4e74d04f345e`.
- Signed manifest: `releases/manifest-2.4.23.json`.
- Manifest expiry: `2026-10-16T13:21:15.921Z`.

Java 25 `dungeonRoutingCheck hudCheck menuCatalogCheck` and
`build -x loaderCheck` passed. Coverage includes the pre-start lifecycle gate,
direct-exit versus mob-stop planning, blocked canonical door anchors in all four
rotations, probe progress across graph revisions and settings groups, search,
saved defaults and independent markers. The existing Windows native restart
fixture's `AccessDeniedException` remains excluded. Backend packaging dry-run
passed. Private R2 readback hash and live pinned Ed25519 signature, selected
version and artifact hash passed verification; health returned 200 and
unauthenticated artifact access returned 403. Deployment retained existing
variables and secrets with `--keep-vars --strict` and retained previous builds.
Evidence: `build/release-2.4.23-live-verified.json`.

The supplied 135-second replay was inspected using 271 full-resolution frames
at half-second intervals, 14 contact sheets and detailed crops. It shows the
full Noamm map before the start announcement and the second-run route dropout;
it does not verify the new build's gameplay. Live pre-start routing, room
traversal and FPS after the update remain unverified.

## Teleport sight lines, carpet handling and route continuity (2026-10-09)

Mod **2.4.24** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`d1c82c83-563b-4cdb-b481-0459b1a54200`. Clear teleport sight lines take priority
between required stops, including across pits. Native checks require loaded
space, player body clearance and a supported destination within 128 blocks,
without requiring floor support between endpoints. Physical walking graph
checks and mandatory mob stops/door centers remain intact. No input or teleport
packets are generated. Clear onward rooms publish two points immediately
without a flood or worker job; blocked sight lines retain walking detours.

Carpets are absent from routing collision, standing-height and support checks;
underlying floors, slabs and stairs retain native geometry. Repeated equivalent
carpet/air updates do not invalidate cached interiors. Block changes are batched
at client tick end and compared with the expected packet state; mismatches defer
once and invalidate conservatively. Safe obstacle removals retain revalidated
guidance during rebuilding until required destinations are rediscovered. New
obstacles, lost landing/support and unloaded chunks invalidate it. Growing
graphs reuse accepted paths while destinations and the player's reachable
geometry remain unchanged. Cached handoffs now cover rooms beyond Fairy when
mob-stop requirements allow them.

Direction arrows are removed. **Line height** under Appearance adjusts cached
strokes from 0 to 4 blocks above the floor, defaulting to 1.2, independently of
marker positions and geometry. Rendering avoids direction calculations for
solid strokes. Existing settings and old releases are retained.

- Artifact: `releases/Sky-Myce-2.4.24.jar`.
- SHA-256: `e5fca84c07eb36f8d7e7abf22b80927f836cf1ddcea16a9a4e0bc44ed7fce66a`.
- Signed manifest: `releases/manifest-2.4.24.json`.
- Manifest expiry: `2026-10-16T14:03:40.600Z`.

Java 25 `build -x loaderCheck` passed all included checks. Coverage includes
carpet/bare-floor equivalence, unsupported carpets, slab height, carpet-height
alignment, teleport guidance across pits and elevation changes, airborne starts,
walls/gates/ceilings, invalid/unloaded geometry, missing landings, direct-path
cache reuse, obstacle-removal retention and safe invalidation. Menu checks cover
the new slider's default, bounds, grouping and search. The existing Windows
loader restart fixture's `AccessDeniedException` remains excluded. Production
Worker packaging dry-run passed. R2 readback hash and live pinned Ed25519
signature, selected version and artifact hash passed verification; health
returned 200 and unauthenticated artifact access returned 403. Deployment used
`--keep-vars --strict`, retaining existing variables, secrets and prior builds.
Evidence: `build/release-2.4.24-live-verified.json`.

Exactly 150 original full-resolution pictures were extracted from the supplied
69-second replay, with five before 0:30 and dense coverage around 0:31–32,
0:40–44 and 0:48–50. Eight contact sheets and detailed crops document the old
door-handoff delay, missing carpet-room guidance and unnecessary door-approach
loop. The user clarified that clear teleport guidance should cross the pit at
0:49. These pictures do not verify the updated mod; real door continuity,
teleport sight lines, carpeted-room behavior and FPS still need gameplay checks.

## Stable room handoffs and Mage Blood-door reminder (2026-10-09)

Mod **2.4.25** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`af1e9f91-bcf0-4694-b6cc-b0376401637f`.

Temporary shortening of a confirmed door chain no longer discards its
destination's checked interior or queued work. Door identities and relevant
block/chunk invalidation still reject stale results. A bounded native doorway
probe can draw checked teleport guidance before the walking flood reaches its
exit; changes to that distant landing independently refresh the probe. Cached
handoffs use standing-node display smoothing with the moving player join kept
separate. Temporary join failures retain smoothed suffixes, and rendering no
longer switches to physical staircase elbows.

All routing stops when Blood opens, including outside Blood and after leaving
it. Only Mage receives a 23-second run-scoped Blood-door reminder; re-entry does
not extend it. The reminder respects Show Blood guidance and uses the installed
NoammAddons 1.2.9 doorway bounds, 3×4×3 at Y 69–73. Small door-approach waypoint
boxes and their labels are removed. Existing settings keys are preserved.

- Artifact: `releases/Sky-Myce-2.4.25.jar`.
- SHA-256: `09507c04a29f0ebd5b562d6258f8f927c163f31f797353e39e9313d74fbbe9fb`.
- Signed manifest: `releases/manifest-2.4.25.json`.
- Manifest expiry: `2026-10-16T14:37:55.006Z`.

Java 25 `build -x loaderCheck` passed all included checks. New regressions cover
shortened/extended chains and queued cache reuse, changed exits/chunks, multi-step
stair handoffs and stable display suffixes, immediate Blood visibility policy,
Mage-only timing, exact expiry, late door coordinates, reset and non-extending
re-entry. The existing Windows loader restart fixture's `AccessDeniedException`
remains excluded. Relay type generation/TypeScript checks and production Worker
packaging dry-run passed. R2 readback hash and the live pinned Ed25519 signature,
selected version and artifact hash passed; health returned 200 and unauthenticated
artifact access returned 403. Verification completed at
`2026-10-09T14:39:41.382Z`. Deployment used `--keep-vars --strict`, retaining
existing variables, secrets and previous builds. Commands were checked against
the current [Worker command reference](https://developers.cloudflare.com/workers/wrangler/commands/workers/)
and [R2 command reference](https://developers.cloudflare.com/workers/wrangler/commands/r2/).
Evidence: `build/release-2.4.25-live-verified.json`.

Exactly 150 original 3840×2160 pictures were extracted from the 60-second
16:09:24 replay, with eight contact sheets, selected keyframes and enlarged
details around 0:10, 0:32–35, 0:38–43 and post-opening Blood. The old route
disappears between 32.133 and 32.250 seconds, flips to short staircase elbows
around 41 seconds, and reappears outside Blood after opening. Gallery and visual
analysis are under
`C:/Users/krister/.codex/visualizations/2026/10/09/01a11fbc-365f-72a0-9cb1-06fb1c068891/routing-replay-160924/`.
These pictures document the previous build; current live-room continuity,
highlight appearance and client FPS still need gameplay validation.

## Gap traversal, stable player guidance and starred miniboss routing (2026-10-09)

Mod **2.4.26** is uploaded to private production R2 and selected by
`sky-myce-backend` (`--env backend`), Worker version
`467851d3-3777-4378-a9f9-538b16dfdc27`.

Nearby supported landings can warm geometry scans and reconnect display guidance
while the player is airborne. Automatic graphs add bounded, level cardinal jumps
over one or two unsupported cells, with loaded body clearance and jump headroom;
clear teleport guidance still crosses longer gaps. Pending target approaches and
null plans from unfinished scans retain the prior checked route and its original
target anchors. Door completion keeps handoff guidance until room entry. Early
native guidance can reach the first required starred target or exit before the
walking flood finishes; empty or exit-only partial plans cannot suppress a
required target guide. Visible anchors remain cached, while completed negative
probes retry after meaningful movement without starving their bounded progress.

**Route through starred minibosses** is independent of regular starred-mob
routing and defaults on. Only actual starred labels establish these targets;
unstarred miniboss profiles are excluded. Both toggles off route directly toward
Blood, with markers independently configurable. Mage now keeps the real Blood
doorway highlighted throughout the run, before and after opening, except while
physically inside Blood. Leaving Blood restores it without a timer. All route
lines and mob markers still stop when Blood opens. Iron bars are ignored like
carpets in collision, support, standing height and equivalent block updates;
neither decoration can supply floor support. The player mines bars manually.

- Artifact: `releases/Sky-Myce-2.4.26.jar`.
- SHA-256: `a4e12f666c5fb5c472fecc8f0cac9ea0dba7789839e0a4064158d955b5f7d2dd`.
- Signed manifest: `releases/manifest-2.4.26.json`.
- Manifest expiry: `2026-10-16T15:34:53.613Z`.

Java 25 focused routing/settings checks and `build -x loaderCheck` passed all
included checks. New regressions cover supported jumps and native wall/headroom
rejection, iron-bar floor handling, airborne display joins, pending approaches,
retained original target anchors, partial graph failures, early visible candidate
selection and probe progress, independent starred-miniboss settings and physical
Blood containment. The existing Windows loader restart fixture's
`AccessDeniedException` remains excluded. Relay type generation/TypeScript checks
and production Worker packaging dry-run passed. Private R2 readback hash and live
pinned Ed25519 signature, selected version and artifact hash passed; health
returned 200 and unauthenticated artifact access returned 403. Verification
completed at `2026-10-09T15:36:32.010Z`. Deployment used `--keep-vars --strict`,
retaining existing variables, secrets and previous builds. Commands were checked
against the current [Worker command reference](https://developers.cloudflare.com/workers/wrangler/commands/workers/)
and [R2 command reference](https://developers.cloudflare.com/workers/wrangler/commands/r2/).
Evidence: `build/release-2.4.26-live-verified.json`.

The 76-second 16:47:43 replay has **180 distinct original 3840×2160 pictures**,
nine timestamp contact sheets, 30 selected keyframes and 30 inspected detail
crops. Sixty-one samples cover 1:07–1:10 at 20 fps, documenting three whole-route
dropouts while the planned crossing remains visible. Gallery and visual analysis:
`C:/Users/krister/.codex/visualizations/2026/10/09/01a11fbc-365f-72a0-9cb1-06fb1c068891/routing-replay-164743/`.
These pictures document the previous build. Actual Doors-room traversal, early
player guidance, door continuity, Mage highlighting and FPS still need gameplay
validation with the new build.

## Menu, chest tracking, statistics cutover and global IRC (2026-10-10)

Mod **2.4.27** is uploaded to private production R2 and selected by
`sky-myce-backend` in **`--env backend`**. The initial mod-release deployment was
`ee1f16b0-ebd5-4489-8790-a3835183f507` /
`40b8f308-e33c-4f3d-8653-fac911454741`, created
`2026-10-10T10:45:21.970038Z`. A subsequent backend-only subscription fix is now
active as version `dd0588b1-a721-4695-9b62-ead63185c157`, deployment
`edb59ed5-eb20-4e43-bf28-ecf8e34df894`, created
`2026-10-10T12:30:22.194111Z`. It retains the exact 2.4.27 JAR/manifest, all
bindings, secret names/types, storage namespaces and runtime configuration.

This release fixes stable menu card hover, shared Settings navigation and catalog
search allocations; repairs bounded chest/RNG observation and inventory confirmation;
preserves inherited profit through the version 2 tracker cutover; and presents
canonical `/sm irc`, `/sm pc` and `/sm chat` commands. Party delivery remains
explicitly unavailable without backend-verifiable Hypixel membership. Existing
routing behavior and previous release artifacts remain.

- Artifact: `releases/Sky-Myce-2.4.27.jar`, **14,027,050 bytes**.
- SHA-256: `6c2ce3c0d7b6f999213c7af4da8cea13eacaeed4d266897518da8d826686b4e2`.
- Signed manifest: `releases/manifest-2.4.27.json`.
- Signing key ID: `production-20260927`.
- Minecraft: `26.1.2`; minimum custom loader: `1.0.11`.
- Manifest expiry: `2026-10-17T10:44:00.323Z`.

Java 25 `--offline check build` passed **all** registered checks, including the
unchanged `loaderCheck`. The Windows restart fixture passed with a clean
task-local Java/Windows PATH; it was not excluded or weakened. Relay tests,
generated types/TypeScript checks and strict backend/staging packaging dry-runs
passed. The existing signer self-check passed. The private artifact was uploaded
before its signed manifest, downloaded and hash-checked, then activated with
`--keep-vars --strict`. The live envelope matches the local signed bytes and
passes pinned Ed25519 signature, version/expiry and artifact-hash checks. Health
returned 200; unauthenticated artifact access returned 403. Verification:
`2026-10-10T10:46:17.040Z`, `build/release-2.4.27-live-verified.json`.

The final backend bundle is 340,924 bytes, SHA-256
`d38d42027d7080d5ce52403b7779ccba769243a172c09cac7b874540d3dd334e`.
Independent Cloudflare readback matches the reviewed strict dry-run bundle.
The live release signature, unchanged artifact hash and access guards were
reverified at `2026-10-10T12:35:24.679Z`;
`build/global-chat-backend-hotfix-verified.json` records that backend-only check.
The current private R2 artifact was downloaded again and matched the built/signed
JAR byte-for-byte at `2026-10-10T12:46:08.938Z`.

The user's current authorized Prism session obtained this exact JAR through the
normal loader restart. Native private-world menu/chat tests and read-only live
migration verification passed: old rows and profit were preserved, and earlier
records are excluded from new confirmed statistics. A new Hypixel reward chest,
controlled FPS comparison and hour-scale soak remain unverified. See the
[implementation report](implementation-overhaul-2026-10.md),
[menu measurements](menu-optimization-2026-10.md) and
[tracker cutover evidence](dungeon-tracker-cutover.md).

The persistent User Bot Gateway is now hosted on the existing production
`skyblock-relay` Worker from `D:\Code\Cessna fork\relay`, with its own additive
SQLite class and the existing `SKY_MYCE_BACKEND` service binding. It uses the
existing bot/application/channel and private Worker secrets; no local Node
process is required. Initial deployment was version
`5c2f606b-003b-48ab-9b9e-0e29e8734045`; replay ordering was repaired in
`c5adcd63-0a33-4100-b7c4-b2b27e36ca00`. The final Gateway version is
`ecd1963b-5bfa-4f2b-b981-be2703d5aeb1`, deployment
`54fcca30-45a2-49b7-8612-80185c635cc3`, created
`2026-10-10T12:05:57.715626Z`. Its 113,250-byte reviewed bundle has SHA-256
`99ae6a696ecf8fe3698c743c0d57bfefff798d6c16f260ea6590197b4a278416`.
Clearable Upgrade-handshake timers now preserve adopted WebSockets; stalled
handshakes still expire. Focused workerd replay, socket lifetime, cancellation,
SQLite eviction, negative controls and TypeScript validation passed. Independent
provider readback matches the reviewed/runtime-tested bundle.

The initial observation after the final publication still saw reconnects; that
failure remains in `build/global-chat-gateway-deployment-verified.json`. A controlled
stop/start retained durable state and recovered both transports. The final window
passed 39/39 samples over 96 seconds with zero pending messages. Real Minecraft
messages and duplicate rejection were verified against Discord. The user's fresh
`hello248` reply reached Minecraft with correct reply context and rendered mention;
`build/global-chat-live-round-trip-verified.json` records the real round trip.
Sound audibility and hour-scale uptime remain unverified. Private control rejects
unauthenticated calls. Existing legacy bindings,
secret names, RelayRoom namespace and signed admin/account forwarding were
preserved; unsigned interactions remain rejected. The reviewed release entry
point in `build/gateway-release/` combines the exact previously deployed legacy
bundle with the new Gateway only, avoiding unrelated uncommitted Cessna admin/
prompt changes. Do not publish that repository's broader dirty entry point as
though it were this reviewed release. The active outbound sockets contribute to
Durable Object usage; no billing plan was changed.

## Dungeon tracker history and recording repair (2026-10-10)

Mod **2.4.28** is selected on production `sky-myce-backend --env backend`.
Worker version `cdf2e6e8-5914-4dfd-baf3-b0270ee05fca`, deployment
`2209afb8-9a8e-40f8-bbd9-1ff38554c61d`, was created at
`2026-10-10T13:18:32.714429Z` with 100% traffic.

This release restores visible historical tracker totals and saved reports,
repairs Master Mode completion and dungeon profile continuity, and observes
direct inventory reward packets. It preserves old history separately from new
measurements. See the [tracker repair record](dungeon-tracker-cutover.md).

- Artifact: `releases/Sky-Myce-2.4.28.jar`, **14,031,505 bytes**.
- SHA-256: `91fe4e8e46a3345d266cfba02454b0c7b1097f1ec3be5f6c3abc63b90414b96e`.
- Signed manifest: `releases/manifest-2.4.28.json`.
- Signing key ID: `production-20260927`.
- Minecraft: `26.1.2`; minimum custom loader: `1.0.11`.
- Manifest expiry: `2026-10-17T13:15:30.883Z`.

Java 25 focused checks and the complete `--offline check build`, including the
unchanged `loaderCheck`, passed. Relay type generation/TypeScript checks and
strict backend/staging packaging dry-runs passed. The private artifact was
uploaded and read back before its signed manifest; both matched their local
bytes. Publication used `--keep-vars --strict`. Only the two release pointers
and deployment message changed; other configuration, bindings, secrets and
storage namespaces were preserved, and previous builds were retained.
The 340,924-byte backend bundle remained byte-identical to the preceding live
version: `d38d42027d7080d5ce52403b7779ccba769243a172c09cac7b874540d3dd334e`.

Live verification at `2026-10-10T13:21:28.810Z` passed the pinned Ed25519
signature, exact envelope, version, expiry and artifact-hash checks. Health
returned 200. The artifact endpoint rejected GET with 405 and an unauthenticated
POST with 403. The normal signed loader installed the matching 2.4.28 JAR in the
user's existing Prism instance. Native private-world tracker UI checks passed
for default historical totals, historical loot/XP and all saved reports;
original view preferences were restored, and history files/backups remained
byte-identical. A fresh Hypixel chest and the HUD during an actual dungeon remain
unverified. Private operator evidence is in
`C:/Users/krister/.codex/visualizations/2026/10/09/01a11fbc-365f-72a0-9cb1-06fb1c068891/tracker-recovery-20261010/`;
no raw player history or credentials were added to this record.
