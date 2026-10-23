package me.mycellium.skymyce.features.garden.greenhouse

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

const val GREENHOUSE_SIZE = 10
const val GREENHOUSE_CELLS = GREENHOUSE_SIZE * GREENHOUSE_SIZE

/** Growth surfaces from the game data. Farmland is placed as dirt and tilled. */
enum class Soil { FARMLAND, DIRT, SAND, SOUL_SAND, MYCELIUM, END_STONE }

enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, RAW }

data class Plant(
    val id: String,
    val name: String,
    val rarity: Rarity,
    val size: Int,
    val soils: List<Soil>,
    val stages: Int,
    /** Bazaar product that prices this plant; raw crops differ from their plant id. */
    val bazaarId: String,
    /** SkyBlock ids of the item the player holds to plant it. */
    val itemIds: Set<String>,
    /** Counts toward the Greenhouse unique-crop growth bonus. */
    val unique: Boolean,
    /** Game-provided (fire, dead plants): no purchase cost. */
    val free: Boolean,
    /** A spreading condition the solver cannot express as neighbour counts. */
    val special: String?,
    val requires: Map<String, Int>,
    /** Skin textures (file names) of the player heads the crop is planted as; base crops are heads in the Greenhouse too. */
    val heads: Set<String> = emptySet(),
    /** Seeds crafted per [bazaarId] item (4 Pumpkin Seeds per Pumpkin), so one plant costs a fraction of that item. */
    val perItem: Int = 1,
    /** Garden level that unlocks planting this base crop; 0 when it has none. */
    val gardenLevel: Int = 0,
    /** Stage it is ready at when that is before its last (Glasscorn can be harvested at 7), else null. */
    val readyStage: Int? = null,
    /** Stages it stops growing at until the player acts on it (Snoozling falls asleep at 5, 10 and 15 until right-clicked). */
    val pauses: List<Int> = emptyList(),
    /** Growth that halts or resets for reasons the planner cannot see (hunger, charge, random resets): not projected past a reading. */
    val unpredictable: Boolean = false,
    /** Another stage passing at its last starts it again from stage 1 (Glasscorn). */
    val restarts: Boolean = false,
) {
    val solvable get() = special == null && requires.isNotEmpty()
}

class PlantCatalog(val plants: List<Plant>) {
    val byId = plants.associateBy { it.id }
    val byHead = plants.flatMap { plant -> plant.heads.map { it to plant } }.toMap()
    operator fun get(id: String) = byId[id]
}

/** Strict parse: any malformed entry rejects the file so callers fall back to the bundled snapshot. */
fun parsePlantCatalog(text: String): PlantCatalog {
    val root = JsonParser.parseString(text).asJsonObject
    require(root["version"].asInt == 1)
    val plants = root.getAsJsonArray("plants").map { element ->
        val json = element.asJsonObject
        val id = json["id"].asString
        require(id.matches(Regex("[A-Z0-9_:]{1,64}")))
        Plant(id, json["name"].asString, Rarity.valueOf(json["rarity"].asString), json["size"].asInt.also { require(it in 1..3) },
            json.getAsJsonArray("soils").map { Soil.valueOf(it.asString) }, json["stages"].asInt.also { require(it in 0..1000) },
            json["bazaar"]?.asString ?: id, (json.getAsJsonArray("items")?.map { it.asString } ?: listOf(id)).toSet(),
            json["unique"]?.asBoolean == true, json["free"]?.asBoolean == true, json["special"]?.asString,
            json.getAsJsonObject("requires")?.entrySet()?.associate { (key, value) -> key to value.asInt.also { require(it in 1..16) } }.orEmpty(),
            (listOfNotNull(json["head"]?.asString) + json.getAsJsonArray("heads")?.map { it.asString }.orEmpty())
                .onEach { require(it.matches(Regex("[0-9a-f]{32,64}"))) }.toSet(),
            json["perItem"]?.asInt?.also { require(it in 1..64) } ?: 1, json["gardenLevel"]?.asInt?.also { require(it in 0..15) } ?: 0,
            json["readyStage"]?.asInt?.also { require(it in 1..1000) }, json.getAsJsonArray("pauses")?.map { it.asInt.also { s -> require(s in 1..1000) } }.orEmpty(),
            json["unpredictable"]?.asBoolean == true, json["restarts"]?.asBoolean == true)
    }
    val catalog = PlantCatalog(plants)
    require(catalog.byId.size == plants.size && plants.all { plant -> plant.requires.keys.all { it in catalog.byId } })
    return catalog
}

enum class PriceMode(val label: String) { SELL_OFFER("Sell Offer"), INSTANT_SELL("Instant Sell") }
enum class ProfitMetric(val label: String) { PER_HOUR("Per hour"), PER_CYCLE("Per cycle"), PER_DAY("Per day") }

/** Bazaar quick-status pair: the highest buy order and lowest sell offer. */
data class Quote(val buyOrder: Double, val sellOffer: Double)

