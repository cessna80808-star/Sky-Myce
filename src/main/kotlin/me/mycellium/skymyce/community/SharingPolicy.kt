package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class SharingCategory(val wire: String) { DUNGEONS("dungeons"), ECONOMY("economy"), RNG("rng"), ACTIVITY("activity"), LEADERBOARDS("leaderboards") }

/** The one user-facing sharing choice. Each state maps onto the relay's unchanged seven-key policy. */
enum class SharingMode(private val title: String) {
    ON("On"), ANONYMOUS("On (anon)"), OFF("Off");

    override fun toString() = title

    val policy: SharingPolicy get() = when (this) {
        ON -> SharingPolicy(publicName = true)
        ANONYMOUS -> SharingPolicy()
        OFF -> SharingPolicy(enabled = false)
    }

    companion object {
        /** Any withheld category reads as Off, so nothing the player turned off starts being shared. */
        fun of(policy: SharingPolicy): SharingMode = when {
            !policy.enabled || SharingCategory.entries.any { !policy.value(it.wire) } -> OFF
            policy.publicName -> ON
            else -> ANONYMOUS
        }
    }
}

/**
 * One-time migration from the 2.7.29 per-category toggles, read from the raw saved file. Returns null when the file
 * already has a mode or never had sharing settings. Consumed legacy keys are removed so it runs once.
 */
internal fun migratedSharingMode(original: JsonObject): SharingMode? {
    fun JsonObject.child(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    val sharing = original.child("Community Sharing")
    val digest = original.child("General")?.child("Daily Digest")
    if (sharing?.has("mode") == true || sharing == null && digest?.has("shareRng") != true && digest?.has("publicRngName") != true) return null
    fun flag(section: JsonObject?, key: String, default: Boolean) =
        section?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default
    // The Daily Digest RNG toggles only count if they were never folded into Community Sharing; later edits there win.
    val folded = flag(sharing, "legacySharingMigrated", false)
    val categories = listOf("dungeons", "economy", "rng", "activity", "leaderboards").map { flag(sharing, it, true) } +
        if (folded) emptyList() else listOf(flag(digest, "shareRng", true))
    val named = flag(sharing, "publicName", false) || !folded && flag(digest, "publicRngName", false)
    val mode = when {
        !flag(sharing, "enabled", true) || categories.any { !it } -> SharingMode.OFF
        named -> SharingMode.ON
        else -> SharingMode.ANONYMOUS
    }
    listOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName", "legacySharingMigrated").forEach { sharing?.remove(it) }
    digest?.remove("shareRng"); digest?.remove("publicRngName")
    return mode
}

data class SharingPolicy(
    val enabled: Boolean = true, val dungeons: Boolean = true, val economy: Boolean = true,
    val rng: Boolean = true, val activity: Boolean = true, val leaderboards: Boolean = true, val publicName: Boolean = false,
) {
    fun value(key: String): Boolean = when (key) {
        "enabled" -> enabled; "dungeons" -> dungeons; "economy" -> economy; "rng" -> rng
        "activity" -> activity; "leaderboards" -> leaderboards; "publicName" -> publicName
        else -> false
    }
    fun allows(category: SharingCategory): Boolean = enabled && value(category.wire)
    fun json(): JsonObject = JsonObject().apply { addProperty("version", 1); keys.forEach { addProperty(it, value(it)) } }
    fun withValues(values: Map<String, Boolean>): SharingPolicy = parse(json().apply { values.forEach { (key, value) -> addProperty(key, value) } })!!
    fun restrictivelyMerge(other: SharingPolicy, initialPublicName: Boolean = false): SharingPolicy = withValues(keys.associateWith {
        if (it == "publicName" && initialPublicName) other.publicName else value(it) && other.value(it)
    })
    companion object {
        val keys = listOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName")
        fun parse(value: JsonObject?): SharingPolicy? = runCatching {
            require(value != null && value.get("version")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble == 1.0 } == true)
            require(keys.all { value.get(it)?.let { field -> field.isJsonPrimitive && field.asJsonPrimitive.isBoolean } == true })
            SharingPolicy(value.get("enabled").asBoolean, value.get("dungeons").asBoolean, value.get("economy").asBoolean,
                value.get("rng").asBoolean, value.get("activity").asBoolean, value.get("leaderboards").asBoolean, value.get("publicName").asBoolean)
        }.getOrNull()
        fun parse(value: String): SharingPolicy? = runCatching { parse(JsonParser.parseString(value).asJsonObject) }.getOrNull()
    }
}

data class SharingSnapshot(val policy: SharingPolicy, val revision: Long, val notBefore: Map<SharingCategory, Long>) {
    companion object {
        fun parse(json: JsonObject?): SharingSnapshot? = runCatching {
            require(json != null)
            val policy = SharingPolicy.parse(json) ?: error("Invalid sharing policy")
            val number = json.get("revision")
            require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber)
            val revision = number.asLong
            require(revision in 0..9_007_199_254_740_991L)
            require(number.asDouble == revision.toDouble())
            val cutoffs = json.getAsJsonObject("notBefore")
            SharingSnapshot(policy, revision, SharingCategory.entries.associateWith { category ->
                (cutoffs?.get(category.wire)?.asLong ?: 0L).also { require(it >= 0) }
            })
        }.getOrNull()
    }
}

/** Old-device edits may restrict a newly selected account, but cannot enable over that account's saved opt-outs. */
internal fun desiredSharingPolicy(snapshot: SharingSnapshot, local: SharingPolicy, dirty: Set<String>, firstAccountSync: Boolean): SharingPolicy {
    if (!firstAccountSync) return snapshot.policy.withValues(dirty.filter { it in SharingPolicy.keys }.associateWith(local::value))
    val merged = snapshot.policy.restrictivelyMerge(local, initialPublicName = snapshot.revision == 0L)
    // Anonymous is a fresh-device default, not an edit to an existing account's explicitly saved name choice.
    return if (snapshot.revision > 0 && "publicName" !in dirty && !local.publicName) merged.copy(publicName = snapshot.policy.publicName) else merged
}
