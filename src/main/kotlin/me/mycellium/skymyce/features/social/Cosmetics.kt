package me.mycellium.skymyce.features.social

import com.google.gson.*
import com.google.gson.JsonObject
import java.nio.file.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.PlayerMessageEvent
import me.mycellium.skymyce.config.misc.CosmeticsConfig
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI

data class CosmeticProfile(val uuid: UUID, val name: String?, val scale: Float, val revision: Long, val updatedAt: Long,
    val nameStyle: Component? = name?.let(Component::literal), val scaleX: Float = scale, val scaleY: Float = scale, val scaleZ: Float = scale,
    val nametagScale: Float = 1f, val capePreset: String? = null, val capeAsset: CapeAsset? = null, val localCape: Boolean = false)
data class CapeAsset(val id: String, val sha256: String, val size: Int, val width: Int = 64, val height: Int = 32)
fun parseCosmeticProfile(json: JsonObject): CosmeticProfile? = runCatching {
    val legacy = setOf("uuid", "name", "scale", "revision", "updatedAt")
    val extended = json.has("scaleX")
    val studio = json.has("nametagScale")
    require(json.keySet() == if (studio) legacy + setOf("nameStyle", "scaleX", "scaleY", "scaleZ", "nametagScale", "capePreset", "capeAsset") else if (extended) legacy + setOf("nameStyle", "scaleX", "scaleY", "scaleZ") else legacy)
    val id = json.get("uuid").also { require(it.asJsonPrimitive.isString) }.asString.also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    val uuid = UUID.fromString("${id.take(8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}")
    val name = json.get("name").takeUnless { it.isJsonNull }?.also { require(it.asJsonPrimitive.isString) }?.asString?.also {
        require(it.length in 1..32 && it.matches(Regex("[\\p{L}\\p{N}\\p{S} _.'•·»«-]+")) && it.none { char -> char == '§' || char == '@' } && it == it.trim() && Normalizer.normalize(it, Normalizer.Form.NFKC) == it)
    }
    val scale = json.get("scale").asDouble.also { require(it.isFinite() && it in .1..3.0 && json.get("scale").asJsonPrimitive.isNumber) }.toFloat()
    fun integer(key: String) = json.get(key).also { require(it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,16}"))) }.asLong.also { require(it in 0..9007199254740991L) }
    val revision = integer("revision"); val time = integer("updatedAt")
    require(revision != 0L || (name == null && scale == 1f && time == 0L))
    require(revision == 0L || time > 0)
    fun axis(key: String): Float = if (!extended) scale else json.get(key).also { require(it.asJsonPrimitive.isNumber) }.asDouble
        .also { require(it.isFinite() && it in .1..3.0) }.toFloat()
    val style = if (!extended) name?.let(Component::literal) else json.get("nameStyle").takeUnless { it.isJsonNull }?.let(::parseCosmeticText)
    require(style?.string == name)
    val x = axis("scaleX"); val y = axis("scaleY"); val z = axis("scaleZ")
    require(scale == y && (revision != 0L || x == 1f && y == 1f && z == 1f))
    val tag = if (studio) json["nametagScale"].also { require(it.asJsonPrimitive.isNumber) }.asDouble.also { require(it.isFinite() && it in .5..2.0) }.toFloat() else 1f
    val preset = if (studio) json["capePreset"].takeUnless { it.isJsonNull }?.also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
        ?.asString?.also { require(it == "none" || it in CapeTextures.presets) } else null
    val asset = if (studio) json["capeAsset"].takeUnless { it.isJsonNull }?.let { parseCapeAsset(it.asJsonObject) } else null
    require(preset == null || asset == null)
    require(revision != 0L || tag == 1f && preset == null && asset == null)
    CosmeticProfile(uuid, name, scale, revision, time, style, x, y, z, tag, preset, asset)
}.getOrNull()

fun parseCapeAsset(json: JsonObject): CapeAsset {
    require(json.keySet() == setOf("id", "sha256", "size", "width", "height"))
    fun string(key: String) = json[key].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
    fun integer(key: String) = json[key].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,9}"))) }.asInt
    val id = string("id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    val hash = string("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val size = integer("size").also { require(it in 1..262144) }
    require(integer("width") == 64 && integer("height") == 32)
    return CapeAsset(id, hash, size)
}

/** A reset remains a revisioned record. Reads are O(1), including in render-state extraction. */
class CosmeticCache(private val maximum: Int = 512) {
    init { require(maximum in 1..512) }
    private data class Entry(val profile: CosmeticProfile, val received: Long)
    private val values = linkedMapOf<UUID, Entry>()
    fun apply(profile: CosmeticProfile, now: Long): Boolean {
        val previous = values[profile.uuid]?.profile
        if (previous != null && (profile.revision < previous.revision || profile.revision == previous.revision && profile != previous)) return false
        values.putLast(profile.uuid, Entry(profile, now))
        while (values.size > maximum) values.remove(values.keys.first())
        return true
    }
    fun get(uuid: UUID, now: Long, touch: Boolean = false): CosmeticProfile? {
        val entry = values[uuid]?.takeIf { now - it.received in 0..600000 } ?: return null
        // Rendered players survive background lookup pressure without extending their offline lifetime.
        if (touch) values.putLast(uuid, entry)
        return entry.profile
    }
    fun clear() = values.clear()
    fun expire(now: Long): Boolean = values.entries.removeIf { now - it.value.received !in 0..600000 }
    val size get() = values.size
}

/** Names from chat are lookup selectors, never proof of a UUID. Keep discovery outside rendering. */
internal class CosmeticIdentities(private val maximum: Int = 512) {
    private data class Entry(val name: String, var uuid: UUID?, var seen: Long, val byName: Boolean, var checked: Long? = null, var priorityUntil: Long = 0)
    private val entries = linkedMapOf<String, Entry>()
    init { require(maximum in 1..512) }
    fun observe(name: String, uuid: UUID?, now: Long, priority: Boolean = false) {
        if (!NAME.matches(name)) return
        val key = name.lowercase()
        val previous = entries[key]
        // The normal roster refresh reports the same identity repeatedly. Preserve its
        // lookup/priority state without sweeping duplicate UUIDs or replacing the DTO.
        // This fast path comes first: the duplicate-UUID sweep below is linear and runs ~1000 times a second otherwise.
        if (previous != null && (uuid == null || previous.uuid == uuid && !previous.byName) && previous.name == name) {
            previous.seen = now
            previous.priorityUntil = maxOf(previous.priorityUntil, if (priority) now + 600000 else 0)
            entries.putLast(key, previous)
            return
        }
        if (!priority && uuid != null && entries.any { it.key != key && it.value.uuid == uuid && it.value.priorityUntil > now }) return
        if (previous == null && entries.size >= maximum) {
            if (!priority) return // Bulk cache scans cannot displace recent chat, tab or menu identities.
            entries.remove(entries.entries.firstOrNull { it.value.priorityUntil <= now }?.key ?: entries.keys.first())
        }
        entries.remove(key)
        if (uuid != null) entries.entries.removeIf { it.value.uuid == uuid }
        val entry = if (uuid == null && previous != null) previous.apply { seen = now }
            else Entry(name, uuid, now, uuid == null)
        entry.priorityUntil = maxOf(previous?.priorityUntil ?: 0, if (priority) now + 600000 else 0)
        entries[key] = entry
    }
    fun expire(now: Long) { entries.entries.removeIf { now - it.value.seen !in 0..600000 } }
    fun uuids() = entries.values.filter { !it.byName }.mapNotNull { it.uuid }.distinct()
    fun names(now: Long) = entries.values.filter { it.byName && (it.checked == null || now - it.checked!! !in 0 until 300000) }
        .sortedByDescending { it.priorityUntil }.map { it.name }
    fun resolved() = entries.values.mapNotNull { entry -> entry.uuid?.let { it to entry.name } }.toMap()
    fun complete(names: List<String>, resolved: Map<String, UUID>, now: Long) {
        for (name in names) entries[name.lowercase()]?.takeIf { it.byName }?.let { entry ->
            entry.checked = now; entry.uuid = resolved[name.lowercase()]
        }
    }
    fun reconnect() { entries.values.forEach { it.checked = null } }
    fun clear() = entries.clear()
    val size get() = entries.size
    private companion object { val NAME = Regex("[A-Za-z0-9_]{1,16}") }
}

internal data class CosmeticLookupResult(val records: List<CosmeticProfile>, val identities: Map<String, UUID>)
internal fun cosmeticLookupCurrent(generation: Long, expectedGeneration: Long, identity: UUID?, expectedIdentity: UUID, currentIdentity: UUID, onHypixel: Boolean) =
    generation == expectedGeneration && identity == expectedIdentity && currentIdentity == expectedIdentity && onHypixel

/** Named responses must account for exactly the requested UUIDs plus the requested, resolved names. */
internal fun parseCosmeticsLookup(result: JsonObject?, ids: List<UUID>, names: List<String>): CosmeticLookupResult? = runCatching {
    require(result != null && ids.size + names.size in 1..16)
    val raw = result.get("records").also { require(it.isJsonArray && it.asJsonArray.size() <= 16) }.asJsonArray
    val records = raw.map { requireNotNull(parseCosmeticProfile(it.asJsonObject)) }
    require(records.map { it.uuid }.distinct().size == records.size)
    val identities = linkedMapOf<String, UUID>()
    if (names.isNotEmpty()) {
        val requested = names.map { it.lowercase() }.toSet()
        val resolved = result.get("identities").also { require(it.isJsonArray && it.asJsonArray.size() <= requested.size) }.asJsonArray
        for (value in resolved) {
            val row = value.asJsonObject
            require(row.keySet() == setOf("name", "uuid") && row.get("name").asJsonPrimitive.isString && row.get("uuid").asJsonPrimitive.isString)
            val name = row.get("name").asString
            val id = row.get("uuid").asString
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")) && name.lowercase() in requested && name.lowercase() !in identities && id.matches(Regex("[a-f0-9]{32}")))
            identities[name.lowercase()] = UUID.fromString("${id.take(8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}")
        }
        require(identities.values.distinct().size == identities.size)
    } else require(!result.has("identities") || result.get("identities").isJsonArray && result.getAsJsonArray("identities").isEmpty)
    require(records.map { it.uuid }.toSet() == ids.toSet() + identities.values)
    CosmeticLookupResult(records, identities)
}.getOrNull()

object CosmeticsClient : SkyMyceModule() {
    private val cache = CosmeticCache()
    private val checked = linkedMapOf<UUID, Long>()
    private val identities = CosmeticIdentities()
    private var lookup: CompletableFuture<JsonObject?>? = null
    private var generation = 0L
    private var nextTick = 0L
    private var identity: UUID? = null
    private var enabled = false
    /** A chat re-wrap is owed (names changed); done at most every 2 s, outside the tick, because it re-wraps all history. */
    private var rescalePending = false
    private var nextRescale = 0L
    private var nextOffHypixelNames = 0L
    var revision = 0L
        private set

    override fun init() { CapeTextures.initialize(); LocalCosmetics.initialize(); ClientTickEvents.END_CLIENT_TICK.register { RuntimeProfiler.measure(RuntimeProfiler.Area.COSMETIC_TICK) { tick() } } }
    private fun requestRescale() { rescalePending = true }

    private fun flushRescale(now: Long) {
        if (!rescalePending || now < nextRescale) return
        rescalePending = false; nextRescale = now + 2_000
        // Never during the tick's own iteration of chat state (a ConcurrentModificationException was seen there).
        MC.instance.execute { MC.instance.gui.chat.rescaleChat() }
    }

    override fun tick() {
        LocalCosmetics.observeOwner()
        val now = System.currentTimeMillis()
        flushRescale(now)
        if (!isEnabled()) { if (enabled || identity != null) clear(); return }
        if (!LocationAPI.onHypixel || MC.instance.player == null || identity != null && identity != MC.instance.user.profileId) {
            if (identity != null || enabled) clear()
            // Local cosmetics only change on their own events; once a second is plenty here.
            if (now >= nextOffHypixelNames) { nextOffHypixelNames = now + 1_000; updateNames(now) }
            return
        }
        identity = MC.instance.user.profileId
        val wanted = CosmeticsConfig.names || CosmeticsConfig.scaling
        if (CosmeticNames.enabled != CosmeticsConfig.names) { CosmeticNames.enabled = CosmeticsConfig.names; requestRescale() }
        if (enabled && !wanted) {
            DungeonFriendRelay.disableCosmetics()
            clear(); identity = MC.instance.user.profileId
        }
        enabled = wanted
        if (now < nextTick) return
        nextTick = now + 1000
        if (cache.expire(now)) revision++
        // Background discovery has no consumers when both visual options are off.
        if (!wanted) return
        // Reuse cached social identities without issuing friend scans or Mojang requests.
        val roster = MC.connection?.onlinePlayers.orEmpty().asSequence().take(256).toList()
        FriendsAPI.friends.asSequence().take(512).forEach { identities.observe(it.name, it.uuid, now) }
        (DungeonFriends.scanner.online.values.asSequence() + DungeonFriends.scanner.all.values.asSequence())
            .distinctBy { it.name.lowercase() }.take(512).forEach { identities.observe(it.name, null, now) }
        PartyAPI.members.asSequence().take(256).forEach { member -> member.name?.let { identities.observe(it, member.uuid, now, priority = true) } }
        roster.forEach { player ->
            identities.observe(player.profile.name(), player.profile.id(), now, priority = true)
        }
        identities.observe(MC.instance.user.name, MC.instance.user.profileId, now, priority = true)
        identities.expire(now)
        checked.keys.retainAll(identities.resolved().keys)
        updateNames(now)
        if (!DungeonFriendRelay.connected || !DungeonFriendRelay.cosmeticsAvailable) return
        if (lookup != null) return
        // Retained social identities continue refreshing even after a player leaves the tab roster.
        val visible = (listOf(MC.instance.user.profileId) + identities.uuids()).distinct()
        val pendingNames = if (DungeonFriendRelay.cosmeticsNamesAvailable) identities.names(now) else emptyList()
        val ids = visible.filter { cache.get(it, now) == null || (checked[it] ?: 0) + 300000 <= now }.take(16 - minOf(8, pendingNames.size))
        val names = pendingNames.take(16 - ids.size)
        if (ids.isEmpty() && names.isEmpty()) return
        val token = generation
        val owner = MC.instance.user.profileId
        val fields = mutableMapOf<String, Any>("uuids" to ids.map { it.toString().replace("-", "") })
        if (names.isNotEmpty()) fields["names"] = names
        val request = DungeonFriendRelay.cosmeticsRequest("cosmetics_get", fields) ?: return
        ids.forEach { checked.putIfAbsent(it, 0) } // Accept a newer live update while this lookup is still pending.
        lookup = request
        request.whenComplete { result, _ -> MC.instance.execute {
            if (!cosmeticLookupCurrent(generation, token, identity, owner, MC.instance.user.profileId, LocationAPI.onHypixel)) return@execute
            lookup = null
            val parsed = parseCosmeticsLookup(result, ids, names)
            if (parsed == null) {
                nextTick = System.currentTimeMillis() + 10000
            } else {
                val received = System.currentTimeMillis()
                identities.complete(names, parsed.identities, received)
                parsed.records.forEach { cache.apply(it, received); checked[it.uuid] = received }
                while (checked.size > 512) checked.remove(checked.keys.first())
                revision++
                updateNames(received)
            }
            if (!enabled) DungeonFriendRelay.disableCosmetics()
        } }
    }
    @Subscription
    fun onPlayerMessage(event: PlayerMessageEvent) { observe(event.player, null) }
    fun observe(name: String, uuid: UUID?) {
        if (!LocationAPI.onHypixel || !enabled || identity != MC.instance.user.profileId) return
        identities.observe(name, uuid, System.currentTimeMillis(), priority = true)
    }
    fun ready() { generation++; lookup?.cancel(false); lookup = null; checked.clear(); identities.reconnect(); nextTick = 0; revision++ }
    fun closed() { generation++; lookup?.cancel(false); lookup = null; revision++ }
    fun clear() {
        closed(); cache.clear(); checked.clear(); identities.clear()
        // An empty name map already turns the text hook off; flipping the enabled flag here caused a second re-wrap.
        if (CosmeticNames.update(emptyMap())) requestRescale()
        identity = null; enabled = false; nextTick = 0
    }
    override fun onDisable() { DungeonFriendRelay.disableCosmetics(); clear() }
    fun event(json: JsonObject) {
        if (!enabled || !LocationAPI.onHypixel) return
        val record = parseCosmeticProfile(json) ?: return
        // Bloom subscription false positives are ignored; only requested/visible identities enter the cache.
        if (record.uuid !in checked) return
        if (cache.apply(record, System.currentTimeMillis())) { revision++; updateNames(System.currentTimeMillis()) }
    }
    fun acceptOwn(profile: CosmeticProfile) {
        if (profile.uuid != MC.instance.user.profileId) return
        if (cache.apply(profile, System.currentTimeMillis())) { checked[profile.uuid] = System.currentTimeMillis(); revision++; updateNames(System.currentTimeMillis()) }
    }
    private fun updateNames(now: Long) {
        val known = identities.resolved().mapNotNull { (uuid, real) -> cache.get(uuid, now)?.nameStyle?.let { real to it } }.toMap().toMutableMap()
        LocalCosmetics.equipped()?.let { local -> known.keys.removeIf { it.equals(MC.instance.user.name, true) }; local.nameStyle?.let { known[MC.instance.user.name] = it } }
        CosmeticNames.enabled = isEnabled() && CosmeticsConfig.names
        if (CosmeticNames.update(known)) requestRescale()
    }
    fun localChanged() { revision++; if (isEnabled()) updateNames(System.currentTimeMillis()) }
    @JvmStatic fun rendering(uuid: UUID): CosmeticProfile? {
        if (!isEnabled()) return null
        if (uuid == MC.instance.user.profileId) LocalCosmetics.equipped()?.let { return it }
        return if (enabled && LocationAPI.onHypixel) cache.get(uuid, System.currentTimeMillis(), touch = true) else null
    }
    fun diagnostics(): Map<String, Long> = mapOf("cosmetics.records" to cache.size.toLong(), "cosmetics.identities" to identities.size.toLong(),
        "cosmetics.checked" to checked.size.toLong(), "cosmetics.pending" to if (lookup != null) 1L else 0L)
}

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
    fun equip(draft: CosmeticProfile) = work("Equipping locally…") { current, repo -> repo.equip(current, draft) }
    fun import(draft: CosmeticProfile, path: String) = work("Validating local cape…") { current, repo -> repo.import(current, draft, Path.of(path.trim()).toAbsolutePath().normalize()) }
    fun delete(asset: CapeAsset) = work("Deleting local cape…") { current, repo -> repo.delete(current, asset) }
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

/** Blank restores the default color; incomplete or malformed hex must never change the preview. */
internal fun parseCosmeticColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (value.isBlank()) return null
    require(hex.matches(Regex("[a-fA-F0-9]{6}"))) { "Use six hex digits, such as #62D7F1, or leave blank for default." }
    return hex.toInt(16)
}

