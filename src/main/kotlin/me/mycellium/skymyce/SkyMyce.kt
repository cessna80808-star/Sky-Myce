package me.mycellium.skymyce

import com.teamresourceful.resourcefulconfig.api.loader.Configurator
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.api.DungeonApi
import me.mycellium.skymyce.api.MiscApi
import me.mycellium.skymyce.commands.SkyMyceCommands
import me.mycellium.skymyce.config.instances.dungeons.GoldorCoordinationConfig
import me.mycellium.skymyce.config.instances.dungeons.PartyFinder
import me.mycellium.skymyce.config.loadSkyMyceConfig
import me.mycellium.skymyce.features.dev.DevTooltips
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.digest.DigestActivities
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.garden.greenhouse.GreenhousePlanner
import me.mycellium.skymyce.features.general.*
import me.mycellium.skymyce.features.general.overlays.AbilityCooldownOverlay
import me.mycellium.skymyce.features.general.overlays.ClientInfoOverlay
import me.mycellium.skymyce.features.general.overlays.NpcDialogue
import me.mycellium.skymyce.features.general.overlays.SkillXpOverlay
import me.mycellium.skymyce.features.general.overlays.StatusBars
import me.mycellium.skymyce.features.general.pricehistory.PriceHistoryFeature
import me.mycellium.skymyce.features.instances.InstanceRequeue
import me.mycellium.skymyce.features.instances.MissingPlayers
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestOverlay
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestProtection
import me.mycellium.skymyce.features.instances.dungeons.DungeonCleanChat
import me.mycellium.skymyce.features.instances.dungeons.DungeonWinOverlay
import me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCamera
import me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraHud
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.instances.dungeons.friends.IrcEmojiChat
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorCoordinator
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorHud
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapMenu
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapPovSharing
import me.mycellium.skymyce.features.instances.dungeons.routing.StarredClearRouting
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplits
import me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsHud
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTracker
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTrackerWidget
import me.mycellium.skymyce.features.instances.dungeons.tracker.RecentRngWidget
import me.mycellium.skymyce.features.mining.MiningFeatures
import me.mycellium.skymyce.features.social.CosmeticsClient
import me.mycellium.skymyce.features.troll.CheesePizza
import me.mycellium.skymyce.features.troll.Plane
import me.mycellium.skymyce.hud.HudRenderer
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.ui.AutoGraphics
import me.mycellium.skymyce.ui.MenuShortcuts
import me.mycellium.skymyce.utils.ServerUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import java.nio.file.Path
import me.mycellium.skymyce.community.CommunityClient


object SkyMyce : ClientModInitializer {
    val mod = FabricLoader.getInstance().getModContainer("skymyce").orElseThrow()
    val MOD_ID = mod.metadata.id

    val logger = LoggerFactory.getLogger(MOD_ID)

    val configPath: Path = FabricLoader.getInstance().configDir.resolve(MOD_ID)

    val configurator = Configurator(MOD_ID)
    lateinit var config: ResourcefulConfig

    override fun onInitializeClient() {
        config = loadSkyMyceConfig()
        GoldorCoordinationConfig.migrateStrategy()
        config.save()
        MenuShortcuts.initialize()

        initializeModules()
        ClientLifecycleEvents.CLIENT_STOPPING.register { RuntimeProfiler.shutdown() }
        ModuleManager.modules.forEach { SkyBlockAPI.eventBus.register(it); it.enable() }
        CommunityClient.initialize()
    }

    private fun initializeModules() {
        ModuleManager.loadModules(
            CheesePizza,
            MiscApi, AuctionAPI, DungeonApi, ServerUtils, HudRenderer, WidgetManager,
            DevTooltips,
            LoadoutKeybinds, SkyMyceCommands, SkillXpOverlay, StatusBars, NpcDialogue, SignConfirm, AutoGraphics, AbilityCooldownOverlay, AutoRefill, ClientInfoOverlay, PartyCommands,
            StashHelper, PartyFinder, DungeonFriends, IrcEmojiChat, InstanceRequeue, MissingPlayers, DungeonChestOverlay, DungeonChestProtection, DungeonTracker, DungeonTrackerWidget, DungeonCleanChat, DungeonWinOverlay,
            MiningFeatures, Plane, AcquisitionRepository, DailyDigest, DigestActivities, DigestRng, RecentRngWidget, StarredClearRouting,
            CosmeticsClient,
            PriceHistoryFeature,
            GreenhousePlanner,
            DungeonSplits,
            DungeonSplitsHud,
            GoldorCoordinator,
            GoldorHud,
            HealerCrushCamera,
            HealerCrushCameraHud,
            LeapMenu,
            LeapPovSharing
        )

        ModuleManager.modules.forEach {
            it.init()
        }
    }

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
