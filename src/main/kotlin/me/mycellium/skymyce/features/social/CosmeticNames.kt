package me.mycellium.skymyce.features.social

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.FormattedCharSink
import net.minecraft.util.StringDecomposer
import java.text.Normalizer
import java.util.Optional

/** Validate an inert bounded tree before letting the pinned native codec preserve its text-component semantics. */
fun parseCosmeticText(json: JsonElement): Component {
    require(json.toString().toByteArray(Charsets.UTF_8).size <= 8192) { "Name JSON may contain at most 8 KiB." }
    var nodes = 0
    val plain = StringBuilder()
    fun text(value: String) {
        require(value.matches(Regex("[\\p{L}\\p{N}\\p{S} _.'•·»«-]*")) && value.none { it == '§' || it == '@' } && Normalizer.normalize(value, Normalizer.Form.NFKC) == value) { "Name text contains unsupported characters or noncanonical Unicode." }
        plain.append(value); require(plain.length <= 32) { "Display names may contain at most 32 characters." }
    }
    fun validate(value: JsonElement, depth: Int) {
        require(depth <= 8 && ++nodes <= 64) { "Name JSON is too complex." }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) { text(value.asString); return }
        if (value.isJsonArray) {
            require(value.asJsonArray.size() in 1..64) { "Name arrays must contain text components." }; value.asJsonArray.forEach { validate(it, depth + 1) }; return
        }
        require(value.isJsonObject) { "Use text strings, objects or arrays in name JSON." }
        val node = value.asJsonObject
        require(node.keySet().all { it in setOf("text", "color", "bold", "italic", "underlined", "strikethrough", "extra") }) { "Only text, color, bold, italic, underlined, strikethrough and extra are allowed." }
        require(node.has("text") && node["text"].isJsonPrimitive && node["text"].asJsonPrimitive.isString) { "Every name object needs a text string." }
        text(node["text"].asString)
        node.get("color")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && TextColor.parseColor(it.asString).result().isPresent) { "Use a Minecraft color name or #RRGGBB." }
        }
        for (key in listOf("bold", "italic", "underlined", "strikethrough")) node.get(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Text style flags must be true or false." }
        }
        node.get("extra")?.let { require(it.isJsonArray && it.asJsonArray.size() in 0..64) { "Extra must be an array of text components." }; it.asJsonArray.forEach { child -> validate(child, depth + 1) } }
    }
    validate(json, 0)
    require(plain.isNotBlank() && plain.toString() == plain.toString().trim()) { "Names must not be blank or start/end with spaces." }
    return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow { IllegalArgumentException("Invalid Minecraft name JSON.") }
}

fun cosmeticTextJson(component: Component): JsonElement = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).result().orElseThrow()

