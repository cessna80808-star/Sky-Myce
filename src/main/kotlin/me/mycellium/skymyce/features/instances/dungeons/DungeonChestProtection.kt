package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.utils.ItemUtils.getPriceOrNull
import me.mycellium.skymyce.utils.MC
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import java.text.NumberFormat
import java.util.Locale

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
