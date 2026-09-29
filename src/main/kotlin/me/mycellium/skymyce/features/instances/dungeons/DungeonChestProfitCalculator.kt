package me.mycellium.skymyce.features.instances.dungeons

import java.math.BigDecimal

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
