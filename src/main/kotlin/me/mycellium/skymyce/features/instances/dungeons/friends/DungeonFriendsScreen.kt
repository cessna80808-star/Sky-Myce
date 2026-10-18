package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.features.social.CosmeticsClient
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI

internal fun dungeonFriendActions(width: Int, controls: List<UIComponent>): FlowLayout =
    UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(4).apply {
        val columns = when { width >= 190 -> 4; width >= 110 -> 2; else -> 1 }
        controls.chunked(columns).forEach { group ->
            child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).gap(4).apply { children(group) })
        }
    }

internal fun dungeonFriendStatWidth(title: String, minimum: Int = 40) =
    maxOf(minimum, kotlin.math.ceil(MenuCanvas.textWidth(title)).toInt() + 12)

internal val FriendActivity.menuColor get() = when (this) {
    FriendActivity.OFFLINE, FriendActivity.UNKNOWN -> MenuTheme.MUTED
    FriendActivity.IN_RUN, FriendActivity.LIMBO -> MenuTheme.RED
    FriendActivity.IDLE -> MenuTheme.GREEN
    FriendActivity.SKYBLOCK -> MenuTheme.YELLOW
    FriendActivity.OTHER_GAME -> MenuTheme.ACCENT
}

internal fun dungeonFriendClassGrid(width: Int, cells: List<UIComponent>): FlowLayout =
    UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(3).apply {
        val columns = (width / 60).coerceIn(1, 5)
        cells.forEach { it.horizontalSizing(Sizing.fixed(width.coerceAtLeast(1) / columns)) }
        cells.chunked(columns).forEach { group ->
            child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { children(group) })
        }
    }

class DungeonFriendsScreen : MenuScreen(Component.literal("Party Finder"), MenuPage.PARTY) {
    private val floor get() = DungeonFriends.hostingFloor
    private var sort = FriendSort.CATACOMBS
    private var ascending = false
    private var opened = false
    private var missingClasses: Set<DungeonClass>? = null
    private var showLowCata = false
    private var search = ""
    private var seenPartyRevision = DungeonFriends.partyRevision
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var partyStatus: LabelComponent
    private lateinit var actionStatus: LabelComponent
    private lateinit var listStatus: LabelComponent
    private lateinit var floorButton: ButtonComponent
    private lateinit var classButton: ButtonComponent
    private lateinit var sortButton: ButtonComponent
    private lateinit var refreshButton: ButtonComponent
    private val refreshProgress = DungeonRefreshProgress()
    private val sortHeaders = mutableMapOf<FriendSort, Pair<ButtonComponent, String>>()
    private val actions = mutableListOf<Pair<ButtonComponent, String>>()
    private val joinActions = mutableListOf<Pair<ButtonComponent, OnlineDungeonFriend>>()
    private var renderedState: List<Any?> = emptyList()
    private val panelWidth get() = menuWidth
    private val compact get() = panelWidth < 600


    override fun init() {
        if (!opened) { opened = true; DungeonFriends.refreshPartyFinder(automatic = true) }
        super.init()
    }

    override fun removed() { opened = false; super.removed() }

    override fun buildContent(root: FlowLayout) {
        sortHeaders.clear()
        root.surface(Surface.BLANK)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(friendsPanel())
        updateResults()
    }

