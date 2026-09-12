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

## Cloudflare Gateway one-click deploy

The repository includes a Windows PowerShell deployment script at [`scripts/deploy_gateway.ps1`](scripts/deploy_gateway.ps1). It installs the Gateway dependencies, runs the TypeScript check, verifies Wrangler authentication, applies pending remote D1 migrations, deploys `wristbrief-gateway`, and can optionally check `/health`.

Before running it, create the Cloudflare resources in the Dashboard and configure the runtime Variables/Secrets:

- D1 database: `wristbrief-account-db`, binding `ACCOUNT_DB`.
- R2 bucket: `wristbrief-transcripts`, binding `TRANSCRIPTS_BUCKET`.
- Queue: `wristbrief-transcript-jobs`, binding `TRANSCRIPT_QUEUE`.
- Replace the placeholder `database_id` in [`gateway/wrangler.toml`](gateway/wrangler.toml) with the real D1 Database ID.
- Put `AI_API_KEY` and Google Play private keys in Cloudflare Secrets, never in Git.

Run from the repository root:

```powershell
git pull --ff-only
.\scripts\deploy_gateway.ps1 -HealthUrl "https://api.example.com"
```

If `gateway/wrangler.toml` was edited in the GitHub web editor, the `git pull --ff-only` step is required before the script can see the real D1 `database_id`.

The script intentionally keeps the remote D1 migration confirmation prompt. This prevents an accidental schema change on the production database. For an already prepared checkout, the optional switches are:

```powershell
.\scripts\deploy_gateway.ps1 -SkipInstall -SkipLogin -HealthUrl "https://api.example.com"
.\scripts\deploy_gateway.ps1 -SkipMigrations -HealthUrl "https://api.example.com"
```

`-SkipMigrations` is only appropriate when the remote D1 schema is already current. The script does not create or delete Cloudflare resources, does not print secret values, and does not configure Google Play/Pub/Sub for you.

For macOS/Linux, run the same script with PowerShell 7 (`pwsh`). The web-first deployment walkthrough, environment variable names, Google Play setup, custom domain setup, and current verification boundaries are documented in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

Current limitation: the repository has the Queue producer binding but no transcript Queue consumer/audio worker. Deploying the Worker therefore does not make asynchronous transcript generation end-to-end complete.

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
