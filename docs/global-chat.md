# Global SkyMyce chat

Global chat extends the authenticated Minecraft relay and the existing User Bot. It does not create another Minecraft WebSocket, license service or Discord bot. Clients opt in under **General → Global SkyMyce Chat**, then use `/sm global <message>` to send. Selecting `/sm global` or `/chat sky` directs ordinary chat input to this channel; normal Hypixel chat remains available outside that explicit selection. Incoming global messages do not require selecting the channel. Turning the feature off hides messages and mention sounds without invalidating SkyMyce authorization.

Every opted-in authorized participant in one Worker deployment receives the same channel, including players in different relay rooms, worlds, lobbies, parties, dungeons and servers. Staging and production deployments remain separate. Existing private messages, account operations and room-local IRC remain compatible.

## Transport and identity

The existing `irc_subscribe`, `irc_send`, `irc_message` and `irc_result` frames gain `scope: "global"`. Authenticated readiness advertises `globalChat`. The existing `RelayRoom` namespace uses the deterministic `global-chat` instance to coordinate quotas, deduplication, Discord delivery and broadcasts to configured relay rooms through internal RPC. Each receiving room validates current account authorization before sending to a socket; disconnected, expired, revoked or unsubscribed sessions receive nothing.

Minecraft names, UUIDs and Discord linkage come from the current verified installation session. Clients cannot choose their sender identity. Discord messages arrive through the User Bot's existing signed bridge at `/discord/bridge?room=global-chat`. The bridge accepts only the configured channel. Discord authors follow that channel's ordinary server permissions; they do not need a Minecraft license to post in Discord.

Normal Minecraft frames contain display identity, message text, timestamp, source, an internal delivery ID, optional reply context and a recipient-specific `mention` flag. Other participants' raw Discord IDs, Minecraft UUIDs, license IDs, devices and session credentials are not included. Private bot/backend frames retain the identifiers needed for correlation. Messages render as literal chat components with the Sky-Hawk palette. Only safe HTTP(S) links become clickable; no attachments are downloaded.

Discord text, embed titles/descriptions/authors/fields/footers/URLs and attachment links become a bounded text representation. Replies retain their relationship and display context, including replies to Minecraft-originated posts. Discord mention IDs map to the existing account association; Minecraft `@Name` mentions use the verified recipient name. The client plays one subtle sound per unique mentioned message, never for ordinary messages, disabled chat or duplicate delivery.

When Discord adds link previews after a cached message's initial delivery, its first embed update becomes one separate, deduplicated continuation. It carries only the embed content, without repeating the message body or mention sound; arbitrary edits do not resend messages. Other Gateway bots can contribute in the configured global channel. The User Bot's own posts and webhook messages are ignored to prevent relay echoes; legacy room IRC keeps its existing bot policy.

## Spam and failures

The backend applies account-wide quotas across sockets and relay rooms, a global burst quota, a short minimum interval and repeated-text suppression. One accepted message's later embed continuation consumes the global quota without charging its sender a second message credit. It performs no profanity, political, keyword or other content classification. Limits result in temporary errors/cooldowns, never automatic license punishment.

Minecraft messages are limited to 1,024 UTF-16 units; normalized Discord messages to 4,096. Minecraft presentation splits long text into bounded lines without separating surrogate pairs, with a visible truncation indicator when necessary. Unsafe formatting/control data is handled as a display-safety boundary, not a word blacklist.

Message IDs and spam state survive Durable Object eviction. A bounded temporary outbox lets Discord delivery retry after interruption; it expires within two minutes and caps attempts. It is not a chat history API. The User Bot confirms a Discord message ID after posting; delivery receipts and source metadata prevent retry echoes and loops. Stable Discord nonces provide another duplicate guard within the retry window. Discord's [message API](https://docs.discord.com/developers/resources/message#create-message) supports enforcing nonce uniqueness for recent messages. Ambiguous or exhausted delivery must not be reported as successful Discord delivery.

The Worker shares each Durable Object's existing alarm with authorization deadlines and retry work. Idle chat performs no Minecraft polling or background parsing. WebSocket attachments restore subscriptions after hibernation, following [Cloudflare's hibernating WebSocket model](https://developers.cloudflare.com/durable-objects/best-practices/websockets/).

## Operator configuration

Use the existing backend environment and User Bot application. Keep all bot tokens, bridge secrets and operator credentials in the current secret stores; never put them in a client JAR.

| Setting | Default / purpose |
| --- | --- |
| `GLOBAL_CHAT_CHANNEL_ID` | Dedicated Discord channel in both Worker and User Bot. Worker falls back to its existing `DISCORD_IRC_CHANNEL_ID`. |
| `IRC_ROOMS` (User Bot) | Set empty for global chat only. Legacy room bridges may use separate channels; a channel cannot serve both scopes. |
| `SKY_MYCE_RELAY_URL` | Existing deployed HTTPS/WSS backend origin. |
| `SKY_MYCE_RELAY_AUTH_SECRET` | Existing private bridge secret; matches Worker `DISCORD_BRIDGE_SECRET`. |
| `GLOBAL_CHAT_RECEIPTS_PATH` | User Bot `.data/global-chat-receipts.json`; retain on a private persistent service volume. Contains short-lived bounded IDs only. |
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

The checked-in backend configuration reuses its existing dedicated IRC channel. Staging operators supply their own channel through the staging configuration; do not copy production credentials or connect staging to the production channel. Run exactly one supervised User Bot service for a configured global channel. The Backend/Admin Bot continues to handle administration.

Enable the existing User Bot's **Message Content** intent and give it View Channel, Send Messages, Embed Links and Read Message History on the dedicated channel. Discord needs that intent for text, embeds and attachments, as described in its [Gateway documentation](https://docs.discord.com/developers/events/gateway#message-content-intent). Account interactions remain on their existing signed HTTP endpoint.

## Verification and rollout

Mod 2.4.13 and loader 1.0.13 were published to private R2 on October 2, 2026.
The backend handlers are deployed on Worker version
`b1410753-f299-4902-ad2e-4e9601ee84c0`. The separate User Bot Gateway service
still needs its runtime rollout; live Minecraft/Discord chat remains unverified.

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

Local tests use synthetic identities, local Worker storage and mocked Discord delivery. They cover global cross-room delivery, sender authority, opt-in, revocation/expiry, hibernation/restart, spam, duplicate deliveries, outage receipts, replies, mentions, rich content and client validation. They send no real Discord messages. They do not establish a live rollout.

Deployment and starting the Gateway service are separate operator actions. After rolling out the reviewed Worker and compatible client, configure and start the existing User Bot with the same dedicated channel. Verify two authorized Minecraft users in different locations, Discord replies/mentions/embeds, disabled chat and revoked access before treating live chat as verified. No new Discord application, OAuth credential, account enrollment or server permission rewrite is required.
