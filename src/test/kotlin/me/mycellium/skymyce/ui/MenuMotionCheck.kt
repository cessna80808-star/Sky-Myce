package me.mycellium.skymyce.ui

import kotlin.math.abs
import kotlin.math.roundToLong
import java.awt.Rectangle
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIGraphics

private class MotionClock {
    var nanos = 0L
    fun millis(value: Long) { nanos = value * 1_000_000 }
    fun value(initial: Double = 0.0) = MotionValue(initial) { nanos }
}

private fun near(actual: Double, expected: Double, message: String, tolerance: Double = 1e-9) {
    check(actual.isFinite() && abs(actual - expected) <= tolerance) { "$message: $actual != $expected" }
}

fun main() {
    val checkpoints = listOf(0L, 20, 60, 100, 120, 170, 220, 280, 350).map { it * 1_000_000 }
    fun trajectory(fps: Int): List<Double> {
        val clock = MotionClock(); val value = clock.value()
        value.animateTo(1.0, 240, MotionEasing.SETTLE, delayMillis = 20)
        val frames = (0..fps).map { (it * 1_000_000_000.0 / fps).roundToLong() }.filter { it <= checkpoints.last() }
        val poses = mutableMapOf<Long, Double>()
        for (time in (frames + checkpoints).distinct().sorted()) {
            clock.nanos = time
            val pose = value.sample()
            if (time in checkpoints) poses[time] = pose
        }
        check(!value.running && value.target == 1.0)
        return checkpoints.map { poses.getValue(it) }
    }
    val baseline = trajectory(30)
    for (fps in listOf(60, 120, 240)) trajectory(fps).zip(baseline).forEachIndexed { index, (actual, expected) ->
        near(actual, expected, "Motion speed changed at $fps FPS/checkpoint $index")
    }
    check(baseline[1] == 0.0 && baseline.last() == 1.0)

    for (easing in MotionEasing.entries) {
        near(easing.apply(0.0), 0.0, "$easing start")
        near(easing.apply(1.0), 1.0, "$easing end")
        near(easing.apply(-1.0), 0.0, "$easing negative progress")
        near(easing.apply(2.0), 1.0, "$easing excessive progress")
        check(easing.apply(Double.NaN).isFinite())
        val samples = (0..1000).map { easing.apply(it / 1000.0) }
        check(samples.all { it in -1e-9..1.04 }) { "$easing overshoot is too large" }
        if (easing != MotionEasing.SETTLE) check(samples.zipWithNext().all { (a, b) -> b >= a })
        else check(samples.max() > 1.0) { "Settling easing lost its subtle overshoot" }
    }

    val clock = MotionClock(); val value = clock.value()
    value.animateTo(1.0, 200, MotionEasing.LINEAR)
    clock.millis(60); near(value.sample(), .3, "Initial motion")
    value.animateTo(1.0, 200, MotionEasing.LINEAR)
    clock.millis(100); near(value.sample(), .5, "Repeated target restarted the timeline")
    value.animateTo(0.0, 100, MotionEasing.LINEAR)
    near(value.sample(), .5, "Reversal snapped the displayed pose")
    clock.millis(120); near(value.sample(), .4, "Reversal direction")
    value.animateTo(1.0, 80, MotionEasing.LINEAR)
    near(value.sample(), .4, "A second interruption snapped the displayed pose")
    clock.millis(200); near(value.sample(), 1.0, "Interrupted motion did not settle")
    check(!value.running)

    clock.millis(300); value.snap(0.0)
    value.animateTo(1.0, 100, MotionEasing.LINEAR, delayMillis = 50)
    clock.millis(325); value.animateTo(1.0, 100, MotionEasing.LINEAR, delayMillis = 50)
    clock.millis(350); near(value.sample(), 0.0, "Stagger start")
    clock.millis(400); near(value.sample(), .5, "Repeated delayed target postponed its entrance")
    value.cancel(); val cancelled = value.sample()
    check(!value.running && value.target == cancelled)
    clock.millis(1000); near(value.sample(), cancelled, "Cancelled screen continued animating")

    value.animateTo(1.0, 400, MotionEasing.SETTLE)
    clock.millis(1050); val beforeReduced = value.sample()
    value.animateTo(1.0, 400, MotionEasing.SETTLE, GuiMotion.REDUCED)
    near(value.sample(), beforeReduced, "Reduced mode snapped the pose")
    for (time in 1051L..1140L) {
        clock.millis(time)
        check(value.sample() in beforeReduced..1.0) { "Reduced motion overshot" }
    }
    check(!value.running && value.sample() == 1.0) { "Reduced duration exceeded 90 ms" }
    value.animateTo(0.0); clock.millis(1180); check(value.sample() > 0.0)
    value.animateTo(0.0, mode = GuiMotion.OFF)
    check(value.sample() == 0.0 && !value.running) { "Off mode left an unfinished transition" }
    value.animateTo(1.0, durationMillis = 0, delayMillis = 500)
    check(value.sample() == 1.0 && !value.running)
    value.animateTo(0.0, durationMillis = -10)
    check(value.sample() == 0.0 && !value.running)

    val staggerClock = MotionClock(); val reducedStagger = staggerClock.value()
    reducedStagger.animateTo(1.0, 400, MotionEasing.LINEAR, delayMillis = 500)
    staggerClock.millis(25); near(reducedStagger.sample(), 0.0, "Full stagger started early")
    reducedStagger.animateTo(1.0, 400, MotionEasing.LINEAR, GuiMotion.REDUCED, delayMillis = 500)
    staggerClock.millis(70); near(reducedStagger.sample(), .5, "Reduced mode retained the full stagger delay")
    staggerClock.millis(115); near(reducedStagger.sample(), 1.0, "Reduced stagger did not finish promptly")

    clock.millis(2000); value.animateTo(1.0, 100, MotionEasing.LINEAR, delayMillis = -50)
    clock.millis(2050); near(value.sample(), .5, "Negative delay was not treated as immediate")
    clock.millis(2010); near(value.sample(), .5, "Backwards clock rewound a transition")
    value.animateTo(Double.NaN); value.snap(Double.POSITIVE_INFINITY)
    check(value.target == 1.0 && value.sample().isFinite()) { "Nonfinite input corrupted motion" }
    clock.millis(8000); near(value.sample(), 1.0, "Long render stall did not settle safely")
    val invalidInitial = clock.value(Double.NaN); near(invalidInitial.sample(), 0.0, "Invalid initial pose")
    clock.nanos = Long.MIN_VALUE + 10
    val hugeJump = clock.value(Double.MAX_VALUE)
    hugeJump.animateTo(-Double.MAX_VALUE)
    clock.nanos = Long.MAX_VALUE
    near(hugeJump.sample(), -Double.MAX_VALUE, "Clock difference overflowed")
    val largeClock = MotionClock(); val largeEndpoints = largeClock.value(Double.MAX_VALUE)
    largeEndpoints.animateTo(-Double.MAX_VALUE, 100, MotionEasing.LINEAR)
    largeClock.millis(50); near(largeEndpoints.sample(), 0.0, "Finite endpoint interpolation overflowed")

    val rapidClock = MotionClock(); val rapid = rapidClock.value()
    for (index in 0 until 2000) {
        rapidClock.millis(index * 2L)
        rapid.animateTo((index % 2).toDouble())
        check(rapid.sample() in 0.0..1.0)
        if (index % 7 == 0) rapid.cancel()
    }
    rapid.animateTo(.75); rapidClock.millis(4200)
    check(!rapid.sample().isNaN() && !rapid.running && rapid.sample() == .75)

    val qualities = GuiQuality.entries
    check(qualities.map { it.rasterScale } == listOf(1.0, 1.25, 1.5, 2.0))
    check(qualities.map { it.shadowLayers } == listOf(0, 1, 2, 3))
    check(qualities.take(2).all { !it.glow && !it.ambient && it.ambientIntervalNanos == 0L })
    check(GuiQuality.MEDIUM.ambientIntervalNanos == 100_000_000L && GuiQuality.HIGH.ambientIntervalNanos == 50_000_000L)
    check(qualities.drop(2).all { it.glow && it.ambient })
    check(qualities.map { it.toString() } == listOf("Extremely Light", "Light", "Medium", "High"))
    check(GuiMotion.choices.map { it.toString() } == listOf("Light", "Medium", "Heavy", "Reduced", "Off"))
    for (mode in GuiMotion.entries) {
        for (life in listOf(1.0, 75.0, 500.0, 2000.0)) {
            for (age in listOf(-10.0, 0.0, life * .25, life * .5, life - .01, life, life + 10)) {
                val opacity = motionEnvelope(age, life, mode)
                check(opacity in 0.0..1.0)
                if (age >= life) check(opacity == 0.0) { "Timed row animation extended its original TTL" }
                else if (mode == GuiMotion.OFF) check(opacity == 1.0) { "Off mode altered a timed row's logical visibility" }
            }
        }
        check(motionEnvelope(Double.NaN, 1000.0, mode) == 0.0)
        check(motionEnvelope(Double.POSITIVE_INFINITY, 1000.0, mode) == 0.0)
        check(motionEnvelope(100.0, Double.NaN, mode) == 0.0)
        check(motionEnvelope(0.0, 0.0, mode) == 0.0)
        check(motionEnvelope(0.0, -1.0, mode) == 0.0)
    }
    near(motionEnvelope(90.0, 2000.0, GuiMotion.FULL), .5, "Timed row entrance")
    near(motionEnvelope(1930.0, 2000.0, GuiMotion.FULL), .5, "Timed row exit")
    near(motionEnvelope(90.0, 2000.0, GuiMotion.REDUCED), 1.0, "Reduced timed row entrance")
    near(motionEnvelope(1965.0, 2000.0, GuiMotion.REDUCED), .5, "Reduced timed row exit")
    for (fps in listOf(30, 60, 120, 240)) {
        val finalAge = 2000.0 + 1000.0 / fps
        check(motionEnvelope(finalAge, 2000.0, GuiMotion.FULL) == 0.0) { "$fps FPS kept an expired timed row" }
    }
    for (quality in qualities) {
        val lowClock = MotionClock(); val essential = lowClock.value()
        essential.animateTo(1.0)
        lowClock.millis(90); check(essential.sample() in .01.. .99) { "$quality lost essential motion" }
        lowClock.millis(180); check(essential.sample() == 1.0)
    }

    for ((width, height) in listOf(320 to 180, 960 to 540, 3440 to 1440, 3840 to 2160)) {
        for (scale in listOf(.8, 1.0, 1.25, 1.5, 2.0)) {
            val pose = MotionPose(.7, scale, 17.0, -9.0, width / 2.0, height / 2.0)
            for ((x, y) in listOf(0.0 to 0.0, 12.5 to 27.25, width.toDouble() to height.toDouble())) {
                val drawnX = (x - pose.pivotX) * pose.scale + pose.pivotX + pose.x
                val drawnY = (y - pose.pivotY) * pose.scale + pose.pivotY + pose.y
                near(pose.inverseX(drawnX), x, "Transformed pointer X at ${width}x$height/$scale")
                near(pose.inverseY(drawnY), y, "Transformed pointer Y at ${width}x$height/$scale")
            }
        }
    }
    for (scale in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        check(MotionPose(scale = scale).inverseX(10.0).isFinite())
        check(MotionPose(scale = scale).inverseY(Double.NaN).isFinite())
    }
    checkMotionCanvas()
    checkMotionFocus()
    checkMotionToasts()
    checkMotionScroll()
    checkRevealCardPointer()
    checkMotionClipping()
    checkLivingMotion()
    println("Menu motion checks passed: 30/60/120/240 FPS, reversal/stagger/cancellation, Reduced/Off, clock/input safety, isolated snapshots/constant-opacity blends, transformed input, retained rendering, scroll retarget/drag/clamp and bounded toast lifetimes")
}