/** [gardenLevel] is 15 (everything unlocked) while the player's level is unknown. */
data class GreenhouseSettings(val upgrade: Int, val uniqueCrops: Int, val priceMode: PriceMode, val gardenLevel: Int = 15)

/** The Garden level a mutation needs: the highest unlock level among the base crops it spreads from. */
fun gardenLevelFor(catalog: PlantCatalog, plant: Plant) = plant.requires.keys.maxOfOrNull { catalog[it]?.gardenLevel ?: 0 } ?: 0

private val GARDEN_LEVEL = Regex("""\bGarden Level:?\s+([IVX]{1,5}|\d{1,2})\b""")

/** The Garden level from a tab list line such as "Garden Level: XII" or "Garden Level 12"; null for anything else. */
fun parseGardenLevel(line: String): Int? {
    val value = GARDEN_LEVEL.find(line)?.groupValues?.get(1) ?: return null
    val level = value.toIntOrNull() ?: value.map { mapOf('I' to 1, 'V' to 5, 'X' to 10).getValue(it) }
        .let { digits -> digits.indices.sumOf { i -> if (i + 1 < digits.size && digits[i] < digits[i + 1]) -digits[i] else digits[i] } }
    return level.takeIf { it in 1..15 }
}

/**
 * skymutations.eu prices with `instantBuy = max(sellPrice, buyPrice)` for both the target ("Sell Offer") and the ingredients
 * ("Instant Buy"). Instant Sell is this mod's lower alternative for the target only.
 */
fun revenuePrice(quote: Quote?, mode: PriceMode): Double? = quote?.let {
    if (mode == PriceMode.SELL_OFFER) max(it.buyOrder, it.sellOffer) else minOf(it.buyOrder, it.sellOffer)
}?.takeIf { it.isFinite() && it > 0 }

fun costPrice(plant: Plant, quotes: Map<String, Quote>): Double? =
    if (plant.free) 0.0 else quotes[plant.bazaarId]?.let { max(it.buyOrder, it.sellOffer) / plant.perItem }?.takeIf { it.isFinite() && it > 0 }

/**
 * skymutations.eu's growth timer: 4 hours per stage, reduced by percentage points from the Greenhouse upgrade
 * (2.5 per level up to 8, plus 5 at level 9) and 2.5 per unique crop type (site allows 12; the wiki caps the bonus at 10).
 */
fun hoursPerStage(upgrade: Int, uniqueCrops: Int): Double {
    val upgradeBonus = if (upgrade <= 0) 0.0 else minOf(upgrade, 8) * 2.5 + if (upgrade >= 9) 5.0 else 0.0
    return 4.0 * (1.0 - (upgradeBonus + uniqueCrops.coerceIn(0, 12) * 2.5) / 100.0)
}

fun cycleHours(plant: Plant, upgrade: Int, uniqueCrops: Int) = max(1, plant.stages) * hoursPerStage(upgrade, uniqueCrops)

data class RankedMutation(val plant: Plant, val revenue: Double, val inputCost: Double, val cycleHours: Double) {
    val profitPerCycle get() = revenue - inputCost
    val profitPerHour get() = profitPerCycle / cycleHours
    val profitPerDay get() = profitPerHour * 24.0
}

/**
 * skymutations.eu's profit leaderboard: one mutation item per spawn priced at [revenuePrice], minus every spreading
 * ingredient bought at its instant-buy price. Multi-tile ingredients count one item per footprint (`ceil(qty / size)`),
 * except PlantBoy Advance, which the site always charges per tile. Crop drops, copper, sowdust, Ethereal Vine drops and
 * ingredient survival are not modelled, matching the site. A missing ingredient price counts as 0, as on the site (often a
 * crop with no sell offers); unlike the site, a missing target price excludes the mutation, and raw crops use their real
 * Bazaar ids.
 */
fun rankMutations(catalog: PlantCatalog, quotes: Map<String, Quote>, settings: GreenhouseSettings,
                  allowed: (Plant) -> Boolean = { true }): List<RankedMutation> = catalog.plants.mapNotNull { plant ->
    if (!plant.solvable || !allowed(plant) || gardenLevelFor(catalog, plant) > settings.gardenLevel) return@mapNotNull null
    val revenue = revenuePrice(quotes[plant.bazaarId], settings.priceMode) ?: return@mapNotNull null
    var cost = 0.0
    for ((id, quantity) in plant.requires) {
        val ingredient = catalog[id]!!
        val count = if (ingredient.size > 1 && id != "PLANTBOY_ADVANCE") ceil(quantity / ingredient.size.toDouble()) else quantity.toDouble()
        cost += (costPrice(ingredient, quotes) ?: 0.0) * count
    }
    RankedMutation(plant, revenue, cost, cycleHours(plant, settings.upgrade, settings.uniqueCrops))
}.sortedByDescending { it.profitPerHour }

enum class Role { TARGET, INGREDIENT }