/** Display-only copies. Never mutate received components, click actions, command input or item data. */
class CosmeticNameMap {
    private var names: Map<String, Component> = emptyMap()
    private val cache = linkedMapOf<FormattedCharSequence, FormattedCharSequence>()
    private val strings = linkedMapOf<String, FormattedCharSequence>()
    fun update(values: Map<String, Component>): Boolean {
        val normalized = values.mapKeys { it.key.lowercase() }
        if (normalized == names) return false
        names = normalized; cache.clear(); strings.clear(); return true
    }
    val empty get() = names.isEmpty()
    fun sequence(source: FormattedCharSequence): FormattedCharSequence {
        if (source is DisplaySequence || source is OriginalSequence || names.isEmpty()) return source
        cache[source]?.let { return it }
        val text = StringBuilder(); val styles = ArrayList<Style>()
        source.accept { _, style, code ->
            if (text.length >= 8192) false else { text.appendCodePoint(code); repeat(Character.charCount(code)) { styles += style }; true }
        }
        if (text.length >= 8192) return source
        val matches = Regex("[A-Za-z0-9_]+").findAll(text).mapNotNull { match ->
            names[match.value.lowercase()]?.let { match.range to it }
        }.toList()
        val result = if (matches.isEmpty()) source else {
            val parts = mutableListOf<FormattedCharSequence>()
            fun original(start: Int, end: Int) {
                var offset = start
                while (offset < end) {
                    val style = styles[offset]; var next = offset + 1
                    while (next < end && styles[next] == style) next++
                    parts += FormattedCharSequence.forward(text.substring(offset, next), style); offset = next
                }
            }
            var offset = 0
            for ((range, component) in matches) {
                original(offset, range.first)
                component.visit(FormattedText.StyledContentConsumer<Unit> { style, value ->
                    parts += FormattedCharSequence.forward(value, style); Optional.empty()
                }, styles[range.first])
                offset = range.last + 1
            }
            original(offset, text.length)
            DisplaySequence(source, FormattedCharSequence.composite(parts))
        }
        if (cache.size >= 256) cache.remove(cache.keys.first())
        cache[source] = result
        return result
    }
    fun string(value: String): FormattedCharSequence {
        strings[value]?.let { return it }
        val raw = FormattedCharSequence { sink -> StringDecomposer.iterateFormatted(value, Style.EMPTY, sink) }
        val result = sequence(raw)
        if (strings.size >= 256) strings.remove(strings.keys.first())
        strings[value] = result
        return result
    }
}
private class DisplaySequence(val source: FormattedCharSequence, val displayed: FormattedCharSequence) : FormattedCharSequence {
    override fun accept(sink: FormattedCharSink) = displayed.accept(sink)
}
private class OriginalSequence(val source: FormattedCharSequence) : FormattedCharSequence {
    override fun accept(sink: FormattedCharSink) = source.accept(sink)
}
private class OriginalComponent(private val source: Component) : Component by source {
    override fun getVisualOrderText(): FormattedCharSequence = OriginalSequence(source.visualOrderText)
}

/** Filled by the relay/roster update, not by render hooks. No network or map scans in rendering. */
object CosmeticNames {
    private val mapping = CosmeticNameMap()
    private val suppressed = ThreadLocal.withInitial { 0 }
    @JvmStatic var enabled = false
    fun update(values: Map<String, Component>) = mapping.update(values)
    @JvmStatic fun active() = enabled && suppressed.get() == 0 && !mapping.empty
    @JvmStatic fun enterOriginal() { suppressed.set(suppressed.get() + 1) }
    @JvmStatic fun exitOriginal() { suppressed.set((suppressed.get() - 1).coerceAtLeast(0)) }
    @JvmStatic fun original(source: FormattedCharSequence): FormattedCharSequence = OriginalSequence(if (source is DisplaySequence) source.source else source)
    fun original(source: Component): Component = OriginalComponent(source)
    /** A chosen cosmetic is already decorated, including when it contains the real username itself. */
    @JvmStatic fun decorated(source: Component): Component = if (source is OriginalComponent) source else OriginalComponent(source)
    @JvmStatic fun display(source: FormattedCharSequence): FormattedCharSequence =
        if (active()) mapping.sequence(source) else if (source is DisplaySequence) source.source else source
    @JvmStatic fun display(value: String): FormattedCharSequence = mapping.string(value)
    @JvmStatic fun display(value: FormattedText): FormattedText {
        if (!active() || value is OriginalComponent) return value
        val parts = mutableListOf<FormattedCharSequence>()
        value.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            parts += FormattedCharSequence { sink -> StringDecomposer.iterateFormatted(text, style, sink) }; Optional.empty()
        }, Style.EMPTY)
        val raw = if (value is Component) value.visualOrderText else FormattedCharSequence.composite(parts)
        val displayed = display(raw)
        if (displayed === raw) return value
        val output = Component.empty()
        displayed.accept { _, style, code -> output.append(Component.literal(String(Character.toChars(code))).withStyle(style)); true }
        return decorated(output)
    }
}
