package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.*
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.digest.DigestEntry
import me.mycellium.skymyce.features.digest.digestShareText
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuViewport
import me.mycellium.skymyce.ui.menuCaretIndex
import me.mycellium.skymyce.ui.menuTextLines
import me.mycellium.skymyce.ui.menuTextWidth
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import java.io.File
import javax.imageio.ImageIO

fun checkSettings() {
    // Settings participates in the same card-to-rail navigation as tools. Inspect dispatch
    // without starting Minecraft; a separate settings sidebar breaks this integration.
    fun navigationOwner(name: String): Class<*> {
        var type: Class<*>? = SettingsScreen::class.java
        while (type != null) {
            if (type.declaredMethods.any { it.name == name && it.parameterCount == 0 }) return type
            type = type.superclass
        }
        error("Missing menu member $name")
    }
    check(navigationOwner("getMenuLayout") == me.mycellium.skymyce.ui.MenuScreen::class.java) {
        "Settings must keep the shared compact category rail width"
    }
    check(navigationOwner("buildNavigation") == me.mycellium.skymyce.ui.MenuScreen::class.java) {
        "Settings must retain shared category, Search and Quick Settings navigation"
    }
    val row = settingsRow().horizontalSizing(io.wispforest.owo.ui.core.Sizing.fixed(400)) as io.wispforest.owo.ui.container.FlowLayout
    val first = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(100), io.wispforest.owo.ui.core.Sizing.fixed(20))
    val second = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(100), io.wispforest.owo.ui.core.Sizing.fixed(20))
    row.children(listOf(first, second)); row.inflate(io.wispforest.owo.ui.core.Size.of(400, 240)); row.mount(null, 0, 0)
    check(row.children().size == 2 && second.x() == first.x() + 105 && second.y() == first.y())
    val build = Config.javaClass.methods.first { it.name.startsWith("build") && it.parameterCount == 1 }
    val raw = build.invoke(Config, null) as com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
    val delegate = com.teamresourceful.resourcefulconfig.common.loader.ParsedConfig(7, raw.id(), raw.info(), raw.elements(), raw.categories())
    var saves = 0
    val wrapped = SavingConfig(delegate) { saves++ }
    val rows = settingRows(wrapped) // Use the same saving wrapper as the live settings screen.
    check(rows.map { it.path } == settingRows(delegate).map { it.path }) { "Saving wrapper lost settings" }
    check(wrapped.version() == 7 && wrapped.id() == delegate.id() && wrapped.info() === delegate.info())
    check(wrapped.categories() === delegate.categories())
    wrapped.save(); check(saves == 1)
    check(rows.map { it.path }.containsAll(listOf("devMode", "gatherData", "openConfigKey")))
    check(rows.isNotEmpty() && rows.map { it.path }.distinct().size == rows.size)
    check(rows.all { it.title.isNotBlank() && it.tip.isNotBlank() })
    val catalog = me.mycellium.skymyce.ui.MenuCatalog(rows)
    val allScopedPaths = catalog.features.flatMap { feature -> settingGroups(rows, feature.id).values.flatten().map { it.path } }
    check(allScopedPaths.sorted() == rows.map { it.path }.sorted()) { "Every persisted setting must have exactly one canonical feature/global owner" }
    catalog.features.forEach { feature ->
        val groups = settingGroups(rows, feature.id)
        check(groups.values.flatten().map { it.path }.toSet() == feature.settings.map { it.path }.toSet())
        groups.forEach { (name, settings) ->
            check(settingGroups(rows, feature.id, name.uppercase())[name] == settings) { "Searching a feature must retain all its controls" }
        }
    }
    check(settingGroups(rows, SettingsTab.GENERAL, "no feature has this name").isEmpty())
    val resources = me.mycellium.skymyce.ui.MenuLayout::class.java.classLoader
    val font = resources.getResourceAsStream("assets/skymyce/font/menu.json")!!.bufferedReader().use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
    val fontProvider = font.getAsJsonArray("providers")[0].asJsonObject
    net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition.CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, fontProvider).getOrThrow()
    check(resources.getResource("assets/skymyce/font/${fontProvider.get("file").asString.substringAfter(':')}") != null) { "Menu font resource is missing" }
    for ((width, height) in listOf(320 to 180, 480 to 270, 640 to 360, 960 to 540, 1920 to 1080)) {
        val layout = me.mycellium.skymyce.ui.MenuLayout.fit(width, height)
        check(layout.width <= width && layout.height <= height && layout.contentWidth > 0)
        check(layout.sidebar in 1 until layout.width && layout.contentWidth + layout.sidebar == layout.width)
        val shell = io.wispforest.owo.ui.container.UIContainers.horizontalFlow(io.wispforest.owo.ui.core.Sizing.fixed(layout.width), io.wispforest.owo.ui.core.Sizing.fixed(layout.height))
        val sidebar = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(layout.sidebar), io.wispforest.owo.ui.core.Sizing.fill())
        val content = io.wispforest.owo.ui.component.UIComponents.box(io.wispforest.owo.ui.core.Sizing.fixed(layout.contentWidth), io.wispforest.owo.ui.core.Sizing.fill())
        shell.children(listOf(sidebar, content)); shell.inflate(io.wispforest.owo.ui.core.Size.of(width, height)); shell.mount(null, 8, 8)
        check(content.x() == sidebar.x() + sidebar.width() && content.y() == sidebar.y())
        check(content.x() + content.width() == shell.x() + shell.width()) { "Menu content overlaps or escapes the sidebar" }
    }
    val commandRows = commandCenterRows(wrapped)
    check(commandRows.map { it.path }.distinct().size == commandRows.size)
    check(commandRows.none { it.path.endsWith("/themeVersion") || it.path.endsWith("/menuIntroPlayed") })
    check(commandRows.any { it.path == "Theme & Appearance/border" })
    val categoryPaths = me.mycellium.skymyce.ui.MenuCategory.entries.flatMap {
        commandCenterSettings(catalog, commandRows, it, "").map { row -> row.path }
    }
    check(categoryPaths.sorted() == commandRows.map { it.path }.sorted()) { "Each setting must appear directly in one category" }
    catalog.features.forEach { feature ->
        val scoped = commandCenterSettings(catalog, commandRows, feature.category, "", feature.id)
        check(scoped.all { catalog.featureForPath(it.path)?.id == feature.id }) { "Feature settings leaked unrelated controls" }
        check(scoped.map { it.path }.toSet() == commandRows.filter { catalog.featureForPath(it.path)?.id == feature.id }.map { it.path }.toSet())
    }
    val searched = commandCenterSettings(catalog, commandRows, me.mycellium.skymyce.ui.MenuCategory.COSMETICS, "dungeon profit")
    check(searched.any { it.path.startsWith("Dungeons/") } && searched.any { it.path == "Community Sharing/dungeons" } &&
        searched.none { it.path.startsWith("Cosmetics/") }) { "Search must override the sidebar category and include matching community privacy controls" }
    val sharingSettings = settingGroups(rows, "community").values.flatten()
    check(sharingSettings.filter { it.path.startsWith("Community Sharing/") }.map { it.path.substringAfterLast('/') }.toSet() ==
        setOf("enabled", "dungeons", "economy", "rng", "activity", "leaderboards", "publicName")) {
        "Community sharing controls must have one canonical owner and exclude migration metadata"
    }
    check(commandCenterSettings(catalog, commandRows, me.mycellium.skymyce.ui.MenuCategory.DUNGEONS, "text shadow").any { it.path == "Theme & Appearance/shadow" })
    check(rows.none { it.path.endsWith("migratedDigestTheme") || it.path == "General/Daily Digest/accent" || it.path == "General/Daily Digest/cardOpacity" })
    check(settingsTab("General/Daily Digest/automatic") == SettingsTab.SOCIAL)
    check(settingsTab("General/Daily Digest/shareRng") == SettingsTab.SOCIAL)
    check(settingsTab("Party Finder/matchPb") == SettingsTab.PARTY)
    check(settingsTab("Dungeons/Dungeon Friends/enabled") == SettingsTab.PARTY)
    val friendSettings = settingGroups(rows, SettingsTab.PARTY)
    check("Availability" in friendSettings && "Dungeon Friends" in friendSettings)
    check("Availability" in settingGroups(rows, SettingsTab.PARTY, "PB limit"))
    check("Dungeon Friends" in settingGroups(rows, SettingsTab.PARTY, "message template"))
    check("Dungeon Friends" in settingGroups(rows, SettingsTab.PARTY, "notifications"))
    check("Availability" !in settingGroups(rows, SettingsTab.SOCIAL))
    check(settingsTab("Dungeons/Dungeon Tracker/dungeonTracker") == SettingsTab.COMBAT)
    check(settingsTab("Theme & Appearance/opacity") == SettingsTab.THEME)
    check(rows.any { it.path == "General/Party Commands/hypixelApiKey" }) // Existing storage path retained.
    check(settingGroups(rows, SettingsTab.GENERAL).values.flatten().map { it.path }.toSet() == setOf("gatherData", "devMode"))
    check(settingGroups(rows, "settings-controls").values.flatten().single().path == "openConfigKey")
    check(settingGroups(rows, "settings-performance").values.flatten().single().path == "Theme & Appearance/graphicsQuality")
    check(settingGroups(rows, "party-finder").values.flatten().all {
        it.path.startsWith("Party Finder/") || it.path.startsWith("Dungeons/Dungeon Friends/")
    })
    val decimal = rows.single { it.path == "Dungeons/negativeProfitThreshold" }.entry
    check(settingNumber(decimal, "100.5") == 100.5)
    listOf("NaN", "Infinity", "-1", "", "1000000000001").forEach { check(settingNumber(decimal, it) == null) }
    val integer = rows.single { it.path == "Dungeons/valuableItemThreshold" }.entry
    check(settingNumber(integer, "10") == 10 && settingNumber(integer, "10.5") == null)
    val focusRow = settingsRow()
    fun focusableControl() = object : io.wispforest.owo.ui.container.FlowLayout(io.wispforest.owo.ui.core.Sizing.fixed(20),
        io.wispforest.owo.ui.core.Sizing.fixed(20), io.wispforest.owo.ui.container.FlowLayout.Algorithm.VERTICAL) {
        override fun canFocus(source: io.wispforest.owo.ui.core.UIComponent.FocusSource) = true
    }
    val control = focusableControl()
    val pin = focusableControl()
    focusRow.child(control); focusRow.child(pin)
    check(settingsFocusTarget(focusRow) === control) { "Search must focus the actual control before its Pin shortcut" }
    val devMode = rows.single { it.path == "devMode" }
    check(devMode.title == "Dev Mode" && settingsTab(devMode.path) == SettingsTab.GENERAL)
    val previousDevMode = Config.devMode
    try {
        check(devMode.entry.setBoolean(!previousDevMode) && Config.devMode == !previousDevMode)
    } finally { devMode.entry.setBoolean(previousDevMode) }
    check(parseThemeHex("#67ccf2") == 0x67CCF2 && parseThemeHex("FFFFFF") == 0xFFFFFF)
    listOf("", "#", "#123", "#12345678", "#GGFFFF", "-1").forEach { check(parseThemeHex(it) == null) }
    val primary = ThemeConfig.primary
    ThemeConfig.primary = 0x123456
    check(HudTheme.ACCENT == 0x123456 && HudTheme.HUD_ACCENT == 0x123456)
    check(me.mycellium.skymyce.ui.MenuTheme.ACCENT == 0x123456) { "Custom menus lost the user's accent override" }
    ThemeConfig.primary = primary
    val previousPreset = ThemeConfig.preset
    val priorDocument = currentThemeEditorDocument()
    try {
        val nextPreset = ThemePreset.entries.first { it != previousPreset }
        ThemeConfig.preset = nextPreset
        val reopenedDocument = currentThemeEditorDocument()
        check(reopenedDocument.preset == nextPreset && priorDocument.preset == previousPreset)
        check(reopenedDocument.overrides == priorDocument.overrides) { "Refreshing a preset must retain existing palette overrides" }
    } finally { ThemeConfig.preset = previousPreset }
    check(HudTheme.alpha(0x123456, 0) == 0x123456 && HudTheme.alpha(0x123456, 100) == 0xFF123456.toInt())
    check(HudTheme.blend(0, 0xFFFFFF, 100) == 0xFFFFFF)
    val report = digestShareText(DigestEntry("id", "§aHandle\n/command", activity = "dungeons", community = true))
    check('§' !in report && '\n' !in report && report.endsWith("[unverified]"))
    check(digestShareText(DigestEntry("id", "x".repeat(500))).length <= 240)
    val longReport = digestShareText(DigestEntry("id", "x".repeat(500), community = true))
    check(longReport.length <= 240 && longReport.endsWith("[unverified]"))
    checkMenuViewport()
    checkMenuDropdownInput()
    checkMenuCanvas()
    checkMenuTextMetrics()
    checkMenuTextureAndRuns()
    checkMenuPerformance()
    checkMenuLabelHover()
    checkMenuDamage()
    checkMenuSkullArt()
    println("Settings checks passed: sidebar bounds, searchable feature coverage, font resources, config paths, themes and safe share drafts")
}

