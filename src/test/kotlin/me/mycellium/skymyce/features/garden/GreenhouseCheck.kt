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

    // Wiki timing: 4h divided by 1 + 2.5% per unique crop (≤10) + 0.25% per Crop Growth + the Desk (5% a tier, 10% at IX).
    check(hoursPerStage(0, 0) == 4.0 && abs(hoursPerStage(9, 12) - 4.0 / 1.75) < 1e-9 && abs(hoursPerStage(8, 0) - 4.0 / 1.4) < 1e-9 &&
        abs(hoursPerStage(9, 10, 210) * 3600 - 14400 / 2.275) < 1e-6) { "Timer" }
    check(gardenCropGrowth(15) == 140 && gardenCropGrowth(1) == 0 && gardenCropGrowth(null) == 0) { "Garden Crop Growth" }

    val quotes = mapOf("GLASSCORN" to Quote(900_000.0, 1_000_000.0), "STARTLEVINE" to Quote(9_000.0, 10_000.0), "CHLORONITE" to Quote(1_000.0, 2_000.0),
        "ALL_IN_ALOE" to Quote(5e6, 5e6), "MAGIC_JELLYBEAN" to Quote(100.0, 100.0), "PLANTBOY_ADVANCE" to Quote(1e5, 1e5))
    val ranked = rankMutations(catalog, quotes, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER))
    val glasscorn = ranked.single { it.plant.id == "GLASSCORN" }
    check(glasscorn.revenue == 1_000_000.0 && glasscorn.inputCost == 6 * 10_000.0 + 6 * 2_000.0 && glasscorn.cycleHours == 32.0) { "Glasscorn ${glasscorn}" }
    // PlantBoy Advance (2x2) is charged per tile, as on the site: 2 x 100k, not 1.
    check(ranked.single { it.plant.id == "ALL_IN_ALOE" }.inputCost == 6 * 100.0 + 2 * 1e5) { "PlantBoy exception" }
    check(ranked.none { it.plant.id == "SOGGYBUD" }) { "Unpriced mutation ranked" }
    // Coalroot still ranks and solves when Scourroot has no price (no sell offers): only the priced Ashwreath is charged.
    val noScourroot = mapOf("COALROOT" to Quote(1e5, 3.7e5), "ASHWREATH" to Quote(1_000.0, 1_800.0))
    check(rankMutations(catalog, noScourroot, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER)).single { it.plant.id == "COALROOT" }.inputCost == 5 * 1_800.0) {
        "Coalroot with an unpriced ingredient"
    }
    check(GreenhouseSolver(catalog, noScourroot, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER), full, listOf("COALROOT"), 20_000).solve(3, 0, "x") != null) {
        "Coalroot solve with an unpriced ingredient"
    }
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
    // An ingredient without a price (Scourroot with no sell offers) is left out of the cost instead of voiding the estimate.
    check(layout.profitPerHour(catalog, priced - "STARTLEVINE", settings)!! > layout.totalProfitPerHour) { "Unpriced ingredient" }
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
        GreenhouseWorld.plantFor("head:d4b3ea5cb6b6f046e326621ca11ffb7d6aec22d66c0d81e5039b19ee4400309f", catalog, emptyMap()) == "CACTUS" &&
        catalog.byHead.size == 45) { "Crop fingerprints" }
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

    // A melon stem named Coalroot on this plot is only a young Coalroot where its ring supplies one; alone at the edge it is a melon.
    val edged = field.copyOf().also { it[90] = "COALROOT" }
    val edgeLooks = arrayOfNulls<String>(GREENHOUSE_CELLS).also { it[90] = "MELON_SEEDS" }
    val edgedLayout = importLayout(catalog, edged, BooleanArray(GREENHOUSE_CELLS) { edged[it] != null }, fieldSoil, full, null, scourrootRich,
        GreenhouseSettings(0, 0, PriceMode.SELL_OFFER), 9, "imported-edge", edgeLooks)!!
    check(edgedLayout.placements.single { it.row == 9 && it.col == 0 }.let { it.plantId == "MELON_SEEDS" && it.role == Role.INGREDIENT } &&
        edgedLayout.targets.size == fieldLayout.targets.size) { "Edge melon ${edgedLayout.placements.filter { it.row == 9 }}" }

    // Snoozling as seen in game (2026-10-21): a plus of heads with decorative wheat in its corners, ringed by its 16 ingredients.
    val snooze = arrayOfNulls<String>(GREENHOUSE_CELLS)
    listOf(34, 43, 44, 45, 54).forEach { snooze[it] = "SNOOZLING" }
    listOf(33, 35, 53, 55).forEach { snooze[it] = "WHEAT_SEEDS" }
    val ring = listOf(22, 23, 24, 25, 26, 32, 36, 42, 46, 52, 56, 62, 63, 64, 65, 66)
    val recipe = listOf("CREAMBLOOM", "CREAMBLOOM", "CREAMBLOOM", "CREAMBLOOM", "DUSTGRAIN", "DUSTGRAIN", "DUSTGRAIN", "WITHERBLOOM", "WITHERBLOOM",
        "WITHERBLOOM", "DUSKBLOOM", "DUSKBLOOM", "DUSKBLOOM", "THORNSHADE", "THORNSHADE", "THORNSHADE")
    ring.zip(recipe).forEach { (cell, id) -> snooze[cell] = id }
    val snoozeLayout = importLayout(catalog, snooze, BooleanArray(GREENHOUSE_CELLS) { snooze[it] != null }, Array(GREENHOUSE_CELLS) { Soil.FARMLAND }, full, null,
        catalog.plants.associate { it.bazaarId to Quote(100.0, 100.0) } + ("SNOOZLING" to Quote(5e6, 5e6)), GreenhouseSettings(0, 0, PriceMode.SELL_OFFER), 9, "imported-snooze")!!
    check(snoozeLayout.targets == listOf(Placement("SNOOZLING", 3, 3, 3, Role.TARGET)) && snoozeLayout.placements.none { it.plantId == "WHEAT_SEEDS" }) {
        "Snoozling import ${snoozeLayout.placements}"
    }

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
    // As read in game on 2026-10-22: Scourroot has 0 stages and is always "FULLY GROWN".
    check(parseGrowth(listOf("Scourroot", "Growth Stages: 0", "Stage: FULLY GROWN", "Next Stage: FULLY GROWN")) == (1 to 1) &&
        parseGrowth(listOf("Growth Stages: 8", "§7Stage: §aFULLY GROWN")) == (8 to 8)) { "Fully grown" }
    val readAt = 1_800_000_000_000L
    val reading = GrowthReading(5, 8, readAt - 6 * 3_600_000L)
    val coalroot = catalog["COALROOT"]
    check(estimateGrowth(coalroot, reading, 3.0, readAt).let { abs(it.stage - 7.0) < 1e-9 && !it.ready && it.readyInMs == 3 * 3_600_000L }) { "Growth projection" }
    check(estimateGrowth(coalroot, reading, 1.0, readAt).let { it.stage == 8.0 && it.ready && it.readyInMs == 0L }) { "Growth projection caps" }
    // Snoozling sleeps at 5, 10 and 15 until right-clicked: read at 3, it stops at 5; woken there, it grows on to 10.
    val snoozling = catalog["SNOOZLING"]
    check(estimateGrowth(snoozling, GrowthReading(3, 20, readAt - 30 * 3_600_000L), 3.0, readAt).let { it.paused && it.stage == 5.0 && it.readyInMs == null }) { "Snoozling sleeps" }
    check(estimateGrowth(snoozling, GrowthReading(5, 20, readAt), 3.0, readAt).paused) { "Read asleep" }
    check(estimateGrowth(snoozling, GrowthReading(5, 20, readAt - 6 * 3_600_000L, awake = true), 3.0, readAt).let { !it.paused && it.stage == 7.0 }) { "Woken Snoozling" }
    check(estimateGrowth(snoozling, GrowthReading(5, 20, readAt - 60 * 3_600_000L, awake = true), 3.0, readAt).let { it.paused && it.stage == 10.0 }) { "Sleeps again" }
    // Glasscorn is ready at 7 and 8, then another stage starts it again from 1.
    val glasscornPlant = catalog["GLASSCORN"]
    check(estimateGrowth(glasscornPlant, GrowthReading(6, 8, readAt - 3 * 3_600_000L), 3.0, readAt).ready) { "Glasscorn ready at 7" }
    check(estimateGrowth(glasscornPlant, GrowthReading(8, 8, readAt - 3 * 3_600_000L), 3.0, readAt).let { it.stage == 1.0 && !it.ready && it.readyInMs == 6 * 3 * 3_600_000L }) { "Glasscorn restarts" }
    // Fleshtrap stops when hungry, so a reading is not projected.
    check(estimateGrowth(catalog["FLESHTRAP"], GrowthReading(4, 14, readAt - 30 * 3_600_000L), 3.0, readAt).let { it.stage == 4.0 && !it.ready && it.readyInMs == null }) { "Unpredictable growth" }
    val grown = namedPlot.copy(growth = mapOf(33 to reading, 34 to GrowthReading(5, 20, readAt, awake = true)))
    check(plotsFromJson(com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply { add(grown.toJson()) }) }, catalog).single() == grown) { "Growth persistence" }
    val watered = grown.copy(growth = mapOf(44 to GrowthReading(5, 8, readAt, water = 48.8, drain = 12.5), 45 to GrowthReading(2, 8, readAt, drain = 10.0)))
    check(plotsFromJson(com.google.gson.JsonObject().apply { add("plots", com.google.gson.JsonArray().apply { add(watered.toJson()) }) }, catalog).single() == watered) { "Water persistence" }

    // A Crop Diagnostics menu as read in game on 2026-10-22 (one line per lore line), here mid-growth with a countdown.
    val menu = "Crop Diagnostics |  | Coalroot | Mutation Crop |  | Size: 1x1 | Growth Surface: Farmland | Growth Stages: 8 |  | Growth Stage | Age: 1d 10h 41m 12s | " +
        "Stage: 5/8 | Next Stage: 1h 40m 20s | Times Mutated: 0 |  | Decay Info | Mutates Remaining: 10 | Water Level | §7Water: §b48.8§7/100 |  | " +
        "Shows how much water the current | crop has, ranging between -100 | (halted) and 100 (fully watered)."
    check(parseDiagnostics(menu.split(" | ")) == Diagnostics(5, 8, 48.8, true, (3600 + 40 * 60 + 20) * 1000L)) { "Diagnostics ${parseDiagnostics(menu.split(" | "))}" }
    check(parseDiagnostics(listOf("Scourroot", "Growth Stages: 0", "Stage: FULLY GROWN", "Next Stage: FULLY GROWN", "Water: 100/100", "This crop doesn't need water!")) ==
        Diagnostics(1, 1, null, false, null)) { "Diagnostics without water" }
    check(parseDuration("1d 10h 41m 12s") == 124_872_000L && parseDuration("45s") == 45_000L && parseDuration("FULLY GROWN") == null) { "Durations" }
    // The Desk's Greenhouse Upgrades item; the tier panes ("Growth Speed IV") and other menus are not it.
    val deskLore = listOf("§7Upgrade your §aGrowth Speed §7tier to", "§7increase your §bGreenhouse Growth", "§bSpeed.", "", "§7Current Tier: §e3§7/§a9", "§7Growth Speed: §b15%")
    check(parseDeskGrowthTier("§aGrowth Speed", deskLore) == 3 && parseDeskGrowthTier("Growth Speed IV", deskLore) == null &&
        parseDeskGrowthTier("Growth Speed", listOf("Current Tier: 3/9")) == null) { "Desk tier" }

    // The shared growth tick: ticks in (from, to] and the n-th tick after a time.
    val hour = 3_600_000L
    val tick = GrowthCycle(readAt, 2 * hour)
    check(tick.ticks(readAt - 1, readAt) == 1 && tick.ticks(readAt, readAt + 2 * hour - 1) == 0 && tick.ticks(readAt - 5 * hour, readAt + 4 * hour) == 5 &&
        tick.tickAfter(readAt, 1) == readAt + 2 * hour && tick.tickAfter(readAt - 1, 1) == readAt && tick.tickAfter(readAt + hour, 3) == readAt + 6 * hour) { "Tick math" }
    // Two countdowns three cycles apart time the cycle; one far off (an upgrade) starts over; one in the same cycle refines the tick.
    val timed = updateCycle(GrowthCycle(readAt, 2 * hour), readAt + 3 * (2 * hour + 60_000), 30 * 60_000, 2 * hour)
    check(timed.measured && timed.cycleMs == 2 * hour + 60_000) { "Cycle measured $timed" }
    check(!updateCycle(GrowthCycle(readAt, 2 * hour, true), readAt + 3 * hour, hour, 2 * hour).measured) { "Cycle changed" }
    check(updateCycle(GrowthCycle(readAt, 2 * hour), readAt + 30_000, 1000, hour).let { it.tickAt == readAt + 30_000 && it.cycleMs == 2 * hour }) { "Same tick" }
    check(updateCycle(null, readAt, 3 * hour, 2 * hour).cycleMs == 3 * hour) { "A countdown is never longer than the cycle" }

    // On the shared tick a Coalroot read at 5/8 just after a tick is ready on the third tick after it.
    val onTick = estimateGrowth(coalroot, GrowthReading(5, 8, readAt), 2.0, readAt + hour, tick)
    check(onTick.stage == 5.0 && onTick.readyInMs == 5 * hour && onTick.dryInMs == null) { "Tick growth $onTick" }
    // Water: 48.8 at 16 a tick still has 0.8 after the 3 ticks to ready, so it is promised and needs no water reminder.
    val wet = estimateGrowth(coalroot, GrowthReading(5, 8, readAt, water = 48.8), 2.0, readAt + hour, tick, 16.0)
    check(wet.readyInMs == 5 * hour && wet.dryInMs == null) { "Water lasts $wet" }
    // At 20 a tick it would be below 0 on the tick it grows ready; the game may skip that stage, so it is not promised.
    check(estimateGrowth(coalroot, GrowthReading(5, 8, readAt, water = 48.8), 2.0, readAt + hour, tick, 20.0).let { it.readyInMs == null && it.dryInMs == 5 * hour }) { "Water edge" }
    // 30 a tick runs out on the second tick, before it is ready: it is not promised, and the dry time is when it runs out.
    val dry = estimateGrowth(coalroot, GrowthReading(5, 8, readAt, water = 48.8), 2.0, readAt + hour, tick, 30.0)
    check(dry.readyInMs == null && dry.dryInMs == 3 * hour) { "Runs dry $dry" }
    check(estimateGrowth(coalroot, GrowthReading(5, 8, readAt, water = -5.0), 2.0, readAt + hour, tick, 20.0).dryInMs == 0L) { "Already dry" }
    check(estimateGrowth(coalroot, GrowthReading(8, 8, readAt, water = 10.0), 2.0, readAt + hour, tick, 20.0).dryInMs == null) { "Grown needs no water" }
    // Drain is seen between two readings with no watering; a watered or grown one teaches nothing.
    check(drainBetween(GrowthReading(4, 8, readAt, water = 80.0), GrowthReading(6, 8, readAt + 4 * hour, water = 40.0), 2) == 20.0 &&
        drainBetween(GrowthReading(4, 8, readAt, water = 20.0), GrowthReading(6, 8, readAt, water = 90.0), 2) == null &&
        drainBetween(GrowthReading(8, 8, readAt, water = 80.0), GrowthReading(8, 8, readAt, water = 60.0), 1) == null &&
        drainBetween(GrowthReading(4, 8, readAt, water = 80.0), GrowthReading(4, 8, readAt, water = 70.0), 0) == null) { "Drain" }

    // Reminders: at 50% of 4 mutations, the second-soonest ready time; untimed ones count as not ready; nothing already true.
    fun timedIn(ms: Long?, dryMs: Long? = null) = GrowthEstimate(1.0, 8, ms == 0L, false, ms, dryMs)
    val four = listOf(timedIn(3 * hour), timedIn(hour, 5 * hour), null, timedIn(2 * hour, 5 * hour + 60_000))
    check(plotReminder(four, 50, readAt) == PlotReminder(readAt + 2 * hour, 2, readAt + 5 * hour, 2, 4)) { "Reminder ${plotReminder(four, 50, readAt)}" }
    check(plotReminder(four, 100, readAt)!!.readyAt == null && plotReminder(four, 10, readAt)!!.readyCount == 1) { "Untimed blocks 100%" }
    check(plotReminder(listOf(timedIn(0), timedIn(0), timedIn(hour)), 50, readAt)!!.readyAt == null) { "Already reached" }
    check(plotReminder(listOf(timedIn(hour, 0)), 50, readAt)!!.dryAt == null && plotReminder(emptyList(), 50, readAt) == null) { "Already dry is not reminded" }

    // Garden plot numbers for /tptoplot, as seen in game: anchors at 187/100 and 196/91 are plot 18, -92/-5 is plot 2.
    check(gardenPlotAt(187, 100) == 18 && gardenPlotAt(196, 91) == 18 && gardenPlotAt(-92, -5) == 2 && gardenPlotAt(-101, 4) == 2) { "Garden plots" }
    check(gardenPlotAt(0, 0) == null && gardenPlotAt(0, -100) == 1 && gardenPlotAt(-200, -200) == 21 && gardenPlotAt(500, 0) == null) { "Garden plot edges" }

    // Only the Bazaar amount sign gets the shopping-list amount typed in, never the price or search sign.
    check(isBazaarAmountSign(listOf("", "^^^^^^^^^^^^^^^", "Enter amount", "to order")) && !isBazaarAmountSign(listOf("", "^^^^^^^^^^^^^^^", "Enter price", "per unit")) &&
        !isBazaarAmountSign(listOf("", "^^^^^^^^^^^^^^^", "Enter query", "")) && !isBazaarAmountSign(emptyList())) { "Amount sign" }

    // Block wrong clicks: the right crop or soil passes, anything else is named.
    val carrotSlot = CellPlan("CARROT", Soil.FARMLAND, Role.INGREDIENT); val carrot = catalog["CARROT"]!!
    check(wrongSlot(carrotSlot, carrot, "CARROT_ITEM", true, emptySet()) { it.name } == null &&
        wrongSlot(carrotSlot, carrot, "POTATO_ITEM", true, emptySet()) { it.name } == "that slot is for Carrot" &&
        wrongSlot(CellPlan("GLASSCORN", Soil.SAND, Role.TARGET), catalog["GLASSCORN"], "CARROT_ITEM", true, emptySet()) { it.name } != null &&
        wrongSlot(null, null, "CARROT_ITEM", true, emptySet()) { it.name } != null &&
        wrongSlot(carrotSlot, carrot, "DIRT", false, setOf(Soil.DIRT, Soil.FARMLAND)) { it.name } == null &&
        wrongSlot(carrotSlot, carrot, "SAND", false, setOf(Soil.SAND)) { it.name } == "that slot needs FARMLAND" &&
        wrongSlot(null, null, "SAND", false, setOf(Soil.SAND)) { it.name } == null) { "Wrong clicks" }

    // Four Pumpkin Seeds are crafted from one Pumpkin; Melon Seeds one per slice.
    check(costPrice(catalog["PUMPKIN_SEEDS"]!!, mapOf("PUMPKIN" to Quote(40.0, 40.0))) == 10.0 &&
        costPrice(catalog["MELON_SEEDS"]!!, mapOf("MELON" to Quote(3.0, 3.0))) == 3.0) { "Crafted seed cost" }
    // Garden level gates mutations through their base crops: Thornshade needs Wild Rose (12).
    check(parseGardenLevel(" Garden Level: XII") == 12 && parseGardenLevel("Garden Level 9") == 9 && parseGardenLevel("Garden Level: XIV") == 14 &&
        parseGardenLevel("Garden Visitors: 3") == null && parseGardenLevel("Garden Level: 99") == null) { "Garden level parse" }
    val roseQuotes = mapOf("THORNSHADE" to Quote(1e5, 1e5), "WILD_ROSE" to Quote(5.0, 5.0), "VEILSHROOM" to Quote(10.0, 10.0))
    check(gardenLevelFor(catalog, catalog["THORNSHADE"]!!) == 12 &&
        rankMutations(catalog, roseQuotes, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER, 11)).none { it.plant.id == "THORNSHADE" } &&
        rankMutations(catalog, roseQuotes, GreenhouseSettings(0, 0, PriceMode.SELL_OFFER, 12)).any { it.plant.id == "THORNSHADE" }) { "Garden level gate" }

    // Harvest history: rolling totals per plot and calendar-day buckets.
    val now = 1_800_000_000_000L
    val history = listOf(Harvest(now - hour, "1", "GLASSCORN", 150.0, 50.0), Harvest(now - 72 * hour, "1", "GLASSCORN", 200.0, 0.0),
        Harvest(now - 480 * hour, "north", "GLASSCORN", 300.0, 0.0), Harvest(now - 1440 * hour, "1", "GLASSCORN", 400.0, 0.0))
    check(harvestTotals(history, now) == HarvestTotals(100.0, 300.0, 600.0, 1000.0, 4)) { "Totals ${harvestTotals(history, now)}" }
    check(harvestTotals(history, now, "north") == HarvestTotals(0.0, 0.0, 300.0, 300.0, 1)) { "Plot totals" }
    val days = dailyProfit(history, now, 14, java.time.ZoneOffset.UTC)
    check(days.size == 14 && days.sum() == 300.0 && days[13] == 100.0 && days[10] == 200.0) { "Daily ${days.toList()}" }
    check(history.all { harvestFromJson(it.toJson(), catalog) == it } && harvestFromJson(com.google.gson.JsonParser.parseString("""[1,"bad name!","GLASSCORN",1,1]"""), catalog) == null) { "Harvest json" }

    checkMarket()
    println("Greenhouse checks passed: catalog, site profit formula, timer, hash buckets, solver validity and parity, repricing, per-day profit, plot persistence, import and harvest history, Bazaar market parsing")
}

