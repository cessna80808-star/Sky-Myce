package me.mycellium.skymyce.features.general

import com.google.gson.Gson
import me.mycellium.skymyce.config.misc.SignsConfig.EnterConfirms
import me.mycellium.skymyce.features.general.overlays.DialogueOption
import me.mycellium.skymyce.features.general.overlays.bars.*
import me.mycellium.skymyce.features.general.overlays.dialogueDuration
import me.mycellium.skymyce.features.general.overlays.isOptionsLine
import me.mycellium.skymyce.features.general.overlays.optionsFrom
import me.mycellium.skymyce.features.general.overlays.parseNpcLine
import me.mycellium.skymyce.features.general.overlays.revealedChars
import me.mycellium.skymyce.features.general.overlays.skillProgress
import net.minecraft.network.chat.ClickEvent

private const val W = 480
private const val H = 270

private fun checkLayout() {
    val layout = StatusBarLayout.default()
    check(layout.bars.map { it.type } == BarType.entries.map { it.id }) { "One entry per bar, in order" }
    val rects = layoutRects(layout, W, H)
    val health = rects.getValue(BarType.HEALTH); val mana = rects.getValue(BarType.MANA)
    // Health and mana share the row above the hotbar: together exactly the hotbar's 182 pixels with a gap.
    check(health.x == W / 2 - 91 && health.y == mana.y && mana.right == W / 2 + 91 && mana.x == health.right + BAR_GAP) { "$health $mana" }
    check(health.bottom == H - 35) { "Rows stack up from above the experience bar" }
    // Defense and speed stack right of the hotbar, vitality and respiration left of it, bottom up.
    val defense = rects.getValue(BarType.DEFENSE); val speed = rects.getValue(BarType.SPEED)
    check(defense.x == W / 2 + 95 && speed.x == defense.x && speed.bottom == defense.y - BAR_GAP)
    val vitality = rects.getValue(BarType.VITALITY)
    check(vitality.right == W / 2 - 95 && vitality.width == 75) { "$vitality" }

    // Inserting beside health splits the target row in three, still 12 units.
    val three = layout.detach(BarType.SPEED).insert(BarType.SPEED, BarAnchor.HOTBAR_TOP, 0, 1, newRow = false)
    val row = three.bars.filter { it.anchor == BarAnchor.HOTBAR_TOP.id }.sortedBy { it.slot }
    check(row.map { it.type } == listOf("health", "speed", "mana") && row.sumOf { it.size } == 12) { row.toString() }
    check(three.bar(BarType.DEFENSE).row == 0) { "Speed's old row closes up" }
    // A new row above health pushes nothing below it; a new row at 0 pushes the old row up.
    val above = layout.detach(BarType.SPEED).insert(BarType.SPEED, BarAnchor.HOTBAR_TOP, 0, 0, newRow = true)
    check(above.bar(BarType.SPEED).row == 0 && above.bar(BarType.HEALTH).row == 1 && above.bar(BarType.SPEED).size == 12)
    check(layoutRects(above, W, H).getValue(BarType.HEALTH).bottom == H - 35 - BAR_HEIGHT - BAR_GAP)
    // Resize moves units between neighbours and never past the limits.
    val wider = layout.resize(BarType.HEALTH, BarType.MANA, 1)
    check(wider.bar(BarType.HEALTH).size == 7 && wider.bar(BarType.MANA).size == 5)
    var squeezed = layout
    repeat(20) { squeezed = squeezed.resize(BarType.HEALTH, BarType.MANA, 1) }
    check(squeezed.bar(BarType.MANA).size == BarAnchor.HOTBAR_TOP.rule.min && squeezed.bar(BarType.HEALTH).size == 12 - BarAnchor.HOTBAR_TOP.rule.min)
    check(layout.resize(BarType.DEFENSE, null, 2).bar(BarType.DEFENSE).size == 5 && layout.resize(BarType.DEFENSE, null, 9).bar(BarType.DEFENSE).size == 6)
    // Floating stays where it was put, on screen.
    val floating = layout.float(BarType.MANA, 40, 60, 120, W, H)
    check(layoutRects(floating, W, H).getValue(BarType.MANA) == BarRect(40, 60, 120, BAR_HEIGHT))
    check(layoutRects(floating, W, H).getValue(BarType.HEALTH).width == 182) { "Health takes the whole row once mana floats" }
    check(layoutRects(layout.float(BarType.MANA, 470, 300, 120, W, H), W, H).getValue(BarType.MANA).let { it.right <= W && it.bottom <= H })
    // Hide removes it; show brings it back on its default anchor.
    val hidden = layout.hide(BarType.SPEED)
    check(BarType.SPEED !in layoutRects(hidden, W, H) && hidden.show(BarType.SPEED).bar(BarType.SPEED).let { it.enabled && it.anchor == BarAnchor.HOTBAR_RIGHT.id })
    // Saved and read back unchanged; damaged values are repaired.
    val json = Gson().toJson(three)
    check(Gson().fromJson(json, StatusBarLayout::class.java).normalized() == three)
    val damaged = StatusBarLayout(bars = listOf(BarConfig("health", anchor = "nowhere", x = Float.NaN, width = 2), BarConfig("bogus"))).normalized()
    check(damaged.bars.size == BarType.entries.size && damaged.bar(BarType.HEALTH).let { it.anchor == null && it.x == 0.5f && it.width == StatusBarLayout.MIN_FREE_WIDTH })
    check(distribute(listOf(1, 1, 1, 1, 1, 1, 1), BarAnchor.HOTBAR_TOP.rule).sum() == 12)
}