/** Run the actual owo child scissors: cards and buttons move, while scroll viewports remain bounded. */
private fun checkMotionClipping() {
    me.mycellium.skymyce.hud.bootstrapHeadlessOwo()
    fun moving(width: Int, height: Int, color: Int, lift: Double, scale: Double = 1.0) =
        object : FlowLayout(Sizing.fixed(width), Sizing.fixed(height), Algorithm.VERTICAL) {
            init { surface(Surface { graphics, component ->
                graphics.fill(component.x(), component.y(), component.x() + component.width(), component.y() + component.height(), color)
            }) }
            override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
                (graphics as MenuGraphics).layer(y = -lift, scale = scale,
                    pivotX = x() + width() / 2.0, pivotY = y() + height() / 2.0) {
                    super.draw(graphics, mouseX, mouseY, partialTicks, delta)
                }
            }
        }
    val button = moving(40, 16, 0xFFFF0000.toInt(), 2.0)
    val buttonRow = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).child(button)
    val card = moving(110, 44, 0xFFFFFFFF.toInt(), 6.0, 1.009).apply {
        padding(Insets.of(8)); child(buttonRow)
    }
    val row = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).child(card)
    val list = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        padding(Insets.of(18, 26, 18, 22)); child(row)
    }
    val scroll = menuScroll(Sizing.fixed(150), Sizing.fixed(100), list)
    val root = UIContainers.verticalFlow(Sizing.fixed(150), Sizing.fixed(100)).child(scroll)
    root.inflate(Size.of(150, 100)); root.mount(null, 20, 20)
    val canvas = MenuCanvas()
    // The production constructor loads Minecraft atlases. This fixture needs only its real matrix,
    // scissor stack and canvas; owo/Minecraft clipping and component drawing run unchanged below.
    val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    fun graphics(): MenuGraphics {
        val graphics = unsafe.allocateInstance(MenuGraphics::class.java) as MenuGraphics
        fun field(owner: Class<*>, name: String, value: Any) = owner.getDeclaredField(name).apply { isAccessible = true }.set(graphics, value)
        val extractor = net.minecraft.client.gui.GuiGraphicsExtractor::class.java
        val scissors = extractor.declaredClasses.single { it.simpleName == "ScissorStack" }
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        field(extractor, "pose", org.joml.Matrix3x2fStack(16)); field(extractor, "scissorStack", scissors)
        field(extractor, "guiRenderState", net.minecraft.client.renderer.state.gui.GuiRenderState())
        field(MenuGraphics::class.java, "canvas", canvas)
        field(MenuGraphics::class.java, "menuWidth", 210); field(MenuGraphics::class.java, "menuHeight", 150)
        return graphics
    }
    fun paint(): BufferedImage {
        canvas.begin()
        root.draw(graphics(), -1, -1, 0f, 0f)
        return canvas.finish(210, 150, 1.0)
    }
    // These samples are outside the rows' original bounds but inside the stationary scroll viewport.
    val cardX = card.x() + 3; val cardY = card.y() - 4
    val buttonX = button.x() + 4; val buttonY = button.y() - 8
    val clipped = paint()
    check(clipped.getRGB(cardX, cardY) ushr 24 == 0) { "Fixture did not reproduce the card row's top clipping" }
    check(clipped.getRGB(buttonX, buttonY) != 0xFFFF0000.toInt()) { "Fixture did not reproduce the button row's top clipping" }
    allowMenuMotionOverflow(root)
    val visible = paint()
    check(visible.getRGB(cardX, cardY) == 0xFFFFFFFF.toInt()) { "Lifted card is still clipped to its original row" }
    check(visible.getRGB(buttonX, buttonY) == 0xFFFF0000.toInt()) { "Lifted button is still clipped by a padded card/action row" }
    check(!scroll.allowOverflow()) { "Motion overflow disabled the scroll viewport clip" }
    // Crossing a viewport edge must remain clipped even after every layout row allows motion.
    list.padding(Insets.none()); root.inflate(Size.of(150, 100)); root.mount(null, 20, 20)
    val edge = paint()
    check(edge.getRGB(card.x() + 3, scroll.y() - 1) ushr 24 == 0) { "Animated card escaped its scroll viewport" }
    check(root.childAt(card.x() + 3, scroll.y() - 1) == null) { "Overflow paint expanded input outside the scroll viewport" }
    // Later search/filter population creates fresh rows after build; the same prepaint pass handles them.
    val replacement = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).child(moving(40, 16, 0xFF00FF00.toInt(), 2.0))
    list.clearChildren(); list.child(replacement); allowMenuMotionOverflow(root)
    check(replacement.allowOverflow() && !scroll.allowOverflow()) { "Dynamically populated controls reverted to row clipping" }
    canvas.close()
    for (width in listOf(191, 240, 320, 680, 960, 1280)) {
        val available = menuDirectoryContentWidth(width)
        val columns = menuFeatureColumns(available)
        val cardWidth = (available - (columns - 1) * 8) / columns
        check(cardWidth * columns + (columns - 1) * 8 <= available)
        val scaleExpansion = cardWidth * GuiMotion.HEAVY.profile.hoverScale * 1.04 / 2
        check(scaleExpansion + 12 < 18) { "Heavy card scale/shadow exhausts its scroll gutter at width $width" }
        val header = UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(22))
        val section = UIContainers.verticalFlow(Sizing.fill(), Sizing.fixed(12))
        val directoryCard = MenuRevealCard("directory/alignment/$width", Sizing.fixed(cardWidth), Sizing.fixed(144))
        val cards = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); child(directoryCard)
            if (columns == 2) child(MenuRevealCard("directory/alignment/$width/right", Sizing.fixed(cardWidth), Sizing.fixed(144)))
        }
        val directoryList = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            padding(Insets.of(18, 26, 18, 22)); gap(8); child(section); child(cards)
        }
        val indentedSetting = MenuRevealCard("directory/search/$width", Sizing.fill(), Sizing.content()).apply {
            margins(Insets.left(14)); padding(Insets.of(10))
        }
        val settingActions = listOf(94, 54).map { it.coerceAtMost((available - 14 - 20).coerceAtLeast(1)) }
        val actionComponents = settingActions.map { UIContainers.verticalFlow(Sizing.fixed(it), Sizing.fixed(22)) }
        menuActionRows(settingActions, available - 14 - 20).forEach { indices ->
            indentedSetting.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(6); indices.forEach { child(actionComponents[it]) }
            })
        }
        directoryList.child(indentedSetting)
        val viewport = menuDirectoryScroll(directoryList)
        val directoryRoot = UIContainers.verticalFlow(Sizing.fixed(width), Sizing.fixed(180)).apply {
            padding(Insets.of(14, 14, 18, 18)); gap(9); child(header); child(viewport)
        }
        directoryRoot.inflate(Size.of(width, 180)); directoryRoot.mount(null, 20, 20)
        allowMenuMotionOverflow(directoryRoot)
        check(header.x() == section.x() && header.x() == directoryCard.x()) { "Directory sections/cards are indented beyond their header at width $width" }
        check(directoryList.width() - directoryList.padding().get().horizontal() == available) {
            "Directory action/card widths disagree with the mounted padded viewport at width $width"
        }
        check(directoryCard.x() - scaleExpansion - 12 > viewport.x() &&
            cards.x() + cards.width() + scaleExpansion + 12 < viewport.x() + viewport.width()) {
            "Header alignment removed the Heavy card's horizontal motion/shadow gutters at width $width"
        }
        check(!viewport.allowOverflow()) { "Aligned directory lost its stationary scroll clip" }
        check(viewport.x() == directoryRoot.x() && viewport.x() + viewport.width() == directoryRoot.x() + directoryRoot.width()) {
            "Aligned directory viewport/scrollbar extends outside its body input bounds"
        }
        check(directoryRoot.childAt(viewport.x() + viewport.width() - 1, viewport.y() + 2) === viewport) {
            "Aligned directory scrollbar edge cannot receive input"
        }
        check(directoryRoot.childAt(viewport.x() - 1, directoryCard.y() + 2) == null) {
            "Aligned directory expanded input outside the scroll viewport"
        }
        check(indentedSetting.x() == directoryCard.x() + 14 && indentedSetting.width() == available - 14) {
            "Matching search settings lost their nested indent or width at width $width"
        }
        check(actionComponents.all { it.x() + it.width() <= indentedSetting.x() + indentedSetting.width() - 10 }) {
            "Indented search actions extend past their card at width $width"
        }
    }
}

