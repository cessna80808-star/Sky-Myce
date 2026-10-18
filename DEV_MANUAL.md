# Sky-Myce Developer Manual

This guide is for people building, testing, or changing Sky-Myce. Keep source and Gradle configuration as the authority for versions and behavior; update this guide when durable development conventions change.

## Project overview

- `src/main/kotlin/` — client mod implementation, primarily Kotlin
- `src/main/java/` — Java integrations, including narrow Minecraft mixins
- `src/test/kotlin/` — executable checks; the project does not use a test framework
- `relay/` — separate TypeScript Cloudflare Worker and Durable Object service
- `gradle.properties` — Minecraft, Java ecosystem, dependency, and mod versions
- `build.gradle.kts` — dependencies, packaging, and verification tasks

The standalone client mod targets Minecraft **26.1.2** and Java **25**. Local gameplay features do not require the relay or a custom Loader. The relay is a separate service and should only be changed when a feature actually needs server-side behavior.

## Build and verify

Use a Java 25 JDK. From the repository root:

```bash
./gradlew build
./gradlew check
```

`check` compiles the project and runs executable checks for feature logic, menus, HUD, routing, camera behavior, community packaging, and loader/update support. For a focused check, run its Gradle task, such as:

```bash
./gradlew dungeonChestProfitCheck
```

For relay changes, use the locked dependencies and run from `relay/`:

```bash
npm ci
npm run check
npm test
```

`npm test` runs a Wrangler dry-run build and local tests; it does not establish production behavior. Do not deploy the Worker as part of ordinary local testing.

## Client source map

| Location | Responsibility |
| --- | --- |
| `src/main/kotlin/me/mycellium/skymyce/SkyMyce.kt` | Fabric client entry point and module initialization |
| `SkyMyceModule.kt`, `ModuleManager.kt` | Feature module lifecycle |
| `api/` | SkyBlock API integration and shared events |
| `config/` | Config categories, settings, and persistence |
| `features/general/` | General features, auction tools, and widgets |
| `features/instances/dungeons/` | Dungeon features, routing, camera, friends, and tracker |
| `features/digest/` | Daily Digest and RNG tracking |
| `community/` | Optional community connection, sharing, rankings, and signed updates |
| `hud/` | HUD widgets, themes, and editors |
| `utils/` | Shared item, text, Minecraft, and persistence helpers |
| `src/main/java/me/mycellium/mixin/` | Focused Minecraft UI/input integrations |

In the relay, `relay/src/` contains Worker routes and feature handlers, while `relay/test/` contains local tests. Read `relay/AGENTS.md` before changing relay bindings, storage, authentication, or deployment-related behavior.

## Adding or changing a client feature

1. Search for an existing feature or helper before creating a parallel implementation.
2. Keep feature logic isolated. Features are generally singleton `SkyMyceModule` objects; register new modules through `SkyMyce.initializeModules` and initialize them using the existing lifecycle.
3. Prefer SkyBlockAPI/Fabric events and `@Subscription` over polling. To prevent an action, use an existing cancellable event at the earliest pre-action point—do not duplicate, undo, or replay player input.
4. Put settings in the appropriate `CategoryKt` config tree. Use `TranslatableValue.literal` for setting labels/descriptions and preserve existing config keys and defaults unless a migration is deliberate.
5. Extract calculations and parsing into pure logic where practical, then add a lightweight executable check under `src/test/kotlin/` and register it in `build.gradle.kts`.
6. Keep UI consistent with the current menu and HUD systems. Reuse existing widgets, themes, navigation, and input helpers instead of introducing a second rendering or settings path.

Prefer existing APIs and shared helpers over new mixins or duplicated state. Keep client interactions local unless the feature explicitly requires community support. Never bypass server authority or simulate player input.

## Data, errors, and external services

- Treat unavailable or malformed external/game data as unknown, not as a valid zero or success.
- Label estimates as estimates; fail safely and log actionable diagnostics without flooding logs.
- Store user data with established bounded and atomic helpers such as `AtomicJsonFile`. Do not commit runtime data, credentials, signing keys, or relay secrets.
- For relay authentication, signatures, account/session binding, rate limits, and user data, preserve fail-closed behavior and inspect existing security boundaries before editing.
- Test relay behavior locally; do not use local mocks as proof of production delivery.

## Release hygiene

Keep the version in `gradle.properties` consistent with player-facing release documentation and artifact names. A normal mod build produces the standalone Fabric mod; verify its metadata and packaging checks before distribution. Do not commit generated output from `build/`, Gradle caches, or runtime data from `run/`.

The relay has separate operator and deployment procedures in `relay/README.md` and `relay/AGENTS.md`. Do not include relay deployment in a code change unless the task calls for it.
