package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal const val FRIEND_WEALTH_TTL = 86400000L
private val WEALTH_NAME = Regex("[A-Za-z0-9_]{1,16}")

/** Empty work queues poll roster/provider changes at most once a second; user actions wake immediately. */
internal class WealthRosterGate {
    private var next = 0L
    fun ready(now: Long) = now >= next
    fun idle(now: Long, deadline: Long = Long.MAX_VALUE) { next = minOf(now + 1000, deadline) }
    fun wake() { next = 0L }
}

data class FriendWealth(
    val networth: Double? = null, val purse: Double? = null, val bank: Double? = null,
    val wardrobe: Double? = null, val profile: String = "", val status: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
    val hasProfile: Boolean? = null, val uuid: String? = null, val expires: Long = fetchedAt + FRIEND_WEALTH_TTL,
) {
    fun isFresh(now: Long): Boolean = hasProfile != null && now < fetchedAt + FRIEND_WEALTH_TTL

    fun canRefreshManually(now: Long): Boolean = hasProfile != false &&
        (!isFresh(now) || networth == null || purse == null || bank == null || wardrobe == null)

    fun shouldRefresh(now: Long, online: Boolean, skyBlockLocation: Boolean): Boolean = !isFresh(now) && when (hasProfile) {
        false -> skyBlockLocation
        true -> (online || expires == 0L) && now >= expires
        null -> now - fetchedAt >= 60000
    }

    internal fun nextRefresh(now: Long, online: Boolean, skyBlockLocation: Boolean): Long = when {
        hasProfile == null -> maxOf(now, fetchedAt + 60000)
        hasProfile == false && !skyBlockLocation -> Long.MAX_VALUE
        hasProfile == true && !online && expires != 0L -> Long.MAX_VALUE
        else -> maxOf(now, fetchedAt + FRIEND_WEALTH_TTL, if (hasProfile == true) expires else 0L)
    }
}

internal fun sharedFriendWealth(record: JsonObject, name: String, uuid: UUID?, now: Long): FriendWealth? = runCatching {
    val reportedName = record.get("name").asString
    if (!reportedName.matches(Regex("[A-Za-z0-9_]{1,16}")) || (uuid == null && !reportedName.equals(name, true))) return null
    val id = record.get("uuid").asString
    if (!id.matches(Regex("[a-f0-9]{32}")) || (uuid != null && id != uuid.toString().replace("-", ""))) return null
    val json = record.getAsJsonObject("wealth")
    val present = json.getAsJsonPrimitive("hasProfile").takeIf { it.isBoolean }?.asBoolean ?: return null
    val timestamp = record.getAsJsonPrimitive("fetchedAt")
    if (!timestamp.isNumber || !timestamp.asDouble.isFinite() || timestamp.asDouble != timestamp.asLong.toDouble()) return null
    val fetched = timestamp.asLong
    val ttl = if (present) FRIEND_WEALTH_TTL else 604800000L
    if (fetched < 0 || fetched <= now - ttl || fetched > now + 60000) return null
    fun money(key: String): Double? = json.get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asDouble.also { amount -> require(present && amount.isFinite() && amount in 0.0..9007199254740991.0) }
    }
    fun text(key: String, limit: Int) = json.get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString
    }.orEmpty().also { value ->
        require(value.length <= limit && value.all { it in ' '..'~' })
    }
    val resolved = UUID.fromString(id.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
    FriendWealth(money("networth"), money("purse"), money("bank"), money("wardrobe"), text("profile", 64),
        text("status", 128), minOf(fetched, now), present, resolved.toString(), minOf(fetched, now) + ttl)
}.getOrNull()

/** A busy/malformed shared lookup is not permission for every client to call the upstream API. */
internal fun wealthLookupRetryAt(response: JsonObject?, sharedAvailable: Boolean, now: Long): Long {
    if (!sharedAvailable) return 0L
    return runCatching {
        if (response == null) return now + 5000
        val retry = response.get("retryAt")?.asLong ?: 0L
        if (retry > now) return retry.coerceAtMost(now + 120000)
        if (response.has("error")) now + 60000 else 0L
    }.getOrDefault(now + 60000)
}

internal fun publicCoins(json: JsonObject?, vararg path: String): Double? = runCatching {
    var value: com.google.gson.JsonElement? = json
    path.forEach { value = value?.asJsonObject?.get(it) }
    value?.asDouble?.takeIf { it.isFinite() && it >= 0 }
}.getOrNull()

internal fun hasPublicInventory(member: JsonObject?): Boolean = runCatching {
    member?.getAsJsonObject("inventory")?.getAsJsonObject("inv_contents")?.get("data")?.asString?.isNotBlank() == true
}.getOrDefault(false)

