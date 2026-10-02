package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.inventory.Slot
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.SlotClickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId.Companion.getSkyBlockId
import tech.thatgravyboat.skyblockapi.utils.text.Text.send
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
import java.util.UUID

object GearLending : SkyMyceModule() {
    private var account: UUID? = null
    private var current: GearLedger? = null
    private val capture = TradeCapture()
    private var screenId = -1
    private var observedScreen: AbstractContainerScreen<*>? = null
    private var tradeId = UUID.randomUUID().toString()

    val ledger: GearLedger? get() {
        val uuid = MC.instance.player?.uuid ?: return null
        if (account != uuid) {
            capture.clear()
            account = uuid
            current = GearLedger(SkyMyce.configPath.resolve("gear_lending_$uuid.json"))
        }
        return current
    }

    override fun init() { ClientTickEvents.END_CLIENT_TICK.register {
        if (isEnabled()) RuntimeProfiler.measure(RuntimeProfiler.Area.TRADE_CAPTURE) { tickTrade() }
    } }

    private fun tickTrade() {
        if (!LocationAPI.isOnSkyBlock) { reset(); return }
        val screen = MC.instance.screen as? AbstractContainerScreen<*> ?: run { observedScreen = null; screenId = -1; return }
        if (screen === observedScreen && screen.menu.containerId == screenId) {
            // A long unchanged trade remains eligible for the same five-second server
            // completion window, without decoding all 32 item slots every tick.
            capture.keepAlive(System.currentTimeMillis())
        } else sampleTrade()
    }

    @Subscription(ContainerInitializedEvent::class, SlotClickEvent::class)
    fun sampleBeforeClick() { sampleTrade() }

    @Subscription
    fun onInventory(event: InventoryChangeEvent) {
        // SBAPI posts at Slot.set HEAD: the incoming item is authoritative while
        // the native Slot still contains its previous item during this callback.
        if (!event.isInPlayerInventory && event.screen === observedScreen && capture.active) sampleTrade(event.slot, event.item)
    }

    private fun sampleTrade(changedSlot: Slot? = null, changedItem: ItemStack? = null) {
        if (!LocationAPI.isOnSkyBlock) { reset(); return }
        val screen = MC.instance.screen as? AbstractContainerScreen<*> ?: run { observedScreen = null; screenId = -1; return }
        val newScreen = screen !== observedScreen || screenId != screen.menu.containerId
        observedScreen = screen
        val friend = tradePartner(screen.title.stripped) ?: run { capture.clear(); screenId = screen.menu.containerId; return }
        // The chest title can truncate names to ten characters. Confirm friendship using the
        // full name in the server's completion message before storing anything locally.
        ledger ?: return
        if (newScreen) {
            screenId = screen.menu.containerId
            tradeId = UUID.randomUUID().toString()
        }
        val slots = screen.menu.slots.takeWhile { it.container !is Inventory }
        if (slots.size < 36) return
        fun current(slot: Slot): ItemStack = if (slot === changedSlot && changedItem != null) changedItem else slot.item
        val sent = (0..3).flatMap { row -> (0..3).map { col -> current(slots[row * 9 + col]) } }
        val received = (0..3).flatMap { row -> (5..8).map { col -> current(slots[row * 9 + col]) } }
        capture.observe(GearTrade(tradeId, System.currentTimeMillis(), friend, null,
            ProfileAPI.profileName, sent.mapNotNull(::item), received.mapNotNull(::item),
            sent.firstNotNullOfOrNull(::coins), received.firstNotNullOfOrNull(::coins)), System.currentTimeMillis())
    }

    private fun coins(stack: ItemStack): String? {
        val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.stripped }.orEmpty()
        return if ("Lump-sum amount" in lore) lore.lastOrNull()?.trim()?.take(80) else null
    }

    private fun item(stack: ItemStack): TradedItem? {
        if (stack.isEmpty || coins(stack) != null) return null
        val id = stack.getSkyBlockId()?.id ?: return null
        return TradedItem(id, stack.hoverName.stripped.take(256), stack.count, stack.getData(DataTypes.UUID)?.toString())
    }

    @Subscription(priority = Subscription.HIGHEST, receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock) return
        val friend = completedTradePartner(event.text) ?: return
        val trade = capture.complete(friend, System.currentTimeMillis()) ?: return
        if (!FriendsAPI.isFriend(friend) && friend.lowercase() !in DungeonFriends.scanner.all) return
        val store = ledger ?: return
        if (!store.record(trade.copy(friendUuid = FriendsAPI.getFriend(friend)?.uuid?.toString()))) {
            Component.literal("§c[SkyMyce] ${store.error}").send()
            return
        }
        if (trade.sent.isNotEmpty()) Component.literal("§b[SkyMyce] Trade with $friend recorded. ").append(
            Component.literal("§a[Mark items as lent]").withStyle {
                it.withClickEvent(ClickEvent.RunCommand("/skymyce loans"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Open your lending log and select the items lent to $friend")))
            }
        ).send()
    }

    @Subscription(ServerDisconnectEvent::class, ProfileChangeEvent::class)
    fun reset() { capture.clear(); observedScreen = null; screenId = -1 }
    override fun onDisable() { reset() }
    fun diagnostics(): Map<String, Long> = mapOf("trade.capture" to if (capture.active) 1L else 0L,
        "trade.retained_screen" to if (observedScreen != null) 1L else 0L, "trade.records" to (current?.trades?.size ?: 0).toLong())
}
