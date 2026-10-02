# Feature and persistence inventory

Recorded before the optimization edits on 2026-10-02. This is a source audit, not a claim of measured Minecraft CPU/FPS or live Hypixel verification. Paths below are relative to `src/main/kotlin/me/mycellium/skymyce/`. Startup, loader, networking APIs, renderer internals, and dependencies have separate inventories in the main performance report.

All feature modules are singleton `SkyMyceModule`s initialized once by `SkyMyce.initializeModules`. SkyBlockAPI subscriptions are filtered through the existing module authorization boundary. Explicit Fabric callbacks need their own gates. There are three explicit feature end-tick registrations: `DungeonFriends`, `GearLending`, and `CosmeticsClient`. `SkyMyceCommands` also drives the shared stats cache and a pending explicit PB lookup. No feature-owned coroutine scope or executor was found: asynchronous feature work uses the existing SkyBlockAPI scheduler or futures supplied by the shared relay.

## General and instance features

| Feature / source | Work and subscriptions | Context / interaction / persistence |
| --- | --- | --- |
| `features/general/AutoRefill.kt` | Right-click captures a refill; TickEvent checks a pending command against 500 ms entry / 1 s command cooldown. | Configured pearl/superboom/leap types; can cancel a final item use. One pending command, no API/cache/file. Pending work needs invalidation on disconnect. |
| `features/general/LoadoutKeybinds.kt` | Container initialization/close, key input, cached-slot render highlight; 3 s scheduled autoclose reset. | Loadout containers; server container input remains native. Twelve configurable keys; cached slots cleared on close. |
| `features/general/WardrobeKeybinds.kt` | Screen key input; empty container-init subscriber. | Native wardrobe API/container input. Twelve configurable keys. Current implementation checks `LoadoutConfig.enabled`; this is existing behavior, not an optimization target. |
| `features/general/StashHelper.kt` | Stash initialization computes price thresholds once; slot render and click read a cached set. | View Stash only; CTRL override preserved. Reuses SkyBlockAPI pricing. Cached Slot references cleared when another container opens, but no close/disconnect subscriber. |
| `features/general/PartyCommands.kt` | PlayerMessageEvent parses a precompiled command regex and dispatches allowed party commands. | Party only, config aliases/cooldown/permissions; shared PB, tracker and requeue helpers. No independent HTTP client. |
| `features/general/PersonalBestCommand.kt` | One explicit pending PB lookup polled by commands tick until data/deadline; connection/account/authorization binding. | `/m7pb` and `!pb`; shared stats cache/provider. At most one 60 s lookup, cancelled by identity/connection loss. |
| `features/general/Waypoints.kt` | Registered PlayerMessageEvent method is currently empty; parser/map retained. | No active waypoint implementation to claim as functional. No work, networking, or disk I/O currently. |
| `features/general/overlays/ClientInfoOverlay.kt` | HUD builder reads FPS/ping/TPS/block coordinates and formats four rows every frame when visible. | Optional client info; shared `ServerUtils`, no independent sampling/network. |
| `features/general/overlays/SkillXpOverlay.kt` | Action-bar change captures skill/display/time; action-bar render can cancel vanilla XP; HUD displays retained icon/label for 5 s. | Config option; cached skill item replaced only on change. No timer/network/file. |
| `features/general/overlays/AbilityCooldownOverlay.kt` | Right-click captures cooldown data; active-item and ready-notification HUD builders run per visible frame. | Existing cooldown timing, five-second notification lifetime and motion modes retained. Map retains historical IDs; ready rows convert IDs to items/names every frame. |
| `features/instances/InstanceRequeue.kt` | Chat/island events plus 1 s TickEvent; initial delay and downtime-message 1 s scheduled tasks. | Catacombs/Kuudra end, leader/config checks, ten retries. Timestamp/cancellation checks must preserve original command order and delays. |
| `features/instances/MissingPlayers.kt` | Chat match compares instance count with party count. | Config option; title only, no tick/network/file. |
| `features/mining/MiningFeatures.kt` | Island/chat events; visible CH HUD formats day/player/session/countdown rows every frame; delayed randomized nucleus warp. | CH only for chat; config retains info, Bal announcements, nucleus warp. Uses native time/location/party; no entity scanner/API. |
| `features/troll/plane.kt` | Catacombs chat trigger, island reset, one HUD callback, delayed 2 s achievement/sound. | Existing creator-once behavior, `espe.png`; small seen-player file read at initialization, written on trigger. Frame-counted existing overlay lifetime retained. |
| `features/troll/CheesePizza.kt` | 1 s TickEvent scans other players' equipped heads. | Existing `trueProfit` switch and disconnect behavior retained. Current filter/map/any chain allocates even when switch suppresses the action. |
| `features/dev/DevTooltips.kt` | Registers development item tooltip hook. | Developer tooling retained; no polling/file/API. |

