package me.mycellium.skymyce.features.instances.dungeons.splits

/**
 * Every milestone the splitter understands. Boss-phase lines are exact server messages from the NoammAddons
 * `runSplits.json` data (CC0 1.0, data branch 3c7b32c4); how each one is detected is documented on [splitTrigger].
 * Saved records refer to these by enum name, so existing names must not be renamed.
 */
enum class SplitMilestone(val title: String) {
    START("Start"),
    BLOOD_OPEN("Blood Open"),
    BLOOD_CLEAR("Watcher Clear"),
    SCORE_300("300 Score"),
    BOSS_ENTRY("Boss Entry"),
    BONZO_FIRST("Bonzo 1"),
    SCARF_UNDEADS("Undeads"),
    GUARDIANS("Guardians"),
    PROFESSOR("Professor"),
    THORN_DIALOGUE("Dialogue"),
    LIVID_DIALOGUE("Dialogue"),
    LIVID_KILL("Livid"),
    TERRACOTTAS("Terracottas"),
    GIANTS("Giants"),
    MAXOR("Maxor"),
    STORM("Storm"),
    S1("S1"),
    S2("S2"),
    S3("S3"),
    /** Ends when the Core entrance opens. Saved as `CORE` by 2.7.12, which is read back as this milestone. */
    S4("S4"),
    GOLDOR("Goldor"),
    NECRON("Necron"),
    RELICS("Relics"),
    END("End"),
}

/**
 * One floor's split layout. [chain] runs from START to END; each later entry ends one section that starts at the entry
 * before it, so section durations always add up to the run time. [markers] are timed from the run start but are not
 * sections, because they can happen before or after other milestones (300 score may come before or after boss entry).
 * Increase [version] whenever a boundary changes so records with different boundaries are never combined.
 */
data class SplitDefinition(
    val floor: String,
    val version: Int,
    val chain: List<SplitMilestone>,
    val markers: List<SplitMilestone> = listOf(SplitMilestone.SCORE_300),
    private val titles: Map<SplitMilestone, String> = emptyMap(),
    /** Totals over consecutive sections, such as all terminals; shown and tracked, never added to the theoretical PB. */
    val groups: List<SplitGroup> = emptyList(),
) {
    init {
        require(chain.first() == SplitMilestone.START && chain.last() == SplitMilestone.END && chain.distinct().size == chain.size)
        require(markers.none { it in chain })
        require(groups.all { chain.indexOf(it.from) in 0 until chain.indexOf(it.to) })
    }
    val sections: List<SplitSection> = chain.zipWithNext { from, to -> SplitSection(to, from) }
    fun title(milestone: SplitMilestone) = titles[milestone] ?: milestone.title
    val applicable: Set<SplitMilestone> = (chain + markers).toSet()
}

/** The sections from just after [from] up to and including [to], timed as one block. */
data class SplitGroup(val title: String, val from: SplitMilestone, val to: SplitMilestone)

private val terms = SplitGroup("Terms", SplitMilestone.STORM, SplitMilestone.S4)

private val clear = listOf(SplitMilestone.START, SplitMilestone.BLOOD_OPEN, SplitMilestone.BLOOD_CLEAR, SplitMilestone.BOSS_ENTRY)

private fun floors(number: Int, boss: List<SplitMilestone>, endTitle: String, extra: Map<SplitMilestone, String> = emptyMap()) =
    listOf("F$number", "M$number").map { SplitDefinition(it, 1, clear + boss + SplitMilestone.END, titles = extra + (SplitMilestone.END to endTitle)) }

private val f7Boss = listOf(SplitMilestone.MAXOR, SplitMilestone.STORM, SplitMilestone.S1, SplitMilestone.S2, SplitMilestone.S3,
    SplitMilestone.S4, SplitMilestone.GOLDOR, SplitMilestone.NECRON)

/** Floor name (F1–F7, M1–M7) to its definition; the Entrance has no boss phases and is not split. */
val splitDefinitions: Map<String, SplitDefinition> = (
    floors(1, listOf(SplitMilestone.BONZO_FIRST), "Bonzo 2") +
        floors(2, listOf(SplitMilestone.SCARF_UNDEADS), "Scarf") +
        floors(3, listOf(SplitMilestone.GUARDIANS, SplitMilestone.PROFESSOR), "Guardifessor") +
        floors(4, listOf(SplitMilestone.THORN_DIALOGUE), "Thorn") +
        floors(5, listOf(SplitMilestone.LIVID_DIALOGUE, SplitMilestone.LIVID_KILL), "End Dialogue") +
        floors(6, listOf(SplitMilestone.TERRACOTTAS, SplitMilestone.GIANTS), "Sadan") +
        SplitDefinition("F7", 1, clear + f7Boss + SplitMilestone.END, titles = mapOf(SplitMilestone.END to "End Dialogue"), groups = listOf(terms)) +
        // Only Master Mode continues into the Wither King phase: relics, then dragons until the run ends.
        SplitDefinition("M7", 1, clear + f7Boss + SplitMilestone.RELICS + SplitMilestone.END, titles = mapOf(SplitMilestone.END to "Drags"),
            groups = listOf(terms))
    ).associateBy { it.floor }