    override fun tick() {
        super.tick()
        if (menuClosing) return
        if (!::results.isInitialized) return
        if (seenPartyRevision != DungeonFriends.partyRevision) {
            seenPartyRevision = DungeonFriends.partyRevision
            missingClasses = null
        }
        val state = listOf(
            DungeonFriendStatsCache.version, DungeonFriends.scanner, DungeonFriends.scanner.version, floor, sort, ascending, wantedClasses(),
            DungeonFriendsSettings.secondaryClasses, DungeonFriends.replies.version, showLowCata, search,
            me.mycellium.skymyce.features.social.CosmeticsClient.revision,
        )
        if (state != renderedState) {
            renderedState = state
            updateResults()
        }
        actions.forEach { (button, tooltip) ->
            val reason = DungeonFriends.actionUnavailable
            button.active(reason == null)
            button.tooltip(Component.literal(reason ?: tooltip))
        }
        val actionReason = DungeonFriends.joining.status.ifBlank { DungeonFriends.actionUnavailable.orEmpty() }
        if (actionStatus.text().string != actionReason) actionStatus.text(Component.literal(actionReason))
        DungeonFriendRelay.requestPolicies(DungeonFriends.scanner.online.values.map { it.name })
        joinActions.forEach { (button, friend) ->
            val reason = DungeonFriends.joinUnavailable(friend, floor)
            button.active(reason == null)
            button.tooltip(Component.literal(reason ?: "Request to join ${friend.name} on ${floor.name}\nThe host verifies PB requirements before inviting"))
        }
        partyStatus.text(Component.literal(DungeonFriends.partyStatus + if (DungeonFriends.partyFull) "  Full" else ""))
        partyStatus.color(Color.ofRgb(if (DungeonFriends.partyFull) MenuTheme.RED else CYAN))
        partyStatus.tooltip(Component.literal(DungeonFriendsSettings.availability.let {
            if (it.enabled) "Available for ${it.floor.name}: ${it.classes.joinToString { clazz -> clazz.displayName }}"
            else "Choose join classes in Settings > Party Finder. Auto-joining requires a relay agreement."
        } + DungeonFriends.joining.status.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()))
        listStatus.tooltip(Component.literal(DungeonFriends.scanner.status))
        val remaining = DungeonFriendStatsCache.pendingCount
        val refreshing = LocationAPI.onHypixel && (DungeonFriends.scanner.scanning || remaining > 0)
        val percent = refreshProgress.update(DungeonFriendStatsCache.completedCount, remaining,
            DungeonFriends.scanner.completedPages, DungeonFriends.scanner.remainingPages)
        refreshButton.message = Component.literal(if (refreshing || percent == 100) "${if (refreshing) "§b" else ""}Refresh $percent%" else "Refresh")
        refreshButton.active(LocationAPI.onHypixel && !DungeonFriends.scanner.scanning)
        refreshButton.tooltip(Component.literal(if (!LocationAPI.onHypixel) "Join Hypixel to refresh" else buildString {
            append(DungeonFriends.scanner.status)
            if (remaining > 0) append("\n$remaining player stats remaining")
        }))
    }

    private fun label(text: String, color: Int = WHITE) = MenuLabel(Component.literal(text))
        .color(Color.ofRgb(color)).shadow(MenuTheme.SHADOW)

