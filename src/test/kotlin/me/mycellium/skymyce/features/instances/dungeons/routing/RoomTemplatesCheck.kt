package me.mycellium.skymyce.features.instances.dungeons.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Synthetic data exercises the format; it makes no claim about real Hypixel room layouts. */
private fun syntheticCatalog(): JsonObject {
    val root = JsonParser.parseString("""{
      "schema":1,"grid":{"spacing":32,"offsetX":-128,"offsetZ":-96,"originY":64},
      "rooms":[{"id":"synthetic-room","floors":["E","F1","F2","F3","F4","F5","F6","F7","M1","M2","M3","M4","M5","M6","M7"],
        "bounds":{"min":[0,0,0],"max":[5,1,4]},
        "landmarks":[{"cell":[0,1,0],"block":"minecraft:stone"},
                     {"cell":[5,1,0],"block":"minecraft:andesite"},
                     {"cell":[1,1,4],"block":"minecraft:sandstone"}],
        "walkable":[],"edges":[],
        "targets":[{"id":"a","cell":[1,0,1]},{"id":"b","cell":[4,0,3]}],"healer":[5,0,4]}]
    }""").asJsonObject
    val room = root.getAsJsonArray("rooms")[0].asJsonObject
    val walkable = room.getAsJsonArray("walkable")
    val edges = room.getAsJsonArray("edges")
    for (x in 0..5) for (z in 0..4) {
        walkable.add(jsonCell(x, 0, z))
        if (x < 5) edges.add(JsonArray().apply { add(jsonCell(x, 0, z)); add(jsonCell(x + 1, 0, z)) })
        if (z < 4) edges.add(JsonArray().apply { add(jsonCell(x, 0, z)); add(jsonCell(x, 0, z + 1)) })
    }
    return root
}

private fun jsonCell(x: Int, y: Int, z: Int) = JsonArray().apply { add(x); add(y); add(z) }
private fun JsonObject.parseCatalog() = RoomTemplateCatalog.parse(toString().toByteArray(Charsets.UTF_8))
private fun JsonObject.room() = getAsJsonArray("rooms")[0].asJsonObject
private fun reject(change: (JsonObject) -> Unit) {
    val input = syntheticCatalog()
    change(input)
    check(runCatching { input.parseCatalog() }.exceptionOrNull() is IllegalArgumentException)
}

