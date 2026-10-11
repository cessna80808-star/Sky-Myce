package me.mycellium.skymyce.features.instances.dungeons.friends

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream

private data class GifFixtureFrame(val pixels: IntArray, val width: Int, val left: Int = 0,
    val disposal: String = "doNotDispose", val delay: Int = 5, val transparent: Boolean = false)

private fun gifFixture(width: Int, height: Int, frames: List<GifFixtureFrame>, loopCount: Int? = 0): ByteArray {
    val colors = IndexColorModel(8, 4, byteArrayOf(0, -1, 0, 0), byteArrayOf(0, 0, 0, -1), byteArrayOf(0, 0, -1, 0), 0)
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    val bytes = ByteArrayOutputStream()
    try {
        MemoryCacheImageOutputStream(bytes).use { output ->
            writer.output = output
            val stream = writer.getDefaultStreamMetadata(writer.defaultWriteParam)
            val streamTree = stream.getAsTree("javax_imageio_gif_stream_1.0") as IIOMetadataNode
            val logical = streamTree.getElementsByTagName("LogicalScreenDescriptor").item(0) as IIOMetadataNode
            logical.setAttribute("logicalScreenWidth", width.toString()); logical.setAttribute("logicalScreenHeight", height.toString())
            stream.setFromTree("javax_imageio_gif_stream_1.0", streamTree)
            writer.prepareWriteSequence(stream)
            for ((index, frame) in frames.withIndex()) {
                val image = BufferedImage(frame.width, frame.pixels.size / frame.width, BufferedImage.TYPE_BYTE_INDEXED, colors)
                for ((pixel, color) in frame.pixels.withIndex()) image.raster.setSample(pixel % frame.width, pixel / frame.width, 0, color)
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), writer.defaultWriteParam)
                val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                val descriptor = tree.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                descriptor.setAttribute("imageLeftPosition", frame.left.toString())
                val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                control.setAttribute("disposalMethod", frame.disposal)
                control.setAttribute("transparentColorFlag", if (frame.transparent) "TRUE" else "FALSE")
                control.setAttribute("transparentColorIndex", "0")
                control.setAttribute("delayTime", frame.delay.toString())
                if (index == 0 && loopCount != null) {
                    val extensions = IIOMetadataNode("ApplicationExtensions")
                    extensions.appendChild(IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, loopCount.toByte(), (loopCount shr 8).toByte())
                    })
                    tree.appendChild(extensions)
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree)
                writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
            }
            writer.endWriteSequence()
        }
    } finally { writer.dispose() }
    return bytes.toByteArray()
}

