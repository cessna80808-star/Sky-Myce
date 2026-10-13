# Sky-Myce 2.5.0 community architecture

Sky-Myce is installed as a normal Fabric mod for Minecraft 26.1.2 with Java 25. The ordinary JAR contains the complete UI runtime and has no dependency on Sky-Myce Loader. Startup, local modules and personal history do not depend on Discord, licenses, activation codes, hardware fingerprints or an update server. Old Loader sources remain available for historical tests and explicit legacy builds; ordinary assembly excludes them. Remove the old Loader when migrating an instance, preserving unrelated mods and local history.

## Identity and authority

The existing AccountStore is the authority for Discord identity, Minecraft identity links, deliberate blacklists, sessions, sharing preferences and cosmetics. Connecting requires a fresh Minecraft certificate/profile proof and a browser Discord OAuth flow. The browser uses an expiring state and an HttpOnly/Secure/SameSite cookie; Discord tokens and client secrets remain on the backend. Mousebot `/connect` accepts an expiring one-time fallback code when the browser flow is unavailable. No staff allowlist is required.

Cosmetics also have a UUID-scoped local appearance store. Local Equip and validated PNG import work without Discord and take priority in the local player renderer; remote session refreshes cannot replace that preference. Only explicit Publish changes the community profile using current session ownership and CAS revision checks. None removes a cape without deleting its reusable file. Native bounded name JSON retains per-character styles. See [Cosmetics Studio](COSMETICS_STUDIO.md).

The mod stores a rotating refresh credential per Minecraft UUID in a bounded private atomic file. Access tokens expire after fifteen minutes; refresh credentials rotate and expire after ninety days. Fresh Minecraft proof accompanies refresh and disconnect. Session changes clear cached online authority. Explicit Connect can move the selected Minecraft identity to the new Discord account, revoking its old sessions while preserving other linked Minecraft accounts and restrictive sharing preferences.

Cancel pending connection sends the exact flow ID and private polling secret to the backend. A confirmed cancellation prevents browser/fallback completion and revokes only that flow's issued session, retaining associations and independent sessions. An unavailable response stays explicitly unconfirmed and retryable until expiry; repeated clicks cannot replace an in-flight revocation. Disconnect cancels a newer pending relink and revokes the saved session independently. Reviewed account disconnect, session kick, unlink and blacklists invalidate their affected stale flows, including completed requests that have not yet issued credentials.

Multiple Minecraft identities and computers can use a Discord account. Device metadata is optional and informational. Missing Linux/Windows/macOS identifiers never deny access. Administrator reports keep device details private. Deliberate Discord/Minecraft blacklists restrict online services; they never prevent local Minecraft startup. Old activation, hardware-reset and Loader routes return explicit migration guidance rather than issuing new licenses.

## Privacy and data

Sharing starts enabled unless an existing explicit preference says otherwise. Global, category and public-name preferences are revisioned, identity-bound and checked again at the authoritative boundary. An opt-out purges affected shared records and queues feed retraction; cutoffs prevent delayed records from returning. Chat and cosmetics are independently connected. A failed consent update is visible in the client and retried, with sensitive category publication suppressed until the saved state is reconciled. See [sharing semantics](community-sharing.md).

The local acquisition archive remains the source of personal history. Cold inventories only establish a baseline. New confirmed receipts and supported announcements use one acquisition identity, so delayed complete receipts enrich rather than duplicate a drop. Neither legacy history nor community-feed history is replayed as a fresh report. Missing prices retain a genuine receipt with unknown valuation. See [journal and observation evidence](rng-journal-and-observation.md).

The journal also preserves actual observed item name/rarity, UTC local period and linked-run comparisons, and an optional aggregate community comparison from the shared RNG ranking dataset. Personal charts count item quantities; shared comparisons count acquisition reports once per event across the current Minecraft identity's profiles/servers. Missing historical metadata, price and duration remain unknown. Filter incompatibility, opt-out and absent community samples are explicit.

In-game and Discord leaderboards query one persisted service. Weekly/monthly boundaries use UTC. Coin values are estimates; economy ranks the latest complete observed snapshot within a period, rather than pretending it measures gains. Dungeons use new timestamped completions and fully valued chest profit, including losses. RNG uses newly accepted supported reports. Metrics without a real publisher are explicitly unavailable. Every result labels source, freshness, samples and participant coverage. See [metric definitions](community-leaderboards.md).

