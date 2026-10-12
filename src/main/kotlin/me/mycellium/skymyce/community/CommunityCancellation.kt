package me.mycellium.skymyce.community

import com.google.gson.JsonObject

internal data class CommunityCancellationRequest(val owner: String, val flowId: String, val pollSecret: String, val expiresAt: Long) {
    fun body() = JsonObject().apply { addProperty("flowId", flowId); addProperty("pollSecret", pollSecret) }
}

internal data class CommunityCancellationAttempt(val request: CommunityCancellationRequest, val epoch: Long)

/** Render-thread state only; transport uses immutable snapshots and never logs the polling secret. */
internal class CommunityConnectCancellation {
    private var pending: CommunityCancellationRequest? = null
    private var active: CommunityCancellationAttempt? = null
    val hasPending get() = pending != null
    val inFlight get() = active != null

    fun remember(owner: String, flow: JsonObject) {
        check(!inFlight)
        require(owner.matches(Regex("[a-f0-9]{32}")))
        val flowId = flow["flowId"].asString; val secret = flow["pollSecret"].asString; val expiry = flow["expiresAt"].asLong
        require(flowId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) && secret.matches(Regex("[A-Za-z0-9_-]{43}")) && expiry > 0)
        pending = CommunityCancellationRequest(owner, flowId, secret, expiry)
    }

    fun snapshot(owner: String) = pending?.takeIf { it.owner == owner }
    fun restore(request: CommunityCancellationRequest) { check(!inFlight); pending = request }
    fun begin(owner: String, epoch: Long): CommunityCancellationAttempt? {
        if (inFlight) return null
        return snapshot(owner)?.let { CommunityCancellationAttempt(it, epoch).also { attempt -> active = attempt } }
    }

    /** A stale owner/epoch or an older replaced flow can neither clear nor confirm a newer request. */
    fun finish(attempt: CommunityCancellationAttempt, owner: String, epoch: Long, confirmed: Boolean): Boolean {
        if (attempt !== active || attempt.request !== pending || attempt.request.owner != owner || attempt.epoch != epoch) return false
        active = null
        if (confirmed) pending = null
        return true
    }

    fun expire(now: Long): Boolean {
        if (inFlight) return false
        if (pending?.expiresAt?.let { it <= now } != true) return false
        pending = null
        return true
    }

    fun clear() { pending = null; active = null }
}

/** No bearer or typed UUID grants authority: only the exact flow's private polling secret can cancel it. */
internal fun performCommunityCancellation(request: CommunityCancellationRequest, exchange: (String, JsonObject) -> JsonObject): Boolean = runCatching {
    val response = exchange("/community/connect/cancel", request.body())
    require(response["ok"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true &&
            response["status"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "cancelled" } == true)
}.isSuccess

internal data class CommunityDisconnectResult(val storageCleared: Boolean, val flowCancelled: Boolean, val sessionRevoked: Boolean)

/** A relink flow and a saved session are separate authorities; failure of either must not skip the other revocation. */
internal fun performCommunityDisconnect(pending: CommunityCancellationRequest?, clearStorage: () -> Unit,
                                       cancel: (CommunityCancellationRequest) -> Boolean, revokeSession: () -> Boolean): CommunityDisconnectResult {
    val storageCleared = runCatching(clearStorage).isSuccess
    val flowCancelled = pending == null || runCatching { cancel(pending) }.getOrDefault(false)
    val sessionRevoked = runCatching(revokeSession).getOrDefault(false)
    return CommunityDisconnectResult(storageCleared, flowCancelled, sessionRevoked)
}
