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
    val allow = DungeonChestProfitCalculator.RerollVerdict.ALLOW
    val valuable = DungeonChestProfitCalculator.RerollVerdict.VALUABLE
    val unknownPrice = DungeonChestProfitCalculator.RerollVerdict.UNKNOWN_PRICE
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("A" to 2, "B" to 1), 5_000_000.0, prices::get) == allow)
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("A" to 2, "B" to 10), 5_000_000.0, prices::get) == valuable)   // 10 × 500k
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("B" to 1), 500_000.0, prices::get) == valuable)                // exactly at the threshold
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("A" to 1, "unknown" to 1), 5_000_000.0, prices::get) == unknownPrice)
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("B" to 10, "unknown" to 1), 5_000_000.0, prices::get) == valuable) // a known valuable item wins
    check(DungeonChestProfitCalculator.rerollVerdict(mapOf("A" to 1), 5_000_000.0) { 0.0 } == unknownPrice)               // zero is not a price
    check(DungeonChestProfitCalculator.rerollVerdict(emptyMap<String, Int>(), 5_000_000.0, prices::get) == unknownPrice)
    println("Dungeon chest profit checks passed: estimation, missing data, break-even, configurable loss thresholds and reroll verdicts")
}

fun main() = checkDungeonChestProfit()
