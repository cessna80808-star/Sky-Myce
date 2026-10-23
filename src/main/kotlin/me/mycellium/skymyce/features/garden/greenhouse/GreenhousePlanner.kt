package me.mycellium.skymyce.features.garden.greenhouse

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.mycellium.mixin.BazaarAssistSignAccessor
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GreenhouseConfig
import me.mycellium.skymyce.features.general.pricehistory.formatCoins
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcChatRendering
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
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
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
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.info.TabListChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.level.RightClickEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.level.BlockChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderWorldEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.text.TextProperties.stripped
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
    private const val ANALYSIS_WINDOW = 5_000L
    /** How long after opening the Bazaar for a crop its amount waits for the amount sign. */
    private const val AMOUNT_WINDOW = 120_000L

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
        /** Block looks named on this plot; see [PlotState.learned]. */
        val learned = LinkedHashMap<String, String>()
        /** Placement (top-left cell) → its last Plant Diagnostics Tool reading. */
        val growth = HashMap<Int, GrowthReading>()
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
    /** Head, item and entity looks → plant id, learned while planting or from /sm greenhouse identify. Block looks are per plot. */
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
        // Crops are mostly armor stands, so analysing one is an entity interaction; a block crop is a block interaction.
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (level.isClientSide) { startAnalysis(player.getItemInHand(hand), entity); noteWake(player.getItemInHand(hand), entity) }
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (!level.isClientSide) return@register InteractionResult.PASS
            startAnalysis(player.getItemInHand(hand), null)
            noteWake(player.getItemInHand(hand), null)
            // FAIL on the client stops the click before it is sent.
            if (wrongClick(player.getItemInHand(hand), hit.blockPos)) InteractionResult.FAIL else InteractionResult.PASS
        }
        Scheduling.schedule(Duration.ZERO) {
            val json = file.read()?.takeIf { it.isJsonObject }?.asJsonObject
            val harvests = historyFile.read()?.takeIf { it.isJsonObject }?.asJsonObject
            MC.instance.execute { json?.let(::restore); harvests?.let(::restoreHistory); loaded = true; dirty = true; revision++ }
        }
    }

    /** Settings for [plot]: the unique-crop count comes from the player's other saved plots unless the setting is higher. */
    internal fun settings(plot: Plot? = null) = GreenhouseSettings(GreenhouseConfig.upgradeLevel,
        maxOf(GreenhouseConfig.uniqueCrops, uniqueElsewhere(plot)), GreenhouseConfig.priceMode, gardenLevel() ?: 15)

    /** The Garden level from the setting, else as last read from the tab list; null while unknown. */
    internal fun gardenLevel(): Int? = GreenhouseConfig.gardenLevel.takeIf { it > 0 } ?: detectedGardenLevel

    private var detectedGardenLevel: Int? = null
    private var tabLogged = false

    @Subscription
    fun onTabList(event: TabListChangeEvent) {
        if (!active() || LocationAPI.island != SkyBlockIsland.GARDEN) return
        val lines = event.new.flatten().map { it.stripped }
        val level = lines.firstNotNullOfOrNull(::parseGardenLevel)
        if (level == null) {
            // Logged once a session so the line format can be checked if the level is never found.
            if (!tabLogged && detectedGardenLevel == null) SkyMyce.logger.info("Greenhouse: no Garden level in the tab list: {}", lines.filter { it.contains("garden", true) })
            tabLogged = true
            return
        }
        if (level != detectedGardenLevel) {
            SkyMyce.logger.info("Greenhouse: Garden level {} from the tab list", level)
            detectedGardenLevel = level
            save(); dirty = true; revision++
        }
        dropLocked()
    }

    /** The server refusing a crop ("You need Garden Level 12 to place Wild Rose!") caps the level below that. */
    @Subscription
    fun onGardenLevelChat(event: ChatReceivedEvent.Pre) {
        if (!active()) return
        val needed = GARDEN_LEVEL_REFUSAL.find(event.text)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if ((gardenLevel() ?: 15) >= needed) {
            detectedGardenLevel = needed - 1
            save(); dirty = true; revision++
        }
        dropLocked()
    }

    private val GARDEN_LEVEL_REFUSAL = Regex("You need Garden Level (\\d{1,2}) to place .+!")

    /**
     * Removes base crops the Garden level cannot plant from pinned layouts (a layout solved before the level was known
     * can hold a Wild Rose as a unique-crop filler), leaving the rest of the layout as it is.
     */
    private fun dropLocked() {
        val catalog = catalog ?: return
        val level = gardenLevel() ?: return
        var changed = false
        for (plot in plots) {
            val layout = plot.layout ?: continue
            val locked = layout.placements.filter { it.role == Role.INGREDIENT && (catalog[it.plantId]?.gardenLevel ?: 0) > level }.toSet()
            if (locked.isEmpty()) continue
            val kept = layout.placements - locked
            plot.layout = layout.copy(placements = kept, soils = layout.soils - locked,
                uniqueCrops = kept.filter { catalog[it.plantId]?.unique == true }.map { it.plantId }.toSet().size)
            val names = locked.groupingBy { catalog[it.plantId]?.name ?: it.plantId }.eachCount().entries.joinToString { (name, count) -> "$count $name" }
            displayModMessage("§7Removed $names from plot ${plot.name}: your Garden level ($level) cannot plant ${if (locked.size == 1) "it" else "them"} yet")
            changed = true
        }
        if (changed) { dirty = true; revision++; save() }
    }

    /**
     * Unique base crops planned on the player's other plots, which count toward this plot's growth bonus.
     * shortcut: combined with this plot's own unique crops by maximum, not union, as the solver takes a count; upgrade if
     * plots often grow different unique crops.
     */
    internal fun uniqueElsewhere(plot: Plot?): Int = plots.asSequence().filter { it !== plot }
        .flatMap { it.layout?.placements.orEmpty().asSequence() }
        .filter { it.role == Role.INGREDIENT && catalog?.get(it.plantId)?.unique == true }.map { it.plantId }.toSet().size

    private fun owner(layout: Layout) = plots.firstOrNull { it.layout === layout }

    private fun allowed(plant: Plant) = plant.rarity.ordinal <= GreenhouseConfig.maxTier.ordinal

    internal fun ranking(plot: Plot? = null): List<RankedMutation> {
        val catalog = catalog ?: return emptyList()
        val ranked = rankMutations(catalog, prices, settings(plot), ::allowed)
        return if (GreenhouseConfig.profitMetric == ProfitMetric.PER_CYCLE) ranked.sortedByDescending { it.profitPerCycle } else ranked
    }

    internal fun selected(): Plot = plot ?: (plots.firstOrNull() ?: Plot("1").also(plots::add)).also { plot = it }

    /** A plot nobody anchored yet, for ranking a new plot without touching the saved ones. */
    private val unanchored = Plot("new")

    /**
     * The plot a layout-changing command acts on: the anchored plot the player stands on (or the only plot before any is
     * anchored). Never the nearest one, so working on a new plot cannot overwrite a finished one.
     */
    private fun plotHere(): Plot? {
        val pos = MC.instance.player?.blockPosition() ?: return null
        plots.firstOrNull { p -> p.anchor?.let { standsOn(it, pos) } == true }?.let { return it }
        return if (plots.none { it.anchor != null }) selected() else null
    }

    private fun plotHereOrExplain(): Plot? = plotHere() ?: null.also {
        displayModMessage("§eStand on the plot you want to change. A new plot needs anchoring first: look at its corner soil and run /sm greenhouse anchor")
    }

    /** On the plot or its one-block edge, at most a few blocks above it. */
    private fun standsOn(origin: Anchor, pos: BlockPos): Boolean {
        val dx = pos.x - origin.pos.x; val dz = pos.z - origin.pos.z
        val right = origin.facing.clockWise
        val row = dx * origin.facing.stepX + dz * origin.facing.stepZ
        val col = dx * right.stepX + dz * right.stepZ
        return row in -1..GREENHOUSE_SIZE && col in -1..GREENHOUSE_SIZE && pos.y - origin.pos.y in -2..6
    }

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
                        // A side with no orders can arrive as 0; it is missing, not free (Scourroot often has no sell offers).
                        val buy = product.buyOrderPrice?.takeIf { it > 0 }; val sell = product.sellOfferPrice?.takeIf { it > 0 }
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
                    (error as? BazaarMarketFailure)?.retryAt?.let { nextPoll = maxOf(nextPoll, it) }
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
        val target = plot.target ?: plotRanking(plot).firstOrNull()?.entry?.plant?.id ?: return displayModMessage("§cNo mutation up to your highest tier and Garden level has Bazaar prices")
        val name = catalog[target]?.name ?: target
        catalog[target]?.let { gardenLevelFor(catalog, it) }?.takeIf { it > settings(plot).gardenLevel }?.let {
            return displayModMessage("§c$name needs Garden level $it for its crops; yours is ${gardenLevel()}")
        }
        val unlocked = unlocked(plot)
        val settings = settings(plot)
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
        // Readings stay for crops still at the same place, so re-reading a plot after naming a crop keeps them.
        plot.growth.keys.retainAll(layout.placements.map { it.row * GREENHOUSE_SIZE + it.col }.toSet())
        plot.layout = layout; plot.spawned.clear(); plot.plantedAt = 0; plot.previous = null; plot.status = ""; plot.scannedAt = 0
        plot.missingPlants = emptyMap(); plot.missingSoil = emptyMap()
        dirty = true; revision++; save()
    }

    /** The pinned layout's profit at current prices; the solve-time figure when a price is missing. */
    internal fun estimate(layout: Layout) = catalog?.let { layout.profitPerHour(it, prices, settings(owner(layout))) } ?: layout.totalProfitPerHour

    /** Hours from a target appearing to its harvest, from the site's timer; null without a target. */
    internal fun cycleHours(layout: Layout): Double? {
        val plant = layout.targets.firstOrNull()?.let { catalog?.get(it.plantId) } ?: return null
        return cycleHours(plant, GreenhouseConfig.upgradeLevel, maxOf(settings(owner(layout)).uniqueCrops, layout.uniqueCrops))
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
        val unlocked = unlocked(plot); val settings = settings(plot)
        return ranking(plot).map { entry ->
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
        val unlocked = unlocked(plot); val settings = settings(plot); val quotes = prices
        val queue = ArrayDeque(ranking(plot).take(ESTIMATED).map { it.plant.id }.filter { estimates[inputsHash(unlocked, listOf(it), settings, catalog, quotes)] == null })
        if (queue.isEmpty()) return done()
        estimating = true
        displayModMessage("§7Estimating how many of the top ${queue.size} mutations fit on ${if (plot === unanchored) "an empty plot" else "plot ${plot.name}"}…")
        fun next() {
            val id = queue.removeFirstOrNull()
            if (id == null) { estimating = false; save(); done(); return }
            val hash = inputsHash(unlocked, listOf(id), settings, catalog, quotes)
            worker.execute {
                val layout = runCatching { GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(id), ESTIMATE_ITERATIONS).solve(hash.hashCode().toLong(), 0, hash) }
                    .onFailure { SkyMyce.logger.warn("Greenhouse estimate for {} could not be solved", id, it) }.getOrNull()
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
        .then(literal("recompute").executes { run { plotHereOrExplain()?.let { plan(it, force = true) } } })
        .then(literal("import").executes { run { plotHereOrExplain()?.let { importPlot(it, null) } } }
            .then(argument("mutation", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.filter { it.solvable }?.map { it.id }.orEmpty(), builder) }
                .executes { context -> val id = StringArgumentType.getString(context, "mutation").uppercase(); run { plotHereOrExplain()?.let { importPlot(it, id) } } }))
        .then(literal("identify").executes { run { identify(null) } }.then(argument("crop", StringArgumentType.word())
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
        .then(literal("missing").executes { run { showMissing(plotHere() ?: selected()) } })
        .then(literal("shopping").executes { run { showMissing(plotHere() ?: selected()) } })
        .then(literal("get").then(argument("plot", StringArgumentType.word()).then(argument("item", StringArgumentType.word()).executes { context ->
            val plot = StringArgumentType.getString(context, "plot"); val item = StringArgumentType.getString(context, "item").uppercase()
            run { get(plot, item) }
        })))

    /**
     * Tab completion for the server's /bz and /gfs. The nodes have no executor, so Fabric passes the typed command to the
     * server unchanged; the client only adds the selected plot's missing crops and soils to the suggestions.
     */
    fun serverCommandHints(): List<LiteralArgumentBuilder<FabricClientCommandSource>> = listOf(
        literal("bz").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(if (active()) plot?.missingPlants?.keys?.mapNotNull { catalog?.get(it) }?.filter { sackFetch(it, 1) == null }?.map { it.name }.orEmpty() else emptyList(), builder)
        }),
        literal("gfs").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            val plot = plot?.takeIf { active() }
            SharedSuggestionProvider.suggest(plot?.missingSoil?.map { (soil, count) -> "${sackId(soil)} $count" }.orEmpty() +
                plot?.missingPlants?.mapNotNull { (id, count) -> catalog?.get(id)?.let { sackFetch(it, count) }?.let { (item, amount) -> "$item $amount" } }.orEmpty(), builder)
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
            "(Plant Diagnostics Tool)" to "Analyze a few mutations: the planner estimates how far the whole plot has grown",
            "identify [crop]" to "Look at a planted crop to see what the planner reads, or name it; the plot is read again",
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
        // Off any anchored plot, rank for an empty unlocked plot rather than the nearest saved one.
        val plot = plotHere() ?: unanchored
        if (estimating) return displayModMessage("§7Still estimating whole-plot profit; the ranking follows when it is done")
        val now = System.currentTimeMillis()
        pollPrices(now)
        if (ranking(plot).take(ESTIMATED).any { estimates[inputsHash(unlocked(plot), listOf(it.plant.id), settings(plot), catalog, prices)] == null })
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
            plot === unanchored -> "§7Counts are for an empty plot. Anchor the plot you are on before planning it"
            current == null -> "§7No layout yet${chatLabel(plot)}. Click [Plan], run /sm greenhouse auto, or import what is planted"
            else -> "§7Current plan${chatLabel(plot)}: §f$target ×${current.targets.size} §a${rate(estimate(current))} §7· ${if (current.inputsHash.startsWith(IMPORTED)) "imported" else "solved"} ${age(now - current.generatedAt)} ago"
        })
        if (plot.anchor == null) displayModMessage("§eLook at the soil of the corner slot and run /sm greenhouse anchor to place the overlay")
        displayModMessage("§8Estimates only, using skymutations.eu's formula (by palmaner); plot counts come from a quick solve. /sm greenhouse help lists every command")
    }

    private fun choose(id: String?) {
        val plant = id?.let { catalog?.get(it) }
        if (id != null && plant?.solvable != true) return displayModMessage("§cUnknown or unsupported mutation: $id")
        val plot = plotHereOrExplain() ?: return
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
                ?: Plot(gardenPlotAt(pos.x, pos.z)?.toString()?.takeIf { n -> plots.none { it.name == n } }
                    ?: generateSequence(1) { it + 1 }.map(Int::toString).first { n -> plots.none { it.name == n } }).also(plots::add)
        plot.anchor = Anchor(pos, facing); plot.previous = null
        this.plot = plot; dirty = true; revision++; save()
        displayModMessage("§aGreenhouse plot §f${plot.name}§a anchored at ${pos.x} ${pos.y} ${pos.z}: §7rows run ${facing.name.lowercase()}, columns to your right")
        if (plot.layout == null && !importPlot(plot, null, quiet = true)) displayModMessage("§7Plan it with /sm greenhouse auto, or click [Plan] in /sm greenhouse")
    }

    /** The Garden plot (for /tptoplot) a greenhouse is on, from its anchor. */
    internal fun gardenPlot(plot: Plot): Int? = plot.anchor?.let { gardenPlotAt(it.pos.x, it.pos.z) }

    internal fun teleport(plot: Plot) {
        val number = gardenPlot(plot) ?: return displayModMessage("§cPlot ${plot.name} has no anchor, so its Garden plot is unknown")
        MC.player.connection.sendCommand("tptoplot $number")
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
        val plot = plotHereOrExplain() ?: return
        val cell = row * GREENHOUSE_SIZE + column
        plot.locked[cell] = !plot.locked[cell]
        save()
        displayModMessage("§7Cell ${row + 1},${column + 1} is now ${if (plot.locked[cell]) "§clocked" else "§aunlocked"}§7. ${plot.locked.count { !it }} cells usable" +
            if (plot.layout != null) "; run /sm greenhouse recompute to re-plan around it" else "")
    }

    /** Base crops kept in sacks, so they are fetched with /gfs instead of bought. */
    private val SACK_CROPS = setOf("CARROT", "POTATO", "CACTUS", "SUGAR_CANE", "WHEAT_SEEDS", "RED_MUSHROOM", "BROWN_MUSHROOM", "NETHER_WART", "SUNFLOWER", "WILD_ROSE")

    /** Seeds no one sells: taken from sacks as the crop they are crafted from, named here. */
    private val CRAFTED_SEEDS = mapOf("MELON_SEEDS" to "Melon Slice", "PUMPKIN_SEEDS" to "Pumpkin")

    /** What /gfs takes for [count] of [plant]: the crop itself, or the item its seeds are crafted from. Null when bought. */
    private fun sackFetch(plant: Plant, count: Int): Pair<String, Int>? = when (plant.id) {
        in CRAFTED_SEEDS -> plant.bazaarId.lowercase() to (count + plant.perItem - 1) / plant.perItem
        in SACK_CROPS -> plant.itemIds.first().lowercase() to count
        else -> null
    }

    /** What a plot still needs, one line per source; each item is a button that fetches or buys exactly what is missing. */
    internal fun showMissing(plot: Plot) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (plot.layout == null) return displayModMessage("§7No layout yet${chatLabel(plot)}. Run /sm greenhouse auto or plan <mutation>")
        if (plot.scannedAt == 0L) return displayModMessage("§7Stand near Greenhouse plot ${plot.name} in the Garden to check what is missing")
        val stale = if (plot !== this.plot || hudLines == null) " §8(as of ${age(System.currentTimeMillis() - plot.scannedAt)} ago)" else ""
        if (plot.missingPlants.isEmpty() && plot.missingSoil.isEmpty()) return displayModMessage("§aPlot ${plot.name} has everything it needs$stale")
        val crops = plot.missingPlants.entries.mapNotNull { (id, count) -> catalog[id]?.let { it to count } }.sortedByDescending { it.second }
        val crafted = crops.filter { it.first.id in CRAFTED_SEEDS }
        val (sacked, bought) = (crops - crafted.toSet()).partition { sackFetch(it.first, it.second) != null }
        val cropCount = crops.sumOf { it.second }; val soilCount = plot.missingSoil.values.sum()
        displayModMessage("§aPlot ${plot.name} still needs " + listOfNotNull(
            "§f$cropCount§a crop${if (cropCount == 1) "" else "s"}".takeIf { cropCount > 0 },
            "§f$soilCount§a soil".takeIf { soilCount > 0 }).joinToString(" and ") + "$stale §7· click an item to get it")
        fun line(label: String, items: List<Triple<String, Int, String>>, hint: (String, Int) -> String) {
            if (items.isEmpty()) return
            val text = Component.literal("§7$label ")
            items.forEach { (name, count, key) ->
                text.append(Component.literal("§8[§f$count §7$name§8] ").withStyle {
                    it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse get ${plot.name} $key")).withHoverEvent(HoverEvent.ShowText(Component.literal(hint(name, count))))
                })
            }
            displayModMessage(text)
        }
        line("Sacks", sacked.map { (plant, count) -> Triple(plant.name, count, plant.id) }) { name, count -> "Take $count $name from your sacks (/gfs)" }
        line("Craft", crafted.map { (plant, count) ->
            val (_, amount) = sackFetch(plant, count)!!
            Triple("${CRAFTED_SEEDS.getValue(plant.id)} §8→ §7$count ${plant.name}", amount, plant.id)
        }) { name, count -> "Take $count ${name.substringBefore(" §8")} from your sacks (/gfs), then craft them into seeds" }
        line("Bazaar", bought.map { (plant, count) -> Triple(plant.name, count, plant.id) }) { name, count -> "Open $name in the Bazaar and copy $count for the buy sign" }
        line("Soil", plot.missingSoil.entries.sortedByDescending { it.value }.map { (soil, count) -> Triple(soilName(soil), count, "SOIL_${soil.name}") }) { name, count ->
            "Take $count $name from your sacks (/gfs)"
        }
    }

    private var copiedAt = 0L
    /** An amount to type into the next Bazaar amount sign, with when the offer lapses; set by a Bazaar shopping-list click or /bz. */
    private var pendingAmount: Pair<Int, Long>? = null
    private var filledSign = 0

    /**
     * Types the pending amount into the first line of the next "Enter amount" Bazaar sign, once, if that line is still
     * empty; the player checks it and clicks Done. Nothing is sent.
     */
    private fun fillAmountSign(now: Long) {
        val (amount, until) = pendingAmount ?: return
        if (now > until) { pendingAmount = null; return }
        val sign = MC.screen as? AbstractSignEditScreen ?: return
        val id = System.identityHashCode(sign)
        if (filledSign == id) return
        val accessor = sign as BazaarAssistSignAccessor
        val lines = accessor.`skymyce$getMessages`()?.map { it.orEmpty() }.orEmpty()
        if (!isBazaarAmountSign(lines)) return
        filledSign = id; pendingAmount = null
        if (accessor.`skymyce$getLine`() != 0 || lines.first().isNotBlank()) return
        val field = accessor.`skymyce$getSignField`() ?: return
        field.selectAll()
        field.insertText(amount.toString())
    }

    /** One shopping-list click: /gfs for sack crops and soil, or /bz with the amount copied. Sends that one command. */
    private fun get(plotName: String, key: String) {
        val catalog = catalog ?: return
        val plot = plots.firstOrNull { it.name.equals(plotName, true) } ?: return displayModMessage("§cNo Greenhouse plot named $plotName")
        if (key.startsWith("SOIL_")) {
            val soil = runCatching { Soil.valueOf(key.removePrefix("SOIL_")) }.getOrNull() ?: return
            val count = plot.missingSoil[soil] ?: return displayModMessage("§7Plot ${plot.name} no longer needs ${soilName(soil)}")
            markClicked(plot.name, key)
            return fetch("${sackId(soil)} $count")
        }
        val plant = catalog[key] ?: return displayModMessage("§cUnknown crop: $key")
        val count = plot.missingPlants[plant.id] ?: return displayModMessage("§7Plot ${plot.name} no longer needs ${plant.name}")
        markClicked(plot.name, key)
        sackFetch(plant, count)?.let { (item, amount) -> return fetch("$item $amount") }
        MC.instance.keyboardHandler.clipboard = count.toString()
        copiedAt = System.currentTimeMillis()
        pendingAmount = count to copiedAt + AMOUNT_WINDOW
        displayModMessage("§7Opening ${plant.name}: §f$count§7 goes into the amount sign for you (also copied)")
        MC.player.connection.sendCommand("bz ${plant.name}")
    }

    /** Turns a clicked shopping-list button green with a tick, in every list still in chat; it stays clickable. */
    private fun markClicked(plotName: String, key: String) {
        val command = "/sm greenhouse get $plotName $key"
        IrcChatRendering.rewriteChat { message ->
            markButton(message.content(), command)?.let { GuiMessage(message.addedTime(), it, message.signature(), message.source(), message.tag()) } ?: message
        }
    }

    private fun markButton(component: Component, command: String): Component? {
        if ((component.style.clickEvent as? ClickEvent.RunCommand)?.command() == command) {
            val label = component.string.replace(Regex("§."), "").trim().removeSurrounding("[", "]").removePrefix("✔ ")
            return Component.literal("§8[§a✔ $label§8] ").setStyle(component.style)
        }
        val siblings = component.siblings.map { markButton(it, command) }
        if (siblings.all { it == null }) return null
        return component.plainCopy().setStyle(component.style).also { copy -> component.siblings.forEachIndexed { i, s -> copy.append(siblings[i] ?: s) } }
    }

    private var fetchedAt = 0L
    private fun fetch(arguments: String) {
        fetchedAt = System.currentTimeMillis()
        MC.player.connection.sendCommand("gfs $arguments")
    }

    /** Hides the sack "Moved …" reply to a shopping-list /gfs, which would push the list out of view. */
    @Subscription
    fun onSackChat(event: ChatReceivedEvent.Pre) {
        if (System.currentTimeMillis() - fetchedAt < 5_000 && SACK_MOVED.matches(event.text.trim())) event.cancel()
    }

    private val SACK_MOVED = Regex("Moved [\\d,]+ .+ from your Sacks to your inventory\\.?")

    /** A /bz for a crop the selected plot still needs copies the missing amount, ready for the Bazaar buy sign. */
    private fun copyBazaarQuantity(command: String) {
        val verb = command.substringBefore(' ').lowercase()
        // A shopping-list click has already copied its own plot's amount.
        if (verb != "bz" && verb != "bazaar" || !active() || System.currentTimeMillis() - copiedAt < 2_000) return
        val query = command.substringAfter(' ', "").trim().replace('_', ' ')
        val catalog = catalog ?: return
        val (plant, count) = plot?.missingPlants.orEmpty().entries.firstNotNullOfOrNull { (id, count) ->
            catalog[id]?.takeIf { it.name.equals(query, true) || it.id.replace('_', ' ').equals(query, true) }?.let { it to count }
        } ?: return
        MC.instance.keyboardHandler.clipboard = count.toString()
        pendingAmount = count to System.currentTimeMillis() + AMOUNT_WINDOW
        displayModMessage("§7${plant.name}: §f$count§7 goes into the amount sign for you (also copied)")
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
        val looks = arrayOfNulls<String>(GREENHOUSE_CELLS)
        val alternative = arrayOfNulls<String>(GREENHOUSE_CELLS)
        val known = knownLooks(plot)
        for (cell in 0 until GREENHOUSE_CELLS) {
            val soilPos = cellPos(origin, cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE)
            soil[cell] = soilOf(level.getBlockState(soilPos))
            val fingerprint = GreenhouseWorld.fingerprint(level, soilPos.above()) ?: continue
            occupied[cell] = true
            planted[cell] = GreenhouseWorld.plantFor(fingerprint, catalog, known)
            if (planted[cell] == null) looks[cell] = fingerprint
            // A named block look may be a young mutation or just the vanilla crop it resembles; the import decides from the ring.
            if (fingerprint.startsWith("block:") && fingerprint in known) alternative[cell] = GreenhouseWorld.plantFor(fingerprint, catalog, emptyMap())
        }
        // Every entity seen, so a crop the planner misreads can be matched from the log.
        SkyMyce.logger.info("Greenhouse plot {} import read: {}", plot.name, (0 until GREENHOUSE_CELLS).mapNotNull { cell ->
            val pos = cellPos(origin, cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE).above()
            GreenhouseWorld.describe(level, pos).takeUnless { it.startsWith("block ") }?.let { "r${cell / GREENHOUSE_SIZE + 1}c${cell % GREENHOUSE_SIZE + 1}=${planted[cell] ?: looks[cell] ?: "empty"}: $it" }
        }.joinToString(" | ").take(20_000))
        val guessed = inferCrops(catalog, planted, looks)
        guessed.forEach { (look, id) -> learn(look, id, plot) }
        for (cell in 0 until GREENHOUSE_CELLS) looks[cell]?.let { look -> guessed[look]?.let { planted[cell] = it; looks[cell] = null } }
        val unknown = looks.filterNotNull().groupingBy { it }.eachCount()
        if (unknown.isNotEmpty()) {
            val samples = unknown.keys.associateWith { look -> looks.indexOf(look).let { cell -> GreenhouseWorld.describe(level, cellPos(origin, cell / GREENHOUSE_SIZE, cell % GREENHOUSE_SIZE).above()) } }
            SkyMyce.logger.info("Greenhouse plot {} has unidentified crops: {}; samples: {}", plot.name, unknown, samples)
        }
        fun unknownHint(count: Int) = if (count == 0) null else "§e$count planted spots are still not recognised. Look at one and run /sm greenhouse identify"
        if (planted.all { it == null }) { unknownHint(unknown.values.sum())?.let { if (!quiet) displayModMessage(it) }; return fail("§7Nothing recognisable is planted on plot ${plot.name}") }
        val now = System.currentTimeMillis()
        // An unrecognised look on a spot whose ring supplies the target is the mutation on its way, so it may become a target.
        val free = BooleanArray(GREENHOUSE_CELLS) { occupied[it] && looks[it] == null }
        val layout = importLayout(catalog, planted, free, soil, unlocked(plot), target, prices, settings(plot), now, IMPORTED + now.toString(36), alternative)
            ?: return fail("§cThe planted crops do not complete any ${target?.let { catalog[it]?.name } ?: "mutation"}'s ingredients yet")
        val targetCells = layout.targets.flatMap { t -> (0 until t.size).flatMap { r -> (0 until t.size).map { c -> (t.row + r) * GREENHOUSE_SIZE + t.col + c } } }.toSet()
        val unknownHint = unknownHint(looks.indices.count { looks[it] != null && it !in targetCells })
        plot.target = layout.targets.first().plantId
        pin(plot, layout)
        val name = catalog[plot.target!!]?.name
        val growing = layout.targets.count { t -> occupied[t.row * GREENHOUSE_SIZE + t.col] }
        displayModMessage("§aImported plot ${plot.name}: §f$name ×${layout.targets.size} §7($growing growing, ${layout.targets.size - growing} open), " +
            "${layout.placements.count { it.role == Role.INGREDIENT }} ingredients, §f${rate(estimate(layout))} §7estimated. This layout is now pinned")
        if (guessed.isNotEmpty()) displayModMessage("§7Recognised from the recipe: ${guessed.values.groupingBy { it }.eachCount().entries.joinToString { (id, looks) ->
            "${catalog[id]?.name}${if (looks > 1) " ($looks looks)" else ""}" }}. If one is wrong, look at it and run /sm greenhouse identify")
        unknownHint?.let(::displayModMessage)
        return true
    }

    /**
     * Names the crop under the crosshair and reads its plot again. Without a crop it says what the planner sees there
     * and offers the plot's recipe crops as clickable choices.
     */
    private fun identify(id: String?) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        val plant = id?.let { catalog[it] ?: return displayModMessage("§cUnknown crop: $it") }
        val level = MC.instance.level ?: return
        // Further than block reach, so a crop in the middle of the plot can be named from its edge.
        val hit = MC.player.pick(32.0, 1f, false) as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) return displayModMessage("§cLook at the crop, or the soil under it, first (within 32 blocks)")
        val pos = if (soilOf(level.getBlockState(hit.blockPos)) != null) hit.blockPos.above() else hit.blockPos
        val fingerprint = GreenhouseWorld.fingerprint(level, pos) ?: return displayModMessage("§cNothing is planted there")
        val plot = plots.firstOrNull { p -> p.anchor?.let { cellOf(it, pos) } != null } ?: selected()
        if (plant == null) {
            val known = GreenhouseWorld.plantFor(fingerprint, catalog, knownLooks(plot))?.let { catalog[it]?.name }
            val target = plot.target?.let { catalog[it] }
            val choices = (listOfNotNull(target) + target?.requires?.keys.orEmpty().mapNotNull { catalog[it] }).distinct()
            displayModMessage(if (known != null) "§7The planner reads that as §f$known§7. If it is something else, pick it:" else "§eThe planner does not recognise that crop. Which is it?")
            if (choices.isEmpty()) return displayModMessage("§7Run /sm greenhouse identify <crop> (tab completes the names)")
            val line = Component.literal("")
            choices.forEach { choice ->
                line.append(Component.literal("§b[${choice.name}] ").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse identify ${choice.id}")) })
            }
            displayModMessage(line.append(Component.literal("§7or /sm greenhouse identify <crop>")))
            return
        }
        learn(fingerprint, plant.id, plot)
        displayModMessage("§aLearned: that crop is ${plant.name}${if (fingerprint.startsWith("block:")) " on plot ${plot.name}" else ""}. §7Reading the plot again…")
        if (plot.anchor != null) importPlot(plot, null)
    }

    /** Head, item and entity looks are the same crop everywhere; a block look is only trusted on the plot it was named on. */
    private fun learn(fingerprint: String, plantId: String, plot: Plot) {
        val into = if (fingerprint.startsWith("block:")) plot.learned else learned
        into.remove(fingerprint); into[fingerprint] = plantId
        while (into.size > MAX_LEARNED) into.remove(into.keys.first())
        save()
    }

    private fun knownLooks(plot: Plot): Map<String, String> = if (plot.learned.isEmpty()) learned else learned + plot.learned

    private fun loaded(level: Level, origin: Anchor) = listOf(0 to 0, 0 to GREENHOUSE_SIZE - 1, GREENHOUSE_SIZE - 1 to 0, GREENHOUSE_SIZE - 1 to GREENHOUSE_SIZE - 1)
        .all { (row, col) -> cellPos(origin, row, col).let { level.hasChunk(Math.floorDiv(it.x, 16), Math.floorDiv(it.z, 16)) } }

    private fun soilOf(state: BlockState): Soil? = Soil.entries.firstOrNull { state.`is`(soilBlock(it)) }

    // ---- Plant Diagnostics Tool ---------------------------------------------------------------------------------

    /** An Analyze click waiting for the tool's output: which plot placement it was aimed at, and what has been shown since. */
    private class Analysis(val plot: Plot, val key: Int, val plantId: String, val until: Long) { val lines = ArrayList<String>() }
    private var analysis: Analysis? = null

    @Subscription
    fun onRightClick(event: RightClickEvent) = startAnalysis(event.stack, null)

    /** Notes which planned crop the player analyses; the reading itself arrives in chat or a menu right after. Read-only. */
    private fun startAnalysis(stack: ItemStack, entity: Entity?) {
        if (!active() || LocationAPI.island != SkyBlockIsland.GARDEN) return
        if (stack.getData(DataTypes.ID)?.contains("DIAGNOSTIC") != true) return
        val now = System.currentTimeMillis()
        // One click can reach several hooks; the first one decides the target.
        if (analysis?.let { now < it.until - ANALYSIS_WINDOW + 500 } == true) return
        val (plot, placement) = aimedTarget(entity) ?: return
        analysis = Analysis(plot, placement.row * GREENHOUSE_SIZE + placement.col, placement.plantId, now + ANALYSIS_WINDOW)
    }

    /** The planned mutation the player is clicking: [entity] when the click hit one, else the block looked at. */
    private fun aimedTarget(entity: Entity?): Pair<Plot, Placement>? {
        val level = MC.instance.level ?: return null
        // An armor-stand crop's head sits about 1.5 blocks above its feet.
        val hit = MC.player.pick(6.0, 1f, false) as? BlockHitResult
        val pos = when {
            entity != null -> BlockPos.containing(entity.x, entity.y + 1.5, entity.z)
            hit == null || hit.type != HitResult.Type.BLOCK -> return null
            soilOf(level.getBlockState(hit.blockPos)) != null -> hit.blockPos.above()
            else -> hit.blockPos
        }
        val plot = plots.firstOrNull { p -> p.anchor?.let { cellOf(it, pos) } != null } ?: return null
        val cell = cellOf(plot.anchor!!, pos) ?: return null
        // Only mutations have a growth stage worth tracking; a base crop or ingredient is left alone.
        val placement = plot.layout?.targets?.firstOrNull { p ->
            cell / GREENHOUSE_SIZE - p.row in 0 until p.size && cell % GREENHOUSE_SIZE - p.col in 0 until p.size
        } ?: return null
        return plot to placement
    }

    /** Right-clicking a mutation stopped at a pause (an asleep Snoozling) wakes it, so its growth is timed on from the click. */
    private fun noteWake(stack: ItemStack, entity: Entity?) {
        if (!active() || LocationAPI.island != SkyBlockIsland.GARDEN) return
        if (stack.getData(DataTypes.ID)?.contains("DIAGNOSTIC") == true) return
        val (plot, placement) = aimedTarget(entity) ?: return
        val key = placement.row * GREENHOUSE_SIZE + placement.col
        val reading = plot.growth[key] ?: return
        val estimate = targetEstimate(plot, placement)?.takeIf { it.paused } ?: return
        plot.growth[key] = GrowthReading(estimate.stage.toInt(), reading.stages, System.currentTimeMillis(), awake = true)
        revision++; dirty = true; save()
        displayModMessage("§7${catalog?.get(placement.plantId)?.name ?: placement.plantId} woken at stage ${estimate.stage.toInt()}/${reading.stages}; its growth is timed from now")
    }

    @Subscription
    fun onAnalyzerChat(event: ChatReceivedEvent.Pre) { analysis?.let { capture(it, listOf(event.text)) } }

    @Subscription
    fun onAnalyzerMenu(event: ContainerInitializedEvent) {
        val pending = analysis ?: return
        capture(pending, listOf(event.title) + event.containerItems.filterNot { it.isEmpty }.flatMap { item ->
            listOf(item.hoverName.string) + item.get(DataComponents.LORE)?.lines?.map { it.string }.orEmpty()
        })
    }

    private fun capture(pending: Analysis, lines: List<String>) {
        val now = System.currentTimeMillis()
        if (now > pending.until) return
        if (pending.lines.size < 120) pending.lines += lines.take(120 - pending.lines.size)
        val (stage, stages) = parseGrowth(lines) ?: return
        analysis = null
        val reading = GrowthReading(stage, stages, now)
        pending.plot.growth[pending.key] = reading
        revision++; dirty = true; save()
        val name = catalog?.get(pending.plantId)?.name ?: pending.plantId
        val estimate = pending.plot.layout?.let { estimateGrowth(catalog?.get(pending.plantId), reading, hoursPerStage(it), now) }
        displayModMessage("§a$name: growth stage $stage/$stages" + when {
            estimate == null -> ""
            estimate.ready -> if (stage >= stages) " §7· fully grown" else " §7· ready to harvest"
            estimate.paused -> " §7· stopped until you act on it (wake it up)"
            estimate.readyInMs == null -> " §7· its growth cannot be timed; analyze it again later"
            else -> " §7· ready in about ${age(estimate.readyInMs)}"
        })
        growthSummary(pending.plot, now)?.let { summary ->
            displayModMessage("§7Plot ${pending.plot.name}: ${summary.analysed} of ${summary.targets} analysed, about stage ${"%.1f".format(summary.stage)}/${summary.stages} on average" + when {
                summary.readyInMs == null -> ", some need you or another analysis before they finish"
                summary.readyInMs <= 0 -> ", ready now"
                else -> ", all ready in about ${age(summary.readyInMs)} (estimated from the growth timer)"
            })
        }
    }

    private fun expireAnalysis(now: Long) {
        val pending = analysis?.takeIf { now > it.until } ?: return
        analysis = null
        SkyMyce.logger.info("Greenhouse analyzer output had no growth stage the planner understands: {}", pending.lines.joinToString(" | ").take(4000))
        displayModMessage("§7No growth stage was found in what the Plant Diagnostics Tool showed; it was written to the log so the planner can learn to read it")
    }

    private fun hoursPerStage(layout: Layout) = hoursPerStage(GreenhouseConfig.upgradeLevel, maxOf(settings(owner(layout)).uniqueCrops, layout.uniqueCrops))

    /** [readyInMs] is when the last analysed one is ready, or null when one of them cannot be timed. */
    internal data class GrowthSummary(val analysed: Int, val targets: Int, val stage: Double, val stages: Int, val readyInMs: Long?)

    /** The plot's mutations' average growth, projected from the analysed ones with the growth timer; null before any reading. */
    internal fun growthSummary(plot: Plot, now: Long = System.currentTimeMillis()): GrowthSummary? {
        val layout = plot.layout ?: return null
        val estimates = layout.targets.filter { plot.growth[it.row * GREENHOUSE_SIZE + it.col] != null }.mapNotNull { targetEstimate(plot, it, now) }
        if (estimates.isEmpty()) return null
        return GrowthSummary(estimates.size, layout.targets.size, estimates.map { it.stage }.average(), estimates.maxOf { it.stages },
            if (estimates.any { it.readyInMs == null }) null else estimates.maxOf { it.readyInMs!! })
    }

    /**
     * A planted target's growth now, following its mutation's rules; null when it has not been analysed since it appeared.
     * A mutation with no growth stages is grown the moment it appears.
     */
    internal fun targetEstimate(plot: Plot, placement: Placement, now: Long = System.currentTimeMillis()): GrowthEstimate? {
        val layout = plot.layout ?: return null
        val plant = catalog?.get(placement.plantId)
        val key = placement.row * GREENHOUSE_SIZE + placement.col
        val reading = plot.growth[key]?.takeIf { it.at >= (plot.spawned[key] ?: 0L) }
            ?: return if (plant?.stages == 0) GrowthEstimate(0.0, 0, ready = true, paused = false, readyInMs = 0) else null
        return estimateGrowth(plant, reading, hoursPerStage(layout), now)
    }

    // ---- World overlay ------------------------------------------------------------------------------------------

    private fun clear() { ghosts = emptyList(); hudLines = null }

    @Subscription
    fun onTick(event: TickEvent) {
        if (!loaded || !active()) return clear()
        val now = System.currentTimeMillis()
        expireAnalysis(now)
        fillAmountSign(now)
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

    /** A crop or soil going into a slot the pinned layout plans for something else. Locked and unplanned slots are free. */
    private fun wrongClick(stack: ItemStack, pos: BlockPos): Boolean {
        if (!active() || !GreenhouseConfig.blockWrongClicks || LocationAPI.island != SkyBlockIsland.GARDEN) return false
        val catalog = catalog ?: return false
        val held = heldItemId(stack) ?: return false
        val crop = catalog.plants.any { held in it.itemIds }
        val soils = soilsForItem(held)
        if (!crop && soils.isEmpty()) return false
        for (plot in plots) {
            val cell = cellOf(plot.anchor ?: continue, pos) ?: continue
            val layout = plot.layout ?: return false
            if (plot.locked[cell]) return false
            val plan = layout.cells[cell]
            val reason = wrongSlot(plan, plan?.let { catalog[it.plantId] }, held, crop, soils, ::soilName) ?: return false
            MC.instance.gui.setOverlayMessage(Component.literal("§cBlocked: $reason"), false)
            return true
        }
        return false
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
        val analyzer = held?.contains("DIAGNOSTIC") == true
        val looks = knownLooks(plot)
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
                var identified = fingerprint?.let { GreenhouseWorld.plantFor(it, catalog, looks) }
                if (ingredient && fingerprint != null && identified == null && previous?.get(cell) == false && held != null && held in plant.itemIds) {
                    learn(fingerprint, plant.id, plot); identified = plant.id
                }
                // Only a head is certain: a vanilla block look may be a young mutation that has not grown its head yet.
                val wrongCrop = ingredient && fingerprint?.startsWith("head:") == true && identified != null && identified != plant.id
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
            // Grown mutations are green and ones waiting on the player (an asleep Snoozling) yellow; holding the Plant
            // Diagnostics Tool also marks the ones not analysed since they appeared red.
            if (!ingredient && planted) {
                val estimate = targetEstimate(plot, placement, now)
                val color = when {
                    estimate?.ready == true -> 0x55FF55
                    estimate?.paused == true -> 0xFFFF55
                    analyzer && estimate == null -> 0xFF5555
                    else -> null
                }
                if (color != null) {
                    val box = AABB(cellPos(origin, placement.row, placement.col).above())
                        .minmax(AABB(cellPos(origin, placement.row + placement.size - 1, placement.col + placement.size - 1).above())).inflate(0.03)
                    shown += Ghost(box, HudTheme.alpha(color, alpha))
                }
            }
            if (!ingredient) {
                val key = placement.row * GREENHOUSE_SIZE + placement.col
                if (previous != null && planted && !wasOccupied) { plot.spawned[key] = now; changed = true }
                if (previous != null && !planted && wasOccupied) { recordHarvest(plot, current, placement, now); plot.growth.remove(key); changed = true }
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
                        plot.plantedAt, HashMap(plot.spawned), plot.status, plot.scannedAt, plot.missingPlants, plot.missingSoil, LinkedHashMap(plot.learned),
                        HashMap(plot.growth)).toJson())
                }
            })
            add("prices", JsonObject().apply {
                addProperty("observedAt", pricesObservedAt)
                prices.forEach { (id, quote) -> add(id, JsonArray().apply { add(quote.buyOrder); add(quote.sellOffer) }) }
            })
            add("layouts", JsonArray().apply { layouts.values.forEach { add(it.toJson()) } })
            add("estimates", JsonObject().apply { estimates.forEach { (hash, e) -> add(hash, JsonArray().apply { add(e.count); add(e.profitPerHour) }) } })
            add("fingerprints", JsonObject().apply { learned.forEach { (fingerprint, id) -> addProperty(fingerprint, id) } })
            detectedGardenLevel?.let { addProperty("gardenLevel", it) }
        }
        writer.submit(json)
    }

    /** Each section is read independently, so one damaged section only loses itself. */
    private fun restore(json: JsonObject) {
        val catalog = catalog ?: return
        runCatching {
            val section = json.getAsJsonObject("prices")
            pricesObservedAt = section["observedAt"].asLong
            // One bad entry only loses itself.
            prices = section.entrySet().filter { it.key != "observedAt" }.mapNotNull { (id, value: JsonElement) -> runCatching {
                val pair = value.asJsonArray
                id to Quote(pair[0].asDouble, pair[1].asDouble).also { require(it.buyOrder > 0 && it.sellOffer > 0) }
            }.getOrNull() }.toMap()
        }
        detectedGardenLevel = runCatching { json["gardenLevel"]?.asInt?.takeIf { it in 1..15 } }.getOrNull()
        runCatching { json.getAsJsonArray("layouts").forEach { element -> runCatching { layoutFromJson(element.asJsonObject, catalog) }.getOrNull()?.let { layouts[it.inputsHash] = it } } }
        runCatching { json.getAsJsonObject("estimates").entrySet().forEach { (hash, value) -> runCatching {
            val pair = value.asJsonArray
            estimates[hash] = PlotEstimate(pair[0].asInt.also { require(it in 0..GREENHOUSE_CELLS) }, pair[1].asDouble.also { require(it.isFinite()) })
        } } }
        runCatching { json.getAsJsonObject("fingerprints").entrySet().forEach { (fingerprint, value) ->
            // Block looks learned before 2.7.34 were global; they are dropped, since they belong to one plot (a young mutation can look like melon).
            runCatching { value.asString }.getOrNull()?.takeIf { catalog[it] != null && fingerprint.length <= 256 && !fingerprint.startsWith("block:") }?.let { learned[fingerprint] = it }
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
                learned.putAll(state.learned)
                growth.putAll(state.growth)
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
