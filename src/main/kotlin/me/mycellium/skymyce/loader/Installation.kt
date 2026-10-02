package me.mycellium.skymyce.loader

import java.nio.file.Files
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

internal class HardwareIdentityUnavailable : IllegalStateException()

internal fun hardwareIdentityHash(source: String, identifier: String): String {
    val normalized = identifier.trim().lowercase(java.util.Locale.ROOT)
    require(normalized.matches(Regex("[a-f0-9-]{32,36}")) && normalized.replace("-", "").let { it.length == 32 && it.toSet().size > 1 })
    return SignedFiles.sha256("Sky-Myce hardware v1\n$source\n$normalized".toByteArray(Charsets.UTF_8))
}

private fun hardwareSources(): List<String> = when {
    System.getProperty("os.name").startsWith("Windows") -> listOf("windows-smbios", "windows-machine-guid")
    System.getProperty("os.name").startsWith("Linux") -> listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id")
    System.getProperty("os.name").startsWith("Mac") -> listOf("macos-platform")
    else -> listOf("software-installation")
}

private fun readHardwareIdentifier(source: String): String? = runCatching {
    val file = when (source) {
        "linux-dmi" -> Path.of("/sys/class/dmi/id/product_uuid")
        "linux-machine-id" -> Path.of("/etc/machine-id")
        "linux-dbus-machine-id" -> Path.of("/var/lib/dbus/machine-id")
        else -> null
    }
    if (file != null) {
        require(Files.size(file) <= 4096)
        Files.readString(file).trim()
    } else {
        val command = when (source) {
            "windows-smbios" -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "(Get-CimInstance -ClassName Win32_ComputerSystemProduct -ErrorAction Stop).UUID")
            "windows-machine-guid" -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "Get-ItemPropertyValue -LiteralPath 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -Name MachineGuid -ErrorAction Stop")
            "macos-platform" -> listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            else -> error("Unsupported hardware source")
        }
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        check(process.waitFor(15, TimeUnit.SECONDS).also { if (!it) process.destroyForcibly() })
        check(process.exitValue() == 0)
        val result = process.inputStream.bufferedReader().use { it.readText() }.also { require(it.length <= 16384) }
        if (source == "macos-platform") Regex("\"IOPlatformUUID\"\\s*=\\s*\"([a-fA-F0-9-]+)\"").find(result)?.groupValues?.get(1)
        else result.trim()
    }
}.getOrNull()

/** Shared per OS user, outside the game instance; runtime/session state never changes this identity. */
class Installation internal constructor(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".sky-myce", "device-v1"),
    private val readHardware: (String) -> String? = ::readHardwareIdentifier,
    private val sources: List<String> = hardwareSources()
) {
    val id: String
    val publicKey: String
    val deviceHash: String
    val hardwareHash: String
    private val privateKey: PrivateKey
    init {
        Files.createDirectories(directory)
        restrict(directory)
        // Process lock serializes first use by multiple Minecraft instances.
        java.nio.channels.FileChannel.open(directory.resolve("identity.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val file = directory.resolve("identity.properties")
                if (!Files.exists(file)) {
                    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                    val properties = Properties().apply {
                        setProperty("id", UUID.randomUUID().toString())
                        setProperty("publicKey", Base64.getEncoder().encodeToString(pair.public.encoded))
                        setProperty("privateKey", protect(pair.private.encoded, false))
                        setProperty("deviceHash", SignedFiles.sha256("Sky-Myce device v1\n${UUID.randomUUID()}\n${UUID.randomUUID()}".toByteArray()))
                    }
                    val bytes = java.io.ByteArrayOutputStream().also { properties.store(it, "Sky-Myce local installation; never share") }.toByteArray()
                    SignedFiles.atomicWrite(file, bytes)
                    restrict(file)
                }
                val properties = Properties().apply { Files.newInputStream(file).use(::load) }
                id = properties.getProperty("id").also { UUID.fromString(it) }
                publicKey = properties.getProperty("publicKey")
                deviceHash = properties.getProperty("deviceHash").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val encoded = Base64.getDecoder().decode(properties.getProperty("privateKey"))
                privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(
                    if (isWindows()) Base64.getDecoder().decode(protect(encoded, true)) else encoded))
                val storedSource = properties.getProperty("hardwareSource")
                val selected = (storedSource?.let(::listOf) ?: sources).firstNotNullOfOrNull { source ->
                    if (source == "software-installation") source to deviceHash
                    else readHardware(source)?.let { identifier -> runCatching { source to hardwareIdentityHash(source, identifier) }.getOrNull() }
                } ?: throw HardwareIdentityUnavailable()
                hardwareHash = selected.second
                if (storedSource == null) {
                    // Upgrade the proof without changing the already-enrolled installation or device binding.
                    properties.setProperty("hardwareSource", selected.first)
                    val bytes = java.io.ByteArrayOutputStream().also { properties.store(it, "Sky-Myce local installation; never share") }.toByteArray()
                    SignedFiles.atomicWrite(file, bytes)
                }
                restrict(file)
            }
        }
    }
    fun sign(text: String): String = Signature.getInstance("Ed25519").run {
        initSign(privateKey); update(text.toByteArray(Charsets.UTF_8)); Base64.getEncoder().encodeToString(sign())
    }
    fun persistBoundary(lease: String) = SignedFiles.atomicWrite(directory.resolve("last-signed-lease"), lease.toByteArray())
    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
    private fun protect(bytes: ByteArray, decrypt: Boolean): String {
        if (!isWindows()) return Base64.getEncoder().encodeToString(bytes)
        val method = if (decrypt) "Unprotect" else "Protect"
        // DPAPI binds ciphertext to this Windows user/machine; input is stdin, never an argument/log.
        val command = "Add-Type -AssemblyName System.Security; " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$method([Convert]::FromBase64String([Console]::ReadLine()), [Text.Encoding]::UTF8.GetBytes('Sky-Myce device v1'), [Security.Cryptography.DataProtectionScope]::CurrentUser))"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command).start()
        process.outputStream.bufferedWriter().use { it.write(Base64.getEncoder().encodeToString(bytes)); it.newLine() }
        check(process.waitFor(15, TimeUnit.SECONDS).also { if (!it) process.destroyForcibly() }) { "Local key protection unavailable" }
        check(process.exitValue() == 0) { "Local key protection unavailable" }
        return process.inputStream.bufferedReader().readText().trim().also { check(it.length in 1..8192) }
    }
    private fun restrict(path: Path) {
        val posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"))
        else {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: error("Private key permissions unavailable")
            acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
        }
    }
}
