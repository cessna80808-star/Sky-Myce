package me.mycellium.skymyce.features.instances.dungeons.friends

import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Codec
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.EncodedImageFormat
import io.github.humbleui.skija.ImageInfo
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.mycellium.mixin.IrcChatAccessor
import me.mycellium.skymyce.config.misc.GlobalChatConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.features.browser.BrowserLinks
import me.mycellium.skymyce.features.browser.InGameBrowser
import me.mycellium.skymyce.ui.MenuButton
import me.mycellium.skymyce.ui.MenuCanvas
import me.mycellium.skymyce.ui.MenuGraphics
import me.mycellium.skymyce.ui.MenuTexture
import me.mycellium.skymyce.ui.MenuTheme
import me.mycellium.skymyce.ui.SkijaMenuFonts
import me.mycellium.skymyce.ui.menuTextLines
import me.mycellium.skymyce.ui.menuTextRuns
import me.mycellium.skymyce.ui.menuTextWidth
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import org.w3c.dom.Node
import tech.thatgravyboat.skyblockapi.utils.Scheduling

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

internal const val IRC_GIF_MAX_BYTES = 6 * 1024 * 1024
private const val MAX_GIF_DIMENSION = 4096
private const val MAX_GIF_SOURCE_FRAMES = 2000
private const val MAX_GIF_FRAMES = 120
private const val MAX_GIF_FRAME_BYTES = 32L * 1024 * 1024
private const val MAX_GIF_WORKING_BYTES = 40L * 1024 * 1024

