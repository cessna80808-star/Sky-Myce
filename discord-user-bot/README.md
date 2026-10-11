# Sky-Myce User Bot

The independently runnable User Bot lives here. Node handles Discord Gateway messages; account commands use the existing Sky-Myce Worker's signed HTTP interaction endpoint. The Backend Bot remains in `D:\Code\Cessna fork\relay`. Architecture: [`docs/secure-system.md`](../docs/secure-system.md). Current deployment facts: [implementation report](../docs/discord-implementation-report.md).

The account Worker and private R2 delivery are deployed. The User application's signed HTTP endpoint remains `https://sky-myce-backend.skyblock-relay.workers.dev/discord/interactions`. Since October 10, 2026, the persistent global-chat Gateway runs on Cloudflare's `skyblock-relay` Worker from `D:\Code\Cessna fork\relay`; this computer does not need a Node process running. `IRC_ROOMS=` remains empty. See [global chat](../docs/global-chat.md) and [deployment verification](../docs/backend-deployment.md).

Production uses exactly one Gateway for the configured global channel. Do not start this Node bridge against that same channel/backend while the Cloudflare Gateway is running. The Node implementation below remains available for an explicitly separate test deployment or a deliberate hosting replacement.

## Prepare and run

Use Node.js 22.12 or newer and PowerShell:

```powershell
Set-Location 'D:\Code\Sky-Myce\discord-user-bot'
.\setup.ps1
# Edit the ignored .env or supply values through the service environment.
npm start
```

Setup installs locked dependencies and runs checks/tests; it preserves an existing `.env`. `npm start` needs only `DISCORD_GUILD_ID`, `USER_BOT_APPLICATION_ID`, and `USER_BOT_TOKEN` when `IRC_ROOMS=` is empty. That mode connects to Discord and answers Gateway slash commands with an ephemeral service-unavailable response. It does not supply simulated account data. Stop with Ctrl+C. Run one supervised instance, with restart on process failure; Discord.js manages Gateway reconnections.

For IRC, set `IRC_ROOMS=testing` first, its channel ID, `SKY_MYCE_RELAY_URL` to the deployed HTTPS/WSS origin, and `SKY_MYCE_RELAY_AUTH_SECRET` to the Worker's separate `DISCORD_BRIDGE_SECRET`. After staging verification, production uses `IRC_ROOMS=friends,testing`; `friends` maps only to `DISCORD_IRC_CHANNEL_ID`, and `testing` only to `DISCORD_IRC_TEST_CHANNEL_ID`. The backend must include this change's protocol-3 room metadata and single-bridge guard. Empty relay configuration never grants access. Do not use the legacy permissive relay endpoint.

The User Bot process must never receive the Backend Bot token or administrative backend credentials. Existing `DISCORD_USER_APPLICATION_ID`, `RELAY_URL`, and `DISCORD_BRIDGE_SECRET` aliases remain accepted for compatibility. The bot token's name is `USER_BOT_TOKEN`. Example files contain no real credentials.

Global SkyMyce chat extends this same frontend bridge. Set `GLOBAL_CHAT_CHANNEL_ID` to the dedicated channel and `IRC_ROOMS=` for global chat only; Minecraft participants keep their existing authenticated relay connection. The Worker must use the same channel ID and have the global-chat implementation deployed. The local receipt file contains bounded delivery IDs, not credentials or chat text; preserve it across supervised process restarts. See [global chat configuration, behavior and verification](../docs/global-chat.md). The existing room-local IRC settings below remain available for compatibility on separate channels.

## Account operations

Start with `/status`, `/key`, `/loader` or `/help`. These commands act on your
own account and respond privately. `/status` explains your license, linked
Minecraft accounts, registered device and access. `/key` privately delivers an
eligible activation key; the backend decides whether a replacement is allowed
and applies its limits. `/loader` provides the authorized current loader with
its Minecraft/version details. `/help` explains setup and the next step.