fun checkIrcGif() {
    for (host in listOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com", "media.tenor.com")) {
        check(trustedIrcGifUrl(URI("https://$host/path/name.GIF?signature=abc%2Fdef")))
    }
    check(trustedIrcGifUrl(URI("https://MEDIA.TENOR.COM:443/test.gif")))
    for (url in listOf("http://media.tenor.com/test.gif", "https://media.tenor.com.evil.invalid/test.gif",
        "https://tenor.com/view/test.gif", "https://giphy.com/test.gif", "https://media.tenor.com:444/test.gif",
        "https://user@media.tenor.com/test.gif", "https://media.tenor.com/test.gif#fragment",
        "https://media.tenor.com/test.png", "https://media.tenor.com/%00test.gif", "https://media.tenor.com/%0atest.gif",
        "https://media.tenor.com/%255ctest.gif", "https://media.tenor.com/%5ctest.gif", "https://media.tenor.com./test.gif")) {
        check(!trustedIrcGifUrl(URI(url))) { url }
    }

    fun decode(bytes: ByteArray) = decodeIrcGif(bytes, System.nanoTime() + 2_000_000_000L)
    val sequence = gifFixture(2, 1, listOf(GifFixtureFrame(intArrayOf(1, 1), 2, delay = 3),
        GifFixtureFrame(intArrayOf(0, 2), 2, delay = 7, transparent = true)))
    var closed = false
    val ownedStream = object : ByteArrayInputStream(sequence) {
        override fun close() { closed = true; super.close() }
    }
    ownedStream.use {
        check(readIrcMediaBytes(it, sequence.size.toLong(), System.nanoTime() + 2_000_000_000L).contentEquals(sequence))
        check(!closed)
    }
    check(closed)
    check(ByteArrayInputStream(sequence).use { readIrcMediaBytes(it, -1, System.nanoTime() + 2_000_000_000L) }.contentEquals(sequence))
    fun rejectedStream(bytes: ByteArray, length: Long, deadline: Long = System.nanoTime() + 2_000_000_000L,
                       cancelled: () -> Boolean = { false }) = runCatching {
        ByteArrayInputStream(bytes).use { readIrcMediaBytes(it, length, deadline, cancelled = cancelled) }
    }.isFailure
    check(rejectedStream(sequence, IRC_GIF_MAX_BYTES.toLong() + 1))
    check(rejectedStream(ByteArray(IRC_GIF_MAX_BYTES + 1), -1))
    check(rejectedStream(sequence, sequence.size.toLong() - 1))
    check(rejectedStream(sequence, sequence.size.toLong() + 1))
    check(rejectedStream(sequence, -2))
    check(rejectedStream(sequence, -1, System.nanoTime() - 1))
    check(rejectedStream(sequence, -1, cancelled = { true }))
    var cancelledAtEof = false
    val eofStream = object : ByteArrayInputStream(sequence) {
        override fun read(target: ByteArray, offset: Int, length: Int): Int = super.read(target, offset, length).also {
            if (it < 0) cancelledAtEof = true
        }
    }
    check(runCatching {
        eofStream.use { readIrcMediaBytes(it, -1, System.nanoTime() + 2_000_000_000L) { cancelledAtEof } }
    }.isFailure)
    val gif = requireNotNull(decode(sequence))
    check(gif.width == 2 && gif.height == 1 && gif.frames.size == 2 && gif.loopCount == 0)
    check(gif.frames[0].image.getRGB(0, 0) == 0xffff0000.toInt())
    check(gif.frames[1].image.getRGB(0, 0) == 0xffff0000.toInt())
    check(gif.frames[1].image.getRGB(1, 0) == 0xff0000ff.toInt())
    check(gif.frames.map { it.durationMillis } == listOf(30L, 70L))
    check(gif.frameIndexAt(-1) == 0 && gif.frameIndexAt(29) == 0 && gif.frameIndexAt(30) == 1)
    check(gif.frameIndexAt(99) == 1 && gif.frameIndexAt(100) == 0 && gif.frameAt(130) === gif.frames[1])
    for (loops in listOf(null, 1)) {
        val finite = requireNotNull(decode(gifFixture(2, 1, listOf(GifFixtureFrame(intArrayOf(1, 1), 2),
            GifFixtureFrame(intArrayOf(2, 2), 2)), loops)))
        check(finite.frameIndexAt(if (loops == null) 100 else 200) == 1)
        if (loops != null) check(finite.frameIndexAt(100) == 0)
    }
    check(requireNotNull(decode(gifFixture(1, 1, listOf(GifFixtureFrame(intArrayOf(1), 1, delay = 0))))).frames.single().durationMillis == 100L)

    for (disposal in listOf("restoreToBackgroundColor", "restoreToPrevious")) {
        val composite = requireNotNull(decode(gifFixture(2, 1, listOf(
            GifFixtureFrame(intArrayOf(1, 1), 2, transparent = true),
            GifFixtureFrame(intArrayOf(2), 1, disposal = disposal, transparent = true),
            GifFixtureFrame(intArrayOf(3), 1, left = 1, transparent = true)))))
        check(composite.frames[1].image.getRGB(0, 0) == 0xff0000ff.toInt())
        check(composite.frames[2].image.getRGB(0, 0) == if (disposal == "restoreToPrevious") 0xffff0000.toInt() else 0)
        check(composite.frames[2].image.getRGB(1, 0) == 0xff00ff00.toInt())
    }
    val clear = requireNotNull(decode(gifFixture(2, 1, listOf(GifFixtureFrame(intArrayOf(0, 1), 2, transparent = true)))))
    check(clear.frames.single().image.getRGB(0, 0) == 0)
    check(decodeIrcGif(sequence, System.nanoTime() - 1) == null)
    check(decode(ByteArray(IRC_GIF_MAX_BYTES + 1)) == null)
    check(decode("not a gif".toByteArray()) == null)
    for (length in listOf(0, 6, 13, sequence.size - 1, sequence.size / 2)) check(decode(sequence.copyOf(length)) == null)
    val tooWide = sequence.clone().also { it[6] = 1; it[7] = 4 }
    check(decode(tooWide) == null)
    check(decode(gifFixture(1, 1, List(121) { GifFixtureFrame(intArrayOf(1), 1) })) == null)
    check(decode(gifFixture(1024, 1024, List(9) { GifFixtureFrame(intArrayOf(1), 1) })) == null)
    println("IRC GIF checks passed")
}

fun main() = checkIrcGif()
