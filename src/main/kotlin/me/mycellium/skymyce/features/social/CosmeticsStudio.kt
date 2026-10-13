package me.mycellium.skymyce.features.social

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.community.readCommunityBody
import me.mycellium.skymyce.utils.MC
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The saved revision is never changed by a preview. All mutations capture the authenticated owner. */
class CosmeticsStudio {
    var profile: CosmeticProfile? = null; private set
    var assets = emptyList<CapeAsset>(); private set
    var status = "Connect Discord to publish; Equip saves locally"; private set
    var busy = false; private set
    var revision = 0L; private set
    @Volatile private var epoch = 0L
    private var ownerKey: String? = null
    private companion object {
        val worker = StudioAsyncWorker("Sky-Myce cosmetics studio", 2, 4)
        val chooser = StudioAsyncWorker("Sky-Myce cape chooser", 1, 0)
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun refresh() = work("Loading saved cosmetics…", false, { owner, token, guard ->
        guard.check()
        val result = CommunityClient.exchange("/community/cosmetics", null, "GET", token)
        val saved = requireNotNull(parseCosmeticProfile(result.getAsJsonObject("profile")))
        require(saved.uuid.toString().replace("-", "") == owner["expectedUuid"].asString)
        saved to result.getAsJsonArray("assets").also { require(it.size() <= 8) }.map { parseCapeAsset(it.asJsonObject) }
    }) { (saved, owned) -> profile = saved; assets = owned; CosmeticsClient.acceptOwn(saved); status = "Saved revision ${saved.revision} • visual changes only" }

    fun save(draft: CosmeticProfile, name: com.google.gson.JsonElement?) {
        if (draft.localCape) { status = "Publish the local PNG first; it is not a community asset."; revision++; return }
        observeOwner()
        val saved = profile ?: return
        work("Saving cosmetics…", true, { owner, token, guard ->
            require(draft.uuid == saved.uuid)
            require(cosmeticDraftOwnerCurrent(saved, draft, owner["expectedUuid"].asString))
            val fields = JsonObject().apply {
                add("name", name ?: JsonNull.INSTANCE)
                addProperty("scaleX", draft.scaleX); addProperty("scaleY", draft.scaleY); addProperty("scaleZ", draft.scaleZ)
                addProperty("nametagScale", draft.nametagScale)
                add("capePreset", draft.capePreset?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                add("capeAsset", draft.capeAsset?.let { com.google.gson.JsonPrimitive(it.id) } ?: JsonNull.INSTANCE)
            }
            guard.check()
            val result = CommunityClient.exchange("/community/cosmetics", owner.apply { addProperty("revision", saved.revision); add("profile", fields) }, "POST", token)
            requireNotNull(parseCosmeticProfile(result.getAsJsonObject("profile")))
        }) { result -> profile = result; CosmeticsClient.acceptOwn(result); status = "Saved revision ${result.revision} • synchronized" }
    }

    fun upload(path: String, accepted: (CapeAsset) -> Unit = {}) = work("Validating and uploading cape…", true, { owner, token, guard ->
        uploadCape(path, owner, token, guard)
    }) { asset -> assets = (assets.filterNot { it.id == asset.id } + asset).takeLast(8); status = "Uploaded cape ${asset.id.take(8)}; select it, then Save"; accepted(asset) }

    /** One accepted mutation retains Publish intent across screen close, with authority checked between requests. */
    fun publishLocal(draft: CosmeticProfile, name: com.google.gson.JsonElement?, path: String) {
        observeOwner()
        val saved = profile ?: return
        work("Uploading and publishing local appearance…", true, { owner, token, guard ->
            require(draft.localCape && cosmeticDraftOwnerCurrent(saved, draft, owner["expectedUuid"].asString))
            capturedStudioPublish(guard, { uploadCape(path, owner, token, guard) }) { asset ->
                val fields = JsonObject().apply {
                    add("name", name ?: JsonNull.INSTANCE)
                    addProperty("scaleX", draft.scaleX); addProperty("scaleY", draft.scaleY); addProperty("scaleZ", draft.scaleZ)
                    addProperty("nametagScale", draft.nametagScale)
                    add("capePreset", JsonNull.INSTANCE); addProperty("capeAsset", asset.id)
                }
                val result = CommunityClient.exchange("/community/cosmetics", owner.apply { addProperty("revision", saved.revision); add("profile", fields) }, "POST", token)
                requireNotNull(parseCosmeticProfile(result.getAsJsonObject("profile")))
            }
        }) { result ->
            profile = result; result.capeAsset?.let { asset -> assets = (assets.filterNot { it.id == asset.id } + asset).takeLast(8) }
            CosmeticsClient.acceptOwn(result); status = "Published revision ${result.revision} • local preference retained"
        }
    }

    private fun uploadCape(path: String, owner: JsonObject, token: String, guard: StudioRequestGuard): CapeAsset {
        val file = Path.of(path.trim()).toAbsolutePath().normalize()
        require(file.fileName.toString().endsWith(".png", true) && Files.isRegularFile(file) && Files.size(file) in 45..262144)
        val bytes = Files.newInputStream(file).use { it.readNBytes(262145) }; validateCapePng(bytes)
        guard.check()
        val grant = CommunityClient.exchange("/community/cosmetics/grant", owner, "POST", token)
        require(grant["uploadPath"].asString == "/community/cosmetics/upload")
        val endpoint = URI(CommunityClient.configuration.getProperty("backend")); require(endpoint.scheme == "https" && endpoint.userInfo == null)
        val request = HttpRequest.newBuilder(endpoint.resolve("/community/cosmetics/upload")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token").header("X-SkyMyce-Upload-Grant", grant["grant"].asString)
            .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        guard.check()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val responseBytes = readCommunityBody(response.body(), 65536, Duration.ofSeconds(30))
        val result = JsonParser.parseString(responseBytes.toString(Charsets.UTF_8)).asJsonObject
        if (response.statusCode() !in 200..299 || result["ok"]?.asBoolean != true) throw CommunityFailure(result["error"]?.asString ?: "upload_failed")
        return parseCapeAsset(result.getAsJsonObject("asset"))
    }

    fun delete(asset: CapeAsset) = work("Removing uploaded cape…", true, { owner, token, guard ->
        guard.check()
        CommunityClient.exchange("/community/cosmetics/assets/${asset.id}", owner, "DELETE", token)
    }) { assets = assets.filterNot { it.id == asset.id }; status = "Cape removed"; refresh() }

    fun observeOwner(): Boolean {
        val identity = CommunityClient.identity
        val key = "${MC.instance.user.profileId}|" + (identity?.let { "${it["accountId"]}:${it["uuid"]}" } ?: "offline")
        if (key == ownerKey) return false
        ownerKey = key; epoch++; busy = false; profile = null; assets = emptyList(); revision++
        status = if (identity == null) "Connect Discord to publish; Equip saves locally" else "Account changed; refresh community cosmetics"
        return true
    }
    fun chooseFile(accept: (String) -> Unit) {
        val requestEpoch = epoch; val owner = MC.instance.user.profileId
        val submitted = chooser.submit {
            val selected = runCatching { org.lwjgl.system.MemoryStack.stackPush().use { stack ->
                val filters = stack.mallocPointer(1).put(0, stack.UTF8("*.png"))
                org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose a 64 x 32 cape PNG", "", filters, "PNG image", false)
            } }.getOrNull()
            MC.instance.execute { if (requestEpoch == epoch && owner == MC.instance.user.profileId) selected?.let(accept) }
        }
        if (!submitted) { status = "A cape file chooser is already open. Complete or cancel it first."; revision++ }
    }
    fun close() { epoch++; busy = false }
    private fun currentOwner(): StudioOwner? {
        val identity = CommunityClient.identity ?: return null
        val token = CommunityClient.sessionToken ?: return null
        return StudioOwner(identity["accountId"].asString, identity["uuid"].asString, token, CommunityClient.sessionGeneration)
    }
    private fun <T> work(message: String, mutation: Boolean, action: (JsonObject, String, StudioRequestGuard) -> T, accept: (T) -> Unit) {
        if (busy) return
        observeOwner()
        val identity = CommunityClient.identity?.deepCopy()
        val token = CommunityClient.sessionToken
        if (!CommunityClient.connected || identity == null || token == null) { status = "Connect Discord to publish; local presets and PNG imports work offline"; revision++; return }
        val owner = JsonObject().apply { addProperty("expectedAccountId", identity["accountId"].asString); addProperty("expectedUuid", identity["uuid"].asString) }
        val generation = CommunityClient.sessionGeneration; val requestEpoch = ++epoch
        val captured = StudioOwner(owner["expectedAccountId"].asString, owner["expectedUuid"].asString, token, generation)
        val guard = StudioRequestGuard(mutation, {
            studioOwnerCurrent(captured, currentOwner(), MC.instance.user.profileId.toString().replace("-", ""), CommunityClient.connected)
        }, { epoch == requestEpoch })
        busy = true; status = message; revision++
        val submitted = worker.submit {
            val result = runCatching { guard.check(); action(owner, token, guard) }
            MC.instance.execute {
                if (epoch != requestEpoch) return@execute
                busy = false
                if (CommunityClient.sessionGeneration != generation || CommunityClient.identity?.get("accountId") != identity["accountId"] || CommunityClient.identity?.get("uuid") != identity["uuid"]) {
                    profile = null; assets = emptyList(); status = "Account changed; refresh before saving"; revision++; return@execute
                }
                result.fold(accept) { failure -> status = when ((failure as? CommunityFailure)?.code) {
                    "revision_conflict" -> "Profile changed elsewhere. Refresh to review before saving."
                    "asset_quota" -> "Cape storage is full. Delete an unused upload first."
                    "session_identity_changed" -> "Account or connection changed; refresh before saving."
                    else -> "Could not complete cosmetics request. Check connection, PNG format and ownership."
                } }
                revision++
            }
        }
        if (!submitted) { busy = false; status = "Cosmetics requests are busy. Retry your save, upload or refresh shortly."; revision++ }
    }
}

internal fun cosmeticDraftOwnerCurrent(saved: CosmeticProfile, draft: CosmeticProfile, ownerUuid: String) =
    saved.uuid == draft.uuid && saved.uuid.toString().replace("-", "") == ownerUuid

internal fun <T> capturedStudioPublish(guard: StudioRequestGuard, upload: () -> CapeAsset, save: (CapeAsset) -> T): T {
    guard.check(); val asset = upload(); guard.check(); return save(asset)
}
