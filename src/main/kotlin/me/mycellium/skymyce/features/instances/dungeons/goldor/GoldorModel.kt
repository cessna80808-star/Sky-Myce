package me.mycellium.skymyce.features.instances.dungeons.goldor

/**
 * Stable identifiers for Goldor (F7/M7 phase 3) coordination. Codes are part of the party-chat protocol and the
 * strategy profiles, so existing codes must never be renamed or reused for something else.
 */
enum class GoldorRole(val code: Char, val title: String, val short: String, val color: Int) {
    HEALER('h', "Healer", "H", 0xF06AC9),
    MAGE('m', "Mage", "M", 0x55D9FF),
    BERSERKER('b', "Berserker", "B", 0xFF5555),
    ARCHER('a', "Archer", "A", 0xFFAA00),
    TANK('t', "Tank", "T", 0x55FF55);

    companion object {
        fun ofCode(code: Char) = entries.firstOrNull { it.code == code }
        /** Hypixel's class names, including "Berserk" from the tab list and party finder. */
        fun ofName(name: String?): GoldorRole? = when (name?.trim()?.lowercase()) {
            "healer" -> HEALER; "mage" -> MAGE; "berserk", "berserker" -> BERSERKER; "archer" -> ARCHER; "tank" -> TANK
            else -> null
        }
    }
}

/** The four terminal sections, then the Core run after S4. [number] is the protocol digit. */
enum class GoldorSection(val number: Int, val title: String) {
    S1(1, "S1"), S2(2, "S2"), S3(3, "S3"), S4(4, "S4"), CORE(5, "Core");

    val next get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun ofNumber(number: Int) = entries.firstOrNull { it.number == number }
    }
}

enum class ObjectiveKind { TERMINAL, DEVICE, LEVER }

/**
 * The two levers of a section, as seen walking in. Only S4's differ in height: its right lever is the top one (y 128)
 * and its left lever the bottom one (y 121), which is how the S4 lever priorities are written.
 */
enum class LeverSide(val code: Char) {
    LEFT('l'), RIGHT('r');

    fun title(section: GoldorSection) = if (section == GoldorSection.S4) (if (this == RIGHT) "top" else "bottom") else if (this == LEFT) "left" else "right"
}

/**
 * One objective of a section: a numbered terminal, the section's device, or one of its two levers.
 * [id] is stable, e.g. `s2.t4`, `s3.dev`, `s4.lr`.
 */
data class ObjectiveId(val section: GoldorSection, val kind: ObjectiveKind, val number: Int = 0, val side: LeverSide? = null) {
    val id: String get() = "s${section.number}." + when (kind) {
        ObjectiveKind.TERMINAL -> "t$number"
        ObjectiveKind.DEVICE -> "dev"
        ObjectiveKind.LEVER -> "l${side!!.code}"
    }
    val label: String get() = when (kind) {
        ObjectiveKind.TERMINAL -> "T$number"
        ObjectiveKind.DEVICE -> "Dev"
        ObjectiveKind.LEVER -> if (section == GoldorSection.S4) (if (side == LeverSide.RIGHT) "Top" else "Bot") else if (side == LeverSide.LEFT) "LL" else "RL"
    }
    override fun toString() = id

    companion object {
        fun terminal(section: GoldorSection, number: Int) = ObjectiveId(section, ObjectiveKind.TERMINAL, number)
        fun device(section: GoldorSection) = ObjectiveId(section, ObjectiveKind.DEVICE)
        fun lever(section: GoldorSection, side: LeverSide) = ObjectiveId(section, ObjectiveKind.LEVER, side = side)
    }
}

/**
 * Terminal puzzles, identified by their window titles. Codes are part of the protocol.
 * Titles as Hypixel shows them; matching ignores colour codes and surrounding spaces.
 */
enum class PuzzleType(val code: Char, val title: String) {
    MELODY('m', "Melody"),
    NUMBERS('n', "Numbers"),
    RUBIKS('r', "Rubik's"),
    STARTS_WITH('s', "Starts with"),
    SELECT_ALL('c', "Select all"),
    PANES('p', "Panes");

    companion object {
        private val colourCode = Regex("§.")
        private val startsWithTitle = Regex("^What starts with: '.'\\?$")
        private val selectAllTitle = Regex("^Select all the [A-Z0-9 _]{1,24} items!$")

        fun ofCode(code: Char) = entries.firstOrNull { it.code == code }

        /** The puzzle a terminal window shows, from its title; null for any other window. */
        fun ofTitle(rawTitle: String): PuzzleType? {
            val title = rawTitle.replace(colourCode, "").trim()
            return when {
                title == "Click the button on time!" -> MELODY
                title == "Click in order!" -> NUMBERS
                title == "Change all to same color!" -> RUBIKS
                title == "Correct all the panes!" -> PANES
                title.matches(startsWithTitle) -> STARTS_WITH
                title.matches(selectAllTitle) -> SELECT_ALL
                else -> null
            }
        }
    }
}

/** Where a piece of state came from; shown as confidence on the map. */
enum class StateSource { LOCAL, SHARED, CHAT, INFERRED, ASSIGNED }

/** How sure a prediction is. Low confidence never shows an exact countdown. */
enum class Confidence { HIGH, MEDIUM, LOW }
