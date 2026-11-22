package me.mycellium.skymyce.features.mining

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.config.mining.MiningConfig
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.Utils
import net.minecraft.client.model.geom.builders.UVPair
import net.minecraft.client.renderer.block.BlockStateModelSet
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.block.dispatch.SingleVariant
import net.minecraft.client.resources.model.SimpleModelWrapper
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.client.resources.model.geometry.QuadCollection
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Anchor
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import me.mycellium.skymyce.utils.PlayerUtils.sendMessage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import tech.thatgravyboat.skyblockapi.api.area.mining.CommissionsAPI
import tech.thatgravyboat.skyblockapi.api.area.mining.MiningBlock
import tech.thatgravyboat.skyblockapi.api.area.mining.MiningBlockFamily
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.OnlyIn
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.info.TabWidget
import tech.thatgravyboat.skyblockapi.api.events.info.TabWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.render.RenderWorldEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockAreas
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling.schedule
import tech.thatgravyboat.skyblockapi.utils.extentions.getRawLore
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockCategory
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.ui.GuiMotion
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.mycellium.skymyce.utils.collectFrameGizmos
import me.mycellium.skymyce.utils.reloadChunks
import me.mycellium.skymyce.utils.vanillaBlock

object MiningFeatures : SkyMyceModule() {
    val crystalRegex = Regex("^\\s*✦ CRYSTAL FOUND \\((\\d+)/(\\d+)\\)$")

    var totalTime = 0L
    var timeSince = System.currentTimeMillis()

    const val CH_LOCK_DAY = 20.5
    const val CH_CLOSE_DAY = 35
    private val crystalHollowsContent by lazy { hudPanel(decorated = false) }

    val crystalHollowsWidget = Widget("crystal_hollows_info", "CH Info", 200, 200, 1f, Anchor.TOP_LEFT) {
        if (!MiningConfig.crystalHollowsInfo) return@Widget null
        if (LocationAPI.island != SkyBlockIsland.CRYSTAL_HOLLOWS) return@Widget null

        val time = (MC.instance.level?.gameTime ?: 0L) - 60240000 // 2510 days in ticks
        val day = ((time / 20.0) / 60.0) / 20.0 // 20 ticks, 60 seconds, 20 minutes
        val lockTimeRemaining = (((CH_LOCK_DAY * 24000) - time) / 20).seconds
        val closeTimeRemaining = (((CH_CLOSE_DAY * 24000) - time) / 20).seconds

        val dayFormatted = when {
            (day > CH_CLOSE_DAY) -> "§c${floor(day * 100) / 100} (CLOSING)"
            (day > CH_LOCK_DAY) -> "§e${floor(day * 100) / 100} (LOCKED)"
            else -> "§a${floor(day * 100) / 100}"
        }

        val lines = mutableListOf<String>()
        lines += "§7Day: §e$dayFormatted"
        lines += "§7Player count: (§e${LocationAPI.playerCount}§7/§a${LocationAPI.maxPlayercount}§7)"
        lines += "§7Lobby Time: §e${(System.currentTimeMillis() - timeSince).milliseconds.inWholeSeconds.seconds}"
        lines += "§7Session Time: §e${(totalTime + System.currentTimeMillis() - timeSince).milliseconds.inWholeSeconds.seconds}"
        if (lockTimeRemaining > 0.seconds) {
            lines += "§7Time to lock: §e${lockTimeRemaining.inWholeSeconds.seconds}"
        } else if (closeTimeRemaining > 0.seconds && LocationAPI.playerCount > 3) {
            lines += "§7Time to close: §e${closeTimeRemaining.inWholeSeconds.seconds}"
        } else {
            lines += "§cLobby Closing!"
        }

        crystalHollowsContent.updateTextLines(lines)
    }

    private const val SPREAD_COLOR = 0x55FFFF
    private const val COMMISSION_COLOR = 0xFFD54F
    private const val MOB_COLOR = 0xFF5555
    /** Horizontal and vertical reach of the once-a-second commission block scan (~35k blocks). */
    private const val SCAN_XZ = 20
    private const val SCAN_Y = 10
    private const val HIGHLIGHTED_BLOCKS = 250
    /** How far the thrown pickaxe flies; farther or hidden veins get an arrow. */
    private const val BLAST_REACH = 20.0
    /** Rough blast size for shortlisting centres and the ripple; [pickobulusBreaks] decides what really breaks. */
    private const val BLAST_RADIUS = 3

    private var miningSpread: Int? = null
    private var gemstoneSpread: Int? = null
    private var nextBlock: Pair<BlockPos, MiningBlock>? = null
    private var nextShownAt = 0L
    /** Blocks the last break's spread took, with when; drawn as a short fading burst. */
    private var spreadBurst: Pair<List<BlockPos>, Long>? = null
    private class Blast(val centre: BlockPos, val hits: List<BlockPos>, val reachable: Boolean)
    /** The spot that breaks the most for your open commissions. */
    private var blast: Blast? = null
    private var blastFrom: BlockPos? = null
    private var blastShownAt = 0L
    /** What a Pickobulus thrown right now would break. */
    private var aim: Blast? = null
    private var aimShownAt = 0L
    private var pickoCooling = false
    private val blastDirectionContent by lazy { hudPanel(decorated = false) }
    private val blastDirectionPreview by lazy { hudPanel(decorated = false).updateTextLines(listOf("§6↗ §7Pickobulus · 14 blocks · 12m")) }
    private val BLAST_MINES_BLOCKS by lazy {
        setOf(Blocks.STONE, Blocks.COBBLESTONE, Blocks.POLISHED_DIORITE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, vanillaBlock("cyan_terracotta"),
            vanillaBlock("light_blue_wool"), vanillaBlock("gray_wool"), Blocks.LAPIS_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.REDSTONE_BLOCK,
            Blocks.COAL_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.GOLD_ORE, Blocks.IRON_ORE, Blocks.COAL_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHERRACK, Blocks.GLOWSTONE, Blocks.OBSIDIAN, Blocks.END_STONE)
    }
    private val BLAST_TUNNEL_BLOCKS by lazy {
        setOf(Blocks.POLISHED_DIORITE, Blocks.INFESTED_STONE, vanillaBlock("light_gray_carpet"), Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, vanillaBlock("light_blue_wool"),
            vanillaBlock("gray_wool"), vanillaBlock("cyan_terracotta"), vanillaBlock("brown_terracotta"), Blocks.TERRACOTTA, Blocks.SMOOTH_RED_SANDSTONE, Blocks.INFESTED_COBBLESTONE, Blocks.CLAY)
    }

