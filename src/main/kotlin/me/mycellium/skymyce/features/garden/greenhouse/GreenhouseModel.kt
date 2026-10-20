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
) {
    val mutation get() = rarity != Rarity.RAW
    val solvable get() = special == null && requires.isNotEmpty()
}

class PlantCatalog(val plants: List<Plant>) {
    val byId = plants.associateBy { it.id }
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
            json.getAsJsonObject("requires")?.entrySet()?.associate { (key, value) -> key to value.asInt.also { require(it in 1..16) } }.orEmpty())
    }
    val catalog = PlantCatalog(plants)
    require(catalog.byId.size == plants.size && plants.all { plant -> plant.requires.keys.all { it in catalog.byId } })
    return catalog
}

enum class PriceMode(val label: String) { SELL_OFFER("Sell Offer"), INSTANT_SELL("Instant Sell") }
enum class ProfitMetric(val label: String) { PER_HOUR("Per hour"), PER_CYCLE("Per cycle"), PER_DAY("Per day") }

/** Bazaar quick-status pair: the highest buy order and lowest sell offer. */
data class Quote(val buyOrder: Double, val sellOffer: Double)

data class GreenhouseSettings(val upgrade: Int, val uniqueCrops: Int, val priceMode: PriceMode)

/**
 * skymutations.eu prices with `instantBuy = max(sellPrice, buyPrice)` for both the target ("Sell Offer") and the ingredients
 * ("Instant Buy"). Instant Sell is this mod's lower alternative for the target only.
 */
fun revenuePrice(quote: Quote?, mode: PriceMode): Double? = quote?.let {
    if (mode == PriceMode.SELL_OFFER) max(it.buyOrder, it.sellOffer) else minOf(it.buyOrder, it.sellOffer)
}?.takeIf { it.isFinite() && it > 0 }

fun costPrice(plant: Plant, quotes: Map<String, Quote>): Double? =
    if (plant.free) 0.0 else quotes[plant.bazaarId]?.let { max(it.buyOrder, it.sellOffer) }?.takeIf { it.isFinite() && it > 0 }

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
 * ingredient survival are not modelled, matching the site. Unlike the site, a missing target or ingredient price excludes
 * the mutation instead of counting as 0, and raw crops use their real Bazaar ids.
 */
fun rankMutations(catalog: PlantCatalog, quotes: Map<String, Quote>, settings: GreenhouseSettings,
                  allowed: (Plant) -> Boolean = { true }): List<RankedMutation> = catalog.plants.mapNotNull { plant ->
    if (!plant.solvable || !allowed(plant)) return@mapNotNull null
    val revenue = revenuePrice(quotes[plant.bazaarId], settings.priceMode) ?: return@mapNotNull null
    var cost = 0.0
    for ((id, quantity) in plant.requires) {
        val ingredient = catalog[id]!!
        val count = if (ingredient.size > 1 && id != "PLANTBOY_ADVANCE") ceil(quantity / ingredient.size.toDouble()) else quantity.toDouble()
        cost += (costPrice(ingredient, quotes) ?: return@mapNotNull null) * count
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
 * cost spread over the fastest target cycle. Null when a price is missing.
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
    return income - ingredients.sumOf { costPrice(it, quotes) ?: return null } / fastest
}

/** A Greenhouse plot as saved: [facing] is a horizontal direction name. Each plot keeps its own pinned layout. */
data class PlotAnchor(val x: Int, val y: Int, val z: Int, val facing: String)
data class PlotState(val name: String, val anchor: PlotAnchor?, val locked: Set<Int>, val target: String?, val layout: Layout?)

private val HORIZONTAL = setOf("NORTH", "EAST", "SOUTH", "WEST")

fun PlotState.toJson(): JsonObject = JsonObject().apply {
    addProperty("name", name)
    anchor?.let { a -> add("anchor", JsonObject().apply { addProperty("x", a.x); addProperty("y", a.y); addProperty("z", a.z); addProperty("facing", a.facing) }) }
    add("locked", com.google.gson.JsonArray().apply { locked.sorted().forEach { add(it) } })
    target?.let { addProperty("target", it) }
    layout?.let { add("layout", it.toJson()) }
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
        return PlotState(name, anchor, locked, target, layout)
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
