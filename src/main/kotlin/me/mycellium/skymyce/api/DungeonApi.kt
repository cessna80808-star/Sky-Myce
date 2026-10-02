package me.mycellium.skymyce.api

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.DungeonChestInitializedEvent
import me.mycellium.skymyce.api.events.DungeonChestOpenEvent
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestProtection
import me.mycellium.skymyce.utils.Utils.displayDevMessage
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
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.SlotClickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.extentions.isSkyblockFiller
import tech.thatgravyboat.skyblockapi.utils.regex.RegexUtils.contains
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
object DungeonApi : SkyMyceModule() {
    val secretDrops = setOf(
        "ARCHITECT_FIRST_DRAFT",
        "CANDYCOMB",
        "DEFUSE_KIT",
        "DUNGEON_CHEST_KEY",
        "DUNGEON_DECOY",
        "DUNGEON_TRAP",
        "DYE_SECRET",
        "INFLATABLE_JERRY",
        "REVIVE_STONE",
        "TRAINING_WEIGHTS",
        "TREASURE_TALISMAN"
    )

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

    private val essenceRegex = Regex("^(.+?) Essence x(\\d+)$")
    private val shardRegex = Regex("^(.+?) Shard x(\\d+)$")

    private val costRegex = Regex("^([\\d,]+) Coins$")
    private val rerollRegex = Regex("^Click to reroll this chest!$")
    private val chestKeyRegex = Regex("^Dungeon Chest Key$")
    private val openedRegex = Regex("(.+) CHEST REWARDS$")
    private val chestControlItems = setOf("Open Reward Chest", "Reroll Chest")

    private var chestFloor: DungeonFloor? = null
    private var chestType: DungeonChest? = null
    private var chestContents: Map<SkyBlockId, Int> = emptyMap()
    private var chestSlots: List<Slot> = emptyList()

    private fun clearChest(forgetFloor: Boolean = false) {
        if (forgetFloor) chestFloor = null
        chestType = null
        chestContents = emptyMap()
        chestSlots = emptyList()
        DungeonChestProtection.resetWarning()
    }

    @Subscription(ContainerCloseEvent::class)
    fun onChestClose() = clearChest()

    @Subscription(IslandChangeEvent::class, ServerDisconnectEvent::class)
    fun onContextChange() = clearChest(forgetFloor = true)

    @Subscription
    fun getChestFloor(event: ContainerInitializedEvent) {
        clearChest()
        if (LocationAPI.island !in arrayOf(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS)) {
            chestFloor = null
            return
        }
        chestFloor = chestNames[event.title] ?: if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            DungeonAPI.dungeonFloor
        } else {
            chestFloor
        }

        chestType = DungeonChest.fromName(event.title)
        if (chestType != null) {
            try {
                chestSlots = event.containerSlots.filter {
                    it.container !is Inventory && !it.item.isSkyblockFiller() && it.item.getSkyBlockId() != null
                }
                chestContents = buildMap {
                    chestSlots.filterNot { slot ->
                        slot.item.customName?.stripped in chestControlItems
                    }.forEach { slot ->
                        val id = slot.item.getSkyBlockId() ?: return@forEach
                        val count = slot.item.skyblockCount()
                        if (count > 0) put(id, (get(id) ?: 0) + count)
                    }
                }
            } catch (exception: Exception) {
                SkyMyce.logger.warn("Could not parse dungeon chest rewards; protection will allow the interaction", exception)
                chestSlots = emptyList()
                chestContents = emptyMap()
            }
            displayDevMessage { "chest floor: $chestFloor" }
            displayDevMessage { "chest type: $chestType" }
            displayDevMessage { "chest contents: $chestContents" }
            DungeonChestInitializedEvent(
                chestFloor ?: return,
                chestType ?: return,
                chestContents
            ).post(SkyBlockAPI.eventBus)
        }
    }

    @Subscription
    @OnlyIn(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS)
    fun slotClicked(event: SlotClickEvent) {
        val dungeonChest = chestType ?: return

        val lore = event.item.get(DataComponents.LORE)?.lines
        when (event.item.customName?.stripped) {
            "Open Reward Chest" -> {
                displayDevMessage { "Chest: $dungeonChest" }
                val cost = lore?.firstNotNullOfOrNull { costRegex.find(it.string) }?.groupValues?.getOrNull(1)
                    ?.replace(",", "")?.toLongOrNull()?.takeIf { it >= 0 }
                if (DungeonChestProtection.shouldBlock(dungeonChest, chestContents, cost)) {
                    event.cancel()
                    return
                }
                val floor = chestFloor ?: return
                lore?.any { chestKeyRegex.contains(it.string) }
                if (DungeonChestOpenEvent(
                        floor,
                        dungeonChest,
                        chestContents,
                        cost ?: 0,
                        costKnown = cost != null
                    ).post(SkyBlockAPI.eventBus)
                ) event.cancel()
            }

            "Reroll Chest" -> {
                val floor = chestFloor ?: return
                val reroll = lore?.firstOrNull { rerollRegex.matches(it.string) }
                if (reroll != null) {
                    if (DungeonChestRerollEvent(
                            floor,
                            dungeonChest,
                            chestContents,
                            chestSlots
                        ).post(SkyBlockAPI.eventBus)
                    ) event.cancel()
                }
            }
        }
    }

    fun ItemStack.skyblockCount(): Int {
        val name = this.customName?.string
        if (name != null) {
            essenceRegex.find(name)?.let {
                return it.groupValues[2].toIntOrNull() ?: this.count
            }
            shardRegex.find(name)?.let {
                return it.groupValues[2].toIntOrNull() ?: this.count
            }
        }
        return this.count
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
        fun DungeonChest.formatted() = "§${this.color.char}${this.label}"
        fun fromName(name: String) = entries.firstOrNull { it.label == name }
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
