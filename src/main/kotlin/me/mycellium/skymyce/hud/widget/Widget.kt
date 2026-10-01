package me.mycellium.skymyce.hud.widget

import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.hud.replaceContent
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.ThemeOverrides
import me.mycellium.skymyce.hud.widget.WidgetManager.register
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.ceil
import kotlin.math.floor

/** A preview works before the widget has ever appeared; recent live content still helps arrange transient HUDs. */
internal fun <T> hudEditorContent(preview: (() -> T?)?, current: () -> T?, retained: T?, sinceActive: Long, now: Long): T? =
    if (preview != null) preview() else current() ?: retained.takeIf { sinceActive > 0 && now - sinceActive in 0L until 10_000L }

class Widget(
    val id: String,
    val title: String,
    var x: Int = 0,
    var y: Int = 0,
    var scale: Float = 1f,
    var anchor: Anchor = Anchor.TOP_LEFT,
    /** Editor-only example; never changes gameplay visibility or recorded activity. */
    val previewBuilder: (Widget.() -> UIComponent?)? = null,
    /** Update and return retained owo components; null hides the widget without discarding its UI state. */
    val builder: Widget.() -> UIComponent? = { null }
) {
    var sinceActive: Long = 0L
    var theme: ThemeOverrides? = null
    private var styledRevision = -1L
    private var styledOverrides: ThemeOverrides? = null

    var lastRenderable: UIComponent? = null
        private set

    var isRenderable: Boolean = false
        private set

    internal var adapter: OwoUIAdapter<FlowLayout>? = null
        private set

    val renderX: Int
        get() = x - (anchor.x * width).toInt()

    val renderY: Int
        get() = y - (anchor.y * height).toInt()

    val width: Int
        get() = ceil((adapter?.rootComponent?.width() ?: 0) * scale).toInt()

    val height: Int
        get() = ceil((adapter?.rootComponent?.height() ?: 0) * scale).toInt()

    init {
        this.register()
    }

    fun getClosestAnchor(x: Int, y: Int): Anchor {
        return Anchor.entries.minByOrNull { anchor ->
            val dx = x - (anchor.x * width) - renderX
            val dy = y - (anchor.y * height) - renderY
            return@minByOrNull dx * dx + dy * dy
        } ?: Anchor.entries[0]
    }

    fun setAnchorPreservePosition(newAnchor: Anchor) {
        x = renderX + (newAnchor.x * width).toInt()
        y = renderY + (newAnchor.y * height).toInt()
        anchor = newAnchor
    }

    fun toConfig(): WidgetConfig = WidgetConfig(x, y, scale, anchor, theme)

    fun inBounds(selX: Int, selY: Int): Boolean = selX >= renderX && selX < renderX + width && selY >= renderY && selY < renderY + height

    internal fun localX(mouseX: Double) = (mouseX - renderX) / scale
    internal fun localY(mouseY: Double) = (mouseY - renderY) / scale
    internal fun localClick(click: MouseButtonEvent) = MouseButtonEvent(localX(click.x), localY(click.y), click.buttonInfo())

    internal fun prepareEditor(): Boolean = HudTheme.withWidget(theme) {
        editorContent()?.let { prepare(it); true } ?: false
    }

    private fun editorContent() = hudEditorContent(previewBuilder?.let { preview -> { preview(this) } }, { builder() }, lastRenderable,
        sinceActive, System.currentTimeMillis())

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float, editor: Boolean = false): Boolean =
        HudTheme.withWidget(theme) {
            val element = if (editor) editorContent() else builder()
            if (!editor) {
                isRenderable = element != null
                if (element != null) sinceActive = System.currentTimeMillis()
            }
            if (element == null) return@withWidget false
            val ui = prepare(element)
            ui.extractRenderState(graphics, floor(localX(mouseX.toDouble())).toInt(), floor(localY(mouseY.toDouble())).toInt(), partialTicks)
            true
        }

    private fun prepare(element: UIComponent): OwoUIAdapter<FlowLayout> {
        val window = MC.instance.window
        val availableWidth = ceil(window.guiScaledWidth / scale).toInt()
        val availableHeight = ceil(window.guiScaledHeight / scale).toInt()
        val ui = adapter ?: OwoUIAdapter.createWithoutScreen<FlowLayout>(0, 0, availableWidth, availableHeight) { _, _ ->
            object : FlowLayout(Sizing.content(), Sizing.content(), Algorithm.VERTICAL) {
                init { allowOverflow(true) }
                override fun draw(graphics: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
                    // Owo updates animations first; apply the anchor using this frame's measured size.
                    transformed(graphics) { super.draw(graphics, mouseX, mouseY, partialTicks, delta) }
                }
            }
        }.also { adapter = it }
        if (lastRenderable !== element) {
            ui.rootComponent.replaceContent(element)
            lastRenderable = element
            ui.inflateAndMount()
            styledRevision = -1
        }
        if (styledRevision != HudTheme.revision || styledOverrides != theme) {
            fun style(component: UIComponent) {
                if (component is io.wispforest.owo.ui.component.LabelComponent) {
                    (component as? me.mycellium.skymyce.hud.HudTextLabel)?.update(force = true)
                    component.color(io.wispforest.owo.ui.core.Color.ofRgb(HudTheme.TEXT)); component.shadow(HudTheme.SHADOW)
                }
                if (component is io.wispforest.owo.ui.core.ParentUIComponent) component.children().forEach(::style)
            }
            style(element)
            // Existing layout dimensions remain authoritative; padding only changes the widget's content box.
            (element as? FlowLayout)?.padding(io.wispforest.owo.ui.core.Insets.of(HudTheme.PADDING))
            styledRevision = HudTheme.revision; styledOverrides = theme
        }
        if (ui.width() != availableWidth || ui.height() != availableHeight) {
            ui.moveAndResize(0, 0, availableWidth, availableHeight)
        }

        return ui
    }

    fun drawTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (!isRenderable || !inBounds(mouseX, mouseY)) return
        transformed(graphics) {
            adapter?.drawTooltip(graphics, floor(localX(mouseX.toDouble())).toInt(), floor(localY(mouseY.toDouble())).toInt(), partialTicks)
        }
    }

    private inline fun transformed(graphics: GuiGraphicsExtractor, draw: () -> Unit) {
        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(renderX.toFloat(), renderY.toFloat())
            graphics.pose().scale(scale)
            draw()
        } finally {
            graphics.pose().popMatrix()
        }
    }

    fun dispose() {
        adapter?.rootComponent?.clearChildren()
        adapter?.dispose()
        adapter = null
        lastRenderable = null
        isRenderable = false
        sinceActive = 0L
    }
}
