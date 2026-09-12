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

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/xtawa/wristbrief/tree/main/gateway)

The repository includes a fully automated PowerShell deployment script at [`scripts/deploy_gateway.ps1`](scripts/deploy_gateway.ps1). It installs dependencies, checks Wrangler authentication, provisions or reuses the declared D1/R2/Queue resources by name, applies remote D1 migrations, uploads an optional local secrets file without printing its values, deploys `wristbrief-gateway`, and can check `/health`.

The binding names and resource names are already declared in [`gateway/wrangler.toml`](gateway/wrangler.toml). Wrangler's beta automatic resource provisioning creates missing resources and writes a newly discovered D1 ID back to that file. The first real run can create billable Cloudflare resources; `keep_vars = true` preserves variables already configured in the Cloudflare Dashboard.

Secrets still require real values that only the operator can provide. Prepare the ignored local file from [`gateway/.env.cloudflare.example`](gateway/.env.cloudflare.example):

```powershell
Copy-Item gateway/.env.cloudflare.example gateway/.env.cloudflare.local
# Edit gateway/.env.cloudflare.local and fill only the secrets required by this deployment.
```

Run one command from the repository root:

```powershell
git pull --ff-only
.\scripts\deploy_gateway.ps1 -HealthUrl "https://api.example.com"
```

The first run opens the Cloudflare browser login when Wrangler is not authenticated. Migrations are confirmed automatically for an unattended run; use `-InteractiveMigrations` when a human confirmation is required. For an already prepared checkout, useful switches are:

```powershell
.\scripts\deploy_gateway.ps1 -SkipInstall -SkipLogin -HealthUrl "https://api.example.com"
.\scripts\deploy_gateway.ps1 -SecretsFile "C:\secure\wristbrief.cloudflare.env" -HealthUrl "https://api.example.com"
.\scripts\deploy_gateway.ps1 -SkipMigrations -SkipSecrets -HealthUrl "https://api.example.com"
.\scripts\deploy_gateway.ps1 -DryRun -SkipInstall -SkipLogin -SkipTypecheck
```

`-DryRun` compiles the Worker and prints the resolved bindings without changing Cloudflare. `-SkipMigrations` is only appropriate when the remote D1 schema is already current. `-SkipProvisioning` is an explicit escape hatch for an account where all bindings are already connected. The script never deletes Cloudflare resources and never prints secret values. It cannot invent Google Play, Google Cloud, Pub/Sub, custom-domain, or provider credentials; those values must be supplied in the ignored secrets file or configured in Cloudflare.

For Cloudflare Workers Builds, the `gateway/package.json` `deploy` script performs the same resource-aware Worker deploy followed by `ACCOUNT_DB` migrations. Set the Workers Build root directory to `gateway`; no VM, emulator, or real-device test is part of this deployment flow.

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
