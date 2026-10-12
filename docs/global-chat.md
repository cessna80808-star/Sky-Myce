# Global SkyMyce chat

Global chat extends the authenticated Minecraft relay and the existing User Bot application. It does not create another Minecraft WebSocket, license service or Discord bot. Clients opt in under **Utils → Global SkyMyce Chat**; explicitly selecting `/sm irc` or sending `/sm irc <message>` also enables receiving. `/sm irc` directs ordinary chat input to global IRC, while the message form sends once without changing the selected mode. `/sm chat` returns to normal chat while receiving remains enabled. `/sm global` and `/chat sky` remain compatibility aliases. Incoming global messages do not require selecting the channel. Turning the feature off hides messages and mention sounds without invalidating SkyMyce authorization.

Every opted-in authorized participant in one Worker deployment receives the same channel, including players in different relay rooms, worlds, lobbies, parties, dungeons and servers. Staging and production deployments remain separate. Existing private messages, account operations and room-local IRC remain compatible.

Party chat is a separate scope and currently fails closed because the backend cannot verify Hypixel membership. It never enters this global channel or Discord; see [the party capability contract and unresolved authority dependency](party-chat.md).

## Transport and identity

The existing `irc_subscribe`, `irc_send`, `irc_message` and `irc_result` frames gain `scope: "global"`. Authenticated readiness advertises `globalChat`. The existing `RelayRoom` namespace uses the deterministic `global-chat` instance to coordinate quotas, deduplication, Discord delivery and broadcasts to configured relay rooms through internal RPC. Each receiving room validates current account authorization before sending to a socket; disconnected, expired, revoked or unsubscribed sessions receive nothing.

Minecraft names, UUIDs and Discord linkage come from the current verified community session. Clients cannot choose their sender identity. Discord messages arrive through the User Bot's existing signed bridge at `/discord/bridge?room=global-chat`. The bridge accepts only the configured channel. Discord authors follow that channel's ordinary server permissions. Local mod gameplay and Discord channel participation need no Minecraft license.

The client accepts global messages only from its current account, relay endpoint and community session. The relay checks session expiry/generation and identity blacklist authority before sending or receiving, including after awaited account checks. Handlers refresh current socket attachments after authorization so overlapping cosmetics, Digest or other feature requests cannot overwrite subscriptions or current feature state. Independent per-scope request tokens let only the newest subscription intent commit, including a newer opt-out; these temporary tokens do not consume persisted attachment space. Fan-out rechecks current opt-in after recipient authorization. Delivery deadlines are also checked after awaited authorization, including before queued Discord posting. The User Bot requires the global-chat capability in authenticated readiness and accepts delivery results only for the global scope.

Normal Minecraft frames contain display identity, message text, timestamp, source, an internal delivery ID, optional reply context and a recipient-specific `mention` flag. Other participants' raw Discord IDs, Minecraft UUIDs, license IDs, devices and session credentials are not included. Private bot/backend frames retain the identifiers needed for correlation. Messages render as literal chat components with the Sky-Hawk palette. Only safe HTTP(S) links become clickable; no attachments are downloaded.

Discord text, embed titles/descriptions/authors/fields/footers/URLs and attachment links become a bounded text representation. Replies retain their relationship and display context, including replies to Minecraft-originated posts. Uncached same-channel parents are fetched with a bounded timeout after a restart. Own Minecraft-origin embed parents use the original author/body and exclude nested quotes/footer; deleted/unavailable parents show an explicit unavailable preview. This fixes dependence on the Gateway message cache without mutating its shared objects. Discord mention IDs map to the existing account association; Minecraft `@Name` mentions use the verified recipient name. The client plays one subtle sound per unique mentioned message, never for ordinary messages, disabled chat or duplicate delivery.

When Discord adds link previews after a cached message's initial delivery, its first embed update becomes one separate, deduplicated continuation. It carries only the embed content, without repeating the message body or mention sound; arbitrary edits do not resend messages. Other Gateway bots can contribute in the configured global channel. The User Bot's own posts and webhook messages are ignored to prevent relay echoes; legacy room IRC keeps its existing bot policy.

## Spam and failures

The backend applies account-wide quotas across sockets and relay rooms, a global burst quota, a short minimum interval and repeated-text suppression. One accepted message's later embed continuation consumes the global quota without charging its sender a second message credit. It performs no profanity, political, keyword or other content classification. Limits result in temporary errors/cooldowns, never automatic identity blacklisting.

