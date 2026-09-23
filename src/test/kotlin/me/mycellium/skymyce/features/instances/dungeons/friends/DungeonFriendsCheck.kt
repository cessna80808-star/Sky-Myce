package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import com.google.gson.Gson
import me.mycellium.mixin.LabelComponentMixin
import me.mycellium.skymyce.config.instances.dungeons.partyListingFromLore
import me.mycellium.skymyce.config.instances.dungeons.partyListingFloor
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.ChatFormatting
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.*
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.*
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Run with ./gradlew dungeonFriendsCheck; requires no Minecraft client or API credentials. */
fun main() {
    check(canBeAvailable(SkyBlockIsland.DUNGEON_HUB, false))
    check(!canBeAvailable(SkyBlockIsland.DUNGEON_HUB, true))
    check(!canBeAvailable(SkyBlockIsland.THE_CATACOMBS, false))
    val progress = DungeonRefreshProgress()
    check(progress.update(0, 100, 0, 0) == 0)
    check(progress.update(3, 97, 0, 0) == 3)
    check(progress.update(4, 96, 0, 0) == 4)
    check(progress.update(4, 196, 0, 0) == 4) // Discovering another page never goes backwards.
    check(progress.update(200, 0, 0, 1) == 99)
    check(progress.update(200, 0, 1, 0) == 100)
    check(progress.update(200, 10, 0, 1) == 0) // A new refresh starts a new percentage.
    check(relayUri("wss://example.workers.dev/websocket") != null)
    check(relayUri("ws://127.0.0.1:8787/websocket?room=testing") != null)
    listOf("http://example.com/websocket", "ws://example.com/websocket", "wss://user:password@example.com/websocket",
        "wss://example.com/websocket#fragment", "wss://example.com/wrong", "wss://example.com/websocket?token=secret").forEach { check(relayUri(it) == null) }
    val deliveries = RelayDeliveries()
    var acknowledged = 0
    var fallback = 0
    deliveries.add("first", "Alice", 0, { acknowledged++ }, { fallback++ })
    deliveries.acknowledge("first", "Bob")
    deliveries.tick(4999)
    check(acknowledged == 0 && fallback == 0)
    deliveries.acknowledge("first", "ALICE")
    deliveries.tick(5000)
    check(acknowledged == 1 && fallback == 0)
    deliveries.add("second", "Alice", 10000, { acknowledged++ }, { fallback++ })
    deliveries.tick(15000)
    deliveries.acknowledge("second", "Alice")
    deliveries.fail("second")
    check(acknowledged == 1 && fallback == 1) // Late/missing receipts fall back exactly once.
    deliveries.add("third", "Bob", 20000, { acknowledged++ }, { fallback++ })
    deliveries.clear()
    deliveries.tick(30000)
    check(fallback == 1) // Leaving the server cancels unsent messages.
    deliveries.add("fourth", "Bob", 30000, { acknowledged++ }, { fallback++ })
    deliveries.clear(failed = true)
    check(fallback == 2) // A connection failure while still playing triggers fallback.
    var testReceived = false
    deliveries.add("lfg", "Alice", 40000, { acknowledged++ }, { fallback++ })
    deliveries.add("connection-test", "Alice", 40000, { testReceived = true }, { error("Test receipt was lost") })
    deliveries.acknowledge("unrelated", "Alice")
    check(!testReceived)
    deliveries.acknowledge("connection-test", "Alice")
    check(testReceived && acknowledged == 1) // A diagnostic receipt cannot accept an outstanding LFG message.
    deliveries.tick(45000)
    check(fallback == 3)
    var relayReady = false
    var transmissions = 0
    val deliveryFailures = mutableListOf<String>()
    deliveries.add("warming-up", "Alice", 50000, { acknowledged++ }, deliveryFailures::add) {
        if (!relayReady) false else { transmissions++; true }
    }
    deliveries.tick(50000)
    deliveries.acknowledge("warming-up", "Alice") // An unsent request cannot be acknowledged.
    deliveries.connectionFailed() // A failed connection attempt must not discard unsent work.
    deliveries.tick(54999)
    check(transmissions == 0 && deliveryFailures.isEmpty() && acknowledged == 1)
    relayReady = true
    deliveries.tick(54999)
    deliveries.tick(55000)
    check(transmissions == 1 && deliveryFailures.isEmpty()) // The receipt timeout starts at transmission.
    deliveries.acknowledge("warming-up", "Alice")
    deliveries.tick(60000)
    check(acknowledged == 2 && deliveryFailures.isEmpty())
    deliveries.add("never-ready", "Alice", 60000, {}, deliveryFailures::add) { false }
    deliveries.tick(65000)
    check(deliveryFailures == listOf("connection_not_ready"))
    deliveries.add("lost-after-send", "Alice", 65000, {}, deliveryFailures::add) { transmissions++; true }
    deliveries.tick(65000)
    deliveries.connectionFailed()
    deliveries.tick(70000)
    check(transmissions == 2 && deliveryFailures == listOf("connection_not_ready", "connection_lost"))
    deliveries.add("cancel-unsent", "Alice", 70000, {}, { error("Leaving Hypixel must cancel without sending /msg") }) { transmissions++; true }
    deliveries.clear()
    deliveries.tick(70001)
    check(transmissions == 2)
    // Hovering a full-width label's blank area has no text style, even when it has a location tooltip.
    val hoverFix = LabelComponentMixin::class.java.getDeclaredMethod("skymyce\$nonNullHoverStyle", Style::class.java)
        .apply { isAccessible = true }
    val mixin = LabelComponentMixin()
    check(hoverFix.invoke(mixin, null) === Style.EMPTY)
    val textStyle = Style.EMPTY.withHoverEvent(HoverEvent.ShowText(Component.literal("Friend location")))
    check(hoverFix.invoke(mixin, textStyle) === textStyle)

    val scanner = FriendListScanner()
    scanner.notification("Dave", true)
    check(!scanner.receive("Friends (Page 1 of 1)", 0))
    check(scanner.tick(0) == "friend list 1")
    check(scanner.tick(1) == null)
    check(!scanner.receive("Party > Alice: hello", 10))
    check(!scanner.receive("Alice is asking for a party", 10))
    check(scanner.receive("--------------------\nFriends (Page 1 of 8)\nAlice is in Dungeons\n--------------------", 100))
    check(scanner.online["alice"]?.badge == "§cIn Run")
    check(scanner.online["alice"]?.statusColor == 0xFF5555)
    check(scanner.tick(1299) == null)
    check(scanner.tick(1300) == "friend list 2")
    check(scanner.receive("--------------------", 1310))
    check(scanner.receive("<< Friends (Page 2 of 8)", 1311))
    check(scanner.receive("[MVP+] Carol is in Dungeon Hub", 1312))
    check(scanner.receive("Bob is offline", 1313))
    check(scanner.receive("§6Dave §cis currently offline", 1314))
    check(scanner.tick(2600) == null) // Drain the response without requesting another page.
    check(scanner.receive("--------------------", 2601))
    check(!scanner.scanning)
    check(scanner.online.keys == setOf("alice", "carol"))
    check(scanner.online["carol"]?.badge == "§aIdle")
    check(OnlineDungeonFriend("Limbo", "Limbo").badge == "§cIn Run")
    check(OnlineDungeonFriend("Unknown", "SkyBlock").statusColor == 0xAAAAAA)
    check(scanner.tick(6200) == null)
    scanner.notification("Alice", false)
    scanner.notification("Dave", true)
    check(scanner.online.keys == setOf("carol", "dave"))
    check(scanner.tick(9999) == null) // Adding a friend only updates the cache.
    scanner.refresh(10000)
    check(scanner.tick(10000) == "friend list 1")
    scanner.tick(20000)
    check(!scanner.scanning && scanner.online.size == 2) // Partial/failed scans don't erase known friends.
    check(!scanner.receive("--------------------", 20001))
    check(scanner.tick(20002) == null)
    scanner.refresh(21000)
    scanner.tick(21000)
    scanner.manualCommand()
    check(scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 21002))
    check(!scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 21003))
    check(scanner.tick(81000) == null) // Manual commands do not restart background scanning.
    scanner.refresh(81001)
    check(scanner.tick(81001) == "friend list 1")
    check(scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 81002))
    check(scanner.online.keys == setOf("eve"))
    scanner.refresh(85000)
    scanner.tick(85000)
    check(scanner.receive("You don't have any friends!", 85001))
    check(!scanner.scanning && scanner.online.isEmpty())
    check(scanner.tick(89000) == null)
    scanner.refresh(90000)
    check(scanner.tick(90000) == "friend list 1")
    check(scanner.receive("You are sending commands too fast!", 90001))
    check(!scanner.scanning && scanner.tick(86400004) == null)
    scanner.cancel()
    check(scanner.tick(86400005) == null) // Reconnecting cannot schedule a scan either.

    val firstOffline = FriendListScanner(listOf(OnlineDungeonFriend("Stale", "Unknown")))
    check(firstOffline.tick(0) == null)
    firstOffline.refresh(0)
    check(firstOffline.tick(0) == "friend list 1")
    check(firstOffline.receive("--------------------\nFriends (Page 1 of 8)\n§6Cessna808 §eis in SkyBlock - Dungeon Hub\n§b10inchleftie §cis currently offline\n--------------------", 1))
    check(firstOffline.online.keys == setOf("cessna808") && !firstOffline.scanning)
    check(firstOffline.tick(60000) == null && firstOffline.remainingPages == 0)
    val bestFriends = FriendListScanner()
    check(bestFriends.tick(0) == "friend list 1")
    val boldFriendList = "--------------------\nFriends (Page 1 of 8)\n§6§lCessna808 §eis in SkyBlock - Dungeon Hub\n§7hettkill §eis in SkyBlock - Garden\n§b10inchleftie §cis currently offline\n-------------------- §8(§7x§r2§8)"
    check(bestFriends.receive(boldFriendList, 1))
    check(!bestFriends.scanning && bestFriends.online.keys == setOf("cessna808", "hettkill"))
    check(bestFriends.online["cessna808"]?.let { it.bestFriend && it.rankColor == 0xFFAA00 } == true)
    check(bestFriends.online["hettkill"]?.let { !it.bestFriend && it.rankColor == 0xAAAAAA } == true)
    check(bestFriends.tick(60001) == null)
    check(bestFriendChange("[MVP++] Cessna808 is now a best friend!") == ("Cessna808" to true))
    check(bestFriendChange("§6[MVP++] Cessna808 §eis no longer a best friend!") == ("Cessna808" to false))
    check(bestFriendChange("From Bob: Cessna808 is now a best friend!") == null)
    bestFriends.bestFriend("Cessna808", false)
    check(bestFriends.online["cessna808"]?.bestFriend == false)
    bestFriends.bestFriend("Cessna808", true)
    bestFriends.notification("Cessna808", true)
    check(bestFriends.online["cessna808"]?.let { it.bestFriend && it.rankColor == 0xFFAA00 } == true)
    // The supplied component dump has inherited blue text and a repeat suffix, with no bold flag remaining.
    val modifiedList = Component.literal("--------------------\nFriends (Page 1 of 8)\n")
        .withStyle(ChatFormatting.BLUE)
        .append(Component.literal("Cessna808 is in SkyBlock - Dungeon Hub"))
        .append(Component.literal("\n10inchleftie is currently offline\n--------------------"))
        .append(Component.literal(" (x2)").withStyle(ChatFormatting.YELLOW))
    bestFriends.refresh(60002)
    check(bestFriends.tick(60002) == "friend list 1")
    check(bestFriends.receive(modifiedList.string, 60003, modifiedList))
    check(bestFriends.online["cessna808"]?.bestFriend == true && !bestFriends.scanning)
    check(bestFriends.online["cessna808"]?.rankColor == 0xFFAA00) // A flattened blue line cannot replace the known gold rank.
    check(bestFriends.tick(120004) == null)

    val rankedPlayer = Component.literal("[MVP++] ").withStyle(ChatFormatting.GOLD)
        .append(Component.empty().withStyle(ChatFormatting.BOLD)
            .append(Component.literal("Cessna")).append(Component.literal("808")))
        .append(Component.literal(" joined the party.").withStyle(ChatFormatting.YELLOW))
    check(dungeonPlayerNameStyle(rankedPlayer, "Cessna808")?.let { it.color?.value == 0xFFAA00 && it.isBold } == true)
    check(dungeonPlayerNameStyle(Component.literal("§6§lCessna808§r joined"), "Cessna808")?.color?.value == 0xFFAA00)
    check(dungeonPlayerNameStyle(Component.literal("Cessna8080 joined"), "Cessna808") == null)
    val rankedTitle = dungeonPlayerTitle("Cessna808", "joined your party", 0xFFAA00)
    check(rankedTitle.string == "Cessna808 joined your party")
    check(dungeonPlayerNameStyle(rankedTitle, "Cessna808")?.color?.value == 0xFFAA00)
    check(dungeonPlayerNameStyle(rankedTitle, "joined")?.color?.value == 0xFFFFFF)

    fun parse(member: String) = DungeonFriendStats.fromProfiles(JsonParser.parseString(
        """{"success":true,"profiles":[{"selected":true,"members":{"abc":$member}}]}"""
    ).asJsonObject, "abc")
    val hidden = parse("{}")
    check(hidden.state == StatsState.HIDDEN && hidden.eligible(M7))
    val empty = parse("""{"dungeons":{}}""")
    check(empty.state == StatsState.AVAILABLE && !empty.eligible(F1))
    check(DungeonFriendStats.fromProfiles(JsonParser.parseString("""{"success":true,"profiles":null}""").asJsonObject, "abc").state == StatsState.NO_PROFILE)
    val known = parse("""{
        "dungeons": {
            "selected_dungeon_class":"archer",
            "player_classes":{"archer":{"experience":999999999},"mage":{"experience":125}},
            "dungeon_types":{
                "catacombs":{"experience":999999999,"fastest_time":{"7":400000},"fastest_time_s_plus":{"7":420000}},
                "master_catacombs":{"tier_completions":{"1":1,"7":1},"fastest_time_s":{"1":100000}}
            }
        }
    }""")
    check(known.catacombs == 52 && known.classes[ARCHER] == 52 && known.classes.size == 2)
    check(known.classes[MAGE] == 2 && known.selectedClass == ARCHER)
    check(known.highestFloor == M7) // Completion count sets defaults, but isn't a PB for eligibility.
    check(known.eligible(F7) && known.eligible(M1) && !known.eligible(M7))
    check(known.completionTimes[F7] == 400000L && known.sPlusTimes[F7] == 420000L)
    val sharedJson = JsonParser.parseString(Gson().toJson(mapOf("name" to "Alice", "uuid" to "a".repeat(32),
        "stats" to known, "fetchedAt" to 1000000L))).asJsonObject
    val cloudStats = sharedDungeonFriend(sharedJson, "ALICE", "a".repeat(32), 1000100)!!
    check(cloudStats.stats == known && cloudStats.verifiedUntil == 1600000L)
    DungeonFriendStatsCache.clear()
    DungeonFriendStatsCache.request("Alice")
    check(DungeonFriendStatsCache.receiveShared(sharedJson, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.get("Alice") == known && DungeonFriendStatsCache.pendingCount == 0)
    val liveVersion = DungeonFriendStatsCache.version
    check(!DungeonFriendStatsCache.receiveShared(sharedJson, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.version == liveVersion) // Duplicate/stale broadcasts cannot renew freshness.
    check(!DungeonFriendStatsCache.receiveShared(sharedJson, "Alice", "b".repeat(32), 1000100))
    val improved = sharedJson.deepCopy().apply {
        addProperty("fetchedAt", 1000050)
        getAsJsonObject("stats").getAsJsonObject("sPlusTimes").addProperty("F7", 290000)
    }
    check(DungeonFriendStatsCache.receiveShared(improved, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.get("Alice")?.sPlusTimes?.get(F7) == 290000L)
    check(!DungeonFriendStatsCache.receiveShared(sharedJson, "Alice", "a".repeat(32), 1000100))
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.receiveShared(sharedJson, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.updateClass("Alice", "a".repeat(32), TANK))
    check(DungeonFriendStatsCache.get("Alice") == known.copy(selectedClass = TANK))
    check(DungeonFriendStatsCache.verified("Alice") == null) // Class updates cannot renew an expired PB.
    val classVersion = DungeonFriendStatsCache.version
    check(!DungeonFriendStatsCache.updateClass("Alice", "a".repeat(32), TANK))
    check(DungeonFriendStatsCache.version == classVersion)
    check(!DungeonFriendStatsCache.updateClass("Alice", "b".repeat(32), HEALER))
    check(DungeonFriendStatsCache.receiveShared(improved, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.get("Alice")?.selectedClass == TANK) // A late stats result cannot undo the live class.
    DungeonFriendStatsCache.forgetLiveClass("Alice")
    check(DungeonFriendStatsCache.receiveShared(improved.deepCopy().apply { addProperty("fetchedAt", 1000075) }, "Alice", "a".repeat(32), 1000100))
    check(DungeonFriendStatsCache.get("Alice")?.selectedClass == ARCHER) // A profile change releases the live override.
    DungeonFriendStatsCache.clear()
    check(sharedDungeonFriend(sharedJson, "Bob", null, 1000100) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", "b".repeat(32), 1000100) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 1600000) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 900000) == null)
    sharedJson.getAsJsonObject("stats").getAsJsonObject("sPlusTimes").addProperty("F7", -1)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 1000100) == null)
    check(!known.copy(catacombs = 23).eligible(F7))
    check(known.copy(catacombs = 24).eligible(F7))
    check(!known.copy(catacombs = 35, completionTimes = mapOf(M7 to 1L)).eligible(M7))
    check(known.copy(catacombs = 36, completionTimes = mapOf(M7 to 1L)).eligible(M7))
    check(dungeonLevel(49.0) == 0 && dungeonLevel(50.0) == 1 && dungeonLevel(125.0) == 2)
    check(dungeonLevel(Double.NaN) == 0 && dungeonLevel(-1.0) == 0)
    val malformed = parse("""{"dungeons":{"dungeon_types":{"catacombs":{"experience":null,"fastest_time":{"7":0,"6":-1,"5":"bad"}}}}}""")
    check(malformed.completionTimes.isEmpty())

    // Each floor and mode has its own S+ PB; never substitute an ordinary completion.
    fun times(offset: Int) = (1..7).joinToString(",") { "\"$it\":${offset + it * 1000}" }
    val allFloors = parse("""{"dungeons":{
        "player_classes":{"healer":{"experience":0},"mage":{"experience":50},
            "berserk":{"experience":125},"archer":{"experience":235},"tank":{"experience":395}},
        "dungeon_types":{
            "catacombs":{"experience":999999999,"fastest_time":{${times(60000)}},"fastest_time_s_plus":{${times(120000)}}},
            "master_catacombs":{"fastest_time":{${times(180000)}},"fastest_time_s_plus":{${times(240000)}}}
        }
    }}""")
    for (floor in FRIEND_FLOORS) {
        val master = floor.name.startsWith("M")
        check(allFloors.sPlusTimes[floor] == (if (master) 240000L else 120000L) + floor.floorNumber * 1000L)
        check(allFloors.completionTimes[floor] == (if (master) 180000L else 60000L) + floor.floorNumber * 1000L)
    }
    check(allFloors.classes == mapOf(HEALER to 0, MAGE to 1, BERSERKER to 2, ARCHER to 3, TANK to 4))
    check(allFloors.selectedClass == null)
    check(parse("""{"dungeons":{"player_classes":{"berserker":{"experience":125}}}}""").classes[BERSERKER] == 2)
    check(parse("""{"dungeons":{"player_classes":{"berserk":{"experience":235},"berserker":{"experience":50}}}}""").classes[BERSERKER] == 3)
    check(dungeonLevel(769809640.0) == 51 && dungeonLevel(569809640.0) == 50)
    check(known.sPlusTimes[M1] == null && known.completionTimes[M1] == 100000L)
    checkJoining(known, hidden)
    val selectedProfile = DungeonFriendStats.fromProfiles(JsonParser.parseString("""{"success":true,"profiles":[
        {"members":{"abc":{"last_save":999999,"dungeons":{}}}},
        {"selected":true,"members":{"other":{"dungeons":{}},"abc":{"dungeons":{"dungeon_types":{
            "catacombs":{"experience":125,"fastest_time_s_plus":{"1":65000}}
        }}}}}
    ]}""").asJsonObject, "abc")
    check(selectedProfile.catacombs == 2 && selectedProfile.sPlusTimes[F1] == 65000L)

    // Repeated list ticks and invalid names must not grow the pending request queue.
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.pendingCount == 0)
    DungeonFriendStatsCache.request("Alice")
    DungeonFriendStatsCache.request("ALICE")
    DungeonFriendStatsCache.request("Bob")
    DungeonFriendStatsCache.request("invalid/name")
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    check(DungeonFriendStatsCache.pendingCount == 2)
    DungeonFriendStatsCache.refresh()
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    check(DungeonFriendStatsCache.pendingCount == 2)
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.status.isEmpty())
    check(DungeonFriendStatsCache.pendingCount == 0)

    // Cache hits complete independently of a slow/missing response at the front of the queue.
    val cacheTime = System.currentTimeMillis()
    val sharedReplies = linkedMapOf<String, CompletableFuture<com.google.gson.JsonObject?>>()
    fun sharedReply(name: String) = JsonParser.parseString(Gson().toJson(mapOf("record" to mapOf(
        "name" to name, "uuid" to "a".repeat(32), "fetchedAt" to cacheTime, "stats" to known,
    )))).asJsonObject
    fun pollCache(time: Long = cacheTime) = DungeonFriendStatsCache.pollShared(time) { name, _ ->
        CompletableFuture<com.google.gson.JsonObject?>().also { sharedReplies[name] = it }
    }
    listOf("Slow", "Alice", "Bob", "Carol").forEach { DungeonFriendStatsCache.request(it) }
    repeat(4) { pollCache() }
    check(sharedReplies.keys == setOf("slow", "alice", "bob")) // Bounded prefetch preserves upload grants.
    sharedReplies.getValue("bob").complete(sharedReply("bob"))
    pollCache()
    check(DungeonFriendStatsCache.get("Bob") == known && DungeonFriendStatsCache.get("Slow") == null)
    check("carol" in sharedReplies && DungeonFriendStatsCache.pendingCount == 3)
    sharedReplies.getValue("alice").complete(sharedReply("alice"))
    sharedReplies.getValue("carol").complete(sharedReply("carol"))
    pollCache()
    check(DungeonFriendStatsCache.pendingCount == 1)
    DungeonFriendStatsCache.clear()
    sharedReplies.getValue("slow").complete(sharedReply("slow"))
    pollCache()
    check(DungeonFriendStatsCache.get("Slow") == null) // A cleared session cannot repopulate from late replies.
    sharedReplies.clear()
    DungeonFriendStatsCache.request("NewFriend", force = true, bypassShared = true)
    pollCache()
    sharedReplies.getValue("newfriend").complete(sharedReply("newfriend"))
    pollCache()
    check(DungeonFriendStatsCache.get("NewFriend") == null && DungeonFriendStatsCache.pendingCount == 1)
    pollCache(cacheTime + 110001)
    check(!sharedReplies.getValue("newfriend").isDone) // Refresh expired upload grants before provider use.
    DungeonFriendStatsCache.clear()
    // Twenty warm records can load without a one-second pause per player; further reads are paced.
    val burstTime = cacheTime + 200000
    var reads = 0
    repeat(21) { DungeonFriendStatsCache.request("Warm$it") }
    fun pollWarm(time: Long) = DungeonFriendStatsCache.pollShared(time) { name, _ ->
        reads++
        CompletableFuture.completedFuture(sharedReply(name))
    }
    repeat(25) { pollWarm(burstTime) }
    check(reads == 20 && DungeonFriendStatsCache.pendingCount == 1)
    pollWarm(burstTime + 999)
    check(reads == 20)
    pollWarm(burstTime + 1000)
    pollWarm(burstTime + 1000)
    check(reads == 21 && DungeonFriendStatsCache.pendingCount == 0)
    DungeonFriendStatsCache.clear()
    DungeonFriendStatsCache.request("Limited")
    pollCache(burstTime + 20000)
    sharedReplies.getValue("limited").complete(JsonParser.parseString("""{"error":"rate_limited"}""").asJsonObject)
    pollCache(burstTime + 20000)
    pollCache(burstTime + 20999)
    check(sharedReplies.getValue("limited").isDone && DungeonFriendStatsCache.pendingCount == 1)
    pollCache(burstTime + 21000)
    check(!sharedReplies.getValue("limited").isDone) // Retry the cache instead of turning its limit into API load.
    DungeonFriendStatsCache.clear()

    // Refresh must finish even when the relay returns the same record, or an older fresh one.
    for (olderBy in listOf(0L, 1000L)) {
        val original = sharedReply("Alice").getAsJsonObject("record")
        check(DungeonFriendStatsCache.receiveShared(original, "Alice", null, cacheTime))
        DungeonFriendStatsCache.refresh()
        val response = sharedReply("Alice").apply {
            getAsJsonObject("record").addProperty("fetchedAt", cacheTime - olderBy)
            if (olderBy > 0) getAsJsonObject("record").getAsJsonObject("stats").addProperty("catacombs", 41)
        }
        var refreshReads = 0
        val completedBefore = DungeonFriendStatsCache.completedCount
        val progress = DungeonRefreshProgress()
        repeat(10) {
            DungeonFriendStatsCache.request("Alice") // The normal every-tick caller.
            progress.update(DungeonFriendStatsCache.completedCount, DungeonFriendStatsCache.pendingCount, 0, 0)
            DungeonFriendStatsCache.pollShared(burstTime + 40000) { _, _ ->
                refreshReads++
                CompletableFuture.completedFuture(response)
            }
        }
        check(refreshReads == 1) { "A confirmed cache hit was repeatedly queued after Refresh" }
        check(DungeonFriendStatsCache.pendingCount == 0)
        check(DungeonFriendStatsCache.completedCount == completedBefore + 1)
        check(progress.update(DungeonFriendStatsCache.completedCount, 0, 0, 0) == 100)
        check(DungeonFriendStatsCache.get("Alice") == known) // Keep a newer local PB/class report.
        DungeonFriendStatsCache.clear()
    }

    check(matchesFriendClass(hidden, emptySet(), setOf(TANK)))
    check(matchesFriendClass(known.copy(selectedClass = null), emptySet(), setOf(TANK)))
    check(matchesFriendClass(known.copy(classes = emptyMap()), emptySet(), setOf(ARCHER)))
    check(!matchesFriendClass(known.copy(classes = emptyMap()), emptySet(), setOf(TANK)))
    check(matchesFriendClass(known, setOf(TANK), setOf(TANK)))
    check(!matchesFriendClass(known, emptySet(), setOf(TANK)))
    check(parseDungeonClass("Berserk") == BERSERKER)
    check(nextMissingClass(ARCHER, listOf(ARCHER)) == BERSERKER)
    check(nextMissingClass(TANK, listOf(ARCHER)) == TANK)
    check(nextMissingClass(TANK, tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.entries) == null)

    val party = DungeonFriendParty()
    check(!party.canInvite)
    party.roster(listOf("Self", "Alice", "Bob", "Carol"), 4, true)
    party.classes.putAll(mapOf("self" to MAGE, "alice" to HEALER, "bob" to TANK, "carol" to BERSERKER))
    party.advance()
    check(party.canInvite && party.neededClass == ARCHER)
    party.chat("Party Finder > [MVP+] Dave joined the dungeon group! (Archer Level 42)", "Self")
    check(party.full && !party.canInvite && party.neededClass == null)
    party.chat("[Party] Dave joined the party.", "Self")
    check(party.size == 5) // The finder and party announcements describe the same join.
    party.roster(listOf("Self", "Alice", "Bob", "Carol"), 4)
    party.chat("Dave has left the party.", "Self")
    check(party.canInvite && party.size == 4 && party.neededClass == ARCHER)
    party.roster(listOf("Self"), 5, true) // UUID-only API members still count toward capacity.
    check(party.full)
    party.chat("You left the party.", "Self")
    check(party.canInvite && party.size == 1 && party.classes.isEmpty())
    party.chat("Party > Alice: Dave joined the party.", "Self")
    check(party.size == 1)
    party.chat("You have joined [MVP+] Alice's party!", "Self")
    check(!party.canInvite) // A join announcement doesn't disclose every member of an existing party.
    party.roster(listOf("Self", "Alice", "Bob", "Carol", "Dave"), 5, true)
    check(party.full && !party.canInvite)

    val friends = listOf(OnlineDungeonFriend("Unknown", "Hub"), OnlineDungeonFriend("Fast", "Dungeons"), OnlineDungeonFriend("Slow", "Hub"))
    val stats = mapOf("unknown" to hidden, "fast" to known.copy(sPlusTimes = mapOf(F7 to 1000)), "slow" to known.copy(sPlusTimes = mapOf(F7 to 2000)))
    check(friends.sortedWith(friendComparator(stats, F7, FriendSort.PB)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(stats, F7, FriendSort.PB, false)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    val levels = stats + mapOf("fast" to known.copy(catacombs = 45, classes = mapOf(ARCHER to 30, MAGE to 50)),
        "slow" to known.copy(catacombs = 41, classes = mapOf(ARCHER to 50, MAGE to 30)))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.CATACOMBS)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.CATACOMBS, true)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.ARCHER)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.MAGE)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.ARCHER, true)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(lfgMessage("Hi {name}, {class} for {floor}?", "Alice", ARCHER, M7) == "Hi Alice, Archer for M7?")
    check(lfgMessage("Hi {name}, {class} for {floor}?", "Alice", null, M7) == "Hi Alice, any class for M7?")
    check(!lfgMessage("hello\n/p someone", "Alice", TANK, F7).contains('\n'))
    check(lfgMessage("x".repeat(300), "Alice", TANK, F7).length + "msg Alice ".length <= 256)
    check(formatDungeonTime(61234) == "1:01.234")

    check(newlyAddedFriend("You are now friends with aryanepstein") == "aryanepstein")
    check(newlyAddedFriend("§aYou are now friends with [MVP++] Alice!") == "Alice")
    check(newlyAddedFriend("From Alice: You are now friends with Bob") == null)
    check(newlyAddedFriend("You are now friends with invalid/name") == null)
    check(newlyAddedFriend("You sent a friend request to Alice!") == null)
    check(dungeonClassChange("You have selected the Berserk Dungeon Class!", "Self") == "Self" to BERSERKER)
    check(dungeonClassChange("§aYou have selected the Mage Dungeon Class! §8(§7x§r2§8)", "Self") == "Self" to MAGE)
    check(dungeonClassChange("Party Finder > Cessna808 set their class to Healer Level 49!", "Self") == "Cessna808" to HEALER)
    check(dungeonClassChange("Party Finder > [MVP+] Alice set their class to Archer Level 44!", "Self") == "Alice" to ARCHER)
    check(dungeonClassChange("Party > Alice: You have selected the Tank Dungeon Class!", "Self") == null)
    check(dungeonClassChange("You have selected the Farmer Dungeon Class!", "Self") == null)
    check(dungeonTitleDetails(F7, listOf(MAGE)) == "to play §aF7§f as §bMage")
    check(dungeonTitleDetails(M7, listOf(TANK, BERSERKER, ARCHER, HEALER)) == "to play §cM7§f as §aTank§f/§6Berserk§f/§cArcher§f/§dHealer") {
        dungeonTitleDetails(M7, listOf(TANK, BERSERKER, ARCHER, HEALER))
    }
    check(dungeonTitleDetails(null, emptyList()) == "to their party")
    check(dungeonJoinedDetails(known, MAGE) == "§bCata 52 §8| §bMage §f2")
    check(dungeonJoinedDetails(null, TANK) == "§bCata ? §8| §aTank §f?")
    check(dungeonJoinedDetails(null, null) == "§bCata ? §8| §7Class ?")

    val replies = DungeonLfgReplies()
    replies.receive("Alice", "yes", 0)
    check(replies.get("Alice") == null)
    replies.sent("Alice", 100)
    replies.receive("ALICE", "sure 1s", 200)
    check(replies.get("alice")?.status == LfgReplyStatus.ACCEPTED)
    replies.receive("alice", "no thanks", 300)
    check(replies.get("Alice")?.status == LfgReplyStatus.DECLINED)
    listOf("yes", "yeah", "yes inv me", "invite me", "I'm down", "OK!", "Sure, 1s", "sounds good").forEach {
        check(classifyLfgReply(it) == LfgReplyStatus.ACCEPTED)
    }
    listOf("yesterday", "yes but not now", "maybe", "sure?", "yes if you wait", "no", "I said yes yesterday").forEach {
        check(classifyLfgReply(it) != LfgReplyStatus.ACCEPTED)
    }
    replies.receive("Alice", "yes", 300101)
    check(replies.get("Alice") == null)
    replies.sent("Alice", 400000)
    check(replies.get("Alice")?.status == LfgReplyStatus.WAITING)
    replies.forget("Alice")
    check(replies.get("Alice") == null)
    replies.invited("Alice", 500000)
    check(replies.get("Alice")?.status == LfgReplyStatus.INVITED)
    replies.prune(560000)
    check(replies.get("Alice") == null)
    replies.invited("Alice", 600000)
    replies.forget("Alice") // Joining the roster clears the pending row marker.
    check(replies.get("Alice") == null)

    // Match SkyBlockPv's public getters, including Kotlin's encoded Duration representation.
    val viewerData = ViewerDungeons(
        mapOf("catacombs" to ViewerType(999999999, mapOf("7" to ViewerFloor(5, 300000.milliseconds, 310000.milliseconds))),
            "master_catacombs" to ViewerType(0, mapOf("1" to ViewerFloor(1, 90000.milliseconds, Duration.INFINITE)))),
        mapOf("berserk" to 125L, "archer" to 235L), "berserk",
    )
    val viewerStats = DungeonFriendProfileProvider.fromViewerDungeonData(viewerData)
    check(viewerStats.catacombs == 52 && viewerStats.classes[BERSERKER] == 2)
    check(viewerStats.sPlusTimes[F7] == 310000L && viewerStats.completionTimes[F7] == 300000L)
    check(viewerStats.sPlusTimes[M1] == null && viewerStats.completionTimes[M1] == 90000L)
    check(viewerStats.selectedClass == BERSERKER)
    // The last-played class wins even when another class has more XP.
    check(viewerStats.classes.getValue(ARCHER) > viewerStats.classes.getValue(BERSERKER))
    check(matchesFriendClass(viewerStats, emptySet(), setOf(BERSERKER)))
    check(!matchesFriendClass(viewerStats, emptySet(), setOf(ARCHER)))
    check(matchesFriendClass(viewerStats, setOf(ARCHER), setOf(ARCHER)))
    check(matchesFriendClass(viewerStats, emptySet(), emptySet()))

    val savedDirectory = Files.createTempDirectory("dungeon-friends-check")
    val savedFile = savedDirectory.resolve("stats.json")
    val savedFriends = savedDirectory.resolve("friends.json")
    try {
        val friendStore = FriendListStore(savedFriends)
        check(friendStore.load() == null)
        check(FriendListScanner(friendStore.load()).tick(0) == "friend list 1")
        friendStore.save(firstOffline.online.values)
        val restoredScanner = FriendListScanner(FriendListStore(savedFriends).load())
        check(restoredScanner.hasScanned && restoredScanner.online == firstOffline.online)
        check(restoredScanner.tick(0) == null && restoredScanner.tick(86400000) == null)
        restoredScanner.notification("aryanepstein", true)
        restoredScanner.notification("Cessna808", false)
        friendStore.save(restoredScanner.online.values)
        val updatedScanner = FriendListScanner(friendStore.load())
        check(updatedScanner.online.keys == setOf("aryanepstein") && updatedScanner.tick(0) == null)
        updatedScanner.refresh(1)
        check(updatedScanner.tick(1) == "friend list 1")
        check(updatedScanner.tick(10001) == null && updatedScanner.tick(86400000) == null) // No timeout retry.
        updatedScanner.refresh(86400001)
        check(updatedScanner.tick(86400001) == "friend list 1")
        updatedScanner.cancel()
        check(updatedScanner.tick(172800000) == null)
        friendStore.save(emptyList())
        val emptyScanner = FriendListScanner(friendStore.load())
        check(emptyScanner.hasScanned && emptyScanner.online.isEmpty() && emptyScanner.tick(0) == null)
        friendStore.save(listOf(OnlineDungeonFriend("Cessna808", "Dungeon Hub", 0xFFAA00, true)))
        check(FriendListScanner(friendStore.load()).online["cessna808"]?.let { it.bestFriend && it.rankColor == 0xFFAA00 } == true)
        Files.writeString(savedFriends, """{"version":1,"online":[{"name":"Alice","location":"Hub"}]}""")
        check(friendStore.load() == listOf(OnlineDungeonFriend("Alice", "Hub"))) // Older caches still load without another scan.
        check(FriendListScanner(FriendListStore(savedDirectory.resolve("other-account.json")).load()).tick(0) == "friend list 1")
        Files.writeString(savedFriends, "{bad json")
        check(runCatching { friendStore.load() }.isFailure)
        check(Files.readString(savedFriends) == "{bad json")
        val store = DungeonFriendStatsStore(savedFile)
        val saved = mapOf(
            "low" to CachedDungeonFriend(known.copy(catacombs = 40), "a".repeat(32), 1),
            "high" to CachedDungeonFriend(known.copy(catacombs = 41), "b".repeat(32), 1),
            "hidden" to CachedDungeonFriend(hidden, null, 1),
        )
        check(!saved.getValue("low").shouldRefresh(1000))
        check(saved.getValue("high").shouldRefresh(1000))
        check(!saved.getValue("high").shouldRefresh(0))
        check(!saved.getValue("hidden").shouldRefresh(1000))
        check(CachedDungeonFriend(DungeonFriendStats(StatsState.UNAVAILABLE), null, 1).shouldRefresh(1000))
        store.save(saved)
        check(DungeonFriendStatsStore(savedFile).load() == saved)
        val oldCache = JsonParser.parseString(Files.readString(savedFile)).asJsonObject
        oldCache.getAsJsonObject("players").entrySet().forEach { it.value.asJsonObject.remove("verifiedUntil") }
        Files.writeString(savedFile, oldCache.toString())
        check(DungeonFriendStatsStore(savedFile).load().values.all { it.verifiedUntil == 0L })
        store.save(saved)
        DungeonFriendStatsCache.clear()
        DungeonFriendStatsCache.initialize(savedFile)
        DungeonFriendStatsCache.request("low")
        DungeonFriendStatsCache.request("high")
        DungeonFriendStatsCache.request("hidden")
        DungeonFriendStatsCache.request("newfriend")
        check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
        DungeonFriendStatsCache.refresh()
        check(DungeonFriendStatsCache.get("low")?.catacombs == 40)
        DungeonFriendStatsCache.disconnect()
        check(DungeonFriendStatsCache.pendingCount == 0)
        DungeonFriendStatsCache.clear()
        DungeonFriendStatsCache.initialize(savedFile)
        check(DungeonFriendStatsCache.get("high")?.sPlusTimes == known.sPlusTimes)
        check(DungeonFriendStatsCache.verified("high") == null) // Displayed old PBs cannot authorize automatic actions.
        DungeonFriendStatsCache.request("low", force = true) // New-friend notification explicitly checks a re-added friend.
        check(DungeonFriendStatsCache.status == "Loading PBs: 1 queued")
        store.save(mapOf("invalid" to CachedDungeonFriend(known, "bad-uuid", 1)))
        check(runCatching { store.load() }.isFailure)
        Files.writeString(savedFile, "{bad json")
        check(runCatching { store.load() }.isFailure)
        check(Files.readString(savedFile) == "{bad json")
        DungeonFriendStatsCache.clear()
    } finally {
        Files.deleteIfExists(savedFriends)
        Files.deleteIfExists(savedFile)
        Files.deleteIfExists(savedDirectory)
    }
    println("Dungeon Friends checks passed")
}