internal fun trustedIrcGifUrl(uri: URI): Boolean {
    if (!uri.scheme.equals("https", true) || uri.isOpaque || uri.userInfo != null || uri.fragment != null ||
        uri.port !in listOf(-1, 443) || uri.toASCIIString().length > 4096) return false
    if (uri.host?.lowercase() !in setOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com", "media.tenor.com", "media1.tenor.com", "static.klipy.com")) return false
    val path = uri.path ?: return false
    return path.endsWith(".gif", true) && path.none { it.isISOControl() || it == '\\' || it == '%' }
}

internal data class IrcGifFrame(val image: BufferedImage, val durationMillis: Long)

internal class IrcGif(val width: Int, val height: Int, val frames: List<IrcGifFrame>, val loopCount: Int) {
    private val cycleMillis = frames.sumOf { it.durationMillis }

    fun frameIndexAt(elapsedMillis: Long): Int {
        val elapsed = elapsedMillis.coerceAtLeast(0)
        val iterations = if (loopCount < 0) 1L else loopCount.toLong() + 1
        if (loopCount != 0 && elapsed >= cycleMillis * iterations) return frames.lastIndex
        var position = elapsed % cycleMillis
        for ((index, frame) in frames.withIndex()) {
            if (position < frame.durationMillis) return index
            position -= frame.durationMillis
        }
        return frames.lastIndex
    }

    fun frameAt(elapsedMillis: Long): IrcGifFrame = frames[frameIndexAt(elapsedMillis)]
}

private data class GifLayout(val width: Int, val height: Int, val frames: Int, val background: Int,
    val backgroundIndex: Int, val restorePrevious: Boolean)

// ImageIO allocates from descriptors and tolerates missing trailers; validate framing and limits first.
private fun gifLayout(bytes: ByteArray, deadline: Long): GifLayout {
    var position = 0
    fun timely() = require(System.nanoTime() - deadline < 0)
    fun byte(): Int {
        require(position < bytes.size)
        return bytes[position++].toInt() and 255
    }
    fun short(): Int = byte() or (byte() shl 8)
    fun skip(count: Int) { require(count <= bytes.size - position); position += count }
    fun blocks(): Int {
        var total = 0
        while (true) {
            timely()
            val count = byte()
            if (count == 0) return total
            skip(count)
            total += count
        }
    }
    require(bytes.size in 14..IRC_GIF_MAX_BYTES)
    require(String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
    position = 6
    val width = short(); val height = short()
    require(width in 1..MAX_GIF_DIMENSION && height in 1..MAX_GIF_DIMENSION)
    val packed = byte(); val backgroundIndex = byte(); byte()
    var background = 0
    if (packed and 128 != 0) {
        val colors = 1 shl ((packed and 7) + 1)
        require(backgroundIndex < colors && colors * 3 <= bytes.size - position)
        val offset = position + backgroundIndex * 3
        background = (255 shl 24) or ((bytes[offset].toInt() and 255) shl 16) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
        skip(colors * 3)
    }
    var frames = 0
    var restorePrevious = false
    while (true) {
        timely()
        when (byte()) {
            0x3b -> {
                require(frames > 0 && position == bytes.size)
                return GifLayout(width, height, frames, background, backgroundIndex, restorePrevious)
            }
            0x21 -> when (byte()) {
                0xf9 -> {
                    require(byte() == 4)
                    val control = byte()
                    require(control and 0xe0 == 0 && (control shr 2 and 7) <= 3 && control and 2 == 0)
                    restorePrevious = restorePrevious || (control shr 2 and 7) == 3
                    short(); byte(); require(byte() == 0)
                }
                0xff, 0xfe -> blocks()
                else -> throw IllegalArgumentException("Unsupported GIF extension")
            }
            0x2c -> {
                val left = short(); val top = short(); val frameWidth = short(); val frameHeight = short()
                require(frameWidth > 0 && frameHeight > 0 && left + frameWidth <= width && top + frameHeight <= height)
                frames++
                require(frames <= MAX_GIF_SOURCE_FRAMES)
                val descriptor = byte()
                require(descriptor and 0x18 == 0)
                if (descriptor and 128 != 0) skip(3 * (1 shl ((descriptor and 7) + 1)))
                require(byte() in 2..8 && blocks() > 0)
            }
            else -> throw IllegalArgumentException("Invalid GIF block")
        }
    }
}

private fun Node.child(name: String): Node? {
    var node = firstChild
    while (node != null) {
        if (node.nodeName == name) return node
        node = node.nextSibling
    }
    return null
}

private fun Node.attribute(name: String): String = attributes.getNamedItem(name).nodeValue

private fun copyGifImage(image: BufferedImage, width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        val graphics = it.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            if (width != image.width || height != image.height) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            }
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally { graphics.dispose() }
    }

internal fun decodeIrcGif(bytes: ByteArray, deadlineNanos: Long, thumbnail: Boolean = false): IrcGif? = me.mycellium.skymyce.features.dev.RuntimeProfiler.measure(me.mycellium.skymyce.features.dev.RuntimeProfiler.Area.IRC_MEDIA) { decodeIrcGifNow(bytes, deadlineNanos, thumbnail) }

private fun decodeIrcGifNow(bytes: ByteArray, deadlineNanos: Long, thumbnail: Boolean): IrcGif? {
    val deadline = minOf(deadlineNanos, System.nanoTime() + 2_000_000_000L)
    fun timely() = require(System.nanoTime() - deadline < 0)
    return try {
        val layout = gifLayout(bytes, deadline)
        val sourceFrames = layout.frames
        val stride = maxOf(1, (sourceFrames - 1 + MAX_GIF_FRAMES - 2) / (MAX_GIF_FRAMES - 1))
        val retainedFrames = if (sourceFrames == 1) 1 else (sourceFrames - 2) / stride + 2
        val canvasBytes = layout.width.toLong() * layout.height * 4
        // Composite every source frame; budget the decode and optional disposal canvas before stored snapshots.
        val workingCanvases = if (layout.restorePrevious) 3 else 2
        val storedLimit = minOf(MAX_GIF_FRAME_BYTES, MAX_GIF_WORKING_BYTES - canvasBytes * workingCanvases)
        require(storedLimit >= retainedFrames * 4L)
        val scale = minOf(1.0, kotlin.math.sqrt(storedLimit.toDouble() / (retainedFrames * canvasBytes)),
            if (thumbnail) minOf(IRC_MEDIA_THUMBNAIL_WIDTH.toDouble() / layout.width,
                IRC_MEDIA_THUMBNAIL_HEIGHT.toDouble() / layout.height) else 1.0)
        val outputWidth = (layout.width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (layout.height * scale).toInt().coerceAtLeast(1)
        require(outputWidth.toLong() * outputHeight * 4 * retainedFrames <= storedLimit)
        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) return null
        val reader = readers.next()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(): Int { timely(); return super.read() }
            override fun read(target: ByteArray, offset: Int, length: Int): Int { timely(); return super.read(target, offset, length) }
        }
        try {
            MemoryCacheImageInputStream(input).use { stream ->
                reader.setInput(stream, true, false)
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
                var warning = false
                reader.addIIOReadWarningListener { _, _ -> warning = true }
                val canvas = BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = canvas.createGraphics()
                var restore: BufferedImage? = null
                val frames = ArrayList<IrcGifFrame>(retainedFrames)
                var loopCount = -1
                var completed = false
                try {
                    for (index in 0 until sourceFrames) {
                        timely()
                        val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                        val descriptor = requireNotNull(metadata.child("ImageDescriptor"))
                        val left = descriptor.attribute("imageLeftPosition").toInt()
                        val top = descriptor.attribute("imageTopPosition").toInt()
                        val width = descriptor.attribute("imageWidth").toInt()
                        val height = descriptor.attribute("imageHeight").toInt()
                        val control = metadata.child("GraphicControlExtension")
                        val transparent = control?.attribute("transparentColorFlag") == "TRUE"
                        val background = if (transparent && control.attribute("transparentColorIndex").toInt() == layout.backgroundIndex) 0 else layout.background
                        if (index == 0) {
                            graphics.composite = AlphaComposite.Src
                            graphics.color = Color(background, true)
                            graphics.fillRect(0, 0, layout.width, layout.height)
                        }
                        var extension = metadata.child("ApplicationExtensions")?.firstChild
                        while (extension != null) {
                            if (extension.attribute("applicationID") == "NETSCAPE" && extension.attribute("authenticationCode") == "2.0") {
                                val data = (extension as IIOMetadataNode).userObject as ByteArray
                                require(data.size == 3 && data[0].toInt() == 1)
                                val count = (data[1].toInt() and 255) or ((data[2].toInt() and 255) shl 8)
                                require(loopCount == -1 || loopCount == count)
                                loopCount = count
                            }
                            extension = extension.nextSibling
                        }
                        val disposal = control?.attribute("disposalMethod") ?: "none"
                        if (disposal == "restoreToPrevious") {
                            val previous = restore ?: BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_ARGB).also { restore = it }
                            val previousGraphics = previous.createGraphics()
                            try { previousGraphics.composite = AlphaComposite.Src; previousGraphics.drawImage(canvas, 0, 0, null) }
                            finally { previousGraphics.dispose() }
                        }
                        val image = reader.read(index)
                        try {
                            timely()
                            require(!warning && image.width == width && image.height == height)
                            graphics.composite = AlphaComposite.SrcOver
                            graphics.drawImage(image, left, top, null)
                        } finally { image.flush() }
                        val delay = (control?.attribute("delayTime")?.toLong() ?: 0) * 10
                        val duration = if (delay == 0L) 100L else delay
                        if (index % stride == 0 || index == sourceFrames - 1) {
                            frames += IrcGifFrame(copyGifImage(canvas, outputWidth, outputHeight), duration)
                        } else {
                            frames[frames.lastIndex] = frames.last().let { it.copy(durationMillis = it.durationMillis + duration) }
                        }
                        when (disposal) {
                            "restoreToBackgroundColor" -> {
                                graphics.composite = AlphaComposite.Src
                                graphics.color = Color(background, true)
                                graphics.fillRect(left, top, width, height)
                            }
                            "restoreToPrevious" -> {
                                graphics.composite = AlphaComposite.Src
                                graphics.drawImage(restore, 0, 0, null)
                            }
                        }
                    }
                    timely()
                    IrcGif(outputWidth, outputHeight, frames, loopCount).also { completed = true }
                } finally {
                    graphics.dispose(); canvas.flush(); restore?.flush()
                    if (!completed) frames.forEach { it.image.flush() }
                }
            }
        } finally { reader.dispose() }
    } catch (_: Exception) { null }
}

