package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import me.mycellium.skymyce.api.DungeonChest
import me.mycellium.skymyce.utils.AtomicJsonFile
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import kotlin.time.TimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

data class FloorTracker(
    var totalRuns: Int = 0,
    var totalTimeMillis: Long = 0L,
    var totalXp: Double = 0.0,
    var classXp: MutableMap<DungeonClass, Double> = mutableMapOf(),
    var chests: MutableMap<DungeonChest, ChestTracker> = mutableMapOf(),
    // -1 preserves the old format's assumption that all runs contributed to a valid total.
    var timedRuns: Int = -1,
    var incompleteCosts: Boolean = false,
) {
    val invalidTime: Boolean get() = totalTimeMillis < 0 || totalTimeMillis > totalRuns.coerceAtLeast(0).toLong() * MAX_DUNGEON_RUN_MILLIS
    val timedRunCount: Int get() = if (invalidTime || totalTimeMillis == 0L) 0 else
        (if (timedRuns < 0) totalRuns else timedRuns).coerceIn(0, totalRuns.coerceAtLeast(0))
    val incompleteTime: Boolean get() = timedRunCount < totalRuns
    val averageRunMillis: Double? get() = safeAverage(totalTimeMillis.toDouble(), timedRunCount.toLong())

    fun recordRun(duration: Long?) {
        val previousTimed = timedRunCount
        totalRuns++
        timedRuns = previousTimed
        if (duration != null && duration in 1..MAX_DUNGEON_RUN_MILLIS) {
            totalTimeMillis += duration
            timedRuns++
        }
    }

    fun hourlyRate(value: Double): Double? = if (!incompleteTime && totalTimeHours > 0 && value.isFinite())
        (value / totalTimeHours).takeIf(Double::isFinite) else null

    val totalChestsOpened: Int
        get() = chests.values.sumOf { it.chestCount }

    val totalChestsCost: Double
        get() = chests.values.sumOf { it.chestCost }

    val totalRerolled: Int
        get() = chests.values.sumOf { it.rerollCount }

    val totalRerolledCost: Double
        get() = chests.values.sumOf { it.rerollCost }

    val netProfit: Double
        get() = chests.values.sumOf { it.netProfit }

    val grossProfit: Double
        get() = chests.values.sumOf { it.grossProfit }

    val totalTimeHours: Double
        get() = totalTimeMillis.milliseconds.toDouble(DurationUnit.HOURS)

    companion object {
        val CODEC: Codec<FloorTracker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("totalRuns").forGetter(FloorTracker::totalRuns),
                Codec.LONG.fieldOf("totalTimeMillis").forGetter(FloorTracker::totalTimeMillis),
                Codec.DOUBLE.fieldOf("totalXp").forGetter(FloorTracker::totalXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonClass.valueOf(it) }, { it.name }),
                    Codec.DOUBLE
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("classXp").forGetter(FloorTracker::classXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonChest.valueOf(it) }, { it.name }),
                    ChestTracker.CODEC
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("chests").forGetter(FloorTracker::chests),
                Codec.INT.optionalFieldOf("timedRuns", -1).forGetter(FloorTracker::timedRuns),
                Codec.BOOL.optionalFieldOf("incompleteCosts", false).forGetter(FloorTracker::incompleteCosts),
            ).apply(instance, ::FloorTracker)
        }
    }
}

internal const val MAX_DUNGEON_RUN_MILLIS = 86_400_000L
internal fun dungeonRunDuration(started: TimeMark?): Long? = started?.elapsedNow()?.inWholeMilliseconds
    ?.takeIf { it in 1..MAX_DUNGEON_RUN_MILLIS }

internal val dungeonTotalsCodec: Codec<MutableMap<DungeonFloor, FloorTracker>> = Codec.unboundedMap(
    Codec.STRING.xmap({ DungeonFloor.valueOf(it) }, { it.name }), FloorTracker.CODEC).xmap({ it.toMutableMap() }, { it })

/** Background-thread load. Preserve the exact original before replacing only impossible duration totals. */
internal fun readDungeonTotals(file: AtomicJsonFile): MutableMap<DungeonFloor, FloorTracker> {
    val json = file.read()
    check(!file.damaged) { "Dungeon totals unreadable" }
    if (json == null) return mutableMapOf()
    val totals = dungeonTotalsCodec.parse(JsonOps.INSTANCE, json).result().orElseThrow()
    runCatching { file.preserveOriginal("before-acquisitions") }
    if (totals.values.any { it.invalidTime }) {
        file.preserveOriginal("before-time-repair")
        val repaired = json.deepCopy().asJsonObject
        totals.filterValues { it.invalidTime }.forEach { (floor, data) ->
            data.totalTimeMillis = 0; data.timedRuns = 0
            repaired.getAsJsonObject(floor.name).apply { addProperty("totalTimeMillis", 0); addProperty("timedRuns", 0) }
        }
        file.write(repaired)
    }
    return totals
}
