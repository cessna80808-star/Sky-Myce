# Cosmetics Studio protocol and storage

The Studio has a local appearance and an optional community profile. **Equip and save locally** persists the selected name, body axes, nametag size and cape on this computer without Discord or Hypixel. Choosing a packaged preset, Minecraft cape, None, or a local PNG equips and persists that selection. **Import PNG and equip locally** validates and copies the original file into the bounded local library; moving or deleting the original download afterward does not break the cape. File validation and storage run on a bounded background worker, never the renderer.

Local data lives under `config/skymyce/local-cosmetics/{minecraftUuid}/`: atomic `appearance.json` plus immutable PNGs named by SHA-256. Each Minecraft UUID has its own preference and library (eight files, two MiB total, 256 KiB each). The renderer reads the equipped local preference first in any world; relay refreshes, reconnects and account changes do not overwrite it. **Use shared / Minecraft appearance** explicitly releases that local preference while retaining the reusable local library. Cosmetic display toggles still control name and body styling; these are visual changes and do not change identity, movement or hitboxes.

**Publish appearance to community** is an explicit separate action. Local PNG publication first uploads a validated copy through the authenticated grant route, then saves the returned community asset using the current server revision. Both requests belong to one bounded accepted mutation: closing the Studio retains that Publish intent, while a session or Minecraft-owner change prevents the next request. The local equipped selection stays local and unchanged. Publishing requires the owner's current community session; local references are never accepted as community asset ownership. A profile changed elsewhere must be refreshed before retrying a conflicting public save. Shared saves and uploads use a current access token and its verified Minecraft UUID; displayed names, client UUIDs and `linked` booleans grant no authority. Captured `expectedAccountId` and `expectedUuid` fields prevent delayed saves from affecting a newly selected account.

**None / Remove cape** persists an unequipped cape without deleting a PNG. **Delete selected PNG from library** requires a second click and removes that local or authenticated community upload; deleting an equipped local upload leaves None selected. The default Minecraft cape and None remain distinct. Community uploads that have not been publicly selected use the owner's authenticated private preview route; the local PNG library needs no bearer token.

The name JSON field accepts inert native Minecraft text strings, objects, arrays, nested arrays and `extra` children. JVM validation bounds UTF-8 input to 8 KiB, total nodes to 64, depth to eight and final visible text to 32 UTF-16 units, rejecting executable events, selectors, translations, fonts, unsafe characters and noncanonical Unicode. The pinned Minecraft component codec parses and serializes the validated tree, preserving per-character colors, style flags and inheritance instead of flattening it into plain text. The relay uses the same inert fields and bounds, with canonical array-to-parent conversion matching Minecraft inheritance. The Discord string option is additionally bounded to Discord's 6,000-character maximum. Editing a body axis or cape keeps the name component intact. The avatar hook marks already chosen cosmetic names so the shared Font hook cannot substitute the real username inside that name a second time.

`AccountStore` owns the authoritative appearance profile. On a room's first upgraded connection, profiles migrate in bounded batches from the configured `DISCORD_INTERACTIONS_ROOM` (default `friends`). Testing rooms in the same Worker read this shared appearance; staging is a separate Worker/account store/bucket. Existing name components, body scaling, revisions and resets remain supported. Discord commands and the Studio update the same records.

New clients negotiate `cosmeticsVersion:5`, retaining the exact v4 profile shape while supporting bounded native component trees. Profiles retain `uuid`, `name`, `nameStyle`, `scale`, `scaleX`, `scaleY`, `scaleZ`, `revision` and `updatedAt`, plus `nametagScale` (0.5–2.0), `capePreset` and `capeAsset`. Nametag size is independent of body axes (0.1–3.0). Preset IDs are `sky-myce`, `forest`, `sunset` and `void`, resolved through packaged client textures. An upload reference is `{id,sha256,size,width:64,height:32}`. Preset and upload selection are mutually exclusive. Negotiated versions 1–4 receive their previous exact fields/ranges. Versions 2–4 keep existing simple styles; a tree beyond the previous 16-node/depth-four/2-KiB bounds falls back to a plain name so their strict parsers can still read the profile.

The exact `capePreset:"none"` sentinel explicitly hides the cape while retaining
the player's elytra. It is distinct from `capePreset:null,capeAsset:null`, which
retains Minecraft's native cape. `none` cannot be combined with an uploaded asset
and does not appear in the controlled texture preset list. It persists and uses
the same revision/ownership checks as other v4 selections; older clients retain
their original response shape.

All authenticated HTTP requests use `Authorization: Bearer <community access token>`:

