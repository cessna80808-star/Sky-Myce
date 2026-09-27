package me.mycellium.skymyce.loader

import me.mycellium.mixin.MinecraftAccountKeyAccessor
import net.minecraft.client.Minecraft
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CompletableFuture

/** Same Mojang certificate + signed profile + domain-separated nonce proof as the existing relay. */
object MinecraftProof {
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
            val uuid = user.profileId.toString().replace("-", "")
            val proof = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.privateKey()); update("SkyMyce relay v2\n$nonce\n$uuid\n${user.name}".toByteArray()); sign()
            }
            val base64 = Base64.getEncoder()
            mapOf("type" to "authenticate", "name" to user.name, "uuid" to uuid,
                "expires" to certificate.expiresAt().toEpochMilli(), "publicKey" to base64.encodeToString(certificate.key().encoded),
                "keySignature" to base64.encodeToString(certificate.keySignature()), "proof" to base64.encodeToString(proof),
                "profile" to textures.value(), "profileSignature" to requireNotNull(textures.signature()))
        }
    }
}