private fun checkMenuTextureAndRuns() {
    checkMenuGraphicsChanges()
    val colors = intArrayOf(0, 0xFF123456.toInt(), 0x8020A0E0.toInt(), 0x01336699)
    val images = listOf(java.awt.image.BufferedImage.TYPE_INT_ARGB, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE,
        java.awt.image.BufferedImage.TYPE_4BYTE_ABGR).flatMap { type ->
        val full = java.awt.image.BufferedImage(4, 2, type)
        full.setRGB(0, 0, 4, 2, colors + colors.reversedArray(), 0, 4)
        listOf(full, full.getSubimage(1, 0, 2, 2))
    }
    for (image in images) {
        val target = IntArray(image.width * image.height)
        me.mycellium.skymyce.ui.menuTexturePixels(image, target)
        check(target.contentEquals(image.getRGB(0, 0, image.width, image.height, null, 0, image.width).map(net.minecraft.util.ARGB::toABGR).toIntArray())) {
            "Texture upload changed alpha/channels or read the wrong subimage stride"
        }
        val direct = java.nio.ByteBuffer.allocateDirect(target.size * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
        direct.put(target); direct.clear().put(target)
        check(IntArray(target.size) { direct.get(it) }.contentEquals(target)) { "Bulk upload failed on a repeated frame" }
    }
    val base = net.minecraft.network.chat.Style.EMPTY
    val colored = base.withColor(0xAABBCC).withBold(true).withItalic(true).withUnderlined(true)
    val sequence = net.minecraft.util.FormattedCharSequence.composite(listOf(
        net.minecraft.util.FormattedCharSequence.forward("Long label ", base),
        net.minecraft.util.FormattedCharSequence.forward("keeps one run", base.withInsertion("irrelevant")),
        net.minecraft.util.FormattedCharSequence.forward(" \uD83D\uDE00\u272A", colored),
        net.minecraft.util.FormattedCharSequence.forward(" tail", base)))
    check(me.mycellium.skymyce.ui.menuTextRuns(sequence, 0x80123456.toInt()) == listOf(
        MenuCanvas.Run("Long label keeps one run", 0x80123456.toInt()),
        MenuCanvas.Run(" \uD83D\uDE00\u272A", 0x80AABBCC.toInt(), bold = true, italic = true, underline = true),
        MenuCanvas.Run(" tail", 0x80123456.toInt()))) { "Text capture split Unicode, lost styles/alpha, or failed to merge adjacent styles" }
    check(me.mycellium.skymyce.ui.menuTextRuns(net.minecraft.util.FormattedCharSequence.EMPTY, -1).isEmpty())
}

private fun checkMenuGraphicsChanges() {
    class SubmittedTexture : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
        fun renderExtractedFrame() { check(closes == 0) { "Graphics change disposed a texture still referenced by the extracted frame" } }
    }
    val fences = ArrayList<Runnable>()
    val resources = me.mycellium.skymyce.ui.MenuTextureRetirement(fences::add)
    val canvas = MenuCanvas()
    var submitted: SubmittedTexture? = null
    val retired = ArrayList<SubmittedTexture>()
    // Repeated quality changes and window resizes must repaint and keep already-extracted frames valid.
    for (size in listOf(160 to 90, 211 to 127, 160 to 90)) {
        for (quality in me.mycellium.skymyce.ui.GuiQuality.entries.reversed()) {
            canvas.begin()
            canvas.gradient(0.0, 0.0, size.first.toDouble(), size.second.toDouble(), -1, 0x80AC7BF1.toInt(), 0, -1)
            canvas.text(listOf(MenuCanvas.Run("Graphics: $quality ✓", -1)), 7.25, 9.5)
            val image = canvas.finish(size.first, size.second, quality.rasterScale)
            check(image.width == (size.first * quality.rasterScale).toInt() && image.height == (size.second * quality.rasterScale).toInt())
            check(canvas.dirtyRegion == java.awt.Rectangle(0, 0, image.width, image.height)) { "Quality/size change did not replace all pixels" }
            val upload = IntArray(image.width * image.height)
            me.mycellium.skymyce.ui.menuTexturePixels(image, upload)
            check(upload.contentEquals(image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                .map(net.minecraft.util.ARGB::toABGR).toIntArray())) { "Quality change corrupted the uploaded canvas" }
            submitted?.let { previous ->
                retired += previous
                resources.retire(previous); resources.retire(previous)
                previous.renderExtractedFrame()
            }
            submitted = SubmittedTexture()
        }
        check(fences.isEmpty()) { "Retirement was fenced before the extracted frame could render" }
        resources.afterTick()
        retired.forEach(SubmittedTexture::renderExtractedFrame)
        submitted!!.renderExtractedFrame()
        fences.toList().forEach(Runnable::run); fences.clear()
        check(retired.all { it.closes == 1 }) { "Retired textures leaked or closed twice" }
        retired.clear()
        submitted!!.renderExtractedFrame()
    }
    resources.retire(submitted!!); resources.afterTick()
    val closingDuringExtraction = SubmittedTexture()
    resources.retire(closingDuringExtraction)
    resources.close(); resources.close()
    fences.forEach(Runnable::run)
    check(submitted!!.closes == 1 && closingDuringExtraction.closes == 1) { "Shutdown leaked pending resources or a fence closed them twice" }
    canvas.close()
    println("Menu graphics checks passed: every quality, resize/upload parity, deferred texture lifetime and shutdown cleanup")
}

