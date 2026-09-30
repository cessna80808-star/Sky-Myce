package me.mycellium.skymyce.hud

import com.google.gson.Gson
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.WidgetConfig
import me.mycellium.skymyce.hud.widget.hudEditorControlAt
import me.mycellium.skymyce.hud.widget.hudEditorCoordinate
import me.mycellium.skymyce.hud.widget.hudEditorToolbar
import me.mycellium.skymyce.hud.widget.hudEditorHelp
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.menuTextWidth

/** Exercises retained owo layout without a Minecraft window or a test framework. */
fun main() {
    checkSettings()
    checkThemes()
    me.mycellium.skymyce.features.digest.digestUiCheck()
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
        toolbar.children(listOf(Sizing.fixed(48), Sizing.expand(), Sizing.fixed(40)).map { sizing -> UIComponents.box(sizing, Sizing.fill()) })
        toolbarRoot.child(toolbar); toolbarRoot.inflate(Size.of(width, height)); toolbarRoot.mount(null, 0, 0)
        val help = hudEditorHelp(width)
        check(toolbar.x() == (width - toolbar.width()) / 2 && toolbar.y() == height - help.size * MenuCanvas.lineHeight() - 40)
        check(help.all { menuTextWidth(it) <= width - 16 }) { "HUD editor help text extends beyond the viewport" }
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
    val config = Gson().fromJson(saved, WidgetConfig::class.java)
    check(config.x == 480 && config.y == 290 && config.scale == 1.5f && config.anchor == Anchor.BOTTOM_RIGHT)
    check(Gson().toJsonTree(config).asJsonObject.keySet() == setOf("x", "y", "scale", "anchor"))
    println("HUD checks passed: retained layout, dynamic rows, resizing, and saved config compatibility")
}