fun main() {
    dungeonRoomContextChecks()
    dungeonStarMobChecks()
    val catalog = syntheticCatalog().parseCatalog()
    val room = catalog.rooms.single()
    check(room.id == "synthetic-room" && room.walkable.size == 30 && room.targets.size == 2)
    check(room.bounds.containsPlayer(room.bounds.min, false) && room.bounds.containsPlayer(room.bounds.max, false))
    for (height in 2..3) {
        val jumping = GridCell(3, height, 2)
        check(room.bounds.containsPlayer(jumping, true) && !room.bounds.containsPlayer(jumping, false))
        check(!room.bounds.contains(jumping))
    }
    check(!room.bounds.containsPlayer(GridCell(3, 4, 2), true))
    check(!room.bounds.containsPlayer(GridCell(3, -1, 2), true))
    for (outside in listOf(GridCell(-1, 0, 2), GridCell(6, 2, 2), GridCell(3, 0, -1), GridCell(3, 2, 5))) {
        check(!room.bounds.containsPlayer(outside, true) && !room.bounds.containsPlayer(outside, false))
    }
    check(room.adjacency.getValue(GridCell(1, 0, 1)).size == 4)
    check(room.healer == GridCell(5, 0, 4))
    check(room.targetCounts == mapOf("a" to null, "b" to null))
    val counted = syntheticCatalog().apply {
        room().getAsJsonArray("targets")[0].asJsonObject.addProperty("count", 2)
    }.parseCatalog().rooms.single()
    check(counted.targetCounts == mapOf("a" to 2, "b" to null))
    check(runCatching { (counted.targetCounts as MutableMap<String, Int?>).clear() }
        .exceptionOrNull() is UnsupportedOperationException)
    check(runCatching { (room.walkable as MutableSet<GridCell>).clear() }.exceptionOrNull() is UnsupportedOperationException)
    check(runCatching { (room.adjacency.getValue(GridCell(1, 0, 1)) as MutableSet<GridCell>).clear() }
        .exceptionOrNull() is UnsupportedOperationException)
    check(syntheticCatalog().apply { add("rooms", JsonArray()) }.parseCatalog().rooms.isEmpty())

    for (rotation in 0..3) for (origin in listOf(GridCell(-128, 64, -96), GridCell(-32, 64, -192), GridCell(64, 64, 64))) {
        val transform = RoomTransform(origin, rotation)
        val landmarks = room.landmarks.associate { transform.toWorld(it.cell) to it.block }
        for (start in listOf(GridCell(0, 0, 0), GridCell(3, 0, 2), GridCell(5, 0, 4))) {
            val world = transform.toWorld(start)
            val player = RoomPoint(world.x + 0.5, world.y + 0.1, world.z + 0.5)
            val reads = mutableMapOf<GridCell, Int>()
            val detected = requireNotNull(catalog.recognize("F1", player) {
                reads[it] = (reads[it] ?: 0) + 1
                landmarks[it] ?: "minecraft:air"
            }) { "Recognition failed for $rotation / $origin / $start" }
            check(detected.key == RoomInstanceKey(room.id, origin, rotation))
            check(reads.values.all { it == 1 })
            for (floor in room.floors) check(catalog.recognize(floor, player) { landmarks[it] ?: "minecraft:air" }?.key == detected.key)
            check(catalog.recognize("F1", player) { null } == null)
            check(catalog.recognize("F1", player) { landmarks[it].takeUnless { it == "minecraft:stone" } } == null)
        }
    }
    var reads = 0
    check(catalog.recognize("F9", RoomPoint(0.0, 64.0, 0.0)) { reads++; "minecraft:air" } == null && reads == 0)
    check(catalog.recognize("F1", RoomPoint(-125.5, 63.9, -93.5)) { reads++; "minecraft:air" } == null && reads == 0)
    val originalTransform = RoomTransform(GridCell(-128, 64, -96), 0)
    val originalBlocks = room.landmarks.associate { originalTransform.toWorld(it.cell) to it.block }
    val ambiguous = syntheticCatalog().apply {
        getAsJsonArray("rooms").add(room().deepCopy().apply { addProperty("id", "other-room") })
    }.parseCatalog()
    check(ambiguous.recognize("F1", RoomPoint(-125.5, 64.1, -93.5)) { originalBlocks[it] ?: "minecraft:air" } == null)
    val airCatalog = syntheticCatalog().apply {
        room().getAsJsonArray("landmarks").forEach { it.asJsonObject.addProperty("block", "minecraft:air") }
    }.parseCatalog()
    check(airCatalog.recognize("F1", RoomPoint(-125.5, 64.1, -93.5)) { null } == null)
    val symmetric = syntheticCatalog().apply {
        room().getAsJsonObject("bounds").add("min", jsonCell(-4, 0, -4))
        for (x in -4..4) for (z in -4..4) if (x < 0 || z < 0) room().getAsJsonArray("walkable").add(jsonCell(x, 0, z))
        room().add("landmarks", JsonArray().apply {
            for ((x, z) in listOf(4 to 0, 0 to 4, -4 to 0, 0 to -4)) add(JsonObject().apply {
                add("cell", jsonCell(x, 1, z)); addProperty("block", "minecraft:stone")
            })
        })
    }.parseCatalog()
    val symmetricBlocks = symmetric.rooms.single().landmarks.associate { originalTransform.toWorld(it.cell) to it.block }
    check(symmetric.recognize("F1", RoomPoint(-125.5, 64.1, -93.5)) { symmetricBlocks[it] ?: "minecraft:air" } == null)
    // Two origins separated by a grid tile can also match: ambiguity must suppress either choice.
    val wide = syntheticCatalog().apply {
        room().getAsJsonObject("bounds").add("max", jsonCell(37, 1, 4))
        for (x in 32..37) for (z in 0..4) room().getAsJsonArray("walkable").add(jsonCell(x, 0, z))
    }.parseCatalog()
    val secondTransform = RoomTransform(GridCell(-96, 64, -96), 0)
    val repeatedBlocks = originalBlocks + room.landmarks.associate { secondTransform.toWorld(it.cell) to it.block }
    check(wide.recognize("F1", RoomPoint(-94.5, 64.1, -93.5)) { repeatedBlocks[it] ?: "minecraft:air" } == null)

    // The missing quadrant of an L room is inside its bounds, but belongs to an adjacent room.
    val lShapeInput = syntheticCatalog().apply {
        val row = room()
        row.getAsJsonObject("bounds").add("max", jsonCell(63, 1, 63))
        val cells = (0..63).flatMap { x -> (0..63).filter { z -> x <= 1 || z <= 1 }.map { z -> GridCell(x, 0, z) } }.toSet()
        row.add("walkable", JsonArray().apply { cells.forEach { add(jsonCell(it.x, it.y, it.z)) } })
        row.add("edges", JsonArray())
        row.getAsJsonArray("targets")[1].asJsonObject.add("cell", jsonCell(4, 0, 1))
        row.add("healer", jsonCell(5, 0, 1))
        row.getAsJsonArray("landmarks").forEach { it.asJsonObject.addProperty("block", "minecraft:diorite") }
    }
    val lShape = lShapeInput.parseCatalog()
    val lBlocks = lShape.rooms.single().landmarks.associate { originalTransform.toWorld(it.cell) to it.block }
    check(lShape.recognize("F1", RoomPoint(-126.5, 64.1, -92.5)) { lBlocks[it] ?: "minecraft:air" }?.key ==
        RoomInstanceKey(room.id, originalTransform.origin, 0))
    val adjacentPlayer = RoomPoint(-92.5, 64.1, -61.5)
    check(lShape.rooms.single().bounds.contains(originalTransform.toLocal(GridCell(-93, 64, -62))))
    check(lShape.recognize("F1", adjacentPlayer) { lBlocks[it] ?: "minecraft:air" } == null)
    lShapeInput.getAsJsonArray("rooms").add(syntheticCatalog().room().deepCopy().apply { addProperty("id", "adjacent-room") })
    val adjacentCatalog = lShapeInput.parseCatalog()
    val adjacentTransform = RoomTransform(GridCell(-96, 64, -64), 0)
    val adjacentBlocks = lBlocks + room.landmarks.associate { adjacentTransform.toWorld(it.cell) to it.block }
    check(adjacentCatalog.recognize("F1", adjacentPlayer) { adjacentBlocks[it] ?: "minecraft:air" }?.key ==
        RoomInstanceKey("adjacent-room", adjacentTransform.origin, 0))

    reject { it.addProperty("schema", 2) }
    reject { it.remove("grid") }
    reject { it.getAsJsonObject("grid").remove("offsetX") }
    reject { it.getAsJsonObject("grid").addProperty("spacing", 31) }
    reject { it.getAsJsonObject("grid").addProperty("offsetX", 30_000_000) }
    reject { it.getAsJsonObject("grid").addProperty("originY", 4097) }
    reject { it.getAsJsonArray("rooms").add(it.room().deepCopy()) }
    reject { it.room().addProperty("id", "Invalid ID") }
    reject { it.room().add("floors", JsonArray().apply { add("F8") }) }
    reject { it.room().add("floors", JsonArray().apply { add("F1"); add("F1") }) }
    reject { it.room().add("floors", JsonArray()) }
    reject { it.room().getAsJsonObject("bounds").add("max", jsonCell(128, 1, 4)) }
    reject { it.room().getAsJsonObject("bounds").add("max", jsonCell(-1, 1, 4)) }
    reject { it.room().getAsJsonArray("landmarks").remove(2) }
    reject { it.room().getAsJsonArray("landmarks")[2].asJsonObject.add("cell", jsonCell(2, 1, 0)) }
    reject { it.room().getAsJsonArray("landmarks")[2].asJsonObject.add("cell", jsonCell(5, 1, 0)) }
    reject { it.room().getAsJsonArray("landmarks")[0].asJsonObject.addProperty("block", "Minecraft:Stone") }
    reject { it.room().getAsJsonArray("landmarks")[0].asJsonObject.add("cell", jsonCell(6, 1, 0)) }
    reject { it.room().getAsJsonArray("walkable").add(jsonCell(0, 0, 0)) }
    reject { it.room().getAsJsonArray("walkable").add(jsonCell(6, 0, 0)) }
    reject { it.room().getAsJsonArray("walkable")[0].asJsonArray[0] = com.google.gson.JsonPrimitive(1.5) }
    reject { it.room().getAsJsonArray("walkable")[0].asJsonArray[0] = com.google.gson.JsonPrimitive("1") }
    reject { it.room().getAsJsonArray("walkable")[0].asJsonArray[0] = com.google.gson.JsonPrimitive(2_147_483_648L) }
    reject { it.room().getAsJsonArray("targets").add(it.room().getAsJsonArray("targets")[0].deepCopy()) }
    reject { it.room().getAsJsonArray("targets")[0].asJsonObject.add("cell", jsonCell(0, 1, 0)) }
    for (count in listOf(0, -1, 129)) {
        reject { it.room().getAsJsonArray("targets")[0].asJsonObject.addProperty("count", count) }
    }
    reject { it.room().getAsJsonArray("targets")[0].asJsonObject.addProperty("count", "2") }
    reject { it.room().getAsJsonArray("targets")[0].asJsonObject.addProperty("count", 1.5) }
    reject { it.room().getAsJsonArray("targets")[0].asJsonObject.add("count", com.google.gson.JsonNull.INSTANCE) }
    reject { it.room().add("healer", jsonCell(0, 1, 0)) }
    reject { it.room().remove("edges") }
    reject { it.room().getAsJsonArray("edges").add(JsonArray().apply { add(jsonCell(0, 0, 0)); add(jsonCell(1, 0, 1)) }) }
    reject { it.room().getAsJsonArray("edges").add(JsonArray().apply { add(jsonCell(0, 0, 0)); add(jsonCell(0, 0, 0)) }) }
    reject { it.room().getAsJsonArray("edges").add(JsonArray().apply { add(jsonCell(0, 0, 0)); add(jsonCell(6, 0, 0)) }) }
    reject { it.room().getAsJsonArray("edges").add(JsonArray().apply { add(jsonCell(1, 0, 0)); add(jsonCell(0, 0, 0)) }) }
    reject { root -> repeat(256) { n -> root.getAsJsonArray("rooms").add(root.room().deepCopy().apply { addProperty("id", "room-$n") }) } }
    reject { root -> repeat(13) { n -> root.room().getAsJsonArray("targets").add(JsonObject().apply {
        addProperty("id", "target-$n"); add("cell", jsonCell(0, 0, 0))
    }) } }
    reject { root -> repeat(16_385) { root.room().getAsJsonArray("walkable").add(jsonCell(0, 0, 0)) } }
    reject { root -> repeat(65_537) { root.room().getAsJsonArray("edges").add(JsonArray()) } }
    val large = syntheticCatalog().apply {
        val row = room()
        row.getAsJsonObject("bounds").add("max", jsonCell(127, 1, 127))
        row.add("walkable", JsonArray().apply { for (x in 0..127) for (z in 0..127) add(jsonCell(x, 0, z)) })
        row.add("edges", JsonArray())
        val prototype = row.deepCopy()
        for (n in 1..7) getAsJsonArray("rooms").add(prototype.deepCopy().apply { addProperty("id", "large-$n") })
    }
    check(large.parseCatalog().rooms.sumOf { it.walkable.size } == 131_072)
    large.getAsJsonArray("rooms").add(large.room().deepCopy().apply { addProperty("id", "over-limit") })
    check(runCatching { large.parseCatalog() }.exceptionOrNull() is IllegalArgumentException)
    check(runCatching { RoomTemplateCatalog.parse(ByteArray(RoomTemplateCatalog.MAX_BYTES + 1)) }.exceptionOrNull() is IllegalArgumentException)
    check(runCatching { RoomTemplateCatalog.parse(byteArrayOf(0xC3.toByte(), 0x28)) }.exceptionOrNull() is IllegalArgumentException)
    for (invalid in listOf("", "{schema:1}", "{\"schema\":1,}", syntheticCatalog().toString() + " {}", "[".repeat(17) + "]".repeat(17))) {
        check(runCatching { RoomTemplateCatalog.parse(invalid.toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
    val raw = syntheticCatalog().toString()
    for (invalid in listOf(
        raw.replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1"),
        raw.replaceFirst("\"grid\":{", "\"grid\":null,\"grid\":{"),
        raw.replaceFirst("\"id\":\"synthetic-room\"", "\"id\":null,\"id\":\"synthetic-room\""),
        raw.replaceFirst("\"id\":\"a\"", "\"id\":\"a\",\"id\":\"other\""),
        raw.replaceFirst("\"schema\":1", "\"schema\":1.0"),
        raw.replaceFirst("\"schema\":1", "\"schema\":1e0"),
    )) check(runCatching { RoomTemplateCatalog.parse(invalid.toByteArray()) }.exceptionOrNull() is IllegalArgumentException)

    val states = RoomTargetStates(mapOf("a" to 2, "b" to 1))
    check(states.state("a") == RoomTargetState.UNKNOWN && states.remaining() == setOf("a", "b"))
    check(states.observe("a", 1) && states.state("a") == RoomTargetState.ACTIVE)
    check(states.targetFor(1) == "a" && states.targetFor(999) == null)
    check(!states.observe("a", 1))
    check(states.removed(1, false) && states.state("a") == RoomTargetState.UNKNOWN)
    check(states.targetFor(1) == "a")
    check(states.observe("a", 1))
    check(!states.observe("a", 2))
    check(!states.removed(1, true) && states.state("a") == RoomTargetState.ACTIVE)
    check(states.removed(2, false) && states.state("a") == RoomTargetState.UNKNOWN)
    check(states.observe("a", 2))
    check(states.removed(2, true) && states.state("a") == RoomTargetState.CLEARED && states.remaining() == setOf("b"))
    check(!states.removed(2, false) && !states.observe("a", 2) && !states.unloaded())
    check(states.observe("b", 3) && states.unloaded() && states.state("b") == RoomTargetState.UNKNOWN)
    check(states.targetFor(3) == "b" && states.targetFor(2) == "a")
    check(states.state("a") == RoomTargetState.CLEARED)
    check(states.observe("a", 4) && states.state("a") == RoomTargetState.ACTIVE)
    check(states.removed(4, true) && states.state("a") == RoomTargetState.UNKNOWN) // More members than authored cannot prove a clear.
    check(!states.removed(999, true) && !states.observe("missing", 5))
    states.reset()
    check(states.targetFor(1) == null && states.targetFor(2) == null && states.targetFor(3) == null)
    check(states.remaining() == setOf("a", "b") && states.state("a") == RoomTargetState.UNKNOWN)
    repeat(129) { states.observe("a", it) }
    repeat(129) { states.removed(it, true) }
    check(states.state("a") == RoomTargetState.UNKNOWN) // A bounded-out observation cannot be called cleared.
    states.reset()
    check(states.observe("a", 1))
    check(!states.observe("b", 1) && states.state("b") == RoomTargetState.UNKNOWN)
    val delayed = RoomTargetStates(mapOf("group" to 2))
    check(delayed.observe("group", 10) && delayed.removed(10, true))
    check(delayed.state("group") == RoomTargetState.UNKNOWN && delayed.remaining() == setOf("group"))
    check(delayed.observe("group", 11) && delayed.removed(11, true))
    check(delayed.state("group") == RoomTargetState.CLEARED && delayed.remaining().isEmpty())
    check(!delayed.unloaded() && delayed.state("group") == RoomTargetState.CLEARED)
    val unknownCount = RoomTargetStates(listOf("group"))
    check(unknownCount.observe("group", 10) && unknownCount.removed(10, true))
    check(unknownCount.state("group") == RoomTargetState.UNKNOWN && unknownCount.remaining() == setOf("group"))
    for (invalid in listOf(0, -1, 129)) check(runCatching { RoomTargetStates(mapOf("group" to invalid)) }.isFailure)
    check(runCatching { RoomTargetStates(listOf("group", "group")) }.isFailure)

    fun draft(rotation: Int = 0): RoomTemplateDraft {
        val transform = RoomTransform(GridCell(-128, 64, -96), rotation)
        return RoomTemplateDraft("recorded-room", setOf("F1"), catalog.grid, transform).apply {
            corner(transform.toWorld(room.bounds.min))
            corner(transform.toWorld(room.bounds.max))
            for (landmark in room.landmarks) this.landmark(transform.toWorld(landmark.cell), landmark.block)
            for ((id, cell) in room.targets) target(id, transform.toWorld(cell), if (id == "a") 2 else 1)
            healer(transform.toWorld(room.healer))
        }
    }
    for (rotation in 0..3) {
        val recorded = draft(rotation)
        val candidate = recorded.candidateTemplate()
        check(candidate.transform.quarterTurns == rotation && candidate.template.walkable.size == 60)
        check(candidate.template.landmarks == room.landmarks && candidate.template.targets == room.targets)
        check(candidate.template.targetCounts == mapOf("a" to 2, "b" to 1))
        check(candidate.template.adjacency.isEmpty()) // Capture discovers connections only between accepted cells.
        check(runCatching { (candidate.template.targets as MutableMap<String, GridCell>).clear() }
            .exceptionOrNull() is UnsupportedOperationException)
        check(runCatching { recorded.export(null) }.isFailure) // Unchecked candidate geometry is never exportable.
        val cells = room.walkable.toMutableSet()
        val adjacency = room.adjacency.mapValues { it.value.toMutableSet() }
        recorded.geometry(cells, adjacency)
        cells.clear()
        adjacency.values.forEach { it.clear() }
        val exported = recorded.export(null).parseCatalog()
        val template = exported.rooms.single()
        check(template.walkable == room.walkable && template.adjacency == room.adjacency)
        check(template.healer == room.healer && template.targetCounts == mapOf("a" to 2, "b" to 1))
        val merged = recorded.export(catalog).parseCatalog()
        check(merged.rooms.size == 2 && merged.rooms.first().id == room.id)
        check(merged.rooms.first().targetCounts == room.targetCounts && merged.rooms.first().adjacency == room.adjacency)
        check(runCatching { recorded.export(merged) }.isFailure)
        val differentGrid = syntheticCatalog().apply {
            getAsJsonObject("grid").addProperty("offsetX", -127)
        }.parseCatalog()
        check(runCatching { recorded.export(differentGrid) }.isFailure)
    }
    val recorded = draft()
    check(runCatching { recorded.corner(originalTransform.toWorld(room.bounds.min)) }.isFailure)
    check(runCatching { recorded.landmark(originalTransform.toWorld(room.landmarks.first().cell), "minecraft:stone") }.isFailure)
    check(runCatching { recorded.target("a", originalTransform.toWorld(room.targets.getValue("a")), 2) }.isFailure)
    check(runCatching { recorded.target("c", originalTransform.toWorld(GridCell(6, 0, 0)), 1) }.isFailure)
    check(runCatching { recorded.target("c", originalTransform.toWorld(GridCell(0, 0, 0)), 0) }.isFailure)
    check(runCatching { recorded.geometry(setOf(GridCell(6, 0, 0)), emptyMap()) }.isFailure)
    check(runCatching { recorded.geometry(room.walkable, mapOf(GridCell(0, 0, 0) to setOf(GridCell(1, 0, 1)))) }.isFailure)
    recorded.geometry(room.walkable - room.healer, room.adjacency.mapValues { it.value - room.healer }.filterKeys { it != room.healer })
    check(runCatching { recorded.export(null) }.isFailure) // A blocked healer is not silently moved to a neighboring node.
    recorded.geometry(room.walkable - room.targets.getValue("a"),
        room.adjacency.mapValues { it.value - room.targets.getValue("a") }.filterKeys { it != room.targets.getValue("a") })
    check(runCatching { recorded.export(null) }.isFailure) // A blocked target is not silently omitted.
    val oneWay = draft()
    val a = GridCell(0, 0, 0)
    val b = GridCell(1, 0, 0)
    oneWay.geometry(room.walkable, mapOf(a to setOf(b)))
    check(oneWay.export(null).parseCatalog().rooms.single().adjacency.getValue(b) == setOf(a))
    val countedCatalog = syntheticCatalog().apply {
        room().getAsJsonArray("targets")[0].asJsonObject.addProperty("count", 128)
    }.parseCatalog()
    check(oneWay.export(countedCatalog).parseCatalog().rooms.first().targetCounts == mapOf("a" to 128, "b" to null))
    val tooLarge = RoomTemplateDraft("large", setOf("F1"), catalog.grid, originalTransform)
    tooLarge.corner(originalTransform.toWorld(GridCell(0, 0, 0)))
    check(runCatching { tooLarge.corner(originalTransform.toWorld(GridCell(63, 31, 63))) }.isFailure)
    check(tooLarge.bounds == null)
    tooLarge.corner(originalTransform.toWorld(GridCell(5, 1, 4)))
    check(tooLarge.bounds == room.bounds)
    check(runCatching { tooLarge.candidateTemplate() }.isFailure)
    check(runCatching { RoomTemplateDraft("Invalid ID", setOf("F1"), catalog.grid, originalTransform) }.isFailure)
    check(runCatching { RoomTemplateDraft("invalid-floor", setOf("F8"), catalog.grid, originalTransform) }.isFailure)
    check(runCatching { RoomTemplateDraft("off-grid", setOf("F1"), catalog.grid,
        RoomTransform(GridCell(-127, 64, -96), 0)) }.isFailure)
    println("Room template parsing, capture/export, rotated recognition and target lifecycle checks passed")
}

private fun dungeonRoomContextChecks() {
    check(dungeonRoutingActive(inDungeon = true, knownFloor = true, completed = false, inBoss = false)) // Waiting lobby and live run share the known-floor gate.
    check(!dungeonRoutingActive(inDungeon = false, knownFloor = true, completed = false, inBoss = false))
    check(!dungeonRoutingActive(inDungeon = true, knownFloor = false, completed = false, inBoss = false))
    check(!dungeonRoutingActive(inDungeon = true, knownFloor = true, completed = true, inBoss = false))
    check(!dungeonRoutingActive(inDungeon = true, knownFloor = true, completed = false, inBoss = true))
    val layout = DungeonMapLayout(5, 5, 16, -200, -200)
    fun blank() = ByteArray(16384)
    fun tile(colours: ByteArray, x: Int, z: Int, colour: Int = 63) {
        for (dx in 0 until layout.roomSize) for (dz in 0 until layout.roomSize)
            colours[(5 + x * layout.stride + dx) + (5 + z * layout.stride + dz) * 128] = colour.toByte()
    }
    fun door(colours: ByteArray, x: Int, z: Int, dx: Int, dz: Int, colour: Int, wide: Boolean = false) {
        val a = RoomSegment(5 + x * layout.stride, 5 + z * layout.stride)
        val leftX = a.x + if (dx > 0) layout.roomSize else if (dx < 0) -4 else 0
        val topZ = a.z + if (dz > 0) layout.roomSize else if (dz < 0) -4 else 0
        val offsets = if (wide) 0 until layout.roomSize else layout.roomSize / 2 - 1..layout.roomSize / 2 + 1
        for (gap in 0..3) for (offset in offsets) {
            val px = leftX + if (dx != 0) gap else offset
            val pz = topZ + if (dz != 0) gap else offset
            colours[px + pz * 128] = colour.toByte()
        }
    }
    check(dungeonGridSegment(-184.5, -184.5) == RoomSegment(-200, -200))
    check(dungeonGridSegment(-200.51, -200.51) == RoomSegment(-232, -232))
    check(dungeonGridSegment(7.5, 7.5) == RoomSegment(-8, -8))
    check(dungeonGridSegment(23.5, 23.5) == RoomSegment(24, 24))
    val boundaryRoom = LiveDungeonRoom("boundary", "Dungeon room", setOf(RoomSegment(-8, -8)), false, null)
    for (coordinate in listOf(-8.5001, -8.5, 23.4999, 23.5)) {
        val inside = coordinate >= -8.5 && coordinate < 23.5
        check(boundaryRoom.containsPositionXZ(coordinate, 0.0) == inside)
        check(boundaryRoom.containsPositionXZ(0.0, coordinate) == inside)
        check((dungeonGridSegment(coordinate, 0.0) == RoomSegment(-8, -8)) == inside)
    }
    check(!boundaryRoom.containsXZ(-9, 0) && !boundaryRoom.containsXZ(24, 0)) // Collision-cell bounds stay exact.

    val shape = blank()
    tile(shape, 1, 1); tile(shape, 2, 1); tile(shape, 1, 2); tile(shape, 2, 2)
    door(shape, 1, 1, 1, 0, 63, true); door(shape, 1, 1, 0, 1, 63, true)
    door(shape, 2, 1, 0, 1, 63) // A normal door must not merge the fourth tile into the L room.
    val room = requireNotNull(dungeonMapRoom(shape, layout, RoomSegment(-168, -168)))
    check(room.segments == setOf(RoomSegment(-168, -168), RoomSegment(-136, -168), RoomSegment(-168, -136)))
    check(room.containsXZ(-137, -137)) // Shared interior corridor.
    check(!room.containsXZ(-135, -135)) // The separate room in the missing quadrant.
    check(!room.cleared)
    shape[(25 + 8) + (25 + 8) * 128] = 34
    check(requireNotNull(dungeonMapRoom(shape, layout, RoomSegment(-168, -168))).cleared)
    val insetSeparator = blank()
    tile(insetSeparator, 1, 1); tile(insetSeparator, 2, 1); tile(insetSeparator, 2, 2, 18)
    door(insetSeparator, 1, 1, 1, 0, 63, true); door(insetSeparator, 2, 1, 0, 1, 18)
    for (gap in 0..3) for (offset in listOf(0, 1, 2, 3, 12, 13, 14, 15))
        insetSeparator[(25 + layout.roomSize + gap) + (25 + offset) * 128] = 0
    val insetRoom = requireNotNull(dungeonMapRoom(insetSeparator, layout, RoomSegment(-168, -168)))
    check(insetRoom.segments == setOf(RoomSegment(-168, -168), RoomSegment(-136, -168)))
    check(insetRoom.exit?.doors?.single()?.from == insetRoom.segments) // Side sampling handles separators without coloured outer corners.

    for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
        val colours = blank()
        tile(colours, 2, 2); tile(colours, 2 + dx, 2 + dz, 18)
        door(colours, 2, 2, dx, dz, 119)
        val exit = requireNotNull(dungeonMapExit(colours, layout, setOf(RoomSegment(-136, -136))))
        check(exit.kind == "Wither door")
        check(exit.door == GridCell(-121 + dx * 16, 69, -121 + dz * 16))
        check(exit.approach == GridCell(exit.door.x - dx * 3, 69, exit.door.z - dz * 3))
        check(dungeonExitApproach(exit.approach, exit))
        check(dungeonExitApproach(exit.approach.copy(x = exit.approach.x + dx * 2, z = exit.approach.z + dz * 2), exit))
        check(dungeonExitApproach(exit.approach.copy(x = exit.approach.x + dz * 2, y = 75, z = exit.approach.z + dx * 2), exit))
        check(!dungeonExitApproach(exit.door, exit))
        check(!dungeonExitApproach(exit.door.copy(x = exit.door.x + dx, z = exit.door.z + dz), exit))
        check(!dungeonExitApproach(exit.approach.copy(x = exit.approach.x - dx * 3, z = exit.approach.z - dz * 3), exit))
        check(!dungeonExitApproach(exit.approach.copy(y = 76), exit))
        val nearbyApproach = exit.approach.copy(x = exit.approach.x + dz, z = exit.approach.z + dx)
        val approachGraph = requireNotNull(RoomNavigation.create(setOf(nearbyApproach), emptyMap(), emptyMap(),
            healer = nearbyApproach))
        check(dungeonExitApproach(approachGraph.healer!!, exit) && approachGraph.plan(nearbyApproach, emptySet()) != null)
        val departure = exit.doors.single().departure
        fun approachSurface(cell: GridCell) = cell.takeIf { it == nearbyApproach || it == departure }
            ?.let { RoomPoint(it.x + .5, it.y.toDouble(), it.z + .5) }
        check(approachSurface(exit.approach) == null) // A blocked canonical column does not reject an already checked nearby graph endpoint.
        val approachContinuation = DungeonRouteContinuation(::approachSurface, { _, _ -> false }, simplify = { it })
        approachContinuation.update(exit.copy(doors = exit.doors.map { it.copy(closed = false) }))
        val unknownCrossing = approachContinuation.sections(approachSurface(nearbyApproach)).single()
        check(unknownCrossing.gate && !unknownCrossing.checked &&
            unknownCrossing.points[1] == RoomPoint(exit.door.x + .5, exit.door.y.toDouble(), exit.door.z + .5))
        check(dungeonMapExit(colours, layout, setOf(RoomSegment(-136 + dx * 32, -136 + dz * 32))) == null)
        door(colours, 2, 2, dx, dz, 18)
        val blood = requireNotNull(dungeonMapRoom(colours, layout, RoomSegment(-136, -136)))
        val bloodExit = requireNotNull(blood.exit)
        check(bloodExit.door == exit.door && bloodExit.doors.single().kind == "Blood door" && bloodExit.reachesBlood)
        check(dungeonRouteVisible(blood) && dungeonRouteVisible(blood, bloodOpened = false))
        check(!dungeonRouteVisible(blood, bloodOpened = true))
        check(!dungeonRouteVisible(dungeonMapRoom(colours, layout, RoomSegment(-136 + dx * 32, -136 + dz * 32))))
        val opened = requireNotNull(dungeonMapExit(colours, layout, blood.segments) { 0 })
        check(opened.reachesBlood && !opened.doors.single().closed) // Opening changes walkability, not the confirmed connection.
    }
    val topology = blank()
    for ((x, z) in listOf(2 to 1, 1 to 1, 1 to 0, 2 to 0, 3 to 0)) tile(topology, x, z)
    tile(topology, 3, 1, 18)
    door(topology, 2, 1, -1, 0, 119)
    door(topology, 1, 1, 0, -1, 63)
    door(topology, 1, 0, 1, 0, 63)
    door(topology, 2, 0, 1, 0, 63)
    door(topology, 3, 0, 0, 1, 18)
    // Blood is immediately east but separated by a wall; the verified map route begins west.
    val aroundWall = requireNotNull(dungeonMapExit(topology, layout, setOf(RoomSegment(-136, -168))))
    check(aroundWall.door == GridCell(-137, 69, -153) && aroundWall.reachesBlood && aroundWall.doors.size == 5)
    check(aroundWall.doors.map { it.door }.distinct().size == 5)
    check(aroundWall.doors.zipWithNext().all { (a, b) -> a.to == b.from })
    check(requireNotNull(dungeonMapExit(topology, layout, setOf(RoomSegment(-136, -168))) { cell ->
        if (cell == GridCell(-105, 69, -153)) 119 else null
    }).door == GridCell(-137, 69, -153)) // A coal wall without a map doorway must not become an exit.

    val unknown = blank()
    tile(unknown, 1, 1); tile(unknown, 2, 1, 85); door(unknown, 1, 1, 1, 0, 119)
    check(requireNotNull(dungeonMapExit(unknown, layout, setOf(RoomSegment(-168, -168)))).kind == "Wither door")
    tile(unknown, 1, 2, 85); door(unknown, 1, 1, 0, 1, 119)
    check(dungeonMapExit(unknown, layout, setOf(RoomSegment(-168, -168))) == null)
    check(requireNotNull(dungeonMapRoom(unknown, layout, RoomSegment(-136, -168))).let { !it.cleared && it.name == "Dungeon room" })
    check(dungeonMapRoom(ByteArray(10), layout, RoomSegment(-168, -168)) == null)

    val gateBlocks = mutableMapOf<GridCell, Int>()
    val gateRoom = setOf(RoomSegment(-168, -168))
    fun gate(x: Int, z: Int, dx: Int, dz: Int, colour: Int) {
        for (dy in 0..3) for (across in -1..1) gateBlocks[GridCell(x + dz * across, 69 + dy, z + dx * across)] = colour
    }
    gate(-137, -153, 1, 0, 119)
    check(requireNotNull(dungeonClosedExit(gateRoom) { gateBlocks[it] }).approach == GridCell(-140, 69, -153))
    gate(-137, -153, 1, 0, 18)
    check(requireNotNull(dungeonClosedExit(gateRoom) { gateBlocks[it] }).kind == "Blood door")
    gate(-137, -153, 1, 0, 119)
    gateBlocks.remove(GridCell(-137, 72, -154))
    check(dungeonClosedExit(gateRoom) { gateBlocks[it] } == null) // Incomplete or unloaded gate.
    gate(-137, -153, 1, 0, 119); gate(-153, -137, 0, 1, 119)
    check(dungeonClosedExit(gateRoom) { gateBlocks[it] } == null) // Unproven direction between two closed gates.

    val fairyMap = blank()
    tile(fairyMap, 1, 1); tile(fairyMap, 2, 1, 82); tile(fairyMap, 3, 1); tile(fairyMap, 4, 1, 18)
    // Hypixel uses the adjoining room's color for ordinary doors, including Fairy pink (82).
    door(fairyMap, 1, 1, 1, 0, 82); door(fairyMap, 2, 1, 1, 0, 119); door(fairyMap, 3, 1, 1, 0, 18)
    val beforeFairy = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-168, -168)))
    val fairy = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-136, -168)))
    val fairyExit = requireNotNull(beforeFairy.exit)
    val fairyGate = fairyExit.doors[1]
    check(fairyExit.door == GridCell(-137, 69, -153) && fairyExit.approach == GridCell(-140, 69, -153))
    check(fairyExit.via.isEmpty() && fairy.fairy && !beforeFairy.fairy)
    check(!beforeFairy.containsXZ(-120, -153) && !beforeFairy.containsRouteXZ(-120, -153))
    check(fairyExit.reachesBlood && fairyExit.doors.map { it.door } == listOf(GridCell(-137, 69, -153), GridCell(-105, 69, -153), GridCell(-73, 69, -153)))
    check(fairyExit.doors.first().to == fairy.segments && fairyGate.from == fairy.segments && fairyGate.closed)
    check(fairyGate.approach == GridCell(-108, 69, -153)) // Fairy's next gate is known before entering it.
    check(dungeonRouteVisible(beforeFairy) && dungeonRouteVisible(fairy))
    check(!dungeonRouteVisible(beforeFairy, bloodOpened = true) && !dungeonRouteVisible(fairy, bloodOpened = true))
    check(!dungeonRouteVisible(null, bloodOpened = false) && !dungeonRouteVisible(null, bloodOpened = true))
    check(!dungeonRouteVisible(beforeFairy.copy(exit = null)))
    check(dungeonContinueFairyRoute(fairyMap, layout, fairy, beforeFairy).key == fairy.key)
    fairyMap[53 + 33 * 128] = 34
    check(requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-136, -168))).cleared)
    // Independent solids: the Fairy tree blocks the direct line; the entrance is only three blocks wide.
    val fairySolids = listOf(
        GridCell(-128, 69, -158) to GridCell(-115, 80, -148),
        GridCell(-138, 69, -168) to GridCell(-136, 80, -154),
        GridCell(-138, 69, -151) to GridCell(-136, 80, -136),
        GridCell(-106, 69, -154) to GridCell(-103, 73, -151),
    )
    fun fairyFeet(cell: GridCell) = RoomPoint(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
    fun fairyMove(a: GridCell, b: GridCell) = validRoomConnector(fairyFeet(a), fairyFeet(b),
        loaded = { _, _ -> true }, clearSegment = { from, to -> fairySolids.none { (min, max) ->
            minOf(from.x, to.x) - 0.3 < max.x && maxOf(from.x, to.x) + 0.3 > min.x &&
                minOf(from.y, to.y) < max.y && maxOf(from.y, to.y) + 1.8 > min.y &&
                minOf(from.z, to.z) - 0.3 < max.z && maxOf(from.z, to.z) + 0.3 > min.z
        } }, supported = { point, _, radius -> point.y == 69.0 && point.x - radius >= -168 &&
            point.x + radius <= -104 && point.z - radius >= -168 && point.z + radius <= -136 }, climbable = { false })
    val fairySeed = fairyExit.doors.first().departure
    val fairyFlood = RoomLiveGeometry(fairySeed,
        { fairy.containsXZ(it.x, it.z) && it.y in 68..72 }, { fairyMove(it, it) }, ::fairyMove)
    fairyFlood.prioritize(listOf(fairyGate.approach))
    while (!fairyFlood.step(maxCells = 32, maxEdges = 64, maxNanos = Long.MAX_VALUE)) { }
    val fairyGeometry = requireNotNull(fairyFlood.result)
    val fairyNavigation = requireNotNull(RoomNavigation.create(fairyGeometry.cells, fairyGeometry.adjacency, emptyMap(), fairyGate.approach))
    val throughFairy = requireNotNull(fairyNavigation.plan(fairySeed, emptySet()))
    check(throughFairy.path.first() == fairySeed && throughFairy.path.last() == fairyGate.approach && throughFairy.path.any { it.x == -120 })
    check(throughFairy.path.any { it.x in -128..-115 && (it.z < -158 || it.z >= -148) })
    check(throughFairy.path.zipWithNext().all { (a, b) -> fairyMove(a, b) })
    door(fairyMap, 1, 1, 1, 0, 119)
    val closedFairyEntry = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-168, -168))).exit!!
    check(closedFairyEntry.doors.size == 3 && closedFairyEntry.doors.take(2).all { it.closed })
    // Opening the entrance must continue through Fairy even before its map pixel updates.
    val readFairyGate: (GridCell) -> Int? = { cell -> if (cell == GridCell(-137, 69, -153)) 0 else null }
    val openedEntrance = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-168, -168), readFairyGate))
    val openedFairyExit = requireNotNull(openedEntrance.exit)
    check(openedFairyExit.doors.map { it.door } == fairyExit.doors.map { it.door })
    check(!openedFairyExit.doors.first().closed && openedFairyExit.doors[1].closed)
    val insideFairy = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-136, -168), readFairyGate))
    check(dungeonContinueFairyRoute(fairyMap, layout, insideFairy, openedEntrance, readFairyGate).key == insideFairy.key)
    // The next room can be Blood itself; keep the ordinary entrance and final red gate in order.
    tile(fairyMap, 3, 1, 18); door(fairyMap, 2, 1, 1, 0, 18)
    val nearbyBlood = requireNotNull(dungeonMapRoom(fairyMap, layout, RoomSegment(-168, -168), readFairyGate)).exit!!
    check(nearbyBlood.reachesBlood && nearbyBlood.doors.size == 2 && nearbyBlood.doors.last().kind == "Blood door")
    tile(fairyMap, 3, 1); door(fairyMap, 2, 1, 1, 0, 119)
    door(fairyMap, 1, 1, 1, 0, 63); door(fairyMap, 2, 1, 1, 0, 63)
    check(dungeonMapRoom(fairyMap, layout, RoomSegment(-168, -168))?.exit?.doors?.size == 3)
    check(dungeonMapRoom(fairyMap, layout, RoomSegment(-136, -168))?.exit?.doors?.size == 2)

    val partialMap = blank()
    tile(partialMap, 1, 1); tile(partialMap, 2, 1, 82); tile(partialMap, 3, 1, 85)
    door(partialMap, 1, 1, 1, 0, 82); door(partialMap, 2, 1, 1, 0, 119); door(partialMap, 3, 1, 1, 0, 119)
    val partial = requireNotNull(dungeonMapExit(partialMap, layout, setOf(RoomSegment(-168, -168))))
    check(!partial.reachesBlood && partial.doors.size == 3 && partial.doors.last().to.isEmpty())
    check(partial.doors[1].to == setOf(RoomSegment(-104, -168))) // Gray is a known unopened room.
    tile(partialMap, 4, 1, 18)
    val extended = requireNotNull(dungeonMapExit(partialMap, layout, setOf(RoomSegment(-168, -168))))
    check(extended.reachesBlood && extended.doors.size == 3 && extended.doors.map { it.door } == partial.doors.map { it.door })
    door(partialMap, 1, 1, 0, 1, 119)
    // A fully confirmed Blood route wins over a newly seen unrelated frontier.
    check(dungeonMapExit(partialMap, layout, setOf(RoomSegment(-168, -168)))?.doors == extended.doors)

    val grayFrontier = blank()
    tile(grayFrontier, 1, 1); tile(grayFrontier, 2, 1, 85)
    door(grayFrontier, 1, 1, 1, 0, 119); door(grayFrontier, 2, 1, 1, 0, 85)
    val forward = requireNotNull(dungeonMapExit(grayFrontier, layout, setOf(RoomSegment(-168, -168))))
    check(!forward.reachesBlood && forward.doors.size == 2 && forward.doors.last().to.isEmpty())
    door(grayFrontier, 2, 1, 0, 1, 85)
    val shared = requireNotNull(dungeonMapExit(grayFrontier, layout, setOf(RoomSegment(-168, -168))))
    check(shared.doors.size == 1 && shared.doors.first().door == forward.doors.first().door) // Stop at the last unambiguous continuation.

    val rareMap = blank()
    tile(rareMap, 1, 1); tile(rareMap, 2, 1, 34); tile(rareMap, 3, 1, 18)
    door(rareMap, 1, 1, 1, 0, 34); door(rareMap, 2, 1, 1, 0, 18)
    check(dungeonMapExit(rareMap, layout, setOf(RoomSegment(-168, -168)))?.doors?.size == 2)
    val rare = requireNotNull(dungeonMapRoom(rareMap, layout, RoomSegment(-136, -168)))
    check(rare.name == "Rare room" && !rare.cleared)

    val symmetric = blank()
    tile(symmetric, 1, 1); tile(symmetric, 1, 0); tile(symmetric, 2, 1); tile(symmetric, 2, 0, 18)
    door(symmetric, 1, 1, 0, -1, 63); door(symmetric, 1, 1, 1, 0, 63)
    door(symmetric, 1, 0, 1, 0, 18); door(symmetric, 2, 1, 0, -1, 18)
    val startPosition = RoomPoint(-152.5, 69.0, -152.5)
    val stable = requireNotNull(dungeonMapExit(symmetric, layout, setOf(RoomSegment(-168, -168)), startPosition))
    val nearbyPosition = startPosition.copy(x = startPosition.x + 1.0)
    val retained = requireNotNull(dungeonMapExit(symmetric, layout, setOf(RoomSegment(-168, -168)), nearbyPosition, stable))
    check(retained.doors == stable.doors) // A near-equivalent chain stays stable while the player moves slightly.
    val movedPosition = startPosition.copy(x = startPosition.x + 5.0)
    val shorter = requireNotNull(dungeonMapExit(symmetric, layout, setOf(RoomSegment(-168, -168)), movedPosition, stable))
    check(shorter.doors.first().door == GridCell(-137, 69, -153) && shorter.doors != stable.doors)

    val multicell = blank()
    tile(multicell, 1, 1); tile(multicell, 2, 1, 85); tile(multicell, 2, 2); tile(multicell, 3, 2, 18)
    door(multicell, 1, 1, 1, 0, 85, true); door(multicell, 2, 1, 0, 1, 63, true); door(multicell, 2, 2, 1, 0, 18)
    val joined = setOf(RoomSegment(-168, -168), RoomSegment(-136, -168), RoomSegment(-136, -136))
    for (segment in joined) {
        val joinedExit = requireNotNull(dungeonMapExit(multicell, layout, joined))
        check(joinedExit.reachesBlood && joinedExit.doors.single().from == joined && joinedExit.door == GridCell(-105, 69, -121))
        check(dungeonMapRoom(multicell, layout, segment)?.segments == joined)
    }

    val calibrated = blank()
    tile(calibrated, 0, 0, 30); tile(calibrated, 2, 1)
    calibrated[13 + 13 * 128] = 34 // Entrance checkmarks do not invalidate its border.
    check(dungeonNativeMapLayout(calibrated, 53, 33, -120.5, -152.5) == layout)
    check(dungeonFloorMapLayout(calibrated, 7) == layout)
    check(dungeonNativeMapLayout(calibrated, 127, 127, -120.5, -152.5) == null)
    tile(calibrated, 1, 0, 30)
    check(dungeonNativeMapLayout(calibrated, 53, 33, -120.5, -152.5) == null)
    check(dungeonFloorMapLayout(calibrated, 7) == null)

    val displacedEntrance = blank()
    tile(displacedEntrance, 3, 2, 30); tile(displacedEntrance, 1, 1); tile(displacedEntrance, 2, 1, 82)
    tile(displacedEntrance, 3, 1); tile(displacedEntrance, 4, 1, 18)
    door(displacedEntrance, 1, 1, 1, 0, 82); door(displacedEntrance, 2, 1, 1, 0, 119); door(displacedEntrance, 3, 1, 1, 0, 18)
    val stableLayout = requireNotNull(dungeonFloorMapLayout(displacedEntrance, 7))
    check(stableLayout == DungeonMapLayout(65, 45, 16, -104, -136)) // Entrance is away from the fixed northwest world cell.
    val staleMarkerLayout = requireNotNull(dungeonNativeMapLayout(displacedEntrance, 33, 33, -120.5, -152.5))
    check(staleMarkerLayout != stableLayout) // A delayed marker paired with a moved player previously shifted every room.
    val beforePinkRoom = requireNotNull(dungeonMapRoom(displacedEntrance, stableLayout, RoomSegment(-168, -168)))
    val inPinkRoom = requireNotNull(dungeonMapRoom(displacedEntrance, stableLayout, RoomSegment(-136, -168)))
    check(beforePinkRoom.exit?.doors?.first()?.to == inPinkRoom.segments && inPinkRoom.fairy)
    check(beforePinkRoom.exit?.reachesBlood == true && inPinkRoom.exit?.reachesBlood == true)
    for (floor in 0..7) {
        val pixels = blank()
        val size = if (floor <= 3) 18 else 16
        val origin = when (floor) { 0 -> RoomSegment(22, 22); 1 -> RoomSegment(22, 11); 2, 3 -> RoomSegment(11, 11); else -> RoomSegment(5, 5) }
        val entrance = RoomSegment(origin.x + (size + 4) * 2, origin.z + (size + 4))
        for (dx in 0 until size) for (dz in 0 until size) pixels[(entrance.x + dx) + (entrance.z + dz) * 128] = 30
        val floorLayout = requireNotNull(dungeonFloorMapLayout(pixels, floor))
        check(floorLayout.physicalX == -136 && floorLayout.physicalZ == -168 && floorLayout.roomSize == size)
        check(floorLayout.world(origin) == RoomSegment(-200, -200))
    }
    check(dungeonFloorMapLayout(displacedEntrance, 8) == null && dungeonFloorMapLayout(ByteArray(10), 7) == null)

    fun scanned(x: Int, z: Int, type: String = "NORMAL", state: String = "UNDISCOVERED",
        door: Boolean = false, separator: Boolean = false, opened: Boolean = false, name: String? = "Unknown") =
        DungeonScannedTile(-185 + x * 16, -185 + z * 16, type, state, door, separator, opened, name)
    val scannedTiles = listOf(scanned(0, 0, "ENTRANCE"), scanned(2, 2),
        scanned(3, 2, door = true), scanned(4, 2, "FAIRY", "UNOPENED", name = "Fairy"),
        scanned(5, 2, "WITHER", door = true), scanned(6, 2),
        scanned(7, 2, "BLOOD", door = true), scanned(8, 2, "BLOOD"))
    val rawHotbarMap = blank().also { tile(it, 0, 0, 30); tile(it, 1, 1) }
    val rawBeforeScan = rawHotbarMap.copyOf()
    check(dungeonMapRoom(rawHotbarMap, layout, RoomSegment(-168, -168))?.exit == null)
    val fullScan = requireNotNull(dungeonScannedMap(scannedTiles))
    val scannedRoom = requireNotNull(dungeonScannedRoom(fullScan, RoomSegment(-168, -168)))
    val scannedExit = requireNotNull(scannedRoom.exit)
    check(scannedExit.reachesBlood && scannedExit.doors.map { it.kind } == listOf("Dungeon door", "Wither door", "Blood door"))
    check(scannedExit.doors.drop(1).all { it.closed } && !scannedRoom.cleared)
    check(rawHotbarMap.contentEquals(rawBeforeScan)) // Full scanned topology never overwrites the received hotbar map.
    val scannedFairy = requireNotNull(dungeonScannedRoom(fullScan, RoomSegment(-136, -168)))
    check(scannedFairy.fairy && scannedFairy.name == "Fairy" && scannedExit.doors.first().to == scannedFairy.segments)
    check(scannedFairy.exit?.reachesBlood == true && scannedFairy.exit?.doors?.size == 2)
    check(dungeonScannedRoom(fullScan, RoomSegment(-72, -168))?.exit == null) // Scanned Blood ends the route before native pixels catch up.
    for (state in listOf("DISCOVERED", "UNOPENED", "UNDISCOVERED")) {
        val changed = requireNotNull(dungeonScannedMap(scannedTiles.map { it.copy(state = state) }))
        val route = requireNotNull(dungeonScannedRoom(changed, RoomSegment(-168, -168)))
        check(route.exit?.doors == scannedExit.doors && !route.cleared && changed.cleared.isEmpty())
    }
    for (state in listOf("GREEN", "CLEARED")) {
        val changed = requireNotNull(dungeonScannedMap(scannedTiles.map { it.copy(state = state) }))
        val route = requireNotNull(dungeonScannedRoom(changed, RoomSegment(-168, -168)))
        check(route.exit?.doors == scannedExit.doors && route.cleared)
        check(dungeonScannedRoom(changed, RoomSegment(-136, -168))?.cleared == true)
    }
    val openedScan = requireNotNull(dungeonScannedMap(scannedTiles.map { it.copy(opened = it.door) }))
    check(dungeonScannedRoom(openedScan, RoomSegment(-168, -168))?.exit?.doors?.all { !it.closed } == true)
    val loadedGate = requireNotNull(dungeonScannedRoom(openedScan, RoomSegment(-168, -168), { cell ->
        if (cell == scannedExit.doors[1].door) 119 else null
    })).exit!!
    check(loadedGate.doors[1].closed && !loadedGate.doors.last().closed) // Loaded blocks override the optional opened metadata.
    check(dungeonScannedRoom(openedScan, RoomSegment(-168, -168), { -1 })?.exit?.doors?.drop(1)?.all { it.closed } == true)
    val rareScan = requireNotNull(dungeonScannedMap(listOf(scanned(2, 2, "RARE", "CLEARED", name = "Rare"))))
    check(dungeonScannedRoom(rareScan, RoomSegment(-168, -168))?.cleared == true) // Explicit metadata avoids the rare-room/checkmark colour collision.
    val multicellTiles = listOf(scanned(2, 2), scanned(3, 2, separator = true), scanned(4, 2),
        scanned(5, 2, "BLOOD", door = true), scanned(6, 2, "BLOOD"))
    val scannedMulticell = requireNotNull(dungeonScannedMap(multicellTiles))
    val connectedScan = requireNotNull(dungeonScannedRoom(scannedMulticell, RoomSegment(-168, -168)))
    check(connectedScan.segments == setOf(RoomSegment(-168, -168), RoomSegment(-136, -168)))
    check(connectedScan.exit?.doors?.single()?.from == connectedScan.segments)
    val noSeam = requireNotNull(dungeonScannedMap(multicellTiles.filterNot { it.separator }))
    check(dungeonScannedRoom(noSeam, RoomSegment(-168, -168))?.let { it.segments.size == 1 && it.exit == null } == true)
    val missingDoor = requireNotNull(dungeonScannedMap(scannedTiles.filterNot { it.x == -105 && it.z == -153 }))
    check(dungeonScannedRoom(missingDoor, RoomSegment(-168, -168))?.exit?.reachesBlood != true)
    check(dungeonScannedMap(emptyList()) == null && dungeonScannedMap(List(122) { scanned(0, 0) }) == null)
    check(dungeonScannedMap(listOf(scanned(2, 2), scanned(2, 2))) == null)
    check(dungeonScannedMap(listOf(scanned(2, 2).copy(x = -152))) == null)
    check(dungeonScannedMap(listOf(scanned(2, 2, door = true))) == null)
    check(dungeonScannedMap(listOf(scanned(3, 3, door = true))) == null)
    check(dungeonScannedMap(listOf(scanned(3, 2))) == null)
    check(dungeonScannedMap(listOf(scanned(2, 2, separator = true))) == null)

    // Combined regression: an empty catalog must still produce a collision-safe automatic clear route.
    for (gateColour in listOf(18, 119)) {
        val colours = blank()
        tile(colours, 0, 0, 30); tile(colours, 2, 1)
        tile(colours, 3, 1, if (gateColour == 18) 18 else 63)
        door(colours, 2, 1, 1, 0, gateColour)
        if (gateColour == 119) { tile(colours, 4, 1, 18); door(colours, 3, 1, 1, 0, 18) }
        val start = GridCell(-134, 69, -153)
        fun feet(cell: GridCell) = RoomPoint(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
        val emptyCatalog = syntheticCatalog().apply { add("rooms", JsonArray()) }.parseCatalog()
        check(emptyCatalog.recognize("F7", feet(start)) { error("An empty catalog should read no room landmarks") } == null)
        val nativeLayout = requireNotNull(dungeonNativeMapLayout(colours, 46, 33, feet(start).x, feet(start).z))
        val detected = requireNotNull(dungeonMapRoom(colours, nativeLayout, dungeonGridSegment(feet(start).x, feet(start).z)))
        val exit = requireNotNull(detected.exit)
        check(!detected.cleared && exit.kind == if (gateColour == 18) "Blood door" else "Wither door")
        check(dungeonRouteVisible(detected))
        check(exit.approach == GridCell(-108, 69, -153))
        // Independent physical solids: a tall dividing wall with a southern opening, and a closed gate.
        val solids = listOf(GridCell(-128, 69, -168) to GridCell(-127, 73, -144),
            GridCell(-106, 69, -154) to GridCell(-103, 73, -151))
        fun clear(a: RoomPoint, b: RoomPoint) = solids.none { (min, max) ->
            minOf(a.x, b.x) - 0.3 < max.x && maxOf(a.x, b.x) + 0.3 > min.x &&
                minOf(a.y, b.y) < max.y && maxOf(a.y, b.y) + 1.8 > min.y &&
                minOf(a.z, b.z) - 0.3 < max.z && maxOf(a.z, b.z) + 0.3 > min.z
        }
        fun move(a: GridCell, b: GridCell) = validRoomConnector(feet(a), feet(b),
            loaded = { _, _ -> true }, clearSegment = ::clear,
            supported = { point, _, radius -> point.y == 69.0 && point.x - radius >= -136 && point.x + radius <= -104 &&
                point.z - radius >= -168 && point.z + radius <= -136 }, climbable = { false })
        var cellReads = 0
        var edgeReads = 0
        val flood = RoomLiveGeometry(start, { detected.containsXZ(it.x, it.z) && it.y in 68..72 },
            { cellReads++; move(it, it) }, { a, b -> edgeReads++; move(a, b) })
        var ticks = 0
        do {
            val previousCells = cellReads
            val previousEdges = edgeReads
            val finished = flood.step(maxCells = 32, maxEdges = 64, maxNanos = Long.MAX_VALUE)
            check(cellReads - previousCells <= 32 && edgeReads - previousEdges <= 64)
            if (!finished) check(flood.result == null)
            check(++ticks < 1000)
        } while (!finished)
        val geometry = requireNotNull(flood.result)
        check(flood.status == RoomGeometryStatus.READY && exit.approach in geometry.cells && exit.door !in geometry.cells)
        val observed = mapOf("behind-wall" to GridCell(-126, 69, -153), "southern" to GridCell(-133, 69, -141))
        val states = RoomTargetStates(observed.mapValues { 1 }).apply { observe("behind-wall", 101); observe("southern", 202) }
        val navigation = requireNotNull(RoomNavigation.create(geometry.cells, geometry.adjacency, observed, exit.approach))
        val initial = requireNotNull(navigation.plan(start, states.remaining()))
        check(initial.path.first() == start && initial.path.last() == exit.approach)
        check(initial.targetOrder == listOf("southern", "behind-wall") && initial.cost == 50)
        check(initial.path.any { it.z >= -144 } && initial.path.all { move(it, it) })
        check(initial.path.zipWithNext().all { (a, b) -> move(a, b) })
        check(!move(exit.door, exit.door) && !clear(feet(exit.approach), feet(exit.door)))
        states.removed(101, true)
        states.removed(202, false)
        check(navigation.plan(start, states.remaining(), initial.targetOrder)?.targetOrder == listOf("southern"))
        colours[53 + 33 * 128] = 34
        val cleared = requireNotNull(dungeonMapRoom(colours, nativeLayout, dungeonGridSegment(feet(start).x, feet(start).z)))
        check(cleared.key == detected.key && cleared.cleared && cleared.exit == exit)
        states.removed(202, cleared.cleared)
        val toExit = requireNotNull(navigation.plan(start, states.remaining(), initial.targetOrder))
        check(toExit.targetOrder.isEmpty() && toExit.path.last() == exit.approach && toExit.cost == 44)
        check(toExit.path.any { it.z >= -144 } && toExit.path.zipWithNext().all { (a, b) -> move(a, b) })
    }
}

