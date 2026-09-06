# WristBrief

WristBrief is a Wear OS-first RSS + Podcast reader with optional AI-generated briefs.

## Repository layout

- `app/` — Wear OS Android client.
- `gateway/` — Cloudflare Worker-style serverless AI gateway.
- `.github/workflows/ci.yml` — Android and gateway validation.

## Current baseline

The bootstrap implementation provides:

- HTTPS RSS/Atom retrieval and parsing.
- Podcast enclosure discovery and Media3 playback primitive.
- Wear OS Compose entry point.
- HTTPS-only client-to-gateway AI summary client.
- Authenticated serverless summary endpoint.
- OpenAI-compatible upstream provider support through `AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY`.
- Separate `GATEWAY_TOKEN` so provider API keys are never shipped in the APK.
- CI for Android assembly and gateway TypeScript checking.

This is a recovery/bootstrap PR for a repository that previously contained only the initial README. It establishes the minimum architecture required for continued product implementation; it is not yet a finished end-user release.

## Gateway secrets

Configure secrets in the deployment environment rather than committing them:

```bash
cd gateway
npx wrangler secret put AI_API_KEY
npx wrangler secret put GATEWAY_TOKEN
```

Provider configuration lives in `gateway/wrangler.toml` and can be changed to another OpenAI-compatible HTTPS endpoint:

- `AI_BASE_URL`
- `AI_MODEL`

Never commit provider keys or gateway bearer tokens.

## Local validation

Android:

```bash
gradle :app:assembleDebug
```

Gateway:

```bash
cd gateway
npm install
npm run typecheck
```

## Remaining product work

The bootstrap still needs persistent feed/subscription storage, a complete round-screen feed UI, article detail/read-state handling, podcast queue/background controls, settings UI for the deployed gateway, robust feed sanitization, offline caching, tests, and production release configuration.