The Minecraft client tracks at most eight outstanding global send/subscription requests. A missing relay receipt after 12 seconds or a lost connection marks sending as **unconfirmed** and advises checking chat before retrying; it never resends automatically or falls through to Hypixel chat. These receipts confirm backend acceptance, not completed Discord delivery. Failed subscriptions retry after five seconds, suppress repeated notices, and honor local opt-out immediately; stale receipts cannot undo a later preference or affect a different account, endpoint or renewed community session.

Minecraft messages are limited to 1,024 UTF-16 units; normalized Discord messages to 4,096. Minecraft presentation splits long text into bounded lines without separating surrogate pairs, with a visible truncation indicator when necessary. Unsafe formatting/control data is handled as a display-safety boundary, not a word blacklist.

Message IDs and spam state survive Durable Object eviction. A bounded temporary outbox lets Discord delivery retry after interruption; it expires within two minutes and caps attempts. It is not a chat history API. The User Bot confirms a Discord message ID after posting; delivery receipts and source metadata prevent retry echoes and loops. Stable Discord nonces provide another duplicate guard within the retry window. Discord's [message API](https://docs.discord.com/developers/resources/message#create-message) supports enforcing nonce uniqueness for recent messages. Ambiguous or exhausted delivery must not be reported as successful Discord delivery.

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

The October 10, 2026 / 2.4.27 deployment report records the production account backend reusing its dedicated IRC channel and its sole User Bot Gateway running in one deterministic Durable Object on the existing `skyblock-relay` Worker, from `D:\Code\Cessna fork\relay`. The Backend/Admin Bot handled administration through the preserved signed HTTP handler, with separate staging channel/credentials. This historical observation does not establish the current hosting state or a 2.5.0 deployment. Confirm which process owns the channel before replacing it; do not start a competing Node bridge. See [Gateway configuration, controls and recovery](<D:/Code/Cessna fork/relay/GLOBAL_CHAT_GATEWAY.md>).

`GET /global-chat/health` reports redacted readiness, pending count and diagnostic categories. `POST /global-chat/control` requires the private control credential and supports only `start`, `stop` and `status`. The persisted running flag and alarms restore connections after eviction. A historical `lastError` may remain populated after both transports recover; it is not the current readiness state. Outbound Discord/backend WebSockets do not support server-side hibernation and contribute to active Durable Object usage. This is documented in [Cloudflare's WebSocket example](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/) and [pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).

Enable the existing User Bot's **Message Content** intent and give it View Channel, Send Messages, Embed Links and Read Message History on the dedicated channel. Discord needs that intent for text, embeds and attachments, as described in its [Gateway documentation](https://docs.discord.com/developers/events/gateway#message-content-intent). Account interactions remain on their existing signed HTTP endpoint.

## Historical verification and current rollout

The deployment/test paragraph below describes 2.4.27, not the new 2.5.0 community
session, uncached-parent or RNG pipeline changes. Current synthetic local tests
cover signed community authority and rich global/room/party lifecycle behavior;
real Discord permissions, service rollout and multiplayer rendering still need
the operator checks in the main-machine handoff.

Mod **2.4.27** was published and selected on October 10, 2026, through production `--env backend`. That report records its signed release running in the user's authorized Prism test session and the persistent Cloudflare User Bot Gateway activated on `skyblock-relay`. Live receive failures exposed Gateway Resume ordering, Upgrade-timeout socket lifetime and concurrent backend attachment bugs, which were fixed and covered by workerd regressions for that deployment. The recorded real Minecraft → Discord → Minecraft test passed: the user's `hello248` reply reached the native client with correct reply context and rendered mention. Both transports passed 39 public-health samples over 96 seconds after that backend fix. Sound audibility and long-term uptime remained unverified. These observations do not verify 2.5.0. Exact historical production versions, deployment IDs and retained earlier failures are in the [implementation report](implementation-overhaul-2026-10.md).

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

Deployment and activation remain separate operator actions. The October 10, 2026 / 2.4.27 report recorded an activated production Gateway with a redacted public health endpoint; it does not establish a 2.5.0 rollout. The current local suite covers multiple synthetic authorized users, replies/mentions/embeds, disabled chat, expiry/revocation and restart behavior. A real two-account/cross-location run and the full Discord-rich-content matrix remain separate from the historical single-account private-world smoke test. That older test required no new Discord application, OAuth credential, account enrollment or server permission rewrite. The 2.5.0 browser connection and dedicated RNG feed require the configuration and live checks in the main-machine rollout guide.
