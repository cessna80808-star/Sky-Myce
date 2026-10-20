package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Optional provider integration follows DungeonProgressHud; no extra bundled mod or API key is needed. */
object DungeonFriendProfileProvider {
    private val viewerRequests = ProfileLookupCooldown()
    private val skyblockerAvailable by lazy { FabricLoader.getInstance().isModLoaded("skyblocker") }
    internal val viewerAvailable by lazy { FabricLoader.getInstance().isModLoaded("skyblockpv") }
    val nextViewerRequest: Long get() = viewerRequests.nextRequest
    val available: Boolean get() = skyblockerAvailable || viewerAvailable

    fun fetch(name: String, uuid: UUID): DungeonFriendStats? {
        if (skyblockerAvailable) {
            runCatching {
                val json = fetchSkyblockerProfiles(uuid)
                DungeonFriendStats.fromProfiles(json, uuid.toString().replace("-", ""))
            }.getOrNull()?.let { return it }
        }
        if (viewerAvailable) {
            runCatching {
                val selected = fetchViewerProfile(uuid) ?: return DungeonFriendStats(StatsState.NO_PROFILE)
                val backing = getter(selected, "getBackingProfile") ?: error("No profile data")
                val future = getter(backing, "getDungeonData") as CompletableFuture<*>
                fromViewerDungeonData(future.get(30, TimeUnit.SECONDS) ?: error("No dungeon data"))
            }.getOrNull()?.let { return it }
        }
        return null // The caller can fall back to the user's Hypixel key or retry later.
    }

    private fun fetchSkyblockerProfiles(uuid: UUID): JsonObject {
        val api = Class.forName("de.hysky.skyblocker.utils.ProfileUtils")
        val future = api.getMethod("fetchFullProfileByUuid", String::class.java)
            .invoke(null, uuid.toString().replace("-", "")) as CompletableFuture<*>
        return future.get(30, TimeUnit.SECONDS) as JsonObject
    }

    internal fun fetchViewerProfile(uuid: UUID, fresh: Boolean = false): Any? =
        fetchViewerProfile(uuid, fresh, Class.forName("me.owdding.skyblockpv.api.ProfileAPI").getField("INSTANCE").get(null))

    internal fun fetchViewerProfile(uuid: UUID, fresh: Boolean, instance: Any): Any? {
        val api = instance.javaClass
        val cached = api.getMethod("getCached", Any::class.java).invoke(instance, uuid) as? List<*>
        if (fresh && cached != null) {
            // The provider has no per-player invalidation. Wait for its cache rather than clearing other mods' data.
            val ttl = (api.getMethod("getMaxCache").invoke(instance) as Number).toLong().coerceIn(1000, 300000)
            throw ProfileLookupDeferred(System.currentTimeMillis() + ttl)
        }
        val profiles = (cached ?: run {
            check(viewerRequests.start(System.currentTimeMillis())) { "Profile lookups are cooling down" }
            var success = false
            try {
                val response = CompletableFuture<List<*>>()
                // getProfiles hides failures as empty lists. Preserve errors so every caller backs off.
                val callback: (Result<List<*>>) -> Unit = { it.fold(response::complete, response::completeExceptionally) }
                api.getMethod("getDataAsync", Any::class.java, String::class.java, kotlin.Function1::class.java)
                    .invoke(instance, uuid, "skymyce", callback)
                response.get(30, TimeUnit.SECONDS).also { success = true }
            } finally {
                viewerRequests.finish(System.currentTimeMillis(), success)
            }
        }).filterNotNull()
        if (profiles.isEmpty()) return null
        return profiles.firstOrNull { getter(it, "getSelected") == true }
            ?: profiles.singleOrNull() ?: error("No selected profile")
    }

    internal fun fromViewerDungeonData(data: Any): DungeonFriendStats {
        val types = getter(data, "getDungeonTypes") as? Map<*, *> ?: error("Missing dungeon types")
        if (types["catacombs"] == null) return DungeonFriendStats(StatsState.HIDDEN)
        val json = JsonObject()
        val classes = JsonObject()
        (getter(data, "getClassExperience") as? Map<*, *>).orEmpty().forEach { (name, xp) ->
            if (name is String && xp is Number) classes.add(name, JsonObject().apply { addProperty("experience", xp) })
        }
        json.add("player_classes", classes)
        (getter(data, "getSelectedClass") as? String)?.let { json.addProperty("selected_dungeon_class", it) }
        val dungeonTypes = JsonObject()
        types.forEach { (name, type) ->
            if (name !is String || type == null) return@forEach
            val dungeon = JsonObject()
            dungeon.addProperty("experience", getter(type, "getExperience") as? Number)
            val completions = JsonObject()
            val fastest = JsonObject()
            val sPlus = JsonObject()
            (getter(type, "getFloors") as? Map<*, *>).orEmpty().forEach { (floor, value) ->
                if (value != null) {
                    val key = floor.toString()
                    completions.addProperty(key, getter(value, "getCompletions") as? Number)
                    viewerTime(value, "getFastestTime-")?.let { fastest.addProperty(key, it) }
                    viewerTime(value, "getFastestTimeSplus-")?.let { sPlus.addProperty(key, it) }
                }
            }
            dungeon.add("tier_completions", completions)
            dungeon.add("fastest_time", fastest)
            dungeon.add("fastest_time_s_plus", sPlus)
            dungeonTypes.add(name, dungeon)
        }
        json.add("dungeon_types", dungeonTypes)
        return DungeonFriendStats.fromDungeons(json)
    }

    private fun getter(instance: Any, name: String): Any? = instance.javaClass.getMethod(name).invoke(instance)

    private fun viewerTime(floor: Any, prefix: String): Long? {
        // Kotlin Duration getters return an encoded value, not milliseconds.
        val getter = floor.javaClass.methods.single { it.name.startsWith(prefix) && it.parameterCount == 0 }
        val encoded = getter.invoke(floor) as Long
        val duration = Duration::class.java.getMethod("box-impl", Long::class.javaPrimitiveType).invoke(null, encoded) as Duration
        return duration.takeIf { it.isFinite() && it.isPositive() }?.inWholeMilliseconds
    }
}

internal class ProfileLookupDeferred(val retryAt: Long) : Exception("Waiting for the provider cache to expire")

/** Both dungeon stats and wealth use the same provider budget; fallback success cannot erase a failure. */
internal class ProfileLookupCooldown {
    @Volatile var nextRequest = 0L
        private set
    private var running = false
    private var retryDelay = 60000L

    @Synchronized fun start(now: Long): Boolean {
        if (running || now < nextRequest) return false
        running = true
        return true
    }

    @Synchronized fun finish(now: Long, success: Boolean) {
        running = false
        nextRequest = now + if (success) 10000 else retryDelay
        retryDelay = if (success) 60000 else (retryDelay * 2).coerceAtMost(300000)
    }
}
