package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.mixin.IrcChatScreenAccessor
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.ui.AutoGraphics
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW

/** Emoji button, popup grid and `:shortcode:` suggestions inside the native chat screen; the chat box keeps real text. */
internal object IrcEmojiPicker {
    private const val COLUMNS = 9
    private const val CELL = 20
    private const val PAD = 5
    private const val HEADER = 12
    private const val FIELD = 14
    private const val TABS = 18
    private const val GRID_ROWS = 6
    private const val WIDTH = PAD * 2 + COLUMNS * CELL
    private const val GRID_TOP = PAD + FIELD + 4 + TABS + 2
    private const val HEIGHT = GRID_TOP + GRID_ROWS * CELL + PAD
    private const val SUGGESTIONS = 8
    private const val INPUT_LIMIT = 256 // ChatScreen's input maximum; never insert half of a joined emoji.

    private class Row(val top: Int, val header: String?, val items: List<IrcEmoji>)
    private class Tab(val name: String, val glyph: String, val top: Int)

    private var open = false
    private var searchFocused = false
    private var scroll = 0
    private var rows = emptyList<Row>()
    private var tabs = emptyList<Tab>()
    private var contentHeight = 0
    private var results = 0
    private var built: Triple<IrcEmojiCatalog, String, Int>? = null
    private var openRevision = 0 // Recents stay put while open, so repeated clicks keep hitting the same cell.
    private val canvas = MenuCanvas()
    private val texture = MenuTexture()
    private val search by lazy {
        EditBox(MC.instance.font, 0, 0, WIDTH - PAD * 2 - 8, 10, Component.literal("Search emoji")).apply {
            setBordered(false); setMaxLength(48); setHint(Component.literal("Search emoji"))
            setResponder { scroll = 0 }
        }
    }
    private val buttonGlyph by lazy { ircReadableText("🙂").first().text }

    private var lastValue: String? = null
    private var lastCursor = -1
    private var suggestionKey: String? = null
    private var dismissed: String? = null
    private var suggestionStart = 0
    private var suggestionWidth = 0
    private var suggestions = emptyList<Pair<IrcEmoji, String>>()
    private var selected = 0

    /** IRC always; normal chat only while its toggle is on. Otherwise the chat screen is left exactly as vanilla. */
    private fun active() = RelayMessages.selectedIrcComposer() || GlobalChatConfig.emojiInNormalChat
    private fun input(screen: ChatScreen) = (screen as IrcChatScreenAccessor).`skymyce$getInput`()
    private fun buttonBounds(screen: ChatScreen) = IrcControlBounds(screen.width - 15, screen.height - 14, 13, 12)
    private fun bounds(screen: ChatScreen): IrcControlBounds {
        val width = minOf(WIDTH, screen.width - 4)
        val height = minOf(HEIGHT, screen.height - 18).coerceAtLeast(GRID_TOP + CELL + PAD)
        return IrcControlBounds(screen.width - 2 - width, screen.height - 16 - height, width, height)
    }

    /** The button sits inside vanilla's input background; the field only gives up its rightmost 14 pixels. */
    fun layout(screen: ChatScreen) {
        val input = input(screen)
        val wide = screen.width - 4
        if (active()) { if (input.width != wide - 14) input.width = wide - 14 }
        else if (input.width == wide - 14) input.width = wide
    }

    fun clear() {
        open = false; searchFocused = false; scroll = 0
        search.value = ""; search.isFocused = false
        lastValue = null; suggestionKey = null; dismissed = null; suggestions = emptyList()
        canvas.close(); texture.close()
    }
    private fun close() { open = false; searchFocused = false; search.isFocused = false; canvas.close(); texture.close() }

    private fun rebuild(catalog: IrcEmojiCatalog) {
        val query = search.value.trim()
        val key = Triple(catalog, query, openRevision)
        if (key == built) return
        built = key
        val sections = arrayListOf<Pair<String, List<IrcEmoji>>>()
        if (query.isEmpty()) {
            IrcEmojiChat.recent.mapNotNull(catalog::text).takeIf { it.isNotEmpty() }?.let { sections += "Recent" to it }
            val grouped = catalog.grid.groupBy { it.category }
            IRC_EMOJI_CATEGORIES.forEachIndexed { index, name -> grouped[index]?.let { sections += name to it } }
        } else sections += "Results" to catalog.search(query, 360).map { it.first }
        results = sections.sumOf { it.second.size }
        val nextRows = arrayListOf<Row>()
        val nextTabs = arrayListOf<Tab>()
        var top = 0
        for ((name, items) in sections) {
            nextTabs += Tab(name, items.first().glyph, top)
            nextRows += Row(top, name, emptyList()); top += HEADER
            for (chunk in items.chunked(COLUMNS)) { nextRows += Row(top, null, chunk); top += CELL }
        }
        rows = nextRows; tabs = if (query.isEmpty()) nextTabs else emptyList(); contentHeight = top
    }

