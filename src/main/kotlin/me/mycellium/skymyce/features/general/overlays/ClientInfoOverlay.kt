package me.mycellium.skymyce.features.general.overlays

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GeneralConfig
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ServerUtils
import kotlin.math.round

object ClientInfoOverlay : SkyMyceModule() {
    private val content by lazy { hudPanel() }
    private val readings = LongArray(7) { Long.MIN_VALUE }
    private var lines = emptyList<String>()

    val widget = Widget("client_info", "Client Info", 10, 10, 1f) {
        if (!GeneralConfig.clientInfo) return@Widget null

        val fps = MC.fps.toLong()
        val ping = ServerUtils.currentPing
        val average = ServerUtils.averagePing
        val tps = round(ServerUtils.tps * 100) / 100
        val tpsBits = tps.toBits().toLong()
        val player = MC.player
        val x = player.blockX.toLong(); val y = player.blockY.toLong(); val z = player.blockZ.toLong()
        if (readings[0] != fps || readings[1] != ping || readings[2] != average || readings[3] != tpsBits ||
            readings[4] != x || readings[5] != y || readings[6] != z) {
            readings[0] = fps; readings[1] = ping; readings[2] = average; readings[3] = tpsBits
            readings[4] = x; readings[5] = y; readings[6] = z
            lines = listOf("§9FPS: §f$fps", "§9Ping: §f${ping}ms §7(${average}ms avg)",
                "§9TPS: §f$tps", "§9XYZ: §f$x $y $z")
        }
        content.updateTextLines(lines)
    }
}
