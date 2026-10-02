# GUI and HUD optimization inventory

Baseline: the implementation after the GUI/motion work, before the optimization pass. Appearance, proportional text metrics, clipping, pointer/focus behavior, saved settings, all four quality modes, and Full/Reduced/Off motion are regression requirements. Headless CPU measurements below are not Minecraft FPS, GPU time, or Hypixel session measurements.

## Shared menu/render implementation

All source paths below are under `src/main/kotlin/me/mycellium/skymyce/` unless stated otherwise.

| System | Source | Dependencies / entrypoints | State and lifetime |
| --- | --- | --- | --- |
| Menu shell, sidebar and navigation | `ui/MenuScreen.kt` | Minecraft `Screen`, owo `BaseOwoScreen`/adapter/layout/focus; screen init, extraction and native input callbacks | Per-screen retained adapter, logical viewport, one canvas/texture, sampled opening/sidebar/page motions, one outgoing page raster, one active/closing dropdown, tooltip and three toasts. Resize/theme revision rebuilds layout. Removal clears effects and closes render resources; parent screen layout can intentionally survive reopening. |
| Paint extraction and text/style conversion | `ui/MenuGraphics.kt` | owo graphics/pose/scissor, Minecraft `Component`/visual-order sequence, `MenuTextTooltipAccessor` mixin | A graphics wrapper/render state is created per extraction. Every primitive synchronizes transform and scissor. Text produces immutable style runs; tooltip owns a sampled fade and at most one outgoing raster. No worker or network work. |
| Cached Java2D rasterizer | `ui/MenuCanvas.kt` | JDK Java2D, bundled Lato regular font plus platform fallback | Per-canvas pending/previous paint lists, straight ARGB image, four gradient rasters; global LRU glyph/font/line caches bounded to 4096/64/32. Equal paint lists skip rasterization. Changing frames calculate conservative damage and redraw through that clip. Snapshots flatten into one inert cropped raster at interaction time. Close clears per-canvas lists/images. |
| Texture upload | `ui/MenuTexture.kt` | Minecraft `DynamicTexture`, LWJGL direct image buffer | One texture and conversion buffer per owner; resize reallocates, revision gates upload, dirty region limits conversion/upload. Render-thread-only; close releases texture/buffer references. |
| Native controls and palette | `ui/MenuUi.kt` | owo buttons/labels/sliders/dropdowns and shared theme configuration | Native editing/input/narration retained. Per-control sampled hover/press/selection/toggle/focus/dropdown motions. Button wrapping is already cached by message, width, language and navigation mode. Theme getters respect preset and explicit overrides. |
| Text editing | `ui/MenuTextBox.kt`, `src/main/java/me/mycellium/mixin/MenuEditBoxAccessor.java` | owo/Minecraft EditBox editing, clipboard, IME, proportional canvas metrics | Field-local text/cursor/caret epoch/scroll and focus motion; measurements and prefix substrings currently repeated during extraction. Native caret selection/word navigation preserved. |
| Smooth scrolling | `ui/MenuScroll.kt` | Native owo scroll geometry, clipping, keyboard/focus and drag | One sampled position; wheel/keyboard input changes native target, scrollbar drag stays immediate. No scheduled work. |
| Collapsible controls | `ui/MenuCollapsible.kt` | Native owo children/toggle events and focus/input | Animated real height plus chevron/hover; collapse removes live children and retains one raster during exit. Native focus traversal excludes clipped controls. Explicit `releaseMotion()` clears images/canvas references when retained screens close. |
| Modal, layers and notifications | `ui/MenuConfirmScreen.kt`, `ui/MenuPresentation.kt`, `ui/MenuMotion.kt` | Native layouts and pure clock-driven motion | Confirmation action remains immediate; visual layers change opacity/pose only. Screen-local toast stack maximum three; timestamp expiry. No global timer, world dependency, executor or coroutine. |
| Color picker | `ui/MenuColorPicker.kt` | Native owo color selection input; canvas gradients | Rebuilds hue-selector stripes on every paint, including unchanged color. Rasterizer caches gradients, but hue stripes currently become one command per row. |
| Item/skull art | `ui/MenuItem.kt`, `ui/MenuSkullArt.kt` | Minecraft resources, vanilla skin cache, `Scheduling.async`, ImageIO/JDK raster math | Item art loaded once per component/stack generation; ordinary item PNG is decoded synchronously on first draw. Skull resolution is async, generation guards stale results, immutable skull icons shared in an LRU capped at128. Fallback initials repeatedly build regex/string/component data while art is unavailable. No custom network connection or executor. |