    private fun maxScroll(bounds: IrcControlBounds) = (contentHeight - (bounds.height - GRID_TOP - PAD)).coerceAtLeast(0)
    private fun cellAt(bounds: IrcControlBounds, x: Int, y: Int): IrcEmoji? {
        val localX = x - bounds.x - PAD
        val localY = y - bounds.y - GRID_TOP
        if (localX < 0 || localX >= COLUMNS * CELL || localY < 0 || localY >= bounds.height - GRID_TOP - PAD) return null
        val content = localY + scroll
        val row = rows.lastOrNull { it.top <= content } ?: return null
        return if (row.header == null && content < row.top + CELL) row.items.getOrNull(localX / CELL) else null
    }
    private fun tabAt(bounds: IrcControlBounds, x: Int, y: Int): Int? {
        val top = bounds.y + PAD + FIELD + 4
        if (y < top || y >= top + TABS || x < bounds.x + PAD) return null
        return ((x - bounds.x - PAD) / CELL).takeIf { it in tabs.indices }
    }

    private fun insert(input: EditBox, emoji: IrcEmoji) {
        if (input.value.length - input.highlighted.length + emoji.text.length > INPUT_LIMIT) return
        input.insertText(emoji.text)
        IrcEmojiChat.pick(emoji.text)
    }
    private fun replace(input: EditBox, start: Int, end: Int, emoji: IrcEmoji) {
        if (input.value.length - (end - start) + emoji.text.length > INPUT_LIMIT) return
        val edit = ircReplaceRange(input.value, start, end, emoji.text)
        input.value = edit.text
        input.moveCursorTo(edit.cursor, false) // After the emoji, not at the end of the message.
        IrcEmojiChat.pick(emoji.text)
    }

    private fun refreshSuggestions(input: EditBox) {
        val value = input.value
        val cursor = input.cursorPosition
        if (value === lastValue && cursor == lastCursor) return
        lastValue = value; lastCursor = cursor
        val catalog = IrcEmojiChat.catalog
        val query = if (catalog != null && ircEmojiProse(value)) ircShortcodeQuery(value, cursor) else null
        val key = query?.let { "${it.first}:${it.second}" }
        if (key == suggestionKey) return
        suggestionKey = key; selected = 0
        if (key != dismissed) dismissed = null
        suggestions = if (query == null || catalog == null) emptyList() else catalog.search(query.second, SUGGESTIONS)
        suggestionStart = query?.first ?: 0
        val font = MC.instance.font
        suggestionWidth = (suggestions.maxOfOrNull { font.width(":${it.second}:") } ?: 0) + 18
    }
    private fun suggesting() = suggestions.isNotEmpty() && dismissed != suggestionKey && !(open && searchFocused)
    private fun suggestionBounds(screen: ChatScreen, input: EditBox): IrcControlBounds {
        val height = suggestions.size * 12
        val x = (input.getScreenX(suggestionStart) - 1).coerceIn(2, (screen.width - suggestionWidth - 2).coerceAtLeast(2))
        return IrcControlBounds(x, screen.height - 14 - height, suggestionWidth, height)
    }
    private fun accept(input: EditBox, index: Int) {
        val emoji = suggestions.getOrNull(index)?.first ?: return
        replace(input, suggestionStart, input.cursorPosition, emoji)
    }

    fun hasInteractionAt(screen: ChatScreen, x: Int, y: Int): Boolean = active() && (buttonBounds(screen).contains(x, y) ||
        open && bounds(screen).contains(x, y) || suggesting() && suggestionBounds(screen, input(screen)).contains(x, y))

