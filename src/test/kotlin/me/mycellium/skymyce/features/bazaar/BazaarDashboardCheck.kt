package me.mycellium.skymyce.features.bazaar

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val hud = me.mycellium.skymyce.hud.widget.WidgetConfig(-42, 150, 2.5f, me.mycellium.skymyce.hud.widget.Anchor.BOTTOM_RIGHT, visible = false)
    check(bazaarHudPreferences(hud) == mapOf("hudX" to "-42", "hudY" to "150", "hudScalePercent" to "250", "hudAnchor" to "BOTTOM_RIGHT", "hudVisible" to "false"))
    check(bazaarHudPreferences(hud.copy(x = -40000, scale = .1f))["hudX"] == "-40000") {
        "Preference projection must not silently move a custom HUD layout into the synchronization range"
    }
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host")
    val calls = AtomicInteger()
    val delayed = CompletableFuture<BazaarUiResult>()
    val bridge = object : BazaarUiBridge {
        override fun snapshot() = BazaarUiState(status = "Private test record", wizardDone = true)
        override fun action(name: String, fields: Map<String, String>): CompletableFuture<BazaarUiResult> {
            calls.incrementAndGet()
            if (fields["value"] == "DELAYED") return delayed
            return CompletableFuture.completedFuture(BazaarUiResult("Recorded local test action"))
        }
    }
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    BazaarDashboardServer(bridge).use { server ->
        val uri = server.launch(); val base = server.origin
        check(uri.host == "127.0.0.1" && uri.port > 0 && uri.rawQuery == null)
        val secret = uri.fragment.substringBefore('&')
        var cookie = ""; var csrf = ""
        fun request(path: String, body: String? = null, origin: String? = base, authenticated: Boolean = true,
                    type: String = "application/json", host: String? = null, method: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(5))
            origin?.let { builder.header("Origin", it) }; host?.let { builder.header("Host", it) }
            if (authenticated && cookie.isNotEmpty()) builder.header("Cookie", cookie)
            if (authenticated && csrf.isNotEmpty()) builder.header("X-SkyMyce-CSRF", csrf)
            if (body != null) builder.header("Content-Type", type)
            builder.method(method ?: if (body == null) "GET" else "POST", body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        val index = request("/", authenticated = false)
        check(index.statusCode() == 200 && !index.body().contains(secret))
        check(index.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"))
        check(index.headers().firstValue("Cache-Control").orElse("") == "no-store")
        check(index.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        check(request("/api/state", authenticated = false).statusCode() == 401)
        check(request("/", host = "attacker.example:${uri.port}").statusCode() == 403)
        check(request("/", origin = "https://attacker.example").statusCode() == 403)
        check(request("/api/bootstrap", "{\"secret\":\"wrong\"}", authenticated = false).statusCode() == 403)
        check(request("/api/bootstrap", "{\"secret\":\"$secret\"}", origin = null, authenticated = false).statusCode() == 403)
        val accepted = request("/api/bootstrap", "{\"secret\":\"$secret\"}", authenticated = false)
        check(accepted.statusCode() == 200)
        val setCookie = accepted.headers().firstValue("Set-Cookie").orElseThrow()
        check(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"))
        cookie = setCookie.substringBefore(';'); csrf = JsonParser.parseString(accepted.body()).asJsonObject["csrf"].asString
        check(request("/api/bootstrap", "{\"secret\":\"$secret\"}", authenticated = false).statusCode() == 403)
        check(request("/api/state").statusCode() == 200)
        check(request("/api/state", origin = "http://127.0.0.1:1").statusCode() == 403)
        check(request("/api/state?callback=evil").statusCode() == 403)
        val saved = csrf; csrf = "wrong"
        check(request("/api/state").statusCode() == 403); csrf = saved
        BazaarDashboardServer(bridge).use { second ->
            val other = second.launch()
            val login = HttpRequest.newBuilder(URI(second.origin + "/api/bootstrap"))
                .header("Origin", second.origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"secret\":\"${other.fragment}\"}")).build()
            val otherCookie = client.send(login, HttpResponse.BodyHandlers.ofString()).headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            check(otherCookie.substringBefore('=') != cookie.substringBefore('='))
            val firstCookie = cookie; cookie += "; $otherCookie"
            check(request("/api/state").statusCode() == 200) { "A second local Minecraft dashboard invalidated the first session" }
            cookie = firstCookie
        }
        val payload = "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"action\":\"preference\",\"fields\":{\"key\":\"risk\",\"value\":\"BALANCED\"}}"
        check(request("/api/action", payload, type = "text/plain").statusCode() == 400)
        check(request("/api/action", payload, origin = "https://attacker.example").statusCode() == 403)
        check(request("/api/action", payload.replace("preference", "place-order")).statusCode() == 400)
        check(request("/api/action", " ".repeat(16_385)).statusCode() == 400)
        check(request("/api/action", payload).statusCode() == 200)
        check(request("/api/action", payload).statusCode() == 200 && calls.get() == 1) { "Retry repeated a local mutation" }
        check(request("/api/action", payload.replace("BALANCED", "AGGRESSIVE")).statusCode() == 400 && calls.get() == 1)
        check(request("/api/action", payload + "{}").statusCode() == 400)
        val inFlight = payload.replace("000000000001", "000000000002").replace("BALANCED", "DELAYED")
        val first = CompletableFuture.supplyAsync { request("/api/action", inFlight) }
        val deadline = System.nanoTime() + 2_000_000_000
        while (calls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        check(calls.get() == 2)
        val duplicate = CompletableFuture.supplyAsync { request("/api/action", inFlight) }
        Thread.sleep(75)
        check(calls.get() == 2) { "An in-flight retry repeated the mutation" }
        delayed.complete(BazaarUiResult("One accepted mutation"))
        check(first.get().statusCode() == 200 && duplicate.get().statusCode() == 200 && calls.get() == 2)
        val reloaded = request("/api/session", origin = null)
        check(reloaded.statusCode() == 200 && JsonParser.parseString(reloaded.body()).asJsonObject["csrf"].asString == csrf)
        val script = request("/dashboard.js"); val style = request("/dashboard.css")
        check(script.statusCode() == 200 && style.statusCode() == 200)
        check(!script.body().contains("innerHTML") && !script.body().contains("https://cdn"))
        check(request("/api/close", "{}").statusCode() == 200)
        Thread.sleep(250)
        check(!server.running)
    }
    val idle = BazaarDashboardServer(bridge, 100)
    Thread.sleep(250)
    check(!idle.running) { "An abandoned dashboard left its listener running" }
    idle.close()
    println("Bazaar dashboard checks passed: loopback, Host/Origin, single-use capability, Strict cookie/CSRF, bounded JSON, action dedupe, local CSP assets, refresh, logout and idle shutdown")
}
