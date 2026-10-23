package me.mycellium.skymyce.ui

import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.settingRows
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.community.CommunityScreen
import me.mycellium.skymyce.community.LeaderboardScreen
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.features.instances.dungeons.friends.RelayRetry
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonTrackerWidget
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerFilter
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerPage
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerScope
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerView
import me.mycellium.skymyce.features.instances.dungeons.tracker.PersonalRateSample
import me.mycellium.skymyce.features.instances.dungeons.tracker.LootOrder
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionArchive
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.features.instances.dungeons.tracker.ChestReceipt
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonRunRecord
import me.mycellium.skymyce.features.instances.dungeons.tracker.RunLoot
import me.mycellium.skymyce.features.instances.dungeons.tracker.legacyTrackerView
import me.mycellium.skymyce.features.instances.dungeons.tracker.marketValuedLoot
import me.mycellium.skymyce.features.instances.dungeons.tracker.marketValuedTrackerSummary
import me.mycellium.skymyce.features.instances.dungeons.tracker.cachedLootUnitPrice
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapCandidate
import me.mycellium.skymyce.features.instances.dungeons.leap.LeapIdentity
import me.mycellium.skymyce.features.instances.dungeons.leap.drawLeapCard
import me.mycellium.skymyce.features.instances.dungeons.leap.leapQuadrantBounds
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import tech.thatgravyboat.skyblockapi.api.remote.api.SimpleItemAPI
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId
import tech.thatgravyboat.skyblockapi.api.repo.apis.SkyBlockItemsRepo
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.digest.DailyDigestScreen
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.digest.DigestNewsItem
import me.mycellium.skymyce.features.digest.DigestNewsRepository
import me.mycellium.skymyce.features.digest.DigestNewsState
import me.mycellium.skymyce.features.digest.NewsSource
import me.mycellium.skymyce.features.social.CosmeticsScreen
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.ThemePreset
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import net.minecraft.core.component.DataComponents
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path
import java.net.http.WebSocket
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.io.IOException

