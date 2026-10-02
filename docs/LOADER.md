# Sky-Myce Loader and client authorization

The loader is a separate Fabric mod, `skymyce-loader`, distributed as
`Sky-Myce-Loader.jar`. The protected `skymyce` JAR depends on it. Minecraft
26.1.2, Fabric Loader 0.19.3, Java 25, Fabric API and Fabric Language Kotlin
are required. Version values live in `gradle.properties`.

## Loading and first activation

1. An existing Sky-Myce admin runs `/authorise user:@person`. The bot DMs
   that person a one-time activation code and the loader. `reissue:true`
   explicitly replaces an outstanding code.
2. Close Minecraft. Run the trusted loader JAR with Java 25 and select the
   existing Minecraft instance directory (not its `mods` subfolder). It installs
   itself into `mods`, keeps one loader copy, and backs up old unverified or
   duplicate Sky-Myce JARs under `.sky-myce/replaced/`. Other mods are untouched.
3. Start the instance normally with Fabric. Loader 1.0.4 opens a desktop window
   before Minecraft's main initialization and rendering. It uses the launcher's
   signed-in Minecraft account; it never requests a Microsoft password or access token.
4. Paste the code from the DM into this **startup window** and click **Confirm and start**.
   No second Discord command is needed. Do not enter the code in Minecraft or
   Discord chat. The backend resolves the account from the code's stored hash;
   the client cannot supply a Discord identity to redirect activation.
5. The backend verifies the existing Minecraft certificate/profile/signature
   proof and installation Ed25519 proof, consumes the activation key atomically,
   and returns a signed lease. The loader downloads and verifies the protected
   release, then installs `mods/Sky-Myce.jar`.
6. Minecraft starts automatically. When the mod changes, the helper closes only
   the initial bootstrap process, applies the verified update, and starts a fresh
   JVM for the same instance before Minecraft's main initialization. Fabric then
   discovers the protected mod and its mixins normally. This happens at most once
   per startup; no classes or mixins are added to an already running game.

First activation and startup updates need **no manual game restart**. Fabric's
discovery phase still runs before the desktop window, but Minecraft's expensive
initialization runs only after setup succeeds. An unchanged verified installation
continues in the same process. No JAR removal or launcher-specific setup is needed.
The desktop window uses Swing with the loader's colors; owo-lib requires Minecraft's
renderer and remains the UI for the in-game **Sky-Myce Loader** button and
`/skymyce-auth`. Updates requested after Minecraft has started still need a restart
or normal exit. The startup window offers Retry, Start without Sky-Myce, and Close;
skipping setup grants no access. The in-game menu can retry later.
A new alternate Minecraft account uses a fresh admin-issued code; existing
activated installations renew automatically without entering a code again.
The old explicit Discord approval endpoint remains for older loaders.
Reconnection and restart first try signed renewal, so a lost
activation response does not require redeeming the consumed key again.

The unchanged Mojang trust roots remain on the backend. Startup uses the existing
Mojang Authlib session and account-key APIs directly, without loading Minecraft.
It shares the domain-separated RSA proof encoder and backend/download client with
the in-game path, which retains its No Chat Reports account-key accessor. Session
tokens and certificate private keys stay in memory. No UUID or display name is
accepted as proof by itself. Startup does not read a launcher's account database.
Launches without an online session or with an authenticated SOCKS proxy can use the
existing in-game sign-in path. A headless environment also retains that fallback.
An early lease keeps its original monotonic deadline through game initialization;
starting Minecraft cannot extend its lifetime or authorize an offline restart.

## Updates and cached artifacts

Starting with loader **1.0.9**, automatic loader updates are **off by default**.
Opt in through the main menu's **Loader → Automatic loader updates: OFF** button.
The saved choice applies to this Minecraft instance on its next launch; press
the button again to turn updates off. Missing, unreadable or invalid settings
leave loader updates disabled. The setting is stored in
`.sky-myce/loader-settings.properties` as `autoUpdateLoader=true` or `false`.
Disabling it also prevents a staged loader update from installing on exit.
Ordinary Sky-Myce mod installation, mod updates and authorization still run.
You can install a new loader manually using the JAR from an admin's Discord DM.

Older loaders (1.0.5–1.0.8) update automatically once to receive this new
default; their existing code cannot read the opt-in setting. From 1.0.9 onward,
future loader self-updates require explicit opt-in.

