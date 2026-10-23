package me.mycellium.skymyce.features.digest

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.consistentItemMetadata
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.world.entity.player.Inventory
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.PlayerInventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ScheduledFuture
import kotlin.time.Duration.Companion.milliseconds
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTracker
import me.mycellium.skymyce.features.instances.dungeons.tracker.canonicalItemId
import me.mycellium.skymyce.features.instances.dungeons.tracker.acquisitionId

data class DetectedRng(val id: String, val item: String, val activity: String, val occurredAt: Long, val confirmation: String? = null,
    val rarity: String? = null, val displayName: String? = null)
internal fun confirmedRng(drop: DetectedRng) = drop.confirmation?.let { drop.copy(id = acquisitionId(it)) } ?: drop
/** [public] items may be shared to the community feed; the relay accepts only those. Others stay in local history. */
data class DigestRareItem(val name: String, val activity: String, val public: Boolean = true)

object DigestRngCatalog {
    fun valid(item: String, activity: String): Boolean = DigestRngParser.catalog[item]?.activity == activity
    fun public(item: String, activity: String): Boolean = DigestRngParser.catalog[item]?.let { it.activity == activity && it.public } == true
    fun name(item: String): String = DigestRngParser.catalog[item]?.name ?: "Unknown drop"
}

/** Presentation only: saved journal records never re-enter detection, publication or recent-history storage. */
internal fun digestLocalRngHistory(acquisitions: List<AcquisitionEvent>, recent: List<DigestRngEntry>,
    context: AcquisitionContext?, now: Long): List<DigestEntry> {
    val journal = acquisitions.filter { DigestRngCatalog.valid(it.item, it.activity) && it.occurredAt in 1..now + 60000 &&
        it.quantity in 1..100000 && (context == null || context.matches(it.context)) }.map { event ->
        DigestEntry(event.id, DigestRngCatalog.name(event.item) + if (event.quantity > 1) " ×${event.quantity}" else "",
            listOfNotNull(event.player, event.activity.replaceFirstChar(Char::uppercase), event.floor).joinToString(" · "),
            if (event.origin == "confirmed") "Saved from your local game confirmation."
            else "Saved journal record; imported and older entries retain their recorded dates.",
            DigestTone.SPECIAL, if (event.origin == "confirmed") "Your drop" else "Saved local record", event.occurredAt,
            activity = event.activity, item = event.item)
    }
    val cached = recent.filter { !it.community && validDigestRng(it, now) }.map { entry ->
        DigestEntry(entry.id, DigestRngCatalog.name(entry.item), "${entry.player} · ${entry.activity.replaceFirstChar(Char::uppercase)}",
            "Saved from your local game confirmation.", DigestTone.SPECIAL, "Your drop", entry.occurredAt, activity = entry.activity, item = entry.item)
    }
    return (journal + cached).distinctBy { it.id }.sortedWith(compareByDescending<DigestEntry> { it.timestamp }.thenBy { it.id })
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
        // Local journal only until the relay's public allowlist includes them.
        "FIRST_MASTER_STAR" to DigestRareItem("First Master Star", "dungeon", public = false),
        "SECOND_MASTER_STAR" to DigestRareItem("Second Master Star", "dungeon", public = false),
        "THIRD_MASTER_STAR" to DigestRareItem("Third Master Star", "dungeon", public = false),
        "FOURTH_MASTER_STAR" to DigestRareItem("Fourth Master Star", "dungeon", public = false),
        "FIFTH_MASTER_STAR" to DigestRareItem("Fifth Master Star", "dungeon", public = false),
        "MASTER_SKULL_TIER_5" to DigestRareItem("Master Skull - Tier 5", "dungeon", public = false),
        "AUTO_RECOMBOBULATOR" to DigestRareItem("Auto Recombobulator", "dungeon", public = false),
        "WARDEN_HEART" to DigestRareItem("Warden Heart", "slayer"),
        "JUDGEMENT_CORE" to DigestRareItem("Judgement Core", "slayer"),
        "OVERFLUX_CAPACITOR" to DigestRareItem("Overflux Capacitor", "slayer"),
    )
    private val dungeon = Regex("RARE REWARD! (?:\\[[A-Z+]+] )?([A-Za-z0-9_]{1,16}) found an? (.{1,100}) in their (?:Obsidian|Bedrock) Chest!")
    private val dungeonDrop = Regex("RNG DROP! (?:\\[[A-Z+]+] )?([A-Za-z0-9_]{1,16}) just found an? (.{1,100})!")
    private val playerName = Regex("[A-Za-z0-9_]{1,16}")
    private val slayer = Regex("(?:CRAZY RARE DROP!|INSANE DROP!|RNGesus INCARNATE DROP!)\\s+(?:\\(([^()]{1,100})\\)|([^()]{1,100}?))(?: \\(\\+[\\d,.]+%? [^\\r\\n]{0,8}Magic Find\\))?[! ]*")

    fun detect(text: String, player: String, location: String, now: Long): DetectedRng? {
        val normalized = normalizeRngText(text) ?: return null
        if (!playerName.matches(player) || now <= 0) return null
        val dungeonMatch = dungeon.matchEntire(normalized) ?: dungeonDrop.matchEntire(normalized)
        val activity: String
        val name: String
        if (dungeonMatch != null) {
            if (location !in setOf("THE_CATACOMBS", "DUNGEON_HUB") || !dungeonMatch.groupValues[1].equals(player, true)) return null
            activity = "dungeon"
            name = dungeonMatch.groupValues[2]
        } else {
            if (location !in setOf("HUB", "SPIDERS_DEN", "THE_PARK", "THE_END", "CRIMSON_ISLE")) return null
            val match = slayer.matchEntire(normalized) ?: return null
            activity = "slayer"
            name = match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        val item = catalog.entries.firstOrNull { it.value.activity == activity && it.value.name == name.replace('’', '\'').trim() }?.key ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest("${player.lowercase()}|$activity|$item|$now".toByteArray(StandardCharsets.UTF_8))
        val id = hash.take(16).joinToString("") { "%02x".format(it) }
        return DetectedRng(id, item, activity, now)
    }
}

