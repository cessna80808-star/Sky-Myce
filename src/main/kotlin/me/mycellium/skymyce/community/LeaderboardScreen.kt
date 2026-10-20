package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.economy.BazaarConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.formatDungeonTime
import me.mycellium.skymyce.features.instances.dungeons.tracker.MAX_DUNGEON_RUN_MILLIS
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.roundToLong

data class LeaderboardRow(val rank: Int, val player: String, val value: Double, val samples: Long, val updatedAt: Long,
    val roi: Double? = null, val profitPerHour: Double? = null, val completedFlips: Long? = null)
data class LeaderboardResult(val label: String, val description: String, val unit: String, val source: String, val coverage: String,
    val start: Long?, val end: Long, val updatedAt: Long, val total: Int, val entries: List<LeaderboardRow>, val hasNext: Boolean, val unavailable: String?,
    val identity: String? = null)

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
        fun optionalNumber(key: String) = row[key]?.takeUnless { it.isJsonNull }?.asDouble?.also { require(it.isFinite()) }
        LeaderboardRow(rank, player, value, samples, row["updatedAt"].asLong.also { value -> require(value >= 0) },
            optionalNumber("roi"), optionalNumber("profitPerHour"), row["completedFlips"]?.takeUnless { it.isJsonNull }?.asLong?.also { require(it >= 0) })
    }
    require(rows.zipWithNext().all { (a, b) -> a.rank <= b.rank })
    return LeaderboardResult(text("metricLabel", 100), text("description", 1500), unit, text("source", 1000), text("coverage", 1000),
        json["startAt"].takeUnless { it.isJsonNull }?.asLong, json["endAt"].asLong, json["updatedAt"].asLong,
        json["total"].asInt.also { require(it >= 0) }, rows, json["hasNext"].asBoolean,
        json["available"]?.takeIf { !it.asBoolean }?.let { text("reason", 1000) },
        json["identity"]?.takeUnless { it.isJsonNull }?.asString?.also { require(it in setOf("uuid", "linked")) })
}

internal fun leaderboardCategoryMetrics(category: String) = when (category) {
    "economy" -> listOf("bazaar_profit")
    "dungeons" -> listOf("dungeon_runs", "dungeon_profit", "dungeon_run_time")
    "rng" -> listOf("rng_drops")
    else -> listOf("completed_tasks")
}
internal fun leaderboardPeriods(metric: String) = linkedMapOf("all-time" to "All time",
    "weekly" to if (metric == "bazaar_profit") "This week • local Sunday" else "This week • UTC",
    "monthly" to if (metric == "bazaar_profit") "This month • local" else "This month • UTC")

internal fun leaderboardRequestPath(category: String, metric: String, period: String, page: Int, query: String, identity: String = "uuid"): String {
    val identityQuery = if (metric == "bazaar_profit") { require(identity in setOf("uuid", "linked")); "&identity=$identity" } else ""
    return "/community/leaderboard?category=$category&metric=$metric&period=$period&page=$page&limit=10&search=${URLEncoder.encode(query, StandardCharsets.UTF_8)}$identityQuery"
}

internal fun formatLeaderboardValue(value: Double, unit: String): String {
    if (unit != "milliseconds") return "${java.text.NumberFormat.getNumberInstance(java.util.Locale.ROOT).format(value)} $unit"
    val millis = value.roundToLong()
    return if (millis < 3_600_000) formatDungeonTime(millis)
    else "${millis / 3_600_000}:${formatDungeonTime(millis % 3_600_000).padStart(9, '0')}"
}