private fun checkMenuTextMetrics() {
    val text = "Updated 2026-09-30 20:47 | SkyBlock profile: Lemon | Agjy \u272a"
    for (size in listOf(8.0, 9.0, 10.0, 12.0, 16.0, 24.0)) {
        val height = MenuCanvas.lineHeight(size)
        for ((bold, italic) in listOf(false to false, true to false, false to true)) {
            val canvas = MenuCanvas()
            fun paint(clipped: Boolean): IntArray {
                canvas.begin()
                if (clipped) canvas.clip = MenuCanvas.Clip(0, 0, 1000, height)
                canvas.text(listOf(MenuCanvas.Run(text, -1, bold, italic, underline = true)), 4.0, 0.0, size)
                val image = canvas.finish(1000, height + 12, 2.0)
                return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            }
            val full = paint(false)
            check(full.any { it ushr 24 != 0 })
            check(full.contentEquals(paint(true))) { "Menu line height clips glyphs at size $size" }
        }
        val styled = net.minecraft.network.chat.Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD)
        val lines = menuTextLines(styled, 96, size)
        check(lines.size > 1 && lines.all { menuTextWidth(it, size) <= 96.01 }) { "Wrapping uses different metrics than custom paint" }
        check(lines.all { line -> var bold = true; line.accept { _, style, _ -> bold = bold && style.isBold; true }; bold }) { "Wrapping lost text styles" }
    }
    check(MenuCanvas.lineHeight(16.0) > MenuCanvas.lineHeight()) { "Larger headings do not reserve more space" }
}