/** Only loaded by runGuiVisualCheck; no world or community session is opened. */
class GuiVisualCheckClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register { client ->
            client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0)
            client.soundManager.updateCategoryVolume(SoundSource.MASTER, 0f)
            val sodium = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")
            check(me.mycellium.skymyce.features.instances.dungeons.camera.render.CameraRenderCheck.checkSodiumChunkEventsCompatibility() == sodium)
            println("GUI visual check: transformed Sodium chunk events verified=$sodium")
        }
        data class Scene(val name: String, val theme: ThemePreset, val width: Int = 1280, val height: Int = 800,
                         val scale: Int = 2, val screen: () -> Screen)
        var trackerHistoryFixture: GuiVisualTrackerHistoryFixture? = null
        var newsFixture: GuiVisualNewsFixture? = null
        var browserFixture: GuiVisualBrowserFixture? = null
        var journalFixture: GuiVisualJournalFixture? = null
        var splitsFixture: GuiVisualSplitsFixture? = null
        var rngArchiveSnapshot: AcquisitionArchive? = null
        var savedReceiveRng: Boolean? = null
        fun receiveRngEntry() = settingRows(SkyMyce.config).single { it.element.id() == "receiveRng" }.entry
        fun trackerScreen(page: TrackerPage): DungeonScreen {
            if (page in listOf(TrackerPage.MUSEUM, TrackerPage.TIMELINE) && AcquisitionRepository.state.events.none { it.floor == "M7" })
                trackerHistoryFixture = GuiVisualTrackerHistoryFixture()
            AcquisitionRepository.remember(TrackerFilter(page = page, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES))
            return DungeonScreen()
        }
        val allScenes = ThemePreset.entries.map { Scene("home-${it.name.lowercase()}", it) { MenuHomeScreen() } } + listOf(
            Scene("dungeons-signature", ThemePreset.SIGNATURE) { MenuDirectoryScreen(MenuCategory.DUNGEONS) },
            Scene("item-models-skyblock", ThemePreset.SIGNATURE) { GuiVisualItemModelsScreen() },
            Scene("dungeon-tracker-widget", ThemePreset.SIGNATURE) { GuiVisualDungeonTrackerPreviewScreen() },
            Scene("dungeon-splits-widget", ThemePreset.SIGNATURE) { GuiVisualDungeonSplitsPreviewScreen() },
            Scene("goldor-map-widget", ThemePreset.SIGNATURE) { GuiVisualGoldorMapPreviewScreen() },
            Scene("status-bars-editor", ThemePreset.SIGNATURE) { me.mycellium.skymyce.features.general.overlays.bars.StatusBarsEditorScreen() },
            Scene("status-bars-editor-options", ThemePreset.SIGNATURE) { me.mycellium.skymyce.features.general.overlays.bars.StatusBarsEditorScreen(null, me.mycellium.skymyce.features.general.overlays.bars.BarType.HEALTH) },
            Scene("dungeon-tracker-levels", ThemePreset.SIGNATURE) {
                // A profile reading for the dev player, as Skyblocker or the API would provide it.
                val xp = me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel(52) + 172_400_000.0
                val classes = mapOf(DungeonClass.HEALER to 45, DungeonClass.MAGE to 50, DungeonClass.BERSERKER to 47, DungeonClass.ARCHER to 46, DungeonClass.TANK to 45)
                    .mapValues { me.mycellium.skymyce.features.instances.dungeons.friends.dungeonXpForLevel(it.value) + 3_000_000.0 }
                val now = System.currentTimeMillis()
                me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache.storeFetched(Minecraft.getInstance().user.name.lowercase(), 0,
                    me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStats(me.mycellium.skymyce.features.instances.dungeons.friends.StatsState.AVAILABLE,
                        52, classes.mapValues { me.mycellium.skymyce.features.instances.dungeons.friends.dungeonLevel(it.value) }, catacombsXp = xp, classXp = classes,
                        selectedClass = DungeonClass.MAGE), null, now, now)
                trackerScreen(TrackerPage.LEVELS)
            },
            Scene("dungeon-splits-stats", ThemePreset.SIGNATURE) { GuiVisualSplitsFixture().also { splitsFixture = it }.screen() },
            Scene("dungeon-splits-stats-wide", ThemePreset.DARK_GLASS, 1920, 1080, 1) { GuiVisualSplitsFixture().also { splitsFixture = it }.screen() },
            Scene("dungeon-splits-stats-narrow", ThemePreset.LIGHT, 640, 480, 2) { GuiVisualSplitsFixture().also { splitsFixture = it }.screen() },
            Scene("dungeon-splits-stats-server", ThemePreset.SIGNATURE) {
                GuiVisualSplitsFixture().also { splitsFixture = it }.screen(me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock.SERVER)
            },
            Scene("spirit-leap-layout", ThemePreset.SIGNATURE) { GuiVisualLeapMenuScreen() },
            Scene("spirit-leap-layout-small", ThemePreset.SIGNATURE, 640, 480, 2) { GuiVisualLeapMenuScreen(missing = true) },
            Scene("journal-compact-wide", ThemePreset.SIGNATURE, 1920, 1080, 1) {
                GuiVisualJournalFixture().also { journalFixture = it }.screen()
            },
            Scene("journal-compact-narrow", ThemePreset.SIGNATURE, 640, 480, 2) {
                GuiVisualJournalFixture(narrow = true).also { journalFixture = it }.screen()
            },
            Scene("journal-compact-tiny", ThemePreset.SIGNATURE, 320, 240, 1) {
                GuiVisualJournalFixture(narrow = true).also { journalFixture = it }.screen()
            },
            Scene("journal-details", ThemePreset.SIGNATURE) {
                GuiVisualJournalFixture(details = true).also { journalFixture = it }.screen()
            },
            Scene("dungeon-tracker-overview", ThemePreset.SIGNATURE) {
                trackerScreen(TrackerPage.OVERVIEW)
            },
            Scene("dungeon-tracker-overview-all-floors", ThemePreset.SIGNATURE) {
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.OVERVIEW, scope = TrackerScope.ALL_LOCAL_PROFILES))
                DungeonScreen()
            },
            Scene("dungeon-tracker-overview-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { trackerScreen(TrackerPage.OVERVIEW) },
            Scene("dungeon-tracker-loot", ThemePreset.SIGNATURE) {
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.LOOT, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES))
                DungeonScreen()
            },
            Scene("dungeon-tracker-museum", ThemePreset.SIGNATURE) { trackerScreen(TrackerPage.MUSEUM) },
            Scene("dungeon-tracker-museum-wide", ThemePreset.SIGNATURE, 1920, 1080, 1) { trackerScreen(TrackerPage.MUSEUM) },
            Scene("dungeon-tracker-museum-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { trackerScreen(TrackerPage.MUSEUM) },
            Scene("dungeon-tracker-timeline-wide", ThemePreset.SIGNATURE, 1920, 1080, 1) { trackerScreen(TrackerPage.TIMELINE) },
            Scene("dungeon-tracker-timeline-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { trackerScreen(TrackerPage.TIMELINE) },
            Scene("dungeon-tracker-handle-history", ThemePreset.SIGNATURE) {
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.TIMELINE, floor = "M7", query = "NECRON_HANDLE", scope = TrackerScope.ALL_LOCAL_PROFILES))
                DungeonScreen()
            },
            Scene("dungeon-tracker-timeline-load-older", ThemePreset.SIGNATURE) {
                trackerHistoryFixture = GuiVisualTrackerHistoryFixture(timelineCount = 120)
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.TIMELINE, floor = "M7", scope = TrackerScope.ALL_LOCAL_PROFILES))
                DungeonScreen()
            },
            Scene("dungeon-tracker-personal-rate-tooltip", ThemePreset.SIGNATURE) {
                if (AcquisitionRepository.state.personalRateSamples.none { it.floor == "M7" && "SHADOW_WARP_SCROLL" in it.itemCounts })
                    trackerHistoryFixture = GuiVisualTrackerHistoryFixture()
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.LOOT, floor = "M7", query = "shadow warp", scope = TrackerScope.ALL_LOCAL_PROFILES))
                DungeonScreen()
            },
            Scene("dungeon-tracker-filter-return", ThemePreset.SIGNATURE) {
                trackerHistoryFixture = GuiVisualTrackerHistoryFixture()
                AcquisitionRepository.remember(TrackerFilter(page = TrackerPage.MUSEUM, floor = "M7", query = "shadow warp",
                    scope = TrackerScope.ALL_LOCAL_PROFILES, legacyTotals = true, sort = LootOrder.NAME, ascending = true, oldestFirst = true))
                DungeonScreen()
            },
            Scene("settings-signature", ThemePreset.SIGNATURE) { SettingsScreen(null, SettingsTab.PARTY) },
            Scene("settings-light", ThemePreset.LIGHT) { SettingsScreen(null, SettingsTab.THEME) },
            Scene("settings-routing-breadcrumbs", ThemePreset.SIGNATURE) { SettingsScreen.forFeature(null, "starred-routes") },
            Scene("settings-routing-breadcrumbs-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { SettingsScreen.forFeature(null, "starred-routes") },
            Scene("themes-signature", ThemePreset.SIGNATURE) { SettingsScreen.forFeature(null, "settings-appearance") },
            Scene("crush-camera", ThemePreset.SIGNATURE) { me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraScreen() },
            Scene("crush-camera-wide", ThemePreset.SIGNATURE, 1920, 1080, 2) { me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraScreen() },
            Scene("crush-camera-settings", ThemePreset.SIGNATURE) { SettingsScreen.forFeature(null, "healer-crush-camera") },
            Scene("themes-light", ThemePreset.LIGHT) { SettingsScreen.forFeature(null, "settings-appearance") },
            Scene("cosmetics-light", ThemePreset.LIGHT) { CosmeticsScreen() },
            Scene("greenhouse", ThemePreset.SIGNATURE) { greenhouseFixture() },
            Scene("greenhouse-narrow", ThemePreset.LIGHT, 640, 480, 2) { greenhouseFixture() },
            Scene("greenhouse-wide", ThemePreset.SIGNATURE, 1920, 1080, 1) { greenhouseFixture() },
            Scene("search-light", ThemePreset.LIGHT) { MenuDirectoryScreen() },
            Scene("leaderboards-community-return", ThemePreset.SIGNATURE) { LeaderboardScreen() },
            Scene("daily-news-normal", ThemePreset.SIGNATURE) { DailyDigestScreen("news") },
            Scene("daily-news-video", ThemePreset.SIGNATURE) {
                newsFixture = GuiVisualNewsFixture()
                DailyDigestScreen("news")
            },
            Scene("daily-rng-local-history", ThemePreset.SIGNATURE) {
                if (AcquisitionRepository.state.events.none { it.item == "IMPLOSION_SCROLL" })
                    trackerHistoryFixture = GuiVisualTrackerHistoryFixture("IMPLOSION_SCROLL")
                rngArchiveSnapshot = AcquisitionRepository.state
                receiveRngEntry().let { savedReceiveRng = it.boolean; check(it.setBoolean(false)) }
                DailyDigest.screenClosed()
                DailyDigestScreen("rng")
            },
            Scene("home-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { MenuHomeScreen() },
            Scene("home-keyboard-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { MenuHomeScreen() },
            Scene("themes-narrow", ThemePreset.LIGHT, 640, 480, 4) { SettingsScreen.forFeature(null, "settings-appearance") },
            Scene("settings-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { SettingsScreen(null, SettingsTab.PARTY) },
            Scene("home-small", ThemePreset.SIGNATURE, 480, 360, 4) { MenuHomeScreen() },
            Scene("home-wide", ThemePreset.BOREALIS, 1920, 1080, 1) { MenuHomeScreen() },
            Scene("controls-signature", ThemePreset.SIGNATURE) { GuiVisualControlsScreen() },
            Scene("controls-light", ThemePreset.LIGHT) { GuiVisualControlsScreen() },
            Scene("controls-contrast", ThemePreset.HIGH_CONTRAST, 640, 480, 4) { GuiVisualControlsScreen() },
            Scene("confirm-light", ThemePreset.LIGHT) { MenuConfirmScreen(MenuHomeScreen(), "Review changes", "A themed confirmation keeps actions and cancellation clear.", "Apply") {} },
            Scene("browser-native", ThemePreset.SIGNATURE) { GuiVisualBrowserFixture().also { browserFixture = it }.screen },
            Scene("browser-native-small", ThemePreset.SIGNATURE, 640, 480, 1) { GuiVisualBrowserFixture().also { browserFixture = it }.screen }
        )
        val scenePrefix = System.getenv("SKYMYCE_GUI_SCENE_PREFIX")
        val scenes = allScenes.filter { scenePrefix == null || it.name.startsWith(scenePrefix) }
        check(scenes.isNotEmpty()) { "No GUI scenes match the requested prefix" }
        val output = Path.of("..", "screenshots").toAbsolutePath().normalize()
        Files.createDirectories(output)
        var scene = 0
        var ticks = 0
        var started = false
        var pending = false
        var leaderboardOrigin: LeaderboardScreen? = null
        var communityOrigin: CommunityScreen? = null
        var leaderboardView = emptyList<Any?>()
        var trackerArchiveSnapshot: AcquisitionArchive? = null
        var previousMouse: Pair<Double, Double>? = null
        fun testMouse(client: Minecraft, x: Double, y: Double) {
            // The hidden test window has no OS hover events; feed its native cursor cache directly.
            for ((name, value) in listOf("xpos" to x, "ypos" to y))
                client.mouseHandler.javaClass.getDeclaredField(name).apply { isAccessible = true }.setDouble(client.mouseHandler, value)
        }
        fun adapter(screen: Screen): OwoUIAdapter<*> {
            val field = BaseOwoScreen::class.java.getDeclaredField("uiAdapter").apply { isAccessible = true }
            return field.get(screen) as OwoUIAdapter<*>
        }
        fun press(title: String, navigation: Boolean = false) {
            val screen = checkNotNull(net.minecraft.client.Minecraft.getInstance().screen)
            val ui = adapter(screen)
            val components = ArrayList<UIComponent>()
            ui.rootComponent.collectDescendants(components)
            val control = components.first { component -> when (component) {
                is MenuButton -> component.navigation == navigation && component.message.string == title
                is MenuLabel -> component.text().string == title && component.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE)
                else -> false
            } }
            checkNotNull(ui.rootComponent.focusHandler()).focus(control, UIComponent.FocusSource.KEYBOARD_CYCLE)
            screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
        }
        fun clickBreadcrumb(title: String) {
            val client = Minecraft.getInstance()
            val screen = client.screen as SettingsScreen
            val components = arrayListOf<UIComponent>().apply { adapter(screen).rootComponent.collectDescendants(this) }
            val control = components.filterIsInstance<MenuButton>().single { !it.navigation && it.message.string == title }
            fun pose(name: String) = MenuScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen) as MotionPose
            val content = pose("paintedContent"); val shell = pose("paintedShell")
            val x = content.pivotX + (control.x + control.width / 2.0 - content.pivotX) * content.scale + content.x
            val y = content.pivotY + (control.y + control.height / 2.0 - content.pivotY) * content.scale + content.y
            val mouse = MouseButtonEvent(
                (shell.pivotX + (x - shell.pivotX) * shell.scale + shell.x) * client.window.guiScaledWidth / screen.width,
                (shell.pivotY + (y - shell.pivotY) * shell.scale + shell.y) * client.window.guiScaledHeight / screen.height,
                MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0))
            check(screen.mouseClicked(mouse, false)) { "Breadcrumb $title did not handle its painted mouse position" }
            client.screen?.mouseReleased(mouse)
        }
        fun checkBreadcrumbDirectory(section: String?) {
            val screen = checkNotNull(Minecraft.getInstance().screen)
            check(screen is MenuDirectoryScreen) { "Breadcrumb did not open its category directory" }
            val components = arrayListOf<UIComponent>().apply { adapter(screen).rootComponent.collectDescendants(this) }
            check(components.filterIsInstance<MenuButton>().any { it.message.string == (section ?: "All sections") }) {
                "Breadcrumb opened the wrong section selector: $section"
            }
            val featureTitles = components.filterIsInstance<MenuLabel>().map { it.text().string }
            check("Dungeon Routing" in featureTitles && (section == null || "Party Finder" !in featureTitles)) {
                "Section breadcrumb did not filter the category's features"
            }
        }
        fun trackerFilter() = DungeonScreen::class.java.getDeclaredField("filter").apply { isAccessible = true }
            .get(checkNotNull(Minecraft.getInstance().screen) as DungeonScreen) as TrackerFilter
        fun trackerView() = DungeonScreen::class.java.getDeclaredField("view").apply { isAccessible = true }
            .get(checkNotNull(Minecraft.getInstance().screen) as DungeonScreen) as TrackerView
        fun leaderboardState(screen: LeaderboardScreen) = listOf("category", "metric", "period", "page", "query").map { name ->
            LeaderboardScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!started) {
                if (client.screen == null || client.overlay != null) return@register
                checkImmediateSendFailure(client)
                checkMenuItemModels(client)
                started = true
                client.options.pauseOnLostFocus = false
                System.setProperty("skymyce.browserMuted", "true")
                ThemeConfig.animationMode = GuiMotion.MEDIUM
                ThemeConfig.menuIntroPlayed = true
                RuntimeProfiler.start(false)
                GLFW.glfwHideWindow(client.window.handle())
            }
            if (pending) return@register
            if (scene >= scenes.size) {
                Files.writeString(output.resolve("performance.txt"), RuntimeProfiler.report())
                client.stop()
                return@register
            }
            val current = scenes[scene]
            if (ticks++ == 0) {
                GLFW.glfwSetWindowSize(client.window.handle(), current.width, current.height)
                client.options.guiScale().set(current.scale)
                client.resizeGui()
                ThemeConfig.preset = current.theme
                HudTheme.changed()
                val next = current.screen()
                trackerArchiveSnapshot = if (next is DungeonScreen) AcquisitionRepository.state else null
                navigateMenu(next)
            }
            if (current.name == "home-keyboard-narrow" && ticks in listOf(12, 14)) {
                val screen = client.screen as MenuScreen
                val field = BaseOwoScreen::class.java.getDeclaredField("uiAdapter").apply { isAccessible = true }
                val adapter = field.get(screen) as OwoUIAdapter<*>
                val handler = checkNotNull(adapter.rootComponent.focusHandler())
                if (ticks == 12) {
                    var attempts = 0
                    while ((handler.focused() as? MenuButton)?.message?.string != "Daily Digest" && attempts++ < 40)
                        screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_TAB, 0, 0))
                    check((handler.focused() as? MenuButton)?.message?.string == "Daily Digest") { "Tab could not reach the last Home shortcut" }
                } else {
                    val focused = checkNotNull(handler.focused())
                    generateSequence(focused.parent()) { it.parent() }.filterIsInstance<MenuScroll<*>>().forEach { scroll ->
                        check(focused.y() >= scroll.y() - 1 && focused.y() + focused.height() <= scroll.y() + scroll.height() + 1) {
                            "Keyboard-focused Home shortcut remained outside the visible scroll area"
                        }
                    }
                }
            }
            if (current.name.startsWith("settings-routing-breadcrumbs")) when (ticks) {
                8 -> {
                    val screen = checkNotNull(client.screen)
                    val components = arrayListOf<UIComponent>().apply { adapter(screen).rootComponent.collectDescendants(this) }
                    val links = components.filterIsInstance<MenuButton>().filter { it.message.string in listOf("Dungeons", "→ Routes", "→ Dungeon Routing") && !it.navigation }
                    check(links.size == 3 && links.all { link ->
                        val row = checkNotNull(link.parent())
                        link.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE) && link.width > 0 &&
                            link.x >= row.x() && link.x + link.width <= row.x() + row.width()
                    }) { "A settings breadcrumb was clipped or could not receive keyboard focus" }
                    clickBreadcrumb("Dungeons"); checkBreadcrumbDirectory(null)
                }
                12, 22 -> navigateMenu(SettingsScreen.forFeature(null, "starred-routes"))
                18 -> { clickBreadcrumb("→ Routes"); checkBreadcrumbDirectory("Routes") }
                28 -> {
                    val previous = client.screen
                    press("→ Dungeon Routing")
                    check(client.screen is SettingsScreen && client.screen !== previous &&
                        (client.screen as MenuScreen).canonicalFeatureId == "starred-routes") {
                        "Keyboard activation of the feature breadcrumb did not open its canonical settings"
                    }
                    println("Visual breadcrumb check passed: mouse category/section, keyboard feature and ${current.width}px layout")
                }
            }
            if (current.name == "leaderboards-community-return") when (ticks) {
                5 -> press("Dungeons")
                6 -> { press("Dungeon runs"); press("Average Run Time") }
                7 -> { press("All time"); press("This week • UTC") }
                8 -> {
                    val screen = client.screen as LeaderboardScreen
                    val components = ArrayList<UIComponent>()
                    adapter(screen).rootComponent.collectDescendants(components)
                    components.filterIsInstance<MenuTextBox>().single().text("fixture")
                    // Offline data has no next page; retain a non-default page in this navigation fixture.
                    LeaderboardScreen::class.java.getDeclaredField("page").apply { isAccessible = true }.setInt(screen, 3)
                    leaderboardOrigin = screen; leaderboardView = leaderboardState(screen)
                    press("Connect Discord")
                    communityOrigin = client.screen as CommunityScreen
                }
                10 -> press("Sharing settings")
                12 -> {
                    check(client.screen is SettingsScreen)
                    client.screen!!.onClose()
                    check(client.screen === communityOrigin) { "Sharing settings discarded its Community origin" }
                }
                14 -> press("Done")
                16 -> {
                    check(client.screen === leaderboardOrigin) { "Community Done replaced the originating leaderboard" }
                    check(leaderboardState(client.screen as LeaderboardScreen) == leaderboardView) {
                        "The Discord connection detour reset leaderboard category, metric, period, page or search"
                    }
                }
            }
            if (current.name == "daily-rng-local-history") when (ticks) {
                5 -> {
                    val components = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                    components.filterIsInstance<MenuTextBox>().single().text("implosion")
                }
                6 -> {
                    val components = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                    val buttons = components.filterIsInstance<MenuButton>()
                    val tabs = buttons.filter { it.message.string in listOf("Local history", "Community") }
                    check(tabs.size == 2 && tabs.all { it.navigation && it.centeredNavigation }) {
                        "RNG Activity tabs lost their centered navigation style"
                    }
                    check(buttons.filter { it.navigation && it !in tabs }.all { !it.centeredNavigation }) {
                        "Centering RNG tabs changed the sidebar navigation style"
                    }
                    press("All activities")
                    check(buttons.any { it.message.string == "Dungeon" } && buttons.none { it.message.string == "dungeon" }) {
                        "RNG activity captions exposed their raw lowercase keys"
                    }
                }
                8, 16 -> {
                    val components = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                    check(components.filterIsInstance<MenuLabel>().any { it.text().string.contains("Implosion") }) {
                        "Saved Implosion was missing from offline RNG Activity"
                    }
                    check(components.filterIsInstance<MenuButton>().none { it.message.string.startsWith("Clear local") })
                    check(AcquisitionRepository.state === rngArchiveSnapshot && client.player == null && client.connection == null && !CommunityClient.connected)
                    if (ticks == 8) press("Community", navigation = true)
                }
                12 -> press("View local history")
            }
            if (current.name == "daily-news-video" && ticks == 18) {
                val components = arrayListOf<UIComponent>()
                adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                check(components.filterIsInstance<MenuButton>().any { it.message.string == "Watch Video" })
                check(components.filterIsInstance<MenuButton>().any { it.message.string == "Watch Video 1" })
                check(components.filterIsInstance<MenuButton>().any { it.message.string == "Open Hypixel 2" })
                check(components.filterIsInstance<MenuLabel>().any { "Derailious" in it.text().string })
                check(components.filterIsInstance<MenuLabel>().none { "Alpha" in it.text().string })
                check(DailyDigest.diagnostics().getValue("digest.news_requests") == 0L)
                check(client.level == null && client.connection == null)
            }
            if (current.name == "dungeon-tracker-timeline-load-older" && ticks in listOf(10, 15)) {
                val components = arrayListOf<UIComponent>()
                adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                val records = components.count { it.id()?.startsWith("timeline-event-") == true }
                check(records == if (ticks == 10) 100 else 120) { "Continuous timeline loaded $records records at tick $ticks" }
                checkNotNull(trackerHistoryFixture).checkIntact()
                if (ticks == 10) press("Load older (20)")
                else check(components.filterIsInstance<MenuButton>().none { it.message.string.startsWith("Load older") })
            }
            if (current.name == "dungeon-tracker-filter-return") when (ticks) {
                5 -> press("History")
                7 -> {
                    check(trackerFilter().let { it.page == TrackerPage.TIMELINE && it.query == "SHADOW_WARP_SCROLL" })
                    check(AcquisitionRepository.preferences.let { it.page == TrackerPage.MUSEUM && it.query == "shadow warp" })
                    press("Back to Museum")
                    check(trackerFilter().let { it.page == TrackerPage.MUSEUM && it.query == "shadow warp" && it.floor == "M7" })
                }
                9 -> press("History")
                11 -> {
                    press("Loot", navigation = true)
                    check(trackerFilter().query == "shadow warp") { "Item history search leaked into Loot" }
                }
                13 -> {
                    press("Clear filters")
                    check(trackerFilter().let { it.query.isEmpty() && it.floor == null && it.scope == TrackerScope.ALL_LOCAL_PROFILES &&
                        it.legacyTotals && it.sort == LootOrder.NAME && it.ascending && it.oldestFirst }) { "Clearing constraints changed the loot source or sorting" }
                    val components = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                    check(components.filterIsInstance<MenuButton>().none { it.message.string in listOf("Show historical totals", "Show new tracking") })
                    checkNotNull(trackerHistoryFixture).checkIntact()
                }
                17 -> {
                    check(trackerView().loot.single { it.item == "GUI_FIXTURE_UNPRICED" }.value == null) {
                        "Loot market values fell back to an old recorded amount for an unpriced item"
                    }
                    check(trackerView().loot.single { it.item == "SHADOW_WARP_SCROLL" }.let {
                        it.rateSample?.completedRuns == 20L && it.observedRate == 0.1
                    }) { "The personal percentage ignored its separate dated run sample" }
                    val labels = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(labels)
                    check(labels.filterIsInstance<MenuLabel>().any { it.text().string.contains("Market value (estimated)") })
                    check(labels.filterIsInstance<MenuLabel>().any { it.text().string.contains("Personal rate") })
                    check(labels.filterIsInstance<MenuLabel>().any { it.text().string.startsWith("All saved loot") })
                    press("RNG Museum", navigation = true)
                    val components = arrayListOf<UIComponent>()
                    adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                    components.filterIsInstance<MenuTextBox>().single().text("shadow warp")
                }
                21 -> press("History")
                23 -> navigateMenu(MenuDirectoryScreen(MenuCategory.DUNGEONS))
                25 -> {
                    check(AcquisitionRepository.preferences.let { it.page == TrackerPage.MUSEUM && it.query == "shadow warp" }) {
                        "Leaving item history through category navigation persisted its item search"
                    }
                    navigateMenu(DungeonScreen())
                }
                28 -> {
                    check(trackerFilter().let { it.page == TrackerPage.MUSEUM && it.query == "shadow warp" &&
                        it.scope == TrackerScope.ALL_LOCAL_PROFILES && it.legacyTotals })
                    checkNotNull(trackerHistoryFixture).checkIntact()
                    println("Visual tracker filter check passed: item history returns, clearing preserves source, no saved loot changes")
                }
            }
            if (current.name.startsWith("dungeon-tracker-") && client.screen is DungeonScreen && ticks == 24) {
                val components = arrayListOf<UIComponent>()
                adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                check(AcquisitionRepository.state === trackerArchiveSnapshot) { "A tracker view changed the acquisition archive" }
                if (trackerFilter().page == TrackerPage.TIMELINE) {
                    val shown = trackerView()
                    check(shown.events.none { it.item == "RECOMBOBULATOR_3000" } &&
                        shown.loot.none { it.item == "RECOMBOBULATOR_3000" } && "RECOMBOBULATOR_3000" !in shown.undatedLoot) {
                        "RNG Timeline still showed standard Recombobulators"
                    }
                    val rows = components.filter { it.id()?.startsWith("timeline-undated-") == true }
                    check(rows.size == shown.undatedLoot.size) { "Timeline omitted saved drops without dates" }
                    for ((item, count) in shown.undatedLoot) {
                        val row = rows.single { it.id() == "timeline-undated-$item" } as FlowLayout
                        val children = arrayListOf<UIComponent>(); row.collectDescendants(children)
                        check(children.filterIsInstance<MenuLabel>().any { it.text().string.endsWith("×$count") })
                    }
                }
                if (trackerFilter().page == TrackerPage.OVERVIEW) {
                    val view = trackerView()
                    val expectedGross = view.loot.sumOf { it.value ?: 0.0 }
                    check(kotlin.math.abs(view.summary.gross - expectedGross) <= maxOf(1e-6, kotlin.math.abs(expectedGross) * 1e-12)) {
                        "Overview gross did not use the displayed cached loot estimates"
                    }
                    check(view.summary.knownNet == view.summary.gross - view.summary.chestCost - view.summary.knownRerollCost &&
                        view.summary.preservedProfit == 0.0) { "Overview reused preserved profit instead of its current loot valuation" }
                    val widgetView = me.mycellium.skymyce.features.instances.dungeons.tracker.trackerView(
                        AcquisitionRepository.state, legacyTrackerView(), TrackerFilter(floor = trackerFilter().floor, scope = TrackerScope.ALL_LOCAL_PROFILES),
                        AcquisitionRepository.currentContext())
                    val widgetSummary = marketValuedTrackerSummary(widgetView.summary, marketValuedLoot(widgetView.loot) {
                        cachedLootUnitPrice(it, DungeonTrackerConfig.bazaarPriceType, DungeonTrackerConfig.auctionPriceType)
                    })
                    check(widgetSummary == view.summary) { "Widget and Overview used different market profit calculations" }
                }
                if (trackerFilter().page in listOf(TrackerPage.OVERVIEW, TrackerPage.MUSEUM, TrackerPage.TIMELINE)) {
                    check(components.filterIsInstance<MenuButton>().none { it.message.string in listOf("‹", "›") }) {
                        "A tracker overview, museum or continuous timeline still showed page arrows"
                    }
                }
                if (current.name.startsWith("dungeon-tracker-museum")) {
                    val cards = components.filterIsInstance<FlowLayout>().filter { it.id()?.startsWith("museum-card-") == true }
                    check(cards.isNotEmpty()) { "Museum capture contained no collection cards" }
                    cards.groupBy { it.parent() }.values.forEach { row ->
                        check(row.map { it.height() }.distinct().size == 1 && row.map { it.y() }.distinct().size == 1) {
                            "Museum cards in one row had mismatched heights or vertical alignment"
                        }
                    }
                    cards.singleOrNull { it.id() == "museum-card-IMPLOSION_SCROLL" }?.let { card ->
                        val contents = arrayListOf<UIComponent>(); card.collectDescendants(contents)
                        val icon = contents.filterIsInstance<MenuItem>().single()
                        val model = SkyBlockItemsRepo.get("IMPLOSION_SCROLL")?.getAsJsonObject("components")?.get("minecraft:item_model")?.asString
                        check(icon.stack().get(DataComponents.ITEM_MODEL) == model?.let(Identifier::parse)) { "Museum scroll icon lost its item model" }
                        val pixels = MenuItem::class.java.getDeclaredField("modelArt").apply { isAccessible = true }.get(icon) as List<*>
                        check(pixels.isNotEmpty()) { "Museum scroll icon did not load its selected model texture pixels" }
                    }
                }
            }
            if (current.name == "item-models-skyblock" && ticks == 30) {
                val components = arrayListOf<UIComponent>()
                adapter(checkNotNull(client.screen)).rootComponent.collectDescendants(components)
                for (id in listOf("IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL", "TARANTULA_CATALYST")) {
                    val icon = components.filterIsInstance<MenuItem>().single { it.id() == "model-$id" }
                    val images = MenuItem::class.java.getDeclaredField("modelArt").apply { isAccessible = true }.get(icon) as List<*>
                    val loaded = MenuItem::class.java.getDeclaredField("loaded").apply { isAccessible = true }.get(icon)
                    val textures = MenuItem::class.java.getDeclaredField("loadedTextures").apply { isAccessible = true }.get(icon)
                    check(images.filterIsInstance<java.awt.image.BufferedImage>().any { image ->
                        image.getRGB(0, 0, image.width, image.height, null, 0, image.width).any { it ushr 24 != 0 }
                    }) { "The actual $id menu icon did not paint texture pixels: loaded=$loaded images=${images.size} textures=$textures bounds=${icon.x()},${icon.y()} ${icon.width()}×${icon.height()}" }
                }
            }
            if (current.name == "dungeon-tracker-personal-rate-tooltip" && ticks == 18) {
                check(trackerView().loot.single { it.item == "SHADOW_WARP_SCROLL" }.rateSample != null)
                val screen = checkNotNull(client.screen)
                val components = arrayListOf<UIComponent>()
                adapter(screen).rootComponent.collectDescendants(components)
                check(components.filterIsInstance<MenuLabel>().any { it.text().string.startsWith("Rate sample:") }) {
                    "Loot did not label its separate dated rate sample"
                }
                val rate = components.filterIsInstance<MenuLabel>().single { it.text().string.endsWith("%") }
                previousMouse = client.mouseHandler.xpos() to client.mouseHandler.ypos()
                val x = (rate.x() + rate.width() / 2.0) / screen.width
                val y = (rate.y() + rate.height() / 2.0) / screen.height
                testMouse(client, x * client.window.screenWidth, y * client.window.screenHeight)
                screen.mouseMoved(x * client.window.guiScaledWidth, y * client.window.guiScaledHeight)
            }
            val captureTick = when (current.name) {
                "settings-routing-breadcrumbs", "settings-routing-breadcrumbs-narrow" -> 38
                "dungeon-tracker-filter-return" -> 30
                "dungeon-tracker-personal-rate-tooltip" -> 40
                "item-models-skyblock" -> 31
                else -> 25
            }
            if (current.name == "dungeon-tracker-personal-rate-tooltip" && ticks == captureTick) {
                val tooltip = MenuScreen::class.java.getDeclaredField("tooltip").apply { isAccessible = true }.get(client.screen)
                check(MenuTooltipMotion::class.java.getDeclaredField("key").apply { isAccessible = true }.get(tooltip) != null) {
                    "Personal-rate screenshot did not show its hover tooltip"
                }
            }
            if (current.name.startsWith("browser-native") && browserFixture?.advance(client) != true) return@register
            if (current.name.startsWith("journal-") && journalFixture?.advance(client) != true) return@register
            if (ticks >= captureTick) {
                pending = true
                Screenshot.takeScreenshot(client.mainRenderTarget) { image ->
                    val captured = runCatching {
                        image.use {
                            it.writeToFile(output.resolve("${current.name}.png"))
                            if (current.name.startsWith("browser-native")) checkNotNull(browserFixture).checkPaint(it, client)
                        }
                    }
                    client.execute {
                        captured.getOrThrow()
                        if (current.name.startsWith("dungeon-tracker-") || current.name == "daily-rng-local-history") {
                            trackerHistoryFixture?.restore(); trackerHistoryFixture = null
                            previousMouse?.let { (x, y) -> testMouse(client, x, y) }; previousMouse = null
                        }
                        if (current.name == "daily-rng-local-history") {
                            check(receiveRngEntry().setBoolean(checkNotNull(savedReceiveRng)))
                            savedReceiveRng = null; rngArchiveSnapshot = null; DailyDigest.screenClosed()
                        }
                        if (current.name == "daily-news-video") { newsFixture?.restore(); newsFixture = null }
                        if (current.name.startsWith("browser-native")) { browserFixture?.close(client); browserFixture = null }
                        if (current.name.startsWith("journal-")) { journalFixture?.restore(); journalFixture = null }
                        if (current.name.startsWith("dungeon-splits-stats")) { splitsFixture?.restore(); splitsFixture = null }
                        scene++; ticks = 0; pending = false
                    }
                }
            }
        }
    }
}

