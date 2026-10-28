package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.roundToLong
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.formatDungeonTime
import me.mycellium.skymyce.features.instances.dungeons.tracker.MAX_DUNGEON_RUN_MILLIS
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

/** State-specific identity actions, independent sharing settings, and explicit update consent. */
class CommunityScreen(private val parent: Screen? = null) : MenuScreen(Component.literal("Sky-Myce Community")) {
    override val menuParent get() = parent ?: super.menuParent
    private lateinit var status: MenuLabel
    private lateinit var heading: MenuLabel
    private lateinit var account: MenuLabel
    private lateinit var autoUpdate: MenuButton
    private lateinit var connectionActions: FlowLayout
    private lateinit var fallback: FlowLayout
    private lateinit var update: MenuLabel
    private lateinit var notes: FlowLayout
    private lateinit var check: MenuButton
    private lateinit var install: MenuButton
    private lateinit var later: MenuButton
    private lateinit var restart: MenuButton
    private lateinit var noteButton: MenuButton
    private val actionButtons = mutableMapOf<CommunityConnectionAction, MenuButton>()
    private var actionKey: Pair<CommunityConnectionKind, List<CommunityConnectionAction>>? = null
    private var fallbackCode: String? = null
    private var shownNotes = ""
    private var notesOpen = false
    override val canonicalFeatureId get() = "community"

    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COMMUNITY", MenuTheme.ACCENT))
        root.child(label("Discord connects chat, cosmetics and shared progress. Local features work offline.", MenuTheme.MUTED))
        val content = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(10) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), content).apply { scrollStep(20); scrollbarThiccness(2) })
        val connection = card(); content.child(connection)
        heading = label("DISCORD", MenuTheme.SECONDARY); connection.child(heading)
        status = label(CommunityClient.status); connection.child(status)
        account = label("", MenuTheme.MUTED); connection.child(account)
        connectionActions = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6) }; connection.child(connectionActions)
        fallback = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(5) }; connection.child(fallback)

        val services = card(); content.child(services)
        services.child(label("CHAT & COSMETICS", MenuTheme.SECONDARY))
        services.child(label("Global Chat uses your connected identity. Local cosmetic choices work offline; Publish shares them with compatible clients.", MenuTheme.MUTED))
        services.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            child(button("Open Global Chat") { openMenuFeature("global-chat") }.horizontalSizing(Sizing.fill(48)))
            child(button("Player cosmetics") { openMenuFeature("cosmetics") }.horizontalSizing(Sizing.fill(48)))
        })
        content.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Community data sharing: ${me.mycellium.skymyce.config.misc.CommunitySharingConfig.mode} · ${CommunitySharing.status}", MenuTheme.MUTED).horizontalSizing(Sizing.expand()))
            child(button("Sharing settings") { navigateMenu(SettingsScreen.forFeature(this@CommunityScreen, "community")) }.horizontalSizing(Sizing.fixed(125)))
        })
        val updates = card(); content.child(updates)
        updates.child(label("UPDATES • installed ${CommunityClient.version}", MenuTheme.SECONDARY))
        update = label(CommunityUpdates.status); updates.child(update)
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6); verticalAlignment(VerticalAlignment.CENTER)
            child(label("Auto-update", MenuTheme.TEXT).horizontalSizing(Sizing.expand()))
            autoUpdate = button(if (CommunityUpdates.autoUpdate) "On" else "Off") { CommunityUpdates.setAutoUpdate(!CommunityUpdates.autoUpdate) }.apply {
                horizontalSizing(Sizing.fixed(64)); toggled = { CommunityUpdates.autoUpdate }
                tooltip(Component.literal("On downloads verified releases and installs after exit. Off leaves installation to Update Now."))
            }; child(autoUpdate)
        })
        updates.child(label("On: download verified releases and install after exit. Off: choose Update Now. Later skips that release. Your current JAR is backed up.", MenuTheme.MUTED))
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            check = button("Check updates") { CommunityUpdates.check(manual = true) }.apply { horizontalSizing(Sizing.fill(31)) }; child(check)
            install = button("Update Now") { CommunityUpdates.install() }.apply { horizontalSizing(Sizing.fill(31)); intent = MenuButtonIntent.PRIMARY }; child(install)
            later = button("Later") { CommunityUpdates.defer() }.apply { horizontalSizing(Sizing.fill(31)) }; child(later)
        })
        updates.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            noteButton = button("Release notes") { notesOpen = !notesOpen; updateNotes() }.apply { horizontalSizing(Sizing.fill(48)) }; child(noteButton)
            restart = button("Restart Minecraft") { CommunityUpdates.restart() }.apply {
                horizontalSizing(Sizing.fill(48))
                tooltip(Component.literal("Closes and relaunches Minecraft. A ready update is installed first. Save your work before restarting."))
            }; child(restart)
        })
        notes = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()); updates.child(notes)
        root.child(button("Done") { onClose() })
        actionKey = null; fallbackCode = null; shownNotes = ""; refresh()
    }

    override fun tick() { super.tick(); if (::status.isInitialized && !menuClosing) refresh() }

    private fun refresh() {
        val view = CommunityConnectionView(CommunityClient.connected, CommunityClient.hasSavedSession,
            CommunityClient.connectionPending, CommunityClient.cancellationPending, CommunityClient.busy, CommunityClient.browserUrl != null)
        val key = view.kind to view.actions
        if (key != actionKey) {
            actionKey = key; actionButtons.clear(); connectionActions.clearChildren()
            view.actions.forEach { action ->
                val title = when (action) {
                    CommunityConnectionAction.CONNECT -> "Connect Discord"
                    CommunityConnectionAction.RECONNECT -> "Reconnect saved session"
                    CommunityConnectionAction.BROWSER -> "Open Discord in browser"
                    CommunityConnectionAction.CANCEL -> if (view.cancelling) "Retry cancellation" else "Cancel connection"
                    CommunityConnectionAction.DISCONNECT -> "Disconnect this session"
                }
                val component = button(title) { when (action) {
                    CommunityConnectionAction.CONNECT -> CommunityClient.connect()
                    CommunityConnectionAction.RECONNECT -> CommunityClient.retry()
                    CommunityConnectionAction.BROWSER -> CommunityClient.openBrowser()
                    CommunityConnectionAction.CANCEL -> CommunityClient.cancelConnect()
                    CommunityConnectionAction.DISCONNECT -> CommunityClient.disconnect()
                } }.apply { horizontalSizing(if (view.actions.size == 1) Sizing.fill() else Sizing.fill(96 / view.actions.size)) }
                component.intent = when (action) {
                    CommunityConnectionAction.CONNECT, CommunityConnectionAction.RECONNECT, CommunityConnectionAction.BROWSER -> MenuButtonIntent.PRIMARY
                    CommunityConnectionAction.DISCONNECT -> MenuButtonIntent.DESTRUCTIVE
                    CommunityConnectionAction.CANCEL -> MenuButtonIntent.SECONDARY
                }
                actionButtons[action] = component; connectionActions.child(component)
            }
        }
        actionButtons.forEach { (action, button) -> button.active = view.enabled(action) }
        heading.text(Component.literal(when (view.kind) {
            CommunityConnectionKind.DISCONNECTED -> "DISCORD • not connected"
            CommunityConnectionKind.SAVED -> "DISCORD • saved session"
            CommunityConnectionKind.CONNECTED -> "DISCORD • connected"
            CommunityConnectionKind.PENDING -> "FINISH CONNECTING"
            CommunityConnectionKind.CANCELLING -> "PENDING CONNECTION • cancellation"
        }).withColor(MenuTheme.SECONDARY))
        status.text(Component.literal(CommunityClient.status))
        account.text(Component.literal(if (CommunityClient.connected) {
            val identity = CommunityClient.identity
            val name = identity?.get("displayName")?.asString ?: identity?.get("username")?.asString ?: "Discord user"
            "Discord: $name • Minecraft: ${MC.instance.user.name}"
        } else "Connecting is optional. Saved credentials stay on this installation."))
        val code = CommunityClient.fallbackCode.takeIf { view.kind == CommunityConnectionKind.PENDING }
        if (code != fallbackCode) {
            fallbackCode = code; fallback.clearChildren()
            if (code != null) {
                fallback.child(label("Browser unavailable? Use Mousebot in the Sky-Myce Discord:", MenuTheme.MUTED))
                fallback.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                    gap(6); verticalAlignment(VerticalAlignment.CENTER)
                    child(label("/connect code:$code", MenuTheme.ACCENT).horizontalSizing(Sizing.expand()))
                    child(button("Copy command") { MC.instance.keyboardHandler.clipboard = "/connect code:$code"; showMenuToast("Connection command copied", MenuToastKind.SUCCESS) }.horizontalSizing(Sizing.fixed(112)))
                })
                fallback.child(label("Private, one-use code • expires within ten minutes. Cancel requests server revocation.", MenuTheme.MUTED))
            }
        }
        check.active = CommunityUpdates.canCheck
        install.active = CommunityUpdates.canInstall; later.active = CommunityUpdates.canDefer
        restart.active = CommunityUpdates.canRestart
        update.text(Component.literal(CommunityUpdates.status))
        autoUpdate.message = Component.literal(if (CommunityUpdates.autoUpdate) "On" else "Off")
        autoUpdate.active = CommunityUpdates.canChangePreferences
        noteButton.active = CommunityUpdates.releaseNotes.isNotBlank()
        if (CommunityUpdates.releaseNotes != shownNotes) updateNotes()
    }

    private fun updateNotes() {
        shownNotes = CommunityUpdates.releaseNotes; notes.clearChildren()
        noteButton.message = Component.literal(if (notesOpen) "Hide release notes" else "Release notes")
        if (notesOpen && shownNotes.isNotBlank()) notes.child(label(shownNotes, MenuTheme.MUTED))
    }

    private fun card() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); padding(Insets.of(10)); surface(MenuTheme.panel()) }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}

