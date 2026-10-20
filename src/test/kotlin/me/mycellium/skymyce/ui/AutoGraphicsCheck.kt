package me.mycellium.skymyce.ui

/** Feeds [ticks] client ticks (50 ms apart) of steady measurements starting at [from]; returns the end time. */
private fun AutoGraphicsController.run(from: Long, seconds: Int, fps: Int, frameMillis: Double, target: Int = 60,
                                       response: AutoResponse = AutoResponse.NORMAL, lowest: GuiQuality = GuiQuality.EXTREMELY_LIGHT): Long {
    var now = from
    repeat(seconds * 20) { now += 50; sample(now, fps, frameMillis, target, response, lowest) }
    return now
}

/** Auto graphics: steps down on sustained trouble, back up on sustained health, ignores blips and loading, holds without data. */
fun autoGraphicsCheck() {
    // Healthy: climbs from Balanced to Heavy after the restore time, then stays.
    val healthy = AutoGraphicsController()
    check(healthy.quality == GuiQuality.MEDIUM)
    var t = healthy.run(0, 14, 144, 6.9)
    check(healthy.quality == GuiQuality.MEDIUM) { "Not before 15 s of good frames" }
    t = healthy.run(t, 3, 144, 6.9)
    check(healthy.quality == GuiQuality.HIGH && healthy.reason.startsWith("Raised")) { healthy.reason }
    t = healthy.run(t, 60, 144, 6.9); check(healthy.quality == GuiQuality.HIGH)

    // Sustained trouble: one step per degrade time, never faster than the cooldown, never below the floor.
    t = healthy.run(t, 4, 35, 28.0); check(healthy.quality == GuiQuality.HIGH) { "A 4 s dip is not enough" }
    t = healthy.run(t, 2, 35, 28.0); check(healthy.quality == GuiQuality.MEDIUM && healthy.reason.startsWith("Lowered")) { healthy.reason }
    val dropped = healthy.lastChange
    t = healthy.run(t, 5, 35, 28.0); check(healthy.quality == GuiQuality.MEDIUM) { "Cooldown between changes" }
    t = healthy.run(t, 4, 35, 28.0); check(healthy.quality == GuiQuality.LIGHT && healthy.lastChange - dropped >= AutoGraphicsController.COOLDOWN_MILLIS)
    t = healthy.run(t, 60, 20, 50.0, lowest = GuiQuality.LIGHT); check(healthy.quality == GuiQuality.LIGHT) { "Never below the lowest allowed" }

    // Fluctuating around the target (55–66 FPS) neither lowers nor raises: hysteresis.
    val wobble = AutoGraphicsController(); var now = 0L
    repeat(60 * 20) { i -> now += 50; wobble.sample(now, if ((i / 20) % 2 == 0) 56 else 66, 16.5, 60, AutoResponse.NORMAL) }
    check(wobble.quality == GuiQuality.MEDIUM) { "Around the threshold nothing changes (${wobble.reason})" }

    // Short spikes (one bad second in five) do not count as sustained trouble.
    val spiky = AutoGraphicsController(); now = 0
    repeat(60 * 20) { i -> now += 50; val bad = (i / 20) % 5 == 0; spiky.sample(now, if (bad) 30 else 120, if (bad && i % 20 == 0) 80.0 else 8.0, 60, AutoResponse.NORMAL) }
    check(spiky.quality.ordinal >= GuiQuality.MEDIUM.ordinal) { "Occasional spikes keep quality (${spiky.reason})" }

    // Loading: measurements during and just after it are ignored.
    val loading = AutoGraphicsController()
    loading.pause(0)
    loading.run(0, 4, 5, 200.0); check(loading.quality == GuiQuality.MEDIUM) { "Ignored while settling" }

    // No data: the tier holds and nothing crashes.
    val empty = AutoGraphicsController()
    empty.run(0, 30, 0, Double.NaN); empty.run(30_000, 30, 60, -1.0); check(empty.quality == GuiQuality.MEDIUM)
    check(AutoGraphicsController.percentile(emptyList(), 0.95) == 0.0 && AutoGraphicsController.percentile(listOf(1.0, 2.0, 3.0, 100.0), 0.5) == 2.0)

    // A capped game is not slow: at a 60 FPS cap a 57 target is met by 59 FPS.
    val capped = AutoGraphicsController()
    capped.run(0, 60, 59, 16.9, target = 57); check(capped.quality == GuiQuality.MEDIUM) { capped.reason }
    // Quick reacts sooner than Gentle.
    val quick = AutoGraphicsController(); quick.run(0, 4, 30, 33.0, response = AutoResponse.QUICK)
    val gentle = AutoGraphicsController(); gentle.run(0, 4, 30, 33.0, response = AutoResponse.GENTLE)
    check(quick.quality == GuiQuality.LIGHT && gentle.quality == GuiQuality.MEDIUM)
    // Fixed modes stay fixed; Auto is the default for new configurations.
    check(GuiQuality.AUTO.toString().startsWith("Auto") && GuiQuality.MEDIUM.toString() == "Medium" && GuiQuality.HIGH.toString() == "High" && GuiQuality.EXTREMELY_LIGHT.toString() == "Extremely Light")
    // Effects Auto controls: decorative leap previews slow down, the menu blur goes at Light and Extremely Light.
    check(me.mycellium.skymyce.features.instances.dungeons.leap.leapPreviewFps(30, GuiQuality.EXTREMELY_LIGHT) == 10 &&
        me.mycellium.skymyce.features.instances.dungeons.leap.leapPreviewFps(30, GuiQuality.LIGHT) == 15 &&
        me.mycellium.skymyce.features.instances.dungeons.leap.leapPreviewFps(30, GuiQuality.MEDIUM) == 30 &&
        me.mycellium.skymyce.features.instances.dungeons.leap.leapPreviewFps(8, GuiQuality.EXTREMELY_LIGHT) == 8)
    check(!menuBlurAllowed(GuiQuality.LIGHT) && !menuBlurAllowed(GuiQuality.EXTREMELY_LIGHT) && menuBlurAllowed(GuiQuality.MEDIUM) && menuBlurAllowed(GuiQuality.HIGH))
    // The cosmetic-name text hook: the cheap pre-check finds names (any case, at word ends) and rejects everything else.
    val names = me.mycellium.skymyce.features.social.CosmeticNameMap()
    names.update(mapOf("MisterKrister" to net.minecraft.network.chat.Component.literal("Krister")))
    fun seq(text: String) = net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
    check(names.mayContainName(seq("Party > misterkrister: hi")) && names.mayContainName(seq("MisterKrister")) && !names.mayContainName(seq("Mister Krister x")))
    check(!names.mayContainName(seq("Healer 45 [bar] 3,402")) && !names.mayContainName(seq("MisterKristerr")))
    val shown = StringBuilder(); names.sequence(seq("hi MisterKrister!")).accept { _, _, c -> shown.appendCodePoint(c); true }
    check(shown.toString() == "hi Krister!") { shown.toString() }
    val plain = seq("nothing to see"); check(names.sequence(plain) === plain) { "Text without names passes through untouched" }
    println("Auto graphics checks passed: sustained drops, recovery, cooldown, floor, hysteresis, spikes, loading, missing data and frame caps")
}
