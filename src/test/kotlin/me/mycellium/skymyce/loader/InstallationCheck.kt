package me.mycellium.skymyce.loader

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal const val FIXTURE_HARDWARE_UUID = "36f73d82-5cb3-4f78-915b-6f4e67497031"
private const val OTHER_HARDWARE_UUID = "04cd8e36-8d2a-4e01-a655-2b629793ed16"

/** Shared by the parent/child JVM checks; never invokes a real hardware probe. */
internal fun fixtureInstallation(directory: Path, identifier: String = FIXTURE_HARDWARE_UUID) =
    Installation(directory, { identifier }, listOf("linux-dmi"), {})

internal fun checkInstallation(root: Path) {
    val compact = FIXTURE_HARDWARE_UUID.replace("-", "")
    val variants = listOf(FIXTURE_HARDWARE_UUID, compact, "  ${FIXTURE_HARDWARE_UUID.uppercase()}\r\n",
        "{${compact.uppercase()}}", "\uFEFF$FIXTURE_HARDWARE_UUID\n")
    // These are the original v1 hashes for the providers' normal output, not hashes calculated by the new normalizer.
    val legacyHashes = mapOf(
        "linux-dmi" to "ffb3ae743c4f4b94b589020ea90c77450e737bdb6fbf59683c0c9e6aed6eac37",
        "windows-smbios" to "67b51266ba72c04645ed9e63935ef0e9fb9854a43ef7f21afdc6af6d15d229cb",
        "windows-machine-guid" to "81ba7c79cddeb1b9c7e1bad6fb3b66dcf03b59e3a6e7fb22be34ac0856245b35",
        "macos-platform" to "0907a634b0a51286968d7e3a2d9aefe261d9398607086c16ba3232b4aaee4ce7",
        "linux-machine-id" to "aa567167b1b1e950f05c077a2c3c4aa2823fc34af04fa6d1e93fbeecbd37e49e",
        "linux-dbus-machine-id" to "40dc8a58d6f8b64ce63a1c30c004b57bc4808cb1e570f53141ce67d773938770",
    )
    for ((source, legacyHash) in legacyHashes) {
        check(variants.all { hardwareIdentityHash(source, it) == legacyHash }) { "Equivalent formats changed $source identity" }
        check(hardwareIdentityHash(source, OTHER_HARDWARE_UUID) != legacyHash)
        for (invalid in listOf("", "0".repeat(32), "f".repeat(32), "boot-id", "current user", "$compact\n$compact",
            "36f73d825-cb3-4f78-915b-6f4e67497031", "{$FIXTURE_HARDWARE_UUID", "g" + compact.drop(1))) {
            check(runCatching { hardwareIdentityHash(source, invalid) }.isFailure) { "Invalid $source identity accepted" }
        }

        val directory = root.resolve(source)
        val diagnostic = mutableListOf<String>()
        fun load(value: String = FIXTURE_HARDWARE_UUID) = Installation(directory, { value }, listOf(source), diagnostic::add)
        val first = load()
        check(first.hardwareHash == legacyHash)
        val file = directory.resolve("identity.properties")
        val before = Files.readAllBytes(file)
        // Alternate real provider representations through game/loader restart, reboot and software/network changes.
        for ((event, value) in listOf("game restart", "loader restart", "OS reboot", "shutdown/start", "Java/network change").zip(variants)) {
            val next = load(value)
            check(first.id == next.id && first.publicKey == next.publicKey && first.deviceHash == next.deviceHash && first.hardwareHash == next.hardwareHash) { event }
            check(first.sign(event) == next.sign(event))
        }
        check(Files.readAllBytes(file).contentEquals(before))
        val differentMachine = load(OTHER_HARDWARE_UUID)
        check(differentMachine.deviceHash == first.deviceHash && differentMachine.hardwareHash != first.hardwareHash)
        check(Files.readAllBytes(file).contentEquals(before)) // Fresh hardware is never replaced with the saved reference.
        for (reader in listOf<(String) -> String?>({ null }, { error("probe failed: $FIXTURE_HARDWARE_UUID") })) {
            check(runCatching { Installation(directory, reader, listOf("other-source"), diagnostic::add) }.exceptionOrNull() is HardwareIdentityUnavailable)
            check(Files.readAllBytes(file).contentEquals(before)) // Transient source loss never picks another binding.
        }
        check(diagnostic.isNotEmpty())
        check(diagnostic.none { line -> listOf(FIXTURE_HARDWARE_UUID, compact, OTHER_HARDWARE_UUID,
            OTHER_HARDWARE_UUID.replace("-", "")).any { line.contains(it, ignoreCase = true) } })

        val properties = Properties().apply { Files.newInputStream(file).use(::load) }
        listOf("hardwareSource", "hardwareFormat", "hardwareReference").forEach(properties::remove)
        Files.newOutputStream(file).use { properties.store(it, "legacy identity") }
        val upgraded = load(compact)
        check(first.id == upgraded.id && first.publicKey == upgraded.publicKey && first.deviceHash == upgraded.deviceHash && first.hardwareHash == upgraded.hardwareHash)
        check(first.sign("migration") == upgraded.sign("migration"))

        // Existing nonstandard v1 encoding is retained when its prior reference proves the representation.
        val retainedFormat = if (standardHardwareFormat(source) == "uuid") "compact" else "uuid"
        val retainedHash = hardwareIdentityHash(source, FIXTURE_HARDWARE_UUID, retainedFormat)
        properties.setProperty("hardwareSource", source)
        properties.remove("hardwareFormat")
        properties.setProperty("hardwareReference", retainedHash)
        Files.newOutputStream(file).use { properties.store(it, "prior hardware encoding") }
        check(load(variants.last()).hardwareHash == retainedHash)
        val migrated = Properties().apply { Files.newInputStream(file).use(::load) }
        check(migrated.getProperty("hardwareFormat") == retainedFormat && migrated.getProperty("hardwareReference") == retainedHash)
    }

    check(hardwareSources("LINUX") == listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id"))
    check(hardwareSources("windows 11") == listOf("windows-smbios", "windows-machine-guid"))
    check(hardwareSources("Darwin") == listOf("macos-platform"))
    val fallbackDirectory = root.resolve("source-fallback")
    val fallback = Installation(fallbackDirectory, { if (it == "linux-machine-id") compact else null }, hardwareSources("Linux"), {})
    val queried = mutableListOf<String>()
    val retained = Installation(fallbackDirectory, { queried += it; if (it == "linux-dmi") OTHER_HARDWARE_UUID else FIXTURE_HARDWARE_UUID }, hardwareSources("Linux").reversed(), {})
    check(fallback.hardwareHash == retained.hardwareHash && queried == listOf("linux-machine-id"))
    val unsupported = Installation(root.resolve("unsupported"), { error("No hardware probes on unsupported platforms") }, listOf("software-installation"), {})
    check(unsupported.hardwareHash == Installation(root.resolve("unsupported"), { null }, listOf("software-installation"), {}).hardwareHash)
    checkReferenceSourceMigration(root.resolve("reference-source"))
    if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
        val directory = Files.createDirectories(root.resolve("broken-record"))
        val file = directory.resolve("identity.properties")
        val missing = Path.of("missing-identity.properties")
        Files.createSymbolicLink(file, missing)
        check(runCatching { fixtureInstallation(directory) }.isFailure)
        check(Files.isSymbolicLink(file) && Files.readSymbolicLink(file) == missing)
        check(!Files.exists(directory.resolve(missing))) // A broken saved identity is never replaced with a new enrollment.
    }
    checkLinuxIdentifierFiles(root.resolve("linux-files"), compact)
    println("Installation checks passed: six stable providers, legacy hashes, equivalent formats, distro file fixtures, source pinning and private fail-closed diagnostics")
}

