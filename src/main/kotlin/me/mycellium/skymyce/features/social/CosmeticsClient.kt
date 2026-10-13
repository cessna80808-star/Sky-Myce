package me.mycellium.skymyce.features.social

import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.PlayerMessageEvent
import me.mycellium.skymyce.config.misc.CosmeticsConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
        if (!priority && uuid != null && entries.any { it.key != key && it.value.uuid == uuid && it.value.priorityUntil > now }) return
        // The normal roster refresh reports the same identity repeatedly. Preserve its
        // lookup/priority state without sweeping duplicate UUIDs or replacing the DTO.
        if (previous != null && (uuid == null || previous.uuid == uuid && !previous.byName) && previous.name == name) {
            previous.seen = now
            previous.priorityUntil = maxOf(previous.priorityUntil, if (priority) now + 600000 else 0)
            entries.putLast(key, previous)
            return
        }
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
    private var forceOwn = false
    var revision = 0L
        private set
    var status = "Waiting for Hypixel"
        private set
    var linkCode: String? = null
        private set
    var linkExpires = 0L
        private set

    override fun init() { CapeTextures.initialize(); LocalCosmetics.initialize(); ClientTickEvents.END_CLIENT_TICK.register { RuntimeProfiler.measure(RuntimeProfiler.Area.COSMETIC_TICK) { tick() } } }
    override fun tick() {
        LocalCosmetics.observeOwner()
        if (!isEnabled()) { if (enabled || identity != null) clear(); return }
        val now = System.currentTimeMillis()
        if (!LocationAPI.onHypixel || MC.instance.player == null || identity != null && identity != MC.instance.user.profileId) {
            if (identity != null || enabled) clear()
            updateNames(now); status = "Local cosmetics work here • join Hypixel to synchronize community cosmetics"; return
        }
        identity = MC.instance.user.profileId
        val wanted = CosmeticsConfig.names || CosmeticsConfig.scaling
        if (CosmeticNames.enabled != CosmeticsConfig.names) { CosmeticNames.enabled = CosmeticsConfig.names; MC.instance.gui.chat.rescaleChat() }
        if (enabled && !wanted) {
            DungeonFriendRelay.disableCosmetics()
            clear(); identity = MC.instance.user.profileId; status = "Cosmetic rendering disabled"
        }
        enabled = wanted
        if (now < nextTick) return
        nextTick = now + 1000
        if (cache.expire(now)) revision++
        if (linkCode != null && now >= linkExpires) { linkCode = null; revision++ }
        // Owner refresh/link UI remains usable with rendering disabled. Background
        // discovery has no consumers when both visual options are off.
        if (!wanted && !forceOwn) return
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
        if (!DungeonFriendRelay.connected || !DungeonFriendRelay.cosmeticsAvailable) {
            status = if (DungeonFriendRelay.connected) "Relay does not support cosmetics" else "Relay offline • cached cosmetics expire after 10 minutes"; return
        }
        if (lookup != null) return
        // Retained social identities continue refreshing even after a player leaves the tab roster.
        val visible = (listOf(MC.instance.user.profileId) + if (wanted) identities.uuids() else emptyList()).distinct()
        val pendingNames = if (wanted && DungeonFriendRelay.cosmeticsNamesAvailable) identities.names(now) else emptyList()
        val ids = visible.filter { cache.get(it, now) == null || (checked[it] ?: 0) + 300000 <= now }.take(16 - minOf(8, pendingNames.size))
        val names = pendingNames.take(16 - ids.size)
        if (ids.isEmpty() && names.isEmpty()) return
        val token = generation
        val owner = MC.instance.user.profileId
        val fields = mutableMapOf<String, Any>("uuids" to ids.map { it.toString().replace("-", "") })
        if (names.isNotEmpty()) fields["names"] = names
        val request = DungeonFriendRelay.cosmeticsRequest("cosmetics_get", fields) ?: return
        ids.forEach { checked.putIfAbsent(it, 0) } // Accept a newer live update while this lookup is still pending.
        forceOwn = false
        lookup = request
        request.whenComplete { result, _ -> MC.instance.execute {
            if (!cosmeticLookupCurrent(generation, token, identity, owner, MC.instance.user.profileId, LocationAPI.onHypixel)) return@execute
            lookup = null
            val parsed = parseCosmeticsLookup(result, ids, names)
            if (parsed == null) {
                status = "Cosmetics unavailable • retrying in 10 seconds"; nextTick = System.currentTimeMillis() + 10000
            } else {
                val received = System.currentTimeMillis()
                identities.complete(names, parsed.identities, received)
                parsed.records.forEach { cache.apply(it, received); checked[it.uuid] = received }
                while (checked.size > 512) checked.remove(checked.keys.first())
                status = "Cosmetics synchronized • visual only"; revision++
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
    fun closed() { generation++; lookup?.cancel(false); lookup = null; linkCode = null; revision++ }
    fun clear() {
        closed(); cache.clear(); checked.clear(); identities.clear()
        val namesEnabled = CosmeticNames.enabled
        CosmeticNames.enabled = false
        val namesChanged = CosmeticNames.update(emptyMap())
        if (namesEnabled || namesChanged) MC.instance.gui.chat.rescaleChat()
        identity = null; enabled = false; forceOwn = false; nextTick = 0
    }
    override fun onDisable() { DungeonFriendRelay.disableCosmetics(); clear() }
    fun event(json: JsonObject) {
        if (!enabled || !LocationAPI.onHypixel) return
        val record = parseCosmeticProfile(json) ?: return
        // Bloom subscription false positives are ignored; only requested/visible identities enter the cache.
        if (record.uuid !in checked) return
        if (cache.apply(record, System.currentTimeMillis())) { revision++; updateNames(System.currentTimeMillis()) }
    }
    fun own() = LocalCosmetics.equipped() ?: cache.get(MC.instance.user.profileId, System.currentTimeMillis())
    fun acceptOwn(profile: CosmeticProfile) {
        if (profile.uuid != MC.instance.user.profileId) return
        if (cache.apply(profile, System.currentTimeMillis())) { checked[profile.uuid] = System.currentTimeMillis(); revision++; updateNames(System.currentTimeMillis()) }
    }
    fun refreshOwn() { checked.remove(MC.instance.user.profileId); forceOwn = true; nextTick = 0 }
    fun requestLink() {
        linkCode = null
        if (!LocationAPI.onHypixel || !DungeonFriendRelay.cosmeticsAvailable) { status = "An authenticated cosmetics-enabled relay connection is required"; revision++; return }
        val request = DungeonFriendRelay.cosmeticsRequest("cosmetics_link") ?: run { status = "Relay busy • retry shortly"; revision++; return }
        val token = generation
        request.whenComplete { result, _ -> MC.instance.execute {
            if (token != generation) return@execute
            val code = runCatching { result?.get("code")?.asString?.takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}|[a-f0-9]{32}")) } }.getOrNull()
            val expires = runCatching { result?.get("expiresAt")?.asLong }.getOrNull()
            val now = System.currentTimeMillis()
            if (code != null && expires != null && expires in now + 1..now + 360000) {
                linkCode = code; linkExpires = expires; status = "Use /cosmetics link in bot-commands (1552057526969835612) within five minutes"
            } else status = when (result?.get("error")?.asString) {
                "not_configured" -> "The relay operator has not configured Discord interactions"
                "rate_limited" -> "Wait a minute before requesting another link code"
                else -> "Could not issue a link code • reconnect and retry"
            }
            revision++
        } }
    }
    private fun updateNames(now: Long) {
        val known = identities.resolved().mapNotNull { (uuid, real) -> cache.get(uuid, now)?.nameStyle?.let { real to it } }.toMap().toMutableMap()
        LocalCosmetics.equipped()?.let { local -> known.keys.removeIf { it.equals(MC.instance.user.name, true) }; local.nameStyle?.let { known[MC.instance.user.name] = it } }
        CosmeticNames.enabled = isEnabled() && CosmeticsConfig.names
        if (CosmeticNames.update(known)) MC.instance.gui.chat.rescaleChat()
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
