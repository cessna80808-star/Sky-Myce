# Starred clear routing

Starred Clear Routing uses the dungeon map, current client collision geometry and an optimized target order to guide you through starred mobs toward a closed Wither door. It hides in rooms without a Wither destination and once Blood is open. It only draws guidance; it never moves, attacks, opens doors or sends gameplay packets.

**No room catalog is required for automatic routing.** Version 2.4.14 incorrectly depended on a manually authored catalog and therefore displayed nothing on a normal installation. Version 2.4.15 starts automatic room detection whenever no authored template matches. `DungeonAPI.roomId` comes from scoreboard metadata and must not be treated as a room-layout identifier.

## Automatic routing

Enable **Starred Clear Routing**, enter the dungeon clearing phase and keep the dungeon's filled map in its normal ninth hotbar slot. `/sm route status` reports the current room, observed targets and scan status without requiring Developer Mode. The native map reader calibrates from the entrance and your map decoration, recognizes joined room segments and reads server checkmarks. If Skyblocker's public room metadata is available, it supplies the room name and segment boundaries; Skyblocker is optional. Missing or ambiguous map data reports a waiting state.

Loaded starred mobs are discovered through attached nametags and direct mob names, with a 100 ms observation fallback. Filled markers and compact distance labels appear while geometry is being scanned. Paths and markers float 1.2 blocks above their validated feet positions; no beacon beams are drawn. Cleared markers remain optional and omit oversized text. The live mode keeps stable per-mob identities and updates meaningful movement; confirmed death clears that observed mob, whereas despawn or unload remains unknown. Only the server's room checkmark establishes whole-room completion. No unobserved spawn positions or total mob counts are fabricated. The planner handles up to twelve reachable observed mobs in a batch and retains other markers for subsequent batches.

The live graph floods from the player's actual supported position through native collision-checked connections inside the current room and any verified adjoining Fairy passage. Version 2.4.17 publishes changed geometry at most every 100 ms, including new edges between existing nodes. Missing destinations take priority over scanning around a moving player. Later snapshots refine the route; optimization applies to the available graph, not to undiscovered geometry. Each client tick processes at most 128 standing candidates and 256 directed connections with a two-millisecond scheduling budget. While no path is visible, render frames can advance the same scan with a separate half-millisecond budget. A single native collision callback may exceed a scheduling budget. Target approach probes share a separate 64-check/two-millisecond budget. These are work limits, not a live FPS guarantee.

Actual collision surfaces supply fractional feet heights for carpets, slabs, snow and stair treads. Native body/support sweeps validate diagonal moves and prevent cutting through corners; live costs use horizontal distance and actual elevation. The graph permits at most 16,384 reachable cells and does not load chunks. Target movement reuses it; relevant block and chunk changes invalidate it. Untouched areas and repeated collision-equivalent block updates do not restart the flood. Rooms at the node limit or targets outside the loaded reachable geometry report their limitation instead of inventing a route through walls.

The endpoint is a closed Wither gate on the current room's boundary or inside an adjoining Fairy room reached through an open passage. Map topology toward Blood chooses between established gates; ambiguous destinations suppress guidance. A normal open door along the Blood route is not a destination. Crossing into that Fairy room retains the source room's checked graph and refreshes its map clear state, rather than stopping at the Fairy entrance. An uncleared room with no reachable observed targets shows only the exit waypoint. Server-confirmed clear rooms and Fairy rooms permit exit-only guidance. Every route ends at a collision-validated standing position on your side of the Wither gate; it cannot open a locked door or pass through solid blocks. `DungeonAPI.bloodOpened` hides all routing, including authored guidance. The persisted `showHealer` setting retains its old key but is labeled **Show Wither Exit**.

Automatic routing uses observed mobs and the live map, rather than claiming a verified fixed spawn catalog. The optional measured templates below can supply known spawn groups before their mobs are observed. No real measured templates are bundled.

## Installing an optional verified catalog

