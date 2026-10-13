# Global SkyMyce chat

Global chat extends the authenticated Minecraft relay and the existing User Bot application. It does not create another Minecraft WebSocket, license service or Discord bot. Clients opt in under **Utils → Global SkyMyce Chat**; explicitly selecting `/sm irc` or sending `/sm irc <message>` also enables receiving. `/sm irc` directs ordinary chat input to global IRC, while the message form sends once without changing the selected mode. `/sm chat` returns to normal chat while receiving remains enabled. `/sm global` and `/chat sky` remain compatibility aliases. Incoming global messages do not require selecting the channel. Turning the feature off hides messages and mention sounds without invalidating SkyMyce authorization.

Every opted-in authorized participant in one Worker deployment receives the same channel, including players in different relay rooms, worlds, lobbies, parties, dungeons and servers. Staging and production deployments remain separate. Existing private messages, account operations and room-local IRC remain compatible.

Party chat is a separate scope and currently fails closed because the backend cannot verify Hypixel membership. It never enters this global channel or Discord; see [the party capability contract and unresolved authority dependency](party-chat.md).

## Transport and identity

The existing `irc_subscribe`, `irc_send`, `irc_message` and `irc_result` frames gain `scope: "global"`. Authenticated readiness advertises `globalChat`. The existing `RelayRoom` namespace uses the deterministic `global-chat` instance to coordinate quotas, deduplication, Discord delivery and broadcasts to configured relay rooms through internal RPC. Each receiving room validates current account authorization before sending to a socket; disconnected, expired, revoked or unsubscribed sessions receive nothing.

Minecraft names, UUIDs and Discord linkage come from the current verified community session. Clients cannot choose their sender identity. Discord messages arrive through the User Bot's existing signed bridge at `/discord/bridge?room=global-chat`. The bridge accepts only the configured channel. Discord authors follow that channel's ordinary server permissions. Local mod gameplay and Discord channel participation need no Minecraft license.

The client accepts global messages only from its current account, relay endpoint and community session. The relay checks session expiry/generation and identity blacklist authority before sending or receiving, including after awaited account checks. Handlers refresh current socket attachments after authorization so overlapping cosmetics, Digest or other feature requests cannot overwrite subscriptions or current feature state. Independent per-scope request tokens let only the newest subscription intent commit, including a newer opt-out; these temporary tokens do not consume persisted attachment space. Fan-out rechecks current opt-in after recipient authorization. Delivery deadlines are also checked after awaited authorization, including before queued Discord posting. The User Bot requires the global-chat capability in authenticated readiness and accepts delivery results only for the global scope.

Compatibility frames contain display identity, message text, timestamp, source, an internal delivery ID, optional reply context and a recipient-specific `mention` flag. Negotiated rich frames also contain the verified public sender identity, canonical message/source IDs, media descriptors, revisions and reaction state. They never expose account credentials, devices or community session tokens. Messages use literal native chat components; links stay inert until clicked. Optional image/GIF previews use the existing bounded downloader/decoder and approved media hosts. Unsupported, expired or failed media retains its browser fallback.

Discord text, embed titles/descriptions/authors/fields/footers/URLs and attachment links become a bounded text representation. Replies retain their relationship and display context, including replies to Minecraft-originated posts. Uncached same-channel parents are fetched with a bounded timeout after a restart. Own Minecraft-origin embed parents use the original author/body and exclude nested quotes/footer; deleted/unavailable parents show an explicit unavailable preview. This fixes dependence on the Gateway message cache without mutating its shared objects. Discord mention IDs map to the existing account association; Minecraft `@Name` mentions use the verified recipient name. The client plays one subtle sound per unique mentioned message, never for ordinary messages, disabled chat or duplicate delivery.

