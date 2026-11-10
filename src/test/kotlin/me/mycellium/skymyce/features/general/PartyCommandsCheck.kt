package me.mycellium.skymyce.features.general

fun checkPartyCommands() {
    val members = listOf("Alice", "Alina", "Bob", "Bobby")
    check(matchPartyMember(members, "alice") == "Alice")   // exact, any case
    check(matchPartyMember(members, "Bob") == "Bob")        // exact wins over the longer "Bobby"
    check(matchPartyMember(members, "Alin") == "Alina")     // unique prefix
    check(matchPartyMember(members, "Al") == "Al")          // ambiguous prefix is left for the server
    check(matchPartyMember(members, "li") == "li")          // a substring never matches
    check(matchPartyMember(members, "Carol") == "Carol")    // unknown name passes through
    check(matchPartyMember(emptyList(), "Alice") == "Alice")
    println("Party command checks passed: exact and unique-prefix member matching")
}
