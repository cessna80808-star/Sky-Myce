package me.mycellium.skymyce.ui

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.ParentUIComponent
import io.wispforest.owo.ui.core.UIComponent
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionArchive
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionContext
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionEvent
import me.mycellium.skymyce.features.instances.dungeons.tracker.AcquisitionRepository
import me.mycellium.skymyce.features.instances.dungeons.tracker.JournalFilter
import me.mycellium.skymyce.features.instances.dungeons.tracker.JournalView
import me.mycellium.skymyce.features.instances.dungeons.tracker.RngJournalScreen
import me.mycellium.skymyce.features.instances.dungeons.tracker.TrackerScope
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/** Synthetic local rows exercise the real journal controls without writing acquisition records. */
internal class GuiVisualJournalFixture(private val details: Boolean = false, private val narrow: Boolean = false) {
    private val stateField = AcquisitionRepository.javaClass.getDeclaredField("state").apply { isAccessible = true }
    private val preferencesField = AcquisitionRepository.javaClass.getDeclaredField("journalPreferences").apply { isAccessible = true }
    private val previous = AcquisitionRepository.state
    private val previousPreferences = AcquisitionRepository.journalPreferences
    private val context = AcquisitionContext("a".repeat(32), "b".repeat(32), "Fixture")
    private val time = System.currentTimeMillis() - 60_000
    private val archive = AcquisitionArchive(events = List(40) { index ->
        val slayer = index % 3 == 2
        AcquisitionEvent((index + 1).toString(16).padStart(32, '0'), if (slayer) "WARDEN_HEART" else "NECRON_HANDLE",
            time - index * 3_600_000L, if (slayer) "slayer" else "dungeon", context,
            floor = if (slayer) null else if (index % 2 == 0) "M7" else "F7", chest = if (slayer) null else "BEDROCK",
            quantity = if (index % 4 == 0) 2 else 1,
            value = when (index % 3) { 0 -> null; 1 -> 0.0; else -> 1_250_000.0 },
            chestCost = if (index % 2 == 0) 0.0 else null, run = if (slayer) null else "fixture-run-$index",
            origin = if (index == 39) "legacy-tracker" else "confirmed", player = "Fixture_Player",
            valuationAt = if (index % 3 == 0) null else time - index * 3_600_000L,
            rarity = if (index % 2 == 0) "LEGENDARY" else null)
    })
    private var stage = 0
    private var ticks = 0
    private var selected = ""

    init {
        check(Minecraft.getInstance().connection == null)
        stateField.set(AcquisitionRepository, archive)
        preferencesField.set(AcquisitionRepository, JournalFilter(scope = TrackerScope.ALL_LOCAL_PROFILES, query = "handle", includeLegacy = true))
    }

    fun screen() = RngJournalScreen()