/** [row]/[col] are the footprint's top-left cell. Targets stay empty soil until the mutation spawns. */
data class Placement(val plantId: String, val row: Int, val col: Int, val size: Int, val role: Role)

data class CellPlan(val plantId: String, val soil: Soil, val role: Role)

data class Layout(
    val placements: List<Placement>,
    val soils: Map<Placement, Soil>,
    val totalProfitPerHour: Double,
    val uniqueCrops: Int,
    val generatedAt: Long,
    val inputsHash: String,
) {
    /** Cell index `row * 10 + col` → plan. */
    val cells: Map<Int, CellPlan> by lazy {
        buildMap { for (placement in placements) for (r in 0 until placement.size) for (c in 0 until placement.size)
            put((placement.row + r) * GREENHOUSE_SIZE + placement.col + c, CellPlan(placement.plantId, soils.getValue(placement), placement.role)) }
    }
    val targets get() = placements.filter { it.role == Role.TARGET }
}

/**
 * The solver's objective re-evaluated for a pinned layout at today's prices: target income per hour minus the ingredient
 * cost spread over the fastest target cycle. Null when a target has no price; unpriced ingredients cost nothing.
 */
fun Layout.profitPerHour(catalog: PlantCatalog, quotes: Map<String, Quote>, settings: GreenhouseSettings): Double? {
    val ingredients = placements.filter { it.role == Role.INGREDIENT }.map { catalog[it.plantId] ?: return null }
    val unique = maxOf(settings.uniqueCrops, ingredients.filter { it.unique }.distinct().size).coerceAtMost(12)
    var income = 0.0
    var fastest = Double.MAX_VALUE
    for (target in targets) {
        val plant = catalog[target.plantId] ?: return null
        val hours = cycleHours(plant, settings.upgrade, unique)
        income += (revenuePrice(quotes[plant.bazaarId], settings.priceMode) ?: return null) / hours
        fastest = minOf(fastest, hours)
    }
    if (fastest == Double.MAX_VALUE) return null
    // An ingredient without a Bazaar price (none listed, or not sold there) is left out of the cost rather than voiding the estimate.
    return income - ingredients.sumOf { costPrice(it, quotes) ?: 0.0 } / fastest
}

/** A Greenhouse plot as saved: [facing] is a horizontal direction name. Each plot keeps its own pinned layout. */
data class PlotAnchor(val x: Int, val y: Int, val z: Int, val facing: String)
data class PlotState(
    val name: String, val anchor: PlotAnchor?, val locked: Set<Int>, val target: String?, val layout: Layout?,
    /** When every ingredient was last seen becoming planted, or 0 when not observed. */
    val plantedAt: Long = 0,
    /** Target footprint (top-left cell) -> when its mutation was seen appearing. */
    val spawned: Map<Int, Long> = emptyMap(),
    /** The last world scan: one [CellStatus] code per cell, empty before the first scan. */
    val status: String = "",
    val scannedAt: Long = 0,
    val missingPlants: Map<String, Int> = emptyMap(),
    val missingSoil: Map<Soil, Int> = emptyMap(),
    /** Block looks named on this plot; a vanilla stem or crop block can be a young mutation on one plot and itself on another. */
    val learned: Map<String, String> = emptyMap(),
    /** Placement (top-left cell) → its last Plant Diagnostics Tool reading. */
    val growth: Map<Int, GrowthReading> = emptyMap(),
)

/** A growth stage read from the Plant Diagnostics Tool: [stage] of [stages] at [at]; [awake] when the player woke it at that stage since. */
data class GrowthReading(val stage: Int, val stages: Int, val at: Long, val awake: Boolean = false)

/** A mutation's growth now. [paused] waits on the player (an asleep Snoozling); [readyInMs] is null when it cannot be told. */
data class GrowthEstimate(val stage: Double, val stages: Int, val ready: Boolean, val paused: Boolean, val readyInMs: Long?)

/**
 * Projects a reading with the growth timer, following [plant]'s rules: it stops at a pause until woken, a restarting crop
 * wraps back to stage 1, and unpredictable growth stays at what was read. Estimated: water and day/night are not modelled.
 */
fun estimateGrowth(plant: Plant?, reading: GrowthReading, hoursPerStage: Double, now: Long): GrowthEstimate {
    val stages = reading.stages
    val readyAt = plant?.readyStage?.coerceAtMost(stages) ?: stages
    val stageMs = hoursPerStage * 3_600_000
    val unpredictable = plant?.unpredictable == true
    val raw = reading.stage + if (unpredictable) 0.0 else (now - reading.at).coerceAtLeast(0) / stageMs
    val pause = plant?.pauses?.firstOrNull { it in reading.stage until stages && (it > reading.stage || !reading.awake) }
    val paused = pause != null && raw >= pause
    val stage = when {
        paused -> pause.toDouble()
        plant?.restarts == true && raw >= stages + 1 -> 1 + (raw - stages - 1) % stages
        else -> raw.coerceAtMost(stages.toDouble())
    }
    val ready = stage >= readyAt
    val readyInMs = when {
        ready -> 0L
        paused || unpredictable -> null
        else -> ((readyAt - stage) * stageMs).toLong()
    }
    return GrowthEstimate(stage, stages, ready, paused, readyInMs)
}

