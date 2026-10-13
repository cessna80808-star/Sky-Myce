package me.mycellium.skymyce.community

internal fun updateSelectionChecks() {
    val preferencePath = java.nio.file.Files.createTempDirectory("community-update-prefs").resolve("preferences.json")
    val store = CommunityUpdatePreferenceStore(preferencePath)
    check(store.read() == CommunityUpdatePreferences())
    java.nio.file.Files.writeString(preferencePath, """{"deferredVersion":"2.5.0"}""")
    check(store.read() == CommunityUpdatePreferences(false, "2.5.0"))
    store.write(CommunityUpdatePreferences(true, "2.5.0"))
    check(CommunityUpdatePreferenceStore(preferencePath).read() == CommunityUpdatePreferences(true, "2.5.0"))
    store.write(CommunityUpdatePreferences(false, "2.5.0"))
    check(CommunityUpdatePreferenceStore(preferencePath).read() == CommunityUpdatePreferences(false, "2.5.0"))
    java.nio.file.Files.writeString(preferencePath, """{"autoUpdate":"true"}""")
    check(!store.read().autoUpdate)
    val selection = CommunityUpdateSelection()
    val downloading = selection.approve()
    var deferredWrites = 0
    repeat(10_000) { if (selection.defer("2.5.1")) deferredWrites++ }
    check(deferredWrites == 1 && selection.generation != downloading)
    // A new explicit approval must still be cancellable, even when this version was previously deferred.
    val approvedAgain = selection.approve()
    check(selection.generation == approvedAgain && selection.defer("2.5.1"))
    check(selection.generation != approvedAgain && !selection.defer("2.5.1"))
    val beforeDifferentRelease = selection.generation
    check(selection.defer("2.5.2") && selection.generation > beforeDifferentRelease)
    repeat(10_000) { check(!selection.defer("2.5.2")) }
    val automatic = selection.approve()
    check(selection.cancel() != automatic) { "Turning auto-update Off must invalidate automatic staging" }
    // A late result from the cancelled automatic request must never match a new manual approval.
    val manualAfterOff = selection.approve()
    check(selection.generation == manualAfterOff && automatic != manualAfterOff)
    check(selection.defer("2.5.2") && selection.generation != manualAfterOff)
    val afterLater = selection.generation
    repeat(10_000) { check(!selection.defer("2.5.2") && selection.generation == afterLater) }
    val latestManual = selection.approve()
    check(latestManual != automatic && latestManual != manualAfterOff && latestManual != afterLater)
    check(selection.generation == latestManual) { "Previous automatic/manual completions cannot replace the latest explicit approval" }
    check(selection.defer("2.5.3") && selection.generation != latestManual)
    val manualAfterNewRelease = selection.approve()
    check(selection.defer("2.5.3") && selection.generation != manualAfterNewRelease)

    // Persist explicit Off/Later independently while a real single-thread download worker is occupied.
    val downloadStarted = java.util.concurrent.CountDownLatch(1)
    val finishDownload = java.util.concurrent.CountDownLatch(1)
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
        val download = worker.submit { downloadStarted.countDown(); check(finishDownload.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        check(downloadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        java.nio.file.Files.writeString(preferencePath, """{"autoUpdate":true,"deferredVersion":"","unrelatedPreference":"retain"}""")
        val cancellation = CommunityUpdatePreferences(false, "2.5.3")
        store.write(cancellation)
        check(!download.isDone && CommunityUpdatePreferenceStore(preferencePath).read() == cancellation) {
            "Restart must observe Off/Later before the occupied download worker completes"
        }
        check(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(preferencePath)).asJsonObject["unrelatedPreference"].asString == "retain")
        finishDownload.countDown(); download.get(2, java.util.concurrent.TimeUnit.SECONDS)
    } finally { finishDownload.countDown(); worker.shutdownNow() }
    java.nio.file.Files.writeString(preferencePath, "{invalid")
    check(store.read() == CommunityUpdatePreferences()) { "Damaged preferences cannot enable unattended updates" }
    store.write(CommunityUpdatePreferences(false, "2.5.4"))
    check(CommunityUpdatePreferenceStore(preferencePath).read() == CommunityUpdatePreferences(false, "2.5.4"))
    check(java.nio.file.Files.list(preferencePath.parent).use { files -> files.anyMatch { it.fileName.toString().startsWith("preferences.json.corrupt-") } })
    println("Update selection checks passed: bounded Later, stale automatic/manual approval interleavings, Off/Later persistence during a blocked worker, unknown-field preservation and fail-closed corrupt preference recovery")
}