Keep the existing `/account status`, `/account approve code`, `/account link code`, `/account unlink uuid confirm`, `/account reset confirm`, and `/hwid reset confirm` commands registered. All act on the authenticated Discord caller. Bare `/account` is not an operation. UUID selection can remove only an already-owned association; it cannot claim another Minecraft identity. Existing cosmetics commands remain available. See [the Discord command guide](../docs/discord-ux.md) for user and administrator workflows.

The existing architecture proves Minecraft identity in the authenticated loader/game session, issues a five-minute server-stored challenge, and binds its consumption to the intended Discord identity. `/account link` submits that private challenge; it does not start a speculative OAuth flow. `/account approve` approves loader enrollment. The device is enrolled once and remains registered through Minecraft/loader restarts, OS reboot/sleep/login, reconnects, lease renewal, network changes and supported updates. Linux uses the same persistent binding as Windows; normal use needs no reset.

User `/hwid reset confirm:true` and the retained `/account reset confirm:true` prepare an intentional hardware transfer and privately deliver a 24-hour activation code. Preparation/reissue leaves the old binding, active leases and transfer allowance unchanged. A successful authenticated activation on a different physical device consumes the seven-day allowance, replaces the binding and invalidates the old device and leases; the first enrollment establishes the initial seven-day boundary. Matching-device activation never consumes a transfer. A failed DM can be retried by enabling DMs and repeating the preparation command. When no device is bound, the same commands issue an enrollment code. Administrator `/hwid reset` remains an explicit, immediate, audited security invalidation and preserves the self-service transfer timestamp. `/account status` shows the registered device without a routine reset countdown.

Configure the User application's HTTP endpoint only after the real Worker is ready: `https://<DEPLOYED-SKY-MYCE-ORIGIN>/discord/interactions`. Discord sends interactions over HTTP instead of Gateway when that endpoint is set. The Worker verifies the application's public key against the original body before trusting identity, immediately defers slow commands, and edits ephemeral replies. Missing backend capability returns `This Sky-Myce service is currently unavailable.`

## Cosmetics

The User Bot command set includes `/cosmetics link code`, `/cosmetics set name x y z`, `/cosmetics show`, `/cosmetics reset`, and `/cosmetics unlink confirm`. Existing option names and limits are preserved: names accept up to 2,048 characters of validated text-component input and 32 visible characters; each independent size axis accepts 0.1–3.0. Omitted axes stay unchanged. Obtain link challenges inside Minecraft with `/sm cosmetics link`; never enter another player's UUID to claim ownership.

Cosmetics commands use the frontend application's signed HTTP endpoint at `https://sky-myce-backend.skyblock-relay.workers.dev/discord/interactions`, independently of the optional Gateway IRC process. The Gateway fallback returns a private service-unavailable response if the HTTP endpoint is missing; it does not execute cosmetic operations through an unsigned API. Strict account ownership applies to cosmetics: `/cosmetics unlink` explains the account unlink/reset operations instead of silently removing the wider Sky-Myce identity.

The migration imports existing appearance and ownership records into matching backend rooms without granting licenses or replacing existing destination records. Cutover registers the frontend command first, verifies the handler and data, then disables the old Backend Bot handler and removes only its exact cosmetics command ID. Registration tools never implicitly delete the old command or bulk-overwrite either application's command set. Current live cutover and verification results belong in the [implementation report](../docs/discord-implementation-report.md).

## Guild registration and server setup

```powershell
# Uses user-only .env. GET inventory and print the diff first:
npm run register:user
npm run register:user -- --apply

# Separate operator-only configuration:
Copy-Item ..\discord-bots\.env.backend.example .env.backend
# Populate application/guild IDs and BACKEND_BOT_TOKEN in the ignored file.
npm run register:backend
npm run register:backend -- --apply

Copy-Item .env.setup.example .env.setup
# Populate existing object IDs and operator token; never load this into npm start.
npm run setup:server
npm run setup:server -- --apply
```

