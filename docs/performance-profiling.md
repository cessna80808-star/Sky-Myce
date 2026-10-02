# Developer performance profiling

The profiler is available in a Fabric development environment, or with the explicit JVM option `-Dskymyce.developerProfiling=true`. Existing developer messages, debug tooltips and logging remain available. The normal loader authorization gate also applies to these commands.

1. `/sm profile start` starts a bounded native Java Flight Recorder profile and fixed feature counters.
2. `/sm profile report` writes sorted CPU, wall-time, allocation and serialized network-byte counters to the client log, with current cache/queue/job/socket/module/FPS gauges and background platform-thread names/states.
3. `/sm profile stop` writes a text report and adjacent `.jfr` file to `config/skymyce/performance/`, then closes the recording. Use JDK `jfr view`/`jfr print`, or a JFR viewer, to inspect execution/allocation stacks and native socket/thread events.

JFR setup and file writes use the existing background scheduler. A busy guard prevents overlapping command operations. On client shutdown the recording closes and late queued starts cannot revive it. Explicit counters store sizes/counts rather than packet contents. Native events containing launch arguments, system properties, environment variables, process command lines or exception messages are disabled to avoid recording Minecraft tokens or relay credentials.

Each active recording keeps at most 64 MiB and fifteen minutes of native events. Explicit counters span the complete recording session and cover command/friend/cosmetic ticks, trade capture, HUD/GUI extraction, chat, auction HTTP, friend API work and relay parsing/sends. Counters are inclusive: nested scopes must not be summed as though they were disjoint. CPU/allocation measurements belong to the calling platform thread, not later asynchronous continuations. Native allocation/CPU sampling provides stacks for other features and the loader independently of the protected-module counters.

When stopped, an inline scope checks the inactive session and runs the original code directly: no clocks, per-event statistics or scope objects. UTF-8 byte-count copies occur only while profiling. Cache and task snapshots are collected on the client thread; sorting, JFR lifecycle and report serialization run in the background.

The report distinguishes configured module subscription methods from global listener counts. Fabric/SkyBlock API do not expose a supported complete global listener counter; the profiler does not invent one. The installed Fabric screen API replaces its screen-local event objects on every initialization/resize, so re-registering chat callbacks after initialization preserves six callbacks on the current screen without accumulating older registrations. Similarly, shared coroutine dispatchers do not expose a reliable global coroutine count. Feature-owned pending jobs/requests/wakes and JVM platform-thread states are reported instead. JFR stack samples are the route to additional library work.

## Reproducing the local evidence

Use Java 25:

```powershell
.\gradlew.bat --offline featureWorkCheck dungeonFriendsCheck hudCheck menuMotionCheck runtimeProfilerCheck sharedRuntimeCheck
.\gradlew.bat --offline check build
.\gradlew.bat --offline menuCanvasBenchmark
.\gradlew.bat --offline dependencies --configuration runtimeClasspath --console=plain
python scripts/performance-artifacts.py build/libs/skymyce-2.4.12.jar build/libs/Sky-Myce-Loader.jar
```

From `relay/`:

```powershell
npm run check
npm test
node test/drm-cache.mjs --benchmark
```

The GUI checks test pixels/dirty regions, Unicode/style/subpixel text, cached gradients, transitions, all graphics/motion modes, clock/input safety, cache bounds, screen-snapshot ownership and lifecycle cleanup. They print CPU/allocation/wall-time metrics for the existing headless quality fixture. `featureWorkCheck` compares reconstructed pre-change identity/activity work with the actual current pure types in the same JVM. Those are synthetic measurements, not game FPS. `performance-artifacts.py` reports each embedded mod's actual ID/version, declared dependencies and compressed contribution while checking nested declarations and duplicate zip entries. Nested byte counts overlap parents and must not be summed into the artifact size.

## Live verification still required

Use a signed release and an authenticated Hypixel client with the same hardware, JVM settings, resource packs and feature configuration for before/after comparisons. Capture Hub idle/active, Catacombs, relevant mining islands, auction browsing, friends/relay, all GUI quality modes, outside-Hypixel idle, and repeated world/screen/authorization transitions. Use actual timestamps for 30-minute, one-hour, two-hour and four-hour sessions; record FPS/frame-time, process CPU, RAM after equivalent GC behavior, cache/job/socket/thread counts, network rates and reconnect behavior. Check feature disable/re-enable, deadlines, native actions and shutdown. A fifteen-minute rolling JFR is useful around individual spikes; text counters span the longer session.

The local environment has not supplied that authenticated/signed gameplay harness or a low-end comparison system. The baseline and final report mark these measurements unavailable, and no loader verification or online authorization requirement is bypassed to obtain them.
