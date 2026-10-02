package me.mycellium.skymyce.features.general.overlays

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GeneralConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.core.HorizontalAlignment
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.hudOpacity
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.ui.motionEnvelope
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.RightClickEvent
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object AbilityCooldownOverlay : SkyMyceModule() {
    private data class Cooldown(val started: Duration, val ability: Duration, val duration: Duration, var notification: String? = null)
    private val cooldowns = mutableMapOf<SkyBlockId, Cooldown>()
    private val notificationLines = ArrayList<String>()
    private val notificationOpacities = ArrayList<Double>()
    private var displayId: SkyBlockId? = null
    private var displaySeconds = Long.MIN_VALUE
    private var displayReady = false
    private var displayText = ""
    private val cooldownLabel by lazy { hudLabel() }
    private val notifications by lazy { hudColumn().apply { horizontalAlignment(HorizontalAlignment.CENTER) } }

    val widget = Widget("ability_cooldown_display", "Ability Cooldown Display", 480, 270, anchor = Anchor.CENTER) {
        if (!GeneralConfig.displayAbilityCooldown) return@Widget null
        val now = System.currentTimeMillis().milliseconds

        val id = PlayerUtils.activeItem.getSkyBlockId() ?: return@Widget null
        val cooldown = cooldowns[id] ?: return@Widget null

        val time = now - cooldown.started
        val duration = maxOf(cooldown.ability, cooldown.duration)
        val ready = time >= duration
        val seconds = (duration - time).inWholeSeconds
        if (displayId != id || displaySeconds != seconds || displayReady != ready) {
            displayId = id; displaySeconds = seconds; displayReady = ready
            displayText = if (ready) "§aAbility Ready!" else "§cCooldown: ${seconds.seconds}"
        }
        cooldownLabel.updateText(displayText)
        cooldownLabel
    }

    val notiWidget = Widget("ability_reset_display", "Ability Reset Display", 480, 270, 1.5f, anchor = Anchor.CENTER) {
        val editor = MC.screen is WidgetEditorScreen
        // Retained transient rows remain legible while arranging their existing editor preview.
        if (editor) notifications.children().forEach { (it as? LabelComponent)?.hudOpacity(1.0) }
        if (!GeneralConfig.displayAbilityNotification) return@Widget null
        val now = System.currentTimeMillis().milliseconds

        notificationLines.clear()
        notificationOpacities.clear()
        for ((id, cooldown) in cooldowns) {
            val time = now - cooldown.started
            val duration = maxOf(cooldown.ability, cooldown.duration)

            if ((time - duration) !in 0.seconds..5.seconds) continue

            notificationLines += cooldown.notification ?: "§a${id.toItem().hoverName.stripped}".also { cooldown.notification = it }
            notificationOpacities += if (editor) 1.0 else motionEnvelope((time - duration).inWholeMilliseconds.toDouble(),
                5000.0, ThemeConfig.animationMode)
        }

        if (notificationLines.isEmpty()) return@Widget null

        notifications.updateTextLines(notificationLines, notificationOpacities)
    }

    @Subscription
    fun onItemRightClick(event: RightClickEvent) {
        addCooldown(event.stack)
    }

    private fun addCooldown(item: ItemStack) {
        val id = item.getSkyBlockId() ?: return
        val now = System.currentTimeMillis().milliseconds

        val cooldown = when {
            item.getData(DataTypes.NECRON_SCROLLS)?.contains("WITHER_SHIELD_SCROLL") ?: false -> 5.seconds
            else -> item.getData(DataTypes.COOLDOWN_ABILITY)?.second
        } ?: return

        val duration = when (item.getData(DataTypes.ID)) {
            "RAGNAROCK_AXE" -> 10.seconds
            "WEIRD_TUBA" -> 20.seconds
            "WEIRDER_TUBA" -> 30.seconds
            "FIRE_VEIL_WAND" -> 5.seconds
            "WAND_OF_HEALING", "WAND_OF_RESTORATION", "WAND_OF_MENDING", "WAND_OF_ATONEMENT" -> 7.seconds
            else -> cooldown
        }

        cooldowns[id]?.let {
            if ((now - it.started) < cooldown) return
        }

        cooldowns[id] = Cooldown(now, cooldown, duration, cooldowns[id]?.notification)
    }
}
