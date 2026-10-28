package me.mycellium.skymyce.features.instances.dungeons

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonApi.skyblockCount
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.api.events.DungeonChestRerollEvent
import me.mycellium.skymyce.api.events.RenderSlotEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.utils.ItemUtils.getPrice
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.NumberUtils
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId

object DungeonChestOverlay : SkyMyceModule() {
    private val rerollThreshold
        get() = DungeonsConfig.safeRerollThreshold * 1_000_000

    private data class ChestOverlayItem(val value: Double, val count: Int) {
        val totalValue = value * count
        val valuable = (totalValue >= (DungeonsConfig.valuableItemThreshold * 1_000_000))
        val text = if (valuable) "§b${NumberUtils.condense(totalValue)}" else "§a${NumberUtils.condense(totalValue)}"
    }

    private var items: Map<Slot, ChestOverlayItem>? = emptyMap()
    private var clickedTimestamp = 0L
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

object DungeonChestProfitCalculator {
    data class Estimate(val chestCost: Long, val rewardValue: Double, val profit: Double)

    sealed interface Result {
        data class Available(val estimate: Estimate) : Result
        data class Unavailable(val reason: String) : Result
    }

    fun <T : Any> calculate(
        chestCost: Long?,
        rewards: Map<T, Int>,
        priceOf: (T) -> Double?,
    ): Result {
        if (chestCost == null || chestCost < 0) return Result.Unavailable("unknown chest cost")
        if (rewards.isEmpty() || rewards.values.any { it <= 0 }) {
            return Result.Unavailable("incomplete reward list")
        }

        var rewardTotal = BigDecimal.ZERO
        for ((reward, count) in rewards) {
            val unitPrice = priceOf(reward)?.takeIf { it.isFinite() && it > 0.0 }
                ?: return Result.Unavailable("missing reward price")
            rewardTotal = rewardTotal.add(BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(count.toLong())))
        }
        val rewardValue = rewardTotal.toDouble()
        val profit = rewardTotal.subtract(BigDecimal.valueOf(chestCost)).toDouble()
        if (!rewardValue.isFinite() || !profit.isFinite()) return Result.Unavailable("invalid reward total")
        return Result.Available(Estimate(chestCost, rewardValue, profit))
    }

    fun blocks(profit: Double, lossThreshold: Double): Boolean =
        profit.isFinite() && lossThreshold.isFinite() && lossThreshold >= 0.0 && profit < -lossThreshold
}

object DungeonChestProtection : SkyMyceModule() {
    private const val WARNING_COOLDOWN_MS = 1_500L

    private var lastWarningKey: String? = null
    private var lastWarningAt = 0L
    private var lastUnavailableKey: String? = null
    private var lastUnavailableAt = 0L

    fun shouldBlock(chest: DungeonChest, contents: Map<SkyBlockId, Int>, cost: Long?): Boolean {
        if (!isEnabled() || !DungeonsConfig.dungeonChestProfitProtection) {
            lastWarningKey = null
            return false
        }
        if (cost == null) {
            SkyMyce.logger.debug("Dungeon chest detected but its opening cost is unavailable; allowing interaction")
            return false
        }

        SkyMyce.logger.debug("Dungeon chest detected: {}", chest.label)
        SkyMyce.logger.debug("Chest cost: {}", cost)
        val result = try {
            DungeonChestProfitCalculator.calculate(cost, contents) { id ->
                id.getPriceOrNull(DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType)
            }
        } catch (exception: Exception) {
            SkyMyce.logger.warn("Could not estimate dungeon chest rewards; allowing interaction", exception)
            return false
        }

        val estimate = (result as? DungeonChestProfitCalculator.Result.Available)?.estimate
        if (estimate == null) {
            val reason = (result as DungeonChestProfitCalculator.Result.Unavailable).reason
            val key = "${chest.name}:$reason"
            val now = System.currentTimeMillis()
            if (key != lastUnavailableKey || now - lastUnavailableAt >= WARNING_COOLDOWN_MS) {
                SkyMyce.logger.warn("Could not estimate dungeon chest rewards ({}); allowing interaction", reason)
                lastUnavailableKey = key
                lastUnavailableAt = now
            }
            return false
        }

        SkyMyce.logger.debug("Estimated reward value: {}", estimate.rewardValue)
        SkyMyce.logger.debug("Estimated profit: {}", estimate.profit)
        if (!DungeonChestProfitCalculator.blocks(estimate.profit, DungeonsConfig.negativeProfitThreshold)) {
            lastWarningKey = null
            return false
        }

        if (MC.instance.hasControlDown()) {
            SkyMyce.logger.debug("Negative-profit chest override detected (CTRL held); allowing chest interaction")
            lastWarningKey = null
            return false
        }

        SkyMyce.logger.debug("Chest opening blocked")
        showWarning(chest, estimate)
        return true
    }

    fun resetWarning() {
        lastWarningKey = null
    }

    private fun showWarning(chest: DungeonChest, estimate: DungeonChestProfitCalculator.Estimate) {
        val key = "${chest.name}:${estimate.chestCost}:${estimate.rewardValue.toBits()}:${estimate.profit.toBits()}"
        val now = System.currentTimeMillis()
        if (key == lastWarningKey && now - lastWarningAt < WARNING_COOLDOWN_MS) return
        lastWarningKey = key
        lastWarningAt = now

        val loss = -estimate.profit
        val player = MC.instance.player ?: return
        player.sendSystemMessage(Component.literal("Sky-Myce").withStyle(ChatFormatting.LIGHT_PURPLE))
        player.sendSystemMessage(Component.literal("⚠ Negative Profit Dungeon Chest").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
        player.sendSystemMessage(Component.literal("Chest Cost: ${coins(estimate.chestCost)} coins"))
        player.sendSystemMessage(Component.literal("Estimated Value: ${coins(estimate.rewardValue)} coins"))
        player.sendSystemMessage(Component.literal("Estimated Profit: ${coins(estimate.profit)} coins").withStyle(ChatFormatting.RED))
        player.sendSystemMessage(Component.literal("Opening this chest is expected to lose ${coins(loss)} coins."))
        player.sendSystemMessage(Component.literal("Hold CTRL while opening to override this protection.").withStyle(ChatFormatting.YELLOW))
    }

    private fun coins(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

    private fun coins(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
    }.format(value)
}