Never overwrite an existing operator file when repeating setup. Alternatively, registration/provisioning support an operator-only `SKY_MYCE_DISCORD_CONFIG_PATH` pointing to a local `KEY=value` file. Do not set that variable for the Gateway runtime. No script displays credential values.

Registration checks token/application identity, GETs existing guild commands, and uses individual POST/PATCH operations. Unknown commands and unknown subcommands are retained. The report includes before/after definitions, unchanged/created/updated commands and an explicit empty removal list. No global registration or bulk overwrite is used. Removing a migrated command remains a separate, deliberate operation after parity verification.

Provisioning reuses explicit IDs or equivalent names, rejects ambiguous names, and changes only selected parents and overwrites. It does not delete roles/channels. The setup operator needs Manage Channels, Manage Roles, and View Channel on every target channel; Discord may reject edits even with the management permissions when View Channel is denied. Preview again after any partial failure. Remove temporary management permissions afterward. A bot may be unable to edit its own highest managed role; in that case the server owner must remove them manually.

Staff overwrites deny ordinary users and the User Bot, and allow the configured staff role/users and Backend Bot. Discord administrators and the server owner intrinsically bypass channel restrictions; they still do not bypass Sky-Myce's persisted backend administrator check. Staff channel access and backend command authorization are separate. Existing synchronized children inherit category changes, so review the preview and existing children before applying.

Backend commands require a persisted Sky-Myce administrator account and the configured guild. Discord roles, generic Administrator permission, and the old developer allowlist do not grant backend command access. Discord account `978376257534328883` is the initial administrator. An existing administrator adds another with Backend Bot `/admin add user:@person`; this command never belongs to the frontend application. Granting backend administrator access is separate from activation and ordinary-user licensing.

## Bridge limits and failure behavior

| Limit | Default |
| --- | --- |
| Text in either direction | 256 UTF-16 code units; reject oversize/empty/control/bidi/format-code input |
| Per Discord caller | Burst 4; replenish one message per 3 seconds |
| Global bridge rate | Burst 20; replenish one message per second, shared across configured rooms/directions |
| Bot duplicate caches | 1,024 IDs per direction per room, five-minute TTL |
| Backend persisted duplicate cache | 4,096 IDs per room, five-minute TTL |
| WebSocket payload / buffered sends | 8 KiB / 16 KiB |
| Pending delivery results | At most 128; ten-second deadline |
| Reconnect | Exponential 1–60 seconds plus bounded jitter, maximum 60 seconds |
| Stable connection | Reset backoff after a pong at least 30 seconds after connection |

See `.env.example` for configurable rate/text/cache limits. Backend room settings use established `IRC_BURST` / `IRC_REFILL_SECONDS` names for the per-caller quota; its global/cache settings match the new names in `relay/.dev.vars.example`. The backend performs linked-account, activation, license and IRC authorization on the authenticated bridge packet before any Minecraft delivery. The Node process does not maintain substitute account state.

Attachments are never forwarded. Message IDs/source/room prevent loops; the server accepts one active bridge per room. Discord-originated echoes are ignored. Minecraft text renders as `[MC] Name » message` with mention parsing disabled; Minecraft clients label Discord authors `[DC]`. Outages drop messages with a bounded failure notice; there is no offline queue or success claim. Deduplication caches and rate maps are bounded. Logs contain event names/IDs, never packet bodies, keys or tokens.

## Developer Portal and deployment checklist

