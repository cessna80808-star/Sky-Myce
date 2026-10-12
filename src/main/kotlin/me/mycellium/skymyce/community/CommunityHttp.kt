package me.mycellium.skymyce.community

import java.io.InputStream
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val bodyDeadlines = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "Sky-Myce HTTP body deadlines").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/** HttpClient's streaming handler returns after headers; close a stalled body independently of the request timeout. */
internal fun <T> withCommunityBodyDeadline(body: InputStream, timeout: Duration, consume: (InputStream) -> T): T {
    require(!timeout.isNegative && !timeout.isZero)
    // Only the reader or the deadline can complete this body. A cancelled task cannot expire a successful read later.
    val state = AtomicInteger(0)
    val expiry = bodyDeadlines.schedule({
        if (state.compareAndSet(0, 2)) runCatching { body.close() }
    }, timeout.toNanos(), TimeUnit.NANOSECONDS)
    return body.use { input ->
        try {
            val value = consume(input)
            if (!state.compareAndSet(0, 1)) throw HttpTimeoutException("Community response body timed out")
            value
        } catch (failure: Throwable) {
            if (state.get() == 2 && failure !is HttpTimeoutException) {
                throw HttpTimeoutException("Community response body timed out").apply { initCause(failure) }
            }
            throw failure
        } finally {
            state.compareAndSet(0, 1)
            expiry.cancel(false)
        }
    }
}

internal fun readCommunityBody(body: InputStream, maximum: Int, timeout: Duration): ByteArray {
    require(maximum in 1..262144)
    return withCommunityBodyDeadline(body, timeout) { input ->
        input.readNBytes(maximum + 1).also { require(it.size <= maximum) }
    }
}

/** A partial/oversized/expired download never remains as an installable candidate. Existing files are never overwritten. */
internal fun writeCommunityArtifactBody(body: InputStream, next: Path, expectedSize: Long, timeout: Duration) {
    require(expectedSize in 1..MAX_COMMUNITY_JAR)
    var created = false
    try {
        withCommunityBodyDeadline(body, timeout) { input ->
            Files.newOutputStream(next, StandardOpenOption.CREATE_NEW).use { output ->
                created = true
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count; require(total <= expectedSize)
                    output.write(buffer, 0, count)
                }
                require(total == expectedSize)
            }
        }
    } catch (failure: Throwable) {
        if (created) runCatching { Files.deleteIfExists(next) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
