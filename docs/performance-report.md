# Sky-Myce performance implementation report

Implemented against `a58b34d00541d0670e43ee7faa5422437e8967e3` on 2026-10-02. Target remains Minecraft 26.1.2, Java 25 and Kotlin 2.4.0. Measurements use Java 25.0.1 and Node 22.15.0 on the same local Windows machine. The existing GUI, configuration, feature set and authorization boundaries remain in place.

This report distinguishes measured local work from source-audited behavior. Authenticated Hypixel gameplay, startup timing, low-end hardware, process-wide CPU/RAM/FPS and 30-minute/1/2/4-hour sessions were unavailable. Those acceptance checks remain unverified; headless fixtures cannot establish them. No deployed service or account authority was changed for testing.

The [2026-10-10 menu follow-up](menu-optimization-2026-10.md) records a separate pre-edit baseline and comparable measurements for cached search, stable navigation geometry, idle ambient state and the shared Settings rail. The historical measurements below retain their original conditions.

## A. Feature inventory

The pre-edit inventories trace sources, native events, tick/render callbacks, jobs, timers, networking, cache ownership, persistence, resources and context gates:

- [All feature and persistence owners](performance-feature-inventory.md).
- [All menus, HUDs, motion, input and rendering owners](performance-gui-inventory.md).
- [Client APIs, relay, bootstrap and security owners](performance-network-inventory.md).

The following capabilities retain their implementations and entrypoints. Functional evidence is the relevant executable checks and source/caller review, rather than an unperformed live playthrough.

| Retained capabilities | Verification / preservation |
| --- | --- |
| Auto refill, loadout/wardrobe keys, stash valuation and CTRL override, party commands and explicit PB lookup | Native actions, cooldowns and config paths retained; delayed refill and cached slots clear on context loss; command/PB checks retained. |
| Client info, skill XP, ability cooldown/ready notifications | Existing text, values, countdown deadlines and motion retained; HUD checks cover formatting/layout/lifecycle; only unchanged formatting is reused. |
| Instance requeue, missing-player warnings, Crystal Hollows info/Bal/nucleus actions | Existing context and command timing retained; event-driven features were not rewritten into pollers. |
| Plane/achievement/sound and CheesePizza behavior, developer tooltips/messages | Required plane art remains; inactive entity discovery is gated; lazy developer messages retain their existing output. |
| Party Finder matching/filtering/highlights, friend scan/pages, invite/join/replies and saved party classes | Scanner/matcher/join/deadline checks retained; native roster/class/leader, scanner and cache revisions invalidate reused work. |
| Social friends, wealth, loan/trade ledger, public stats/PBs and optional profile integrations | Store reload, exact expiry/retry, alias/UUID, cancellation and server-confirmed trade checks cover changed paths. Closing a trade alone does not create a record. |
| Dungeon chest prices/profit protection/reroll warning, clean chat, dungeon completion HUD | Unknown versus zero prices, CTRL/cancellation and native pre-action checks retained; pure profit and render checks retained. |
| Dungeon run/acquisition/chest/floor tracking, history, queries, profit and HUD | Existing event capture, context, archives, atomic persistence, query generations and tracker checks retained; unchanged HUD summaries use the tracker revision. |
| Daily Digest, history, tasks/forge/calendar, market/news and RNG/drop tracking | Existing daily gate, source freshness, generation cancellation, inventory confirmation and atomic retention checks retained; diagnostics expose the existing single wake/job owners. |
| Relay party policies, friend chat/ACKs, IRC, cosmetics/render names/linking and shared digest/stat/wealth protocols | Bounded queues, generation/identity gates, deadlines and tests retained; scanning disabled still permits independently required social/IRC/digest/cosmetic work. |
| Settings/search/keybinds, theme presets/import/export/overrides, HUD editor and all feature menus | Native controls, focus, text editing, tooltips, clipboard/IME, viewport and input transforms retained; exact-pixel fixtures and HUD/motion checks. |
| Four graphics modes and Full/Reduced/Off motion, transitions, hover/focus, collapsibles, modal/toast/dropdown behavior | Pixel/dirty-region/Unicode/style/subpixel and transition interruption checks; no removal of animation or quality settings. |
| Protected bootstrap, activation/update/install/recovery and full relay license/Discord administration | Existing loader and relay security suites retained; no security cadence or mutable authority check removed. |

`Waypoints` was already an empty event subscriber, and wardrobe key handling already checked the loadout switch. These baseline limitations remain; they are not represented as newly functional features. Every individual owner, including pure data/codec/config helpers, is listed in the inventories.

## B. Biggest bottlenecks found

