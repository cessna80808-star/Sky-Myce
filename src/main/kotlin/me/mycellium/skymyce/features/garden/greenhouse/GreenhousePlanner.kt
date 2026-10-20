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
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
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
 * Greenhouse planner. Client-local and visual only: it reads the world and the held item, never places blocks or sends input.
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

    private var loaded = false
    private var anchor: Anchor? = null
    private val locked = BooleanArray(GREENHOUSE_CELLS)
    private var targetOverride: String? = null
    private val layouts = LinkedHashMap<String, Layout>()
    private var layout: Layout? = null
    private var prices: Map<String, Quote> = emptyMap()
    private var pricesObservedAt = 0L
    private var nextPoll = 0L
    private var polling = false
    private var priceFailureLogged = false
    private var solving = false
    private var solveAgain = false
    private var announce = false

    private var ticks = 0
    private var heldStack: ItemStack? = null
    private var heldId: String? = null
    private var dirty = true
    private var ghosts: List<Ghost> = emptyList()
    private var hudLines: List<String>? = null

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

    // ---- Prices -------------------------------------------------------------------------------------------------

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
                        save(); plan(force = false)
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

    /** Reuses a cached layout whenever the inputs hash matches; only a forced recompute ignores the cache. */
    private fun plan(force: Boolean) {
        val catalog = catalog ?: return
        if (prices.isEmpty()) return
        val target = targetOverride ?: ranking().firstOrNull()?.plant?.id ?: return
        val unlocked = BooleanArray(GREENHOUSE_CELLS) { !locked[it] }
        val settings = settings()
        val hash = inputsHash(unlocked, listOf(target), settings, catalog, prices)
        if (!force) {
            layouts[hash]?.let { cached -> if (layout !== cached) { layout = cached; dirty = true }; return }
            if (layout?.inputsHash == hash) return
        }
        if (solving) { solveAgain = true; return }
        solving = true
        val quotes = prices
        Scheduling.schedule(Duration.ZERO) {
            val result = runCatching { GreenhouseSolver(catalog, quotes, settings, unlocked, listOf(target)).solve(hash.hashCode().toLong(), System.currentTimeMillis(), hash) }
            MC.instance.execute {
                solving = false
                result.onFailure { SkyMyce.logger.warn("Greenhouse layout could not be solved", it) }
                val solved = result.getOrNull()
                if (solved != null) {
                    layouts.remove(hash); layouts[hash] = solved
                    while (layouts.size > MAX_LAYOUTS) layouts.remove(layouts.keys.first())
                    layout = solved; dirty = true; save()
                }
                if (announce) {
                    announce = false
                    if (solved == null) displayModMessage("§cNo valid layout for ${catalog[target]?.name ?: target} with the unlocked cells and current prices")
                    else displayModMessage("§aGreenhouse layout ready: ${catalog[target]?.name} ×${solved.targets.size}, §f${formatCoins(solved.totalProfitPerHour)}/h §7estimated")
                }
                if (solveAgain) { solveAgain = false; plan(force = false) }
            }
        }
    }

    // ---- Commands -----------------------------------------------------------------------------------------------

    fun command(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("greenhouse")
        .executes { MC.instance.execute(::showRanking); 1 }
        .then(literal("plan").then(argument("mutation", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(catalog?.plants?.filter { it.solvable }?.map { it.id }.orEmpty(), builder) }
            .executes { context -> val id = StringArgumentType.getString(context, "mutation").uppercase(); MC.instance.execute { choose(id) }; 1 }))
        .then(literal("auto").executes { MC.instance.execute { choose(null) }; 1 })
        .then(literal("recompute").executes { MC.instance.execute { announce = true; plan(force = true); displayModMessage("§7Recomputing the Greenhouse layout…") }; 1 })
        .then(literal("anchor").executes { MC.instance.execute(::setAnchor); 1 })
        .then(literal("lock").then(argument("row", IntegerArgumentType.integer(1, GREENHOUSE_SIZE))
            .then(argument("column", IntegerArgumentType.integer(1, GREENHOUSE_SIZE)).executes { context ->
                val row = IntegerArgumentType.getInteger(context, "row") - 1; val column = IntegerArgumentType.getInteger(context, "column") - 1
                MC.instance.execute { toggleLock(row, column) }; 1
            })))

    private fun showRanking() {
        if (catalog == null) return displayModMessage("§cGreenhouse data is unavailable")
        if (!GreenhouseConfig.enabled) displayModMessage("§7Greenhouse Planner is off in settings; the ranking still works")
        if (prices.isEmpty()) { nextPoll = 0; pollPrices(System.currentTimeMillis()); return displayModMessage("§7Fetching Bazaar prices… run /sm greenhouse again in a moment") }
        val now = System.currentTimeMillis()
        pollPrices(now)
        val perHour = GreenhouseConfig.profitMetric == ProfitMetric.PER_HOUR
        displayModMessage("§aGreenhouse profit ranking §7(${GreenhouseConfig.priceMode.label}, ${GreenhouseConfig.profitMetric.label.lowercase()}, prices ${age(now - pricesObservedAt)} old)")
        ranking().take(10).forEachIndexed { i, entry ->
            val metric = if (perHour) "${signed(entry.profitPerHour)}/h" else "${signed(entry.profitPerCycle)}/cycle"
            val line = Component.literal("§7${i + 1}. §f${entry.plant.name} §a$metric §7· cost ${formatCoins(entry.inputCost)} · ${"%.1f".format(entry.cycleHours)}h ")
                .append(Component.literal("§b[Plan]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/sm greenhouse plan ${entry.plant.id}")) })
            displayModMessage(line)
        }
        val current = layout
        val target = current?.targets?.firstOrNull()?.let { catalog!![it.plantId]?.name }
        displayModMessage(when {
            current == null -> "§7No layout yet. Click [Plan] or run /sm greenhouse auto"
            else -> "§7Current plan: §f$target ×${current.targets.size} §a${formatCoins(current.totalProfitPerHour)}/h §7· solved ${age(now - current.generatedAt)} ago${if (targetOverride == null) " · auto" else ""}"
        })
        if (anchor == null) displayModMessage("§eLook at the soil of the corner slot and run /sm greenhouse anchor to place the overlay")
        displayModMessage("§8Estimates only, using skymutations.eu's formula (by palmaner). Drops, copper and growth effects are not modelled")
    }

    private fun choose(id: String?) {
        val plant = id?.let { catalog?.get(it) }
        if (id != null && plant?.solvable != true) return displayModMessage("§cUnknown or unsupported mutation: $id")
        targetOverride = plant?.id
        announce = true
        save()
        if (prices.isEmpty()) { nextPoll = 0; pollPrices(System.currentTimeMillis()); return displayModMessage("§7Waiting for Bazaar prices before solving…") }
        plan(force = false)
        if (solving) displayModMessage("§7Solving the Greenhouse layout…")
        else layout?.let { displayModMessage("§aUsing the cached layout: ${it.targets.size} placed, §f${formatCoins(it.totalProfitPerHour)}/h") }
        announce = solving
    }

    private fun setAnchor() {
        val hit = MC.instance.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) return displayModMessage("§cLook at the soil block of the corner slot first")
        val facing = MC.player.direction.takeIf { it.axis.isHorizontal } ?: Direction.NORTH
        anchor = Anchor(hit.blockPos.immutable(), facing)
        dirty = true; save()
        displayModMessage("§aGreenhouse anchored at ${hit.blockPos.x} ${hit.blockPos.y} ${hit.blockPos.z}: §7rows run ${facing.name.lowercase()}, columns to your right")
    }

    private fun toggleLock(row: Int, column: Int) {
        val cell = row * GREENHOUSE_SIZE + column
        locked[cell] = !locked[cell]
        save(); plan(force = false)
        displayModMessage("§7Cell ${row + 1},${column + 1} is now ${if (locked[cell]) "§clocked" else "§aunlocked"}§7. ${locked.count { !it }} cells usable")
    }

    // ---- World overlay ------------------------------------------------------------------------------------------

    @Subscription
    fun onTick(event: TickEvent) {
        if (!loaded || !GreenhouseConfig.enabled || !isEnabled()) { ghosts = emptyList(); hudLines = null; return }
        val now = System.currentTimeMillis()
        val inGarden = LocationAPI.island == SkyBlockIsland.GARDEN
        if (inGarden) pollPrices(now)
        val player = MC.instance.player
        val current = layout
        val origin = anchor
        if (!inGarden || player == null || current == null) { ghosts = emptyList(); hudLines = null; return }
        if (origin == null) { ghosts = emptyList(); hudLines = listOf("§aGreenhouse §7· plan ready", "§eLook at the corner slot's soil:", "§e/sm greenhouse anchor"); return }
        if (origin.pos.distSqr(player.blockPosition()) > square(GreenhouseConfig.renderDistance + 24)) { ghosts = emptyList(); hudLines = null; return }
        val stack = player.mainHandItem
        if (stack !== heldStack) {
            heldStack = stack
            val id = heldItemId(stack)
            if (id != heldId) { heldId = id; dirty = true }
        }
        // Block updates mark dirty; the periodic pass covers chunk reloads that bypass them.
        if (dirty || ++ticks % 40 == 0) { dirty = false; rebuild(current, origin, now) }
    }

    @Subscription
    fun onBlockChange(event: BlockChangeEvent) {
        val origin = anchor ?: return
        if (abs(event.pos.y - origin.pos.y) <= 2 && event.pos.distSqr(origin.pos) <= square(GREENHOUSE_SIZE * 2)) dirty = true
    }

    private fun heldItemId(stack: ItemStack): String? = if (stack.isEmpty) null
        else stack.getData(DataTypes.ID) ?: BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase()

    private fun cellPos(origin: Anchor, row: Int, col: Int): BlockPos = origin.pos.relative(origin.facing, row).relative(origin.facing.clockWise, col)

    private fun rebuild(current: Layout, origin: Anchor, now: Long) {
        val catalog = catalog ?: return
        val level = MC.instance.level ?: return
        val held = heldId
        val heldSoils = held?.let(::soilsForItem).orEmpty()
        val alpha = GreenhouseConfig.opacity
        val shown = ArrayList<Ghost>()
        var done = 0
        val missingPlants = LinkedHashMap<String, Int>()
        val missingSoil = LinkedHashMap<Soil, Int>()
        for (placement in current.placements) {
            val plant = catalog[placement.plantId] ?: continue
            var planted = placement.role == Role.TARGET
            for (r in 0 until placement.size) for (c in 0 until placement.size) {
                val soilPos = cellPos(origin, placement.row + r, placement.col + c)
                val above = level.getBlockState(soilPos.above())
                val soilState = level.getBlockState(soilPos)
                val soilOk = plant.soils.any { soilState.`is`(soilBlock(it)) }
                if (placement.role == Role.INGREDIENT && !above.isAir && !above.canBeReplaced()) planted = true
                if (!soilOk) missingSoil.merge(current.soils.getValue(placement), 1, Int::plus)
                if (!soilOk && current.soils.getValue(placement) in heldSoils)
                    shown += Ghost(AABB(soilPos).inflate(0.004), HudTheme.alpha(soilColor(current.soils.getValue(placement)), alpha))
                if (held != null && placement.role == Role.INGREDIENT && held in plant.itemIds && (above.isAir || above.canBeReplaced()))
                    shown += Ghost(AABB(soilPos.above()).deflate(0.12), HudTheme.alpha(plantColor(plant.id), alpha))
                if (soilOk && (placement.role == Role.TARGET || !above.isAir && !above.canBeReplaced())) done++
            }
            if (!planted) missingPlants.merge(plant.name, 1, Int::plus)
        }
        ghosts = shown
        val cells = current.placements.sumOf { it.size * it.size }
        val target = current.targets.firstOrNull()?.let { catalog[it.plantId]?.name } ?: "?"
        hudLines = buildList {
            add("§aGreenhouse §7· $target ×${current.targets.size}")
            add("§f${signed(current.totalProfitPerHour)}/h §7est. · ${if (cells == 0) 0 else done * 100 / cells}% built")
            missingPlants.entries.sortedByDescending { it.value }.take(4).forEach { add("§7${it.key} §f×${it.value}") }
            missingSoil.entries.sortedByDescending { it.value }.take(3).forEach { add("§7${soilName(it.key)} soil §f×${it.value}") }
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
            addProperty("version", 1)
            anchor?.let { a -> add("anchor", JsonObject().apply { addProperty("x", a.pos.x); addProperty("y", a.pos.y); addProperty("z", a.pos.z); addProperty("facing", a.facing.name) }) }
            add("locked", JsonArray().apply { locked.forEachIndexed { cell, value -> if (value) add(cell) } })
            targetOverride?.let { addProperty("target", it) }
            add("prices", JsonObject().apply {
                addProperty("observedAt", pricesObservedAt)
                prices.forEach { (id, quote) -> add(id, JsonArray().apply { add(quote.buyOrder); add(quote.sellOffer) }) }
            })
            add("layouts", JsonArray().apply { layouts.values.forEach { add(it.toJson()) } })
            layout?.let { addProperty("active", it.inputsHash) }
        }
        writer.submit(json)
    }

    /** Each section is read independently, so one damaged section only loses itself. */
    private fun restore(json: JsonObject) {
        val catalog = catalog ?: return
        runCatching {
            val a = json.getAsJsonObject("anchor")
            anchor = Anchor(BlockPos(a["x"].asInt, a["y"].asInt, a["z"].asInt), Direction.valueOf(a["facing"].asString).takeIf { it.axis.isHorizontal }!!)
        }
        runCatching { json.getAsJsonArray("locked").forEach { cell -> cell.asInt.takeIf { it in 0 until GREENHOUSE_CELLS }?.let { locked[it] = true } } }
        targetOverride = runCatching { json["target"].asString }.getOrNull()?.takeIf { catalog[it]?.solvable == true }
        runCatching {
            val section = json.getAsJsonObject("prices")
            pricesObservedAt = section["observedAt"].asLong
            prices = section.entrySet().filter { it.key != "observedAt" }.associate { (id, value: JsonElement) ->
                val pair = value.asJsonArray
                id to Quote(pair[0].asDouble, pair[1].asDouble).also { require(it.buyOrder > 0 && it.sellOffer > 0) }
            }
        }
        runCatching { json.getAsJsonArray("layouts").forEach { element -> runCatching { layoutFromJson(element.asJsonObject, catalog) }.getOrNull()?.let { layouts[it.inputsHash] = it } } }
        layout = runCatching { json["active"].asString }.getOrNull()?.let(layouts::get)
    }

    // ---- Formatting ---------------------------------------------------------------------------------------------

    private fun square(value: Int) = value * value
    private fun signed(value: Double) = (if (value >= 0) "+" else "-") + formatCoins(abs(value))
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

    private fun soilName(soil: Soil) = soil.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun soilColor(soil: Soil) = when (soil) {
        Soil.FARMLAND, Soil.DIRT -> 0x8B5A2B; Soil.SAND -> 0xE8D9A0; Soil.SOUL_SAND -> 0x5A4030
        Soil.MYCELIUM -> 0x8E6FA8; Soil.END_STONE -> 0xE6E8B0
    }

    /** Stable distinct hue per plant so neighbouring ghosts of different crops are told apart. */
    private fun plantColor(id: String): Int = java.awt.Color.HSBtoRGB((id.hashCode() and 0xFFFF) / 65535f, 0.65f, 0.95f) and 0xFFFFFF

}
