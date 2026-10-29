package me.mycellium.skymyce.features.garden.greenhouse

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.withCommunityBodyDeadline
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData

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

/**
 * [gardenLevel] is 15 (everything unlocked) while the player's level is unknown; [cropGrowth] counts only what is known.
 * [uniqueElsewhere] holds the unique base crops on the player's other plots; [uniqueCrops] is the least the bonus counts.
 */
data class GreenhouseSettings(val upgrade: Int, val uniqueCrops: Int, val priceMode: PriceMode, val gardenLevel: Int = 15, val cropGrowth: Int = 0,
                              val uniqueElsewhere: Set<String> = emptySet())

/**
 * The unique-crop bonus count for a plot growing [here]: the game counts unique crop types in ANY Greenhouse plot, so a crop
 * already on another plot adds nothing. Up to 10 (wiki, 0.27.2).
 */
fun GreenhouseSettings.uniqueWith(here: Collection<String>) = maxOf(uniqueCrops, (uniqueElsewhere + here).size).coerceAtMost(10)

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
 * The Greenhouse growth cycle (hypixelskyblock.minecraft.wiki/w/Greenhouse, 2026-10-28): 4 hours divided by 1 + 2.5% per
 * unique crop (up to 10) + 0.25% per Crop Growth + the Desk's Growth Speed (5% a tier, 10% at IX). Flora and Timestalk
 * shards are not known here, so this can only be slower than the game, never faster.
 */
fun hoursPerStage(upgrade: Int, uniqueCrops: Int, cropGrowth: Int = 0): Double {
    val desk = if (upgrade >= 9) 0.5 else upgrade.coerceAtLeast(0) * 0.05
    return 4.0 / (1.0 + uniqueCrops.coerceIn(0, 10) * 0.025 + cropGrowth.coerceIn(0, 210) * 0.0025 + desk)
}

/** Crop Growth from the Garden level: 10 for each level after I. */
fun gardenCropGrowth(gardenLevel: Int?) = ((gardenLevel ?: 1).coerceIn(1, 15) - 1) * 10

