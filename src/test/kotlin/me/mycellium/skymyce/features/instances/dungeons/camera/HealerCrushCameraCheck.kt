package me.mycellium.skymyce.features.instances.dungeons.camera

private val validContext = CrushEncounterContext(1, true, "F7", true, true, true, true)
private fun readySequence(floor: String = "F7") = HealerCrushSequence().apply {
    context(validContext.copy(floor = floor), 0); stormStarted(1_000)
}

fun main() {
    // The camera builds during the 10 s before the crush view, never before or once it is shown.
    readySequence().let { seq ->
        check(!seq.warming(16_999) && seq.warming(17_000) && seq.warming(26_999)) { "Warm from 16 s into Storm" }
        seq.tick(27_000); check(seq.stage == CrushStage.PURPLE && !seq.warming(27_000))
    }
    check(!HealerCrushSequence().apply { context(validContext.copy(viewpointsReady = false), 0); stormStarted(1_000) }.warming(20_000)) { "No viewpoints, nothing to build" }
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
    println("Crush Camera class toggles, 26/38-second timing, delayed switching, and Storm cleanup checks passed")
}