// Compiled once: normalization runs on every chat message.
private val rngStyle = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
private val rngSpaces = Regex("[\\s\\u00a0\\u202f]+")

/** Strip known Minecraft styling and equivalent spacing, without removing sender prefixes or combining lines. */
internal fun normalizeRngText(text: String): String? {
    if (text.length !in 1..600 || text.any { it == '\n' || it == '\r' || it.isISOControl() && it != '\t' }) return null
    val stripped = text.replace(rngStyle, "")
    if ('§' in stripped) return null
    return stripped.replace('’', '\'').replace('‘', '\'').replace(rngSpaces, " ").trim().takeIf { it.length in 1..300 }
}

/** Announcements alone may describe a preview. Require a matching aggregate inventory increase. */
class DigestDungeonAcquisition {
    private data class InventoryIncrease(val at: Long, val confirmation: String?, val metadata: ObservedItemMetadata?)
    private var counts: Map<String, Int>? = null
    private val pending = linkedMapOf<String, DetectedRng>()
    private val increased = mutableMapOf<String, InventoryIncrease>()
    private val slayerMetadata = linkedMapOf<String, DetectedRng>()

    fun seed(snapshot: Map<String, Int>) { counts = snapshot.toMap(); pending.clear(); increased.clear(); slayerMetadata.clear() }
    fun clear() { counts = null; pending.clear(); increased.clear(); slayerMetadata.clear() }

    fun announce(drop: DetectedRng, now: Long): DetectedRng? {
        if (counts == null || drop.activity != "dungeon") return null
        prune(now)
        increased.remove(drop.item)?.takeIf { now - it.at in 0..3000 }?.let {
            return drop.copy(confirmation = it.confirmation, rarity = it.metadata?.rarity, displayName = it.metadata?.displayName)
        }
        pending[drop.item] = drop
        return null
    }

    /** Slayer chat already confirms the personal drop. Correlate only its optional metadata, never an old held item. */
    fun annotateSlayer(drop: DetectedRng, now: Long): DetectedRng {
        if (counts == null || drop.activity != "slayer") return drop
        prune(now)
        increased.remove(drop.item)?.takeIf { now - it.at in 0..3000 }?.let {
            return drop.copy(rarity = it.metadata?.rarity, displayName = it.metadata?.displayName)
        }
        slayerMetadata.putIfAbsent(drop.item, drop)
        return drop
    }