Put the UTF-8 JSON catalog at `config/skymyce/dungeon-routing-rooms.json` in the Minecraft instance, then run `/sm route reload`. A recognized authored room takes precedence within an eligible Wither room; automatic geometry handles adjoining Fairy passages. `/sm route status` reports the current routing state. Reload after editing the catalog.

The author must establish the actual dungeon grid offsets and base Y coordinate from measurements. Do not substitute guessed coordinates, secret waypoints or legacy block-ID hashes for verified room clear data. The schema's legacy `healer` field stores an explicitly authored safe standing position by the intended Blood/Wither exit; its spelling is retained for catalog compatibility.

## Recording a room in Minecraft

Recording is an explicit local authoring workflow. Measure the room's canonical origin, total spawn-group counts and intended Blood/Wither exit first. Start inside an active, non-boss dungeon room:

1. Stand at the measured origin block and run `/sm route record start <room-id> <quarterTurns>`. Quarter turns are `0`–`3`, following the table below. A new catalog uses the origin's X/Z modulo 32 and block Y for its grid. With an installed catalog, the selected X/Z must already align with that grid; its existing origin Y is retained.
2. Aim at two opposing structural corner blocks and run `/sm route record corner` for each. Select the room's lower floor/wall extent and its opposing upper wall/ceiling extent. Bounds must include all intended feet cells **and every landmark block**, including upper levels; player feet alone do not describe structural bounds. Each axis spans at most 128 cells, coordinates remain within local ±128, and the scan volume is at most 65,536 candidate cells. Use tight bounds; the final room permits at most 16,384 walkable cells.
3. Look directly at a stable structural block and run `/sm route record landmark`; repeat for at least three distinctive blocks noncollinear in X/Z. Air, fluids and blocks without collision are rejected. Landmarks must lie inside the recorded bounds and identify exactly one room orientation.
4. Stand at each verified starred spawn group's safe approach and run `/sm route record target <target-id> <count>`. Supply the verified total number of starred mobs in that group, from 1 to 128. There may be at most 12 groups. Overlapping or ambiguous spawn regions cannot establish reliable mob associations.
5. Stand at the intended Blood/Wither-door approach and run `/sm route record healer` (the legacy command name). Targets and exit positions use integer candidate feet heights; fractional slab/stair heights round up and must pass native support/collision checks.
6. Open the intended passages, then run `/sm route record scan`. The existing incremental builder examines at most 128 candidate cells and 256 directed connections per tick, reads only loaded chunks, and derives neighbor candidates only after standing cells pass collision/support checks. Cells blocked during capture are omitted from the template; later runtime checks can close an authored passage but cannot invent an omitted one. Keep the room and its chunk margin loaded. `/sm route record status` reports progress or the rejection reason.
7. When the scan is complete, run `/sm route record save`. The full merged catalog is validated before an atomic local write; the original catalog is backed up as `dungeon-routing-rooms.json.before-room-recording` on the first save. Existing room IDs, incompatible grids and malformed existing files are rejected. Success reloads the routing catalog automatically.

Editing a draft or changing relevant blocks/chunks invalidates its scan; run `/scan` again before `/save`. `/sm route record cancel` discards the draft. Death, a dungeon reset or a changed world cancels unfinished recording. A save already explicitly requested may finish after cancellation. Recording adds only the current floor: add another floor label only after validating its layout, spawn counts and endpoint. Reuse one canonical template for its four rotations.

The recorded geometry is a collision-checked snapshot, not proof of production room support. Validate recognition, all rotations and real target lifecycle before distributing a catalog. Runtime revalidates authored cells and connections against the current loaded world.

## Schema 1

