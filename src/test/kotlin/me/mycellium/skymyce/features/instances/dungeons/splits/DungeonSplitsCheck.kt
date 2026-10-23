package me.mycellium.skymyce.features.instances.dungeons.splits

import me.mycellium.skymyce.features.instances.dungeons.splits.SplitMilestone.*

private const val MS = 1_000_000L

private fun run(id: String, floor: String, recordedAt: Long, marks: Map<SplitMilestone, Long>, outcome: SplitOutcome = SplitOutcome.COMPLETED,
                start: Boolean = true, conflicts: Int = 0) =
    SplitRunRecord(id, floor, 1, recordedAt, start, (if (start) mapOf(START to 0L) else emptyMap<SplitMilestone, Long>()) + marks, outcome, conflicts)

/** Marks for a fully split run from consecutive section durations, in chain order. */
private fun chained(definition: SplitDefinition, durations: List<Long>): Map<SplitMilestone, Long> {
    check(durations.size == definition.sections.size)
    var at = 0L
    return definition.sections.mapIndexed { index, section -> at += durations[index]; section.milestone to at }.toMap()
}

private fun checkSignals() {
    check(splitSignal(MORT_START, 7) == SplitSignal.Start)
    check(splitSignal("The BLOOD DOOR has been opened!", 7) == SplitSignal.Milestone(BLOOD_OPEN))
    check(splitSignal("[BOSS] The Watcher: You have proven yourself. You may pass.", 7) == SplitSignal.Milestone(BLOOD_CLEAR))
    check(splitSignal("[BOSS] The Watcher: Things feel a little more roomy now, eh?", 7) == SplitSignal.WatcherLine)
    check(splitSignal("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 7) == SplitSignal.Milestone(BOSS_ENTRY))
    check(splitSignal("[BOSS] Maxor: YOU TRICKED ME!", 7) == SplitSignal.BossLine)
    check(splitSignal("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 3) == null) { "Another floor's boss line is not boss entry" }
    check(splitSignal("[BOSS] Storm: Pathetic Maxor, just like expected.", 7) == SplitSignal.Milestone(MAXOR))
    check(splitSignal("[BOSS] Goldor: Who dares trespass into my domain?", 7) == SplitSignal.Milestone(STORM))
    check(splitSignal("The Core entrance is opening!", 7) == SplitSignal.Milestone(S4))
    check(splitSignal("[BOSS] Necron: You went further than any human before, congratulations.", 7) == SplitSignal.Milestone(GOLDOR))
    check(splitSignal("[BOSS] Necron: All this, for nothing...", 7) == SplitSignal.Milestone(NECRON))
    check(splitSignal("[BOSS] Necron: ARGH!", 7) == SplitSignal.NecronArgh) { "Arms the boss bar check; not his death itself" }
    check(splitSignal("Steve activated a terminal! (3/7)", 7) == SplitSignal.Terminal(3, 7))
    check(splitSignal("Alex_99 completed a device! (8/8)", 7) == SplitSignal.Terminal(8, 8))
    // Relayed player chat cannot impersonate server progress lines.
    check(splitSignal("Party > [MVP+] Steve: Steve activated a terminal! (7/7)", 7) == null)
    check(splitSignal("Steve: Bob activated a terminal! (7/7)", 7) == null)
    check(splitSignal("[BOSS] Hockey Livid: Impossible! How did you figure out which one I was?!", 5) == SplitSignal.Milestone(LIVID_KILL))
    check(splitSignal("[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.", 5) == SplitSignal.Milestone(BOSS_ENTRY))
    check(splitSignal("[BOSS] Livid: I respect you for making it to here, but I'll be your undoing.", 5) == SplitSignal.Milestone(LIVID_DIALOGUE))
    check(splitSignal("[BOSS] Sadan: ENOUGH!", 6) == SplitSignal.Milestone(TERRACOTTAS))
    check(splitSignal("                ☠ Defeated Maxor, Storm, Goldor, and Necron in 05m 21s", 7) == SplitSignal.Defeated)
    check(splitSignal("☠ Defeated Bonzo in 01m 02s (NEW RECORD!)", 1) == SplitSignal.Defeated)
    for (number in 1..7) check(splitSignal(bossEntryLines.getValue(number), number) == SplitSignal.Milestone(BOSS_ENTRY))
}

private fun checkDefinitions() {
    for (number in 1..7) for (mode in listOf("F", "M")) check("$mode$number" in splitDefinitions)
    check("E" !in splitDefinitions)
    val f7 = splitDefinitions.getValue("F7"); val m7 = splitDefinitions.getValue("M7")
    check(listOf(BLOOD_OPEN, BLOOD_CLEAR, BOSS_ENTRY, MAXOR, STORM, S1, S2, S3, S4, GOLDOR, NECRON).all { it in f7.chain })
    check(S1 != S2 && f7.sections.map { it.milestone }.containsAll(listOf(S1, S2, S3, S4))) { "Terminal sections stay independent" }
    check(RELICS !in f7.applicable && RELICS in m7.applicable) { "Relics and dragons only exist in Master Mode 7" }
    check(m7.title(END) == "Drags" && f7.title(END) == "End Dialogue")
    check(splitDefinitions.values.all { SCORE_300 in it.markers && SCORE_300 !in it.chain })
    check(splitDefinitions.getValue("F1").chain.none { it in listOf(MAXOR, S1, RELICS) })
    check(splitDefinitions.values.all { def -> def.sections.zipWithNext().all { (a, b) -> a.milestone == b.from } })
}

private fun checkTimeline() {
    val f7 = splitDefinitions.getValue("F7")
    val t = SplitTimeline(f7, true, 1_000 * MS)
    check(t.mark(BLOOD_OPEN, 21_000 * MS) == MarkResult.ACCEPTED)
    check(t.mark(BLOOD_OPEN, 22_000 * MS) == MarkResult.DUPLICATE && t.marks.getValue(BLOOD_OPEN) == 20_000L) { "Duplicates never move a split" }
    check(t.mark(BOSS_ENTRY, 90_000 * MS) == MarkResult.ACCEPTED) // Watcher clear missed: kept missing, not invented
    check(BLOOD_CLEAR !in t.marks)
    check(t.mark(BLOOD_CLEAR, 91_000 * MS) == MarkResult.OUT_OF_ORDER && t.conflicts == 1) { "A late earlier phase is rejected and noted" }
    check(t.markAt(SCORE_300, BLOOD_CLEAR) == MarkResult.NOT_APPLICABLE && t.markAt(MAXOR, BOSS_ENTRY) == MarkResult.NOT_APPLICABLE)
    check(t.mark(SCORE_300, 95_000 * MS) == MarkResult.ACCEPTED) { "Markers are independent of chain order" }
    check(t.markAt(SCORE_300, BOSS_ENTRY) == MarkResult.DUPLICATE && t.marks.getValue(SCORE_300) == 94_000L)
    // A 300 only confirmed in the boss is dated to boss entry on both clocks.
    val confirmed = SplitTimeline(f7, true, 0L, 0L)
    confirmed.mark(BOSS_ENTRY, 90_000 * MS, 1_760L); confirmed.mark(MAXOR, 120_000 * MS, 2_350L)
    check(confirmed.markAt(SCORE_300, BOSS_ENTRY) == MarkResult.ACCEPTED && confirmed.marks.getValue(SCORE_300) == 90_000L &&
        confirmed.ticks.getValue(SCORE_300) == 1_760L) { "${confirmed.marks}" }
    check(t.mark(RELICS, 96_000 * MS) == MarkResult.NOT_APPLICABLE) { "F7 has no relic phase" }
    check(t.current() == MAXOR)
    check(t.mark(START, 99_000 * MS) == MarkResult.DUPLICATE && t.marks.getValue(START) == 0L) { "Phase transitions never restart the run" }
    check(t.mark(END, 400_000 * MS) == MarkResult.ACCEPTED && t.finished)
    check(t.mark(NECRON, 401_000 * MS) == MarkResult.OUT_OF_ORDER)
    val record = t.record("a", 5, SplitOutcome.COMPLETED)
    check(record.conflicts == 1 && record.total == null) { "A run with conflicting events is never a PB candidate" }

    val late = SplitTimeline(f7, false, 50_000 * MS)
    check(late.mark(MAXOR, 60_000 * MS) == MarkResult.ACCEPTED && START !in late.marks && late.marks.getValue(MAXOR) == 10_000L)
    check(late.mark(STORM, 100_000 * MS) == MarkResult.ACCEPTED)
    val lateRecord = late.record("late", 1, SplitOutcome.COMPLETED)
    check(sectionDurations(lateRecord, f7) == mapOf(STORM to 40_000L)) { "Late joins keep only sections seen end to end" }
    check(lateRecord.total == null)

    val terminals = TerminalSections()
    check(terminals.accept(1, 7) == null && terminals.accept(7, 7) == S1)
    check(terminals.accept(7, 7) == null) { "A repeated full count is not the next section" }
    check(terminals.accept(1, 8) == null && terminals.accept(8, 8) == S2)
    check(terminals.accept(2, 7) == null && terminals.accept(7, 7) == S3)
    check(terminals.accept(1, 7) == null && terminals.accept(7, 7) == null) { "Section four ends at the Core entrance line" }
}

private fun checkStats() {
    val m7 = splitDefinitions.getValue("M7")
    val n = m7.sections.size
    val a = run("A", "M7", 100, chained(m7, List(n) { 20_000L }) + (SCORE_300 to 110_000L))
    val bDurations = MutableList(n) { 21_000L }.also { it[1] = 15_000L; it[5] = 18_000L }
    val b = run("B", "M7", 200, chained(m7, bDurations) + (SCORE_300 to 100_000L))
    // Faster on paper but incomplete: its sections must never become bests.
    val partial = run("P", "M7", 300, chained(m7, List(n) { 1_000L }).filterKeys { it != NECRON && it != RELICS && it != END }, SplitOutcome.FAILED)
    val conflicted = run("C", "M7", 400, chained(m7, List(n) { 1_000L }), conflicts = 1)
    val f7 = splitDefinitions.getValue("F7")
    val otherFloor = run("F", "F7", 500, chained(f7, List(f7.sections.size) { 500L }))
    val stats = floorSplitStats(listOf(a, b, partial, conflicted, otherFloor), m7, SplitWindow.ALL)
    check(stats.actualPb == SectionBest(20_000L * n, "A", 100)) { "Actual PB is a real completed run" }
    // Best possible is the best boss entry (one run's clear: B's 57 s, not A and B's best sections) plus each boss section's best.
    val clear = m7.chain.indexOf(BOSS_ENTRY)
    val onlyFinished = floorSplitStats(listOf(a, b), m7, SplitWindow.ALL)
    val expected = 57_000L + (clear until n).sumOf { minOf(20_000L, bDurations[it]) }
    check(onlyFinished.theoreticalPb == expected) { "Best possible starts from the best boss entry: ${onlyFinished.theoreticalPb} != $expected" }
    check(onlyFinished.checkpoints.single().best?.duration == 57_000L && (0 until clear).sumOf { minOf(20_000L, bDurations[it]) } < 57_000L)
    check(stats.theoreticalPb == 3 * 1_000L + (clear until n).sumOf { minOf(20_000L, bDurations[it]) }) { "A failed run's boss entry is still a real clear" }
    check(stats.theoreticalPb!! < stats.actualPb!!.duration)
    check(stats.sections[1].best?.run == "B" && stats.sections[0].best?.run == "A") { "Each section best keeps its source run" }
    check(stats.checkpoints.single().best!!.duration + stats.sections.drop(clear).sumOf { it.best!!.duration } == stats.theoreticalPb) { "Traceable to its parts" }
    check(stats.completedRuns == 2 && stats.savedRuns == 4) { "F7 is never mixed into M7" }
    check(stats.sections[0].samples == 3) { "Section averages include sections seen in partial runs, never conflicting ones" }
    check(stats.averageSamples == 2 && stats.averageTotal == (a.total!! + b.total!!) / 2.0)
    // Worst times come from the same runs as bests: B is slower overall, A on the sections B ran faster.
    check(stats.worstTotal == SectionBest(b.total!!, "B", 200) && b.total!! > a.total!!) { "${stats.worstTotal}" }
    check(stats.sections[1].worst?.run == "A" && stats.sections[0].worst?.run == "B") { "Each section's worst keeps its source run" }
    check(stats.sections[0].reference(SplitComparison.WORST) == 21_000L && stats.sections[1].reference(SplitComparison.BEST) == 15_000L)
    check(stats.sections[0].reference(SplitComparison.AVERAGE) == stats.sections[0].average!!.toLong())
    check(stats.totalReference(SplitComparison.WORST) == b.total && stats.totalReference(SplitComparison.BEST) == a.total)
    check(stats.markers.single().best?.duration == 100_000L)

    // A section with no eligible best leaves the theoretical PB unavailable instead of counting it as zero.
    val missing = run("M", "M7", 600, chained(m7, List(n) { 10_000L }).filterKeys { it != RELICS })
    val onlyMissing = floorSplitStats(listOf(missing), m7, SplitWindow.ALL)
    check(onlyMissing.theoreticalPb == null && onlyMissing.actualPb?.run == "M")
    // Zero is a valid, known duration.
    val zero = chained(m7, MutableList(n) { 10_000L }.also { it[3] = 0L })
    check(floorSplitStats(listOf(run("Z", "M7", 700, zero)), m7, SplitWindow.ALL).sections[3].best?.duration == 0L)

    val many = (1..30).map { i -> run("r$i", "M7", i.toLong() * 1000, chained(m7, List(n) { if (i > 20) 30_000L else 10_000L })) }
    check(floorSplitStats(many, m7, SplitWindow.LAST_10).averageTotal == 30_000.0 * n) { "Last 10 uses the newest ten runs" }
    check(floorSplitStats(many, m7, SplitWindow.LAST_10).averageSamples == 10)
    check(floorSplitStats(many, m7, SplitWindow.ALL).averageSamples == 30)
    check(floorSplitStats(many, m7, SplitWindow.LAST_25).sections[0].samples == 25)
    check(floorSplitStats(many, m7, SplitWindow.LAST_50).sections[0].average == (20 * 10_000.0 + 10 * 30_000.0) / 30)

    val older = a.copy(id = "old", definition = 0)
    check(floorSplitStats(listOf(older), m7, SplitWindow.ALL).savedRuns == 0) { "Different split layouts are never combined" }
}

private fun checkStore() {
    val m7 = splitDefinitions.getValue("M7")
    val record = run("id-1", "M7", 1234, chained(m7, List(m7.sections.size) { 1_500L }) + (SCORE_300 to 7_000L), conflicts = 0)
    val parsed = parseSplitStore(encodeSplitStore(listOf(record, record.copy(id = "id-2", outcome = SplitOutcome.ABANDONED, startObserved = false,
        marks = mapOf(MAXOR to 5L))))).associateBy { it.id }
    check(parsed["id-1"] == record && parsed["id-2"]?.outcome == SplitOutcome.ABANDONED)
    val bad = encodeSplitRun(record).apply { getAsJsonObject("marks").addProperty("MAXOR", -5) }
    check(parseSplitRun(bad) == null) { "A damaged time is rejected, not repaired" }
    check(parseSplitRun(encodeSplitRun(record).apply { addProperty("floor", "E") }) == null)
    val future = encodeSplitRun(record).apply { getAsJsonObject("marks").addProperty("SOMETHING_NEW", 5) }
    check(parseSplitRun(future)?.marks == record.marks) { "Unknown future milestones are ignored" }
    check(parseSplitStore(com.google.gson.JsonObject().apply { addProperty("version", 99) }).isEmpty())
}

private fun checkScore() {
    val perfect = ScoreInputs("M7", secretPercent = 100.0, clearedPercent = 93, completedRooms = 28, crypts = 5, elapsedSeconds = 300,
        mimic = true, prince = true)
    check(estimateDungeonScore(perfect) == 40 + 60 + 100 + 8 + 100) { "${estimateDungeonScore(perfect)}" }
    check(estimateDungeonScore(perfect.copy(secretPercent = 90.0, crypts = 1, mimic = false, prince = false)) == 297)
    check(estimateDungeonScore(perfect.copy(openPuzzles = 1))!! < estimateDungeonScore(perfect)!!)
    check(estimateDungeonScore(perfect.copy(deaths = 2)) == estimateDungeonScore(perfect)!! - 3)
    // Unread inputs count as NoammAddons counts them: nothing found, 36 rooms, no time spent; never more than a full read.
    check(estimateDungeonScore(perfect.copy(secretPercent = null)) == estimateDungeonScore(perfect)!! - 40 && !perfect.copy(secretPercent = null).complete)
    check(estimateDungeonScore(perfect.copy(clearedPercent = 0)) == 40 + 50 + 20 + 66 + 8 + 100) { "${estimateDungeonScore(perfect.copy(clearedPercent = 0))}" }
    check(estimateDungeonScore(ScoreInputs("M7"))!! < 300 && estimateDungeonScore(ScoreInputs("X9")) == null && perfect.complete)
    // A teammate's mimic kill out of sight is what usually decides 300 on M7.
    check(estimateDungeonScore(perfect.copy(secretPercent = 80.0, mimic = false, prince = false)) == 297)
    check(estimateDungeonScore(perfect.copy(secretPercent = 80.0, prince = false)) == 299 && estimateDungeonScore(perfect.copy(secretPercent = 80.0)) == 300)
    check(announcesMimic("Party > [MVP+] lfCrit: Mimic Killed!") && announcesMimic("Party > Boraannn: \$SKYTILS-DUNGEON-SCORE-MIMIC\$"))
    check(announcesPrince("A Prince falls. +1 Bonus Score") && announcesPrince("Party > imM6: Prince Killed!"))
    check(!announcesMimic("[BOSS] Necron: All this, for nothing...") && !announcesPrince("Party > imM6: Mimic Killed!"))
    check(estimateDungeonScore(perfect.copy(elapsedSeconds = 1000))!! < estimateDungeonScore(perfect)!!)
    check(estimateDungeonScore(ScoreInputs("F5", secretPercent = 70.0, clearedPercent = 93, completedRooms = 28, crypts = 5, elapsedSeconds = 100)) ==
        40 + 60 + 100 + 5 + 100)
    check(speedDeduction(0.0) == 0.0 && speedDeduction(20.0) == 10.0)

    val tab = readScoreTabList(ScoreInputs("F7"), listOf(
        listOf("Party (5)", " Team Deaths: 1"),
        listOf("Dungeon: Catacombs", " Secrets Found: 13", " Secrets Found: 54.3%", " Crypts: 3", " Completed Rooms: 21",
            "Puzzles: (3)", " Water Board: [✔]", " Three Weirdos: [✖]", " ???: [✦]")))
    check(tab.secretPercent == 54.3 && tab.crypts == 3 && tab.completedRooms == 21 && tab.deaths == 1 && tab.openPuzzles == 2) { "$tab" }
    val board = readScoreSidebar(tab, listOf("Time Elapsed: 5m 12s", "Cleared: 70% (231)"), watcherCleared = false)
    check(board.elapsedSeconds == 312 && board.clearedPercent == 70 && !board.bloodDone)
    check(readScoreSidebar(board, listOf("Cleared: 74% (240)"), watcherCleared = true).bloodDone)
    check(readScoreSidebar(board, listOf("Cleared: 70% (240)"), watcherCleared = true).bloodDone.not())
    // Hypixel's own score: final in the boss, a floor under the estimate during the clear.
    check(board.shownScore == 231 && readScoreSidebar(ScoreInputs("M7"), listOf("Cleared: 70%"), false).shownScore == null)
    val boss = readScoreSidebar(perfect.copy(secretPercent = 90.0, crypts = 1, mimic = false, prince = false, inBoss = true),
        listOf("Cleared: 100% (301)"), watcherCleared = true)
    check(estimateDungeonScore(boss)!! < 300 && dungeonScore(boss) == 301 && boss.exact) { "${dungeonScore(boss)}" }
    check(dungeonScore(boss.copy(inBoss = false)) == 301 && !boss.copy(inBoss = false).exact) { "A higher shown score lifts the estimate" }
    check(dungeonScore(boss.copy(inBoss = false, shownScore = 250)) == estimateDungeonScore(boss.copy(inBoss = false)))
    check(dungeonScore(boss.copy(shownScore = 296)) == 296) { "In the boss Hypixel's score wins either way" }
}

private fun checkHud() {
    val f7 = splitDefinitions.getValue("F7")
    val marks = mapOf(START to 0L, BLOOD_OPEN to 20_000L, BOSS_ENTRY to 90_000L, SCORE_300 to 95_000L)
    // Server ticks: 1.2 s of lag before blood opened, none after.
    val ticks = mapOf(START to 0L, BLOOD_OPEN to 376L, BOSS_ENTRY to 1_776L, SCORE_300 to 1_876L)
    val view = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), null, SplitHudOptions())
    val rows = view.rows.associateBy { it.name.drop(2).trim() }
    check(rows.getValue("Blood Open").time == "§f20.00" && rows.getValue("Blood Open").server == "§7(§b18.80§7)") { "${view.rows}" }
    check(rows.getValue("Watcher Clear").delta == "§8missed" && rows.getValue("Portal").time == "§8—") { "${view.rows}" }
    // Boss entry is its own row after Portal, timed from the run start on both clocks.
    check(rows.getValue("Boss Entry").time == "§a1:30.0" && rows.getValue("Boss Entry").server == "§7(§b1:28.8§7)") { "${view.rows}" }
    val clearNames = view.rows.map { it.name.drop(2) }
    check(clearNames.indexOf("Boss Entry") == clearNames.indexOf("Portal") + 1 && clearNames.indexOf("Maxor") == clearNames.indexOf("Boss Entry") + 1)
    val clearing = splitHudView(f7, SplitHudRun(mapOf(START to 0L, BLOOD_OPEN to 20_000L), emptyMap(), true, 50_000L, null, false, null), null, SplitHudOptions())
    check(clearing.rows.first { it.name.endsWith("Boss Entry") }.time == "§e50.00") { "Boss entry counts up from the start during the clear" }
    check(rows.getValue("Maxor").time == "§e10.00" && rows.getValue("Maxor").server == "§7(§b10.00§7)") { "The running section counts from the last split" }
    check(rows.getValue("Storm").time == "§8—")
    check(rows.getValue("300 Score").time == "§f1:35.0" && rows.getValue("300 Score").server == "§7(§b1:33.8§7)")
    val unmarked = SplitHudRun(marks - SCORE_300, ticks, true, 100_000L, 1_976L, false, 298)
    check(splitHudView(f7, unmarked, null, SplitHudOptions()).rows.first { it.name.endsWith("300 Score") }.time == "§7est. 298")
    check(splitHudView(f7, unmarked.copy(estimate = 299, scoreExact = true), null, SplitHudOptions()).rows
        .first { it.name.endsWith("300 Score") }.time == "§7score 299") { "Hypixel's own score is not shown as an estimate" }
    check(view.footer[0] == "§7Run §f1:40.0 §7(§b1:38.8§7)" && view.footer[1] == "§7Lag lost §c+1.20") { "${view.footer}" }
    // Terms heads S1–S4, which are indented; Goldor is not.
    val names = view.rows.map { it.name.drop(2) }
    check(names.indexOf("Terms") == names.indexOf("  S1") - 1 && "  S4" in names && "Goldor" in names) { "$names" }
    val noServer = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), null, SplitHudOptions(server = false))
    check(noServer.rows.all { it.server.isEmpty() } && noServer.footer.size == 1)
    val late = splitHudView(f7, SplitHudRun(mapOf(MAXOR to 0L), emptyMap(), false, 5_000L, null, false, null), null,
        SplitHudOptions(comparison = null, upcoming = false, precise = false))
    check(late.footer.first().contains("not timed") && late.rows.map { it.name } == listOf("§7Maxor", "§eStorm")) { "${late.rows}" }

    // Terms while terminals are running, then complete.
    val inTerms = mapOf(START to 0L, BLOOD_OPEN to 1L, BLOOD_CLEAR to 2L, BOSS_ENTRY to 3L, MAXOR to 4L, STORM to 100_000L, S1 to 112_000L)
    val running = splitHudView(f7, SplitHudRun(inTerms, emptyMap(), true, 120_000L, null, false, null), null, SplitHudOptions(comparison = null))
    check(running.rows.first { it.name.endsWith("Terms") }.time == "§e20.00" && running.rows.first { it.name.endsWith("S2") }.time == "§e8.00")
    val done = inTerms + mapOf(S2 to 125_000L, S3 to 140_000L, S4 to 150_000L)
    val finished = splitHudView(f7, SplitHudRun(done, emptyMap(), true, 151_000L, null, false, null), null, SplitHudOptions(comparison = null))
    check(finished.rows.first { it.name.endsWith("Terms") }.time == "§f50.00")
    check(formatSplitTime(61_250L) == "1:01.2" && formatSplitTime(null) == "—" && formatSplitDelta(-1_230L) == "-1.23")

    // Server time as the timer: it leads each row and real time moves into the brackets.
    val serverView = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), null, SplitHudOptions(clock = SplitClock.SERVER))
    val serverRows = serverView.rows.associateBy { it.name.drop(2).trim() }
    check(serverRows.getValue("Blood Open").time == "§f18.80" && serverRows.getValue("Blood Open").server == "§7(§f20.00§7)") { "${serverView.rows}" }
    check(serverView.footer[0] == "§7Run §f1:38.8 §7(§f1:40.0§7)" && serverView.footer[1] == "§7Lag lost §c+1.20") { "${serverView.footer}" }
    // Without ticks (after a reconnect) rows fall back to real time, with nothing in brackets.
    val noTicks = splitHudView(f7, SplitHudRun(inTerms, emptyMap(), true, 120_000L, null, false, null), null, SplitHudOptions(clock = SplitClock.SERVER))
    check(noTicks.rows.first { it.name.endsWith("Terms") }.let { it.time == "§e20.00" && it.server.isEmpty() })

    // Comparing with the best, the average or the worst: one run at 18 s per section and one at 22 s.
    val history = listOf(run("fast", "F7", 100, chained(f7, List(f7.sections.size) { 18_000L })),
        run("slow", "F7", 200, chained(f7, List(f7.sections.size) { 22_000L })))
    val f7Stats = floorSplitStats(history, f7, SplitWindow.ALL)
    fun bloodDelta(comparison: SplitComparison?) = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), f7Stats,
        SplitHudOptions(comparison = comparison)).rows.first { it.name.endsWith("Blood Open") }.delta
    check(bloodDelta(SplitComparison.BEST) == "§c+2.00" && bloodDelta(SplitComparison.AVERAGE) == "§a+0.00") { bloodDelta(SplitComparison.AVERAGE) }
    check(bloodDelta(SplitComparison.WORST) == "§a-2.00" && bloodDelta(null) == "") { bloodDelta(SplitComparison.WORST) }
    val averageFooter = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), f7Stats,
        SplitHudOptions(comparison = SplitComparison.AVERAGE)).footer.last()
    check(averageFooter.startsWith("§7Average §f") && "PB" in averageFooter) { averageFooter }
    val upcoming = splitHudView(f7, SplitHudRun(marks, ticks, true, 100_000L, 1_976L, false, 287), f7Stats,
        SplitHudOptions(comparison = SplitComparison.WORST)).rows.first { it.name.endsWith("Storm") }
    check(upcoming.time == "§822.00") { "Upcoming splits show the compared time: $upcoming" }
}