private val GROWTH_STAGE = Regex("(?i)(?:growth\\s+)?stage\\D{0,12}?(\\d{1,3})\\s*(?:/|of)\\s*(\\d{1,3})")
private val FORMATTING = Regex("§.")

/** The growth stage in the analyzer's output (chat or menu text, formatting codes allowed), or null. */
fun parseGrowth(lines: List<String>): Pair<Int, Int>? {
    val text = lines.map { it.replace(FORMATTING, "") }
    text.firstNotNullOfOrNull { line ->
        GROWTH_STAGE.find(line)?.let { match ->
            val stage = match.groupValues[1].toInt(); val stages = match.groupValues[2].toInt()
            (stage to stages).takeIf { stages in 1..200 && stage in 0..stages }
        }
    }?.let { return it }
    // A grown crop shows "Stage: FULLY GROWN" instead of x/y; Scourroot (0 stages) always does.
    if (text.none { FULLY_GROWN.containsMatchIn(it) }) return null
    val stages = text.firstNotNullOfOrNull { GROWTH_STAGES.find(it)?.groupValues?.get(1)?.toInt() }?.coerceIn(1, 200) ?: 1
    return stages to stages
}

private val FULLY_GROWN = Regex("(?i)\\bstage:\\s*fully grown")
private val GROWTH_STAGES = Regex("(?i)growth stages:\\s*(\\d{1,3})")

/**
 * Why holding [held] (a crop when [crop], else a soil placing [soils]) is wrong for a slot planned as [plan], or null
 * when it fits. Targets and unplanned slots take no crop; a slot without a plan takes any soil.
 */
fun wrongSlot(plan: CellPlan?, plant: Plant?, held: String, crop: Boolean, soils: Set<Soil>, soilName: (Soil) -> String): String? = when {
    crop && (plan == null || plan.role != Role.INGREDIENT) -> "nothing is planted there"
    crop && held !in plant?.itemIds.orEmpty() -> "that slot is for ${plant?.name}"
    !crop && plan != null && plan.soil !in soils -> "that slot needs ${soilName(plan.soil)}"
    else -> null
}

/** One character per cell in [PlotState.status]. */
enum class CellStatus(val code: Char) {
    UNUSED('.'), DONE('o'), MISSING_CROP('m'), WRONG_CROP('w'), WRONG_SOIL('s'), TARGET_WAITING('t'), TARGET_GROWING('T');
    companion object { fun of(code: Char) = entries.firstOrNull { it.code == code } }
}

private val HORIZONTAL = setOf("NORTH", "EAST", "SOUTH", "WEST")

fun PlotState.toJson(): JsonObject = JsonObject().apply {
    addProperty("name", name)
    anchor?.let { a -> add("anchor", JsonObject().apply { addProperty("x", a.x); addProperty("y", a.y); addProperty("z", a.z); addProperty("facing", a.facing) }) }
    add("locked", com.google.gson.JsonArray().apply { locked.sorted().forEach { add(it) } })
    target?.let { addProperty("target", it) }
    layout?.let { add("layout", it.toJson()) }
    if (plantedAt > 0) addProperty("plantedAt", plantedAt)
    if (spawned.isNotEmpty()) add("spawned", JsonObject().apply { spawned.forEach { (cell, at) -> addProperty(cell.toString(), at) } })
    if (status.isNotEmpty()) { addProperty("status", status); addProperty("scannedAt", scannedAt) }
    if (missingPlants.isNotEmpty()) add("missingPlants", JsonObject().apply { missingPlants.forEach { (id, n) -> addProperty(id, n) } })
    if (missingSoil.isNotEmpty()) add("missingSoil", JsonObject().apply { missingSoil.forEach { (soil, n) -> addProperty(soil.name, n) } })
    if (learned.isNotEmpty()) add("learned", JsonObject().apply { learned.forEach { (look, id) -> addProperty(look, id) } })
    if (growth.isNotEmpty()) add("growth", JsonObject().apply {
        growth.forEach { (cell, reading) -> add(cell.toString(), com.google.gson.JsonArray().apply { add(reading.stage); add(reading.stages); add(reading.at); if (reading.awake) add(true) }) }
    })
}

/**
 * Reads version 2 `plots`, or turns a version 1 file's single anchor, locks, target and active cached layout into plot "1".
 * Each section is read independently, so one damaged section only loses itself.
 */
