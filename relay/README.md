# Sky-Myce account and chat relay

This TypeScript Worker uses the existing `AccountStore` and `RelayRoom` Durable
Objects. Minecraft connections prove their Mojang identity and installation
authorization before protected requests. Account, licensing, loader and Discord
administration remain owned by their existing handlers.

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
follow [the deployment record](../docs/backend-deployment.md) for reviewed
deployment, private artifact upload, signed manifest activation and verification.
Deployment and persistent User Bot startup are separate operations.