## Screens and user-facing features

Screen `tick()` runs only while that screen is open. Common presentation resources are allocated lazily by screen construction; menu screens do not initialize all feature backends themselves.

| Feature | Screen source | Data/config/network dependencies | Refresh and persistent state |
| --- | --- | --- | --- |
| Daily Digest/history/RNG | `features/digest/DailyDigestScreen.kt` | `DailyDigest`, market/news/source snapshots and preferences | Tick compares source/config/history state, updates availability/countdown text, rebuilds changed results. Async requests/storage belong to DailyDigest. Screen removed cancels its local selection work. |
| Party Finder | `features/instances/dungeons/friends/DungeonFriendsScreen.kt` | Friend scanner, stats cache, party revision, replies, filters/preferences, authenticated policy relay and cosmetics revision | Tick currently copies online-friend map and constructs a composite state list, revises action tooltips/status and requests policies (backend coalesces requests). Manual/automatic open refresh remains unchanged; preferences save through existing owner. |
| Friends, loans and wealth | `features/instances/dungeons/friends/FriendsSocialScreen.kt` | Saved scanner/lending history, `FriendWealthCache`, cosmetics | Tick compares data state and replaces Refresh message/tooltip even when text is unchanged. User refresh is explicit; cached wealth throttling/network belongs to cache owner. |
| Dungeon tracker browser | `features/instances/dungeons/tracker/DungeonScreen.kt` | Recorded acquisitions/runs/profit filters, dungeon preferences | Tick detects repository/filter generation, debounces120ms and schedules existing off-thread filtering; generation/closed guards discard stale results. No additional render-time requests. |
| Auction browser | `features/general/AuctionHouseScreen.kt` | `AuctionAPI` index/browser session, search/sort/preferences | Tick compares immutable index version, debounces150ms, schedules filtering off-thread and checks expiry. Open/removed starts/stops browser session; unchanged periodic expiration filtering avoids animated rebuilds. |
| Cosmetics settings/linking | `features/social/CosmeticsScreen.kt` | Authenticated `CosmeticsClient`, `CosmeticsConfig` | First open refreshes own state/requested link; tick compares revision/status to redraw. Dirty local config saves on removal. No unrelated friend/API querying. |
| Configuration | `config/SettingsScreen.kt` | Resourceful Config entries, existing feature settings, theme/quality/motion settings | User actions/search/categories update retained body/drawer; native text, keybind and slider semantics. Dirty settings persist asynchronously on removal under existing save lock. |
| Theme editor/import/export/widget overrides | `hud/ThemeEditorScreen.kt`, `hud/ThemeData.kt`, `hud/HudTheme.kt` | Strict theme JSON, presets, global/widget overrides, widgets | User-driven changes; live preview remains retained. Global config or widget persistence on removal. No polling/background thread owned by editor. |
| HUD editor | `hud/widget/WidgetEditorScreen.kt` | Shared custom control canvas plus original live native widget renderer | Per-frame widget preview and measured bounds; native GUI coordinate space, stored positions/anchors/scales unchanged. Layout snapshot at init, native UI disposal and texture close on removal; saves widget state. |
| License/loader activation | `loader/AuthorizationScreen.kt` | Loader API/client and pure motion primitives; protected bootstrap boundary | Standalone native bootstrap UI, tick updates loader status; clears activation input on removal. It must not depend on protected menu/theme/canvas classes. Security/state/network optimization belongs to loader owner. |

## HUD feature inventory

`hud/HudRenderer.kt` registers one Fabric HUD callback before chat, screen-local chat pointer/tooltip listeners, and disconnect/stopping cleanup. It returns early for disabled module, hidden GUI, missing player, or the HUD editor. Chat alone enables HUD pointer interaction. `hud/widget/Widget.kt` retains adapters/components, remeasures replaced contents, styles on theme/override revision, and converts pointer positions through the stored widget scale/anchor. Builders determine feature visibility; `WidgetManager.widgets` currently cleans weak references and allocates a fresh list each access.

