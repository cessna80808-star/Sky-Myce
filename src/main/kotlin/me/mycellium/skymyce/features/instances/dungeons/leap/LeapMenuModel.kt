package me.mycellium.skymyce.features.instances.dungeons.leap

import java.util.Locale
import java.util.UUID
import org.lwjgl.glfw.GLFW

private val leapNamePattern = Regex("^(?:\\[[^]]+]\\s*)*([A-Za-z0-9_]{1,16})$")
private val leapHeadPattern = Regex("^(?:\\[[^]]+]\\s*)*([A-Za-z0-9_]{1,16})(?:\\s+\\((?:DEAD|Archer|Berserk(?:er)?|Healer|Mage|Tank)(?:\\s+[IVXLCDM0-9]+)?\\))?$", RegexOption.IGNORE_CASE)
private val leapScoreboardPattern = Regex("^\\[([ABHMT])]\\s+([A-Za-z0-9_]{1,16})\\s+(?:[0-9][0-9,.]*[kmb]?\\s*[❤♥]|DEAD)$", RegexOption.IGNORE_CASE)
private val leapFormatting = Regex("§.")

fun isLeapMenuTitle(value: String): Boolean = value.replace(leapFormatting, "").trim().lowercase(Locale.ROOT)
    .let { it == "spirit leap" || it == "teleport to player" }

fun normalizedLeapName(value: String): String? = leapNamePattern.matchEntire(value.replace(leapFormatting, "").trim())
    ?.groupValues?.get(1)?.lowercase(Locale.ROOT)

fun leapHeadName(value: String): String? = leapHeadPattern.matchEntire(value.replace(leapFormatting, "").trim())
    ?.groupValues?.get(1)?.lowercase(Locale.ROOT)

fun leapHeadIsDead(value: String): Boolean = value.replace(leapFormatting, "").trim().endsWith("(DEAD)", ignoreCase = true)

fun leapScoreboardClass(name: String, lines: List<String>): String? {
    val key = normalizedLeapName(name) ?: return null
    val match = lines.mapNotNull { leapScoreboardPattern.matchEntire(it.replace(leapFormatting, "").trim()) }
        .filter { it.groupValues[2].lowercase(Locale.ROOT) == key }.singleOrNull() ?: return null
    return when (match.groupValues[1].uppercase(Locale.ROOT)) {
        "A" -> "Archer"
        "B" -> "Berserk"
        "H" -> "Healer"
        "M" -> "Mage"
        "T" -> "Tank"
        else -> null
    }
}

fun leapKeyQuadrant(key: Int): Int? = when (key) {
    in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_4 -> key - GLFW.GLFW_KEY_1
    in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_4 -> key - GLFW.GLFW_KEY_KP_1
    else -> null
}

class LeapKeys {
    private val pressed = mutableSetOf<Int>()

    fun press(key: Int, focused: Int): Int? {
        val quadrant = leapKeyQuadrant(key) ?: focused.takeIf {
            key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE
        } ?: return null
        return quadrant.takeIf { it in 0..3 && pressed.add(key) }
    }

    fun release(key: Int) { pressed.remove(key) }
}

/** One manual choice may wait briefly for the server's menu, never for a later use. */
class LeapIntent {
    private var started: Long? = null
    private var confirmed = false
    private var quadrant: Int? = null

    fun arm(now: Long) { clear(); started = now }

    fun active(now: Long): Boolean {
        val age = now - (started ?: return false)
        if (age !in 0..2_000_000_000L) { clear(); return false }
        return true
    }

    fun confirm(now: Long) { if (active(now)) confirmed = true }

    fun queue(choice: Int, now: Long): Boolean {
        if (choice !in 0..3 || !active(now)) return false
        if (quadrant == null) quadrant = choice
        return true
    }

    fun take(now: Long, ready: (Int) -> Boolean): Int? {
        if (!active(now) || !confirmed) return null
        val choice = quadrant?.takeIf(ready) ?: return null
        clear()
        return choice
    }

    fun clear() { started = null; confirmed = false; quadrant = null }
}

data class LeapIdentity(val name: String, val uuid: UUID? = null) {
    val key: String? get() = normalizedLeapName(name)
}

data class LeapCandidate(val identity: LeapIdentity, val slot: Int, val className: String, val dead: Boolean = false)

