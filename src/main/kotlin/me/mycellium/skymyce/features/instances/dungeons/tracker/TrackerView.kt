package me.mycellium.skymyce.features.instances.dungeons.tracker

import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
data class TrackerSummary(val runs: Long = 0, val time: Long = 0, val timedRuns: Long = 0,
    val gross: Double = 0.0, val chestCost: Double = 0.0, val rerollCost: Double? = 0.0,
    val cataXp: Double = 0.0, val classXp: Map<String, Double> = emptyMap(), val chests: Int = 0,
    val rerolls: Int = 0, val incompleteValues: Boolean = false, val incompleteCosts: Boolean = false,
    val knownRerollCost: Double = rerollCost ?: 0.0, val preservedProfit: Double = 0.0,
    val profitTimingComplete: Boolean = true, val xpTimingComplete: Boolean = true) {
    val average get() = safeAverage(time.toDouble(), timedRuns)
    val completeTiming get() = time > 0 && timedRuns == runs
    val profitIncomplete get() = incompleteValues || incompleteCosts || rerollCost == null
    val knownNet get() = gross - chestCost - knownRerollCost + preservedProfit
    val net get() = knownNet.takeUnless { profitIncomplete }
    fun hourlyRate(value: Double): Double? = if (completeTiming && value.isFinite())
        (value * 3600000 / time).takeIf(Double::isFinite) else null
}
data class LegacyFloorView(val floor: String, val summary: TrackerSummary, val loot: Map<String, List<TrackerLootRow>>,
    val chestCosts: Map<String, Double>, val rerollCosts: Map<String, Double>, val chestCounts: Map<String, Int>)
data class TrackerView(val summary: TrackerSummary = TrackerSummary(), val loot: List<TrackerLootRow> = emptyList(),
    val events: List<AcquisitionEvent> = emptyList(), val usingLegacy: Boolean = false,
    val inheritedProfit: Double = 0.0, val trackedLifetimeProfit: Double? = 0.0,
    val historicalRuns: Long = 0, val historicalChests: Int = 0) {
    val lifetimeProfit get() = trackedLifetimeProfit?.let { inheritedProfit + it }
}

/** Market presentation replaces frozen recorded values; it never changes the saved profit ledger. */
internal fun marketValuedTrackerSummary(recorded: TrackerSummary, marketLoot: List<TrackerLootRow>) = recorded.copy(
    gross = marketLoot.sumOf { it.value ?: 0.0 }, incompleteValues = marketLoot.any { it.value == null }, preservedProfit = 0.0)

