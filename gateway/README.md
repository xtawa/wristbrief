# WristBrief Gateway

Cloudflare Workers gateway service for WristBrief, handling AI summary generation, account sessions, email/Google authentication, Google Play in-app billing verification, and cloud synchronization.

## Quick Start

### 1. Install Dependencies
```bash
npm install
```

### 2. Local Development
Create `.dev.vars` in this directory:
```ini
AI_API_KEY="your-deepseek-api-key"
RESEND_API_KEY="re_xxxxxxxx"
# Optional:
# RESEND_FROM_EMAIL="WristBrief <auth@yourdomain.com>"
```

Run locally:
```bash
npm run dev
```

### 3. Run Tests & Typecheck
```bash
npm run typecheck
npm test
```

## Deployment

### Cloudflare Worker Secrets
Configure secrets in Cloudflare before or after deploying:
```bash
# Required for AI summaries:
npx wrangler secret put AI_API_KEY

# Required for transactional emails (verification, password reset):
npx wrangler secret put RESEND_API_KEY

# Required for Google sign-in; this must match the Android Web client ID:
npx wrangler secret put GOOGLE_OAUTH_CLIENT_ID
```

### Deploy to Cloudflare
Deploy the worker and apply remote D1 migrations in one command:
```bash
npm run deploy
```

Or deploy worker only:
```bash
npx wrangler deploy --keep-vars
```

Apply D1 database migrations only:
```bash
npm run db:migrations:apply
```

### Live Logs
Monitor worker requests and errors in real-time:
```bash
npx wrangler tail
```
