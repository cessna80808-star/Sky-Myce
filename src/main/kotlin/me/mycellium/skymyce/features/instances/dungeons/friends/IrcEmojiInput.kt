package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import kotlin.math.roundToInt

private val inputEmojiStyle = Style.EMPTY.withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath("skymyce", "irc_emoji")))
    .withColor(0xFFFFFF).withBold(false).withItalic(false)

/** Raw UTF-16 indices remain owned by EditBox; one joined emoji occupies one displayed glyph. */
class IrcEmojiInputLayout internal constructor(val value: String) {
    private data class Run(val start: Int, val end: Int, val shown: String, val emoji: Boolean)
    private val runs = buildList {
        var start = 0
        for (part in ircReadableText(value)) {
            val raw = part.original ?: part.text
            add(Run(start, start + raw.length, if (part.emoji) part.text else raw, part.emoji))
            start += raw.length
        }
    }
    val hasEmoji = runs.any { it.emoji }

    private fun boundary(index: Int): Int {
        val safe = index.coerceIn(0, value.length)
        return if (safe > 0 && safe < value.length && value[safe].isLowSurrogate() && value[safe - 1].isHighSurrogate()) safe - 1 else safe
    }

    fun floor(index: Int): Int {
        val safe = boundary(index)
        return runs.firstOrNull { it.emoji && safe in it.start + 1 until it.end }?.start ?: safe
    }

    fun next(index: Int): Int {
        val safe = boundary(index)
        if (safe == value.length) return safe
        return runs.firstOrNull { it.emoji && safe in it.start until it.end }?.end ?: value.offsetByCodePoints(safe, 1)
    }

    internal fun position(index: Int, measure: (String, Boolean) -> Int): Int {
        val safe = boundary(index)
        var width = 0
        for (run in runs) {
            if (safe <= run.start) break
            val full = measure(run.shown, run.emoji)
            if (safe >= run.end) width += full
            else {
                width += if (run.emoji) (full * value.codePointCount(run.start, safe).toDouble() / value.codePointCount(run.start, run.end)).roundToInt()
                    else measure(value.substring(run.start, safe), false)
                break
            }
        }
        return width
    }

    internal fun index(offset: Int, measure: (String, Boolean) -> Int): Int {
        if (offset <= 0) return 0
        var left = 0
        for (run in runs) {
            val right = left + measure(run.shown, run.emoji)
            if (offset < right) {
                if (run.emoji) return if (offset * 2 < left + right) run.start else run.end
                var raw = run.start
                var previous = left
                while (raw < run.end) {
                    val end = value.offsetByCodePoints(raw, 1)
                    val next = left + measure(value.substring(run.start, end), false)
                    if (offset * 2 < previous + next) return raw
                    previous = next; raw = end
                }
                return run.end
            }
            left = right
        }
        return value.length
    }

    internal fun scroll(index: Int, current: Int, available: Int, measure: (String, Boolean) -> Int): Int {
        val safe = boundary(index)
        var start = floor(minOf(current, safe))
        val width = available.coerceAtLeast(1)
        val caret = position(safe, measure)
        while (caret - position(start, measure) > width) {
            val next = next(start)
            if (next <= start || next > safe) break
            start = next
        }
        val end = position(value.length, measure)
        while (start > 0) {
            val previous = floor(value.offsetByCodePoints(start, -1))
            if (end - position(previous, measure) > width) break
            start = previous
        }
        return start
    }

    fun position(index: Int, font: Font): Int = position(index) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, inputEmojiStyle)) else font.width(text)
    }

    fun index(offset: Int, font: Font): Int = index(offset) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, inputEmojiStyle)) else font.width(text)
    }

    fun scroll(index: Int, current: Int, available: Int, font: Font): Int = scroll(index, current, available) { text, emoji ->
        if (emoji) font.width(FormattedCharSequence.forward(text, inputEmojiStyle)) else font.width(text)
    }

    fun display(source: FormattedCharSequence): FormattedCharSequence {
        val text = StringBuilder()
        val styles = arrayListOf<Style>()
        source.accept { _, style, code ->
            text.appendCodePoint(code); repeat(Character.charCount(code)) { styles.add(style) }; true
        }
        if (text.toString() != value) return display(FormattedCharSequence.forward(value, Style.EMPTY))
        return FormattedCharSequence { sink ->
            var complete = true
            for (run in runs) {
                if (!complete) break
                if (run.emoji) complete = sink.accept(run.start, inputEmojiStyle, run.shown[0].code)
                else {
                    var index = run.start
                    while (complete && index < run.end) {
                        val code = value.codePointAt(index)
                        complete = sink.accept(index, styles[index], code)
                        index += Character.charCount(code)
                    }
                }
            }
            complete
        }
    }
}

object IrcEmojiInput {
    private var current = IrcEmojiInputLayout("")
    @JvmStatic fun layout(value: String): IrcEmojiInputLayout {
        if (current.value != value) current = IrcEmojiInputLayout(value)
        return current
    }
}
