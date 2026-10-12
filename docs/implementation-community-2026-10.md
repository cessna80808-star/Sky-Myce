# Sky-Myce 2.5.0 implementation and verification

This change implements the adopted normal-Fabric/community request and the
Cosmetics, tracker, journal/menu, HUD and Discord bridge requests supplied on
12 October 2026. Minecraft 26.1.2 and Java 25 are the supported build target;
26.3 compatibility has not been claimed or tested.

## Implemented behavior

- **Normal Fabric startup:** no custom Loader, license, activation code,
  staff approval, HWID binding, PC limit or Minecraft-account-count gate.
  Local modules remain available when Discord or the relay is disconnected.
- **Optional community identity:** browser Discord OAuth with a one-time
  existing-bot fallback, fresh Mojang account proof, private persisted rotating
  sessions, explicit disconnect and deliberate Discord/Minecraft blacklists.
  Device metadata is optional, private and informational.
- **Sharing:** category controls and global opt-out preserve existing choices.
  Revision/owner checks, removal barriers and asynchronous-response guards
  prevent old responses from restoring removed shared records. Unlinking
  preserves restrictive consent rather than resetting it to default sharing.
- **Cosmetics Studio:** actual player skin/model, rotation, inert styled names,
  independent nametag and X/Y/Z body scale, explicit no-cape selection, packaged capes and bounded uploaded
  PNGs. Owned ready uploads preview through an authenticated private route before
  public Save; its bounded cache is separate from active public capes and clears
  on screen/account changes. Shared saves use authoritative revisions; private cape storage is
  separate from signed-release storage. Older cosmetic protocol versions retain
  their original response shape.
- **Tracker and RNG:** cold inventories establish a baseline; newly confirmed
  receipts and supported announcements share a permanent acquisition identity.
  Digest being disabled no longer disables RNG observation. Missing valuation
  remains unknown, and delayed complete receipts enrich the same event.
- **Journal:** permanent local history, saved search/activity/floor/profile/date
  filters, ordering, details, UTC period comparisons, observed rarity distributions,
  linked-run comparisons and actual-data trends. Compatible community comparisons
  count acquisition reports across the current Minecraft identity's profiles/servers;
  personal graphs retain item quantities. Legacy records remain explicitly
  distinguished and never replay into feeds. Missing old rarity is not inferred.
- **Leaderboards:** one persisted model serves Minecraft and Discord, with
  economy snapshots, dungeon completions/profit and supported RNG observations,
  UTC week/month boundaries, ties, search, pagination, source and coverage.
  Metrics without a genuine publisher say unavailable. Community rankings do
  not purport to cover every Hypixel player.
- **Discord RNG and chat:** an optional dedicated RNG channel, observed item
  name/rarity/known dungeon floor, durable bounded forwarding/outbox retries and
  deduplication. Older clients retain their exact event fields through capability
  negotiation. Reply context survives a cold
  bot cache by retrieving the reference; bounded Minecraft/Discord routing,
  edit/delete/reaction mappings and safe media/link fallbacks are retained.
- **Menu and HUD:** the established six main categories, search, favorites,
  recent tools, quick settings, icon rail and motion modes remain integrated.
  New screens use that navigation. Compact panels, recent personal RNG,
  viewport-safe saved layouts, per-widget visibility/reset and isolated graphics
  state improve the existing HUD rather than replacing its framework.
- **Signed optional updates:** metadata checks do not interrupt play. Update
  Now verifies and stages only a selected normal Fabric JAR. The JDK-only
  installer runs after exit, retains a backup and attempts rollback on failure.
  Later cancels staging/acceptance; settings and worlds are outside replacement.

## Confirmed defects and repairs

The old account/device startup path could reject an otherwise authorized new
Discord account because the saved installation was still enrolled to the older
account. The adopted architecture removes that local-startup dependency; it does
not secretly transfer production account ownership or bypass a blacklist.

Final integration review also found that the new client's 43-character random
nonce was rejected by the server's UUID-shaped validator. The server now accepts
the exact client format for connect, refresh and disconnect while rejecting
malformed values and generating its own independent proof challenge.

Cancelling a connection previously only cleared local state. The new cancellation
route requires that exact flow's private polling secret and revokes only its
issued session, retaining account associations and other computers' sessions.
Reviewed account disconnect, session kick, unlink and blacklist changes also
invalidate affected pending and completed-but-unpolled flows. The client handles
cancellation before a start response, duplicate clicks and concurrent polling;
disconnect independently cancels a newer relink and revokes the saved session.
Timeouts remain explicitly unconfirmed and retryable until the flow expires.

Source inspection identified inconsistent GUI/backing-inventory slot indexes,
Digest-dependent RNG initialization, verified-profile gaps inside dungeons,
partial/complete receipt duplication and all-or-nothing parsing of feed history.
The repaired paths preserve cold baselines, context and permanent identities.
The fixture evidence is described in [RNG observation notes](rng-journal-and-observation.md).