## Dungeons and trackers

| Feature / source | Work and subscriptions | Context / interaction / persistence |
| --- | --- | --- |
| `config/instances/dungeons/PartyFinder.kt` | Container/slot-change/close/click and slot-render events. Parsed listings cached by slot; matcher reruns only when shared stats revision changes. | Party Finder/SkyBlock/config; native click behavior. Parsing currently recompiles formatting/floor regexes; developer messages eagerly interpolate lore/listings. |
| `features/instances/dungeons/PartyFinderMatcher.kt` | Pure class/PB/open-class matching. | No callbacks, network, disk or tasks. |
| `features/instances/dungeons/DungeonChestOverlay.kt` | Container computes contents/prices once; reroll event can cancel; slot render reads cached item text and short warning fade. | Chest/config/CTRL checks; shared pricing. Slot references have no explicit close/disconnect cleanup. Warning allocates AWT Color per rendered item. |
| `features/instances/dungeons/DungeonChestProtection.kt` | Earliest chest interaction calls pure profit calculator; bounded warning-key/cooldown. | Unknown value/cost permits native action, CTRL override. Preserves cancellation/security policy; no tick/polling/API client. |
| `features/instances/dungeons/DungeonChestProfitCalculator.kt` | Pure reward/cost computation with nullable price provider. | Explicit unknown versus zero, no callbacks/tasks/state. |
| `features/instances/dungeons/DungeonCleanChat.kt` | Catacombs chat checks configured groups of precompiled regexes. | Only selected filters suppress messages; no tick/render/network/file. |
| `features/instances/dungeons/DungeonWinOverlay.kt` | Catacombs score chat captures three lines; island clears; HUD callback samples shared motion values. | Config/hide-GUI checks; rank textures loaded by MC. No timer/network/file; preserve all reveal motion/quality. |
| `features/instances/dungeons/tracker/DungeonTracker.kt` | Queue/enter/start/chat/chest-open/reroll/profile/disconnect events. Inventory confirmation is coalesced through DigestRng. | Context-bound run/chest receipts; all-time totals, 500 ms coalesced atomic background saves; startup queue max 200. |
| `features/instances/dungeons/tracker/AcquisitionRepository.kt` | Startup asynchronous migration; immutable archive changes on actual run/drop/pin/filter events. | Account/profile/alpha context; max 100,000 events/runs, 2,048 pins, startup queue 1,000; atomic archive (32 MiB) and preference files with 500 ms coalescing. |
| `features/instances/dungeons/tracker/DungeonTrackerWidget.kt` | Visible HUD builds formatted totals every frame though totals have `DungeonTracker.revision`. | Dungeon hub/Catacombs; current active/completed rules and editor-only preview retained. |
| `features/instances/dungeons/tracker/DungeonScreen.kt` | Screen tick checks revisions; 120 ms query debounce, one background query with generation/closed guards. | Visible screen only; native menu controls, immutable archive query and scoped filters; preferences through repository. |
| `features/instances/dungeons/tracker/AcquisitionData.kt`, `ChestTracker.kt`, `FloorTracker.kt`, `TrackerView.kt` | Pure codecs/records/aggregation/query; query captured on client and processed off-thread. | Canonical item IDs; legacy unknown timing/value metadata preserved. No standalone listeners/network/timers. |

## Friends, social, and cosmetics