/** Pinned owo static initialization asks Fabric only for its development flag; no game/mixins are started. */
private fun bootstrapHeadlessOwo() {
    val launcherType = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher")
    val base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase")
    if (base.getMethod("getLauncher").invoke(null) != null) return
    val launcher = java.lang.reflect.Proxy.newProxyInstance(launcherType.classLoader, arrayOf(launcherType)) { proxy, method, args ->
        when (method.name) {
            "isDevelopment" -> false
            "toString" -> "Headless owo static-initialization fixture"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected Fabric launcher operation in a headless UI check: ${method.name}")
        }
    }
    base.getMethod("setLauncher", launcherType).invoke(null, launcher)
}

private fun checkNativeMenuDropdown(rowHeight: Int) {
    bootstrapHeadlessOwo()
    for (height in listOf(140, 540)) for (count in listOf(13, 45)) {
        val root = object : io.wispforest.owo.ui.container.FlowLayout(
            Sizing.fixed(240), Sizing.fixed(height), io.wispforest.owo.ui.container.FlowLayout.Algorithm.VERTICAL) {
            fun duringMouseDispatch(action: () -> Unit) = runAndDeferEvents { action() }
        }
        root.inflate(io.wispforest.owo.ui.core.Size.of(240, height)); root.mount(null, 11, 23)
        // Exercise the actual unmounted constructor, not a replacement layout shell.
        val popup = me.mycellium.skymyce.ui.MenuDropdown(500) {}
        check(popup.parent() == null)
        val viewport = popup.children().single() as io.wispforest.owo.ui.container.ScrollContainer<*>
        val entries = viewport.children().single() as io.wispforest.owo.ui.container.FlowLayout
        // Text labels require the real Minecraft font. Native box rows exercise the same
        // actual entries/scroll/mount lifecycle without manufacturing a Minecraft singleton.
        repeat(count) { entries.child(io.wispforest.owo.ui.component.UIComponents.box(Sizing.fill(), Sizing.fixed(rowHeight))) }
        root.duringMouseDispatch {
            me.mycellium.skymyce.ui.mountMenuDropdown(root, popup, root.x() + root.width() - 2, root.y() + root.height() - 2)
            check(popup.parent() === root && popup.width() in 1..232 && popup.height() in 1..height - 8)
            check(popup.x() >= root.x() + 4 && popup.y() >= root.y() + 4)
            check(popup.x() + popup.width() <= root.x() + root.width() - 4 && popup.y() + popup.height() <= root.y() + root.height() - 4)
        }
        check(entries.children().size == count)
        if (count * rowHeight + 10 > height - 8) {
            check(entries.height() > viewport.height())
            check(viewport.onMouseScroll(10.0, 10.0, -5.0)) { "The actual dropdown could not scroll its retained choices" }
            check(entries.children().last().parent() === entries)
        }
        root.focusHandler()?.focus(null, io.wispforest.owo.ui.core.UIComponent.FocusSource.KEYBOARD_CYCLE)
        root.removeChild(popup)
    }
}