fun cycleHours(plant: Plant, upgrade: Int, uniqueCrops: Int, cropGrowth: Int = 0) = max(1, plant.stages) * hoursPerStage(upgrade, uniqueCrops, cropGrowth)

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
    RankedMutation(plant, revenue, cost, cycleHours(plant, settings.upgrade, settings.uniqueCrops, settings.cropGrowth))
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
    val unique = settings.uniqueWith(ingredients.filter { it.unique }.map { it.id })
    var income = 0.0
    var fastest = Double.MAX_VALUE
    for (target in targets) {
        val plant = catalog[target.plantId] ?: return null
        val hours = cycleHours(plant, settings.upgrade, unique, settings.cropGrowth)
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

/**
 * A growth stage read from the Plant Diagnostics Tool: [stage] of [stages] at [at]; [awake] when the player woke it at that
 * stage since. [water] is its water level (-100..100) when it needs water and the level is known; [drain] the water it loses
 * per hour, from the tool's "Lasts for". [enough] when the tool said it "has enough water to reach full growth".
 */
data class GrowthReading(val stage: Int, val stages: Int, val at: Long, val awake: Boolean = false, val water: Double? = null, val drain: Double? = null,
                         val enough: Boolean = false)

/**
 * A mutation's growth now. [paused] waits on the player (an asleep Snoozling); [readyInMs] is null when it cannot be told,
 * including when its water runs out first or it must be woken on the way. [dryInMs] is when a growing one's water runs out
 * (0: already), null when untracked. [waitInMs] is when it stops until the player acts (0: stopped now), null when it will not.
 * [nextInMs] is when it reaches its next stage, null when that cannot be told (paused, unpredictable, fully grown or dry first).
 */
data class GrowthEstimate(val stage: Double, val stages: Int, val ready: Boolean, val paused: Boolean, val readyInMs: Long?, val dryInMs: Long? = null,
                          val waitInMs: Long? = null, val nextInMs: Long? = null)

/**
 * The Greenhouse's shared growth tick, which advances every plant at once: a tick at [tickAt] and every [cycleMs] around it.
 * [measured] once two "Next Stage" countdowns timed the cycle; until then [cycleMs] is only a lower bound.
 */
data class GrowthCycle(val tickAt: Long, val cycleMs: Long, val measured: Boolean = false) {
    /** Ticks in (from, to]. */
    fun ticks(from: Long, to: Long): Int = if (to <= from) 0 else (Math.floorDiv(to - tickAt, cycleMs) - Math.floorDiv(from - tickAt, cycleMs)).toInt()
    /** The [n]-th tick after [from] (n >= 1). */
    fun tickAfter(from: Long, n: Int): Long = tickAt + (Math.floorDiv(from - tickAt, cycleMs) + n) * cycleMs
}

/**
 * Folds a Diagnostics "Next Stage" countdown (the next tick at [nextTick]) into [cycle]. Two ticks a few cycles apart time
 * the cycle exactly; one far off the expected length means it changed (a Desk upgrade), so timing starts over.
 */
fun updateCycle(cycle: GrowthCycle?, nextTick: Long, countdownMs: Long, formulaMs: Long): GrowthCycle {
    val length = maxOf(cycle?.cycleMs ?: formulaMs, countdownMs)
    if (cycle == null) return GrowthCycle(nextTick, length)
    val k = Math.round((nextTick - cycle.tickAt).toDouble() / length)
    if (k == 0L) return GrowthCycle(nextTick, length, cycle.measured)
    val measured = (nextTick - cycle.tickAt).toDouble() / k
    if (abs(k) > 6 || abs(measured - length) > length * 0.1) return GrowthCycle(nextTick, maxOf(formulaMs, countdownMs))
    return GrowthCycle(nextTick, Math.round(measured).coerceAtLeast(countdownMs), measured = true)
}

/**
 * Water lost per hour, from the Diagnostics "Water: 45.8/100" and "Lasts for: 15h 02m 38s". "Lasts for" runs to -100, where
 * growth halts: read in game on 2026-10-29, 45.8 lasting 15h 02m 38s and 100 lasting 19h 11m 02s both lose about 10 an hour
 * that way, while to 0 they would disagree (3.0 and 5.2). Water drops once a growth cycle, so this rate is an average.
 */
fun drainPerHour(water: Double?, lastsMs: Long?): Double? =
    if (water == null || lastsMs == null || water <= -100 || lastsMs <= 0) null else (water + 100) / (lastsMs / 3_600_000.0)

/** Water lost per hour until a plant's own rate is read: about 10 in game on 2026-10-29 (see [drainPerHour]). */
const val DEFAULT_DRAIN = 10.0

/**
 * Projects a reading with the growth timer, following [plant]'s rules: it stops at a pause until woken, a restarting crop
 * wraps back to stage 1, and unpredictable growth stays at what was read. With [cycle] it advances on the shared tick.
 * With [drain] (water per hour), growth is only promised while the water stays above 0, since below it the game may skip a
 * stage. Day/night (Noctilume) is not modelled.
 */
fun estimateGrowth(plant: Plant?, reading: GrowthReading, hoursPerStage: Double, now: Long, cycle: GrowthCycle? = null, drain: Double? = null): GrowthEstimate {
    val stages = reading.stages
    val readyAt = plant?.readyStage?.coerceAtMost(stages) ?: stages
    val stageMs = hoursPerStage * 3_600_000
    val unpredictable = plant?.unpredictable == true
    val passed = cycle?.ticks(reading.at, now)?.toDouble() ?: ((now - reading.at).coerceAtLeast(0) / stageMs)
    val raw = reading.stage + if (unpredictable) 0.0 else passed
    val pause = plant?.pauses?.firstOrNull { it in reading.stage until stages && (it > reading.stage || !reading.awake) }
    val paused = pause != null && raw >= pause
    val stage = when {
        paused -> pause.toDouble()
        plant?.restarts == true && raw >= stages + 1 -> 1 + (raw - stages - 1) % stages
        else -> raw.coerceAtMost(stages.toDouble())
    }
    val ready = stage >= readyAt
    // When the water reaches 0; never before full growth when the game said it has enough.
    val dryAt = reading.water?.takeIf { drain != null && drain > 0 && !reading.enough }?.let { reading.at + (it.coerceAtLeast(0.0) / drain!! * 3_600_000).toLong() }
    // Time until it reaches [target], promised only while its water lasts.
    fun until(target: Int): Long? = (if (cycle != null) cycle.tickAfter(now, ceil(target - stage).toInt()) - now else ((target - stage) * stageMs).toLong())
        .takeIf { dryAt == null || now + it <= dryAt }
    val readyInMs = when {
        ready -> 0L
        // A pause on the way (a Snoozling falling asleep) waits on the player, so the ready time cannot be told yet.
        paused || unpredictable || pause != null && pause < readyAt -> null
        else -> until(readyAt)
    }
    // Only water that runs out before it is ready matters; after that it needs none.
    val dryInMs = dryAt?.takeIf { !ready }?.let { (it - now).coerceAtLeast(0) }?.takeIf { readyInMs == null || it < readyInMs }
    val waitInMs = when {
        paused -> 0L
        pause == null || unpredictable || ready -> null
        else -> until(pause)
    }
    val nextInMs = if (paused || unpredictable || stage >= stages) null else until(floor(stage).toInt() + 1)
    return GrowthEstimate(stage, stages, ready, paused, readyInMs, dryInMs, waitInMs, nextInMs)
}

/**
 * One plot's next Discord reminders: when [percent] of its mutations are ready, when the first growing one runs out of
 * water, and when the first one stops until the player acts (wake a Snoozling, kill a Cheesebite's rat). [estimates] has one
 * entry per mutation on the plot (null when not timed). Only future times: something already true is not reminded.
 */
data class PlotReminder(val readyAt: Long?, val readyCount: Int, val dryAt: Long?, val dryCount: Int, val total: Int,
                        val waitAt: Long? = null, val waitCount: Int = 0)

fun plotReminder(estimates: List<GrowthEstimate?>, percent: Int, now: Long): PlotReminder? {
    if (estimates.isEmpty()) return null
    val total = estimates.size
    val needed = ceil(total * percent.coerceIn(1, 100) / 100.0).toInt().coerceIn(1, total)
    val readyAt = if (estimates.count { it?.ready == true } >= needed) null
        else estimates.map { it?.readyInMs }.sortedWith(nullsLast(naturalOrder()))[needed - 1]?.let { now + it }
    // The first future time, and how many fall within 30 minutes of it (one DM covers them).
    fun soonest(times: List<Long>): Pair<Long, Int>? = times.filter { it > 0 }.minOrNull()?.let { first -> now + first to times.count { it in 1..first + 30 * 60_000 } }
    val dry = soonest(estimates.mapNotNull { it?.dryInMs })
    val wait = soonest(estimates.mapNotNull { it?.waitInMs })
    return PlotReminder(readyAt, needed, dry?.first, dry?.second ?: 0, total, wait?.first, wait?.second ?: 0)
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

/** What a Crop Diagnostics menu showed. [water] is null when the crop needs no water or it was not shown. */
data class Diagnostics(val stage: Int, val stages: Int, val water: Double?, val needsWater: Boolean?, val nextStageMs: Long?, val lastsMs: Long? = null,
                       val enoughWater: Boolean = false)

fun parseDiagnostics(lines: List<String>): Diagnostics? {
    val (stage, stages) = parseGrowth(lines) ?: return null
    val text = lines.map { it.replace(FORMATTING, "") }
    val noWater = text.any { NO_WATER.containsMatchIn(it) }
    val water = if (noWater) null else text.firstNotNullOfOrNull { WATER.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.takeIf { it in -100.0..100.0 }
    val next = text.firstNotNullOfOrNull { NEXT_STAGE.find(it)?.groupValues?.get(1) }?.let(::parseDuration)
    val lasts = if (water == null) null else text.firstNotNullOfOrNull { LASTS_FOR.find(it)?.groupValues?.get(1) }?.let(::parseDuration)
    return Diagnostics(stage, stages, water, if (noWater) false else if (water != null) true else null, next, lasts,
        water != null && ENOUGH_WATER.containsMatchIn(text.joinToString(" ")))
}

/** "1d 10h 41m 12s" in milliseconds, or null without a part. */
fun parseDuration(text: String): Long? {
    val parts = DURATION_PART.findAll(text).toList().takeIf { it.isNotEmpty() } ?: return null
    return parts.sumOf { part ->
        part.groupValues[1].toLong() * when (part.groupValues[2]) { "d" -> 86_400_000L; "h" -> 3_600_000L; "m" -> 60_000L; else -> 1000L }
    }
}

/**
 * A plant's water from the bar shown over it while a watering can is held, as (glyph, RGB colour) pairs. The bar has 16
 * segments: blue filled from the left above 0, red from the right below it, white otherwise (read in game 2026-10-29: 7 blue
 * at 45.8, 6 red at -40). Only the first line counts; the lower end of the segment's range is returned. Null for anything else.
 */
fun parseWaterBar(glyphs: List<Pair<Char, Int>>): Double? {
    val segments = glyphs.takeWhile { it.first != '\n' }.filter { !it.first.isWhitespace() }
    if (segments.size !in 8..40 || segments.map { it.first }.distinct().size != 1) return null
    val kinds = segments.map { (_, rgb) -> barKind(rgb) ?: return null }
    val blue = kinds.count { it == 'b' }
    val red = kinds.count { it == 'r' }
    if (blue > 0 && red > 0 || kinds.take(blue).any { it != 'b' } || kinds.takeLast(red).any { it != 'r' }) return null
    val step = 100.0 / segments.size
    return if (red > 0) -(red + 1) * step else blue * step
}

private fun barKind(rgb: Int): Char? {
    val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
    return when {
        r > 200 && g > 200 && b > 200 -> 'w'
        b > r + 60 && b > g + 60 -> 'b'
        r > g + 60 && r > b + 60 -> 'r'
        else -> null
    }
}

/** The Desk's Greenhouse Growth Speed tier, from its menu item ("Growth Speed", lore "Current Tier: 3/9"), or null. */
fun parseDeskGrowthTier(name: String, lore: List<String>): Int? {
    if (name.replace(FORMATTING, "").trim() != "Growth Speed") return null
    val text = lore.map { it.replace(FORMATTING, "") }
    if (text.none { it.contains("Greenhouse Growth") }) return null
    return text.firstNotNullOfOrNull { DESK_TIER.find(it)?.groupValues?.get(1)?.toInt() }?.takeIf { it in 0..9 }
}

private val WATER = Regex("(?i)^\\s*water:\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*/\\s*100\\b")
private val NO_WATER = Regex("(?i)doesn't need water")
// Wrapped over two lore lines in the menu ("This crop has enough water to" / "reach full growth!").
private val ENOUGH_WATER = Regex("(?i)\\bhas enough water to\\s+reach full growth")
private val LASTS_FOR = Regex("(?i)lasts for:\\s*((?:\\d{1,4}\\s*[dhms]\\s*)+)")
private val NEXT_STAGE = Regex("(?i)next stage:\\s*((?:\\d{1,4}\\s*[dhms]\\s*)+)")
private val DURATION_PART = Regex("(\\d{1,4})\\s*([dhms])")
private val DESK_TIER = Regex("Current Tier:\\s*(\\d)\\s*/\\s*9")
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
        growth.forEach { (cell, reading) -> add(cell.toString(), com.google.gson.JsonArray().apply {
            add(reading.stage); add(reading.stages); add(reading.at)
            if (reading.awake || reading.water != null || reading.drain != null || reading.enough) add(reading.awake)
            if (reading.water != null || reading.drain != null || reading.enough) { add(reading.water); add(reading.drain) }
            if (reading.enough) add(true)
        }) }
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
                        GrowthReading(row[0].asInt, row[1].asInt, row[2].asLong, row.size() > 3 && row[3].asBoolean,
                            row.takeIf { it.size() > 4 && !it[4].isJsonNull }?.get(4)?.asDouble?.takeIf { it in -100.0..100.0 },
                            row.takeIf { it.size() > 5 && !it[5].isJsonNull }?.get(5)?.asDouble?.takeIf { it > 0 && it <= 200 },
                            row.size() > 6 && row[6].asBoolean)
                            .also { require(it.stages in 1..200 && it.stage in 0..it.stages) }
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
    val unique = settings.uniqueWith(placements.filter { it.role == Role.INGREDIENT && catalog[it.plantId]!!.unique }.map { it.plantId })
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

/**
 * Reads what occupies a crop spot as a stable fingerprint: `block:<id>@<soil block>`, `head:<texture>` for a player head
 * (as a block, an armor stand's helmet or a display item), `item:<SkyBlock or vanilla id>`, or `entity:<type>` when
 * nothing more is known. Mutations are mostly player heads using their item's texture, but some reuse a vanilla crop
 * block on a different soil (Ashwreath is wheat on soul sand), so block fingerprints carry the soil. Null means empty.
 */
internal object GreenhouseWorld {
    private val TEXTURE_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

    /** Mutations seen in game as a vanilla crop block on their own soil; checked before [VANILLA]. */
    private val ON_SOIL = mapOf("block:wheat@soul_sand" to "ASHWREATH")

    /** Vanilla crop blocks for the base crops, on any soil; everything else is matched by head texture, item id or learned. */
    private val VANILLA = mapOf(
        "wheat" to "WHEAT_SEEDS", "potatoes" to "POTATO", "carrots" to "CARROT", "melon_stem" to "MELON_SEEDS",
        "attached_melon_stem" to "MELON_SEEDS", "pumpkin_stem" to "PUMPKIN_SEEDS", "attached_pumpkin_stem" to "PUMPKIN_SEEDS",
        "cocoa" to "COCOA_BEANS", "sugar_cane" to "SUGAR_CANE", "cactus" to "CACTUS", "nether_wart" to "NETHER_WART",
        "brown_mushroom" to "BROWN_MUSHROOM", "red_mushroom" to "RED_MUSHROOM", "fire" to "FIRE", "sunflower" to "SUNFLOWER",
    ).mapKeys { "block:${it.key}" }

    fun fingerprint(level: Level, pos: BlockPos): String? {
        // A mutation is a head on an armor stand, usually over a vanilla wheat or melon block that is only decoration,
        // so a worn or displayed item wins over the block. Bare armor stands are Hypixel's invisible markers, not crops.
        // An entity belongs to the slot it stands in; one near a slot edge also overlaps its neighbour's column.
        val entities = level.getEntitiesOfClass(Entity::class.java, column(pos)) { entity ->
            entity.blockX == pos.x && entity.blockZ == pos.z &&
                (entity is Display || entity is ArmorStand && EquipmentSlot.entries.any { !entity.getItemBySlot(it).isEmpty })
        }
        var fallback: String? = null
        for (entity in entities) {
            val stack = when (entity) {
                is ArmorStand -> EquipmentSlot.entries.firstNotNullOfOrNull { slot -> entity.getItemBySlot(slot).takeUnless { it.isEmpty } }
                is Display.ItemDisplay -> entity.itemRenderState()?.itemStack()
                else -> null
            }
            itemKey(stack)?.let { return it }
            fallback = fallback ?: (entity as? Display.BlockDisplay)?.blockRenderState()?.blockState()?.let { blockKey(level, pos, it) }
                ?: ("entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path)
        }
        val state = level.getBlockState(pos)
        if (state.`is`(Blocks.FIRE) || !state.isAir && !state.canBeReplaced()) {
            (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.let { textureKey(it.partialProfile()) }?.let { return "head:$it" }
            return blockKey(level, pos, state)
        }
        return fallback
    }

    private fun blockKey(level: Level, pos: BlockPos, state: BlockState) =
        "block:" + BuiltInRegistries.BLOCK.getKey(state.block).path + "@" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).block).path

    /**
     * The space a crop's entities can occupy: its own block column from 1.5 below the crop block to 2.5 above. Armor-stand
     * crops stand with their feet below the block so the head shows in it, and marker stands (no size) float above it.
     */
    private fun column(pos: BlockPos) = AABB(pos.x.toDouble(), pos.y - 1.5, pos.z.toDouble(), pos.x + 1.0, pos.y + 2.5, pos.z + 1.0)

    /** What is at a spot, for the log when a look is not recognised: entity types, names, flags and equipment. */
    fun describe(level: Level, pos: BlockPos): String = level.getEntitiesOfClass(Entity::class.java, column(pos)) { it is ArmorStand || it is Display }
        .take(4).joinToString("; ") { entity ->
            val equipment = (entity as? ArmorStand)?.let { stand -> EquipmentSlot.entries.mapNotNull { slot -> stand.getItemBySlot(slot).takeUnless { it.isEmpty }?.let { "$slot=${itemKey(it)}" } } }
            "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path} at ${"%.2f".format(entity.y - pos.y)} name=${entity.customName?.string} invisible=${entity.isInvisible}" +
                ((entity as? ArmorStand)?.let { " marker=${it.isMarker} small=${it.isSmall} equipment=$equipment" } ?: "")
        }.ifEmpty { "block " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path }

    /** The plant a fingerprint shows: learned first, then bundled head textures, SkyBlock item ids and vanilla crop blocks. */
    fun plantFor(fingerprint: String, catalog: PlantCatalog, learned: Map<String, String>): String? = learned[fingerprint] ?: when {
        fingerprint.startsWith("head:") -> catalog.byHead[fingerprint.removePrefix("head:")]?.id
        fingerprint.startsWith("item:") -> fingerprint.removePrefix("item:").let { id -> catalog[id]?.id ?: catalog.plants.firstOrNull { id in it.itemIds }?.id }
        else -> ON_SOIL[fingerprint] ?: VANILLA[fingerprint.substringBefore('@')]
    }

    private fun itemKey(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty) return null
        stack.getData(DataTypes.ID)?.let { return "item:$it" }
        stack.get(DataComponents.PROFILE)?.let { textureKey(it.partialProfile()) }?.let { return "head:$it" }
        return "item:" + BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase()
    }

    /** The skin file name from a head's texture property; the full value also carries a timestamp that varies. */
    private fun textureKey(profile: GameProfile): String? {
        val value = profile.properties()["textures"].firstOrNull()?.value ?: return null
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(value)) }.getOrNull() ?: return value.take(64)
        return TEXTURE_URL.find(decoded)?.groupValues?.get(1)?.substringAfterLast('/') ?: value.take(64)
    }
}

