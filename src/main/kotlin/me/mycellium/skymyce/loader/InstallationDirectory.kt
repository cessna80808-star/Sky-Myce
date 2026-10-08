package me.mycellium.skymyce.loader

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.UUID

private val directoryResolutionLock = Any()
private const val IDENTITY_LIMIT = 16384
private const val LEASE_LIMIT = 8192

internal fun defaultInstallationDirectory(): Path {
    val platform = launchPlatform(System.getProperty("os.name"))
    return resolveInstallationDirectory(System.getProperty("user.home").orEmpty(),
        System.getenv(if (platform == "windows") "USERPROFILE" else "HOME"), platform) {
        java.util.logging.Logger.getLogger("Sky-Myce-Loader").info(it)
    }
}

/** Launcher JVM overrides must not make an existing OS user's registration disappear. */
internal fun resolveInstallationDirectory(
    javaHome: String,
    osHome: String?,
    platform: String,
    diagnostic: (String) -> Unit = {},
): Path = synchronized(directoryResolutionLock) {
    require(platform in setOf("windows", "linux", "macos", "other"))
    fun home(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let { runCatching {
        val path = Path.of(it)
        require(path.isAbsolute && Files.isDirectory(path))
        path.toRealPath()
    }.getOrNull() }
    val legacyHome = home(javaHome)
    val stableHome = home(osHome)
    val selected = stableHome ?: legacyHome ?: throw IOException("Persistent user directory unavailable")
    diagnostic(if (stableHome != null) "Identity directory: stable OS user home" else "Identity directory: JVM home fallback; OS user home unavailable")
    val target = selected.resolve(".sky-myce/device-v1")
    val legacy = legacyHome?.resolve(".sky-myce/device-v1")
    if (legacy == null || legacy == target || !identityFileExists(legacy.resolve("identity.properties"))) return@synchronized target
    if (Files.exists(target) && Files.isSameFile(target, legacy)) return@synchronized target

    Files.createDirectories(target)
    restrictIdentityPath(target)
    restrictIdentityPath(legacy)
    // The same order prevents two processes migrating in opposite directions from deadlocking.
    val directories = listOf(target, legacy).distinct().sortedBy { it.toString() }
    fun migrate() {
        val original = legacy.resolve("identity.properties")
        val destination = target.resolve("identity.properties")
        if (!identityFileExists(original)) return
        val originalBytes = boundedIdentityBytes(original, IDENTITY_LIMIT)
        val originalIdentity = identityReference(originalBytes)
        val destinationExists = identityFileExists(destination)
        if (destinationExists && identityReference(boundedIdentityBytes(destination, IDENTITY_LIMIT)) != originalIdentity) {
            diagnostic("Identity migration stopped: conflicting saved installation identities; both preserved")
            throw InstallationIdentityConflict()
        }
        val originalLease = legacy.resolve("last-signed-lease")
        val destinationLease = target.resolve("last-signed-lease")
        val leaseBytes = if (!identityFileExists(destinationLease) && identityFileExists(originalLease))
            boundedIdentityBytes(originalLease, LEASE_LIMIT) else null
        if (!destinationExists) {
            SignedFiles.atomicWrite(destination, originalBytes)
            diagnostic("Identity migration: retained the existing installation; original preserved")
        } else diagnostic("Identity migration: matching existing installation retained")
        restrictIdentityPath(destination)
        if (leaseBytes != null) {
            SignedFiles.atomicWrite(destinationLease, leaseBytes)
            restrictIdentityPath(destinationLease)
        }
    }
    FileChannel.open(directories[0].resolve("identity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { first ->
        first.lock().use {
            FileChannel.open(directories[1].resolve("identity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { second ->
                second.lock().use { migrate() }
            }
        }
    }
    target
}

private fun identityFileExists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

private fun boundedIdentityBytes(path: Path, limit: Int): ByteArray {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Local identity record is not a regular file")
    val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
    if (bytes.isEmpty() || bytes.size > limit) throw IOException("Local identity record has an invalid size")
    return bytes
}

/** Compare only the enrollment identity; metadata upgrades must not replace its keys. */
private fun identityReference(bytes: ByteArray): List<String> {
    val values = Properties().apply { bytes.inputStream().use(::load) }
    fun field(key: String) = values.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw IOException("Local installation identity is incomplete")
    val id = field("id")
    if (runCatching { UUID.fromString(id) }.isFailure || !field("deviceHash").matches(Regex("[a-f0-9]{64}")))
        throw IOException("Local installation identity is invalid")
    field("privateKey")
    return listOf(id, field("publicKey"), field("deviceHash"))
}