private fun checkSnapping() {
    val others = mapOf(BarType.HEALTH to BarRect(100, 200, 80, BAR_HEIGHT))
    // Near another bar's left edge: aligns; near the space just below it: stacks with the gap.
    val snapped = snapFloating(BarRect(102, 214, 60, BAR_HEIGHT), others.values, W, H)
    check(snapped.x == 100 && snapped.y == 200 + BAR_HEIGHT + BAR_GAP && snapped.guidesX == listOf(100)) { snapped.toString() }
    check(snapFloating(BarRect(W / 2 - 31, 50, 60, BAR_HEIGHT), emptyList(), W, H).x == W / 2 - 30) { "Centres on the screen" }
    check(snapFloating(BarRect(300, 40, 60, BAR_HEIGHT), others.values, W, H).let { it.x == 300 && it.guidesX.isEmpty() }) { "Far from everything: no snap" }
    // Drops: beside an anchored bar joins its row, above starts a row, an empty anchor starts there.
    val layout = StatusBarLayout.default().detach(BarType.SPEED)
    val rects = layoutRects(layout, W, H) { it.enabled && it.type != "speed" }
    val health = rects.getValue(BarType.HEALTH)
    val dragged = BarRect(0, 0, 60, BAR_HEIGHT)
    check(barDropAt(layout, rects, health.x - 3.0, health.y + 4.0, dragged, W, H, true) == BarDrop.Attach(BarAnchor.HOTBAR_TOP, 0, 0, false))
    check(barDropAt(layout, rects, health.x + 40.0, health.y - 3.0, dragged, W, H, true) == BarDrop.Attach(BarAnchor.HOTBAR_TOP, 1, 0, true)) { "Above: a new outer row" }
    val left = BarAnchor.TOP_LEFT.hitbox(W, H)
    check(barDropAt(layout, rects, left.x + 5.0, left.y + 5.0, dragged, W, H, true) == BarDrop.Attach(BarAnchor.TOP_LEFT, 0, 0, true))
    check(barDropAt(layout, rects, health.x - 3.0, health.y + 4.0, dragged, W, H, false) is BarDrop.Float) { "Without snapping it floats" }
}

private fun checkReadings() {
    // Hearts drive health; the action bar gives the maximum.
    check(estimateHealth(2000, 2000, 0.5) == 1000 && estimateHealth(1500, 2000, null) == 1500 && estimateHealth(2400, 2000, null) == 2000)
    check(absorptionFrom(2400, 2000, 1.0) == 400 && absorptionFrom(1400, 2000, 0.5) == 400 && absorptionFrom(1800, 2000, null) == 0)
    check(estimateHealth(10, 0, 0.5) == 10 && estimateHealth(500, 1000, Double.NaN) == 500) { "Unusable inputs fall back" }
    check(speedValue(0.1, false) == 100 && speedValue(0.13, true) == 100 && speedValue(0.4, false) == 400)
    check(speedMax(300) == 400 && speedMax(450) == 500 && defenseFill(100) == 0.5 && defenseFill(-5) == 0.0)
    check(parsePair("§c1,200/1,500♨") == 1200 to 1500 && parsePair("nothing") == null)
    check(effectiveHealth(1000, 100) == 2000.0 && statusNumber(1_234_567) == "1,234,567")
    val health = StatusBarLayout.defaultBar(BarType.HEALTH)
    check(barLabel(BarType.HEALTH, health, 1834, 2140, 220) == "1,834 +220" && barLabel(BarType.HEALTH, health.copy(showMax = true), 1834, 2140, 220) == "1,834/2,140 +220")
    check(barLabel(BarType.HEALTH, health.copy(showMax = false, showOverflow = false), 1834, 2140, 220) == "1,834")
    val defense = StatusBarLayout.defaultBar(BarType.DEFENSE)
    check(barLabel(BarType.DEFENSE, defense, 612, 0, 0, 1000) == "612" && barLabel(BarType.DEFENSE, defense.copy(extra = true), 100, 0, 0, 1000).endsWith("EHP"))
    check(barLabel(BarType.RESPIRATION, StatusBarLayout.defaultBar(BarType.RESPIRATION), 150, 300, 0) == "50%")
}

