package me.mycellium.skymyce.community

internal enum class CommunityConnectionKind { DISCONNECTED, SAVED, CONNECTED, PENDING, CANCELLING }
internal enum class CommunityConnectionAction { CONNECT, RECONNECT, BROWSER, CANCEL, DISCONNECT }

/** Presentation mirrors client authorities; local cancellation is never reported as server confirmation. */
internal data class CommunityConnectionView(val connected: Boolean, val saved: Boolean, val pending: Boolean,
                                            val cancelling: Boolean, val busy: Boolean, val browser: Boolean) {
    val kind get() = when {
        cancelling -> CommunityConnectionKind.CANCELLING
        pending -> CommunityConnectionKind.PENDING
        connected -> CommunityConnectionKind.CONNECTED
        saved -> CommunityConnectionKind.SAVED
        else -> CommunityConnectionKind.DISCONNECTED
    }
    val actions get(): List<CommunityConnectionAction> = when (kind) {
        CommunityConnectionKind.CANCELLING -> buildList {
            add(CommunityConnectionAction.CANCEL)
            if (saved || connected) add(CommunityConnectionAction.DISCONNECT)
        }
        CommunityConnectionKind.PENDING -> buildList {
            if (browser) add(CommunityConnectionAction.BROWSER)
            add(CommunityConnectionAction.CANCEL)
            if (saved || connected) add(CommunityConnectionAction.DISCONNECT)
        }
        CommunityConnectionKind.CONNECTED -> listOf(CommunityConnectionAction.DISCONNECT)
        CommunityConnectionKind.SAVED -> listOf(CommunityConnectionAction.RECONNECT, CommunityConnectionAction.DISCONNECT)
        CommunityConnectionKind.DISCONNECTED -> listOf(CommunityConnectionAction.CONNECT)
    }
    fun enabled(action: CommunityConnectionAction): Boolean = action in actions && when (action) {
        CommunityConnectionAction.BROWSER -> browser
        // Start/poll can be cancelled while running; a revocation already in flight cannot be repeated.
        CommunityConnectionAction.CANCEL -> !cancelling || !busy
        else -> !busy
    }
}