/** A style can decorate the real name without requiring a replacement name. */
internal fun cosmeticDraftName(value: String, realName: String, color: Int?, bold: Boolean): JsonObject? {
    if (value.isBlank() && color == null && !bold) return null
    val text = value.trim().ifEmpty { realName }
    return JsonObject().apply {
        addProperty("text", text)
        color?.let { require(it in 0..0xffffff); addProperty("color", "#%06x".format(Locale.ROOT, it)) }
        addProperty("bold", bold)
    }.also { parseCosmeticText(it) }
}

internal enum class CosmeticDraftField { NAME, WIDTH, HEIGHT, DEPTH, NAMETAG, CAPE }

internal fun cosmeticFieldMatches(a: CosmeticProfile, b: CosmeticProfile, field: CosmeticDraftField): Boolean = when (field) {
    CosmeticDraftField.NAME -> a.name == b.name && a.nameStyle?.let(::cosmeticTextJson) == b.nameStyle?.let(::cosmeticTextJson)
    CosmeticDraftField.WIDTH -> a.scaleX == b.scaleX
    CosmeticDraftField.HEIGHT -> a.scaleY == b.scaleY
    CosmeticDraftField.DEPTH -> a.scaleZ == b.scaleZ
    CosmeticDraftField.NAMETAG -> a.nametagScale == b.nametagScale
    CosmeticDraftField.CAPE -> a.capePreset == b.capePreset && a.capeAsset == b.capeAsset && a.localCape == b.localCape
}

/** Late reads/saves adopt authoritative metadata and untouched fields, preserving subsequent local edits. */
internal fun mergeCosmeticDraft(saved: CosmeticProfile, draft: CosmeticProfile, edited: Set<CosmeticDraftField>): CosmeticProfile {
    require(saved.uuid == draft.uuid)
    var result = saved
    if (CosmeticDraftField.NAME in edited) result = result.copy(name = draft.name, nameStyle = draft.nameStyle)
    if (CosmeticDraftField.WIDTH in edited) result = result.copy(scaleX = draft.scaleX)
    if (CosmeticDraftField.HEIGHT in edited) result = result.copy(scale = draft.scaleY, scaleY = draft.scaleY)
    if (CosmeticDraftField.DEPTH in edited) result = result.copy(scaleZ = draft.scaleZ)
    if (CosmeticDraftField.NAMETAG in edited) result = result.copy(nametagScale = draft.nametagScale)
    if (CosmeticDraftField.CAPE in edited) result = result.copy(capePreset = draft.capePreset, capeAsset = draft.capeAsset, localCape = draft.localCape)
    return result
}