/** The Bazaar prices the planner fetches: Hypixel's raw bid/ask naming, the shared normalized form, retry deadlines and body limits. */
private fun checkMarket() {
    val now = 1_800_000_000_000L
    fun near(actual: Double?, expected: Double) = check(actual != null && abs(actual - expected) < 1e-6) { "$actual != $expected" }
    fun reject(action: () -> Unit) = check(runCatching(action).isFailure) { "Malformed market data accepted" }
    fun product(id: String) = """{"product_id":"$id","sell_summary":[{"pricePerUnit":4.2,"amount":100},{"pricePerUnit":3.8,"amount":500}],"buy_summary":[{"pricePerUnit":4.8,"amount":300},{"pricePerUnit":5.0,"amount":700}],"quick_status":{"buyMovingWeek":999999,"sellMovingWeek":1111111,"buyOrders":7,"sellOrders":11}}"""
    fun raw() = com.google.gson.JsonParser.parseString("""{"success":true,"lastUpdated":$now,"products":{"WHEAT":${product("WHEAT")},"DIAMOND":${product("DIAMOND")}}}""").asJsonObject
    // Hypixel's sell_summary holds the bids (buy orders) and buy_summary the asks (sell offers).
    val wheat = parseBazaarMarket(raw(), now).products.first { it.id == "WHEAT" }
    near(wheat.buyOrderPrice, 4.2); near(wheat.sellOfferPrice, 4.8); near(wheat.buyVolume, 600.0); near(wheat.sellVolume, 1000.0)
    check(wheat.buyOrders == 11 && wheat.sellOrders == 7) { "Order counts" }
    reject { parseBazaarMarket(raw().apply { addProperty("lastUpdated", now + 10_001) }, now) }
    reject { parseBazaarMarket(raw().apply { addProperty("success", false) }, now) }
    reject { parseBazaarMarket(raw().apply { getAsJsonObject("products").getAsJsonObject("WHEAT").getAsJsonArray("sell_summary")[0].asJsonObject.addProperty("amount", -1) }, now) }
    reject { parseBazaarMarket(raw().apply { getAsJsonObject("products").getAsJsonObject("WHEAT").addProperty("product_id", "DIAMOND") }, now) }
    check(parseBazaarMarket(raw().apply { getAsJsonObject("products").getAsJsonObject("WHEAT").remove("sell_summary") }, now).products.first { it.id == "WHEAT" }.buyOrderPrice == null) { "Missing side" }
    val normalized = com.google.gson.JsonParser.parseString("""{"ok":true,"version":1,"source":"Synthetic shared source","observedAt":$now,"retrievedAt":$now,"products":[{"id":"WHEAT","buyPrice":4.2,"sellPrice":4.8,"buyDepth":[{"price":4.2,"amount":1000}],"sellDepth":[{"price":4.8,"amount":1000}]}]}""").asJsonObject
    near(parseBazaarMarket(normalized, now, normalized = true).products.single().buyOrderPrice, 4.2)
    reject { parseBazaarMarket(normalized.deepCopy().apply { getAsJsonArray("products").add(getAsJsonArray("products")[0].deepCopy()) }, now, normalized = true) }
    reject { parseBazaarMarket(normalized.deepCopy().apply { addProperty("version", 2) }, now, normalized = true) }
    // Unmodified public Hypixel response captured 2026-10-16; compressed only to keep the fixture small.
    val live = readBazaarMarketBody(java.util.zip.GZIPInputStream(Plant::class.java.getResourceAsStream("/greenhouse/hypixel-bazaar-2026-10-16.json.gz")!!))
    val parsed = parseBazaarMarket(live, live["lastUpdated"].asLong + 15_000)
    check(parsed.products.size == 2201 && parsed.products.any { it.id == "WHEAT" && it.buyOrderPrice != null && it.sellOfferPrice != null }) { "Live body" }
    reject { readBazaarMarketBody(java.io.ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1) { ' '.code.toByte() })) }
    reject { readBazaarMarketBody(java.io.ByteArrayInputStream("[]".toByteArray())) }
    check(parseBazaarRetryAt("900", now) == now + 900_000 && parseBazaarRetryAt("  900  ", now) == now + 900_000) { "Retry seconds" }
    val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(now + 900_000).atZone(java.time.ZoneOffset.UTC))
    check(parseBazaarRetryAt(date, now) == now + 900_000) { "Retry date" }
    for (invalid in listOf(null, "", "not a retry deadline", "-1", "1.5", "Thu, 1 Jan 1970 00:00:00 GMT")) check(parseBazaarRetryAt(invalid, now) == null) { "Retry $invalid" }
    check(parseBazaarRetryAt("999999999999999999999999999999999999", now) == Long.MAX_VALUE && parseBazaarRetryAt("2", Long.MAX_VALUE - 1000) == Long.MAX_VALUE) { "Retry saturates" }
}
