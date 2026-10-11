package me.mycellium.skymyce.features.instances.dungeons.friends

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readIrcMediaBytes(input: InputStream, declaredLength: Long, deadlineNanos: Long,
    maximumBytes: Int = IRC_GIF_MAX_BYTES, cancelled: () -> Boolean = { false }): ByteArray {
    require(maximumBytes in 1..12 * 1024 * 1024 && declaredLength in -1..maximumBytes.toLong())
    val output = ByteArrayOutputStream(minOf(declaredLength.coerceAtLeast(0L), 65536L).toInt())
    val buffer = ByteArray(8192)
    fun current() = check(!cancelled() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadlineNanos)
    while (true) {
        current()
        val read = input.read(buffer)
        if (read < 0) break
        require(read > 0 && read <= maximumBytes - output.size())
        output.write(buffer, 0, read)
    }
    current()
    require(declaredLength < 0 || declaredLength == output.size().toLong())
    return output.toByteArray()
}