private fun checkServerTime() {
    val m7 = splitDefinitions.getValue("M7")
    val t = SplitTimeline(m7, true, 0L, 1_000L)
    check(t.mark(BLOOD_OPEN, 20_000 * MS, 1_380L) == MarkResult.ACCEPTED && t.ticks.getValue(BLOOD_OPEN) == 380L)
    check(t.ticks.getValue(START) == 0L && t.elapsedTicks(1_500L) == 500L)
    // More server time than real time means the tick source is wrong: stop server timing, keep real time.
    check(t.mark(BLOOD_CLEAR, 30_000 * MS, 2_000L) == MarkResult.ACCEPTED && BLOOD_CLEAR in t.marks && BLOOD_CLEAR !in t.ticks && !t.ticksAvailable)
    check(t.mark(BOSS_ENTRY, 40_000 * MS, 2_100L) == MarkResult.ACCEPTED && BOSS_ENTRY !in t.ticks)
    val silent = SplitTimeline(m7, true, 0L, 500L)
    silent.mark(BLOOD_OPEN, 15_000 * MS, 500L)
    check(!silent.ticksAvailable && BLOOD_OPEN !in silent.ticks) { "No pings for a long time: server time unavailable, not zero" }
    val resumed = SplitTimeline(m7, true, 0L, 0L)
    resumed.mark(BLOOD_OPEN, 20_000 * MS, 400L); resumed.stopTicks(); resumed.mark(BLOOD_CLEAR, 60_000 * MS, 1_000L)
    check(resumed.ticks.keys == setOf(START, BLOOD_OPEN)) { "Ticks missed while away are never filled in" }

    val n = m7.sections.size
    val marks = chained(m7, List(n) { 20_000L })
    // Each section lost 100 ms to lag: 19.9 s on the server clock.
    val ticks = mapOf(START to 0L) + chained(m7, List(n) { 19_900L }).mapValues { it.value / TICK_MILLIS }
    val laggy = run("L", "M7", 100, marks).copy(ticks = ticks)
    val realOnly = run("R", "M7", 200, chained(m7, List(n) { 19_950L }))
    check(laggy.total(SplitClock.SERVER) == 19_900L * n && laggy.lagLost == 100L * n && realOnly.lagLost == null)
    val real = floorSplitStats(listOf(laggy, realOnly), m7, SplitWindow.ALL, SplitClock.REAL)
    val server = floorSplitStats(listOf(laggy, realOnly), m7, SplitWindow.ALL, SplitClock.SERVER)
    check(real.actualPb?.run == "R" && server.actualPb?.run == "L") { "Each clock has its own PB" }
    check(server.completedRuns == 1 && server.theoreticalPb == 19_900L * n) { "Runs without ticks never count on the server clock" }
    check(real.averageLag == 100.0 * n)
    // Boss entry's best is the fastest single clear; it is not a section, and Best possible starts from it.
    check(real.checkpoints.single().let { it.milestone == BOSS_ENTRY && it.best?.duration == 3 * 19_950L && it.best?.run == "R" && it.samples == 2 })
    check(sectionDurations(laggy, m7, SplitClock.SERVER).getValue(MAXOR) == 19_900L)
    val terms = server.groups.single()
    check(terms.group.title == "Terms" && terms.best?.duration == 4 * 19_900L && terms.samples == 1)
    check(real.groups.single().best?.duration == 4 * 19_950L) { "Terms best is one run's terminals, not a sum of section bests" }
    check(server.checkpoints.single().best!!.duration + server.sections.drop(m7.chain.indexOf(BOSS_ENTRY)).sumOf { it.best!!.duration } ==
        server.theoreticalPb) { "Terms is not added to Best possible" }
    // Stored ticks survive a round trip; a 2.7.12 `CORE` mark is read back as S4.
    check(parseSplitRun(encodeSplitRun(laggy)) == laggy)
    val legacy = encodeSplitRun(realOnly).apply {
        val marksJson = getAsJsonObject("marks"); val s4 = marksJson.remove("S4"); marksJson.add("CORE", s4)
    }
    check(parseSplitRun(legacy)?.marks?.get(S4) == realOnly.marks[S4] && parseSplitRun(legacy) == realOnly)
    val badTicks = encodeSplitRun(laggy).apply { getAsJsonObject("serverTicks").addProperty("MAXOR", -1) }
    check(parseSplitRun(badTicks)?.let { it.ticks.isEmpty() && it.marks == laggy.marks } == true) { "Damaged ticks drop only server time" }
    val pingCounts = encodeSplitRun(laggy).apply { add("ticks", remove("serverTicks")) }
    check(parseSplitRun(pingCounts)?.let { it.ticks.isEmpty() && it.marks == laggy.marks } == true) { "2.7.13 ping counts are not server time" }
}

