package me.mycellium.skymyce.loader

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.EnumSet
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import com.google.gson.JsonParser

internal class HardwareIdentityUnavailable : IllegalStateException()
internal class InstallationIdentityConflict : IllegalStateException()

internal fun standardHardwareFormat(source: String) = if (source in listOf("linux-machine-id", "linux-dbus-machine-id")) "compact" else "uuid"

private fun normalizedHardwareIdentifier(identifier: String, format: String): String {
    val text = identifier.trim().removePrefix("\uFEFF").trim().removeSurrounding("{", "}").lowercase(java.util.Locale.ROOT)
    require(text.matches(Regex("[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
    val compact = text.replace("-", "")
    require(compact.toSet().size > 1 && format in listOf("compact", "uuid"))
    return if (format == "compact") compact else "${compact.take(8)}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.substring(20)}"
}

internal fun hardwareIdentityHash(source: String, identifier: String, format: String = standardHardwareFormat(source)): String {
    val normalized = normalizedHardwareIdentifier(identifier, format)
    return SignedFiles.sha256("Sky-Myce hardware v1\n$source\n$normalized".toByteArray(Charsets.UTF_8))
}

internal fun hardwareSources(osName: String = System.getProperty("os.name")): List<String> = when (launchPlatform(osName)) {
    "windows" -> listOf("windows-smbios", "windows-machine-guid")
    "linux" -> listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id")
    "macos" -> listOf("macos-platform")
    else -> listOf("software-installation")
}

internal fun readHardwareIdentifier(source: String, root: Path? = null): String? = runCatching {
    val paths = when (source) {
        "linux-dmi" -> listOf("/sys/class/dmi/id/product_uuid", "/sys/devices/virtual/dmi/id/product_uuid")
        "linux-machine-id" -> listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
        "linux-dbus-machine-id" -> listOf("/var/lib/dbus/machine-id", "/etc/machine-id")
        else -> emptyList()
    }
    if (paths.isNotEmpty()) {
        paths.firstNotNullOfOrNull { name -> runCatching {
            val path = root?.resolve(name.removePrefix("/")) ?: Path.of(name)
            val bytes = Files.newInputStream(path).use { it.readNBytes(4097) }
            require(bytes.size <= 4096)
            String(bytes, Charsets.UTF_8).also { normalizedHardwareIdentifier(it, standardHardwareFormat(source)) }
        }.getOrNull() }
    } else {
        val command = when (source) {
            "windows-smbios" -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "(Get-CimInstance -ClassName Win32_ComputerSystemProduct -ErrorAction Stop).UUID")
            "windows-machine-guid" -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "Get-ItemPropertyValue -LiteralPath 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name MachineGuid -ErrorAction Stop")
            "macos-platform" -> listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            else -> error("Unsupported hardware source")
        }
        val result = boundedIdentityCommand(command)
        if (source == "macos-platform") Regex("\"IOPlatformUUID\"\\s*=\\s*\"([a-fA-F0-9-]+)\"").find(result)?.groupValues?.get(1)
        else result.trim()
    }
}.getOrNull()

/** Drain stdout during the bounded wait; a full pipe must not turn a stable source into a timeout. */
private fun boundedIdentityCommand(command: List<String>, input: String? = null, maxBytes: Int = 16384): String {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(maxBytes + 1) } }
    try {
        process.outputStream.bufferedWriter().use { if (input != null) { it.write(input); it.newLine() } }
        check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Identity source unavailable" }
        val bytes = output.get(1, TimeUnit.SECONDS)
        require(bytes.size <= maxBytes)
        return String(bytes, Charsets.UTF_8).trim()
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close(); output.cancel(true)
    }
}