fun plotsFromJson(json: JsonObject, catalog: PlantCatalog): List<PlotState> {
    fun plot(section: JsonObject, name: String, layout: Layout?): PlotState {
        val anchor = runCatching {
            val a = section.getAsJsonObject("anchor")
            PlotAnchor(a["x"].asInt, a["y"].asInt, a["z"].asInt, a["facing"].asString.takeIf { it in HORIZONTAL }!!)
        }.getOrNull()
        val locked = runCatching { section.getAsJsonArray("locked").map { it.asInt }.filter { it in 0 until GREENHOUSE_CELLS }.toSet() }.getOrDefault(emptySet())
        val target = runCatching { section["target"].asString }.getOrNull()?.takeIf { catalog[it]?.solvable == true }
        fun counts(key: String) = runCatching {
            section.getAsJsonObject(key).entrySet().associate { it.key to it.value.asInt.also { n -> require(n in 1..GREENHOUSE_CELLS) } }
        }.getOrDefault(emptyMap())
        val status = runCatching { section["status"].asString }.getOrNull()?.takeIf { text -> text.length == GREENHOUSE_CELLS && text.all { CellStatus.of(it) != null } }
        return PlotState(name, anchor, locked, target, layout,
            plantedAt = runCatching { section["plantedAt"].asLong }.getOrDefault(0L),
            spawned = runCatching {
                section.getAsJsonObject("spawned").entrySet().associate { it.key.toInt().also { cell -> require(cell in 0 until GREENHOUSE_CELLS) } to it.value.asLong }
            }.getOrDefault(emptyMap()),
            status = status.orEmpty(),
            scannedAt = if (status == null) 0L else runCatching { section["scannedAt"].asLong }.getOrDefault(0L),
            missingPlants = counts("missingPlants").filterKeys { catalog[it] != null },
            missingSoil = counts("missingSoil").mapNotNull { (key, n) -> runCatching { Soil.valueOf(key) }.getOrNull()?.let { it to n } }.toMap(),
            learned = runCatching {
                section.getAsJsonObject("learned").entrySet().mapNotNull { (look, id) -> runCatching { id.asString }.getOrNull()?.takeIf { catalog[it] != null && look.length <= 256 }?.let { look to it } }.toMap()
            }.getOrDefault(emptyMap()),
            growth = runCatching {
                section.getAsJsonObject("growth").entrySet().mapNotNull { (cell, value) -> runCatching {
                    val row = value.asJsonArray
                    cell.toInt().also { require(it in 0 until GREENHOUSE_CELLS) } to
                        GrowthReading(row[0].asInt, row[1].asInt, row[2].asLong, row.size() > 3 && row[3].asBoolean).also { require(it.stages in 1..200 && it.stage in 0..it.stages) }
                }.getOrNull() }.toMap()
            }.getOrDefault(emptyMap()))
    }
    val plots = json["plots"]?.takeIf { it.isJsonArray } ?: return listOf(plot(json, "1", runCatching {
        val active = json["active"].asString
        layoutFromJson(json.getAsJsonArray("layouts").map { it.asJsonObject }.first { it["hash"].asString == active }, catalog)
    }.getOrNull()))
    return plots.asJsonArray.mapNotNull { element -> runCatching {
        val section = element.asJsonObject
        plot(section, section["name"].asString.takeIf { it.matches(PLOT_NAME) }!!,
            runCatching { layoutFromJson(section.getAsJsonObject("layout"), catalog) }.getOrNull())
    }.getOrNull() }.distinctBy { it.name.lowercase() }
}

val PLOT_NAME = Regex("[A-Za-z0-9_-]{1,16}")

/** The Garden's plot numbers as laid out around the barn (0), north row first; each plot is 96 blocks square. */
private val GARDEN_PLOTS = arrayOf(
    intArrayOf(21, 13, 9, 14, 22),
    intArrayOf(15, 5, 1, 6, 16),
    intArrayOf(10, 2, 0, 3, 11),
    intArrayOf(17, 7, 4, 8, 18),
    intArrayOf(23, 19, 12, 20, 24),
)

/** The Garden plot number (as used by /tptoplot) containing a block, or null on the barn or outside the Garden grid. */
fun gardenPlotAt(x: Int, z: Int): Int? =
    GARDEN_PLOTS.getOrNull(Math.floorDiv(z + 240, 96))?.getOrNull(Math.floorDiv(x + 240, 96))?.takeIf { it > 0 }

/** Price buckets are 25% wide, so ordinary Bazaar noise does not invalidate a cached layout. */
fun priceBucket(price: Double?) = if (price == null || price <= 0) "-" else (ln(price) / ln(1.25)).roundToInt().toString()

