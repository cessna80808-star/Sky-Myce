# IRC redesign — 2.4.29

Global IRC is the shared Minecraft ↔ Discord channel. Party IRC is Minecraft-only,
with delivery restricted to opted-in clients reporting the same complete current
party roster. Party messages, replies, reactions and mutations never enter a
Discord delivery queue. This follows the user's final audience clarification.

## Using it

`/sm irc` (also `/sm global`) selects Global; `/sm party` (also `/sm pc`) selects
Party; `/sm chat` returns to ordinary chat. Passing text sends one message to the
selected channel without changing that selection. `/sm ircstatus` shows current
availability. Failed IRC sends remain private and cannot fall through to Hypixel
public chat.

The existing Global settings category retains its path and disabled default. It
now contains separate Global/Party switches, chat thumbnails, GIF animation in the
viewer, mention highlighting and mention sounds. Party routing stops when the
roster is incomplete, membership changes, the consent lease expires, or account/
relay authorization changes.

Messages use the cyan → blue → violet `Sky-Myce Global:` and `Sky-Myce Party:`
prefixes. The compatibility room renderer uses `Sky-Myce IRC:`. Discord-created
cards omit the old `[SkyMyce]` tag. Every Minecraft message sent to Discord uses
an embed, including text-only messages. Its one-line author header includes the
verified Minecraft name and an optional server-owned cosmetic name. Player-head
URLs derive from the authenticated UUID. The footer says `Minecraft`; it does not
claim continuous online presence after the original send.

Image/GIF previews occupy two native chat rows with a compact 48×18 content area.
They follow vanilla wrapping, scrolling, resize and chat scale because each row
belongs to the native message identified by its scoped tag. Clicking opens the
viewer; chat GIF thumbnails remain static. The viewer offers fit, zoom, drag/pan,
GIF play/pause, retry, close/Escape, and Minecraft's confirmed browser-opening
flow. Videos show a poster/play indicator when one is available and open the
original in an external browser for playback. Unsupported or oversized media
keeps a usable original link and an explanatory placeholder.

Each recent message exposes controls for reply and the six supported reactions.
Own Minecraft messages additionally expose edit/delete controls for 15 minutes.
Native Discord edits/deletions update the corresponding game message; deleting
clears media, replies and controls. Minecraft never edits/deletes a human-authored
Discord message. Reply context travels in both directions. New mentions may
highlight and ping once; edits, duplicate delivery and reactions do not repeat the
notification.

GIF deduplication compares canonical media identities across message text,
attachments and embeds. Discord's `ex`, `is`, `hm` signing parameters are ignored
only for identity comparison on numeric Discord attachment URLs. Actual downloads
retain the signed URL. Meaningful query parameters and different URLs are not
collapsed. Delayed duplicate embeds do not create extra chat messages.

## Significant changed files

Paths below are relative to this repository unless another root is named.