| Retained feature | Source | Render data / events | Config, state, resources and cleanup |
| --- | --- | --- | --- |
| Client information | `features/general/overlays/ClientInfoOverlay.kt` | Builder formats FPS, ping/average ping, TPS and integer position every visible frame | `GeneralConfig.clientInfo`, existing ServerUtils data; one lazy retained column. No request/thread owned by overlay. |
| Ability cooldown | `features/general/overlays/AbilityCooldownOverlay.kt` | Right-click subscription records item ID/start/cooldown/duration; builder formats active-item countdown | `GeneralConfig.displayAbilityCooldown`; cooldown map and retained label. Right-click parsing currently runs regardless of the two display toggles. |
| Ability ready notifications | Same source, `ability_reset_display` widget | Builder scans cooldown map and allocates names/lines/opacities every frame during5s expiry; shared motion envelope controls fades | `GeneralConfig.displayAbilityNotification`; retained centered rows, original editor behavior. Item-name resolution is repeated for qualifying entries. Cooldown map has no explicit expiry/disconnect clear in this source. |
| Skill XP | `features/general/overlays/SkillXpOverlay.kt` | Action-bar changes update display/skill/time; render subscription suppresses original skill widget when enabled. Builder checks5s TTL, updates item only on change and label only on change | `GeneralConfig.displayXp`; lazy retained item/label/row. No overlay-owned coroutine or requests. |
| Crystal Hollows information | `features/mining/MiningFeatures.kt` | Island/chat subscriptions; builder formats day, player count, lobby/session timers and lock/close countdown every visible frame | `MiningConfig.crystalHollowsInfo`, confirmed CH location. State: lobby start and accumulated session time; lazy panel. Warp delay/party notification are separate existing mining actions and must be retained. |
| Dungeon tracker | `features/instances/dungeons/tracker/DungeonTrackerWidget.kt` | Builder reads floor and accumulated profit statistics; reformats all summary/rate lines every visible frame even when statistics are unchanged | `DungeonTrackerConfig.dungeonTrackerWidget`, dungeon-area/start/completion gating. Lazy live/example panels; fixed editor example remains isolated from recorded totals. |
| Dungeon completion rank/details | `features/instances/dungeons/DungeonWinOverlay.kt` | Dedicated Fabric HUD callback; dungeon score chat starts sampled rank/details motions | `DungeonsConfig.dungeonWinScreen`, hide-GUI gating. Bundled rank textures; current rank/stats/motions. Island-change clears presentation. No timer/worker or render-time network request. |
| Dungeon chest prices/safe reroll | `features/instances/dungeons/DungeonChestOverlay.kt` | Container init calculates slot prices/text once; slot-render callback draws cached text and warning fade; reroll event can cancel | Dungeon profit/safety/value-threshold configs and shared price caches. Slot->cached overlay map, timestamp; no overlay-owned thread/request. Preserve reroll authority/cancellation and unknown-price semantics. |

Widget positions/scales/anchors/themes persist in bounded `widgets.json` through `AtomicJsonFile` and coalesced `StateWriter` (500ms). Widget manager owns one shutdown flush hook; save/read work uses the existing scheduler. Weak references avoid making the manager the owner of feature instances. Native widget adapters dispose on disconnect/client stopping; menu timelines have no background tasks.

Listener lifecycle was checked against the installed Fabric `fabric-screen-api-v1-5.0.1+d871b99e47-sources.jar`: `ScreenMixin.beforeInit` replaces all screen-local remove, render, tick, keyboard and mouse events at both `init(II)` and `resize`; `AFTER_INIT` then runs. HUD installs six callbacks in the current ChatScreen event objects and DailyDigest installs one removal callback per current screen. Reusing or resizing a screen replaces those objects, so these registrations do not accumulate. Global registrations occur once through module initialization. No artificial private Fabric/SBAPI listener count is reported; repeated live-screen open/resize leak measurement still requires Minecraft.

## Dependencies and resources