/** Checks the complete roster in the background, reusing confirmed profile results across sessions. */
object FriendWealthCache {
    private val entries = mutableMapOf<String, FriendWealth>()
    private var loading: String? = null
    private var nextRequest = 0L
    private var store: FriendWealthStore? = null
    private var dirty = false
    private var nextSave = 0L
    private val newFriends = linkedSetOf<String>()
    private val manualRefresh = linkedSetOf<String>()
    private val waiting = mutableMapOf<String, Long>()
    private var nextManualRefresh = 0L
    private val rosterGate = WealthRosterGate()
    private var rosterVersion = -1L
    private var rosterOwner: UUID? = null
    private var sharedAvailable = false
    var version = 0L
        private set
    private val providerAvailable get() = DungeonFriendProfileProvider.viewerAvailable
    val available get() = providerAvailable || DungeonFriendRelay.sharedWealthAvailable
    val busy get() = loading != null
    fun get(name: String) = entries[name.lowercase()]
    fun isLoading(name: String) = loading == name.lowercase()
    fun isQueued(name: String) = name.lowercase() in manualRefresh
    fun diagnostics(): Map<String, Long> = mapOf("wealth.cached" to entries.size.toLong(), "wealth.apiJob" to if (busy) 1L else 0L,
        "wealth.manualQueue" to manualRefresh.size.toLong(), "wealth.retrying" to waiting.size.toLong())
    fun canRefresh(now: Long = System.currentTimeMillis()) = manualRefresh.isEmpty() && now >= nextManualRefresh

    fun refreshStatus(now: Long = System.currentTimeMillis()): String = when {
        !available -> "Offline • showing saved wealth"
        busy -> "Updating $loading • ${manualRefresh.size} queued"
        manualRefresh.isNotEmpty() -> "Waiting for API/cache cooldown • ${manualRefresh.size} queued"
        now < nextManualRefresh -> "Refresh requested • available again in ${(nextManualRefresh - now + 999) / 1000}s"
        else -> "Cached for 24h • refresh retries missing values or expired estimates"
    }

    fun initialize(file: Path) {
        try {
            val persistence = FriendWealthStore(file)
            entries.putAll(persistence.load())
            store = persistence
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not read friend wealth cache; original file preserved")
        }
        version++
    }

    fun save(force: Boolean = false) {
        if (force) rosterGate.wake()
        val persistence = store ?: return
        if (!dirty || (!force && System.currentTimeMillis() < nextSave)) return
        nextSave = System.currentTimeMillis() + 5000
        try { persistence.save(entries); dirty = false }
        catch (_: Exception) { SkyMyce.logger.warn("Could not save friend wealth cache; will retry") }
    }

    fun checkNewFriend(name: String) { newFriends += name.lowercase(); rosterGate.wake() }

    fun refresh(friends: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (!canRefresh(now)) return
        nextManualRefresh = now + 60000
        manualRefresh += friends.map { it.lowercase() }.filter { get(it)?.canRefreshManually(now) != false }
        rosterGate.wake()
        version++
    }

    internal fun finishAttempt(name: String, retryable: Boolean, now: Long, retryAt: Long = 0L) {
        val key = name.lowercase()
        // A miss/failure only delays this player. The provider enforces its own API cooldown.
        nextRequest = now + 1000
        waiting.remove(key)
        if (retryAt > now) {
            waiting[key] = retryAt
            return // A cooldown has deferred this attempt, not completed the manual refresh.
        }
        if (retryable) waiting[key] = now + 60000
        // One pass per click: a broken profile must not keep jumping ahead of the remaining friends.
        newFriends.remove(key)
        manualRefresh.remove(key)
    }

    internal fun canLookup(name: String, now: Long): Boolean =
        now >= nextRequest && now >= (waiting[name.lowercase()] ?: 0L)

