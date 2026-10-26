package me.mycellium.skymyce.ui

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

/**
 * Times the cosmetic-name text hook on HUD-like strings that contain no known name (the common case: nametags,
 * scoreboard lines, timers), as fresh objects each time so the cache misses as it does per frame in a dungeon.
 * Compares the previous approach (copy every character and style, then regex-scan) with the current pre-check.
 * Runs only with SKYMYCE_BENCH set; prints nanoseconds per string and bytes allocated per string.
 */
fun textHookBenchmark() {
    if (System.getenv("SKYMYCE_BENCH") == null) return
    val known = (1..60).associate { "Player$it" to Component.literal("Name$it") }
    val map = me.mycellium.skymyce.features.social.CosmeticNameMap().apply { update(known) }
    val lowered = known.keys.associateBy { it.lowercase() }
    val word = Regex("[A-Za-z0-9_]+")
    val lines = (0 until 400).map { i -> listOf("Score: ${290 + i % 10}", "Goldor: ${i % 60}.${i % 10}s", "Healer 45 [bar] 3,402", "Cata level 52 › Target 53",
        "❤ 5,163/6,000  ❈ 335  ✎ 2,259/2,400", "Time Elapsed: 0${i % 9}m ${i % 60}s", "[MVP+] SomeoneElse ✦")[i % 7] }
    fun previous(source: FormattedCharSequence): Int {
        val text = StringBuilder(); val styles = ArrayList<Style>()
        source.accept { _, style, code -> text.appendCodePoint(code); repeat(Character.charCount(code)) { styles += style }; true }
        return word.findAll(text).count { lowered.containsKey(it.value.lowercase()) }
    }
    val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    fun measure(label: String, block: (FormattedCharSequence) -> Any) {
        repeat(3) { lines.forEach { block(FormattedCharSequence.forward(it, Style.EMPTY)) } } // warm up
        val rounds = 200
        val bytes = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val start = System.nanoTime()
        repeat(rounds) { lines.forEach { block(FormattedCharSequence.forward(it, Style.EMPTY)) } }
        val elapsed = System.nanoTime() - start
        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - bytes
        val count = rounds * lines.size
        println("Text hook $label: ${elapsed / count} ns/string, ${allocated / count} B/string")
    }
    measure("previous") { previous(it) }
    measure("current") { map.sequence(it) }
    // Font.width(String) path: the string hook, then the sequence hook it calls back into. Fresh strings miss the cache.
    var serial = 0
    measure("string") { map.sequence(map.string("${lines[serial % lines.size]} ${serial++}")) }
    // The same HUD strings redrawn each frame, so the string cache hits.
    measure("repeated string") { map.sequence(map.string(lines[serial++ % lines.size])) }
}
