package me.mycellium.skymyce.community

/** Identical Later presses are idempotent; a new Update Now approval remains independently cancellable. */
internal class CommunityUpdateSelection {
    var generation = 0L; private set
    private var deferredGeneration = -1L
    private var deferredVersion = ""

    fun approve(): Long = ++generation
    fun cancel(): Long = ++generation
    fun defer(version: String): Boolean {
        if (deferredGeneration == generation && deferredVersion == version) return false
        deferredVersion = version; deferredGeneration = ++generation
        return true
    }
}
