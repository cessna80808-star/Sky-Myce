package me.mycellium.skymyce.loader

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Synthetic fixtures only: no real user records, hardware providers or credentials. */
internal fun checkInstallationDirectory(root: Path) {
    Files.createDirectories(root)
    val fixtureId = "7498a1b2-e4a2-4efa-a6c8-d9c57e14234c"
    val fixturePublicKey = "synthetic-public-key"
    val fixtureHash = "ab".repeat(32)
    fun home(name: String) = Files.createDirectories(root.resolve(name)).toAbsolutePath()
    fun record(home: Path) = home.resolve(".sky-myce/device-v1/identity.properties")
    fun bytes(id: String = fixtureId, publicKey: String = fixturePublicKey, hash: String = fixtureHash, note: String = "fixture") =
        "# $note\r\nid=$id\r\npublicKey=$publicKey\r\nprivateKey=synthetic-private-value\r\ndeviceHash=$hash\r\nhardwareSource=linux-machine-id\r\n".toByteArray()
    fun put(home: Path, value: ByteArray = bytes()) {
        Files.createDirectories(record(home).parent); Files.write(record(home), value)
    }
    val javaHome = home("launcher-overridden-home")
    val osHome = home("persistent-os-home")
    val original = bytes(note = "preserve these exact bytes")
    put(javaHome, original)
    val lease = "synthetic-signed-lease-boundary".toByteArray()
    Files.write(record(javaHome).parent.resolve("last-signed-lease"), lease)
    val logs = ArrayList<String>()
    for (platform in listOf("windows", "linux", "macos", "other")) {
        val selected = resolveInstallationDirectory(javaHome.toString(), osHome.toString(), platform, logs::add)
        check(selected == record(osHome).parent)
        check(Files.readAllBytes(record(osHome)).contentEquals(original))
        check(Files.readAllBytes(record(javaHome)).contentEquals(original))
        check(Files.readAllBytes(selected.resolve("last-signed-lease")).contentEquals(lease))
    }
    val anotherJavaHome = home("different-launcher-home")
    check(resolveInstallationDirectory(anotherJavaHome.toString(), osHome.toString(), "linux") == record(osHome).parent)
    check(!Files.exists(record(anotherJavaHome)))
    check(resolveInstallationDirectory("not-an-absolute-java-home", osHome.toString(), "linux") == record(osHome).parent)
    for (missing in listOf(null, "relative-home", root.resolve("missing-home").toString())) {
        check(resolveInstallationDirectory(javaHome.toString(), missing, "linux") == record(javaHome).parent)
    }
    check(runCatching { resolveInstallationDirectory("relative-home", null, "linux") }.exceptionOrNull() is IOException)
    check(logs.none { fixtureId in it || fixturePublicKey in it || fixtureHash in it || "synthetic-private" in it || javaHome.toString() in it || osHome.toString() in it || String(lease) in it })

    // Matching identity metadata may differ; neither record is overwritten to "repair" it.
    val matchingLegacy = home("matching-legacy")
    val matchingTarget = home("matching-target")
    put(matchingLegacy, bytes(note = "legacy metadata")); put(matchingTarget, bytes(note = "current metadata"))
    val current = Files.readAllBytes(record(matchingTarget))
    resolveInstallationDirectory(matchingLegacy.toString(), matchingTarget.toString(), "windows")
    check(Files.readAllBytes(record(matchingTarget)).contentEquals(current))

    for ((name, replacement) in listOf(
        "id" to bytes(id = "c9ee66de-7c16-4d2b-bcf7-479ba29ec9d3"),
        "key" to bytes(publicKey = "different-synthetic-public-key"),
        "device" to bytes(hash = "cd".repeat(32)),
    )) {
        val old = home("conflict-$name-legacy"); val destination = home("conflict-$name-target")
        put(old); put(destination, replacement)
        val beforeOld = Files.readAllBytes(record(old)); val beforeTarget = Files.readAllBytes(record(destination))
        check(runCatching { resolveInstallationDirectory(old.toString(), destination.toString(), "linux") }.exceptionOrNull() is InstallationIdentityConflict)
        check(Files.readAllBytes(record(old)).contentEquals(beforeOld) && Files.readAllBytes(record(destination)).contentEquals(beforeTarget))
    }
    for ((name, invalid) in listOf("oversized" to ByteArray(16385) { 'x'.code.toByte() }, "incomplete" to "id=$fixtureId\n".toByteArray())) {
        val old = home("invalid-$name-legacy"); val destination = home("invalid-$name-target")
        put(old, invalid)
        check(runCatching { resolveInstallationDirectory(old.toString(), destination.toString(), "linux") }.isFailure)
        check(!Files.exists(record(destination)) && Files.readAllBytes(record(old)).contentEquals(invalid))
    }
    val oversizedLeaseHome = home("oversized-lease-legacy"); val oversizedLeaseTarget = home("oversized-lease-target")
    put(oversizedLeaseHome)
    Files.write(record(oversizedLeaseHome).parent.resolve("last-signed-lease"), ByteArray(8193) { 'x'.code.toByte() })
    check(runCatching { resolveInstallationDirectory(oversizedLeaseHome.toString(), oversizedLeaseTarget.toString(), "linux") }.exceptionOrNull() is IOException)
    check(!Files.exists(record(oversizedLeaseTarget)))

    if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
        check(PosixFilePermissions.toString(Files.getPosixFilePermissions(record(osHome).parent)) == "rwx------")
        check(PosixFilePermissions.toString(Files.getPosixFilePermissions(record(osHome))) == "rw-------")
        check(PosixFilePermissions.toString(Files.getPosixFilePermissions(record(osHome).parent.resolve("last-signed-lease"))) == "rw-------")
        val aliasHome = home("symlinked-launcher-home")
        Files.createDirectories(aliasHome.resolve(".sky-myce"))
        Files.createSymbolicLink(record(aliasHome).parent, record(osHome).parent)
        check(resolveInstallationDirectory(aliasHome.toString(), osHome.toString(), "linux") == record(osHome).parent)
        val brokenHome = home("broken-record-home"); val brokenTarget = home("broken-record-target")
        Files.createDirectories(record(brokenHome).parent)
        Files.createSymbolicLink(record(brokenHome), root.resolve("missing-record"))
        check(runCatching { resolveInstallationDirectory(brokenHome.toString(), brokenTarget.toString(), "linux") }.exceptionOrNull() is IOException)
        check(!Files.exists(record(brokenTarget)))
    }
    println("Installation directory checks passed: stable OS homes, retained byte-identical migration, bounded records, conflicts and private diagnostics")
}