private fun checkLagMeter() {
    val meter = LagMeter()
    check(meter.shown == null)
    // Packet jitter of ±0.2 s around a steady 1.0 s: the late (high) readings never show, and it never goes down.
    val shownValues = listOf(1_000L, 1_180L, 1_020L, 1_150L, 990L, 1_200L, 1_010L).map { meter.read(it); meter.shown!! }
    check(shownValues.zipWithNext().all { (a, b) -> b >= a } && shownValues.last() <= 1_020L) { "$shownValues" }
    // Real lag persists across readings, so it shows once it is the lowest of the window.
    repeat(3) { meter.read(2_500L) }
    check(meter.shown == 2_500L)
    meter.read(-300L)
    check(meter.shown == 2_500L) { "Never goes down" }
    // The HUD shows the steady value live and the exact splits once finished.
    val f7 = splitDefinitions.getValue("F7")
    val marks = mapOf(START to 0L, BLOOD_OPEN to 20_000L)
    val live = splitHudView(f7, SplitHudRun(marks, mapOf(START to 0L, BLOOD_OPEN to 380L), true, 30_000L, 563L, false, null, lag = 1_000L), null, SplitHudOptions())
    check(live.footer[1] == "§7Lag lost §c+1.00") { "${live.footer}" }
}

private fun checkServerClock() {
    val clock = ServerTickClock()
    clock.ping()
    check(clock.now == null) { "Pings alone are not a clock" }
    check(clock.time(10_000L) && clock.now == 10_000L)
    repeat(25) { clock.ping() }
    check(clock.now == 10_020L) { "Pings advance at most to the next game time update" }
    check(clock.time(10_019L) && clock.now == 10_020L) { "Never reported lower than before" }
    check(clock.time(10_040L) && clock.now == 10_040L)
    // Five minutes at 20 TPS with Hypixel's extra pings (about 21 per 20 ticks) stays exact on the server's clock.
    val start = clock.now!!
    for (second in 1..300) { repeat(21) { clock.ping() }; clock.time(start + second * 20L) }
    check(clock.now == start + 6_000L)
    // A lag spike: no updates or pings for 3 s, then the server resumes; server time only counts ticks it ran.
    val beforeLag = clock.now!!
    clock.time(beforeLag + 20L)
    check(clock.now == beforeLag + 20L)
    check(!clock.time(500L) && clock.now == 500L) { "Another world's game time is a jump" }
    check(!clock.time(500L + 20L * 60 * 10)) { "Minutes without updates is a jump" }
    clock.reset()
    check(clock.now == null)
}

