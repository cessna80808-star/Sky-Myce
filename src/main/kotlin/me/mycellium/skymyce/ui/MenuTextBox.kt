package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.mixin.MenuEditBoxAccessor
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/** EditBox owns editing, clipboard and narration; MenuCanvas owns every painted pixel and metric. */
class MenuTextBox(sizing: Sizing) : TextBoxComponent(sizing) {
    private var hintText: Component? = null
    private var suggestionText: String? = null
    private var editable = true
    private var centered = false
    private var composition: PreeditEvent? = null
    private var scrollX = 0.0
    private var lastCursor = -1
    private var lastValue = ""
    private var caretEpoch = System.nanoTime()

    init { setTextShadow(false) }

    override fun setHint(hint: Component) { hintText = hint; super.setHint(hint) }
    override fun setSuggestion(suggestion: String?) { suggestionText = suggestion; super.setSuggestion(suggestion) }
    override fun setEditable(editable: Boolean) { this.editable = editable; super.setEditable(editable) }
    override fun setCentered(centered: Boolean) { this.centered = centered; super.setCentered(centered) }
    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)
        if (focused) caretEpoch = System.nanoTime() else composition = null
    }

    override fun keyPressed(event: KeyEvent): Boolean = super.keyPressed(event).also {
        if (it) caretEpoch = System.nanoTime()
    }
    override fun charTyped(event: CharacterEvent): Boolean = super.charTyped(event).also {
        if (it) { caretEpoch = System.nanoTime(); composition = null }
    }
    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        composition = event
        caretEpoch = System.nanoTime()
        return true
    }

    private val inset get() = if (isBordered) 6 else 0
    private val viewport get() = (width - inset * 2).coerceAtLeast(1).toDouble()
    private fun measure(text: String) = MenuCanvas.textWidth(text)

    private fun updateScroll() {
        val text = value
        val cursor = cursorPosition.coerceIn(0, text.length)
        if (cursor != lastCursor || text != lastValue) {
            caretEpoch = System.nanoTime(); lastCursor = cursor; lastValue = text
        }
        val fullWidth = measure(text)
        val caret = measure(text.substring(0, cursor))
        if (caret < scrollX) scrollX = caret
        if (caret > scrollX + viewport - 1) scrollX = caret - viewport + 1
        scrollX = scrollX.coerceIn(0.0, (fullWidth - viewport + 1).coerceAtLeast(0.0))
    }

    private fun origin(): Double {
        val extra = if (centered) ((viewport - measure(value)) / 2).coerceAtLeast(0.0) else 0.0
        return x + inset + extra - scrollX
    }

    override fun getScreenX(index: Int): Int {
        updateScroll()
        return (origin() + measure(value.substring(0, index.coerceIn(0, value.length)))).roundToInt()
    }

    private fun clickedPosition(event: MouseButtonEvent): Int {
        updateScroll()
        return menuCaretIndex(value, event.x() - origin(), ::measure)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val position = clickedPosition(event)
        if (doubleClick) {
            moveCursorTo(position, false)
            val start = getWordPosition(-1)
            val end = getWordPosition(1)
            moveCursorTo(start, false)
            moveCursorTo(end, true)
        } else moveCursorTo(position, event.hasShiftDown())
        caretEpoch = System.nanoTime()
    }

    override fun onDrag(event: MouseButtonEvent, deltaX: Double, deltaY: Double) {
        moveCursorTo(clickedPosition(event), true)
        caretEpoch = System.nanoTime()
    }

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!isVisible) return
        updateScroll()
        if (isBordered) MenuTheme.drawField(g, x, y, width, height, isFocused)
        val left = origin().roundToInt()
        val top = y + (height - 10) / 2
        val text = value
        val cursor = cursorPosition.coerceIn(0, text.length)
        val highlight = (this as Any as MenuEditBoxAccessor).`skymyce$getHighlightPosition`().coerceIn(0, text.length)
        val color = MenuTheme.alpha(if (editable && active) MenuTheme.TEXT else MenuTheme.MUTED)
        g.enableScissor(x + inset, y + 1, x + width - inset, y + height - 1)
        if (cursor != highlight) {
            val first = (left + measure(text.substring(0, minOf(cursor, highlight)))).roundToInt()
            val last = (left + measure(text.substring(0, maxOf(cursor, highlight)))).roundToInt()
            g.fill(first, top - 1, last, top + 11, MenuTheme.alpha(MenuTheme.ACCENT, 40))
        }
        // MenuGraphics captures these calls into Java2D; Minecraft's glyph renderer is never invoked.
        if (text.isEmpty()) hintText?.let { g.text(MC.font, it, left, top, MenuTheme.alpha(MenuTheme.MUTED), false) }
        else g.text(MC.font, text, left, top, color, false)
        if (cursor == text.length) suggestionText?.let {
            g.text(MC.font, it, (left + measure(text)).roundToInt(), top, MenuTheme.alpha(MenuTheme.MUTED), false)
        }
        if (isFocused && editable && (System.nanoTime() - caretEpoch) / 500_000_000L % 2L == 0L) {
            val caret = (left + measure(text.substring(0, cursor))).roundToInt()
            g.fill(caret, top - 1, caret + 1, top + 11, MenuTheme.alpha(MenuTheme.TEXT))
        }
        g.disableScissor()
        composition?.takeIf { isFocused && it.fullText().isNotEmpty() }?.let { preedit ->
            val popupWidth = (measure(preedit.fullText()) + 12).roundToInt().coerceIn(12, width.coerceAtLeast(12))
            val popupX = getScreenX(cursor).coerceIn(x, (x + width - popupWidth).coerceAtLeast(x))
            val popupY = y + height + 3
            MenuTheme.drawField(g, popupX, popupY, popupWidth, 19, true)
            g.enableScissor(popupX + 5, popupY + 1, popupX + popupWidth - 5, popupY + 18)
            g.text(MC.font, preedit.toFormattedText(Style.EMPTY.withUnderlined(true)), popupX + 6, popupY + 4, color, false)
            val caret = (popupX + 6 + measure(preedit.fullText().substring(0, preedit.caretPosition().coerceIn(0, preedit.fullText().length)))).roundToInt()
            g.fill(caret, popupY + 3, caret + 1, popupY + 15, MenuTheme.alpha(MenuTheme.TEXT))
            g.disableScissor()
        }
    }
}

/** Pointer positions use the same proportional metrics as paint and never split a surrogate pair. */
internal fun menuCaretIndex(text: String, offset: Double, measure: (String) -> Double): Int {
    if (offset <= 0) return 0
    var index = 0
    var left = 0.0
    while (index < text.length) {
        val end = text.offsetByCodePoints(index, 1)
        val right = measure(text.substring(0, end))
        if (offset < (left + right) / 2) return index
        left = right; index = end
    }
    return text.length
}
