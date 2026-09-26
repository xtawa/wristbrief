# WristBrief Server gateway

A long-running Node.js HTTP service with SQLite, a local object directory, and an administrator-only web console. It serves mobile and watch authentication, sync, AI summaries, content, and billing APIs. It no longer deploys as a Cloudflare Worker.

## Run locally

Node 24 or newer is recommended for `node:sqlite`. From `gateway/`:

```bash
npm ci
export WRISTBRIEF_DATA_DIR="$(pwd)/data"
export WRISTBRIEF_MASTER_KEY="$(openssl rand -base64 32)"
export WRISTBRIEF_ADMIN_INITIAL_PASSWORD='replace-with-a-random-16-plus-character-secret'
npm start
```

Browse `/admin/login`. Sign in as `zeromostia@gmail.com` with the temporary password. The first sign-in requires a new password before accessing any management route. Remove the temporary password from the deployment environment after this step. Server migrations apply on startup. Start with a private data directory; never commit the `.sqlite` database, object storage, secrets, or a populated environment file.

For production set `NODE_ENV=production`, `WRISTBRIEF_PUBLIC_ORIGIN=https://your-public-domain` and run the server behind a TLS reverse proxy. The listener defaults to `127.0.0.1:8787`; keep it private and proxy HTTPS traffic to it. Persist and back up both `WRISTBRIEF_DATA_DIR/wristbrief.sqlite` (including WAL consistency) and `WRISTBRIEF_DATA_DIR/objects`. Back up `WRISTBRIEF_MASTER_KEY` separately; losing it prevents decrypting SMTP and AI provider credentials. SQLite supports one server process against this database. Use a process supervisor for restart and monitoring.

## Admin console

- `/admin` dashboard and audit log; `/admin/providers` add, edit, delete and health-check model configurations.
- `/admin/provider-keys` stores provider API keys encrypted with AES-256-GCM. The matching secret slot is chosen in a model configuration; keys cannot be read back.
- `/admin/users` edits ordinary mobile users' name, active status, membership (FREE or PRO) and current-month AI quota. The administrator cannot be edited or promoted through this endpoint. Disabling a user revokes current sessions.
- `/admin/smtp` configures SMTP host, TLS mode, account, password and sender address. Until configured, email verification/reset delivery cannot work.
- `/admin/settings` controls **mobile API** email registration. It starts closed; configure and test SMTP before enabling it. The web console has no registration page and only the preseeded admin may log in. Admin role is intentionally fixed to one account.

The initial monthly AI limits are 10 for FREE and 100 for PRO, adjustable with `FREE_AI_MONTHLY_LIMIT` and `PRO_AI_MONTHLY_LIMIT`. Admin overrides apply to the current month; the following month uses the configured plan default. Configure an AI model and secret before enabling AI use.

## External services

Google Cloud is optional for this server. To keep Android Google sign-in, set `GOOGLE_OAUTH_CLIENT_ID` and configure the Android OAuth client. For Google Play subscriptions, set the Play service account and package/product settings; realtime notifications additionally need authenticated Pub/Sub push. Neither the admin web login nor SMTP nor AI provider management uses Google OAuth. See [server architecture](../docs/SERVER_BACKEND_ARCHITECTURE.md).

Asynchronous transcript generation is not yet connected to a server-side audio transcription worker. In server mode, requests for new transcript jobs return `503 transcript_worker_unavailable` instead of leaving an unfinishable queued job. Do not advertise this feature as ready until a durable worker is installed.

Verify with `npm run typecheck` and `npm test`.
