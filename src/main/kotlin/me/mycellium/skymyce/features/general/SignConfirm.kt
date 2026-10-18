package me.mycellium.skymyce.features.general

import me.mycellium.mixin.BazaarAssistSignAccessor
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.SignsConfig
import me.mycellium.skymyce.config.misc.SignsConfig.EnterConfirms
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent

private val promptMarker = Regex("""^\^{3,}$""")
private val marketWords = Regex("""(?i)\b(price|bid|bids|amount|auction|bazaar|order|offer|quantity|query|search|coins|deposit|withdraw|per unit|starting)\b""")
private val marketMenus = Regex("""(?i)(auction|bazaar|\bbin\b|\bbid|bank|how many|how much|manage orders|create .*order)""")

/**
 * Whether Enter should confirm a sign. A Hypixel input sign has the player's line first and a "^^^^" marker under it;
 * market signs also name a price, bid, amount or search, or open from an Auction House, Bazaar or bank menu seen in
 * the last minute.
 */
internal fun signConfirms(mode: EnterConfirms, lines: List<String>, recentMenu: String?): Boolean {
    if (mode == EnterConfirms.OFF) return false
    if (mode == EnterConfirms.ALL) return true
    val prompt = lines.getOrNull(1)?.trim()?.let(promptMarker::matches) == true
    if (!prompt) return false
    if (mode == EnterConfirms.PROMPTS) return true
    return lines.drop(1).any { marketWords.containsMatchIn(it) } || recentMenu?.let(marketMenus::containsMatchIn) == true
}

/** Enter confirms Auction House, Bazaar and other prompt signs, by the player's own key press. */
object SignConfirm : SkyMyceModule() {
    private var lastMenu: String? = null
    private var lastMenuAt = 0L

    @Subscription
    fun onContainer(event: ContainerInitializedEvent) { lastMenu = event.title; lastMenuAt = System.currentTimeMillis() }

    /** Called from the sign screen's key handler; true when Enter was used to finish the sign. */
    fun onKey(screen: AbstractSignEditScreen, key: Int): Boolean {
        if (!isEnabled() || (key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER)) return false
        val lines = (screen as BazaarAssistSignAccessor).`skymyce$getMessages`()?.map { it.orEmpty() }.orEmpty()
        val menu = lastMenu?.takeIf { System.currentTimeMillis() - lastMenuAt < 60_000 }
        if (!signConfirms(SignsConfig.enterConfirms, lines, menu)) return false
        screen.onClose()
        return true
    }
}
