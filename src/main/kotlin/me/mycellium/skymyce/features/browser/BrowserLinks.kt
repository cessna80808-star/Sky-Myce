package me.mycellium.skymyce.features.browser

import java.net.URI
import java.util.Locale

/** Any public HTTPS website may open; navigation never admits local files, custom protocols, IP literals or local hosts. */
object BrowserLinks {
    private val numericHost = Regex("[0-9.]+")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val videoPath = Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    fun supported(value: String?): URI? = https(value)?.takeIf { publicHost(it.host) }

    /** Hypixel pages and YouTube videos only: the sources Daily Digest news may link to. */
    fun trusted(value: String?): URI? {
        val uri = https(value) ?: return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (host == "hypixel.net" || host.endsWith(".hypixel.net")) return uri
        if (host !in youtubeHosts) return null
        val path = uri.path.orEmpty()
        val video = if (host == "youtu.be") path.removePrefix("/").takeIf(videoId::matches)
        else if (path == "/watch") uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
            .singleOrNull()?.substringAfter('=')?.takeIf(videoId::matches)
        else videoPath.matchEntire(path)?.groupValues?.get(1)
        return uri.takeIf { video != null && uri.rawQuery.orEmpty().split('&').none { part ->
            part.substringBefore('=').lowercase(Locale.ROOT) in setOf("url", "redirect", "redirect_url", "q", "u")
        } }
    }

    fun navigationAllowed(value: String?): Boolean = value == "about:blank" || supported(value) != null

    private fun publicHost(value: String): Boolean {
        val host = value.lowercase(Locale.ROOT)
        return '.' in host && ':' !in host && !host.matches(numericHost) &&
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")
    }

    private fun https(value: String?, maximumLength: Int = 2048): URI? = value?.takeIf { it.length in 1..maximumLength && it.none(Char::isISOControl) }?.let {
        runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
        }
    }

    fun resourceAllowed(value: String?): Boolean {
        if (value == "about:blank" || value?.startsWith("data:") == true || value?.startsWith("chrome-extension://") == true) return true
        if (value?.startsWith("blob:") == true) return navigationAllowed(value.removePrefix("blob:"))
        return https(value, 8192)?.let { publicHost(it.host) } == true
    }
}
