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

/** Shared per OS user, outside the game instance: copying a mods/config folder cannot copy its key. */
class Installation(private val directory: Path = Path.of(System.getProperty("user.home"), ".sky-myce", "device-v1")) {
    val id: String
    val publicKey: String
    val deviceHash: String
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
