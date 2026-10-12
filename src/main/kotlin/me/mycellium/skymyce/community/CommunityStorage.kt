package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import me.mycellium.skymyce.utils.AtomicJsonFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.*
import java.util.EnumSet

internal fun restrictCommunityPath(path: Path) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"))
    else {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: error("Private session storage unavailable")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
        acl.acl = listOf(entry.build())
    }
}

/** Refresh credentials are private runtime data, never resources in the distributed JAR. */
internal class CommunityStorage(directory: Path, uuid: String) {
    private val file: AtomicJsonFile
    init {
        require(uuid.matches(Regex("[a-f0-9]{32}")))
        Files.createDirectories(directory); restrictCommunityPath(directory)
        file = AtomicJsonFile(directory.resolve("$uuid.json"), 16384)
    }
    fun read(): JsonObject? = file.read()?.asJsonObject?.takeIf {
        val token = it["refreshToken"]?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
        token?.matches(Regex("[A-Za-z0-9_-]{43}")) == true && it["refreshExpiresAt"]?.asLong?.let { expiry -> expiry > System.currentTimeMillis() } == true
    }
    fun write(json: JsonObject) { file.write(json); restrictCommunityPath(file.path) }
    fun clear() = write(JsonObject())
}
