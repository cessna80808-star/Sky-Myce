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

    for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
        val colours = blank()
        tile(colours, 2, 2); tile(colours, 2 + dx, 2 + dz, 18)
        door(colours, 2, 2, dx, dz, 18)
        val exit = requireNotNull(dungeonMapExit(colours, layout, setOf(RoomSegment(-136, -136))))
        check(exit.kind == "Blood door")
        check(exit.door == GridCell(-121 + dx * 16, 69, -121 + dz * 16))
        check(exit.approach == GridCell(exit.door.x - dx * 3, 69, exit.door.z - dz * 3))
        check(dungeonMapExit(colours, layout, setOf(RoomSegment(-136 + dx * 32, -136 + dz * 32))) == null)
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
    check(requireNotNull(dungeonMapExit(topology, layout, setOf(RoomSegment(-136, -168)))).door == GridCell(-137, 69, -153))

    val unknown = blank()
    tile(unknown, 1, 1); tile(unknown, 2, 1, 85); door(unknown, 1, 1, 1, 0, 119)
    check(requireNotNull(dungeonMapExit(unknown, layout, setOf(RoomSegment(-168, -168)))).kind == "Wither door")
    tile(unknown, 1, 2, 85); door(unknown, 1, 1, 0, 1, 119)
    check(dungeonMapExit(unknown, layout, setOf(RoomSegment(-168, -168))) == null)
    check(dungeonMapRoom(unknown, layout, RoomSegment(-136, -168)) == null)
    check(dungeonMapRoom(ByteArray(10), layout, RoomSegment(-168, -168)) == null)

    val gateBlocks = mutableMapOf<GridCell, Int>()
    val gateRoom = setOf(RoomSegment(-168, -168))
    fun gate(x: Int, z: Int, dx: Int, dz: Int, colour: Int) {
        for (dy in 0..3) for (across in -1..1) gateBlocks[GridCell(x + dz * across, 69 + dy, z + dx * across)] = colour
    }
    gate(-137, -153, 1, 0, 119)
    check(requireNotNull(dungeonClosedExit(gateRoom) { gateBlocks[it] }).approach == GridCell(-140, 69, -153))
    gateBlocks.remove(GridCell(-137, 72, -154))
    check(dungeonClosedExit(gateRoom) { gateBlocks[it] } == null) // Incomplete or unloaded gate.
    gate(-137, -153, 1, 0, 119); gate(-153, -137, 0, 1, 18)
    check(dungeonClosedExit(gateRoom) { gateBlocks[it] } == null) // Unproven direction between two closed gates.

    val calibrated = blank()
    tile(calibrated, 0, 0, 30); tile(calibrated, 2, 1)
    calibrated[13 + 13 * 128] = 34 // Entrance checkmarks do not invalidate its border.
    check(dungeonNativeMapLayout(calibrated, 53, 33, -120.5, -152.5) == layout)
    check(dungeonNativeMapLayout(calibrated, 127, 127, -120.5, -152.5) == null)
    tile(calibrated, 1, 0, 30)
    check(dungeonNativeMapLayout(calibrated, 53, 33, -120.5, -152.5) == null)

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