/** A historical signed lease chooses only the enrolled text encoding, never launch authorization. */
internal fun signedHardwareReference(token: String, config: Properties, id: String, deviceHash: String, publicKey: String): String? = runCatching {
    require(token.length <= 8192)
    val parts = token.split('.').also { require(it.size == 3) }
    val decode = Base64.getUrlDecoder()
    val header = JsonParser.parseString(String(decode.decode(parts[0]), Charsets.UTF_8)).asJsonObject
    require(header["alg"].asString == "EdDSA" && header["typ"].asString == "JWT")
    SignedFiles.verify(SignedFiles.key(config, header["kid"].asString), "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8), parts[2])
    val claims = JsonParser.parseString(String(decode.decode(parts[1]), Charsets.UTF_8)).asJsonObject
    require(claims["product"].asString == "sky-myce" && claims["protocol"].asInt == 3 && claims["installationId"].asString == id &&
        claims["deviceHash"].asString == deviceHash && claims["keyFingerprint"].asString == SignedFiles.sha256(Base64.getDecoder().decode(publicKey)))
    claims["hardwareHash"].asString.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
}.getOrNull()

internal fun restrictIdentityPath(path: Path) {
    val posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
    if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"))
    else {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: error("Private key permissions unavailable")
        acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
    }
}

/** Shared per OS user, outside the game instance; runtime/session state never changes this identity. */
class Installation internal constructor(
    private val directory: Path = defaultInstallationDirectory(),
    private val readHardware: (String) -> String? = { readHardwareIdentifier(it) },
    private val sources: List<String> = hardwareSources(),
    private val diagnostic: (String) -> Unit = { java.util.logging.Logger.getLogger("Sky-Myce-Loader").info(it) }
) {
    val id: String
    val publicKey: String
    val deviceHash: String
    val hardwareHash: String
    private val privateKey: PrivateKey
    init {
        Files.createDirectories(directory)
        restrictIdentityPath(directory)
        // Process lock serializes first use by multiple Minecraft instances.
        java.nio.channels.FileChannel.open(directory.resolve("identity.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val file = directory.resolve("identity.properties")
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    diagnostic("Device identity: creating persistent installation outside the Minecraft instance")
                    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    val properties = Properties().apply {
                        setProperty("id", UUID.randomUUID().toString())
                        setProperty("publicKey", Base64.getEncoder().encodeToString(pair.public.encoded))
                        setProperty("privateKey", protect(pair.private.encoded, false))
                        setProperty("deviceHash", SignedFiles.sha256("Sky-Myce device v1\n${UUID.randomUUID()}\n${UUID.randomUUID()}".toByteArray()))
                    }
                    val bytes = java.io.ByteArrayOutputStream().also { properties.store(it, "Sky-Myce local installation; never share") }.toByteArray()
                    SignedFiles.atomicWrite(file, bytes)
                    restrictIdentityPath(file)
                }
                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Installation identity is not a regular file" }
                val properties = Properties().apply { Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val bytes = input.readNBytes(16385); require(bytes.size <= 16384); load(bytes.inputStream())
                } }
                id = properties.getProperty("id").also { UUID.fromString(it) }
                publicKey = properties.getProperty("publicKey")
                deviceHash = properties.getProperty("deviceHash").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val encoded = Base64.getDecoder().decode(properties.getProperty("privateKey"))
                privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(
                    if (isWindows()) Base64.getDecoder().decode(protect(encoded, true)) else encoded))
                val storedSource = properties.getProperty("hardwareSource")
                val knownSources = listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id", "windows-smbios", "windows-machine-guid", "macos-platform", "software-installation")
                val reference = properties.getProperty("hardwareReference")?.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    ?: runCatching {
                        val lease = Files.newInputStream(directory.resolve("last-signed-lease")).use { it.readNBytes(8193) }
                        require(lease.size <= 8192)
                        signedHardwareReference(String(lease, Charsets.UTF_8), SignedFiles.configuration(), id, deviceHash, publicKey)
                    }.getOrNull()
                var selectedFormat: String? = null
                val selected = (storedSource?.let(::listOf) ?: sources).firstNotNullOfOrNull { source ->
                    if (source !in knownSources) { diagnostic("Hardware source: unsupported saved source; identity retained"); null }
                    else if (source == "software-installation") {
                        if (storedSource == null && reference != null && reference != deviceHash) null else source to deviceHash
                    }
                    else {
                        val identifier = runCatching { readHardware(source) }.getOrNull()
                        if (identifier == null) { diagnostic("Hardware source $source: unavailable; no replacement identity generated"); null }
                        else runCatching {
                            val format = properties.getProperty("hardwareFormat") ?: reference?.let { hash ->
                                listOf("uuid", "compact").firstOrNull { hardwareIdentityHash(source, identifier, it) == hash }
                            } ?: standardHardwareFormat(source)
                            val hash = hardwareIdentityHash(source, identifier, format)
                            if (storedSource == null && reference != null && hash != reference) {
                                diagnostic("Hardware source $source: valid but differs from historical enrollment; checking remaining sources")
                                null
                            } else {
                                selectedFormat = format
                                diagnostic("Hardware source $source: live identifier validated, encoding=$format")
                                source to hash
                            }
                        }.getOrElse { diagnostic("Hardware source $source: invalid identifier or encoding; identity retained"); null }
                    }
                } ?: throw HardwareIdentityUnavailable()
                hardwareHash = selected.second
                diagnostic("Device identity: ${if (reference == null) "hardware baseline recorded" else if (reference == hardwareHash) "live hardware matches saved baseline" else "live hardware differs from saved baseline; server binding preserved"}")
                if (storedSource == null || properties.getProperty("hardwareFormat") == null && selectedFormat != null || properties.getProperty("hardwareReference") == null) {
                    // Upgrade the proof without changing the already-enrolled installation or device binding.
                    properties.setProperty("hardwareSource", selected.first)
                    selectedFormat?.let { properties.setProperty("hardwareFormat", it) }
                    properties.setProperty("hardwareReference", reference ?: hardwareHash)
                    val bytes = java.io.ByteArrayOutputStream().also { properties.store(it, "Sky-Myce local installation; never share") }.toByteArray()
                    SignedFiles.atomicWrite(file, bytes)
                }
                restrictIdentityPath(file)
            }
        }
    }
    fun sign(text: String): String = Signature.getInstance("Ed25519").run {
        initSign(privateKey); update(text.toByteArray(Charsets.UTF_8)); Base64.getEncoder().encodeToString(sign())
    }
    fun persistBoundary(lease: String) = SignedFiles.atomicWrite(directory.resolve("last-signed-lease"), lease.toByteArray())
    private fun isWindows() = launchPlatform(System.getProperty("os.name")) == "windows"
    private fun protect(bytes: ByteArray, decrypt: Boolean): String {
        if (!isWindows()) return Base64.getEncoder().encodeToString(bytes)
        val method = if (decrypt) "Unprotect" else "Protect"
        // DPAPI binds ciphertext to this Windows user/machine; input is stdin, never an argument/log.
        val command = "Add-Type -AssemblyName System.Security; " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$method([Convert]::FromBase64String([Console]::ReadLine()), [Text.Encoding]::UTF8.GetBytes('Sky-Myce device v1'), [Security.Cryptography.DataProtectionScope]::CurrentUser))"
        return boundedIdentityCommand(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command),
            Base64.getEncoder().encodeToString(bytes), 8192).also { check(it.isNotBlank()) }
    }
}
