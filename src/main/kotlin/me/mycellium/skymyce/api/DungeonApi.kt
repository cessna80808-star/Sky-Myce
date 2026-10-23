package me.mycellium.skymyce.api

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.DungeonChestOpenEvent
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestProtection
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import me.mycellium.skymyce.utils.ObservedItemMetadata
import me.mycellium.skymyce.utils.observedItemMetadata
import me.mycellium.skymyce.utils.consistentItemMetadata
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyIn
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.SlotClickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.extentions.isSkyblockFiller
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.util.UUID
object DungeonApi : SkyMyceModule() {
    val chestNames = mapOf(
        "Catacombs - Floor I" to DungeonFloor.F1,
        "Catacombs - Floor II" to DungeonFloor.F2,
        "Catacombs - Floor III" to DungeonFloor.F3,
        "Catacombs - Floor IV" to DungeonFloor.F4,
        "Catacombs - Floor V" to DungeonFloor.F5,
        "Catacombs - Floor VI" to DungeonFloor.F6,
        "Catacombs - Floor VII" to DungeonFloor.F7,
        "Master Catacombs - Floor I" to DungeonFloor.M1,
        "Master Catacombs - Floor II" to DungeonFloor.M2,
        "Master Catacombs - Floor III" to DungeonFloor.M3,
        "Master Catacombs - Floor IV" to DungeonFloor.M4,
        "Master Catacombs - Floor V" to DungeonFloor.M5,
        "Master Catacombs - Floor VI" to DungeonFloor.M6,
        "Master Catacombs - Floor VII" to DungeonFloor.M7
    )

    private var chestFloor: DungeonFloor? = null
    private var chestType: DungeonChest? = null
    private var chestContents: Map<SkyBlockId, Int> = emptyMap()
    private var chestMetadata: Map<SkyBlockId, ObservedItemMetadata> = emptyMap()
    private var chestSlots: List<Slot> = emptyList()
    private var chestScreen: AbstractContainerScreen<*>? = null
    private var observation = UUID.randomUUID().toString()

    private fun clearChest(forgetFloor: Boolean = false) {
        if (forgetFloor) chestFloor = null
        chestType = null
        chestContents = emptyMap()
        chestMetadata = emptyMap()
        chestSlots = emptyList()
        chestScreen = null
        observation = UUID.randomUUID().toString()
        DungeonChestProtection.resetWarning()
    }

    @Subscription(ContainerCloseEvent::class)
    fun onChestClose() = clearChest()

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun onContextChange() = clearChest(forgetFloor = true)

    @Subscription
    fun getChestFloor(event: ContainerInitializedEvent) {
        if (LocationAPI.island !in arrayOf(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS)) {
            clearChest(forgetFloor = true)
            return
        }
        val title = normalizeChestText(event.title) ?: return clearChest()
        chestFloor = chestNames[title] ?: if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            DungeonAPI.dungeonFloor
        } else {
            chestFloor
        }