fun inputsHash(unlocked: BooleanArray, targets: List<String>, settings: GreenhouseSettings, catalog: PlantCatalog, quotes: Map<String, Quote>): String {
    val relevant = targets.flatMap { id -> listOf(id) + catalog[id]?.requires?.keys.orEmpty() }.distinct().sorted() +
        catalog.plants.filter { it.unique }.map { it.id }
    val text = buildString {
        append(SOLVER_VERSION).append('|').append(unlocked.joinToString("") { if (it) "1" else "0" }).append('|')
        append(targets.joinToString(",")).append('|').append(settings).append('|')
        relevant.forEach { id -> append(id).append('=').append(priceBucket(catalog[id]?.let { costPrice(it, quotes) })).append(',') }
        targets.forEach { id -> append(priceBucket(catalog[id]?.let { revenuePrice(quotes[it.bazaarId], settings.priceMode) })).append(',') }
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

const val SOLVER_VERSION = 1

fun Layout.toJson(): JsonObject = JsonObject().apply {
    addProperty("hash", inputsHash); addProperty("generatedAt", generatedAt)
    addProperty("profitPerHour", totalProfitPerHour); addProperty("uniqueCrops", uniqueCrops)
    add("placements", com.google.gson.JsonArray().apply {
        placements.forEach { placement -> add(JsonObject().apply {
            addProperty("plant", placement.plantId); addProperty("row", placement.row); addProperty("col", placement.col)
            addProperty("size", placement.size); addProperty("role", placement.role.name); addProperty("soil", soils.getValue(placement).name)
        }) }
    })
}

/** Rejects layouts that no longer fit the catalog or grid; the caller re-solves instead. */
fun layoutFromJson(json: JsonObject, catalog: PlantCatalog): Layout {
    val soils = LinkedHashMap<Placement, Soil>()
    json.getAsJsonArray("placements").forEach { element ->
        val row = element.asJsonObject
        val plant = catalog[row["plant"].asString] ?: error("Unknown plant")
        val placement = Placement(plant.id, row["row"].asInt, row["col"].asInt, row["size"].asInt, Role.valueOf(row["role"].asString))
        require(placement.size == plant.size && placement.row in 0..GREENHOUSE_SIZE - placement.size && placement.col in 0..GREENHOUSE_SIZE - placement.size)
        soils[placement] = Soil.valueOf(row["soil"].asString)
    }
    return Layout(soils.keys.toList(), soils, json["profitPerHour"].asDouble, json["uniqueCrops"].asInt, json["generatedAt"].asLong, json["hash"].asString)
}

/**
 * Turns what is already planted on a plot into a layout instead of solving a new one. [planted] holds the identified
 * plant per cell (null when empty or unidentified), [occupied] marks every cell with anything on it, [soil] the soil found.
 * Cells holding the target become targets and every other identified crop an ingredient; empty unlocked spots whose ring
 * already holds the target's ingredients become open targets. Without a [target], the mutation that yields the most
 * profit (by count when unpriced) among those planted or fully supplied is chosen.
 */
fun importLayout(catalog: PlantCatalog, planted: Array<String?>, occupied: BooleanArray, soil: Array<Soil?>, unlocked: BooleanArray, target: String?,
                 quotes: Map<String, Quote>, settings: GreenhouseSettings, now: Long, hash: String,
                 alternative: Array<String?> = arrayOfNulls(GREENHOUSE_CELLS)): Layout? {
    val present = planted.filterNotNull().toSet()
    val candidates = if (target != null) listOf(target) else catalog.plants.filter { plant ->
        plant.solvable && (plant.id in present || plant.requires.keys.all { it in present })
    }.map { it.id }
    // The mutation already growing with its ingredients around it is what the plot was built for; profit only breaks ties.
    return candidates.mapNotNull { id -> importFor(catalog, planted, occupied, soil, unlocked, id, quotes, settings, now, hash, alternative) }
        .maxWithOrNull(compareBy<Pair<Layout, Int>>({ it.second }, { it.first.profitPerHour(catalog, quotes, settings) != null },
            { it.first.profitPerHour(catalog, quotes, settings) ?: 0.0 }, { it.first.targets.size }))?.first
}

/**
 * [alternative] holds, for cells named only by a learned block look (a young mutation can look exactly like a melon stem),
 * what that block is on its own; such a cell only counts as the target when its ring supplies the target.
 */
private fun importFor(catalog: PlantCatalog, planted: Array<String?>, occupied: BooleanArray, soil: Array<Soil?>, unlocked: BooleanArray, targetId: String,
                      quotes: Map<String, Quote>, settings: GreenhouseSettings, now: Long, hash: String, alternative: Array<String?>): Pair<Layout, Int>? {
    val target = catalog[targetId]?.takeIf { it.solvable } ?: return null
    val owner = arrayOfNulls<Placement>(GREENHOUSE_CELLS)
    val placements = ArrayList<Placement>()
    fun cells(row: Int, col: Int, size: Int) = (0 until size).flatMap { r -> (0 until size).map { c -> (row + r) * GREENHOUSE_SIZE + col + c } }
    fun place(placement: Placement) { cells(placement.row, placement.col, placement.size).forEach { owner[it] = placement }; placements += placement }
    val found = footprints(catalog, planted)
    fun placeAll(entries: List<Triple<String, Int, Int>>) {
        for ((id, row, col) in entries) {
            val plant = catalog[id] ?: continue
            if (cells(row, col, plant.size).any { owner[it] != null }) continue
            place(Placement(plant.id, row, col, plant.size, if (plant.id == targetId) Role.TARGET else Role.INGREDIENT))
        }
    }
    // Multi-tile crops first, so the decorative vanilla blocks inside a Snoozling cannot claim its cells; then
    // ingredients, so a doubtful target below can be checked against its ring.
    val (large, single) = found.partition { (id) -> (catalog[id]?.size ?: 1) > 1 }
    val (singleTargets, singleIngredients) = single.partition { (id) -> id == targetId }
    placeAll(large); placeAll(singleIngredients)
    fun supplied(row: Int, col: Int): Boolean {
        val counts = HashMap<String, Int>()
        for (r in row - 1..row + target.size) for (c in col - 1..col + target.size) {
            if (r !in 0 until GREENHOUSE_SIZE || c !in 0 until GREENHOUSE_SIZE || (r in row until row + target.size && c in col until col + target.size)) continue
            owner[r * GREENHOUSE_SIZE + c]?.takeIf { it.role == Role.INGREDIENT }?.let { counts.merge(it.plantId, 1, Int::plus) }
        }
        return target.requires.all { (id, count) -> (counts[id] ?: 0) >= count }
    }
    for (entry in singleTargets) {
        val (_, row, col) = entry
        val cell = row * GREENHOUSE_SIZE + col
        val stand = alternative[cell]
        if (stand == null || supplied(row, col)) placeAll(listOf(entry)) else placeAll(listOf(Triple(stand, row, col)))
    }
    val supported = placements.count { it.role == Role.TARGET && supplied(it.row, it.col) }
    for (row in 0..GREENHOUSE_SIZE - target.size) for (col in 0..GREENHOUSE_SIZE - target.size) {
        if (cells(row, col, target.size).any { !unlocked[it] || occupied[it] || owner[it] != null }) continue
        if (supplied(row, col)) place(Placement(targetId, row, col, target.size, Role.TARGET))
    }
    if (placements.none { it.role == Role.TARGET }) return null
    val soils = placements.associateWith { placement ->
        val plant = catalog[placement.plantId]!!
        soil[placement.row * GREENHOUSE_SIZE + placement.col]?.takeIf { it in plant.soils } ?: plant.soils.first()
    }
    val unique = maxOf(settings.uniqueCrops, placements.filter { it.role == Role.INGREDIENT && catalog[it.plantId]!!.unique }.map { it.plantId }.distinct().size).coerceAtMost(12)
    val draft = Layout(placements, soils, 0.0, unique, now, hash)
    return draft.copy(totalProfitPerHour = draft.profitPerHour(catalog, quotes, settings) ?: 0.0) to supported
}

/**
 * Where each recognised crop sits, as (plant, top-left row, top-left column). A multi-tile crop is often seen on only
 * some of its cells (a 3×3 Snoozling shows one head on its middle cell), so each connected group of its cells gets a
 * footprint centred on the group; a group too large for one footprint is tiled in reading order.
 */
internal fun footprints(catalog: PlantCatalog, planted: Array<String?>): List<Triple<String, Int, Int>> {
    val result = ArrayList<Triple<String, Int, Int>>()
    val seen = BooleanArray(GREENHOUSE_CELLS)
    for (start in 0 until GREENHOUSE_CELLS) {
        val plant = planted[start]?.let { catalog[it] } ?: continue
        if (seen[start]) continue
        if (plant.size == 1) { seen[start] = true; result += Triple(plant.id, start / GREENHOUSE_SIZE, start % GREENHOUSE_SIZE); continue }
        val group = ArrayList<Int>()
        val queue = ArrayDeque(listOf(start)); seen[start] = true
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst(); group += cell
            val r = cell / GREENHOUSE_SIZE; val c = cell % GREENHOUSE_SIZE
            for ((nr, nc) in listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1)) {
                val next = nr * GREENHOUSE_SIZE + nc
                if (nr in 0 until GREENHOUSE_SIZE && nc in 0 until GREENHOUSE_SIZE && !seen[next] && planted[next] == plant.id) { seen[next] = true; queue += next }
            }
        }
        val rows = group.map { it / GREENHOUSE_SIZE }; val cols = group.map { it % GREENHOUSE_SIZE }
        val limit = GREENHOUSE_SIZE - plant.size
        if (rows.max() - rows.min() < plant.size && cols.max() - cols.min() < plant.size) {
            // Integer halves round towards the top-left, which suits a 2×2 seen on one corner cell no worse than any other choice.
            val row = ((rows.min() + rows.max() + 1 - plant.size) / 2).coerceIn(0, limit)
            val col = ((cols.min() + cols.max() + 1 - plant.size) / 2).coerceIn(0, limit)
            result += Triple(plant.id, row, col)
        } else {
            val taken = HashSet<Int>()
            for (cell in group.sorted()) {
                val r = cell / GREENHOUSE_SIZE; val c = cell % GREENHOUSE_SIZE
                if (cell in taken || r > limit || c > limit) continue
                val cover = (0 until plant.size).flatMap { dr -> (0 until plant.size).map { dc -> (r + dr) * GREENHOUSE_SIZE + c + dc } }
                if (cover.any { it in taken }) continue
                taken += cover; result += Triple(plant.id, r, c)
            }
        }
    }
    return result
}