Discord create, edit and delayed embed events refer to one canonical message. Rich updates replace that native message in place, retaining its sender/reply relationship and never repeating mention sounds. Current Gateway normalization sends a complete snapshot for its legacy delayed-embed adapter; the relay applies it to the existing ID instead of allocating a second `:embeds` message. Older external Gateways that send only an embed fragment can enrich media while retaining the original body before a complete edit; unversioned fragments after a complete edit are ignored because they cannot safely identify the current body or restore removed media. Actual Discord edit timestamps reject older edits while permitting equal-timestamp embed enrichment. Older Minecraft clients receive the original text/link once and do not receive unsupported mutation frames. Update the actual Gateway owner for full current behavior. Other Gateway bots can contribute in the configured global channel. The User Bot's own posts and webhook messages are ignored to prevent relay echoes; legacy room IRC keeps its existing bot policy.

Recent rich messages expose **Reply** and **React** directly in native chat. Reply selects the channel, focuses the existing composer without replacing its draft, and shows a compact sender/body preview with **Cancel**. The send carries the parent's canonical ID; the relay resolves the actual Discord parent ID. React opens a small picker beside the clicked message, with six readable common reactions, counts and the user's selection. Repeating a pending toggle does not enqueue another operation; counts change only on authoritative updates. Escape or clicking outside closes the picker. Command suggestions retain priority. **Edit/Delete** remains available only for the author's recent Minecraft message, within the existing 15-minute backend ownership window.

Unicode text, supplementary characters, skin tones, flags, variation selectors and joined emoji remain intact on the wire and through edits/replies. Truncation/wrapping uses extended grapheme boundaries. Discord custom emoji retain their original name, animation marker and ID. Native chat presents joined/color emoji as readable labels and custom emoji as `:name:` fallbacks; it does not pretend to provide a complete Discord emoji font or animate custom emoji assets.

One media descriptor represents an attachment/GIF across content URLs, attachments and embeds. Signing parameters are ignored only for the identity of numeric Discord attachment URLs; the original signed URL remains the download target. Current Gateway resource IDs stay stable when embed order or signatures change. For a `gifv` embed, its actual supplied GIF/video and original page link travel together. Preview mode renders that resource once; disabling previews leaves one clickable media label. The existing GIF viewer handles supported animation and offers the original page/browser link for unsupported playback.

## Spam and failures

The backend applies account-wide quotas across sockets and relay rooms, a global burst quota, a short minimum interval and repeated-text suppression. In-place embed enrichments use the bounded mutation/deduplication path rather than consuming another initial-message credit. It performs no profanity, political, keyword or other content classification. Limits result in temporary errors/cooldowns, never automatic identity blacklisting.

The Minecraft client tracks at most eight outstanding global send/subscription requests. A missing relay receipt after 12 seconds or a lost connection marks sending as **unconfirmed** and advises checking chat before retrying; it never resends automatically or falls through to Hypixel chat. These receipts confirm backend acceptance, not completed Discord delivery. Failed subscriptions retry after five seconds, suppress repeated notices, and honor local opt-out immediately; stale receipts cannot undo a later preference or affect a different account, endpoint or renewed community session.

Minecraft messages are limited to 1,024 UTF-16 units; normalized Discord messages to 4,096. Presentation avoids splitting extended graphemes and shows a truncation indicator when needed. An indivisible pathological cluster can exceed the preferred line width but remains bounded by the accepted message size. Unsafe formatting/control data is handled as a display-safety boundary, not a word blacklist. A small current-account buffer handles an authenticated update arriving before its canonical initial fan-out; it never creates a message by itself, expires after 15 seconds and clears on account/world boundaries.

Message IDs and spam state survive Durable Object eviction. A bounded temporary outbox lets Discord delivery retry after interruption; it expires within two minutes and caps attempts. It is not a chat history API. The User Bot confirms a Discord message ID after posting; delivery receipts and source metadata prevent retry echoes and loops. Stable Discord nonces provide another duplicate guard within the retry window. Discord's [message API](https://docs.discord.com/developers/resources/message#create-message) supports enforcing nonce uniqueness for recent messages. Ambiguous or exhausted delivery must not be reported as successful Discord delivery.