    private fun button(text: String, size: Int, onPress: (ButtonComponent) -> Unit) =
        object : MenuButton(Component.literal(text), onPress) {
            // owo's vanilla wrapper suppresses disabled-widget tooltips, including the reason Join is blocked.
            override fun shouldDrawTooltip(mouseX: Double, mouseY: Double): Boolean =
                visible && isInBoundingBox(mouseX, mouseY) && !tooltip().isNullOrEmpty()
        }.apply {
            sizing(Sizing.fixed(size), Sizing.fixed(22))
            themed()
            textShadow(false)
        }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
        verticalAlignment(VerticalAlignment.CENTER)
    }

    private fun separator() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.fixed(1))
        .surface(Surface.flat(MenuTheme.alpha(MenuTheme.BORDER)))

    private fun panel() = UIContainers.verticalFlow(Sizing.fixed(panelWidth), Sizing.fixed(menuHeight)).apply {
        surface(MenuTheme.panel())
        padding(Insets.of(12))
        gap(8)
    }

    private fun title(text: String) = row().apply {
        gap(8)
        child(UIContainers.verticalFlow(Sizing.fixed(3), Sizing.fixed(18)).surface(MenuTheme.tintedPanel { MenuTheme.ACCENT }))
        child(label("§l$text").horizontalSizing(Sizing.expand()))
    }

    private fun friendsPanel(): FlowLayout = panel().apply {
        child(title("Dungeon Friends").apply {
            floorButton = button(floor.name, 42) { button ->
                menu(button) { dropdown ->
                    FRIEND_FLOORS.forEach { value ->
                        dropdown.button(Component.literal(value.name)) {
                            DungeonFriends.selectFloor(value)
                            updateResults()
                        }
                    }
                }
            }.apply { trailingChevron = true }
            floorButton.tooltip(Component.literal("Dungeon floor for stats, invitations and Join requests. Changes save immediately."))
            child(floorButton)
        })
        child(row().apply {
            gap(6)
            sortButton = button("Sort: ${sort.label}", if (panelWidth < 350) 90 else 116) { button ->
                menu(button) { dropdown ->
                    FriendSort.entries.forEach { value ->
                        dropdown.button(Component.literal(value.label)) { changeSort(value) }
                    }
                }
            }
            sortButton.tooltip(Component.literal("Choose Cata, a class, or S+ PB. Select it again to reverse the order."))
            child(sortButton)
            classButton = button("Missing", if (panelWidth < 350) 90 else 104) { button ->
                menu(button) { dropdown ->
                    DungeonClass.entries.forEach { value ->
                        dropdown.checkbox(Component.literal(value.displayName), value in wantedClasses()) { checked ->
                            missingClasses = if (checked) wantedClasses() + value else wantedClasses() - value
                            updateResults()
                        }
                    }
                    dropdown.button(Component.literal("Use party classes")) { missingClasses = null; updateResults() }
                    dropdown.button(Component.literal("Clear (show all)")) { missingClasses = emptySet(); updateResults() }
                }
            }
            classButton.tooltip(Component.literal("Check the classes you need. Clear all boxes to show every class."))
            child(classButton)
            if (panelWidth >= 350) child(lowCataButton())
            if (!compact) {
                child(UIContainers.horizontalFlow(Sizing.expand(), Sizing.fixed(1)))
                toolbarActions(this)
            }
        })
        if (panelWidth < 350) child(lowCataButton())
        child(row().apply {
            gap(6)
            partyStatus = label(DungeonFriends.partyStatus, CYAN)
            child(partyStatus.horizontalSizing(Sizing.expand()))
            if (compact && panelWidth >= 350) toolbarActions(this)
            else child(label("PARTY FINDER", MUTED))
        })
        if (panelWidth < 350) child(row().apply { gap(6); toolbarActions(this) })
        child(row().apply {
            gap(6)
            val field = MenuTextBox(Sizing.expand()).apply {
                setMaxLength(16); text(search); setHint(Component.literal("Search friends..."))
                onChanged().subscribe { search = it; updateResults() }
            }
            child(field)
            child(button("Clear", 42) { field.text("") }.tooltip(Component.literal("Clear the friend search")))
        })
        child(separator())
        child(row().apply {
            listStatus = label("ONLINE FRIENDS", CYAN)
            child(listStatus.horizontalSizing(Sizing.expand()))
            if (!compact) {
                child(sortHeader("CATA", dungeonFriendStatWidth("CATA"), FriendSort.CATACOMBS))
                displayedClasses.forEach { (clazz, heading) ->
                    child(sortHeader(heading, dungeonFriendStatWidth(heading, 42), FriendSort.entries.first { it.dungeonClass == clazz }))
                }
                child(sortHeader("S+ PB", 70, FriendSort.PB))
            }
            padding(Insets.horizontal(6))
            // Match the list's scrollbar gutter so headers align with row values.
            margins(Insets.right(4))
        })
        actionStatus = label(DungeonFriends.actionUnavailable.orEmpty(), MenuTheme.YELLOW).apply { horizontalSizing(Sizing.fill()) }
        child(actionStatus)
        results = menuScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
            scrollbarThiccness(2)
            padding(Insets.right(4))
        }
        child(results)
    }

    private fun lowCataButton() = button("Low Cata", 70) {
        showLowCata = !showLowCata; updateResults()
    }.apply { selected = { showLowCata } }
        .tooltip(Component.literal("Show friends with stats who do not meet the selected floor's Catacombs or completion requirement."))

    private fun toolbarActions(row: FlowLayout) {
        refreshButton = button("Refresh", 82) {
            DungeonFriends.refreshPartyFinder()
            it.active(false)
        }
        row.child(refreshButton)
    }

    private fun wantedClasses(): Set<DungeonClass> = missingClasses ?: DungeonFriends.openClasses

    private fun messageClass(friend: OnlineDungeonFriend): DungeonClass? {
        val wanted = wantedClasses()
        val stats = DungeonFriendStatsCache.get(friend.name)
        return stats?.selectedClass?.takeIf { it in wanted }
            ?: DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()]?.firstOrNull { it in wanted }
            ?: wanted.firstOrNull()
    }

    private fun changeSort(value: FriendSort) {
        ascending = if (sort == value) !ascending else value == FriendSort.PB
        sort = value
        updateResults()
    }

    private fun sortHeader(text: String, width: Int, value: FriendSort) = button(text, width) { changeSort(value) }.apply {
        sortHeaders[value] = this to text
        tooltip(Component.literal("Sort by ${value.label}; click again to reverse"))
    }

    private fun updateResults() {
        if (!::results.isInitialized) return
        floorButton.message = Component.literal("§b${floor.name}")
        val wanted = wantedClasses()
        classButton.message = Component.literal("Missing (${wanted.size})")
        sortButton.message = Component.literal("Sort: ${sort.label} ${if (ascending) "↑" else "↓"}")
        sortHeaders.forEach { (value, header) ->
            header.first.message = Component.literal("${if (sort == value) "§b" else "§7"}${header.second}")
        }
        val stats = DungeonFriendStatsCache.stats
        val friends = DungeonFriends.scanner.online.values.filter { friend ->
            val data = stats[friend.name.lowercase()]
            matchesFriendName(friend.name, search) && (data?.eligible(floor) != false || (showLowCata && data.state == StatsState.AVAILABLE)) && matchesFriendClass(
                data, DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty(), wanted,
            )
        }.sortedWith(friendComparator(stats, floor, sort, ascending))
        friends.take(128).forEach { CosmeticsClient.observe(it.name, FriendsAPI.getFriend(it.name)?.uuid) }
        listStatus.text(Component.literal("Showing ${friends.size} of ${DungeonFriends.scanner.online.size} online"))
        actions.clear()
        joinActions.clear()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(3)
            if (friends.isEmpty()) child(label("No matching friends. Clear search, change floor or clear Missing.", MUTED)
                .horizontalSizing(Sizing.fill()).margins(Insets.of(8)))
            friends.forEach { friend -> child(friendRow(friend, stats[friend.name.lowercase()])) }
        })
    }

    private fun friendRow(friend: OnlineDungeonFriend, stats: DungeonFriendStats?): FlowLayout =
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            padding(Insets.of(6))
            gap(5)
            val secondary = DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty()
                .filter { it != stats?.selectedClass }
            val reply = DungeonFriends.replies.get(friend.name)
            val pendingInvite = reply?.status in setOf(LfgReplyStatus.WAITING, LfgReplyStatus.INVITED)
            val declined = reply?.status == LfgReplyStatus.DECLINED
            val activity = friend.activity
            surface(MenuTheme.tintedPanel { when {
                declined -> MenuTheme.blend(MenuTheme.CARD, MenuTheme.RED, 14)
                pendingInvite -> MenuTheme.blend(MenuTheme.CARD, MenuTheme.YELLOW, 14)
                else -> MenuTheme.CARD
            } }.and(Surface { graphics, component ->
                graphics.fill(component.x(), component.y(), component.x() + 2, component.y() + component.height(),
                    MenuTheme.alpha(activity.menuColor))
            }))
            val accepted = reply?.status == LfgReplyStatus.ACCEPTED
            val availability = stats?.state?.takeIf { it != StatsState.AVAILABLE }?.label
                ?: if (stats == null) "Waiting for SkyBlock stats" else "SkyBlock profile stats"
            val identity = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
                gap(3)
                child(label(if (friend.bestFriend) "§l${friend.name}" else friend.name).apply {
                    horizontalSizing(Sizing.fill())
                })
                child(label(activity.label, activity.menuColor).horizontalSizing(Sizing.fill()))
                child(label(stats?.selectedClass?.displayName ?: "Unknown", if (stats?.selectedClass != null) CYAN else MUTED)
                    .horizontalSizing(Sizing.fill()))
                if (reply != null) child(label(reply.status.label).horizontalSizing(Sizing.fill()))
                tooltip(Component.literal("${friend.badge}§r\n${friend.location}\nLast played: ${stats?.selectedClass?.displayName ?: "Unknown"}\n$availability${reply?.text?.takeIf { it.isNotEmpty() }?.let { "\nReply: $it" }.orEmpty()}"))
            }
            child(row().apply {
                child(identity)
                if (!compact) statColumns(this, stats)
            })
            if (compact) {
                child(row().apply {
                    child(label("Cata ${stats?.catacombs ?: "—"}", MUTED).horizontalSizing(Sizing.expand()))
                    child(label("${floor.name} S+  ${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—"}", CYAN))
                })
                child(dungeonFriendClassGrid((panelWidth - 40).coerceAtLeast(1),
                    displayedClasses.map { (clazz, heading) -> classColumn(stats, clazz, heading, true) }))
            }
            if (secondary.isNotEmpty()) child(label("Also: ${secondary.joinToString { it.displayName }}", MUTED)
                .horizontalSizing(Sizing.fill()))
            val invite = button("Invite", 46) { DungeonFriends.invite(friend) }.apply {
                active(DungeonFriends.canAct); selected = { accepted }
            }
            val inviteTooltip = "Send ${friend.name} a party invite to accept manually"
            invite.tooltip(Component.literal(DungeonFriends.actionUnavailable ?: inviteTooltip))
            val message = button("Message", 60) { DungeonFriends.message(friend, floor, messageClass(friend)) }.active(DungeonFriends.canAct)
            val messageTooltip = lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, messageClass(friend), floor) + "\nTries relay first; uses /msg if their mod does not acknowledge within 5 seconds."
            message.tooltip(Component.literal(DungeonFriends.actionUnavailable ?: messageTooltip))
            actions += invite to inviteTooltip
            actions += message to messageTooltip
            val joinReason = DungeonFriends.joinUnavailable(friend, floor)
            val join = button("Join", 38) { DungeonFriends.join(friend, floor) }.active(joinReason == null)
            join.tooltip(Component.literal(joinReason ?: "Request to join ${friend.name} on ${floor.name}"))
            joinActions += join to friend
            val edit = button("Edit", 34) { navigateMenu(SettingsScreen(this@DungeonFriendsScreen, SettingsTab.PARTY, dungeonFriend = friend.name)) }
                .tooltip(Component.literal("Edit ${friend.name}'s secondary classes in Party Finder settings"))
            child(dungeonFriendActions((panelWidth - 40).coerceAtLeast(1), listOf(invite, message, join, edit)))
        }

    private fun statColumns(row: FlowLayout, stats: DungeonFriendStats?) {
        row.child(label(stats?.catacombs?.toString() ?: "—").horizontalTextAlignment(HorizontalAlignment.CENTER)
            .horizontalSizing(Sizing.fixed(dungeonFriendStatWidth("CATA"))))
        displayedClasses.forEach { (clazz, heading) ->
            row.child(classColumn(stats, clazz, heading, false))
        }
        row.child(label(stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—", CYAN)
            .horizontalTextAlignment(HorizontalAlignment.CENTER)
            .horizontalSizing(Sizing.fixed(70))
            .tooltip(Component.literal("${floor.name} fastest S+ completion\n${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "No published S+ time"}")))
    }

    private fun classColumn(stats: DungeonFriendStats?, clazz: DungeonClass, heading: String, titles: Boolean): FlowLayout =
        UIContainers.verticalFlow(Sizing.fixed(dungeonFriendStatWidth(heading, 42)), Sizing.content()).apply {
            gap(3); horizontalAlignment(HorizontalAlignment.CENTER)
            if (titles) child(label(heading, MUTED))
            val selected = stats?.selectedClass
            child(label(stats?.classes?.get(clazz)?.toString() ?: "—", if (clazz == selected) CYAN else WHITE))
            tooltip(Component.literal("${clazz.displayName}${if (clazz == selected) " (last played)" else ""}\n${stats?.state?.label ?: "Waiting for SkyBlock stats"}"))
        }

    private fun menu(button: ButtonComponent, entries: (DropdownComponent) -> Unit) = openMenuDropdown(button, entries)

    companion object {
        private val CYAN get() = MenuTheme.ACCENT
        private val WHITE get() = MenuTheme.TEXT
        private val MUTED get() = MenuTheme.MUTED
        private val displayedClasses = listOf(
            DungeonClass.HEALER to "HEAL", DungeonClass.MAGE to "MAGE", DungeonClass.BERSERKER to "BERS",
            DungeonClass.ARCHER to "ARCH", DungeonClass.TANK to "TANK",
        )
    }
}
