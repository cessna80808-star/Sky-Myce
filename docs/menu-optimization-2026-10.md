# Menu fixes and measured costs — 2026-10-10

The reported lower-edge card flicker came from input following the previous painted hover pose: lift/scale changed whether the stationary pointer hit, which reversed the animation and changed the hit again. Navigation now selects from logical bounds that follow only the category-to-rail layout. Hover, press and reveal remain visible. Mounted feature/settings reveal cards use logical hover ownership too, while child input still follows its visible painted control. Leaving the body refreshes hover ownership, and clipping/unrevealed controls continue to reject input.

The six Home categories and their compact icon rail remain. Category previews, distinct Search/Quick Settings rail buttons, view-specific titles/help, useful blank-search suggestions and **Open / Configure / Full settings** labels clarify navigation. Settings inherits this shared rail without changing its saved controls, all-settings search, direct-control focus or friend drafts. Selection follows the current screen category when an existing editor returns.

The existing Light/Medium/Heavy personalities retain their distinct 1.25/3.5/6-pixel lift, deadlines, depth and glow; Reduced/Off and saved Full compatibility remain. The initial category stagger waits for the shell to arrive. A saved introduction skips that stagger on subsequent launches. Heavy ambient detail advances during transitions and stays at its final pose when idle. No new runtime dependency, feature timer, background animation worker or config storage key was added.

## Measurement conditions

The baseline was run **before production UI edits**, then repeated after the fixes on the same Windows machine with Temurin Java 25.0.3+9, Minecraft 26.1.2 and the existing Gradle JavaExec fixtures. Only fixture measurement code was added for the second baseline capture. No parallel Gradle invocation ran during these measurements. Timings below are aggregate elapsed time divided by operation count, not medians or game frame rates; allocations are current-thread `ThreadMXBean` deltas. Volatile sinks retain results. JIT, desktop activity and short-run timing variation remain.

- Navigation geometry: six category rectangles at a 760×440 logical layout with a 52-pixel rail; 2,000 warm-up frames and 10,000 measured frames. The baseline called the original geometry function six times per frame; the fixed implementation updates and reads its cache through the same loop/sink. Settled cache revision and object identities are checked.
- Search: the real catalog with 145 entries, including 28 features and 105 saved settings; queries `f`, `fortune`, `party`, `animations`, `blood`, `economy friends`; 360 warm-up and 600 measured queries. Both runs used the existing matching and category ordering. Metadata/index creation is outside the warm query loop. This measures recurring typing work rather than cold opening cost.
- Renderer: the existing 480×280 logical-pixel Java2D fixture, every graphics quality, 24 warm-up and 60 measured frames per idle/moving/page-blend condition. These are the real rasterizer's fixtures, not complete native screens, GPU uploads or an authenticated game session. Timers can be coarse at these durations.

Reproduce current measurements and regressions with Java 25:

```powershell
.\gradlew.bat menuNavigationCheck menuMotionCheck menuCatalogCheck menuShortcutsCheck hudCheck --console=plain
```

## Before and after

| Warm operation | Baseline µs/op | First fixed run µs/op | Baseline B/op | First fixed run B/op |
| --- | ---: | ---: | ---: | ---: |
| Six settled category rectangles / frame | 0.397 | 0.155 | 490 | 56 |
| Catalog search / query | 547.155 | 75.214 | 1,316,888 | 6,576 |

The repeated final focused run reported navigation **0.170 µs / 56 B per frame** and search **121.845 µs / 7,735 B per query**. Both search samples reduce allocation by more than 99% in this fixture. Geometry retains the fixture's loop/sink overhead; the cache does not rebuild rectangles while progress/origin are unchanged. Search now tokenizes and sorts immutable entry metadata once instead of rebuilding strings, words and ordering for every query. It retains a bounded inventory snapshot, without keeping an unbounded query history. Quick Settings also compares its at most eight current values without allocating a comparison list every idle tick; that change has a correctness regression but no separate measured timing claim.