/** Temporarily seed the public-news cache; offline screens never start refresh or thumbnail requests. */
private class GuiVisualNewsFixture {
    private val repository = DailyDigest.javaClass.getDeclaredField("news").apply { isAccessible = true }.get(DailyDigest) as DigestNewsRepository
    @Suppress("UNCHECKED_CAST")
    private val states = repository.javaClass.getDeclaredField("states").apply { isAccessible = true }.get(repository) as MutableMap<NewsSource, DigestNewsState>
    private val saved = states.toMap()
    private val enabled = me.mycellium.skymyce.config.misc.DailyDigestConfig.news
    init {
        check(repository.pendingCount == 0 && Minecraft.getInstance().connection == null)
        me.mycellium.skymyce.config.misc.DailyDigestConfig.news = true
        val now = System.currentTimeMillis()
        states[NewsSource.GAME] = DigestNewsState(listOf(DigestNewsItem("fixture-update", NewsSource.GAME,
            "Game update (layout fixture)", "A cached normal update remains alongside the video card.", now - 120000,
            links = listOf("https://youtu.be/abcdefghijk", "https://hypixel.net/threads/fixture-update.123/"))), now)
        states[NewsSource.DERAILIOUS] = DigestNewsState(listOf(DigestNewsItem("fixture-video", NewsSource.DERAILIOUS,
            "Derailious video (layout fixture)", "Text-first video preview. The browser opens only after confirmation.",
            now - 60000, "https://www.youtube.com/watch?v=abcdefghijk")), now)
        DailyDigest.screenClosed()
    }
    fun restore() {
        check(repository.pendingCount == 0)
        states.clear(); states.putAll(saved)
        me.mycellium.skymyce.config.misc.DailyDigestConfig.news = enabled
        DailyDigest.screenClosed()
    }
}

