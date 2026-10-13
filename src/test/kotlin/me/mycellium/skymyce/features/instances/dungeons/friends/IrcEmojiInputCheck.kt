package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode

internal fun checkIrcEmojiInput() {
    val measure: (String, Boolean) -> Int = { value, emoji -> if (emoji) 9 else value.codePointCount(0, value.length) * 6 }
    val flag = "🏳️‍⚧️"
    val raw = "/msg Name ab $flag 👩🏽‍💻 🇸🇪 end"
    val layout = IrcEmojiInput.layout(raw)
    check(layout.value == raw && layout.hasEmoji)
    check(IrcEmojiInput.layout(raw) === layout)
    check(!IrcEmojiInput.layout("ordinary <a:custom:12345678901234567> text").hasEmoji)
    check(!IrcEmojiInput.layout("☀\uFE0E").hasEmoji)
    val start = raw.indexOf(flag)
    val end = start + flag.length
    val left = layout.position(start, measure)
    val right = layout.position(end, measure)
    check(right - left == 9)
    check(layout.floor(start + 3) == start && layout.next(start) == end)
    check(layout.index(left + 4, measure) == start && layout.index(left + 5, measure) == end)
    var previous = -1
    for (index in -2..raw.length + 2) {
        val width = layout.position(index, measure)
        check(width >= previous && width in 0..layout.position(raw.length, measure))
        previous = width
        val floored = layout.floor(index)
        check(floored in 0..raw.length && (floored == 0 || floored == raw.length || !raw[floored].isLowSurrogate()))
        if (index in 1 until raw.length && raw[index].isLowSurrogate()) check(width == layout.position(index - 1, measure))
    }
    for (pixel in -2..layout.position(raw.length, measure) + 2) {
        val index = layout.index(pixel, measure)
        check(index in 0..raw.length && (index == raw.length || !raw[index].isLowSurrogate()))
        check(index !in start + 1 until end)
    }
    val scrolled = layout.scroll(raw.length, 0, 45, measure)
    check(scrolled > 0 && layout.floor(scrolled) == scrolled)
    check(layout.position(raw.length, measure) - layout.position(scrolled, measure) <= 45)
    val shortened = IrcEmojiInput.layout("abc $flag d")
    check(shortened.scroll(shortened.value.length, scrolled, 45, measure) == 0)
    check(layout.scroll(start, scrolled, 45, measure) <= start)
    for (cursor in 0..raw.length) {
        val origin = layout.scroll(cursor, scrolled, 45, measure)
        check(origin in 0..cursor && layout.floor(origin) == origin)
        check(layout.position(cursor, measure) - layout.position(origin, measure) in 0..45)
    }
    val style = Style.EMPTY.withColor(0xFFAA00).withUnderlined(true)
    val shown = StringBuilder()
    val emojiIndices = arrayListOf<Int>()
    layout.display(FormattedCharSequence.forward(raw, style)).accept { index, painted, code ->
        shown.appendCodePoint(code)
        if (code in 0xE000..0xEFFF) {
            emojiIndices.add(index)
            check(painted.font != style.font && painted.color?.value == 0xFFFFFF)
        } else check(painted == style)
        true
    }
    check(emojiIndices == listOf(start, raw.indexOf("👩"), raw.indexOf("🇸")))
    check(shown.length == raw.length - flag.length - "👩🏽‍💻".length - "🇸🇪".length + 3)
    check(layout.value == raw) // Painting, selection and measurement never change clipboard/wire text.

    val mixin = ClassNode().also { node ->
        ClassReader(requireNotNull(IrcEmojiInputLayout::class.java.getResourceAsStream("/me/mycellium/mixin/CosmeticEditBoxMixin.class")))
            .accept(node, 0)
    }
    check(mixin.methods.none { method -> method.instructions.any { instruction ->
        instruction is FieldInsnNode && instruction.opcode == Opcodes.PUTFIELD && instruction.name in setOf("value", "cursorPos", "highlightPos")
    } })
    val native = ClassNode().also { node ->
        ClassReader(requireNotNull(IrcEmojiInputLayout::class.java.getResourceAsStream("/net/minecraft/client/gui/components/EditBox.class")))
            .accept(node, ClassReader.SKIP_CODE)
    }
    val visibility = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC
    val shadow = "Lorg/spongepowered/asm/mixin/Shadow;"
    for (field in mixin.fields.filter { (it.visibleAnnotations.orEmpty() + it.invisibleAnnotations.orEmpty()).any { it.desc == shadow } }) {
        val original = native.fields.single { it.name == field.name && it.desc == field.desc }
        check((original.access and visibility) == (field.access and visibility))
        if ((field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty()).any { it.desc == "Lorg/spongepowered/asm/mixin/Final;" })
            check(original.access and Opcodes.ACC_FINAL != 0)
    }
    for (method in mixin.methods.filter { (it.visibleAnnotations.orEmpty() + it.invisibleAnnotations.orEmpty()).any { it.desc == shadow } }) {
        val original = native.methods.single { it.name == method.name && it.desc == method.desc }
        check((original.access and visibility) == (method.access and visibility))
    }
}
