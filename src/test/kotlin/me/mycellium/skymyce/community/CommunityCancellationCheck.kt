package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import java.net.http.HttpTimeoutException

internal fun cancellationChecks() {
    val owner = "a".repeat(32); val other = "b".repeat(32); val now = System.currentTimeMillis()
    fun flow(id: String = "12345678-1234-4234-9234-123456789abc") = JsonObject().apply {
        addProperty("flowId", id); addProperty("pollSecret", "s".repeat(43)); addProperty("expiresAt", now + 600_000)
        addProperty("fallbackCode", "not-sent"); addProperty("browserUrl", "https://not-sent.invalid/")
    }
    val cancellation = CommunityConnectCancellation()
    cancellation.remember(owner, flow())
    val first = requireNotNull(cancellation.begin(owner, 4))
    check(cancellation.inFlight && cancellation.begin(owner, 5) == null)
    // Repeated Cancel and expiry while transport is running cannot invalidate its acknowledgement.
    check(!cancellation.expire(now + 600_000) && cancellation.inFlight)
    var calls = 0
    val unavailable = performCommunityCancellation(first.request) { path, body ->
        calls++
        check(path == "/community/connect/cancel" && body.keySet() == setOf("flowId", "pollSecret"))
        check(body["flowId"].asString == first.request.flowId && body["pollSecret"].asString == first.request.pollSecret)
        throw HttpTimeoutException("synthetic timeout")
    }
    check(!unavailable && calls == 1 && cancellation.finish(first, owner, 4, unavailable) && cancellation.hasPending)
    // A timeout retains exactly the same secret-bound cancellation for retry; it does not become successful locally.
    val retry = requireNotNull(cancellation.begin(owner, 5))
    check(retry.request === first.request)
    check(!cancellation.finish(first, owner, 5, true) && cancellation.hasPending)
    check(!cancellation.finish(retry, other, 5, true) && cancellation.hasPending)
    check(cancellation.begin(other, 5) == null)
    for (response in listOf(JsonObject().apply { addProperty("ok", true); addProperty("status", "pending") },
        JsonObject().apply { addProperty("ok", false); addProperty("status", "cancelled") }, JsonObject().apply { addProperty("status", "cancelled") })) {
        check(!performCommunityCancellation(retry.request) { _, _ -> response })
        check(cancellation.hasPending)
    }
    // An unknown/wrong-secret response is not proof of cancellation before the recorded expiry.
    check(!performCommunityCancellation(retry.request) { _, _ -> throw CommunityFailure("connect_expired") })
    check(!cancellation.expire(now + 599_999) && cancellation.hasPending)
    val confirmed = performCommunityCancellation(retry.request) { _, _ -> JsonObject().apply { addProperty("ok", true); addProperty("status", "cancelled") } }
    check(confirmed && cancellation.finish(retry, owner, 5, confirmed) && !cancellation.hasPending)
    cancellation.remember(owner, flow())
    val replaced = requireNotNull(cancellation.begin(owner, 7))
    cancellation.clear()
    cancellation.remember(owner, flow("87654321-4321-4321-9321-cba987654321"))
    check(!cancellation.finish(replaced, owner, 7, true) && cancellation.hasPending)
    check(cancellation.expire(now + 600_000) && !cancellation.hasPending)
    cancellation.remember(owner, flow()); val cleared = requireNotNull(cancellation.begin(owner, 9)); cancellation.clear()
    check(!cancellation.finish(cleared, owner, 9, true) && !cancellation.hasPending)
    for (invalid in listOf(flow().apply { addProperty("pollSecret", "bad") }, flow().apply { addProperty("flowId", "bad") })) {
        check(runCatching { cancellation.remember(owner, invalid) }.isFailure && !cancellation.hasPending)
    }
    // Disconnect a saved session during a newer relink: cancel its exact pending flow AND revoke the saved session.
    cancellation.remember(owner, flow())
    val relink = requireNotNull(cancellation.snapshot(owner))
    var clearCalls = 0; var cancelCalls = 0; var revokeCalls = 0
    val disconnected = performCommunityDisconnect(relink, { clearCalls++ }, { captured ->
        cancelCalls++; check(captured === relink); true
    }, { revokeCalls++; true })
    check(disconnected == CommunityDisconnectResult(true, true, true) && clearCalls == 1 && cancelCalls == 1 && revokeCalls == 1)
    val failedFlow = performCommunityDisconnect(relink, {}, { false }, { revokeCalls++; true })
    check(failedFlow == CommunityDisconnectResult(true, false, true) && revokeCalls == 2)
    val failedSession = performCommunityDisconnect(relink, {}, { cancelCalls++; true }, { throw HttpTimeoutException("synthetic timeout") })
    check(failedSession == CommunityDisconnectResult(true, true, false) && cancelCalls == 2)
    val failedStorage = performCommunityDisconnect(relink, { throw IllegalStateException("synthetic storage failure") }, { cancelCalls++; true }, { revokeCalls++; true })
    check(failedStorage == CommunityDisconnectResult(false, true, true) && cancelCalls == 3 && revokeCalls == 3)
    check(performCommunityDisconnect(null, {}, { error("no flow to cancel") }, { true }) == CommunityDisconnectResult(true, true, true))
    println("Community cancellation checks passed: secret-bound requests, uncertain timeout/retry, duplicate/expiry guards and independent pending-flow/saved-session revocation")
}
