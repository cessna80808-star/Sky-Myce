package me.mycellium.skymyce.ui

import me.mycellium.skymyce.features.garden.greenhouse.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/** One planned and partly built plot with harvest history, plus an unplanned one, for the Greenhouses screen. */
internal fun greenhouseFixture(): GreenhouseScreen {
    if (GreenhousePlanner.plots.isEmpty()) {
        val catalog = GreenhousePlanner.catalog!!
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val quotes = catalog.plants.associate { it.bazaarId to Quote(100.0, 120.0) } + ("GLASSCORN" to Quote(9e5, 1e6))
        val layout = GreenhouseSolver(catalog, quotes, GreenhouseSettings(3, 4, PriceMode.SELL_OFFER), BooleanArray(GREENHOUSE_CELLS) { true }, listOf("GLASSCORN"), 8_000)
            .solve(1, now - 5 * hour, "fixture")!!
        val status = CharArray(GREENHOUSE_CELLS) { CellStatus.UNUSED.code }
        layout.placements.forEachIndexed { i, placement ->
            val code = when {
                placement.role == Role.TARGET -> if (i % 2 == 0) CellStatus.TARGET_GROWING else CellStatus.TARGET_WAITING
                i % 7 == 0 -> CellStatus.MISSING_CROP
                i % 11 == 0 -> CellStatus.WRONG_SOIL
                else -> CellStatus.DONE
            }.code
            for (r in 0 until placement.size) for (c in 0 until placement.size) status[(placement.row + r) * GREENHOUSE_SIZE + placement.col + c] = code
        }
        GreenhousePlanner.plots += GreenhousePlanner.Plot("1").apply {
            anchor = GreenhousePlanner.Anchor(BlockPos(12, 71, -40), Direction.NORTH)
            this.layout = layout; target = "GLASSCORN"
            this.status = String(status); scannedAt = now - 2 * 60_000L; plantedAt = now - 30 * hour
            layout.targets.filterIndexed { i, _ -> i % 2 == 0 }.forEachIndexed { i, t -> spawned[t.row * GREENHOUSE_SIZE + t.col] = now - (i * 9 + 4) * hour }
            missingPlants = mapOf("CHLORONITE" to 2, "STARTLEVINE" to 1); missingSoil = mapOf(Soil.SAND to 3)
            layout.targets.take(3).forEachIndexed { i, t -> growth[t.row * GREENHOUSE_SIZE + t.col] = GrowthReading(3 + i * 2, 8, now - 2 * hour) }
        }
        // A 3×3 mutation with many single unique crops, so the legend's "1 each" group and a fully built plot are covered.
        val snoozling = GreenhouseSolver(catalog, quotes + ("SNOOZLING" to Quote(4e5, 5e5)), GreenhouseSettings(3, 12, PriceMode.SELL_OFFER), BooleanArray(GREENHOUSE_CELLS) { true }, listOf("SNOOZLING"), 8_000)
            .solve(1, now - 40 * hour, "fixture")!!
        GreenhousePlanner.plots += GreenhousePlanner.Plot("2").apply {
            anchor = GreenhousePlanner.Anchor(BlockPos(12, 71, -20), Direction.NORTH)
            this.layout = snoozling; target = "SNOOZLING"
            this.status = String(CharArray(GREENHOUSE_CELLS) { i -> when (snoozling.cells[i]?.role) { null -> CellStatus.UNUSED; Role.TARGET -> CellStatus.TARGET_GROWING; else -> CellStatus.DONE }.code })
            scannedAt = now - 60_000L; plantedAt = now - 20 * hour
            snoozling.targets.forEach { spawned[it.row * GREENHOUSE_SIZE + it.col] = now - 10 * hour }
        }
        GreenhousePlanner.plots += GreenhousePlanner.Plot("north").apply { anchor = GreenhousePlanner.Anchor(BlockPos(40, 71, -40), Direction.EAST) }
        repeat(18) { i -> GreenhousePlanner.history += Harvest(now - (i * 17 + 3) * hour, "1", "GLASSCORN", 900_000.0 + i * 10_000, if (i == 5) 1_200_000.0 else 140_000.0) }
    }
    return GreenhouseScreen()
}
