package me.mycellium.skymyce.hud.widget

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import java.io.File
import java.lang.ref.WeakReference
import me.mycellium.skymyce.hud.ThemeOverrides
import me.mycellium.skymyce.hud.parseThemeDocument
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

object WidgetManager : SkyMyceModule() {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    val saveFile: File = SkyMyce.configPath.resolve("widgets.json").toFile()

    private var saved = JsonObject()
    private var loaded = false
    private var pendingSave = false
    private val initial = mutableMapOf<String, WidgetConfig>()
    private lateinit var writer: StateWriter<JsonObject>

    override fun init() {
        val file = AtomicJsonFile(saveFile.toPath(), 256 * 1024)
        writer = StateWriter({ file.write(it) }, { action -> Scheduling.schedule(500.milliseconds) { action() } },
            { SkyMyce.logger.warn("Could not save HUD layout; previous file retained") })
        Scheduling.schedule(0.milliseconds) {
            val json = file.read()
            if (json != null) file.preserveOriginal()
            val root = json?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            MC.instance.execute {
                saved = root
                widgets.forEach { widget ->
                    parseWidgetConfig(root.get(widget.id))?.let { stored ->
                        val before = initial[widget.id] ?: return@let
                        val current = widget.toConfig()
                        stored.copy(x = if (current.x != before.x) current.x else stored.x,
                            y = if (current.y != before.y) current.y else stored.y,
                            scale = if (current.scale != before.scale) current.scale else stored.scale,
                            anchor = if (current.anchor != before.anchor) current.anchor else stored.anchor,
                            visible = if (current.visible != before.visible) current.visible else stored.visible,
                            theme = if (current.theme != before.theme) current.theme else stored.theme).applyConfig(widget)
                    }
                }
                initial.clear(); loaded = true
                if (pendingSave) save()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Hawk-HUD-Save"))
    }

    private val instances = mutableListOf<WeakReference<Widget>>()

    fun Widget.register() {
        instances += WeakReference(this)
        if (loaded) parseWidgetConfig(saved.get(id))?.applyConfig(this) else initial[id] = toConfig()
    }

    fun save() {
        if (!loaded) { pendingSave = true; return }
        val snapshot = saved.deepCopy()
        widgets.forEach { widget ->
            val row = snapshot.get(widget.id)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            row.remove("theme") // Null resets the override instead of retaining its previous JSON field.
            gson.toJsonTree(widget.toConfig()).asJsonObject.entrySet().forEach { (key, value) -> row.add(key, value) }
            snapshot.add(widget.id, row)
        }
        saved = snapshot; pendingSave = false; writer.submit(snapshot)
    }

    val widgets: List<Widget>
        get() {
            instances.removeIf { it.get() == null }
            return instances.mapNotNull { it.get() }
        }

    /** The render loop keeps weak ownership without materializing a widget list each frame. */
    fun forEachWidget(action: (Widget) -> Unit) = instances.forEachAlive(action)

    fun widgetAt(mouseX: Int, mouseY: Int): Widget? =
        instances.lastAliveOrNull { it.isRenderable && it.inBounds(mouseX, mouseY) }

    val renderableWidgets: List<Widget>
        get() = widgets.filter { it.isRenderable }

    fun getActiveWidgets(): List<Widget> {
        val now = System.currentTimeMillis()
        return widgets.filter { (now - it.sinceActive) < 10000 }
    }
}

internal fun <T : Any> MutableList<WeakReference<T>>.forEachAlive(action: (T) -> Unit) {
    var index = 0
    var remaining = size // Widgets registered by a builder join the next frame, as with the old snapshot.
    while (remaining-- > 0) {
        val value = this[index].get()
        if (value == null) removeAt(index) else { index++; action(value) }
    }
}

internal fun <T : Any> MutableList<WeakReference<T>>.lastAliveOrNull(predicate: (T) -> Boolean): T? {
    for (index in lastIndex downTo 0) {
        val value = this[index].get()
        if (value == null) removeAt(index) else if (predicate(value)) return value
    }
    return null
}

data class WidgetConfig(
    val x: Int = 0,
    val y: Int = 0,
    val scale: Float = 1f,
    val anchor: Anchor = Anchor.TOP_LEFT,
    val theme: ThemeOverrides? = null,
    val visible: Boolean = true,
) {
    fun applyConfig(widget: Widget) {
        widget.x = x
        widget.y = y
        widget.scale = scale
        widget.anchor = anchor
        widget.theme = theme
        widget.visible = visible
    }
}

internal fun parseWidgetConfig(json: JsonElement?): WidgetConfig? = runCatching {
    val row = json?.asJsonObject ?: return null
    fun position(key: String) = runCatching { row.get(key)?.asInt?.coerceIn(-100000, 100000) }.getOrNull() ?: 0
    val scale = runCatching { row.get("scale")?.asFloat?.takeIf { it.isFinite() }?.coerceIn(.1f, 3f) }.getOrNull() ?: 1f
    val anchor = runCatching { Anchor.valueOf(row.get("anchor")?.asString.orEmpty()) }.getOrNull() ?: Anchor.TOP_LEFT
    val theme = runCatching { row.get("theme")?.takeUnless { it.isJsonNull }?.let {
        parseThemeDocument("""{"version":1,"overrides":$it}""").overrides
    } }.getOrNull()
    val visible = runCatching { row.get("visible")?.asBoolean }.getOrNull() ?: true
    WidgetConfig(position("x"), position("y"), scale, anchor, theme, visible)
}.getOrNull()