        val type = DungeonChest.fromName(title)
        if (type == null) {
            if (event.containerSlots.any { dungeonChestControl(it.item.hoverName.stripped) != null })
                displayDevMessage { "Dungeon chest detection rejected: unrecognized title, titleChars=${title.length}" }
            clearChest(); return
        }
        if (chestScreen !== event.screen || chestType != type) {
            clearChest(); chestScreen = event.screen
        }
        chestType = type
        refreshRewards(event.containerSlots)
        displayDevMessage { "Dungeon chest preview: floor=${chestFloor?.name ?: "unknown"}, tier=${type.name}, rewardKinds=${chestContents.size}" }
    }

    @Subscription
    @OnlyIn(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS)
    fun slotClicked(event: SlotClickEvent) {
        val dungeonChest = chestType ?: return
        if (event.screen !== chestScreen || event.isInPlayerInventory || DungeonChest.fromName(event.title) != dungeonChest) return
        refreshRewards(event.slots.filter { it.container !is Inventory })

        val lore = event.item.get(DataComponents.LORE)?.lines
        when (dungeonChestControl(event.item.hoverName.stripped)) {
            "Open Reward Chest" -> {
                val cost = dungeonChestCost(lore?.map { it.stripped }.orEmpty())
                if (DungeonChestProtection.shouldBlock(dungeonChest, chestContents, cost)) {
                    event.cancel()
                    return
                }
                val floor = chestFloor ?: return
                if (DungeonChestOpenEvent(
                        floor,
                        dungeonChest,
                        chestContents,
                        cost ?: 0,
                        costKnown = cost != null,
                        observation = observation,
                        metadata = chestMetadata
                    ).post(SkyBlockAPI.eventBus)
                ) event.cancel()
            }

            "Reroll Chest" -> {
                val floor = chestFloor ?: return
                val reroll = lore?.firstOrNull { normalizeChestText(it.stripped) == "Click to reroll this chest!" }
                if (reroll != null) {
                    if (DungeonChestRerollEvent(
                            floor,
                            dungeonChest,
                            chestContents,
                            chestSlots,
                            observation
                        ).post(SkyBlockAPI.eventBus)
                    ) event.cancel() else observation = UUID.randomUUID().toString()
                }
            }
        }
    }

    fun ItemStack.skyblockCount(): Int {
        return dungeonRewardQuantity(hoverName.stripped, count) ?: 0
    }

    private fun refreshRewards(slots: List<Slot>) {
        val previous = chestContents
        val metadata = mutableMapOf<SkyBlockId, MutableList<ObservedItemMetadata?>>()
        val parsed = runCatching {
            require(slots.size <= 90)
            chestSlots = slots.filter { it.container !is Inventory && !it.item.isEmpty && !it.item.isSkyblockFiller() && dungeonChestControl(it.item.hoverName.stripped) == null }
            buildMap<SkyBlockId, Int> {
                for (slot in chestSlots) {
                    val item = slot.item
                    val id = dungeonEssenceId(item.hoverName.stripped)?.let(SkyBlockId::item) ?: item.getSkyBlockId() ?: continue
                    val count = item.skyblockCount()
                    require(count > 0)
                    val total = (get(id) ?: 0).toLong() + count
                    require(total <= 100000 && (id in this || size < 64))
                    put(id, total.toInt())
                    metadata.getOrPut(id) { mutableListOf() }.add(observedItemMetadata(item))
                }
            }
        }
        chestContents = parsed.getOrElse {
            displayDevMessage { "Dungeon chest preview rejected: invalid reward bounds" }
            emptyMap()
        }
        chestMetadata = if (parsed.isSuccess) metadata.mapNotNull { (id, variants) ->
            consistentItemMetadata(variants)?.let { id to it }
        }.toMap() else emptyMap()
        if (previous != chestContents) observation = UUID.randomUUID().toString()
    }
}

enum class DungeonChest(val label: String, val color: ChatFormatting) {
    WOOD("Wood", ChatFormatting.WHITE),
    GOLD("Gold", ChatFormatting.GOLD),
    DIAMOND("Diamond", ChatFormatting.AQUA),
    EMERALD("Emerald", ChatFormatting.DARK_GREEN),
    OBSIDIAN("Obsidian", ChatFormatting.DARK_PURPLE),
    BEDROCK("Bedrock", ChatFormatting.DARK_GRAY);

    companion object {
        fun fromName(name: String) = normalizeChestText(name)?.let { normalized -> entries.firstOrNull { normalized == it.label || normalized == "${it.label} Chest" } }
    }
}

enum class DungeonRank(val label: String, val color: ChatFormatting, val texture: Identifier) {
    SPLUS("S+", ChatFormatting.YELLOW, SkyMyce.id("textures/dungeon_score/splus.png")),
    S("S", ChatFormatting.GOLD, SkyMyce.id("textures/dungeon_score/s.png")),
    A("A", ChatFormatting.DARK_PURPLE, SkyMyce.id("textures/dungeon_score/a.png")),
    B("B", ChatFormatting.GREEN, SkyMyce.id("textures/dungeon_score/b.png")),
    C("C", ChatFormatting.BLUE, SkyMyce.id("textures/dungeon_score/c.png")),
    D("D", ChatFormatting.RED, SkyMyce.id("textures/dungeon_score/d.png")),
    UNKNOWN("?", ChatFormatting.GRAY, SkyMyce.id("textures/dungeon_score/unknown.png"));

    companion object {
        fun fromLabel(label: String) = entries.firstOrNull { it.label == label } ?: UNKNOWN

        fun DungeonRank.formatted() = "§${this.color.char}§l${this.label}"
    }
}