- Required UI runtime: Minecraft/Fabric native rendering/input, owo retained controls/adapter/focus, Resourceful Config/Kotlin config settings, Kotlin stdlib, existing SkyBlock API scheduling/events. Canvas rendering uses JDK `java.desktop`; no new rendering dependency is proposed.
- Lato regular font:656,568 source bytes, menu JSON219 bytes, OFL license4,406 bytes. Font metrics and glyph pixels are intentional and must remain exact.
- Six rank textures are small (175–401 bytes each). Item textures/skins remain vanilla-owned; copied art is bounded and never closes shared vanilla images.
- Larger unrelated bundled art (`espe.png`1,085,349 bytes, `espe.jpg`260,670, `marina.png`408,838) requires whole-repository caller/resource-name review by the packaging owner before deletion. It is not evidence of menu hot-path CPU work.

## Measured baseline and priorities

Fresh Java25 Gradle `check build` passed before edits. Existing quality fixture uses480x280 logical pixels,24 warm-up frames and60 measured frames per case:

| Quality | Idle allocation | Idle wall time | Moving allocation | Moving wall time |
| --- | ---: | ---: | ---: | ---: |
| Extremely Light | 8,663 B/frame | 0.026ms | 39,864 B/frame | 0.470ms |
| Light | 9,480 B/frame | 0.027ms | 68,760 B/frame | 0.549ms |
| Medium | 11,096 B/frame | 0.015ms | 72,823 B/frame | 0.652ms |
| High | 11,472 B/frame | 0.011ms | 73,418 B/frame | 1.067ms |

All idle cases have zero dirty pixels. Saved evidence: `build/menu-motion-check/performance.txt`. CPU time from ThreadMXBean is too coarse over60 frames for small static cases; report wall/allocation rather than deriving FPS.

Existing standalone60-card benchmark, current compiled source,960x540 logical pixels at2x,50 warm-ups and121 samples, separate Java25 JVM with JFR enabled: warm median measurement0.102ms, static extraction0.070ms, changing raster5.914ms, changing raster plus full direct pixel conversion/copy6.753ms. This older fixture intentionally copies the whole image; production already limits uploads to damage. It excludes actual owo traversal, GPU upload and game scheduling. Evidence directory: `build/performance-opt-before/`; JFR recording:`canvas.jfr`.

JFR sampled allocations identify glyph lookup keys (~71.7MB sampled weight), boxed doubles (~16.1MB), paint commands (~15.4MB), and Java2D transform/array/graphics internals as real hotspots in that standalone run. Sampled weights are statistical allocation estimates across the complete benchmark including warm-up/parity/copy, not exact retained memory or per-frame allocation. The largest underlying Java2D array/transform work comes from per-glyph raster calls; batching it is only acceptable with exact Unicode/fallback/style/subpixel pixel parity.

Proposed implementation order:

1. Replace boxed per-command numeric/color lists with fixed primitive fields and remove lookup-key allocation on glyph-cache hits. Keep immutable retained comparisons, cache limits, damage geometry and pixels.
2. Reuse equal transform/scissor state and bounded styled-run conversions; preserve mutable component/language/style invalidation and exact focus/IME geometry. Cache stable fallback initials and hue stripes only when their actual inputs change.
3. Replace repeated widget-list materialization with safe iteration, and cache HUD display strings against their actual input fields/countdown transitions. Keep data freshness and exact formatted text; do not throttle animations.
4. Measure each change with existing quality/large-menu fixtures, JFR and exact pixel/dirty-region/Unicode/motion/input checks. Retain only demonstrated improvements; add opt-in GUI/HUD profiler scopes and cold cache-size diagnostics through the shared developer profiler.

## Implemented renderer changes and reproduction

Paint geometry/colors now use primitive fields instead of boxed lists. Glyph-cache hits use a reusable lookup probe while stored keys remain separate and cache limits remain unchanged. Unchanged commands reuse the previous immutable record at the same index; changed geometry, text styles, image identity, transform, clip, opacity or group creates a new record. Graphics extraction reuses equal transform/clip objects. Buttons and labels retain measured widths and styled runs until wrapped text, font size or color changes. Entirely offscreen text lines are culled using conservative regular/fallback font bounds; the original per-codepoint reference checks also cover narrow clips at transformed edges.

