package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.features.social.CosmeticsClient
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI

class FriendsSocialScreen : MenuScreen(Component.literal("Friends"), MenuPage.FRIENDS) {
    override val menuCategory get() = MenuCategory.UTILS
    internal override val canonicalFeatureId get() = "player-utilities"
    private var search = ""
    private var previous: List<Any?> = emptyList()
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var refreshButton: ButtonComponent
    private lateinit var scanStatus: LabelComponent
    private val panelWidth get() = menuWidth


    override fun buildContent(root: FlowLayout) {
        DungeonFriendsSettings.load()
        root.surface(Surface.BLANK)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(UIContainers.verticalFlow(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)).apply {
            padding(Insets.of(12)); gap(8)
            surface(MenuTheme.panel())
            child(row().apply {
                child(label("§lFriends", CYAN).horizontalSizing(Sizing.expand()))
                refreshButton = button("Refresh", 72) { updateRows() }
                child(refreshButton)
                child(button("Close", 44) { onClose() })
            })
            child(row().apply {
                child(label("Username", MUTED))
                val field = MenuTextBox(Sizing.expand()).apply {
                    setMaxLength(16); text(search); setHint(Component.literal("Search usernames..."))
                    onChanged().subscribe { search = it; updateRows(animate = false) }
                }
                child(field)
                child(button("Clear", 40) { field.text("") })
            })
            scanStatus = label(DungeonFriends.scanner.status, MUTED).apply { horizontalSizing(Sizing.fill()) }
            child(scanStatus)
            results = menuScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
                scrollbarThiccness(2); padding(Insets.right(4))
            }
            child(results)
        })
        updateRows()
    }

    override fun tick() {
        super.tick()
        if (menuClosing) return
        refreshButton.tooltip(Component.literal("Redraw the saved friends. This does not request the friends list."))
        val status = if (!LocationAPI.onHypixel) "Join Hypixel to refresh friends"
            else if (!DungeonFriends.scanner.hasScannedAll) "Friend cache incomplete • open Party Finder to finish loading it"
            else "${DungeonFriends.scanner.all.size} cached friends • online status from Party Finder"
        if (scanStatus.text().string != status) scanStatus.text(Component.literal(status))
        val state = listOf(DungeonFriends.scanner, DungeonFriends.scanner.version, DungeonFriendsSettings.error, LocationAPI.onHypixel)
        if (state != previous) { previous = state; updateRows(animate = false) }
    }

    private fun updateRows(animate: Boolean = true) {
        if (!::results.isInitialized) return
        if (animate) contentChanged()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            friendRows(this)
        })
    }

    private fun friendRows(list: FlowLayout) {
        if (!LocationAPI.onHypixel) { list.child(label("Join Hypixel to view this account's friends.", MUTED)); return }
        val friends = DungeonFriends.scanner.all.values.filter { matchesFriendName(it.name, search) }
            .sortedWith(compareBy<OnlineDungeonFriend> { it.activity == FriendActivity.OFFLINE }
                .thenByDescending { it.bestFriend }.thenBy { it.name.lowercase() })
        friends.take(128).forEach { CosmeticsClient.observe(it.name, FriendsAPI.getFriend(it.name)?.uuid) }
        list.child(label("FRIENDS  ${friends.size}/${DungeonFriends.scanner.all.size} • ${DungeonFriends.scanner.online.size} online", CYAN))
        if (friends.isEmpty()) list.child(label("No matching friends.", MUTED))
        friends.forEach { friend ->
            list.child(card().apply {
                surface(surface().and(Surface { graphics, component ->
                    graphics.fill(component.x(), component.y(), component.x() + 2, component.y() + component.height(),
                        MenuTheme.alpha(friend.activity.menuColor))
                }))
                child(row().apply {
                    child(label("${if (friend.bestFriend) "§l" else ""}${friend.name}").horizontalSizing(Sizing.expand()))
                    child(label(friend.activity.label, friend.activity.menuColor))
                })
                child(label(friend.location, MUTED).horizontalSizing(Sizing.fill()))
            })
        }
    }

    private fun label(text: String, color: Int = WHITE) = MenuLabel(Component.literal(text)).color(Color.ofRgb(color)).shadow(MenuTheme.SHADOW)
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); verticalAlignment(VerticalAlignment.CENTER) }
    private fun card() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(6); padding(Insets.of(6)); surface(MenuTheme.panel(true))
    }
    private fun button(text: String, width: Int, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply {
        sizing(Sizing.fixed(width), Sizing.fixed(18)); textShadow(false)
        themed()
    }
    companion object {
        private val CYAN get() = MenuTheme.ACCENT
        private val WHITE get() = MenuTheme.TEXT
        private val MUTED get() = MenuTheme.MUTED
    }
}
