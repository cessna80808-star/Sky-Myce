# Sky-Myce account and chat relay

This TypeScript Worker uses the existing `AccountStore` and `RelayRoom` Durable
Objects. Sky-Myce is a normal Fabric mod; local features work without a custom
Loader or Discord connection. Online community services verify fresh Minecraft
identity proof, a Discord-linked community session and deliberate identity
blacklists. Browser Discord OAuth is the primary connection flow, with an
expiring one-time code through the existing User Bot as a fallback. Device
observations are optional private administrator metadata and do not restrict
access. See [community architecture](../docs/community-architecture.md).

Global chat uses `scope: "global"` on the authenticated Minecraft WebSocket and
the `global-chat` coordinator. The independently supervised User Bot connects
through the signed `/discord/bridge?room=global-chat` endpoint; only the
configured global channel belongs to this bridge. See
[global chat](../docs/global-chat.md) and the
[User Bot guide](../discord-user-bot/README.md).

Current Hypixel party membership cannot be verified by this backend. Readiness
therefore advertises `partyChat: false` and
`partyChatError: "party_membership_unverified"`. Party chat requests receive an
explicit party-scoped rejection, never legacy room or global delivery. Existing
Party Finder `party_set`/`party_get` policies do not establish membership. See
[the party protocol and precise blocker](../docs/party-chat.md).

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
explicit intended environment. Preserve bindings, secrets and persisted state;
follow [the main-machine rollout](../docs/main-machine-community-rollout.md) for
OAuth configuration, private cape storage, reviewed deployment, standalone Fabric
release publication and live verification. The [older deployment record](../docs/backend-deployment.md)
describes the historical license/Loader flow and is not the current rollout guide.
Deployment and persistent User Bot startup are separate operations.