When enabled, the loader checks Cloudflare for a newer loader before normal
account/mod setup. It verifies a separate Ed25519-signed manifest, downloads the loader,
and verifies its SHA-256, size, mod ID and version. A helper running from a
cached copy waits for this process to exit, backs up the old loader, installs
the new one and restarts the same instance. The updated loader resumes setup
automatically. A loader update and a protected-mod update may each restart the
small bootstrap once; Minecraft initializes after both are ready. Further
handoffs in the same launch are rejected to prevent loops.

`GET /loader/bootstrap-release` exposes signed metadata only; loader bytes
remain private. `/loader/bootstrap/renew` and `/loader/bootstrap/activate`
require a fresh, single-use installation challenge plus verified Minecraft
identity and the existing account/device/license checks. First-time activation
also requires the DM code and consumes it atomically. These endpoints can
update an outdated loader but issue **no feature lease**; normal authorization
runs again in the updated loader without requiring a second code. Protected
artifact and relay minimum-version checks remain enforced.

Loader downloads are limited to **64 MiB** over HTTPS with redirects disabled
and a **90-second** deadline. Same/older releases are ignored, failed verification
does not replace the installed JAR, and installation failures restore backed-up
JARs. Loader **1.0.7** also recovers leftover staging files after a completed
replacement without reinstalling different bytes at the same version or
blocking a pending protected-mod update.
A temporary metadata-service outage leaves the current loader available
subject to normal authorization. Loader signing keys are pinned locally.

Operators sign the bootstrap with `node relay/scripts/release.mjs sign-loader
<loader.jar> <private.pem> <key-id> <minecraft-version> <loader-version>
<manifest.json>`. Upload the immutable `loaders/Sky-Myce-Loader-<version>.jar`
first, then its signed manifest; set backend `LOADER_MANIFEST_KEY` to that
manifest and `SKYMYCE_LOADER_R2_KEY` to the same JAR for new Discord deliveries.
Manifests expire after **seven days** and must be refreshed with the release
signer if that release is still offered. Never replace an existing version's
JAR with different bytes. The private R2 bucket remains nonpublic.

`GET /loader/release` exposes only an Ed25519-signed release manifest. Its
object name is metadata, not a public download capability. The loader verifies
its bundled public key and discovers the target version before authorization.
This avoids trapping an outdated mod behind the minimum-version policy.
Installation challenges and leases target that signed version, but an older
loaded payload remains disabled.

The artifact itself requires a current account/device lease plus a fresh,
one-use signed installation challenge. Its SHA-256 is authenticated by the
manifest signature. The loader checks product, protocol, Minecraft version,
minimum loader version, release version, signature and hash before installing.
Downloads are capped at 128 MiB; redirects are disabled. Failure leaves the
previous JAR intact. All HTTP, key protection, disk access and payload hashing
run away from Minecraft's render thread.

The loader stages updates under `.sky-myce` because Windows cannot reliably
replace an open JAR. A single helper using Minecraft's Java runtime waits for
the exact Minecraft process to exit, verifies the download again, backs up
old Sky-Myce payloads (including renamed copies), then installs the replacement.
From the in-game menu, choose **Restart Minecraft** to install and reopen the same instance.
Normal Minecraft exit also triggers installation; it is not tied to the button.
The helper retries transient file locks at most 20 times, 250 ms apart. It never
authorizes the account. It relaunches for the pre-game update handoff or an explicit
in-game Restart click; a normal quit never relaunches. Its safe result is recorded in
`.sky-myce/update.log`. Preparation errors report a fixed stage identifier,
never launch arguments or credentials. Recovery appends to this log instead of
erasing the failure; logs over 64 KiB rotate to `update.previous.log`.

Restart is independent of the launcher brand. It reuses the same Java runtime,
VM settings, Fabric classpath, instance directory and current Minecraft account.
Session/game arguments pass through anonymous pipes to the helper and new JVM;
they are never saved in argument files or diagnostic logs. The loader does not
read a launcher's account database or require a launcher-specific directory layout.
Before Minecraft closes, the helper verifies that its restart configuration can
be prepared. If preparation fails, Minecraft stays open and the screen explains
the failure. The Restart button never silently changes into a Quit action;
normal Quit remains available from Minecraft's own menus.