private fun checkMenuSliderKeys() {
    check(io.wispforest.owo.ui.inject.GreedyInputUIComponent::class.java.isAssignableFrom(me.mycellium.skymyce.ui.MenuSlider::class.java))
    var value = .5
    var changes = 0
    fun key(code: Int, modifiers: Int = 0, active: Boolean = true, focused: Boolean = true) =
        me.mycellium.skymyce.ui.adjustMenuSliderKey(net.minecraft.client.input.KeyEvent(code, 0, modifiers), active, focused, value, .05) {
            value = it; changes++
        }
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) && kotlin.math.abs(value - .55) < 1e-9 && changes == 1)
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) && kotlin.math.abs(value - .5) < 1e-9 && changes == 2)
    check(!key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, active = false))
    check(!key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, focused = false))
    check(!key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_MOD_ALT))
    check(!key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL))
    check(!key(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) && value == .5 && changes == 2)
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) && changes == 3)
    value = .99
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) && value == 1.0 && changes == 4)
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) && changes == 4) { "An endpoint keyboard press emitted another value change" }
    value = .01
    check(key(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) && value == 0.0 && changes == 5)
}

internal fun checkMenuDropdownInput() {
    val text = net.minecraft.network.chat.Component.literal("Clear (show all)").withStyle(net.minecraft.ChatFormatting.BOLD)
    val popupWidth = me.mycellium.skymyce.ui.menuDropdownWidth(text, false)
    val rowHeight = me.mycellium.skymyce.ui.menuDropdownRowHeight()
    val line = menuTextLines(text, Int.MAX_VALUE).single()
    check(popupWidth >= kotlin.math.ceil(menuTextWidth(line)).toInt() + 16)
    check(rowHeight >= MenuCanvas.lineHeight() + 8)
    checkNativeMenuDropdown(rowHeight)
    checkMenuSliderKeys()
    for (height in listOf(140, 540)) for (count in listOf(13, 45)) {
        val root = object : io.wispforest.owo.ui.container.FlowLayout(
            Sizing.fixed(240), Sizing.fixed(height), io.wispforest.owo.ui.container.FlowLayout.Algorithm.VERTICAL) {
            fun duringMouseDispatch(action: () -> Unit) = runAndDeferEvents { action() }
        }
        root.inflate(io.wispforest.owo.ui.core.Size.of(240, height)); root.mount(null, 11, 23)
        val entries = io.wispforest.owo.ui.container.UIContainers.verticalFlow(Sizing.fixed(500), Sizing.content()).apply {
            padding(io.wispforest.owo.ui.core.Insets.of(5))
        }
        repeat(count) { entries.child(io.wispforest.owo.ui.component.UIComponents.box(Sizing.fill(), Sizing.fixed(rowHeight))) }
        // Native ScrollContainer initializes Fabric. Exercise its actual shared measurement
        // and deferred mount with a retained layout shell in this headless process.
        val popupSize = me.mycellium.skymyce.ui.menuDropdownContentSize(entries, 500,
            io.wispforest.owo.ui.core.Size.of(root.width() - 8, root.height() - 8))
        val viewport = io.wispforest.owo.ui.container.UIContainers.verticalFlow(
            Sizing.fixed(popupSize.width()), Sizing.fixed(popupSize.height())).child(entries)
        val popup = io.wispforest.owo.ui.container.UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).child(viewport)
        root.duringMouseDispatch {
            me.mycellium.skymyce.ui.mountMenuDropdown(root, popup, root.x() + root.width() - 2, root.y() + root.height() - 2)
            check(popup.width() > 0 && popup.height() > 0 && popup.parent() === root) {
                "Deferred mouse-event layout left an opened dropdown unmeasured or unmounted"
            }
            check(popup.x() >= root.x() + 4 && popup.y() >= root.y() + 4)
            check(popup.x() + popup.width() <= root.x() + root.width() - 4 &&
                popup.y() + popup.height() <= root.y() + root.height() - 4) {
                "A $count-option dropdown overflowed a $height-pixel viewport before deferred layout finished"
            }
        }
        check(entries.children().size == count && viewport.height() <= root.height() - 8)
        if (count * rowHeight + 10 > height - 8) {
            check(entries.height() > viewport.height())
            check(entries.children().last().parent() === entries) { "Bounding a dropdown discarded its last option" }
        }
        root.removeChild(popup)
    }
    for (scale in 1..4) {
        val viewport = MenuViewport.fit(1920, 1080, 1920 / scale, 1080 / scale)
        val root = io.wispforest.owo.ui.container.UIContainers.verticalFlow(
            io.wispforest.owo.ui.core.Sizing.fixed(viewport.width), io.wispforest.owo.ui.core.Sizing.fixed(viewport.height))
        root.inflate(io.wispforest.owo.ui.core.Size.of(viewport.width, viewport.height)); root.mount(null, 0, 0)
        // DropdownComponent initializes Fabric; use its native parent lifecycle and the shared focus policy.
        var closing = false
        val popup = object : io.wispforest.owo.ui.container.FlowLayout(
            io.wispforest.owo.ui.core.Sizing.fixed(maxOf(116, popupWidth)), io.wispforest.owo.ui.core.Sizing.content(),
            io.wispforest.owo.ui.container.FlowLayout.Algorithm.VERTICAL) {
            private val exitFocus = io.wispforest.owo.ui.util.FocusHandler(this)
            override fun focusHandler() = me.mycellium.skymyce.ui.menuDropdownFocus(super.focusHandler(), closing, exitFocus)
        }
        val entries = io.wispforest.owo.ui.container.UIContainers.verticalFlow(
            io.wispforest.owo.ui.core.Sizing.fill(), io.wispforest.owo.ui.core.Sizing.content()).apply {
            padding(io.wispforest.owo.ui.core.Insets.of(5))
        }
        popup.child(entries)
        var selected = 0
        val row = io.wispforest.owo.ui.component.UIComponents.box(
            io.wispforest.owo.ui.core.Sizing.fill(), io.wispforest.owo.ui.core.Sizing.fixed(rowHeight))
        row.mouseDown().subscribe { _, _ -> selected++; true }
        entries.child(row)
        me.mycellium.skymyce.ui.mountMenuDropdown(root, popup, viewport.width - 2, viewport.height - 2)
        check(popup.width() >= 116 && popup.x() >= 0 && popup.y() >= 0)
        check(popup.x() + popup.width() <= viewport.width && popup.y() + popup.height() <= viewport.height)
        fun click(x: Double, y: Double) = viewport.mouse(MouseButtonEvent(
            x * viewport.screenWidth / viewport.width, y * viewport.screenHeight / viewport.height, MouseButtonInfo(0, 0)))
        val inside = click(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0)
        check(!me.mycellium.skymyce.ui.dismissMenuDropdownOutside(popup, inside))
        check(popup.parent() === root && root.onMouseDown(inside, false) && selected == 1) {
            "Mounted dropdown selection was dismissed before dispatch at Minecraft GUI scale $scale"
        }
        val outside = click(1.0, 1.0)
        closing = true
        check(me.mycellium.skymyce.ui.dismissMenuDropdownOutside(popup, outside))
        check(popup.parent() == null && popup !in root.children() && selected == 1)
        val exitFocus = checkNotNull(popup.focusHandler()) { "Detached closing popup lost the focus handler required by owo drawChildren" }
        check(exitFocus !== root.focusHandler() && exitFocus.focused() == null)
        check(entries.focusHandler() === exitFocus && row.focusHandler() === exitFocus)
        checkNotNull(entries.focusHandler()).lastFocusSource()
        val replacementRoot = io.wispforest.owo.ui.container.UIContainers.verticalFlow(
            io.wispforest.owo.ui.core.Sizing.fixed(viewport.width), io.wispforest.owo.ui.core.Sizing.fixed(viewport.height))
        replacementRoot.inflate(io.wispforest.owo.ui.core.Size.of(viewport.width, viewport.height)); replacementRoot.mount(null, 0, 0)
        me.mycellium.skymyce.ui.mountMenuDropdown(replacementRoot, popup, 10, 10)
        check(popup.focusHandler() === replacementRoot.focusHandler() && row.focusHandler() === replacementRoot.focusHandler()) {
            "Remounted popup retained the detached exit's focus context"
        }
        closing = false
        replacementRoot.removeChild(popup)
        check(popup.focusHandler() == null) { "Detached open popup unexpectedly acquired an exit focus context" }
    }
    val white = 0xFFFFFFFF.toInt()
    fun paint(clipped: Boolean): java.awt.image.BufferedImage = MenuCanvas().run {
        begin()
        if (clipped) clip = MenuCanvas.Clip(0, 0, popupWidth, rowHeight)
        text(listOf(MenuCanvas.Run(text.string, white, bold = true)), 8.0, (rowHeight - MenuCanvas.lineHeight()) / 2.0)
        finish(popupWidth, rowHeight + 10, 2.0)
    }
    val clipped = paint(true); val whole = paint(false)
    check(clipped.getRGB(0, 0, clipped.width, clipped.height, null, 0, clipped.width)
        .contentEquals(whole.getRGB(0, 0, whole.width, whole.height, null, 0, whole.width))) {
        "Dropdown row clips custom font glyphs"
    }
}

