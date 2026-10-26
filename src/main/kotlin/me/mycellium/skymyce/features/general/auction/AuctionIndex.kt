package me.mycellium.skymyce.features.general.auction

import me.mycellium.skymyce.api.AuctionAPI

data class AuctionSnapshot(val auctions: List<AuctionAPI.ActiveAuction> = emptyList(), val pages: Int = 0,
    val totalPages: Int = 0, val updatedAt: Long = 0, val complete: Boolean = false,
    val loading: Boolean = false, val stale: Boolean = false, val message: String = "Not loaded", val version: Long = 0) {
    val coverage get() = "${if (complete) "Complete" else "Partial"} • $pages/$totalPages API pages"
}

/** Deterministic snapshot assembly; responses from another refresh or API update are never combined. */
class AuctionIndex {
    @Volatile var view = AuctionSnapshot()
        private set
    private var generation = 0L
    private var sourceTime = 0L
    private var pages = 0
    private var totalPages = 0
    private var itemBytes = 0
    private var skipped = 0
    private val staging = linkedMapOf<String, AuctionAPI.ActiveAuction>()
    @Synchronized fun begin(): Long {
        generation++; sourceTime = 0; pages = 0; totalPages = 0; itemBytes = 0; skipped = 0; staging.clear()
        view = view.copy(loading = true, stale = view.auctions.isNotEmpty(), message = "Indexing auctions…", version = view.version + 1)
        return generation
    }
    @Synchronized fun isCurrent(token: Long) = token == generation && view.loading
    @Synchronized fun accept(token: Long, requested: Int, page: AuctionAPI.AuctionPage): Boolean {
        if (!isCurrent(token)) return false
        if (page.page != requested || requested != pages || page.totalPages !in 1..MAX_PAGES ||
            (pages > 0 && (page.lastUpdated != sourceTime || page.totalPages != totalPages))) {
            fail(token, "API snapshot changed • saved results retained; refresh later")
            return false
        }
        sourceTime = page.lastUpdated; totalPages = page.totalPages; pages++; skipped += page.skipped
        for (auction in page.auctions) {
            if (auction.uuid in staging) continue
            if (staging.size >= MAX_AUCTIONS) {
                publish(false); fail(token, "Index limit reached • partial results"); return false
            }
            val bytes = auction.encodedItem?.length ?: 0
            staging[auction.uuid] = if (itemBytes + bytes <= 48 * 1024 * 1024) { itemBytes += bytes; auction }
                else auction.copy(encodedItem = null)
        }
        val complete = pages == totalPages
        // Keep the previous complete snapshot visible until its replacement is coherent and complete.
        if (!view.complete || complete) publish(complete)
        if (complete) staging.clear() // The published snapshot holds the listings; drop the 80k-entry lookup map.
        else view = view.copy(message = "Refreshing • $pages/$totalPages API pages • showing saved snapshot", version = view.version + 1)
        return !complete
    }
    private fun publish(complete: Boolean) {
        view = AuctionSnapshot(staging.values.toList(), pages, totalPages, sourceTime, complete, !complete,
            false, (if (complete) "Up to date" else "Indexing • partial results") +
                (if (skipped > 0) " • $skipped invalid listings omitted" else ""), view.version + 1)
    }
    @Synchronized fun fail(token: Long, message: String) {
        if (token != generation) return
        view = view.copy(loading = false, stale = view.auctions.isNotEmpty(), message = message, version = view.version + 1)
    }
    @Synchronized fun cancel() {
        generation++
        if (view.loading) view = view.copy(loading = false, message = "Indexing paused • reopen to refresh", version = view.version + 1)
    }
    companion object { const val MAX_PAGES = 200; const val MAX_AUCTIONS = 80000 }
}
