package me.mycellium.skymyce.ui

import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.config.SettingsTab
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.community.CommunityScreen
import me.mycellium.skymyce.community.LeaderboardScreen
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.digest.DailyDigestScreen
import me.mycellium.skymyce.features.social.CosmeticsScreen
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.ThemeEditorScreen
import me.mycellium.skymyce.hud.ThemeOverrides
import me.mycellium.skymyce.hud.ThemePreset
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

/** Only loaded by runGuiVisualCheck; no world, community session or user profile is used. */
class GuiVisualCheckClient : ClientModInitializer {
    override fun onInitializeClient() {
        data class Scene(val name: String, val theme: ThemePreset, val width: Int = 1280, val height: Int = 800,
                         val scale: Int = 2, val screen: () -> Screen)
        val scenes = ThemePreset.entries.map { Scene("home-${it.name.lowercase()}", it) { MenuHomeScreen() } } + listOf(
            Scene("dungeons-signature", ThemePreset.SIGNATURE) { MenuDirectoryScreen(MenuCategory.DUNGEONS) },
            Scene("settings-signature", ThemePreset.SIGNATURE) { SettingsScreen(null, SettingsTab.PARTY) },
            Scene("settings-light", ThemePreset.LIGHT) { SettingsScreen(null, SettingsTab.THEME) },
            Scene("themes-signature", ThemePreset.SIGNATURE) { ThemeEditorScreen(null) },
            Scene("themes-light", ThemePreset.LIGHT) { ThemeEditorScreen(null) },
            Scene("cosmetics-light", ThemePreset.LIGHT) { CosmeticsScreen() },
            Scene("search-light", ThemePreset.LIGHT) { MenuDirectoryScreen() },
            Scene("leaderboards-community-return", ThemePreset.SIGNATURE) { LeaderboardScreen() },
            Scene("daily-news-normal", ThemePreset.SIGNATURE) { DailyDigestScreen("news") },
            Scene("home-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { MenuHomeScreen() },
            Scene("home-keyboard-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { MenuHomeScreen() },
            Scene("themes-narrow", ThemePreset.LIGHT, 640, 480, 4) { ThemeEditorScreen(null) },
            Scene("settings-narrow", ThemePreset.SIGNATURE, 640, 480, 4) { SettingsScreen(null, SettingsTab.PARTY) },
            Scene("home-small", ThemePreset.SIGNATURE, 480, 360, 4) { MenuHomeScreen() },
            Scene("home-wide", ThemePreset.BOREALIS, 1920, 1080, 1) { MenuHomeScreen() },
            Scene("controls-signature", ThemePreset.SIGNATURE) { GuiVisualControlsScreen() },
            Scene("controls-light", ThemePreset.LIGHT) { GuiVisualControlsScreen() },
            Scene("controls-contrast", ThemePreset.HIGH_CONTRAST, 640, 480, 4) { GuiVisualControlsScreen() },
            Scene("confirm-light", ThemePreset.LIGHT) { MenuConfirmScreen(MenuHomeScreen(), "Review changes", "A themed confirmation keeps actions and cancellation clear.", "Apply") {} }
        )
        val output = Path.of("..", "screenshots").toAbsolutePath().normalize()
        Files.createDirectories(output)
        var scene = 0
        var ticks = 0
        var started = false
        var pending = false
        var leaderboardOrigin: LeaderboardScreen? = null
        var communityOrigin: CommunityScreen? = null
        var leaderboardView = emptyList<Any?>()
        fun adapter(screen: Screen): OwoUIAdapter<*> {
            val field = BaseOwoScreen::class.java.getDeclaredField("uiAdapter").apply { isAccessible = true }
            return field.get(screen) as OwoUIAdapter<*>
        }
        fun press(title: String) {
            val screen = checkNotNull(net.minecraft.client.Minecraft.getInstance().screen)
            val ui = adapter(screen)
            val components = ArrayList<UIComponent>()
            ui.rootComponent.collectDescendants(components)
            val control = components.first { component -> when (component) {
                is MenuButton -> !component.navigation && component.message.string == title
                is MenuLabel -> component.text().string == title && component.canFocus(UIComponent.FocusSource.KEYBOARD_CYCLE)
                else -> false
            } }
            checkNotNull(ui.rootComponent.focusHandler()).focus(control, UIComponent.FocusSource.KEYBOARD_CYCLE)
            screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
        }
        fun leaderboardState(screen: LeaderboardScreen) = listOf("category", "metric", "period", "page", "query").map { name ->
            LeaderboardScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!started) {
                if (client.screen == null || client.overlay != null) return@register
                started = true
                client.options.pauseOnLostFocus = false
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
                ThemeConfig.apply(ThemeOverrides())
                HudTheme.changed()
                navigateMenu(current.screen())
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
            if (ticks >= 25) {
                pending = true
                Screenshot.takeScreenshot(client.mainRenderTarget) { image ->
                    image.use { it.writeToFile(output.resolve("${current.name}.png")) }
                    client.execute { scene++; ticks = 0; pending = false }
                }
            }
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