    fun tick(friends: Collection<OnlineDungeonFriend>, online: Set<String>, revision: Long = 0L, owner: UUID? = null) {
        save()
        if (revision != rosterVersion || owner != rosterOwner || sharedAvailable != DungeonFriendRelay.sharedWealthAvailable) {
            rosterVersion = revision
            rosterOwner = owner
            sharedAvailable = DungeonFriendRelay.sharedWealthAvailable
            rosterGate.wake()
        }
        if (!available || busy) return
        val now = System.currentTimeMillis()
        if (!rosterGate.ready(now) || now < nextRequest || (!DungeonFriendRelay.sharedWealthAvailable && now < DungeonFriendProfileProvider.nextViewerRequest)) return
        val roster = friends.mapTo(mutableSetOf()) { it.name.lowercase() }
        manualRefresh.retainAll(roster)
        newFriends.retainAll(roster)
        val candidates = friends.filter { it.name.matches(WEALTH_NAME) && canLookup(it.name, now) }
        candidates.forEach { friend ->
            val key = friend.name.lowercase()
            val uuid = DungeonFriends.friendUuid(friend.name)?.toString()
            val entry = get(key)
            if (entry?.uuid != null && uuid != null && uuid != entry.uuid) { entries.remove(key); dirty = true; version++ }
        }
        newFriends.removeAll { get(it)?.isFresh(now) == true }
        manualRefresh.removeAll { get(it)?.canRefreshManually(now) == false }
        val friend = candidates.firstOrNull { it.name.lowercase() in newFriends }
            ?: candidates.firstOrNull { it.name.lowercase() in manualRefresh }
            ?: candidates.firstOrNull { get(it.name) == null }
            ?: candidates.firstOrNull { friend ->
                get(friend.name)?.shouldRefresh(now, friend.name.lowercase() in online,
                    friend.location.contains("SkyBlock", true) || friend.activity == FriendActivity.IN_RUN) == true
            } ?: run {
                // Wake on the exact existing freshness/retry deadline even inside the idle polling interval.
                val deadline = friends.minOfOrNull { candidate ->
                    if (!WEALTH_NAME.matches(candidate.name)) return@minOfOrNull Long.MAX_VALUE
                    val key = candidate.name.lowercase()
                    maxOf(waiting[key] ?: 0L, get(key)?.nextRefresh(now, key in online,
                        candidate.location.contains("SkyBlock", true) || candidate.activity == FriendActivity.IN_RUN) ?: now)
                } ?: Long.MAX_VALUE
                rosterGate.idle(now, deadline)
                return
            }
        val name = friend.name
        val newlyAdded = name.lowercase() in newFriends
        val manual = name.lowercase() in manualRefresh
        loading = name.lowercase()
        version++
        val knownUuid = DungeonFriends.friendUuid(name) ?: get(name)?.uuid?.let(UUID::fromString)
        val alreadyAbsent = !newlyAdded && get(name) == null && DungeonFriendStatsCache.get(name)?.state == StatsState.NO_PROFILE &&
            !friend.location.contains("SkyBlock", true) && friend.activity != FriendActivity.IN_RUN
        val sharedAvailable = DungeonFriendRelay.sharedWealthAvailable
        val sharedLookup = DungeonFriendRelay.lookupWealth(name, knownUuid?.toString()?.replace("-", ""),
            manual || get(name)?.hasProfile == false,
            providerAvailable && now >= DungeonFriendProfileProvider.nextViewerRequest)
        Scheduling.schedule(0.seconds) {
            var retryAt = 0L
            var sharedHit = false
            var upload: String? = null
            val result = RuntimeProfiler.measure(RuntimeProfiler.Area.FRIEND_API) { runCatching {
                val response = sharedLookup?.get(6, TimeUnit.SECONDS)
                retryAt = wealthLookupRetryAt(response, sharedAvailable, System.currentTimeMillis())
                if (retryAt > System.currentTimeMillis()) return@runCatching null
                val shared = response?.get("record")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { sharedFriendWealth(it, name, knownUuid, System.currentTimeMillis()) }
                if (shared != null) {
                    sharedHit = true
                    if (manual && shared.canRefreshManually(System.currentTimeMillis()) && providerAvailable) {
                        // Keep the partial result visible and the manual retry queued during provider/cache cooldowns.
                        retryAt = maxOf(System.currentTimeMillis() + 5000,
                            DungeonFriendProfileProvider.nextViewerRequest, shared.fetchedAt + 60000)
                    }
                    return@runCatching shared
                }
                upload = response?.get("upload")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                if (!providerAvailable) return@runCatching FriendWealth(status = "No shared estimate yet; install SkyBlockPv for local lookup")
                if (System.currentTimeMillis() < DungeonFriendProfileProvider.nextViewerRequest) {
                    retryAt = DungeonFriendProfileProvider.nextViewerRequest
                    return@runCatching null
                }
                if (sharedAvailable && upload == null) {
                    retryAt = System.currentTimeMillis() + 5000
                    return@runCatching null
                }
                // The same public UUID lookup as the dungeon stats cache; no private credentials are sent.
                val uuid = knownUuid ?: Http.get("https://api.mojang.com/users/profiles/minecraft/$name") {
                    val body = asText()
                    check(isOk)
                    val id = JsonParser.parseString(body).asJsonObject.get("id").asString
                    require(id.matches(Regex("[a-fA-F0-9]{32}")))
                    UUID.fromString(id.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                }
                (if (alreadyAbsent) FriendWealth(hasProfile = false, status = "No SkyBlock profile") else fetch(uuid, manual))
                    .copy(uuid = uuid.toString())
            }.getOrElse {
                if (it is ProfileLookupDeferred) { retryAt = it.retryAt; null }
                else FriendWealth(status = "API unavailable or rate limited; retrying later")
            } }
            MC.instance.execute {
                loading = null
                val completedAt = System.currentTimeMillis()
                val retryable = result?.hasProfile == null || result.status == "SkyBlock profile found; wealth data unavailable"
                finishAttempt(name, retryable, completedAt,
                    if (result == null) maxOf(retryAt, completedAt + 1000) else retryAt)
                if (result == null) {
                    version++
                    return@execute
                }
                val previous = entries[name.lowercase()]
                if (sharedHit && previous?.fetchedAt != result.fetchedAt)
                    SkyMyce.logger.info("[Friend wealth] Received cached relay estimate (age={}s)",
                        (completedAt - result.fetchedAt).coerceAtLeast(0) / 1000)
                entries[name.lowercase()] = if (retryable && previous?.hasProfile != null)
                    previous.copy(hasProfile = result.hasProfile ?: previous.hasProfile, uuid = result.uuid ?: previous.uuid,
                        status = result.status, expires = System.currentTimeMillis() + 60000) else result
                if (result.hasProfile == true && DungeonFriendStatsCache.get(name)?.state == StatsState.NO_PROFILE)
                    DungeonFriendStatsCache.request(name, result.uuid?.let(UUID::fromString), force = true)
                if (!sharedHit && upload != null && (result.hasProfile == false ||
                    listOfNotNull(result.networth, result.purse, result.bank, result.wardrobe).isNotEmpty()))
                    DungeonFriendRelay.publishWealth(name, result, upload)
                dirty = true
                version++
            }
        }
    }

    private fun fetch(uuid: UUID, fresh: Boolean): FriendWealth {
        val profile = try { DungeonFriendProfileProvider.fetchViewerProfile(uuid, fresh) }
        catch (failure: Exception) {
            if (failure is ProfileLookupDeferred) throw failure
            // Some providers reject Hypixel's valid profiles:null response; verify it through the raw provider.
            val present = DungeonFriendProfileProvider.profilePresence(uuid) ?: throw failure
            return FriendWealth(hasProfile = present, status = if (present) "SkyBlock profile found; wealth data unavailable" else "No SkyBlock profile")
        }
            ?: return FriendWealth(hasProfile = false, status = "No SkyBlock profile")
        return runCatching { fromProfile(profile) }.getOrElse {
            FriendWealth(hasProfile = true, status = "SkyBlock profile found; wealth data unavailable")
        }
    }

    internal fun fromProfile(profile: Any): FriendWealth {
        val backing = read(profile, "getBackingProfile") ?: error("No profile")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun await(instance: Any, getter: String) = runCatching {
            (read(instance, getter) as CompletableFuture<*>).get(
                (deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        }.getOrNull()
        val currency = await(backing, "getCurrency")
        val banking = await(backing, "getBank")
        val member = currency?.let { read(it, "getJson") as? JsonObject }
        val inventory = if (hasPublicInventory(member)) await(backing, "getInventory") else null
        val purse = publicCoins(member, "currencies", "coin_purse")
        val bankJson = banking?.let { read(it, "getJson") as? JsonObject }
        val personal = banking?.let { read(it, "getMember") as? JsonObject }
        val bank = publicCoins(bankJson, "banking", "balance")?.let {
            it + (publicCoins(personal, "profile", "bank_account") ?: 0.0)
        }
        val networth = if (inventory != null) await(profile, "getNetWorth") as? Pair<*, *> else null
        val total = if (bank != null && purse != null) {
            (networth?.first as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        } else null
        val wardrobe = runCatching {
            if (inventory == null || read(inventory, "getLoadouts") == null) return@runCatching null
            // Reuse the provider's priced armor/equipment breakdown; do not price every item again.
            val categories = networth?.second as? Map<*, *> ?: return@runCatching null
            val items = categories.entries.firstOrNull { (it.key as? Enum<*>)?.name == "LOADOUT" }?.value as? Map<*, *>
                ?: return@runCatching null
            items.values.sumOf {
                require(it is Number)
                it.toDouble().also { value -> require(value.isFinite() && value >= 0) }
            }.takeIf { it.isFinite() && it <= 9007199254740991.0 }
        }.getOrNull()
        val profileName = read(profile, "getId")?.let { read(it, "getName") as? String }.orEmpty()
        return FriendWealth(total, purse, bank, wardrobe, profileName, when {
            member == null -> "Public balances unavailable; retry later"
            !hasPublicInventory(member) -> "Inventory API disabled"
            inventory == null -> "Inventory calculation unavailable; showing public balances"
            bank == null -> "Bank API unavailable or disabled; total unavailable"
            purse == null -> "Purse unavailable; total unavailable"
            total == null -> "Networth calculation unavailable; showing public balances"
            wardrobe == null -> "Wardrobe estimate unavailable"
            else -> ""
        }, hasProfile = true)
    }

    private fun read(instance: Any, method: String): Any? = instance.javaClass.getMethod(method).invoke(instance)
}
