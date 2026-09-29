package me.mycellium.skymyce.features.instances.dungeons

fun checkDungeonChestProfit() {
    val prices = mapOf("A" to 200_000.0, "B" to 500_000.0)
    val estimate = DungeonChestProfitCalculator.calculate(1_000_000L, mapOf("A" to 2, "B" to 1), prices::get)
    check(estimate == DungeonChestProfitCalculator.Result.Available(
        DungeonChestProfitCalculator.Estimate(1_000_000L, 900_000.0, -100_000.0)
    ))
    val breakEven = DungeonChestProfitCalculator.calculate(900_000L, mapOf("A" to 2, "B" to 1), prices::get)
    check(breakEven is DungeonChestProfitCalculator.Result.Available && breakEven.estimate.profit == 0.0)
    val profitable = DungeonChestProfitCalculator.calculate(800_000L, mapOf("A" to 2, "B" to 1), prices::get)
    check(profitable is DungeonChestProfitCalculator.Result.Available && profitable.estimate.profit > 0.0)
    val decimalBreakEven = DungeonChestProfitCalculator.calculate(3L, mapOf("A" to 1, "B" to 1)) {
        if (it == "A") 0.1 else 2.9
    }
    check(decimalBreakEven is DungeonChestProfitCalculator.Result.Available && decimalBreakEven.estimate.profit == 0.0)

    check(DungeonChestProfitCalculator.calculate(null, mapOf("A" to 1), prices::get) is DungeonChestProfitCalculator.Result.Unavailable)
    check(DungeonChestProfitCalculator.calculate(1L, mapOf("unknown" to 1), prices::get) is DungeonChestProfitCalculator.Result.Unavailable)
    check(DungeonChestProfitCalculator.calculate(1L, emptyMap<String, Int>()) { 1.0 } is DungeonChestProfitCalculator.Result.Unavailable)
    check(DungeonChestProfitCalculator.calculate(1L, mapOf("A" to 0)) { 1.0 } is DungeonChestProfitCalculator.Result.Unavailable)
    check(DungeonChestProfitCalculator.calculate(1L, mapOf("A" to 1)) { Double.NaN } is DungeonChestProfitCalculator.Result.Unavailable)

    check(DungeonChestProfitCalculator.blocks(-0.01, 0.0))
    check(!DungeonChestProfitCalculator.blocks(0.0, 0.0))
    check(!DungeonChestProfitCalculator.blocks(-100.0, 100.0))
    check(DungeonChestProfitCalculator.blocks(-100.01, 100.0))
    println("Dungeon chest profit checks passed: estimation, missing data, break-even and configurable loss thresholds")
}

fun main() = checkDungeonChestProfit()
