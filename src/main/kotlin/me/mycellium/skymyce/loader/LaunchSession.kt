package me.mycellium.skymyce.loader

import java.util.Locale
import java.util.UUID

/** One authorization session per JVM; never persisted or handed to an updater/restarted JVM. */
internal object LaunchSession {
    val id: String = UUID.randomUUID().toString()
    val platform: String = launchPlatform(System.getProperty("os.name"))
    @Volatile var authorized = false
        private set

    fun accept() { authorized = true }
}

internal fun launchPlatform(name: String): String = when {
    name.lowercase(Locale.ROOT).startsWith("linux") -> "linux"
    name.lowercase(Locale.ROOT).startsWith("windows") -> "windows"
    name.lowercase(Locale.ROOT).let { it.startsWith("mac") || it.startsWith("darwin") } -> "macos"
    else -> "other"
}