Preparation preserves temporary runtime JARs and native libraries that a launcher
may remove when its original game process exits. The `.sky-myce` cache in the
game's original working directory stores
executable libraries, not account files. Native copying is bounded to 4,096 libraries
and 256 MiB per preparation and verifies the copied contents before reuse. The
JVM may list native search directories that do not exist; these optional entries
remain unchanged and do not block restart when LWJGL loads its natives elsewhere.
This covers Minecraft 26.1 launchers whose old native extraction directory is unused.
The new JVM starts Fabric normally only after the original Minecraft process exits
and any pending signed update has been verified and installed.
Windows instance names, including characters outside the active system code page,
travel through encoded file references and the private pipe. Special JVM options
that cannot be replayed safely, or temporary agent JARs with relative manifest
dependencies, fail during preparation with Minecraft left open.

If automatic installation fails, the pending download is retained. Close
Minecraft and run the loader JAR again for that instance, or run:

```text
java -jar Sky-Myce-Loader.jar --apply /path/to/minecraft-instance
```

The installer verifies the staged signature/hash again, rejects a downgrade,
and atomically replaces the fixed `mods/Sky-Myce.jar`. It identifies duplicates
by the exact Fabric mod ID, not filename guesses. Failed writes restore moved
files; backups are retained and no other mod is replaced. Restart afterward.
The pending JAR/manifest pair remains available until both replacements finish;
if installation is interrupted, rerun the installer with Minecraft closed.

Install loader 1.0.5 once to upgrade from 1.0.0–1.0.4. Those older loaders
cannot gain self-updating from a protected-mod update. Later compatible loader
releases install through the startup window when automatic loader updates are enabled.
The loader includes owo and its dependencies, so its themed setup screen
works before the protected mod exists. Activation input is masked and never
placed in chat, command history or screen-reader narration.

A small Java-only Fabric `preLaunch` entrypoint verifies the cached protected
JAR against its signed installation manifest before game/mod initialization.
An expired acquisition manifest can still authenticate previously installed
bytes; it cannot authorize runtime operation. Current runtime authorization
also checks the freshly published release. A corrupted JAR fails startup with
a repair message. Run the standalone loader to back up the invalid payload and
restore it through fresh authorization; never disable verification to
recover. Fabric discovery happens before this hook, so another malicious mod
or a modified Fabric loader is outside the host trust boundary.

## Runtime authorization sessions

Loader **1.0.10** gives each game process a new random authorization session ID.
The ID stays in memory and is reused across retries, activation and lease renewal;
it is never saved or carried into a restarted process. Each launch still requires
the existing fresh Minecraft and installation proofs before protected features load.
The standalone installer only installs verified files; it grants no authorization.

Linux, Windows, macOS and other platforms use the same persistent-device policy.
The obsolete `LINUX_REQUIRE_REAUTH_ON_EVERY_LAUNCH` setting is ignored. New runtime
session IDs do not replace the HWID, invalidate another legitimate same-device
session, require another activation code or consume a hardware transfer.
Retries and lease renewal retain the same runtime session ID.

Launch authorization never clears or changes the persistent HWID, installation
key or seven-day hardware-transfer cooldown. Boot, shutdown, suspend and login
events do not affect this identity. A fresh game process requires reauthorization
regardless of whether the operating system restarted. Genuine device transfers
still use the existing authorized reset and activation flow.

The platform and session are bound into the installation signature and returned
signed lease. The backend pins the first authenticated platform per installation
and rejects a platform change. As with the existing device identity, a compromised
host can spoof software-reported metadata; this is not hardware or OS attestation.

Sky-Myce **2.4.12** requires loader **1.0.10** on every platform, because older
loaders cannot supply this signed session metadata. Loader self-updates remain
opt-in and off by default. Users who keep them disabled must install the compatible
loader manually; the bootstrap-only authenticated upgrade path remains available.

## Device privacy and local key storage

Identity is shared by Minecraft instances belonging to the same OS user in
`~/.sky-myce/device-v1/`. It contains:

- A random installation UUID and an Ed25519 keypair.
- A persistent opaque device reference, created once and retained outside
  Minecraft's instance folder. Existing references and keys survive upgrades.
- A selected hardware source. Its product-specific SHA-256 identifier is
  recalculated on launch and included in the signed installation proof;
  the backend pins it after successful authentication. Windows uses the
  SMBIOS system UUID, falling back to the persistent OS `MachineGuid`;
  Linux uses a readable DMI UUID, falling back to the
  persistent OS machine ID; macOS uses `IOPlatformUUID`. The selected source
  is retained so a newly readable source never changes an existing identity.
- The last signed lease, retained as a boundary/audit aid, **never** used to
  authorize an offline restart.

