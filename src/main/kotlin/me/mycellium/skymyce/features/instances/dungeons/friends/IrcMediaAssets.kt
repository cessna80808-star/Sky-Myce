package me.mycellium.skymyce.features.instances.dungeons.friends

import tech.thatgravyboat.skyblockapi.utils.Scheduling
import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Codec
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.EncodedImageFormat
import io.github.humbleui.skija.ImageInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class IrcMediaAssetStatus { IDLE, LOADING, READY, FAILED, UNSUPPORTED, UNAVAILABLE }
internal data class IrcMediaAssetState(val status: IrcMediaAssetStatus, val asset: IrcGif? = null, val detail: String? = null)
internal fun compactIrcMedia(status: IrcMediaAssetStatus) = when (status) {
    IrcMediaAssetStatus.FAILED, IrcMediaAssetStatus.UNSUPPORTED, IrcMediaAssetStatus.UNAVAILABLE -> true
    else -> false
}
internal fun unavailableIrcKlipyMedia(uri: URI, bytes: ByteArray): Boolean = trustedIrcMediaUrl(uri) &&
    uri.host.lowercase() == "static.klipy.com" && bytes.size == 78534 &&
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "e7324db306981c0e40cfa2bc1e2cfd0a5b52163689961f073804d2db2f74794e"

internal fun ircMediaViewerCurrent(capturedGeneration: Long, generation: Long, media: IrcMedia,
    key: IrcKey?, message: GlobalChatMessage?, currentChannel: Boolean): Boolean = capturedGeneration == generation &&
    (key == null || currentChannel && message != null && message.key == key && !message.deleted && media in message.media)

private val mediaHosts = setOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com",
    "media.tenor.com", "media1.tenor.com", "static.klipy.com", "i.imgur.com", "images.unsplash.com")
private const val FULL_IMAGE_BYTES = 12 * 1024 * 1024
private const val THUMBNAIL_BYTES = 2 * 1024 * 1024
internal const val IRC_MEDIA_THUMBNAIL_WIDTH = 192
internal const val IRC_MEDIA_THUMBNAIL_HEIGHT = 144
private const val MAX_IMAGE_DIMENSION = 4096
private const val MAX_IMAGE_PIXELS = 16 * 1024 * 1024
private const val MAX_IMAGE_DECODED_BYTES = 32L * 1024 * 1024
private val klipyVideoPath = Regex("/ii/[a-fA-F0-9]{32}/[a-fA-F0-9]{2}/[a-fA-F0-9]{2}/[A-Za-z0-9_-]{1,128}\\.mp4")

internal fun trustedIrcMediaUrl(uri: URI): Boolean {
    if (!uri.scheme.equals("https", true) || uri.isOpaque || uri.userInfo != null || uri.fragment != null ||
        uri.port !in listOf(-1, 443) || uri.toASCIIString().length > 4096 || uri.host?.lowercase() !in mediaHosts) return false
    val path = uri.path ?: return false
    return path.startsWith('/') && path.length > 1 && path.none { it.isISOControl() || it == '\\' || it == '%' } &&
        uri.query.orEmpty().none { it.isISOControl() || it == '\\' }
}

/** Only public unicast destinations are eligible, including after a trusted CDN name is resolved. */
internal fun publicIrcMediaAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val (a, b, c) = bytes
        return a !in listOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
            !(a == 192 && b == 0 && c in listOf(0, 2)) && !(a == 198 && b in 18..19) &&
            !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113)
    }
    return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
            (bytes[2] == 0 && bytes[3] == 0 || bytes[2] == 0x0d && bytes[3] == 0xb8))
}

internal fun ircDiscordThumbnail(uri: URI): URI? {
    if (!trustedIrcMediaUrl(uri) || uri.host.lowercase() !in setOf("cdn.discordapp.com", "media.discordapp.net")) return null
    // Supplying both dimensions makes Discord crop the frame; resize the complete image locally.
    val query = uri.rawQuery.orEmpty().split('&').filter {
        it.isNotEmpty() && it.substringBefore('=').lowercase() !in setOf("width", "height", "format", "fit")
    } + listOf("width=$IRC_MEDIA_THUMBNAIL_WIDTH", "format=png")
    return URI("https://media.discordapp.net${uri.rawPath}?${query.joinToString("&")}")
}

