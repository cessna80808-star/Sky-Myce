package me.mycellium.skymyce

import com.teamresourceful.resourcefulconfig.api.loader.Configurator
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.api.DungeonApi
import me.mycellium.skymyce.api.MiscApi
import me.mycellium.skymyce.commands.SkyMyceCommands
import me.mycellium.skymyce.config.Config
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.config.instances.dungeons.PartyFinder
import me.mycellium.skymyce.features.dev.DevTooltips
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.digest.DigestActivities
import me.mycellium.skymyce.features.digest.DigestRng
import me.mycellium.skymyce.features.general.*
import me.mycellium.skymyce.features.general.overlays.AbilityCooldownOverlay
import me.mycellium.skymyce.features.general.overlays.ClientInfoOverlay
import me.mycellium.skymyce.features.general.overlays.SkillXpOverlay
import me.mycellium.skymyce.features.instances.InstanceRequeue
import me.mycellium.skymyce.features.instances.MissingPlayers
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestOverlay
import me.mycellium.skymyce.features.instances.dungeons.DungeonChestProtection
import me.mycellium.skymyce.features.instances.dungeons.DungeonCleanChat
import me.mycellium.skymyce.features.instances.dungeons.DungeonWinOverlay
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.instances.dungeons.friends.GearLending
import me.mycellium.skymyce.features.instances.dungeons.routing.StarredClearRouting
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTracker
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTrackerWidget
import me.mycellium.skymyce.features.mining.MiningFeatures
import me.mycellium.skymyce.features.troll.CheesePizza
import me.mycellium.skymyce.features.troll.Plane
import me.mycellium.skymyce.hud.HudRenderer
import me.mycellium.skymyce.hud.widget.WidgetManager
import me.mycellium.skymyce.utils.ServerUtils
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import java.nio.file.Path
import me.mycellium.skymyce.loader.LoaderClient


object SkyMyce : ClientModInitializer {
    val mod = FabricLoader.getInstance().getModContainer("skymyce").orElseThrow()
    val MOD_ID = mod.metadata.id

    val logger = LoggerFactory.getLogger(MOD_ID)

    val configPath: Path = FabricLoader.getInstance().configDir.resolve(MOD_ID)

    val configurator = Configurator(MOD_ID)
    lateinit var config: ResourcefulConfig

    override fun onInitializeClient() {
        config = me.mycellium.skymyce.config.loadSkyMyceConfig()
        ThemeConfig.migrateDigestTheme()
        config.save()
        me.mycellium.skymyce.ui.MenuShortcuts.initialize()

        // Fabric key mappings must register before GameOptions exists. Execution is gated below.
        initializeModules()
        ClientLifecycleEvents.CLIENT_STOPPING.register { RuntimeProfiler.shutdown() }
        LoaderClient.observe { allowed ->
            ModuleManager.modules.forEach {
                if (allowed) { SkyBlockAPI.eventBus.unregister(it); SkyBlockAPI.eventBus.register(it); it.enable() }
                else { SkyBlockAPI.eventBus.unregister(it); it.disable() }
            }
            if (!allowed && modulesInitialized) {
                me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay.disconnect()
            }
        }
    }

    private var modulesInitialized = false
    private fun initializeModules() {
        modulesInitialized = true
        ModuleManager.loadModules(
            CheesePizza,
            MiscApi, AuctionAPI, DungeonApi, ServerUtils, HudRenderer, WidgetManager,
            DevTooltips,
            LoadoutKeybinds, SkyMyceCommands, SkillXpOverlay, AbilityCooldownOverlay, AutoRefill, ClientInfoOverlay, PartyCommands, Waypoints,
            StashHelper, PartyFinder, DungeonFriends, GearLending, InstanceRequeue, MissingPlayers, DungeonChestOverlay, DungeonChestProtection, DungeonTracker, DungeonTrackerWidget, DungeonCleanChat, DungeonWinOverlay,
            MiningFeatures, Plane, AcquisitionRepository, DailyDigest, DigestActivities, DigestRng, StarredClearRouting,
            me.mycellium.skymyce.features.social.CosmeticsClient
        )

        ModuleManager.modules.forEach {
            it.init()
        }
    }

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
