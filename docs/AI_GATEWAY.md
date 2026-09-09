# AI Gateway

The existing `gateway/` remains WristBrief's single serverless backend for managed AI, membership and billing verification boundaries.

## AI rules

- Provider selection is server-side and allowlisted; clients cannot supply arbitrary upstream hosts.
- Structured brief output is validated before it reaches Wear.
- Managed AI is gated by server-owned quota.
- BYOK does not consume managed-AI quota.
- Summary cache keys depend on normalized content, language, prompt version and schema version.
- API keys, Authorization headers and source bodies are not written to logs.

## Billing interaction

Billing verification is intentionally separate from AI provider adapters. Verified Play state updates membership entitlement; AI authorization consumes that membership snapshot. Client-side Play state never bypasses `/v1/me` or managed quota rules.

## Required production configuration

Configure provider keys/base URLs, gateway authentication, summary-cache bindings, membership persistence, Play package/product allowlists and Google verification credentials through deployment secrets/bindings. Do not commit production values to Git.
