# Starred clear routing

Starred Clear Routing uses room-relative templates, current client collision geometry and an optimized target order. It only draws guidance; it never moves, attacks, opens doors or sends gameplay packets.

**No real room templates are bundled.** The repository and its installed SkyBlock API do not provide verified starred spawn coordinates, walkable geometry or a healer-door definition. Until an author supplies those data, unknown rooms show no route. `DungeonAPI.roomId` comes from scoreboard metadata and must not be treated as a room-layout identifier.

## Installing a verified catalog

Put the UTF-8 JSON catalog at `config/skymyce/dungeon-routing-rooms.json` in the Minecraft instance, enable **Starred Clear Routing** in the existing dungeon configuration, then run `/sm route reload`. `/sm route status` reports the current routing state. Reload after editing the catalog.

The author must establish the actual dungeon grid offsets and base Y coordinate from measurements. Do not substitute guessed coordinates, secret waypoints or legacy block-ID hashes for verified room clear data. A healer endpoint is an explicitly authored safe standing position by the intended door; the implementation does not infer which door the author means.

## Schema 1

Every field below is required. This is a **synthetic format example**, not a Hypixel room or a usable production catalog:

```json
{
  "schema": 1,
  "grid": { "spacing": 32, "offsetX": 0, "offsetZ": 0, "originY": 0 },
  "rooms": [{
    "id": "synthetic-only",
    "floors": ["F1"],
    "bounds": { "min": [0, 0, 0], "max": [2, 1, 2] },
    "landmarks": [
      { "cell": [0, 1, 2], "block": "minecraft:stone" },
      { "cell": [1, 1, 2], "block": "minecraft:andesite" },
      { "cell": [0, 1, 1], "block": "minecraft:sandstone" }
    ],
    "walkable": [[0, 0, 0], [1, 0, 0], [2, 0, 0], [2, 0, 1], [2, 0, 2]],
    "edges": [
      [[0, 0, 0], [1, 0, 0]],
      [[1, 0, 0], [2, 0, 0]],
      [[2, 0, 0], [2, 0, 1]],
      [[2, 0, 1], [2, 0, 2]]
    ],
    "targets": [{ "id": "star-a", "cell": [1, 0, 0] }],
    "healer": [2, 0, 2]
  }]
}
```

| Field | Meaning and validation |
| --- | --- |
| `schema` | Integer `1`. |
| `grid` | `spacing` must be `32`. Offsets are signed world X/Z integers within ±29,999,000; `originY` is within ±4,096. Candidate origins are `(offsetX + 32*i, originY, offsetZ + 32*j)`. |
| `rooms` | At most 256 templates. `[]` is a valid empty catalog. |
| `id` | Unique stable room ID matching `[a-z0-9][a-z0-9_.-]{0,63}`. |
| `floors` | Nonempty, distinct labels from `E`, `F1`–`F7`, `M1`–`M7`. Only list floors actually validated for the template. |
| `bounds` | Inclusive local cell bounds. Each coordinate is within ±128; each axis spans at most 128 cells. |
| `landmarks` | 3–64 distinct cells in bounds, including three noncollinear positions in X/Z. Block IDs use a namespaced resource identifier, at most 128 characters; block states are not included. Choose stable, distinctive structural blocks. |
| `walkable` | 1–16,384 distinct local feet cells in bounds. At most 131,072 cells across the catalog. These are candidate standing positions, not a declaration that the client may ignore collisions. |
| `edges` | At most 65,536 distinct undirected pairs of walkable cells. X/Z Manhattan difference is at most one, Y difference at most one, and endpoints must differ. Diagonals, jumps over missing cells and duplicate/reversed pairs are rejected. |
| `targets` | At most 12 targets, each with a unique ID using the same pattern as room IDs and a `cell` in `walkable`. Use confirmed starred spawn groups, not ordinary mobs or secrets. An empty list permits a healer-only route. |
| `healer` | A walkable feet cell at the intended safe door approach. Routing cannot pass through a closed door. |

All coordinates and numeric fields are plain signed 32-bit JSON integer literals. Numeric strings, decimals, exponent notation, duplicate property names, invalid UTF-8 and trailing content are rejected. Input is limited to 4 MiB and nesting depth 16. The entire catalog is rejected on malformed data; partially valid rooms are not silently installed.

