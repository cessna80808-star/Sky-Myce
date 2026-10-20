package me.mycellium.skymyce.features.garden.greenhouse

import kotlin.math.exp
import kotlin.random.Random

/**
 * First-principles reimplementation; skymutations.eu's solver is server-side and unlicensed, so only its outputs were compared.
 *
 * Model (same as the site): a mutation spawns in an empty footprint when the 8-neighbour ring around the whole footprint,
 * diagonals included, holds at least the required number of planted ingredient tiles. Ingredients may serve several targets.
 * Targets never count as ingredients for each other, since they are empty until they spawn.
 *
 * Objective, whole plot per hour: each valid target earns its revenue once per growth cycle; every planted ingredient is bought
 * once per cycle of the fastest target (the site charges ingredients per spawn, so a lone placement matches its leaderboard).
 * Unique base crops on the plot shorten every cycle, so the search may add them when the faster cycle pays for them.
 *
 * Search: simulated annealing over the planted ingredients only. Targets are then filled greedily, most valuable first, into
 * every free footprint whose ring is satisfied (exact for 1x1 targets, which cannot block each other). Nearly satisfied
 * footprints earn partial credit to guide the search. A final prune drops ingredients that do not pay for themselves.
 * Deterministic for a given seed.
 */
class GreenhouseSolver(
    private val catalog: PlantCatalog,
    private val quotes: Map<String, Quote>,
    private val settings: GreenhouseSettings,
    private val unlocked: BooleanArray,
    targetIds: List<String>,
    private val iterations: Int = 120_000,
) {
    private val targets = targetIds.mapNotNull { catalog[it] }.filter { plant ->
        plant.solvable && revenuePrice(quotes[plant.bazaarId], settings.priceMode) != null &&
            plant.requires.keys.all { costPrice(catalog[it]!!, quotes) != null }
    }.distinct()
    private val pool = (targets + targets.flatMap { it.requires.keys }.map { catalog[it]!! } +
        catalog.plants.filter { it.unique && costPrice(it, quotes) != null }).distinct()
    private val index = pool.withIndex().associate { (i, plant) -> plant.id to i }
    private val revenue = DoubleArray(pool.size) { revenuePrice(quotes[pool[it].bazaarId], settings.priceMode) ?: 0.0 }
    private val cost = DoubleArray(pool.size) { costPrice(pool[it], quotes) ?: 0.0 }
    private val needs = pool.map { plant -> if (plant in targets) plant.requires.map { (id, count) -> index.getValue(id) to count } else emptyList() }
    private val targetIndices = targets.map { index.getValue(it.id) }
    private val uniqueIndices = pool.indices.filter { pool[it].unique }
    /** Plants worth planting: anything a target needs, plus unique crops for the growth bonus. */
    private val plantable = (targetIndices.flatMap { t -> needs[t].map { it.first } } + uniqueIndices).distinct()

    private data class Item(val plant: Int, val row: Int, val col: Int, val size: Int)
    private class Evaluation(val exact: Double, val guided: Double, val unique: Int, val targets: List<Item>, val ingredients: List<Item> = emptyList())

    fun solve(seed: Long, now: Long, hash: String): Layout? {
        if (targets.isEmpty()) return null
        val random = Random(seed)
        var ingredients = prune(anneal(emptyList(), random, ::mutate, ::evaluate))
        if (targets.all { target -> target.requires.keys.all { catalog[it]!!.size == 1 } }) {
            // Rings shared between multi-tile targets need all their tiles at once; choosing footprints first finds those lattices.
            val footprints = anneal(emptyList(), random, ::mutateTargets, ::assign)
            val alternative = prune(assign(footprints).ingredients)
            if (evaluate(alternative).exact > evaluate(ingredients).exact) ingredients = alternative
        }
        val final = evaluate(ingredients)
        if (final.targets.isEmpty()) return null
        val placements = ingredients.map { Placement(pool[it.plant].id, it.row, it.col, it.size, Role.INGREDIENT) } +
            final.targets.map { Placement(pool[it.plant].id, it.row, it.col, it.size, Role.TARGET) }
        return Layout(placements, placements.associateWith { catalog[it.plantId]!!.soils.first() }, final.exact, final.unique, now, hash)
    }

    private fun anneal(start: List<Item>, random: Random, mutate: (List<Item>, Random) -> List<Item>?, evaluate: (List<Item>) -> Evaluation): List<Item> {
        var current = start
        var currentScore = evaluate(current)
        var best = current
        var bestScore = currentScore
        val scale = targetIndices.maxOf { revenue[it] / cycle(it, 12) }
        for (step in 0 until iterations) {
            val next = mutate(current, random) ?: continue
            val nextScore = evaluate(next)
            val temperature = scale * TEMPERATURE * (1.0 - step / iterations.toDouble()) + scale * 1e-4
            val delta = nextScore.guided - currentScore.guided
            if (delta >= 0 || random.nextDouble() < exp(delta / temperature)) {
                current = next; currentScore = nextScore
                if (nextScore.exact > bestScore.exact) { best = next; bestScore = nextScore }
            }
        }
        return best
    }

    private fun cycle(plant: Int, unique: Int) = cycleHours(pool[plant], settings.upgrade, unique)

    private fun fits(cells: IntArray, row: Int, col: Int, size: Int): Boolean {
        if (row < 0 || col < 0 || row + size > GREENHOUSE_SIZE || col + size > GREENHOUSE_SIZE) return false
        for (r in row until row + size) for (c in col until col + size) {
            val cell = r * GREENHOUSE_SIZE + c
            if (!unlocked[cell] || cells[cell] >= 0) return false
        }
        return true
    }

    private fun occupy(cells: IntArray, item: Item, owner: Int) {
        for (r in 0 until item.size) for (c in 0 until item.size) cells[(item.row + r) * GREENHOUSE_SIZE + item.col + c] = owner
    }

    private fun evaluate(ingredients: List<Item>): Evaluation {
        val cells = IntArray(GREENHOUSE_CELLS) { -1 }
        ingredients.forEachIndexed { i, item -> occupy(cells, item, i) }
        val unique = maxOf(settings.uniqueCrops, uniqueIndices.count { u -> ingredients.any { it.plant == u } }).coerceAtMost(12)
        val counts = IntArray(pool.size)
        val valid = ArrayList<Pair<Item, Double>>()
        var partial = 0.0
        for (target in targetIndices) {
            val size = pool[target].size
            val value = revenue[target] / cycle(target, unique)
            for (row in 0..GREENHOUSE_SIZE - size) for (col in 0..GREENHOUSE_SIZE - size) {
                if (!fits(cells, row, col, size)) continue
                counts.fill(0)
                for (r in row - 1..row + size) for (c in col - 1..col + size) {
                    if (r !in 0 until GREENHOUSE_SIZE || c !in 0 until GREENHOUSE_SIZE) continue
                    if (r in row until row + size && c in col until col + size) continue
                    val owner = cells[r * GREENHOUSE_SIZE + c]
                    if (owner >= 0) counts[ingredients[owner].plant]++
                }
                var have = 0; var need = 0
                for ((ingredient, count) in needs[target]) { have += minOf(counts[ingredient], count); need += count }
                if (have == need) valid += Item(target, row, col, size) to value
                else if (have > 0) { val fraction = have / need.toDouble(); partial += value * fraction * fraction * 0.3 / (size * size) }
            }
        }
        // Most valuable first; ties keep scan order so results are deterministic.
        valid.sortByDescending { it.second }
        val placed = ArrayList<Item>()
        var income = 0.0
        var fastest = Double.MAX_VALUE
        for ((item, value) in valid) {
            if (!fits(cells, item.row, item.col, item.size)) continue
            occupy(cells, item, GREENHOUSE_CELLS)
            placed += item; income += value
            fastest = minOf(fastest, cycle(item.plant, unique))
        }
        if (fastest == Double.MAX_VALUE) fastest = hoursPerStage(settings.upgrade, unique)
        val exact = income - ingredients.sumOf { cost[it.plant] } / fastest
        return Evaluation(exact, exact + partial, unique, placed)
    }

    private fun mutate(items: List<Item>, random: Random): List<Item>? {
        val roll = random.nextInt(100)
        if (roll < 30) return repair(items, random)
        if (items.isEmpty() || roll < 45) return add(items, items, random.nextInt(plantable.size).let { plantable[it] }, random)
        val i = random.nextInt(items.size)
        val item = items[i]
        val rest = items.toMutableList().apply { removeAt(i) }
        return when {
            roll < 70 -> rest
            roll < 88 -> {
                val options = plantable.filter { pool[it].size == item.size && it != item.plant }
                if (options.isEmpty()) null else items.toMutableList().apply { set(i, item.copy(plant = options[random.nextInt(options.size)])) }
            }
            else -> add(rest, rest, item.plant, random)
        }
    }

    private fun add(base: List<Item>, occupied: List<Item>, plant: Int, random: Random): List<Item>? {
        val cells = IntArray(GREENHOUSE_CELLS) { -1 }
        occupied.forEachIndexed { i, item -> occupy(cells, item, i) }
        val size = pool[plant].size
        val row = random.nextInt(GREENHOUSE_SIZE - size + 1); val col = random.nextInt(GREENHOUSE_SIZE - size + 1)
        return if (fits(cells, row, col, size)) base + Item(plant, row, col, size) else null
    }

    /**
     * Adds one missing ingredient to the ring of the most complete of a few sampled free target footprints, so rings that
     * need many tiles (a 2x2 needs all 12) get finished instead of spreading ingredients evenly.
     */
    private fun repair(items: List<Item>, random: Random): List<Item>? {
        val cells = IntArray(GREENHOUSE_CELLS) { -1 }
        items.forEachIndexed { i, item -> occupy(cells, item, i) }
        val target = targetIndices[random.nextInt(targetIndices.size)]
        val size = pool[target].size
        val counts = IntArray(pool.size)
        var row = -1; var col = -1; var bestFraction = -1.0
        var missing = emptyList<Pair<Int, Int>>()
        repeat(6) {
            val r0 = random.nextInt(GREENHOUSE_SIZE - size + 1); val c0 = random.nextInt(GREENHOUSE_SIZE - size + 1)
            if (!fits(cells, r0, c0, size)) return@repeat
            counts.fill(0)
            for (r in r0 - 1..r0 + size) for (c in c0 - 1..c0 + size) {
                if (r !in 0 until GREENHOUSE_SIZE || c !in 0 until GREENHOUSE_SIZE) continue
                val owner = cells[r * GREENHOUSE_SIZE + c]
                if (owner >= 0) counts[items[owner].plant]++
            }
            var have = 0; var need = 0
            for ((ingredient, count) in needs[target]) { have += minOf(counts[ingredient], count); need += count }
            if (have < need && have / need.toDouble() > bestFraction) {
                bestFraction = have / need.toDouble(); row = r0; col = c0
                missing = needs[target].filter { (ingredient, count) -> counts[ingredient] < count }
            }
        }
        if (missing.isEmpty()) return null
        val ingredient = missing[random.nextInt(missing.size)].first
        val tile = pool[ingredient].size
        // Reserve the target footprint so the ingredient lands in the ring, not on it.
        occupy(cells, Item(target, row, col, size), GREENHOUSE_CELLS)
        val options = ArrayList<Int>()
        for (r in row - tile..row + size) for (c in col - tile..col + size) if (fits(cells, r, c, tile)) options += r * GREENHOUSE_SIZE + c
        if (options.isEmpty()) return null
        val cell = options[random.nextInt(options.size)]
        return items + Item(ingredient, cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE, tile)
    }

    private fun mutateTargets(items: List<Item>, random: Random): List<Item>? {
        val roll = random.nextInt(100)
        if (items.isEmpty() || roll < 40) {
            val plant = targetIndices[random.nextInt(targetIndices.size)]
            return add(items, items, plant, random)
        }
        val i = random.nextInt(items.size)
        val item = items[i]
        val rest = items.toMutableList().apply { removeAt(i) }
        return when {
            roll < 62 -> rest
            roll < 80 -> {
                val cells = IntArray(GREENHOUSE_CELLS) { -1 }
                rest.forEachIndexed { j, other -> occupy(cells, other, j) }
                val moved = item.copy(row = item.row + random.nextInt(3) - 1, col = item.col + random.nextInt(3) - 1)
                if (moved != item && fits(cells, moved.row, moved.col, moved.size)) rest + moved else null
            }
            roll < 85 -> {
                // Slide the whole layout one cell so a lattice that is nearly aligned can gain its missing row or column.
                val dr = random.nextInt(3) - 1; val dc = random.nextInt(3) - 1
                val cells = IntArray(GREENHOUSE_CELLS) { -1 }
                val shifted = items.map { it.copy(row = it.row + dr, col = it.col + dc) }
                if (shifted.all { fits(cells, it.row, it.col, it.size) && run { occupy(cells, it, 0); true } }) shifted else null
            }
            else -> {
                val options = targetIndices.filter { pool[it].size == item.size && it != item.plant }
                if (options.isEmpty()) null else items.toMutableList().apply { set(i, item.copy(plant = options[random.nextInt(options.size)])) }
            }
        }
    }

    /**
     * Fixed target footprints, 1x1 ingredients: ring cells shared by the most targets are assigned first, each to the
     * ingredient its touching targets lack the most of in total (cheaper on ties); cells no target lacks stay empty.
     * Leftover free cells then take the cheapest missing unique crops while the faster cycle pays for them.
     */
    private fun assign(footprints: List<Item>): Evaluation {
        val cells = IntArray(GREENHOUSE_CELLS) { -1 }
        footprints.forEachIndexed { i, item -> occupy(cells, item, i) }
        val touching = Array(GREENHOUSE_CELLS) { ArrayList<Int>(4) }
        footprints.forEachIndexed { i, item ->
            for (r in item.row - 1..item.row + item.size) for (c in item.col - 1..item.col + item.size) {
                if (r !in 0 until GREENHOUSE_SIZE || c !in 0 until GREENHOUSE_SIZE) continue
                val cell = r * GREENHOUSE_SIZE + c
                if (unlocked[cell] && cells[cell] < 0) touching[cell] += i
            }
        }
        val required = footprints.map { item -> IntArray(pool.size).also { d -> needs[item.plant].forEach { (ingredient, count) -> d[ingredient] = count } } }
        val have = footprints.map { IntArray(pool.size) }
        val kind = IntArray(GREENHOUSE_CELLS) { -1 }
        val ring = (0 until GREENHOUSE_CELLS).filter { touching[it].isNotEmpty() }.sortedByDescending { touching[it].size }
        fun plant(cell: Int, plant: Int) {
            if (kind[cell] >= 0) touching[cell].forEach { have[it][kind[cell]]-- }
            kind[cell] = plant
            if (plant >= 0) touching[cell].forEach { have[it][plant]++ }
        }
        for (cell in ring) {
            var bestPlant = -1; var bestGain = 0
            for (plant in plantable) {
                val gain = touching[cell].sumOf { maxOf(0, required[it][plant] - have[it][plant]) }
                if (gain > bestGain || gain == bestGain && gain > 0 && cost[plant] < cost[bestPlant]) { bestGain = gain; bestPlant = plant }
            }
            if (bestPlant >= 0) plant(cell, bestPlant)
        }
        val ingredients = ArrayList<Item>()
        for (cell in ring) if (kind[cell] >= 0) {
            ingredients += Item(kind[cell], cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE, 1)
            cells[cell] = GREENHOUSE_CELLS
        }
        val deficits = footprints.indices.map { t -> IntArray(pool.size) { maxOf(0, required[t][it] - have[t][it]) } }
        val validTargets = footprints.filterIndexed { i, _ -> deficits[i].all { it == 0 } }
        val present = uniqueIndices.filter { u -> ingredients.any { it.plant == u } }
        val missing = (uniqueIndices - present.toSet()).sortedBy { cost[it] }
        val free = (0 until GREENHOUSE_CELLS).filter { unlocked[it] && cells[it] < 0 }
        var bestExtra = 0; var bestExact = Double.NEGATIVE_INFINITY
        for (extra in 0..minOf(missing.size, free.size)) {
            val unique = maxOf(settings.uniqueCrops, present.size + extra).coerceAtMost(12)
            val fastest = validTargets.minOfOrNull { cycle(it.plant, unique) } ?: hoursPerStage(settings.upgrade, unique)
            val exact = validTargets.sumOf { revenue[it.plant] / cycle(it.plant, unique) } -
                (ingredients.sumOf { cost[it.plant] } + missing.take(extra).sumOf { cost[it] }) / fastest
            if (exact > bestExact) { bestExact = exact; bestExtra = extra }
        }
        missing.take(bestExtra).forEachIndexed { i, plant -> ingredients += Item(plant, free[i] / GREENHOUSE_SIZE, free[i] % GREENHOUSE_SIZE, 1) }
        val unique = maxOf(settings.uniqueCrops, present.size + bestExtra).coerceAtMost(12)
        var partial = 0.0
        footprints.forEachIndexed { i, item ->
            val need = needs[item.plant].sumOf { it.second }
            val fraction = (need - deficits[i].sum()) / need.toDouble()
            if (fraction < 1) partial += revenue[item.plant] / cycle(item.plant, unique) * fraction * fraction * 0.3
        }
        return Evaluation(bestExact, bestExact + partial, unique, validTargets, ingredients)
    }

    private fun prune(items: List<Item>): List<Item> {
        var current = items
        var changed = true
        while (changed) {
            changed = false
            val base = evaluate(current).exact
            for (i in current.indices.reversed()) {
                val without = current.toMutableList().apply { removeAt(i) }
                if (evaluate(without).exact >= base - 1e-9) { current = without; changed = true; break }
            }
        }
        return current
    }
}

private const val TEMPERATURE = 0.3