private fun checkReferenceSourceMigration(directory: Path) {
    val first = Installation(directory, { if (it == "linux-machine-id") FIXTURE_HARDWARE_UUID else null }, hardwareSources("Linux"), {})
    val file = directory.resolve("identity.properties")
    fun forgetSource() {
        val properties = Properties().apply { Files.newInputStream(file).use(::load) }
        properties.remove("hardwareSource")
        properties.remove("hardwareFormat")
        Files.newOutputStream(file).use { properties.store(it, "prior hardware reference without source metadata") }
    }
    forgetSource()
    val queried = mutableListOf<String>()
    val migrated = Installation(directory, { source ->
        queried += source
        if (source == "linux-dmi") OTHER_HARDWARE_UUID else FIXTURE_HARDWARE_UUID
    }, hardwareSources("Linux"), {})
    check("linux-dmi" in queried && "linux-machine-id" in queried)
    check(migrated.hardwareHash == first.hardwareHash && migrated.id == first.id && migrated.publicKey == first.publicKey && migrated.deviceHash == first.deviceHash)
    check(migrated.sign("reference source migration") == first.sign("reference source migration"))
    val properties = Properties().apply { Files.newInputStream(file).use(::load) }
    check(properties.getProperty("hardwareSource") == "linux-machine-id" && properties.getProperty("hardwareReference") == first.hardwareHash)

    forgetSource()
    val before = Files.readAllBytes(file)
    check(runCatching { Installation(directory, { OTHER_HARDWARE_UUID }, hardwareSources("Linux"), {}) }.exceptionOrNull() is HardwareIdentityUnavailable)
    check(Files.readAllBytes(file).contentEquals(before)) // Unknown source and changed hardware cannot persist a new binding.
}

private fun checkLinuxIdentifierFiles(root: Path, compact: String) {
    fun write(directory: Path, path: String, value: String) {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }
    // Synthetic Linux layouts exercise distro-independent files, including machine-id aliases without readable DMI.
    for ((distro, value) in listOf("Ubuntu" to "$compact\n", "Debian" to compact.uppercase(),
        "Fedora" to "\uFEFF$compact\n", "Arch" to " $compact\r\n")) {
        val directory = root.resolve(distro)
        write(directory, "etc/machine-id", value)
        check(readHardwareIdentifier("linux-dmi", directory) == null)
        for (source in listOf("linux-machine-id", "linux-dbus-machine-id")) {
            check(hardwareIdentityHash(source, requireNotNull(readHardwareIdentifier(source, directory))) == hardwareIdentityHash(source, compact))
        }
    }
    val dbusOnly = root.resolve("dbus-only")
    write(dbusOnly, "var/lib/dbus/machine-id", compact)
    write(dbusOnly, "etc/machine-id", "uninitialized\n")
    check(readHardwareIdentifier("linux-machine-id", dbusOnly) == compact)
    val dmiAlias = root.resolve("dmi-alias")
    write(dmiAlias, "sys/devices/virtual/dmi/id/product_uuid", FIXTURE_HARDWARE_UUID)
    write(dmiAlias, "sys/class/dmi/id/product_uuid", "0".repeat(32))
    check(readHardwareIdentifier("linux-dmi", dmiAlias) == FIXTURE_HARDWARE_UUID)
    val bounded = root.resolve("bounded")
    write(bounded, "etc/machine-id", "a".repeat(4097))
    check(readHardwareIdentifier("linux-machine-id", bounded) == null)
    Files.delete(bounded.resolve("etc/machine-id"))
    Files.createDirectory(bounded.resolve("etc/machine-id"))
    check(readHardwareIdentifier("linux-machine-id", bounded) == null)
}
