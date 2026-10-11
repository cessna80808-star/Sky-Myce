package me.mycellium.skymyce.features.instances.dungeons.friends

import org.w3c.dom.Node
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

internal const val IRC_GIF_MAX_BYTES = 6 * 1024 * 1024
private const val MAX_GIF_DIMENSION = 1024
private const val MAX_GIF_FRAMES = 120
private const val MAX_GIF_FRAME_BYTES = 32L * 1024 * 1024
private const val MAX_GIF_WORKING_BYTES = 40L * 1024 * 1024

internal fun trustedIrcGifUrl(uri: URI): Boolean {
    if (!uri.scheme.equals("https", true) || uri.isOpaque || uri.userInfo != null || uri.fragment != null ||
        uri.port !in listOf(-1, 443) || uri.toASCIIString().length > 4096) return false
    if (uri.host?.lowercase() !in setOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com", "media.tenor.com")) return false
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

private data class GifLayout(val width: Int, val height: Int, val frames: Int, val background: Int, val backgroundIndex: Int)

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
    val canvasBytes = width.toLong() * height * 4
    val storedLimit = minOf(MAX_GIF_FRAME_BYTES, MAX_GIF_WORKING_BYTES - canvasBytes * 3)
    var frames = 0
    while (true) {
        timely()
        when (byte()) {
            0x3b -> {
                require(frames > 0 && position == bytes.size)
                return GifLayout(width, height, frames, background, backgroundIndex)
            }
            0x21 -> when (byte()) {
                0xf9 -> {
                    require(byte() == 4)
                    val control = byte()
                    require(control and 0xe0 == 0 && (control shr 2 and 7) <= 3 && control and 2 == 0)
                    short(); byte(); require(byte() == 0)
                }
                0xff, 0xfe -> blocks()
                else -> throw IllegalArgumentException("Unsupported GIF extension")
            }
            0x2c -> {
                val left = short(); val top = short(); val frameWidth = short(); val frameHeight = short()
                require(frameWidth > 0 && frameHeight > 0 && left + frameWidth <= width && top + frameHeight <= height)
                frames++
                require(frames <= MAX_GIF_FRAMES && frames * canvasBytes <= storedLimit)
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

private fun copyGifImage(image: BufferedImage): BufferedImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
    val graphics = it.createGraphics()
    try { graphics.composite = AlphaComposite.Src; graphics.drawImage(image, 0, 0, null) } finally { graphics.dispose() }
}

internal fun decodeIrcGif(bytes: ByteArray, deadlineNanos: Long): IrcGif? {
    val deadline = minOf(deadlineNanos, System.nanoTime() + 2_000_000_000L)
    fun timely() = require(System.nanoTime() - deadline < 0)
    return try {
        val layout = gifLayout(bytes, deadline)
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
                val frames = ArrayList<IrcGifFrame>(layout.frames)
                var loopCount = -1
                try {
                    for (index in 0 until layout.frames) {
                        timely()
                        val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                        val descriptor = requireNotNull(metadata.child("ImageDescriptor"))
                        val left = descriptor.attribute("imageLeftPosition").toInt()
                        val top = descriptor.attribute("imageTopPosition").toInt()
                        val width = descriptor.attribute("imageWidth").toInt()
                        val height = descriptor.attribute("imageHeight").toInt()
                        val control = metadata.child("GraphicControlExtension")
                        val transparent = control?.attribute("transparentColorFlag") == "TRUE"
                        val background = if (transparent && control?.attribute("transparentColorIndex")?.toInt() == layout.backgroundIndex) 0 else layout.background
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
                        timely()
                        require(!warning && image.width == width && image.height == height)
                        graphics.composite = AlphaComposite.SrcOver
                        graphics.drawImage(image, left, top, null)
                        image.flush()
                        val delay = (control?.attribute("delayTime")?.toLong() ?: 0) * 10
                        frames += IrcGifFrame(copyGifImage(canvas), if (delay == 0L) 100L else delay)
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
                    IrcGif(layout.width, layout.height, frames, loopCount)
                } finally { graphics.dispose(); canvas.flush(); restore?.flush() }
            }
        } finally { reader.dispose() }
    } catch (_: Exception) { null }
}
