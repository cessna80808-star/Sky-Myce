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
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
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
 * World reads, layout state and rendering stay on the client thread; price fetches and solving run on the shared scheduler
 * and hand results back through [MC.instance] execute.
 */
object GreenhousePlanner : SkyMyceModule() {
    private const val PRICE_INTERVAL = 5 * 60_000L
    private const val MAX_LAYOUTS = 8

    private val file by lazy { AtomicJsonFile(SkyMyce.configPath.resolve("greenhouse.json"), 262_144) }
    private val writer by lazy {
        StateWriter<JsonObject>({ file.write(it) }, { work -> Scheduling.schedule(200.milliseconds) { work() } }, {
            SkyMyce.logger.warn("Greenhouse plan could not be saved", it)
        })
    }
    private val transport by lazy { BazaarTransport() }
    private val catalog: PlantCatalog? by lazy {
        runCatching { parsePlantCatalog(GreenhousePlanner::class.java.getResourceAsStream("/greenhouse/plants.json")!!.reader().use { it.readText() }) }
            .onFailure { SkyMyce.logger.error("Bundled Greenhouse data is unreadable; the planner stays off", it) }.getOrNull()
    }

    private data class Anchor(val pos: BlockPos, val facing: Direction)
    private data class Ghost(val box: AABB, val color: Int)

    /** One Greenhouse plot. Its [layout] is pinned: only plan, auto and recompute replace it, never a price update. */
    private class Plot(val name: String) {
        var anchor: Anchor? = null
        val locked = BooleanArray(GREENHOUSE_CELLS)
        var target: String? = null
        var layout: Layout? = null
    }

    private var loaded = false
    private val plots = ArrayList<Plot>()
    /** The plot the overlay, HUD and commands use: the nearest anchored plot while in the Garden. */
    private var plot: Plot? = null
    /** Solved layouts by inputs hash, so planning the same inputs again is instant. */
    private val layouts = LinkedHashMap<String, Layout>()
    private var prices: Map<String, Quote> = emptyMap()
    private var pricesObservedAt = 0L
    private var nextPoll = 0L
    private var polling = false
    private var priceFailureLogged = false
    private var solving = false
    /** An explicit plan request waiting for the first Bazaar prices: the plot and whether it was a forced recompute. */
    private var waitingForPrices: Pair<Plot, Boolean>? = null

