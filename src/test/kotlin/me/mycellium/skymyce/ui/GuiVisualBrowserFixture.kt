package me.mycellium.skymyce.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import me.mycellium.skymyce.features.browser.BrowserRuntime
import me.mycellium.skymyce.features.browser.InGameBrowserScreen
import me.mycellium.skymyce.features.browser.browserViewport
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.sounds.SoundSource
import org.cef.browser.CefDevToolsClient
import java.util.concurrent.CompletableFuture

/** The real Chromium paints a self-contained page; no website, game server or community session is opened. */
internal class GuiVisualBrowserFixture {
    private val parent: Screen = MenuHomeScreen()
    val screen = InGameBrowserScreen(parent, "about:blank")
    private val deadline = System.nanoTime() + 180_000_000_000L
    private var tools: CefDevToolsClient? = null
    private var pending: CompletableFuture<JsonElement>? = null
    private var phase = 0
    private var scale: Int? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var paintX = 0.0
    private var paintY = 0.0
    private var settled = 0
    private val text = "Sky-Myce native input"

    fun advance(client: Minecraft): Boolean {
        check(client.level == null && client.connection == null && client.player == null)
        check(client.options.getSoundSourceOptionInstance(SoundSource.MASTER).get() == 0.0)
        val runtimeStatus = BrowserRuntime.status()
        check(!runtimeStatus.startsWith("Browser unavailable") && !runtimeStatus.startsWith("Chromium could not start") &&
            !runtimeStatus.startsWith("Browser runtime was started")) { "Native browser setup failed: $runtimeStatus" }
        check(System.nanoTime() < deadline) { "Native browser check timed out at phase $phase: $runtimeStatus" }
        check(client.screen === screen)
        val session = screen.session?.takeIf { it.ready } ?: return false
        if (!session.browser.isTextureReady) return false
        if (tools == null) tools = session.browser.devToolsClient
        when (phase) {
            0 -> {
                val html = """<!doctype html><meta charset="utf-8"><style>
                    *{box-sizing:border-box}body{margin:0;padding:32px;background:#103c52;color:#edf8f8;font:22px Arial;min-height:2400px}
                    h1{margin:0 0 16px;font-size:36px}p{max-width:680px;line-height:1.5}
                    input,button{display:block;margin:20px 0;padding:12px 16px;border:2px solid #38d8ab;border-radius:8px;font:22px Arial}
                    input{width:420px;max-width:75vw;background:#092431;color:white}button{background:#38d8ab;color:#092431;cursor:pointer}
                    #paint{position:fixed;right:24px;top:24px;width:64px;height:64px;background:#38d8ab}
                    </style><div id="paint"></div><h1>Native Chromium</h1><p>Offline browser check. These pixels come from the live web page.</p>
                    <input id="edit" aria-label="Input test" placeholder="Type here"><button id="click" onclick="document.body.dataset.clicks='1';document.getElementById('result').textContent='Input received: '+document.getElementById('edit').value">Click test</button>
                    <p id="result">Waiting for input</p><p>Mouse, keyboard, scrolling and GUI scale are checked before this capture.</p>""".trimIndent()
                val quoted = com.google.gson.Gson().toJson(html)
                val result = evaluate("document.open();document.write($quoted);document.close();document.fonts.ready.then(()=>true)") ?: return false
                check(result.asBoolean); phase++
            }
            1 -> {
                val result = evaluate("(()=>{const r=document.getElementById('edit').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()") ?: return false
                click(result.asJsonObject); phase++
            }
            2 -> {
                if (evaluate("document.activeElement?.id==='edit'")?.asBoolean != true) return false
                text.codePoints().forEach { check(screen.charTyped(CharacterEvent(it))) }
                phase++
            }
            3 -> {
                val result = evaluate("document.getElementById('edit').value") ?: return false
                check(text.startsWith(result.asString)) { "Native keyboard input was changed: $result" }
                if (result.asString == text) phase++
            }
            4 -> {
                val result = evaluate("(()=>{const r=document.getElementById('click').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()") ?: return false
                click(result.asJsonObject); phase++
            }
            5 -> {
                if (evaluate("document.body.dataset.clicks==='1'")?.asBoolean != true) return false
                check(screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0.0, -8.0))
                phase++
            }
            6 -> {
                val result = evaluate("({scrolled:scrollY>0,width:innerWidth,height:innerHeight})")?.asJsonObject ?: return false
                if (!result.get("scrolled").asBoolean) return false
                previousWidth = result.get("width").asInt; previousHeight = result.get("height").asInt
                scale = client.options.guiScale().get()
                client.options.guiScale().set(if (scale == 1) 2 else 1)
                client.resizeGui(); phase++
            }
            7 -> {
                val result = evaluate("({width:innerWidth,height:innerHeight})")?.asJsonObject ?: return false
                val viewport = browserViewport(screen.width, screen.height, client.window.guiScale.toDouble())
                if (result.get("width").asInt != viewport.pixelsWide || result.get("height").asInt != viewport.pixelsHigh) return false
                check(viewport.pixelsWide != previousWidth || viewport.pixelsHigh != previousHeight) { "Resizing did not reach Chromium" }
                phase++
            }
            8 -> {
                val result = evaluate("window.scrollTo(0,0);new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>{const r=document.getElementById('paint').getBoundingClientRect();resolve({x:r.x+r.width/2,y:r.y+r.height/2,text:document.getElementById('edit').value,clicked:document.body.dataset.clicks})})))")?.asJsonObject ?: return false
                check(result.get("text").asString == text && result.get("clicked").asString == "1")
                paintX = result.get("x").asDouble; paintY = result.get("y").asDouble
                phase++
            }
            else -> return ++settled >= 3
        }
        return false
    }

    private fun evaluate(expression: String): JsonElement? {
        if (pending == null) {
            val parameters = JsonObject().apply {
                addProperty("expression", expression); addProperty("returnByValue", true); addProperty("awaitPromise", true)
            }
            pending = checkNotNull(tools).executeDevToolsMethod("Runtime.evaluate", parameters.toString()).thenApply { response ->
                val result = JsonParser.parseString(response).asJsonObject
                check(!result.has("exceptionDetails")) { "Native browser script failed: $result" }
                checkNotNull(result.getAsJsonObject("result").get("value")) { "Native browser returned no value: $result" }
            }
            return null
        }
        if (pending?.isDone != true) return null
        return pending!!.join().also { pending = null }
    }

    private fun click(point: JsonObject) {
        val client = Minecraft.getInstance()
        val viewport = browserViewport(screen.width, screen.height, client.window.guiScale.toDouble())
        val event = MouseButtonEvent(viewport.x + point.get("x").asDouble * viewport.width / viewport.pixelsWide,
            viewport.y + point.get("y").asDouble * viewport.height / viewport.pixelsHigh, MouseButtonInfo(0, 0))
        check(screen.mouseClicked(event, false)); check(screen.mouseReleased(event))
    }

    fun checkPaint(image: NativeImage, client: Minecraft) {
        val viewport = browserViewport(screen.width, screen.height, client.window.guiScale.toDouble())
        val x = ((viewport.x + paintX * viewport.width / viewport.pixelsWide) * image.width / screen.width).toInt()
        val y = ((viewport.y + paintY * viewport.height / viewport.pixelsHigh) * image.height / screen.height).toInt()
        val rgb = image.getPixel(x, y)
        check((rgb shr 16 and 255) < 100 && (rgb shr 8 and 255) > 180 && (rgb and 255) > 120) {
            "Chromium DOM did not paint into the actual game screenshot at $x,$y: ${Integer.toHexString(rgb)}"
        }
        check(client.connection == null)
    }

    fun close(client: Minecraft) {
        pending?.cancel(false); pending = null
        tools?.close(); tools = null
        screen.onClose()
        check(client.screen === parent && screen.session == null) { "Closing the browser retained its page or lost its return screen" }
        scale?.let { client.options.guiScale().set(it); client.resizeGui() }; scale = null
        println("Native browser GUI checks passed: muted offline Chromium pixels, DOM, mouse, text, wheel, resize and close")
    }
}