Every field below except target `count` is required. This is a **synthetic format example**, not a Hypixel room or a usable production catalog:

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
    "targets": [{ "id": "star-a", "cell": [1, 0, 0], "count": 2 }],
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
| `targets` | At most 12 targets, each with a unique ID using the same pattern as room IDs and a `cell` in `walkable`. Optional `count` is the verified starred mob total for that spawn group, from 1 to 128. An omitted count remains unknown and cannot establish completion. Use confirmed starred spawn groups, not ordinary mobs or secrets. An empty list permits a healer-only route. |
| `healer` | Legacy field name for a walkable feet cell at the intended safe Blood/Wither-door approach. Routing cannot pass through a closed door. |

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

Minecraft world reads stay on the client thread. Both geometry builders check loaded chunk margins, a 0.6-wide/1.8-high player body, support, swept connectors, conservative one-block ascents and climbable vertical connectors. Small same-column adjustments up to 0.6 blocks may align actual slab feet with an integer candidate node only when the entire swept body is clear and every vertical sample stays supported or climbable; full-block vertical rises still require a ladder. Horizontal support probes shrink by half their sampling interval so a narrow unsupported hole cannot hide between samples. Neither builder loads chunks or publishes a partial scan. In authored mode, a node or edge absent from the template is never invented; live collision checks may remove authored nodes and edges. Automatic routing and explicit recording derive neighbor candidates, and every candidate passes the same native movement checks. Block/chunk invalidation uses the shared collision envelope, including support and jump-headroom margins.

## Routing and target state

`RoomNavigation` caches Dijkstra paths from fixed targets and the healer. Each replan searches from the player's actual reachable node, then uses Held–Karp dynamic programming to minimize the complete path through remaining targets **including the healer endpoint**. Cost sums horizontal and vertical movement blocks along validated edges: a horizontal step costs one and a one-block ascent with horizontal movement costs two. It is not a time prediction or straight-line distance. Up to 12 targets keeps exact ordering bounded.

A still-valid previous order is retained unless the new order saves more than three movement blocks. Meaningful movement requests a replan after 100 ms; target, room, geometry and teleport changes request it immediately. Player connections refresh each client tick, stationary observations reuse the previous result, and rendering interpolates the leading endpoint. New worker graphs can start planning immediately. Ordinary walking or jumping off the previous connector retains its checked remaining path while awaiting a replacement; actual teleports and invalid geometry hide stale guidance. A failed plan has one bounded retry after 500 ms. The connector may advance only within the first target leg and must pass collision/support checks, so it cannot skip a still-needed target or snap through a wall.

`RoomTargetStates` distinguishes `UNKNOWN`, `ACTIVE` and `CLEARED`. Only confirmed deaths complete targets, and the confirmed count must equal the group's authored `count`. Missing counts, incomplete sightings, excess observations and conflicting associations fail to unknown. Disappearance or chunk unload becomes unknown; an active observed member keeps its group unfinished. Confirmed completion survives temporary unloads and player death within the same run. Entity-to-target associations remain stable as mobs move or unload. Reaching the healer after clearing all targets hides that room's route for the rest of the run only when the player is nearby **and a safe connector reaches the endpoint**; proximity through a wall cannot finish the room. A newly observed mob can reactivate it. A run reset clears observations and completion. The 128-observation limit per target fails to unknown rather than claiming completion.

## Developer checks

Use Java 25:

```powershell
.\gradlew.bat dungeonRoutingCheck
.\gradlew.bat check build
```

`dungeonRoutingCheck` runs `roomNavigationCheck`, `roomTemplatesCheck`, `roomWorldGeometryCheck` and `roomRoutingUpdatesCheck`. Checks cover native-map calibration, joined rooms, L-shaped containment, map topology toward Blood, closed-door approaches, unknown exits, wall detours, optional endpoints, exact target order, live flood budgets and caps, immutable snapshots, unloaded cells, collision/support geometry, authored catalogs and target lifecycle. They do not establish every real floor's coverage or live rendering/FPS behavior, and do not replace Minecraft validation. Developer diagnostics should remain disabled during normal play and record room identity, orientation, origin, targets, endpoint, geometry status, route cost/order, replan reason and execution time only when requested.
