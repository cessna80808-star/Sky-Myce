package me.mycellium.skymyce.features.social

import me.mycellium.skymyce.community.CommunityFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun studioAsyncWorkChecks() {
    val release = CountDownLatch(1); val started = CountDownLatch(1); val completed = CountDownLatch(3)
    val executed = AtomicInteger()
    StudioAsyncWorker("test bounded studio", 1, 2).use { worker ->
        try {
            check(worker.submit { started.countDown(); release.await(3, TimeUnit.SECONDS); executed.incrementAndGet(); completed.countDown() })
            check(started.await(2, TimeUnit.SECONDS))
            var accepted = 0
            repeat(10_000) { if (worker.submit { executed.incrementAndGet(); completed.countDown() }) accepted++ }
            check(accepted == 2) // One running + two waiting; saturation is explicit, accepted writes are retained.
            release.countDown(); check(completed.await(3, TimeUnit.SECONDS) && executed.get() == 3)
        } finally { release.countDown() }
    }
    val closeDialog = CountDownLatch(1); val dialogStarted = CountDownLatch(1); val dialogEnded = CountDownLatch(1)
    StudioAsyncWorker("test sole chooser", 1, 0).use { chooser ->
        try {
            check(chooser.submit { dialogStarted.countDown(); closeDialog.await(3, TimeUnit.SECONDS); dialogEnded.countDown() })
            check(dialogStarted.await(2, TimeUnit.SECONDS))
            repeat(10_000) { check(!chooser.submit { error("Concurrent or queued native chooser") }) }
            closeDialog.countDown(); check(dialogEnded.await(3, TimeUnit.SECONDS))
        } finally { closeDialog.countDown() }
    }

    val captured = StudioOwner("account-a", "a".repeat(32), "s".repeat(43), 7)
    check(studioOwnerCurrent(captured, captured, captured.uuid, true))
    for (changed in listOf(null, captured.copy(accountId = "account-b"), captured.copy(uuid = "b".repeat(32)),
        captured.copy(token = "t".repeat(43)), captured.copy(generation = 8))) {
        check(!studioOwnerCurrent(captured, changed, captured.uuid, true))
    }
    check(!studioOwnerCurrent(captured, captured, "b".repeat(32), true)) // MC account changed before the next identity tick.
    check(!studioOwnerCurrent(captured, captured, captured.uuid, false))

    // Actual queued work begins after a screen is closed: reads do no HTTP work, but an accepted owned write runs once.
    val owner = AtomicReference<StudioOwner?>(captured)
    val gate = CountDownLatch(1); val blocking = CountDownLatch(1); val finished = CountDownLatch(2)
    val reads = AtomicInteger(); val mutations = AtomicInteger()
    val readGuard = StudioRequestGuard(false, { studioOwnerCurrent(captured, owner.get(), captured.uuid, true) }, { false })
    val writeGuard = StudioRequestGuard(true, { studioOwnerCurrent(captured, owner.get(), captured.uuid, true) }, { false })
    StudioAsyncWorker("test closed studio", 1, 2).use { worker ->
        try {
            check(worker.submit { blocking.countDown(); gate.await(3, TimeUnit.SECONDS) }); check(blocking.await(2, TimeUnit.SECONDS))
            check(worker.submit { check(runCatching { readGuard.check(); reads.incrementAndGet() }.exceptionOrNull() is CommunityFailure); finished.countDown() })
            check(worker.submit { writeGuard.check(); mutations.incrementAndGet(); finished.countDown() })
            gate.countDown(); check(finished.await(3, TimeUnit.SECONDS) && reads.get() == 0 && mutations.get() == 1)
        } finally { gate.countDown() }
    }

    val ownerGate = CountDownLatch(1); val ownerBlocking = CountDownLatch(1); val staleFinished = CountDownLatch(1)
    val httpCalls = AtomicInteger()
    StudioAsyncWorker("test stale owner", 1, 1).use { worker ->
        try {
            check(worker.submit { ownerBlocking.countDown(); ownerGate.await(3, TimeUnit.SECONDS) }); check(ownerBlocking.await(2, TimeUnit.SECONDS))
            check(worker.submit {
                val failure = runCatching { writeGuard.check(); httpCalls.incrementAndGet() }.exceptionOrNull()
                check((failure as? CommunityFailure)?.code == "session_identity_changed"); staleFinished.countDown()
            })
            owner.set(captured.copy(accountId = "account-b")); ownerGate.countDown()
            check(staleFinished.await(3, TimeUnit.SECONDS) && httpCalls.get() == 0)
        } finally { ownerGate.countDown() }
    }
    // The same guard is checked again after a grant response, before any PNG is submitted under stale authority.
    owner.set(captured); writeGuard.check(); val grants = 1
    owner.set(captured.copy(generation = 8))
    check(runCatching { writeGuard.check(); httpCalls.incrementAndGet() }.isFailure && grants == 1 && httpCalls.get() == 0)
    println("Studio async checks passed: bounded accepted jobs, single native chooser, closed read suppression, preserved writes and pre-HTTP owner guards")
}