Body edits and individual emoji toggles retain separate applied progress. A successful newer reaction cannot acknowledge an edit that previously failed; retrying that edit preserves the newer reaction summary. Durable deletion suppresses subsequent mutations. The client similarly merges body, media and reaction snapshots by their own revisions when delivery order changes. A known reply target uses the relay's canonical current preview, including its deleted-message placeholder, rather than stale fetched Discord text.

The Worker shares each Durable Object's existing alarm with authorization deadlines and retry work. Idle chat performs no Minecraft polling or background parsing. WebSocket attachments restore subscriptions after hibernation, following [Cloudflare's hibernating WebSocket model](https://developers.cloudflare.com/durable-objects/best-practices/websockets/).

## Operator configuration

The settings below preserve existing chat infrastructure. The new Node reply/RNG
handlers require updating the process that actually owns the live Gateway; the
separate historical Cloudflare checkout is not updated by building this repo.
See [main-machine deployment/hosting handoff](main-machine-community-rollout.md)
before replacing a host or starting a process. The dedicated RNG channel remains
separate from ordinary IRC; it never publishes a drop as a chat message.

Use the existing backend environment and User Bot application. Keep all bot tokens, bridge secrets and operator credentials in the current secret stores; never put them in a client JAR.

| Setting | Default / purpose |
| --- | --- |
| `GLOBAL_CHAT_CHANNEL_ID` | Dedicated Discord channel in both Worker and User Bot. Worker falls back to its existing `DISCORD_IRC_CHANNEL_ID`. |
| `IRC_ROOMS` (User Bot) | Set empty for global chat only. Legacy room bridges may use separate channels; a channel cannot serve both scopes. |
| `SKY_MYCE_RELAY_URL` | Existing deployed HTTPS/WSS backend origin. |
| `SKY_MYCE_RELAY_AUTH_SECRET` | Existing private bridge secret; matches Worker `DISCORD_BRIDGE_SECRET`. |
| `GLOBAL_CHAT_RECEIPTS_PATH` | Optional Node-hosted User Bot receipt file. The historical Cloudflare Gateway used bounded SQLite receipts in the October 10, 2026 / 2.4.27 deployment. |
| `GLOBAL_CHAT_ENABLED` (Cessna relay) | Recorded as production `true` for October 10, 2026 / 2.4.27; confirm the current live setting before rollout. Other deployments default disabled until explicitly activated. |
| `USER_BOT_TOKEN` (Cessna relay secret) | Existing User Bot application's bot token, stored as a Worker secret. |
| `DISCORD_BRIDGE_SECRET` (Cessna relay secret) | Same private bridge credential as the account backend; never shipped to clients. |
| `GLOBAL_CHAT_CONTROL_SECRET` (Cessna relay secret) | Separate private credential for bounded start/stop/status operations. |
| `GLOBAL_CHAT_BURST` | 6 messages per sender burst. |
| `GLOBAL_CHAT_REFILL_SECONDS` | One sender credit every 2 seconds. |
| `GLOBAL_CHAT_MIN_INTERVAL_MS` | 250 ms between accepted sender messages. |
| `GLOBAL_CHAT_DUPLICATE_MS` | 15,000 ms suppression of identical sender text. |
| `GLOBAL_CHAT_GLOBAL_BURST` | 40 messages per global burst. |
| `GLOBAL_CHAT_GLOBAL_REFILL_MS` | One global credit every 250 ms. |
| `GLOBAL_CHAT_MAX_PENDING` | 256 pending Discord deliveries. |
| `GLOBAL_CHAT_OUTBOX_TTL_MS` | 120,000 ms maximum retry window; cannot be extended beyond two minutes. |
| `GLOBAL_CHAT_MC_MAX_LENGTH` | 1,024 UTF-16 units; operators may lower this protocol ceiling. |
| `GLOBAL_CHAT_DISCORD_MAX_LENGTH` | 4,096 UTF-16 units; operators may lower this protocol ceiling. |

The October 10, 2026 / 2.4.27 deployment report records the production account backend reusing its dedicated IRC channel and its sole User Bot Gateway running in one deterministic Durable Object on the existing `skyblock-relay` Worker, from `D:\Code\Cessna fork\relay`. The Backend/Admin Bot handled administration through the preserved signed HTTP handler, with separate staging channel/credentials. This historical observation does not establish the current hosting state or a 2.5.1 deployment. Confirm which process owns the channel before replacing it; do not start a competing Node bridge. See [Gateway configuration, controls and recovery](<D:/Code/Cessna fork/relay/GLOBAL_CHAT_GATEWAY.md>).

`GET /global-chat/health` reports redacted readiness, pending count and diagnostic categories. `POST /global-chat/control` requires the private control credential and supports only `start`, `stop` and `status`. The persisted running flag and alarms restore connections after eviction. A historical `lastError` may remain populated after both transports recover; it is not the current readiness state. Outbound Discord/backend WebSockets do not support server-side hibernation and contribute to active Durable Object usage. This is documented in [Cloudflare's WebSocket example](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/) and [pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).

Enable the existing User Bot's **Message Content** intent and give it View Channel, Send Messages, Embed Links, Read Message History and **Add Reactions** on the dedicated channel. The runtime requests Guilds, Guild Messages and Guild Message Reactions alongside Message Content; Members and Presence remain unused. Discord needs Message Content for text, embeds and attachments, as described in its [Gateway documentation](https://docs.discord.com/developers/events/gateway#message-content-intent). Account interactions remain on their existing signed HTTP endpoint.

## Historical verification and current rollout

The deployment/test paragraph below describes 2.4.27, not the new 2.5.1 community
session, uncached-parent or RNG pipeline changes. Current synthetic local tests
cover signed community authority and rich global/room/party lifecycle behavior;
real Discord permissions, service rollout and multiplayer rendering still need
the operator checks in the main-machine handoff.

Mod **2.4.27** was published and selected on October 10, 2026, through production `--env backend`. That report records its signed release running in the user's authorized Prism test session and the persistent Cloudflare User Bot Gateway activated on `skyblock-relay`. Live receive failures exposed Gateway Resume ordering, Upgrade-timeout socket lifetime and concurrent backend attachment bugs, which were fixed and covered by workerd regressions for that deployment. The recorded real Minecraft → Discord → Minecraft test passed: the user's `hello248` reply reached the native client with correct reply context and rendered mention. Both transports passed 39 public-health samples over 96 seconds after that backend fix. Sound audibility and long-term uptime remained unverified. These observations do not verify 2.5.1. Exact historical production versions, deployment IDs and retained earlier failures are in the [implementation report](implementation-overhaul-2026-10.md).

```powershell
# Java 25, from the repository root
.\gradlew.bat --offline globalChatCheck
.\gradlew.bat --offline check build

Set-Location relay
npm run check
npm test

Set-Location ../discord-user-bot
npm run check
npm test
```

Local tests use synthetic identities, local Worker storage and mocked Discord delivery. They cover global cross-room delivery, sender authority, opt-in, revocation/expiry (including expiry during authority RPCs), hibernation/restart, spam, duplicate deliveries, outage receipts, capability/scope binding, queued delivery deadlines, subscription recovery, replies, mentions, rich content and client validation. They send no real Discord messages. They do not establish a live rollout.

Deployment and activation remain separate operator actions. The October 10, 2026 / 2.4.27 report recorded an activated production Gateway with a redacted public health endpoint; it does not establish a 2.5.1 rollout. The current local suite covers multiple synthetic authorized users, replies/mentions/embeds, disabled chat, expiry/revocation and restart behavior. A real two-account/cross-location run and the full Discord-rich-content matrix remain separate from the historical single-account private-world smoke test. That older test required no new Discord application, OAuth credential, account enrollment or server permission rewrite. The 2.5.1 browser connection and dedicated RNG feed require the configuration and live checks in the main-machine rollout guide.