/** The in-game and Discord views query the same consent-filtered persisted ranking service. */
class LeaderboardScreen @JvmOverloads constructor(initialMetric: String = "bazaar_profit") : MenuScreen(Component.literal("Community Leaderboards")) {
    override val canonicalFeatureId get() = "leaderboards"
    override val menuCategory get() = MenuCategory.UTILS
    private val metrics = linkedMapOf("bazaar_profit" to "Realized Bazaar profit", "dungeon_runs" to "Dungeon runs", "dungeon_profit" to "Chest profit", "dungeon_run_time" to "Average Run Time", "rng_drops" to "RNG drops", "completed_tasks" to "Completed tasks")
    private var metric = initialMetric.takeIf { it in metrics } ?: "bazaar_profit"
    private var category = listOf("economy", "dungeons", "rng", "other").first { metric in leaderboardCategoryMetrics(it) }
    private var rankingIdentity = "uuid"
    private var period = "all-time"
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
    private lateinit var identityControls: FlowLayout
    private companion object {
        val worker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor {
            Thread(it, "Sky-Myce leaderboards").apply { isDaemon = true }
        })
    }
    override fun init() { closed = false; super.init() }
    override fun buildContent(root: FlowLayout) {
        root.surface(Surface.BLANK).padding(Insets.of(10)); root.gap(8)
        root.child(label("COMMUNITY LEADERBOARDS", MenuTheme.ACCENT))
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            listOf("economy" to "Economy", "dungeons" to "Dungeons", "rng" to "RNG", "other" to "Other").forEach { (id, title) ->
                child(button(title) { category = id; metric = leaderboardCategoryMetrics(category).first(); page = 1; updateControls(); request() }.apply { horizontalSizing(Sizing.fill(23)); selected = { category == id } })
            }
        })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            metricButton = button(metrics[metric]!!) { openMenuDropdown(metricButton) { popup -> leaderboardCategoryMetrics(category).forEach { id -> popup.button(Component.literal(metrics[id]!!)) { metric = id; page = 1; updateControls(); request() } } } }.apply { horizontalSizing(Sizing.fill(35)) }; child(metricButton)
            periodButton = button(leaderboardPeriods(metric).getValue(period)) { openMenuDropdown(periodButton) { popup -> leaderboardPeriods(metric).forEach { (id, title) -> popup.button(Component.literal(title)) { period = id; periodButton.message = Component.literal(title); page = 1; request() } } } }.apply { horizontalSizing(Sizing.fill(35)) }; child(periodButton)
            child(button("Refresh") { request() }.horizontalSizing(Sizing.fixed(85)))
        })
        identityControls = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6) }; root.child(identityControls)
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
        periodButton.message = Component.literal(leaderboardPeriods(metric).getValue(period))
        periodButton.tooltip(Component.literal(if (metric == "bazaar_profit")
            "Sunday-start local weeks and local calendar months. Your configured timezone: ${BazaarConfig.timezone}. Each participant keeps its receipt-time timezone attribution."
            else "Calendar periods use UTC"))
        if (!::identityControls.isInitialized) return
        identityControls.clearChildren()
        if (metric != "bazaar_profit") return
        listOf("uuid" to "Minecraft account", "linked" to "Linked identities").forEach { (id, title) ->
            identityControls.child(button(title) { rankingIdentity = id; page = 1; request() }.apply {
                id("leaderboard-identity-$id"); horizontalSizing(Sizing.fill(48)); selected = { rankingIdentity == id }
            })
        }
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
        val path = leaderboardRequestPath(category, metric, period, page, query, rankingIdentity)
        val requestedIdentity = rankingIdentity.takeIf { metric == "bazaar_profit" }
        busy = true; result = null; status = "Loading ${metrics[metric]}…"; paint()
        fun currentOwner() = !closed && CommunityClient.connected && current == epoch && token == CommunityClient.sessionToken &&
            minecraftOwner == MC.instance.user.profileId.toString().replace("-", "") &&
            ownerGeneration == CommunityClient.sessionGeneration && identity == CommunityClient.identity
        worker.submit(this, ::currentOwner, { parseLeaderboard(CommunityClient.exchange(path, null, "GET", token)).also {
            if (requestedIdentity != null) require(it.identity == requestedIdentity)
        } }) { loaded ->
            MC.instance.execute {
                if (!currentOwner() || menuClosing) return@execute
                busy = false
                loaded.fold({ result = it; status = "${it.label} • page $page • ${it.total} participants" }, { status = "Rankings unavailable. Refresh after reconnecting; local features keep working." })
                paint()
            }
        }
    }
    private fun paint() {
        rows.clearChildren(); rows.child(label(status, MenuTheme.SECONDARY))
        val data = result ?: return
        rows.child(label(data.description, MenuTheme.MUTED))
        val attribution = if (metric == "bazaar_profit") when (period) {
            "weekly" -> "Each participant's local Sunday week"
            "monthly" -> "Each participant's local calendar month"
            else -> "All recorded time"
        } else data.start?.let(::utc) ?: "All recorded time"
        rows.child(label("$attribution → ${displayTime(data.end)} • updated ${if (data.updatedAt > 0) displayTime(data.updatedAt) else "no samples"}", MenuTheme.MUTED))
        if (metric == "bazaar_profit") rows.child(label("${if (rankingIdentity == "linked") "Linked identity totals" else "Per Minecraft account"} · Display timezone ${bazaarZone().id} · Timezone changes apply to future receipts", MenuTheme.MUTED))
        data.unavailable?.let { rows.child(label(it, MenuTheme.YELLOW)); return }
        if (data.entries.isEmpty()) rows.child(label("No consenting participants match this period and search.", MenuTheme.MUTED))
        data.entries.forEach { entry -> rows.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8); padding(Insets.of(7)); surface(MenuTheme.panel())
            child(label("#${entry.rank}", MenuTheme.ACCENT).horizontalSizing(Sizing.fixed(38)))
            child(label(entry.player).horizontalSizing(Sizing.expand()))
            child(label(formatLeaderboardValue(entry.value, data.unit)).horizontalSizing(Sizing.content()))
        }); rows.child(label(if (metric == "bazaar_profit")
            "${entry.samples} supported portions · ${entry.completedFlips ?: 0} completed flips · ROI ${entry.roi?.let { "%.1f%%".format(java.util.Locale.ROOT, it * 100) } ?: "unknown"} · Profit / supported hour ${entry.profitPerHour?.let { formatLeaderboardValue(it, "coins") } ?: "unknown"}"
            else "${entry.samples} samples • ${utc(entry.updatedAt)}", MenuTheme.MUTED)) }
        rows.child(label("Source: ${data.source}", MenuTheme.MUTED)); rows.child(label(data.coverage, MenuTheme.MUTED))
    }
    private fun utc(time: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(time))
    private fun bazaarZone() = runCatching { ZoneId.of(BazaarConfig.timezone) }.getOrDefault(ZoneId.systemDefault())
    private fun displayTime(time: Long) = if (metric == "bazaar_profit") DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(bazaarZone()).format(Instant.ofEpochMilli(time)) else utc(time)
    override fun removed() { closed = true; epoch++; worker.cancel(this); busy = false; nextQuery = 0; super.removed() }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