private fun checkMenuViewport() {
    val info = MouseButtonInfo(2, 1 or 4) // Middle button, Shift and Alt.
    for (nativeScale in 1..4) {
        val viewport = MenuViewport.fit(1920, 1080, 1920 / nativeScale, 1080 / nativeScale)
        check(viewport.width == 960 && viewport.height == 540) { "Minecraft GUI scale changed custom menu density" }
        check(viewport.x(viewport.screenWidth / 2.0) == 480.0 && viewport.y(viewport.screenHeight / 2.0) == 270.0)
        val event = viewport.mouse(MouseButtonEvent(viewport.screenWidth / 2.0, viewport.screenHeight / 2.0, info))
        check(event.x() == 480.0 && event.y() == 270.0 && event.buttonInfo() === info)
        check(event.button() == 2 && event.modifiers() == 5 && event.hasShiftDown() && event.hasAltDown()) { "Mapping custom pointer coordinates lost modifiers" }
        check(viewport.x(-viewport.screenWidth / 10.0) == -96.0 && viewport.y(viewport.screenHeight / 10.0) == 54.0) { "Pointer drag deltas changed direction or scale" }
    }
    for ((width, height) in listOf(0 to 0, 1 to 1, 80 to 4096, 8192 to 80, Int.MAX_VALUE to Int.MAX_VALUE)) {
        val viewport = MenuViewport.fit(width, height, width / 4, height / 4)
        check(viewport.width > 0 && viewport.height > 0 && viewport.x(1.0).isFinite() && viewport.y(1.0).isFinite()) { "Extreme window dimensions broke menu coordinate mapping" }
    }
}

