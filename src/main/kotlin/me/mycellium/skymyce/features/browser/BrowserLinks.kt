package me.mycellium.skymyce.features.browser

import java.net.URI
import java.util.Locale

/** Entry points are videos or Hypixel pages; browser navigation never admits local files or custom protocols. */
object BrowserLinks {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    fun supported(value: String?): URI? {
        val uri = https(value) ?: return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (hypixel(host)) return uri
        if (host !in youtubeHosts) return null
        val path = uri.path.orEmpty()
        val video = if (host == "youtu.be") path.removePrefix("/").takeIf(videoId::matches)
        else if (path == "/watch") uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
            .singleOrNull()?.substringAfter('=')?.takeIf(videoId::matches)
        else Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})").matchEntire(path)?.groupValues?.get(1)
        return uri.takeIf { video != null && uri.rawQuery.orEmpty().split('&').none { part ->
            part.substringBefore('=').lowercase(Locale.ROOT) in setOf("url", "redirect", "redirect_url", "q", "u")
        } }
    }

    fun navigationAllowed(value: String?): Boolean {
        if (value == "about:blank") return true
        val uri = https(value) ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        return hypixel(host) || host in youtubeHosts || host == "consent.youtube.com"
    }

    private fun hypixel(host: String) = host == "hypixel.net" || host.endsWith(".hypixel.net")

    private fun https(value: String?, maximumLength: Int = 2048): URI? = value?.takeIf { it.length in 1..maximumLength && it.none(Char::isISOControl) }?.let {
        runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in listOf(-1, 443)
        }
    }

    fun resourceAllowed(value: String?): Boolean {
        if (value == "about:blank" || value?.startsWith("data:") == true || value?.startsWith("chrome-extension://") == true) return true
        if (value?.startsWith("blob:") == true) return navigationAllowed(value.removePrefix("blob:"))
        val uri = https(value, 8192) ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        return '.' in host && ':' !in host && !host.matches(Regex("[0-9.]+")) &&
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")
    }
}
