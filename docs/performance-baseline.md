# Optimization baseline

Captured before implementation on 2026-10-02, from commit `a58b34d00541d0670e43ee7faa5422437e8967e3`, Java 25.0.1, Minecraft 26.1.2, Kotlin 2.4.0. The unrelated existing README, cosmetic registration script and untracked files were excluded from this work.

`gradlew --offline check build dependencies --configuration runtimeClasspath` passed before changes. This confirms the existing pure feature, auction, tracker, digest, cosmetic, HUD, GUI and loader security checks; it is not a live Hypixel session.

## Measured headless GUI baseline

Existing `MenuMotionCheck` fixture: 480 × 280 logical pixels, 24 warm-up frames, 60 measured frames for each idle/moving case. Wall time includes command capture and Java2D rasterization; ThreadMXBean reports allocations on that thread. Native CPU timer resolution is coarse, so zero does not mean free. No Minecraft GPU upload, FPS or live server is measured.

| Mode | Idle wall ms/frame | Idle B/frame | Moving wall ms/frame | Moving B/frame | Moving dirty pixels/frame |
| --- | ---: | ---: | ---: | ---: | ---: |
| Extremely Light | 0.026 | 8,663 | 0.470 | 39,864 | 19,246 |
| Light | 0.027 | 9,480 | 0.549 | 68,760 | 29,596 |
| Medium | 0.015 | 11,096 | 0.652 | 72,823 | 41,268 |
| High | 0.011 | 11,472 | 1.067 | 73,418 | 71,043 |

All idle cases had zero dirty pixels. Thus allocations in unchanged command capture are a directly measured optimization target. Timing comparisons require repeated runs on the same machine; a single warm-up/run is noisy.

## Packaged size baseline

| Artifact | Bytes |
| --- | ---: |
| Protected mod `skymyce-2.4.12.jar` | 20,172,546 |
| Bootstrap `Sky-Myce-Loader.jar` | 2,537,443 |

Top-level embedded jars, before optimization:

| Dependency | Uncompressed jar bytes | Compressed bytes in mod |
| --- | ---: | ---: |
| SkyBlock Profile Viewer 1.8.9 | 11,112,115 | 10,954,206 |
| SkyBlock API 4.2.19 | 5,192,370 | 4,939,205 |
| Resourceful Config 4.0.1 | 439,690 | 372,444 |
| Resourceful Config Kotlin 4.0.0-beta.1 | 153,429 | 132,576 |
| Hypixel Mod API core 1.0.2 | 55,989 | 47,039 |
| owo sentinel 0.13.1+26.1 | 16,887 | 15,595 |

The viewer also embeds SkyBlock API 4.2.18 (4,121,479 bytes), the same two Resourceful Config jars (593,119 bytes), repo-lib 2.0.2, meowdding-lib, Olympus, Placeholder API and Resourceful Lib. SkyBlock API and both config copies are shadowed by the newer/identical top-level mods that Fabric already selects. Repo-lib 2.0.2 is also shadowed by the same Fabric ID at 4.1.5 inside SkyBlock API 4.2.19; the viewer's dependency metadata has no upper version bound. The viewer itself is required by the reflected profile/wealth providers and its Fabric entrypoint and mixins; removing it would remove functionality.

`assets/skymyce/marina.png` (408,838 bytes) and `espe.jpg` (260,670 bytes) have no code, config, metadata, mixin or resource references. The plane uses `espe.png`, which remains required. Fonts, rank textures, icon, licenses and sounds metadata remain required.

## Source audit baseline

See the feature, GUI and networking inventories for all retained capabilities and their dependencies, events, callbacks, timers, network work, persistence and context gates. This includes debug tooltips and command/troll features, not only the major screens.

Shared lifecycle: `SkyMyce` constructs singleton modules, initializes their required Fabric hooks and registers/unregisters their SkyBlock API subscriptions on loader authorization changes. Fabric key mappings must register before GameOptions exists. `ModuleManager` uses weak references. Widgets retain their own adapters and release them on disconnect/shutdown. Config/state saves share SkyBlock API Scheduling and atomic coalescing writers rather than a thread per feature; the dependency's scheduler has up to ten daemon platform threads. Loader authorization executes independently of protected-module activation and must keep its existing expiry/revocation cadence.

An unused `MiscApi` synthetic server-tick producer has no consumers. Its suspend method also does not satisfy the installed event bus's one-parameter reflection registration, so removing it must **not** be reported as a measured reduction in active packet CPU.

## Measurements unavailable in this environment

Authenticated Hypixel Hub, dungeon, mining, auction, relay and 30-minute/1/2/4-hour sessions, low-end hardware comparisons, game startup latency, live idle/active process CPU, game RAM, FPS/GPU frame times and actual request/message rates have not been measured. The protected loader requires a valid signed release and authenticated account; this optimization does not bypass it. Headless tests and source-level counts do not replace those measurements.
