package me.mycellium.skymyce.community

import me.mycellium.mixin.MinecraftAccountKeyAccessor
import net.minecraft.client.Minecraft
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CompletableFuture

/** Minecraft owns its signing key. Only its signed public certificate/profile proof leaves the client. */
object CommunityMinecraftProof {
    fun create(nonce: String): CompletableFuture<Map<String, Any>> {
        require(nonce.matches(Regex("[a-f0-9]{32}|[A-Za-z0-9_-]{43}")))
        val minecraft = Minecraft.getInstance()
        val user = minecraft.user
        val service = minecraft.services().sessionService()
        val manager = (minecraft as MinecraftAccountKeyAccessor).`skymyce$getAccountKeyPairManager`()
        return manager.prepareKeyPair().thenApplyAsync { optional ->
            val pair = optional.orElseThrow { IllegalStateException("Minecraft account key unavailable") }
            val certificate = pair.publicKey().data()
            check(!certificate.hasExpired())
            val profile = service.fetchProfile(user.profileId, true)?.profile() ?: error("Minecraft profile unavailable")
            check(profile.id() == user.profileId && profile.name().equals(user.name, true))
            val textures = service.getPackedTextures(profile)
            check(textures != null && textures.hasSignature())
            val id = user.profileId.toString().replace("-", "")
            val proof = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.privateKey())
                update("SkyMyce relay v2\n$nonce\n$id\n${user.name}".toByteArray(Charsets.UTF_8)); sign()
            }
            val base64 = Base64.getEncoder()
            mapOf("type" to "authenticate", "name" to user.name, "uuid" to id,
                "expires" to certificate.expiresAt().toEpochMilli(),
                "publicKey" to base64.encodeToString(certificate.key().encoded),
                "keySignature" to base64.encodeToString(certificate.keySignature()),
                "proof" to base64.encodeToString(proof), "profile" to textures.value(),
                "profileSignature" to requireNotNull(textures.signature()))
        }
    }
}
