package me.mycellium.skymyce.features.instances.dungeons.leap

import com.mojang.blaze3d.platform.InputConstants
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.LeapMenuConfig as Settings
import me.mycellium.skymyce.features.instances.dungeons.camera.render.SecondaryWorldCamera
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.RightClickEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.helpers.McClient
import java.util.Locale
import java.util.UUID

/** Keeps the server's inventory open; a card sends exactly one ordinary, revalidated slot click. */
object LeapMenu : SkyMyceModule() {
    private val feeds by lazy { Array(4) { SecondaryWorldCamera.createFeed() } }
    private var state: State? = null
    private val intent = LeapIntent()
    private var intentLevel: ClientLevel? = null
    private var intentPlayer: Player? = null
    private val earlyKeys = mutableSetOf<Int>()
    private var initializedScreen: AbstractContainerScreen<*>? = null
    private var initializedLevel: ClientLevel? = null

    private class State(val screen: AbstractContainerScreen<*>, val level: ClientLevel) {
        val quadrants = LeapQuadrants()
        var candidates: List<LeapCandidate?> = List(4) { null }
        val images = arrayOfNulls<String>(4)
        val keys = LeapKeys()
        var contentsReady = false
        val buttons = Array(4) { quadrant ->
            Button.builder(Component.empty()) { select(this, quadrant) }.bounds(0, 0, 1, 1).build().apply { visible = false }
        }
    }