data class LeaderboardRow(val rank: Int, val player: String, val value: Double, val samples: Long, val updatedAt: Long)
data class LeaderboardResult(val label: String, val description: String, val unit: String, val source: String, val coverage: String,
    val start: Long?, val end: Long, val updatedAt: Long, val total: Int, val entries: List<LeaderboardRow>, val hasNext: Boolean, val unavailable: String?)

fun parseLeaderboard(json: JsonObject): LeaderboardResult {
    require(json["ok"].asBoolean && json["version"].asInt == 1)
    fun text(key: String, max: Int) = json[key].asString.also { require(it.length <= max && it.none { c -> c == '\u0000' }) }
    val unit = text("unit", 30)
    val rows = json.getAsJsonArray("entries").also { require(it.size() <= 25) }.map {
        val row = it.asJsonObject
        val rank = row["rank"].asInt.also { value -> require(value > 0) }
        val player = row["player"].asString.also { value -> require(value.length in 1..100 && value.none { c -> c < ' ' }) }
        val value = row["value"].asDouble.also { value -> require(value.isFinite()) }
        if (unit == "milliseconds") require(value in 1.0..MAX_DUNGEON_RUN_MILLIS.toDouble())
        val samples = row["sampleSize"].asLong.also { value -> require(value >= 0) }
        LeaderboardRow(rank, player, value, samples, row["updatedAt"].asLong.also { value -> require(value >= 0) })
    }
    require(rows.zipWithNext().all { (a, b) -> a.rank <= b.rank })
    return LeaderboardResult(text("metricLabel", 100), text("description", 1500), unit, text("source", 1000), text("coverage", 1000),
        json["startAt"].takeUnless { it.isJsonNull }?.asLong, json["endAt"].asLong, json["updatedAt"].asLong,
        json["total"].asInt.also { require(it >= 0) }, rows, json["hasNext"].asBoolean,
        json["available"]?.takeIf { !it.asBoolean }?.let { text("reason", 1000) })
}

