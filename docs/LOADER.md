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
3. Start the instance with Fabric. The loader uses the launcher's signed-in
   Minecraft account; it never requests a Microsoft password or access token.
4. Paste the code from the DM into the **local screen** and click **Confirm**.
   No second Discord command is needed. Do not enter the code in Minecraft or
   Discord chat. The backend resolves the account from the code's stored hash;
   the client cannot supply a Discord identity to redirect activation.
5. The backend verifies the existing Minecraft certificate/profile/signature
   proof and installation Ed25519 proof, consumes the activation key atomically,
   and returns a signed lease. The loader downloads and verifies the protected
   release, then installs `mods/Sky-Myce.jar`.
6. Click **Restart Minecraft**. The helper waits for the game to close,
   applies any verified pending update and reopens the same Minecraft instance.
   Fabric now discovers the protected mod's mixin
   configuration during normal startup. No classes or mixins are added after
   Minecraft has started.

First activation needs **one restart after the download**. The first launch is
the bootstrap; the second loads the protected mod. You do not need to remove
JARs or run the installer between those launches. The authorization screen
can be dismissed to play vanilla/other mods with Sky-Myce disabled. Open it
again using the **Sky-Myce Loader** button in Minecraft's main menu or
`/skymyce-auth`. The screen also checks for updates and restarts Minecraft.
A new alternate Minecraft account uses a fresh admin-issued code; existing
activated installations renew automatically without entering a code again.
The old explicit Discord approval endpoint remains for older loaders.
Reconnection and restart first try signed renewal, so a lost
activation response does not require redeeming the consumed key again.

The unchanged Mojang trust roots remain on the backend. The bootstrap copies
the existing domain-separated RSA proof format and preserves the account-key
accessor used with No Chat Reports. No UUID or display name is accepted as
proof by itself.

## Updates and cached artifacts

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

Loader 1.0.3 stages updates under `.sky-myce` because Windows cannot reliably
replace an open JAR. A single helper using Minecraft's Java runtime waits for
the exact Minecraft process to exit, verifies the download again, backs up
old Sky-Myce payloads (including renamed copies), then installs the replacement.
Choose **Restart Minecraft** to install and reopen the same Minecraft instance.
Normal Minecraft exit also triggers installation; it is not tied to the button.
The helper retries transient file locks at most 20 times, 250 ms apart. It never
authorizes the account. It only relaunches after an explicit
Restart click; a normal quit never relaunches. Its safe result is recorded in
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

Install the new trusted loader once to upgrade from 1.0.0–1.0.2; an older
loader cannot gain the new restart behavior from a protected-mod update.
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

## Device privacy and local key storage

Identity is shared by Minecraft instances belonging to the same OS user in
`~/.sky-myce/device-v1/`. It contains:

- A random installation UUID and an Ed25519 keypair.
- A SHA-256 device identifier over a product-specific label and two random
  local UUIDs, created once and retained outside Minecraft's instance folder.
- The last signed lease, retained as a boundary/audit aid, **never** used to
  authorize an offline restart.

No MAC, hardware serial, product key, hostname, machine registry identifier,
Minecraft token or Discord token participates or is uploaded. The private key
is protected with Windows DPAPI `CurrentUser`, with a product-specific entropy
label and an owner-only ACL. On POSIX it is stored under a `0700` directory in
a `0600` file. Creation is serialized with an OS file lock.

Copying the Minecraft instance does not copy this identity. Copying Windows
key ciphertext to another machine/user does not make DPAPI decrypt it. A user
who controls their host can export/spoof a software identity; POSIX permissions
do not resist the account owner. A different OS user is a different device
identity. These are explicit limits, not claims of physical hardware proof.
Deleting the identity creates a new installation that the server rejects
until the account's reset/re-enrollment policy permits it.

## Leases, revocation and outages

Public signing keys are compiled into the loader. Server replies cannot add
keys. Lease claims bind account, Discord ID, Minecraft UUID, installation,
device hash, public-key fingerprint, product/version, generation, features and
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

An unconfigured build has an invalid backend hostname and no trusted signing
key, so it fails closed. A distribution build must provide these values and a
matching Worker signing key. Public-key rotation requires distributing an
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
