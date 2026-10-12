package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal fun observationHash(source: String, raw: String): String {
    val normalized = raw.trim().removePrefix("\uFEFF").trim().removeSurrounding("{", "}").lowercase().replace("-", "")
    require(normalized.matches(Regex("[a-f0-9]{32}")) && normalized.toSet().size > 1)
    val family = if (source in setOf("windows-smbios", "linux-dmi", "macos-platform")) "physical-uuid" else source
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("Sky-Myce device observation v1\n$family\n$normalized".toByteArray()))
}

/** One existing OS identifier, hashed locally. Missing/changed/shared metadata never limits a connection. */
object DeviceMetadata {
    private var cached: JsonObject? = null
    private var attempted = false
    @Synchronized fun observe(): JsonObject? {
        if (attempted) return cached?.deepCopy()
        attempted = true
        cached = runCatching {
            val name = System.getProperty("os.name").lowercase()
            val platform = when { name.contains("win") -> "windows"; name.contains("linux") -> "linux"; name.contains("mac") || name.contains("darwin") -> "macos"; else -> "other" }
            val sources = when (platform) {
                "windows" -> listOf("windows-smbios")
                "linux" -> listOf("linux-dmi", "linux-machine-id", "linux-dbus-machine-id")
                "macos" -> listOf("macos-platform")
                else -> emptyList()
            }
            val observation = sources.firstNotNullOfOrNull { source -> runCatching {
                val raw = read(source) ?: return@runCatching null
                source to observationHash(source, raw)
            }.getOrNull() } ?: software(platform)
            JsonObject().apply { addProperty("identifier", observation.second); addProperty("source", observation.first); addProperty("platform", platform) }
        }.getOrNull()
        return cached?.deepCopy()
    }
    private fun software(platform: String): Pair<String, String> {
        val home = System.getenv(if (platform == "windows") "USERPROFILE" else "HOME")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        val directory = Path.of(home).resolve(".sky-myce/community")
        Files.createDirectories(directory)
        val file = AtomicJsonFile(directory.resolve("device-observation.json"), 1024)
        val saved = file.read()?.asJsonObject?.get("id")?.asString
        val id = saved?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString().also {
            file.write(JsonObject().apply { addProperty("id", it) })
        }
        return "software-installation" to observationHash("software-installation", id)
    }
    private fun read(source: String): String? = runCatching {
        val paths = when (source) {
            "linux-dmi" -> listOf("/sys/class/dmi/id/product_uuid", "/sys/devices/virtual/dmi/id/product_uuid")
            "linux-machine-id" -> listOf("/etc/machine-id")
            "linux-dbus-machine-id" -> listOf("/var/lib/dbus/machine-id")
            else -> emptyList()
        }
        if (paths.isNotEmpty()) return@runCatching paths.firstNotNullOfOrNull { path -> runCatching {
            val bytes = Files.newInputStream(Path.of(path)).use { it.readNBytes(4097) }; require(bytes.size <= 4096)
            bytes.toString(Charsets.UTF_8).also { observationHash(source, it) }
        }.getOrNull() }
        val command = if (source == "windows-smbios") listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "(Get-CimInstance -ClassName Win32_ComputerSystemProduct -ErrorAction Stop).UUID")
        else listOf("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(16385) } }
        try {
            process.outputStream.close()
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
            val bytes = output.get(1, TimeUnit.SECONDS); require(bytes.size <= 16384)
            val value = bytes.toString(Charsets.UTF_8)
            if (source == "macos-platform") Regex("\"IOPlatformUUID\"\\s*=\\s*\"([a-fA-F0-9-]+)\"").find(value)?.groupValues?.get(1) else value.trim()
        } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close(); output.cancel(true) }
    }.getOrNull()
}