internal fun ircKlipyGif(uri: URI): URI? {
    if (!trustedIrcMediaUrl(uri) || uri.host.lowercase() != "static.klipy.com" ||
        !uri.path.matches(klipyVideoPath)) return null
    // Try a GIF alternative before falling back to Klipy's supplied thumbnail.
    return URI("https://static.klipy.com${uri.rawPath.dropLast(4)}.gif${uri.rawQuery?.let { "?$it" }.orEmpty()}")
}
internal fun ircKlipyPoster(media: IrcMedia, status: IrcMediaAssetStatus?): URI? =
    if (media.type == "video" && ircKlipyGif(media.url) != null && status == IrcMediaAssetStatus.UNAVAILABLE)
        media.thumbnailUrl?.takeIf(::trustedIrcMediaUrl) else null

private fun imageFormat(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
    bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.size - 2] == (-1).toByte() && bytes.last() == 0xd9.toByte() -> "jpeg"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> null
}

// The PNG reader may accept a missing IEND; require complete, bounded chunks before decoding.
private fun completePng(bytes: ByteArray, timely: () -> Unit): Boolean {
    var position = 8
    var first = true
    while (position <= bytes.size - 12) {
        timely()
        val size = (0..3).fold(0L) { value, offset -> (value shl 8) or (bytes[position + offset].toLong() and 255) }
        if (size > bytes.size - position - 12) return false
        val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
        if (first && (type != "IHDR" || size != 13L)) return false
        first = false
        val crcOffset = position + 8 + size.toInt()
        val expectedCrc = (0..3).fold(0L) { value, offset -> (value shl 8) or (bytes[crcOffset + offset].toLong() and 255) }
        val crc = CRC32().apply { update(bytes, position + 4, size.toInt() + 4) }
        if (crc.value != expectedCrc) return false
        position += size.toInt() + 12
        if (type == "IEND") return size == 0L && position == bytes.size
    }
    return false
}

/** Decode one verified PNG/JPEG frame. Source bounds apply before any raster allocation. */
internal fun decodeIrcImage(bytes: ByteArray, deadlineNanos: Long, thumbnail: Boolean): IrcGif? = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.IRC_MEDIA) { decodeIrcImageNow(bytes, deadlineNanos, thumbnail) }

private fun decodeIrcImageNow(bytes: ByteArray, deadlineNanos: Long, thumbnail: Boolean): IrcGif? {
    val deadline = minOf(deadlineNanos, System.nanoTime() + 2_000_000_000L)
    fun timely() = require(System.nanoTime() - deadline < 0)
    return try {
        require(bytes.size in 1..if (thumbnail) THUMBNAIL_BYTES else FULL_IMAGE_BYTES)
        timely()
        val format = imageFormat(bytes)
        if (format == "webp") return decodeIrcWebp(bytes, deadline, thumbnail)
        require(format in setOf("png", "jpeg"))
        if (format == "png") require(completePng(bytes, ::timely))
        val reader = ImageIO.getImageReadersByFormatName(format).next()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(): Int { timely(); return super.read() }
            override fun read(target: ByteArray, offset: Int, length: Int): Int { timely(); return super.read(target, offset, length) }
        }
        try {
            MemoryCacheImageInputStream(input).use { stream ->
                reader.setInput(stream, true, true)
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 1..MAX_IMAGE_DIMENSION && height in 1..MAX_IMAGE_DIMENSION && width.toLong() * height <= MAX_IMAGE_PIXELS)
                var warned = false
                reader.addIIOReadWarningListener { _, _ -> warned = true }
                reader.addIIOReadProgressListener(object : IIOReadProgressListener {
                    private fun stop(source: ImageReader) { if (System.nanoTime() - deadline >= 0) source.abort() }
                    override fun sequenceStarted(source: ImageReader, minIndex: Int) = stop(source)
                    override fun sequenceComplete(source: ImageReader) = stop(source)
                    override fun imageStarted(source: ImageReader, imageIndex: Int) = stop(source)
                    override fun imageProgress(source: ImageReader, percentageDone: Float) = stop(source)
                    override fun imageComplete(source: ImageReader) = stop(source)
                    override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) = stop(source)
                    override fun thumbnailProgress(source: ImageReader, percentageDone: Float) = stop(source)
                    override fun thumbnailComplete(source: ImageReader) = stop(source)
                    override fun readAborted(source: ImageReader) = Unit
                })
                var sample = if (thumbnail) maxOf(1, width / IRC_MEDIA_THUMBNAIL_WIDTH, height / IRC_MEDIA_THUMBNAIL_HEIGHT) else 1
                while (ceil(width.toDouble() / sample).toLong() * ceil(height.toDouble() / sample).toLong() * 4 > MAX_IMAGE_DECODED_BYTES) sample++
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val decoded = reader.read(0, parameters)
                var retained = false
                try {
                    timely()
                    require(!warned && decoded.width > 0 && decoded.height > 0 && decoded.width.toLong() * decoded.height * 4 <= MAX_IMAGE_DECODED_BYTES)
                    if (!thumbnail) {
                        retained = true
                        return IrcGif(decoded.width, decoded.height, listOf(IrcGifFrame(decoded, 100)), -1)
                    }
                    val scale = minOf(1.0, IRC_MEDIA_THUMBNAIL_WIDTH.toDouble() / decoded.width, IRC_MEDIA_THUMBNAIL_HEIGHT.toDouble() / decoded.height)
                    val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                    val image = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null)
                    } finally { graphics.dispose() }
                    timely()
                    IrcGif(targetWidth, targetHeight, listOf(IrcGifFrame(image, 100)), -1)
                } finally { if (!retained) decoded.flush() }
            }
        } finally { reader.dispose() }
    } catch (_: Exception) { null }
}