    /** True when the click belongs to the emoji controls and must not reach vanilla chat. */
    fun click(screen: ChatScreen, x: Int, y: Int): Boolean {
        if (!active()) return false
        val input = input(screen)
        if (suggesting()) {
            val list = suggestionBounds(screen, input)
            if (list.contains(x, y)) { accept(input, (y - list.y) / 12); return true }
        }
        if (buttonBounds(screen).contains(x, y)) {
            if (open) close() else { open = true; searchFocused = true; search.isFocused = true; openRevision = IrcEmojiChat.revision }
            return true
        }
        if (!open) return false
        val bounds = bounds(screen)
        if (!bounds.contains(x, y)) {
            // Clicking the chat field returns typing to chat and keeps the picker for more picks.
            if (y >= screen.height - 14) { searchFocused = false; search.isFocused = false } else close()
            return false
        }
        if (y < bounds.y + PAD + FIELD + 2) { searchFocused = true; search.isFocused = true; return true }
        tabAt(bounds, x, y)?.let { scroll = tabs[it].top.coerceAtMost(maxScroll(bounds)); return true }
        cellAt(bounds, x, y)?.let { insert(input, it) }
        return true
    }

    fun scroll(screen: ChatScreen, x: Int, y: Int, amount: Double): Boolean {
        if (!open || !active()) return false
        val bounds = bounds(screen)
        if (!bounds.contains(x, y)) return false
        scroll = (scroll - (amount * CELL).toInt()).coerceIn(0, maxScroll(bounds))
        return true
    }

