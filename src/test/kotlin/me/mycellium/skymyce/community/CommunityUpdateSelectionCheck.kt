package me.mycellium.skymyce.community

internal fun updateSelectionChecks() {
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
    println("Update selection checks passed: repeated Later is bounded and each explicit download remains independently cancellable")
}