/** Discord can return animated WebP for a .gif URL; keep native composition and snapshots bounded. */
private fun decodeIrcWebp(bytes: ByteArray, deadline: Long, thumbnail: Boolean): IrcGif? = try {
    fun timely() = require(System.nanoTime() - deadline < 0)
    val declared = (0..3).fold(0L) { value, index -> value or ((bytes[4 + index].toLong() and 255) shl (index * 8)) }
    require(declared + 8 == bytes.size.toLong())
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val info = codec.imageInfo
            require(codec.encodedImageFormat == EncodedImageFormat.WEBP)
            require(info.width in 1..MAX_IMAGE_DIMENSION && info.height in 1..MAX_IMAGE_DIMENSION &&
                info.width.toLong() * info.height * 4 <= MAX_IMAGE_DECODED_BYTES)
            val sourceFrames = codec.frameCount.coerceAtLeast(1)
            require(sourceFrames <= 2000)
            val animated = sourceFrames > 1
            val metadata = if (animated) codec.framesInfo else emptyArray()
            require(!animated || metadata.size == sourceFrames)
            metadata.forEachIndexed { index, frame ->
                require(frame.isFullyReceived && frame.requiredFrame in -1 until index && frame.duration in 0..0xffffff)
            }
            val stride = maxOf(1, (sourceFrames - 1 + 120 - 2) / (120 - 1))
            val retainedFrames = if (sourceFrames == 1) 1 else (sourceFrames - 2) / stride + 2
            timely()
            val sourceBytes = info.width.toLong() * info.height * 4
            val snapshotBudget = if (animated) MAX_IMAGE_DECODED_BYTES - 2 * sourceBytes else MAX_IMAGE_DECODED_BYTES / 3
            require(snapshotBudget >= retainedFrames * 4L)
            val scale = minOf(1.0, kotlin.math.sqrt(snapshotBudget.toDouble() /
                (retainedFrames * sourceBytes)), if (thumbnail)
                minOf(IRC_MEDIA_THUMBNAIL_WIDTH.toDouble() / info.width, IRC_MEDIA_THUMBNAIL_HEIGHT.toDouble() / info.height) else 1.0)
            val width = (info.width * scale).toInt().coerceAtLeast(1)
            val height = (info.height * scale).toInt().coerceAtLeast(1)
            require(width.toLong() * height * 4 * retainedFrames <= snapshotBudget)
            // Skia requires source-sized canvases for correct animated WebP blend/disposal.
            val decodeWidth = if (animated) info.width else width
            val decodeHeight = if (animated) info.height else height
            val rgba = ImageInfo(decodeWidth, decodeHeight, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            Bitmap().use { bitmap ->
                require(bitmap.allocPixels(rgba))
                val frames = ArrayList<IrcGifFrame>(retainedFrames)
                var completed = false
                try {
                    val row = IntArray(width)
                    for (index in 0 until sourceFrames) {
                        timely()
                        val frame = metadata.getOrNull(index)
                        val prior = if (index > 0 && frame != null && frame.requiredFrame >= 0 &&
                            metadata[index - 1].disposalMethod != io.github.humbleui.skija.AnimationDisposalMode.RESTORE_PREVIOUS) index - 1 else -1
                        codec.readPixels(bitmap, index, prior)
                        timely()
                        val duration = frame?.duration?.toLong()?.takeIf { it > 0 } ?: 100L
                        if (index % stride != 0 && index != sourceFrames - 1) {
                            frames[frames.lastIndex] = frames.last().let { it.copy(durationMillis = it.durationMillis + duration) }
                            continue
                        }
                        val pixels = requireNotNull(bitmap.readPixels())
                        require(pixels.size.toLong() == decodeWidth.toLong() * decodeHeight * 4)
                        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                        var retained = false
                        try {
                            for (y in 0 until height) {
                                timely()
                                val sourceY = y.toLong() * decodeHeight / height
                                for (x in 0 until width) {
                                    val sourceX = x.toLong() * decodeWidth / width
                                    val offset = ((sourceY * decodeWidth + sourceX) * 4).toInt()
                                    row[x] = ((pixels[offset + 3].toInt() and 255) shl 24) or
                                        ((pixels[offset].toInt() and 255) shl 16) or
                                        ((pixels[offset + 1].toInt() and 255) shl 8) or (pixels[offset + 2].toInt() and 255)
                                }
                                image.setRGB(0, y, width, 1, row, 0, width)
                            }
                            frames += IrcGifFrame(image, duration); retained = true
                        } finally { if (!retained) image.flush() }
                    }
                    timely()
                    val repetitions = codec.repetitionCount
                    require(repetitions >= -1)
                    val loopCount = if (!animated || repetitions == 0) -1 else if (repetitions == -1) 0 else repetitions
                    IrcGif(width, height, frames, loopCount).also { completed = true }
                } finally {
                    if (!completed) frames.forEach { it.image.flush() }
                }
            }
        }
    }
} catch (_: Exception) { null } catch (_: LinkageError) { null }

