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
    val catalog = syntheticCatalog().parseCatalog()
    val room = catalog.rooms.single()
    check(room.id == "synthetic-room" && room.walkable.size == 30 && room.targets.size == 2)
    check(room.adjacency.getValue(GridCell(1, 0, 1)).size == 4)
    check(room.healer == GridCell(5, 0, 4))
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

    val states = RoomTargetStates(listOf("a", "b"))
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
    check(states.removed(4, true) && states.state("a") == RoomTargetState.CLEARED)
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
    println("Room template parsing, rotated recognition and target lifecycle checks passed")
}