    fun advance(client: Minecraft): Boolean {
        check(client.connection == null) { "Journal visual checks must stay offline" }
        check(++ticks < 240) { "Journal visual controls did not finish stage $stage" }
        checkIntact()
        val screen = client.screen as? RngJournalScreen ?: return false
        val adapter = adapter(screen)
        val components = arrayListOf<UIComponent>().apply { adapter.rootComponent.collectDescendants(this) }
        val view = RngJournalScreen::class.java.getDeclaredField("view").apply { isAccessible = true }.get(screen) as JournalView
        val filter = RngJournalScreen::class.java.getDeclaredField("filter").apply { isAccessible = true }.get(screen) as JournalFilter
        if (view.events.isEmpty() || components.none { it.id()?.startsWith("journal-event-") == true }) return false
        when (stage) {
            0 -> {
                check(view.events.all { it.item == "NECRON_HANDLE" })
                (components.single { it.id() == "journal-search" } as MenuTextBox).text("warden")
                stage++
            }
            1 -> {
                if (filter.query != "warden" || view.events.any { it.item != "WARDEN_HEART" }) return false
                press(screen, adapter, components.single { it.id() == "journal-clear" })
                stage++
            }
            2 -> {
                if (filter.query.isNotEmpty() || view.events.size != archive.events.size) return false
                check(filter.scope == TrackerScope.ALL_LOCAL_PROFILES) { "Clear filters changed the offline profile scope" }
                check(filter.includeLegacy) { "Clear filters hid imported reports" }
                check(components.filterIsInstance<MenuButton>().any { it.message.string == "All profiles" })
                checkCollapsed(components)
                checkLayout(components)
                selected = view.events.first().id
                check(!(components.single { it.id() == "journal-previous" } as MenuButton).active())
                check((components.single { it.id() == "journal-next" } as MenuButton).active())
                press(screen, adapter, components.single { it.id() == "journal-next" })
                stage++
            }
            3 -> {
                if (components.any { it.id() == "journal-event-$selected" }) return false
                check((components.single { it.id() == "journal-previous" } as MenuButton).active())
                check(!(components.single { it.id() == "journal-next" } as MenuButton).active())
                press(screen, adapter, components.single { it.id() == "journal-previous" })
                stage++
            }
            4 -> {
                if (components.none { it.id() == "journal-event-$selected" }) return false
                press(screen, adapter, components.single { it.id() == "journal-details-toggle-$selected" })
                stage++
            }
            5 -> {
                val expanded = components.singleOrNull { it.id() == "journal-details-$selected" } ?: return false
                val labels = labels(expanded)
                check(labels.any { "NECRON_HANDLE" in it }) { "Details lost the recorded item identifier" }
                check(labels.any { "confirmation" in it.lowercase() || "confirmed" in it.lowercase() }) { "Details lost acquisition provenance" }
                check(labels.any { "unknown" in it.lowercase() }) { "An unknown recorded value was hidden or treated as zero" }
                check(labels.any { "chest" in it.lowercase() && Regex("(?<![0-9])0(?:\\.0)?(?![0-9])").containsMatchIn(it) }) {
                    "A valid zero chest cost was hidden or treated as unknown"
                }
                checkIntact()
                if (details) { stage = 7; return true }
                screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
                stage++
            }
            6 -> {
                check(components.none { it.id() == "journal-details-$selected" }) {
                    "A second Enter did not close journal details; the rebuilt toggle lost keyboard focus"
                }
                checkCollapsed(components)
                checkLayout(components)
                stage++
                return true
            }
            else -> return true
        }
        return false
    }

    fun restore() {
        checkIntact()
        stateField.set(AcquisitionRepository, previous)
        AcquisitionRepository.rememberJournal(previousPreferences)
    }

    private fun checkIntact() = check(AcquisitionRepository.state === archive && archive.events.size == 40) {
        "A journal viewing control modified acquisition records"
    }

    private fun checkCollapsed(components: List<UIComponent>) {
        check(components.none { it.id()?.startsWith("journal-details-") == true && it.id()?.startsWith("journal-details-toggle-") != true })
        val text = components.filterIsInstance<MenuLabel>().map { it.text().string }
        check(text.none { "NECRON_HANDLE" in it || "Fixture_Player" in it || "LEGENDARY" in it || "market cache age" in it }) {
            "Collapsed journal rows still expose expanded metadata"
        }
        check(text.any { "unpriced" in it.lowercase() }) { "Unpriced rows need a visible unknown estimate" }
        check(text.any { "UTC" in it }) { "Journal dates lost their UTC period caption" }
    }

    private fun checkLayout(components: List<UIComponent>) {
        val rows = components.filter { it.id()?.startsWith("journal-event-") == true }
        check(rows.all { it.height() <= if (narrow) 80 else 60 }) { "Collapsed journal rows still use oversized cards" }
        val results = components.single { it.id() == "journal-results" }
        val panel = components.single { it.id() == "journal-panel" } as ParentUIComponent
        val controls = arrayListOf<UIComponent>().apply { panel.collectDescendants(this) }
            .filter { it is MenuButton || it is MenuTextBox }.filter { it.y() < results.y() }
        check(controls.all { it.x() >= panel.x() && it.x() + it.width() <= panel.x() + panel.width() }) {
            "Journal filter controls overflow the content panel"
        }
        check(results.height() >= if (narrow) 50 else 100) { "Journal filters leave no useful history viewport" }
    }

    private fun labels(component: UIComponent) = arrayListOf<UIComponent>().apply { (component as ParentUIComponent).collectDescendants(this) }
        .filterIsInstance<MenuLabel>().map { it.text().string }

    private fun adapter(screen: RngJournalScreen) = BaseOwoScreen::class.java.getDeclaredField("uiAdapter")
        .apply { isAccessible = true }.get(screen) as OwoUIAdapter<*>

    private fun press(screen: RngJournalScreen, adapter: OwoUIAdapter<*>, control: UIComponent) {
        checkNotNull(adapter.rootComponent.focusHandler()).focus(control, UIComponent.FocusSource.KEYBOARD_CYCLE)
        screen.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
    }
}
