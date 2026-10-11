package me.mycellium.skymyce.features.instances.dungeons.friends

import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
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

internal enum class IrcMediaAssetStatus { IDLE, LOADING, READY, FAILED, UNSUPPORTED }
internal data class IrcMediaAssetState(val status: IrcMediaAssetStatus, val asset: IrcGif? = null, val detail: String? = null)

internal fun ircMediaViewerCurrent(capturedGeneration: Long, generation: Long, media: IrcMedia,
    key: IrcKey?, message: GlobalChatMessage?, currentChannel: Boolean): Boolean = capturedGeneration == generation &&
    (key == null || currentChannel && message != null && message.key == key && !message.deleted && media in message.media)

private val mediaHosts = setOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com",
    "media.tenor.com", "i.imgur.com", "images.unsplash.com")
private const val FULL_IMAGE_BYTES = 12 * 1024 * 1024
private const val THUMBNAIL_BYTES = 2 * 1024 * 1024
private const val THUMBNAIL_WIDTH = 192
private const val THUMBNAIL_HEIGHT = 144
private const val MAX_IMAGE_DIMENSION = 4096
private const val MAX_IMAGE_PIXELS = 16 * 1024 * 1024
private const val MAX_IMAGE_DECODED_BYTES = 32L * 1024 * 1024

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
    } + listOf("width=$THUMBNAIL_WIDTH", "format=png")
    return URI("https://media.discordapp.net${uri.rawPath}?${query.joinToString("&")}")
}

private fun imageFormat(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
    bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.size - 2] == (-1).toByte() && bytes.last() == 0xd9.toByte() -> "jpeg"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
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
internal fun decodeIrcImage(bytes: ByteArray, deadlineNanos: Long, thumbnail: Boolean): IrcGif? {
    val deadline = minOf(deadlineNanos, System.nanoTime() + 2_000_000_000L)
    fun timely() = require(System.nanoTime() - deadline < 0)
    return try {
        require(bytes.size in 1..if (thumbnail) THUMBNAIL_BYTES else FULL_IMAGE_BYTES)
        timely()
        val format = imageFormat(bytes)
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
                var sample = if (thumbnail) maxOf(1, width / THUMBNAIL_WIDTH, height / THUMBNAIL_HEIGHT) else 1
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
                    val scale = minOf(1.0, THUMBNAIL_WIDTH.toDouble() / decoded.width, THUMBNAIL_HEIGHT.toDouble() / decoded.height)
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

private fun IrcGif.decodedBytes(): Long = frames.sumOf { it.image.width.toLong() * it.image.height * 4 }
private fun IrcGif.release() = frames.forEach { it.image.flush() }

/** Pure cache: load tokens survive clear, so stale completions cannot reappear after an account change. */
internal class IrcMediaCache(private val maximumBytes: Long = 48L * 1024 * 1024,
    private val maximumEntries: Int = 64, private val ttlNanos: Long = 120_000_000_000L) {
    private data class Entry(val token: Long, var state: IrcMediaAssetState, var touched: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private var sequence = 0L
    internal val decodedBytes get() = entries.values.sumOf { it.state.asset?.decodedBytes() ?: 0L }
    internal val size get() = entries.size
    private fun remove(key: String) { entries.remove(key)?.state?.asset?.release() }
    private fun expire(now: Long) = entries.filterValues {
        it.state.status != IrcMediaAssetStatus.LOADING && now - it.touched >= ttlNanos
    }.keys.toList().forEach(::remove)
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
    val explicit = media.thumbnailUrl?.takeIf {
        trustedIrcMediaUrl(it) && (!it.path.endsWith(".gif", true) ||
            it.host.lowercase() == "media.discordapp.net" && it.query.orEmpty().split('&').any { part -> part.equals("format=png", true) })
    }
    return explicit?.let { ircDiscordThumbnail(it) ?: it } ?: (if (media.type != "video") ircDiscordThumbnail(media.url) else null)
        ?: media.url.takeIf { media.type == "image" && trustedIrcMediaUrl(it) && !it.path.endsWith(".gif", true) }
}

private fun mediaSource(media: IrcMedia, full: Boolean): MediaSource? {
    if (media.type !in setOf("image", "gif", "video")) return null
    if (full && media.type != "video") return media.url.takeIf(::trustedIrcMediaUrl)?.let {
        MediaSource(it, true, if (media.type == "gif") IRC_GIF_MAX_BYTES else FULL_IMAGE_BYTES)
    }
    return ircMediaThumbnailUrl(media)?.let { MediaSource(it, false, THUMBNAIL_BYTES) }
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

    fun view(media: IrcMedia, full: Boolean): IrcMediaAssetState {
        val source = mediaSource(media, full) ?: return IrcMediaAssetState(IrcMediaAssetStatus.UNSUPPORTED, detail = when (media.type) {
            "gif" -> if (full) "This GIF host is unsupported. Open it in your browser." else "Open to load GIF"
            "video" -> "Video playback is available in your browser."
            else -> "This media host or format is unsupported. Open it in your browser."
        })
        return synchronized(lock) { cache.view(source.key, System.nanoTime()) }
    }

    fun request(media: IrcMedia, full: Boolean) {
        val source = mediaSource(media, full) ?: return
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

    fun retry(media: IrcMedia, full: Boolean) { cancel(media, full); request(media, full) }
    fun cancel(media: IrcMedia, full: Boolean) {
        val source = mediaSource(media, full) ?: return
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
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif")
                connection.setRequestProperty("User-Agent", "Sky-Myce IRC media")
                require(connection.responseCode == 200)
                val mime = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                require(mime in setOf("image/png", "image/jpeg", "image/gif", "application/octet-stream"))
                val bytes = connection.inputStream.use {
                    readIrcMediaBytes(it, connection.contentLengthLong, deadline, load.source.maximumBytes) { load.cancelled }
                }
                require(!load.cancelled)
                val format = imageFormat(bytes)
                require(mime == "application/octet-stream" || mime == "image/$format")
                val decodeDeadline = minOf(deadline, System.nanoTime() + 2_000_000_000L)
                val asset = if (format == "gif" && load.source.full) decodeIrcGif(bytes, decodeDeadline)
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
