package me.mycellium.skymyce.features.instances.dungeons.routing

private val minibossProfiles = setOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")
private val minibossHealthLabel = Regex("""(?:\[Lv\s*\d+\]\s*)?(?:${minibossProfiles.joinToString("|", transform = Regex::escape)})\s+\d[\d.,]*[kKmMbB]?(?:/\d[\d.,]*[kKmMbB]?)?❤""")

internal fun dungeonMinibossProfile(name: String?) = name != null && name in minibossProfiles

internal fun dungeonStarPlayer(uuidVersion: Int, local: Boolean, invisible: Boolean, profile: String? = null) =
    !local && (dungeonMinibossProfile(profile) || uuidVersion == 2 && !invisible)

internal fun dungeonStarLabel(name: String?) = name?.contains('✯') == true

/** A complete starred health label can identify a miniboss before its body profile arrives. */
internal fun dungeonMinibossLabel(name: String?) = dungeonStarLabel(name) &&
    minibossHealthLabel.matches(name!!.substringAfterLast('✯').trim())

/** Profile eligibility never supplies the star that routing requires. */
internal fun dungeonRouteStarTarget(starred: Boolean, miniboss: Boolean, throughMobs: Boolean, throughMinibosses: Boolean) =
    starred && if (miniboss) throughMinibosses else throughMobs

/** NoammAddons StarMobESP: resolve the health label to its real entity, not the armor stand's feet. */
internal fun <T> dungeonStarMob(labelId: Int, name: String, entity: (Int) -> T?,
    eligible: (T) -> Boolean, beneath: () -> Iterable<T>): T? {
    if (!dungeonStarLabel(name) || !name.trimEnd().endsWith('❤')) return null
    // Withermancers have two projectile entities between their body and their health label.
    val offset = if (name.contains("Withermancer", ignoreCase = true)) 3 else 1
    return entity(labelId - offset)?.takeIf(eligible) ?: beneath().firstOrNull(eligible)
}
