package me.mycellium.skymyce.features.browser

fun main() {
    listOf("https://www.youtube.com/watch?v=ovmsEmAhoIQ", "https://m.youtube.com/watch?v=ovmsEmAhoIQ&t=15",
        "https://youtu.be/ovmsEmAhoIQ?si=test", "https://www.youtube.com/shorts/ovmsEmAhoIQ",
        "https://www.youtube.com/live/ovmsEmAhoIQ", "https://www.youtube.com/embed/ovmsEmAhoIQ",
        "https://hypixel.net/threads/example.123/#post", "https://www.hypixel.net/", "HTTPS://HYPIXEL.NET/news/1",
        "https://x.com/TheInternetFish/status/2113364266501931412", "https://github.com/").forEach {
        check(BrowserLinks.supported(it) != null) { "Supported website was rejected: $it" }
    }
    listOf(null, "", "file:///C:/private", "javascript:alert(1)", "data:text/html,hello", "http://hypixel.net/",
        "https://hypixel.net@evil.example/", "https://hypixel.net:8443/", "https://hypixel.net/\nprivate",
        "https://localhost/", "https://127.0.0.1/", "https://[::1]/", "https://2130706433/", "https://host.local/",
        "https://hypixel.net/" + "x".repeat(2048)).forEach {
        check(BrowserLinks.supported(it) == null) { "Unexpected browser destination: $it" }
    }
    check(BrowserLinks.navigationAllowed("about:blank"))
    check(BrowserLinks.navigationAllowed("https://consent.youtube.com/"))
    check(BrowserLinks.navigationAllowed("https://x.com/"))
    listOf("file:///private", "mod://skymyce/secret", "https://192.168.1.1/", "chrome://settings/", "about:config")
        .forEach { check(!BrowserLinks.navigationAllowed(it)) }
    check(BrowserLinks.resourceAllowed("https://r1.googlevideo.com/videoplayback?signature=" + "x".repeat(3000)))
    check(BrowserLinks.resourceAllowed("https://i.ytimg.com/vi/abcdefghijk/default.jpg"))
    check(BrowserLinks.resourceAllowed("blob:https://www.youtube.com/fixture"))
    check(BrowserLinks.resourceAllowed("chrome-extension://extension/js/background.js"))
    listOf("file:///private", "http://localhost/", "https://127.0.0.1/", "https://[::1]/", "https://2130706433/",
        "https://host.local/", "ftp://hypixel.net/file", "blob:file:///private").forEach { check(!BrowserLinks.resourceAllowed(it)) }

    for ((size, scale) in listOf(320 to 240 to 2.0, 1280 to 800 to 1.0, 1920 to 1080 to 4.0)) {
        val view = browserViewport(size.first, size.second, scale)
        check(view.width > 0 && view.height > 0 && view.pixelsWide in 1..1920 && view.pixelsHigh in 1..1080)
        check(view.contains(view.x.toDouble(), view.y.toDouble()))
        check(!view.contains((view.x + view.width).toDouble(), view.y.toDouble()))
        check(view.mouseX(view.x.toDouble()) == 0 && view.mouseY(view.y.toDouble()) == 0)
        check(view.mouseX((view.x + view.width).toDouble()) == view.pixelsWide)
        check(view.mouseY((view.y + view.height).toDouble()) == view.pixelsHigh)
    }
    check(runCatching { browserViewport(640, 480, Double.POSITIVE_INFINITY) }.isFailure)
    println("Browser link and viewport checks passed")
}