The measured standalone renderer allocates for unchanged commands: the original quality fixture uses 8,663–11,472 B per idle frame despite zero dirty pixels. Its larger 60-card run has expensive per-glyph Java2D work: changing raster median 5.914 ms, plus whole-image conversion/copy 6.753 ms. JFR sampled allocation weights include glyph keys approximately 71.7 MB, boxed doubles 16.1 MB and paint records 15.4 MB across the complete fixture. These sampled weights include warm-up and are not retained-heap sizes.

Actual DRM functions repeatedly import unchanged keys and self-test unchanged signing configuration: 300 warm cycles perform 1,200 imports, 600 signs and 600 verifies. Actual SQL authorization checks also rewrite unchanged heartbeat telemetry.

Actual pure feature fixtures confirm repeated cosmetic identity/touch allocation and repeated location-to-activity parsing. Source review additionally finds full roster/stat/party/trade reconstruction in client ticks, repeated visible HUD formatting and three regex compilations per valid auction listing. This is a ranking of measured local operations and audited repeated work; a global live-client CPU ranking is still unavailable.

## C. Architectural changes

Existing singleton modules, native events, authorization lifecycle, scheduler and atomic persistence remain the architecture. The change uses their existing revisions, deadlines and ownership instead of introducing a replacement framework.

Party/friend state is reconstructed when its inputs change. Native friend records are compared once with a snapshot and indexed by name, replacing repeated linear UUID lookups. Visible menu data uses scanner identity/version. Automatic stats work wakes on roster/cache/capability revisions or its exact refresh deadline. Disconnect, discarded in-flight completion and dequeued work invalidate cached scheduling ownership, including API-key changes.

Trade capture responds to container, click and authoritative inventory updates. The installed slot-change event fires before the slot stores its item, so capture substitutes the incoming item for that slot. A cheap tick retains the unchanged trade's five-second completion window. Disabled cosmetics skip discovery; first required relay connection creates its shared HTTP client lazily.

## D. Tick optimization

Unchanged party ticks compare native member/name/UUID/leader/size/class fields without rebuilding the saved party. Pending replies, joins, offers, ACKs, titles and command deadlines continue to progress normally. Idle join processing no longer constructs a command context; shared policy objects are reused only while their actual inputs match.

Stats bulk queue traversal skips unchanged inputs until the exact existing eligibility deadline. Wealth with no candidate scans at most once per second, waking earlier for its existing expiry/retry, explicit refresh/new friend, roster/UUID, account or relay-capability change. This gates discovery work rather than reducing API freshness.

Unchanged trades avoid decoding all 32 item slots every tick. Disabled entity/cosmetic features skip discovery. Fixed chat/lore/floor regexes are compiled once; suppressed developer output does not build interpolated strings.

The unused synthetic `ServerTickEvent` producer and type were removed after checking all callers. Its suspend subscriber did not match the installed bus's one-argument registration signature, so this deletion is not claimed as an active packet/tick CPU gain. Cheap native tick gates and security deadlines remain necessary.

## E. Render optimization

Paint geometry/colors use primitive fields. Equal commands reuse the previous immutable record at the same index after checking all geometry bits, styles, image identity, transform, clip, opacity and group. No reusable mutable paint pool was introduced.

Glyph hits use a reusable lookup probe which is never inserted into the bounded map. Stored keys remain separate. Equal transform/scissor state is reused; labels/buttons retain runs and widths until text, size or color changes. Whole offscreen text lines use conservative bounds from all regular/fallback font styles, retaining styled/Unicode/subpixel output.

The HUD's normal path traverses its weak references directly; editor/save paths keep snapshots. Dead references, registration during traversal and reverse pointer priority are checked. Client-info, tracker and ready-name text reuse is invalidated by actual displayed inputs; animation clocks and notification expiry continue every frame.

Existing damage-limited rasterization/upload, font, colors, shapes, quality effects, text metrics, focus and pointer transforms are preserved. Large-menu, resized and styled/Unicode/transformed PNGs compare identically with the original fixture; quality and motion checks also test exact pixels and dirty bounds.

## F. Memory optimization

Final local allocation metrics are in section M. Retained font/glyph/line caches still cap at 64/4,096/32, gradients at four and skull art at 128. Snapshot owners remain one outgoing page, one tooltip and one per collapsing control; interrupted transitions flatten their outgoing state instead of retaining a chain.

Relay party-policy history now expires on insertion and caps at 1,024; its 20-second freshness is unchanged. Cosmetic identities/state stay bounded at 512, with alias priority and existing expiry rules retained. Stable records use Java 25 ordered-map repositioning without allocating replacement nodes.