The normal HUD render/pointer path traverses existing weak references directly. The editor/save APIs retain their list snapshots. Dead references are removed, widgets registered during a render join the following frame, and pointer selection still prefers the last overlapping widget. GUI/HUD scopes use the opt-in developer profiler's disabled inline path; normal play adds no profiling clock read or scope object. `MenuCanvas.cacheSizes()` and `MenuScreen.cacheSizes()` allocate diagnostic maps only when requested, exposing actual commands/cache entries/raster byte counts and the current page snapshot count.

The large fixture is retained in `src/test/java/me/mycellium/skymyce/hud/MenuCanvasBenchmark.java`. Run with Java25:

```powershell
.\gradlew.bat --offline hudCheck menuMotionCheck menuCanvasBenchmark
```

The optional benchmark produces three PNGs (large menu, resize and styled/Unicode/transformed text) under `build/menu-canvas-benchmark/`. It performs50 warm-ups and121 samples. The separate full-image conversion/copy measurement remains a historical comparison and does not represent the production damage-limited texture upload. Compare unchanged fixture images from the baseline and optimized revision byte-for-byte or by decoded pixels; bundled font, style advances and width checksum remain unchanged. Run benchmarks without competing Gradle/game workloads and compare the same JDK, font environment and JFR setting. Cold output stays under `build/`; source and measured results below provide reproducible evidence.

Final Java25 focused checks passed with all four modes. Same quality fixture, bytes per frame:

| Quality | Idle before → after | Moving before → after | Allocation reduction idle / moving | Latest idle / moving wall time |
| --- | ---: | ---: | ---: | ---: |
| Extremely Light | 8,663 → 1,687 B | 39,864 → 26,022 B | 80.5% / 34.7% | 0.018 / 0.476ms |
| Light | 9,480 → 1,709 B | 68,760 → 44,071 B | 82.0% / 35.9% | 0.049 / 0.433ms |
| Medium | 11,096 → 1,679 B | 72,823 → 46,001 B | 84.9% / 36.8% | 0.010 / 0.800ms |
| High | 11,472 → 1,800 B | 73,418 → 44,903 B | 84.3% / 38.8% | 0.031 / 1.226ms |

Idle dirty pixels remain zero and moving dirty areas are unchanged (19,246 / 29,596 / 41,268 / 71,043 physical pixels per frame by quality). Allocation reductions reproduce across focused runs. Short wall-time results vary with JIT/desktop load, so these rows establish allocation and damage behavior without claiming a quality-mode CPU/FPS improvement.

Final standalone run after all competing Gradle work stopped, same JFR-enabled fixture:

| Warm median | Before | After |
| --- | ---: | ---: |
| Text measurement | 0.102ms | 0.089ms |
| Unchanged extraction | 0.070ms | 0.049ms |
| Changing raster | 5.914ms | 5.604ms |
| Changing raster + historical full-image conversion/copy | 6.753ms | 7.141ms |

The full-image diagnostic did not improve consistently; production's existing damage-limited upload is preserved. The final three PNGs are byte-identical to baseline, and width checksum is exactly26896.42608642578. Evidence: `build/performance-opt-final-isolated/`; contested concurrent-build timing was excluded. JFR recorded221 allocation and55 execution samples. Sampled weight for `MenuCanvas.Paint` fell from approximately15.4MB to1.6MB; glyph lookup keys and boxed doubles no longer appeared in these final samples (this does not prove zero cold allocation). Remaining largest sampled weights are Java2D `AffineTransform`70.0MB, `double[]`69.2MB, `int[]`57.1MB and `SunGraphics2D`28.5MB. These are whole-run statistical samples, including warm-up, screenshots and the artificial full-copy phase, not per-frame or retained heap sizes.

`hudCheck` includes weak-reference ownership/new-registration/reverse-pointer checks and exact uncached per-codepoint reference comparison across fallback, combining marks, styles, scales and narrow/transformed clip edges. Existing damage/full-frame parity, bounded cache eviction, configuration/theme compatibility, focus and30/60/120/240Hz motion checks passed. No feature, quality effect, animation timeline or native input path was removed to achieve these results.

Live Hypixel GPU/FPS, idle/background CPU, low-end hardware and30min/1h/2h/4h play-session stability remain unmeasured in this headless environment. Do not substitute synthetic timing for those scenarios.