private fun IrcGif.decodedBytes(): Long = frames.sumOf { it.image.width.toLong() * it.image.height * 4 }
private fun IrcGif.release() = frames.forEach { it.image.flush() }

/** Pure cache: load tokens survive clear, so stale completions cannot reappear after an account change. */
internal class IrcMediaCache(private val maximumBytes: Long = 48L * 1024 * 1024,
    private val maximumEntries: Int = 64, private val ttlNanos: Long = 120_000_000_000L) {
    private data class Entry(val token: Long, var state: IrcMediaAssetState, var touched: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private var sequence = 0L
    var revision = 0L
        private set
    internal val decodedBytes get() = entries.values.sumOf { it.state.asset?.decodedBytes() ?: 0L }
    internal val size get() = entries.size
    private fun remove(key: String) { entries.remove(key)?.let { it.state.asset?.release(); revision++ } }
    // Runs on every view (each rendered frame); iterate in place instead of copying the map.
    private fun expire(now: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.state.status == IrcMediaAssetStatus.LOADING || now - entry.touched < ttlNanos) continue
            iterator.remove(); entry.state.asset?.release(); revision++
        }
    }
    fun view(key: String, now: Long): IrcMediaAssetState {
        expire(now)
        return entries[key]?.also { it.touched = now }?.state ?: IrcMediaAssetState(IrcMediaAssetStatus.IDLE)
    }
    fun begin(key: String, now: Long): Long? {
        expire(now)
        if (key in entries) return null
        while (entries.size >= maximumEntries) {
            val oldest = entries.filterValues { it.state.status != IrcMediaAssetStatus.LOADING }.minByOrNull { it.value.touched }?.key ?: return null
            remove(oldest)
        }
        val token = ++sequence
        entries[key] = Entry(token, IrcMediaAssetState(IrcMediaAssetStatus.LOADING), now)
        revision++
        return token
    }
    fun complete(key: String, token: Long, state: IrcMediaAssetState, now: Long): Boolean {
        val entry = entries[key]
        if (entry == null || entry.token != token || entry.state.status != IrcMediaAssetStatus.LOADING) {
            state.asset?.release(); return false
        }
        val bytes = state.asset?.decodedBytes() ?: 0L
        if (bytes > maximumBytes) {
            state.asset?.release()
            entry.state = IrcMediaAssetState(IrcMediaAssetStatus.FAILED, detail = "Image exceeds the memory limit. Open it in your browser.")
        } else {
            while (decodedBytes + bytes > maximumBytes) {
                val oldest = entries.filter { it.key != key && it.value.state.asset != null }.minByOrNull { it.value.touched }?.key ?: break
                remove(oldest)
            }
            entry.state = state
        }
        entry.touched = now
        revision++
        return true
    }
    fun failExpired(key: String, token: Long, deadline: Long, now: Long): Boolean = now - deadline >= 0 &&
        complete(key, token, IrcMediaAssetState(IrcMediaAssetStatus.FAILED,
            detail = "Media loading timed out. Retry or open it in your browser."), now)
    fun cancel(key: String) = remove(key)
    fun clear() = entries.keys.toList().forEach(::remove)
}

