package me.mycellium.skymyce.community

import me.mycellium.skymyce.features.digest.DigestRngEntry
import me.mycellium.skymyce.features.digest.DigestRngHistory

fun checkCommunitySharing() {
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