/** Shared menu chrome surrounds native media composition; the existing decoded asset cache owns GIF frames. */
internal class IrcMediaViewerScreen(private val previous: Screen?, private val media: IrcMedia,
    private val sender: String, private val context: String, internal val messageKey: IrcKey? = null) : Screen(Component.literal("IRC media")) {
    private var texture = MenuTexture()
    private val chrome = MenuCanvas()
    private val chromeTexture = MenuTexture()
    private data class Header(val source: String, val available: Int, val color: Int, val size: Double,
                              val native: Boolean, val runs: List<MenuCanvas.Run>, val width: Double)
    private val headers = arrayOfNulls<Header>(5)
    private var detailText: String? = null
    private var detailWidth = -1
    private var detailColor = 0
    private var detailNative = false
    private var detailRuns = emptyList<List<MenuCanvas.Run>>()
    private var detailWidths = emptyList<Double>()
    private var paintedAsset: IrcGif? = null
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var elapsedNanos = 0L
    private var lastTickNanos = System.nanoTime()
    private var paused = !GlobalChatConfig.animateGifs
    private val previousChatScroll = if (previous is ChatScreen) (MC.instance.gui.chat as IrcChatAccessor).`skymyce$getScroll`() else null
    private var preference = GlobalChatConfig.animateGifs
    private val generation = IrcMediaAssets.generation
    private var browserDisabled = false
    private lateinit var pauseButton: MenuButton
    private lateinit var fitButton: MenuButton
    private lateinit var minusButton: MenuButton
    private lateinit var plusButton: MenuButton
    private lateinit var retryButton: MenuButton
    private val imageTop get() = 58
    private val imageBottom get() = (height - 76).coerceAtLeast(imageTop + 1)

    override fun init() {
        if (chromeTexture.prepareFrame()) chrome.close()
        if (!current()) return
        fun button(label: String, x: Int, y: Int, size: Int, action: () -> Unit): MenuButton =
            addRenderableWidget(MenuButton(Component.literal(label)) { action() }.apply { setRectangle(size, 20, x, y) })
        val start = (width - 250) / 2
        val row = height - 54
        fitButton = button("Fit", start, row, 52) { fit() }
        minusButton = button("−", start + 56, row, 32) { changeZoom(1 / 1.25) }
        plusButton = button("+", start + 92, row, 32) { changeZoom(1.25) }
        pauseButton = button(if (paused) "Play GIF" else "Pause GIF", start + 128, row, 66) {
            paused = !paused; lastTickNanos = System.nanoTime()
        }
        retryButton = button("Retry", start + 198, row, 52) {
            if (current()) { discardTexture(); elapsedNanos = 0; IrcMediaAssets.retry(media, true) }
        }
        button("Back", (width - 172) / 2, height - 28, 58) { onClose() }
        button("Open browser", (width - 172) / 2 + 62, height - 28, 110) { openBrowser() }
        lastTickNanos = System.nanoTime()
        if (current()) IrcMediaAssets.request(media, true)
    }

    private fun current(): Boolean = ircMediaViewerCurrent(generation, IrcMediaAssets.generation, media, messageKey,
        messageKey?.let(RelayMessages.chatStore::get), messageKey?.let(RelayMessages::currentIrcChannel) ?: true)

    private fun openBrowser() {
        if (!current()) return
        if (!minecraft.options.chatLinks().get()) { browserDisabled = true; return }
        browserDisabled = false
        val original = media.sourceUrl ?: media.url
        fun open() {
            if (BrowserLinks.supported(original.toString()) != null) InGameBrowser.open(this, original.toString())
            else {
                Util.getPlatform().openUri(original)
                if (minecraft.screen !== this) minecraft.setScreen(this)
            }
        }
        if (minecraft.options.chatLinksPrompt().get()) {
            minecraft.setScreen(ConfirmLinkScreen({ confirmed ->
                if (!current()) closeExpired()
                else {
                    if (confirmed && minecraft.options.chatLinks().get()) open()
                    else minecraft.setScreen(this)
                }
            }, original.toString(), false))
        } else open()
    }

    private fun fit() { zoom = 1.0; panX = 0.0; panY = 0.0 }
    private fun changeZoom(factor: Double) { zoom = (zoom * factor).coerceIn(0.25, 16.0); clampPan() }
    private fun clampPan() {
        val asset = paintedAsset ?: return
        val scale = minOf((width - 32).coerceAtLeast(1).toDouble() / asset.width,
            (imageBottom - imageTop).toDouble() / asset.height) * zoom
        val edgeX = ((asset.width * scale - (width - 32)) / 2).coerceAtLeast(0.0)
        val edgeY = ((asset.height * scale - (imageBottom - imageTop)) / 2).coerceAtLeast(0.0)
        panX = panX.coerceIn(-edgeX, edgeX); panY = panY.coerceIn(-edgeY, edgeY)
    }

    override fun extractRenderState(output: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (chromeTexture.prepareFrame()) chrome.close()
        chrome.begin()
        val graphics = MenuGraphics(chrome, mouseX, mouseY, width, height)
        graphics.fill(0, 0, width, height, MenuTheme.alpha(MenuTheme.PANEL, 95))
        if (!current()) {
            discardTexture()
            centerLine(graphics, 4, "This media is no longer available.", height / 2, MenuTheme.alpha(MenuTheme.MUTED))
            renderChrome(output)
            return
        }
        centerLine(graphics, 0, "${media.name ?: media.type.uppercase()} · $sender", 10, MenuTheme.alpha(MenuTheme.TEXT), 12.0)
        centerLine(graphics, 1, context.replace('\n', ' '), 27, MenuTheme.alpha(MenuTheme.MUTED), 9.0)
        centerLine(graphics, 2, media.url.host.orEmpty(), 42, MenuTheme.alpha(MenuTheme.ACCENT), 9.0)
        MenuTheme.box(graphics, 15, imageTop - 1, (width - 30).coerceAtLeast(1), imageBottom - imageTop + 2,
            MenuTheme.CARD)
        val state = IrcMediaAssets.view(media, true)
        val asset = state.asset
        if (asset !== paintedAsset) {
            discardTexture()
            paintedAsset = asset
            elapsedNanos = 0
            fit()
        }
        val now = System.nanoTime()
        if (GlobalChatConfig.animateGifs != preference) {
            preference = GlobalChatConfig.animateGifs
            paused = !preference
        }
        if (!paused && asset != null) elapsedNanos += (now - lastTickNanos).coerceIn(0, 250_000_000)
        lastTickNanos = now
        if (asset != null) {
            clampPan()
        } else {
            val detail = when (state.status) {
                IrcMediaAssetStatus.IDLE -> "Media session changed. Return to chat and open it again."
                IrcMediaAssetStatus.LOADING -> "Loading media…"
                else -> state.detail ?: "Media is unavailable. Open it in your browser."
            }
            drawDetail(graphics, detail)
        }
        val animated = asset?.frames?.size?.let { it > 1 } == true
        pauseButton.active = animated
        pauseButton.message = Component.literal(if (paused) "Play GIF" else "Pause GIF")
        fitButton.active = asset != null; minusButton.active = asset != null && zoom > 0.25; plusButton.active = asset != null && zoom < 16
        retryButton.active = state.status == IrcMediaAssetStatus.FAILED
        val status = if (browserDisabled) "Enable Chat Links in Minecraft settings to open the browser." else if (media.type == "video" && !animated) "Video · Open browser to play" else if (asset != null)
            "${asset.width} × ${asset.height} · ${(zoom * 100).roundToInt()}% of fit · Scroll to zoom, drag to pan"
            else if (state.status == IrcMediaAssetStatus.LOADING) "Downloading safely…" else "Open browser for the original"
        centerLine(graphics, 3, status, height - 69, MenuTheme.alpha(MenuTheme.MUTED), 9.0)
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks)
        renderChrome(output)
        if (asset != null) {
            val scale = minOf((width - 32).coerceAtLeast(1).toDouble() / asset.width,
                (imageBottom - imageTop).toDouble() / asset.height) * zoom
            val drawWidth = (asset.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (asset.height * scale).roundToInt().coerceAtLeast(1)
            val x = ((width - drawWidth) / 2.0 + panX).toFloat()
            val y = (imageTop + (imageBottom - imageTop - drawHeight) / 2.0 + panY).toFloat()
            val index = asset.frameIndexAt(elapsedNanos / 1_000_000)
            output.enableScissor(16, imageTop, width - 16, imageBottom)
            output.pose().pushMatrix()
            try {
                output.pose().translate(x, y)
                texture.render(output, asset.frames[index].image, drawWidth, drawHeight, index.toLong())
            } finally { output.pose().popMatrix(); output.disableScissor() }
        }
    }

    private fun renderChrome(output: GuiGraphicsExtractor) {
        val scale = minOf(me.mycellium.skymyce.ui.AutoGraphics.quality.rasterScale, 2560.0 / width.coerceAtLeast(1), 1440.0 / height.coerceAtLeast(1))
        chromeTexture.render(output, chrome, width, height, scale)
    }
    private fun centerLine(graphics: MenuGraphics, slot: Int, text: String, y: Int, color: Int, size: Double = 10.0) {
        val available = (width - 24).coerceAtLeast(1)
        val native = SkijaMenuFonts.active
        var header = headers[slot]
        if (header == null || header.source != text || header.available != available || header.color != color ||
            header.size != size || header.native != native) {
            val shown = ircMediaTextEllipsis(text, available.toDouble(), size)
            header = Header(text, available, color, size, native, listOf(MenuCanvas.Run(shown, color)), MenuCanvas.textWidth(shown, size = size))
            headers[slot] = header
        }
        graphics.styledText(header.runs, (width - header.width) / 2, y.toDouble(), size)
    }
    private fun drawDetail(graphics: MenuGraphics, text: String) {
        val available = (width - 64).coerceAtLeast(1)
        val color = MenuTheme.alpha(MenuTheme.TEXT)
        if (detailText != text || detailWidth != available || detailColor != color || detailNative != SkijaMenuFonts.active) {
            detailText = text; detailWidth = available; detailColor = color; detailNative = SkijaMenuFonts.active
            val lines = menuTextLines(Component.literal(text), available)
            detailRuns = lines.map { menuTextRuns(it, color) }; detailWidths = lines.map { menuTextWidth(it) }
        }
        val lineHeight = MenuCanvas.lineHeight()
        val top = imageTop + (imageBottom - imageTop - detailRuns.size * lineHeight) / 2
        detailRuns.forEachIndexed { index, runs ->
            graphics.styledText(runs, (width - detailWidths[index]) / 2, (top + index * lineHeight).toDouble())
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (event.button() == 0 && event.y() in imageTop.toDouble()..imageBottom.toDouble() && paintedAsset != null) {
            panX += dx; panY += dy; clampPan(); return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
        if (y in imageTop.toDouble()..imageBottom.toDouble() && paintedAsset != null && vertical != 0.0) {
            changeZoom(1.2.pow(vertical.coerceIn(-4.0, 4.0))); return true
        }
        return super.mouseScrolled(x, y, horizontal, vertical)
    }

    private fun discardTexture() { texture.close(); texture = MenuTexture(); paintedAsset = null }
    override fun removed() {
        texture.close(); paintedAsset = null
        chrome.close(); chromeTexture.close()
        headers.fill(null); detailText = null; detailRuns = emptyList(); detailWidths = emptyList()
        if (generation == IrcMediaAssets.generation) IrcMediaAssets.cancel(media, true)
        super.removed()
    }
    private fun closeExpired() {
        if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen()
        minecraft.setScreen(null)
    }
    override fun tick() { if (!current()) closeExpired() else super.tick() }
    override fun onClose() {
        if (!current()) closeExpired()
        else {
            val returned = if (previous is ChatScreen) minecraft.gui.chat.restoreChatScreen() ?: previous else previous
            minecraft.setScreen(returned)
            if (returned is ChatScreen && previousChatScroll != null) {
                // ChatScreen.init resets scrolling; restore the captured position after reopening it.
                (minecraft.gui.chat as IrcChatAccessor).`skymyce$setScroll`(previousChatScroll)
                minecraft.gui.chat.scrollChat(0)
            }
        }
    }
    override fun isPauseScreen() = false
}

/** Truncate using the same glyph advances as the shared renderer, without splitting surrogate pairs. */
internal fun ircMediaTextEllipsis(text: String, available: Double, size: Double = 10.0): String {
    if (MenuCanvas.textWidth(text, size = size) <= available) return text
    val suffix = "…"
    val limit = available - MenuCanvas.textWidth(suffix, size = size)
    if (limit < 0) return ""
    var width = 0.0
    var end = 0
    while (end < text.length) {
        val codepoint = text.codePointAt(end)
        val advance = MenuCanvas.textWidth(codepoint, false, false, size)
        if (width + advance > limit) break
        width += advance; end += Character.charCount(codepoint)
    }
    return text.substring(0, end).trimEnd() + suffix
}