    fun inventory(snapshot: Map<String, Int>, now: Long, observation: String? = null,
                  metadata: Map<String, ObservedItemMetadata> = emptyMap()): List<DetectedRng> {
        val previous = counts ?: run { seed(snapshot); return emptyList() }
        counts = snapshot.toMap()
        prune(now)
        val found = mutableListOf<DetectedRng>()
        snapshot.forEach { (item, count) ->
            if (item !in DigestRngParser.catalog || count <= (previous[item] ?: 0)) return@forEach
            val observed = metadata[item]?.let { observedItemMetadata(it.rarity, it.displayName) }
            if (DigestRngCatalog.valid(item, "slayer")) {
                val drop = slayerMetadata.remove(item)
                if (drop != null && observed != null) found += drop.copy(rarity = observed.rarity, displayName = observed.displayName)
                else increased[item] = InventoryIncrease(now, null, observed)
                return@forEach
            }
            val drop = pending.remove(item)
            val confirmation = observation?.let { "$it/$item" }
            if (drop != null) found += drop.copy(confirmation = confirmation, rarity = observed?.rarity, displayName = observed?.displayName)
            else increased[item] = InventoryIncrease(now, confirmation, observed)
        }
        return found
    }

    private fun prune(now: Long) {
        pending.entries.removeIf { now - it.value.occurredAt !in 0..15_000 }
        increased.entries.removeIf { now - it.value.at !in 0..3000 }
        slayerMetadata.entries.removeIf { now - it.value.occurredAt !in 0..15_000 }
    }
}

object DigestRng : SkyMyceModule() {
    var detected: ((DetectedRng) -> Unit)? = null
    var contextReady: () -> Boolean = { AcquisitionRepository.currentContext() != null }
    private val recent = linkedMapOf<String, Long>()
    private val confirmedReceipts = linkedSetOf<String>()
    private val acquisition = DigestDungeonAcquisition()
    private var inventoryCheck: ScheduledFuture<*>? = null
    private var context = 0L
    private var rareSlots = emptySet<Int>()
    private var inventoryContext: me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext? = null
    private var inventoryWorld: Any? = null
    fun diagnostics(): Map<String, Long> = mapOf("digest.inventory_task" to if (inventoryCheck?.isDone == false) 1L else 0L,
        "digest.recent_drops" to recent.size.toLong(), "digest.rare_slots" to rareSlots.size.toLong())

    fun clearContext() {
        recent.clear()
        acquisition.clear()
        rareSlots = emptySet()
        inventoryContext = null
        inventoryWorld = null
        context++
        inventoryCheck?.cancel(false)
        inventoryCheck = null
    }

    /** Called once after the account/profile is ready, before accepting any announcements. */
    fun contextChanged() { ensureContext() }