Container Slot/content references clear on close, unrelated menus, island/disconnect and module disable; delayed refill and trade context clear on connection loss. These fix concrete retention paths. A long-session heap leak has not been measured or declared solved. Persistent friend/PB/acquisition/loan history is user data and is not discarded as a disposable cache.

## G. Networking

Auction parsing reuses two compiled UUID/style expressions: a valid 2,000-row page no longer compiles 6,000 expressions. Request deadlines, response/NBT bounds, pagination, 500 ms pacing and stale-generation checks remain intact.

Relay timeout sweeps allocate a callback snapshot only when work expires/cancels, allowing completion callbacks to enqueue safely. One WebSocket, existing ping/liveness/reconnect policy, queue/message limits and fresh account proofs remain. Wealth/stats retain provider serialization, exact TTLs, rate limits, shared lookup grants and at most one upstream job.

Service heartbeat telemetry writes only for a newer observation from the current connection. An actual Miniflare SQLite trigger records zero writes for 50 unchanged observations, exactly one for a new current-connection timestamp, and none for older/retired observations. Each call still rechecks mutable authority. Actual deployed message/request rates and public-server latency remain unmeasured.

## H. Threading/coroutines

No new executor, feature thread, coroutine scope or repeating timer was added. Features continue to share SkyBlock API Scheduling, JDK networking/futures and the existing coalesced state writer. Profile setup/dump/report serialization uses that scheduler, with one busy guard.

Existing single-flight stats/wealth jobs, digest wake/source/RNG tasks and screen query generation guards are retained. Lazy relay client creation defers its resources until required; it is not a measured startup/thread-count improvement. Cold diagnostics show feature-owned jobs/queues and JVM platform-thread states. A global coroutine count cannot be obtained reliably from shared dispatchers and is not invented.

## I. Dependency/JAR reduction

The complete resolved Gradle graph is [performance-runtime-dependencies.txt](performance-runtime-dependencies.txt). The recursive artifact inventory, including exact nested versions, dependency constraints and compressed sizes, is [performance-artifacts.json](performance-artifacts.json). Reproduce it with the standard-library-only [artifact script](../scripts/performance-artifacts.py); nested byte sizes overlap their parent and cannot be summed.

| Direct/runtime dependency | Why retained / packaging |
| --- | --- |
| Minecraft 26.1.2; Fabric Loader resolved 0.19.5 (minimum 0.19.3); Fabric API 0.145.1+26.1 | Game, native event/input/render hooks and mod loading; environment dependencies rather than copied game code. |
| Fabric Kotlin 1.13.12+kotlin.2.4.0 | Language entrypoints, stdlib/reflect/coroutines/serialization/atomicfu; external language runtime. Exact transitive versions are in the graph. |
| SkyBlock API 4.2.19 | Shared game/profile/party/container/chat/pricing events and scheduling; directly nested once. |
| SkyBlock Profile Viewer 1.8.9 (`iVpdy3W0`) | Optional-provider profile and wealth integration plus required entrypoints/mixins; retained with shared copies removed. |
| Hypixel Mod API 1.0.2 | Native party/info protocol; core directly nested. |
| Resourceful Config 4.0.1 and Kotlin 4.0.0-beta.1 | Existing config schema/settings; directly nested once each. |
| owo 0.13.1+26.1 / sentinel | Native GUI widgets, layout, focus and adapter; sentinel in mod, full owo in bootstrap for first activation. |
| DevAuth 1.2.2 | Development-only account authentication, not a protected-release embedded feature. |
| Gson / JDK Java2D, HTTP, JFR and crypto | Minecraft/shared/native facilities; bootstrap retains Gson for standalone installation. No new production dependency. |

Indirect embedded repo-lib 4.1.5, meowdding-lib, Olympus, Placeholder API, Resourceful Lib and owo's nested support libraries retain their upstream responsibilities. The recursive inventory gives every ID/version/declared edge; the inventories connect event/config/profile/GUI integrations to their callers. Game-owned native/logging/network/codec libraries remain in the resolved graph; there is no demonstrated replacement benefit.

The viewer contained four copies already shadowed by selected Fabric mod IDs: SkyBlock API 4.2.18, byte-identical Resourceful Config/Kotlin, and repo-lib 2.0.2 (4.1.5 already selected). All other viewer classes/assets/entrypoints/mixins and nested libraries remain byte-identical, and every declaration resolves to a retained jar. Build checks fail if this upstream layout changes. Loom's module/capability nesting machinery is preserved. Fabric resolves one version per mod ID; this packaging removes redundant stored copies. See the primary [Fabric Loader](https://docs.fabricmc.net/develop/loader/) and [Loom](https://docs.fabricmc.net/develop/loom/) documentation.

