package me.mycellium.skymyce.features.bazaar

import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The selected Minecraft account's private trading journal. State changes happen on the client thread and disk
 * IO on the scheduler. Opening another account first waits for the previous account's writes and deletions.
 */
internal class BazaarJournal(private val folder: (String) -> Path, private val writeFailed: () -> Unit) {
    var owner = ""
        private set
    var archive = BazaarArchive()
        private set
    var loaded = false
        private set
    /** Set after a failed write; recording stays off until the history is reloaded or deleted. */
    var paused = false
        private set
    var deleting = false
        private set
    private var storage: BazaarStorage? = null
    private var writer: StateWriter<BazaarArchive>? = null
    private var generation = 0L
    private val deletions = hashMapOf<String, CompletableFuture<Unit>>()

    val error get() = storage?.error
    val writable get() = loaded && !paused && !deleting && storage?.writable == true

    /** Loads [account]'s journal in the background; [ready] runs on the client thread if it is still selected. */
    fun open(account: String, ready: (BazaarStorage) -> Unit) {
        val token = ++generation
        val previous = writer
        val next = BazaarStorage(folder(account))
        val fence = deletions[account]
        owner = account; archive = BazaarArchive(); loaded = false; paused = false; deleting = false
        Scheduling.schedule(0.seconds) {
            runCatching { previous?.flush() }
            fence?.handle { _, _ -> Unit }?.join()
            val saved = runCatching { next.load() }.getOrElse { BazaarArchive() }
            MC.instance.execute {
                if (token != generation) return@execute
                storage = next; writer = writer(next); archive = saved; loaded = true
                ready(next)
            }
        }
    }

    private fun writer(store: BazaarStorage) = StateWriter<BazaarArchive>({ store.save(it) },
        { job -> Scheduling.schedule(500.milliseconds) { job() } }) {
        SkyMyce.logger.warn("Private Bazaar history could not be saved; previous committed data retained")
        MC.instance.execute { if (storage === store) { paused = true; writeFailed() } }
    }

    private fun commit(next: BazaarArchive) {
        archive = next
        writer?.submit(next)
    }

    /** Appends new events and returns how many were rejected as invalid or conflicting. Duplicates are no-ops. */
    fun record(events: List<BazaarEvent>): Int {
        if (!writable || events.isEmpty()) return 0
        var next = archive
        var rejected = 0
        for (event in events) runCatching { recordBazaarEvent(next, event) }.onSuccess { next = it }.onFailure { rejected++ }
        if (next !== archive) commit(next)
        return rejected
    }

    /** Validates [event] against the current journal without keeping it. */
    fun preview(event: BazaarEvent): BazaarArchive = recordBazaarEvent(archive, event)

    fun startSession(session: BazaarSession) = commit(archive.copy(sessions = archive.sessions + session))

    fun endSession(session: BazaarSession) {
        val next = archive.copy(sessions = archive.sessions.map { if (it.id == session.id) session else it })
        if (writable) commit(next) else archive = next
    }

    /** Removes a local replay only; its accounting events stay in the journal. */
    fun deleteSession(id: String) {
        require(archive.sessions.any { it.id == id && it.context.account == owner })
        commit(archive.copy(sessions = archive.sessions.filterNot { it.id == id }))
    }

    fun export(): CompletableFuture<String> {
        val store = storage ?: return CompletableFuture.failedFuture(IllegalStateException("History is still loading"))
        val captured = archive.copy(events = archive.events.filter { it.context.account == owner }, sessions = archive.sessions.filter { it.context.account == owner })
        val result = CompletableFuture<String>()
        Scheduling.schedule(0.seconds) { runCatching { store.export(captured) }.fold(result::complete, result::completeExceptionally) }
        return result
    }

    /**
     * Permanently deletes the selected account's private journal. Shared contributions, publications and sharing
     * choices are separate and unchanged. [done] reports whether the account was still selected when it finished.
     */
    fun delete(done: (Result<Boolean>) -> Unit) {
        check(loaded && !deleting)
        val account = owner
        val store = checkNotNull(storage)
        val previous = checkNotNull(writer)
        val token = generation
        val fence = CompletableFuture<Unit>()
        deletions[account] = fence
        deleting = true
        Scheduling.schedule(0.seconds) {
            val deleted = runCatching { previous.flush(); store.delete(); store.save(BazaarArchive()) }
            deleted.fold({ fence.complete(Unit) }, fence::completeExceptionally)
            MC.instance.execute {
                deletions.remove(account, fence)
                if (token != generation || owner != account) { done(deleted.map { false }); return@execute }
                deleting = false
                writer = writer(store)
                deleted.onSuccess { archive = BazaarArchive(); paused = false }.onFailure { loaded = false }
                done(deleted.map { true })
            }
        }
    }

    fun flush() { runCatching { writer?.flush() } }
}
