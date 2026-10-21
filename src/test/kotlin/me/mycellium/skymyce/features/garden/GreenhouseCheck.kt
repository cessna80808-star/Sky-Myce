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

    // The screen's grid is rotated: data row r is screen column r, data column c is screen row c; a 2×2 footprint at (2, 5) spans x 32..63, y 80..111 at 16px cells.
    check(greenhouseCellAt(2 * 16 + 3, 5 * 16 + 1, 16) == 25 && greenhouseCellAt(63, 111, 16) == 36 && greenhouseCellAt(0, 0, 16) == 0 && greenhouseCellAt(159, 159, 16) == 99) { "Grid rotation" }

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

    // A pinned layout is re-priced, not re-solved: at the solve prices the estimate equals the solver's objective.
    check(abs(layout.profitPerHour(catalog, priced, settings)!! - layout.totalProfitPerHour) < 1e-6) { "Repricing differs from the solver" }
    val cheaper = priced + ("GLASSCORN" to Quote(4e5, 5e5))
    check(layout.profitPerHour(catalog, cheaper, settings)!! < layout.totalProfitPerHour && layout.profitPerHour(catalog, priced - "GLASSCORN", settings) == null) { "Repricing" }
    check(abs(glasscorn.profitPerDay - glasscorn.profitPerHour * 24) < 1e-9 && ProfitMetric.valueOf("PER_DAY").label == "Per day") { "Per day" }

    // Plots: version 1 files become plot "1" with their pinned layout; version 2 keeps each plot's own layout.
    val v1 = com.google.gson.JsonParser.parseString("""{"version":1,"anchor":{"x":1,"y":70,"z":-3,"facing":"EAST"},"locked":[3,250],"target":"GLASSCORN",
        "active":"$hash","layouts":[]}""").asJsonObject.apply { getAsJsonArray("layouts").add(layout.toJson()) }
    val migrated = plotsFromJson(v1, catalog).single()
    check(migrated.name == "1" && migrated.anchor == PlotAnchor(1, 70, -3, "EAST") && migrated.locked == setOf(3) && migrated.target == "GLASSCORN" &&
        migrated.layout?.placements == layout.placements) { "Version 1 migration $migrated" }
    val second = PlotState("north", PlotAnchor(40, 70, 9, "SOUTH"), emptySet(), null, null)
    val v2 = com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply {
        add(migrated.toJson()); add(second.toJson())
        add(com.google.gson.JsonParser.parseString("""{"name":"bad name!"}""")); add(com.google.gson.JsonParser.parseString("""{"name":"NORTH"}"""))
        add(com.google.gson.JsonParser.parseString("""{"name":"up","anchor":{"x":0,"y":0,"z":0,"facing":"UP"},"layout":{"placements":7}}"""))
    }) }
    val plots = plotsFromJson(v2, catalog)
    check(plots.map { it.name } == listOf("1", "north", "up") && plots[0].layout?.placements == layout.placements && plots[1] == second &&
        plots[2].anchor == null && plots[2].layout == null) { "Version 2 plots $plots" }
    val scanned = second.copy(plantedAt = 5, spawned = mapOf(44 to 9L), status = "o".repeat(50) + "T".repeat(50), scannedAt = 7,
        missingPlants = mapOf("CHLORONITE" to 2), missingSoil = mapOf(Soil.SAND to 3))
    val reread = plotsFromJson(com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply { add(scanned.toJson()) }) }, catalog).single()
    check(reread == scanned) { "Scan state round trip $reread" }

    // Import: the planted crops become the layout as they stand, instead of a fresh solve.
    val planted = arrayOfNulls<String>(GREENHOUSE_CELLS)
    val soil = arrayOfNulls<Soil>(GREENHOUSE_CELLS)
    val open = layout.targets.first()
    layout.placements.forEach { placement -> for (r in 0 until placement.size) for (c in 0 until placement.size) {
        val cell = (placement.row + r) * GREENHOUSE_SIZE + placement.col + c
        soil[cell] = layout.soils.getValue(placement)
        if (placement != open) planted[cell] = placement.plantId
    } }
    val occupied = BooleanArray(GREENHOUSE_CELLS) { planted[it] != null }
    val imported = importLayout(catalog, planted, occupied, soil, full, null, priced, settings, 9, "imported-x")!!
    check(imported.targets.all { it.plantId == "GLASSCORN" } && imported.targets.size == layout.targets.size) { "Import targets ${imported.targets}" }
    check(imported.placements.filter { it.role == Role.INGREDIENT }.toSet() == layout.placements.filter { it.role == Role.INGREDIENT }.toSet()) { "Import ingredients" }
    validate(imported, full)
    check(imported.totalProfitPerHour == imported.profitPerHour(catalog, priced, settings)) { "Import profit" }
    check(importLayout(catalog, arrayOfNulls(GREENHOUSE_CELLS), BooleanArray(GREENHOUSE_CELLS), soil, full, null, priced, settings, 9, "x") == null) { "Empty import" }

    // A plot as seen in game (2026-10-21): two Coalroot, each ringed by 5 Ashwreath (wheat blocks on soul sand) and 3
    // Scourroot heads, plus a stray Scourroot that has its own Potato and Carrot. Coalroot is what the plot is built for,
    // even when Scourroot is priced higher.
    check(GreenhouseWorld.plantFor("block:wheat@soul_sand", catalog, emptyMap()) == "ASHWREATH" &&
        GreenhouseWorld.plantFor("block:wheat@farmland", catalog, emptyMap()) == "WHEAT_SEEDS" &&
        GreenhouseWorld.plantFor("head:a9da3b8dcffbb5dd9708b83e54746fced475f0ee16c6c0ce4668cca7999c4d1e", catalog, emptyMap()) == "SCOURROOT" &&
        GreenhouseWorld.plantFor("head:f946443fa0039354edd31a70c749c4f963464744dc20b79137bd9910356ee90", catalog, emptyMap()) == "COALROOT" &&
        GreenhouseWorld.plantFor("head:unknown", catalog, mapOf("head:unknown" to "COALROOT")) == "COALROOT" &&
        catalog.byHead.size == 41) { "Crop fingerprints" }
    val field = arrayOfNulls<String>(GREENHOUSE_CELLS)
    val fieldSoil = Array<Soil?>(GREENHOUSE_CELLS) { Soil.FARMLAND }
    fun put(row: Int, col: Int, id: String) { field[row * 10 + col] = id; if (id == "ASHWREATH") fieldSoil[row * 10 + col] = Soil.SOUL_SAND }
    for ((centreRow, centreCol) in listOf(2 to 2, 2 to 6)) {
        put(centreRow, centreCol, "COALROOT")
        listOf(-1 to -1, -1 to 1, 0 to -1, 1 to -1, 1 to 1).forEach { (r, c) -> put(centreRow + r, centreCol + c, "ASHWREATH") }
        listOf(-1 to 0, 0 to 1, 1 to 0).forEach { (r, c) -> put(centreRow + r, centreCol + c, "SCOURROOT") }
    }
    put(7, 1, "SCOURROOT"); put(6, 0, "POTATO"); put(8, 0, "CARROT"); put(9, 8, "WHEAT_SEEDS"); put(9, 9, "WHEAT_SEEDS")
    val scourrootRich = catalog.plants.associate { it.bazaarId to Quote(10.0, 10.0) } + ("SCOURROOT" to Quote(1e7, 1e7)) + ("COALROOT" to Quote(5e4, 5e4))
    val fieldLayout = importLayout(catalog, field, BooleanArray(GREENHOUSE_CELLS) { field[it] != null }, fieldSoil, full, null, scourrootRich,
        GreenhouseSettings(0, 0, PriceMode.SELL_OFFER), 9, "imported-field")!!
    check(fieldLayout.targets.map { it.plantId }.toSet() == setOf("COALROOT") && fieldLayout.targets.size >= 2) { "Field import chose ${fieldLayout.targets}" }
    check(fieldLayout.placements.filter { it.plantId == "ASHWREATH" }.all { fieldLayout.soils[it] == Soil.SOUL_SAND }) { "Ashwreath soil" }
    validate(fieldLayout, full)

    // Unrecognised looks are named from the recipe they would complete: the 3 unknown heads around each Coalroot are Scourroot.
    val partial = field.copyOf()
    val looks = arrayOfNulls<String>(GREENHOUSE_CELLS)
    for (cell in 0 until GREENHOUSE_CELLS) if (partial[cell] == "SCOURROOT" && cell != 71) { partial[cell] = null; looks[cell] = "head:young-scourroot" }
    partial[71] = null; looks[71] = "head:lonely"
    check(inferCrops(catalog, partial, looks) == mapOf("head:young-scourroot" to "SCOURROOT")) { "Inference ${inferCrops(catalog, partial, looks)}" }
    // A look that could be either of two short ingredients stays unnamed.
    val tied = arrayOfNulls<String>(GREENHOUSE_CELLS).also { it[22] = "COALROOT" }
    val tiedLooks = arrayOfNulls<String>(GREENHOUSE_CELLS).also { cells -> listOf(11, 12, 13, 21, 23, 31, 32, 33).forEach { cells[it] = "head:same" } }
    check(inferCrops(catalog, tied, tiedLooks).isEmpty()) { "Ambiguous inference" }
    val namedPlot = PlotState("north", null, emptySet(), null, null, learned = mapOf("block:melon_stem@farmland" to "COALROOT"))
    check(plotsFromJson(com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply { add(namedPlot.toJson()) }) }, catalog).single() == namedPlot) { "Plot looks" }

    // Multi-tile crops seen on only some cells: a 3×3 with one head in the middle is centred on it, a full 2×2 keeps its
    // corner, and two touching 2×2 groups are tiled.
    val tiles = arrayOfNulls<String>(GREENHOUSE_CELLS)
    tiles[44] = "SNOOZLING"
    listOf(0, 1, 10, 11).forEach { tiles[it] = "GLASSCORN" }
    listOf(7, 8, 17, 18, 27, 28, 37, 38).forEach { tiles[it] = "GLASSCORN" }
    check(footprints(catalog, tiles).toSet() == setOf(Triple("SNOOZLING", 3, 3), Triple("GLASSCORN", 0, 0), Triple("GLASSCORN", 0, 7), Triple("GLASSCORN", 2, 7))) {
        "Footprints ${footprints(catalog, tiles)}"
    }
    val edge = arrayOfNulls<String>(GREENHOUSE_CELLS).also { it[0] = "SNOOZLING" }
    check(footprints(catalog, edge) == listOf(Triple("SNOOZLING", 0, 0))) { "Edge footprint" }

    // Plant Diagnostics Tool readings: chat or menu text with colour codes, projected forward with the growth timer.
    check(parseGrowth(listOf("§aCoalroot", "§7Growth Stage: §e5§7/§e8")) == (5 to 8) && parseGrowth(listOf("Stage 3 of 16")) == (3 to 16)) { "Growth parse" }
    check(parseGrowth(listOf("Water Level: 80%", "Next Stage in 2h")) == null && parseGrowth(listOf("Growth Stage: 9/8")) == null) { "Growth rejects" }
    val readAt = 1_800_000_000_000L
    val reading = GrowthReading(5, 8, readAt - 6 * 3_600_000L)
    check(abs(reading.projected(3.0, readAt) - 7.0) < 1e-9 && reading.projected(1.0, readAt) == 8.0) { "Growth projection" }
    val grown = namedPlot.copy(growth = mapOf(33 to reading))
    check(plotsFromJson(com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply { add(grown.toJson()) }) }, catalog).single() == grown) { "Growth persistence" }

    // Harvest history: rolling totals per plot and calendar-day buckets.
    val now = 1_800_000_000_000L
    val hour = 3_600_000L
    val history = listOf(Harvest(now - hour, "1", "GLASSCORN", 150.0, 50.0), Harvest(now - 72 * hour, "1", "GLASSCORN", 200.0, 0.0),
        Harvest(now - 480 * hour, "north", "GLASSCORN", 300.0, 0.0), Harvest(now - 1440 * hour, "1", "GLASSCORN", 400.0, 0.0))
    check(harvestTotals(history, now) == HarvestTotals(100.0, 300.0, 600.0, 1000.0, 4)) { "Totals ${harvestTotals(history, now)}" }
    check(harvestTotals(history, now, "north") == HarvestTotals(0.0, 0.0, 300.0, 300.0, 1)) { "Plot totals" }
    val days = dailyProfit(history, now, 14, java.time.ZoneOffset.UTC)
    check(days.size == 14 && days.sum() == 300.0 && days[13] == 100.0 && days[10] == 200.0) { "Daily ${days.toList()}" }
    check(history.all { harvestFromJson(it.toJson(), catalog) == it } && harvestFromJson(com.google.gson.JsonParser.parseString("""[1,"bad name!","GLASSCORN",1,1]"""), catalog) == null) { "Harvest json" }

    println("Greenhouse checks passed: catalog, site profit formula, timer, hash buckets, solver validity and parity, repricing, per-day profit, plot persistence, import and harvest history")
}