| Graphics quality | Baseline idle B/frame | First fixed idle B/frame | Baseline/fixed idle dirty pixels | Baseline moving ms/frame | First fixed moving ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: |
| Extremely Light | 1,687 | 1,687 | 0 / 0 | 0.386 | 0.380 |
| Light | 1,672 | 1,672 | 0 / 0 | 0.394 | 0.360 |
| Medium | 1,672 | 1,672 | 0 / 0 | 0.568 | 0.567 |
| High | 968 | 968 | 0 / 0 | 0.734 | 0.750 |

Active page-blend wall time was **1.768 / 1.909 / 3.308 / 5.313 ms** in the baseline and **1.385 / 2.182 / 3.030 / 6.207 ms** in the first fixed run, in the same quality order. The final repeated run reported **1.635 / 2.159 / 3.703 / 6.220 ms**. Renderer timings are mixed and do not establish a uniform speedup. Raster/blend buffer sizes and dirty areas were unchanged; idle renderer allocations were already small. The transition-only ambient rule is covered by state checks across 10,000 settled samples; this fixture does not render the entire Heavy native shell. No measured FPS, startup, packaged-size or process-wide heap improvement is attributed to this menu change.

## Regression and visual evidence

All five focused tasks passed after the Settings follow-up; Gradle compiled Kotlin/Java/test sources and completed in 28 seconds. The checks cover every category edge, lower-edge leave/re-entry, all motion choices and Full compatibility, four layout sizes, intermediate transforms and nonzero screen origins. Hover/press/reveal cannot change logical hit ownership or reconstruct settled geometry. Existing fake-clock 30/60/120/240 FPS tests cover interruption, reversal, close/open, saved intro, first/repeated viewport reveal and idle state. Mounted reveal-card fixtures verify inverse-painted child coordinates, adjacent hover clearing, clipping and unrevealed input rejection. These are headless layout/event-coordinate checks; full Fabric screen dispatch remains a separate runtime check.

Catalog regressions compare blank, prefix, typo, uppercase, punctuation, multiword and missing-query results/order with the pre-change matching algorithm. Every setting still has exactly one canonical owner and remains searchable. Settings checks assert use of the shared rail/layout and retain all category scopes, search overrides, actual-control-before-Pin focus and saving-wrapper coverage. Shortcut identity/order/bounds/restart/corrupt-file checks, HUD raster pixel parity and deferred texture lifetime all pass.

All nine production navigation-tile PNGs were visually inspected: `build/menu-navigation-check/navigation-{light,medium,heavy}-{home,transform,submenu}.png` (1140×660). They show readable Home category text/previews, typography fading during the morph, and the compact selected icon rail in each personality. They exercise real tile painting, not the full Search/Settings body or OS mouse focus. `menuMotionCheck` and `hudCheck` also write their existing raster/transition fixtures.

The final Java 25 `check build` passed every registered check, including `loaderCheck`. Its search fixture reported **81.091 µs / 6,576 B per query** and navigation **0.139 µs / 56 B per frame**; the same short-run timing limitations apply.

The signed production **2.4.27** release was then installed through the normal authenticated loader in the user's current Prism session. Native checks in a private world covered Home, category/settings navigation, Search (`blood`), direct controls, Quick Settings, changing Light/Medium/Heavy and restoring the original Light setting. Repeated captures at the reported Combat lower edge stayed stable in Light and confirmed Heavy. Screenshots are in the authorized visualization directory, including `menu-edge-after-1.png` through `menu-edge-after-4.png`, `minecraft-heavy-selected.png`, `menu-heavy-confirmed-1.png` through `menu-heavy-confirmed-3.png`, `minecraft-light-restored.png`, and the Search/Quick Settings captures.

Two 245-second native JFR recordings captured the previous 2.4.26 session and installed 2.4.27 session. JVM arguments, environment/properties, process starts and exception-text events were excluded and checked. The sessions had different menu interactions and elapsed world/weather state; they do not establish a controlled FPS or process-wide heap improvement. Headless checks cover more edge/profile/resize combinations than the native smoke pass. Repeated graphics changes, the full live resize/keyboard matrix, low-end hardware and sustained-session heap behavior remain unchecked. See [the final implementation report](implementation-overhaul-2026-10.md), [GUI acceptance scenarios](gui-motion.md#verification) and [profiling guide](performance-profiling.md).
