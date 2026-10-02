package me.mycellium.skymyce.features.digest

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.utils.MC
import net.minecraft.world.entity.player.Inventory
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.PlayerInventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ScheduledFuture
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTracker
import me.mycellium.skymyce.features.instances.dungeons.tracker.canonicalItemId

data class DetectedRng(val id: String, val item: String, val activity: String, val occurredAt: Long, val confirmation: String? = null)
data class DigestRareItem(val name: String, val activity: String)

object DigestRngCatalog {
    fun valid(item: String, activity: String): Boolean = DigestRngParser.catalog[item]?.activity == activity
    fun name(item: String): String = DigestRngParser.catalog[item]?.name ?: "Unknown drop"
}

/** A deliberately narrow catalog: common loot and reward previews can never become feed events. */
object DigestRngParser {
    val catalog = mapOf(
        "NECRON_HANDLE" to DigestRareItem("Necron's Handle", "dungeon"),
        "GIANTS_SWORD" to DigestRareItem("Giant's Sword", "dungeon"),
        "DARK_CLAYMORE" to DigestRareItem("Dark Claymore", "dungeon"),
        "SHADOW_FURY" to DigestRareItem("Shadow Fury", "dungeon"),
        "IMPLOSION_SCROLL" to DigestRareItem("Implosion", "dungeon"),
        "WITHER_SHIELD_SCROLL" to DigestRareItem("Wither Shield", "dungeon"),
        "SHADOW_WARP_SCROLL" to DigestRareItem("Shadow Warp", "dungeon"),
        "WARDEN_HEART" to DigestRareItem("Warden Heart", "slayer"),
        "JUDGEMENT_CORE" to DigestRareItem("Judgement Core", "slayer"),
        "OVERFLUX_CAPACITOR" to DigestRareItem("Overflux Capacitor", "slayer"),
    )
    private val dungeon = Regex("RARE REWARD! (?:\\[[A-Z+]+] )?([A-Za-z0-9_]{1,16}) found an? (.{1,100}) in their (?:Obsidian|Bedrock) Chest!")
    private val slayer = Regex("(?:CRAZY RARE DROP!|INSANE DROP!|RNGesus INCARNATE DROP!)\\s+(?:\\(([^()]{1,100})\\)|([^()]{1,100}?))(?: \\(\\+[\\d,.]+%? [^\\r\\n]{0,8}Magic Find\\))?[! ]*")

    fun detect(text: String, player: String, location: String, now: Long): DetectedRng? {
        if (text.length !in 1..300 || !Regex("[A-Za-z0-9_]{1,16}").matches(player) || text.any { it == '\n' || it == '\r' || it == '§' }) return null
        val dungeonMatch = dungeon.matchEntire(text)
        val activity: String
        val name: String
        if (dungeonMatch != null) {
            if (location !in setOf("THE_CATACOMBS", "DUNGEON_HUB") || !dungeonMatch.groupValues[1].equals(player, true)) return null
            activity = "dungeon"
            name = dungeonMatch.groupValues[2]
        } else {
            if (location !in setOf("HUB", "SPIDERS_DEN", "THE_PARK", "THE_END", "CRIMSON_ISLE")) return null
            val match = slayer.matchEntire(text) ?: return null
            activity = "slayer"
            name = match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        val item = catalog.entries.firstOrNull { it.value.activity == activity && it.value.name == name.replace('’', '\'').trim() }?.key ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest("${player.lowercase()}|$activity|$item|$now".toByteArray(StandardCharsets.UTF_8))
        val id = hash.take(16).joinToString("") { "%02x".format(it) }
        return DetectedRng(id, item, activity, now)
    }
}

/** Announcements alone may describe a preview. Require a matching aggregate inventory increase. */
class DigestDungeonAcquisition {
    private var counts: Map<String, Int>? = null
    private val pending = linkedMapOf<String, DetectedRng>()
    private val increased = mutableMapOf<String, Pair<Long, String?>>()

    fun seed(snapshot: Map<String, Int>) { counts = snapshot.toMap(); pending.clear(); increased.clear() }
    fun clear() { counts = null; pending.clear(); increased.clear() }