/** Runs only when data or filters change, on the background scheduler. */
fun trackerView(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, filter: TrackerFilter, context: AcquisitionContext?, includeDetails: Boolean = true): TrackerView {
    val legacyMode = filter.scope == TrackerScope.UNSCOPED_LEGACY && !filter.needsRecords
    val events = if (includeDetails) filter.events(archive.events, context, archive.runs) else emptyList()
    val inheritedProfit = archive.legacyProfit.filterKeys(filter::floorMatches).values.sum()
    val trackedLifetimeProfit = summarizeRuns(archive.runs.filter { it.trackingVersion == 2 && filter.floorMatches(it.floor) }, null, false).first.net
    val floors = legacy.filter { filter.floorMatches(it.floor) }
    val historicalRuns = floors.sumOf { it.summary.runs }
    val historicalChests = floors.sumOf { it.chestCounts.values.sum() }
    fun sampledLoot(rows: List<TrackerLootRow>): List<TrackerLootRow> {
        if (filter.scope == TrackerScope.CURRENT_PROFILE || filter.needsRecords || filter.chest != null) return rows
        val samples = archive.personalRateSamples.filter { filter.floorMatches(it.floor) }
        return rows.map { row -> row.copy(rateSample = samples.singleOrNull { row.item in it.itemCounts }) }
    }
    if (legacyMode) {
        val (summary, loot) = summarizeLegacy(floors, filter.chest)
        return TrackerView(summary,
            sortedLoot(sampledLoot(loot), filter), events, true, inheritedProfit, trackedLifetimeProfit, historicalRuns, historicalChests)
    }
    val runs = filter.runs(archive.runs, context)
    val (recordedSummary, loot) = summarizeRuns(runs, filter.chest, includeDetails)
    val selectedSummary = recordedSummary.copy(incompleteCosts = recordedSummary.incompleteCosts || runs.any { run ->
        run.trackingVersion == 1 && floors.any { it.floor == run.floor && it.summary.incompleteCosts }
    })
    val combined = filter.page in listOf(TrackerPage.OVERVIEW, TrackerPage.LOOT, TrackerPage.MUSEUM) &&
        filter.scope == TrackerScope.ALL_LOCAL_PROFILES && !filter.needsRecords
    val summary = if (combined)
        dungeonTrackerLifetimeSummary(archive, legacy, filter.floor, filter.mode, filter.chest) else selectedSummary
    val savedLoot = if (combined && includeDetails) {
        (summarizeLegacy(floors, filter.chest).second + loot).groupBy { it.item }.map { (item, rows) ->
            TrackerLootRow(item, rows.sumOf { it.count }, if (rows.any { it.value == null }) null else rows.sumOf { it.value!! },
                if (rows.any { it.acquiredChests == null }) null else rows.sumOf { it.acquiredChests!! }, rows.sumOf { it.sampledChests })
        }
    } else loot
    return TrackerView(summary, sortedLoot(sampledLoot(savedLoot), filter), events, false, inheritedProfit, trackedLifetimeProfit, historicalRuns, historicalChests)
}

/** Frozen aggregates and v2 receipts describe separate periods; v1 receipts already belong to the aggregates. */
fun dungeonTrackerLifetimeSummary(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, floor: String? = null,
    mode: String? = null, chest: String? = null): TrackerSummary {
    val filter = TrackerFilter(floor = floor, mode = mode, chest = chest, scope = TrackerScope.ALL_LOCAL_PROFILES)
    val floors = legacy.filter { filter.floorMatches(it.floor) }
    val historical = summarizeLegacy(floors, chest).first
    val current = summarizeRuns(filter.runs(archive.runs, null), chest, false).first
    val preserved = if (chest == null) {
        archive.legacyProfit.filterKeys(filter::floorMatches).values.sum() +
            floors.filter { it.floor !in archive.legacyProfit }.sumOf { summarizeLegacy(listOf(it), null).first.knownNet }
    } else historical.knownNet
    val missingHistorical = chest == null && archive.legacyProfit.keys.any { filter.floorMatches(it) && floors.none { floor -> floor.floor == it } }
    return TrackerSummary(historical.runs + current.runs, historical.time + current.time, historical.timedRuns + current.timedRuns,
        historical.gross + current.gross, historical.chestCost + current.chestCost,
        if (historical.rerollCost == null || current.rerollCost == null) null else historical.rerollCost + current.rerollCost,
        historical.cataXp + current.cataXp, mergeXp(listOf(historical.classXp, current.classXp)),
        historical.chests + current.chests, historical.rerolls + current.rerolls,
        historical.incompleteValues || current.incompleteValues, historical.incompleteCosts || current.incompleteCosts,
        historical.knownRerollCost + current.knownRerollCost, preserved - historical.knownNet,
        !missingHistorical && historical.profitTimingComplete && current.profitTimingComplete,
        historical.xpTimingComplete && current.xpTimingComplete)
}

private fun summarizeLegacy(floors: List<LegacyFloorView>, chest: String?): Pair<TrackerSummary, List<TrackerLootRow>> {
    val selected = floors.flatMap { floor -> floor.loot.filterKeys { chest == null || chest == it }.values.flatten() }
    val denominator = floors.sumOf { floor -> floor.chestCounts.filterKeys { chest == null || chest == it }.values.sum() }
    val loot = selected.groupBy { it.item }.map { (item, rows) -> TrackerLootRow(item, rows.sumOf { it.count },
        rows.sumOf { it.value ?: 0.0 }, null, denominator) }
    val summaries = floors.map { it.summary }
    return TrackerSummary(summaries.sumOf { it.runs }, summaries.sumOf { it.time }, summaries.sumOf { it.timedRuns },
        loot.sumOf { it.value ?: 0.0 }, floors.sumOf { floor -> floor.chestCosts.filterKeys { chest == null || chest == it }.values.sum() },
        floors.sumOf { floor -> floor.rerollCosts.filterKeys { chest == null || chest == it }.values.sum() },
        summaries.sumOf { it.cataXp }, mergeXp(summaries.map { it.classXp }), denominator, summaries.sumOf { it.rerolls },
        incompleteCosts = summaries.any { it.incompleteCosts }) to loot
}