| Feature / source | Work and subscriptions | Context / interaction / persistence |
| --- | --- | --- |
| `features/instances/dungeons/friends/DungeonFriends.kt` | End-client tick, allow-command, party info, chat/private replies, profile/disconnect. Every tick currently reconstructs party roster/class/saved-party copies, traverses stats candidates, constructs join context and prunes queues. | Authorization/Hypixel gates; scanning config controls scanner, not relay/social functionality. Party data request 60 s (10 s minimum/retry rules); commands paced 1 s; scanner save change-only/5 s. Shared relay supports independent IRC/cosmetics/digest; do not disable it merely because scanning is off. |
| `.../friends/DungeonFriendsScreen.kt` | Visible screen tick compares stats/options/state, refreshes action availability/policies. Currently copies online map each tick. | Manual refresh/open throttle 60 s; class/PB filters, invitations, join agreements, tooltips/progress. |
| `.../friends/FriendsSocialScreen.kt` | Visible screen tick compares wealth/ledger/friends; currently copies complete friends map each tick. | Saved friends/loans/wealth pages; wealth refresh explicitly paced; no independent network task. |
| `.../friends/FriendListScanner.kt`, `FriendListStore.kt` | Pure scanner state with version; requests pages >=1.2 s apart, 10 s response deadline, at most 1,000 pages; chat updates online/full snapshots. Atomic friend-list persistence per MC UUID. | Does not consume unrelated chat; manual commands yield scan; no background executor. Full roster remains user data, not a disposable size cap. |
| `.../friends/DungeonFriendData.kt`, `DungeonFriendJoining.kt` | Pure bounded request/offer/title/reply/party state machines with timestamp expiry. | Friendship and UUID checks preserved, no synthetic duplicate actions. Join negotiation must remain tick-responsive while pending. |
| `.../friends/DungeonFriendRelay.kt`, `RelayMessages.kt`, `IrcMode.kt` | One authenticated shared JDK WebSocket; paced reconnect, 45 s ping, bounded deadline/request/delivery queues; parsing dispatched to client. | Party agreement, friend chat/delivery ACKs, IRC, cosmetics and digest protocols; out-of-context/old-generation results rejected. Network agent owns detailed audit. IRC dedupe 256 IDs. |
| `.../friends/DungeonFriendStatsCache.kt`, `DungeonFriendStatsStore.kt`, `DungeonFriendProfileProvider.kt` | Shared queued/coalesced relay/provider/Hypixel/Mojang requests, at most one external fetch and three shared prefetches; client tick drives queue. | Party/PF/PB/profile freshness, verified UUID/profile checks, API cooldowns and atomic saved stats. Networking agent owns changes. |
| `.../friends/FriendWealth.kt`, `FriendWealthStore.kt` | Shared single-flight provider/relay wealth; retries per friend; saved complete estimates 24 h, changed files 5 s. | Cached friends, online/context/explicit refresh selection; foreground tick currently materializes roster/candidates when no entry needs refresh. Networking agent owns changes. |
| `.../friends/FriendWealth.kt` / `GearLedger.kt` | Pure wealth/loan records and calculations; GearLedger reads on first account access, atomically saves before publishing on completion/mark. | Loans persist per account. Ledger history intentionally retained; entire-file work is cold but synchronous, size/read bounds deserve follow-up. |
| `.../friends/GearLending.kt` | Explicit end-tick plus container/slot-click and confirmed-trade chat. Every active trade tick parses 32 item slots/lore and allocates a full record; even unrelated menus compile title regex. | SkyBlock trade only; 5 s confirmation capture, name truncation handling, friendship validation. Native trade/server confirmation is authority; closing alone never records. |
| `features/social/CosmeticsClient.kt` | End-client tick, player-message identity observation; discovery once/s, refresh 5 min, offline expiry 10 min, bounded batch16 and one future. | Loader/Hypixel/account/generation validation, bounded cache/identity/checked maps <=512. With both rendering toggles off, discovery still precedes the disabled return. |
| `features/social/CosmeticNames.kt` | Render hooks use bounded display-copy caches (256 sequences/256 strings); identity/name mapping built outside rendering. | Inert safe component subset, original tooltip/command identity retained; never mutate server components or outgoing input. |
| `features/social/CosmeticsScreen.kt` | Visible screen tick reacts to `CosmeticsClient.revision`; explicit owner refresh/link. | Link codes expire <=6 min; no public key exposure, no extra poller/executor. |

## Daily Digest and configuration