private fun checkPace() {
    val m7 = splitDefinitions.getValue("M7"); val n = m7.sections.size
    // Pace uses the newest ten runs only: 20 s per section, not the older 10 s ones.
    val history = (1..30).map { i -> run("p$i", "M7", i * 1000L, chained(m7, List(n) { if (i > 20) 20_000L else 10_000L })) }
    val averages = floorSplitStats(history, m7, SplitWindow.LAST_10)
    val first = m7.sections[0].milestone
    fun live(elapsed: Long, finished: Boolean = false) = SplitHudRun(mapOf(START to 0L, first to 15_000L), emptyMap(), true, elapsed, null, finished, null)
    check(splitPace(m7, live(18_000L), averages, SplitClock.REAL) == 15_000L + (n - 1) * 20_000L) { "Remaining sections at their average" }
    check(splitPace(m7, live(40_000L), averages, SplitClock.REAL) == 15_000L + 25_000L + (n - 2) * 20_000L) { "A slow running section counts its time so far" }
    check(splitPace(m7, live(18_000L), floorSplitStats(emptyList(), m7, SplitWindow.LAST_10), SplitClock.REAL) == null) { "No history, no pace" }
    check(splitPace(m7, live(18_000L, finished = true), averages, SplitClock.REAL) == null)
    check(splitPace(m7, live(18_000L), averages, SplitClock.SERVER) == null) { "No server ticks, no server pace" }
    val view = splitHudView(m7, live(18_000L), null, SplitHudOptions(comparison = null, pace = true), averages)
    check(view.footer.any { "Pace" in it && formatSplitTime(15_000L + (n - 1) * 20_000L) in it && "est." in it }) { view.footer.toString() }
    check(splitHudView(m7, live(18_000L), null, SplitHudOptions(comparison = null), averages).footer.none { "Pace" in it }) { "Off by default" }
    // Chat summary: real time, server time and the lag between them.
    val ended = run("s", "M7", 1, mapOf(END to 300_000L)).copy(ticks = mapOf(START to 0L, END to 5_900L))
    val summary = splitRunSummary(ended)!!
    check(formatSplitTime(300_000L) in summary && formatSplitTime(295_000L) in summary && "+${formatSplitTime(5_000L)}" in summary) { summary }
    check(splitRunSummary(run("s", "M7", 1, mapOf(END to 300_000L)))!!.contains("server time unavailable"))
    check(splitRunSummary(run("s", "M7", 1, mapOf(END to 300_000L), start = false)) == null) { "A late join has no run time" }
}

fun main() {
    checkSignals(); checkDefinitions(); checkTimeline(); checkStats(); checkStore(); checkScore(); checkHud(); checkServerTime(); checkServerClock(); checkLagMeter(); checkPace()
    println("Dungeon split checks passed: triggers, ordering, PBs, theoretical best, averages, storage, 300 score, HUD rows, comparisons, Terms and server time")
}
