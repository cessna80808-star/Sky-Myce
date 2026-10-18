package me.mycellium.skymyce.features.instances.dungeons.goldor

import kotlin.math.sqrt

/** A block position in the F7/M7 boss room; identical on both floors. */
data class BossPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSq(px: Double, py: Double, pz: Double) = (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz)
    fun distance(other: BossPoint) = sqrt(distanceSq(other.x, other.y, other.z))
}

/**
 * One objective on the map. [mapX]/[mapY] are pixels on the section's 96×296 map image; [points] are where the
 * objective is used in the world, for position-assisted completion matching. Devices have a single stand point.
 */
data class Station(val objective: ObjectiveId, val mapX: Int, val mapY: Int, val points: List<BossPoint>)

/**
 * The fixed Goldor geometry: terminal and lever coordinates and map positions from DungeonTerminalMap (MIT,
 * © 2026 MisterKrister), device and early-enter stand points from Devonian `PositionMessages` (verified in the
 * 2026-10-17 leap research). Terminal numbers follow the ADG convention documented in `GOLDOR-COORDINATION.md`.
 */
object GoldorLayout {
    /** Completion matching radius around a station, as in DungeonTerminalMap. */
    const val MATCH_RADIUS = 5.0
    const val MAP_WIDTH = 96
    const val MAP_HEIGHT = 296

    private fun p(x: Number, y: Number, z: Number) = BossPoint(x.toDouble(), y.toDouble(), z.toDouble())
    private fun t(section: GoldorSection, n: Int, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.terminal(section, n), mx, my, points.toList())
    private fun l(section: GoldorSection, side: LeverSide, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.lever(section, side), mx, my, points.toList())
    private fun d(section: GoldorSection, mx: Int, my: Int, vararg points: BossPoint) = Station(ObjectiveId.device(section), mx, my, points.toList())

    val stations: Map<GoldorSection, List<Station>> = mapOf(
        GoldorSection.S1 to listOf(
            t(GoldorSection.S1, 1, 6, 204, p(111, 113, 73), p(110, 113, 73)),
            t(GoldorSection.S1, 2, 6, 174, p(111, 119, 79), p(110, 119, 79)),
            t(GoldorSection.S1, 3, 85, 120, p(89, 112, 92), p(90, 112, 92)),
            t(GoldorSection.S1, 4, 85, 87, p(89, 122, 101), p(90, 122, 101)),
            // Simon Says.
            d(GoldorSection.S1, 6, 116, p(108.5, 120, 94.5)),
            l(GoldorSection.S1, LeverSide.LEFT, 23, 35, p(94, 124, 113), p(94, 125, 113)),
            l(GoldorSection.S1, LeverSide.RIGHT, 72, 35, p(106, 124, 113), p(106, 125, 113)),
        ),
        GoldorSection.S2 to listOf(
            t(GoldorSection.S2, 1, 85, 208, p(68, 109, 121), p(68, 109, 122)),
            t(GoldorSection.S2, 2, 82, 168, p(59, 120, 122), p(59, 119, 123)),
            t(GoldorSection.S2, 3, 85, 129, p(47, 109, 121), p(47, 109, 122)),
            t(GoldorSection.S2, 4, 85, 95, p(40, 124, 122), p(40, 124, 123)),
            t(GoldorSection.S2, 5, 15, 83, p(39, 108, 143), p(39, 108, 142)),
            // The lights device, reached from the EE2 high spot.
            d(GoldorSection.S2, 8, 173, p(60.5, 132, 140.5)),
            l(GoldorSection.S2, LeverSide.LEFT, 22, 18, p(23, 132, 138), p(23, 133, 138)),
            l(GoldorSection.S2, LeverSide.RIGHT, 68, 35, p(27, 124, 127), p(27, 125, 127)),
        ),
        GoldorSection.S3 to listOf(
            t(GoldorSection.S3, 1, 12, 254, p(-3, 109, 112), p(-2, 109, 112)),
            t(GoldorSection.S3, 2, 14, 175, p(-3, 119, 93), p(-2, 119, 93)),
            t(GoldorSection.S3, 3, 76, 158, p(19, 123, 93), p(18, 123, 93)),
            t(GoldorSection.S3, 4, 16, 95, p(-3, 109, 77), p(-2, 109, 77)),
            // Arrows (target practice) device.
            d(GoldorSection.S3, 16, 122, p(1.5, 120, 77.5)),
            l(GoldorSection.S3, LeverSide.LEFT, 23, 19, p(2, 122, 55), p(2, 123, 55)),
            l(GoldorSection.S3, LeverSide.RIGHT, 73, 19, p(14, 122, 55), p(14, 123, 55)),
        ),
        GoldorSection.S4 to listOf(
            t(GoldorSection.S4, 1, 10, 202, p(41, 109, 29), p(41, 109, 30)),
            t(GoldorSection.S4, 2, 10, 185, p(44, 121, 29), p(44, 121, 30)),
            t(GoldorSection.S4, 3, 14, 99, p(67, 109, 29), p(67, 109, 30)),
            t(GoldorSection.S4, 4, 76, 72, p(72, 115, 48), p(72, 114, 47)),
            // i4, the arrow device; Berserker usually does it before S1 ("pre-dev").
            d(GoldorSection.S4, 28, 110, p(63.5, 127, 35.5)),
            // The right lever is the top one (y 128); the left one is the bottom lever (y 121).
            l(GoldorSection.S4, LeverSide.LEFT, 23, 23, p(84, 121, 34), p(84, 122, 34)),
            l(GoldorSection.S4, LeverSide.RIGHT, 73, 15, p(86, 128, 46), p(86, 129, 46)),
        ),
    )

    /** Where each early-enter route stands once it is in. */
    val routePoints: Map<EarlyEnter, BossPoint> = mapOf(
        EarlyEnter.MEE2 to p(58.5, 109, 131.5),
        EarlyEnter.HEE3 to p(2.5, 109, 104.5),
        EarlyEnter.MEE4 to p(54.5, 115, 51.5),
    )
    /** How close a player must be to a route point to count as in. */
    const val ROUTE_RADIUS = 3.0

    fun station(objective: ObjectiveId): Station? = stations[objective.section]?.firstOrNull { it.objective == objective }
    fun stations(section: GoldorSection): List<Station> = stations[section].orEmpty()

    /** The count Hypixel shows as `(n/N)` for a section: every terminal, the device and both levers. */
    fun total(section: GoldorSection) = stations(section).size

    /** The section whose objective count matches a `(n/N)` total; S2 is the only section with 8. */
    fun sectionsWithTotal(total: Int) = GoldorSection.entries.filter { it != GoldorSection.CORE && total(it) == total }

    /** The open station of [kind] nearest to a position within [MATCH_RADIUS], or null. */
    fun nearest(section: GoldorSection, kind: ObjectiveKind, x: Double, y: Double, z: Double, exclude: Set<ObjectiveId>): Station? =
        stations(section).asSequence().filter { it.objective.kind == kind && it.objective !in exclude && it.points.isNotEmpty() }
            .map { station -> station to station.points.minOf { it.distanceSq(x, y, z) } }
            .filter { it.second <= MATCH_RADIUS * MATCH_RADIUS }
            .minByOrNull { it.second }?.first
}
