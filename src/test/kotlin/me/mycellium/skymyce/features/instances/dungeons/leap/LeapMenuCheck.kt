package me.mycellium.skymyce.features.instances.dungeons.leap

import java.util.UUID
import kotlin.math.abs
import org.lwjgl.glfw.GLFW
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera.Status

fun main() {
    checkLeapPeers()
    checkLeapSharing()
    val archer = LeapCandidate(LeapIdentity("Alice", UUID.fromString("12345678-1234-1234-1234-123456789abc")), 11, "Archer")
    val tank = LeapCandidate(LeapIdentity("Bob"), 15, "Tank")
    val healer = LeapCandidate(LeapIdentity("Carol"), 12, "Healer")
    val mage = LeapCandidate(LeapIdentity("David"), 14, "Mage")
    check(normalizedLeapName("§b[MVP+] §fALIce") == "alice")
    check(normalizedLeapName("[MVP+] [TAG] Alice_1") == "alice_1")
    listOf("", "Alice Bob", "[MVP+]", "§aClick to teleport!", "Alice/../../", "x".repeat(17)).forEach { check(normalizedLeapName(it) == null) }
    check(isLeapMenuTitle("§6Spirit Leap "))
    check(isLeapMenuTitle(" teleport to player"))
    listOf("Spirit Leap Shop", "Not Spirit Leap", "Auction House", "").forEach { check(!isLeapMenuTitle(it)) }
    check(leapHeadName("§b[MVP+] Alice §e(Mage 30)") == "alice")
    check(leapHeadName("[MVP+] [TAG] Alice_1 (DEAD)") == "alice_1")
    check(leapHeadIsDead("§b[MVP+] Alice §c(DEAD) "))
    check(!leapHeadIsDead("Alice (Mage 30)"))
    listOf("Alice Bob", "Click to teleport!", "Alice (Other)", "[MVP+]", "x".repeat(17)).forEach { check(leapHeadName(it) == null) }

    val scoreboard = listOf("The Catacombs (F7)", "§e[B] §aAlice §a9,115§c❤", "[H] Carol 6,217❤",
        "[B] Bob 7,923❤", "[B] David 8,252❤")
    listOf("Alice", "Bob", "David").forEach { check(leapScoreboardClass(it, scoreboard) == "Berserk") }
    check(leapScoreboardClass("CAROL", scoreboard) == "Healer")
    listOf("A" to "Archer", "B" to "Berserk", "H" to "Healer", "M" to "Mage", "T" to "Tank").forEach { (id, name) ->
        check(leapScoreboardClass("Alice", listOf("[$id] Alice 12.5k♥")) == name)
        check(leapScoreboardClass("Alice", listOf("[$id] Alice DEAD")) == name)
    }
    check(leapScoreboardClass("Alice", listOf("[M] Alice_1 100❤", "[T] Alicee 100❤")) == null)
    check(leapScoreboardClass("Alice", listOf("[M] Alice 100❤", "[T] ALICE 100❤")) == null)
    check(leapScoreboardClass("Alice", listOf("[M] Alice 100❤", "[M] Alice 100❤")) == null)
    listOf("[X] Alice 100❤", "Party > [M] Alice 100❤", "[M] Alice 100❤ trailing", "[M] Alice", "[M] Alice ??❤").forEach {
        check(leapScoreboardClass("Alice", listOf(it)) == null)
    }
    check(leapScoreboardClass("Alice Bob", listOf("[M] Alice 100❤")) == null)
    check(leapScoreboardClass("Alice", emptyList()) == null)

    for (status in Status.entries) {
        check(leapPovCaption(false, false, false, status) == null)
        check(leapPovCaption(true, true, false, status) == null)
        check(leapPovCaption(true, false, false, status) == "POV out of range")
    }
    check(leapPovCaption(true, false, true, Status.WAITING_CHUNKS) == "World not loaded")
    check(leapPovCaption(true, false, true, Status.PREPARING) == "Loading POV…")
    check(leapPovCaption(true, false, true, Status.LIVE) == null)
    check(leapPovCaption(true, false, true, Status.TERRAIN_ONLY) == null)
    check(leapPovCaption(true, false, true, Status.FAILED) == "POV unavailable")

    val keys = LeapKeys()
    for (key in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_4) {
        check(keys.press(key, -1) == key - GLFW.GLFW_KEY_1)
        check(keys.press(key, 3) == null)
        keys.release(key)
        check(keys.press(key, -1) == key - GLFW.GLFW_KEY_1)
        keys.release(key)
    }
    for (key in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_4) {
        check(keys.press(key, -1) == key - GLFW.GLFW_KEY_KP_1)
        check(keys.press(key, 0) == null)
        keys.release(key)
    }
    check(keys.press(GLFW.GLFW_KEY_ENTER, 2) == 2)
    check(keys.press(GLFW.GLFW_KEY_ENTER, 0) == null)
    keys.release(GLFW.GLFW_KEY_ENTER)
    check(keys.press(GLFW.GLFW_KEY_ENTER, -1) == null)
    check(keys.press(GLFW.GLFW_KEY_KP_ENTER, 3) == 3)
    check(keys.press(GLFW.GLFW_KEY_SPACE, 0) == 0)
    listOf(GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_0, GLFW.GLFW_KEY_KP_5,
        GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_E).forEach { check(keys.press(it, 1) == null) }

    val intent = LeapIntent()
    val now = 10_000_000_000L
    check(!intent.queue(0, now))
    intent.confirm(now)
    check(intent.take(now) { true } == null)
    intent.arm(now)
    check(intent.queue(0, now + 1)) // The number can precede the actual item-use callback in the same tick.
    check(intent.take(now + 2) { true } == null)
    intent.confirm(now + 3)
    check(intent.take(now + 4) { false } == null) // Wait for the complete menu and selected target.
    check(intent.queue(3, now + 5))
    check(intent.take(now + 6) { it == 0 } == 0) // The first explicit choice wins; it is consumed only once.
    check(intent.take(now + 7) { true } == null)
    check(!intent.queue(1, now + 8))
    intent.arm(now)
    check(!intent.queue(-1, now) && !intent.queue(4, now))
    intent.queue(2, now)
    intent.confirm(now)
    check(intent.take(now + 2_000_000_001) { true } == null)
    intent.arm(now)
    intent.queue(2, now)
    intent.confirm(now)
    intent.clear() // Cancelled use, a different action/menu, or a changed/disconnected client context.
    check(intent.take(now + 1) { true } == null && !intent.active(now + 1))
    intent.arm(now)
    intent.queue(1, now)
    intent.confirm(now)
    intent.arm(now + 1) // Another manual use cannot inherit an earlier number or confirmation.
    intent.confirm(now + 2)
    check(intent.take(now + 3) { true } == null)
    check(intent.queue(3, now + 4))
    check(intent.take(now + 5) { true } == 3)
    intent.arm(now)
    check(!intent.active(now - 1))

    val quadrants = LeapQuadrants()
    check(quadrants.update(listOf(tank, mage, archer, healer)) == listOf(archer, healer, mage, tank))
    check(quadrants.update(listOf(tank, archer)) == listOf(archer, null, null, tank))
    check(quadrants.update(emptyList()) == List(4) { null })
    check(quadrants.update(listOf(healer, tank, archer.copy(slot = 13), mage)) == listOf(archer.copy(slot = 13), healer, mage, tank))
    check(LeapQuadrants().update(listOf(archer, archer.copy(slot = 14), tank)) == listOf(tank, null, null, null))
    check(LeapQuadrants().update(listOf(tank.copy(slot = -1))).all { it == null })

    val identities = listOf(LeapIdentity("§b[MVP+] alice", archer.identity.uuid), tank.identity)
    check(matchingLeapPlayer(archer.identity, identities) == 0)
    check(matchingLeapPlayer(archer.identity.copy(uuid = UUID.randomUUID()), identities) == null)
    check(matchingLeapPlayer(archer.identity, listOf(archer.identity.copy(name = "Other"))) == null)
    check(matchingLeapPlayer(tank.identity.copy(name = "BOB"), identities) == 1)
    check(matchingLeapPlayer(healer.identity, identities) == null)
    check(matchingLeapPlayer(tank.identity, listOf(tank.identity, tank.identity)) == null)
    check(archer.identity.uuid == UUID.fromString("12345678-1234-1234-1234-123456789ABC"))

    val before = LeapCameraPose(10.0, 20.0, 30.0, 179f, -10f)
    val after = LeapCameraPose(14.0, 24.0, 38.0, -179f, 30f)
    val interpolated = checkNotNull(interpolatedLeapPose(before, after, 1.62f, 0.5f))
    check(abs(interpolated.x - 12) < 0.0001 && abs(interpolated.y - 23.62) < 0.0001 && abs(interpolated.z - 34) < 0.0001)
    check(interpolated.yaw == -180f && interpolated.pitch == 10f)
    check(interpolatedLeapPose(before, after, 1.62f, 0f)?.x == 10.0)
    check(interpolatedLeapPose(before, after, 1.62f, 1f)?.x == 14.0)
    check(interpolatedLeapPose(before, after, 1.62f, 2f)?.x == 14.0)
    check(interpolatedLeapPose(before, after.copy(x = Double.NaN), 1.62f, 0.5f) == null)
    check(interpolatedLeapPose(before, after, Float.NaN, 0.5f) == null)
    check(interpolatedLeapPose(before, after, 0f, 0.5f) == null)
    check(interpolatedLeapPose(before, after, 1.62f, Float.NaN) == null)
    check(interpolatedLeapPose(before, after.copy(x = 50_000_000.0), 1.62f, 1f) == null)
    check(interpolatedLeapPose(before, after.copy(pitch = 180f), 1.62f, 1f)?.pitch == 89.9f)
    check(interpolatedLeapPose(before, after.copy(yaw = 20f), 1.62f, 1f)?.yaw == 20f)
    check(interpolatedLeapPose(before.copy(yaw = Float.MAX_VALUE, pitch = Float.MAX_VALUE),
        after.copy(yaw = -Float.MAX_VALUE, pitch = -Float.MAX_VALUE), 1.62f, 0.5f)?.yaw?.isFinite() == true)

    for ((width, height) in listOf(320 to 240, 427 to 240, 640 to 360, 854 to 480, 1920 to 1080)) {
        val menu = leapMenuBounds(width, height)
        check(menu.x > 0 && menu.y > 0 && menu.width < width * 3 / 4 && menu.height < height * 3 / 5)
        check(menu.x + menu.width < width && menu.y + menu.height < height)
        val views = (0..3).map { leapQuadrantBounds(width, height, it) }
        val faces = views.map(::leapFaceBounds)
        views.forEachIndexed { index, view ->
            val face = faces[index]
            check(view.width <= 300 && view.height <= 112 && view.width > view.height * 2)
            check(menu.contains(view.x.toDouble(), view.y.toDouble()))
            check(menu.contains((view.x + view.width - 1).toDouble(), (view.y + view.height - 1).toDouble()))
            check(leapHoveredQuadrant(width, height, (view.x + 1).toDouble(), (view.y + 1).toDouble()) == index)
            check(!face.contains((view.x + 1).toDouble(), (view.y + 1).toDouble()))
            check(leapHoveredQuadrant(width, height, (view.x + view.width - 1).toDouble(), (view.y + view.height - 1).toDouble()) == index)
            check(view.contains(face.x.toDouble(), face.y.toDouble()))
            check(view.contains((face.x + face.width - 1).toDouble(), (face.y + face.height - 1).toDouble()))
            check(face.width == face.height && face.width >= view.height / 2)
            check(faces.count { it.contains((face.x + 1).toDouble(), (face.y + 1).toDouble()) } == 1)
        }
        check(views[1].x - views[0].x - views[0].width >= width / 16)
        check(views[2].y - views[0].y - views[0].height >= height / 12)
        check(leapHoveredQuadrant(width, height, 0.0, 0.0) == null)
        check(leapHoveredQuadrant(width, height, (views[0].x + views[0].width).toDouble(), views[0].y.toDouble()) == null)
        check(leapHoveredQuadrant(width, height, (width / 2).toDouble(), (height / 2).toDouble()) == null)
    }
    check(leapSlotForClick(archer, listOf(tank, archer)) == 11)
    check(leapSlotForClick(archer, listOf(archer.copy(slot = 12))) == null)
    check(leapSlotForClick(archer, listOf(archer.copy(identity = healer.identity))) == null)
    check(leapSlotForClick(archer, listOf(archer.copy(identity = archer.identity.copy(uuid = UUID.randomUUID())))) == null)
    check(leapSlotForClick(archer, listOf(archer.copy(dead = true))) == null)
    check(leapSlotForClick(archer.copy(dead = true), listOf(archer)) == null)
    check(leapSlotForClick(archer, listOf(archer, archer)) == null)
    check(leapSlotForClick(archer, listOf(archer, archer.copy(slot = 14))) == null)
    check(leapSlotForClick(archer.copy(identity = LeapIdentity("Click to teleport!")), listOf(archer.copy(identity = LeapIdentity("Click to teleport!")))) == null)
    check(leapSlotForClick(archer, emptyList()) == null)
    println("Leap menu checks passed")
}
