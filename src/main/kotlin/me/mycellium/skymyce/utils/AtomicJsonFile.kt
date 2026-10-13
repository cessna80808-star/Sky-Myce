package me.mycellium.skymyce.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Synchronous file operations: use from the background scheduler, never a GUI/render callback. */
class AtomicJsonFile(val path: Path, private val maxBytes: Int = 65536, private val serializeNulls: Boolean = false) {
    var damaged = false
        private set

    fun read(): JsonElement? = try {
        if (!Files.exists(path)) null else {
            require(Files.size(path) <= maxBytes)
            Files.newBufferedReader(path).use { source ->
                val reader = com.google.gson.stream.JsonReader(source).apply { strictness = com.google.gson.Strictness.STRICT }
                JsonParser.parseReader(reader).also { require(it.isJsonObject && reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) }
            }
        }
    } catch (_: Exception) { damaged = true; null }

    fun preserveOriginal(suffix: String = "before-upgrade") {
        val backup = path.resolveSibling("${path.fileName}.$suffix")
        if (Files.exists(path) && !Files.exists(backup)) Files.copy(path, backup)
    }

    @Synchronized fun write(json: JsonElement) {
        val bytes = (if (serializeNulls) GsonBuilder().serializeNulls().create() else Gson()).toJson(json).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes)
        Files.createDirectories(path.parent)
        if (damaged) { preserveOriginal("corrupt-${System.currentTimeMillis()}"); damaged = false }
        val temporary = Files.createTempFile(path.parent, ".skymyce-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** The Digest's coalescing save pattern, shared by the new immutable preference/history snapshots. */
class StateWriter<T : Any>(private val write: (T) -> Unit, private val schedule: (() -> Unit) -> Unit,
                           private val failed: (Exception) -> Unit = {}) {
    private val latest = AtomicReference<T?>()
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var written: T? = null
    fun submit(value: T) { latest.set(value); start() }
    private fun start() {
        if (closed.get() || !running.compareAndSet(false, true)) return
        schedule {
            var attempted: T? = null
            try { synchronized(lock) {
                if (!closed.get()) {
                    attempted = latest.get()
                    attempted?.takeIf { it != written }?.let { write(it); written = it }
                }
            } } catch (error: Exception) { failed(error) }
            finally { running.set(false); if (latest.get() != attempted && !closed.get()) start() }
        }
    }
    fun flush() = synchronized(lock) {
        closed.set(true)
        latest.get()?.takeIf { it != written }?.let { write(it); written = it }
    }
}
