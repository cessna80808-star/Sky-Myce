package me.mycellium.skymyce.features.social

import me.mycellium.skymyce.community.CommunityFailure
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared across screens. Saturation is reported to the caller; accepted mutations are never coalesced. */
internal class StudioAsyncWorker(name: String, concurrency: Int, queued: Int) : AutoCloseable {
    private val queue: BlockingQueue<Runnable> = if (queued == 0) SynchronousQueue() else ArrayBlockingQueue(queued)
    private val executor = ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS, queue,
        { runnable -> Thread(runnable, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun submit(action: () -> Unit): Boolean = try { executor.execute(action); true } catch (_: RejectedExecutionException) { false }
    override fun close() { executor.shutdownNow() }
}

internal data class StudioOwner(val accountId: String, val uuid: String, val token: String, val generation: Long)
internal fun studioOwnerCurrent(captured: StudioOwner, current: StudioOwner?, minecraftUuid: String, connected: Boolean) =
    connected && captured == current && captured.uuid == minecraftUuid

/** Closing a screen discards reads, while an accepted write retains its explicit intent under its captured authority. */
internal class StudioRequestGuard(private val mutation: Boolean, private val ownerCurrent: () -> Boolean, private val displayCurrent: () -> Boolean) {
    fun check() {
        if (!ownerCurrent()) throw CommunityFailure("session_identity_changed")
        if (!mutation && !displayCurrent()) throw CommunityFailure("request_superseded")
    }
}