    /** Keeps pointing at the spot once it is off screen, which the marks in the world cannot do. */
    val blastDirectionWidget = Widget("pickobulus_direction", "Pickobulus Direction", 480, 230, 1f, Anchor.CENTER,
        previewBuilder = { blastDirectionPreview.takeIf { MiningConfig.pickobulusHelper } }) {
        val b = blast ?: return@Widget null
        val player = MC.instance.player ?: return@Widget null
        val toSpot = Vec3.atCenterOf(b.centre).subtract(player.eyePosition)
        val halfHeight = tan(Math.toRadians(MC.instance.options.fov().get() / 2.0))
        val window = MC.instance.window
        val arrow = offScreenArrow(toSpot, player.lookAngle, halfHeight * window.width / window.height.coerceAtLeast(1), halfHeight) ?: return@Widget null
        blastDirectionContent.updateTextLines(listOf("§6$arrow §7Pickobulus · ${b.hits.size} blocks · ${toSpot.length().toInt()}m"))
    }
    private var commissionBlocks: List<BlockPos> = emptyList()
    private var commissionMobs: List<Entity> = emptyList()
    private var ticks = 0
    /** Read from chunk-build threads through [gemstoneLook]. */
    @Volatile private var gemstoneWool = false
    private val GEMSTONE_ISLANDS = setOf(SkyBlockIsland.CRYSTAL_HOLLOWS, SkyBlockIsland.DWARVEN_MINES, SkyBlockIsland.MINESHAFT)
    private val GEMSTONE_WOOL by lazy {
        fun block(name: String) = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name))
        // Written out because SkyBlockAPI files Jasper under brown, while the Fairy Grotto's Jasper is magenta.
        listOf("red", "light_blue", "lime", "orange", "purple", "yellow", "magenta", "white", "green", "brown", "black", "blue").flatMap { colour ->
            val wool = block("${colour}_wool").defaultBlockState()
            listOf(block("${colour}_stained_glass") to wool, block("${colour}_stained_glass_pane") to wool)
        }.toMap()
    }

    /** Glass becomes the wool model; a pane keeps its own shape and only takes on the wool texture. */
    fun gemstoneModel(state: BlockState, original: BlockStateModel, models: BlockStateModelSet, panes: MutableMap<BlockState, BlockStateModel>): BlockStateModel {
        if (!gemstoneWool) return original
        val wool = models.get(GEMSTONE_WOOL[state.block] ?: return original)
        return if (state.block is StainedGlassPaneBlock) panes.computeIfAbsent(state) { woolPane(original, wool) } else wool
    }

    private fun woolPane(pane: BlockStateModel, wool: BlockStateModel): BlockStateModel {
        val random = RandomSource.create()
        val woolPart = ArrayList<BlockStateModelPart>().also { wool.collectParts(random, it) }.firstOrNull() ?: return pane
        val material = Direction.entries.firstNotNullOfOrNull { woolPart.getQuads(it).firstOrNull() }?.materialInfo() ?: return pane
        val to = material.sprite()
        fun retexture(quad: BakedQuad): BakedQuad {
            val from = quad.materialInfo().sprite()
            fun uv(packed: Long) = UVPair.pack(remapUv(UVPair.unpackU(packed), from.u0, from.u1, to.u0, to.u1), remapUv(UVPair.unpackV(packed), from.v0, from.v1, to.v0, to.v1))
            return BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(), uv(quad.packedUV0()), uv(quad.packedUV1()), uv(quad.packedUV2()), uv(quad.packedUV3()), quad.direction(), material)
        }
        val quads = QuadCollection.Builder()
        ArrayList<BlockStateModelPart>().also { pane.collectParts(random, it) }.forEach { part ->
            Direction.entries.forEach { side -> part.getQuads(side).forEach { quads.addCulledFace(side, retexture(it)) } }
            part.getQuads(null).forEach { quads.addUnculledFace(retexture(it)) }
        }
        return SingleVariant(SimpleModelWrapper(quads.build(), woolPart.useAmbientOcclusion(), woolPart.particleMaterial()))
    }

    private fun updateGemstoneWool() {
        if (MC.instance.level == null) return
        val wanted = MiningConfig.gemstoneWool && LocationAPI.island in GEMSTONE_ISLANDS
        if (wanted == gemstoneWool) return
        gemstoneWool = wanted
        MC.instance.reloadChunks()
    }

    @Subscription
    fun onTabWidget(event: TabWidgetChangeEvent) {
        when (event.widget) {
            TabWidget.STATS -> {
                miningSpread = parseStat(event.new, "Mining Spread")
                gemstoneSpread = parseStat(event.new, "Gemstone Spread")
            }
            TabWidget.PICKAXE_ABILITY -> event.new.firstNotNullOfOrNull(::pickobulusCooling)?.let { pickoCooling = it }
            else -> {}
        }
    }

    /** Covers players who removed the Pickaxe Ability line from their tab list. */
    @Subscription
    fun onPickobulusChat(event: ChatReceivedEvent.Pre) {
        when (event.text.trim()) {
            "You used your Pickobulus Pickaxe Ability!" -> pickoCooling = true
            "Pickobulus is now available!" -> pickoCooling = false
        }
    }

    @Subscription
    fun onTick(event: TickEvent) {
        ticks++
        updateGemstoneWool()
        if (MiningConfig.spreadPredictor) predictSpread() else { nextBlock = null; mining = null; spreadBurst = null }
        // The commission scan covers ~35k blocks, so it refreshes once a second; Pickobulus ranks the same blocks.
        val picko = MiningConfig.pickobulusHelper && !pickoCooling && pickobulusHeld()
        val blocksWanted = MiningConfig.commissionHighlight || picko
        if (!blocksWanted) commissionBlocks = emptyList() else if (ticks % 20 == 0) commissionBlocks = scanCommissionBlocks()
        if (!picko) blast = null else if (ticks % 20 == 0) blast = findBlast()
        val aimed = if (picko) aimedBlast() else null
        if (aimed != null && aim == null) aimShownAt = System.nanoTime()
        aim = aimed
        if (!MiningConfig.commissionHighlight) commissionMobs = emptyList() else if (ticks % 5 == 0) commissionMobs = findCommissionMobs()
        if (Config.devMode && MiningConfig.recordSpread) recordSpread() else { snapshot = null; pending = null }
    }

    // ---- Developer spread recorder: raw samples for working out how Hypixel picks spread blocks ----
    // It diffs world snapshots on the client thread; SkyBlockAPI's mined events come from packet handling and missed every break.

    private const val SAMPLE_RADIUS = 3
    private const val SAMPLE_WINDOW = 10
    private const val MAX_SAMPLES = 1500

    private class Snapshot(val centre: BlockPos, val name: String, val blocks: List<Block>, val sample: JsonObject) {
        var tick = 0
        val brokenAt = HashMap<Int, Int>()
    }
    private var snapshot: Snapshot? = null
    private var pending: Snapshot? = null
    private val sampleFile = AtomicJsonFile(SkyMyce.configPath.resolve("mining_spread_samples.json"), 4 * 1024 * 1024)
    private val sampleWriter = StateWriter<JsonArray>({ sampleFile.write(it) }, { action -> Scheduling.schedule(500.milliseconds) { action() } },
        { SkyMyce.logger.warn("Could not save Mining Spread samples; previous file retained") })
    private val samples by lazy { runCatching { sampleFile.read()?.asJsonArray }.getOrNull() ?: JsonArray() }

    private fun gone(block: Block) = block == Blocks.AIR || block == Blocks.BEDROCK
    private fun around(centre: BlockPos) = BlockPos.betweenClosed(centre.offset(-SAMPLE_RADIUS, -SAMPLE_RADIUS, -SAMPLE_RADIUS), centre.offset(SAMPLE_RADIUS, SAMPLE_RADIUS, SAMPLE_RADIUS))

    private fun recordSpread() {
        val level = MC.instance.level ?: return
        val player = MC.instance.player ?: return
        // The block being mined just broke: watch its surroundings for the spread.
        snapshot?.takeIf { pending == null && gone(level.getBlockState(it.centre).block) }?.let { pending = it.also { s -> s.tick = ticks }; snapshot = null }
        pending?.let { shot ->
            around(shot.centre).forEachIndexed { i, pos -> if (i !in shot.brokenAt && !gone(shot.blocks[i]) && gone(level.getBlockState(pos).block)) shot.brokenAt[i] = ticks - shot.tick }
            if (ticks >= shot.tick + SAMPLE_WINDOW) { finishSample(shot); pending = null }
            return
        }
        val hit = (MC.instance.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK && MC.instance.options.keyAttack.isDown }
        val centreBlock = hit?.let { level.getBlockState(it.blockPos).block }
        if (hit == null || centreBlock == null || gone(centreBlock)) { snapshot = null; return }
        val type = MiningBlock.currentlyActiveBlocks.firstOrNull { centreBlock in it.blocks }
        val eye = player.eyePosition.subtract(Vec3.atCenterOf(hit.blockPos))
        val look = player.lookAngle
        snapshot = Snapshot(hit.blockPos.immutable(), type?.name ?: BuiltInRegistries.BLOCK.getKey(centreBlock).toString(),
            around(hit.blockPos).map { level.getBlockState(it).block }, JsonObject().apply {
            addProperty("island", LocationAPI.island?.name)
            addProperty("block", type?.name)
            addProperty("family", type?.family?.name)
            miningSpread?.let { addProperty("miningSpread", it) }
            gemstoneSpread?.let { addProperty("gemstoneSpread", it) }
            addProperty("face", hit.direction.name)
            add("eye", JsonArray().apply { add(eye.x); add(eye.y); add(eye.z) })
            add("look", JsonArray().apply { add(look.x); add(look.y); add(look.z) })
        })
    }

    private fun finishSample(shot: Snapshot) {
        val centreIndex = shot.blocks.size / 2
        val broken = shot.brokenAt.filterKeys { it != centreIndex }
        val (palette, grid) = encodeGrid(shot.blocks.map { BuiltInRegistries.BLOCK.getKey(it).toString() }) ?: return
        val side = SAMPLE_RADIUS * 2 + 1
        shot.sample.apply {
            addProperty("t", System.currentTimeMillis())
            add("palette", JsonArray().apply { palette.forEach(::add) })
            addProperty("grid", grid)
            add("broken", JsonArray().apply { broken.forEach { (i, dt) -> add(JsonArray().apply {
                add(i % side - SAMPLE_RADIUS); add(i / side % side - SAMPLE_RADIUS); add(i / (side * side) - SAMPLE_RADIUS); add(dt)
            }) } })
        }
        samples.add(shot.sample)
        while (samples.size() > MAX_SAMPLES) samples.remove(0)
        sampleWriter.submit(samples.deepCopy())
        Utils.displayDevMessage("Spread sample ${samples.size()}: ${shot.name} took ${broken.size} more")
    }

    /** The block being broken, the tick it was last held, and its spreadable neighbours as they were. */
    private class Breaking(val pos: BlockPos, val type: MiningBlock, var tick: Int, var around: List<BlockPos>)
    private var mining: Breaking? = null

    /** Spread only works with a pickaxe or drill, never in the Catacombs (dungeon pickaxes are their own category). */
    private fun miningToolHeld(): Boolean {
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) return false
        val category = runCatching { MC.instance.player?.mainHandItem?.getData(DataTypes.CATEGORY) }.getOrNull() ?: return false
        return category == SkyBlockCategory.PICKAXE || category == SkyBlockCategory.DRILL
    }

    /** Hypixel lists the selected pickaxe ability on the tool, so this is false for anyone on Mining Speed Boost or similar. */
    private fun pickobulusHeld() = MC.instance.player?.mainHandItem?.getRawLore().orEmpty().any { "Ability: Pickobulus" in it }

    private fun predictSpread() {
        val level = MC.instance.level ?: return
        nextBlock = nextBlock?.takeIf { (pos, type) -> level.getBlockState(pos).block in type.blocks }
        if (!miningToolHeld()) { nextBlock = null; mining = null; return }
        // Only a real break counts: the block vanished while you were still breaking it, not one you tapped and left.
        mining?.let { breaking ->
            if (!gone(level.getBlockState(breaking.pos).block)) return@let
            mining = null
            if (ticks - breaking.tick > 2) return@let
            // Spread lands in the same tick as the break (every recorded sample), so the world shows what it took.
            val taken = breaking.around.filter { gone(level.getBlockState(it).block) }
            if (taken.isNotEmpty() && ThemeConfig.animationMode != GuiMotion.OFF) spreadBurst = taken to System.nanoTime()
            val next = pickFollowUp(breaking.pos, breaking.type)
            if (next != nextBlock?.first) nextShownAt = System.nanoTime()
            nextBlock = next?.let { it to breaking.type }
        }
        val hit = (MC.instance.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK && MC.instance.options.keyAttack.isDown }?.blockPos?.immutable()
        val type = hit?.let { pos -> MiningBlock.currentlyActiveBlocks.firstOrNull { level.getBlockState(pos).block in it.blocks } }
            ?.takeIf { spreadApplies(it.family) }
        if (hit == null || type == null) return
        val around = { NEIGHBOURS_26.map { (x, y, z) -> hit.offset(x, y, z) }.filter { level.getBlockState(it).block in type.family.blocks } }
        val current = mining
        if (current != null && current.pos == hit) { current.tick = ticks; if (ticks % 4 == 0) current.around = around() }
        else mining = Breaking(hit, type, ticks, around())
    }

    private fun pickFollowUp(mining: BlockPos, type: MiningBlock): BlockPos? {
        val level = MC.instance.level ?: return null
        val player = MC.instance.player ?: return null
        fun sameType(pos: BlockPos) = level.getBlockState(pos).block in type.blocks
        fun open(pos: BlockPos) = gone(level.getBlockState(pos).block)
        val eye = player.eyePosition
        val reach = player.blockInteractionRange()
        val r = ceil(reach).toInt()
        val origin = BlockPos.containing(eye)
        val look = player.lookAngle
        // The FOV setting is vertical; widen it to the screen's horizontal half-angle so "on screen" covers the sides too.
        val window = MC.instance.window
        val viewCone = cos(atan(tan(Math.toRadians(MC.instance.options.fov().get() / 2.0)) * window.width / window.height.coerceAtLeast(1)))
        // Only blocks on screen with a clear line through air: a mined block turns to bedrock in the Dwarven Mines, so nothing is seen through it.
        val candidates = BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r)).mapNotNull { pos ->
            pos.immutable().takeIf { it != mining && sameType(it) && Vec3.atCenterOf(it).distanceTo(eye) <= reach &&
                look.dot(Vec3.atCenterOf(it).subtract(eye).normalize()) >= viewCone &&
                canSee(eye, it) { p -> level.getBlockState(p).isAir } }
        }
        val spread = spreadFor(type.family == MiningBlockFamily.GEMSTONES, miningSpread, gemstoneSpread, MiningConfig.spreadFallback, MiningConfig.gemstoneSpreadFallback)
        // Spread takes any block of the mined block's family (Titanium took Mithril and back), never stone.
        return bestSpreadFollowUp(mining, candidates, { level.getBlockState(it).block in type.family.blocks }, ::open, spread.toDouble(), spreadDone = true) {
            1 - look.dot(Vec3.atCenterOf(it).subtract(eye).normalize())
        }
    }

    /** Open commissions' targets that are still in progress. */
    private fun openCommissions() = CommissionsAPI.commissions.filter { it.progress < 1f }.mapNotNull { commissionTarget(it.name) }

    /** Every block an open commission wants within the scan box around you, in this area's ore types. */
    private fun scanCommissionBlocks(): List<BlockPos> {
        val level = MC.instance.level ?: return emptyList()
        val player = MC.instance.player ?: return emptyList()
        val ores = openCommissions().flatMap { it.ores }.toSet()
        if (ores.isEmpty()) return emptyList()
        val blocks = MiningBlock.currentlyActiveBlocks.filter { block -> ores.any { oreMatches(block.name, block.family == MiningBlockFamily.GEMSTONES, it) } }
            .flatMap { it.blocks }.toSet()
        if (blocks.isEmpty()) return emptyList()
        val origin = player.blockPosition()
        return BlockPos.betweenClosed(origin.offset(-SCAN_XZ, -SCAN_Y, -SCAN_XZ), origin.offset(SCAN_XZ, SCAN_Y, SCAN_XZ))
            .mapNotNull { pos -> pos.immutable().takeIf { level.getBlockState(it).block in blocks } }
    }

    /** The Mineshafts and Hollows break everything; elsewhere only these blocks, and most of them stay solid (bedrock), so they never expose what is behind them. */
    private fun blastRule(): (Block) -> BlastBlock {
        val island = LocationAPI.island
        val area = LocationAPI.area
        fun glass(block: Block) = block is StainedGlassBlock || block is StainedGlassPaneBlock
        return when {
            island == SkyBlockIsland.MINESHAFT || island == SkyBlockIsland.CRYSTAL_HOLLOWS -> { _ -> BlastBlock.LEAVES_AIR }
            area == SkyBlockAreas.GLACITE_TUNNELS || area == SkyBlockAreas.GREAT_LAKE -> { block ->
                when {
                    block == Blocks.PACKED_ICE || glass(block) -> BlastBlock.LEAVES_AIR
                    block in BLAST_TUNNEL_BLOCKS -> BlastBlock.LEAVES_SOLID
                    else -> BlastBlock.KEEP
                }
            }
            island == SkyBlockIsland.DWARVEN_MINES || island == SkyBlockIsland.GOLD_MINES || island == SkyBlockIsland.DEEP_CAVERNS -> { block ->
                if (block in BLAST_MINES_BLOCKS || glass(block)) BlastBlock.LEAVES_SOLID else BlastBlock.KEEP
            }
            else -> { _ -> BlastBlock.KEEP }
        }
    }

    private fun blastBreaks(centre: BlockPos): List<BlockPos> {
        val level = MC.instance.level ?: return emptyList()
        val rule = blastRule()
        return pickobulusBreaks(centre) { pos ->
            val state = level.getBlockState(pos)
            if (state.isAir) BlastBlock.AIR else if (state.`is`(Blocks.BEDROCK)) BlastBlock.KEEP else rule(state.block)
        }
    }

    /** Where the pickaxe is thrown from: Skyblocker's eye heights plus the offset it measured (youtu.be/5hdDrr6jk4E). */
    private fun blastStart(player: Player) = player.position().add(0.0, (if (player.isShiftKeyDown) 1.27 else 1.62) + 0.53625, 0.0)

    private fun aimedBlast(): Blast? {
        val level = MC.instance.level ?: return null
        val player = MC.instance.player ?: return null
        val start = blastStart(player)
        val hit = level.clip(ClipContext(start, start.add(player.lookAngle.scale(BLAST_REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return null
        return blastBreaks(hit.blockPos).takeIf { it.isNotEmpty() }?.let { Blast(hit.blockPos, it, true) }
    }

    /** The best Pickobulus centre among nearby veins: marked where you can hit it, otherwise an arrow leads to it. */
    private fun findBlast(): Blast? {
        val level = MC.instance.level ?: return null
        val player = MC.instance.player ?: return null
        if (commissionBlocks.isEmpty()) return null
        val start = blastStart(player)
        val targets = commissionBlocks.toHashSet()
        // shortcut: only the 64 densest target blocks are simulated as centres; a hard stone block between two veins could score higher.
        val ranked = rankBlastCentres(commissionBlocks, BLAST_RADIUS).take(64)
            .map { (pos, _) -> blastBreaks(pos).let { hits -> Triple(pos, hits, hits.count { it in targets }) } }
            .sortedByDescending { it.third }
        val best = ranked.firstOrNull()?.takeIf { it.third > 0 } ?: return null
        // The thrown pickaxe lands on the first block along your aim, so a hittable centre must be visible and near.
        val reachable = ranked.firstOrNull { (pos, _, score) ->
            score >= best.third * 0.75 && Vec3.atCenterOf(pos).distanceTo(start) <= BLAST_REACH &&
                level.clip(ClipContext(start, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).blockPos == pos
        }
        val chosen = reachable ?: best
        if (chosen.first != blast?.centre) { blastFrom = blast?.centre; blastShownAt = System.nanoTime() }
        return Blast(chosen.first, chosen.second, reachable != null)
    }

    /** Living mobs whose own or floating name matches an open slayer commission; real players never count. */
    private fun findCommissionMobs(): List<Entity> {
        val level = MC.instance.level ?: return emptyList()
        val player = MC.instance.player ?: return emptyList()
        val names = openCommissions().flatMap { it.mobs }
        if (names.isEmpty()) return emptyList()
        fun named(entity: Entity) = entity.customName?.string?.replace(LEGACY_CODES, "")?.let { text -> names.any { text.contains(it, ignoreCase = true) } } == true
        fun mob(entity: Entity) = entity is LivingEntity && entity !is ArmorStand && entity.isAlive && !(entity is Player && entity.uuid.version() == 4)
        val found = LinkedHashSet<Entity>()
        for (entity in level.entitiesForRendering()) {
            if (entity.distanceToSqr(player) > 48.0 * 48.0) continue
            if (mob(entity) && named(entity)) { found += entity; continue }
            // SkyBlock names most mobs with an armor stand floating just above them.
            if (entity is ArmorStand && named(entity)) level.getEntities(entity, entity.boundingBox.inflate(0.6, 3.0, 0.6), ::mob)
                .filter { it.y <= entity.y }.minByOrNull { it.distanceToSqr(entity) }?.let(found::add)
        }
        return found.toList()
    }

    @Subscription
    fun render(event: RenderWorldEvent.AfterTranslucent) {
        val next = nextBlock?.first
        val blast = blast
        val aim = aim
        val burst = spreadBurst
        val highlight = if (MiningConfig.commissionHighlight) exposedNearest(commissionBlocks) else emptyList()
        val mobs = commissionMobs
        if (next == null && blast == null && aim == null && burst == null && highlight.isEmpty() && mobs.isEmpty()) return
        val now = System.nanoTime()
        val mode = ThemeConfig.animationMode
        val burstNanos = if (mode == GuiMotion.REDUCED) 200_000_000L else 450_000_000L
        MC.instance.collectFrameGizmos().use {
            highlight.forEach { Gizmos.cuboid(AABB(it).inflate(0.002), GizmoStyle.fill(HudTheme.alpha(COMMISSION_COLOR, 22))) }
            mobs.forEach { mob -> Gizmos.cuboid(mob.boundingBox.inflate(0.05), GizmoStyle.stroke(HudTheme.alpha(MOB_COLOR, 90), 2f)).setAlwaysOnTop() }
            // Blocks the spread just took flash and expand away, so you see each proc.
            burst?.let { (taken, at) ->
                val t = (now - at).toDouble() / burstNanos
                if (t >= 1.0) spreadBurst = null
                else taken.forEach { Gizmos.cuboid(AABB(it).inflate(0.02 + 0.18 * t), GizmoStyle.fill(HudTheme.alpha(SPREAD_COLOR, (45 * (1 - t)).toInt()))) }
            }
            next?.let {
                // The next block's outline settles in from slightly larger, instead of popping.
                val t = if (mode == GuiMotion.OFF) 1.0 else ((now - nextShownAt) / 140_000_000.0).coerceIn(0.0, 1.0)
                val ease = 1 - (1 - t) * (1 - t)
                Gizmos.cuboid(AABB(it).inflate(0.004 + 0.12 * (1 - ease)), GizmoStyle.stroke(HudTheme.alpha(SPREAD_COLOR, (40 + 60 * ease).toInt()), 3f)).setAlwaysOnTop()
            }
            val colour = MiningConfig.pickobulusColor and 0xFFFFFF
            // Exactly what a blast at your crosshair breaks.
            aim?.let { a ->
                val seconds = (now - aimShownAt) / 1e9
                a.hits.forEach {
                    val alpha = blastHitAlpha(sqrt(it.distSqr(a.centre)), seconds, BLAST_RADIUS, mode)
                    if (alpha > 0) Gizmos.cuboid(AABB(it).inflate(0.002), GizmoStyle.fill(HudTheme.alpha(colour, alpha)))
                }
                Gizmos.billboardText("Pickobulus · ${a.hits.size} blocks", Vec3.atCenterOf(a.centre).add(0.0, 0.9, 0.0),
                    TextGizmo.Style.forColorAndCentered(HudTheme.alpha(colour, 100)).withScale(0.4f)).setAlwaysOnTop()
            }
            blast?.let { b ->
                val seconds = (now - blastShownAt) / 1e9
                val moving = mode != GuiMotion.OFF
                // The marker glides to a new spot instead of jumping, and breathes gently once it is there.
                val glide = if (moving) (seconds / 0.2).coerceIn(0.0, 1.0).let { 1 - (1 - it) * (1 - it) } else 1.0
                val from = Vec3.atLowerCornerOf(blastFrom ?: b.centre)
                val at = from.add(Vec3.atLowerCornerOf(b.centre).subtract(from).scale(glide))
                val breathe = if (moving && mode != GuiMotion.REDUCED) 0.5 + 0.5 * sin(seconds * 3.0) else 0.0
                val marker = AABB(at, at.add(1.0, 1.0, 1.0)).inflate(0.004 + 0.035 * breathe)
                Gizmos.cuboid(marker, GizmoStyle.stroke(HudTheme.alpha(colour, (75 + 25 * breathe).toInt()), 3f)).setAlwaysOnTop()
                val label = Vec3.atCenterOf(b.centre).add(0.0, 0.9, 0.0)
                if (b.reachable) {
                    if (b.centre != aim?.centre) Gizmos.billboardText("Best spot · ${b.hits.size} blocks", label,
                        TextGizmo.Style.forColorAndCentered(HudTheme.alpha(colour, 100)).withScale(0.4f)).setAlwaysOnTop()
                } else MC.instance.player?.let { player ->
                    // Out of reach or hidden: point the way from just in front of you to the best vein.
                    val target = Vec3.atCenterOf(b.centre)
                    val start = player.eyePosition.add(player.lookAngle.scale(1.2)).add(0.0, -0.4, 0.0)
                    Gizmos.arrow(start, target, HudTheme.alpha(colour, (65 + 25 * breathe).toInt()), 2f).setAlwaysOnTop()
                    Gizmos.billboardText("Best spot · ${b.hits.size} blocks · ${target.distanceTo(player.eyePosition).toInt()}m", label,
                        TextGizmo.Style.forColorAndCentered(HudTheme.alpha(colour, 100)).withScale(0.5f)).setAlwaysOnTop()
                }
            }
        }
    }

    /** Commission blocks with an open face, nearest first and capped, so the highlight shows minable blocks without clutter. */
    private fun exposedNearest(blocks: List<BlockPos>): List<BlockPos> {
        val level = MC.instance.level ?: return emptyList()
        val player = MC.instance.player ?: return emptyList()
        if (blocks.isEmpty()) return emptyList()
        if (exposedFor === blocks) return exposed
        exposedFor = blocks
        exposed = blocks.filter { pos -> Direction.entries.any { gone(level.getBlockState(pos.relative(it)).block) } }
            .sortedBy { it.distSqr(player.blockPosition()) }.take(HIGHLIGHTED_BLOCKS)
        return exposed
    }
    private var exposedFor: List<BlockPos>? = null
    private var exposed: List<BlockPos> = emptyList()
    private val LEGACY_CODES = Regex("§.")

    @Subscription
    fun onIslandChange(event: IslandChangeEvent) {
        nextBlock = null
        mining = null
        spreadBurst = null
        blast = null
        aim = null
        pickoCooling = false
        commissionBlocks = emptyList()
        commissionMobs = emptyList()
        if (event.old == SkyBlockIsland.CRYSTAL_HOLLOWS) {
            totalTime += System.currentTimeMillis() - timeSince
        }
        if (event.new == SkyBlockIsland.CRYSTAL_HOLLOWS) {
            timeSince = System.currentTimeMillis()
        }
    }

    @Subscription
    @OnlyIn(SkyBlockIsland.CRYSTAL_HOLLOWS)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (MiningConfig.autoNucleusWarp && crystalRegex.matches(event.text)) {
            schedule(Random.nextDouble(MiningConfig.autoNucleusWarpMin, MiningConfig.autoNucleusWarpMax).seconds) {
                sendCommand("warp nuc", true)
            }
        }

        if (MiningConfig.balNotify && PartyAPI.inParty) {
            when (event.text) {
                "The ground starts to shake and the lava bubbles..." -> sendMessage("Bal is spawning!", ChatChannel.PARTY)

                "The boss's outer shell looks to be weakening!" -> sendMessage("Bal 75% HP", ChatChannel.PARTY)

                "Half way there! The boss is starting to become weaker!" -> sendMessage("Bal 50% HP", ChatChannel.PARTY)

                "Nearly there! The boss is shaking, it can't last much longer!" -> sendMessage("Bal 25% HP", ChatChannel.PARTY)

                "The boss looks weak and tired and retreats into the lava..." -> sendMessage("Bal dead!", ChatChannel.PARTY)
            }
        }
    }
}

internal fun parseStat(lines: List<String>, stat: String) =
    lines.firstNotNullOfOrNull { Regex("$stat: \\D*([\\d,]+)").find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }

/** The ore of a collector commission, e.g. "Umber Collector" -> UMBER and "Onyx Gemstone Collector" -> ONYX, matching [MiningBlock] name suffixes. */
internal fun collectorOre(name: String) = name.takeIf { it.endsWith("Collector") }?.substringBefore(' ')?.uppercase()

/** Mining Spread works on ores and gemstones; stone and Hard Stone are only ever broken by hand. */
/** Maps one texture coordinate from one atlas sprite's range to another's. */
internal fun remapUv(value: Float, from0: Float, from1: Float, to0: Float, to1: Float) = to0 + (value - from0) / (from1 - from0) * (to1 - to0)

/** Null when the target is on screen, otherwise the arrow toward it relative to where you look. */
internal fun offScreenArrow(toTarget: Vec3, look: Vec3, halfWidthTan: Double, halfHeightTan: Double): String? {
    val right = look.cross(Vec3(0.0, 1.0, 0.0)).normalize()
    val up = right.cross(look)
    val x = toTarget.dot(right)
    val y = toTarget.dot(up)
    val z = toTarget.dot(look)
    if (z > 0 && abs(x) <= z * halfWidthTan && abs(y) <= z * halfHeightTan) return null
    return "↑↗→↘↓↙←↖"[Math.floorMod(Math.round(atan2(x, y) / (Math.PI / 4)).toInt(), 8)].toString()
}

/** How a block reacts to Pickobulus: empty, untouched, broken but left solid (it turns to bedrock), or broken and left open. */
internal enum class BlastBlock { AIR, KEEP, LEAVES_SOLID, LEAVES_AIR }

/**
 * What a Pickobulus landing on [hit] breaks, worked out like Skyblocker's PickobulusHelper (LGPL-3.0, github.com/SkyblockerMod/Skyblocker):
 * every breakable block in the box from -3 to +2 around it that touches air, where a block left open exposes the ones scanned after it.
 */
internal fun pickobulusBreaks(hit: BlockPos, kindAt: (BlockPos) -> BlastBlock): List<BlockPos> {
    fun index(x: Int, y: Int, z: Int) = x * 64 + y * 8 + z
    val kinds = Array(512) { kindAt(hit.offset(it / 64 - 4, it / 8 % 8 - 4, it % 8 - 4)) }
    val broken = ArrayList<BlockPos>()
    for (x in 1..6) for (y in 1..6) for (z in 1..6) {
        val kind = kinds[index(x, y, z)]
        if (kind == BlastBlock.AIR || kind == BlastBlock.KEEP) continue
        val exposed = listOf(index(x - 1, y, z), index(x + 1, y, z), index(x, y - 1, z), index(x, y + 1, z), index(x, y, z - 1), index(x, y, z + 1)).any { kinds[it] == BlastBlock.AIR }
        if (!exposed) continue
        broken += hit.offset(x - 4, y - 4, z - 4)
        if (kind == BlastBlock.LEAVES_AIR) kinds[index(x, y, z)] = BlastBlock.AIR
    }
    return broken
}

/** From a tab list line under Pickaxe Ability: whether Pickobulus is cooling down, null for any other line. */
internal fun pickobulusCooling(line: String) = line.trim().takeIf { it.startsWith("Pickobulus: ") }?.let { it != "Pickobulus: Available" }

/** Blast blocks ripple in from the centre, then a soft wave keeps travelling outward so the radius reads at a glance. */
internal fun blastHitAlpha(distance: Double, seconds: Double, radius: Int, mode: GuiMotion): Int {
    if (mode == GuiMotion.OFF) return 25
    val appear = ((seconds - distance * 0.05) / 0.2).coerceIn(0.0, 1.0)
    val wave = if (mode == GuiMotion.REDUCED) 0.0 else (1 - abs(distance - seconds % 1.6 / 1.6 * (radius + 1))).coerceAtLeast(0.0)
    return ((18 + 22 * wave) * appear).toInt()
}

internal fun spreadApplies(family: MiningBlockFamily) = family != MiningBlockFamily.VANILLA_BLOCKS && family != MiningBlockFamily.HARD_STONE

/** What a commission asks for: ore names in [MiningBlock] naming ("MITHRIL", "RUBY", "GEMSTONE" = any gemstone) or mob name parts. */
internal data class CommissionTarget(val ores: Set<String> = emptySet(), val mobs: List<String> = emptyList())

internal fun commissionTarget(name: String): CommissionTarget? {
    collectorOre(name)?.let { return CommissionTarget(ores = setOf(it)) }
    when {
        name == "Hard Stone Miner" -> return CommissionTarget(ores = setOf("HARD_STONE"))
        name.endsWith("Mithril") || name.startsWith("Mithril") -> return CommissionTarget(ores = setOf("MITHRIL"))
        name.endsWith("Titanium") || name.startsWith("Titanium") -> return CommissionTarget(ores = setOf("TITANIUM"))
    }
    val mob = name.removeSuffix(" Slayer").removeSuffix(" Puncher").takeIf { it != name && it != "Mines" } ?: return null
    return CommissionTarget(mobs = listOf(when (mob) {
        // The Glacite slayer commission counts every Glacite mob, including the Mineshaft's Bowmen, Casters and Mages.
        "Glacite Walker" -> "Glacite"
        "Boss Corleone" -> "Corleone"
        "Team Treasurite Member" -> "Team Treasurite"
        else -> mob
    }))
}

/** Whether a [MiningBlock] named [block] is the commission [ore]: exact, a tier variant (LOW_TIER_MITHRIL) or a variant family (HARD_STONE_*). */
internal fun oreMatches(block: String, gemstone: Boolean, ore: String) =
    if (ore == "GEMSTONE") gemstone else block == ore || block.endsWith("_$ore") || block.startsWith("${ore}_")

/**
 * Chance Mining Spread takes one exposed block among the [pool] spreadable blocks around a broken one.
 * Fitted to 258 recorded Mithril/Titanium breaks at 288 spread: ~22% each with nine candidates. The +10 stands in for the
 * fifth of picks that land up to two blocks beyond the 26-neighbourhood.
 */
// shortcut: fitted at one spread value and on Mithril only; refit with samples at other spreads, gemstones and Glacite Tunnels ores.
internal fun spreadTakeChance(spread: Double, pool: Double) = if (pool <= 0) 0.0 else (spread / 100 * 1.45 / (pool + 10)).coerceIn(0.0, 1.0)

private val NEIGHBOURS_26 = (-1..1).flatMap { x -> (-1..1).flatMap { y -> (-1..1).map { z -> Triple(x, y, z) } } }.filter { it != Triple(0, 0, 0) }

/** Blocks spread can take once [centre] and [gone] are broken: same family ([spreadKind]) with an open face. Spread never took a buried block (0 of 997). */
internal fun spreadPool(centre: BlockPos, gone: Set<BlockPos>, spreadKind: (BlockPos) -> Boolean, open: (BlockPos) -> Boolean) =
    NEIGHBOURS_26.map { (x, y, z) -> centre.offset(x, y, z) }
        .filter { it !in gone && spreadKind(it) && Direction.entries.any { d -> it.relative(d).let { n -> n in gone || open(n) } } }

/** The candidate worth mining once [mining] breaks: likely to survive its spread (unless [spreadDone]), with the most spread of its own; near-ties go to the easiest aim. */
internal fun bestSpreadFollowUp(mining: BlockPos, candidates: Collection<BlockPos>, spreadKind: (BlockPos) -> Boolean, open: (BlockPos) -> Boolean,
                                spread: Double, spreadDone: Boolean = false, aimCost: (BlockPos) -> Double): BlockPos? {
    val first = spreadPool(mining, setOf(mining), spreadKind, open).toSet()
    // Once [spreadDone], the world already shows what the spread took, so every candidate survives.
    val take = if (spreadDone) 0.0 else spreadTakeChance(spread, first.size.toDouble())
    fun survives(pos: BlockPos) = if (pos in first) 1 - take else 1.0
    fun value(pos: BlockPos) = spreadPool(pos, setOf(mining, pos), spreadKind, open).sumOf(::survives).let { n -> minOf(spread / 100, n * spreadTakeChance(spread, n)) }
    val scored = candidates.filter { it != mining }.associateWith { survives(it) * (1 + value(it)) }
    val best = scored.values.maxOrNull() ?: return null
    // Within 5% of the best, re-aiming costs more than the difference, so the easiest block to aim at wins.
    return scored.filterValues { it >= best * 0.95 }.keys.minBy(aimCost)
}

/** Blast centres ranked by how many [targets] lie within [radius] blocks of them; hashed, so thousands of blocks stay cheap. */
internal fun rankBlastCentres(targets: List<BlockPos>, radius: Int): List<Pair<BlockPos, Int>> {
    val present = targets.mapTo(HashSet(targets.size * 2)) { it.asLong() }
    val offsets = (-radius..radius).flatMap { x -> (-radius..radius).flatMap { y -> (-radius..radius).map { z -> Triple(x, y, z) } } }
        .filter { (x, y, z) -> x * x + y * y + z * z <= radius * radius }
    return targets.map { c -> c to offsets.count { (x, y, z) -> BlockPos.offset(c.asLong(), x, y, z) in present } }.sortedByDescending { it.second }
}

/** Gemstone Spread drives gemstones and Mining Spread every other block; a stat missing from the tab list uses its fallback. */
internal fun spreadFor(gemstone: Boolean, miningSpread: Int?, gemstoneSpread: Int?, miningFallback: Int, gemstoneFallback: Int) =
    if (gemstone) gemstoneSpread ?: gemstoneFallback else miningSpread ?: miningFallback

/** Block ids as a palette plus one character per block in BlockPos.betweenClosed order (x fastest, then y, then z), or null if there are too many kinds to encode. */
internal fun encodeGrid(blocks: List<String>): Pair<List<String>, String>? {
    val palette = blocks.distinct()
    if (palette.size > 75) return null
    return palette to blocks.joinToString("") { ('0' + palette.indexOf(it)).toString() }
}

/**
 * Whether [eye] can see an open face of [target]: the straight line to that face's centre crosses only [passable] blocks.
 * shortcut: checks face centres only, so a face hidden at its centre but visible at an edge is skipped; sample more points if that misses good blocks.
 */
internal fun canSee(eye: Vec3, target: BlockPos, passable: (BlockPos) -> Boolean) = Direction.entries.any { d ->
    val normal = Vec3(d.stepX.toDouble(), d.stepY.toDouble(), d.stepZ.toDouble())
    val face = Vec3.atCenterOf(target).add(normal.scale(0.49))
    val toFace = face.subtract(eye)
    if (!passable(target.relative(d)) || normal.dot(toFace) >= 0) return@any false
    val steps = ceil(toFace.length() / 0.05).toInt()
    (0..steps).all { i -> BlockPos.containing(eye.add(toFace.scale(i.toDouble() / steps))).let { it == target || passable(it) } }
}