    private var ticks = 0
    private var heldStack: ItemStack? = null
    private var heldId: String? = null
    private var dirty = true
    private var ghosts: List<Ghost> = emptyList()
    private var hudLines: List<String>? = null
    /** What the selected plot still needs, from the last world scan; [scanned] is false while no plot is in range. */
    private var missingPlants: Map<String, Int> = emptyMap()
    private var missingSoil: Map<Soil, Int> = emptyMap()
    private var scanned = false

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
            MC.instance.execute { json?.let(::restore); loaded = true; dirty = true }
        }
    }

    private fun settings() = GreenhouseSettings(GreenhouseConfig.upgradeLevel, GreenhouseConfig.uniqueCrops, GreenhouseConfig.priceMode)

    private fun allowed(plant: Plant) = plant.rarity.ordinal <= GreenhouseConfig.maxTier.ordinal

    fun ranking(): List<RankedMutation> {
        val catalog = catalog ?: return emptyList()
        val ranked = rankMutations(catalog, prices, settings(), ::allowed)
        return if (GreenhouseConfig.profitMetric == ProfitMetric.PER_CYCLE) ranked.sortedByDescending { it.profitPerCycle } else ranked
    }

    private fun selected(): Plot = plot ?: (plots.firstOrNull() ?: Plot("1").also(plots::add)).also { plot = it }

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
                        dirty = true; save()
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
    private fun plan(plot: Plot, force: Boolean) {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (prices.isEmpty()) {
            waitingForPrices = plot to force; nextPoll = 0; pollPrices(System.currentTimeMillis())
            return displayModMessage("§7Waiting for Bazaar prices before solving…")
        }
        if (solving) return displayModMessage("§7Still solving the previous layout; try again in a moment")
        val target = plot.target ?: ranking().firstOrNull()?.plant?.id ?: return displayModMessage("§cNo mutation up to your highest tier has Bazaar prices")
        val name = catalog[target]?.name ?: target
        val unlocked = BooleanArray(GREENHOUSE_CELLS) { !plot.locked[it] }
        val settings = settings()
        val hash = inputsHash(unlocked, listOf(target), settings, catalog, prices)
        if (!force) layouts[hash]?.let { cached ->
            pin(plot, cached)
            return displayModMessage("§aUsing the cached $name layout${chatLabel(plot)}: ${cached.targets.size} placed, §f${rate(estimate(cached))} §7estimated")
        }
        solving = true
        displayModMessage("§7Solving the $name layout${chatLabel(plot)}…")
        val quotes = prices
        Scheduling.schedule(Duration.ZERO) {
            val result = runCatching { GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(target)).solve(hash.hashCode().toLong(), System.currentTimeMillis(), hash) }
            MC.instance.execute {
                solving = false
                result.onFailure { SkyMyce.logger.warn("Greenhouse layout could not be solved", it) }
                val solved = result.getOrNull() ?: return@execute displayModMessage("§cNo valid layout for $name with the unlocked cells and current prices")
                layouts.remove(hash); layouts[hash] = solved
                while (layouts.size > MAX_LAYOUTS) layouts.remove(layouts.keys.first())
                pin(plot, solved)
                displayModMessage("§aGreenhouse layout ready${chatLabel(plot)}: $name ×${solved.targets.size}, §f${rate(solved.totalProfitPerHour)} §7estimated")
            }
        }
    }

    private fun pin(plot: Plot, layout: Layout) { plot.layout = layout; dirty = true; save() }

    /** The pinned layout's profit at current prices; the solve-time figure when a price is missing. */
    private fun estimate(layout: Layout) = catalog?.let { layout.profitPerHour(it, prices, settings()) } ?: layout.totalProfitPerHour

    // ---- Commands -----------------------------------------------------------------------------------------------

    fun command(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("greenhouse")
        .executes { MC.instance.execute(::showRanking); 1 }
        .then(literal("plan").then(argument("mutation", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.filter { it.solvable }?.map { it.id }.orEmpty(), builder) }
            .executes { context -> val id = StringArgumentType.getString(context, "mutation").uppercase(); MC.instance.execute { choose(id) }; 1 }))
        .then(literal("auto").executes { MC.instance.execute { choose(null) }; 1 })
        .then(literal("recompute").executes { MC.instance.execute { plan(selected(), force = true) }; 1 })
        .then(literal("anchor").executes { MC.instance.execute { setAnchor(null) }; 1 }
            .then(argument("name", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(plots.map { it.name }, builder) }
                .executes { context -> val name = StringArgumentType.getString(context, "name"); MC.instance.execute { setAnchor(name) }; 1 }))
        .then(literal("remove").then(argument("name", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(plots.map { it.name }, builder) }
            .executes { context -> val name = StringArgumentType.getString(context, "name"); MC.instance.execute { removePlot(name) }; 1 }))
        .then(literal("lock").then(argument("row", IntegerArgumentType.integer(1, GREENHOUSE_SIZE))
            .then(argument("column", IntegerArgumentType.integer(1, GREENHOUSE_SIZE)).executes { context ->
                val row = IntegerArgumentType.getInteger(context, "row") - 1; val column = IntegerArgumentType.getInteger(context, "column") - 1
                MC.instance.execute { toggleLock(row, column) }; 1
            })))
        .then(literal("missing").executes { MC.instance.execute(::showMissing); 1 })
        .then(literal("shopping").executes { MC.instance.execute(::showMissing); 1 })
        .then(literal("buy").then(argument("crop", StringArgumentType.word()).executes { context ->
            val id = StringArgumentType.getString(context, "crop").uppercase(); MC.instance.execute { buy(id) }; 1
        }))

    /**
     * Tab completion for the server's /bz and /gfs. The nodes have no executor, so Fabric passes the typed command to the
     * server unchanged; the client only adds the selected plot's missing crops and soils to the suggestions.
     */
    fun serverCommandHints(): List<LiteralArgumentBuilder<FabricClientCommandSource>> = listOf(
        literal("bz").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(missingPlants.keys.mapNotNull { catalog?.get(it)?.name }, builder)
        }),
        literal("gfs").then(argument("item", StringArgumentType.greedyString()).suggests { _, builder ->
            SharedSuggestionProvider.suggest(missingSoil.map { (soil, count) -> "${sackId(soil)} $count" }, builder)
        }),
    )

    private fun showRanking() {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        if (!GreenhouseConfig.enabled) displayModMessage("§7Greenhouse Planner is off in settings; the ranking still works")
        if (prices.isEmpty()) { nextPoll = 0; pollPrices(System.currentTimeMillis()); return displayModMessage("§7Fetching Bazaar prices… run /sm greenhouse again in a moment") }
        val now = System.currentTimeMillis()
        pollPrices(now)
        val metric = GreenhouseConfig.profitMetric
        displayModMessage("§aGreenhouse profit ranking §7(${GreenhouseConfig.priceMode.label}, ${metric.label.lowercase()}, prices ${age(now - pricesObservedAt)} old)")
        ranking().take(10).forEachIndexed { i, entry ->
            val value = when (metric) {
                ProfitMetric.PER_HOUR -> "${signed(entry.profitPerHour)}/h"
                ProfitMetric.PER_DAY -> "${signed(entry.profitPerDay)}/d"
                ProfitMetric.PER_CYCLE -> "${signed(entry.profitPerCycle)}/cycle"
            }
            val line = Component.literal("§7${i + 1}. §f${entry.plant.name} §a$value §7· cost ${formatCoins(entry.inputCost)} · ${"%.1f".format(entry.cycleHours)}h ")
                .append(Component.literal("§b[Plan]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse plan ${entry.plant.id}")) })
            displayModMessage(line)
        }
        val plot = selected()
        val current = plot.layout
        val target = current?.targets?.firstOrNull()?.let { catalog[it.plantId]?.name }
        displayModMessage(when {
            current == null -> "§7No layout yet${chatLabel(plot)}. Click [Plan] or run /sm greenhouse auto"
            else -> "§7Current plan${chatLabel(plot)}: §f$target ×${current.targets.size} §a${rate(estimate(current))} §7· solved ${age(now - current.generatedAt)} ago${if (plot.target == null) " · auto" else ""}"
        })
        if (plot.anchor == null) displayModMessage("§eLook at the soil of the corner slot and run /sm greenhouse anchor to place the overlay")
        displayModMessage("§8Estimates only, using skymutations.eu's formula (by palmaner). Drops, copper and growth effects are not modelled")
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
        plot.anchor = Anchor(pos, facing)
        this.plot = plot; dirty = true; save()
        displayModMessage("§aGreenhouse plot §f${plot.name}§a anchored at ${pos.x} ${pos.y} ${pos.z}: §7rows run ${facing.name.lowercase()}, columns to your right")
        if (plot.layout == null) displayModMessage("§7Plan it with /sm greenhouse auto, or click [Plan] in /sm greenhouse")
    }

    private fun removePlot(name: String) {
        val removed = plots.firstOrNull { it.name.equals(name, true) } ?: return displayModMessage("§cNo Greenhouse plot named $name")
        plots.remove(removed)
        if (plot === removed) plot = null
        if (waitingForPrices?.first === removed) waitingForPrices = null
        dirty = true; save()
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

    private fun showMissing() {
        val catalog = catalog ?: return displayModMessage("§cGreenhouse data is unavailable")
        val plot = selected()
        if (plot.layout == null) return displayModMessage("§7No layout yet${chatLabel(plot)}. Run /sm greenhouse auto or plan <mutation>")
        if (!scanned) return displayModMessage("§7Stand near your anchored Greenhouse plot in the Garden to check what is missing")
        if (missingPlants.isEmpty() && missingSoil.isEmpty()) return displayModMessage("§aNothing missing${chatLabel(plot)}: every crop and soil matches the plan")
        displayModMessage("§aGreenhouse shopping list${chatLabel(plot)} §7(click to open the Bazaar and copy the amount, or to fill in /gfs)")
        missingPlants.entries.sortedByDescending { it.value }.forEach { (id, count) ->
            displayModMessage(Component.literal("§f$count× §7${catalog[id]?.name ?: id} ")
                .append(Component.literal("§b[Bazaar]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse buy $id")) }))
        }
        missingSoil.entries.sortedByDescending { it.value }.forEach { (soil, count) ->
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
        if (verb != "bz" && verb != "bazaar" || !GreenhouseConfig.enabled || !isEnabled()) return
        val query = command.substringAfter(' ', "").trim().replace('_', ' ')
        val catalog = catalog ?: return
        val (plant, count) = missingPlants.entries.firstNotNullOfOrNull { (id, count) ->
            catalog[id]?.takeIf { it.name.equals(query, true) || it.id.replace('_', ' ').equals(query, true) }?.let { it to count }
        } ?: return
        MC.instance.keyboardHandler.clipboard = count.toString()
        displayModMessage("§7Copied §f$count§7 to the clipboard (${plant.name} still needed)")
    }

    // ---- World overlay ------------------------------------------------------------------------------------------

    private fun clear() {
        ghosts = emptyList(); hudLines = null
        missingPlants = emptyMap(); missingSoil = emptyMap(); scanned = false
    }

    @Subscription
    fun onTick(event: TickEvent) {
        if (!loaded || !GreenhouseConfig.enabled || !isEnabled()) return clear()
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
        if (current != null) return rebuild(plot, current, origin, now)
        clear()
        hudLines = listOf("§aGreenhouse${hudLabel(plot)} §7· no plan", "§7/sm greenhouse auto §8or §7plan <mutation>")
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

    /** Some crops (Scourroot and others) are armor stands or display entities with custom heads above an air block. */
    private fun cropPlaced(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!state.isAir && !state.canBeReplaced()) return true
        return level.getEntitiesOfClass(Entity::class.java, AABB(pos)) { it is ArmorStand || it is Display }.isNotEmpty()
    }

    private fun rebuild(plot: Plot, current: Layout, origin: Anchor, now: Long) {
        val catalog = catalog ?: return
        val level = MC.instance.level ?: return
        val held = heldId
        val heldSoils = held?.let(::soilsForItem).orEmpty()
        val allSoil = GreenhouseConfig.alwaysShowSoilGhosts
        val alpha = GreenhouseConfig.opacity
        val shown = ArrayList<Ghost>()
        var done = 0
        val plants = LinkedHashMap<String, Int>()
        val soils = LinkedHashMap<Soil, Int>()
        for (placement in current.placements) {
            val plant = catalog[placement.plantId] ?: continue
            val soil = current.soils.getValue(placement)
            val ingredient = placement.role == Role.INGREDIENT
            var planted = !ingredient
            for (r in 0 until placement.size) for (c in 0 until placement.size) {
                val soilPos = cellPos(origin, placement.row + r, placement.col + c)
                val soilState = level.getBlockState(soilPos)
                val soilOk = plant.soils.any { soilState.`is`(soilBlock(it)) }
                val occupied = ingredient && cropPlaced(level, soilPos.above())
                if (occupied) planted = true
                if (!soilOk) {
                    soils.merge(soil, 1, Int::plus)
                    if (allSoil || soil in heldSoils) shown += Ghost(AABB(soilPos).inflate(0.004), HudTheme.alpha(soilColor(soil), alpha))
                }
                if (held != null && ingredient && held in plant.itemIds && !occupied)
                    shown += Ghost(AABB(soilPos.above()).deflate(0.12), HudTheme.alpha(plantColor(plant.id), alpha))
                if (soilOk && (!ingredient || occupied)) done++
            }
            if (!planted) plants.merge(plant.id, 1, Int::plus)
        }
        ghosts = shown
        missingPlants = plants; missingSoil = soils; scanned = true
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
            add("§8solved ${age(now - current.generatedAt)} ago · prices ${if (pricesObservedAt > 0) age(now - pricesObservedAt) + " old" else "pending"}")
        }
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
                    add(PlotState(plot.name, anchor, plot.locked.indices.filter { plot.locked[it] }.toSet(), plot.target, plot.layout).toJson())
                }
            })
            add("prices", JsonObject().apply {
                addProperty("observedAt", pricesObservedAt)
                prices.forEach { (id, quote) -> add(id, JsonArray().apply { add(quote.buyOrder); add(quote.sellOffer) }) }
            })
            add("layouts", JsonArray().apply { layouts.values.forEach { add(it.toJson()) } })
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
        runCatching { plotsFromJson(json, catalog) }.onFailure { SkyMyce.logger.warn("Greenhouse plots could not be read", it) }.getOrNull()?.forEach { state ->
            plots += Plot(state.name).apply {
                anchor = state.anchor?.let { Anchor(BlockPos(it.x, it.y, it.z), Direction.valueOf(it.facing)) }
                state.locked.forEach { locked[it] = true }
                target = state.target
                layout = state.layout
            }
        }
    }

    // ---- Formatting ---------------------------------------------------------------------------------------------

    private fun square(value: Int) = value * value
    private fun signed(value: Double) = (if (value >= 0) "+" else "-") + formatCoins(abs(value))
    private fun rate(perHour: Double) = if (GreenhouseConfig.profitMetric == ProfitMetric.PER_DAY) "${signed(perHour * 24.0)}/d" else "${signed(perHour)}/h"
    private fun chatLabel(plot: Plot) = if (plots.size > 1) " (plot ${plot.name})" else ""
    private fun hudLabel(plot: Plot) = if (plots.size > 1) " §f${plot.name}" else ""
    private fun age(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return when { minutes < 1 -> "<1m"; minutes < 120 -> "${minutes}m"; else -> "${minutes / 60}h" }
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

    private fun soilName(soil: Soil) = soil.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun soilColor(soil: Soil) = when (soil) {
        Soil.FARMLAND, Soil.DIRT -> 0x8B5A2B; Soil.SAND -> 0xE8D9A0; Soil.SOUL_SAND -> 0x5A4030
        Soil.MYCELIUM -> 0x8E6FA8; Soil.END_STONE -> 0xE6E8B0
    }

    /** Stable distinct hue per plant so neighbouring ghosts of different crops are told apart. */
    private fun plantColor(id: String): Int = java.awt.Color.HSBtoRGB((id.hashCode() and 0xFFFF) / 65535f, 0.65f, 0.95f) and 0xFFFFFF

}