private fun checkMenuCanvas() {
    val canvas = MenuCanvas()
    val white = -1
    val red = 0xFFFF3344.toInt()
    canvas.begin()
    listOf(20.0 to 20.0, 60.0 to 20.0, 20.0 to 60.0, 60.0 to 60.0).forEachIndexed { index, (x, y) ->
        canvas.arc(x, y, 8.0, index * 90.0, (index + 1) * 90.0, red)
    }
    canvas.arc(100.0, 20.0, 8.0, 0.0, 360.0, white, 5.0)
    canvas.fill(90.0, 50.0, 20.0, 10.0, 0x804080C0.toInt())
    var image = canvas.finish(120, 80, 2.0)
    fun pixel(x: Int, y: Int) = image.getRGB(x * 2, y * 2)
    listOf(16 to 16, 64 to 16, 24 to 64, 56 to 64).forEach { (x, y) -> check(pixel(x, y) == red) { "Arc painted the wrong quadrant" } }
    listOf(24 to 24, 56 to 24, 16 to 56, 64 to 56).forEach { (x, y) -> check(pixel(x, y) == 0) { "Arc escaped its quadrant" } }
    check(pixel(100, 20) == 0 && pixel(106, 20) == white) { "Ring lost its hollow center or border" }
    check(pixel(95, 55) == 0x804080BF.toInt() || pixel(95, 55) == 0x804080C0.toInt()) { "Canvas changed straight alpha or RGB channels" }
    check((0 until image.height).any { y -> (0 until image.width).any { x -> (image.getRGB(x, y) ushr 24) in 1..254 && x < 176 && y < 90 } }) { "Curved edges are not antialiased" }

    canvas.begin()
    canvas.clip = MenuCanvas.Clip(25, 8, 5, 10)
    canvas.transform = MenuCanvas.Transform(a = 2.0, d = 1.0, x = 20.0, y = 5.0)
    canvas.fill(0.0, 0.0, 10.0, 20.0, red)
    image = canvas.finish(50, 30, 2.0)
    check(pixel(27, 10) == red && pixel(24, 10) == 0 && pixel(30, 10) == 0 && pixel(27, 7) == 0) { "Canvas clip must remain in screen coordinates through transforms" }

    fun textFrame(text: String) {
        canvas.begin()
        canvas.text(listOf(MenuCanvas.Run(text, white)), 4.25, 3.5)
    }
    textFrame("Smooth Wi glyphs")
    image = canvas.finish(140, 30, 2.0)
    val firstRevision = canvas.revision
    val firstPixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    check(firstPixels.any { (it ushr 24) in 1..254 }) { "Custom font lacks smooth fractional glyph coverage" }
    check(MenuCanvas.textWidth("WWW") > MenuCanvas.textWidth("iii") * 2) { "Custom text must use proportional font metrics" }
    textFrame("Smooth Wi glyphs")
    check(canvas.finish(140, 30, 2.0) === image && canvas.revision == firstRevision) { "Unchanged frame rerasterized" }
    textFrame("Changed Wi glyphs")
    canvas.finish(140, 30, 2.0)
    check(canvas.revision == firstRevision + 1 && !firstPixels.contentEquals(image.getRGB(0, 0, image.width, image.height, null, 0, image.width))) { "Changed text failed to invalidate the canvas" }
    textFrame("Changed Wi glyphs")
    image = canvas.finish(160, 40, 2.0)
    check(image.width == 320 && image.height == 80 && canvas.revision == firstRevision + 2) { "Resize failed to invalidate the canvas" }

    val measure: (String) -> Double = { text -> text.codePoints().toArray().sumOf { if (it == 'W'.code) 12.0 else if (it > 0xFFFF) 10.0 else 3.0 } }
    check(menuCaretIndex("WiW", -1.0, measure) == 0)
    check(menuCaretIndex("WiW", 5.9, measure) == 0 && menuCaretIndex("WiW", 6.1, measure) == 1)
    check(menuCaretIndex("WiW", 13.4, measure) == 1 && menuCaretIndex("WiW", 13.6, measure) == 2)
    check(menuCaretIndex("WiW", 999.0, measure) == 3)
    val astral = "i\uD83D\uDE00W"
    check(menuCaretIndex(astral, 7.9, measure) == 1 && menuCaretIndex(astral, 8.1, measure) == 3) { "Pointer caret split a surrogate pair" }
    val long = "Wi".repeat(40)
    val originAfterScroll = 12.0 - measure(long.substring(0, 60))
    check(menuCaretIndex(long, 12.0 - originAfterScroll, measure) == 60) { "Scrolled field caret failed to map screen position to text position" }
    writeMenuCanvasArtifact()
}