internal fun leaderboardCategoryMetrics(category: String) = when (category) {
    "dungeons" -> listOf("dungeon_runs", "dungeon_profit", "dungeon_run_time")
    "rng" -> listOf("rng_drops")
    else -> listOf("completed_tasks")
}
internal val leaderboardPeriods = linkedMapOf("all-time" to "All time", "weekly" to "This week • UTC", "monthly" to "This month • UTC")

internal fun leaderboardRequestPath(category: String, metric: String, period: String, page: Int, query: String, floor: String? = null): String =
    "/community/leaderboard?category=$category&metric=$metric&period=$period&page=$page&limit=10&search=${URLEncoder.encode(query, StandardCharsets.UTF_8)}" +
        (floor?.let { "&floor=$it" } ?: "")

internal fun formatLeaderboardValue(value: Double, unit: String): String {
    if (unit != "milliseconds") return "${java.text.NumberFormat.getNumberInstance(java.util.Locale.ROOT).format(value)} $unit"
    val millis = value.roundToLong()
    return if (millis < 3_600_000) formatDungeonTime(millis)
    else "${millis / 3_600_000}:${formatDungeonTime(millis % 3_600_000).padStart(9, '0')}"
}

/** The in-game and Discord views query the same consent-filtered persisted ranking service. */
class LeaderboardScreen @JvmOverloads constructor(initialMetric: String = "dungeon_runs") : MenuScreen(Component.literal("Community Leaderboards")) {
    override val canonicalFeatureId get() = "leaderboards"
    override val menuCategory get() = MenuCategory.UTILS
    private val metrics = linkedMapOf("dungeon_runs" to "Dungeon runs", "dungeon_profit" to "Chest profit", "dungeon_run_time" to "Average Run Time", "rng_drops" to "RNG drops", "completed_tasks" to "Completed tasks")
    private var metric = initialMetric.takeIf { it in metrics } ?: "dungeon_runs"
    private var category = listOf("dungeons", "rng", "other").first { metric in leaderboardCategoryMetrics(it) }
    private var period = "all-time"
    private var floor: String? = null
    private var page = 1
    private var query = ""
    private var nextQuery = 0L
    private var generation = -1L
    @Volatile private var epoch = 0L
    @Volatile private var closed = false
    private var busy = false
    private var result: LeaderboardResult? = null
    private var status = "Connect Discord to view community leaderboards"
    private lateinit var rows: FlowLayout
    private lateinit var metricButton: MenuButton
    private lateinit var periodButton: MenuButton
    private lateinit var floorButton: MenuButton
    private lateinit var controls: FlowLayout
    private companion object {
        val worker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce leaderboards").apply { isDaemon = true }
        })
        val utcFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
    override fun init() { closed = false; super.init() }
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COMMUNITY LEADERBOARDS", MenuTheme.ACCENT))
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            listOf("dungeons" to "Dungeons", "rng" to "RNG", "other" to "Other").forEach { (id, title) ->
                child(button(title) { category = id; metric = leaderboardCategoryMetrics(category).first(); page = 1; updateControls(); request() }.apply { horizontalSizing(Sizing.fill(32)); selected = { category == id } })
            }
        })
        controls = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content())
        root.child(controls.apply {
            gap(6)
            metricButton = button(metrics[metric]!!) { openMenuDropdown(metricButton) { popup -> leaderboardCategoryMetrics(category).forEach { id -> popup.button(Component.literal(metrics[id]!!)) { metric = id; page = 1; updateControls(); request() } } } }.apply { horizontalSizing(Sizing.fill(35)) }; child(metricButton)
            periodButton = button(leaderboardPeriods.getValue(period)) { openMenuDropdown(periodButton) { popup -> leaderboardPeriods.forEach { (id, title) -> popup.button(Component.literal(title)) { period = id; periodButton.message = Component.literal(title); page = 1; request() } } } }.apply { horizontalSizing(Sizing.fill(35)) }; child(periodButton)
            floorButton = button("All floors") { openMenuDropdown(floorButton) { popup -> (listOf<String?>(null) + DungeonFloor.entries.map { it.name }).forEach { id -> popup.button(Component.literal(id ?: "All floors")) { floor = id; page = 1; updateControls(); request() } } } }
                .apply { horizontalSizing(Sizing.fill(18)); tooltip(Component.literal("Average Run Time floor")) }
            child(button("Refresh") { request() }.horizontalSizing(Sizing.fixed(85)))
        })
        updateControls()
        root.child(MenuTextBox(Sizing.fill()).apply {
            setMaxLength(100); setHint(Component.literal("Search shared display names…")); text(query)
            onChanged().subscribe {
                query = it; page = 1; nextQuery = System.currentTimeMillis() + 350
                epoch++; worker.cancel(this@LeaderboardScreen); busy = false; result = null
            }
        })
        rows = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(8)) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), rows).apply { scrollbarThiccness(2); scrollStep(20) })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6); child(button("Previous") { if (page > 1 && !busy) { page--; request() } }.horizontalSizing(Sizing.fill(31)))
            child(button("Next") { if (result?.hasNext == true && !busy) { page++; request() } }.horizontalSizing(Sizing.fill(31)))
            child(button("Connect Discord") { navigateMenu(CommunityScreen(this@LeaderboardScreen)) }.horizontalSizing(Sizing.fill(31)))
        })
        paint(); request()
    }
    private fun updateControls() {
        metricButton.message = Component.literal(metrics.getValue(metric))
        periodButton.message = Component.literal(leaderboardPeriods.getValue(period))
        periodButton.tooltip(Component.literal("Calendar periods use UTC"))
        // Only the average run time board is split by floor; the others keep the wider two-dropdown row.
        val timed = metric == "dungeon_run_time"
        metricButton.horizontalSizing(Sizing.fill(if (timed) 26 else 35)); periodButton.horizontalSizing(Sizing.fill(if (timed) 26 else 35))
        floorButton.message = Component.literal(floor ?: "All floors")
        if (timed && floorButton.parent() == null) controls.child(2, floorButton) else if (!timed && floorButton.parent() != null) controls.removeChild(floorButton)
    }
    override fun tick() {
        super.tick(); if (menuClosing) return
        if (generation != CommunityClient.sessionGeneration) { generation = CommunityClient.sessionGeneration; epoch++; busy = false; result = null; request() }
        if (nextQuery != 0L && System.currentTimeMillis() >= nextQuery) { nextQuery = 0; request() }
    }
    private fun request() {
        if (!::rows.isInitialized || closed || menuClosing) return
        val token = CommunityClient.sessionToken; val identity = CommunityClient.identity?.deepCopy()
        if (!CommunityClient.connected || token == null || identity == null) {
            epoch++; worker.cancel(this); busy = false; result = null
            status = "Connect Discord to view shared rankings. Your local journal remains available."; paint(); return
        }
        val minecraftOwner = MC.instance.user.profileId.toString().replace("-", "")
        if (identity["uuid"]?.asString != minecraftOwner) {
            epoch++; worker.cancel(this); busy = false; result = null
            status = "Reconnect the selected Minecraft account to view its community rankings."; paint(); return
        }
        val current = ++epoch; val ownerGeneration = CommunityClient.sessionGeneration
        val selectedFloor = floor.takeIf { metric == "dungeon_run_time" }
        val path = leaderboardRequestPath(category, metric, period, page, query, selectedFloor)
        val floorSuffix = selectedFloor?.let { " • $it" } ?: ""
        busy = true; result = null; status = "Loading ${metrics[metric]}$floorSuffix…"; paint()
        fun currentOwner() = !closed && CommunityClient.connected && current == epoch && token == CommunityClient.sessionToken &&
            minecraftOwner == MC.instance.user.profileId.toString().replace("-", "") &&
            ownerGeneration == CommunityClient.sessionGeneration && identity == CommunityClient.identity
        worker.submit(this, ::currentOwner, { parseLeaderboard(CommunityClient.exchange(path, null, "GET", token)) }) { loaded ->
            MC.instance.execute {
                if (!currentOwner() || menuClosing) return@execute
                busy = false
                loaded.fold({ result = it; status = "${it.label}$floorSuffix • page $page • ${it.total} participants" }, { status = "Rankings unavailable. Refresh after reconnecting; local features keep working." })
                paint()
            }
        }
    }
    private fun paint() {
        rows.clearChildren(); rows.child(label(status, MenuTheme.SECONDARY))
        val data = result ?: return
        rows.child(label(data.description, MenuTheme.MUTED))
        val attribution = data.start?.let(::utc) ?: "All recorded time"
        rows.child(label("$attribution → ${utc(data.end)} • updated ${if (data.updatedAt > 0) utc(data.updatedAt) else "no samples"}", MenuTheme.MUTED))
        data.unavailable?.let { rows.child(label(it, MenuTheme.YELLOW)); return }
        if (data.entries.isEmpty()) rows.child(label("No consenting participants match this period and search.", MenuTheme.MUTED))
        data.entries.forEach { entry -> rows.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); padding(Insets.of(7)); surface(MenuTheme.panel())
            child(label("#${entry.rank}", MenuTheme.ACCENT).horizontalSizing(Sizing.fixed(38)))
            child(label(entry.player).horizontalSizing(Sizing.expand()))
            child(label(formatLeaderboardValue(entry.value, data.unit)).horizontalSizing(Sizing.content()))
        }); rows.child(label("${entry.samples} samples • ${utc(entry.updatedAt)}", MenuTheme.MUTED)) }
        rows.child(label("Source: ${data.source}", MenuTheme.MUTED)); rows.child(label(data.coverage, MenuTheme.MUTED))
    }
    private fun utc(time: Long) = utcFormat.format(Instant.ofEpochMilli(time))
    override fun removed() { closed = true; epoch++; worker.cancel(this); busy = false; nextQuery = 0; super.removed() }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
