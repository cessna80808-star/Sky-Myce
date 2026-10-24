# Relay and Cloudflare backend notes for agents

Cloudflare Workers APIs and limits change; check the current docs at https://developers.cloudflare.com/workers/ before relying on a binding or limit.

## Workers

- `sky-myce-backend` is the production backend: the `backend` environment in `wrangler.jsonc`, served at `https://sky-myce-backend.skyblock-relay.workers.dev`.
- The root Worker, `skyblock-relay`, is the separate production Discord Gateway. Its source of truth is `D:\Code\Cessna fork\relay`; do not deploy this checkout's root environment over it. The backend calls its `GatewayControl` service binding for global chat.
- Production, staging and Gateway are separate Workers/bindings. Use staging for backend experiments.

## Backend pieces

- `src/index.ts` routes HTTP and WebSocket requests.
- `RelayRoom` and `AccountStore` are Durable Objects for room/session and account state.
- `PRIVATE_ARTIFACTS` is the private R2 bucket for signed release artifacts and manifests. `COSMETIC_ASSETS` stores public cosmetic files.
- `COMMUNITY_RELEASE_MANIFEST_KEY` selects `releases/community-current.json`. `/community/release` returns its signed protocol-4 manifest; `/community/artifact?version=...` serves the matching artifact after validation.

## Checks and deployment

Run `npm run check` and `npm test`; run `npx wrangler types` after changing bindings. For a config/bundle preflight use `npx wrangler deploy --env backend --dry-run --keep-vars --strict`.

Deploy the backend only with `npx wrangler deploy --env backend --keep-vars --strict`. `--keep-vars` preserves dashboard variables; `--strict` fails on conflicting remote changes. Never use bare `wrangler deploy` from this directory: it targets the root `skyblock-relay` Gateway.

## Mod release order

1. Build and check the mod with Java 25: `..\gradlew.bat --offline build check`.
2. Sign the standalone Fabric JAR with `node scripts/release.mjs sign-community ...`. Keep the Ed25519 private key outside the repository; its key ID must match the production pinned public key.
3. Upload `releases/Sky-Myce-<version>.jar` to the production `sky-myce-private-artifacts` bucket.
4. Deploy `sky-myce-backend` if backend code changed.
5. Only then upload the signed manifest to `releases/community-current.json` to activate it.
6. Read back the current manifest and artifact. Verify the Ed25519 signature, version, size and SHA-256 against the JAR before calling the release complete.

Use a new version each time. Keep older JARs and historical Loader pointers. Do not publish signing keys, credentials, runtime data or user records. Do not print secrets or `.wrangler` auth files in logs.