private fun checkRevealCardPointer() {
    val first = MenuRevealCard("fixture/first", Sizing.fixed(120), Sizing.fixed(80))
    val second = MenuRevealCard("fixture/second", Sizing.fixed(120), Sizing.fixed(80))
    val root = UIContainers.verticalFlow(Sizing.fixed(160), Sizing.fixed(220)).gap(12).children(listOf(first, second))
    root.inflate(Size.of(160, 220)); root.mount(null, 20, 30)
    val pose = MenuRevealCard::class.java.getDeclaredField("painted").apply { isAccessible = true }
    val hover = MenuRevealCard::class.java.getDeclaredField("logicalHover").apply { isAccessible = true }
    val clip = MenuRevealCard::class.java.getDeclaredField("paintedClip").apply { isAccessible = true }
    for (mode in GuiMotion.choices) {
        val profile = mode.profile
        for (card in listOf(first, second)) pose.set(card, MotionPose(scale = 1 + profile.hoverScale, y = -profile.cardLift,
            pivotX = card.x() + card.width() / 2.0, pivotY = card.y() + card.height() / 2.0))
        repeat(40) {
            menuCardPointer(root, first.x() + first.width() / 2.0, first.y() + first.height() - .25)
            check(hover.getBoolean(first) && !hover.getBoolean(second)) { "$mode feature-card feedback changed its own edge hover" }
        }
        val x = first.x() + first.width() / 2.0; val y = first.y() + first.height() / 2.0
        val point = checkNotNull(menuCardPointer(root, x, y))
        val painted = pose.get(first) as MotionPose
        near(point.first, painted.inverseX(x), "Animated feature controls lost their visible click coordinates")
        near(point.second, painted.inverseY(y), "Animated feature controls lost their visible click coordinates")
        menuCardPointer(root, second.x() + second.width() / 2.0, second.y() + second.height() - .25)
        check(!hover.getBoolean(first) && hover.getBoolean(second)) { "Adjacent cards retained stale hover" }
        menuCardPointer(root, -1.0, -1.0)
        check(!hover.getBoolean(first) && !hover.getBoolean(second)) { "Leaving the card tree retained hover" }
    }
    clip.set(first, MenuRect(first.x().toDouble(), first.y().toDouble(), first.width().toDouble(), 10.0))
    menuCardPointer(root, first.x() + 5.0, first.y() + 50.0)
    check(!hover.getBoolean(first)) { "A clipped card area became hoverable" }
    clip.set(first, null); pose.set(first, MotionPose(alpha = 0.0))
    menuCardPointer(root, first.x() + 5.0, first.y() + 5.0)
    check(!hover.getBoolean(first)) { "An unrevealed card became hoverable" }
}

