package me.mycellium.skymyce.community

internal fun connectionViewChecks() {
    val disconnected = CommunityConnectionView(false, false, false, false, false, false)
    check(disconnected.kind == CommunityConnectionKind.DISCONNECTED && disconnected.actions == listOf(CommunityConnectionAction.CONNECT))
    check(disconnected.enabled(CommunityConnectionAction.CONNECT))
    check(!disconnected.enabled(CommunityConnectionAction.DISCONNECT) && !disconnected.enabled(CommunityConnectionAction.CANCEL))
    check(!disconnected.copy(busy = true).enabled(CommunityConnectionAction.CONNECT))
    val saved = disconnected.copy(saved = true)
    check(saved.kind == CommunityConnectionKind.SAVED && saved.actions == listOf(CommunityConnectionAction.RECONNECT, CommunityConnectionAction.DISCONNECT))
    val connected = saved.copy(connected = true)
    check(connected.kind == CommunityConnectionKind.CONNECTED && connected.actions == listOf(CommunityConnectionAction.DISCONNECT))
    val starting = disconnected.copy(pending = true, busy = true)
    check(starting.kind == CommunityConnectionKind.PENDING && starting.actions == listOf(CommunityConnectionAction.CANCEL))
    check(starting.enabled(CommunityConnectionAction.CANCEL)) // Cancel can revoke a start whose response hasn't returned.
    val polling = starting.copy(browser = true)
    check(polling.enabled(CommunityConnectionAction.BROWSER) && polling.enabled(CommunityConnectionAction.CANCEL))
    check(!polling.enabled(CommunityConnectionAction.CONNECT)) // Do not start another flow while the browser is open.
    val cancelling = polling.copy(cancelling = true)
    check(cancelling.kind == CommunityConnectionKind.CANCELLING && cancelling.actions == listOf(CommunityConnectionAction.CANCEL))
    check(!cancelling.enabled(CommunityConnectionAction.CANCEL))
    check(cancelling.copy(busy = false).enabled(CommunityConnectionAction.CANCEL)) // Unconfirmed server revocation stays retryable.
    val cancelSaved = cancelling.copy(saved = true, busy = false)
    check(cancelSaved.actions == listOf(CommunityConnectionAction.CANCEL, CommunityConnectionAction.DISCONNECT))
    check(cancelSaved.enabled(CommunityConnectionAction.DISCONNECT)) // Failure to cancel a relink must not hide saved-session revocation.
    check(!cancelSaved.copy(busy = true).enabled(CommunityConnectionAction.DISCONNECT))
    check(cancelling.copy(connected = true, busy = false).enabled(CommunityConnectionAction.DISCONNECT))
    val relink = polling.copy(saved = true, busy = false)
    check(relink.actions.last() == CommunityConnectionAction.DISCONNECT && relink.enabled(CommunityConnectionAction.DISCONNECT))
}
