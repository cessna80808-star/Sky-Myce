package me.mycellium.skymyce.loader

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal fun checkInstallation(root: Path) {
    val identifier = "36f73d82-5cb3-4f78-915b-6f4e67497031"
    val otherIdentifier = "04cd8e36-8d2a-4e01-a655-2b629793ed16"
    check(hardwareIdentityHash("linux-dmi", identifier) == hardwareIdentityHash("linux-dmi", "  ${identifier.uppercase()}\n"))
    check(hardwareIdentityHash("linux-dmi", identifier) != hardwareIdentityHash("linux-dmi", otherIdentifier))
    for (invalid in listOf("", "0".repeat(32), "f".repeat(32), "boot-id", "current user")) {
        check(runCatching { hardwareIdentityHash("linux-dmi", invalid) }.isFailure)
    }
    for (source in listOf("linux-dmi", "windows-smbios", "macos-platform", "linux-machine-id")) {
        val directory = root.resolve(source)
        fun load(value: String = identifier) = Installation(directory, { value }, listOf(source))
        val first = load()
        val before = Files.readAllBytes(directory.resolve("identity.properties"))
        // Runtime lifecycle, software versions and network settings never enter the identity calculation.
        for (event in listOf("game relaunch", "loader relaunch", "OS reboot", "shutdown/start", "suspend/resume", "logout/login",
            "Minecraft/Fabric update", "Sky-Myce/loader update", "Java update", "network/IP/VPN change", "WebSocket reconnect", "lease renewal")) {
            val next = load()
            check(first.id == next.id && first.publicKey == next.publicKey && first.deviceHash == next.deviceHash && first.hardwareHash == next.hardwareHash) { event }
            check(first.sign(event) == next.sign(event))
        }
        check(Files.readAllBytes(directory.resolve("identity.properties")).contentEquals(before))
        val differentMachine = load(otherIdentifier)
        check(differentMachine.deviceHash == first.deviceHash && differentMachine.hardwareHash != first.hardwareHash)
        check(Files.readAllBytes(directory.resolve("identity.properties")).contentEquals(before)) // Mismatch never replaces the binding.
        check(runCatching { Installation(directory, { null }, listOf("other-source")) }.exceptionOrNull() is HardwareIdentityUnavailable)
        check(Files.readAllBytes(directory.resolve("identity.properties")).contentEquals(before)) // Transient source loss never selects another identity.

        val properties = Properties().apply { Files.newInputStream(directory.resolve("identity.properties")).use(::load) }
        properties.remove("hardwareSource") // Existing v1 identity upgraded in place, without consuming a transfer.
        Files.newOutputStream(directory.resolve("identity.properties")).use { properties.store(it, "legacy identity") }
        val upgraded = load()
        check(first.id == upgraded.id && first.publicKey == upgraded.publicKey && first.deviceHash == upgraded.deviceHash && first.hardwareHash == upgraded.hardwareHash)
        check(first.sign("migration") == upgraded.sign("migration"))
    }
    val fallback = Installation(root.resolve("source-fallback"), { if (it == "linux-machine-id") identifier else null }, listOf("linux-dmi", "linux-machine-id"))
    val retained = Installation(root.resolve("source-fallback"), { if (it == "linux-dmi") otherIdentifier else identifier }, listOf("linux-dmi", "linux-machine-id"))
    check(fallback.hardwareHash == retained.hardwareHash) // A newly readable preferred source does not replace the enrolled source.
    val unsupported = Installation(root.resolve("unsupported"), { error("No hardware commands on unsupported platforms") }, listOf("software-installation"))
    check(unsupported.hardwareHash == Installation(root.resolve("unsupported"), { null }, listOf("software-installation")).hardwareHash)
    println("Installation checks passed: persistent Linux/Windows/macOS identity, lifecycle independence, source pinning, migration and fail-closed hardware access")
}
