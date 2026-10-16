package me.mycellium.skymyce.features.instances.dungeons.leap

import com.google.gson.JsonParser
import java.util.UUID

internal fun checkLeapSharing() {
    val self = LeapIdentity("Alice", UUID(0, 1))
    val peer = LeapIdentity("Bob", UUID(0, 2))
    val other = LeapIdentity("Carol", UUID(0, 3))
    val party = listOf(self, peer, other).map { it.uuid.toString().replace("-", "") }.toSet()
    val names = listOf("ALICE", "bob")
    check(leapPovMembers(names, listOf(peer, self, other), party, self) == mapOf(self.uuid to "Alice", peer.uuid to "Bob"))
    check(leapPovMembers(names.reversed(), listOf(peer, self), party, self) == leapPovMembers(names, listOf(self, peer), party, self))
    check(leapPovMembers(listOf("Alice"), listOf(self), party, self) == null)
    check(leapPovMembers(listOf("Alice", "ALICE"), listOf(self, peer), party, self) == null)
    check(leapPovMembers(listOf("Alice", "Invalid name"), listOf(self, peer), party, self) == null)
    check(leapPovMembers(names, listOf(self), party, self) == null)
    check(leapPovMembers(names, listOf(self, peer, peer), party, self) == null)
    check(leapPovMembers(names, listOf(self, peer.copy(uuid = null)), party, self) == null)
    check(leapPovMembers(names, listOf(self, peer.copy(uuid = UUID(0, 0))), party, self) == null)
    check(leapPovMembers(names, listOf(self, peer.copy(uuid = self.uuid)), party, self) == null)
    check(leapPovMembers(names, listOf(self, peer), setOf(self.uuid.toString().replace("-", "")), self) == null)
    check(leapPovMembers(names, listOf(self, peer), party, self.copy(uuid = other.uuid)) == null)
    check(leapPovMembers(names, listOf(self, peer), party, self.copy(name = "Carol")) == null)
    val tooMany = (1L..6).map { LeapIdentity("Player$it", UUID(0, it)) }
    check(leapPovMembers(tooMany.map { it.name }, tooMany, tooMany.map { it.uuid.toString().replace("-", "") }.toSet(), tooMany.first()) == null)

    fun ack(text: String) = leapPovAcknowledgement(JsonParser.parseString(text).asJsonObject)
    check(ack("""{"enabled":true,"serverTime":1000,"expiresAt":16000}""") == (1000L to 16000L))
    for (bad in listOf(
        """{"enabled":false,"serverTime":1000,"expiresAt":16000}""",
        """{"enabled":"true","serverTime":1000,"expiresAt":16000}""",
        """{"enabled":true,"serverTime":"1000","expiresAt":16000}""",
        """{"enabled":true,"serverTime":1000,"expiresAt":"16000"}""",
        """{"enabled":true,"serverTime":1e3,"expiresAt":16000}""",
        """{"enabled":true,"serverTime":1000.0,"expiresAt":16000}""",
        """{"enabled":true,"serverTime":-1,"expiresAt":16000}""",
        """{"enabled":true,"serverTime":1000,"expiresAt":9007199254740992}""",
        """{"enabled":true,"serverTime":1000,"expiresAt":16000,"error":"not_authorized"}""",
        """{"enabled":true,"serverTime":1000}""",
    )) check(ack(bad) == null)
}
