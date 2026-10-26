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

/** A bounded cache that empties when full. Reads never reorder it, so the per-draw lookup stays a plain hash read. */
private class BoundedCache<K, V>(private val capacity: Int) : HashMap<K, V>(capacity * 4 / 3 + 1) {
    fun store(key: K, value: V) { if (size >= capacity) clear(); put(key, value) }
}

private val nameWord = Regex("[A-Za-z0-9_]+")

private fun isNameChar(code: Int) = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code || code == '_'.code

/**
 * Display-only copies. Never mutate received components, click actions, command input or item data.
 *
 * This runs for every string any mod draws, so the common case (no known name in the text) must be cheap: one pass
 * over the characters hashing each word exactly as String.hashCode() would, checked against the sorted hashes of the
 * known names, with no allocation. Only text with a hash hit is copied and matched properly.
 */
class CosmeticNameMap {
    private var names: Map<String, Component> = emptyMap()
    private var nameHashes = IntArray(0)
    private var shortest = Int.MAX_VALUE
    private val cache = BoundedCache<FormattedCharSequence, FormattedCharSequence>(1024)
    private val strings = BoundedCache<String, FormattedCharSequence>(1024)
    private val texts = BoundedCache<FormattedCharSequence, FormattedText>(256)
    fun update(values: Map<String, Component>): Boolean {
        val normalized = values.mapKeys { it.key.lowercase() }
        if (normalized == names) return false
        names = normalized
        nameHashes = normalized.keys.map { it.hashCode() }.distinct().sorted().toIntArray()
        shortest = normalized.keys.minOfOrNull { it.length } ?: Int.MAX_VALUE
        cache.clear(); strings.clear(); texts.clear(); return true
    }
    val empty get() = names.isEmpty()

    /** True when some word of [source] may be a known name (a hash hit; the full match decides). */
    internal fun mayContainName(source: FormattedCharSequence): Boolean = NameScan(nameHashes, shortest).run(source)

    fun sequence(source: FormattedCharSequence): FormattedCharSequence {
        if (source is DisplaySequence || source is OriginalSequence || names.isEmpty()) return source
        cache[source]?.let { return it }
        if (!mayContainName(source)) return source
        val text = StringBuilder(); val styles = ArrayList<Style>()
        source.accept { _, style, code ->
            if (text.length >= 8192) false else { text.appendCodePoint(code); repeat(Character.charCount(code)) { styles += style }; true }
        }
        if (text.length >= 8192) return source
        val matches = nameWord.findAll(text).mapNotNull { match ->
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
        cache.store(source, result)
        return result
    }
    /** No-match results are marked original, so the sequence hook the caller goes through next does not scan again. */
    fun string(value: String): FormattedCharSequence {
        strings[value]?.let { return it }
        val raw = FormattedCharSequence { sink -> StringDecomposer.iterateFormatted(value, Style.EMPTY, sink) }
        if (value.length < shortest) return OriginalSequence(raw)
        val result = sequence(raw).let { if (it === raw) OriginalSequence(raw) else it }
        strings.store(value, result)
        return result
    }

    /** Component copy of a displayed sequence, kept so measuring the same text each frame does not rebuild it. */
    internal fun text(displayed: FormattedCharSequence, build: () -> FormattedText): FormattedText =
        texts[displayed] ?: build().also { texts.store(displayed, it) }
}

/** One object per scan instead of a lambda plus a boxed ref per local: this runs for every string any mod draws. */
private class NameScan(private val hashes: IntArray, private val shortest: Int) : FormattedCharSink {
    private var hash = 0; private var length = 0; private var found = false; private var seen = 0
    fun run(source: FormattedCharSequence): Boolean {
        source.accept(this)
        return found || (length >= shortest && hashes.binarySearch(hash) >= 0)
    }
    override fun accept(index: Int, style: Style, code: Int): Boolean {
        if (++seen > 8192) return false
        if (isNameChar(code)) {
            hash = 31 * hash + (if (code in 'A'.code..'Z'.code) code + 32 else code); length++
            return true
        }
        if (length >= shortest && hashes.binarySearch(hash) >= 0) { found = true; return false }
        hash = 0; length = 0; return true
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
    @JvmStatic fun active() = enabled && !mapping.empty && suppressed.get() == 0
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
        val raw = if (value is Component) value.visualOrderText else {
            val parts = mutableListOf<FormattedCharSequence>()
            value.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
                parts += FormattedCharSequence { sink -> StringDecomposer.iterateFormatted(text, style, sink) }; Optional.empty()
            }, Style.EMPTY)
            FormattedCharSequence.composite(parts)
        }
        val displayed = display(raw)
        if (displayed === raw) return value
        return mapping.text(displayed) {
            val output = Component.empty()
            displayed.accept { _, style, code -> output.append(Component.literal(String(Character.toChars(code))).withStyle(style)); true }
            decorated(output)
        }
    }
}
