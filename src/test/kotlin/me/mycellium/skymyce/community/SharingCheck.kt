package me.mycellium.skymyce.community

import me.mycellium.skymyce.features.digest.DigestRngEntry
import me.mycellium.skymyce.features.digest.DigestRngHistory

private fun checkSharingModes() {
    check(SharingMode.ANONYMOUS.policy == SharingPolicy() && SharingMode.ON.policy == SharingPolicy(publicName = true))
    check(SharingCategory.entries.none(SharingMode.OFF.policy::allows))
    SharingMode.entries.forEach { check(SharingMode.of(it.policy) == it) }
    // A partial server policy (left by an older client) reads as Off rather than re-enabling a withheld category.
    check(SharingMode.of(SharingPolicy(rng = false)) == SharingMode.OFF && SharingMode.of(SharingPolicy(economy = false, publicName = true)) == SharingMode.OFF)

    fun migrate(sharing: String?, digest: String? = null): Pair<SharingMode?, com.google.gson.JsonObject> {
        val json = com.google.gson.JsonParser.parseString(buildString {
            append("{")
            sharing?.let { append("\"Community Sharing\":{$it}") }
            digest?.let { if (sharing != null) append(","); append("\"General\":{\"Daily Digest\":{$it}}") }
            append("}")
        }).asJsonObject
        return migratedSharingMode(json) to json
    }
    check(migrate(null).first == null) // Fresh installs keep the On (anon) default.
    check(migrate(""""mode":"OFF","enabled":false""").first == null) // Already migrated.
    check(migrate(""""enabled":true,"legacySharingMigrated":true""").first == SharingMode.ANONYMOUS)
    check(migrate(""""enabled":false,"legacySharingMigrated":true""").first == SharingMode.OFF)
    for (category in listOf("dungeons", "economy", "rng", "activity", "leaderboards"))
        check(migrate(""""$category":false,"publicName":true,"legacySharingMigrated":true""").first == SharingMode.OFF) { "$category opt-out was not kept" }
    check(migrate(""""publicName":true,"legacySharingMigrated":true""").first == SharingMode.ON)
    // The Daily Digest toggles count only before they were folded into Community Sharing.
    check(migrate(""""legacySharingMigrated":false""", """"publicRngName":true""").first == SharingMode.ON)
    check(migrate(""""publicName":false,"legacySharingMigrated":true""", """"publicRngName":true""").first == SharingMode.ANONYMOUS) {
        "A stale Daily Digest name choice re-exposed a name that was later turned off"
    }
    check(migrate(""""legacySharingMigrated":false""", """"shareRng":false""").first == SharingMode.OFF)
    check(migrate(null, """"shareRng":true,"publicRngName":false""").first == SharingMode.ANONYMOUS)
    val (_, consumed) = migrate(""""enabled":true,"rng":true,"publicName":true,"syncedPolicy":"x"""", """"shareRng":true,"news":true""")
    check(consumed.getAsJsonObject("Community Sharing").keySet() == setOf("syncedPolicy")) // Synced state stays; legacy toggles go.
    check(consumed.getAsJsonObject("General").getAsJsonObject("Daily Digest").keySet() == setOf("news"))
}

fun checkCommunitySharing() {
    checkSharingModes()
    val defaults = SharingPolicy()
    check(SharingCategory.entries.all(defaults::allows) && !defaults.publicName)
    val disabled = defaults.copy(enabled = false)
    check(SharingCategory.entries.none(disabled::allows))
    val categories = defaults.copy(rng = false, economy = false)
    check(!categories.allows(SharingCategory.RNG) && !categories.allows(SharingCategory.ECONOMY) && categories.allows(SharingCategory.DUNGEONS))
    // An old local default cannot re-enable a server opt-out. A new account can retain an explicit public-name preference.
    check(categories.restrictivelyMerge(defaults).rng == false)
    check(!defaults.restrictivelyMerge(defaults.copy(publicName = true)).publicName)
    check(defaults.restrictivelyMerge(defaults.copy(publicName = true), initialPublicName = true).publicName)
    val savedAccount = SharingSnapshot(categories, 4, emptyMap())
    check(!desiredSharingPolicy(savedAccount, defaults, setOf("rng", "economy"), firstAccountSync = true).rng) {
        "Enabling edits from another account overwrote an existing account's saved opt-out"
    }
    check(desiredSharingPolicy(savedAccount, defaults, setOf("rng"), firstAccountSync = false).rng) {
        "A fresh explicit edit on the synchronized account could not enable a category"
    }
    check(!desiredSharingPolicy(savedAccount, defaults.copy(dungeons = false), emptySet(), firstAccountSync = true).dungeons)
    val namedAccount = savedAccount.copy(policy = categories.copy(publicName = true))
    check(desiredSharingPolicy(namedAccount, defaults, emptySet(), firstAccountSync = true).publicName) {
        "A new device's untouched anonymous default replaced an existing account's public-name preference"
    }
    check(!desiredSharingPolicy(namedAccount, defaults, setOf("publicName"), firstAccountSync = true).publicName)
    check(SharingPolicy.parse(categories.json()) == categories)
    check(SharingPolicy.parse(categories.json().apply { addProperty("rng", "false") }) == null)
    check(SharingPolicy.parse(categories.json().apply { remove("enabled") }) == null)
    val state = defaults.json().apply { addProperty("revision", 4); add("notBefore", com.google.gson.JsonObject()) }
    check(SharingSnapshot.parse(state)?.revision == 4L)
    check(SharingSnapshot.parse(state.deepCopy().apply { addProperty("revision", 4.5) }) == null)

    val history = DigestRngHistory()
    val personal = DigestRngEntry("a".repeat(32), "NECRON_HANDLE", "dungeon", 1000, "Alice")
    val shared = personal.copy(id = "b".repeat(32), community = true)
    val other = personal.copy(id = "c".repeat(32), community = true)
    history.restore(listOf(personal, shared, other), 100, 2000)
    history.removeCommunity(setOf(shared.id))
    check(history.entries.toSet() == setOf(personal, other))
    check(!history.add(shared, 100, 2000)) // A delayed subscription response cannot restore a privacy removal.
    history.removeCommunity()
    check(history.entries == listOf(personal)) // Deleting the public feed never deletes personal local history.

}