| Files | Purpose |
| --- | --- |
| `.../friends/RelayMessages.kt`, `GlobalChat.kt`, `IrcMode.kt` | Channel selection, capabilities, subscriptions, receipts, native message updates, reply/mention/action controls and safe failure handling. |
| `.../friends/IrcChatModel.kt`, `IrcParty.kt` | Strict scoped message parsing, revision/tombstone handling, bounded client metadata and fresh complete party-roster/lease checks. |
| `.../friends/IrcChatRendering.kt`, `IrcMessageControlsScreen.kt` | Native-chat thumbnails, gradient prefixes and explicit reply/reaction/own-message controls. |
| `.../friends/IrcMediaAssets.kt`, `IrcMediaBytes.kt`, `IrcGif.kt`, `IrcMediaViewerScreen.kt` | Safe asynchronous media loading, bounded cache/GIF decoding and functional media viewer. |
| `.../friends/DungeonFriendRelay.kt`, `DungeonFriends.kt` | Authenticated rich/Party protocol dispatch and reuse of the existing party-info scheduler. |
| `src/main/java/me/mycellium/mixin/IrcChatAccessor.java`, `IrcChatScreenAccessor.java`, `skymyce.mixins.json` | Narrow native chat-row, scroll and click access; vanilla suggestions retain priority. |
| `RelayChatPrivacyMixin.java`, `HudRenderer.kt` | Document privacy scope and let IRC pointer handling coexist with HUD interactions. |
| `GlobalChatConfig.kt`, `MenuCatalog.kt`, `SkyMyceCommands.kt` | New preferences and user-facing command/help/status entries without changing inherited Global defaults. |
| `relay/src/irc-model.ts` | Shared validated media/reply/reaction schema; bounded lifecycle, owner checks, revisions, deduplication and tombstones. |
| `relay/src/irc.ts`, `index.ts` | Capability-aware Global/legacy delivery and mutations; separate private Party coordinator and consent delivery; scoped bridge receipts/errors. |
| `discord-user-bot/src/global-chat.mjs`, `relay.mjs` | Shared card/media normalization, signed-URL deduplication, negotiation buffer, deterministic nonces and durable receipts before ACK. |
| `discord-user-bot/src/chat-operations.mjs`, `user-bot.mjs` | Native Gateway lifecycle/reaction handling, real destination ownership checks, scoped REST mutations and loop suppression. |
| Client `GlobalChatCheck.kt`, `IrcChatModelCheck.kt`, `IrcGifCheck.kt`, `IrcMediaCheck.kt`, `MenuCatalogCheck.kt`; `build.gradle.kts` | Executable pure-logic/client regressions and registration in Gradle `check`. |
| `relay/test/rich-irc.mjs`, `relay.mjs`, `irc.mjs`, `global-chat.mjs`, `social.mjs` | Actual workerd rich lifecycle, Party audience/expiry and older-client compatibility regressions. |
| `discord-user-bot/test/rich-relay.test.mjs`, `global-chat.test.mjs`, `discord-bots/test/bridge.test.mjs` | Delivery/receipt/restart/failure/native-operation and embed/media regressions. |
| `gradle.properties`, `relay/wrangler.jsonc` | Release 2.4.29 and its signed private-artifact pointers. |
| `D:/Code/Cessna fork/relay/src/gateway-chat.ts`, `global-chat-gateway.ts` | Persistent Cloudflare Gateway counterpart using the same rich normalization, native events, ownership rules, receipt/revision recovery and bounded queues. |
| Cessna `relay/test/global-chat-gateway.mjs`, `GLOBAL_CHAT_GATEWAY.md` | Gateway actual-workerd regressions and hosting/protocol instructions. |
| Cessna `relay/src/index.ts`, `package.json`, `wrangler.jsonc` | Selective Gateway route/export/type, test command and service/DO/identity binding integration; unrelated local admin/cosmetic changes are excluded from the commit. |

`.../friends/` is
`src/main/kotlin/me/mycellium/skymyce/features/instances/dungeons/friends/`.
Unrelated dirty files, credentials, runtime data and generated release bundles
are not part of this change.

## Protocol and authorization

The existing authenticated protocol remains version 3. A ready packet advertises
`richChatVersion: 1` and, for authorized clients, `partyChat`/`partyChatVersion: 1`.
Clients and bridges explicitly negotiate version 1 and wait for the matching
subscription/capability ACK. Initial packets arriving during negotiation are
bounded by 64 entries/1 MiB and are discarded on failed/replaced transports.
Older clients receive plain initial messages and no unsupported update packets.

Rich `irc_message` packets add `model: 1`, scoped canonical `id`, `revision`,
verified `sender`, up to four `media` descriptors, optional `reply`, and bounded
`reactions`. Media descriptors contain an ID, type (`image`, `gif`, `video`), HTTPS
URL and optional poster/name/MIME/size/dimensions. Replies carry canonical and,
where known, Discord IDs plus bounded sender/body preview. Rich `irc_update`
targets `messageId` with a monotonic revision and edit/delete/reaction state.
Client `irc_operation` requests have a separate 32-hex operation ID.

Discord source IDs stay distinct from canonical IDs and destination receipts.
`discord_ack` and `discord_operation_ack` commit the actual destination identity;
terminal `discord_operation_result` includes its configured channel. Wrong-scope
or wrong-channel receipts/errors cannot acknowledge/cancel another audience's
operation. A trusted superseded error removes obsolete work. REST success and
durable receipt persistence precede ACK; a failed write remains retryable.
Deterministic Discord nonces cover the crash interval between POST and receipt.
Native event IDs derive from Gateway session/sequence and source identity.