/** The boss's opening line on each floor number, sent when the first player enters the boss room. */
internal val bossEntryLines = mapOf(
    1 to "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.",
    2 to "[BOSS] Scarf: This is where the journey ends for you, Adventurers.",
    3 to "[BOSS] The Professor: I was burdened with terrible news recently...",
    4 to "[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!",
    5 to "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.",
    6 to "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!",
    7 to "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
)
/** The boss name in `[BOSS] Name:` lines, used only as a fallback when the opening line changes. */
internal val bossChatNames = mapOf(1 to "Bonzo", 2 to "Scarf", 3 to "The Professor", 4 to "Thorn", 5 to "Livid", 6 to "Sadan", 7 to "Maxor")

/** Exact boss-phase lines (stripped chat text). The first of these ends the section named by its milestone. */
internal val bossPhaseLines: Map<String, SplitMilestone> = mapOf(
    "[BOSS] Bonzo: Oh noes, you got me.. what ever will I do?!" to SplitMilestone.BONZO_FIRST,
    "[BOSS] Scarf: Those toys are not strong enough I see." to SplitMilestone.SCARF_UNDEADS,
    "[BOSS] The Professor: Oh? You found my Guardians' one weakness?" to SplitMilestone.GUARDIANS,
    "[BOSS] The Professor: I see. You have forced me to use my ultimate technique." to SplitMilestone.PROFESSOR,
    "[BOSS] Thorn: Dance! Dance with my Spirit animals! And may you perish in a delightful way!" to SplitMilestone.THORN_DIALOGUE,
    "[BOSS] Livid: I respect you for making it to here, but I'll be your undoing." to SplitMilestone.LIVID_DIALOGUE,
    "[BOSS] Sadan: ENOUGH!" to SplitMilestone.TERRACOTTAS,
    "[BOSS] Sadan: You did it. I understand now, you have earned my respect." to SplitMilestone.GIANTS,
    "[BOSS] Storm: Pathetic Maxor, just like expected." to SplitMilestone.MAXOR,
    "[BOSS] Goldor: Who dares trespass into my domain?" to SplitMilestone.STORM,
    "The Core entrance is opening!" to SplitMilestone.S4,
    "[BOSS] Necron: You went further than any human before, congratulations." to SplitMilestone.GOLDOR,
)

internal const val MORT_START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
internal const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
internal const val WATCHER_CLEAR = "[BOSS] The Watcher: You have proven yourself. You may pass."
internal const val NECRON_ARGH = "[BOSS] Necron: ARGH!"
internal val watcherLine = Regex("^\\[BOSS] The Watcher: .+$")
/** Hypixel prefixes the real Livid's name with a colour word, e.g. `[BOSS] Hockey Livid:`. */
internal val lividKill = Regex("^\\[BOSS] \\w+ Livid: Impossible! How did you figure out which one I was\\?!$")
/** System lines only: a player name is a plain identifier, so a relayed player chat line cannot match. */
internal val terminalProgress = Regex("^(\\w{1,16}) (?:activated|completed) a (?:terminal|device|lever)! \\((\\d)/(\\d)\\)$")
internal val bossDefeated = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")

/** Pure chat classification shared by the live splitter and the executable check. */
sealed interface SplitSignal {
    data class Milestone(val milestone: SplitMilestone) : SplitSignal
    data class Terminal(val done: Int, val total: Int) : SplitSignal
    data object Start : SplitSignal
    /** Any Watcher line: blood open only when the run has been watched continuously since its start. */
    data object WatcherLine : SplitSignal
    /** Any line from the floor's boss: boss entry only when the run has been watched continuously since its start. */
    data object BossLine : SplitSignal
    data object NecronArgh : SplitSignal
    data object Defeated : SplitSignal
}

fun splitSignal(text: String, floorNumber: Int): SplitSignal? {
    if (text.length > 160) return null
    if (text == MORT_START) return SplitSignal.Start
    if (text == BLOOD_DOOR) return SplitSignal.Milestone(SplitMilestone.BLOOD_OPEN)
    if (text == WATCHER_CLEAR) return SplitSignal.Milestone(SplitMilestone.BLOOD_CLEAR)
    if (watcherLine.matches(text)) return SplitSignal.WatcherLine
    if (bossEntryLines[floorNumber] == text) return SplitSignal.Milestone(SplitMilestone.BOSS_ENTRY)
    bossPhaseLines[text]?.let { return SplitSignal.Milestone(it) }
    if (lividKill.matches(text)) return SplitSignal.Milestone(SplitMilestone.LIVID_KILL)
    if (text == NECRON_ARGH) return SplitSignal.NecronArgh
    bossChatNames[floorNumber]?.let { if (text.startsWith("[BOSS] $it: ")) return SplitSignal.BossLine }
    terminalProgress.matchEntire(text)?.let {
        val done = it.groupValues[2].toInt(); val total = it.groupValues[3].toInt()
        return if (total in 1..9 && done in 0..total) SplitSignal.Terminal(done, total) else null
    }
    if (bossDefeated.matches(text)) return SplitSignal.Defeated
    return null
}

/**
 * Counts F7/M7 terminal sections from the server's `(n/n)` progress lines. A section completes on its first full
 * count; a repeated full count is ignored until a partial count shows the next section has begun.
 */
class TerminalSections {
    private var completed = 0
    private var armed = true
    fun accept(done: Int, total: Int): SplitMilestone? {
        if (done < total) { armed = true; return null }
        // The fourth section ends on the Core entrance line instead.
        if (!armed || completed >= 3) return null
        armed = false
        return listOf(SplitMilestone.S1, SplitMilestone.S2, SplitMilestone.S3)[completed++]
    }
}