No raw hardware identifier, MAC, product key, hostname, Minecraft token or
Discord token is uploaded or logged. Only the opaque hardware hash participates
in authorization. The private key
is protected with Windows DPAPI `CurrentUser`, with a product-specific entropy
label and an owner-only ACL. On POSIX it is stored under a `0700` directory in
a `0600` file. Creation is serialized with an OS file lock.

Copying the Minecraft instance does not copy this identity. Copying Windows
key ciphertext to another machine/user does not make DPAPI decrypt it. A user
who controls their host can export/spoof a software identity and hardware
inputs; POSIX permissions do not resist the account owner. The OS machine-ID
fallback identifies an OS installation rather than an immutable physical
machine, and virtual machines can clone/spoof their firmware UUID. Unsupported
platforms retain the persistent software identity without hardware commands.
These are explicit software trust limits, not claims of hardware attestation.
Copying an identity to a machine with another hardware identifier causes a
backend `HWID_MISMATCH`; the client never trusts a cached hardware hash.
If the selected source becomes temporarily inaccessible, authorization pauses
with retry guidance; the identity and binding remain intact.
Deleting the identity creates a new installation that the server rejects
until the account's reset/re-enrollment policy permits it.

### Persistent enrollment and moving installations

Enroll once, then use the same device normally. Linux and Windows use the same
persistent binding policy. Reopening Minecraft or the loader, rebooting,
shutdown/startup, suspend/resume, logout/login, software updates, WebSocket
reconnection, lease expiry/renewal and IP/network/VPN changes never reset the
HWID or consume a hardware transfer. An existing device does not need another
activation code merely because a runtime session ended. The backend remains
authoritative for enrollment, verification and deliberate binding changes.

Normal hardware transfers use a seven-day cooldown that applies only to actual
binding changes. Admin emergency resets and deliberate security invalidation
revoke the affected authorization and are audited; normal launches never
produce HWID-reset events.

Ubuntu uses the same loader JAR, activation window, signed self-update and
launcher-independent restart. Use Java 25 with desktop/AWT support in Prism;
the loader starts the same Java executable inside the launcher's environment.
It does not invoke Windows commands on Linux. Automated update/restart checks
run on Ubuntu 24.04 CI; the same checks and the native desktop window were also
verified on Ubuntu 22.04 under WSLg. These checks do not authenticate a real
Minecraft account or verify every launcher sandbox.

An OS reinstall, switching from Windows to Linux, or a launcher package with
a different Java `user.home` can produce a new installation identity. Keep
`~/.sky-myce/device-v1/` private and persistent; deleting it does not fix sign-in.
If the loader reports `HWID_MISMATCH` (or legacy `device_reset_required`), use
the User Bot's `/hwid reset confirm:true` for an intentional hardware transfer
after the seven-day cooldown.
An emergency `/hwid reset user:<Discord user>` is an explicit administrator
action and revokes the old installation. Retry the newest unexpired, unused
activation code afterward; `/authorise` by itself does not reset the device.
The desktop and in-game menus explain this restriction.

## Leases, revocation and outages

Public signing keys are compiled into the loader. Server replies cannot add
keys. Lease claims bind account, Discord ID, Minecraft UUID, installation,
device hash, hardware hash, public-key fingerprint, product/version, generation, features and
the **exact request challenge ID and freshly generated client nonce**. The
client nonce is retained from the outgoing request, never taken from an echoed
server field. Replaying an old challenge response together with its signed lease
cannot reset a new request's local grace boundary. WebSocket authorization additionally
requires an installation signature over the relay nonce and exact lease.

Renewal runs at half the signed lease duration, capped at two minutes, and
reconnects the existing relay using the new lease. Normal server lease duration
is five minutes with fifteen additional minutes of offline grace. There are
bounded immediate retries, then a thirty-second retry interval. IRC and all
relay operations require live online authorization. Local handlers may continue
within the signed grace after an actual backend outage.

Elapsed time uses `System.nanoTime`, anchored to a successful live request.
Changing the wall clock cannot extend a running session. Offline restart is
intentionally unsupported: every new JVM requires the backend. This removes
the clock-rollback loophole in software-only persisted offline counters.
Grace expiry unregisters module handlers and disables direct tick/render/
scheduled callbacks. Explicit server denial bypasses grace. A request epoch
prevents an older in-flight renewal from undoing a received revocation.

Fabric key mappings and hooks register at their required initialization phase;
their execution is gated until authorization. Revocation disables Sky-Myce,
clears online cosmetic state, closes its relay and shows the appeal/contact
message. Minecraft and other mods can continue. Backend authorization remains
the security boundary even if a local gate is patched.