Edit/delete permission is checked at the backend against the authenticated
Minecraft UUID and linked Discord account, source ownership and retention window.
The bridge independently GETs the actual configured guild/channel/message and
checks its real author before changing it. HMAC, nonce, lease/session checks,
input bounds and existing send quotas remain in force. Reactions additionally
use a per-actor burst of 12, refilling one credit every two seconds. Discord uses
one bot reaction per emoji to represent the aggregate Minecraft audience; it
cannot impersonate separate human Discord reactors. Counts and individual
Minecraft toggle state are tracked separately.

Party uses its own `party-chat` coordinator and a SHA-256 roster/leader hash.
Each connected participant independently opts into the same exact roster (2–20
authenticated UUIDs), with a short lease and monotonic subscription revision.
The client requires fresh authoritative SkyBlockAPI PartyInfo UUIDs and a unique
leader, compares against current PartyAPI state, refreshes consent every 30s and
uses the returned server expiry capped at 55s. The backend's consent lifetime is
at most 60s. Account, connection, membership or authorization changes invalidate
delivery before fanout and clear stale media/actions. The backend can verify
authenticated identity and matching independent consent; it cannot independently
attest Hypixel's live roster. This trust limit is explicit in settings/help.

No chat-history endpoint is added. Lifecycle metadata is limited to 1,024 recent
messages/15 minutes, 256-character reply previews, up to four media descriptors,
six emoji/384 reaction actors per message and 4,096 seen operations. Tombstones
clear media, reply, body digest and preview. Existing Global delivery and new
mutation outboxes retain only bounded transient bodies for at most two minutes,
with at most five attempts. Bridge routing/receipt metadata also expires after
15 minutes; its small own-card recovery snapshot is at most two minutes.

## Media safety and limits

The backend does not fetch arbitrary media. Client downloads allow only HTTPS on
`cdn.discordapp.com`, `media.discordapp.net`, `media.giphy.com`, `i.giphy.com`,
`media.tenor.com`, `i.imgur.com`, and `images.unsplash.com`. Numeric/private/local
destinations, credentials, unusual ports, unsafe DNS addresses and redirects are
rejected. Returned bytes and decoder input are checked; MIME/extensions alone
are not trusted.

There are two active downloads and a bounded pending queue. A five-second load
watchdog cancels queued/active work by token, rejects late results and keeps a
blocked native DNS job counted until it exits. Thumbnail downloads are at most
2 MiB; full still images 12 MiB; GIFs 6 MiB. Still images allow dimensions up to
4,096 and 16 million pixels, with decoded storage bounded to 32 MiB. GIFs allow
1,024-pixel dimensions, 120 frames, 32 MiB stored frames and a 40 MiB working
budget. The shared decoded cache is 48 MiB/64 entries with a two-minute idle TTL.
Account/disconnect/replacement cancellation prevents stale jobs from updating
another message; images/GPU resources are released on eviction/viewer close.

## Deployment configuration

Production Global stays on channel `1553536927498506270` using the existing User
Bot, Worker secrets and HMAC bridge. The persistent Gateway runs on
`skyblock-relay`, forwarding to `sky-myce-backend`; a local Node process is not
needed for production. The maintained Node implementation remains usable as an
alternative host, but must not be started alongside the persistent Gateway for
the same Discord audience.

No Party Discord channel, token or environment variable is needed. Optional
legacy room Gateway support requires explicit `IRC_ROOMS` and separate configured
Discord channel IDs; production leaves it empty/off. `/irc/party-chat/*` is not
supported. Existing legacy relay namespaces and other Worker bindings stay intact.

The User Bot needs View Channel, Send Messages, Embed Links, Read Message History
and Add Reactions in the Global channel. Gateway uses Guilds, Guild Messages,
Guild Message Reactions and the enabled Message Content intent. Existing
`GLOBAL_CHAT_ENABLED`, `GLOBAL_CHAT_CHANNEL_ID`, `USER_BOT_APPLICATION_ID`,
`USER_BOT_TOKEN`, `DISCORD_GUILD_ID`, `DISCORD_BRIDGE_SECRET`, private control
secret and backend service binding are reused. No secrets reach the client.