private fun summarizeRuns(runs: List<DungeonRunRecord>, chest: String?, includeLoot: Boolean): Pair<TrackerSummary, List<TrackerLootRow>> {
    val receipts = runs.flatMap { it.chests }.filter { chest == null || chest == it.chest }
    val loot = if (includeLoot) receipts.flatMap { chest -> chest.loot.map { it to chest.id } }.groupBy { it.first.item }.map { (item, entries) ->
        TrackerLootRow(item, entries.sumOf { it.first.count.toLong() }, if (entries.any { it.first.value == null }) null else entries.sumOf { it.first.value!! }, entries.map { it.second }.distinct().size, receipts.size)
    } else emptyList()
    val timed = runs.filter { it.completedAt != null && it.duration != null && it.duration > 0 }
    fun DungeonRunRecord.timed() = completedAt != null && duration != null && duration > 0
    val rerollsKnown = runs.none { (it.rerolls > 0 || (it.rerollCost ?: 0.0) > 0) && (chest != null || it.rerollCost == null) }
    val knownRerollCost = if (chest == null) runs.sumOf { it.rerollCost ?: 0.0 } else 0.0
    return TrackerSummary(runs.count { it.completedAt != null }.toLong(), timed.sumOf { it.duration!! }, timed.size.toLong(),
        receipts.sumOf { receipt -> receipt.loot.sumOf { it.value ?: 0.0 } }, receipts.sumOf { it.cost ?: 0.0 }, if (rerollsKnown) knownRerollCost else null,
        runs.sumOf { it.cataXp ?: 0.0 }, mergeXp(runs.map { it.classXp }), receipts.size, runs.sumOf { it.rerolls }, receipts.any { receipt -> receipt.loot.any { it.value == null } }, receipts.any { it.cost == null },
        knownRerollCost = knownRerollCost,
        profitTimingComplete = runs.none { run -> !run.timed() && (run.rerolls > 0 || (run.rerollCost ?: 0.0) > 0 || run.chests.any { chest == null || chest == it.chest }) },
        xpTimingComplete = runs.none { !it.timed() && (it.cataXp ?: 0.0) > 0 }) to loot
}
private fun mergeXp(values: List<Map<String, Double>>) = values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

/** Captured on the client thread; immutable primitives are safe for background queries. */
fun legacyTrackerView(totals: Map<DungeonFloor, FloorTracker> = DungeonTracker.profitData): List<LegacyFloorView> = totals.map { (floor, data) ->
    fun Double.safe() = takeIf { it.isFinite() && it >= 0 } ?: 0.0
    LegacyFloorView(floor.name, TrackerSummary(data.totalRuns.coerceAtLeast(0).toLong(), if (data.invalidTime) 0 else data.totalTimeMillis,
        data.timedRunCount.toLong(), cataXp = data.totalXp.safe(),
        classXp = data.classXp.mapKeys { it.key.name }.mapValues { it.value.safe() }, rerolls = data.totalRerolled.coerceAtLeast(0),
        incompleteCosts = data.incompleteCosts),
        data.chests.mapKeys { it.key.name }.mapValues { (_, chest) -> chest.trackedItems.map { (id, loot) ->
            TrackerLootRow(canonicalItemId(id.id), loot.count.coerceAtLeast(0).toLong(), loot.value.safe(), null, chest.chestCount.coerceAtLeast(0)) } },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.rerollCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCount.coerceAtLeast(0) })
}