/**
 * One product's top of book. [buyOrderPrice] is the best bid (what a new buy order competes with) and
 * [sellOfferPrice] the best ask. Volumes are the summed amounts of the best five levels on each side.
 */
data class BazaarProduct(
    val id: String,
    val buyOrderPrice: Double?,
    val sellOfferPrice: Double?,
    val buyVolume: Double? = null,
    val sellVolume: Double? = null,
    val buyMovingWeek: Double? = null,
    val sellMovingWeek: Double? = null,
    val buyOrders: Int? = null,
    val sellOrders: Int? = null,
)

data class BazaarSnapshot(val source: String, val observedAt: Long, val retrievedAt: Long, val products: List<BazaarProduct>)

/** Public market traffic has no player identity or credentials. The planner owns its single refresh job. */
internal class BazaarTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private var backendRetryAt = 0L
    private val retryDeadlines = ConcurrentHashMap<String, Long>()

    fun fetch(now: Long): BazaarSnapshot {
        if (now >= backendRetryAt) {
            val origin = runCatching { URI(CommunityClient.configuration.getProperty("backend")) }.getOrNull()
            if (origin?.scheme == "https" && origin.userInfo == null && origin.rawQuery == null && origin.fragment == null) {
                runCatching { return parseBazaarMarket(get(origin.resolve("/community/bazaar/market?active=true")), now, normalized = true) }
                    .exceptionOrNull()?.let { if (it is InterruptedException || it is BazaarMarketFailure && it.rateLimited) throw it }
            }
            backendRetryAt = now + 10 * 60_000
        }
        return parseBazaarMarket(get(URI("https://api.hypixel.net/v2/skyblock/bazaar")), now).copy(source = "Hypixel public Bazaar · direct fallback")
    }

    private fun get(uri: URI): JsonObject {
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        val origin = "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host.lowercase(Locale.ROOT)}:${uri.port.takeIf { it >= 0 } ?: defaultPort}"
        val now = System.currentTimeMillis()
        retryDeadlines[origin]?.let { deadline ->
            if (deadline > now) throw BazaarMarketFailure(true, deadline)
            retryDeadlines.remove(origin, deadline)
        }
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            if (response.statusCode() == 429) {
                val received = System.currentTimeMillis()
                val fallback = if (received > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else received + 60_000
                val requested = parseBazaarRetryAt(response.headers().firstValue("Retry-After").orElse(null), received)
                val deadline = requireNotNull(retryDeadlines.merge(origin, maxOf(fallback, requested ?: fallback)) { previous, next -> maxOf(previous, next) })
                runCatching { response.body().close() }
                throw BazaarMarketFailure(true, deadline)
            }
            response.body().close()
            throw BazaarMarketFailure(false)
        }
        return readBazaarMarketBody(response.body())
    }
}