| Route | Request / result |
| --- | --- |
| `GET /community/cosmetics` | Own `profile`, uploaded `assets`, controlled `presets`, `quota` |
| `POST /community/cosmetics` | `{expectedAccountId,expectedUuid,revision,profile:{name,scaleX,scaleY,scaleZ,nametagScale,capePreset,capeAsset}}`; name is null/plain text/inert component, asset selection is its ID/null |
| `POST /community/cosmetics/grant` | Captured identity; returns `grant`, `assetId`, `expiresAt`, `uploadPath` |
| `PUT /community/cosmetics/upload` | `Content-Type:image/png`, `X-SkyMyce-Upload-Grant`, binary PNG; returns the canonical asset descriptor |
| `DELETE /community/cosmetics/assets/{id}` | Captured identity JSON body; removes owned upload and revisioned active selections |
| `GET /community/cosmetics/assets/{id}?uuid={viewedUuid}` | Controlled public active-cape retrieval; no arbitrary URLs, paths or bucket listing |
| `GET /community/cosmetics/preview/{id}?uuid={currentUuid}` | Authenticated private preview of an owned ready upload before selection; current token UUID required, `private,no-store` |

Every save checks the current server revision. `revision_conflict` returns the latest authoritative profile so the client can reload instead of overwriting it. Accepted changes notify cosmetic subscriptions in every configured room; revision ordering invalidates stale client selections.

Upload grants last two minutes and are tied to one account, verified UUID, community session/generation and generated asset ID. Only their hashes are stored. Claiming consumes the grant before reading/validating/writing. Account ownership, current session, expiry and blacklist are rechecked after asynchronous R2 writes before finalization. Failed validation or interrupted writes cannot create selectable assets or overwrite another upload. Uploads do not activate a cape until a separate revisioned profile save selects it.

The limit is eight uploaded/reserved capes, two MiB per account, 256 KiB per file. Reservations include the maximum file size, so concurrent grants cannot exceed quota. PNG validation checks the signature, every chunk CRC, exact 64×32 RGB/RGBA eight-bit noninterlaced layout, critical chunk order, a complete zlib stream, exact bounded scanline count and valid filter bytes. Unsupported dimensions, animation, malformed chunks and excess decompressed output fail. Ancillary metadata is stripped before storage; consequently the returned SHA-256 can differ from the input file's hash. Clients verify the returned descriptor against downloaded bytes before decoding.

`COSMETIC_ASSETS` is a dedicated R2 binding. Production/backend names `sky-myce-cosmetic-assets`; staging uses `sky-myce-staging-cosmetic-assets`. The code never passes `PRIVATE_ARTIFACTS` to cape handlers. Create these private buckets on the authorized main machine before rollout, keep public bucket access disabled, then regenerate types and perform the normal local/backend dry-run and checks. Configuration changes in this checkout do not provision or deploy buckets.

Public retrieval serves only ready assets selected by an active profile under the asset owner's current account mapping. Selection, ownership and blacklist are checked again after storage/integrity validation. Unlink/reconnect makes a former owner's upload unavailable without transferring upload ownership. Retrieval uses `image/png`, SHA-256 ETags, `nosniff`, and `private,max-age=60`, with no immutable caching; deletion/profile changes invalidate local texture selection. Removal becomes authoritative before physical R2 deletion; a transient delete failure remains queued and can safely be retried.

The separate private preview route accepts only the authenticated session's
current Minecraft UUID and assets in its account's uploaded collection. It does
not activate the upload or loosen public retrieval. Session, ownership, blacklist
and ready state are checked before R2 access and again after bounded byte/hash/PNG
validation. A revocation, relink or deletion during that work withholds the PNG.
The client keeps private preview textures in a separate bounded cache pinned to
the captured account, Minecraft UUID, session generation and token. Closing the
Studio or switching identity clears that cache and prevents an in-flight preview
from installing pixels into a later screen. Public active-cape caching remains
separate; private preview never saves the community profile. Local Equip persists only the local preference.

Durable Object alarms release expired reservations and remove orphaned/interrupted uploads, retrying R2 cleanup after failures. Deleted metadata is retained for thirty days then pruned once cleanup is complete. Ready uploaded capes stay in the owner's bounded collection until explicitly removed; switching presets does not delete a reusable upload.

`node test/relay.mjs --studio-only` exercises production HTTP routes, real local Durable Object SQLite and separate local R2 buckets using entirely synthetic identities. It covers ownership, revisions, compatibility, cross-room events, PNG corruption/dimensions/inflate bombs, bounded bodies, replay/expiry, quota reservations, download integrity, deletion, interrupted-write cleanup and revocation before finalization. It is included in `npm test`. These local tests do not establish live Discord/OAuth provisioning, R2 deployment, cross-platform file picker behavior or rendering in an actual multiplayer session.

`dungeonFriendsCheck` includes local library restart/UUID isolation, import-copy and malformed-file preservation, None versus deletion, native JSON round trips/inheritance and protection against recursive cosmetic-name substitution. `node test/relay.mjs --cosmetics-only` covers signed Discord component-array edits, preservation during body-only saves and old-client style fallback. These are regression fixtures; current fullscreen observations and executed check results belong in the release implementation report.
