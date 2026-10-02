package me.mycellium.skymyce.loader

import com.mojang.authlib.properties.Property
import java.io.IOException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

fun checkStartup() {
    val token = "test-session-only-never-a-real-token"
    val uuid = UUID(0, 42)
    val arguments = arrayOf("--username", "Alice", "--uuid", uuid.toString(), "--accessToken", token)
    val account = StartupAccount.parse(arguments)
    check(account.name == "Alice" && account.uuid == uuid && token !in account.toString())
    check(StartupAccount.parse(arrayOf("--username=Alice", "--uuid=${uuid.toString().replace("-", "")}", "--accessToken=$token")).uuid == uuid)
    fun reject(action: () -> Unit) { check(runCatching(action).isFailure) }
    reject { StartupAccount.parse(arguments + arrayOf("--username", "Bob")) }
    reject { StartupAccount.parse(arguments + arrayOf("--uuid", UUID(0, 43).toString())) }
    reject { StartupAccount.parse(arrayOf("--username", "Alice", "--uuid", uuid.toString(), "--accessToken", "0")) }
    reject { StartupAccount.parse(arrayOf("--username", "Bad Name", "--uuid", uuid.toString(), "--accessToken", token)) }
    reject { StartupAccount.parse(arrayOf("--username", "Alice", "--uuid", "42", "--accessToken", token)) }
    reject { StartupAccount.parse(arguments + arrayOf("--proxyUser", "private")) }
    reject { StartupAccount.parse(arguments + arrayOf("--proxyHost", "localhost", "--proxyPort", "0")) }
    val missingAccount = runCatching { StartupAccount.parse(emptyArray()) }.exceptionOrNull()!!
    check(missingAccount is StartupIdentityUnavailable && "online Minecraft account" in startupError(missingAccount))
    // HttpClient DNS failures contain an IllegalArgumentException subclass; they
    // must not tell a valid online player that their launch identity is invalid.
    val dns = IOException(token, ConnectException(token).apply { initCause(UnresolvedAddressException()) })
    check("could not reach its sign-in service" in startupError(dns))
    check("online Minecraft account" !in startupError(dns))
    check("launch settings" !in startupError(IllegalArgumentException(token)))
    val diagnostic = startupFailureDiagnostic(dns)
    check("IOException" in diagnostic && "UnresolvedAddressException" in diagnostic && token !in diagnostic)
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val base64 = Base64.getEncoder()
    val pem = "-----BEGIN RSA PRIVATE KEY-----\n${base64.encodeToString(pair.private.encoded)}\n-----END RSA PRIVATE KEY-----"
    check(StartupAccount.pem(pem).contentEquals(pair.private.encoded))
    val nonce = "a".repeat(32)
    val expires = System.currentTimeMillis() + 60000
    val textures = Property("textures", "test-textures", "test-signature")
    val proof = signedMinecraftProof("Alice", uuid, nonce, expires, pair.private, pair.public, byteArrayOf(1, 2), textures)
    val expected = "SkyMyce relay v2\n$nonce\n${uuid.toString().replace("-", "")}\nAlice".toByteArray()
    fun verifies(message: ByteArray) = Signature.getInstance("SHA256withRSA").run {
        initVerify(pair.public); update(message); verify(Base64.getDecoder().decode(proof["proof"] as String))
    }
    check(verifies(expected) && !verifies(expected + 0))
    check(proof["uuid"] == uuid.toString().replace("-", "") && proof["profileSignature"] == "test-signature")
    check(proof.keys == setOf("type", "name", "uuid", "expires", "publicKey", "keySignature", "proof", "profile", "profileSignature"))
    check(proof.values.none { it == token || it == base64.encodeToString(pair.private.encoded) })
    reject { signedMinecraftProof("Alice", uuid, "invalid", expires, pair.private, pair.public, byteArrayOf(), textures) }
    reject { signedMinecraftProof("Alice", uuid, nonce, 0, pair.private, pair.public, byteArrayOf(), textures) }
    reject { signedMinecraftProof("Alice", uuid, nonce, expires, pair.private, pair.public, byteArrayOf(), Property("textures", "unsigned")) }
    for (code in listOf("installation_required", "invalid_activation_key", "account_required", "minecraft_link_required")) check(startupActivationAllowed(code))
    for (code in listOf("license_revoked", "account_suspended", "secret-error-$token")) check(!startupActivationAllowed(code))
    requireStartupHandoffAllowed(null)
    for (marker in listOf("1", "0", "invalid")) reject { requireStartupHandoffAllowed(marker) }
    check("prevent a relaunch loop" in startupError(StartupHandoffLimit()))
    val deviceHelp = requireNotNull(accountAccessError("device_reset_required"))
    check("/hwid reset" in deviceHelp && "new code alone cannot switch devices" in deviceHelp)
    check("User Bot's /hwid reset confirm:true" in deviceHelp && "explicit emergency reset" in deviceHelp)
    check(startupError(AuthorizationDenied("device_reset_required")) == deviceHelp)
    check("seven-day cooldown" in deviceHelp)
    check("Do not reset your HWID" in startupError(HardwareIdentityUnavailable()))
    for (code in listOf("HWID_MISMATCH", "device_reset_required", "device_reset_cooldown", "device_transfer_cooldown", "device_owned", "device_revoked",
        "minecraft_account_owned", "minecraft_proof_invalid", "version_denied", "loader_update_required", "launch_authorization_required", "platform_changed", "authorization_revoked")) {
        check(!startupActivationAllowed(code)) // Another code must never bypass an account/device restriction.
        check(code in requireNotNull(accountAccessError(code)))
    }
    val launchHelp = requireNotNull(accountAccessError("launch_authorization_required"))
    check("relaunch this instance" in launchHelp && "new activation code is not needed" in launchHelp)
    check("session was replaced" in requireNotNull(accountAccessError("authorization_revoked")))
    check("Keep its device identity" in requireNotNull(accountAccessError("platform_changed")))
    check(accountAccessError(token) == null)
    for (failure in listOf(IllegalStateException(token), AuthorizationDenied(token), SecurityException(token), IllegalArgumentException(token))) {
        check(token !in startupError(failure))
    }
    // Catch accidentally adding Minecraft render/game dependencies to the pre-game path.
    for (name in listOf("StartupLoader", "StartupAccount", "StartupWindow", "LoaderApi", "MinecraftProofDataKt", "Installation", "LaunchSession", "LaunchSessionKt", "AuthorizationState", "SignedFiles")) {
        val resource = "/me/mycellium/skymyce/loader/$name.class"
        val bytes = requireNotNull(StartupAccount::class.java.getResourceAsStream(resource)).use { it.readBytes() }
        check("net/minecraft/" !in String(bytes, Charsets.ISO_8859_1)) { "$name depends on Minecraft initialization" }
    }
    println("Startup checks passed: launch identity, private token handling, shared signed proof, safe errors and no Minecraft render dependencies")
}