    override fun init() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*> || !leapTitle(screen)) { clearIntent(); clearContents(); return@register }
            current(screen)
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                val active = current(screen) ?: return@register true
                update(active)
                val quadrant = leapHoveredQuadrant(screen.width, screen.height, click.x, click.y)
                    ?.takeIf { active.buttons[it].visible }
                if (quadrant != null && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    screen.focused = active.buttons[quadrant]
                    select(active, quadrant)
                }
                false
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { _, _ -> current(screen) == null }
            ScreenMouseEvents.allowMouseDrag(screen).register { _, _, _, _ -> current(screen) == null }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, _, _, _, _ -> current(screen) == null }
            ScreenKeyboardEvents.allowKeyPress(screen).register { _, key ->
                val active = current(screen) ?: return@register true
                update(active)
                if (leapKeyQuadrant(key.key()) == null && MC.instance.options.keyInventory.matches(key)) { clearIntent(); return@register true }
                val quadrant = active.keys.press(key.key(), active.buttons.indexOfFirst { it.isFocused })
                if (quadrant != null) {
                    if ((!active.contentsReady || active.candidates.getOrNull(quadrant) == null) && leapKeyQuadrant(key.key()) != null) {
                        val now = System.nanoTime()
                        if (!validIntent(now)) armIntent(now)
                        intent.confirm(now)
                        intent.queue(quadrant, now)
                    } else select(active, quadrant)
                }
                if (leapKeyQuadrant(key.key()) != null) return@register false
                when (key.key()) {
                    GLFW.GLFW_KEY_ESCAPE -> true
                    GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> true
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> false
                    else -> false
                }
            }
            ScreenKeyboardEvents.allowKeyRelease(screen).register { _, key ->
                state?.takeIf { it.screen === screen }?.keys?.release(key.key())
                true
            }
            ScreenEvents.remove(screen).register {
                if (state?.screen === screen) dispose()
                else { clearIntent(); if (initializedScreen === screen) clearContents() }
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!client.isWindowActive) earlyKeys.clear()
            validIntent(System.nanoTime())
            state?.let {
                if (client.screen !== it.screen || !available(it.screen) || client.level !== it.level) dispose()
                else { update(it); selectQueued(it) }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { earlyKeys.clear(); dispose() } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { dispose() }
    }

    override fun onDisable() { earlyKeys.clear(); dispose() }

    /** Raw input hooks run before vanilla handles hotbar changes and item use in its next tick. */
    @JvmStatic
    fun manualInput(key: InputConstants.Key) {
        val client = MC.instance
        if (client.screen != null) return
        val options = client.options
        if (key == KeyMappingHelper.getBoundKeyOf(options.keyUse)) {
            val id = client.player?.mainHandItem?.getData(DataTypes.ID)
            if (contextAvailable() && client.isWindowActive && client.overlay == null &&
                id in setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")) armIntent(System.nanoTime())
            else clearIntent()
        } else if (listOf(options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint).none { KeyMappingHelper.getBoundKeyOf(it) == key }) clearIntent()
    }

    @JvmStatic
    fun earlyKey(key: Int, action: Int): Boolean {
        if (action == GLFW.GLFW_RELEASE) {
            earlyKeys.remove(key)
            state?.keys?.release(key)
            return false
        }
        if (MC.instance.screen != null || !validIntent(System.nanoTime())) return false
        val quadrant = leapKeyQuadrant(key) ?: return false
        if (action == GLFW.GLFW_PRESS) {
            earlyKeys.add(key)
            intent.queue(quadrant, System.nanoTime())
        }
        return action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT
    }

    @JvmStatic
    fun cancelEarlyChoice() { clearIntent() }

    @Subscription(priority = Subscription.LOWEST, receiveCancelled = true)
    fun onItemUse(event: RightClickEvent) {
        if (event.isCancelled || event.stack.getData(DataTypes.ID) !in setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")) clearIntent()
        else if (validIntent(System.nanoTime())) intent.confirm(System.nanoTime())
    }

    @Subscription
    fun onMenuContents(event: ContainerInitializedEvent) {
        val client = MC.instance
        if (client.screen !== event.screen || !leapTitle(event.screen) || client.level == null) return
        initializedScreen = event.screen
        initializedLevel = client.level
        if (!available(event.screen)) return
        val active = current(event.screen) ?: return
        active.contentsReady = true
        update(active)
        selectQueued(active)
    }

    private fun armIntent(now: Long) {
        intent.arm(now)
        intentLevel = MC.instance.level
        intentPlayer = MC.instance.player
    }

    private fun clearIntent() { intent.clear(); intentLevel = null; intentPlayer = null }

    private fun clearContents() { initializedScreen = null; initializedLevel = null }

    private fun validIntent(now: Long): Boolean {
        val client = MC.instance
        val screen = client.screen
        if (!intent.active(now) || !contextAvailable() || !client.isWindowActive || client.overlay != null ||
            intentLevel !== client.level || intentPlayer !== client.player ||
            (screen != null && (screen !is AbstractContainerScreen<*> || !available(screen)))) {
            clearIntent()
            return false
        }
        return true
    }

    private fun selectQueued(active: State) {
        val now = System.nanoTime()
        if (!validIntent(now) || !active.contentsReady) return
        intent.take(now) { active.candidates.getOrNull(it)?.dead == false }?.let { select(active, it) }
    }

    private fun leapTitle(screen: AbstractContainerScreen<*>) = isLeapMenuTitle(screen.title.string)

    private fun available(screen: AbstractContainerScreen<*>): Boolean {
        val client = MC.instance
        return contextAvailable() && client.screen === screen && leapTitle(screen) &&
            client.player?.containerMenu === screen.menu && screen.menu.carried.isEmpty
    }

    private fun contextAvailable(): Boolean {
        val client = MC.instance
        val address = client.currentServer?.ip?.substringBefore(':')?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return isEnabled() && Settings.enabled && client.level != null && client.player?.isAlive == true &&
            (address == "hypixel.net" || address.endsWith(".hypixel.net")) &&
            LocationAPI.isOnSkyBlock && LocationAPI.island == SkyBlockIsland.THE_CATACOMBS
    }

    private fun current(screen: AbstractContainerScreen<*>): State? {
        if (!available(screen)) { if (state?.screen === screen) dispose(); return null }
        if (state?.screen !== screen || state?.level !== MC.instance.level) {
            val contentsReady = initializedScreen === screen && initializedLevel === MC.instance.level
            dispose(clearPending = state != null)
            state = State(screen, MC.instance.level ?: return null).also { active ->
                active.contentsReady = contentsReady
                earlyKeys.forEach { active.keys.press(it, -1) }
            }
        }
        return state?.also { active ->
            val widgets = Screens.getWidgets(screen)
            active.buttons.forEach { if (it !in widgets) widgets.add(it) }
        }
    }

    private fun entries(screen: AbstractContainerScreen<*>): List<LeapCandidate> {
        val client = MC.instance
        val self = normalizedLeapName(client.player?.gameProfile?.name.orEmpty())
        val roster = DungeonAPI.teammates.mapNotNull { member -> normalizedLeapName(member.name)?.let { it to member } }.toMap()
        val scoreboard = McClient.scoreboard.map { it.string }
        return screen.menu.slots.mapNotNull { slot ->
            if (slot.container === client.player?.inventory || !slot.isActive || slot.item.item != Items.PLAYER_HEAD) return@mapNotNull null
            val name = leapHeadName(slot.item.hoverName.string) ?: return@mapNotNull null
            if (name == self) return@mapNotNull null
            val member = roster[name]
            val info = client.connection?.getPlayerInfoIgnoreCase(name)
            val profile = slot.item.get(DataComponents.PROFILE)?.partialProfile()
            if (member == null && info == null && normalizedLeapName(profile?.name.orEmpty()) != name) return@mapNotNull null
            val uuid = info?.profile?.id ?: profile?.id?.takeIf { normalizedLeapName(profile.name.orEmpty()) == name }
            val clazz = leapScoreboardClass(name, scoreboard) ?: member?.dungeonClass?.displayName ?: "Class unavailable"
            LeapCandidate(LeapIdentity(member?.name ?: info?.profile?.name ?: profile?.name ?: name, uuid), slot.index, clazz,
                member?.dead == true || leapHeadIsDead(slot.item.hoverName.string))
        }
    }

    private fun update(active: State) {
        if (!active.contentsReady) return
        active.candidates = active.quadrants.update(entries(active.screen))
        active.buttons.forEachIndexed { index, button ->
            val candidate = active.candidates[index]
            val bounds = leapQuadrantBounds(active.screen.width, active.screen.height, index)
            button.x = bounds.x; button.y = bounds.y; button.width = bounds.width; button.height = bounds.height
            button.visible = candidate != null; button.active = candidate != null && !candidate.dead
            if (candidate != null) button.message = Component.literal("${index + 1}: Leap to ${candidate.identity.name}, ${candidate.className}${if (candidate.dead) ", dead" else ""}")
        }
    }

    private fun select(active: State, quadrant: Int) {
        if (state !== active || !active.contentsReady || !available(active.screen)) return
        val displayed = active.candidates.getOrNull(quadrant) ?: return
        val slot = leapSlotForClick(displayed, entries(active.screen)) ?: return
        val client = MC.instance
        clearIntent()
        client.gameMode?.handleContainerInput(active.screen.menu.containerId, slot, GLFW.GLFW_MOUSE_BUTTON_LEFT,
            ContainerInput.PICKUP, client.player ?: return)
    }

    @JvmStatic
    fun active(screen: Screen): Boolean = screen is AbstractContainerScreen<*> && current(screen) != null

    /** Only the actual, populated server menu may authorize receiving a teammate's shared pose. */
    fun sharedTargets(): Map<UUID, String> {
        if (!Settings.povEnabled) return emptyMap()
        val screen = MC.instance.screen as? AbstractContainerScreen<*> ?: return emptyMap()
        val active = current(screen)?.takeIf { it.contentsReady } ?: return emptyMap()
        val connection = MC.instance.connection ?: return emptyMap()
        update(active)
        return active.candidates.filterNotNull().filterNot { it.dead }.mapNotNull { candidate ->
            val uuid = candidate.identity.uuid ?: return@mapNotNull null
            val profile = connection.getPlayerInfo(uuid)?.profile ?: return@mapNotNull null
            if (normalizedLeapName(profile.name) != candidate.identity.key) return@mapNotNull null
            uuid to profile.name
        }.toMap()
    }

    @JvmStatic
    fun extract(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float): Boolean {
        if (screen !is AbstractContainerScreen<*>) return false
        val active = current(screen) ?: return false
        update(active)
        val players = active.level.players()
        val identities = players.map { LeapIdentity(it.gameProfile.name, it.uuid) }
        repeat(4) { quadrant ->
            val bounds = leapQuadrantBounds(screen.width, screen.height, quadrant)
            val candidate = active.candidates[quadrant]
            val player = candidate?.takeUnless { it.dead }?.let { matchingLeapPlayer(it.identity, identities) }?.let { players[it] }
            val livePlayer = player?.takeIf { it.isAlive && !it.isRemoved }
            val shared = if (Settings.povEnabled && livePlayer == null) candidate?.takeUnless { it.dead }?.let { LeapPovSharing.pose(it.identity) } else null
            val view = if (Settings.povEnabled) livePlayer?.let { viewpoint(it, partialTicks) } ?: shared?.let(::viewpoint) else null
            // The player, not whether we see them live or by shared pose: switching between the two keeps the feed.
            val image = if (view != null && candidate != null) "${candidate.identity.key}|${candidate.identity.uuid}" else null
            if (image != active.images[quadrant] || image == null) {
                if (active.images[quadrant] != null) feeds[quadrant].release()
                active.images[quadrant] = image
            }
            if (image != null && view != null) feeds[quadrant].extract(graphics, view, bounds.x, bounds.y, bounds.width, bounds.height, 1f, livePlayer?.uuid ?: candidate?.identity?.uuid)
            if (candidate != null) {
                val caption = leapPovCaption(Settings.povEnabled, candidate.dead,
                    livePlayer != null || shared != null,
                    if (active.images[quadrant] != null) feeds[quadrant].status() else SecondaryWorldCamera.Status.UNAVAILABLE, shared != null)
                drawCard(active, quadrant, graphics, mouseX, mouseY, caption)
            }
        }
        if (active.candidates.all { it == null }) {
            graphics.centeredText(MC.font, "Waiting for leap targets…", screen.width / 2, screen.height / 2 - 4, 0xFFABB3C2.toInt())
        }
        return true
    }

    private fun viewpoint(player: Player, partialTicks: Float): SecondaryWorldCamera.View? {
        val pose = interpolatedLeapPose(LeapCameraPose(player.xo, player.yo, player.zo, player.yHeadRotO, player.xRotO),
            LeapCameraPose(player.x, player.y, player.z, player.yHeadRot, player.xRot), player.eyeHeight, partialTicks) ?: return null
        return viewpoint(pose)
    }

    private fun viewpoint(pose: LeapCameraPose) = SecondaryWorldCamera.View(pose.x, pose.y, pose.z, pose.yaw, pose.pitch, 70f, 4,
        leapPreviewFps(Settings.previewFramesPerSecond, me.mycellium.skymyce.ui.AutoGraphics.quality))

    private fun drawCard(active: State, quadrant: Int, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, caption: String?) {
        val candidate = active.candidates[quadrant] ?: return
        val button = active.buttons[quadrant]
        val bounds = leapQuadrantBounds(active.screen.width, active.screen.height, quadrant)
        val hovered = bounds.contains(mouseX.toDouble(), mouseY.toDouble()) || button.isFocused
        val client = MC.instance
        val profile = active.screen.menu.slots[candidate.slot].item.get(DataComponents.PROFILE)
        val skin = client.connection?.getPlayerInfoIgnoreCase(candidate.identity.key ?: return)?.skin
            ?: profile?.let { client.playerSkinRenderCache().getOrDefault(it).playerSkin() }
            ?: DefaultPlayerSkin.get(candidate.identity.uuid ?: UUID(0, 0))
        drawLeapCard(graphics, bounds, candidate, skin, hovered, quadrant, caption)
        // Goldor coordination's recommended target gets a marker; choosing stays with the player.
        val advice = me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorCoordinator.advice?.self?.leap
        if (advice?.targetName?.equals(candidate.identity.key, ignoreCase = true) == true) {
            graphics.outline(bounds.x, bounds.y, bounds.width, bounds.height, if (advice.plan?.phase == me.mycellium.skymyce.features.instances.dungeons.goldor.LeapPhase.LEAP) 0xFFFF5555.toInt() else 0xFFFFD25A.toInt())
            graphics.text(MC.font, "★ ${advice.reason}", bounds.x + 4, bounds.y + bounds.height - 11, 0xFFFFD25A.toInt(), true)
        }
    }

    private fun dispose(clearPending: Boolean = true) {
        if (clearPending) { clearIntent(); clearContents() }
        val previous = state ?: return
        state = null
        // Released, not stopped: the next leap reuses the built pipelines (closed after 20 s unused or on a world change).
        previous.images.indices.forEach { if (previous.images[it] != null) feeds[it].release() }
        Screens.getWidgets(previous.screen).removeAll(previous.buttons.toSet())
    }
}

/**
 * Leap previews are decoration beside the player's choice, so lighter graphics tiers cap their frame rate:
 * Minimal at 10, Light at 15; Balanced and Heavy use the configured rate.
 */
internal fun leapPreviewFps(configured: Int, quality: me.mycellium.skymyce.ui.GuiQuality): Int = when (quality) {
    me.mycellium.skymyce.ui.GuiQuality.EXTREMELY_LIGHT -> minOf(configured, 10)
    me.mycellium.skymyce.ui.GuiQuality.LIGHT -> minOf(configured, 15)
    else -> configured
}

internal fun leapPovCaption(enabled: Boolean, dead: Boolean, tracked: Boolean, status: SecondaryWorldCamera.Status, shared: Boolean = false): String? = when {
    !enabled || dead -> null
    !tracked -> "POV out of range"
    status == SecondaryWorldCamera.Status.WAITING_CHUNKS -> "World not loaded"
    status == SecondaryWorldCamera.Status.PREPARING -> "Loading POV…"
    status == SecondaryWorldCamera.Status.LIVE || status == SecondaryWorldCamera.Status.TERRAIN_ONLY -> if (shared) "Shared POV" else null
    else -> "POV unavailable"
}

internal fun drawLeapCard(graphics: GuiGraphicsExtractor, bounds: LeapRect, candidate: LeapCandidate, skin: PlayerSkin, highlighted: Boolean, quadrant: Int, caption: String? = null) {
    val color = when (candidate.className) {
        "Archer" -> 0xFFFF5555.toInt()
        "Berserk" -> 0xFFFFAA00.toInt()
        "Healer" -> 0xFFFF55FF.toInt()
        "Mage" -> 0xFF55FFFF.toInt()
        "Tank" -> 0xFF55FF55.toInt()
        else -> 0xFFB4C9E0.toInt()
    }
    graphics.fill(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height,
        if (candidate.dead) 0x90502323.toInt() else if (highlighted) 0x90505050.toInt() else 0x78212121.toInt())
    val face = leapFaceBounds(bounds)
    PlayerFaceExtractor.extractRenderState(graphics, skin, face.x, face.y, face.width)
    graphics.outline(face.x - 1, face.y - 1, face.width + 2, face.height + 2, color)
    val textX = face.x + face.width + (bounds.height / 16).coerceAtLeast(4)
    val status = if (candidate.dead) "DEAD" else candidate.className
    val textWidth = maxOf(MC.font.width(candidate.identity.name), MC.font.width(status),
        caption?.let { kotlin.math.ceil(MC.font.width(it) * .8).toInt() } ?: 0).coerceAtLeast(1)
    val scale = minOf(1.5f, bounds.height / 48f, (bounds.x + bounds.width - textX - 6).coerceAtLeast(1).toFloat() / textWidth)
    graphics.enableScissor(textX, bounds.y + 2, bounds.x + bounds.width - 3, bounds.y + bounds.height - 2)
    graphics.pose().pushMatrix()
    try {
        graphics.pose().translate(textX.toFloat(), bounds.y + (bounds.height - (if (caption == null) 22 else 34) * scale) / 2)
        graphics.pose().scale(scale)
        graphics.text(MC.font, candidate.identity.name, 0, 0, color, true)
        graphics.text(MC.font, status, 0, 11, color, true)
        if (caption != null) {
            graphics.pose().scale(.8f)
            graphics.text(MC.font, caption, 0, 30, 0xFFABB3C2.toInt(), true)
        }
    } finally {
        graphics.pose().popMatrix()
        graphics.disableScissor()
    }
    val badgeX = bounds.x + bounds.width - 13
    graphics.fill(badgeX - 2, bounds.y + 2, badgeX + 9, bounds.y + 13, 0x70101010)
    graphics.text(MC.font, "${quadrant + 1}", badgeX, bounds.y + 3, color, false)
}