Unreferenced `marina.png` (408,838 B) and `espe.jpg` (260,670 B) were removed after code/config/metadata/resource-name review. Required `espe.png`, fonts, rank art, icon and licenses remain. Final artifact measurements are in section M. Bootstrap security/dependencies are unchanged.

## J. DRM

Only immutable crypto setup is reused: at most 256 public-key imports, four private-key imports and four validated configurations, keyed by exact configured bytes. Concurrent identical setup coalesces; failures evict; configuration values are captured before awaiting. Every actual lease still signs/verifies, and every required message signature, replay consumption and mutable authority lookup remains real.

One-license Discord ownership, multiple linked Minecraft UUIDs, persistent hardware binding, one device/session, seven-day physical transfer, existing authorization lease/online/revocation deadlines, signed leases/releases, anti-replay, protected bootstrap, build revocation and audit reporting remain in their existing implementations. No cadence, proof, grace period or authority property was relaxed. Native profiling events carrying launch/environment/process arguments or exception text are disabled.

The existing loader and full local relay suites cover signatures, identity/HWID/transfers, session generations, revocation, replay, signed Discord interactions, confirmations/recovery, artifacts, IRC and prompt authority. New checks cover key rotation, forged tokens, failure retry, concurrent imports, bounded eviction and mutation during setup. The heartbeat check confirms telemetry deduplication does not skip authentication.

## K. Debugging/profiling

Existing logs, developer messages and item tooltips remain. New `/sm profile start|report|stop` is gated to Fabric development or explicit `-Dskymyce.developerProfiling=true`, under the existing loader authorization gate.

The optional native recording caps at 64 MiB/fifteen minutes; fixed inclusive scopes report sorted CPU/wall/allocated/network bytes, current FPS and actual cache/module/job/queue/socket/thread gauges. Inactive inline scopes read no clock and allocate no scope. Client-owned state is snapshotted on the client thread; JFR/file work uses the background scheduler. Stop/shutdown closes recordings and queued starts cannot revive them.

Tests exercise concurrent counters, exception propagation, native recording, excluded sensitive events and shutdown. See [profiling instructions and reproduction commands](performance-profiling.md) for metric limits, stack inspection and the live scenario checklist. Global listeners/coroutines are not fabricated: the installed screen API replaces screen-local events at init/resize, so current-screen callback registration remains correct.

## L. Hypixel testing

| Scenario | Evidence / status |
| --- | --- |
| Pure dungeon, party/friend/PB, auction, acquisition, digest/RNG, cosmetic, trade, cache and persistence behavior | Executable Java 25 checks; source review of native event ordering and context/authorization gates. |
| GUI quality/motion/input/Unicode/style/transformed clips, repeated snapshot and weak-widget lifecycle | Headless pixel/layout/motion checks and baseline-identical large/resize/text fixtures. |
| WebSocket/IRC/Discord/license/transfer/session/revocation/artifact behavior | Local Wrangler dry-run, TypeScript and Miniflare/relay suite; no deployment. |
| Hub idle/active, Catacombs, mining, live auction/friend/relay, outside Hypixel and world/authorization transitions | Live gameplay not run; requires authenticated signed release and player/client harness. |
| 30-minute, one-hour, two-hour and four-hour stability; low-end comparison | Not run; process CPU/RAM/FPS, network rates and long-session heap stability remain unverified. |

Final verification passed: Java 25 `gradlew --offline check build` (all executable checks, including native JFR privacy/shutdown and shared-runtime byte preservation), optional `menuCanvasBenchmark`, relay `npm run check` and complete `npm test`. The artifact inventory checks both final jars. These commands perform local builds/tests; no deployment occurred.

## M. Before/after metrics

The original measurements and fixture definitions are in [performance-baseline.md](performance-baseline.md). Timing is machine/workload dependent; no fixture timing is converted into game FPS.

| Warm source-operation fixture | Before median | After median | Before allocated B | After allocated B |
| --- | ---: | ---: | ---: | ---: |
| Cosmetic identity discovery, 3,072 operations | 7.359 ms | 4.194 ms | 3,677,496 | 580,920 |
| Stable cosmetic record touches, 5,120 operations | 1.063 ms | 0.619 ms | 205,464 | 664 |
| Friend activity lookup, 5,120 operations | 6.868 ms | 0.430 ms | 10,694,744 | 664 |