internal class BazaarMarketFailure(val rateLimited: Boolean, val retryAt: Long? = null) : Exception()

private const val DEPTH_LEVELS = 5
private val PRODUCT_ID = Regex("[A-Z0-9_:;-]{1,100}")

/** Full public order books exceed the community DTO reader's deliberate 256 KiB ceiling. */
internal fun readBazaarMarketBody(body: InputStream): JsonObject = withCommunityBodyDeadline(body, Duration.ofSeconds(15)) { input ->
    val maximum = 8 * 1024 * 1024
    val bytes = input.readNBytes(maximum + 1)
    require(bytes.size <= maximum) { "Bazaar market response exceeds 8 MiB" }
    val json = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) }
        catch (failure: com.google.gson.JsonParseException) { throw IllegalArgumentException("Bazaar market response is invalid JSON", failure) }
    require(json.isJsonObject) { "Bazaar market response must be a JSON object" }
    json.asJsonObject
}

/** Retry-After has no short upper cap: overflowing positive waits saturate rather than retrying early. */
internal fun parseBazaarRetryAt(header: String?, now: Long): Long? {
    if (now < 0) return null
    val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.all { it in '0'..'9' }) {
        var seconds = 0L
        for (character in value) {
            val digit = character - '0'
            if (seconds > (Long.MAX_VALUE - digit) / 10) return Long.MAX_VALUE
            seconds = seconds * 10 + digit
        }
        return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
    }
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull()?.takeIf { it >= now }
}