/** Synthetic in-memory records exercise the real controls without editing the acquisition archive. */
private class GuiVisualTrackerHistoryFixture(item: String = "SHADOW_WARP_SCROLL", private val timelineCount: Int = 2) {
    private val stateField = AcquisitionRepository.javaClass.getDeclaredField("state").apply { isAccessible = true }
    private val previous = AcquisitionRepository.state
    private val previousPreferences = AcquisitionRepository.preferences
    private val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Fixture")
    private val time = 1_792_003_200_000L
    private val state = AcquisitionArchive(events = if (timelineCount == 2) listOf(
        AcquisitionEvent("1".repeat(32), item, time, "dungeon", floor = "M7", quantity = 2, origin = "legacy-tracker"),
        AcquisitionEvent("2".repeat(32), item, time + 1000, "dungeon", context, "M7", "BEDROCK", run = "fixture-run"))
        else List(timelineCount) { index -> AcquisitionEvent((index + 1).toString(16).padStart(32, '0'), item,
            time + index * 1000L, "dungeon", context = if (index == 0) context else null, floor = "M7",
            chest = if (index == 0) "BEDROCK" else null, run = if (index == 0) "fixture-run" else null,
            origin = if (index == 0) "confirmed" else "legacy-tracker") },
        runs = listOf(DungeonRunRecord("fixture-run", "fixture-session", context, "M7", time, time + 1000, 1000,
            chests = listOf(ChestReceipt("fixture-chest", time + 1000, "BEDROCK", 0.0, listOf(
                RunLoot(item, 1, 1_000_000.0), RunLoot("GUI_FIXTURE_UNPRICED", 2, 123_456_789.0)))))),
        personalRateSamples = listOf(PersonalRateSample("M7", time - 86400000, time, 20,
            mapOf(item to 2L))))
    init { stateField.set(AcquisitionRepository, state) }
    fun checkIntact() {
        check(AcquisitionRepository.state === state && state.events.size == timelineCount && state.runs.size == 1) {
            "A tracker viewing control modified saved acquisitions"
        }
    }
    fun restore() { checkIntact(); stateField.set(AcquisitionRepository, previous); AcquisitionRepository.remember(previousPreferences) }
}