## Coordinates and orientation

Cells are integer block indices; Y is the player's feet height. The template origin is local `[0,0,0]`. Positive quarter turns map local +X to world +Z:

| Quarter turns | Local `(x,z)` becomes |
| --- | --- |
| 0 | `(x,z)` |
| 1 | `(-z,x)` |
| 2 | `(-x,-z)` |
| 3 | `(z,-x)` |

Add the world origin after rotating integer cell coordinates. For rendering or standing positions, **transform the cell first**, then add `0.5` to world X/Z; retain world Y as feet height. This rotates block centers about the origin block's center. Do not rotate already-centered local points with the raw vector `RoomPoint` overload and assume the result is a transformed block center.

Recognition floors the player's world position to a block cell, considers only matching floor templates and nearby grid origins within four tiles, then checks transformed landmarks. A candidate also needs an authored walkable feet cell within two blocks of the player; the bounded search covers X/Z ±2 and Y ±1. This rejects another room occupying the empty quadrant of an L-shaped room's rectangular bounds. Live geometry independently validates the actual player-to-node connector. Landmark reads are memoized and capped at 8,192 distinct blocks per recognition. Unloaded blocks never count as air. More than one matching template, origin or orientation suppresses the route rather than selecting an arbitrary match.

## Authoring and validation

1. Record a room's actual grid origin, orientation and complete bounds. Convert measurements into one canonical local orientation; do not add separate templates for rotated copies.
2. Select at least three distinctive structural landmarks spread across X/Z. Check that they identify exactly one orientation and do not depend on mob entities, loot, opened doors or changing blocks.
3. Record safe feet cells and every intended connection. Include actual corridors, stairs and climbable sections. Never join nodes across a wall, unsupported hole or blocked doorway merely because their endpoints are close.
4. Record fixed starred spawn locations and the intended healer approach. Validate which floors and spawn groups share the layout. Each spawn group needs a safe reachable walkable node outside solid geometry.
5. Test all four rotations, multiple grid origins and arbitrary player starts, including the room center. Test partially cleared rooms, inaccessible targets, closed doors, missing chunks and changed geometry before claiming room support.

Minecraft world reads stay on the client thread. The incremental geometry builder checks loaded chunk margins, a 0.6-wide/1.8-high player body, support, swept connectors, conservative one-block ascents and climbable vertical connectors. It never loads chunks. Missing geometry publishes no partial graph. A node or edge absent from the template is never invented; live collision checks may remove authored nodes and edges.

## Routing and target state

`RoomNavigation` caches BFS paths from fixed targets and the healer. Each replan searches from the player's actual reachable node, then uses Held–Karp dynamic programming to minimize the complete path through remaining targets **including the healer endpoint**. Cost counts validated movement edges; it is not a time prediction or straight-line distance. Up to 12 targets keeps exact ordering bounded.

A still-valid previous order is retained unless the new order saves more than three movement edges. Meaningful movement, target changes, room changes and invalid geometry trigger recalculation; rendering reuses cached results.

`RoomTargetStates` distinguishes `UNKNOWN`, `ACTIVE` and `CLEARED`. Only confirmed deaths complete targets. Disappearance or chunk unload becomes unknown; a multi-mob group remains unfinished until all observed members are confirmed dead. Confirmed completion survives temporary unloads and player death within the same run. Entity-to-target associations remain stable as mobs move or unload. Reaching the healer after clearing all targets hides that room's route for the rest of the run; a newly observed mob can reactivate it. A run reset clears observations and completion. The 128-observation limit per target fails to unknown rather than claiming completion.

## Developer checks

Use Java 25:

```powershell
.\gradlew.bat dungeonRoutingCheck
.\gradlew.bat check build
```

`dungeonRoutingCheck` runs `roomNavigationCheck`, `roomTemplatesCheck`, `roomWorldGeometryCheck` and `roomRoutingUpdatesCheck`. Pure checks cover synthetic walls, target order, healer routes, transforms, catalog bounds, ambiguity and target lifecycle. They do not establish real room accuracy or replace live Minecraft validation. Developer diagnostics should remain disabled during normal play and record room identity, orientation, origin, targets, endpoint, geometry status, route cost/order, replan reason and execution time only when requested.
