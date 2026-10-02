package me.mycellium.skymyce.config.instances.dungeons

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.instances.dungeons.friends.parseDungeonClass
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.partyOpenClasses
import me.mycellium.skymyce.features.instances.dungeons.partyMatches
import me.mycellium.skymyce.api.events.RenderSlotEvent
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.SlotClickEvent
import java.util.UUID

private val MEMBER_LINE_REGEX = Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}):\\s*(\\w+)\\s+\\(\\d+\\)$")
private val FORMAT_REGEX = Regex("§.")
private val FLOOR_REGEX = Regex("^Floor:\\s*(?:Floor\\s+)?([1-7]|VII|VI|IV|V|III|II|I)$", RegexOption.IGNORE_CASE)

data class PartyListing(
    val slotIndex: Int,
    val leaderName: String,
    val leaderUuid: UUID?,
    val memberUsername: List<String>,
    val note: String?,
    val memberClasses: Map<String, DungeonClass> = emptyMap(),
    val floor: DungeonFloor? = null,
    val openClasses: Set<DungeonClass>? = null,
)

object PartyFinder : SkyMyceModule() {
    private var screen: AbstractContainerScreen<*>? = null
    private val listings = mutableMapOf<Int, PartyListing>()
    private val dimmed = mutableSetOf<Int>()
    private var statsRevision = -1L

    fun invalidateMatcher() { statsRevision = -1 }

    private fun updateMatcher() {
        val self = MC.instance.player ?: return
        val stats = DungeonFriendStatsCache.get(self.name.string)
        val classes = PartyFinderConfig.classes.toSet().ifEmpty { stats?.selectedClass?.let(::setOf).orEmpty() }
        dimmed.clear()
        listings.values.forEach { listing ->
            if (!partyMatches(listing, classes, listing.floor?.let { stats?.completionTimes?.get(it) },
                    PartyFinderConfig.matchClass, PartyFinderConfig.matchPb)) dimmed += listing.slotIndex
        }
        statsRevision = DungeonFriendStatsCache.version
    }

    @Subscription fun onSlotChanged(event: InventoryChangeEvent) {
        if (event.screen !== screen || event.isInPlayerInventory || event.slot.index !in 10..43) return
        val listing = if (event.item.item == Items.PLAYER_HEAD) parsePartyItem(event.slot.index, event.item) else null
        if (listing == null) listings.remove(event.slot.index) else listings[event.slot.index] = listing
        invalidateMatcher()
    }

    @Subscription fun onClose(event: ContainerCloseEvent) {
        screen = null; listings.clear(); dimmed.clear(); invalidateMatcher()
    }

    @Subscription fun onRender(event: RenderSlotEvent.After) {
        if (!PartyFinderConfig.enabled || !LocationAPI.isOnSkyBlock || screen == null || MC.screen !== screen) return
        if (statsRevision != DungeonFriendStatsCache.version) updateMatcher()
        if (event.slot.index !in dimmed || screen?.menu?.slots?.getOrNull(event.slot.index) !== event.slot) return
        event.graphics.fill(event.slot.x, event.slot.y, event.slot.x + 16, event.slot.y + 16, 0xA0181B20.toInt())
    }

    @Subscription(priority = Subscription.LOW)
    fun onListingClick(event: SlotClickEvent) {
        if (!event.title.startsWith("Party Finder", true) || event.isInPlayerInventory || event.slot.index !in 10..43) return
        if (event.item.item != Items.PLAYER_HEAD) return
        parsePartyItem(event.slot.index, event.item)?.let(DungeonFriends::onListingSelected)
    }