private fun checkMenuItemModels(client: Minecraft) {
    fun sprites(stack: ItemStack) = menuItemModelTextures(stack).map { it.first.contents().name() }.toSet()
    val defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS)
        .set(DataComponents.ITEM_MODEL, Identifier.parse("minecraft:paper")).build()
    val holder = Holder.direct(Items.PAPER, defaults)
    fun model(name: String) = ItemStack(holder, 1).apply { set(DataComponents.ITEM_MODEL, Identifier.parse(name)) }
    val paper = setOf(Identifier.parse("minecraft:item/paper"))
    check(sprites(model("minecraft:paper")) == paper)
    check(sprites(model("minecraft:writable_book")) == setOf(Identifier.parse("minecraft:item/writable_book")))
    val missing = model("skymyce:gui_fixture_missing_model")
    check(sprites(missing) == paper && missing.get(DataComponents.ITEM_MODEL) == Identifier.parse("skymyce:gui_fixture_missing_model")) {
        "A missing icon model changed the original item stack instead of its display copy"
    }
    for (item in listOf("IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL")) {
        val raw = SkyBlockItemsRepo.get(item)?.getAsJsonObject("components")?.get("minecraft:item_model")?.asString
        val decoded = SimpleItemAPI.getLazyItemStackForItem(SkyBlockId.item(item))?.get(DataComponents.ITEM_MODEL)
        val actual = runCatching { me.mycellium.skymyce.utils.ItemUtils.displayItem(SkyBlockId.item(item)) }
        println("Visual actual item model: $item raw=$raw decoded=$decoded stack=${actual.getOrNull()?.get(DataComponents.ITEM_MODEL)} error=${actual.exceptionOrNull()?.message}")
        check(raw != null && decoded == Identifier.parse(raw)) { "A scroll lost its canonical SkyBlock item model during decoding" }
        check(actual.getOrThrow().get(DataComponents.ITEM_MODEL) == decoded && sprites(actual.getOrThrow()) == sprites(model(raw))) {
            "A real scroll rendered as a fallback item"
        }
    }
    if (client.resourceManager.getResource(Identifier.parse("hypixel_skyblock:items/item/combat_1/tarantula_catalyst.json")).isPresent) {
        check(sprites(model("hypixel_skyblock:item/combat_1/tarantula_catalyst")) == setOf(Identifier.parse("hypixel_skyblock:item/combat_1/tarantula_catalyst")))
        check(sprites(model("hypixel_skyblock:item/uncategorized/titanium_drill_dr_x655")) == setOf(
            Identifier.parse("hypixel_skyblock:item/uncategorized/titanium_drill_dr_x655_head"),
            Identifier.parse("hypixel_skyblock:item/uncategorized/titanium_drill_dr_x655_handle")))
        check(sprites(model("hypixel_skyblock:item/great_spook/ghoul_buster")) == setOf(Identifier.parse("hypixel_skyblock:item/great_spook/ghoul_buster")))
    }
    println("Visual item model check passed: selected item model, safe missing-model fallback and available pack GUI/composite layers")
}