    /** True when the key was used by the suggestions or the picker search. */
    fun key(screen: ChatScreen, event: KeyEvent): Boolean {
        if (!active()) return false
        val input = input(screen)
        refreshSuggestions(input)
        if (suggesting()) when (event.key()) {
            GLFW.GLFW_KEY_TAB -> { accept(input, selected); return true }
            GLFW.GLFW_KEY_UP -> { selected = (selected - 1 + suggestions.size) % suggestions.size; return true }
            GLFW.GLFW_KEY_DOWN -> { selected = (selected + 1) % suggestions.size; return true }
            GLFW.GLFW_KEY_ESCAPE -> { dismissed = suggestionKey; return true }
        }
        if (!open) return false
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        if (!searchFocused) return false
        when (event.key()) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                // An empty search lets Enter send the message as usual.
                if (search.value.isBlank()) { close(); return false }
                IrcEmojiChat.catalog?.let { rebuild(it) }
                rows.firstOrNull { it.header == null }?.items?.firstOrNull()?.let { insert(input, it) }
                return true
            }
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> return true
        }
        search.keyPressed(event)
        return true
    }

    fun charTyped(event: CharacterEvent): Boolean {
        if (!open || !searchFocused || !active()) return false
        search.charTyped(event)
        return true
    }

    /** Typing the closing colon of a known `:name:` turns it into the emoji, like Discord. */
    fun afterCharTyped(screen: ChatScreen, event: CharacterEvent) {
        if (event.codepoint() != ':'.code || !active()) return
        val catalog = IrcEmojiChat.catalog ?: return
        val input = input(screen)
        if (!ircEmojiProse(input.value)) return
        val cursor = input.cursorPosition
        val (start, text) = ircShortcodeBefore(input.value, cursor, catalog::shortcode) ?: return
        catalog.text(text)?.let { replace(input, start, cursor, it) }
    }

    private fun glyph(output: GuiGraphicsExtractor, glyph: String, x: Int, y: Int, scale: Float) {
        output.pose().pushMatrix()
        output.pose().translate(x.toFloat(), y.toFloat())
        output.pose().scale(scale, scale)
        output.text(MC.instance.font, FormattedCharSequence.forward(glyph, ircEmojiStyle), 0, 0, -1, false)
        output.pose().popMatrix()
    }

    fun render(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, vanillaSuggestions: Boolean) {
        layout(screen)
        if (!active()) { if (open || suggestions.isNotEmpty()) clear(); return }
        val font = MC.instance.font
        val input = input(screen)
        output.nextStratum()
        val button = buttonBounds(screen)
        if (open || button.contains(mouseX, mouseY)) output.fill(button.x, button.y, button.x + button.width, button.y + button.height, 0x40FFFFFF)
        glyph(output, buttonGlyph, button.x + 2, button.y + 2, 1f)
        if (button.contains(mouseX, mouseY) && !open) output.setTooltipForNextFrame(Component.literal("Emoji"), mouseX, mouseY)

        refreshSuggestions(input)
        if (!vanillaSuggestions && suggesting()) {
            val list = suggestionBounds(screen, input)
            output.fill(list.x, list.y, list.x + list.width, list.y + list.height, 0xD0000000.toInt())
            suggestions.forEachIndexed { index, (emoji, name) ->
                val y = list.y + index * 12
                if (list.contains(mouseX, mouseY) && (mouseY - list.y) / 12 == index) selected = index
                glyph(output, emoji.glyph, list.x + 2, y + 2, 1f)
                output.text(font, ":$name:", list.x + 14, y + 2, if (index == selected) 0xFFFFFF00.toInt() else 0xFFAAAAAA.toInt(), true)
            }
        }
        if (open) renderPopup(screen, output, mouseX, mouseY, delta)
    }

    private fun renderPopup(screen: ChatScreen, output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val catalog = IrcEmojiChat.catalog
        val bounds = bounds(screen)
        val font = MC.instance.font
        catalog?.let(::rebuild)
        val gridHeight = bounds.height - GRID_TOP - PAD
        scroll = scroll.coerceIn(0, maxScroll(bounds))
        val visible = rows.filter { it.top + CELL > scroll && it.top < scroll + gridHeight }
        val hovered = cellAt(bounds, mouseX, mouseY)
        val hoveredTab = tabAt(bounds, mouseX, mouseY)
        val currentTab = tabs.indexOfLast { it.top <= scroll + 1 }

        if (texture.prepareFrame()) canvas.close()
        canvas.begin()
        val graphics = MenuGraphics(canvas, mouseX - bounds.x, mouseY - bounds.y, bounds.width, bounds.height)
        MenuTheme.box(graphics, 0, 0, bounds.width, bounds.height, MenuTheme.SIDEBAR)
        MenuTheme.drawField(graphics, PAD, PAD, bounds.width - PAD * 2, FIELD, searchFocused)
        val tabTop = PAD + FIELD + 4
        if (catalog == null) graphics.text(font, "Loading emoji…", PAD + 2, tabTop + 5, MenuTheme.alpha(MenuTheme.MUTED), false)
        else if (tabs.isEmpty()) graphics.text(font, if (results == 0) "No emoji found" else "$results found", PAD + 2, tabTop + 5, MenuTheme.alpha(MenuTheme.MUTED), false)
        tabs.forEachIndexed { index, _ ->
            if (index == currentTab || index == hoveredTab)
                MenuTheme.rounded(graphics, PAD + index * CELL, tabTop, CELL - 2, TABS, if (index == currentTab) MenuTheme.CARD else MenuTheme.blend(MenuTheme.SIDEBAR, MenuTheme.CARD, 50), 3)
        }
        graphics.enableScissor(0, GRID_TOP, bounds.width, GRID_TOP + gridHeight)
        for (row in visible) {
            val y = GRID_TOP + row.top - scroll
            if (row.header != null) graphics.text(font, row.header, PAD + 2, y + 2, MenuTheme.alpha(MenuTheme.MUTED), false)
            else row.items.forEachIndexed { column, emoji ->
                if (emoji === hovered) MenuTheme.rounded(graphics, PAD + column * CELL, y, CELL, CELL, MenuTheme.CARD, 3)
            }
        }
        graphics.disableScissor()
        output.nextStratum()
        output.pose().pushMatrix()
        output.pose().translate(bounds.x.toFloat(), bounds.y.toFloat())
        val scale = minOf(AutoGraphics.quality.rasterScale, 2560.0 / screen.width.coerceAtLeast(1), 1440.0 / screen.height.coerceAtLeast(1))
        texture.render(output, canvas, bounds.width, bounds.height, scale)
        output.pose().popMatrix()

        output.nextStratum()
        search.x = bounds.x + PAD + 4
        search.y = bounds.y + PAD + 3
        search.width = bounds.width - PAD * 2 - 8
        search.setTextColor(MenuTheme.alpha(MenuTheme.TEXT))
        search.extractRenderState(output, mouseX, mouseY, delta)
        tabs.forEachIndexed { index, tab -> glyph(output, tab.glyph, bounds.x + PAD + index * CELL + 1, bounds.y + tabTop + 1, 2f) }
        output.enableScissor(bounds.x, bounds.y + GRID_TOP, bounds.x + bounds.width, bounds.y + GRID_TOP + gridHeight)
        for (row in visible) if (row.header == null) {
            val y = bounds.y + GRID_TOP + row.top - scroll
            row.items.forEachIndexed { column, emoji -> glyph(output, emoji.glyph, bounds.x + PAD + column * CELL + 2, y + 2, 2f) }
        }
        output.disableScissor()
        hovered?.let { emoji -> catalog?.descriptiveName(emoji)?.let { output.setTooltipForNextFrame(Component.literal(":$it:"), mouseX, mouseY) } }
        hoveredTab?.let { output.setTooltipForNextFrame(Component.literal(tabs[it].name), mouseX, mouseY) }
    }
}
