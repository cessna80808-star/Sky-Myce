package me.mycellium.skymyce.features.instances.dungeons.routing

internal fun dungeonMinibossProfile(name: String?) = when (name) {
    "Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas" -> true
    else -> false
}

internal fun dungeonStarPlayer(uuidVersion: Int, local: Boolean, invisible: Boolean, profile: String? = null) =
    !local && (dungeonMinibossProfile(profile) || uuidVersion == 2 && !invisible)

/** NoammAddons StarMobESP: resolve the health label to its real entity, not the armor stand's feet. */
internal fun <T> dungeonStarMob(labelId: Int, name: String, entity: (Int) -> T?,
    eligible: (T) -> Boolean, beneath: () -> Iterable<T>): T? {
    if ('✯' !in name || !name.trimEnd().endsWith('❤')) return null
    // Withermancers have two projectile entities between their body and their health label.
    val offset = if (name.contains("Withermancer", ignoreCase = true)) 3 else 1
    return entity(labelId - offset)?.takeIf(eligible) ?: beneath().firstOrNull(eligible)
}