    @Subscription(ProfileChangeEvent::class, ServerDisconnectEvent::class) fun resetAcquisitionContext() { clearContext() }
    @Subscription(TickEvent::class) fun observeContext() { ensureContext() }
    private fun ensureContext(): Boolean {
        val current = AcquisitionRepository.currentContext()
        val world = MC.instance.level
        if (current == null || world == null || !contextReady()) {
            if (!LocationAPI.isOnSkyBlock && inventoryContext != null) clearContext()
            return false
        }
        if (inventoryContext?.matches(current) != true || inventoryWorld !== world) {
            clearContext()
            inventoryContext = current
            inventoryWorld = world
            acquisition.seed(inventoryCounts())
        }
        return true
    }

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock || !ensureContext()) return
        val now = System.currentTimeMillis()
        val drop = DigestRngParser.detect(event.text, MC.instance.user.name, LocationAPI.island?.name.orEmpty(), now) ?: return
        if (drop.activity == "dungeon") acquisition.announce(drop, now)?.let(::publish)
        else publish(acquisition.annotateSlayer(drop, now))
    }

    @Subscription fun onInventory(event: PlayerInventoryChangeEvent) {
        if (!LocationAPI.isOnSkyBlock || inventoryCheck != null) return
        val digestReady = ensureContext()
        val trackerWaiting = DungeonTracker.awaitsInventory()
        if (!digestReady && !trackerWaiting) return
        if (!trackerWaiting && canonicalItemId(event.item.getSkyBlockId()?.id.orEmpty()) !in DigestRngParser.catalog && event.inventorySlot.containerSlot !in rareSlots) return
        requestInventoryObservation()
    }

    @Subscription fun onPacket(event: PacketReceivedEvent) {
        if (event.packet !is ClientboundSetPlayerInventoryPacket) return
        // This packet writes Inventory directly, without the API's Slot.set event.
        MC.instance.execute { requestInventoryObservation() }
    }

    /** Read after packet handling; a matching reward header can also request this once. */
    fun requestInventoryObservation() {
        if (!LocationAPI.isOnSkyBlock || inventoryCheck != null || !ensureContext() && !DungeonTracker.awaitsInventory()) return
        val token = context
        // Coalesce the source/destination updates of item moves before comparing total counts.
        inventoryCheck = Scheduling.schedule(200.milliseconds) { MC.instance.execute {
            if (token == context) inventoryCheck = null
            val digestCurrent = token == context && contextReady()
            if (digestCurrent || DungeonTracker.awaitsInventory()) {
                val snapshot = inventorySnapshot(); val now = System.currentTimeMillis(); val observation = java.util.UUID.randomUUID().toString()
                DungeonTracker.inventoryObserved(snapshot.counts, now, observation, snapshot.metadata)
                if (digestCurrent) acquisition.inventory(snapshot.counts, now, observation, snapshot.metadata).forEach {
                    // Late slayer metadata enriches the same archive ID, without replaying Digest or public publication.
                    if (it.activity == "slayer") AcquisitionRepository.enrichConfirmed(it) else publish(it)
                }
            }
        } }
    }

    private data class ObservedInventory(val counts: Map<String, Int>, val metadata: Map<String, ObservedItemMetadata>)
    fun inventoryCounts(): Map<String, Int> = inventorySnapshot().counts
    private fun inventorySnapshot(): ObservedInventory {
        val inventory = MC.instance.player?.inventoryMenu ?: return ObservedInventory(emptyMap(), emptyMap())
        val counts = mutableMapOf<String, Int>()
        val metadata = mutableMapOf<String, MutableList<ObservedItemMetadata?>>()
        val slots = mutableSetOf<Int>()
        for (slot in inventory.slots) {
            if (slot.container !is Inventory) continue
            val item = slot.item
            val id = item.getSkyBlockId()?.id?.let(::canonicalItemId) ?: continue
            counts[id] = (counts[id] ?: 0) + item.count
            // Container-menu indexes shift while a chest is open; the backing player inventory index stays stable.
            if (id in DigestRngParser.catalog) {
                slots += slot.containerSlot
                metadata.getOrPut(id) { mutableListOf() }.add(observedItemMetadata(item))
            }
        }
        rareSlots = slots
        return ObservedInventory(counts, metadata.mapNotNull { (id, variants) -> consistentItemMetadata(variants)?.let { id to it } }.toMap())
    }

    private fun publish(observed: DetectedRng) {
        val drop = confirmedRng(observed)
        val now = System.currentTimeMillis()
        if (drop.confirmation != null) {
            if (!confirmedReceipts.add(drop.id)) return
            if (confirmedReceipts.size > 10000) confirmedReceipts.remove(confirmedReceipts.first())
        }
        recent.entries.removeIf { it.value + 3000 < now }
        val key = drop.confirmation ?: "${drop.activity}|${drop.item}"
        if (key in recent) return
        recent[key] = now
        AcquisitionRepository.confirmed(drop)
        me.mycellium.skymyce.utils.Utils.displayDevMessage { "RNG acquisition confirmed locally: activity=${drop.activity}, inventoryReceipt=${drop.confirmation != null}" }
        detected?.invoke(drop)
    }
    /** Trusted in-process complete chest receipt; preview/control events never invoke this boundary. */
    internal fun receiptConfirmed(drop: DetectedRng) { if (AcquisitionRepository.currentContext() != null) publish(drop) }
}