fun matchingLeapPlayer(target: LeapIdentity, players: List<LeapIdentity>): Int? {
    val name = target.key ?: return null
    val matches = players.indices.filter { index ->
        val player = players[index]
        if (target.uuid != null && target.uuid != UUID(0, 0)) player.uuid == target.uuid && player.key == name else player.key == name
    }
    return matches.singleOrNull()
}

/** Reserve identities until this menu closes, including during temporarily empty server slots. */
class LeapQuadrants {
    private val names = arrayOfNulls<String>(4)

    fun update(candidates: List<LeapCandidate>): List<LeapCandidate?> {
        val unique = candidates.filter { it.slot >= 0 && it.identity.key != null }
            .groupBy { it.identity.key }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        unique.values.sortedWith(compareBy<LeapCandidate> { it.className }.thenBy { it.identity.key }).forEach { candidate ->
            val key = candidate.identity.key ?: return@forEach
            if (key !in names) names.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { names[it] = key }
        }
        return names.map { unique[it] }
    }
}

data class LeapCameraPose(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

fun interpolatedLeapPose(previous: LeapCameraPose, current: LeapCameraPose, eyeHeight: Float, partialTicks: Float): LeapCameraPose? {
    if (!listOf(previous.x, previous.y, previous.z, current.x, current.y, current.z).all(Double::isFinite) ||
        !listOf(previous.yaw, previous.pitch, current.yaw, current.pitch, eyeHeight, partialTicks).all(Float::isFinite) || eyeHeight <= 0) return null
    val partial = partialTicks.coerceIn(0f, 1f)
    val yawDelta = ((current.yaw.toDouble() - previous.yaw) % 360 + 540) % 360 - 180
    val pose = LeapCameraPose(previous.x + (current.x - previous.x) * partial,
        previous.y + (current.y - previous.y) * partial + eyeHeight,
        previous.z + (current.z - previous.z) * partial,
        (((previous.yaw + yawDelta * partial) % 360 + 540) % 360 - 180).toFloat(),
        (previous.pitch + (current.pitch.toDouble() - previous.pitch) * partial).coerceIn(-89.9, 89.9).toFloat())
    return pose.takeIf { kotlin.math.abs(it.x) <= 30_000_000 && kotlin.math.abs(it.z) <= 30_000_000 && kotlin.math.abs(it.y) <= 4096 }
}

data class LeapRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(mouseX: Double, mouseY: Double) = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
}

fun leapMenuBounds(width: Int, height: Int): LeapRect {
    val cardWidth = minOf(300, width * 29 / 100).coerceAtLeast(1)
    val cardHeight = minOf(112, height * 17 / 100).coerceAtLeast(1)
    val menuWidth = cardWidth * 2 + (width * 7 / 100).coerceAtLeast(12)
    val menuHeight = cardHeight * 2 + (height / 10).coerceAtLeast(12)
    return LeapRect((width - menuWidth) / 2, (height - menuHeight) / 2, menuWidth, menuHeight)
}

fun leapQuadrantBounds(width: Int, height: Int, quadrant: Int): LeapRect {
    require(quadrant in 0..3)
    val menu = leapMenuBounds(width, height)
    val cardWidth = (menu.width - (width * 7 / 100).coerceAtLeast(12)) / 2
    val cardHeight = (menu.height - (height / 10).coerceAtLeast(12)) / 2
    return LeapRect(menu.x + if (quadrant % 2 == 0) 0 else menu.width - cardWidth,
        menu.y + if (quadrant < 2) 0 else menu.height - cardHeight, cardWidth, cardHeight)
}

fun leapFaceBounds(card: LeapRect): LeapRect {
    val padding = (card.height / 9).coerceIn(4, 12)
    val size = minOf(card.width * 3 / 10, card.height - padding * 2).coerceAtLeast(1)
    return LeapRect(card.x + padding, card.y + (card.height - size) / 2, size, size)
}

fun leapHoveredQuadrant(width: Int, height: Int, mouseX: Double, mouseY: Double): Int? =
    (0..3).firstOrNull { leapQuadrantBounds(width, height, it).contains(mouseX, mouseY) }

fun leapSlotForClick(displayed: LeapCandidate, current: List<LeapCandidate>): Int? {
    val name = displayed.identity.key ?: return null
    if (displayed.dead || displayed.slot < 0) return null
    return current.singleOrNull { it.identity.key == name }?.takeIf {
        it.slot == displayed.slot && it.identity.uuid == displayed.identity.uuid && !it.dead
    }?.slot
}