The pinned owo HUD adapter can skip scissor cleanup when a component draw throws
and shares the parent matrix/clipping stacks. The HUD now isolates those stacks
and rebuilds a failed widget without preventing its peers from rendering. This
is a source-confirmed error path, **not a reproduced cause of the Ubuntu/KDE
report**. See the same observation notes for the precise platform boundary.

Fullscreen Studio testing exposed a button row that allocated the first button
the entire row, unlabeled switches used as category/style buttons, slider wheel
handling that consumed page scrolling, an unsynchronized rotation thumb and a
dropdown clipped at the bottom of the screen. Buttons now share the row and show
their labels, rotation buttons update the slider, dropdowns measure before their
deferred mount and retain a bounded scrolling viewport, and menu sliders leave
wheel events to their enclosing scroll container. Click/drag and keyboard-focus
adjustment remain available. The journal's Trends/History caption now follows
the selected view immediately.

The final live pass also exposed a dropdown constructor exception: owo's
`removeChild` override tries to queue popup dismissal through its parent, but a
new popup has no parent yet. The constructor now clears the inherited flow
directly; the executable check constructs the actual popup and covers deferred
mounting and scrolling with retained rows. Slider arrows follow owo's supported
greedy-input path and logical focus, using the configured step and native value
setter. Tests cover focus, disabled controls, modifiers, endpoints and one
callback per change. Live arrow-key verification remains inconclusive: the
Computer Use helper's arrows also failed to move Minecraft's native chat caret.
Tab/Enter navigation and selection work in the Studio; a manual arrow-key check
is included in the operator acceptance work.

Rapid leaderboard selections previously accumulated obsolete HTTP tasks in an
unbounded executor queue. A shared serial drain now retains one running request
and only the latest pending selection. Screen removal interrupts its request;
captured identity/token/generation checks run before HTTP and before acceptance.
Fixtures cover 10,000 queued selections, 5,000 replacements behind a blocked
transport, owner changes, removal, stale results and failure recovery.

Repeated Later selections now coalesce identical preference writes while still
allowing a new Update Now choice to be cancelled. Studio work has an explicit
two-running/four-queued limit with visible retry feedback, and only one native
file chooser can run with no dialog queue. Captured authority is checked before
HTTP. Accepted mutations retain their intent after screen close; obsolete reads
and responses cannot restore another screen or account's data.

The final requirements review found that clearing a custom selection only
restored Minecraft's native cape. An explicit revisioned No cape choice now
hides that cape while preserving the skin, elytra and model. Inactive ready uploads
previously failed to preview because retrieval served only saved public selections.
The separate authenticated owner-only preview route rechecks current authority
after R2 reading; unauthenticated and other-account access remains unavailable.
Client preview decoding remains bounded, integrity checked and outside rendering.

Recorded item metadata now reaches the durable RNG forwarding/outbox and Discord
embed. New clients negotiate these optional fields; unnegotiated clients receive
the exact original event shape. Unsafe optional names are omitted at observation
without losing the genuine drop; strict incoming payloads reject unsafe values.
Rarity and floor are displayed only when actually supplied, never inferred from
the item catalog or replayed from old history.

Delivered Discord RNG posts previously rechecked only the newest 100 records,
which could leave older posts visible after a blacklist, ownership change or
sharing withdrawal. A persisted per-channel round-robin cursor now revisits all
unflagged delivered records in bounded batches, while known suppressed posts
retain deletion priority. SQLite fixtures cover 205 delivered posts, cursor
persistence and the oldest post's blacklist, relink and consent changes. Actual
Discord deletion still depends on subsequent bot polls and successful retries.

Relay validation previously normalized malformed name consent before validating
it. Raw non-boolean consent now fails validation. Pending shared lookups are
invalidated by local removal generations as well as server barriers, so client
clock skew cannot reintroduce removed data. A durable RNG forward queue closes
the gap between room acknowledgement and global-feed acceptance.

Automatic Worker tracing is disabled because request spans can retain full OAuth
URLs, including one-time flow/code/state query values. The operator handoff also
requires reviewing existing Tail/Logpush/exporter settings. No live logging
configuration or stored production logs were changed here.

## Verification

Automated checks use real local SQLite Durable Objects/R2 fixtures and synthetic
identities, mocked Discord/Mojang responses and lightweight executable JVM checks.
They exercise production routes and handlers; they are not live Discord/Hypixel
tests. All service checks completed successfully:

