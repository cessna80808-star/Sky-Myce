package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonApi.skyblockCount
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.api.events.RenderSlotEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.utils.ItemUtils.getPrice
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId


object DungeonChestOverlay : SkyMyceModule() {
    val rerollThreshold
        get() = DungeonsConfig.safeRerollThreshold * 1_000_000
    
    data class ChestOverlayItem(val value: Double, val count: Int) {
        val totalValue = value * count
        val valuable = (totalValue >= (DungeonsConfig.valuableItemThreshold * 1_000_000))
        val text = if (valuable) "§b${NumberUtils.condense(totalValue)}" else "§a${NumberUtils.condense(totalValue)}"
    }

    var items: Map<Slot, ChestOverlayItem>? = emptyMap()
    var clickedTimestamp = 0L
    @Subscription(ContainerCloseEvent::class, ServerDisconnectEvent::class)
    fun clearContainer() { items = null; clickedTimestamp = 0L }
    override fun onDisable() { clearContainer() }

    @Subscription
    fun onChestInit(event: ContainerInitializedEvent) {
        if (!DungeonsConfig.dungeonChestProfit) return
        if (DungeonChest.fromName(event.title) == null) {
            items = null
            return
        }
        items = event.containerSlots.mapNotNull { slot ->
            val id = slot.item.getSkyBlockId() ?: return@mapNotNull null
            val count = slot.item.skyblockCount()
            val value = id.getPrice(DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType)
            slot to ChestOverlayItem(value, count)
        }.toMap()
    }

    @Subscription
    fun onChestReroll(event: DungeonChestRerollEvent) {
        if (!DungeonsConfig.safeReroll) return
        if (MC.instance.hasControlDown()) return
        if (items != null) {
            for ((_, overlay) in items) {
                if (overlay.totalValue >= rerollThreshold) {
                    clickedTimestamp = System.currentTimeMillis()
                    event.cancel()
                }
            }
        }
    }

    @Subscription
    fun onRender(event: RenderSlotEvent.After) {
        if (event.slot.container is Inventory) return
        val overlay = items?.get(event.slot) ?: return

        val time = (System.currentTimeMillis() - clickedTimestamp) / 500.0
        if (time < 1.0) {
            if (overlay.totalValue >= rerollThreshold) {
                event.graphics.fill(
                    event.slot.x,
                    event.slot.y,
                    event.slot.x + 16,
                    event.slot.y + 16,
                    ((255 - (time * 255).toInt()).coerceIn(0, 255) shl 24) or 0xFF0000
                )
            }
        }

        if (DungeonsConfig.dungeonChestProfit) {
            event.graphics.pose().pushMatrix()
            event.graphics.pose().scaleAround(0.5f, (event.slot.x + 8).toFloat(), (event.slot.y + 8).toFloat())
            event.graphics.pose().translate((event.slot.x + 8).toFloat(), (event.slot.y + 8).toFloat())
            event.graphics.centeredText(
                MC.font,
                overlay.text,
                0, -MC.font.lineHeight / 2,
                0xFFFFFFFF.toInt(),
            )
            event.graphics.pose().popMatrix()
        }
    }
}