/** One harvested mutation, valued when it was harvested; [cost] is the layout's ingredient cost per target. Estimated. */
data class Harvest(val at: Long, val plot: String, val plantId: String, val revenue: Double, val cost: Double) {
    val profit get() = revenue - cost
}

data class HarvestTotals(val day: Double, val week: Double, val month: Double, val total: Double, val count: Int)

private const val DAY_MS = 86_400_000L

fun harvestTotals(history: List<Harvest>, now: Long, plot: String? = null): HarvestTotals {
    val own = if (plot == null) history else history.filter { it.plot.equals(plot, true) }
    fun since(ms: Long) = own.filter { it.at > now - ms }.sumOf { it.profit }
    return HarvestTotals(since(DAY_MS), since(7 * DAY_MS), since(30 * DAY_MS), own.sumOf { it.profit }, own.size)
}

/** Profit per local calendar day, oldest first, ending today. */
fun dailyProfit(history: List<Harvest>, now: Long, days: Int, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): DoubleArray {
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val result = DoubleArray(days)
    for (harvest in history) {
        val day = java.time.Instant.ofEpochMilli(harvest.at).atZone(zone).toLocalDate()
        val index = days - 1 - java.time.temporal.ChronoUnit.DAYS.between(day, today).toInt()
        if (index in 0 until days) result[index] += harvest.profit
    }
    return result
}

