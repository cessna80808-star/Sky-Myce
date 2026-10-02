# GUI motion and graphics

The client keeps owo's retained layout, focus, keyboard input, narration and scrolling. `MenuScreen` supplies the shared shell; `MenuButton`, `MenuLabel`, `MenuTextBox`, `MenuSlider`, `MenuDropdown`, `MenuCollapsible` and `menuScroll` supply the controls. Menus paint through `MenuGraphics` into the existing `MenuCanvas`, then `MenuTexture` uploads changed pixels. No new runtime dependency is required.

The default menu and HUD palette uses dark purple surfaces and lavender accents. Existing saved color overrides and other presets still apply. Titles, body text, captions and values use the existing bundled proportional font and Unicode fallback. Widget placement remains in Minecraft GUI coordinates.

## Motion

`MenuMotion.kt` contains pure, monotonic-clock primitives. `MotionValue` interpolates elapsed nanoseconds, rather than counting frames. Controls request their logical target immediately; sampling only changes presentation. Repeated requests for the same target preserve the deadline. Reversal starts from the current displayed value, cancellation preserves that value, and zero-duration/Off requests snap to the destination.

Use `QUICK` for response and page content, `SMOOTH` for switches and fades, and `SETTLE` for the shell's restrained spring. Sliders follow the pointer immediately; their emphasis animates independently. Typical durations are 110–180 ms for controls, 220–245 ms for pages and entry, and 90–145 ms for exit. The shell, navigation and content enter with a short stagger. Delayed timelines compose the entrance without timers or coroutines.

```kotlin
private val emphasis = MotionValue(0.0)

// Call when painting or when the state changes. Identical targets do not restart.
emphasis.animateTo(if (focused) 1.0 else 0.0, 120,
    MotionEasing.QUICK, ThemeConfig.animationMode)
val amount = emphasis.sample().coerceIn(0.0, 1.0)
graphics.layer(alpha = amount) { /* paint */ }
```

`MenuGraphics.layer` composes opacity and transforms and restores them in `finally`. `MotionPose` supplies the inverse transform for pointer input. The screen uses the last painted shell/content poses for clicking, dragging, scrolling and hovering; the empty strip left by entering content rejects hits. Popups settle their anchor's pose before mounting. Closing screens reject clicks, keyboard input and text composition.

## Graphics and accessibility settings

Open **Settings → Theme & Appearance**. The graphics and animation selectors are saved in the existing Theme category.

| Graphics | Raster scale limit | Shadows | Glow | Ambient corner |
| --- | --- | --- | --- | --- |
| Extremely Light | 1× | None | None | None |
| Light | 1.25× | One layer | None | None |
| Medium (default) | 1.5× | Two layers | Yes | Three subtle points, at most 10 updates/s |
| High | 2× | Three layers | Yes | Five subtle points, at most 20 updates/s |

Raster dimensions remain capped at 2560×1440. Light and higher add cached gradients. Shadows use bounded silhouettes; effects do not allocate blur surfaces or introduce a post-processing pass. Ambient animation changes a small decorative corner, preserving the rest of the dirty-region cache.

| Animations | Behavior |
| --- | --- |
| Full (default) | Layered entry, short directional page motion, shell settle, control feedback and ambient detail |
| Reduced | Fades and immediate interaction feedback; timelines capped at 90 ms, with no large page/shell movement, spring overshoot or ambient motion |
| Off | Logical state and final presentation update immediately; decorative transitions and ambient motion stop |

Graphics quality and motion are independent. Essential feedback remains available in every graphics mode. Changing either setting during a transition takes effect in the next frame.

## Adding a screen

Extend `MenuScreen` and implement `buildContent(FlowLayout)`. Use the shared controls and `menuScroll` for scrollable content. The shell owns entry/exit, tooltips, viewport conversion and rendering.

```kotlin
class ExampleScreen(private val parent: Screen?) : MenuScreen(Component.literal("Example")) {
    override fun buildContent(root: FlowLayout) {
        root.child(MenuLabel(Component.literal("Example")))
        root.child(MenuButton(Component.literal("Done")) { onClose() })
    }
    override fun onClose() = closeMenuTo(parent)
}
```

Use `navigateMenu(destination)` when opening another menu. It switches the live screen immediately and captures outgoing content for the visual transition. For a tab change, call `rebuildMenu(direction)`; it retains the shell and navigation. When replacing a results list or details pane, call `contentChanged(direction)` **before** modifying its children. Construction and unchanged periodic labels do not start transitions. Existing async generation/revision checks still decide whether results may mutate the screen.

User actions can call `showMenuToast(message, MenuToastKind.SUCCESS/INFO/WARNING/ERROR)`. The stack retains at most three messages, deduplicates adjacent identical messages, and animates entry, expiry/progress, stack position and exit. Keep existing chat/status feedback where it conveys feature state.

## Ownership and cleanup

Outgoing content is a cropped inert raster, captured once per interaction. Interrupted transitions flatten the currently visible composition into one raster, so rapid A→B→C navigation does not accumulate a chain of old screens or paint commands. Neither snapshots nor timelines retain adapters, textures, world references or callbacks. Removal clears snapshots, tooltips and toasts and closes the canvas and texture.

The canvas reuses its image and gradient rasters; pose, clip and opacity changes reuse gradients. Unchanged frames perform no rasterization or texture upload. Glyph/font/gradient caches are bounded. There is no off-screen animation manager, scheduled task, listener or coroutine to keep running after a screen closes.

The HUD editor fades its controls, help and outlines while preserving saved HUD positions, scales and anchors. Live widget previews use their existing native renderer. Ability-ready notifications use a shared motion envelope for entry and exit within their existing five-second lifetime; widget previews stay readable. Dungeon-win presentation shares the motion settings and cancels immediately on an island change. The standalone loader packages only the pure motion classes: its authorization screen uses a brief Reduced fade and the same default color family without importing protected mod UI/configuration. It clears activation input immediately on closing.

## Verification

Use Java 25:

```powershell
.\gradlew.bat menuMotionCheck hudCheck
.\gradlew.bat check build
```

`menuMotionCheck` exercises fake-clock timelines at 30/60/120/240 FPS, repeated targets, rapid reversals, stagger, cancellation, accessibility changes, coordinate inverses, opacity, clipping, flattened snapshots, cache/idle behavior and cleanup. It writes quality/transition PNG fixtures and prints warmed raster CPU/wall time, allocated bytes and dirty-pixel area in `build/menu-motion-check`.

These fixtures use the real Java2D rasterizer but are not in-game screenshots or GPU/FPS measurements. `hudCheck` also covers existing retained layout, GUI scales, narrow/wide viewports, proportional/Unicode text, damage parity, themes and HUD behavior. Native scroll/focus integration needs Fabric's launcher and cannot initialize in the plain JavaExec checks. A live performance and interaction pass needs a supported Minecraft 26.1.2 instance with the signed protected release; the raw Loom class-directory client does not satisfy release verification.

For live acceptance, compare all four graphics modes and Full/Reduced/Off at 30/60/120/240 FPS. Exercise rapid page/tab changes, popup replacement, close/reopen, resize and GUI-scale changes, dragging and scrollbar use, Unicode/long text, delayed/missing results, and disconnect/reconnect or island changes during a transition. Inspect F3 frame time/GPU behavior and Java allocation/resource profiles alongside visual and input alignment. Do not report headless raster timings as Minecraft performance.