| Feature / source | Work and subscriptions | Context / interaction / persistence |
| --- | --- | --- |
| `features/digest/DailyDigest.kt` | Join/profile/leave events, screen-remove callback, one cancellable wake future: initial3–30 s, temporary2 s delayed-profile retry <=60 s, then next midnight. | Account/profile/alpha bound immutable view; menu opening daily gate; clears/cancels news/subscription/context on leave. Bounded saved profiles64; 500 ms coalesced background persistence. |
| `features/digest/DigestActivities.kt` | Mayor/chat/dungeon-start/relevant-container/slot/scoreboard events. | Six local tasks, forge slots7, calendar events24. Countdown corrections persisted at most once/minute. Uses already-parsed game APIs rather than another HTTP client. |
| `features/digest/DigestRng.kt` | Narrow chat RNG parser and PlayerInventoryChangeEvent; relevant changes coalesced200 ms then aggregate inventory confirmation. | Catalog-limited drops; context cancels future; never announce preview-only loot; recent event dedupe. |
| `features/digest/DigestNews.kt` | One future/source, 5 min fresh cache, 60 s minimum attempt, no automatic retry loop. | Eight safe inert items/source, bounded content; retains stale data on failure; generation cancel. |
| `features/digest/DailyDigestScreen.kt` | Visible screen tick responds to view revision; countdown/date text once/s, retained changed cards only. | Freshness/visibility from coordinator; no independent fetch timer. |
| `features/digest/DigestLifecycle.kt`, `DigestStorage.kt`, `DigestView.kt` | Pure daily gate/parser/history/view and latest-state background writer. | Last-opened cache64, bounded RNG retention, strict JSON, atomic temp-file move/recovery; shutdown flush retained. |
| `config/Config.kt`, `ConfigPersistence.kt` | Resourceful schema loads once, merges unknown original fields, 100 ms coalesced atomic saves and shutdown flush. | Existing config paths/defaults/migration and corrupt-original preservation retained. Parsing/merge on explicit save is cold. |
| `config/SettingsScreen.kt` | Retained settings controls and navigation; event-driven config edits save through wrapper. | Existing search/categories/keybinds/theme/context UI preserved. No independent poller/network. |
| `config/misc/{General,DailyDigest,PartyCommands,StashHelper,Wardrobe,Loadout,Theme,Cosmetics}Config.kt`, `config/instances/{InstancesConfig,dungeons/{Dungeons,DungeonTracker,PartyFinder,DungeonFriends}Config}.kt`, `config/mining/MiningConfig.kt` | Declarative categories, registered through the existing tree. Theme retains four quality / three motion modes. | Config values are read on next native event/frame/tick; optimization must not freeze runtime changes. `DungeonFriendsSettings` uses separate atomic save-first preferences and party restoration. |
| `utils/AtomicJsonFile.kt` | Strict bounded JSON read, preserves damaged original, atomic writes. Shared StateWriter coalesces latest submitted state and flushes on shutdown. | Never remove recovery/bounds to save CPU. Writers/files serve distinct user data; no speculative common cache needed. |

## Priorities and verification

These are observed repeated operations, not a ranking from a live client profile:

1. Avoid formatting unchanged feature HUD rows each frame. Tracker revision and current primitive values supply exact change detection; ability fades still sample every frame.
2. Remove disabled cosmetics discovery and repeated identity-regex/entry allocation. Retain manual owner sync, link expiry and online authorization checks.
3. Replace full friend-map copies in visible menus with existing scanner identity/version, and avoid party-roster reconstruction when native party API data is unchanged. Continue pending join/ACK/deadline work each tick.
4. Use slot-change/container/click events for expensive trade capture; retain a minimal trade-window detector and five-second last-observation semantics to tolerate server close-before-chat ordering.
5. Profile shared stats/wealth/relay queues before changing rate policy. Preserve verified profile freshness, API cooldowns, security heartbeats, and IRC/digest/cosmetic availability independently of scanning toggle.
6. Clear cached container Slot references on close/disconnect, and reset delayed command state when its connection/context ends. Keep unchanged cancellation/CTRL/error behavior.

Run `dungeonFriendsCheck` (also invokes cosmetics, tracker, dungeon timing, auction, PB and digest checks), `hudCheck`, and the focused profit checks. Add focused deterministic assertions for new event/change gates, stale close/disconnect state, repeated trade confirmation, and cache name/UUID replacement. A standalone JVM allocation/timing fixture can compare pure operations before/after; it cannot substitute for real Hypixel tick/render/FPS or a 2–4 h session. Those scenarios remain explicitly unmeasured until run in a launched authenticated client.

## Measured source-operation checks

On Java 25, `featureWorkCheck` compares bounded warmed old/new paths on the same thread (four warmups, five samples, median wall time and allocated bytes). The identity fixture retains the previous implementation; activity classification is checked against the previous getter; the cosmetic touch fixture compares the previous remove/reinsert with the actual bounded cache touch. Both versions produce matching results. These measurements cover the stated operations, not network polling, whole-client memory, FPS, or GPU time.

| Work per sample | Previous wall / allocated | Updated wall / allocated |
| --- | --- | --- |
| 3,072 repeated identities across 256 players | 7.359 ms / 3,677,496 B | 4.194 ms / 580,920 B |
| 5,120 cosmetic cache touches across 256 records | 1.063 ms / 205,464 B | 0.619 ms / 664 B |
| 5,120 immutable friend activity reads | 6.868 ms / 10,694,744 B | 0.430 ms / 664 B |

Focused `featureWorkCheck`, `dungeonFriendsCheck`, `hudCheck`, and `menuMotionCheck` passed together. Regressions also cover exact automatic freshness deadlines, disconnect queue ownership/version, request expiry callbacks that enqueue new work, priority identity aliases and UUID changes, actual saved roster reload/activity/schema, and the trade confirmation window after long-open menus. The native inventory event fires before the slot changes; capture explicitly uses that event's replacement stack. The custom renderer checks retain transformed pixel/input parity and all existing motion modes.
