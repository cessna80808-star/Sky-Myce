package me.mycellium.skymyce.features.social

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Check dimensions and exact bounded inflation before a native/GUI decoder sees untrusted PNG bytes. */
fun validateCapePng(bytes: ByteArray) {
    require(bytes.size in 45..262144 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
    val data = ByteBuffer.wrap(bytes); data.position(8)
    var header = false; var ended = false; var channels = 0; var pixelsEnded = false; var sawPixels = false
    val compressed = ByteArrayOutputStream()
    while (data.remaining() >= 12 && !ended) {
        val size = data.int; require(size >= 0 && size <= data.remaining() - 8)
        val typeBytes = ByteArray(4).also(data::get); val type = typeBytes.toString(Charsets.US_ASCII)
        require(typeBytes.all { it.toInt() in 65..90 || it.toInt() in 97..122 } && type !in setOf("acTL", "fcTL", "fdAT"))
        val payload = ByteArray(size).also(data::get); val crc = data.int.toLong() and 0xffffffffL
        require(CRC32().run { update(typeBytes); update(payload); value } == crc)
        require(header || type == "IHDR")
        when (type) {
            "IHDR" -> {
                require(!header && size == 13); val h = ByteBuffer.wrap(payload)
                require(h.int == 64 && h.int == 32 && h.get().toInt() == 8)
                channels = when (h.get().toInt()) { 2 -> 3; 6 -> 4; else -> error("RGB/RGBA cape required") }
                require(h.get().toInt() == 0 && h.get().toInt() == 0 && h.get().toInt() == 0); header = true
            }
            "IDAT" -> { require(!pixelsEnded); sawPixels = true; compressed.write(payload) }
            "IEND" -> { require(size == 0 && compressed.size() > 0); ended = true }
            else -> { require(type.length == 4 && type[0].isLowerCase()); if (sawPixels) pixelsEnded = true }
        }
    }
    require(ended && data.remaining() == 0)
    val expected = 32 * (1 + 64 * channels)
    val inflater = Inflater()
    try {
        inflater.setInput(compressed.toByteArray()); val decoded = ByteArray(expected + 1); var count = 0
        while (!inflater.finished() && count < decoded.size) {
            val read = inflater.inflate(decoded, count, decoded.size - count)
            require(read > 0); count += read
        }
        require(inflater.finished() && inflater.remaining == 0 && count == expected)
        repeat(32) { require(decoded[it * (1 + 64 * channels)].toInt() in 0..4) }
    } finally { inflater.end() }
}