1. Open the **existing** Backend application and new User application in [Discord Developer Portal](https://discord.com/developers/applications). Record each General Information → Application ID/Public Key in its own environment. Use the existing tokens in secret storage; do not regenerate them as a setup step. `BACKEND_BOT_CLIENT_ID` / `USER_BOT_CLIENT_ID` refer to the respective application IDs when installation/OAuth tooling needs them.
2. User application → Bot → Privileged Gateway Intents: enable Message Content only; leave Server Members and Presence off. Check the privileged-intent review status separately. As checked September 27, 2026, review starts at **10,000 unique users with access across installed servers**, with 90 days to apply; previously approved access requires annual reapplication. Check current policy before deployment. [Discord intent policy](https://support-dev.discord.com/hc/en-us/articles/6207308062871-What-are-Privileged-Intents), [annual review policy](https://support-dev.discord.com/hc/en-us/articles/40281523410967-Changes-to-Privileged-Intent-Access-for-Discord-Apps).
3. Installation → Guild Install: scopes `bot` and `applications.commands`; User permissions View Channel, Send Messages, Read Message History, Embed Links. Backend uses those where needed in staff channels and Attach Files only when sending the implemented loader attachment. No Administrator, Manage Server, Kick or Ban permissions. Management permissions are temporary provisioning permissions only. Both applications are already installed in the supplied guild; avoid duplicate installs.
4. Retain the Backend's current signed endpoint for administrative commands. Deploy reviewed hardening from its original repository; set its `DISCORD_GUILD_ID` and application ID/public key. Configure `SKY_MYCE_BACKEND_URL` to the separate real Sky-Myce backend; the legacy bot forwards the original signed body to `/discord/backend/interactions`, which verifies it again and checks the persisted administrator account. Do not point this URL back at the legacy Worker. Cosmetics belong to the frontend HTTP endpoint described above.
5. For the real account Worker, put `USER_BOT_APPLICATION_ID`, `USER_BOT_PUBLIC_KEY`, `BACKEND_BOT_APPLICATION_ID`, `BACKEND_BOT_PUBLIC_KEY`, `BACKEND_BOT_TOKEN`, and `USER_BOT_TOKEN` in its environment/secret system. Existing `DISCORD_USER_*` / `DISCORD_BACKEND_*` application/public-key aliases and `DISCORD_BACKEND_BOT_TOKEN` remain supported; choose one set. Supply the architecture's account/relay bindings, private loader object and signing configuration. Then set User → General Information → Interactions Endpoint URL to the real User endpoint and verify Discord's signed PING acceptance. The new Gateway process needs no signing public key or OAuth secret.
6. Server Settings → Integrations → Backend application → Manage: allow registered Sky-Myce administrators to see/invoke privileged commands, including `/admin add`. Update this visibility configuration when adding an administrator if needed. Discord command permissions are defense-in-depth; the persisted backend administrator check still decides access. Frontend `/cosmetics` remains a normal-user command. Role/user-specific command-permission API changes require an authorized OAuth Bearer token; bot tokens are insufficient, so no automatic visibility update is claimed.
7. OAuth2 → Redirects and `USER_BOT_CLIENT_SECRET` / `DISCORD_OAUTH_REDIRECT_URI` are **not required** by the implemented signed-challenge flow. If an approved architecture change adds identity OAuth, choose the real redirect URI, store its secret outside source, request only `identify`, and implement/validate that feature before enabling it. Do not configure invented callbacks.
8. After staging passes, start exactly one Gateway service. Verify private command replies, real activation/blocked-DM behavior and IRC with test accounts. Only then enable `friends`. Migrate `/cosmetics` only after preserving appearance/ownership state and verifying live parity; stop the old handler and delete only its exact old command ID at cutover. The legacy Backend has no Discord IRC listener to disable.

The main repository's default Worker name is the same as the existing production Worker. Do not run an unreviewed default deployment from `D:\Code\Sky-Myce\relay`: it would roll out the wider account/DRM changes. Use the established isolated staging deployment first; this Discord task does not replace the wider architecture.

## Verification

`npm run check` checks project/module syntax; JavaScript uses the repository's existing `.mjs` convention. `npm test` checks configuration, identity, routing, mentions, duplicate bounds, reconnects, registration and staff permission calculations. `relay` tests exercise persistent account state, signatures, challenge replay, reset concurrency and authorise delivery using local test identities. See the implementation report for tests actually run and live checks still pending.