/** Visual artifact exercises the production rasterizer, including the reference card/switch shape. */
private fun writeMenuCanvasArtifact() {
    val canvas = MenuCanvas()
    canvas.begin()
    val panel = 0xFF201116.toInt()
    val card = 0xFF29171D.toInt()
    val sidebar = 0xFF10090C.toInt()
    val border = 0xFF4A2B35.toInt()
    val accent = 0xFFDF8196.toInt()
    val white = 0xFFF2E9EC.toInt()
    val muted = 0xFFAF929C.toInt()
    fun rounded(x: Double, y: Double, width: Double, height: Double, radius: Double, color: Int) {
        canvas.fill(x + radius, y, width - radius * 2, height, color)
        canvas.fill(x, y + radius, width, height - radius * 2, color)
        canvas.arc(x + radius, y + radius, radius, 0.0, 90.0, color)
        canvas.arc(x + width - radius, y + radius, radius, 90.0, 180.0, color)
        canvas.arc(x + width - radius, y + height - radius, radius, 180.0, 270.0, color)
        canvas.arc(x + radius, y + height - radius, radius, 270.0, 360.0, color)
    }
    fun label(text: String, x: Double, y: Double, size: Double = 16.0, color: Int = white) =
        canvas.text(listOf(MenuCanvas.Run(text, color)), x, y, size)
    fun switch(x: Double, y: Double, enabled: Boolean) {
        rounded(x, y, 42.0, 24.0, 12.0, if (enabled) accent else border)
        canvas.arc(x + if (enabled) 30.0 else 12.0, y + 12.0, 8.0, 0.0, 360.0, if (enabled) white else muted)
    }
    rounded(0.0, 0.0, 1100.0, 650.0, 10.0, border)
    rounded(1.0, 1.0, 1098.0, 648.0, 9.0, panel)
    canvas.fill(2.0, 2.0, 198.0, 646.0, sidebar)
    label("SKY-MYCE", 28.0, 30.0, 20.0)
    canvas.fill(18.0, 75.0, 162.0, 1.0, border)
    listOf("Daily Digest", "Party Finder", "Friends & loans", "Dungeons", "Auctions", "Cosmetics", "HUD editor").forEachIndexed { index, text ->
        if (index == 1) rounded(14.0, 92.0 + index * 43, 172.0, 37.0, 7.0, card)
        label(text, 28.0, 100.0 + index * 43, 15.0, if (index == 1) white else muted)
    }
    canvas.fill(18.0, 590.0, 162.0, 1.0, border)
    label("Settings", 28.0, 609.0, 15.0, muted)
    label("CATEGORY", 230.0, 28.0, 11.0, muted)
    label("Party Finder", 230.0, 49.0, 26.0)
    rounded(585.0, 35.0, 212.0, 34.0, 6.0, border)
    rounded(586.0, 36.0, 210.0, 32.0, 5.0, card)
    label("Search modules", 600.0, 43.0, 13.0, muted)
    val titles = listOf("Dungeon Friends", "Class matching", "Party Commands", "Missing roles", "Friends & loans", "Recent parties", "Level requirements", "Accepted invites", "Sort by speed", "Remember filters", "Compact view", "Notifications")
    titles.forEachIndexed { index, title ->
        val x = 230.0 + index % 2 * 291
        val y = 99.0 + index / 2 * 79
        rounded(x, y, 277.0, 66.0, 7.0, if (index == 0) accent else border)
        rounded(x + 1, y + 1, 275.0, 64.0, 6.0, card)
        label(title, x + 15, y + 23, 15.0)
        switch(x + 220, y + 21, index % 3 != 1)
    }
    canvas.fill(826.0, 2.0, 1.0, 646.0, border)
    label("FEATURE SETTINGS", 850.0, 28.0, 11.0, muted)
    label("Dungeon Friends", 850.0, 49.0, 21.0)
    label("Find the right party with", 850.0, 94.0, 13.0, muted)
    label("friends and saved filters.", 850.0, 113.0, 13.0, muted)
    listOf("Show friends first", "Require class match", "Hide offline friends", "Save party filters").forEachIndexed { index, title ->
        val y = 180.0 + index * 69
        label(title, 850.0, y, 14.0)
        switch(1033.0, y - 3, index != 2)
        canvas.fill(850.0, y + 43, 225.0, 1.0, border)
    }
    label("Minimum level", 850.0, 470.0, 14.0)
    label("30", 1056.0, 470.0, 14.0, muted)
    rounded(850.0, 510.0, 225.0, 4.0, 2.0, border)
    rounded(850.0, 510.0, 112.0, 4.0, 2.0, accent)
    canvas.arc(962.0, 512.0, 5.0, 0.0, 360.0, white)
    val output = File("custom-menu-renderer.png")
    check(ImageIO.write(canvas.finish(1100, 650, 1.0), "png", output))
    println("Custom canvas checks passed; visual artifact: ${output.absolutePath}")
}