See [the deployment record](backend-deployment.md) for release hashes, live Worker
versions and staging/provider readback. Previous releases are retained. The
reviewed Gateway deployment package preserves the exact previously deployed
legacy bundle and adds only Gateway changes; unrelated local Cessna admin/prompt
changes are excluded.

## Checks actually run

| Check | Result |
| --- | --- |
| Java 25 focused `globalChatCheck`, `ircChatModelCheck`, `ircGifCheck`, `ircMediaCheck`, `menuCatalogCheck` | Passed, including malformed metadata, revision/deletion/stale identity, GIF framing/disposal/bounds, cancellation/byte budgets and Party lease expiry. |
| Java 25 `gradlew.bat --offline --no-daemon check build --console=plain` | Passed all registered checks, including the unchanged loader/dungeon checks; produced `skymyce-2.4.29.jar`. |
| Backend `npm test` | Passed full relay/auth/Global/IRC/social/Discord/prompt/signing suite with actual Miniflare/workerd fixtures. |
| Backend `npm run check` | Passed Wrangler type generation and TypeScript. |
| Backend strict `--env backend` and `--env staging` dry-runs | Passed; final bundles match. |
| Final `node test/relay.mjs --rich-only` | Passed Global/legacy/Party separation, consent races/revocation, ownership/native mutations, media/replies/mentions, tombstones, actual DO eviction/expiry and wrong-channel/superseded error handling. |
| Node User Bot `npm test`, `npm run check` | Passed 54/54 and TypeScript after the final scoped error-envelope correction. Windows sandbox blocked atomic fixture renames; the authorized unsandboxed rerun passed. |
| Cessna full `npm test`, `npm run check` | Passed; final focused actual-workerd Gateway test also passed after the error-channel correction. |
| Clean Cessna staged snapshot `npm test`, `npm run check` | Passed with only the seven selected Gateway files/integration changes atop the Git baseline; unrelated dirty admin/cosmetic code was absent. |
| Reviewed isolated Gateway wrapper runtime check | Passed exact legacy/auth/admin forwarding and input limits, configured Gateway routes, legacy rooms off and Party rejection; no external traffic. |
| Live staging | Health 200, GET artifact 405, anonymous POST artifact 403 and protocol-3 challenge passed. Independent provider readback matches the reviewed final bundle. |
| Private artifact/manifest and live release verification | Passed byte/hash readback, exact envelope, pinned Ed25519 signature, version/expiry and anonymous artifact guards. |
| Final production Gateway | Exact provider bundle hash; all 18 bindings preserved. Controlled stop/start adopted rich version 1, with both transports ready for 75,805 sampled consecutive milliseconds; 32 samples included startup. No pending messages at any sample. Legacy health 200, unsigned interactions/private control 401, legacy room bridges 503 and Party route 404. |

Full staging rich-chat authentication is not claimed: no test license or Discord
credentials are configured there. Those lifecycle cases ran in isolated actual
workerd fixtures. No new synthetic message was posted to the live Discord channel
for this release.

## Remaining verification and limits

The current Prism session was actively in a Hypixel dungeon and was not restarted
or interrupted. New native GUI rendering/clicking, scroll/scale/resize behavior
and a real 2.4.29 Minecraft ↔ Discord interaction have not been exercised in that
session. Linux/Ubuntu/KDE was unavailable; no Linux rendering test or hour-scale
performance soak is claimed. The previous release's live round trip is not
evidence for this release's new media/actions UI.

The in-game decoder supports PNG/JPEG/GIF, with GIF animation only in the viewer.
WebP/AVIF and videos retain browser fallback rather than claiming untested native
playback. Signed/expired links may require opening or reposting the original;
there is no token-bearing media proxy or arbitrary local-file upload. Party
delivery requires every recipient to use an opted-in compatible client with a
matching fresh complete roster, and can pause while that roster is unavailable.
