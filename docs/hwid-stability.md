# HWID stability verification

The loader continues to read a machine identifier on every launch and sign it
with the persistent installation key. The server remains authoritative for
the enrolled hardware, license, account and launch session. No automatic HWID
reset, cached-hash authorization, relaxed signature check or relay change is
part of this fix.

## Demonstrated instability paths fixed

- Equivalent UUID output previously produced different hashes when one probe
  returned 32 hexadecimal digits and another returned a hyphenated UUID.
  Normalization now handles these forms, case, braces, BOM and whitespace.
  Normal existing v1 hashes retain their source label and encoding. Existing
  installations recover a historical encoding from a pinned, signed lease
  when available; an expired lease supplies no authorization.
- Hardware platform selection now uses the same case-insensitive classifier
  as launch authorization. A differently capitalized Linux OS name cannot
  accidentally select the software-only provider.
- Linux sources accept equivalent standard locations, including both DMI
  sysfs paths and the system/dbus machine-ID locations. A registered source
  remains pinned when a more preferred source later becomes readable. Missing
  source metadata is recovered against the historical reference; a mismatch
  fails rather than silently enrolling another source.
- Launcher overrides of JVM `user.home` no longer create another installation
  when the OS user's home is available. Legacy records are retained and copied
  byte-for-byte under locks. Different existing identities are preserved and
  reported as a conflict.
- Identity writes force contents before atomic replacement and synchronize
  the directory where supported. Existing identity records are bounded and
  must be regular files; malformed records are never replaced with new keys.

The audit found no process ID, boot ID, timestamp, network interface or random
launch session in the hardware hash. Random installation references are
created once, saved, and retained. No original failure log was supplied, so
these are demonstrated code paths, not a claim to have reproduced a specific
user's reboot failure.

## Automated checks

Use Java 25:

```powershell
.\gradlew.bat loaderCheck
.\gradlew.bat check build
```

`loaderCheck` uses isolated synthetic identities. It checks all six native
provider policies, fixed pre-change hashes, equivalent formats, changed
hardware, unavailable/malformed sources, source order, migration, signatures,
denied authorization and privacy. Ubuntu, Debian, Fedora and Arch-style file
layouts are deterministic fixtures; these do not constitute actual boots of
those distributions. POSIX permissions and symlink checks run on POSIX.

The opt-in native diagnostic uses the real hardware provider with a separate
test installation, never the user's enrolled Sky-Myce directory:

```powershell
.\gradlew.bat hardwareIdentityCheck -PhardwareIdentityCheckDirectory=D:/Code/Sky-Myce/build/native-hwid-windows
```

Or use its standalone JAR with Java 25 on Linux or Windows:

```bash
java -jar Sky-Myce-Hardware-Identity-Check.jar --directory /absolute/path/to/private-test-identity
```

Keep that test directory between runs. The check starts independent JVMs,
compares hardware and installation identity, verifies the persistent original
baseline, and requires new unauthorized launch sessions. Its output contains
source names and pass/fail results, never hardware IDs, hashes or keys. The
directory contains private test keys and must not be shared. Do not select the
real `.sky-myce/device-v1` directory.

## Manual reboot and Minecraft checks

1. Run the native diagnostic and retain its private test directory.
2. Repeat after closing/reopening the launcher and after a normal reboot and
   shutdown/startup, using the same directory. Every run must pass against
   the original baseline, not just compare two processes after the reboot.
3. With the newly built loader in a configured Minecraft instance, launch,
   authorize, close and relaunch. Confirm the live source matches the saved
   baseline and the server accepts the existing enrollment. Repeat after a
   crash/relaunch and OS reboot. A new launch session still requires normal
   server authorization; it must not require an HWID reset.
4. Repeat on supported Linux systems where available. A distribution's name
   is never hashed. Reinstalling an OS can change a machine-ID fallback;
   firmware changes and cloned/ephemeral VMs can also change identifiers.
   A genuinely different live identifier must continue to be rejected.

Only Ubuntu WSL is available locally for native Linux testing. Physical
reboots, actual Minecraft authorization and native boots of other Linux
distributions remain manual checks. The [systemd machine-ID documentation](https://www.freedesktop.org/software/systemd/man/latest/machine-id.html)
describes the OS-installation lifetime of its fallback identifier; firmware
UUIDs are described in [Microsoft's SMBIOS provider documentation](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-computersystemproduct).

## Local results (2026-10-07)

| Check | Result |
| --- | --- |
| Java 25 `check build` | Passed, including loader signatures, denied/replayed authorization and all existing mod checks |
| Native Windows fresh JVMs | Passed using `windows-smbios`, retained DPAPI key and original hardware baseline |
| Native Ubuntu 22.04.5 WSL fresh JVMs | Passed using `linux-machine-id`, retained key, original baseline and private POSIX permissions |
| Linux hardware and home-directory fixture runner | Passed the repository's same Kotlin fixtures on actual POSIX, including aliases, broken symlinks, conflicting records and retained migrations |
| Debian, Fedora and Arch identifier layouts | Passed as fixtures; those distributions are not installed locally |
| Physical reboot/shutdown and Minecraft launch | Pending manual verification |

The Linux run uses a private `/tmp` test directory for the current launch
checks. For an actual reboot test choose a persistent private directory; many
Linux systems clear `/tmp` at boot. Windows native probes require normal host
CIM/DPAPI access; the restricted tool sandbox can make them unavailable, which
correctly fails without replacing the test identity. No production record or
server binding was modified, and these builds have not been published.
