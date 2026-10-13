package me.mycellium.skymyce.features.instances.dungeons.camera

import com.google.gson.JsonParser

private val validContext = CrushEncounterContext(1, true, "F7", true, true, true, true)
private fun readySequence(floor: String = "F7") = HealerCrushSequence().apply {
    context(validContext.copy(floor = floor), 0); stormStarted(1_000)
}

fun main() {
    val settings = me.mycellium.skymyce.config.instances.dungeons.HealerCrushCameraConfig
    val classes = tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.entries
    check(settings.classEnabled(null) == false)
    check(classes.filter(settings::classEnabled) == listOf(tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.HEALER))
    settings.healer = false
    val toggles = listOf<(Boolean) -> Unit>(
        { settings.healer = it }, { settings.mage = it }, { settings.berserk = it }, { settings.archer = it }, { settings.tank = it })
    val selected = listOf("HEALER", "MAGE", "BERSERKER", "ARCHER", "TANK")
    toggles.forEachIndexed { index, toggle ->
        toggle(true)
        check(classes.filter(settings::classEnabled).map { it.name } == listOf(selected[index]))
        toggle(false); check(classes.none(settings::classEnabled))
    }
    settings.healer = true
    val messages = StormCrushMessages()
    check(messages.accept("Party > Friend: [BOSS] Storm: Oof", 0) == null)
    check(messages.accept("[BOSS] Storm: Oof!", 0) == null)
    check(messages.accept("[BOSS] Storm: Oof", 100)?.serial == 1L)
    check(messages.accept("[BOSS] Storm: Ouch, that hurt!", 120) == null)
    check(messages.accept("[BOSS] Storm: Oof", 600) == null)
    check(messages.accept("[BOSS] Storm: Ouch, that hurt!", 15_000)?.serial == 2L)
    messages.reset(); check(messages.latest == null)
    check("[BOSS] Storm: I should have known that I stood no chance." in STORM_END)
    check("[BOSS] Goldor: Who dares trespass into my domain?" in STORM_END)
    for (floor in listOf("F7", "M7")) {
        val sequence = readySequence(floor)
        sequence.tick(26_999); check(sequence.stage == null)
        sequence.stormStarted(26_999) // Duplicate entry cannot restart the timer.
        sequence.tick(27_000); check(sequence.stage == CrushStage.PURPLE)
        sequence.crushed(1, 27_100); check(sequence.stage == CrushStage.PURPLE)
        sequence.crushed(1, 27_200); check(sequence.stage == CrushStage.PURPLE)
        sequence.crushed(2, 28_000); check(sequence.stage == CrushStage.PURPLE)
        sequence.tick(28_099); check(sequence.stage == CrushStage.PURPLE)
        sequence.tick(28_100); check(sequence.stage == CrushStage.YELLOW)
        sequence.crushed(3, 28_200); check(sequence.stage == CrushStage.YELLOW)
        sequence.context(validContext.copy(floor = floor), 400_000); check(sequence.stage == CrushStage.YELLOW)
        sequence.reset("Storm died"); check(sequence.stage == null && !sequence.stormActive)
        sequence.tick(500_000); check(sequence.stage == null)
        sequence.stormStarted(500_000); sequence.tick(526_000); check(sequence.stage == CrushStage.PURPLE)
        sequence.tick(537_999); check(sequence.stage == CrushStage.PURPLE)
        sequence.tick(538_000); check(sequence.stage == CrushStage.YELLOW)
        sequence.crushed(1, 538_100); check(sequence.stage == CrushStage.YELLOW)
    }
    val earlyCrush = readySequence().apply { crushed(1, 20_000); tick(26_999) }
    check(earlyCrush.stage == null)
    earlyCrush.tick(27_000); check(earlyCrush.stage == CrushStage.YELLOW)
    val lateCrush = readySequence().apply { tick(38_500); crushed(1, 38_500) }
    lateCrush.tick(39_000); check(lateCrush.stage == CrushStage.PURPLE)
    lateCrush.tick(39_499); check(lateCrush.stage == CrushStage.PURPLE)
    lateCrush.tick(39_500); check(lateCrush.stage == CrushStage.YELLOW)
    val endedDuringDelay = readySequence().apply { crushed(1, 27_000); reset("Storm died"); tick(28_000) }
    check(endedDuringDelay.stage == null && !endedDuringDelay.stormActive)
    val unconfigured = readySequence().apply { context(validContext.copy(viewpointsReady = false), 27_000) }
    check(unconfigured.stage == null && unconfigured.stormActive)
    unconfigured.context(validContext, 28_000); check(unconfigured.stage == CrushStage.PURPLE)
    for (lost in listOf(validContext.copy(alive = false), validContext.copy(classEnabled = false), validContext.copy(world = 2),
        validContext.copy(inBoss = false), validContext.copy(enabled = false), validContext.copy(floor = "F6"))) {
        val sequence = readySequence().apply { tick(27_000); context(lost, 27_250) }
        check(sequence.stage == null && !sequence.stormActive)
        sequence.context(validContext, 28_000); check(sequence.stage == null)
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
    println("Crush Camera class toggles, 26/38-second timing, delayed switching, Storm cleanup and measured pillar checks passed")
}
