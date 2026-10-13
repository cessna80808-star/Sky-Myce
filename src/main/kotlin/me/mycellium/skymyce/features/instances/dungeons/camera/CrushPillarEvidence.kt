package me.mycellium.skymyce.features.instances.dungeons.camera

data class CrushBlockCell(val x: Int, val y: Int, val z: Int)
data class ObservedCrushBlock(val id: String, val fullCollision: Boolean)
data class CrushFootprintCell(val x: Int, val z: Int)

private fun rectangularCrushFootprint(minX: Int, maxX: Int, minZ: Int, maxZ: Int): List<CrushFootprintCell> =
    if (maxX - minX in 1..8 && maxZ - minZ in 1..8) (minX..maxX).flatMap { x -> (minZ..maxZ).map { CrushFootprintCell(x, it) } } else emptyList()

/** Explicitly observed hanging geometry. It contains no assumed F7 coordinates or default material. */
data class CrushPillarCalibration(val stage: CrushStage, val environment: String, val minX: Int, val maxX: Int,
                                  val minZ: Int, val maxZ: Int, val bottom: Int, val top: Int, val blockId: String,
                                  val footprint: List<CrushFootprintCell> = rectangularCrushFootprint(minX, maxX, minZ, maxZ)) {
    fun valid() = environment.length in 1..240 && environment.none(Char::isISOControl) &&
        minX in -29_999_984..29_999_975 && minZ in -29_999_984..29_999_975 &&
        maxX - minX in 1..8 && maxZ - minZ in 1..8 && bottom in -2016..2016 && top - bottom in 2..63 &&
        observedPillarMaterial(blockId) && footprint.size in 4..81 && footprint.distinct().size == footprint.size &&
        footprint.all { it.x in minX..maxX && it.z in minZ..maxZ } &&
        footprint.minOf { it.x } == minX && footprint.maxOf { it.x } == maxX &&
        footprint.minOf { it.z } == minZ && footprint.maxOf { it.z } == maxZ
    fun contains(cell: CrushBlockCell) = cell.x in minX..maxX && cell.z in minZ..maxZ && cell.y in bottom - 32..top &&
        CrushFootprintCell(cell.x, cell.z) in footprint
    fun bounds(lower: Int, upper: Int) = CrushBounds(minX.toDouble(), lower.toDouble(), minZ.toDouble(),
        maxX + 1.0, upper + 1.0, maxZ + 1.0)
    fun intersects(bounds: CrushBounds, lower: Int, upper: Int) = footprint.any {
        CrushBounds(it.x.toDouble(), lower.toDouble(), it.z.toDouble(), it.x + 1.0, upper + 1.0, it.z + 1.0).intersects(bounds)
    }
}

// Pinned primary Noamm IHateDiorite source identifies these server blocks; its colored glass is a client replacement.
// Stage/color is assigned explicitly by the user, never inferred from a material or upstream coordinates.
internal fun observedPillarMaterial(id: String): Boolean = id in setOf("minecraft:diorite", "minecraft:polished_diorite")

/** Null reads mean unloaded, and invalidate rather than silently treating a missing chunk as air. */
fun captureCrushPillar(stage: CrushStage, environment: String, hit: CrushBlockCell,
                       read: (CrushBlockCell) -> ObservedCrushBlock?): CrushPillarCalibration? {
    if (hit.x !in -29_999_984..29_999_975 || hit.z !in -29_999_984..29_999_975 || hit.y !in -1952..1952) return null
    val chosen = read(hit)?.takeIf { it.fullCollision && observedPillarMaterial(it.id) } ?: return null
    fun matches(cell: CrushBlockCell) = read(cell)?.let { it.fullCollision && observedPillarMaterial(it.id) }
    var bottom = hit.y; var top = hit.y
    while (bottom > hit.y - 64 && matches(hit.copy(y = bottom - 1)) == true) bottom--
    while (top < hit.y + 64 && matches(hit.copy(y = top + 1)) == true) top++
    if (top - bottom !in 2..63 || matches(hit.copy(y = bottom - 1)) == null || matches(hit.copy(y = top + 1)) == null) return null
    val cells = linkedSetOf(hit.copy(y = bottom)); val pending = ArrayDeque(cells)
    while (pending.isNotEmpty()) {
        val cell = pending.removeFirst()
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val next = cell.copy(x = cell.x + dx, z = cell.z + dz)
            val present = matches(next) ?: return null
            if (present && cells.add(next)) { if (cells.size > 81) return null; pending.addLast(next) }
        }
    }
    val value = CrushPillarCalibration(stage, environment, cells.minOf { it.x }, cells.maxOf { it.x },
        cells.minOf { it.z }, cells.maxOf { it.z }, bottom, top, chosen.id, cells.map { CrushFootprintCell(it.x, it.z) })
    if (!value.valid()) return null
    for (cell in value.footprint) {
        for (y in bottom..top) if (matches(CrushBlockCell(cell.x, y, cell.z)) != true) return null
        // A flat control pad or a column resting on the floor is not hanging crush geometry.
        for (y in bottom - 2 until bottom) if (read(CrushBlockCell(cell.x, y, cell.z))?.fullCollision != false) return null
    }
    return value
}

