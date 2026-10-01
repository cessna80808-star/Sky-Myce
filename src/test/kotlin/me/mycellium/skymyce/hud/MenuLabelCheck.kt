package me.mycellium.skymyce.hud

import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.menuTextStyleAt
import me.mycellium.skymyce.ui.menuTextWidth
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

fun checkMenuLabelHover() {
    val plain = Style.EMPTY
    val interactive = plain.withColor(0xDF8196).withBold(true).withItalic(true)
        .withUnderlined(true).withHoverEvent(HoverEvent.ShowText(Component.literal("Custom hover")))
        .withClickEvent(ClickEvent.SuggestCommand("/sm settings"))
        .withInsertion("Preserve this style")
    val before = "Wi"
    val unicode = "\uD83D\uDE00\u272A"
    val after = "end"
    val text = FormattedCharSequence.composite(listOf(
        FormattedCharSequence.forward(before, plain),
        FormattedCharSequence.forward(unicode, interactive),
        FormattedCharSequence.forward(after, plain)))
    for (size in listOf(8.0, 10.0, 16.0, 24.0)) {
        val height = MenuCanvas.lineHeight(size)
        var left = 0.0
        for ((content, style) in listOf(before to plain, unicode to interactive, after to plain)) {
            for (codepoint in content.codePoints().toArray()) {
                val width = MenuCanvas.textWidth(codepoint, style.isBold, style.isItalic, size)
                check(width > 0)
                check(menuTextStyleAt(text, left + width / 2, height / 2.0, size) === style) { "Custom label hover lost style identity or split a supplementary glyph" }
                check(menuTextStyleAt(text, left, 0.0, size) === style) { "Custom label hover selected the previous glyph at a style boundary" }
                left += width
            }
        }
        check(menuTextStyleAt(text, left, 0.0, size) == null)
        check(menuTextStyleAt(text, -0.01, 0.0, size) == null)
        check(menuTextStyleAt(text, 0.0, -0.01, size) == null)
        check(menuTextStyleAt(text, 0.0, height.toDouble(), size) == null)
        check(menuTextStyleAt(FormattedCharSequence.EMPTY, 0.0, 0.0, size) == null)

        // The production override receives drawText's already aligned absolute line positions.
        val lines = listOf(FormattedCharSequence.forward("Wide line", plain), FormattedCharSequence.forward("i", interactive))
        val originX = 73
        val originY = 41
        val labelWidth = 180
        val spacing = 5
        for (alignment in 0..2) {
            val origins = lines.mapIndexed { index, line ->
                val width = kotlin.math.ceil(menuTextWidth(line, size)).toInt()
                val offset = when (alignment) { 1 -> (labelWidth - width) / 2; 2 -> labelWidth - width; else -> 0 }
                (originX + offset) to (originY + index * (height + spacing))
            }
            fun hit(x: Double, y: Double): Style? = lines.indices.firstNotNullOfOrNull { index ->
                val (lineX, lineY) = origins[index]
                menuTextStyleAt(lines[index], x - lineX, y - lineY, size, height)
            }
            origins.forEachIndexed { index, (lineX, lineY) ->
                val expected = if (index == 0) plain else interactive
                check(hit(lineX + .1, lineY + height / 2.0) === expected) { "Aligned or multiline label hover used a different origin from paint" }
                check(hit(lineX - .1, lineY + height / 2.0) == null)
            }
            check(hit(originX + labelWidth / 2.0, originY + height + spacing / 2.0) == null) { "Line spacing became interactive text" }
        }
    }
    println("Custom label hover checks passed: original styles, proportional metrics, Unicode, alignment and multiline bounds")
}
