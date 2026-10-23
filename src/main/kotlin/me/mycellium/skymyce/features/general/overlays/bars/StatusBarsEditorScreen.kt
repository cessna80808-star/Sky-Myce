package me.mycellium.skymyce.features.general.overlays.bars

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.parseThemeHex
import me.mycellium.skymyce.features.general.overlays.StatusBars
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.hud.widget.hudEditorControlAt
import me.mycellium.skymyce.hud.wrappedTooltip
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuButtonIntent
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuColorPicker
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuLabel
import me.mycellium.skymyce.ui.MenuTextBox
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.ui.MenuTooltipMotion
import me.mycellium.skymyce.ui.menuTextLines
import me.mycellium.skymyce.ui.menuTextWidth
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.util.Optional
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a dragged bar will land. */
internal sealed interface BarDrop {
    data class Attach(val anchor: BarAnchor, val row: Int, val slot: Int, val newRow: Boolean) : BarDrop
    data class Float(val x: Int, val y: Int, val guidesX: List<Int>, val guidesY: List<Int>) : BarDrop
}

/**
 * The drop for a pointer at ([px], [py]): beside another anchored bar (same row, before or after it), above or below
 * it (a new row), onto an empty anchor, or floating. [others] are the other bars' rectangles; the dragged bar is not
 * among them. Pure, so the editor's snapping can be checked.
 */
internal fun barDropAt(layout: StatusBarLayout, others: Map<BarType, BarRect>, px: Double, py: Double, dragged: BarRect, width: Int, height: Int, snap: Boolean): BarDrop {
    if (snap) {
        // Beside wins over a new row: a pointer in a bar's row, up to a bar width past it, joins that row. Its top and bottom 2 px start a new row.
        for ((type, r) in others) {
            val bar = layout.bar(type); val anchor = bar.barAnchor ?: continue
            if (py < r.y + 2 || py > r.bottom - 2) continue
            val middle = r.x + r.width / 2.0
            when {
                px >= r.x - r.width && px < middle -> return BarDrop.Attach(anchor, bar.row, if (anchor.right) bar.slot else bar.slot + 1, false)
                px >= middle && px <= r.right + r.width -> return BarDrop.Attach(anchor, bar.row, if (anchor.right) bar.slot + 1 else bar.slot, false)
            }
        }
        for ((type, r) in others) {
            val bar = layout.bar(type); val anchor = bar.barAnchor ?: continue
            if (px < r.x || px >= r.right) continue
            when {
                py >= r.y - SIDE && py < r.y + 2 -> return BarDrop.Attach(anchor, if (anchor.up) bar.row + 1 else bar.row, 0, true)
                py > r.bottom - 2 && py <= r.bottom + SIDE -> return BarDrop.Attach(anchor, if (anchor.up) bar.row else bar.row + 1, 0, true)
            }
        }
        for (anchor in BarAnchor.entries) {
            val occupied = layout.bars.any { it.enabled && it.anchor == anchor.id && it.barType in others }
            if (!occupied && anchor.hitbox(width, height).contains(px, py)) return BarDrop.Attach(anchor, 0, 0, true)
        }
        val snapped = snapFloating(dragged, others.values, width, height)
        return BarDrop.Float(snapped.x, snapped.y, snapped.guidesX, snapped.guidesY)
    }
    return BarDrop.Float(dragged.x, dragged.y, emptyList(), emptyList())
}

private const val SIDE = 8

/**
 * Arranges the status bars like Skyblocker's: drag a bar beside or above another to join its anchor, onto an empty
 * anchor (shown while dragging) to start one, or anywhere else to float it with edge snapping. Drag an edge to resize;
 * right click for icon, text, colour and visibility options. Shows live values on SkyBlock, examples elsewhere.
 */
class StatusBarsEditorScreen(private val parent: Screen? = null, openOptions: BarType? = null) : Screen(MC.instance, MC.font, Component.literal("Status bars")) {
    private var layout = StatusBars.layout
    private var dragging: BarType? = null
    private var grabX = 0; private var grabY = 0
    private var dragWidth = 100
    private var drop: BarDrop? = null
    private var preview: StatusBarLayout? = null
    private var resizing: Resize? = null
    private var hovered: BarType? = null
    private var hoverEdge: Pair<BarType, Boolean>? = null
    private var options: BarType? = openOptions
    private var optionsAt = 0 to 0
    private var placeOptions = openOptions != null
    private var colorKey: String? = null
    private var pointer = 0.0 to 0.0
    // Polled: a lone Alt press does not always report itself as a held modifier.
    private val altDown get() = MC.instance.window.let { InputConstants.isKeyDown(it, GLFW.GLFW_KEY_LEFT_ALT) || InputConstants.isKeyDown(it, GLFW.GLFW_KEY_RIGHT_ALT) }