private fun dungeonStarMobChecks() {
    data class Mob(val id: Int, val feet: RoomPoint, val eligible: Boolean = true)
    val body = Mob(100, RoomPoint(-121.5, 69.0, -152.5))
    val label = Mob(101, RoomPoint(-121.5, 71.0, -152.5), false)
    val projectile = Mob(102, RoomPoint(-121.5, 72.0, -152.5), false)
    val entities = listOf(body, label, projectile).associateBy { it.id }.toMutableMap()
    val health = "[Lv23] ✯ Undead Skeleton 360k/360k❤"
    fun resolve(id: Int, name: String, beneath: () -> Iterable<Mob> = { emptyList() }) =
        dungeonStarMob(id, name, entities::get, { it.eligible }, beneath)
    check(resolve(101, health) { error("The real entity ID should avoid a spatial scan") } == body)
    check(resolve(103, "✯ Withermancer 1M/1M❤") == body) // Skip both skull entities.
    check(resolve(103, "✯ WITHERMANCER 1M/1M❤") == body)
    check(resolve(120, health) { listOf(label, projectile, body) }?.feet == body.feet)
    check(resolve(120, "✯ item") { error("An item name is not a health label") } == null)
    check(resolve(120, "Undead Skeleton 360k/360k❤") == null)
    check(resolve(120, health) == null)
    // A metadata-before-body failure must be retryable; an ID-only checked cache loses this mob.
    entities.remove(100)
    check(resolve(101, health) == null)
    entities[100] = body
    check(resolve(101, health) == body)
    for (profile in listOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")) {
        check(dungeonMinibossProfile(profile))
        check(dungeonStarPlayer(4, local = false, invisible = true, profile)) // Known profiles permit body resolution, without proving a star.
        check(!dungeonStarPlayer(2, local = true, invisible = false, profile))
        val unstarred = "$profile 3M/3M❤"
        val starred = "[Lv100] ✯ $unstarred"
        check(!dungeonStarLabel(unstarred) && !dungeonMinibossLabel(unstarred))
        check(dungeonStarLabel(starred) && dungeonMinibossLabel(starred))
        check(dungeonMinibossLabel("✯ [Lv100] $unstarred"))
        check(resolve(101, unstarred) == null && resolve(101, starred) == body)
        for (throughMobs in listOf(false, true)) for (throughMinibosses in listOf(false, true)) {
            check(!dungeonRouteStarTarget(false, true, throughMobs, throughMinibosses)) // A known miniboss without a star is always excluded.
            check(dungeonRouteStarTarget(true, dungeonMinibossProfile(profile), throughMobs, throughMinibosses) == throughMinibosses)
            check(dungeonRouteStarTarget(true, false, throughMobs, throughMinibosses) == throughMobs)
        }
    }
    check(dungeonStarPlayer(2, local = false, invisible = false))
    check(!dungeonStarPlayer(4, local = false, invisible = false, "DungeonTeammate"))
    check(!dungeonStarPlayer(2, local = false, invisible = true))
    check(!dungeonMinibossProfile(null) && !dungeonMinibossProfile("Lost AdventurerFan"))
    check(!dungeonMinibossLabel(null) && !dungeonMinibossLabel("✯ Shadow AssassinFan 3M❤"))
    check(!dungeonMinibossLabel("✯ Shadow Assassin impersonator 3M❤") && !dungeonMinibossLabel("✯ Shadow Assassin item"))
    check(!dungeonMinibossLabel(health))
}