| Component | Commands and result |
| --- | --- |
| Standalone mod | Java 25 `./gradlew.bat build check --console=plain`: passed, 33 tasks (27 executed, 6 up-to-date). Includes normal-Fabric packaging, cancellation/disconnect, community/update HTTP deadlines, observed RNG metadata/strict Unicode, journal comparisons, bounded scheduling, private upload previews, no-cape skin/elytra preservation, actual dropdown construction, HUD, menus and existing routing/chat checks. |
| Relay | `npm test`: passed, including Wrangler dry-run bundles, SQLite Durable Objects/R2 fixtures, client-shaped nonces, OAuth/cancellation/revocation/privacy, cosmetics, leaderboard, durable RNG forwarding, IRC and release-signing checks. No production deployment. |
| Relay types | `npm run check`: passed (Wrangler type generation and TypeScript). |
| Backend/registration bot | `npm test`: 10/10 passed. |
| User Gateway bot | `npm test`: 65/65 passed; `npm run check`: passed. |

Windows visual verification uses the existing Prism Launcher with Java 25,
Fabric 0.19.5 and Minecraft 26.1.2 at 1920×1080 fullscreen, in the isolated
**Sky-Myce Verification** instance and **Sky-Myce Visual QA** local world. No
synthetic rewards were inserted into the running game's history or public feeds.
The final built and installed `skymyce-2.5.0.jar` have identical SHA-256:

```text
61efebf6ee88a9da792863b1779ddcf8a219fc31af1a0728809fac5a8820f66e
```

| Windows in-game check | Observed result |
| --- | --- |
| Startup and local gameplay | Title and local world start without an activation/Loader dialog or Discord connection. |
| Community screen | Fullscreen title entry opens readable connection, cancellation and update controls. Disconnected identity and unavailable update metadata are explicit. OAuth and privacy controls were not automated. |
| Menu | Six cards, readable wrapped descriptions, category icon tooltips, global feature/setting search, breadcrumbs and original setting destinations work. |
| Studio | Actual account skin, independent nametag/body preview, front/back rotation and synchronized slider; labeled style and local-display controls. The cape popup fits all six choices at the bottom of the fullscreen view. Sky-Myce and Void presets render; selecting No cape removes the rendered preset, and Minecraft cape restores the original skin appearance (this account had no native cape visible). Tab/Enter selection also works. Native Windows PNG chooser opens and cancellation returns to the screen. No live upload/public save performed. |
| Journal | Local screen opens offline; current-profile unavailability and empty history remain explicit. All local profiles shows actual zero-sample all-time/week/month counts. Recorded rarity and linked-run sections scroll into view; the community section explains offline status and incompatible floor filters. Weekly selection and Reset filters work; Trends changes to History immediately. No invented graph or drop probability. |
| Leaderboards | All four category labels render; selecting Dungeons chooses Dungeon runs. Weekly selection retains its caption across an 854×512 window and fullscreen restoration. Disconnected state explains connection and keeps the personal journal available. No live rankings were fetched. |
| Digest | Overview and RNG activity render; waiting-for-profile, cached-data age, empty history and the saved disabled-feed state are explicit. |
| Dungeon Tracker | Overview and preserved-history controls render; the new empty instance shows zero records with unknown average duration. The floor dropdown's final M7 option remains visible and selectable in an 854×512 window, then fullscreen is restored. No real chest was opened. |
| HUD | Client Info renders in gameplay and editor. Drag, scale and visibility work; changed position/scale/hidden state survive screen close and full game restart. Full reset restores default position, scale and gameplay visibility. Recent RNG and dungeon examples are explicitly editor-only and disappear in gameplay. |
| Resize | Tracker layout responds to an 854×512 window as well as fullscreen. Client Info and the HUD editor render at native GUI scales 3 and 4; scale 3 was restored after testing. |

## Main-machine work and limitations

[Main-machine rollout](main-machine-community-rollout.md) gives the exact Discord
permissions/intents, OAuth redirect and secret setup, dedicated RNG channel,
private cape buckets, command registration, signed release ordering and live
acceptance checks. Keep only one Discord Gateway. The existing live Backend Bot
uses a separate checkout, so deploying this account Worker alone cannot update
that running Gateway implementation.

No Cloudflare production deployment, bot command registration, OAuth secret
creation, public RNG post or production enrollment transfer occurred here.
The laptop lacks the authenticated Wrangler session/private release key and
the separate live bot checkout. Linux/KDE, fresh live Hypixel reward acquisition,
cross-client cosmetics and live Discord/OAuth/IRC require the documented operator
checks. Both supplied YouTube references could not be retrieved; no viewing or
transcript analysis is claimed.

GitHub rejected the first push because the saved OAuth login lacks `workflow`
scope. The existing Actions workflow is preserved; the proposed Node 22 service
test matrix is supplied as [an operator patch](community-services-ci.patch) in the
main-machine handoff. Local checks passed independently of that CI addition.

Visual checks do not establish audio playback. A later verification launch logged
Minecraft's `Failed to open OpenAL device` and disabled sound; no OS audio/device
settings were changed. Existing optional-mod/resource warnings also remain, so
the report does not claim a warning-free startup log.

Key implementation contracts are documented in
[community architecture](community-architecture.md),
[Cosmetics Studio](COSMETICS_STUDIO.md),
[leaderboard definitions](community-leaderboards.md) and
[sharing](community-sharing.md).