private data class MediaSource(val uri: URI, val full: Boolean, val maximumBytes: Int) {
    val key = "${if (full) "full" else "thumbnail"}:${uri.toASCIIString()}"
}

internal fun ircMediaThumbnailUrl(media: IrcMedia): URI? {
    val explicit = media.thumbnailUrl?.takeIf(::trustedIrcMediaUrl)
    ircKlipyGif(media.url)?.let { return it }
    if (media.type == "gif") {
        // A PNG Discord derivative loses animation; resize the verified GIF frames locally.
        media.url.takeIf { trustedIrcGifUrl(it) && trustedIrcMediaUrl(it) }?.let { return it }
        explicit?.takeIf(::trustedIrcGifUrl)?.let { return it }
    }
    return explicit?.let { ircDiscordThumbnail(it) ?: it } ?: (if (media.type != "video") ircDiscordThumbnail(media.url) else null)
        ?: media.url.takeIf { media.type in setOf("image", "gif") && trustedIrcMediaUrl(it) }
}

private fun mediaSource(media: IrcMedia, full: Boolean): MediaSource? {
    if (media.type !in setOf("image", "gif", "video")) return null
    ircKlipyGif(media.url)?.let { return MediaSource(it, full, IRC_GIF_MAX_BYTES) }
    if (full && media.type != "video") return media.url.takeIf(::trustedIrcMediaUrl)?.let {
        MediaSource(it, true, if (media.type == "gif") IRC_GIF_MAX_BYTES else FULL_IMAGE_BYTES)
    }
    return ircMediaThumbnailUrl(media)?.let { MediaSource(it, false,
        if (trustedIrcGifUrl(it)) IRC_GIF_MAX_BYTES else THUMBNAIL_BYTES) }
}

internal object IrcMediaAssets {
    private class Load(val source: MediaSource, val token: Long, val deadline: Long) {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        var watchdog: ScheduledFuture<*>? = null
        fun stop() {
            cancelled = true
            watchdog?.cancel(false)
            val current = connection ?: return
            // Screen removal and session reset run on the client thread; socket cleanup may block.
            Scheduling.schedule(Duration.ZERO) { current.disconnect() }
        }
    }
    private val lock = Any()
    private val cache = IrcMediaCache()
    private val queued = ArrayDeque<Load>()
    private val active = mutableSetOf<Load>()
    private var identity: String? = null
    @Volatile internal var generation = 0L
        private set
    val revision get() = synchronized(lock) { cache.revision }
    private fun source(media: IrcMedia, full: Boolean): MediaSource? {
        val primary = mediaSource(media, full) ?: return null
        if (media.type != "video" || ircKlipyGif(media.url) == null) return primary
        val status = synchronized(lock) {
            val now = System.nanoTime()
            cache.view(primary.key, now).status.takeUnless { it == IrcMediaAssetStatus.IDLE } ?:
                cache.view("${if (full) "thumbnail" else "full"}:${primary.uri.toASCIIString()}", now).status
        }
        val poster = ircKlipyPoster(media, status) ?: return primary
        return MediaSource(if (full) poster else ircDiscordThumbnail(poster) ?: poster, full,
            if (trustedIrcGifUrl(poster)) IRC_GIF_MAX_BYTES else if (full) FULL_IMAGE_BYTES else THUMBNAIL_BYTES)
    }

    fun view(media: IrcMedia, full: Boolean): IrcMediaAssetState {
        val source = source(media, full) ?: return IrcMediaAssetState(IrcMediaAssetStatus.UNSUPPORTED, detail = when (media.type) {
            "gif" -> if (full) "This GIF host is unsupported. Open it in your browser." else "Open to load GIF"
            "video" -> "Video playback is available in your browser."
            else -> "This media host or format is unsupported. Open it in your browser."
        })
        return synchronized(lock) { cache.view(source.key, System.nanoTime()) }
    }