fun Harvest.toJson() = com.google.gson.JsonArray().apply { add(at); add(plot); add(plantId); add(revenue); add(cost) }

fun harvestFromJson(json: com.google.gson.JsonElement, catalog: PlantCatalog): Harvest? = runCatching {
    val row = json.asJsonArray
    Harvest(row[0].asLong, row[1].asString.takeIf { it.matches(PLOT_NAME) }!!, row[2].asString.takeIf { catalog[it] != null }!!,
        row[3].asDouble.takeIf { it.isFinite() }!!, row[4].asDouble.takeIf { it.isFinite() }!!)
}.getOrNull()

/**
 * Names unrecognised crops from what the recognised mutations around them still lack. Every unknown look (fingerprint)
 * in the ring of a recognised mutation with at least half its ingredients in place votes for what that ring is short of,
 * weighted by the shortfall; a look
 * whose votes clearly favour one ingredient (at least two votes and half again the runner-up) is named after it.
 * [looks] holds the fingerprint of each unrecognised cell, null elsewhere.
 */
fun inferCrops(catalog: PlantCatalog, planted: Array<String?>, looks: Array<String?>): Map<String, String> {
    val votes = HashMap<String, HashMap<String, Int>>()
    for (cell in 0 until GREENHOUSE_CELLS) {
        val plant = planted[cell]?.let { catalog[it] }?.takeIf { it.solvable } ?: continue
        val row = cell / GREENHOUSE_SIZE; val col = cell % GREENHOUSE_SIZE
        // One instance per footprint: only its top-left recognised cell counts.
        if (col > 0 && planted[cell - 1] == plant.id || row > 0 && planted[cell - GREENHOUSE_SIZE] == plant.id) continue
        if (row + plant.size > GREENHOUSE_SIZE || col + plant.size > GREENHOUSE_SIZE) continue
        val counts = HashMap<String, Int>()
        val unknown = ArrayList<String>()
        for (r in row - 1..row + plant.size) for (c in col - 1..col + plant.size) {
            if (r !in 0 until GREENHOUSE_SIZE || c !in 0 until GREENHOUSE_SIZE || (r in row until row + plant.size && c in col until col + plant.size)) continue
            val ring = r * GREENHOUSE_SIZE + c
            planted[ring]?.let { counts.merge(it, 1, Int::plus) }
            looks[ring]?.let(unknown::add)
        }
        val short = plant.requires.mapValues { (id, need) -> need - (counts[id] ?: 0) }.filterValues { it > 0 }
        // Only a mutation with most of its ring in place is a target; an ingredient that is itself a mutation is not.
        if (short.values.sum() * 2 > plant.requires.values.sum()) continue
        for (look in unknown) for ((id, missing) in short) votes.getOrPut(look) { HashMap() }.merge(id, missing, Int::plus)
    }
    return votes.mapNotNull { (look, tally) ->
        val ranked = tally.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0
        if (best.value >= 2 && best.value * 2 >= runnerUp * 3 && best.value > runnerUp) look to best.key else null
    }.toMap()
}

/** Hypixel's Bazaar amount prompt (Buy Instantly or buy order "Custom Amount"): the player types on line 1, line 3 reads "Enter amount". */
fun isBazaarAmountSign(lines: List<String>) = lines.size >= 3 && lines[2].trim().equals("Enter amount", true)
