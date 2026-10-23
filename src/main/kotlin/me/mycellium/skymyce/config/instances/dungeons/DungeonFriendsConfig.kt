package me.mycellium.skymyce.config.instances.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonAvailability
import me.mycellium.skymyce.features.instances.dungeons.friends.SavedDungeonParty
import me.mycellium.skymyce.features.instances.dungeons.friends.FRIEND_FLOORS
import me.mycellium.skymyce.features.instances.dungeons.friends.parseDungeonClass
import me.mycellium.skymyce.features.instances.dungeons.friends.relayUri
import me.mycellium.skymyce.features.instances.dungeons.friends.currentRelayUrl
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DungeonFriendsConfig : CategoryKt("Dungeon Friends") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Scan Dungeon Friends")
        description = TranslatableValue.literal("Load a missing friend cache on joining; only Party Finder refreshes online status, at most once per minute on opening")
    }

    init {
        button {
            title = "Dungeon Friend & LFG Assistant"
            text = "Open"
            description = "Online dungeon friends, floor stats and quick invites (/skymyce pf)"
            onClick { MC.instance.execute { navigateMenu(DungeonFriendsScreen()) } }
        }
    }
}

/** User-entered friend preferences live alongside the existing dungeon tracker data. */
object DungeonFriendsSettings {
    private val file = SkyMyce.configPath.resolve("dungeon_friends.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    var messageTemplate = "Hey {name}, want to play {class} on {floor}?"
        private set
    var secondaryClasses: Map<String, Set<DungeonClass>> = emptyMap()
        private set
    var availability = DungeonAvailability()
        private set
    var titleNotifications = false
        private set
    var lastParty: SavedDungeonParty? = null
        private set
    var relayUrl = currentRelayUrl(null)
        private set
    var error: String? = null
        private set
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        if (!Files.exists(file)) return
        try {
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            val template = json.get("messageTemplate")?.asString ?: messageTemplate
            require(template.isNotBlank() && template.none { it < ' ' })
            val secondary = json.getAsJsonObject("secondaryClasses")?.entrySet()?.associate { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
                name.lowercase() to value.asJsonArray.map { parseDungeonClass(it.asString) ?: error("Invalid class") }.toSet()
            }.orEmpty()
            val available = json.get("availability")?.takeUnless { it.isJsonNull }
                ?.let { gson.fromJson(it, DungeonAvailability::class.java) } ?: DungeonAvailability()
            require(available.floor in FRIEND_FLOORS && available.classes.all { it in DungeonClass.entries })
            require(available.maxPbMillis == null || available.maxPbMillis in 1..59999999)
            val relay = currentRelayUrl(json.get("relayUrl")?.asString)
            require(relay.isBlank() || relayUri(relay) != null)
            val savedParty = json.get("lastParty")?.takeUnless { it.isJsonNull }
                ?.let { gson.fromJson(it, SavedDungeonParty::class.java) }
            savedParty?.let { saved ->
                require(saved.leader.matches(Regex("[A-Za-z0-9_]{1,16}")))
                require(saved.members.size in 1..5 && saved.members.all { it.matches(Regex("[a-z0-9_]{1,16}")) })
                require(saved.leader.lowercase() in saved.members)
                require(saved.classes.keys.all { it in saved.members } && saved.classes.values.all { it in DungeonClass.entries })
                require(saved.floor == null || saved.floor in FRIEND_FLOORS)
                require(saved.savedAt >= 0)
            }
            messageTemplate = template
            secondaryClasses = secondary
            availability = available
            titleNotifications = json.get("titleNotifications")?.asBoolean ?: false
            lastParty = savedParty
            relayUrl = relay.trim()
        } catch (failure: Exception) {
            error = "Could not read dungeon_friends.json. Repair or rename it before saving."
            SkyMyce.logger.warn("Could not read dungeon friend preferences; original file preserved", failure)
        }
    }

    fun save(
        template: String = messageTemplate,
        classes: Map<String, Set<DungeonClass>> = secondaryClasses,
        available: DungeonAvailability = availability,
        savedParty: SavedDungeonParty? = lastParty,
        relay: String = relayUrl,
        titles: Boolean = titleNotifications,
    ): Boolean {
        load()
        if (error != null) return false
        if (template.isBlank() || template.any { it < ' ' || it == '§' } || template.length > 220) return false
        if (available.floor !in FRIEND_FLOORS) return false
        if (available.maxPbMillis != null && available.maxPbMillis !in 1..59999999) return false
        if (relay.isNotBlank() && relayUri(relay) == null) return false
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "dungeon_friends", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { writer ->
                    gson.toJson(mapOf("messageTemplate" to template, "secondaryClasses" to classes,
                        "availability" to available, "lastParty" to savedParty, "relayUrl" to relay.trim(), "titleNotifications" to titles), writer)
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            messageTemplate = template
            secondaryClasses = classes
            availability = available
            titleNotifications = titles
            lastParty = savedParty
            relayUrl = relay.trim()
            return true
        } catch (failure: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend preferences", failure)
            return false
        }
    }
}
