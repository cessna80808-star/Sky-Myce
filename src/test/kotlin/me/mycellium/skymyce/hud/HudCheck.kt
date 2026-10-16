package me.mycellium.skymyce.hud
import me.mycellium.skymyce.hud.widget.parseWidgetConfig

import com.google.gson.Gson
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.WidgetConfig
import me.mycellium.skymyce.hud.widget.forEachAlive
import me.mycellium.skymyce.hud.widget.lastAliveOrNull
import me.mycellium.skymyce.hud.widget.hudEditorControlAt
import me.mycellium.skymyce.hud.widget.hudEditorCoordinate
import me.mycellium.skymyce.hud.widget.hudEditorToolbar
import me.mycellium.skymyce.hud.widget.hudEditorHelp
import me.mycellium.skymyce.hud.widget.hudEditorContent
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonTrackerPreviewLines
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonTrackerWidgetVisible
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonTrackerDisplayFloor
import me.mycellium.skymyce.features.instances.dungeons.tracker.dungeonTrackerWidgetLines
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonRunRecord
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerSummary
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.menuTextWidth

/** Exercises retained owo layout without a Minecraft window or a test framework. */
fun main() {
    for (anchor in Anchor.entries) {
        for (extent in listOf(0, 80, 640, 900)) for (position in listOf(-100000, 0, 180, 100000)) {
            val result = me.mycellium.skymyce.hud.widget.hudVisibleAnchor(position, extent, 640, anchor.x)
            val left = result - (anchor.x * extent).toInt()
            check(left in 0..(640 - extent).coerceAtLeast(0)) { "A saved HUD position became inaccessible after resolution change" }
        }
    }
    val hiddenLayout = checkNotNull(me.mycellium.skymyce.hud.widget.parseWidgetConfig(com.google.gson.JsonParser.parseString("""{"visible":false,"x":40,"y":60,"scale":1.2}""")))
    check(!hiddenLayout.visible && hiddenLayout.x == 40)
    check(me.mycellium.skymyce.hud.widget.parseWidgetConfig(com.google.gson.JsonParser.parseString("{}"))?.visible == true)
    val firstOwned = Any(); val secondOwned = Any(); val laterOwned = Any()
    val released = java.lang.ref.WeakReference(Any()).apply { clear() }
    val owned = mutableListOf(java.lang.ref.WeakReference(firstOwned), released, java.lang.ref.WeakReference(secondOwned))
    val visited = mutableListOf<Any>()
    owned.forEachAlive { value ->
        visited += value
        if (value === firstOwned) owned += java.lang.ref.WeakReference(laterOwned)
    }
    check(visited == listOf(firstOwned, secondOwned) && owned.size == 3) {
        "A render traversal retained released widgets or rendered a newly registered widget too early"
    }
    check(owned.lastAliveOrNull { true } === laterOwned) { "Overlapping widgets lost reverse pointer priority" }
    owned.last().clear()
    check(owned.lastAliveOrNull { true } === secondOwned && owned.size == 2)
    visited.clear(); owned.forEachAlive { visited += it }
    check(visited == listOf(firstOwned, secondOwned))
    checkSettings()
    checkThemes()
    me.mycellium.skymyce.features.digest.digestUiCheck()
    // On a fresh client the live tracker is hidden outside the dungeon hub, with no retained UI or activity.
    val trackerPreview = checkNotNull(dungeonTrackerPreviewLines(enabled = true))
    val plainPreview = trackerPreview.map { it.replace(Regex("§."), "") }
    check(plainPreview.size == 9 && plainPreview[0] == "M7 Dungeon Tracker" && plainPreview[1] == "/skymyce dungeon")
    check(plainPreview[2].isEmpty() && plainPreview[6].isEmpty())
    check(plainPreview[3] == "Time: 1h (6m/run)" && plainPreview[4] == "Runs: 10 (10/h)")
    check(plainPreview[5] == "Chests: 10 (2 rerolls)" && plainPreview[7].contains("12M") && plainPreview[8].contains("400K"))
    check(plainPreview.none { it.contains("Editor preview") || it.contains("Historical") || it.contains("Inherited") || it.contains("profile") })
    val partial = TrackerSummary(runs = 2, timedRuns = 1, time = 300_000, gross = 50_000.0, chestCost = 10_000.0, incompleteValues = true)
    val partialRows = dungeonTrackerWidgetLines(DungeonFloor.M7, partial).map { it.replace(Regex("§."), "") }
    check(partialRows.size == 9 && partialRows[3] == "Time: 5m recorded (5m/run)" && partialRows[6] == "Rates need matched run times")
    check(partialRows[4].contains("—/h") && partialRows[7].contains("40K + ?") && partialRows[7].contains("—/h"))
    check(dungeonTrackerWidgetLines(DungeonFloor.M7, TrackerSummary())[3].contains("0s") && plainPreview[7].contains("~"))
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        val rows = dungeonTrackerWidgetLines(null, TrackerSummary(gross = value, cataXp = value, time = Long.MAX_VALUE, timedRuns = 1))
        check(rows.none { it.contains("NaN") || it.contains("Infinity") || it.contains("∞") })
    }
    val context = AcquisitionContext("a".repeat(32), profileName = "Apple")
    val completed = DungeonRunRecord("latest", "session", context, "M7", completedAt = 100)
    val runs = listOf(completed.copy(id = "older", floor = "F7", completedAt = 1), completed,
        completed.copy(id = "legacy", floor = "M6", completedAt = 200, trackingVersion = 1),
        completed.copy(id = "other", floor = "M5", completedAt = 300, context = context.copy(profileName = "Pear")),
        completed.copy(id = "unfinished", floor = "M4", completedAt = null),
        completed.copy(id = "invalid", floor = "invalid", completedAt = 400))
    check(dungeonTrackerDisplayFloor(null, null, runs, context) == DungeonFloor.M7)
    check(dungeonTrackerDisplayFloor(DungeonFloor.F6, DungeonFloor.F5, runs, context) == DungeonFloor.F6)
    check(dungeonTrackerDisplayFloor(null, DungeonFloor.F5, runs, context) == DungeonFloor.F5)
    check(dungeonTrackerDisplayFloor(null, null, runs, null) == null)
    check(dungeonTrackerDisplayFloor(null, null, runs, context.copy(account = "b".repeat(32))) == null)
    check(dungeonTrackerDisplayFloor(null, null, runs, context.copy(server = "alpha")) == null)
    check(dungeonTrackerPreviewLines(enabled = false) == null)
    val firstEditorContent = hudEditorContent({ dungeonTrackerPreviewLines(true) },
        { error("A deterministic preview must not depend on live dungeon state") }, null, sinceActive = 0, now = 100_000)
    check(firstEditorContent === trackerPreview) { "A never-active tracker did not initialize its editor preview" }
    check(!dungeonTrackerWidgetVisible(true, false, false, false))
    check(!dungeonTrackerWidgetVisible(false, true, false, false))
    check(!dungeonTrackerWidgetVisible(true, true, true, false))
    check(dungeonTrackerWidgetVisible(true, true, false, false))
    check(dungeonTrackerWidgetVisible(true, true, true, true))
    check(hudEditorContent<List<String>>(null, { null }, null, 0, 100_000) == null)
    check(hudEditorContent(null, { null }, trackerPreview, 99_000, 100_000) === trackerPreview)
    check(hudEditorContent(null, { null }, trackerPreview, 90_000, 100_000) == null)
    check(hudEditorContent({ dungeonTrackerPreviewLines(false) }, { trackerPreview }, trackerPreview, 99_000, 100_000) == null) {
        "A disabled tracker reappeared through recently retained live content"
    }
    val editorRoot = io.wispforest.owo.ui.container.UIContainers.verticalFlow(Sizing.fill(), Sizing.fill())
    val resetControl = UIComponents.box(Sizing.fixed(150), Sizing.fixed(20)).positioning(io.wispforest.owo.ui.core.Positioning.absolute(245, 330))
    editorRoot.child(resetControl)
    editorRoot.inflate(Size.of(640, 360)); editorRoot.mount(null, 0, 0)
    check(!hudEditorControlAt(editorRoot, 100.0, 100.0)) // HUD region must reach widget dragging.
    check(hudEditorControlAt(editorRoot, 250.0, 335.0)) // Reset control retains native handling.
    check(!hudEditorControlAt(editorRoot, 395.0, 335.0)) // Right edge is outside the button.
    check(!hudEditorControlAt(null, 100.0, 100.0))
    check(!hudEditorControlAt(editorRoot, 250.0, 335.0, visible = false)) { "Hidden editor controls still intercept a widget" }
    // Native GUI positions must survive dragging at every Minecraft GUI scale and anchor.
    for (guiScale in 1..4) {
        val nativeWidth = 1920 / guiScale
        for (anchor in Anchor.entries) {
            val size = 120 / guiScale
            val anchoredEdge = (anchor.x * size).toInt()
            check(hudEditorCoordinate(200.0, 20, anchor.x, size, nativeWidth, false) == 180)
            check(hudEditorCoordinate(203.0, 20, anchor.x, size, nativeWidth, true) == 180)
            check(hudEditorCoordinate(-1000.0, 20, anchor.x, size, nativeWidth, false) == anchoredEdge)
            check(hudEditorCoordinate(10000.0, 20, anchor.x, size, nativeWidth, false) == anchoredEdge + nativeWidth - size)
        }
    }
    check(hudEditorCoordinate(50.0, 10, .5f, 800, 640, false) == 240) { "Oversized widget drag range became invalid" }
    for ((width, height) in listOf(320 to 180, 640 to 360, 1280 to 720)) {
        val toolbarRoot = io.wispforest.owo.ui.container.UIContainers.verticalFlow(Sizing.fill(), Sizing.fill())
        val toolbar = hudEditorToolbar(width, height)
        toolbar.children(listOf(Sizing.expand(), Sizing.fixed(40)).map { sizing -> UIComponents.box(sizing, Sizing.fill()) })
        toolbarRoot.child(toolbar); toolbarRoot.inflate(Size.of(width, height)); toolbarRoot.mount(null, 0, 0)
        val help = hudEditorHelp(width)
        check(toolbar.x() == (width - toolbar.width()) / 2 && toolbar.y() == height - help.size * MenuCanvas.lineHeight() - 40)
        check(help.all { menuTextWidth(it) <= width - 16 }) { "HUD editor help text extends beyond the viewport" }
        check(toolbar.children().size == 2 && toolbar.width() == 208) { "HUD editor toolbar must contain only Reset positions and Done" }
        check(toolbar.children()[1].width() == 40 && toolbar.children()[1].x() + 40 == toolbar.x() + toolbar.width() - 4)
        check(toolbar.children().all { it.x() >= toolbar.x() && it.x() + it.width() <= toolbar.x() + toolbar.width() })
        check(!hudEditorControlAt(toolbarRoot, 20.0, 20.0)) { "HUD editor still covers top-left widgets by default" }
    }
    val first = UIComponents.box(Sizing.fixed(40), Sizing.fixed(9))
    val second = UIComponents.box(Sizing.fixed(20), Sizing.fixed(9))
    val panel = hudPanel(padding = 6).children(listOf(first, second))
    val root = hudColumn().gap(0).child(panel)
    root.inflate(Size.of(960, 540))
    root.mount(null, 0, 0)
    check(root.width() == 52 && root.height() == 32)
    check(first.x() == 6 && second.y() == 17)

    // The same mounted component grows and shrinks; its parent must remeasure immediately.
    first.horizontalSizing(Sizing.fixed(80))
    check(root.width() == 92 && root.height() == 32)
    val third = UIComponents.box(Sizing.fixed(60), Sizing.fixed(9))
    panel.child(third)
    check(root.width() == 92 && root.height() == 43)
    panel.removeChild(first)
    check(root.width() == 72 && root.height() == 32)
    check(panel.children()[0] === second && second.y() == 6 && third.y() == 17)

    root.inflate(Size.of(480, 270))
    root.mount(null, 0, 0)
    check(root.width() == 72 && root.height() == 32)
    root.clearChildren()
    check(root.width() == 0 && root.height() == 0)
    second.horizontalSizing(Sizing.fixed(120))
    root.replaceContent(panel)
    check(root.width() == 132 && root.height() == 32 && panel.children()[0] === second) {
        "Remounted HUD kept stale dimensions: ${root.width()}x${root.height()}"
    }

    val saved = """{"x":480,"y":290,"scale":1.5,"anchor":"BOTTOM_RIGHT"}"""
    val config = requireNotNull(parseWidgetConfig(com.google.gson.JsonParser.parseString(saved)))
    check(config.x == 480 && config.y == 290 && config.scale == 1.5f && config.anchor == Anchor.BOTTOM_RIGHT)
    check(config.visible)
    check(Gson().toJsonTree(config).asJsonObject.keySet() == setOf("x", "y", "scale", "anchor", "visible"))
    println("HUD checks passed: retained layout, dynamic rows, resizing, and saved config compatibility")
}
