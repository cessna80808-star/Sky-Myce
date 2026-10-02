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
    check(GuiMotion.entries.map { it.toString() } == listOf("Full", "Reduced", "Off"))
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
    println("Menu motion checks passed: 30/60/120/240 FPS, reversal/stagger/cancellation, Reduced/Off, bounded TTL fades, clock/input safety, quality budgets, transformed input and retained rendering")
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
    for (index in rasterPixels.indices) if (rasterPixels[index] ushr 24 != 0) check(rasterPixels[index] == capturedPixels[index]) {
        "Snapshot changed the visible pose when its source closed"
    }
    captured.close()

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

    val mxBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    if (mxBean?.isThreadAllocatedMemorySupported == true) mxBean.isThreadAllocatedMemoryEnabled = true
    if (mxBean?.isThreadCpuTimeSupported == true) mxBean.isThreadCpuTimeEnabled = true
    fun allocated() = mxBean?.takeIf { it.isThreadAllocatedMemoryEnabled }?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L
    fun cpu() = mxBean?.takeIf { it.isThreadCpuTimeEnabled }?.currentThreadCpuTime ?: -1L
    fun measure(canvas: MenuCanvas, quality: GuiQuality, moving: Boolean): String {
        val frames = 60
        val startAllocated = allocated(); val startCpu = cpu(); val start = System.nanoTime()
        var dirtyPixels = 0L
        repeat(frames) { index ->
            motionScene(canvas, quality, phase = if (moving) (index % 21) / 20.0 else 0.0)
            canvas.finish(480, 280, quality.rasterScale)
            dirtyPixels += canvas.dirtyRegion?.let { it.width.toLong() * it.height } ?: 0
        }
        val wall = (System.nanoTime() - start) / frames / 1_000_000.0
        val cpuPerFrame = cpu().takeIf { it >= 0 && startCpu >= 0 }?.let { (it - startCpu) / frames / 1_000_000.0 }
        val allocatedPerFrame = allocated().takeIf { it >= 0 && startAllocated >= 0 }?.let { (it - startAllocated) / frames }
        return "wall=%.3fms CPU=%s allocated=%s dirty=%dpx/frame".format(java.util.Locale.ROOT, wall,
            cpuPerFrame?.let { "%.3fms".format(java.util.Locale.ROOT, it) } ?: "unavailable",
            allocatedPerFrame?.let { "${it}B/frame" } ?: "unavailable", dirtyPixels / frames)
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
                        mode: GuiMotion = GuiMotion.FULL) {
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
    canvas.text(listOf(MenuCanvas.Run("Your client, in motion", 0xFFF4EAFE.toInt(), bold = true)), 141.0, 92.0, 14.0)
    canvas.text(listOf(MenuCanvas.Run("Renderer fixture · $mode motion", 0xFFAE9BC8.toInt())), 141.0, 115.0, 9.0)
    canvas.fill(140.0, 141.0, 299.0, 37.0, 0xFF251C37.toInt())
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