private fun checkLivingMotion() {
    var ambient = menuAmbientStep(Long.MIN_VALUE, 100_000_000, 100_000_000, false)
    repeat(10000) { check(menuAmbientStep(ambient, 100_000_000L + it * 16_000_000L, 100_000_000, false) == ambient) {
        "Settled Heavy motion kept invalidating the raster"
    } }
    ambient = menuAmbientStep(ambient, 600_000_000, 100_000_000, true)
    check(ambient == 6L && menuAmbientStep(ambient, 650_000_000, 100_000_000, true) == ambient)
    check(menuAmbientStep(ambient, 10_000_000_000, 100_000_000, false) == ambient)
    val profiles = listOf(GuiMotion.LIGHT, GuiMotion.MEDIUM, GuiMotion.HEAVY).map { it.profile }
    check(profiles.zipWithNext().all { (a, b) ->
        b.cardLift > a.cardLift && b.hoverScale > a.hoverScale && b.shadowStrength > a.shadowStrength &&
            b.glowStrength > a.glowStrength && b.revealOffset > a.revealOffset
    }) { "Light/Medium/Heavy differ only in timing, rather than physical treatment" }
    check(GuiMotion.FULL.profile === GuiMotion.MEDIUM.profile && GuiMotion.FULL !in GuiMotion.choices) {
        "Legacy Full config lost its compatible Medium personality"
    }
    check(!GuiMotion.LIGHT.profile.ambient && !GuiMotion.MEDIUM.profile.ambient && GuiMotion.HEAVY.profile.ambient)
    check(GuiMotion.REDUCED.profile.cardLift == 0.0 && GuiMotion.REDUCED.profile.pageOffset == 0.0)

    val lightClock = MotionClock(); val light = lightClock.value()
    light.animateTo(1.0, 200, MotionEasing.SETTLE, GuiMotion.LIGHT)
    for (time in 0L..135L) {
        lightClock.millis(time)
        check(light.sample() in 0.0..1.0) { "Light kept an expressive spring" }
    }
    lightClock.millis(136); check(light.sample() == 1.0 && !light.running) { "Light lost its fast response" }
    val springs = listOf(GuiMotion.MEDIUM, GuiMotion.HEAVY).map { mode ->
        val clock = MotionClock(); val value = clock.value()
        value.animateTo(1.0, 200, MotionEasing.SETTLE, mode)
        clock.millis(150); val settle = value.sample()
        check(settle in 1.0..1.04) { "$mode spring became distracting: $settle" }
        clock.millis(200); check(value.sample() == 1.0 && !value.running) { "$mode extended its deadline" }
        settle
    }
    check(springs[1] > springs[0]) { "Heavy lost its richer spring response" }

    var persisted = false; var writes = 0
    val intro = MenuIntroduction({ persisted }, { persisted = true; writes++ })
    check(intro.claim())
    repeat(20) { check(!intro.claim()) { "Refresh/remount replayed the app introduction" } }
    check(!persisted && writes == 0) { "Introduction was saved before it settled" }
    intro.complete(); intro.complete()
    check(persisted && writes == 1)
    check(!MenuIntroduction({ persisted }, { error("Already played introduction wrote again") }).claim()) {
        "Restart ignored the persisted introduction flag"
    }
    val unclaimed = MenuIntroduction({ false }, { error("Unclaimed introduction completed") })
    unclaimed.complete()

    val clock = MotionClock(); val reveals = MenuRevealState(maximum = 2) { clock.nanos }
    near(reveals.progress("dungeons/settings", false, GuiMotion.MEDIUM), 0.0, "Offscreen reveal started")
    check(reveals.size == 0)
    near(reveals.progress("dungeons/settings", true, GuiMotion.MEDIUM, delayMillis = 36), 0.0, "First viewport reveal")
    clock.millis(18)
    near(reveals.progress("dungeons/settings", true, GuiMotion.MEDIUM, delayMillis = 36), 0.0, "Reveal stagger")
    clock.millis(100)
    check(reveals.progress("dungeons/settings", true, GuiMotion.MEDIUM) in .01.. .99)
    clock.millis(300)
    near(reveals.progress("dungeons/settings", false, GuiMotion.MEDIUM), 1.0, "Scrolled-away reveal remained active")
    near(reveals.progress("dungeons/settings", true, GuiMotion.HEAVY, delayMillis = 500), 1.0, "Back/refresh restarted a stable reveal")
    near(reveals.progress("dungeons/other", false, GuiMotion.HEAVY), 0.0, "Another offscreen section revealed early")
    check(reveals.size == 1)
    near(reveals.progress("dungeons/other", true, GuiMotion.OFF), 1.0, "Off reveal animated")
    check(reveals.size == 2)
    near(reveals.progress("overflow", true, GuiMotion.HEAVY), 1.0, "Bounded reveal cache replayed an evicted item")
    check(reveals.size == 2)
    near(reveals.progress("dungeons/settings", true, GuiMotion.MEDIUM), 1.0, "Overflow evicted an existing logical identity")

    for (mode in GuiMotion.choices) {
        val modeClock = MotionClock(); val state = MenuRevealState { modeClock.nanos }
        state.progress("first", true, mode, delayMillis = 20)
        modeClock.millis(30); val visible = state.progress("first", true, mode)
        check(visible in 0.0..1.0)
        near(state.progress("first", true, GuiMotion.OFF), 1.0, "Mode change retained a reveal in $mode")
        modeClock.millis(500)
        near(state.progress("first", true, mode, delayMillis = 100), 1.0, "Mode change restarted a completed reveal")
    }
    println("Living motion checks passed: distinct physical modes, bounded springs, persisted one-time intro and stable first-viewport reveals across refresh/back/mode changes")
}

