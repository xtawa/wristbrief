# WristBrief

WristBrief is a Wear OS-first RSS + Podcast reader inbox with calm AI summaries and a companion Android phone application.

## Repository layout

- `app/` — Wear OS Android client (Material 3 Expressive, Tiles, Complications, rotary input, offline playback).
- `mobile/` — Android phone companion client (feed management, OPML, reader, Media3 podcast player, account, Play Billing).
- `gateway/` — Cloudflare Worker AI gateway, D1 membership & Play Billing verification backend.
- `.github/workflows/ci.yml` — Android unit/instrumentation, gateway Vitest, and release-guard CI checks.
- `docs/` — Architecture, billing, security, roadmap, and execution status tracking.

## Execution Status & Single Source of Truth

The authoritative, verified progress of WristBrief across all product batches (Batch 0 through Batch 8) is tracked in:

👉 [`docs/EXECUTION_STATUS.md`](docs/EXECUTION_STATUS.md)

This tracks code existence, local unit tests, CI test evidence, device verification status, and production configuration boundaries.

## Architecture Highlights

1. **Wear OS First**: Designed for fast glanceable decisions on wrist, rotary crown scrolling, round screen padding, and low-power operations.
2. **Companion Phone Workspace**: Provides comprehensive feed subscription management, OPML import/export, sanitized article reading, Media3 audio background playback, Google sign-in, and Google Play subscription flows.
3. **Canonical Data Layer Sync**: Bidirectional sync between phone and watch for feed subscriptions, read/saved states, playback progress, and scoped revocable session tokens.
4. **Serverless Gateway & Play Verification**: Google Play subscription server verification via Google Android Publisher APIs, RTDN push processing with OIDC validation, D1-backed entitlement tracking, and structured AI highlights.

## Local Validation

Android Phone Companion:
```bash
./gradlew :mobile:testDebugUnitTest :mobile:assembleDebug
```

Wear OS Client:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Gateway:
```bash
cd gateway
npm install
npm run typecheck
npm test
```

Release Security Guard:
```bash
python scripts/release_guard.py
```