data class PillarColumnObservation(val bottom: Int, val top: Int, val serverRevision: Long, val observedAt: Long,
                                   val stormId: String, val stormBounds: CrushBounds, val crushAt: Long?, val crushSerial: Long,
                                   val serverChangedAt: Long = observedAt)
data class CrushEvidence(val underway: Boolean, val confirmationId: String? = null)
internal fun ambiguousCrushEvidence(evidence: Map<CrushStage, CrushEvidence>) = evidence.values.count { it.underway } > 1

/** Uses measured server block descent/collision AND a fresh exact server crush notice, never generic damage. */
class CrushPillarObserver(private val calibration: CrushPillarCalibration) {
    private var baselineSeen = false
    private var previous: PillarColumnObservation? = null
    private var descendingAt: Long? = null
    private var collisionAt: Long? = null
    private var collisionRevision = 0L
    private var boss: String? = null
    private var confirmed = false

    fun observe(sample: PillarColumnObservation): CrushEvidence {
        if (confirmed || !calibration.valid() || !sample.stormBounds.valid() || sample.stormId.isBlank() ||
            sample.top - sample.bottom !in 2..63 || sample.bottom !in calibration.bottom - 32..calibration.bottom ||
            sample.top > calibration.top || sample.serverRevision < 0 || sample.observedAt < 0 ||
            sample.serverChangedAt !in 0..sample.observedAt) return CrushEvidence(false)
        val last = previous
        if (last != null && (sample.observedAt < last.observedAt || sample.serverRevision < last.serverRevision)) return CrushEvidence(false)
        if (!baselineSeen) {
            if (sample.bottom == calibration.bottom && sample.top == calibration.top) { baselineSeen = true; previous = sample }
            return CrushEvidence(false)
        }
        if (boss != null && boss != sample.stormId) { collisionAt = null; descendingAt = null; previous = sample; return CrushEvidence(false) }
        val atPillar = calibration.intersects(sample.stormBounds, calibration.bottom - 32, calibration.top)
        if (last != null && sample.bottom < last.bottom && sample.serverRevision > last.serverRevision && atPillar) {
            boss = sample.stormId; descendingAt = sample.serverChangedAt
            if (calibration.intersects(sample.stormBounds, sample.bottom, sample.top)) {
                collisionAt = sample.observedAt; collisionRevision = sample.serverRevision
            }
        }
        previous = sample
        val moving = descendingAt?.let { sample.observedAt - it in 0..2_000L } == true && atPillar
        val collision = collisionAt
        val notice = sample.crushAt
        if (moving && collision != null && notice != null && notice >= (descendingAt ?: Long.MAX_VALUE) - 1_000L &&
            sample.observedAt - collision in 0..1_000L && sample.observedAt - notice in 0..1_000L &&
            calibration.intersects(sample.stormBounds, sample.bottom, sample.top) && sample.crushSerial > 0) {
            confirmed = true
            return CrushEvidence(true, "${calibration.stage}:${sample.stormId}:$collisionRevision:${sample.crushSerial}")
        }
        return CrushEvidence(moving)
    }
}

/** Read only the saved footprint; complete solid slices distinguish it from isolated debris. */
fun observedCrushColumn(value: CrushPillarCalibration, read: (CrushBlockCell) -> ObservedCrushBlock?): Pair<Int, Int>? {
    if (!value.valid()) return null
    var bottom: Int? = null; var top: Int? = null; var finished = false
    for (y in value.bottom - 32..value.top) {
        var complete = true
        for (cell in value.footprint) {
            val block = read(CrushBlockCell(cell.x, y, cell.z)) ?: return null
            if (!block.fullCollision || !observedPillarMaterial(block.id)) complete = false
        }
        if (complete) {
            if (finished) return null
            if (bottom == null) bottom = y
            top = y
        } else if (bottom != null) finished = true
    }
    return if (bottom != null && top != null && top - bottom >= 2) bottom to top else null
}