    @Subscription
    fun onContainerOpen(event: ContainerInitializedEvent) {
        val title = event.title.trim()
        screen = null; listings.clear(); dimmed.clear(); invalidateMatcher()

        if (title.startsWith("Party Finder", ignoreCase = true)) {
            displayDevMessage { "[PartyFinder] Detected container: title='$title', slots=${event.containerSlots.size}" }
            val listings = parsePartyListings(event)
            if (LocationAPI.isOnSkyBlock) {
                screen = event.screen
                this.listings.putAll(listings.associateBy { it.slotIndex })
                if (PartyFinderConfig.enabled) MC.instance.player?.let {
                    DungeonFriendStatsCache.request(it.name.string, it.uuid, priority = true)
                }
            }
            displayDevMessage { "[PartyFinder] Parsed ${listings.size} listing(s): $listings" }
            DungeonFriends.onListings(listings)
        }
    }
}

private fun parsePartyListings(event: ContainerInitializedEvent): List<PartyListing> {
    return event.containerSlots
        .filter { it.index in 10..43 }
        .mapNotNull { slot ->
            val item = slot.item
            if (item.isEmpty) {
                displayDevMessage { "[PartyFinder] Slot ${slot.index}: empty" }
                return@mapNotNull null
            }
            if (item.item != Items.PLAYER_HEAD) {
                displayDevMessage { "[PartyFinder] Slot ${slot.index}: ignored ${item.item}" }
                return@mapNotNull null
            }

            displayDevMessage { "[PartyFinder] Slot ${slot.index}: detected player head" }
            parsePartyItem(slot.index, item)
        }
}

private fun parsePartyItem(slotIndex: Int, item: ItemStack): PartyListing? {
    val profileComponent = item.get(DataComponents.PROFILE)
    val leaderUuid: UUID? = profileComponent?.partialProfile()?.id

    val loreLines = item.get(DataComponents.LORE)?.lines?.map { it.string }
    if (loreLines == null) {
        displayDevMessage { "[PartyFinder] Slot $slotIndex: player head has no lore" }
        return null
    }
    displayDevMessage { "[PartyFinder] Slot $slotIndex: profileUuid=$leaderUuid, lore=$loreLines" }
    return partyListingFromLore(slotIndex, leaderUuid, loreLines)
}

fun partyListingFromLore(slotIndex: Int, leaderUuid: UUID?, loreLines: List<String>): PartyListing? {
    var leaderName: String? = null
    val members = mutableListOf<String>()
    val classes = mutableMapOf<String, DungeonClass>()
    var note: String? = null
    var inMembersSection = false

    for (line in loreLines) {
        val cleanLine = line.replace(FORMAT_REGEX, "").trim()

        when {
            cleanLine.equals("Members:", ignoreCase = true) -> {
                inMembersSection = true
            }
            cleanLine.startsWith("Note:", ignoreCase = true) -> {
                note = cleanLine.substringAfter("Note:").trim()
            }
            inMembersSection && cleanLine.equals("Empty", ignoreCase = true) -> {
                inMembersSection = false
            }
            inMembersSection -> {
                val memberMatch = MEMBER_LINE_REGEX.matchEntire(cleanLine)
                if (memberMatch != null) {
                    val memberName = memberMatch.groupValues[1]
                    members.add(memberName)
                    parseDungeonClass(memberMatch.groupValues[2])?.let { classes[memberName] = it }
                    leaderName = leaderName ?: memberName
                }
            }
        }
    }

    return PartyListing(slotIndex, leaderName ?: return null, leaderUuid, members, note, classes, partyListingFloor(loreLines),
        partyOpenClasses(loreLines, classes.values))
}

fun partyListingFloor(lines: List<String>): DungeonFloor? {
    val clean = lines.map { it.replace(FORMAT_REGEX, "").trim() }
    val master = clean.any { it.startsWith("Dungeon:") && it.contains("Master Mode", true) }
    val floor = clean.firstNotNullOfOrNull { FLOOR_REGEX.matchEntire(it)?.groupValues?.get(1) } ?: return null
    val number = floor.toIntOrNull() ?: (listOf("I", "II", "III", "IV", "V", "VI", "VII").indexOf(floor.uppercase()) + 1)
    return DungeonFloor.entries.firstOrNull { it.name == "${if (master) "M" else "F"}$number" }
}
