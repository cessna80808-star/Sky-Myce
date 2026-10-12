# Sky-Myce User Bot

This independently runnable Gateway handles Discord chat and message operations.
User and administrator account commands use the existing Worker's signed HTTP
interaction endpoints. The Backend Bot remains its separate existing application;
no replacement Backend Gateway is required. The operator tooling and command
schemas live in [discord-bots](../discord-bots/README.md).

The new community architecture installs as a normal Fabric mod. A custom Sky-Myce
Loader, whitelist, activation key and HWID reset are no longer part of user setup.
Choose Connect Discord in Minecraft for browser linking, or submit its one-time
fallback code through `/connect`. See [connection, privacy, reports and blacklist
instructions](../docs/discord-ux.md).

## Run a Gateway

Use Node.js 22.12 or newer:

```powershell
Set-Location discord-user-bot
.\setup.ps1
# Supply the ignored .env or service environment, then:
npm start
```

Setup preserves an existing `.env`. Do not start a second Gateway on the same
production chat channel when a Cloudflare-hosted Gateway is already running.
Use a separate test deployment or deliberate hosting replacement. The Node process
is an alternative chat host, not an account backend or substitute for a missing
signed interaction endpoint.

`npm start` needs `DISCORD_GUILD_ID`, `USER_BOT_APPLICATION_ID` and `USER_BOT_TOKEN`.
With no chat rooms configured it connects to Discord and returns a private
unavailable response to Gateway commands; HTTP commands require the Worker.
For chat, configure the selected rooms/channels, HTTPS/WSS relay origin and the
separate `DISCORD_BRIDGE_SECRET` through the existing supported variables. Keep
exactly one bridge per room. Discord.js handles Gateway reconnects.

The User Gateway never receives the Backend Bot token, OAuth client secret or
administrator operator configuration. Existing public ID/config aliases remain
supported. Credentials belong only in ignored operator files or secret storage;
never print them, commit them or ship them in a mod JAR.

## User functions

`/connect code:...` completes the mod's ten-minute, one-time Minecraft-proof-bound
fallback. `/status` shows the user's own Discord/Minecraft associations privately.
`/account link` uses the same code flow; `/account unlink` reviews removing one
selected association. `/disconnect` reviews ending current community sessions and
retains associated Minecraft accounts, cosmetics and sharing preferences.
`/help` explains normal Fabric installation and Community Sharing settings.

One Discord identity can have multiple Minecraft accounts and use multiple PCs.
Device observations are private administrative metadata and never restrict the
mod. A backend identity blacklist can restrict online services; local gameplay
continues. Community sharing defaults to enabled unless an explicit previous
preference says otherwise. Global/category opt-outs are separate from chat,
cosmetics and Discord identity.

`/cosmetics link`, `set`, `show`, `reset` and `unlink` remain on this User
application. Existing appearance data and verified Minecraft associations are
retained. Prompt/admin commands are never exposed through the User registry.

## Registration and operator configuration

Use separate ignored user and operator configuration files. Never overwrite an
existing operator file during setup:

```powershell
npm run register:user
npm run register:backend
# Review the live inventory and precise migration first; then apply deliberately:
npm run register:user -- --apply
npm run register:backend -- --apply
```

Registration verifies the token/application identity and uses individual
POST/PATCH/DELETE requests. Unknown commands/extensions remain intact. The explicit
retirement manifest removes the obsolete whitelist, activation, Loader approval
and hardware-locking commands and reports each removal; no bulk overwrite occurs.
Deploy the matching community Worker implementation before registering the new
schemas. These scripts are operator tools; no live change is performed merely by
building/testing the project.

Keep the existing application IDs/public keys and signed endpoints:
`/discord/interactions` for User commands and `/discord/backend/interactions` for
Backend commands. User chat runtime requires no OAuth secret. Browser linking uses
the existing User application's `identify` scope, a deployed exact HTTPS callback
`/community/oauth/callback`, and server-only `DISCORD_OAUTH_CLIENT_ID`,
`DISCORD_OAUTH_CLIENT_SECRET`, `DISCORD_OAUTH_REDIRECT_URI` and
`SKYMYCE_PUBLIC_URL`. Missing OAuth configuration leaves the bot-code fallback
available; it does not require administrator approval.

Administrator commands require a persisted backend administrator plus the
configured guild. Discord roles, generic Administrator permission and command
visibility do not grant this authority. `/admin add` uses private confirmation.
Backend reports and identity-blacklist management remain private; hardware hashes
are exposed only in authorized device reports. Prompt-job authorization remains
its separate existing capability.

Keep the established server provisioning and staff-channel permissions. The User
Bot needs View Channel, Send Messages, Read Message History, Embed Links and the
existing chat reaction permissions where used; no Administrator, Manage Server,
Kick or Ban permission is required. Retain only the Gateway intents needed for
chat, including Message Content where configured. Do not add Members/Presence
intents merely for account linking. Review current Discord policy before a live
configuration change.

## Chat boundaries

Optional `DISCORD_RNG_CHANNEL_ID` enables a dedicated supported-drop feed in this
same process. Set the identical channel on the account Worker, keep it separate
from every IRC/global channel, retain the bridge credential and persist
`RNG_FEED_RECEIPTS_PATH` (default `.data/rng-feed-receipts.json`). The feed polls
a signed, bounded global outbox, posts structured unverified reports, deduplicates
retries across restarts and removes bot-owned posts when sharing is withdrawn.
Posts include observed rarity, the actual plain item name and dungeon floor when
the accepted new event supplies them. Names are rendered as inert text; metadata
cannot enable mentions or change anonymous attribution. Older events omit unknown
rarity/floor, and a missing observed name uses the supported item catalog label.
It does not replay Digest history or interfere with chat when unavailable. Its
channel needs View/Send/Embed/Read History; no additional intent or bot is needed.
`/leaderboard` uses the same privacy-filtered persistent query as the mod.
See the [main-machine handoff](../docs/main-machine-community-rollout.md) for exact
Worker variables, permissions, storage bindings and live verification.

Discord reply previews fetch an uncached same-channel parent with a bounded
timeout after a restart. Minecraft-origin embed parents retain their original
sender/body rather than the bot's name or nested quote/footer. Deleted/unavailable
parents have an explicit unavailable preview; native reply IDs remain intact.
This uses the existing Read Message History permission and Discord.js
[fetchReference](https://discord.js.org/docs/packages/discord.js/14.27.0/Message:Class).

The existing bridge keeps bounded text, per-user/global rate limits, replay/receipt
caches, room routing, mentions disabled and no offline message queue. Backend
Minecraft proof, community-session validation and identity-blacklist checks remain
authoritative. Discord-originated echoes are ignored. Logs contain event names and
safe operational IDs rather than packet bodies, tokens, connection codes or HWIDs.
See [global chat](../docs/global-chat.md) for media/operation behavior and host
configuration.

## Verification

`npm run check` checks modules; `npm test` covers routing, identity, mentions,
duplicates, reconnects, registration and staff boundaries. Relay tests verify signed
interactions, private community reports, fallback command scope, confirmations,
blacklists and device-report privacy with local synthetic identities. Live
OAuth/Discord/Cloudflare verification requires deployed code and operator
credentials; a local test pass does not claim successful live linking.