private fun checkMotion() {
    // A loss: the fill springs down without overshoot while the trail holds, then drains to the fill.
    val m = BarMotion()
    m.update(1.0, 100.0, true, 0, true)
    m.update(0.5, 50.0, true, 16, true)
    check(m.trail == 1.0 && m.flash(16) > 0) { "Big hit: trail and flash" }
    var low = 1.0
    for (t in 2..60) { m.update(0.5, 50.0, true, t * 16L, true); low = minOf(low, m.shown) }
    check(low >= 0.5 - 1e-6 && m.shown == 0.5) { "Critically damped: no overshoot ($low)" }
    check(m.trail > 0.5 || m.trail == 0.5)
    for (t in 61..200) m.update(0.5, 50.0, true, t * 16L, true)
    check(m.trail == 0.5 && m.number == 50.0) { "Trail drained, number exact (${m.trail}, ${m.number})" }
    // The trail holds for its hold time before draining.
    val hold = BarMotion(); hold.update(1.0, 0.0, true, 0, true); hold.update(0.9, 0.0, true, 10, true)
    for (t in 2..20) hold.update(0.9, 0.0, true, t * 10L, true)
    check(hold.trail == 1.0) { "Still holding at 200 ms" }
    // A gain sweeps; small changes do not flash.
    val gain = BarMotion(); gain.update(0.5, 0.0, true, 0, true); gain.update(0.55, 0.0, true, 16, true)
    check(gain.heal(16) != null && gain.flash(16) == 0.0 && gain.healFrom == 0.5)
    // Without animation every value is exact; fading follows visibility.
    val exact = BarMotion(); exact.update(1.0, 10.0, true, 0, false); exact.update(0.25, 2.0, false, 5, false)
    check(exact.shown == 0.25 && exact.trail == 0.25 && exact.number == 2.0 && exact.alpha == 0.0)
    val fade = BarMotion(); fade.update(0.5, 0.0, false, 0, true); fade.update(0.5, 0.0, true, 80, true)
    check(fade.alpha in 0.4..0.6) { "Fades in over about 160 ms (${fade.alpha})" }
    // Heartbeat only below 30%, faster when lower.
    check(heartbeat(0.5, 1000) == 0.0 && (0 until 1100 step 10).maxOf { heartbeat(0.1, it.toLong()) } > 0.9)
}

private fun checkSkillAndDialogue() {
    check(skillProgress(42.5f, null, null) == 0.425 && skillProgress(null, 250, 1000) == 0.25 && skillProgress(null, null, null) == null)
    check(skillProgress(null, 5, 0) == null && skillProgress(150f, null, null) == 1.0)
    check(parseNpcLine("[NPC] Elle: Hello there, adventurer!")?.let { it.name == "Elle" && it.message == "Hello there, adventurer!" } == true)
    check(parseNpcLine("[NPC] Elle") == null && parseNpcLine("Party > [NPC] Elle: hi") == null && parseNpcLine("[VIP] Bob: [NPC] x: y") == null)
    check(isOptionsLine("Select an option: [Yes] [No]") && isOptionsLine("\nClick an option: [Sure]") && !isOptionsLine("Pick one"))
    val yes = ClickEvent.RunCommand("/selectnpcoption elle r_1_1"); val no = ClickEvent.RunCommand("/selectnpcoption elle r_2_1")
    val options = optionsFrom(listOf("Select an option: " to null, "[" to yes, "YES" to yes, "]" to yes, " " to null, "[NO]" to no))
    check(options == listOf(DialogueOption("YES", yes), DialogueOption("NO", no))) { options.toString() }
    check(dialogueDuration("one two three", 180, 2000) == 2000L && dialogueDuration(List(60) { "w" }.joinToString(" "), 120, 2000) == 30_000L)
    check(revealedChars(40, 0, 3000) == 0 && revealedChars(40, 500, 3000) == 20 && revealedChars(40, 1500, 3000) == 40)
}

private fun checkSigns() {
    val bid = listOf("", "^^^^^^^^^^^^^^^", "Your bid", "")
    val bazaar = listOf("12", "^^^^^^^^^^^^^^^", "Enter amount", "to buy")
    val other = listOf("", "^^^^^^^^^^^^^^^", "Enter your", "message")
    val placed = listOf("Hello", "world", "", "")
    check(signConfirms(EnterConfirms.MARKET, bid, null) && signConfirms(EnterConfirms.MARKET, bazaar, null))
    check(!signConfirms(EnterConfirms.MARKET, other, null) && signConfirms(EnterConfirms.MARKET, other, "Auction House")) { "A market menu seen first counts" }
    check(signConfirms(EnterConfirms.PROMPTS, other, null) && !signConfirms(EnterConfirms.PROMPTS, placed, null))
    check(signConfirms(EnterConfirms.ALL, placed, null) && !signConfirms(EnterConfirms.OFF, bid, "Bazaar") && !signConfirms(EnterConfirms.MARKET, placed, "Bazaar"))
}

/** Status bars (layout, snapping, readings, motion), the skill XP bar, NPC dialogue and sign Enter. */
fun overlaysCheck() {
    checkLayout(); checkSnapping(); checkReadings(); checkMotion(); checkSkillAndDialogue(); checkSigns()
}