/** Hypixel's names describe instant transactions: sell_summary is bids; buy_summary is asks. */
internal fun parseBazaarMarket(json: JsonObject, now: Long, normalized: Boolean = false): BazaarSnapshot {
    require(now > 0 && (if (normalized) json["ok"]?.asBoolean else json["success"]?.asBoolean) == true)
    if (normalized) require(json.number("version") == 1.0)
    val observed = json.number(if (normalized) "observedAt" else "lastUpdated")?.toLong() ?: error("Market timestamp unavailable")
    val retrieved = if (normalized) json.number("retrievedAt")?.toLong() ?: error("Retrieval timestamp unavailable") else now
    require(observed > 0 && retrieved > 0 && observed <= now + 10_000 && retrieved <= now + 10_000)
    val entries = if (normalized) {
        val array = json.getAsJsonArray("products") ?: error("Products unavailable")
        require(array.size() <= 10_000)
        array.map { item -> require(item.isJsonObject); val product = item.asJsonObject; (product.text("id", 100) ?: error("Product id unavailable")) to product }
    } else {
        val products = json.getAsJsonObject("products") ?: error("Products unavailable")
        require(products.size() <= 10_000)
        products.entrySet().map { (id, value) -> require(value.isJsonObject); id to value.asJsonObject }
    }
    val seen = hashSetOf<String>()
    val products = entries.map { (id, product) ->
        require(seen.add(id) && id.matches(PRODUCT_ID))
        if (!normalized) require(product.text("product_id", 100) == id)
        val quick = if (normalized) product else product.getAsJsonObject("quick_status") ?: JsonObject()
        // Both sources describe depth as the best five price levels.
        fun depth(name: String, bids: Boolean): Pair<Double?, Double?> {
            val levels = product.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null to null
            require(levels.size() <= 30)
            val valid = levels.map { value ->
                require(value.isJsonObject)
                val level = value.asJsonObject
                val price = level.number(if (normalized) "price" else "pricePerUnit")
                val amount = level.number("amount")
                require(price != null && price > 0 && amount != null && amount >= 0)
                price to amount
            }.filter { it.second > 0 }
            val best = (if (bids) valid.sortedByDescending { it.first } else valid.sortedBy { it.first }).take(DEPTH_LEVELS)
            return best.firstOrNull()?.first to best.sumOf { it.second }
        }
        val bid = depth(if (normalized) "buyDepth" else "sell_summary", true)
        val ask = depth(if (normalized) "sellDepth" else "buy_summary", false)
        BazaarProduct(id,
            if (normalized) product.number("buyPrice") ?: bid.first else bid.first,
            if (normalized) product.number("sellPrice") ?: ask.first else ask.first,
            bid.second, ask.second,
            quick.number(if (normalized) "buyMovingWeek" else "sellMovingWeek"),
            quick.number(if (normalized) "sellMovingWeek" else "buyMovingWeek"),
            quick.number(if (normalized) "buyOrders" else "sellOrders")?.toInt(),
            quick.number(if (normalized) "sellOrders" else "buyOrders")?.toInt())
    }
    return BazaarSnapshot(if (normalized) json.text("source", 160) ?: "Sky-Myce shared Bazaar" else "Hypixel public Bazaar", observed, retrieved, products)
}