private fun checkMotionToasts() {
    val clock = MotionClock(); val toasts = MenuToasts { clock.nanos }
    val field = MenuToasts::class.java.getDeclaredField("messages").apply { isAccessible = true }
    fun messages() = (field.get(toasts) as List<*>).map { toast ->
        toast!!::class.java.getDeclaredField("text").apply { isAccessible = true }.get(toast) as String
    }
    toasts.show("Saved", MenuToastKind.SUCCESS)
    clock.millis(100); toasts.show("Saved", MenuToastKind.SUCCESS)
    check(messages() == listOf("Saved")) { "Repeated action feedback duplicated the last toast" }
    toasts.show("Notice", MenuToastKind.INFO)
    toasts.show("Warning", MenuToastKind.WARNING)
    toasts.show("Error", MenuToastKind.ERROR)
    check(messages() == listOf("Notice", "Warning", "Error")) { "Toast stack is not bounded to three current messages" }
    clock.millis(5000); toasts.show("Error", MenuToastKind.ERROR)
    check(messages() == listOf("Error")) { "Expired, undrawn toast prevented a fresh notification" }
    toasts.show("x".repeat(255) + "\uD83D\uDE80", MenuToastKind.INFO)
    check(messages().last().length <= 256 && messages().last().lastOrNull()?.isHighSurrogate() != true) {
        "Bounded toast text cut a Unicode surrogate pair"
    }
    toasts.clear(); check(messages().isEmpty()) { "Closed screen retained toast text and motion" }
    clock.millis(10_000); toasts.show("Expiry", MenuToastKind.INFO)
    clock.millis(13_599); toasts.show("", MenuToastKind.INFO)
    check(messages() == listOf("Expiry"))
    clock.millis(13_600); toasts.show("Fresh", MenuToastKind.INFO)
    check(messages() == listOf("Fresh")) { "Toast animation extended the 3600 ms logical lifetime" }
    toasts.clear()
}

private fun checkMotionScroll() {
    // The exact helpers used by MenuScroll remain runnable without owo's Fabric-only static setup.
    val clock = MotionClock(); val position = clock.value()
    position.scrollTo(80.0, GuiMotion.FULL)
    clock.millis(45); val before = position.scrollTo(80.0, GuiMotion.FULL)
    check(before > 0.0 && before < 80.0)
    near(position.scrollTo(140.0, GuiMotion.FULL), before, "Repeated wheel input snapped the visible scroll pose")
    clock.millis(200); near(position.scrollTo(140.0, GuiMotion.FULL), 140.0, "Retargeted wheel motion did not reach its logical offset")
    near(position.scrollTo(100.0, GuiMotion.FULL, instant = true), 100.0, "Instant scrollbar drag was still smoothed")
    clock.millis(400); near(position.sample(), 100.0, "Old wheel animation continued after an instant drag")
    near(position.clampScroll(20.5), 20.5, "Shrinking content left an out-of-bounds scroll timeline")
    clock.millis(600); near(position.sample(), 20.5, "Clamped content resumed its old out-of-bounds motion")
    near(position.scrollTo(.5, GuiMotion.OFF), .5, "Off mode retained smoothed wheel input")
    position.scrollTo(10.0, GuiMotion.REDUCED)
    clock.millis(690); near(position.sample(), 10.0, "Reduced wheel motion exceeded its timing budget")
}

private fun checkMotionFocus() {
    // Ancestry does not depend on a native input/button renderer, so layout-only components suffice.
    val search = UIComponents.box(Sizing.fixed(160), Sizing.fixed(20))
    val oldResult = UIComponents.box(Sizing.fixed(120), Sizing.fixed(20))
    val results = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).child(oldResult)
    val root = UIContainers.verticalFlow(Sizing.fill(), Sizing.fill()).children(listOf(search, results))
    root.inflate(Size.of(320, 180)); root.mount(null, 0, 0)
    check(menuComponentAttached(root, search) && menuComponentAttached(root, oldResult))
    check(menuComponentAttached(root, root))
    check(!menuComponentAttached(root, null))
    results.clearChildren()
    val refreshedResult = UIComponents.box(Sizing.fixed(120), Sizing.fixed(20))
    results.child(refreshedResult)
    check(!menuComponentAttached(root, oldResult)) { "A removed result still accepts keyboard focus dispatch" }
    check(menuComponentAttached(root, search)) { "Refreshing results detached the still-active search input" }
    check(menuComponentAttached(root, refreshedResult))
    root.removeChild(results)
    check(!menuComponentAttached(root, refreshedResult)) { "A descendant retained focus through its detached container" }
    check(menuComponentAttached(root, search))
    root.child(results)
    check(menuComponentAttached(root, refreshedResult))
    val foreignRoot = UIContainers.verticalFlow(Sizing.fill(), Sizing.fill())
    check(!menuComponentAttached(foreignRoot, search))
    root.clearChildren()
    check(!menuComponentAttached(root, search) && !menuComponentAttached(root, refreshedResult))
    println("Menu focus checks passed: refreshed/detached results cannot receive stale input; attached search and remounted descendants retain ownership")
}

