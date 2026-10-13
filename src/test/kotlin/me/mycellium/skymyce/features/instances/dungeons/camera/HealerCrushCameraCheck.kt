package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonParser

private val validContext = CrushEncounterContext(1, true, "F7", true, true, true, true)
private val berserker = LeapDestination("ActualBerserker", "00000000-0000-0000-0000-000000000001", true, true, CrushPoint(11.0, 43.0, 21.0))
private fun readySequence(floor: String = "F7") = HealerCrushSequence().apply {
    context(validContext.copy(floor = floor), 0); stormStarted(1_000)
}
private fun HealerCrushSequence.realLeap(time: Long = 1_100) {
    leapSucceeded(berserker.name, time, berserker)
    positionCorrected(1, CrushPoint(0.0, 43.0, 21.0), berserker.point, time + 50, berserker)
}

fun main() {
    check(successfulLeapName("You have teleported to ActualBerserker!") == berserker.name)
    check(successfulLeapName("Party > Friend: You have teleported to ActualBerserker!") == null)
    check(successfulLeapName("You have teleported to ActualBerserker! trailing") == null)
    check(successfulLeapName("You have teleported to bad-name!") == null)
    val messages = StormCrushMessages()
    check(messages.accept("Party > Friend: [BOSS] Storm: Oof", 0) == null)
    check(messages.accept("[BOSS] Storm: Oof!", 0) == null)
    check(messages.accept("[BOSS] Storm: Oof", 100)?.serial == 1L)
    check(messages.accept("[BOSS] Storm: Ouch, that hurt!", 120) == null)
    check(messages.accept("[BOSS] Storm: Oof", 600) == null)
    check(messages.accept("[BOSS] Storm: Ouch, that hurt!", 15_000)?.serial == 2L)
    messages.reset(); check(messages.latest == null)
    for (floor in listOf("F7", "M7")) {
        val sequence = readySequence(floor)
        sequence.tick(1_100); sequence.underway(CrushStage.PURPLE, 1_200)
        check(sequence.stage == null) // Phase/time/pillar alone cannot substitute for a successful leap.
        sequence.realLeap()
        sequence.stormStarted(1_160) // Duplicate phase entry must not erase verified leap evidence.
        sequence.underway(CrushStage.YELLOW, 1_200); check(sequence.stage == null)
        sequence.underway(CrushStage.PURPLE, 1_200); check(sequence.stage == CrushStage.PURPLE)
        sequence.confirmed(CrushStage.PURPLE, "purple:collision", 1_200); check(sequence.stage == CrushStage.PURPLE)
        sequence.confirmed(CrushStage.PURPLE, "purple:collision", 1_210); check(sequence.stage == CrushStage.YELLOW)
        sequence.confirmed(CrushStage.PURPLE, "purple:collision", 1_220); check(sequence.stage == CrushStage.YELLOW)
        sequence.confirmed(CrushStage.YELLOW, "yellow:collision", 1_210); check(sequence.stage == CrushStage.YELLOW)
        sequence.confirmed(CrushStage.YELLOW, "yellow:collision", 1_230); check(sequence.stage == null)
        sequence.realLeap(1_300); sequence.underway(CrushStage.PURPLE, 1_400); check(sequence.stage == null)
        sequence.stormStarted(1_500); sequence.realLeap(1_600); sequence.underway(CrushStage.PURPLE, 1_700); check(sequence.stage == null)
        sequence.reset(); sequence.context(validContext.copy(floor = floor), 2_000); sequence.stormStarted(2_000)
        sequence.realLeap(2_100); sequence.underway(CrushStage.PURPLE, 2_200); check(sequence.stage == CrushStage.PURPLE)
    }
    for (destination in listOf(berserker.copy(berserker = false), berserker.copy(alive = false), berserker.copy(name = "Other"))) {
        val sequence = readySequence()
        sequence.leapSucceeded(berserker.name, 1_100, destination)
        sequence.positionCorrected(1, CrushPoint(0.0, 43.0, 21.0), berserker.point, 1_150, destination)
        sequence.underway(CrushStage.PURPLE, 1_200); check(sequence.stage == null)
    }
    for (to in listOf(CrushPoint(0.0, 43.0, 21.0), CrushPoint(100.0, 43.0, 21.0), CrushPoint(Double.NaN, 43.0, 21.0))) {
        val sequence = readySequence()
        sequence.leapSucceeded(berserker.name, 1_100, berserker)
        sequence.positionCorrected(1, CrushPoint(0.0, 43.0, 21.0), to, 1_150, berserker)
        sequence.underway(CrushStage.PURPLE, 1_200); check(sequence.stage == null)
    }
    val correctionFirst = readySequence()
    correctionFirst.positionCorrected(3, CrushPoint(0.0, 43.0, 21.0), berserker.point, 1_050, null)
    correctionFirst.leapSucceeded(berserker.name, 1_100, berserker)
    correctionFirst.underway(CrushStage.PURPLE, 1_200); check(correctionFirst.stage == CrushStage.PURPLE)
    val tooLate = readySequence()
    tooLate.leapSucceeded(berserker.name, 1_100, berserker)
    tooLate.positionCorrected(1, CrushPoint(0.0, 43.0, 21.0), berserker.point, 4_000, berserker)
    tooLate.underway(CrushStage.PURPLE, 4_100); check(tooLate.stage == null)
    val expired = readySequence().apply { realLeap(); tick(31_151); underway(CrushStage.PURPLE, 31_200) }
    check(expired.stage == null)
    val timeout = readySequence().apply { realLeap(); underway(CrushStage.PURPLE, 1_200); tick(61_201) }
    check(timeout.stage == null); timeout.realLeap(61_300); timeout.underway(CrushStage.PURPLE, 61_400); check(timeout.stage == null)
    for (lost in listOf(validContext.copy(alive = false), validContext.copy(healer = false), validContext.copy(world = 2),
        validContext.copy(inBoss = false), validContext.copy(enabled = false), validContext.copy(floor = "F6"), validContext.copy(calibrated = false))) {
        val sequence = readySequence().apply { realLeap(); underway(CrushStage.PURPLE, 1_200); context(lost, 1_250) }
        check(sequence.stage == null)
    }
    val world = linkedMapOf<CrushBlockCell, ObservedCrushBlock>()
    val purple = ObservedCrushBlock("minecraft:diorite", true)
    val air = ObservedCrushBlock("minecraft:air", false)
    fun install(bottom: Int, top: Int) {
        world.clear()
        for (x in 10..12) for (z in 20..22) for (y in bottom..top) world[CrushBlockCell(x, y, z)] = purple
    }
    install(50, 58)
    world[CrushBlockCell(11, 55, 21)] = ObservedCrushBlock("minecraft:polished_diorite", true)
    val calibration = captureCrushPillar(CrushStage.PURPLE, "validated-test-world", CrushBlockCell(11, 55, 21)) { world[it] ?: air }!!
    check(calibration.minX == 10 && calibration.maxZ == 22 && calibration.bottom == 50 && calibration.top == 58)
    check(observedCrushColumn(calibration) { world[it] ?: air } == 50 to 58)
    check(captureCrushPillar(CrushStage.YELLOW, "test", CrushBlockCell(11, 55, 21)) { world[it] ?: air }?.stage == CrushStage.YELLOW)
    check(captureCrushPillar(CrushStage.PURPLE, "test", CrushBlockCell(11, 55, 21)) { if (it.x == 12) null else world[it] ?: air } == null)
    install(50, 58)
    for (y in 50..58) world.remove(CrushBlockCell(10, y, 20)) // Real measured nonrectangular cross-section.
    val irregular = captureCrushPillar(CrushStage.PURPLE, "test", CrushBlockCell(11, 55, 21)) { world[it] ?: air }!!
    check(irregular.footprint.size == 8 && observedCrushColumn(irregular) { world[it] ?: air } == 50 to 58)
    install(50, 50) // Flat color pad is not a hanging pillar.
    check(captureCrushPillar(CrushStage.PURPLE, "test", CrushBlockCell(11, 50, 21)) { world[it] ?: air } == null)
    install(50, 58); world[CrushBlockCell(10, 54, 20)] = air
    check(captureCrushPillar(CrushStage.PURPLE, "test", CrushBlockCell(11, 55, 21)) { world[it] ?: air } == null)
    val decoded = parseCrushPillar(encodeCrushPillar(calibration))!!
    check(decoded == calibration)
    check(parseCrushPillar(encodeCrushPillar(calibration).apply { addProperty("bottom", 50.5) }) == null)
    check(parseCrushPillar(encodeCrushPillar(calibration).apply { addProperty("blockId", "minecraft:purple_wool") }) == null)
    check(parseCrushPillar(JsonParser.parseString("{\"stage\":\"PURPLE\"}")) == null)
    val boss = CrushBounds(10.5, 44.0, 20.5, 12.5, 47.0, 22.5)
    fun sample(bottom: Int, revision: Long, time: Long, notice: Long? = null, id: String = "observed-Storm", box: CrushBounds = boss) =
        PillarColumnObservation(bottom, bottom + 8, revision, time, id, box, notice, if (notice == null) 0 else 1)
    val observer = CrushPillarObserver(calibration)
    check(!observer.observe(sample(50, 0, 0)).underway)
    check(observer.observe(sample(49, 1, 100)) == CrushEvidence(true))
    check(observer.observe(sample(46, 2, 200)).confirmationId == null) // Block descent/collision alone is insufficient.
    check(observer.observe(sample(46, 2, 250, 240)).confirmationId != null)
    check(!observer.observe(sample(46, 2, 260, 240)).underway) // One physical crush emits one confirmation.
    val noServerChange = CrushPillarObserver(calibration)
    noServerChange.observe(sample(50, 0, 0)); check(!noServerChange.observe(sample(46, 0, 100, 100)).underway)
    val staleNotice = CrushPillarObserver(calibration)
    staleNotice.observe(sample(50, 0, 0)); check(staleNotice.observe(sample(46, 1, 2_000, 500)).confirmationId == null)
    val missedBaseline = CrushPillarObserver(calibration)
    check(!missedBaseline.observe(sample(46, 1, 100, 100)).underway)
    val chatBeforeSnapshot = CrushPillarObserver(calibration)
    chatBeforeSnapshot.observe(sample(50, 0, 0))
    check(chatBeforeSnapshot.observe(sample(46, 1, 300, 100).copy(serverChangedAt = 150)).confirmationId != null)
    val genericDamageIsInsufficient = CrushPillarObserver(calibration)
    genericDamageIsInsufficient.observe(sample(50, 0, 0))
    check(genericDamageIsInsufficient.observe(sample(46, 1, 100)).confirmationId == null)
    // A large boss intersecting two independently moving, adjacent measured pillars is ambiguous.
    val adjacent = calibration.copy(stage = CrushStage.YELLOW, minX = 13, maxX = 15,
        footprint = calibration.footprint.map { it.copy(x = it.x + 3) })
    val broadBoss = boss.copy(maxX = 14.0)
    val purpleAmbiguous = CrushPillarObserver(calibration)
    val yellowAmbiguous = CrushPillarObserver(adjacent)
    purpleAmbiguous.observe(sample(50, 0, 0, box = broadBoss)); yellowAmbiguous.observe(sample(50, 0, 0, box = broadBoss))
    val both = mapOf(CrushStage.PURPLE to purpleAmbiguous.observe(sample(46, 1, 100, 100, box = broadBoss)),
        CrushStage.YELLOW to yellowAmbiguous.observe(sample(46, 1, 100, 100, box = broadBoss)))
    check(ambiguousCrushEvidence(both))
    check(!ambiguousCrushEvidence(mapOf(CrushStage.PURPLE to both.getValue(CrushStage.PURPLE))))
    val wrongBoss = CrushPillarObserver(calibration)
    wrongBoss.observe(sample(50, 0, 0)); wrongBoss.observe(sample(49, 1, 100))
    check(wrongBoss.observe(sample(46, 2, 200, 200, "different-Wither")).confirmationId == null)
    val notAtPillar = CrushPillarObserver(calibration)
    notAtPillar.observe(sample(50, 0, 0))
    check(!notAtPillar.observe(sample(46, 1, 100, 100, box = boss.copy(minX = 100.0, maxX = 102.0))).underway)
    install(46, 54)
    check(observedCrushColumn(calibration) { world[it] ?: air } == 46 to 54)
    world[CrushBlockCell(10, 48, 20)] = air
    check(observedCrushColumn(calibration) { world[it] ?: air } == null)
    println("Healer crush sequence, measured pillar evidence, leap correlation, and reset checks passed")
}
