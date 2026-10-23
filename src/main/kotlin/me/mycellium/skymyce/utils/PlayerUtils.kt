package me.mycellium.skymyce.utils

import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.minecraft.world.item.ItemStack
import java.util.*

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