private fun checkMotionCanvas() {
    fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val icon = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, if ((x + y) % 2 == 0) 0xFFCAAAF4.toInt() else 0x90665B91.toInt())
    }
    fun body(canvas: MenuCanvas) {
        canvas.opacity = .8
        canvas.group = "body"
        canvas.clip = MenuCanvas.Clip(8, 8, 120, 70)
        canvas.transform = MenuCanvas.Transform(1.0, .04, -.03, 1.0, 6.0, -4.0)
        canvas.gradient(12.0, 18.0, 84.0, 35.0, 0xD0412859.toInt(), 0xE024173B.toInt(), 0xB027173F.toInt(), 0xF035214F.toInt())
        canvas.fill(22.0, 24.0, 18.0, 12.0, 0xC0B392E5.toInt())
        canvas.text(listOf(MenuCanvas.Run("Motion ✓ café 中文", 0xE0F4EAFD.toInt())), 16.0, 29.0)
        canvas.picture(icon, 89.0, 20.0, 21.0, 21.0)
    }
    val source = MenuCanvas()
    source.begin(); source.group = "shell"; source.fill(0.0, 0.0, 180.0, 100.0, 0xFF100D18.toInt()); body(source)
    val capturedPixels = pixels(source.finish(180, 100, 1.25))
    val isolated = MenuCanvas(); isolated.begin(); body(isolated)
    val isolatedPixels = pixels(isolated.finish(180, 100, 1.25)); isolated.close()
    source.begin(); source.group = "shell"; source.fill(0.0, 0.0, 180.0, 100.0, 0xFF100D18.toInt()); body(source)
    source.group = "tooltip"; source.opacity = 1.0; source.clip = null; source.transform = MenuCanvas.Transform()
    source.fill(28.0, 25.0, 24.0, 24.0, 0xFFFF0000.toInt()); source.finish(180, 100, 1.25)
    val snapshot = checkNotNull(source.snapshot("body"))
    check(source.snapshot("missing") == null)
    // Outgoing content must contain inert commands, rather than retaining the originating live canvas.
    check(snapshot.replay.javaClass.declaredFields.none { MenuCanvas::class.java.isAssignableFrom(it.type) }) {
        "Snapshot retains its originating canvas instead of inert paint commands"
    }
    source.begin(); source.fill(0.0, 0.0, 180.0, 100.0, -1); source.finish(180, 100, 1.25)
    source.close()
    check(source.snapshot("body") == null && source.dirtyRegion == null)

    val captured = MenuCanvas(); captured.begin(); captured.replay(snapshot)
    val rasterPixels = pixels(captured.finish(180, 100, 1.25))
    check(rasterPixels.count { it ushr 24 != 0 } in 1 until rasterPixels.size) { "Snapshot failed to crop its content group" }
    check(rasterPixels.contentEquals(isolatedPixels)) { "Content snapshot included underlying shell pixels or an overlaid tooltip" }
    captured.begin(); captured.group = "shell"; captured.fill(0.0, 0.0, 180.0, 100.0, 0xFF100D18.toInt()); captured.replay(snapshot)
    val restoredPixels = pixels(captured.finish(180, 100, 1.25))
    for (index in restoredPixels.indices) for (shift in listOf(0, 8, 16, 24)) check(abs((restoredPixels[index] ushr shift and 255) - (capturedPixels[index] ushr shift and 255)) <= 2) {
        "Snapshot changed the visible pose when composited over its original shell"
    }
    captured.close()

    val backing = MenuCanvas()
    backing.begin(); backing.group = "shell"; backing.fill(0.0, 0.0, 80.0, 50.0, 0x7043295D)
    backing.group = "content"; backing.fill(10.0, 10.0, 45.0, 25.0, 0x806E4D96.toInt())
    val backingPixels = pixels(backing.finish(80, 50, 1.0))
    var contentCapture = checkNotNull(backing.snapshot("content"))
    repeat(50) {
        backing.begin(); backing.group = "shell"; backing.fill(0.0, 0.0, 80.0, 50.0, 0x7043295D)
        backing.group = "content"; backing.replay(contentCapture)
        check(pixels(backing.finish(80, 50, 1.0)).contentEquals(backingPixels)) {
            "Repeated content captures accumulated the shell's translucent backing"
        }
        contentCapture = checkNotNull(backing.snapshot("content"))
    }
    backing.close()

    val blend = MenuCanvas()
    fun blendShell() {
        blend.group = "shell"; blend.fill(0.0, 0.0, 100.0, 80.0, 0xFF1B1127.toInt())
        blend.group = "content"
    }
    fun blendBody(alternate: Boolean = false) {
        blend.fill(12.0, 10.0, 70.0, 50.0, 0x806E4D96.toInt())
        blend.fill(if (alternate) 42.0 else 18.0, 16.0, 32.0, 24.0, 0xC0C7A4ED.toInt())
        blend.text(listOf(MenuCanvas.Run("café ✓", 0xE0EFE3FF.toInt())), 20.0, 44.0)
    }
    fun nearPixels(actual: IntArray, expected: IntArray, message: String) {
        check(actual.size == expected.size)
        for (index in actual.indices) for (shift in listOf(0, 8, 16, 24)) {
            check(abs((actual[index] ushr shift and 255) - (expected[index] ushr shift and 255)) <= 2) { message }
        }
    }
    blend.begin(); blendShell(); blendBody()
    val stableBlendPixels = pixels(blend.finish(100, 80, 1.25))
    val blendSnapshot = checkNotNull(blend.snapshot("content"))
    val blendBuffer = MenuCanvas::class.java.getDeclaredField("transitionBlend").apply { isAccessible = true }
    var reusedBuffer: Any? = null
    for (progress in listOf(0.0, .25, .5, .75, .99, 1.0)) {
        blend.begin(); blendShell()
        blend.transition(blendSnapshot, progress, MenuCanvas.Clip(10, 8, 78, 58)) { blendBody() }
        nearPixels(pixels(blend.finish(100, 80, 1.25)), stableBlendPixels, "Identical translucent content changed brightness during a page transition at $progress")
        if (progress < 1.0) {
            val buffer = checkNotNull(blendBuffer.get(blend))
            if (reusedBuffer == null) reusedBuffer = buffer
            else check(buffer === reusedBuffer) { "Active page transition reallocated its blend raster" }
        }
    }
    blend.begin(); blendShell()
    blend.transition(blendSnapshot, .4, MenuCanvas.Clip(10, 8, 78, 58), -2.0) { blendBody(alternate = true) }
    val interruptedBlendPixels = pixels(blend.finish(100, 80, 1.25))
    val interruptedBlendSnapshot = checkNotNull(blend.snapshot("content"))
    blend.begin(); blendShell()
    blend.transition(interruptedBlendSnapshot, 0.0, MenuCanvas.Clip(10, 8, 78, 58)) { blendBody() }
    nearPixels(pixels(blend.finish(100, 80, 1.25)), interruptedBlendPixels, "Rapid A→B→C interrupted the visible page pose")
    blend.begin(); blendShell(); blend.opacity = .5; blendBody()
    val halfOpacityPixels = pixels(blend.finish(100, 80, 1.25))
    val halfOpacitySnapshot = checkNotNull(blend.snapshot("content"))
    for (progress in listOf(0.0, .5, .99)) {
        blend.begin(); blendShell(); blend.opacity = .5
        blend.transition(halfOpacitySnapshot, progress, MenuCanvas.Clip(10, 8, 78, 58)) { blendBody() }
        nearPixels(pixels(blend.finish(100, 80, 1.25)), halfOpacityPixels, "Parent opacity was applied twice during a page transition")
    }
    blend.close()
    for (fieldName in listOf("transitionOld", "transitionNew", "transitionBlend")) {
        check(MenuCanvas::class.java.getDeclaredField(fieldName).apply { isAccessible = true }.get(blend) == null) {
            "Closed screen retained transition rasters"
        }
    }

    val retained = MenuCanvas()
    var previousPixels: IntArray? = null
    var previousRevision = -1L
    var previousImage: BufferedImage? = null
    val boundary = MenuCanvas.Clip(13, 9, 92, 56)
    for ((alpha, dx, dy) in listOf(Triple(1.0, 0.0, 0.0), Triple(.6, 2.25, 1.5), Triple(.3, -5.0, 3.0),
        Triple(0.0, 0.0, 0.0), Triple(1.0, 0.0, 0.0), Triple(1.0, 0.0, 0.0))) {
        retained.begin(); retained.group = "outgoing"; retained.replay(snapshot, alpha, dx, dy, boundary)
        val image = retained.finish(180, 100, 1.25)
        val fresh = MenuCanvas(); fresh.begin(); fresh.replay(snapshot, alpha, dx, dy, boundary)
        val actual = pixels(image); val expected = pixels(fresh.finish(180, 100, 1.25))
        check(actual.contentEquals(expected)) { "Retained snapshot fade/translation/clipping differs from a fresh frame" }
        check(retained.snapshot("body") == null) { "Replayed content retained its old capture group" }
        if (previousPixels?.contentEquals(actual) == true) {
            check(retained.dirtyRegion == null && retained.revision == previousRevision && image === previousImage) {
                "Settled content reallocated or repainted its retained frame"
            }
        } else {
            val dirty = checkNotNull(retained.dirtyRegion)
            check(Rectangle(0, 0, image.width, image.height).contains(dirty))
            previousPixels?.let { old -> actual.indices.forEach { index ->
                if (!dirty.contains(index % image.width, index / image.width)) check(old[index] == actual[index]) {
                    "Fading pixels changed outside the reported upload region"
                }
            } }
        }
        previousPixels = actual; previousRevision = retained.revision; previousImage = image
        fresh.close()
    }
    val interrupted = MenuCanvas()
    val pendingPaints = MenuCanvas::class.java.getDeclaredField("pending").apply { isAccessible = true }
    for (count in listOf(10, 100)) {
        interrupted.begin(); interrupted.group = "page"
        repeat(count) { index -> interrupted.fill((index % 10 * 12).toDouble(), (index / 10 * 7).toDouble(), 14.0, 9.0, 0xD06F5499.toInt()) }
        interrupted.finish(180, 100, 1.0)
        var interruptedSnapshot = checkNotNull(interrupted.snapshot("page"))
        repeat(100) { step ->
            interrupted.begin(); interrupted.group = "page"
            interrupted.replay(interruptedSnapshot, .8)
            check((pendingPaints.get(interrupted) as Collection<*>).size == 1) { "Repeated interruptions accumulated old paint history" }
            interrupted.fill((step % 12).toDouble(), 2.0, 4.0, 3.0, 0xCCBA91E9.toInt())
            interrupted.finish(180, 100, 1.0)
            interruptedSnapshot = checkNotNull(interrupted.snapshot("page"))
            check(interruptedSnapshot.replay.javaClass.declaredFields.none { MenuCanvas.Snapshot::class.java.isAssignableFrom(it.type) }) {
                "Interrupted snapshot retained a chain of previous captures"
            }
        }
    }
    interrupted.close()
    val layer = MenuCanvas(); layer.begin(); layer.opacity = .5
    layer.fill(2.0, 2.0, 20.0, 16.0, 0xFF884422.toInt())
    val layerPixel = layer.finish(30, 24, 1.0).getRGB(10, 10)
    check(layerPixel ushr 24 in 127..128 && abs((layerPixel ushr 16 and 255) - 136) <= 1) {
        "Layer opacity changed color or failed to apply alpha"
    }
    layer.close(); retained.close()
    fun gradientFrame(alpha: Double): BufferedImage {
        val canvas = MenuCanvas(); canvas.begin(); canvas.opacity = alpha
        canvas.gradient(3.0, 4.0, 44.0, 20.0, 0xD04B2F68.toInt(), 0xB09C79BF.toInt(), 0xA062417A.toInt(), 0xF03B2253.toInt())
        return canvas.finish(50, 32, 1.0).also { canvas.close() }
    }
    val originalGradient = gradientFrame(1.0)
    val referenceGradient = BufferedImage(50, 32, BufferedImage.TYPE_INT_ARGB)
    val referenceGraphics = referenceGradient.createGraphics()
    referenceGraphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f)
    referenceGraphics.drawImage(originalGradient, 0, 0, null); referenceGraphics.dispose()
    check(pixels(gradientFrame(.5)).contentEquals(pixels(referenceGradient))) { "Cached gradient opacity differs from a composited raster layer" }
    for (quality in GuiQuality.entries) {
        val flat = MenuCanvas(); flat.begin(); flat.fill(0.0, 0.0, 44.0, 32.0, 0xFF171021.toInt())
        flat.gradient(4.0, 4.0, 36.0, 24.0, 0xB0875BA0.toInt(), 0xB0875BA0.toInt(), 0xB0875BA0.toInt(), 0xB0875BA0.toInt())
        val image = flat.finish(44, 32, quality.rasterScale)
        val first = (4 * quality.rasterScale).toInt() + 2
        val last = (28 * quality.rasterScale).toInt() - 3
        val x = (22 * quality.rasterScale).toInt()
        val color = image.getRGB(x, first)
        for (y in first..last) check(image.getRGB(x, y) == color) {
            "$quality flat gradient varies at raster scale ${quality.rasterScale}, row $y: " +
                "expected 0x${color.toUInt().toString(16)}, actual 0x${image.getRGB(x, y).toUInt().toString(16)}"
        }
        flat.close()
    }

    val mxBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    if (mxBean?.isThreadAllocatedMemorySupported == true) mxBean.isThreadAllocatedMemoryEnabled = true
    if (mxBean?.isThreadCpuTimeSupported == true) mxBean.isThreadCpuTimeEnabled = true
    fun allocated() = mxBean?.takeIf { it.isThreadAllocatedMemoryEnabled }?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L
    fun cpu() = mxBean?.takeIf { it.isThreadCpuTimeEnabled }?.currentThreadCpuTime ?: -1L
    fun measure(canvas: MenuCanvas, quality: GuiQuality, moving: Boolean, snapshot: MenuCanvas.Snapshot? = null): String {
        val frames = 60
        val startAllocated = allocated(); val startCpu = cpu(); val start = System.nanoTime()
        var dirtyPixels = 0L
        repeat(frames) { index ->
            val phase = if (moving) (index % 21) / 20.0 else 0.0
            motionScene(canvas, quality, phase = phase, snapshot = snapshot, transitionProgress = .1 + .8 * phase)
            canvas.finish(480, 280, quality.rasterScale)
            dirtyPixels += canvas.dirtyRegion?.let { it.width.toLong() * it.height } ?: 0
        }
        val wall = (System.nanoTime() - start) / frames / 1_000_000.0
        val cpuPerFrame = cpu().takeIf { it >= 0 && startCpu >= 0 }?.let { (it - startCpu) / frames / 1_000_000.0 }
        val allocatedPerFrame = allocated().takeIf { it >= 0 && startAllocated >= 0 }?.let { (it - startAllocated) / frames }
        val resources = canvas.cacheSizes()
        return "wall=%.3fms CPU=%s allocated=%s dirty=%dpx/frame raster=%dB blend=%dB".format(java.util.Locale.ROOT, wall,
            cpuPerFrame?.let { "%.3fms".format(java.util.Locale.ROOT, it) } ?: "unavailable",
            allocatedPerFrame?.let { "${it}B/frame" } ?: "unavailable", dirtyPixels / frames,
            resources.getValue("rasterBytes"), resources.getValue("transitionBytes"))
    }
    val reports = mutableListOf("Headless 480x280 raster fixture; 24 warm-up and 60 measured frames per quality. These measurements do not represent Minecraft FPS or GPU frame time.")
    for (quality in GuiQuality.entries) {
        val canvas = MenuCanvas()
        repeat(24) { motionScene(canvas, quality, phase = (it % 21) / 20.0); canvas.finish(480, 280, quality.rasterScale) }
        motionScene(canvas, quality); val image = canvas.finish(480, 280, quality.rasterScale); val revision = canvas.revision
        repeat(25) {
            motionScene(canvas, quality)
            check(canvas.finish(480, 280, quality.rasterScale) === image && canvas.revision == revision && canvas.dirtyRegion == null) {
                "$quality repainted a static frame or continuously recreated its raster"
            }
        }
        ImageIO.write(image, "png", File("motion-${quality.name.lowercase().replace('_', '-')}-open.png"))
        val report = "Headless menu renderer $quality idle: ${measure(canvas, quality, false)}; moving: ${measure(canvas, quality, true)}"
        println(report); reports += report
        val cache = MenuCanvas::class.java.getDeclaredField("gradients").apply { isAccessible = true }
        check((cache.get(canvas) as Map<*, *>).size <= 4) { "$quality retained unbounded gradient images" }
        motionScene(canvas, quality); canvas.finish(480, 280, quality.rasterScale)
        val transitionSnapshot = checkNotNull(canvas.snapshot("body"))
        repeat(24) {
            motionScene(canvas, quality, phase = .5, snapshot = transitionSnapshot, transitionProgress = .1 + .8 * (it % 21) / 20)
            canvas.finish(480, 280, quality.rasterScale)
        }
        val cachedGradients = (cache.get(canvas) as Map<*, *>).values.toList()
        val transitionReport = "Headless menu renderer $quality active page blend: ${measure(canvas, quality, true, transitionSnapshot)}"
        println(transitionReport); reports += transitionReport
        check((cache.get(canvas) as Map<*, *>).values.all { it in cachedGradients }) {
            "$quality recreated warmed gradient images on each active page frame"
        }
        check((cache.get(canvas) as Map<*, *>).size <= 4) { "$quality retained unbounded page gradient images" }
        ImageIO.write(canvas.finish(480, 280, quality.rasterScale), "png", File("motion-${quality.name.lowercase().replace('_', '-')}-page-blend.png"))
        canvas.releaseTransition()
        check(canvas.cacheSizes().getValue("transitionBytes") == 0L) { "Settled page retained its temporary blend buffers" }
        motionScene(canvas, quality); canvas.finish(480, 280, quality.rasterScale)
        canvas.close()
        check((cache.get(canvas) as Map<*, *>).isEmpty()) { "Closed renderer retained gradients" }
        for (name in listOf("pending", "previous")) {
            val paints = MenuCanvas::class.java.getDeclaredField(name).apply { isAccessible = true }.get(canvas) as Collection<*>
            check(paints.isEmpty()) { "Closed renderer retained $name content" }
        }
        check(MenuCanvas::class.java.getDeclaredField("image").apply { isAccessible = true }.get(canvas) == null) {
            "Closed renderer retained its raster"
        }
    }
    File("performance.txt").writeText(reports.joinToString("\n", postfix = "\n"))
    for ((name, pose, mode) in listOf(
        Triple("enter", MotionPose(.6, .97, 0.0, 9.0, 240.0, 140.0), GuiMotion.FULL),
        Triple("transition", MotionPose(.8, 1.0, -14.0), GuiMotion.FULL),
        Triple("reduced", MotionPose(.8), GuiMotion.REDUCED),
        Triple("off", MotionPose(), GuiMotion.OFF),
    )) {
        val canvas = MenuCanvas(); motionScene(canvas, GuiQuality.HIGH, pose, .7, mode)
        ImageIO.write(canvas.finish(480, 280, GuiQuality.HIGH.rasterScale), "png", File("motion-high-$name.png"))
        canvas.close()
    }
}

