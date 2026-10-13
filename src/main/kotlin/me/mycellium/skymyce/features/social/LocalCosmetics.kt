package me.mycellium.skymyce.features.social

import com.google.gson.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import java.nio.file.*
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal data class LocalAppearance(val profile: CosmeticProfile?, val assets: List<CapeAsset> = emptyList())

internal fun cosmeticProfileJson(profile: CosmeticProfile) = JsonObject().apply {
    addProperty("uuid", profile.uuid.toString().replace("-", "")); add("name", profile.name?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    add("nameStyle", profile.nameStyle?.let(::cosmeticTextJson) ?: JsonNull.INSTANCE)
    addProperty("scale", profile.scaleY); addProperty("scaleX", profile.scaleX); addProperty("scaleY", profile.scaleY); addProperty("scaleZ", profile.scaleZ)
    addProperty("nametagScale", profile.nametagScale); addProperty("revision", profile.revision); addProperty("updatedAt", profile.updatedAt)
    add("capePreset", profile.capePreset?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    add("capeAsset", profile.capeAsset?.let(::capeAssetJson) ?: JsonNull.INSTANCE)
}
internal fun capeAssetJson(asset: CapeAsset) = JsonObject().apply {
    addProperty("id", asset.id); addProperty("sha256", asset.sha256); addProperty("size", asset.size); addProperty("width", asset.width); addProperty("height", asset.height)
}

/** UUID-scoped content-addressed files. Only validated descriptors can select a file; metadata commits atomically. */
internal class LocalCosmeticsRepository(root: Path, val owner: UUID) {
    private val folder = root.resolve(owner.toString().replace("-", ""))
    private val metadata = AtomicJsonFile(folder.resolve("appearance.json"), 32768, serializeNulls = true)
    private fun path(asset: CapeAsset) = folder.resolve("${asset.sha256}.png")
    fun read(): LocalAppearance {
        val raw = metadata.read() ?: run { require(!metadata.damaged) { "Local appearance file is damaged. Your files were retained." }; return LocalAppearance(null) }
        val json = raw.asJsonObject
        val keys = setOf("version", "uuid", "profile", "localCape", "assets")
        require(json.keySet() == keys || json.keySet() == keys - "profile")
        require(json["version"].asJsonPrimitive.isNumber && json["version"].asString == "1")
        require(json["uuid"].asString == owner.toString().replace("-", ""))
        val assets = json.getAsJsonArray("assets").also { require(it.size() <= 8) }.map { parseCapeAsset(it.asJsonObject) }
        require(assets.map { it.id }.distinct().size == assets.size && assets.sumOf { it.size } <= 2097152)
        val local = json["localCape"].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }.asBoolean
        // Early local v1 writes omitted explicit JsonNull members. Restore only those known nullable fields;
        // the normal strict profile parser still validates every field and the exact owner afterward.
        val profile = json["profile"]?.takeUnless { it.isJsonNull }?.let {
            val stored = it.asJsonObject.deepCopy()
            for (field in listOf("name", "nameStyle", "capePreset", "capeAsset")) if (!stored.has(field)) stored.add(field, JsonNull.INSTANCE)
            requireNotNull(parseCosmeticProfile(stored)) { "Local appearance profile is invalid. Your files were retained." }
        }.also { require(it == null || it.uuid == owner) }
        require(!local || profile?.capeAsset != null && profile.capeAsset in assets)
        if (local) bytes(requireNotNull(profile?.capeAsset))
        return LocalAppearance(profile?.copy(localCape = local), assets)
    }
    fun equip(state: LocalAppearance, draft: CosmeticProfile): LocalAppearance {
        require(draft.uuid == owner) { "Minecraft account changed; select the appearance again." }
        require(!draft.localCape || draft.capeAsset != null && draft.capeAsset in state.assets) { "The selected local cape is unavailable." }
        draft.capeAsset?.takeIf { draft.localCape }?.let(::bytes)
        val candidate = draft.copy(revision = maxOf(1, (state.profile?.revision ?: 0) + 1), updatedAt = System.currentTimeMillis())
        requireNotNull(parseCosmeticProfile(cosmeticProfileJson(candidate))) { "Invalid local appearance." }
        return state.copy(profile = candidate).also(::write)
    }
    fun import(state: LocalAppearance, draft: CosmeticProfile, file: Path): LocalAppearance {
        require(draft.uuid == owner) { "Minecraft account changed; import the cape again." }
        requireNotNull(parseCosmeticProfile(cosmeticProfileJson(draft.copy(revision = 1, updatedAt = System.currentTimeMillis())))) { "Invalid local appearance." }
        require(file.fileName.toString().endsWith(".png", true) && Files.isRegularFile(file) && Files.size(file) in 45..262144) { "Choose a PNG file no larger than 256 KiB." }
        val bytes = Files.newInputStream(file).use { it.readNBytes(262145) }
        require(runCatching { validateCapePng(bytes) }.isSuccess) { "Cape PNG must be valid 64 × 32 RGB/RGBA, eight-bit, noninterlaced and unanimated." }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val asset = CapeAsset(hash.take(32), hash, bytes.size)
        val assets = if (asset in state.assets) state.assets else state.assets + asset
        require(assets.size <= 8 && assets.sumOf { it.size } <= 2097152) { "Local cape library is full. Delete an unused cape first." }
        Files.createDirectories(folder)
        val target = path(asset)
        if (!Files.exists(target)) {
            val temporary = Files.createTempFile(folder, ".cape-", ".tmp")
            try {
                Files.write(temporary, bytes)
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        return try { equip(state.copy(assets = assets), draft.copy(capePreset = null, capeAsset = asset, localCape = true)) }
        catch (error: Exception) { if (asset !in state.assets) runCatching { Files.deleteIfExists(target) }; throw error }
    }
    fun delete(state: LocalAppearance, asset: CapeAsset): LocalAppearance {
        require(asset in state.assets)
        // Deleting an equipped upload explicitly removes its cape; it does not silently select the native cape.
        val profile = state.profile?.let { if (it.localCape && it.capeAsset == asset) it.copy(capePreset = "none", capeAsset = null, localCape = false) else it }
        val next = state.copy(profile = profile, assets = state.assets - asset)
        write(next); runCatching { Files.deleteIfExists(path(asset)) }; return next
    }
    fun useShared(state: LocalAppearance) = state.copy(profile = null).also(::write)
    fun bytes(asset: CapeAsset): ByteArray {
        parseCapeAsset(capeAssetJson(asset))
        val source = path(asset); require(Files.isRegularFile(source) && Files.size(source) == asset.size.toLong()) { "Local cape file is missing or changed." }
        return Files.newInputStream(source).use { it.readNBytes(262145) }.also { validateCapeDownload(it, asset) }
    }
    fun file(asset: CapeAsset) = path(asset)
    private fun write(state: LocalAppearance) { metadata.read(); metadata.write(JsonObject().apply {
        addProperty("version", 1); addProperty("uuid", owner.toString().replace("-", "")); addProperty("localCape", state.profile?.localCape == true)
        add("profile", state.profile?.let(::cosmeticProfileJson) ?: JsonNull.INSTANCE)
        add("assets", JsonArray().apply { state.assets.forEach { add(capeAssetJson(it)) } })
    }) }
}

/** A single bounded local worker; accepted writes keep their captured UUID even if a screen closes. */
object LocalCosmetics {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { Thread(it, "Sky-Myce local cosmetics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile private var owner: UUID? = null
    @Volatile private var state = LocalAppearance(null)
    private var epoch = 0L
    private var initialized = false
    var ready = false; private set
    var busy = false; private set
    var revision = 0L; private set
    var status = "Loading local appearance…"; private set
    val assets get() = state.assets
    private fun repository(uuid: UUID) = LocalCosmeticsRepository(SkyMyce.configPath.resolve("local-cosmetics"), uuid)
    fun initialize() {
        if (initialized) return; initialized = true
        ClientLifecycleEvents.CLIENT_STOPPING.register { worker.shutdown(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }
    fun observeOwner() {
        val uuid = MC.instance.user.profileId
        if (uuid == owner) return
        owner = uuid; epoch++; state = LocalAppearance(null); ready = false; busy = false; revision++
        work("Loading local appearance…", true) { _, repo -> repo.read() }
    }
    fun equipped() = state.profile.takeIf { ready && owner == MC.instance.user.profileId }
    fun reload() { if (!busy) work("Loading local appearance…", true) { _, repo -> repo.read() } }
    fun equip(draft: CosmeticProfile) = work("Equipping locally…") { current, repo -> repo.equip(current, draft) }
    fun import(draft: CosmeticProfile, path: String) = work("Validating local cape…") { current, repo -> repo.import(current, draft, Path.of(path.trim()).toAbsolutePath().normalize()) }
    fun delete(asset: CapeAsset) = work("Deleting local cape…") { current, repo -> repo.delete(current, asset) }
    fun useShared() = work("Using community appearance…") { current, repo -> repo.useShared(current) }
    internal fun localBytes(profile: CosmeticProfile): ByteArray {
        require(profile.localCape && profile.capeAsset != null)
        require(owner == profile.uuid && owner == MC.instance.user.profileId && profile.capeAsset in state.assets)
        return repository(profile.uuid).bytes(profile.capeAsset)
    }
    internal fun localFile(profile: CosmeticProfile): Path? = profile.capeAsset?.takeIf { profile.localCape && it in state.assets && profile.uuid == owner }?.let { repository(profile.uuid).file(it) }
    private fun work(message: String, loading: Boolean = false, action: (LocalAppearance, LocalCosmeticsRepository) -> LocalAppearance) {
        if (busy || !loading && !ready) { status = "Wait for the current local appearance operation, then retry."; revision++; return }
        val uuid = owner ?: return; val requestEpoch = epoch; val captured = state
        busy = true; status = message; revision++
        try { worker.execute {
            val result = runCatching { action(captured, repository(uuid)) }
            MC.instance.execute {
                if (owner != uuid || epoch != requestEpoch || MC.instance.user.profileId != uuid) return@execute
                busy = false
                result.fold({ state = it; ready = true; status = if (it.profile != null) "Equipped locally • saved on this computer" else "Using community / Minecraft appearance"; CosmeticsClient.localChanged() },
                    { status = it.message?.take(180) ?: "Local appearance could not be saved. Your current appearance is unchanged." })
                revision++
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { busy = false; status = "Local appearance worker is busy. Retry shortly."; revision++ }
    }
}
