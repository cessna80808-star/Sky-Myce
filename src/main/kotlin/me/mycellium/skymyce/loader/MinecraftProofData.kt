package me.mycellium.skymyce.loader

import com.mojang.authlib.properties.Property
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Identical domain-separated proof before startup and while Minecraft is running. */
internal fun signedMinecraftProof(name: String, uuid: UUID, nonce: String, expires: Long, privateKey: PrivateKey,
    publicKey: PublicKey, keySignature: ByteArray, textures: Property): Map<String, Any> {
    require(name.matches(Regex("[A-Za-z0-9_]{1,16}")) && nonce.matches(Regex("[a-f0-9]{32}|[A-Za-z0-9_-]{43}")))
    check(expires > System.currentTimeMillis() && textures.hasSignature())
    val id = uuid.toString().replace("-", "")
    val proof = Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey); update("SkyMyce relay v2\n$nonce\n$id\n$name".toByteArray(Charsets.UTF_8)); sign()
    }
    val base64 = Base64.getEncoder()
    return mapOf("type" to "authenticate", "name" to name, "uuid" to id, "expires" to expires,
        "publicKey" to base64.encodeToString(publicKey.encoded), "keySignature" to base64.encodeToString(keySignature),
        "proof" to base64.encodeToString(proof), "profile" to textures.value(), "profileSignature" to requireNotNull(textures.signature()))
}
