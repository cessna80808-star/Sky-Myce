package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.ui.*
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.features.social.CosmeticsClient
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI

class DungeonFriendsScreen : MenuScreen(Component.literal("Party Finder"), MenuPage.PARTY) {
    private val floor get() = DungeonFriends.hostingFloor
    private var sort = FriendSort.CATACOMBS
    private var ascending = false
    private var opened = false
    private var missingClasses: Set<DungeonClass>? = null
    private var settingsOpen = false
    private var editingFriend: String? = null
    private var showLowCata = false
    private var templateDraft = DungeonFriendsSettings.messageTemplate
    private var classesDraft = emptySet<DungeonClass>()
    private var availableDraft = emptySet<DungeonClass>()
    private var settingsFloor = floor
    private var pbLimitDraft = ""
    private var titlesDraft = false
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
    private var reconnectButton: ButtonComponent? = null
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
        root.child(if (settingsOpen) settingsPanel() else friendsPanel())
        if (!settingsOpen) updateResults()
    }

    override fun tick() {
        super.tick()
        reconnectButton?.tooltip(Component.literal(DungeonFriendRelay.status))
        if (settingsOpen || !::results.isInitialized) return
        if (seenPartyRevision != DungeonFriends.partyRevision) {
            seenPartyRevision = DungeonFriends.partyRevision
            missingClasses = null
        }
        val state = listOf(
            DungeonFriendStatsCache.version, DungeonFriends.scanner.online.toMap(), floor, sort, ascending, wantedClasses(),
            DungeonFriendsSettings.secondaryClasses, DungeonFriends.replies.version, showLowCata,
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
        val actionReason = DungeonFriends.actionUnavailable.orEmpty()
        if (actionStatus.text().string != actionReason) actionStatus.text(Component.literal(actionReason))
        DungeonFriendRelay.requestPolicies(DungeonFriends.scanner.online.values.map { it.name })
        joinActions.forEach { (button, friend) ->
            val reason = DungeonFriends.joinUnavailable(friend, floor)
            button.active(reason == null)
            button.tooltip(Component.literal(reason ?: "Request to join ${friend.name} on ${floor.name}\nThe host verifies PB requirements before inviting"))
        }
        partyStatus.text(Component.literal(DungeonFriends.partyStatus + if (DungeonFriends.partyFull) "  Full" else ""))
        partyStatus.color(Color.ofRgb(if (DungeonFriends.partyFull) 0xF18C8C else CYAN))
        partyStatus.tooltip(Component.literal(DungeonFriendsSettings.availability.let {
            if (it.enabled) "Available for ${it.floor.name}: ${it.classes.joinToString { clazz -> clazz.displayName }}"
            else "Choose Available classes for Join requests. Auto-joining requires a relay agreement."
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
            sizing(Sizing.fixed(size), Sizing.fixed(18))
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
            floorButton = button(floor.name + " v", 42) { button ->
                menu(button) { dropdown ->
                    FRIEND_FLOORS.forEach { value ->
                        dropdown.button(Component.literal(value.name)) {
                            DungeonFriends.selectFloor(value)
                            updateResults()
                        }
                    }
                }
            }
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
        child(separator())
        child(row().apply {
            listStatus = label("ONLINE FRIENDS", CYAN)
            child(listStatus.horizontalSizing(Sizing.expand()))
            if (!compact) {
                child(sortHeader("CATA", 32, FriendSort.CATACOMBS))
                displayedClasses.forEach { (clazz, heading) ->
                    child(sortHeader(heading, 42, FriendSort.entries.first { it.dungeonClass == clazz }))
                }
                child(sortHeader("S+ PB", 70, FriendSort.PB))
                child(label("ACTIONS", MUTED).horizontalTextAlignment(HorizontalAlignment.CENTER)
                    .horizontalSizing(Sizing.fixed(ACTIONS_WIDTH)).margins(Insets.left(ACTIONS_GAP)))
            }
            padding(Insets.horizontal(6))
            // Match the list's scrollbar gutter so headers align with row values.
            margins(Insets.right(4))
        })
        actionStatus = label(DungeonFriends.actionUnavailable.orEmpty(), 0xE8BF71).apply { horizontalSizing(Sizing.fill()) }
        child(actionStatus)
        results = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
            scrollbarThiccness(2)
            padding(Insets.right(4))
        }
        child(results)
    }

    private fun lowCataButton() = button(if (showLowCata) "§aLow Cata" else "Low Cata", 70) {
        showLowCata = !showLowCata; updateResults()
    }.tooltip(Component.literal("Show friends with stats who do not meet the selected floor's Catacombs or completion requirement."))

    private fun toolbarActions(row: FlowLayout) {
        row.child(button(if (DungeonFriendsSettings.availability.enabled) "§aAvailable" else "Available", if (panelWidth < 350) 60 else 76) { openSettings(null) })
        refreshButton = button("Refresh", if (panelWidth < 350) 68 else 82) {
            DungeonFriends.refreshPartyFinder()
            it.active(false)
        }
        row.child(refreshButton)
        row.child(button("Settings", if (panelWidth < 350) 48 else 54) { openSettings(null) })
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
        if (settingsOpen || !::results.isInitialized) return
        floorButton.message = Component.literal("§b${floor.name} v")
        val wanted = wantedClasses()
        classButton.message = Component.literal("Missing (${wanted.size})")
        sortButton.message = Component.literal("Sort: ${sort.label} ${if (ascending) "↑" else "↓"}")
        sortHeaders.forEach { (value, header) ->
            header.first.message = Component.literal("${if (sort == value) "§b" else "§7"}${header.second}")
        }
        val stats = DungeonFriendStatsCache.stats
        val friends = DungeonFriends.scanner.online.values.filter { friend ->
            val data = stats[friend.name.lowercase()]
            (data?.eligible(floor) != false || (showLowCata && data.state == StatsState.AVAILABLE)) && matchesFriendClass(
                data, DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty(), wanted,
            )
        }.sortedWith(friendComparator(stats, floor, sort, ascending))
        friends.take(128).forEach { CosmeticsClient.observe(it.name, FriendsAPI.getFriend(it.name)?.uuid) }
        listStatus.text(Component.literal("FRIENDS  ${friends.size}/${DungeonFriends.scanner.online.size}"))
        actions.clear()
        joinActions.clear()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(3)
            if (friends.isEmpty()) child(label("No matching friends. Change floor or clear Missing.", MUTED)
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
                    0xFF000000.toInt() or activity.color)
            }))
            val accepted = reply?.status == LfgReplyStatus.ACCEPTED
            val availability = stats?.state?.takeIf { it != StatsState.AVAILABLE }?.label
                ?: if (stats == null) "Waiting for SkyBlock stats" else "SkyBlock profile stats"
            val identity = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
                gap(3)
                child(label(if (friend.bestFriend) "§l${friend.name}" else friend.name).apply {
                    horizontalSizing(Sizing.fill())
                })
                child(label(activity.label, activity.color).horizontalSizing(Sizing.fill()))
                child(label(stats?.selectedClass?.displayName ?: "Unknown", if (stats?.selectedClass != null) CYAN else MUTED)
                    .horizontalSizing(Sizing.fill()))
                if (reply != null) child(label(reply.status.label).horizontalSizing(Sizing.fill()))
                tooltip(Component.literal("${friend.badge}§r\n${friend.location}\nLast played: ${stats?.selectedClass?.displayName ?: "Unknown"}\n$availability${reply?.text?.takeIf { it.isNotEmpty() }?.let { "\nReply: $it" }.orEmpty()}"))
            }
            child(row().apply {
                child(identity)
                if (!compact) statColumns(this, stats, false)
                child((if (panelWidth < 350) UIContainers.verticalFlow(Sizing.fixed(88), Sizing.content()) else row()).apply {
                    horizontalSizing(Sizing.fixed(if (panelWidth < 350) 88 else ACTIONS_WIDTH))
                    margins(Insets.left(ACTIONS_GAP))
                    gap(3)
                    val invite = button("Party", 40) { DungeonFriends.invite(friend) }.active(DungeonFriends.canAct)
                    if (accepted) invite.renderer { g, b, _ -> MenuTheme.rounded(g, b.x, b.y, b.width, b.height, MenuTheme.alpha(if (b.isHovered) 0x34865C else 0x246545)) }
                    val inviteTooltip = "Send ${friend.name} a party invite to accept manually"
                    invite.tooltip(Component.literal(DungeonFriends.actionUnavailable ?: inviteTooltip))
                    val message = button("Invite", 44) { DungeonFriends.message(friend, floor, messageClass(friend)) }.active(DungeonFriends.canAct)
                    val messageTooltip = lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, messageClass(friend), floor) + "\nTries relay first; uses /msg if their mod does not acknowledge within 5 seconds."
                    message.tooltip(Component.literal(DungeonFriends.actionUnavailable ?: messageTooltip))
                    actions += invite to inviteTooltip
                    actions += message to messageTooltip
                    val join = button("Join", 34) { DungeonFriends.join(friend, floor) }.active(DungeonFriends.joinUnavailable(friend, floor) == null)
                    joinActions += join to friend
                    val edit = button("Edit", 30) { openSettings(friend.name) }.tooltip(Component.literal("Edit secondary classes"))
                    if (panelWidth < 350) {
                        child(row().apply { gap(3); children(listOf(invite, message)) })
                        child(row().apply { gap(3); children(listOf(join, edit)) })
                    } else children(listOf(invite, message, join, edit))
                })
            })
            if (compact) {
                child(row().apply {
                    child(label("Cata ${stats?.catacombs ?: "—"}", MUTED).horizontalSizing(Sizing.expand()))
                    child(label("${floor.name} S+  ${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—"}", CYAN))
                })
                child(row().apply { statColumns(this, stats, true) })
            }
            if (secondary.isNotEmpty()) child(label("Also: ${secondary.joinToString { it.displayName }}", MUTED)
                .horizontalSizing(Sizing.fill()))
        }

    private fun statColumns(row: FlowLayout, stats: DungeonFriendStats?, titles: Boolean) {
        val selected = stats?.selectedClass
        if (!titles) row.child(label(stats?.catacombs?.toString() ?: "—").horizontalTextAlignment(HorizontalAlignment.CENTER)
            .horizontalSizing(Sizing.fixed(32)))
        displayedClasses.forEach { (clazz, heading) ->
            row.child(UIContainers.verticalFlow(if (titles) Sizing.fill(20) else Sizing.fixed(42), Sizing.content()).apply {
                gap(3)
                horizontalAlignment(HorizontalAlignment.CENTER)
                if (titles) child(label(heading, MUTED))
                child(label(stats?.classes?.get(clazz)?.toString() ?: "—", if (clazz == selected) CYAN else WHITE))
                tooltip(Component.literal("${clazz.displayName}${if (clazz == selected) " (last played)" else ""}\n${stats?.state?.label ?: "Waiting for SkyBlock stats"}"))
            })
        }
        if (!titles) row.child(label(stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—", CYAN)
            .horizontalTextAlignment(HorizontalAlignment.CENTER)
            .horizontalSizing(Sizing.fixed(70))
            .tooltip(Component.literal("${floor.name} fastest S+ completion\n${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "No published S+ time"}")))
    }

    private fun openSettings(friend: String?) {
        editingFriend = friend
        settingsFloor = floor
        templateDraft = DungeonFriendsSettings.messageTemplate
        classesDraft = DungeonFriendsSettings.secondaryClasses[friend?.lowercase()].orEmpty()
        availableDraft = DungeonFriendsSettings.availability.classes
        pbLimitDraft = DungeonFriendsSettings.availability.maxPbMillis?.let(::formatDungeonTime).orEmpty()
        titlesDraft = DungeonFriendsSettings.titleNotifications
        settingsOpen = true
        rebuild()
    }

    private fun settingsPanel(): FlowLayout = panel().apply {
        child(title(editingFriend?.let { "Classes: $it" } ?: "LFG Settings"))
        child(separator())
        child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), settingsContent()).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
            scrollbarThiccness(2)
            padding(Insets.right(4))
        })
    }

    private fun settingsContent(): FlowLayout = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(8)
        if (editingFriend == null) {
            reconnectButton = button("Reconnect", 76) { DungeonFriendRelay.reconnect() }.also {
                it.tooltip(Component.literal(DungeonFriendRelay.status))
                child(it)
            }
            child(button("Title notifications: ${if (titlesDraft) "On" else "Off"}", 164) { button ->
                titlesDraft = !titlesDraft
                button.message = Component.literal("Title notifications: ${if (titlesDraft) "On" else "Off"}")
            }.tooltip(Component.literal("Show invitations and players joining your party as titles.")))
            child(label("AVAILABLE FOR ${settingsFloor.name}", CYAN))
            DungeonClass.entries.chunked(((panelWidth - 28) / 98).coerceIn(1, 5)).forEach { group ->
                child(row().apply {
                    gap(4)
                    group.forEach { clazz ->
                        child(button("${if (clazz in availableDraft) "[x]" else "[ ]"} ${clazz.displayName}", 94) { button ->
                            availableDraft = if (clazz in availableDraft) availableDraft - clazz else availableDraft + clazz
                            button.message = Component.literal("${if (clazz in availableDraft) "[x]" else "[ ]"} ${clazz.displayName}")
                        }.tooltip(Component.literal("Offer ${clazz.displayName} when requesting to join. Auto-accept requires an agreed relay request.")))
                    }
                })
            }
            child(row().apply {
                gap(8)
                child(label("Join PB limit", CYAN))
                child(MenuTextBox(Sizing.fixed(100)).apply {
                    setMaxLength(10)
                    text(pbLimitDraft)
                    setHint(Component.literal("No limit"))
                    onChanged().subscribe { pbLimitDraft = it }
                }.tooltip(Component.literal("Your ${settingsFloor.name} requirement for players using Join (m:ss). Leave blank for no limit. Invitations bypass it.")))
            })
            child(separator())
        }
        child(label("MESSAGE TEMPLATE", CYAN))
        child(label("Use {name}, {class} and {floor}.", MUTED).horizontalSizing(Sizing.fill()))
        child(MenuTextBox(Sizing.fill()).apply {
            setMaxLength(220)
            text(templateDraft)
            setHint(Component.literal("Message sent by the Invite button"))
            onChanged().subscribe { templateDraft = it }
        })
        editingFriend?.let { name ->
            child(label("SECONDARY CLASSES", CYAN))
            val stats = DungeonFriendStatsCache.get(name)
            DungeonClass.entries.forEach { clazz ->
                child(row().apply {
                    gap(8)
                    child(button("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}", 104) { button ->
                        classesDraft = if (clazz in classesDraft) classesDraft - clazz else classesDraft + clazz
                        button.message = Component.literal("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}")
                    })
                    child(label("Level ${stats?.classes?.get(clazz) ?: "—"}", CYAN))
                })
            }
        }
        val feedback = label(DungeonFriendsSettings.error.orEmpty(), 0xF18C8C)
        child(feedback.horizontalSizing(Sizing.fill()))
        child(row().apply {
            gap(6)
            child(button("Save", 58) {
                val available = if (editingFriend == null) DungeonAvailability(settingsFloor, availableDraft, parsePbLimit(pbLimitDraft))
                    else DungeonFriendsSettings.availability
                if (editingFriend == null && pbLimitDraft.isNotBlank() && available.maxPbMillis == null) {
                    feedback.text(Component.literal("Use a time such as 5:30, or leave the PB limit blank."))
                    return@button
                }
                val classes = DungeonFriendsSettings.secondaryClasses.toMutableMap()
                editingFriend?.lowercase()?.let { name ->
                    if (classesDraft.isEmpty()) classes.remove(name) else classes[name] = classesDraft
                }
                val changedAvailability = available != DungeonFriendsSettings.availability
                if (DungeonFriendsSettings.save(templateDraft, classes, available, titles = titlesDraft)) {
                    if (changedAvailability) DungeonFriends.joining.clear()
                    settingsOpen = false
                    rebuild()
                } else feedback.text(Component.literal(DungeonFriendsSettings.error ?: "Use a nonempty message (up to 220 characters)."))
            })
            child(button("Cancel", 58) {
                settingsOpen = false
                rebuild()
            })
        })
    }

    private fun menu(button: ButtonComponent, entries: (DropdownComponent) -> Unit) {
        DropdownComponent.openContextMenu(this, uiAdapter.rootComponent, { root, dropdown -> root.child(dropdown) },
            button.x.toDouble(), (button.y + button.height).toDouble()) { menu -> menu.surface(MenuTheme.panel()); entries(menu) }
    }

    private fun rebuild() {
        uiAdapter.rootComponent.clearChildren()
        build(uiAdapter.rootComponent)
        uiAdapter.inflateAndMount()
    }

    companion object {
        private const val ACTIONS_WIDTH = 157 // Party + Invite + Join + Edit, with three 3px gaps.
        private const val ACTIONS_GAP = 12
        private val CYAN get() = MenuTheme.ACCENT
        private val WHITE get() = MenuTheme.TEXT
        private val MUTED get() = MenuTheme.MUTED
        private val displayedClasses = listOf(
            DungeonClass.HEALER to "HEAL", DungeonClass.MAGE to "MAGE", DungeonClass.BERSERKER to "BERS",
            DungeonClass.ARCHER to "ARCH", DungeonClass.TANK to "TANK",
        )
    }
}
