package me.mycellium.skymyce.loader

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Uses only this launch's arguments and Mojang Authlib; never opens launcher account files. */
internal class StartupAccount private constructor(val name: String, val uuid: UUID, private val token: String, private val proxy: Proxy) {
    override fun toString() = "Private startup Minecraft account"
    companion object {
        fun parse(arguments: Array<String>): StartupAccount {
            val wanted = setOf("username", "uuid", "accessToken", "proxyHost", "proxyPort", "proxyUser", "proxyPass")
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index++]
                if (!argument.startsWith("--")) continue
                val key = argument.removePrefix("--").substringBefore('=')
                if (key !in wanted) continue
                require(key !in values) { "Ambiguous launch identity" }
                values[key] = if ('=' in argument) argument.substringAfter('=') else arguments.getOrNull(index++) ?: error("Incomplete launch identity")
            }
            val name = requireNotNull(values["username"]).also { require(it.matches(Regex("[A-Za-z0-9_]{1,16}"))) }
            val rawUuid = requireNotNull(values["uuid"]).also { require(it.matches(Regex("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))) }.replace("-", "")
            val uuid = UUID.fromString(rawUuid.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
            val token = requireNotNull(values["accessToken"]).also { require(it.length in 20..16384 && it.none(Char::isWhitespace)) { "Online Minecraft account required" } }
            // Authenticated SOCKS proxies must remain with Minecraft's own authenticator setup.
            require(values["proxyUser"].isNullOrBlank() && values["proxyPass"].isNullOrBlank()) { "Startup proxy authentication unavailable" }
            val proxy = values["proxyHost"]?.takeIf { it.isNotBlank() }?.let {
                val port = values["proxyPort"]?.toIntOrNull() ?: 8080
                require(port in 1..65535)
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(it, port))
            } ?: Proxy.NO_PROXY
            return StartupAccount(name, uuid, token, proxy)
        }
        internal fun pem(value: String): ByteArray = Base64.getMimeDecoder().decode(value
            .lineSequence().filterNot { it.startsWith("-----") }.joinToString(""))
    }
    fun proof(nonce: String): Map<String, Any> {
        require(nonce.matches(Regex("[a-f0-9]{32}|[A-Za-z0-9_-]{43}")))
        val service = YggdrasilAuthenticationService(proxy)
        val certificate = requireNotNull(service.createUserApiService(token).keyPair)
        val expires = Instant.parse(certificate.expiresAt()).toEpochMilli()
        check(expires > System.currentTimeMillis())
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(pem(certificate.keyPair().privateKey())))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(pem(certificate.keyPair().publicKey())))
        val session = service.createMinecraftSessionService()
        val profile = requireNotNull(session.fetchProfile(uuid, true)?.profile())
        check(profile.id() == uuid && profile.name().equals(name, true))
        val textures = requireNotNull(session.getPackedTextures(profile))
        check(textures.hasSignature())
        val signature = certificate.publicKeySignature().duplicate().let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
        return signedMinecraftProof(name, uuid, nonce, expires, privateKey, publicKey, signature, textures)
    }
}