    fun request(media: IrcMedia, full: Boolean) {
        val source = source(media, full) ?: return
        synchronized(lock) {
            val now = System.nanoTime()
            val token = cache.begin(source.key, now) ?: return
            if (queued.size >= 32) {
                cache.complete(source.key, token, IrcMediaAssetState(IrcMediaAssetStatus.FAILED, detail = "Media queue is full. Retry shortly."), System.nanoTime())
                return
            }
            val load = Load(source, token, now + 5_000_000_000L)
            queued.add(load)
            // DNS cannot be interrupted by HttpURLConnection. Expire its UI/cache token independently,
            // including time spent waiting for a slot, without freeing a still-running worker.
            load.watchdog = Scheduling.schedule(5.seconds) {
                synchronized(lock) {
                    if (cache.failExpired(source.key, token, load.deadline, System.nanoTime())) {
                        queued.remove(load)
                        load.stop()
                        startQueued()
                    }
                }
            }
            startQueued()
        }
    }

    fun retry(media: IrcMedia, full: Boolean) {
        source(media, full)?.let(::cancelSource)
        request(media, full)
    }
    fun cancel(media: IrcMedia, full: Boolean) {
        val source = source(media, full) ?: return
        if (full && !source.full) return // A viewer never owns the shared inline thumbnail.
        cancelSource(source)
    }
    private fun cancelSource(source: MediaSource) {
        synchronized(lock) {
            cache.cancel(source.key)
            val iterator = queued.iterator()
            while (iterator.hasNext()) {
                val load = iterator.next()
                if (load.source.key == source.key) { load.stop(); iterator.remove() }
            }
            active.filter { it.source.key == source.key }.forEach(Load::stop)
        }
    }

    fun reset(identity: String) {
        synchronized(lock) {
            if (this.identity == identity) return
            this.identity = identity
            generation++
            queued.forEach(Load::stop); queued.clear()
            active.forEach(Load::stop)
            cache.clear()
        }
    }

    // Keep cancelled active jobs counted until their finally block releases the download slot.
    private fun startQueued() {
        while (active.size < 2 && queued.isNotEmpty()) {
            val load = queued.removeFirst()
            active.add(load)
            Scheduling.schedule(Duration.ZERO) {
                val state = fetch(load)
                synchronized(lock) {
                    try { cache.complete(load.source.key, load.token, state, System.nanoTime()) }
                    finally { load.watchdog?.cancel(false); active.remove(load); startQueued() }
                }
            }
        }
    }

    private fun fetch(load: Load): IrcMediaAssetState {
        try {
            require(!load.cancelled)
            val deadline = load.deadline
            require(System.nanoTime() - deadline < 0)
            require(InetAddress.getAllByName(load.source.uri.host).let { it.isNotEmpty() && it.all(::publicIrcMediaAddress) })
            require(!load.cancelled && System.nanoTime() - deadline < 0)
            val connection = load.source.uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            load.connection = connection
            try {
                require(!load.cancelled)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp")
                connection.setRequestProperty("User-Agent", "Sky-Myce IRC media")
                val status = connection.responseCode
                if (status == 404 || status == 410) return IrcMediaAssetState(IrcMediaAssetStatus.UNAVAILABLE,
                    detail = "This preview is unavailable from its host. You can open the original link in your browser.")
                require(status == 200)
                val mime = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                require(mime in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/octet-stream"))
                val bytes = connection.inputStream.use {
                    readIrcMediaBytes(it, connection.contentLengthLong, deadline, load.source.maximumBytes) { load.cancelled }
                }
                require(!load.cancelled)
                // This exact provider GIF is a takedown banner, not the requested animation.
                if (unavailableIrcKlipyMedia(load.source.uri, bytes)) return IrcMediaAssetState(IrcMediaAssetStatus.UNAVAILABLE,
                    detail = "Klipy's GIF preview is unavailable. You can open the original link in your browser.")
                val format = imageFormat(bytes)
                require(mime == "application/octet-stream" || mime == "image/$format")
                val decodeDeadline = minOf(deadline, System.nanoTime() + 2_000_000_000L)
                val asset = if (format == "gif") decodeIrcGif(bytes, decodeDeadline, !load.source.full)
                    else decodeIrcImage(bytes, decodeDeadline, !load.source.full)
                if (load.cancelled) { asset?.release(); return IrcMediaAssetState(IrcMediaAssetStatus.IDLE) }
                return if (asset != null) IrcMediaAssetState(IrcMediaAssetStatus.READY, asset)
                    else IrcMediaAssetState(IrcMediaAssetStatus.FAILED, detail = "Image is unsupported, damaged, or exceeds safe limits. Retry or open in your browser.")
            } finally { connection.disconnect(); load.connection = null }
        } catch (_: Exception) {
            return IrcMediaAssetState(IrcMediaAssetStatus.FAILED, detail = "Media could not be loaded. Retry or open it in your browser.")
        }
    }
}
