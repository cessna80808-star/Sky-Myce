package me.mycellium.skymyce.features.instances.dungeons.friends

/** A disconnected private channel stays selected: failed sends never become public chat. */
internal class IrcMode {
    private var account: java.util.UUID? = null

    fun account(current: java.util.UUID) {
        if (account != null && account != current) selected = false
        account = current
    }
    var selected = false
        set(value) { field = value; if (!value) { global = false; party = false } }
    var global = false
        private set
    var party = false
        private set
    fun selectParty() { selected = true; global = false; party = true }

    fun command(command: String): Boolean {
        val normalized = command.trim().lowercase().split(spaces)
        if (normalized.size == 2 && normalized[0] == "chat" && normalized[1] in setOf("irc", "sky", "skymyce", "global")) {
            selected = true
            global = true
            party = false
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

    private companion object { val spaces = Regex("\\s+") }
}
