package me.mycellium.skymyce.features.instances.dungeons.friends

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream

private fun imageFixture(format: String, width: Int = 256, height: Int = 128): ByteArray {
    val image = BufferedImage(width, height, if (format == "jpeg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 0xffef2311.toInt())
    image.setRGB(width - 1, height - 1, 0xffabcdef.toInt())
    val bytes = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName(format).next()
    try {
        MemoryCacheImageOutputStream(bytes).use { stream -> writer.output = stream; writer.write(image) }
    } finally { writer.dispose(); image.flush() }
    return bytes.toByteArray()
}

private fun pngWidth(bytes: ByteArray, width: Int): ByteArray = bytes.clone().also {
    for (index in 0..3) it[16 + index] = (width ushr (24 - index * 8)).toByte()
    val crc = CRC32().apply { update(it, 12, 17) }.value
    for (index in 0..3) it[29 + index] = (crc ushr (24 - index * 8)).toByte()
}

fun checkIrcMedia() {
    for (host in listOf("cdn.discordapp.com", "media.discordapp.net", "media.giphy.com", "i.giphy.com",
        "media.tenor.com", "i.imgur.com", "images.unsplash.com")) check(trustedIrcMediaUrl(URI("https://$host/path/image.png?signature=abc%2Fdef")))
    check(trustedIrcMediaUrl(URI("https://I.IMGUR.COM:443/attachment.jpeg")))
    for (url in listOf("http://i.imgur.com/a.png", "https://i.imgur.com:444/a.png", "https://user@i.imgur.com/a.png",
        "https://i.imgur.com/a.png#fragment", "https://i.imgur.com.evil.invalid/a.png", "https://i.imgur.com./a.png",
        "https://127.0.0.1/a.png", "https://imgur.com/gallery/a.png", "https://tenor.com/view/a.gif",
        "https://i.imgur.com/%00a.png", "https://i.imgur.com/%0Aa.png", "https://i.imgur.com/%255Ca.png",
        "https://i.imgur.com/%5Ca.png", "https://i.imgur.com/a.png?signature=%0A", "https://i.imgur.com/")) {
        check(!trustedIrcMediaUrl(URI(url))) { url }
    }
    for (address in listOf("0.0.0.0", "10.2.3.4", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.255",
        "192.168.0.1", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.1", "198.18.1.1", "198.19.1.1",
        "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1",
        "fd00::1", "ff02::1", "2001:db8::1", "2002:a00:1::", "2001:0:1::")) {
        check(!publicIrcMediaAddress(InetAddress.getByName(address))) { address }
    }
    for (address in listOf("1.1.1.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111")) {
        check(publicIrcMediaAddress(InetAddress.getByName(address))) { address }
    }
    val discord = URI("https://cdn.discordapp.com/attachments/1/2/name.gif?ex=abc&hm=def%2Fghi&width=900&height=800&format=webp")
    val derivative = requireNotNull(ircDiscordThumbnail(discord))
    check(derivative.host == "media.discordapp.net" && derivative.rawPath == discord.rawPath)
    check(derivative.rawQuery == "ex=abc&hm=def%2Fghi&width=64&height=32&format=png")
    check(ircDiscordThumbnail(URI("https://media.tenor.com/a.gif")) == null)
    check(ircMediaThumbnailUrl(IrcMedia("gif", "gif", discord)) == derivative)
    val gif = URI("https://media.tenor.com/a.gif")
    check(ircMediaThumbnailUrl(IrcMedia("gif", "gif", gif)) == null)
    check(ircMediaThumbnailUrl(IrcMedia("gif", "gif", gif, gif)) == null)
    val poster = URI("https://i.imgur.com/poster.png")
    check(ircMediaThumbnailUrl(IrcMedia("gif", "gif", gif, poster)) == poster)
    check(ircMediaThumbnailUrl(IrcMedia("video", "video", URI("https://cdn.discordapp.com/a.mp4"))) == null)
    check(ircMediaThumbnailUrl(IrcMedia("video", "video", URI("https://cdn.discordapp.com/a.mp4"), poster)) == poster)

    // A modal browser confirmation may return after a session change, deletion, or media replacement.
    val viewerMedia = IrcMedia("image", "image", poster)
    val message = GlobalChatMessage("a".repeat(32), "minecraft", "Tester", "image", 0, false, null,
        scope = "party", room = "party-test", model = 1, media = listOf(viewerMedia))
    fun current(generation: Long = 1, item: GlobalChatMessage? = message, channel: Boolean = true) =
        ircMediaViewerCurrent(1, generation, viewerMedia, message.key, item, channel)
    check(current())
    check(!current(generation = 2))
    check(!current(item = null))
    check(!current(channel = false))
    check(!current(item = message.copy(deleted = true)))
    check(!current(item = message.copy(media = emptyList())))
    check(!current(item = message.copy(media = listOf(viewerMedia.copy(url = URI("https://i.imgur.com/replacement.png"))))))
    check(!current(item = message.copy(scope = "global")))
    check(current(item = message.copy(text = "text edit", revision = 2)))
    check(ircMediaViewerCurrent(1, 1, viewerMedia, null, null, false))
    check(!ircMediaViewerCurrent(1, 2, viewerMedia, null, null, false))

    fun decode(bytes: ByteArray, thumbnail: Boolean = false) = decodeIrcImage(bytes, System.nanoTime() + 2_000_000_000L, thumbnail)
    val png = imageFixture("png")
    val original = requireNotNull(decode(png))
    check(original.width == 256 && original.height == 128 && original.frames.size == 1)
    check(original.frames.single().image.getRGB(0, 0) == 0xffef2311.toInt())
    check(original.frames.single().image.getRGB(20, 20) == 0)
    val thumbnail = requireNotNull(decode(png, true))
    check(thumbnail.width == 64 && thumbnail.height == 32)
    val jpeg = imageFixture("jpeg")
    val jpegImage = requireNotNull(decode(jpeg))
    check(jpegImage.width == 256 && jpegImage.height == 128)
    val tiny = requireNotNull(decode(imageFixture("png", 1, 1), true))
    check(tiny.width == 1 && tiny.height == 1)
    check(decodeIrcImage(png, System.nanoTime() - 1, false) == null)
    check(decode(ByteArray(12 * 1024 * 1024 + 1)) == null)
    check(decode(ByteArray(2 * 1024 * 1024 + 1), true) == null)
    check(decode("not an image".toByteArray()) == null)
    check(decode("GIF89a".toByteArray(), true) == null)
    check(decode(pngWidth(png, 4097)) == null)
    for (length in listOf(0, 8, 24, png.size - 1, png.size / 2)) check(decode(png.copyOf(length)) == null)
    check(decode(png.clone().also { it[29] = (it[29].toInt() xor 1).toByte() }) == null)
    check(decode(jpeg.copyOf(jpeg.size - 1)) == null)

    // Caller limits also apply independently of a declared Content-Length.
    check(ByteArrayInputStream(png).use {
        readIrcMediaBytes(it, png.size.toLong(), System.nanoTime() + 2_000_000_000L, png.size)
    }.contentEquals(png))
    check(runCatching { ByteArrayInputStream(png).use {
        readIrcMediaBytes(it, -1, System.nanoTime() + 2_000_000_000L, png.size - 1)
    } }.isFailure)

    fun asset() = IrcGif(4, 4, listOf(IrcGifFrame(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), 100)), -1)
    fun ready() = IrcMediaAssetState(IrcMediaAssetStatus.READY, asset())
    val cache = IrcMediaCache(maximumBytes = 128, maximumEntries = 3, ttlNanos = 100)
    val a = requireNotNull(cache.begin("a", 0)); check(cache.begin("a", 1) == null)
    check(cache.view("a", 2).status == IrcMediaAssetStatus.LOADING)
    check(cache.complete("a", a, ready(), 3)); check(cache.decodedBytes == 64L)
    val b = requireNotNull(cache.begin("b", 4)); check(cache.complete("b", b, ready(), 5))
    cache.view("a", 6)
    val c = requireNotNull(cache.begin("c", 7)); check(cache.complete("c", c, ready(), 8))
    check(cache.view("b", 9).status == IrcMediaAssetStatus.IDLE && cache.decodedBytes == 128L)
    check(cache.view("a", 106).status == IrcMediaAssetStatus.IDLE)
    check(cache.view("c", 108).status == IrcMediaAssetStatus.IDLE && cache.decodedBytes == 0L)
    val old = requireNotNull(cache.begin("same", 200))
    cache.clear()
    val current = requireNotNull(cache.begin("same", 201))
    check(current != old && !cache.complete("same", old, ready(), 202))
    check(cache.view("same", 203).status == IrcMediaAssetStatus.LOADING)
    check(cache.complete("same", current, ready(), 204))
    cache.cancel("same"); check(cache.size == 0 && cache.decodedBytes == 0L)
    val bounded = IrcMediaCache(maximumBytes = 1, maximumEntries = 2)
    val large = requireNotNull(bounded.begin("large", 0))
    bounded.complete("large", large, ready(), 1)
    check(bounded.view("large", 2).status == IrcMediaAssetStatus.FAILED && bounded.decodedBytes == 0L)
    bounded.clear()
    check(bounded.begin("one", 3) != null && bounded.begin("two", 4) != null && bounded.begin("three", 5) == null)
    check(!bounded.complete("gone", 123, ready(), 6) && bounded.size == 2)
    // A queued or DNS-blocked worker times out independently; its late completion cannot revive it.
    val stalled = IrcMediaCache()
    val stalledToken = requireNotNull(stalled.begin("dns", 0))
    check(!stalled.failExpired("dns", stalledToken, 5, 4))
    check(stalled.view("dns", 4).status == IrcMediaAssetStatus.LOADING)
    check(stalled.failExpired("dns", stalledToken, 5, 5))
    check(stalled.view("dns", 6).status == IrcMediaAssetStatus.FAILED)
    check(!stalled.complete("dns", stalledToken, ready(), 7))
    check(!stalled.failExpired("dns", stalledToken, 5, 8))
    stalled.cancel("dns")
    val replacement = requireNotNull(stalled.begin("dns", 9))
    check(!stalled.failExpired("dns", stalledToken, 5, 10))
    check(stalled.view("dns", 10).status == IrcMediaAssetStatus.LOADING)
    check(stalled.complete("dns", replacement, ready(), 11))
    check(!stalled.failExpired("dns", replacement, 12, 12))
    println("IRC media checks passed")
}

fun main() = checkIrcMedia()