    fun announce(drop: DetectedRng, now: Long): DetectedRng? {
        if (counts == null || drop.activity != "dungeon") return null
        prune(now)
        increased.remove(drop.item)?.takeIf { now - it.first in 0..3000 }?.let { return drop.copy(confirmation = it.second) }
        pending[drop.item] = drop
        return null
    }

    fun inventory(snapshot: Map<String, Int>, now: Long, observation: String? = null): List<DetectedRng> {
        val previous = counts ?: run { seed(snapshot); return emptyList() }
        counts = snapshot.toMap()
        prune(now)
        val found = mutableListOf<DetectedRng>()
        snapshot.forEach { (item, count) ->
            if (!DigestRngCatalog.valid(item, "dungeon") || count <= (previous[item] ?: 0)) return@forEach
            val drop = pending.remove(item)
            val confirmation = observation?.let { "$it/$item" }
            if (drop != null) found += drop.copy(confirmation = confirmation) else increased[item] = now to confirmation
        }
        return found
    }

    private fun prune(now: Long) {
        pending.entries.removeIf { now - it.value.occurredAt !in 0..15_000 }
        increased.entries.removeIf { now - it.value.first !in 0..3000 }
    }
}

object DigestRng : SkyMyceModule() {
    var detected: ((DetectedRng) -> Unit)? = null
    var contextReady: () -> Boolean = { false }
    private val recent = linkedMapOf<String, Long>()
    private val acquisition = DigestDungeonAcquisition()
    private var inventoryCheck: ScheduledFuture<*>? = null
    private var context = 0L
    private var rareSlots = emptySet<Int>()
    fun diagnostics(): Map<String, Long> = mapOf("digest.inventory_task" to if (inventoryCheck?.isDone == false) 1L else 0L,
        "digest.recent_drops" to recent.size.toLong(), "digest.rare_slots" to rareSlots.size.toLong())

    fun clearContext() {
        recent.clear()
        acquisition.clear()
        rareSlots = emptySet()
        context++
        inventoryCheck?.cancel(false)
        inventoryCheck = null
    }

    /** Called once after the account/profile is ready, before accepting any announcements. */
    fun contextChanged() { clearContext(); if (contextReady()) acquisition.seed(inventoryCounts()) }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock || !contextReady()) return
        val now = System.currentTimeMillis()
        val drop = DigestRngParser.detect(event.text, MC.instance.user.name, LocationAPI.island?.name.orEmpty(), now) ?: return
        if (drop.activity == "dungeon") acquisition.announce(drop, now)?.let(::publish) else publish(drop)
    }

    @Subscription fun onInventory(event: PlayerInventoryChangeEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady() || inventoryCheck != null) return
        if (!DigestRngCatalog.valid(canonicalItemId(event.item.getSkyBlockId()?.id.orEmpty()), "dungeon") && event.slotIndex !in rareSlots && !DungeonTracker.awaitsInventory()) return
        val token = context
        // Coalesce the source/destination updates of item moves before comparing total counts.
        inventoryCheck = Scheduling.schedule(200.milliseconds) { MC.instance.execute {
            if (token != context) return@execute
            inventoryCheck = null
            if (contextReady()) {
                val counts = inventoryCounts(); val now = System.currentTimeMillis(); val observation = java.util.UUID.randomUUID().toString()
                DungeonTracker.inventoryObserved(counts, now, observation)
                acquisition.inventory(counts, now, observation).forEach(::publish)
            }
        } }
    }

    fun inventoryCounts(): Map<String, Int> {
        val inventory = MC.instance.player?.inventoryMenu ?: return emptyMap()
        val counts = mutableMapOf<String, Int>()
        val slots = mutableSetOf<Int>()
        for (slot in inventory.slots) {
            if (slot.container !is Inventory) continue
            val item = slot.item
            val id = item.getSkyBlockId()?.id?.let(::canonicalItemId) ?: continue
            counts[id] = (counts[id] ?: 0) + item.count
            if (DigestRngCatalog.valid(id, "dungeon")) slots += slot.index
        }
        rareSlots = slots
        return counts
    }

    private fun publish(drop: DetectedRng) {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { it.value + 3000 < now }
        val key = drop.confirmation ?: "${drop.activity}|${drop.item}"
        if (key in recent) return
        recent[key] = now
        AcquisitionRepository.confirmed(drop)
        detected?.invoke(drop)
    }
}