data class ViewerFloor(val completions: Long, val fastestTime: Duration, val fastestTimeSplus: Duration)
data class ViewerType(val experience: Long, val floors: Map<String, ViewerFloor>)
data class ViewerDungeons(val dungeonTypes: Map<String, ViewerType>, val classExperience: Map<String, Long>, val selectedClass: String)

private fun checkJoining(known: DungeonFriendStats, hidden: DungeonFriendStats) {
    check(parsePbLimit("5:30") == 330000L && parsePbLimit("0:00.001") == 1L)
    check(parsePbLimit(" 7:00.1 ") == 420100L && parsePbLimit("999:59.999") == 59999999L)
    listOf("", "0:00", "1:60", "-1:00", "5", "5:3", "5:00.0000", "1:00\n/p Bob").forEach { check(parsePbLimit(it) == null) }
    val available = DungeonAvailability(F7, setOf(ARCHER, TANK), 420000)
    check(available.enabled && available.accepts(known, F7))
    check(!available.accepts(known, M7) && !available.accepts(hidden, F7) && !available.accepts(null, F7))
    check(!available.accepts(known.copy(sPlusTimes = emptyMap()), F7))
    check(!available.accepts(known.copy(sPlusTimes = mapOf(F7 to 420001)), F7))
    check(!available.accepts(known.copy(catacombs = 23), F7))
    check(!available.copy(classes = emptySet()).enabled && available.copy(maxPbMillis = null).enabled)
    val policy = DungeonJoinPolicy(F7, 420000)
    check(policy.accepts(known, F7))
    check(!policy.accepts(known.copy(sPlusTimes = mapOf(F7 to 420001)), F7))
    check(!policy.accepts(null, F7) && !policy.accepts(hidden, F7) && !policy.accepts(known, M7))
    check(policy.copy(maxPbMillis = null).accepts(null, F7))
    check(!policy.copy(open = false).accepts(known, F7))
    check(Gson().fromJson(Gson().toJson(available), DungeonAvailability::class.java) == available)

    val inviteText = "---------------------\n[MVP++] Cessna808 has invited you to join their party!\nYou have 60 seconds to accept. Click here to join!\n---------------------"
    fun invite(command: String) = Component.literal(inviteText).append(Component.literal("Accept")
        .withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand(command))))
    check(serverPartyInviter(invite("/party accept Cessna808")) == "Cessna808")
    check(serverPartyInviter(invite("/p accept Cessna808")) == "Cessna808")
    check(serverPartyInviter(invite("/party accept SomeoneElse")) == null)
    check(serverPartyInviter(Component.literal(inviteText)) == null)
    check(partyInviter("From Alice: Bob has invited you to join their party!") == null)
    check(joinedPartyLeader("You have joined [MVP++] Cessna808's party!") == "Cessna808")
    check(joinedPartyLeader("From Bob: You have joined Alice's party!") == null)
    val notices = DungeonPartyNotices()
    val invitedAlice = "[MVP+] Self invited [VIP] Alice to the party! They have 60 seconds to accept."
    check(notices.compact(invitedAlice, "Self", 0) == null)
    check(notices.compact("[MVP+] Alice joined the party.", "Self", 0) == null)
    check(notices.compact("You have joined aryanepstein's party!", "Self", 0) == null)
    notices.command("p Alice", automatic = false, 0)
    check(notices.compact(invitedAlice, "Self", 1) == null)
    notices.command("party invite Alice", automatic = true, 2)
    check(notices.compact(invitedAlice.replace("Self invited", "Other invited"), "Self", 3) == null)
    check(notices.compact(invitedAlice, "Self", 4) == "Alice has been invited")
    check(notices.player == "Alice")
    check(notices.compact("From Bob: Alice joined the party.", "Self", 5) == null)
    check(notices.compact("Alice joined the party.\nUnrelated message", "Self", 5) == null)
    check(notices.compact("[MVP+] Alice joined the party.", "Self", 6) == "Alice joined")
    check(notices.player == "Alice") // Both phases replace the same player's message.
    check(notices.compact("Alice joined the party.", "Self", 7) == null) // Consume the tracked join once.
    notices.command("p Alice", automatic = true, 8)
    check(notices.compact("---------------------\nYou have invited [MVP+] Alice to your party! They have 60 seconds to accept.\n---------------------", "Self", 9) == "Alice has been invited")
    notices.command("p invite ALICE", automatic = false, 10)
    check(notices.compact(invitedAlice, "Self", 11) == null)
    check(notices.compact("Alice joined the party.", "Self", 12) == null) // Manual re-invite takes ownership.
    notices.command("p Alice", automatic = true, 13)
    check(notices.compact(invitedAlice, "Self", 10013) == null) // An unconfirmed command expires.
    notices.command("p Alice", automatic = true, 10014)
    check(notices.compact(invitedAlice, "Self", 10015) != null)
    check(notices.compact("Alice joined the party.", "Self", 70015) == null)
    notices.command("party accept aryanepstein", automatic = true, 70016)
    check(notices.compact("You have joined Other's party!", "Self", 70017) == null)
    check(notices.compact("You have joined aryanepstein's party!", "Self", 70018) == "You joined aryanepstein's party")
    check(notices.player == "aryanepstein") // The accepting player's phases use the host's ID.
    notices.command("party accept aryanepstein", automatic = true, 70019)
    notices.command("p accept aryanepstein", automatic = false, 70020)
    check(notices.compact("You have joined aryanepstein's party!", "Self", 70021) == null)
    notices.command("p Alice", automatic = true, 70022)
    notices.clear()
    check(notices.compact(invitedAlice, "Self", 70023) == null)

    val borders = DungeonPartyBorders()
    val restoredBorders = mutableListOf<Component>()
    val border = Component.literal("§9-----------------------------------------------------")
    check(!borders.filter(border, false, false, 0, restoredBorders::add)) // Normal party chat stays untouched.
    check(borders.filter(border, true, false, 1, restoredBorders::add))
    check(!borders.filter(Component.literal(invitedAlice), true, true, 2, restoredBorders::add))
    check(borders.filter(border, true, false, 3, restoredBorders::add))
    check(restoredBorders.isEmpty()) // The opening and closing borders around a mod notice are both removed.
    check(borders.filter(border, true, false, 4, restoredBorders::add))
    check(!borders.filter(Component.literal("Party Members (2)"), true, false, 5, restoredBorders::add))
    check(restoredBorders.single() === border) // Unrelated content restores the original component, including its style.
    check(borders.filter(border, true, false, 6, restoredBorders::add))
    borders.tick(256, restoredBorders::add)
    check(restoredBorders.size == 2) // A lone border is never lost.
    check(!borders.filter(Component.literal("-----\nYou have joined Host's party!\n-----"), true, true, 300, restoredBorders::add))
    check(!borders.filter(border, false, false, 301, restoredBorders::add)) // A bundled closing border needs no extra suppression.
    borders.clear()

    check(borders.filter(border, true, false, 400, restoredBorders::add))
    var reentered = 0
    borders.filter(Component.literal("Unrelated"), false, false, 401) { restored ->
        reentered++
        check(!borders.filter(restored, false, false, 401) { error("Recursive border restore") })
    }
    check(reentered == 1) // The held packet is released before invoking a callback.
    borders.clear()

    val request = DungeonJoinRequest(F7, setOf(ARCHER, TANK), "0123456789abcdef")
    val lfg = DungeonLfgOffer(request, "Want to join?", 60000)
    check(DungeonLfgOffer.parse(lfg.message(), 0) == lfg)
    check(DungeonLfgOffer.parse("yes", 0) == null)
    check(DungeonLfgOffer.parse(lfg.message().replace("F7", "F8"), 0) == null)
    check(DungeonJoinRequest.parse(request.message()) == request)
    check(DungeonJoinRequest.parse(request.message().replace("F7", "M8")) == null)
    check(DungeonJoinRequest.parse(request.message().replace("Archer", "Unknown")) == null)
    check(DungeonJoinRequest.parse(request.message() + "\n/p Alice") == null)
    val client = DungeonFriendJoining()
    val host = DungeonFriendJoining()
    val clientContext = JoinPartyContext(available, true, true, 1, F7, setOf(HEALER, ARCHER, TANK), setOf("self"))
    check(clientContext.policy.open)
    check(!clientContext.copy(availability = DungeonAvailability()).policy.open)
    check(!clientContext.copy(partySize = 5).policy.open)
    val hostContext = clientContext.copy(solo = false, partySize = 4, missing = setOf(TANK), members = setOf("host", "bob", "carol", "dave"))
    check(client.request("Host", request, 100) == "msg Host ${request.message()}")
    check(host.receiveRequest("Self", request, 200))
    check(!host.receiveRequest("SELF", request, 300))
    check(host.nextCommand(hostContext, 300) { null } == null)
    check(host.nextCommand(hostContext, 300) { hidden } == null)
    check(host.nextCommand(hostContext, 300) { known.copy(sPlusTimes = mapOf(F7 to 420001)) } == null)
    check(host.nextCommand(hostContext.copy(partySize = 5), 300) { known } == null)
    check(host.nextCommand(hostContext.copy(canInvite = false), 300) { known } == null)
    check(host.nextCommand(hostContext.copy(floor = M7), 300) { known } == null)
    val offer = host.nextCommand(hostContext, 400) { known }!!
    check(offer == "msg self Inviting you for F7 as Tank [SkyMyce Ready ${request.token}]")
    client.receiveOffer("Host", offer.removePrefix("msg self "), 500)
    check(client.nextCommand(clientContext, 600) { known } == null) // A private offer is not a server invitation.
    check(host.nextCommand(hostContext.copy(missing = emptySet()), 600) { known } == null)
    check(host.nextCommand(hostContext, 700) { known } == null) // Wait for the recipient's mod, not just the relay server.
    host.acknowledged("Self", "ffffffffffffffff")
    host.failed("Self", "ffffffffffffffff")
    check(host.nextCommand(hostContext, 800) { known } == null) // Old receipts/failures cannot affect a newer exchange.
    host.acknowledged("Self", request.token)
    check(host.nextCommand(hostContext, 1500) { known } == "party invite self")
    check(host.nextCommand(hostContext, 1600) { known } == null)
    check(host.classFor("SELF") == TANK)
    check(host.receiveRequest("Other", request, 1600))
    check(host.nextCommand(hostContext, 1700) { known } == null) // Reserve both the last slot and offered class.
    check(host.nextCommand(hostContext.copy(partySize = 3), 1700) { known } == null) // A free seat cannot reuse a reserved class.
    client.invited("Stranger", 1700)
    check(client.nextCommand(clientContext, 1800) { known } == null)
    client.invited("Host", 1800)
    check(client.nextCommand(clientContext.copy(solo = false), 1900) { known } == null)
    check(client.nextCommand(clientContext.copy(availability = available.copy(classes = emptySet())), 1900) { known } == null)
    check(client.nextCommand(clientContext, 2000) { hidden } == "party accept host") // Receiving an invitation never gates on the inviter's PB.
    check(client.accepted == "host" to (F7 to TANK))
    check(client.nextCommand(clientContext, 2001) { known } == null)
    client.clear()
    client.request("Host", request, 3000)
    client.receiveOffer("Stranger", offer.removePrefix("msg self "), 3001)
    client.receiveOffer("Host", offer.removePrefix("msg self ").replace(request.token, "ffffffffffffffff"), 3001)
    client.invited("Host", 3002)
    check(client.nextCommand(clientContext, 3003) { known } == null) // Stale or unrelated relay offers cannot authorize acceptance.
    client.receiveOffer("Host", offer.removePrefix("msg self "), 3004)
    check(client.nextCommand(clientContext, 3005) { known } == null) // The earlier unapproved invite was not queued.
    client.invited("Host", 3006)
    check(client.nextCommand(clientContext, 3007) { known } == "party accept host")
    check(client.accepted?.second?.second == TANK)
    client.clear()
    client.invited("Host", 3000)
    check(client.nextCommand(clientContext, 3001) { known } == null) // Available alone must not accept a manual Party invite.
    client.receiveOffer("Host", offer.removePrefix("msg self "), 3002)
    client.invited("Host", 3003)
    check(client.nextCommand(clientContext, 3004) { known } == null) // Even a relay offer needs local Yes/Join consent.
    client.clear()
    client.request("Host", request, 4000)
    client.receiveRequest("Other", request, 4001)
    check(client.nextCommand(clientContext, 4002) { known } == null) // Do not host while waiting to join.
    client.prune(64000)
    check(!client.busy(64000) && client.status == "Join request expired")
    client.clear()
    client.request("Host", request, 0, manual = true)
    client.receiveOffer("Host", offer.removePrefix("msg self "), 0)
    client.invited("Host", 0)
    check(client.nextCommand(clientContext, 55000) { known } == null)
    client.invited("Host", 60000)
    check(client.nextCommand(clientContext, 60001) { known } == null) // Expired consent is not reusable.
    host.prune(62000)
    check(host.classFor("Self") == null)

    // Explicit Yes consents to this host even with background availability off.
    val manualClient = DungeonFriendJoining()
    val manualHost = DungeonFriendJoining()
    val manualContext = clientContext.copy(availability = DungeonAvailability())
    val manualHostContext = manualContext.copy(members = setOf("host"))
    manualClient.request("Host", request, 100, manual = true)
    check(!manualClient.expectsInvite("Host", 101)) // Wait for the host's matching relay agreement.
    check(manualHost.receiveRequest("Self", request, 101, manual = true))
    val manualOffer = manualHost.nextCommand(manualHostContext, 102) { null }!!
    manualClient.receiveOffer("Host", manualOffer.removePrefix("msg self "), 103)
    check(manualClient.expectsInvite("Host", 103) && !manualClient.expectsInvite("Stranger", 103))
    check(manualHost.nextCommand(manualHostContext, 104) { null } == null)
    manualHost.acknowledged("Self", request.token)
    check(manualHost.nextCommand(manualHostContext, 105) { null } == "party invite self")
    manualClient.invited("Stranger", 105)
    check(manualClient.nextCommand(manualContext, 106) { null } == null)
    manualClient.invited("Host", 107)
    check(manualClient.nextCommand(manualContext, 108) { null } == "party accept host")
    val privateReply = DungeonFriendJoining()
    privateReply.receiveAcceptedReply("Self", request, 0)
    check(privateReply.nextCommand(manualHostContext, 1) { null } == "party invite self")
    check(privateReply.nextCommand(manualHostContext, 2) { null } == null)
    val guarded = DungeonFriendJoining()
    guarded.request("Host", request, 0, manual = true)
    guarded.invited("Host", 1)
    check(guarded.nextCommand(clientContext, 2) { hidden } == null) // Yes alone does not approve an unrelated/manual invite.
    guarded.receiveOffer("Host", offer.removePrefix("msg self "), 3)
    guarded.invited("Host", 4)
    check(guarded.nextCommand(clientContext, 5) { hidden } == "party accept host") // Agreed invitations bypass the Join PB requirement.
    val inviting = DungeonFriendJoining()
    inviting.receiveAcceptedReply("Self", request, 0)
    check(inviting.nextCommand(hostContext, 1) { hidden } == "party invite self") // An invited player need not meet the host's Join PB.
    val clickingJoin = DungeonFriendJoining()
    clickingJoin.request("Host", request, 0, manual = true)
    clickingJoin.receiveOffer("Host", offer.removePrefix("msg self "), 1)
    clickingJoin.invited("Host", 1)
    check(clickingJoin.nextCommand(manualContext, 2) { null } == "party accept host") // Join works with Available off.

    val listing = partyListingFromLore(10, null, listOf("§7Dungeon: §bMaster Mode", "§7Floor: §bFloor VII", "Members:",
        "§b[MVP+] Leader: Mage (50)", "Alice: Berserk (49)", "Bob: Healer (48)", "Note: quick runs", "Empty"))!!
    check(listing.floor == M7 && listing.leaderName == "Leader")
    check(listing.memberClasses == mapOf("Leader" to MAGE, "Alice" to BERSERKER, "Bob" to HEALER))
    check(partyListingFloor(listOf("Dungeon: The Catacombs", "Floor: Floor IV")) == F4)
    check(partyListingFloor(listOf("Floor: Entrance")) == null)
    val party = DungeonFriendParty()
    party.roster(listing.memberUsername + "Self", 4, true)
    party.classes.putAll(listing.memberClasses.mapKeys { it.key.lowercase() } + ("self" to ARCHER))
    party.roster(listOf("Self", "Leader"), 4, completeNames = false)
    check(party.openClasses == setOf(TANK) && party.members.size == 4)
    val saved = SavedDungeonParty("leader", party.members.toSet(), party.classes.toMap(), M7, 1000)
    val restored = Gson().fromJson(Gson().toJson(saved), SavedDungeonParty::class.java)
    check(saved == restored && restored.matches("Leader", setOf("Leader", "Self", "Alice", "Bob"), 1001))
    check(!restored.matches("Other", party.members, 1001) && !restored.matches("Leader", setOf("Self", "Leader"), 1001))
    check(!restored.matches("Leader", party.members, 86401001) && !restored.matches("Leader", party.members, 999))
    party.roster(listOf("Self", "Leader", "Bob"), 3)
    check(party.openClasses == setOf(BERSERKER, TANK))
    check(party.chat("You left the party.", "Self") && party.classes.isEmpty())
}
