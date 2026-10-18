package me.mycellium.skymyce.features.digest

import java.net.URI

/** Immutable presentation data. The screen only reads these snapshots; providers own all I/O. */
enum class DigestTone { POSITIVE, WAITING, ATTENTION, INFO, SPECIAL, MUTED }

data class DigestEntry(
    val id: String,
    val title: String,
    val summary: String = "",
    val detail: String = "",
    val tone: DigestTone = DigestTone.MUTED,
    val meta: String = "",
    val timestamp: Long? = null,
    val until: Long? = null,
    val url: String? = null,
    val manualId: String? = null,
    val manualComplete: Boolean = false,
    val community: Boolean = false,
    val activity: String = "",
    val slots: List<DigestEntry> = emptyList(),
    val video: Boolean = false,
    val links: List<String> = emptyList(),
    /** SkyBlock item id for an item icon (RNG drops); empty for none. */
    val item: String = "",
)

data class DigestCardState(
    val id: String,
    val title: String,
    val summary: String,
    val status: String = "",
    val tone: DigestTone = DigestTone.MUTED,
    val entries: List<DigestEntry> = emptyList(),
    val updatedAt: Long? = null,
    val loading: Boolean = false,
)

data class DigestView(
    val context: String = "Waiting for SkyBlock profile",
    val status: String = "",
    val cards: List<DigestCardState> = emptyList(),
    val revision: Long = 0,
)

/** Plain bounded report text only; never invent a public URL or dispatch a chat message here. */
fun digestShareText(entry: DigestEntry): String {
    val suffix = if (entry.community) " [unverified]" else ""
    return "[Sky-Hawk] ${entry.title} • ${entry.summary} (${entry.activity})"
        .replace(Regex("§."), "").filter { it >= ' ' && it != '\u007f' }.take(240 - suffix.length) + suffix
}

/** Logical GUI pixels, so Minecraft's selected GUI scale applies without another transform. */
data class DigestLayout(val width: Int, val height: Int, val padding: Int, val gap: Int, val columns: Int) {
    val contentWidth get() = (width - padding * 2 - 4).coerceAtLeast(1)
    val cardWidth get() = ((contentWidth - gap * (columns - 1)) / columns).coerceAtLeast(1)

    companion object {
        fun fit(width: Int, height: Int, compact: Boolean = false): DigestLayout {
            val margin = if (width < 380 || height < 240) 6 else 12
            val panelWidth = (width - margin * 2).coerceIn(1, 760)
            val dense = compact || height < 300
            return DigestLayout(panelWidth, (height - margin * 2).coerceIn(1, 490),
                if (dense || panelWidth < 400) 8 else 14, if (dense) 7 else 10,
                if (panelWidth >= if (dense) 400 else 560) 2 else 1)
        }
    }
}

fun digestText(value: String, limit: Int = 240): String = value
    .filter { it >= ' ' && it != '§' || it == '\n' }.take(limit)

fun digestTimeRemaining(until: Long, now: Long): String {
    val seconds = ((until - now).coerceAtLeast(0) + 999) / 1000
    return when {
        seconds == 0L -> "Ready now"
        seconds >= 86_400 -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h remaining"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m remaining"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s remaining"
        else -> "${seconds}s remaining"
    }
}

fun digestRelativeTime(timestamp: Long, now: Long): String {
    val seconds = ((now - timestamp).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

fun digestLink(value: String?): URI? = runCatching {
    value?.takeIf { it.length <= 2048 }?.let(::URI)?.takeIf {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && (it.port == -1 || it.port == 443)
    }
}.getOrNull()

fun digestHistory(entries: List<DigestEntry>, community: Boolean, activity: String, search: String): List<DigestEntry> {
    val query = search.trim().take(80)
    return entries.asSequence().filter { it.community == community && (activity.isEmpty() || it.activity == activity) }
        .filter { query.isEmpty() || it.title.contains(query, true) || it.summary.contains(query, true) || it.meta.contains(query, true) }
        .sortedByDescending { it.timestamp ?: 0 }.toList()
}
