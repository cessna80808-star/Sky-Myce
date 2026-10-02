package me.mycellium.skymyce.features.instances.dungeons.friends

/** A disconnected private channel stays selected: failed sends never become public chat. */
internal class IrcMode {
    var selected = false
        set(value) { field = value; if (!value) global = false }
    var global = false
        private set
    fun command(command: String): Boolean {
        val normalized = command.trim().lowercase().split(Regex("\\s+"))
        if (normalized.size == 2 && normalized[0] == "chat" && normalized[1] in setOf("irc", "sky", "skymyce", "global")) {
            selected = true
            global = normalized[1] != "irc"
            return false
        }
        if (normalized.size == 2 && normalized[0] == "chat" &&
            normalized[1] in setOf("p", "party", "a", "all", "g", "guild", "o", "officer", "c", "co", "coop", "co-op")) selected = false
        return true
    }
    fun chat(text: String, send: (String) -> Unit): Boolean {
        if (!selected) return true
        send(text)
        return false
    }
}