private fun checkImmediateSendFailure(client: Minecraft) {
    check(client.options.getSoundSourceOptionInstance(SoundSource.MASTER).get() == 0.0) { "Test client audio must be muted" }
    val relay = DungeonFriendRelay
    fun field(name: String) = relay.javaClass.getDeclaredField(name).apply { isAccessible = true }
    val fields = listOf("socket", "sending", "connected", "generation", "status", "stage").associateWith(::field)
    val saved = fields.mapValues { (_, field) -> field.get(relay) }
    val retry = field("retry").get(relay) as RelayRetry
    check(client.player == null && client.connection == null && !CommunityClient.connected && !CommunityClient.hasSavedSession)
    check(saved["socket"] == null && saved["connected"] == false && relay.diagnostics().values.all { it == 0L })
    check(retry.nextAttempt == 0L && retry.delay == 1000L)
    var inline = false; client.execute { inline = true }; check(inline)
    val packet = relay.javaClass.getDeclaredMethod("packet", Any::class.java, Function0::class.java).apply { isAccessible = true }
    val writes = arrayListOf<String>(); var aborts = 0
    fun socket(fail: Boolean) = Proxy.newProxyInstance(WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)) { proxy, method, args ->
        when (method.name) {
            "sendText" -> { writes += args!![0].toString()
                if (fail) CompletableFuture.failedFuture<WebSocket>(IOException("Synthetic immediate send failure"))
                else CompletableFuture.completedFuture(proxy as WebSocket) }
            "abort" -> { aborts++; null }
            else -> error("Unexpected test WebSocket call: ${method.name}")
        }
    } as WebSocket
    try {
        fields.getValue("socket").set(relay, socket(true)); fields.getValue("connected").setBoolean(relay, true)
        packet.invoke(relay, "test-only", { true })
        val recovered = fields.getValue("sending").get(relay) as CompletableFuture<*>
        check(recovered.isDone && !recovered.isCompletedExceptionally && !relay.connected && aborts == 1)
        check(fields.getValue("socket").get(relay) == null)
        fields.getValue("socket").set(relay, socket(false))
        packet.invoke(relay, "fresh test-only", { true })
        check(writes == listOf("test-only", "fresh test-only") && aborts == 1)
        val blocked = CompletableFuture<WebSocket>()
        fields.getValue("sending").set(relay, blocked)
        var consent = true
        packet.invoke(relay, "cancelled test-only", { consent })
        consent = false
        blocked.complete(fields.getValue("socket").get(relay) as WebSocket)
        check((fields.getValue("sending").get(relay) as CompletableFuture<*>).isDone)
        check(writes == listOf("test-only", "fresh test-only")) { "A queued write survived revoked consent" }
    } finally {
        fields.forEach { (name, field) -> field.set(relay, saved[name]) }; retry.reset()
    }
    println("Visual fixture relay check passed: inline failure recovery, fresh writes, revoked pending consent, no replay or network")
}

