# Development guide for coding agents

This file is the repository's development reference. Prefer code and build configuration as the source of truth; verify details against the implementation when they change.

## Project at a glance

- Sky-Myce is a standalone client-side Fabric mod for Minecraft 26.1.2, written primarily in Kotlin with some Java and compiled for Java 25. Historical Loader sources remain for compatibility checks; the installed mod requires no custom Loader.
- `relay/` is a separate TypeScript Cloudflare Worker and Durable Object service.
- Gradle dependency versions and Minecraft/Fabric versions are in `gradle.properties`; dependency wiring and build/test tasks are in `build.gradle.kts`.
- Do not edit generated output, runtime data or dependency caches under `build/`, `.gradle/`, or `run/`.

## Source map

| Path | Responsibility |
| --- | --- |
| `src/main/kotlin/me/mycellium/skymyce/SkyMyce.kt` | Fabric client entry point and module initialization |
| `.../SkyMyceModule.kt`, `.../ModuleManager.kt` | Feature module lifecycle |
| `.../api/` | SkyBlock API integration and shared events |
| `.../config/` | Resourceful Config categories, persistence and settings UI |
| `.../features/general/` | General client features, auction browser and widgets |
| `.../features/instances/dungeons/` | Dungeon features, friends and tracker |
| `.../features/digest/` | Daily Digest and RNG tracking |
| `.../community/` | Optional Discord connection, sharing, leaderboards and signed Fabric updates |
| `.../hud/` | HUD widgets and theme/editor UI |
| `.../utils/` | Shared Minecraft, item, text and persistence helpers |
| `src/main/java/me/mycellium/mixin/` | Narrow Minecraft UI/input integrations |
| `src/test/kotlin/` | Lightweight executable checks; this project does not use a test framework |
| `relay/src/` | Worker routes and feature handlers |
| `relay/test/` | Relay tests |
| `relay/AGENTS.md` | Relay-specific development notes |

## Mod architecture and conventions

- Features are generally singleton `SkyMyceModule` objects. Follow the existing initialization pattern in `SkyMyce.initializeModules`; ensure a new module is registered and its `init()` runs.
- Event-driven features use SkyBlockAPI event types and `@Subscription`. Use existing cancellable events at the earliest pre-action point when preventing an interaction; do not open/undo, duplicate, or replay player actions.
- Keep feature logic isolated, and extract pure calculation/parsing logic so it can be tested without a live client. Follow nearby code's Kotlin formatting and naming.
- Config belongs in a `CategoryKt` category under `config/`; register it through the existing config tree and use `TranslatableValue.literal` for names/descriptions. Preserve existing config paths and defaults unless a migration is intended.
- For item values, reuse `utils/ItemUtils.kt` and the configured Bazaar/Auction types rather than embedding prices. Distinguish an unknown price from a valid zero value when safety decisions depend on lookup completeness.
- Prefer existing SkyBlockAPI, Fabric API, Minecraft, and shared helpers over duplicate state tracking or mixin hooks. Add a mixin only when existing event hooks cannot correctly implement the behavior.
- Keep user-visible estimates explicitly estimated; fail safely on malformed or unavailable external/game data and log actionable diagnostics without flooding logs.
- Local user data should follow established bounded/atomic persistence helpers such as `AtomicJsonFile`; never commit runtime data, credentials, signing keys or relay secrets.
- Keep interactions client-local unless the feature explicitly requires relay/server support. Do not bypass server authority, simulate player input, or send duplicate packets.

## Build and verification

Use Java 25 for Gradle. A Java 21 Gradle invocation can fail before compiling project changes because the configured Java/Kotlin targets are 25.

```bash
./gradlew build
./gradlew check
```

`check` compiles the project and runs the repository's executable check tasks (including dungeon, HUD and loader checks). For a focused pure-logic check, run its Gradle task when one exists, for example `./gradlew dungeonChestProfitCheck`.

The lightweight checks are ordinary top-level Kotlin functions with a `main()` entry point, registered as `JavaExec` tasks in `build.gradle.kts`. Add coverage to the most relevant existing check or define a corresponding task and wire it into `check`.

For relay changes, run from `relay/`:

```bash
npm test
npm run check
```

`npm test` performs a Wrangler dry-run build and local relay tests; `npm run check` runs Wrangler type generation and TypeScript type checking. Consult `relay/AGENTS.md` and nearby tests for relay details. Do not run deploy commands unless the user explicitly requests deployment.

Run the narrowest relevant checks while iterating, then `./gradlew check` for mod-wide changes. Report any verification that could not be run and why.

For GUI changes, `./gradlew runGuiVisualCheck` launches an isolated development client, captures real menu/theme/control screens at multiple sizes and GUI scales under `build/gui-visual-check/screenshots`, and exits. It uses test-only Fabric code and no multiplayer connection or existing player profile. This supplements the executable checks; gameplay HUD and native player previews still need in-world verification.

The user has authorized deployment of every completed mod release build to the production Cloudflare backend. Include deployment in future build tasks without asking again: use a new release version, upload the private artifact before its signed protocol-4 Fabric manifest (`sign-community`), activate `releases/community-current.json` on `--env backend`, and verify the live signature and artifact hash. Retain previous builds and historical Loader release pointers for later cleanup.

The sole production Discord Gateway lives in the separate `D:\Code\Cessna fork\relay` checkout. Update that existing service for Gateway changes; do not start a competing Node bot. Keep RNG posts in a dedicated channel, Global bridged to Discord and Party restricted to game clients. Local features and personal history remain usable without a community session.

## Working safely

- Inspect the affected code and its call sites before changing behavior; preserve unrelated worktree changes.
- Make small, complete changes and update this guide only when durable development conventions change.
- Do not treat repository visibility as authorization to publish code, credentials or private user data elsewhere.
- For relay authentication, Discord interactions, signatures, session binding, rate limits or user data, inspect existing security boundaries and preserve fail-closed behavior. Relay-specific notes remain in `relay/AGENTS.md`.