private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.asDouble?.takeIf { it.isFinite() && it in 0.0..1e15 }
private fun JsonObject.text(key: String, maximum: Int): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString?.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }

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
        // An ingredient without a price costs nothing here, matching the ranking; only the target must be priced.
        plant.solvable && revenuePrice(quotes[plant.bazaarId], settings.priceMode) != null && gardenLevelFor(catalog, plant) <= settings.gardenLevel
    }.distinct()
    private val pool = (targets + targets.flatMap { it.requires.keys }.map { catalog[it]!! } +
        catalog.plants.filter { it.unique && it.gardenLevel <= settings.gardenLevel && costPrice(it, quotes) != null }).distinct()
    private val index = pool.withIndex().associate { (i, plant) -> plant.id to i }
    private val revenue = DoubleArray(pool.size) { revenuePrice(quotes[pool[it].bazaarId], settings.priceMode) ?: 0.0 }
    private val cost = DoubleArray(pool.size) { costPrice(pool[it], quotes) ?: 0.0 }
    private val needs = pool.map { plant -> if (plant in targets) plant.requires.map { (id, count) -> index.getValue(id) to count } else emptyList() }
    private val targetIndices = targets.map { index.getValue(it.id) }
    private val uniqueIndices = pool.indices.filter { pool[it].unique }
    /** Unique crops already on the player's other plots: planting them here adds nothing to the bonus. */
    private val elsewhere = uniqueIndices.filter { pool[it].id in settings.uniqueElsewhere }.toSet()
    private val elsewhereOutsidePool = settings.uniqueElsewhere.count { id -> uniqueIndices.none { pool[it].id == id } }
    /** The bonus count with [counted] of the pool's unique crops on this plot or elsewhere. */
    private fun uniqueCount(counted: Int) = maxOf(settings.uniqueCrops, elsewhereOutsidePool + counted).coerceAtMost(10)
    /** Plants worth planting: anything a target needs, plus unique crops for the growth bonus. */
    private val plantable = (targetIndices.flatMap { t -> needs[t].map { it.first } } + uniqueIndices).distinct()

    private data class Item(val plant: Int, val row: Int, val col: Int, val size: Int)
    private class Evaluation(val exact: Double, val guided: Double, val unique: Int, val targets: List<Item>, val ingredients: List<Item> = emptyList())

    fun solve(seed: Long, now: Long, hash: String): Layout? {
        if (targets.isEmpty()) return null
        val random = Random(seed)
        var ingredients = prune(anneal(random, ::mutate, ::evaluate))
        if (targets.all { target -> target.requires.keys.all { catalog[it]!!.size == 1 } }) {
            // Rings shared between multi-tile targets need all their tiles at once; choosing footprints first finds those lattices.
            val footprints = anneal(random, ::mutateTargets, ::assign)
            val alternative = prune(assign(footprints).ingredients)
            if (evaluate(alternative).exact > evaluate(ingredients).exact) ingredients = alternative
        }
        val final = evaluate(ingredients)
        if (final.targets.isEmpty()) return null
        val placements = ingredients.map { Placement(pool[it.plant].id, it.row, it.col, it.size, Role.INGREDIENT) } +
            final.targets.map { Placement(pool[it.plant].id, it.row, it.col, it.size, Role.TARGET) }
        return Layout(placements, placements.associateWith { catalog[it.plantId]!!.soils.first() }, final.exact, final.unique, now, hash)
    }

    private fun anneal(random: Random, mutate: (List<Item>, Random) -> List<Item>?, evaluate: (List<Item>) -> Evaluation): List<Item> {
        var current = emptyList<Item>()
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

    private fun cycle(plant: Int, unique: Int) = cycleHours(pool[plant], settings.upgrade, unique, settings.cropGrowth)

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
        val unique = uniqueCount(uniqueIndices.count { u -> u in elsewhere || ingredients.any { it.plant == u } })
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
        if (fastest == Double.MAX_VALUE) fastest = hoursPerStage(settings.upgrade, unique, settings.cropGrowth)
        val exact = income - ingredients.sumOf { cost[it.plant] } / fastest
        return Evaluation(exact, exact + partial, unique, placed)
    }

    private fun mutate(items: List<Item>, random: Random): List<Item>? {
        val roll = random.nextInt(100)
        if (roll < 30) return repair(items, random)
        if (items.isEmpty() || roll < 45) return add(items, random.nextInt(plantable.size).let { plantable[it] }, random)
        val i = random.nextInt(items.size)
        val item = items[i]
        val rest = items.toMutableList().apply { removeAt(i) }
        return when {
            roll < 70 -> rest
            roll < 88 -> {
                val options = plantable.filter { pool[it].size == item.size && it != item.plant }
                if (options.isEmpty()) null else items.toMutableList().apply { set(i, item.copy(plant = options[random.nextInt(options.size)])) }
            }
            else -> add(rest, item.plant, random)
        }
    }

    private fun add(base: List<Item>, plant: Int, random: Random): List<Item>? {
        val cells = IntArray(GREENHOUSE_CELLS) { -1 }
        base.forEachIndexed { i, item -> occupy(cells, item, i) }
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
            return add(items, plant, random)
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
        val present = uniqueIndices.filter { u -> u in elsewhere || ingredients.any { it.plant == u } }
        val missing = (uniqueIndices - present.toSet()).sortedBy { cost[it] }
        val free = (0 until GREENHOUSE_CELLS).filter { unlocked[it] && cells[it] < 0 }
        var bestExtra = 0; var bestExact = Double.NEGATIVE_INFINITY
        for (extra in 0..minOf(missing.size, free.size)) {
            val unique = uniqueCount(present.size + extra)
            val fastest = validTargets.minOfOrNull { cycle(it.plant, unique) } ?: hoursPerStage(settings.upgrade, unique, settings.cropGrowth)
            val exact = validTargets.sumOf { revenue[it.plant] / cycle(it.plant, unique) } -
                (ingredients.sumOf { cost[it.plant] } + missing.take(extra).sumOf { cost[it] }) / fastest
            if (exact > bestExact) { bestExact = exact; bestExtra = extra }
        }
        missing.take(bestExtra).forEachIndexed { i, plant -> ingredients += Item(plant, free[i] / GREENHOUSE_SIZE, free[i] % GREENHOUSE_SIZE, 1) }
        val unique = uniqueCount(present.size + bestExtra)
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
