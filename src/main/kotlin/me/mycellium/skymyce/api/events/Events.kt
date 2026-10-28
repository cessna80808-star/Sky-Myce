package me.mycellium.skymyce.api.events

import me.mycellium.skymyce.api.DungeonChest
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.inventory.Slot
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.CancellableSkyBlockEvent
import tech.thatgravyboat.skyblockapi.api.events.base.SkyBlockEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId

data class DungeonChestOpenEvent(
    val floor: DungeonFloor,
    val chest: DungeonChest,
    val contents: Map<SkyBlockId, Int>,
    val cost: Long,
    val costKnown: Boolean = true,
    val observation: String = java.util.UUID.randomUUID().toString(),
    /** Plain observed reward presentation; a preview becomes a record only after acquisition confirmation. */
    val metadata: Map<SkyBlockId, me.mycellium.skymyce.utils.ObservedItemMetadata> = emptyMap()
): CancellableSkyBlockEvent()

data class DungeonChestRerollEvent(
    val floor: DungeonFloor,
    val chest: DungeonChest,
    val contents: Map<SkyBlockId, Int>,
    val slots: List<Slot>,
    val observation: String = java.util.UUID.randomUUID().toString()
): CancellableSkyBlockEvent()

data class PlayerMessageEvent(val type: ChatChannel, val player: String, val message: String) : SkyBlockEvent()

enum class ChatChannel {
    PUBLIC,
    PRIVATE,
    PARTY,
    GUILD,
}

abstract class RenderSlotEvent : SkyBlockEvent() {
    abstract val slot: Slot
    abstract val graphics: GuiGraphicsExtractor

    class Before(override val graphics: GuiGraphicsExtractor, override val slot: Slot) : RenderSlotEvent()
    class After(override val graphics: GuiGraphicsExtractor, override val slot: Slot) : RenderSlotEvent()
}