    private var ui: OwoUIAdapter<FlowLayout>? = null
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    private val tooltip = MenuTooltipMotion()

    /** A resize in progress: the bars either side of the dragged edge (screen left and right), or one floating bar's edge. */
    private data class Resize(val left: BarType?, val right: BarType?, val single: BarType?, val rightEdge: Boolean)

    private fun commit(next: StatusBarLayout) { layout = next.normalized(); StatusBars.layout = layout }

    override fun init() {
        texture.prepareFrame()
        // Opened with a bar's options (from a shortcut or the visual check): place them beside that bar.
        if (placeOptions) { placeOptions = false; options?.let { type -> rects(layout)[type]?.let { optionsAt = it.x to (it.y - 230) } } }
        ui?.dispose()
        val adapter = OwoUIAdapter.create(this, UIContainers::verticalFlow)
        ui = adapter
        adapter.rootComponent.child(toolbar())
        options?.let { adapter.rootComponent.child(optionsPanel(it)) }
        adapter.inflateAndMount()
        super.init()
    }

    private fun button(text: String, width: Int, tip: String? = null, intent: MenuButtonIntent = MenuButtonIntent.SECONDARY, action: () -> Unit) =
        MenuButton(Component.literal(text)) { action() }.apply {
            themed(); this.intent = intent; sizing(Sizing.fixed(width), Sizing.fixed(18))
            if (tip != null) wrappedTooltip(Component.literal(tip))
        }

    private fun toolbar(): FlowLayout {
        val hidden = layout.bars.filter { !it.enabled }.mapNotNull { it.barType }
        val widthNeeded = 6 + 92 + 46 + hidden.sumOf { MC.font.width("+ ${it.title}") + 14 } + 5 * (hidden.size + 2)
        val w = widthNeeded.coerceAtMost(width - 8)
        return UIContainers.horizontalFlow(Sizing.fixed(w), Sizing.fixed(26)).apply {
            padding(Insets.of(4)); gap(5); surface(MenuTheme.panel(true))
            positioning(Positioning.absolute((width - w) / 2, 6))
            for (type in hidden) child(button("+ ${type.title}", MC.font.width("+ ${type.title}") + 14, "Show the ${type.title.lowercase()} bar again, on ${type.defaultAnchor.title.lowercase()}") {
                commit(layout.show(type)); rebuildWidgets()
            })
            child(button("Reset layout", 92, "Put every bar back where it started, with default colours and options") {
                commit(StatusBarLayout.default()); options = null; rebuildWidgets()
            })
            child(button("Done", 46, intent = MenuButtonIntent.PRIMARY) { onClose() })
        }
    }

    private fun optionsPanel(type: BarType): FlowLayout {
        val bar = layout.bar(type)
        fun change(edit: (BarConfig) -> BarConfig) { commit(layout.update(type, edit)); rebuildWidgets() }
        fun <E : Enum<E>> next(value: E, all: Array<E>) = all[(value.ordinal + 1) % all.size]
        val w = 150
        val panel = UIContainers.verticalFlow(Sizing.fixed(w), Sizing.content()).apply {
            padding(Insets.of(6)); gap(3); surface(MenuTheme.panel(true))
            child(MenuLabel(Component.literal(type.title).withColor(MenuTheme.TEXT)))
            child(button("Icon: ${bar.icon.title}", w - 12, "Where the icon sits") { change { it.copy(icon = next(it.icon, IconPosition.entries.toTypedArray())) } })
            child(button("Text: ${bar.text.title}", w - 12, "Where the value sits on the bar") { change { it.copy(text = next(it.text, TextPosition.entries.toTypedArray())) } })
            if (type.hasOverflow) child(button("Show ${if (type == BarType.HEALTH) "absorption" else "overflow"}: ${onOff(bar.showOverflow)}", w - 12,
                "Draw the extra layer and add it to the label") { change { it.copy(showOverflow = !it.showOverflow) } })
            type.extra?.let { title -> child(button("$title: ${onOff(bar.extra)}", w - 12, "Health × (1 + defense ÷ 100), beside the defense") { change { it.copy(extra = !it.extra) } }) }
            colorRow(this, "fill", "Bar colour", bar.fill, w - 12)
            if (type.hasOverflow) colorRow(this, "overflow", if (type == BarType.HEALTH) "Absorption colour" else "Overflow colour", bar.overflow, w - 12)
            colorRow(this, "text", "Text colour", bar.textColor, w - 12)
            child(button("Reset this bar", w - 12, "Default colours and options; the position stays") {
                val d = StatusBarLayout.defaultBar(type)
                change { it.copy(icon = d.icon, text = d.text, showOverflow = d.showOverflow, extra = d.extra, fill = d.fill, overflow = d.overflow, textColor = d.textColor) }
            })
            child(button("Hide bar", w - 12, "Remove it from the HUD. Hidden bars are listed at the top to bring back", MenuButtonIntent.DESTRUCTIVE) {
                commit(layout.hide(type)); options = null; rebuildWidgets()
            })
        }
        val estimatedHeight = 26 + 21 * (7 + (if (type.hasOverflow) 2 else 0) + (if (type.extra != null) 1 else 0)) + (if (colorKey != null) 110 else 0)
        panel.positioning(Positioning.absolute(optionsAt.first.coerceIn(2, (width - w - 2).coerceAtLeast(2)), optionsAt.second.coerceIn(36, (height - estimatedHeight - 2).coerceAtLeast(36))))
        return panel
    }