/** Render the production widget's editor preview without a world or fabricated tracking state. */
private class GuiVisualDungeonTrackerPreviewScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Dungeon Tracker preview")) {
    private val widget = DungeonTrackerWidget.widget
    override fun init() {
        super.init()
        check(widget.prepareEditor()) { "Dungeon Tracker editor preview did not mount" }
        val components = arrayListOf<UIComponent>()
        checkNotNull(widget.adapter).rootComponent.collectDescendants(components)
        val rows = components.filterIsInstance<me.mycellium.skymyce.hud.HudTextLabel>().map { it.text().string.trim() }
        check(rows.any { it == "M7 Dungeon Tracker" } && rows.count { it.isEmpty() } == 2)
        for (field in listOf("Time:", "Runs:", "Chests:", "Profit:", "Cata Exp:")) check(rows.any { it.startsWith(field) })
        check(rows.none { it.contains("Historical") || it.contains("Inherited") || it.contains("Current profile") || it.contains("Editor preview") })
        check(widget.width in 1..width && widget.height in 1..height)
    }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        output.fill(0, 0, width, height, 0xFF101725.toInt())
        check(widget.render(output, mouseX, mouseY, partialTick, editor = true))
    }
}

private class GuiVisualWidgetPreviewScreen(private val widget: me.mycellium.skymyce.hud.widget.Widget) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("${widget.title} preview")) {
    override fun init() {
        super.init()
        check(widget.prepareEditor()) { "${widget.title} editor preview did not mount" }
        check(widget.width in 1..width && widget.height in 1..height) { "${widget.width}x${widget.height} in ${width}x$height" }
    }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        output.fill(0, 0, width, height, 0xFF101725.toInt())
        check(widget.render(output, mouseX, mouseY, partialTick, editor = true))
    }
}

private class GuiVisualGoldorMapPreviewScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Goldor map preview")) {
    private val widget = me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorHud.widget
    override fun init() {
        super.init()
        check(widget.prepareEditor()) { "Goldor map editor preview did not mount" }
        check(widget.width in 1..width && widget.height in 1..height) { "${widget.width}x${widget.height} in ${width}x$height" }
    }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        output.fill(0, 0, width, height, 0xFF101725.toInt())
        check(widget.render(output, mouseX, mouseY, partialTick, editor = true))
    }
}

private class GuiVisualDungeonSplitsPreviewScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Dungeon Splits preview")) {
    private val widget = me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsHud.widget
    override fun init() {
        super.init()
        check(widget.prepareEditor()) { "Dungeon Splits editor preview did not mount" }
        val components = arrayListOf<UIComponent>()
        checkNotNull(widget.adapter).rootComponent.collectDescendants(components)
        val rows = components.filterIsInstance<me.mycellium.skymyce.hud.HudTextLabel>().map { it.text().string.trim() }
        check("M7 Splits" in rows && "Terms" in rows && "S1" in rows && "S4" in rows && "Goldor" in rows && "Drags" in rows && "Portal" in rows && "Boss Entry" in rows && "300 Score" in rows) { "$rows" }
        check(rows.any { it.startsWith("Lag lost") } && rows.any { it.startsWith("(") }) { "Server time and lag lost must be visible: $rows" }
        check(widget.width in 1..width && widget.height in 1..height)
    }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        output.fill(0, 0, width, height, 0xFF101725.toInt())
        check(widget.render(output, mouseX, mouseY, partialTick, editor = true))
    }
}

/** Synthetic in-memory runs for the statistics menu; the saved history file is never written. */
internal class GuiVisualSplitsFixture {
    private val store = me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitStore
    private val saved = store.javaClass.getDeclaredField("saved").apply { isAccessible = true }
    private val previous = store.runs
    init {
        check(Minecraft.getInstance().connection == null)
        val definition = me.mycellium.skymyce.features.instances.dungeons.splits.splitDefinitions.getValue("M7")
        val now = System.currentTimeMillis()
        val runs = (0 until 14).map { index ->
            var at = 0L
            val marks = linkedMapOf(me.mycellium.skymyce.features.instances.dungeons.splits.SplitMilestone.START to 0L)
            definition.sections.forEachIndexed { section, it -> at += 9_000L + ((index * 7 + section * 13) % 11) * 1_100L; marks[it.milestone] = at }
            marks[me.mycellium.skymyce.features.instances.dungeons.splits.SplitMilestone.SCORE_300] = 95_000L + index * 1_300L
            if (index % 5 == 4) marks.remove(me.mycellium.skymyce.features.instances.dungeons.splits.SplitMilestone.RELICS)
            // Server ticks for most runs, with a little lag that grows through the run; two older runs have real time only.
            val ticks = if (index >= 12) emptyMap() else marks.mapValues { (_, value) -> (value - value * (index % 4 + 1) / 150) / 50 }
            me.mycellium.skymyce.features.instances.dungeons.splits.SplitRunRecord("fixture-$index", "M7", 1, now - index * 1_900_000L, index != 9, marks,
                if (index == 6) me.mycellium.skymyce.features.instances.dungeons.splits.SplitOutcome.FAILED else me.mycellium.skymyce.features.instances.dungeons.splits.SplitOutcome.COMPLETED,
                ticks = ticks)
        }
        saved.set(store, runs)
    }
    fun screen(clock: me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock = me.mycellium.skymyce.features.instances.dungeons.splits.SplitClock.REAL) =
        me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen(clock)
    fun restore() { saved.set(store, previous) }
}

/** Card/layout verification only; no world exists and these blank windows never claim gameplay. */
private class GuiVisualLeapMenuScreen(private val missing: Boolean = false) : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Spirit Leap")) {
    private val candidates = listOf(LeapCandidate(LeapIdentity("ArcherTeammate"), 11, "Archer"),
        LeapCandidate(LeapIdentity("BerserkTeammate"), 12, "Berserk"), LeapCandidate(LeapIdentity("HealerTeammate"), 14, "Healer"),
        LeapCandidate(LeapIdentity("TankTeammate"), 15, "Tank", dead = true))
    override fun init() {
        check(minecraft.level == null && minecraft.connection == null && minecraft.player == null)
        check(minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).get() == 0.0)
    }
    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        output.fill(0, 0, width, height, 0xFF394B42.toInt())
        repeat(4) { index ->
            val view = leapQuadrantBounds(width, height, index)
            if (!missing || index != 2) {
                val skin = net.minecraft.client.resources.DefaultPlayerSkin.get(java.util.UUID(0, index + 1L))
                val caption = when (index) { 0 -> "Shared POV"; 1 -> "World not loaded"; else -> null }
                drawLeapCard(output, view, candidates[index], skin, index == 0, index, caption)
            }
        }
    }
}

/** Actual repository definitions exercise the offline icon path, including the official pack. */
private class GuiVisualItemModelsScreen : MenuScreen(Component.literal("SkyBlock item models")) {
    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(16)); root.gap(10)
        root.child(MenuLabel(Component.literal("Official SkyBlock item models")))
        listOf("IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL", "NECRON_HANDLE",
            "TARANTULA_CATALYST", "RECOMBOBULATOR_3000").chunked(2).forEach { items ->
            root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(12)
                items.forEach { id ->
                    val stack = me.mycellium.skymyce.utils.ItemUtils.displayItem(SkyBlockId.item(id))
                    child(UIContainers.horizontalFlow(Sizing.fill(48), Sizing.content()).apply {
                        gap(8)
                        child(MenuItem(stack).apply { id("model-$id"); sizing(Sizing.fixed(40), Sizing.fixed(40)) })
                        child(MenuLabel(stack.hoverName).apply { horizontalSizing(Sizing.expand()) })
                    })
                }
            })
        }
    }
}

/** Shared production controls mounted together, with an open scrollable dropdown for visual review. */
private class GuiVisualControlsScreen : MenuScreen(Component.literal("Control review")) {
    private lateinit var options: ButtonComponent
    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(20)); root.gap(12)
        root.child(MenuLabel(Component.literal("Controls and overlays")).apply { fontSize = 17f; horizontalSizing(Sizing.fill()) })
        root.child(MenuLabel(Component.literal("Preview").withColor(MenuTheme.MUTED)).apply {
            text(Component.literal("Updated captions retain their readable theme color."))
            check(color().get().rgb() == MenuTheme.MUTED) { "A plain caption update lost its readable theme color" }
            horizontalSizing(Sizing.fill())
        })
        root.child(MenuTextBox(Sizing.fill()).apply { setHint(Component.literal("Search features…")) })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(8)
            child(MenuButton(Component.literal("Enabled")) {}.apply {
                toggled = { true }; sizing(Sizing.fixed(70), Sizing.fixed(26))
            })
            child(MenuButton(Component.literal("Disabled")) {}.apply {
                active = false; sizing(Sizing.fixed(70), Sizing.fixed(26))
            })
            child(MenuButton(Component.literal("Selected")) {}.apply {
                selected = { true }; sizing(Sizing.fixed(80), Sizing.fixed(26))
            })
        })
        root.child(MenuSlider().apply { value(.6); verticalSizing(Sizing.fixed(24)) })
        root.child(MenuLabel(Component.literal("Invalid value: enter a number between 0 and 100.").withColor(MenuTheme.RED)).apply {
            horizontalSizing(Sizing.fill())
        })
        options = MenuButton(Component.literal("Choose an option")) {}.apply {
            trailingChevron = true; sizing(Sizing.fixed(180), Sizing.fixed(26))
        }
        root.child(options)
    }
    override fun init() {
        super.init()
        openMenuDropdown(options) { dropdown ->
            repeat(14) { index -> dropdown.checkbox(Component.literal("Option ${index + 1}"), index == 2) {} }
        }
    }
}
