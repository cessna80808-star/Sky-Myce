package me.mycellium.skymyce.utils

import java.text.NumberFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.PacketEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent

object Utils {
    fun displayModMessage(string: String) {
        displayModMessage(Component.literal(string))
    }

    fun displayModMessage(component: Component) {
        MC.player.sendSystemMessage(Component.literal("§dSkyMyce §r§5» ").append(component))
    }

    fun displayMessage(string: String) {
        displayMessage(Component.literal(string))
    }

    fun displayMessage(component: Component) {
        MC.player.sendSystemMessage(component)
    }

    fun displayDevMessage(string: String) {
        if (Config.devMode) MC.player.sendSystemMessage(Component.literal("§dSkyMyce §bDev §r§5» ").append(Component.literal(string)))
    }

    inline fun displayDevMessage(message: () -> String) {
        if (Config.devMode) displayDevMessage(message())
    }

    fun displayTitle(title: String, subtitle: String) {
        displayTitle(Component.literal(title), Component.literal(subtitle))
    }

    fun displayTitle(title: Component, subtitle: Component) {
        MC.instance.gui.setTitle(title)
        MC.instance.gui.setSubtitle(subtitle)
        MC.instance.gui.setTimes(10, 40, 10)
    }
}

object MC {
    val instance: Minecraft = Minecraft.getInstance()
    val connection get() = instance.connection
    val fps get() = instance.fps
    val font get() = instance.font
    var screen get() = instance.screen
        set(value) = instance.setScreen(value)
    val player get() = instance.player!!
}

object NumberUtils {
    private val formatter: NumberFormat = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT)
    init {
        formatter.maximumFractionDigits = 2
    }

    fun condense(num: Number): String = formatter.format(num)
}

object TextUtils {
    fun parseProfit(value: Number, margin: Number = 10000.0): String {
        val v = value.toDouble()
        val m = margin.toDouble()

        return "${if (abs(v) < m) "§7" else if (v > 0) "§a+" else "§c-"}${NumberUtils.condense(abs(v))}"
    }
}

object PlayerUtils {
    val activeItem: ItemStack get() = MC.player.activeItem

    fun sendMessage(message: String, channel: ChatChannel? = null) {
        when (channel) {
            ChatChannel.PUBLIC -> sendCommand("ac $message")
            ChatChannel.PRIVATE -> sendCommand("r $message")
            ChatChannel.PARTY -> sendCommand("pc $message")
            ChatChannel.GUILD -> sendCommand("gc $message")
            else -> MC.connection?.sendChat(message)
        }
    }

    fun sendModMessage(text: String, channel: ChatChannel? = null) {
        sendMessage("[SkyMyce] $text", channel)
    }

    fun sendCommand(command: String, sendFeedback: Boolean = false) {
        if (sendFeedback) displayModMessage("§7Sending command: §b$command")
        MC.connection?.sendCommand(command)
    }
}
/** `id.toString().replace("-", "")`, rebuilt only when the account UUID changes; one instance per client-thread owner. */
class CompactProfileId {
    private var source: UUID? = null
    private var text = ""
    fun of(id: UUID): String {
        if (id != source) { source = id; text = id.toString().replace("-", "") }
        return text
    }
}

object ServerUtils : SkyMyceModule() {
    var tps = 20f
        private set

    var currentPing = 0L
        private set

    var averagePing = 0L
        private set

    private var lastTimePacket = 0L
    private var pingStartTime = 0L
    private var isPinging = false
    private var tickCounter = 0

    @Subscription
    fun onTick(event: TickEvent) {
        if (isPinging && Util.getNanos() - pingStartTime > 10_000_000_000L) {
            isPinging = false
        }

        tickCounter ++
        if (tickCounter >= 80) {
            tickCounter = 0
            sendPingRequest()
        }
    }

    @Subscription
    fun onPacket(event: PacketEvent) {
        val packet = event.packet

        if (event.packet is ClientboundSetTimePacket) {
            val now = System.currentTimeMillis()
            if (lastTimePacket != 0L) {
                val diff = now - lastTimePacket
                tps = (20_000f / diff).coerceIn(0f, 20f)
            }
            lastTimePacket = now
        }
        else if (packet is ClientboundPongResponsePacket) {
            currentPing = (Util.getMillis() - packet.time).coerceAtLeast(0)
            isPinging = false

            val pingLog = MC.instance.debugOverlay.pingLogger
            val sampleSize = min(pingLog.size(), 10)

            if (sampleSize > 0) {
                var total = 0L
                for (i in 0 until sampleSize) total += pingLog.get(i)
                averagePing = total / sampleSize
            }
            else averagePing = currentPing
        }
    }

    private fun sendPingRequest() {
        if (isPinging) return
        val connection = MC.connection ?: return

        isPinging = true
        pingStartTime = Util.getNanos()
        connection.send(ServerboundPingRequestPacket(Util.getMillis()))
    }
}