    /** A colour option; opening it shows a picker and a hex field that recolour the bar live. */
    private fun colorRow(parent: FlowLayout, key: String, title: String, value: Int, w: Int) {
        val open = colorKey == key
        parent.child(button("$title  ■", w, "Pick a colour") { colorKey = if (open) null else key; rebuildWidgets() }.apply {
            setMessage(Component.literal("$title  ").append(Component.literal("■").withColor(value)))
        })
        if (!open) return
        val hex = MenuTextBox(Sizing.fixed(w)).apply { setMaxLength(7); text("#%06X".format(value)) }
        val picker = MenuColorPicker().apply { sizing(Sizing.fixed(w), Sizing.fixed(64)); selectedColor(Color.ofRgb(value)) }
        var applied = value
        fun apply(c: Int) { if (c != applied) { applied = c; commit(layout.update(options ?: return) { it.copy(
            fill = if (key == "fill") c else it.fill, overflow = if (key == "overflow") c else it.overflow, textColor = if (key == "text") c else it.textColor) }) } }
        picker.onChanged().subscribe { apply(it.rgb() and 0xFFFFFF); hex.text("#%06X".format(it.rgb() and 0xFFFFFF)) }
        hex.onChanged().subscribe { parseThemeHex(it)?.let(::apply) }
        parent.child(picker); parent.child(hex)
    }

    private fun onOff(value: Boolean) = if (value) "On" else "Off"

    override fun removed() {
        StatusBars.layout = layout; StatusBars.save()
        canvas.close(); texture.close(); ui?.dispose(); ui = null
        super.removed()
    }