/** Uses the real rasterizer; fixture labels distinguish it from an in-game screenshot. */
private fun motionScene(canvas: MenuCanvas, quality: GuiQuality, pose: MotionPose = MotionPose(), phase: Double = 0.0,
                        mode: GuiMotion = GuiMotion.FULL, snapshot: MenuCanvas.Snapshot? = null, transitionProgress: Double = 1.0) {
    canvas.begin()
    canvas.fill(0.0, 0.0, 480.0, 280.0, 0xFF0D0B16.toInt())
    if (quality.ambient && mode == GuiMotion.FULL) {
        canvas.arc(418.0, 45.0, 62.0, 0.0, 360.0, 0x133D2A66, 36.0)
        canvas.arc(49.0, 250.0, 49.0, 0.0, 360.0, 0x163D2A66, 28.0)
    }
    canvas.opacity = pose.alpha
    canvas.transform = MenuCanvas.Transform(pose.scale, 0.0, 0.0, pose.scale,
        pose.x + pose.pivotX * (1.0 - pose.scale), pose.y + pose.pivotY * (1.0 - pose.scale))
    for (layer in quality.shadowLayers downTo 1) canvas.fill(18.0 + layer * 2, 24.0 + layer * 2, 444.0, 232.0, 0x26000000)
    canvas.fill(18.0, 24.0, 444.0, 232.0, 0xFF1A1429.toInt())
    if (quality != GuiQuality.EXTREMELY_LIGHT) canvas.gradient(19.0, 25.0, 442.0, 52.0,
        0xFF342249.toInt(), 0xFF251A36.toInt(), 0xFF251A36.toInt(), 0xFF281D3C.toInt())
    canvas.text(listOf(MenuCanvas.Run("SKY-MYCE", 0xFFF1E7FF.toInt(), bold = true)), 32.0, 31.0, 17.0)
    canvas.text(listOf(MenuCanvas.Run("Motion system · $quality", 0xFFBAACD1.toInt())), 33.0, 56.0, 9.0)
    canvas.fill(18.0, 78.0, 106.0, 178.0, 0xFF151020.toInt())
    canvas.fill(25.0, 101.0, 93.0, 25.0, 0xFF392650.toInt())
    canvas.fill(25.0, 101.0, 2.0, 25.0, 0xFFD2ACFF.toInt())
    for ((index, text) in listOf("Dungeons", "Cosmetics", "Friends", "Settings").withIndex()) {
        canvas.text(listOf(MenuCanvas.Run(text, if (index == 0) 0xFFF4EAFE.toInt() else 0xFFAB9BC0.toInt())), 36.0, 108.0 + index * 30)
    }
    canvas.group = "body"
    fun drawBody() {
        canvas.text(listOf(MenuCanvas.Run("Your client, in motion", 0xFFF4EAFE.toInt(), bold = true)), 141.0, 92.0, 14.0)
        canvas.text(listOf(MenuCanvas.Run("Renderer fixture · $mode motion", 0xFFAE9BC8.toInt())), 141.0, 115.0, 9.0)
        if (quality == GuiQuality.EXTREMELY_LIGHT) canvas.fill(140.0, 141.0, 299.0, 37.0, 0xFF251C37.toInt())
        else canvas.gradient(140.0, 141.0, 299.0, 37.0,
            0xFF352342.toInt(), 0xFF251C37.toInt(), 0xFF251C37.toInt(), 0xFF2B1F3B.toInt())
        canvas.text(listOf(MenuCanvas.Run("Dungeon alerts", 0xFFE7DDF5.toInt())), 150.0, 152.0)
        if (quality.glow && mode == GuiMotion.FULL) canvas.fill(388.0, 150.0, 43.0, 19.0, 0x224F367A)
        canvas.fill(392.0, 153.0, 33.0, 13.0, 0xFF785BAA.toInt())
        canvas.arc(399.0 + 19 * phase, 159.5, 4.5, 0.0, 360.0, 0xFFF0E4FF.toInt())
        canvas.text(listOf(MenuCanvas.Run("Interaction feedback", 0xFFE7DDF5.toInt())), 149.0, 190.0)
        canvas.fill(150.0, 213.0, 267.0, 3.0, 0xFF48365F.toInt())
        canvas.fill(150.0, 213.0, 140.0 + phase * 94, 3.0, 0xFFBB91F0.toInt())
        canvas.arc(290.0 + phase * 94, 214.5, 4.0, 0.0, 360.0, 0xFFF0E4FF.toInt())
        canvas.clip = MenuCanvas.Clip(140, 231, 288, 20)
        canvas.text(listOf(MenuCanvas.Run("Unicode · café · 中文 · ✓ · responsive state changes stay immediate", 0xFFB6A4CC.toInt())), 149.0, 235.0, 9.0)
    }
    if (snapshot == null) drawBody()
    else canvas.transition(snapshot, transitionProgress, MenuCanvas.Clip(124, 78, 338, 178), outgoingX = -transitionProgress * 18) {
        val transform = canvas.transform
        canvas.transform = transform.copy(x = transform.x + (1 - transitionProgress) * 18)
        drawBody()
        canvas.transform = transform
    }
}
