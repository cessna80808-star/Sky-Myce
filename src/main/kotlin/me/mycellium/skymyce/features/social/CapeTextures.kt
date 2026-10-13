package me.mycellium.skymyce.features.social

import com.mojang.blaze3d.platform.NativeImage
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.community.readCommunityBody
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.net.http.*
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Executors

/** Immutable references and bounded asynchronous decode; renderer only reads completed texture entries. */
object CapeTextures {
    val presets = linkedMapOf("sky-myce" to "Sky-Myce", "forest" to "Forest", "sunset" to "Sunset", "void" to "Void")
    private data class Cached(val texture: Identifier, val at: Long)
    private val values = linkedMapOf<String, Cached>()
    private val pending = mutableSetOf<String>()
    private val failed = linkedMapOf<String, Long>()
    private val localValues = linkedMapOf<String, Identifier>()
    private val localPending = mutableSetOf<String>()
    private val localFailed = linkedMapOf<String, Long>()
    private var localOwner: java.util.UUID? = null
    // Private Studio previews never populate the public active-cape cache.
    private val previewValues = linkedMapOf<CapePreviewRequest, Cached>()
    private val previewPending = mutableSetOf<CapePreviewRequest>()
    private val previewFailed = linkedMapOf<CapePreviewRequest, Long>()
    private var previewOwner: StudioOwner? = null
    @Volatile private var previewEpoch = 0L
    private var textureSerial = 0L
    private var initialized = false
    @Volatile private var stopping = false
    private val worker = Executors.newFixedThreadPool(2) { Thread(it, "Sky-Myce capes").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun initialize() {
        if (initialized) return; initialized = true
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            stopping = true; worker.shutdownNow(); clearPreviews()
            values.values.forEach { MC.instance.textureManager.release(it.texture) }; values.clear()
            localValues.values.forEach { MC.instance.textureManager.release(it) }; localValues.clear()
        }
    }
    fun selected(profile: CosmeticProfile): Identifier? {
        if (stopping) return null
        profile.capePreset?.let { if (it in presets) return Identifier.fromNamespaceAndPath("skymyce", "textures/capes/$it.png") }
        if (profile.localCape) return local(profile)
        val asset = profile.capeAsset ?: return null
        val key = "${asset.id}:${asset.sha256}"
        values[key]?.let {
            values.remove(key)
            if (System.currentTimeMillis() - it.at in 0..60000) { values[key] = it; return it.texture }
            MC.instance.textureManager.release(it.texture)
        }
        if (pending.size >= 16 || key in pending || (failed[key] ?: 0) > System.currentTimeMillis()) return null
        pending += key
        worker.submit {
            val decoded = runCatching {
                val backend = URI(CommunityClient.configuration.getProperty("backend")); require(backend.scheme == "https" && backend.userInfo == null)
                val endpoint = backend.resolve("/community/cosmetics/assets/${asset.id}?uuid=${profile.uuid.toString().replace("-", "")}")
                val response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = readCommunityBody(response.body(), 262144, Duration.ofSeconds(20)); require(response.statusCode() == 200 && bytes.size == asset.size)
                validateCapeDownload(bytes, asset); NativeImage.read(bytes)
            }
            MC.instance.execute {
                pending.remove(key)
                if (stopping) { decoded.getOrNull()?.close(); return@execute }
                decoded.fold({ pixels ->
                    val id = Identifier.fromNamespaceAndPath("skymyce", "cape-assets/${asset.id}")
                    MC.instance.textureManager.register(id, DynamicTexture({ "Sky-Myce cape" }, pixels))
                    values[key] = Cached(id, System.currentTimeMillis())
                    while (values.size > 32) MC.instance.textureManager.release(values.remove(values.keys.first())!!.texture)
                }, { failed[key] = System.currentTimeMillis() + 60000; while (failed.size > 64) failed.remove(failed.keys.first()) })
            }
        }
        return null
    }
    @JvmStatic fun apply(skin: PlayerSkin, profile: CosmeticProfile): PlayerSkin {
        return applyCosmeticCape(skin, profile, if (profile.capePreset == "none") null else selected(profile))
    }
    /** Studio-only reads can preview owned ready uploads without changing the saved public selection. */
    fun applyPreview(skin: PlayerSkin, profile: CosmeticProfile): PlayerSkin {
        val owner = currentPreviewOwner().takeIf { CommunityClient.connected }
        if (owner != previewOwner) { clearPreviews(); previewOwner = owner }
        val selected = when {
            profile.capePreset == "none" -> null
            profile.localCape -> local(profile)
            profile.capeAsset == null -> selected(profile)
            else -> preview(profile, owner)
        }
        return applyCosmeticCape(skin, profile, selected)
    }
    private fun local(profile: CosmeticProfile): Identifier? {
        val asset = profile.capeAsset ?: return null
        val uuid = MC.instance.user.profileId
        if (localOwner != uuid) {
            localValues.values.forEach { MC.instance.textureManager.release(it) }; localValues.clear(); localFailed.clear(); localOwner = uuid
        }
        if (profile.uuid != uuid || asset !in LocalCosmetics.assets) return null
        val key = "$uuid:${asset.sha256}"
        localValues[key]?.let { return it }
        if (localPending.size >= 8 || key in localPending || (localFailed[key] ?: 0) > System.currentTimeMillis()) return null
        localPending += key
        worker.submit {
            val decoded = runCatching { NativeImage.read(LocalCosmetics.localBytes(profile)) }
            MC.instance.execute {
                localPending.remove(key)
                if (stopping || MC.instance.user.profileId != uuid || asset !in LocalCosmetics.assets) { decoded.getOrNull()?.close(); return@execute }
                decoded.fold({ pixels ->
                    val id = Identifier.fromNamespaceAndPath("skymyce", "local-capes/${asset.id}-${++textureSerial}")
                    MC.instance.textureManager.register(id, DynamicTexture({ "Sky-Myce local cape" }, pixels)); localValues[key] = id
                    while (localValues.size > 8) MC.instance.textureManager.release(localValues.remove(localValues.keys.first())!!)
                }, { localFailed[key] = System.currentTimeMillis() + 60000; while (localFailed.size > 16) localFailed.remove(localFailed.keys.first()) })
            }
        }
        return null
    }
    fun clearPreviews() {
        previewEpoch++; previewOwner = null
        previewValues.values.forEach { MC.instance.textureManager.release(it.texture) }; previewValues.clear(); previewFailed.clear()
        // Keep in-flight entries until their callbacks drain, so close/reopen cannot evade the eight-request bound.
    }
    private fun currentPreviewOwner(): StudioOwner? {
        val identity = CommunityClient.identity ?: return null
        val token = CommunityClient.sessionToken ?: return null
        return StudioOwner(identity["accountId"].asString, identity["uuid"].asString, token, CommunityClient.sessionGeneration)
            .takeIf { it.uuid == MC.instance.user.profileId.toString().replace("-", "") }
    }
    private fun preview(profile: CosmeticProfile, owner: StudioOwner?): Identifier? {
        if (stopping) return null
        val request = capePreviewRequest(profile, owner, previewEpoch) ?: return null
        fun current() = !stopping && request.current(currentPreviewOwner(), MC.instance.user.profileId.toString().replace("-", ""), CommunityClient.connected, previewEpoch)
        if (!current()) return null
        previewValues[request]?.let {
            previewValues.remove(request)
            if (System.currentTimeMillis() - it.at in 0..60000) { previewValues[request] = it; return it.texture }
            MC.instance.textureManager.release(it.texture)
        }
        if (previewPending.size >= 8 || request in previewPending || (previewFailed[request] ?: 0) > System.currentTimeMillis()) return null
        previewPending += request
        worker.submit {
            val decoded = runCatching {
                val bytes = loadCapePreview(request, ::current) { path, token ->
                    val backend = URI(CommunityClient.configuration.getProperty("backend"))
                    require(backend.scheme == "https" && backend.userInfo == null && backend.rawQuery == null && backend.fragment == null)
                    val response = http.send(HttpRequest.newBuilder(backend.resolve(path)).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                    val content = readCommunityBody(response.body(), 262144, Duration.ofSeconds(20))
                    require(response.statusCode() == 200); content
                }
                NativeImage.read(bytes)
            }
            MC.instance.execute {
                previewPending.remove(request)
                if (!current()) { decoded.getOrNull()?.close(); return@execute }
                decoded.fold({ pixels ->
                    val id = Identifier.fromNamespaceAndPath("skymyce", "cape-previews/${request.asset.id}-${++textureSerial}")
                    MC.instance.textureManager.register(id, DynamicTexture({ "Sky-Myce private cape preview" }, pixels))
                    previewValues[request] = Cached(id, System.currentTimeMillis())
                    while (previewValues.size > 8) MC.instance.textureManager.release(previewValues.remove(previewValues.keys.first())!!.texture)
                }, { previewFailed[request] = System.currentTimeMillis() + 60000; while (previewFailed.size > 16) previewFailed.remove(previewFailed.keys.first()) })
            }
        }
        return null
    }
}

internal data class CapePreviewRequest(val asset: CapeAsset, val owner: StudioOwner, val epoch: Long) {
    val path get() = "/community/cosmetics/preview/${asset.id}?uuid=${owner.uuid}"
    fun current(current: StudioOwner?, minecraftUuid: String, connected: Boolean, currentEpoch: Long) =
        epoch == currentEpoch && studioOwnerCurrent(owner, current, minecraftUuid, connected)
}
internal fun capePreviewRequest(profile: CosmeticProfile, owner: StudioOwner?, epoch: Long): CapePreviewRequest? {
    val asset = profile.capeAsset ?: return null
    if (owner == null || profile.capePreset != null || profile.uuid.toString().replace("-", "") != owner.uuid) return null
    return CapePreviewRequest(asset, owner, epoch)
}
/** Recheck authority before transmission and after the response, then validate the same canonical upload descriptor. */
internal fun loadCapePreview(request: CapePreviewRequest, current: () -> Boolean, fetch: (String, String) -> ByteArray): ByteArray {
    if (!current()) throw CommunityFailure("session_identity_changed")
    val bytes = fetch(request.path, request.owner.token)
    if (!current()) throw CommunityFailure("session_identity_changed")
    validateCapeDownload(bytes, request.asset)
    if (!current()) throw CommunityFailure("session_identity_changed")
    return bytes
}

/** Null selection retains the native cape; explicit none hides only the cape, preserving skin and elytra. */
internal fun applyCosmeticCape(skin: PlayerSkin, profile: CosmeticProfile, selected: Identifier?): PlayerSkin = when {
    profile.capePreset == "none" -> PlayerSkin(skin.body(), null, skin.elytra(), skin.model(), skin.secure())
    selected == null -> skin
    else -> PlayerSkin(skin.body(), ClientAsset.ResourceTexture(selected, selected), skin.elytra(), skin.model(), skin.secure())
}

/** GUI render state only: invisibility and equipped wings must not conceal the cape being edited. */
internal fun prepareCosmeticPreview(state: AvatarRenderState) {
    state.isInvisible = false
    state.showCape = true
    state.chestEquipment = ItemStack.EMPTY
    state.capeFlap = 0f; state.capeLean = 0f; state.capeLean2 = 0f
}

internal fun validateCapeDownload(bytes: ByteArray, asset: CapeAsset) {
    require(bytes.size == asset.size && bytes.size <= 262144)
    require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == asset.sha256)
    validateCapePng(bytes)
}