## IRC

`/chat irc` selects the existing relay's IRC subscription. Ordinary text is
intercepted locally and sent only as `irc_send`; `/chat p` or `/chat a` leaves
IRC and proceeds to the normal server chat-channel command. `/sm msg`, `/sm r`
and existing LFG commands remain separate. Selecting `/sm chat <name>` leaves
IRC for the existing private relay conversation.

The selected IRC channel remains private through backend disconnects,
rate limits and rejected sends. Failed messages never fall through to public
chat. Incoming messages are displayed locally, bounded, source tagged and
deduplicated; they never enter the outbound chat event. A game-server disconnect
clears the selection. The backend owns sender identity, licensing, room
isolation, validation and delivery limits.

## Building and deployment configuration

Loader 1.0.12 reports a rejected installation proof separately from account
restrictions. Startup logs include only recognized authorization codes; unknown
server text and account credentials are never logged. The hardware-bound v3
installation proof requires the matching backend from this repository. A backend
that verifies only v2 rejects it as `installation_proof_invalid`; deploy the v3
verifier rather than downgrading the proof or resetting device identity. The prepared
production rollout requires loader 1.0.11 or newer, the first configured v3 build.

Loader 1.0.11 fixes local/default builds that previously embedded
`configure-before-distribution.invalid` and an empty trust key. Java HTTP DNS
failures can wrap `UnresolvedAddressException`, an `IllegalArgumentException`,
so the old startup window incorrectly blamed online-account launch settings.
Connection failures now show connection guidance; invalid launch identity is
classified separately. The log records only exception classes and an owned
source location, without exception messages, launch arguments or credentials.
Account proof, signature checks, hardware registration and restart handoff are
unchanged. Replace the old bootstrap JAR after closing the Minecraft instance;
no HWID reset or new activation code is needed for this configuration fix.

```powershell
$env:JAVA_HOME = '<JDK-25-directory>'
$publicKey = Get-Content -Raw '<public-SPKI-base64-file>'
.\gradlew.bat build `
  '-PskyMyceBackend=https://<staging-worker-host>' `
  '-PskyMyceSigningKeyId=release-1' `
  "-PskyMyceSigningPublicKey=$($publicKey.Trim())"
```

These values are public. Never pass a signing private key or bot credential
to Gradle. `build/libs/Sky-Myce-Loader.jar` is the bootstrap/installer;
`build/libs/skymyce-<mod_version>.jar` is the artifact to sign and upload to
private storage. `loaderJar` builds just the bootstrap. `loaderCheck` executes
the security checks and is included in `check`/`build`.

The repository defaults to the existing production backend and pinned public
signing key in `gradle.properties`. Override all three values together for a
staging backend. Blank, malformed or placeholder configuration is rejected by
the build; no signing check is bypassed. Public-key rotation requires distributing an
updated trusted loader before switching the active key ID. Server token
rotation requires no client rebuild. Use the release signing/setup procedures
in the relay/operator documentation.

## Validation and remaining operator checks

Automated checks cover real Ed25519 signatures, altered JAR bytes, bad/missing
trust keys, unsupported versions, request replay, UUID/device/key bindings,
DPAPI identity persistence, monotonic grace expiry, explicit denial and IRC
no-fallback routing. Existing dungeon, social, cosmetics, digest and HUD
assertion checks remain part of the Gradle build.

Before rollout, run a staging Minecraft/Discord smoke test with real Mojang
certificate material: first activation and restart, alternate account approval,
two-player IRC in both rooms, runtime revoke, a backend outage, and a Windows
quit/apply/restart update. Production credentials or a live Minecraft session
are not required by the automated tests and were not used to claim these
manual checks passed. Development integration should use built signed artifacts
in a separate launcher instance; raw Loom `runClient` class directories are
not a signed protected release and intentionally cannot pass artifact checks.

Official behavior checked: [Fabric Loader and mixins](https://docs.fabricmc.net/develop/loader/),
[Fabric metadata](https://docs.fabricmc.net/develop/loader/fabric-mod-json),
[Minecraft 26.1 Java/toolchain changes](https://www.fabricmc.net/2026/03/14/261.html).
The installed Fabric API source also confirms key mappings must be registered
before `GameOptions` initialization. Moving to 26.3 requires normal Minecraft
API/mixin compatibility testing; the authentication/account protocol is separate.
