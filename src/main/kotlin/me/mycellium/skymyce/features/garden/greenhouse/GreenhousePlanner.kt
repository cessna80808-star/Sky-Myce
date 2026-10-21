package me.mycellium.skymyce.features.garden.greenhouse

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GreenhouseConfig
import me.mycellium.skymyce.features.bazaar.BazaarTransport
import me.mycellium.skymyce.features.general.pricehistory.formatCoins
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.BlockChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderWorldEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Greenhouse planner. Client-local and visual only: it reads the world and the held item and never places blocks. The
 * only command it sends is one /bz when the player clicks a crop in the shopping list.
 * World reads, layout state and rendering stay on the client thread; price fetches run on the shared scheduler and
 * solving on one low-priority worker thread, handing results back through [MC.instance] execute.
 */
object GreenhousePlanner : SkyMyceModule() {
    private const val PRICE_INTERVAL = 5 * 60_000L
    private const val MAX_LAYOUTS = 8
    private const val MAX_ESTIMATES = 128
    private const val MAX_LEARNED = 512
    private const val MAX_HARVESTS = 4000
    /** Mutations whose whole-plot profit the ranking estimates, by per-mutation profit. */
    private const val ESTIMATED = 12
    private const val ESTIMATE_ITERATIONS = 30_000
    /** Planting, spawns and harvests count only between two scans this close, made with the player this near. */
    private const val CONTINUITY_MS = 10_000L
    private const val WATCH_DISTANCE = 24

    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("greenhouse.json"), 262_144) }
    private val historyFile by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("greenhouse-history.json"), 1_048_576) }
    private fun writer(target: () -> AtomicJsonFile, what: String) =
        StateWriter<JsonObject>({ target().write(it) }, { work -> Scheduling.schedule(200.milliseconds) { work() } }, {
            SkyMyce.logger.warn("Greenhouse $what could not be saved", it)
        })
    private val writer by lazy { writer({ file }, "plan") }
    private val historyWriter by lazy { writer({ historyFile }, "history") }
    private val transport by lazy { BazaarTransport() }
    private val worker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "Sky-Myce Greenhouse solver").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
    internal val catalog: PlantCatalog? by lazy {
        runCatching { parsePlantCatalog(GreenhousePlanner::class.java.getResourceAsStream("/greenhouse/plants.json")!!.reader().use { it.readText() }) }
            .onFailure { SkyMyce.logger.error("Bundled Greenhouse data is unreadable; the planner stays off", it) }.getOrNull()
    }

    internal data class Anchor(val pos: BlockPos, val facing: Direction)
    private data class Ghost(val box: AABB, val color: Int)
    private data class PlotEstimate(val count: Int, val profitPerHour: Double)

    /** One Greenhouse plot. Its [layout] is pinned: only plan, auto, recompute and import replace it, never a price update. */
    internal class Plot(val name: String) {
        var anchor: Anchor? = null
        val locked = BooleanArray(GREENHOUSE_CELLS)
        var target: String? = null
        var layout: Layout? = null
        var plantedAt = 0L
        val spawned = HashMap<Int, Long>()
        var status = ""
        var scannedAt = 0L
        var missingPlants: Map<String, Int> = emptyMap()
        var missingSoil: Map<Soil, Int> = emptyMap()
        /** The previous scan, kept in memory, so planting, spawns and harvests are seen as they happen. */
        var previous: BooleanArray? = null
        var previousAt = 0L
        var previousComplete = false
    }

    private var loaded = false
    internal val plots = ArrayList<Plot>()
    /** The plot the overlay, HUD and commands use: the nearest anchored plot while in the Garden. */
    private var plot: Plot? = null
    /** Solved layouts by inputs hash, so planning the same inputs again is instant. */
    private val layouts = LinkedHashMap<String, Layout>()
    private val estimates = LinkedHashMap<String, PlotEstimate>()
    /** Crop fingerprint → plant id, learned while planting or from /sm greenhouse identify. */
    private val learned = LinkedHashMap<String, String>()
    internal val history = ArrayList<Harvest>()
    private var prices: Map<String, Quote> = emptyMap()
    private var pricesObservedAt = 0L
    private var nextPoll = 0L
    private var polling = false
    private var priceFailureLogged = false
    private var solving = false
    private var estimating = false
    /** An explicit plan request waiting for the first Bazaar prices: the plot and whether it was a forced recompute. */
    private var waitingForPrices: Pair<Plot, Boolean>? = null
    /** Bumped whenever plot state the menu shows changes. */
    internal var revision = 0
        private set

    private var ticks = 0
    private var heldStack: ItemStack? = null
    private var heldId: String? = null
    private var dirty = true
    private var ghosts: List<Ghost> = emptyList()
    private var hudLines: List<String>? = null

    private val unanchoredLines = listOf("§aGreenhouse §7· plan ready", "§eLook at the corner slot's soil:", "§e/sm greenhouse anchor")
    private val content by lazy { hudPanel() }
    private val preview by lazy {
        hudPanel().updateTextLines(listOf("§aGreenhouse §7· Glasscorn ×8", "§f+312k/h §7est. · 64% built", "§7Chloronite §f×5 §7· Startlevine §f×3",
            "§7Sand soil §f×12", "§8solved 3m ago · prices 2m old"))
    }

    val widget = Widget("greenhouse_planner", "Greenhouse Planner", 10, 200, 1f, previewBuilder = { preview }) {
        if (!isEnabled() || !GreenhouseConfig.enabled) return@Widget null
        hudLines?.let { content.updateTextLines(it) }
    }

    override fun init() {
        ClientSendMessageEvents.COMMAND.register(::copyBazaarQuantity)
        Scheduling.schedule(Duration.ZERO) {
            val json = file.read()?.takeIf { it.isJsonObject }?.asJsonObject
            val harvests = historyFile.read()?.takeIf { it.isJsonObject }?.asJsonObject
            MC.instance.execute { json?.let(::restore); harvests?.let(::restoreHistory); loaded = true; dirty = true; revision++ }
        }
    }

    internal fun settings() = GreenhouseSettings(GreenhouseConfig.upgradeLevel, GreenhouseConfig.uniqueCrops, GreenhouseConfig.priceMode)

    private fun allowed(plant: Plant) = plant.rarity.ordinal <= GreenhouseConfig.maxTier.ordinal

    fun ranking(): List<RankedMutation> {
        val catalog = catalog ?: return emptyList()
        val ranked = rankMutations(catalog, prices, settings(), ::allowed)
        return if (GreenhouseConfig.profitMetric == ProfitMetric.PER_CYCLE) ranked.sortedByDescending { it.profitPerCycle } else ranked
    }

    internal fun selected(): Plot = plot ?: (plots.firstOrNull() ?: Plot("1").also(plots::add)).also { plot = it }

    private fun unlocked(plot: Plot) = BooleanArray(GREENHOUSE_CELLS) { !plot.locked[it] }

    // ---- Prices -------------------------------------------------------------------------------------------------

    /** Prices only refresh the estimates; a pinned layout is never re-solved here. */
    private fun pollPrices(now: Long) {
        if (polling || now < nextPoll) return
        polling = true
        nextPoll = now + PRICE_INTERVAL
        Scheduling.schedule(Duration.ZERO) {
            val result = runCatching { transport.fetch(System.currentTimeMillis()) }
            MC.instance.execute {
                polling = false
                result.onSuccess { snapshot ->
                    val wanted = catalog?.plants?.map { it.bazaarId }?.toSet().orEmpty()
                    val quotes = snapshot.products.filter { it.id in wanted }.mapNotNull { product ->
                        val buy = product.buyOrderPrice; val sell = product.sellOfferPrice
                        if (buy == null && sell == null) null else product.id to Quote(buy ?: sell!!, sell ?: buy!!)
                    }.toMap()
                    if (quotes.isNotEmpty()) {
                        prices = quotes; pricesObservedAt = snapshot.observedAt; priceFailureLogged = false
                        dirty = true; revision++; save()
                        waitingForPrices?.let { (plot, force) -> waitingForPrices = null; plan(plot, force) }
                    }
                }.onFailure { error ->
                    // Keep the last good prices; log the first failure of a streak only.
                    if (!priceFailureLogged) SkyMyce.logger.warn("Greenhouse Bazaar prices unavailable, keeping the last known prices", error)
                    priceFailureLogged = true
                    (error as? me.mycellium.skymyce.features.bazaar.BazaarMarketFailure)?.retryAt?.let { nextPoll = maxOf(nextPoll, it) }
                }
            }
        }
    }

    // ---- Solving ------------------------------------------------------------------------------------------------

    /** Runs only for an explicit request. Reuses a cached layout whenever the inputs hash matches, unless [force]d. */
    internal fun plan(plot: Plot, force: Boolean) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (prices.isEmpty()) {
            waitingForPrices = plot to force; nextPoll = 0; pollPrices(System.currentTimeMillis())
            return displayModMessage("§7Waiting for Bazaar prices before solving…")
        }
        if (solving) return displayModMessage("§7Still solving the previous layout; try again in a moment")
        val target = plot.target ?: plotRanking(plot).firstOrNull()?.entry?.plant?.id ?: return displayModMessage("§cNo mutation up to your highest tier has Bazaar prices")
        val name = catalog[target]?.name ?: target
        val unlocked = unlocked(plot)
        val settings = settings()
        val hash = inputsHash(unlocked, listOf(target), settings, catalog, prices)
        if (!force) layouts[hash]?.let { cached ->
            pin(plot, cached)
            return displayModMessage("§aUsing the cached $name layout${chatLabel(plot)}: ${cached.targets.size} placed, §f${rate(estimate(cached))} §7estimated")
        }
        solving = true
        displayModMessage("§7Solving the $name layout${chatLabel(plot)}…")
        val quotes = prices
        worker.execute {
            val result = runCatching { GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(target)).solve(hash.hashCode().toLong(), System.currentTimeMillis(), hash) }
            MC.instance.execute {
                solving = false
                result.onFailure { SkyMyce.logger.warn("Greenhouse layout could not be solved", it) }
                val solved = result.getOrNull() ?: return@execute displayModMessage("§cNo valid layout for $name with the unlocked cells and current prices")
                layouts.remove(hash); layouts[hash] = solved
                while (layouts.size > MAX_LAYOUTS) layouts.remove(layouts.keys.first())
                remember(hash, PlotEstimate(solved.targets.size, solved.totalProfitPerHour))
                pin(plot, solved)
                displayModMessage("§aGreenhouse layout ready${chatLabel(plot)}: $name ×${solved.targets.size}, §f${rate(solved.totalProfitPerHour)} §7estimated")
            }
        }
    }

    private fun pin(plot: Plot, layout: Layout) {
        plot.layout = layout; plot.spawned.clear(); plot.plantedAt = 0; plot.previous = null; plot.status = ""; plot.scannedAt = 0
        plot.missingPlants = emptyMap(); plot.missingSoil = emptyMap()
        dirty = true; revision++; save()
    }

    /** The pinned layout's profit at current prices; the solve-time figure when a price is missing. */
    internal fun estimate(layout: Layout) = catalog?.let { layout.profitPerHour(it, prices, settings()) } ?: layout.totalProfitPerHour

    /** Hours from a target appearing to its harvest, from the site's timer; null without a target. */
    internal fun cycleHours(layout: Layout): Double? {
        val plant = layout.targets.firstOrNull()?.let { catalog?.get(it.plantId) } ?: return null
        return cycleHours(plant, GreenhouseConfig.upgradeLevel, maxOf(GreenhouseConfig.uniqueCrops, layout.uniqueCrops))
    }

    private fun remember(hash: String, estimate: PlotEstimate) {
        estimates.remove(hash); estimates[hash] = estimate
        while (estimates.size > MAX_ESTIMATES) estimates.remove(estimates.keys.first())
    }

    // ---- Whole-plot ranking -------------------------------------------------------------------------------------

    internal data class PlotRanked(val entry: RankedMutation, val count: Int?, val plotPerHour: Double?)

    /** Ranks by what one plot of each mutation earns where that is estimated, then by per-mutation profit. */
    internal fun plotRanking(plot: Plot): List<PlotRanked> {
        val catalog = catalog ?: return emptyList()
        val unlocked = unlocked(plot); val settings = settings()
        return ranking().map { entry ->
            val known = estimates[inputsHash(unlocked, listOf(entry.plant.id), settings, catalog, prices)]
            PlotRanked(entry, known?.count, known?.profitPerHour)
        }.sortedWith(compareByDescending<PlotRanked> { it.plotPerHour != null }.thenByDescending { row ->
            val perHour = row.plotPerHour ?: row.entry.profitPerHour
            if (GreenhouseConfig.profitMetric == ProfitMetric.PER_CYCLE) perHour * row.entry.cycleHours else perHour
        })
    }

    /** Solves the top mutations at reduced effort, one at a time, so an explicit plan waits for at most one of them. */
    private fun estimatePlot(plot: Plot, done: () -> Unit) {
        val catalog = catalog ?: return done()
        val unlocked = unlocked(plot); val settings = settings(); val quotes = prices
        val queue = ArrayDeque(ranking().take(ESTIMATED).map { it.plant.id }.filter { estimates[inputsHash(unlocked, listOf(it), settings, catalog, quotes)] == null })
        if (queue.isEmpty()) return done()
        estimating = true
        displayModMessage("§7Estimating how many of the top ${queue.size} mutations fit on plot ${plot.name}…")
        fun next() {
            val id = queue.removeFirstOrNull()
            if (id == null) { estimating = false; save(); done(); return }
            val hash = inputsHash(unlocked, listOf(id), settings, catalog, quotes)
            worker.execute {
                val layout = runCatching { GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(id), ESTIMATE_ITERATIONS).solve(hash.hashCode().toLong(), 0, hash) }.getOrNull()
                MC.instance.execute { remember(hash, PlotEstimate(layout?.targets?.size ?: 0, layout?.totalProfitPerHour ?: 0.0)); next() }
            }
        }
        next()
    }

    // ---- Commands -----------------------------------------------------------------------------------------------

    private fun run(action: () -> Unit): Int { MC.instance.execute(action); return 1 }

    fun command(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("greenhouse")
        .executes { run(::showRanking) }
        .then(literal("help").executes { run(::showHelp) })
        .then(literal("gui").executes { run { navigateMenu(GreenhouseScreen()) } })
        .then(literal("plan").then(argument("mutation", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.filter { it.solvable }?.map { it.id }.orEmpty(), builder) }
            .executes { context -> val id = StringArgumentType.getString(context, "mutation").uppercase(); run { choose(id) } }))
        .then(literal("auto").executes { run { choose(null) } })
        .then(literal("recompute").executes { run { plan(selected(), force = true) } })
        .then(literal("import").executes { run { importPlot(selected(), null) } }
            .then(argument("mutation", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.filter { it.solvable }?.map { it.id }.orEmpty(), builder) }
                .executes { context -> val id = StringArgumentType.getString(context, "mutation").uppercase(); run { importPlot(selected(), id) } }))
        .then(literal("identify").then(argument("crop", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.map { it.id }.orEmpty(), builder) }
            .executes { context -> val id = StringArgumentType.getString(context, "crop").uppercase(); run { identify(id) } }))
        .then(literal("anchor").executes { run { setAnchor(null) } }
            .then(argument("name", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(plots.map { it.name }, builder) }
                .executes { context -> val name = StringArgumentType.getString(context, "name"); run { setAnchor(name) } }))
        .then(literal("remove").then(argument("name", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(plots.map { it.name }, builder) }
            .executes { context -> val name = StringArgumentType.getString(context, "name"); run { removePlot(name) } }))
        .then(literal("lock").then(argument("row", IntegerArgumentType.integer(1, GREENHOUSE_SIZE))
            .then(argument("column", IntegerArgumentType.integer(1, GREENHOUSE_SIZE)).executes { context ->
                val row = IntegerArgumentType.getInteger(context, "row") - 1; val column = IntegerArgumentType.getInteger(context, "column") - 1
                run { toggleLock(row, column) }
            })))
        .then(literal("missing").executes { run { showMissing(selected()) } })
        .then(literal("shopping").executes { run { showMissing(selected()) } })
        .then(literal("buy").then(argument("crop", StringArgumentType.word()).executes { context ->
            val id = StringArgumentType.getString(context, "crop").uppercase(); run { buy(id) }
        }))

    /**
     * Tab completion for the server's /bz and /gfs. The nodes have no executor, so Fabric passes the typed command to the
     * server unchanged; the client only adds the selected plot's missing crops and soils to the suggestions.
     */
    fun serverCommandHints(): List<LiteralArgumentBuilder<FabricClientCommandSource>> = listOf(
        literal("bz").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(if (active()) plot?.missingPlants?.keys?.mapNotNull { catalog?.get(it)?.name }.orEmpty() else emptyList(), builder)
        }),
        literal("gfs").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(if (active()) plot?.missingSoil?.map { (soil, count) -> "${sackId(soil)} $count" }.orEmpty() else emptyList(), builder)
        }),
    )

    private fun active() = GreenhouseConfig.enabled && isEnabled()

    internal fun showHelp() {
        displayModMessage("§aGreenhouse Planner commands §7(all start with /sm greenhouse)")
        listOf(
            "" to "Profit ranking: what a full plot of each mutation earns. Click [Plan] to plan it",
            "gui" to "Your greenhouses: layout, growth timers, estimated and harvested profit",
            "anchor [name]" to "Look at the corner slot's soil to place a plot; reads crops already planted there",
            "import [mutation]" to "Read what is planted on this plot and pin it as the layout instead of solving a new one",
            "identify <crop>" to "Look at a planted crop the planner does not recognise and name it",
            "plan <mutation>" to "Solve a new layout for that mutation on this plot",
            "auto" to "Solve a new layout for the most profitable mutation",
            "recompute" to "Solve again, ignoring the cached layout",
            "missing" to "Everything still needed; click a crop for /bz with the amount copied, or a soil for /gfs (also: shopping)",
            "lock <row> <column>" to "Exclude or include one slot; run recompute afterwards",
            "remove <name>" to "Forget a plot",
            "help" to "This list",
        ).forEach { (args, text) ->
            val command = "/sm greenhouse${if (args.isEmpty()) "" else " $args"}"
            displayModMessage(Component.literal("§b$command §7$text").withStyle {
                it.withClickEvent(ClickEvent.SuggestCommand(command.substringBefore(" <").substringBefore(" [")))
            })
        }
        displayModMessage("§8A layout stays as it is until you plan, auto, recompute or import. The overlay follows the plot you are closest to")
    }

    internal fun showRanking() {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (!GreenhouseConfig.enabled) displayModMessage("§7Greenhouse Planner is off in settings; the ranking still works")
        if (prices.isEmpty()) { nextPoll = 0; pollPrices(System.currentTimeMillis()); return displayModMessage("§7Fetching Bazaar prices… run /sm greenhouse again in a moment") }
        val plot = selected()
        if (estimating) return displayModMessage("§7Still estimating whole-plot profit; the ranking follows when it is done")
        val now = System.currentTimeMillis()
        pollPrices(now)
        if (ranking().take(ESTIMATED).any { estimates[inputsHash(unlocked(plot), listOf(it.plant.id), settings(), catalog, prices)] == null })
            return estimatePlot(plot, ::showRanking)
        val metric = GreenhouseConfig.profitMetric
        displayModMessage("§aGreenhouse profit per plot §7(${GreenhouseConfig.priceMode.label}, ${metric.label.lowercase()}, prices ${age(now - pricesObservedAt)} old)")
        plotRanking(plot).take(10).forEachIndexed { i, row ->
            val entry = row.entry
            val value = metricText(row.plotPerHour ?: entry.profitPerHour, entry.cycleHours)
            val scope = if (row.count != null) "§7×${row.count} on the plot" else "§7each, not estimated"
            val line = Component.literal("§7${i + 1}. §f${entry.plant.name} §a$value $scope §8· each ${metricText(entry.profitPerHour, entry.cycleHours)} · ${"%.1f".format(entry.cycleHours)}h ")
                .append(Component.literal("§b[Plan]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse plan ${entry.plant.id}")) })
            displayModMessage(line)
        }
        val current = plot.layout
        val target = current?.targets?.firstOrNull()?.let { catalog[it.plantId]?.name }
        displayModMessage(when {
            current == null -> "§7No layout yet${chatLabel(plot)}. Click [Plan], run /sm greenhouse auto, or import what is planted"
            else -> "§7Current plan${chatLabel(plot)}: §f$target ×${current.targets.size} §a${rate(estimate(current))} §7· ${if (current.inputsHash.startsWith(IMPORTED)) "imported" else "solved"} ${age(now - current.generatedAt)} ago"
        })
        if (plot.anchor == null) displayModMessage("§eLook at the soil of the corner slot and run /sm greenhouse anchor to place the overlay")
        displayModMessage("§8Estimates only, using skymutations.eu's formula (by palmaner); plot counts come from a quick solve. /sm greenhouse help lists every command")
    }

    private fun choose(id: String?) {
        val plant = id?.let { catalog?.get(it) }
        if (id != null && plant?.solvable != true) return displayModMessage("§cUnknown or unsupported mutation: $id")
        val plot = selected()
        plot.target = plant?.id
        save()
        plan(plot, force = false)
    }

    /** Without a name: anchors the unanchored plot, re-anchors the plot containing the block, or adds a new plot. */
    private fun setAnchor(name: String?) {
        val hit = MC.instance.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) return displayModMessage("§cLook at the soil block of the corner slot first")
        if (name != null && !name.matches(PLOT_NAME)) return displayModMessage("§cPlot names are 1-16 letters, digits, _ or -")
        val pos = hit.blockPos.immutable()
        val facing = MC.player.direction.takeIf { it.axis.isHorizontal } ?: Direction.NORTH
        val plot = if (name != null) plots.firstOrNull { it.name.equals(name, true) } ?: Plot(name).also(plots::add)
            else plots.firstOrNull { it.anchor == null } ?: plots.firstOrNull { p -> p.anchor?.let { cellOf(it, pos) } != null }
                ?: Plot(generateSequence(1) { it + 1 }.map(Int::toString).first { n -> plots.none { it.name == n } }).also(plots::add)
        plot.anchor = Anchor(pos, facing); plot.previous = null
        this.plot = plot; dirty = true; revision++; save()
        displayModMessage("§aGreenhouse plot §f${plot.name}§a anchored at ${pos.x} ${pos.y} ${pos.z}: §7rows run ${facing.name.lowercase()}, columns to your right")
        if (plot.layout == null && !importPlot(plot, null, quiet = true)) displayModMessage("§7Plan it with /sm greenhouse auto, or click [Plan] in /sm greenhouse")
    }

    private fun removePlot(name: String) {
        val removed = plots.firstOrNull { it.name.equals(name, true) } ?: return displayModMessage("§cNo Greenhouse plot named $name")
        plots.remove(removed)
        if (plot === removed) plot = null
        if (waitingForPrices?.first === removed) waitingForPrices = null
        dirty = true; revision++; save()
        displayModMessage("§7Removed Greenhouse plot ${removed.name}")
    }

    private fun toggleLock(row: Int, column: Int) {
        val plot = selected()
        val cell = row * GREENHOUSE_SIZE + column
        plot.locked[cell] = !plot.locked[cell]
        save()
        displayModMessage("§7Cell ${row + 1},${column + 1} is now ${if (plot.locked[cell]) "§clocked" else "§aunlocked"}§7. ${plot.locked.count { !it }} cells usable" +
            if (plot.layout != null) "; run /sm greenhouse recompute to re-plan around it" else "")
    }

    internal fun showMissing(plot: Plot) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (plot.layout == null) return displayModMessage("§7No layout yet${chatLabel(plot)}. Run /sm greenhouse auto or plan <mutation>")
        if (plot.scannedAt == 0L) return displayModMessage("§7Stand near Greenhouse plot ${plot.name} in the Garden to check what is missing")
        val stale = if (plot !== this.plot || hudLines == null) " §8(as of ${age(System.currentTimeMillis() - plot.scannedAt)} ago)" else ""
        if (plot.missingPlants.isEmpty() && plot.missingSoil.isEmpty()) return displayModMessage("§aNothing missing${chatLabel(plot)}: every crop and soil matches the plan$stale")
        displayModMessage("§aGreenhouse shopping list${chatLabel(plot)}$stale §7(click to open the Bazaar and copy the amount, or to fill in /gfs)")
        plot.missingPlants.entries.sortedByDescending { it.value }.forEach { (id, count) ->
            displayModMessage(Component.literal("§f$count× §7${catalog[id]?.name ?: id} ")
                .append(Component.literal("§b[Bazaar]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse buy $id")) }))
        }
        plot.missingSoil.entries.sortedByDescending { it.value }.forEach { (soil, count) ->
            displayModMessage(Component.literal("§f$count× §7${soilName(soil)} soil ")
                .append(Component.literal("§b[Sacks]").withStyle { it.withClickEvent(ClickEvent.SuggestCommand("/gfs ${sackId(soil)} $count")) }))
        }
    }

    /** A shopping-list click sends one /bz; [copyBazaarQuantity] then copies the amount, as it does for a typed /bz. */
    private fun buy(id: String) {
        val name = catalog?.get(id)?.name ?: return displayModMessage("§cUnknown crop: $id")
        MC.player.connection.sendCommand("bz $name")
    }

    /** A /bz for a crop the selected plot still needs copies the missing amount, ready for the Bazaar buy sign. */
    private fun copyBazaarQuantity(command: String) {
        val verb = command.substringBefore(' ').lowercase()
        if (verb != "bz" && verb != "bazaar" || !active()) return
        val query = command.substringAfter(' ', "").trim().replace('_', ' ')
        val catalog = catalog ?: return
        val (plant, count) = plot?.missingPlants.orEmpty().entries.firstNotNullOfOrNull { (id, count) ->
            catalog[id]?.takeIf { it.name.equals(query, true) || it.id.replace('_', ' ').equals(query, true) }?.let { it to count }
        } ?: return
        MC.instance.keyboardHandler.clipboard = count.toString()
        displayModMessage("§7Copied §f$count§7 to the clipboard (${plant.name} still needed)")
    }

    // ---- Reading planted crops ----------------------------------------------------------------------------------

    private const val IMPORTED = "imported-"

    /** Pins what is planted on [plot] as its layout. Returns false (with a message unless [quiet]) when nothing usable is found. */
    internal fun importPlot(plot: Plot, target: String?, quiet: Boolean = false): Boolean {
        fun fail(message: String): Boolean { if (!quiet) displayModMessage(message); return false }
        val catalog = catalog ?: return fail("§cGreenhouse data is unavailable")
        if (target != null && catalog[target]?.solvable != true) return fail("§cUnknown or unsupported mutation: $target")
        val origin = plot.anchor ?: return fail("§cAnchor plot ${plot.name} first: look at the corner slot's soil and run /sm greenhouse anchor")
        val level = MC.instance.level ?: return fail("§cJoin your Garden first")
        if (LocationAPI.island != SkyBlockIsland.GARDEN || !loaded(level, origin)) return fail("§cStand near plot ${plot.name} in your Garden to read what is planted")
        val planted = arrayOfNulls<String>(GREENHOUSE_CELLS)
        val occupied = BooleanArray(GREENHOUSE_CELLS)
        val soil = arrayOfNulls<Soil>(GREENHOUSE_CELLS)
        val unknown = HashMap<String, Int>()
        for (cell in 0 until GREENHOUSE_CELLS) {
            val soilPos = cellPos(origin, cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE)
            soil[cell] = soilOf(level.getBlockState(soilPos))
            val fingerprint = GreenhouseWorld.fingerprint(level, soilPos.above()) ?: continue
            occupied[cell] = true
            planted[cell] = GreenhouseWorld.plantFor(fingerprint, catalog, learned)
            if (planted[cell] == null) unknown.merge(fingerprint, 1, Int::plus)
        }
        if (unknown.isNotEmpty()) SkyMyce.logger.info("Greenhouse plot {} has unidentified crops: {}", plot.name, unknown)
        val unknownHint = if (unknown.isEmpty()) null else "§e${unknown.values.sum()} planted spots were not recognised. Look at one and run /sm greenhouse identify <crop>, then import again"
        if (planted.all { it == null }) { unknownHint?.let { if (!quiet) displayModMessage(it) }; return fail("§7Nothing recognisable is planted on plot ${plot.name}") }
        val now = System.currentTimeMillis()
        val layout = importLayout(catalog, planted, occupied, soil, unlocked(plot), target, prices, settings(), now, IMPORTED + now.toString(36))
            ?: return fail("§cThe planted crops do not complete any ${target?.let { catalog[it]?.name } ?: "mutation"}'s ingredients yet")
        plot.target = layout.targets.first().plantId
        pin(plot, layout)
        val name = catalog[plot.target!!]?.name
        val growing = layout.targets.count { t -> occupied[t.row * GREENHOUSE_SIZE + t.col] }
        displayModMessage("§aImported plot ${plot.name}: §f$name ×${layout.targets.size} §7($growing growing, ${layout.targets.size - growing} open), " +
            "${layout.placements.count { it.role == Role.INGREDIENT }} ingredients, §f${rate(estimate(layout))} §7estimated. This layout is now pinned")
        unknownHint?.let(::displayModMessage)
        return true
    }

    private fun identify(id: String) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        val plant = catalog[id] ?: return displayModMessage("§cUnknown crop: $id")
        val level = MC.instance.level ?: return
        // Further than block reach, so a crop in the middle of the plot can be named from its edge.
        val hit = MC.player.pick(32.0, 1f, false) as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) return displayModMessage("§cLook at the crop, or the soil under it, first (within 32 blocks)")
        val pos = if (soilOf(level.getBlockState(hit.blockPos)) != null) hit.blockPos.above() else hit.blockPos
        val fingerprint = GreenhouseWorld.fingerprint(level, pos) ?: return displayModMessage("§cNothing is planted there")
        learn(fingerprint, plant.id)
        displayModMessage("§aLearned: that crop is ${plant.name}. §7Run /sm greenhouse import to read the plot again")
    }

    private fun learn(fingerprint: String, plantId: String) {
        learned.remove(fingerprint); learned[fingerprint] = plantId
        while (learned.size > MAX_LEARNED) learned.remove(learned.keys.first())
        save()
    }

    private fun loaded(level: Level, origin: Anchor) = listOf(0 to 0, 0 to GREENHOUSE_SIZE - 1, GREENHOUSE_SIZE - 1 to 0, GREENHOUSE_SIZE - 1 to GREENHOUSE_SIZE - 1)
        .all { (row, col) -> level.hasChunkAt(cellPos(origin, row, col)) }

    private fun soilOf(state: BlockState): Soil? = Soil.entries.firstOrNull { state.`is`(soilBlock(it)) }

    // ---- World overlay ------------------------------------------------------------------------------------------

    private fun clear() { ghosts = emptyList(); hudLines = null }

    @Subscription
    fun onTick(event: TickEvent) {
        if (!loaded || !active()) return clear()
        val now = System.currentTimeMillis()
        val inGarden = LocationAPI.island == SkyBlockIsland.GARDEN
        if (inGarden) pollPrices(now)
        val player = MC.instance.player
        if (!inGarden || player == null) return clear()
        val position = player.blockPosition()
        var nearest: Plot? = null
        var nearestDistance = Double.MAX_VALUE
        for (candidate in plots) {
            val distance = candidate.anchor?.let { centerDistanceSq(it, position) } ?: continue
            if (distance < nearestDistance) { nearest = candidate; nearestDistance = distance }
        }
        if (nearest != null && nearest !== plot) { plot = nearest; dirty = true }
        val plot = selected()
        val current = plot.layout
        val origin = plot.anchor
        if (origin == null) { clear(); if (current != null) hudLines = unanchoredLines; return }
        if (origin.pos.distSqr(position) > square(GreenhouseConfig.renderDistance + 24)) return clear()
        val stack = player.mainHandItem
        if (stack !== heldStack) {
            heldStack = stack
            val id = heldItemId(stack)
            if (id != heldId) { heldId = id; dirty = true }
        }
        // Block updates mark dirty; the periodic pass covers chunk reloads and crop entities that bypass them.
        if (!dirty && ++ticks % 40 != 0) return
        dirty = false
        if (current != null) return rebuild(plot, current, origin, now, centerDistanceSq(origin, position) <= square(WATCH_DISTANCE))
        clear()
        hudLines = listOf("§aGreenhouse${hudLabel(plot)} §7· no plan", "§7/sm greenhouse auto §8or §7import")
    }

    @Subscription
    fun onBlockChange(event: BlockChangeEvent) {
        val origin = plot?.anchor ?: return
        if (abs(event.pos.y - origin.pos.y) <= 2 && event.pos.distSqr(origin.pos) <= square(GREENHOUSE_SIZE * 2)) dirty = true
    }

    private fun heldItemId(stack: ItemStack): String? = if (stack.isEmpty) null
        else stack.getData(DataTypes.ID) ?: BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase()

    private fun cellPos(origin: Anchor, row: Int, col: Int): BlockPos = origin.pos.relative(origin.facing, row).relative(origin.facing.clockWise, col)

    /** The plot cell holding [pos], or null outside the plot's 10×10 area. */
    private fun cellOf(origin: Anchor, pos: BlockPos): Int? {
        val dx = pos.x - origin.pos.x; val dz = pos.z - origin.pos.z
        val right = origin.facing.clockWise
        val row = dx * origin.facing.stepX + dz * origin.facing.stepZ
        val col = dx * right.stepX + dz * right.stepZ
        return if (row in 0 until GREENHOUSE_SIZE && col in 0 until GREENHOUSE_SIZE && abs(pos.y - origin.pos.y) <= 2) row * GREENHOUSE_SIZE + col else null
    }

    private fun centerDistanceSq(origin: Anchor, pos: BlockPos): Double {
        val half = (GREENHOUSE_SIZE - 1) / 2.0
        val right = origin.facing.clockWise
        val x = origin.pos.x + (origin.facing.stepX + right.stepX) * half - pos.x
        val z = origin.pos.z + (origin.facing.stepZ + right.stepZ) * half - pos.z
        val y = (origin.pos.y - pos.y).toDouble()
        return x * x + y * y + z * z
    }

    /**
     * Scans the plot: ghosts, HUD and the plot's status. With a recent previous scan and the player watching, it also
     * learns unrecognised crops as they are planted from the held item, times mutations from when they appear, and
     * records a harvest when one disappears.
     */
    private fun rebuild(plot: Plot, current: Layout, origin: Anchor, now: Long, watching: Boolean) {
        val catalog = catalog ?: return
        val level = MC.instance.level ?: return
        if (!loaded(level, origin)) return
        val held = heldId
        val heldSoils = held?.let(::soilsForItem).orEmpty()
        val allSoil = GreenhouseConfig.alwaysShowSoilGhosts
        val alpha = GreenhouseConfig.opacity
        val previous = plot.previous?.takeIf { watching && now - plot.previousAt <= CONTINUITY_MS }
        val occupiedNow = BooleanArray(GREENHOUSE_CELLS)
        val status = CharArray(GREENHOUSE_CELLS) { CellStatus.UNUSED.code }
        val shown = ArrayList<Ghost>()
        var done = 0
        var changed = false
        val plants = LinkedHashMap<String, Int>()
        val soils = LinkedHashMap<Soil, Int>()
        for (placement in current.placements) {
            val plant = catalog[placement.plantId] ?: continue
            val soil = current.soils.getValue(placement)
            val ingredient = placement.role == Role.INGREDIENT
            var planted = false
            var wasOccupied = false
            for (r in 0 until placement.size) for (c in 0 until placement.size) {
                val cell = (placement.row + r) * GREENHOUSE_SIZE + placement.col + c
                val soilPos = cellPos(origin, placement.row + r, placement.col + c)
                val soilState = level.getBlockState(soilPos)
                val soilOk = plant.soils.any { soilState.`is`(soilBlock(it)) }
                val fingerprint = GreenhouseWorld.fingerprint(level, soilPos.above())
                val occupied = fingerprint != null
                occupiedNow[cell] = occupied
                if (previous?.get(cell) == true) wasOccupied = true
                var identified = fingerprint?.let { GreenhouseWorld.plantFor(it, catalog, learned) }
                if (ingredient && fingerprint != null && identified == null && previous?.get(cell) == false && held != null && held in plant.itemIds) {
                    learn(fingerprint, plant.id); identified = plant.id
                }
                val wrongCrop = ingredient && identified != null && identified != plant.id
                if (occupied && !wrongCrop) planted = true
                if (!soilOk) {
                    soils.merge(soil, 1, Int::plus)
                    if (allSoil || soil in heldSoils) shown += Ghost(AABB(soilPos).inflate(0.004), HudTheme.alpha(soilColor(soil), alpha))
                }
                if (held != null && ingredient && held in plant.itemIds && !occupied)
                    shown += Ghost(AABB(soilPos.above()).deflate(0.12), HudTheme.alpha(plantColor(plant.id), alpha))
                if (soilOk && (!ingredient || occupied && !wrongCrop)) done++
                status[cell] = when {
                    !soilOk -> CellStatus.WRONG_SOIL
                    !ingredient -> if (occupied) CellStatus.TARGET_GROWING else CellStatus.TARGET_WAITING
                    wrongCrop -> CellStatus.WRONG_CROP
                    occupied -> CellStatus.DONE
                    else -> CellStatus.MISSING_CROP
                }.code
            }
            if (ingredient && !planted) plants.merge(plant.id, 1, Int::plus)
            if (!ingredient) {
                val key = placement.row * GREENHOUSE_SIZE + placement.col
                if (previous != null && planted && !wasOccupied) { plot.spawned[key] = now; changed = true }
                if (previous != null && !planted && wasOccupied) { recordHarvest(plot, current, placement, now); changed = true }
                if (!planted && plot.spawned.remove(key) != null) changed = true
            }
        }
        val complete = plants.isEmpty()
        if (complete && previous != null && !plot.previousComplete) { plot.plantedAt = now; changed = true }
        if (!complete && plot.plantedAt != 0L) { plot.plantedAt = 0; changed = true }
        plot.previous = occupiedNow; plot.previousAt = now; plot.previousComplete = complete
        val text = String(status)
        if (text != plot.status || plants != plot.missingPlants || soils != plot.missingSoil) changed = true
        plot.status = text; plot.scannedAt = now; plot.missingPlants = plants; plot.missingSoil = soils
        if (changed) { revision++; save() }
        ghosts = shown
        val cells = current.placements.sumOf { it.size * it.size }
        val target = current.targets.firstOrNull()?.let { catalog[it.plantId]?.name } ?: "?"
        val holdingMissingCrop = held != null && plants.keys.any { held in catalog[it]!!.itemIds }
        val requirements = plants.entries.sortedByDescending { it.value }.map { "§7${catalog[it.key]?.name} §f×${it.value}" } +
            soils.entries.sortedByDescending { it.value }.map { "§7${soilName(it.key)} soil §f×${it.value}" }
        val limit = GreenhouseConfig.maxDisplayedRequirements
        hudLines = buildList {
            add("§aGreenhouse${hudLabel(plot)} §7· $target ×${current.targets.size}")
            add("§f${rate(estimate(current))} §7est. · ${if (cells == 0) 0 else done * 100 / cells}% built")
            if (plants.isNotEmpty() && heldSoils.isEmpty() && !holdingMissingCrop) add("§eHold missing crop to preview spots")
            requirements.take(limit).forEach(::add)
            if (requirements.size > limit) add("§7+${requirements.size - limit} more · /sm greenhouse missing")
            add("§8${if (current.inputsHash.startsWith(IMPORTED)) "imported" else "solved"} ${age(now - current.generatedAt)} ago · prices ${if (pricesObservedAt > 0) age(now - pricesObservedAt) + " old" else "pending"}")
        }
    }

    /** Values a harvested mutation at today's prices, less its share of the layout's ingredients. Estimated. */
    private fun recordHarvest(plot: Plot, layout: Layout, placement: Placement, now: Long) {
        val catalog = catalog ?: return
        val plant = catalog[placement.plantId] ?: return
        val revenue = revenuePrice(prices[plant.bazaarId], GreenhouseConfig.priceMode) ?: return
        val cost = layout.placements.filter { it.role == Role.INGREDIENT }.sumOf { catalog[it.plantId]?.let { p -> costPrice(p, prices) } ?: 0.0 } / layout.targets.size.coerceAtLeast(1)
        history += Harvest(now, plot.name, plant.id, revenue, cost)
        while (history.size > MAX_HARVESTS) history.removeAt(0)
        historyWriter.submit(JsonObject().apply { addProperty("version", 1); add("harvests", JsonArray().apply { history.forEach { add(it.toJson()) } }) })
    }

    @Subscription
    fun render(event: RenderWorldEvent.AfterTranslucent) {
        val shown = ghosts
        if (shown.isEmpty()) return
        val limit = square(GreenhouseConfig.renderDistance).toDouble()
        MC.instance.levelRenderer.collectPerFrameGizmos().use {
            for (ghost in shown) if (ghost.box.center.distanceToSqr(event.cameraPosition) <= limit) Gizmos.cuboid(ghost.box, GizmoStyle.fill(ghost.color))
        }
    }

    // ---- Persistence --------------------------------------------------------------------------------------------

    private fun save() {
        val json = JsonObject().apply {
            addProperty("version", 2)
            add("plots", JsonArray().apply {
                plots.forEach { plot ->
                    val anchor = plot.anchor?.let { PlotAnchor(it.pos.x, it.pos.y, it.pos.z, it.facing.name) }
                    add(PlotState(plot.name, anchor, plot.locked.indices.filter { plot.locked[it] }.toSet(), plot.target, plot.layout,
                        plot.plantedAt, HashMap(plot.spawned), plot.status, plot.scannedAt, plot.missingPlants, plot.missingSoil).toJson())
                }
            })
            add("prices", JsonObject().apply {
                addProperty("observedAt", pricesObservedAt)
                prices.forEach { (id, quote) -> add(id, JsonArray().apply { add(quote.buyOrder); add(quote.sellOffer) }) }
            })
            add("layouts", JsonArray().apply { layouts.values.forEach { add(it.toJson()) } })
            add("estimates", JsonObject().apply { estimates.forEach { (hash, e) -> add(hash, JsonArray().apply { add(e.count); add(e.profitPerHour) }) } })
            add("fingerprints", JsonObject().apply { learned.forEach { (fingerprint, id) -> addProperty(fingerprint, id) } })
        }
        writer.submit(json)
    }

    /** Each section is read independently, so one damaged section only loses itself. */
    private fun restore(json: JsonObject) {
        val catalog = catalog ?: return
        runCatching {
            val section = json.getAsJsonObject("prices")
            pricesObservedAt = section["observedAt"].asLong
            prices = section.entrySet().filter { it.key != "observedAt" }.associate { (id, value: JsonElement) ->
                val pair = value.asJsonArray
                id to Quote(pair[0].asDouble, pair[1].asDouble).also { require(it.buyOrder > 0 && it.sellOffer > 0) }
            }
        }
        runCatching { json.getAsJsonArray("layouts").forEach { element -> runCatching { layoutFromJson(element.asJsonObject, catalog) }.getOrNull()?.let { layouts[it.inputsHash] = it } } }
        runCatching { json.getAsJsonObject("estimates").entrySet().forEach { (hash, value) -> runCatching {
            val pair = value.asJsonArray
            estimates[hash] = PlotEstimate(pair[0].asInt.also { require(it in 0..GREENHOUSE_CELLS) }, pair[1].asDouble.also { require(it.isFinite()) })
        } } }
        runCatching { json.getAsJsonObject("fingerprints").entrySet().forEach { (fingerprint, value) ->
            runCatching { value.asString }.getOrNull()?.takeIf { catalog[it] != null && fingerprint.length <= 256 }?.let { learned[fingerprint] = it }
        } }
        runCatching { plotsFromJson(json, catalog) }.onFailure { SkyMyce.logger.warn("Greenhouse plots could not be read", it) }.getOrNull()?.forEach { state ->
            plots += Plot(state.name).apply {
                anchor = state.anchor?.let { Anchor(BlockPos(it.x, it.y, it.z), Direction.valueOf(it.facing)) }
                state.locked.forEach { locked[it] = true }
                target = state.target
                layout = state.layout
                plantedAt = state.plantedAt; spawned.putAll(state.spawned)
                status = state.status; scannedAt = state.scannedAt
                missingPlants = state.missingPlants; missingSoil = state.missingSoil
            }
        }
    }

    private fun restoreHistory(json: JsonObject) {
        val catalog = catalog ?: return
        runCatching { json.getAsJsonArray("harvests").mapNotNullTo(history) { harvestFromJson(it, catalog) } }
        history.sortBy { it.at }
        while (history.size > MAX_HARVESTS) history.removeAt(0)
    }

    // ---- Formatting ---------------------------------------------------------------------------------------------

    private fun square(value: Int) = value * value
    internal fun signed(value: Double) = (if (value >= 0) "+" else "-") + formatCoins(abs(value))
    private fun rate(perHour: Double) = if (GreenhouseConfig.profitMetric == ProfitMetric.PER_DAY) "${signed(perHour * 24.0)}/d" else "${signed(perHour)}/h"
    private fun metricText(perHour: Double, cycleHours: Double) = when (GreenhouseConfig.profitMetric) {
        ProfitMetric.PER_HOUR -> "${signed(perHour)}/h"
        ProfitMetric.PER_DAY -> "${signed(perHour * 24.0)}/d"
        ProfitMetric.PER_CYCLE -> "${signed(perHour * cycleHours)}/cycle"
    }
    private fun chatLabel(plot: Plot) = if (plots.size > 1) " (plot ${plot.name})" else ""
    private fun hudLabel(plot: Plot) = if (plots.size > 1) " §f${plot.name}" else ""
    internal fun age(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return when { minutes < 1 -> "<1m"; minutes < 120 -> "${minutes}m"; minutes < 2880 -> "${minutes / 60}h ${minutes % 60}m"; else -> "${minutes / 1440}d ${minutes / 60 % 24}h" }
    }

    private fun soilBlock(soil: Soil): Block = when (soil) {
        Soil.FARMLAND -> Blocks.FARMLAND; Soil.DIRT -> Blocks.DIRT; Soil.SAND -> Blocks.SAND
        Soil.SOUL_SAND -> Blocks.SOUL_SAND; Soil.MYCELIUM -> Blocks.MYCELIUM; Soil.END_STONE -> Blocks.END_STONE
    }

    /** Farmland is placed as dirt. SkyBlock keeps legacy ids for some blocks (MYCEL, ENDER_STONE). */
    private fun soilsForItem(id: String): Set<Soil> = when (id) {
        "DIRT" -> setOf(Soil.DIRT, Soil.FARMLAND)
        "SAND" -> setOf(Soil.SAND)
        "SOUL_SAND" -> setOf(Soil.SOUL_SAND)
        "MYCEL", "MYCELIUM" -> setOf(Soil.MYCELIUM)
        "ENDER_STONE", "END_STONE" -> setOf(Soil.END_STONE)
        else -> emptySet()
    }

    /** The SkyBlock item id /gfs takes for each soil, with the same legacy ids as [soilsForItem]. */
    private fun sackId(soil: Soil) = when (soil) {
        Soil.FARMLAND, Soil.DIRT -> "dirt"; Soil.SAND -> "sand"; Soil.SOUL_SAND -> "soul_sand"
        Soil.MYCELIUM -> "mycel"; Soil.END_STONE -> "ender_stone"
    }

    internal fun soilName(soil: Soil) = soil.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    internal fun soilColor(soil: Soil) = when (soil) {
        Soil.FARMLAND, Soil.DIRT -> 0x8B5A2B; Soil.SAND -> 0xE8D9A0; Soil.SOUL_SAND -> 0x5A4030
        Soil.MYCELIUM -> 0x8E6FA8; Soil.END_STONE -> 0xE6E8B0
    }

    /** Stable hue per plant, spread by catalog position (golden ratio) so crops in one layout are told apart. */
    internal fun plantColor(id: String): Int {
        val index = catalog?.plants?.indexOfFirst { it.id == id }?.takeIf { it >= 0 } ?: (id.hashCode() and 0xFFFF)
        return java.awt.Color.HSBtoRGB((index * 0.618034f) % 1f, 0.65f, 0.95f) and 0xFFFFFF
    }

}
