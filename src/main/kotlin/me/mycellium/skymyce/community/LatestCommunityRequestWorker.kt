package me.mycellium.skymyce.community

import java.util.concurrent.Executor

/** One drain task, one running request and one replaceable latest request across screen lifetimes. */
internal class LatestCommunityRequestWorker(private val executor: Executor) {
    private class Work(val owner: Any, val current: () -> Boolean, val run: () -> Unit) {
        @Volatile var cancelled = false
        fun isCurrent() = !cancelled && current()
    }
    private val lock = Any()
    private var pending: Work? = null
    private var active: Work? = null
    private var thread: Thread? = null
    private var draining = false

    fun <T> submit(owner: Any, current: () -> Boolean, load: () -> T, complete: (Result<T>) -> Unit) {
        lateinit var work: Work
        work = Work(owner, current) {
            val result = runCatching(load)
            if (work.isCurrent()) complete(result)
        }
        synchronized(lock) {
            pending = work
            interruptLocked(owner)
            if (!draining) startLocked()
        }
    }

    fun cancel(owner: Any) = synchronized(lock) {
        if (pending?.owner === owner) pending = null
        interruptLocked(owner)
    }

    private fun interruptLocked(owner: Any) {
        active?.takeIf { it.owner === owner && !it.cancelled }?.let {
            it.cancelled = true
            // Interrupt while holding the state lock: this cannot accidentally interrupt the next owner's task.
            thread?.interrupt()
        }
    }

    private fun startLocked() {
        draining = true
        try { executor.execute(::drain) } catch (failure: RuntimeException) { draining = false; throw failure }
    }

    private fun drain() {
        try {
            while (true) {
                val work = synchronized(lock) {
                    val next = pending ?: return
                    pending = null; active = next; thread = Thread.currentThread()
                    next
                }
                // A cancelled request may finish without consuming its interruption. Never carry it into its replacement.
                Thread.interrupted()
                try { if (work.isCurrent()) work.run() } finally {
                    synchronized(lock) { active = null; thread = null }
                    Thread.interrupted()
                }
            }
        } finally {
            synchronized(lock) {
                active = null; thread = null; draining = false
                // A submit can race the empty-queue return, or an unexpected callback failure.
                if (pending != null) startLocked()
            }
        }
    }
}
