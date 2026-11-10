# Sky-Myce account and chat relay

This TypeScript Worker uses the existing `AccountStore` and `RelayRoom` Durable
Objects. Sky-Myce is a normal Fabric mod; local features work without a custom
Loader or Discord connection. Online community services verify fresh Minecraft
identity proof, a Discord-linked community session and deliberate identity
blacklists. Browser Discord OAuth is the primary connection flow, with an
expiring one-time code through the existing User Bot as a fallback. Device
observations are optional private administrator metadata and do not restrict
access.

Global chat uses `scope: "global"` on the authenticated Minecraft WebSocket and
the `global-chat` coordinator. The independently supervised User Bot connects
through the signed `/discord/bridge?room=global-chat` endpoint; only the
configured global channel belongs to this bridge. Discord bot projects and
operator documentation are maintained locally outside Git tracking.

Party chat uses roster consent. A rich client (`richChatVersion: 1`) subscribes
with `scope: "party"`, the member UUID list and the leader; the party id is a
digest of that roster, and the sender must be in it. Only clients that
registered the identical roster share a party room. Readiness advertises
`partyChat: true` and `partyChatVersion: 1` to connected accounts. The backend
does not verify Hypixel party membership itself. Legacy `irc_send` packets with
`scope: "party"` are refused with `party_membership_unverified`, never
delivered to a room or to global chat. Party Finder `party_set`/`party_get`
policies do not establish membership.

From this directory, run the locked dependencies and existing checks:

```powershell
npm ci
npm run check
npm test
```

`npm test` packages an isolated dry-run bundle, runs local Worker/SQLite tests,
and checks release tooling. Its synthetic identities and mocked Discord calls
do not prove live delivery. Keep credentials in the established secret stores
and ignored local environment files.

Production is `sky-myce-backend`, selected with `--env backend`. The default
Worker name can collide with the older relay, so never deploy without the
explicit intended environment. Preserve bindings, secrets and persisted state.
Upload the standalone Fabric JAR privately before publishing its signed manifest,
then verify the live signature and artifact hash. Retain previous releases.
Deployment and persistent User Bot startup are separate operations.

Bazaar protocol, provider-policy review, immutable local-calendar attribution, replay
privacy, Discord controls, and measured storage costs are documented in [BAZAAR.md](BAZAAR.md).
The Bazaar feature requires separate authorization before deployment or Discord command registration.