These use reconstructed pre-change operations and actual current pure types in one warmed JVM. Allocations include the fixture's fixed measurement overhead. Identity discovery still enforces priority alias semantics; it is not claimed to eliminate every roster scan.

| Actual local relay crypto fixture, 300 warm cycles | Before | After |
| --- | ---: | ---: |
| Five-sample median wall | 595.339 ms | 136.315 ms |
| Key imports | 1,200 | 0 |
| Sign calls | 600 | 300 |
| Verify calls | 600 | 300 |

All remaining 300 signs/verifies serve actual leases. Removed signs/verifies were repeat self-tests of identical setup.

| Quality fixture, 480 × 280 logical pixels | Before idle B/frame | After idle B/frame | Before moving B/frame | After moving B/frame |
| --- | ---: | ---: | ---: | ---: |
| Extremely Light | 8,663 | 1,687 | 39,864 | 26,022 |
| Light | 9,480 | 1,709 | 68,760 | 44,071 |
| Medium | 11,096 | 1,679 | 72,823 | 46,001 |
| High | 11,472 | 1,800 | 73,418 | 44,903 |

Idle allocations fall 80–85%; moving allocations fall 35–39%. All idle frames still have zero dirty pixels. Final idle/moving wall ms per mode are 0.018/0.476, 0.049/0.433, 0.010/0.800 and 0.031/1.226. These short wall measurements vary with desktop/JIT activity and do not establish a uniform speedup.

| Isolated 60-card fixture, 960 × 540 logical pixels at 2×, JFR enabled | Before median ms | After median ms |
| --- | ---: | ---: |
| Text measurement | 0.102 | 0.089 |
| Static command extraction | 0.070 | 0.049 |
| Changing raster | 5.914 | 5.604 |
| Changing raster plus historical full-image conversion/copy | 6.753 | 7.141 |

Fifty warm-ups and 121 samples run in a separate JVM without competing builds. The full-copy result regresses in this sample; no whole-copy speedup is claimed. Production already copies only damage. All three large/resize/styled-text PNGs are byte-identical and the width checksum is unchanged at 26896.42608642578. Final JFR sampled paint allocation weight is approximately 1.6 MB versus 15.4 MB originally; glyph-key and boxed-double types are absent from the final samples. Java2D transforms/arrays/graphics remain prominent. Sample weights are estimates across the entire fixture, not exact heap savings.

The optional Gradle task was also run successfully without JFR: measurement 0.080 ms, static 0.038 ms, changing 5.933 ms and changing plus full copy 8.603 ms. That different recording configuration is a reproducibility check, not another controlled before/after comparison.

| Built artifact | Before B | After B | Reduction |
| --- | ---: | ---: | ---: |
| `skymyce-2.4.12.jar` | 20,172,546 | 13,430,609 | 6,741,937 B / 33.42% |
| `Sky-Myce-Loader.jar` | 2,537,443 | 2,537,443 | 0 |
| Nested viewer jar (overlaps mod total) | 11,112,115 | 4,998,941 | 6,113,174 B |

The artifact script checks all nested declarations and duplicate zip entries; `sharedRuntimeCheck` verifies retained class/resource bytes and metadata. Bootstrap size remains identical. Startup time, game FPS, process CPU/heap and live network rates have no before/after value in this environment.

## N. Remaining bottlenecks

Changing text-heavy menus still rasterize styled glyphs with Java2D, and necessary effects/texture transfers remain. The large fixture still spends milliseconds rasterizing changing frames; its whole-image copy deliberately exceeds production's damage-limited transfer. No unsafe glyph batching or renderer replacement was introduced without exact output evidence.

Large archived tracker queries, synchronous cold loan-ledger load/save and profile-provider cold work can still be expensive. They are not shown to dominate active gameplay here. Native friend APIs require cheap comparisons because their mutable lists lack a supported complete revision signal. Context/deadline checks, pending trades/joins and security renewals cannot disappear.

Relay fanout still performs persistent authority checks per required recipient/message; correctness outweighs speculative authorization-result caching. Public-server load, long-session growth, startup and game GPU/CPU rankings need the live harness before claiming complete acceptance.

## O. Recommended future work

Run the documented signed/authenticated scenario matrix on the same normal and low-end machines and compare measured startup, frame times, CPU, equivalent post-GC heap, cache/job/socket counts and request rates for the full session durations. This closes a significant evidence gap rather than prescribing another architecture.

If live stacks confirm changing Java2D text is a top game bottleneck, investigate glyph batching with existing styled/Unicode/subpixel pixel checks before changing the renderer. Retain current authority checks and rate policies unless deployed load measurements show a significant issue that can be solved without weakening security.
