package me.mycellium.skymyce.features.garden

import me.mycellium.skymyce.features.garden.greenhouse.*
import kotlin.math.abs

private fun check(condition: Boolean, message: () -> String) { if (!condition) throw AssertionError(message()) }

private val catalog = parsePlantCatalog(Plant::class.java.getResourceAsStream("/greenhouse/plants.json")!!.reader().readText())
private val full = BooleanArray(GREENHOUSE_CELLS) { true }
/** skymutations.eu's starting slots: the 4x4 at rows/columns 3-6 without its corners. */
private val starting = BooleanArray(GREENHOUSE_CELLS) { cell ->
    val r = cell / 10; val c = cell % 10
    r in 3..6 && c in 3..6 && !((r == 3 || r == 6) && (c == 3 || c == 6))
}

/** Independent of the solver: every target's ring holds its ingredients, nothing overlaps, everything is unlocked. */
private fun validate(layout: Layout, unlocked: BooleanArray) {
    val owner = arrayOfNulls<Placement>(GREENHOUSE_CELLS)
    for (placement in layout.placements) for (r in 0 until placement.size) for (c in 0 until placement.size) {
        val cell = (placement.row + r) * 10 + placement.col + c
        check(unlocked[cell] && owner[cell] == null) { "Overlap or locked cell at $cell" }
        owner[cell] = placement
    }
    for (target in layout.targets) {
        val counts = HashMap<String, Int>()
        for (r in target.row - 1..target.row + target.size) for (c in target.col - 1..target.col + target.size) {
            if (r !in 0..9 || c !in 0..9 || (r in target.row until target.row + target.size && c in target.col until target.col + target.size)) continue
            owner[r * 10 + c]?.takeIf { it.role == Role.INGREDIENT }?.let { counts.merge(it.plantId, 1, Int::plus) }
        }
        catalog[target.plantId]!!.requires.forEach { (id, count) -> check((counts[id] ?: 0) >= count) { "${target.plantId} at ${target.row},${target.col} lacks $id" } }
    }
}

private fun countSolve(target: String, unlocked: BooleanArray): Int {
    // Revenue dominates cost, so profit maximisation reduces to the site's "maximize count" mode.
    val quotes = catalog.plants.associate { it.bazaarId to Quote(1.0, 1.0) } + (catalog[target]!!.bazaarId to Quote(1e7, 1e7))
    val settings = GreenhouseSettings(0, 0, PriceMode.SELL_OFFER)
    val layout = GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(target)).solve(7, 0, "check")!!
    validate(layout, unlocked)
    return layout.targets.size
}

fun main() {
    check(catalog.plants.size == 57 && catalog["GLASSCORN"]!!.let { it.size == 2 && it.soils == listOf(Soil.SAND) && it.requires == mapOf("STARTLEVINE" to 6, "CHLORONITE" to 6) }) { "Catalog" }
    check(catalog["WHEAT_SEEDS"]!!.bazaarId == "SEEDS" && catalog.plants.count { it.unique } == 12) { "Raw crop ids" }
    check(runCatching { parsePlantCatalog("""{"version":1,"plants":[{"id":"A","name":"A","rarity":"RARE","size":1,"soils":[],"stages":1,"requires":{"B":1}}]}""") }.isFailure) { "Dangling ingredient accepted" }

    // Site timing: 4h per stage minus 2.5 points per upgrade level (≤8), +5 at level 9, and 2.5 per unique crop.
    check(hoursPerStage(0, 0) == 4.0 && abs(hoursPerStage(9, 12) - 1.8) < 1e-9 && abs(hoursPerStage(8, 0) - 3.2) < 1e-9) { "Timer" }

    val quotes = mapOf("GLASSCORN" to Quote(900_000.0, 1_000_000.0), "STARTLEVINE" to Quote(9_000.0, 10_000.0), "CHLORONITE" to Quote(1_000.0, 2_000.0),
        "ALL_IN_ALOE" to Quote(5e6, 5e6), "MAGIC_JELLYBEAN" to Quote(100.0, 100.0), "PLANTBOY_ADVANCE" to Quote(1e5, 1e5))
    val ranked = rankMutations(catalog, quotes, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER))
    val glasscorn = ranked.single { it.plant.id == "GLASSCORN" }
    check(glasscorn.revenue == 1_000_000.0 && glasscorn.inputCost == 6 * 10_000.0 + 6 * 2_000.0 && glasscorn.cycleHours == 36.0) { "Glasscorn ${glasscorn}" }
    // PlantBoy Advance (2x2) is charged per tile, as on the site: 2 x 100k, not 1.
    check(ranked.single { it.plant.id == "ALL_IN_ALOE" }.inputCost == 6 * 100.0 + 2 * 1e5) { "PlantBoy exception" }
    check(ranked.none { it.plant.id == "SOGGYBUD" }) { "Unpriced mutation ranked" }
    check(rankMutations(catalog, quotes, GreenhouseSettings(0, 0, PriceMode.INSTANT_SELL)).single { it.plant.id == "GLASSCORN" }.revenue == 900_000.0) { "Instant sell" }

    // Parity with api.skymutations.eu/solve "maximize" outputs captured 2026-10-20.
    val reference = listOf(Triple("GLOOMGOURD", starting, 8), Triple("GLOOMGOURD", full, 72), Triple("CHLORONITE", full, 16), Triple("GLASSCORN", full, 9))
    val parity = reference.map { (target, grid, site) ->
        val started = System.nanoTime()
        val ours = countSolve(target, grid)
        println("  $target on ${grid.count { it }} cells: solver $ours, skymutations.eu $site (${(System.nanoTime() - started) / 1_000_000} ms)")
        Triple(target, ours, site)
    }
    // Measured 2026-10-20: 8/8, 66/72, 16/16, 8/9. The floor catches solver regressions, not exact parity.
    parity.forEach { (target, ours, site) -> check(ours >= site * 0.85) { "$target: $ours vs site $site" } }

    val priced = catalog.plants.associate { it.bazaarId to Quote(100.0, 120.0) } + ("GLASSCORN" to Quote(9e5, 1e6))
    val settings = GreenhouseSettings(3, 4, PriceMode.SELL_OFFER)
    val hash = inputsHash(full, listOf("GLASSCORN"), settings, catalog, priced)
    check(hash == inputsHash(full, listOf("GLASSCORN"), settings, catalog, priced + ("GLASSCORN" to Quote(9.1e5, 1.01e6)))) { "Small move changed hash" }
    check(hash != inputsHash(full, listOf("GLASSCORN"), settings, catalog, priced + ("GLASSCORN" to Quote(2e6, 2e6)))) { "Large move kept hash" }
    val layout = GreenhouseSolver(catalog, priced, settings, full, listOf("GLASSCORN")).solve(1, 5, hash)!!
    validate(layout, full)
    val restored = layoutFromJson(layout.toJson(), catalog)
    check(restored.placements == layout.placements && restored.cells == layout.cells && restored.inputsHash == hash) { "Round trip" }
    check(layout.cells.values.filter { it.plantId == "GLASSCORN" }.all { it.soil == Soil.SAND && it.role == Role.TARGET }) { "Soil" }

    println("Greenhouse checks passed: catalog, site profit formula, timer, hash buckets, solver validity and parity, persistence")
}