    override fun onClose() { navigateMenu(parent) }
    override fun isPauseScreen() = false
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    private fun shown() = preview ?: layout
    private fun rects(base: StatusBarLayout = shown()) = layoutRects(base, width, height)

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (texture.prepareFrame()) { tooltip.clear(); canvas.close(); ui?.inflateAndMount() }
        output.fill(0, 0, width, height, 0x30000000)
        output.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, width / 2 - 91, height - 22, 182, 22)
        val rects = rects()
        val dragged = dragging
        // Empty anchors light up while dragging; occupied ones show their point.
        if (dragged != null) for (anchor in BarAnchor.entries) {
            val occupied = shown().bars.any { it.enabled && it.anchor == anchor.id && it.type != dragged.id }
            val box = anchor.hitbox(width, height)
            val target = (drop as? BarDrop.Attach)?.anchor == anchor
            if (!occupied) output.fill(box.x, box.y, box.right, box.bottom, if (target) 0x80FFFFFF.toInt() else 0x40FFFFFF)
        }
        StatusBars.drawBars(output, rects, preview = !StatusBars.hasLiveReadings) { type -> if (type == dragged && drop is BarDrop.Float) 0.85 else 1.0 }
        // Outlines: the hovered bar, the dragged one, and the bar whose options are open.
        for ((type, rect) in rects) {
            val color = when { type == dragged -> 0xFFFFD34D.toInt(); type == options -> 0xFF67CCF2.toInt(); type == hovered -> 0xC0FFFFFF.toInt(); else -> 0 }
            if (color != 0) output.outline(rect.x - 1, rect.y - 1, rect.width + 2, rect.height + 2, color)
        }
        (drop as? BarDrop.Float)?.let { f ->
            f.guidesX.forEach { gx -> output.fill(gx, 0, gx + 1, height, 0x9067CCF2.toInt()) }
            f.guidesY.forEach { gy -> output.fill(0, gy, width, gy + 1, 0x9067CCF2.toInt()) }
        }
        hoverEdge?.let { if (dragging == null && options == null) output.requestCursor(CursorTypes.RESIZE_EW) }
        if (resizing != null) output.requestCursor(CursorTypes.RESIZE_EW)
        if (dragged != null && drop != null) {
            val label = when (val d = drop) {
                is BarDrop.Attach -> "${d.anchor.title}${if (d.newRow) ", new row" else ""}"
                is BarDrop.Float -> if (d.guidesX.isEmpty() && d.guidesY.isEmpty()) "Floating" else "Floating, snapped"
                null -> ""
            }
            output.text(MC.font, label, mouseX + 10, mouseY + 10, 0xFFFFFFFF.toInt(), true)
        }

        canvas.begin(); tooltip.beginFrame()
        val graphics = MenuGraphics(canvas, mouseX, mouseY, width, height, tooltip)
        graphics.layer(alpha = 1.0) {
            val lineHeight = MenuCanvas.lineHeight()
            val lines = menuTextLines(Component.literal(HELP), (width - 40).coerceAtMost(520))
            // Under the toolbar, clear of the bars around the hotbar.
            lines.forEachIndexed { i, line -> graphics.text(MC.font, line, (width - menuTextWidth(line).toInt()) / 2, 38 + i * lineHeight, MenuTheme.alpha(MenuTheme.MUTED), false) }
            super.extractRenderState(graphics, mouseX, mouseY, a)
            canvas.group = "tooltip"
            try { ui?.drawTooltip(graphics, mouseX, mouseY, a) } finally { canvas.group = null }
        }
        tooltip.endFrame(canvas)
        val rasterScale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        texture.render(output, canvas, width, height, rasterScale)
    }

    private fun overControls(x: Double, y: Double) = hudEditorControlAt(ui?.rootComponent, x, y)

    override fun getChildAt(x: Double, y: Double): Optional<GuiEventListener> =
        super.getChildAt(x, y).filter { it !== ui || overControls(x, y) }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        pointer = mouseX to mouseY
        if (dragging == null && resizing == null) {
            val rects = rects()
            hovered = if (overControls(mouseX, mouseY)) null else rects.entries.lastOrNull { it.value.expand(1).contains(mouseX, mouseY) }?.key
            hoverEdge = if (overControls(mouseX, mouseY)) null else rects.entries.firstNotNullOfOrNull { (type, r) ->
                if (mouseY < r.y || mouseY >= r.bottom) null
                else if (abs(mouseX - r.x) <= 2) (type to false).takeIf { resizable(type, false) }
                else if (abs(mouseX - r.right) <= 2) (type to true).takeIf { resizable(type, true) } else null
            }
        }
        super.mouseMoved(mouseX, mouseY)
    }

    /** Which edges resize: both of a floating bar, the shared edge in a target row, the outer edge of a free row bar. */
    private fun resizable(type: BarType, rightEdge: Boolean): Boolean {
        val bar = layout.bar(type); val anchor = bar.barAnchor ?: return true
        if (anchor.rule.isTarget) return neighbour(type, rightEdge) != null
        return rightEdge == anchor.right
    }

    /** The bar touching [type]'s left or right edge in the same row. */
    private fun neighbour(type: BarType, rightEdge: Boolean): BarType? {
        val rects = rects(layout); val r = rects[type] ?: return null
        val bar = layout.bar(type)
        return rects.entries.firstOrNull { (other, o) ->
            other != type && layout.bar(other).anchor == bar.anchor && layout.bar(other).row == bar.row &&
                (if (rightEdge) abs(o.x - (r.right + BAR_GAP)) <= 1 else abs(o.right + BAR_GAP - r.x) <= 1)
        }?.key
    }

    override fun mouseClicked(button: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(button, doubled)) return true
        val rects = rects()
        val hit = rects.entries.lastOrNull { it.value.expand(1).contains(button.x, button.y) }?.key
        if (button.button() == 1) {
            options = hit; colorKey = null
            optionsAt = button.x.toInt() + 4 to button.y.toInt() + 4
            rebuildWidgets(); return hit != null
        }
        if (button.button() != 0) return false
        if (options != null) { options = null; colorKey = null; rebuildWidgets() }
        hoverEdge?.let { (type, rightEdge) ->
            resizing = if (layout.bar(type).barAnchor?.rule?.isTarget == true) {
                val other = neighbour(type, rightEdge)
                if (rightEdge) Resize(type, other, null, true) else Resize(other, type, null, false)
            } else Resize(null, null, type, rightEdge)
            return true
        }
        if (hit == null) return false
        val rect = rects.getValue(hit)
        dragging = hit; grabX = (button.x - rect.x).toInt(); grabY = (button.y - rect.y).toInt(); dragWidth = rect.width.coerceAtLeast(StatusBarLayout.MIN_FREE_WIDTH)
        updateDrag(button.x, button.y, snap = !altDown)
        return true
    }

    override fun mouseDragged(button: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        pointer = button.x to button.y
        resizing?.let { resize(it, button.x); return true }
        if (dragging != null) { updateDrag(button.x, button.y, snap = !altDown); return true }
        return super.mouseDragged(button, offsetX, offsetY)
    }

    private fun updateDrag(px: Double, py: Double, snap: Boolean) {
        val type = dragging ?: return
        val base = layout.detach(type)
        val others = layoutRects(base, width, height) { it.enabled && it.type != type.id }
        val draggedRect = BarRect((px - grabX).roundToInt(), (py - grabY).roundToInt(), dragWidth, BAR_HEIGHT)
        val d = barDropAt(base, others, px, py, draggedRect, width, height, snap)
        drop = d
        preview = when (d) {
            is BarDrop.Attach -> base.insert(type, d.anchor, d.row, d.slot, d.newRow)
            is BarDrop.Float -> base.float(type, d.x, d.y, dragWidth, width, height)
        }
    }

    private fun resize(r: Resize, px: Double) {
        val rects = rects(layout)
        r.single?.let { type ->
            val rect = rects[type] ?: return
            val anchor = layout.bar(type).barAnchor
            if (anchor != null) {
                val wanted = if (r.rightEdge) px - rect.x else rect.right - px
                commit(layout.update(type) { it.copy(size = (wanted / anchor.rule.unitWidth).roundToInt().coerceIn(anchor.rule.min, anchor.rule.max)) })
            } else if (r.rightEdge) {
                commit(layout.float(type, rect.x, rect.y, (px - rect.x).roundToInt().coerceAtLeast(StatusBarLayout.MIN_FREE_WIDTH), width, height))
            } else {
                val newX = px.roundToInt().coerceAtMost(rect.right - StatusBarLayout.MIN_FREE_WIDTH)
                commit(layout.float(type, newX, rect.y, rect.right - newX, width, height))
            }
            return
        }
        // A shared edge in a target row moves a unit at a time once the pointer is most of a unit past it.
        val left = r.left; val right = r.right
        val edge = left?.let { rects[it]?.right } ?: right?.let { rects[it]?.x } ?: return
        val anchor = (left ?: right)?.let { layout.bar(it).barAnchor } ?: return
        val unit = anchor.rule.totalWidth.toDouble() / anchor.rule.target
        if (abs(px - edge) > unit * 0.75) commit(layout.resize(left, right, if (px > edge) 1 else -1))
    }

    override fun mouseReleased(button: MouseButtonEvent): Boolean {
        if (resizing != null) { resizing = null; StatusBars.save(); return true }
        if (dragging != null) {
            preview?.let(::commit)
            dragging = null; drop = null; preview = null
            StatusBars.save(); rebuildWidgets()
            return true
        }
        return super.mouseReleased(button)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.isEscape) {
            if (options != null) { options = null; colorKey = null; rebuildWidgets(); return true }
            if (dragging != null) { dragging = null; drop = null; preview = null; return true }
            onClose(); return true
        }
        if (dragging != null) updateDrag(pointer.first, pointer.second, snap = !altDown)
        return super.keyPressed(input)
    }

    override fun keyReleased(input: KeyEvent): Boolean {
        if (dragging != null) updateDrag(pointer.first, pointer.second, snap = !altDown)
        return super.keyReleased(input)
    }

    companion object {
        private val HOTBAR = Identifier.withDefaultNamespace("hud/hotbar")
        private const val HELP = "Drag a bar beside or above another to join it, onto a lit anchor to start a new spot, anywhere else to float it. " +
            "Drag an edge to resize. Right click for options. Hold Alt to place without snapping. Esc when done."
    }
}