## Cosmetics and presentation

The Studio edits a draft with a real skin/model preview, rotation, inert styled names, independent nametag scale, body X/Y/Z scales, preset capes and bounded uploaded PNG capes. The preview paints a separate render state. Saving captures the account/UUID and the authoritative revision; concurrent changes require a refresh instead of silently overwriting another edit. Uploaded capes are selected and saved explicitly. The server owns profile revisions, upload grants, quotas and asset references; older clients receive compatible profile shapes.

Cape files use a dedicated private R2 binding, separate from release artifacts. Both client and server validate 64 × 32 RGB/RGBA PNGs, chunk CRCs and exact bounded decompression before decoding. Animated, malformed, oversized and ownership-mismatched uploads fail closed. Serving only exposes an active profile's authorized opaque asset reference. The client verifies returned size/hash, decodes away from rendering, and keeps a bounded texture cache. See [Studio protocol and asset storage](COSMETICS_STUDIO.md).

The menu keeps the existing six-category architecture, shared icon rail, search, favorites, recent items and motion modes. New tools are reachable through search and `/sm cosmetics`, `/sm journal`, `/sm leaderboard`, and `/sm community`. The HUD retains the existing Fabric/owo pipeline, bounds saved layouts to the current viewport and supports per-widget visibility/reset. Recent RNG uses actual confirmed current-profile records; illustrative content is editor-only and labeled.

## Optional signed updates

Automatic checks fetch public signed metadata only. A release uses protocol 4, `installation: fabric`, a matching Minecraft version, bounded size, SHA-256, Ed25519 signature, expiry and release notes. The compiled mod contains HTTPS backend configuration and public signing keys only. Invalid, expired, unavailable or older metadata never stops the installed mod.

**Auto-update** is a persisted On/Off switch on the existing Community page, defaulting Off for new and migrated installations. Off leaves artifact installation to **Update Now**. On authorizes automatic downloading and staging of a newer verified release; installation still occurs only after Minecraft exits. **Later** skips the selected version in both modes. Turning Off cancels automatic staging and keeps an independently approved Update Now intact; Later cancels either. The bounded atomic preference write finishes immediately, independently of an in-flight download, so exit cannot silently restore the previous On state.

Both approval paths use the same installer. The client checks the signature before trusting metadata, verifies size/hash and the normal Fabric identity, and creates a tiny JDK-only helper. On Minecraft exit the helper waits for the old process to end, verifies the old/new files again, atomically retains the old JAR as a backup, and replaces only the selected mod JAR. If replacement fails after backup, it attempts to restore the old JAR. Unsupported layouts/filesystems fail safely and require manual replacement. Backups remain in `.sky-myce-updates`; settings and worlds are outside the replacement scope.

**Later** also cancels a previously staged installation or the acceptance of an in-progress download. Exiting after selecting Later does not install that deferred release. HTTP bodies have an explicit consumption deadline as well as a size limit, so a server that sends headers and then stalls cannot leave the community/update worker busy indefinitely.

On the operator machine, sign a built normal JAR using:

```powershell
node relay/scripts/release.mjs sign-community build/libs/skymyce-2.5.0.jar <private-key.pem> <key-id> 26.1.2 2.5.0 <notes.txt> <new-manifest.json>
```

Keep the private key outside this checkout. Upload the signed version's JAR to its exact `releases/Sky-Myce-<version>.jar` object first, then publish the envelope as `releases/community-current.json`. Ordinary startup never fetches a Loader or minimum-Loader gate. Legacy signing commands remain explicitly labeled historical workflows.

## Main-machine work and verification limits

The laptop can build code and run local cryptographic, SQLite, R2, bot and GUI checks. It has no authenticated Wrangler operator session, release private key, or the separate live Backend Bot checkout. No production deployment, slash-command registration or live bot hosting change is implied by a passing local test. Use the [main-machine rollout](main-machine-community-rollout.md) for exact permissions, secrets, storage bindings, deployment order, one-Gateway hosting choice and live acceptance checks.

The actual Windows fullscreen testing and final command results are recorded separately in the implementation report. Linux/KDE gameplay, cross-client online cosmetic delivery, real fresh Hypixel drops and the live Discord service require their stated environments; fixture results do not substitute for those checks.
