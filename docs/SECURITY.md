# Security

## Secrets and sensitive data

Never commit or log AI API keys, Google service-account credentials, Authorization headers, raw Google Play purchase tokens, or user article/transcript bodies. Purchase ownership should persist only a SHA-256 token hash when possible.

## Gateway boundaries

- Upstream AI hosts are fixed/server-allowlisted; the gateway must never become an arbitrary URL proxy.
- Request sizes and provider timeouts are bounded.
- Provider failures are normalized without echoing upstream bodies or credentials.
- Membership and quota decisions are server-owned.
- Client `isPro` or equivalent claims are ignored.

## Billing boundaries

- Package name and product IDs are server-configured and allowlisted.
- The Play verifier re-queries Google Play; client billing state is advisory only.
- A purchase token can belong to only one WristBrief user.
- RTDN push requests require authenticated Pub/Sub verification.
- RTDN payload fields are not accepted as final entitlement state; Play is queried again before entitlement changes.

## Android

Review exported components before release, keep Data Layer payloads bounded, and avoid placing provider/billing secrets in either APK. Billing remains on the phone companion.

## Release checks

Before production release, run secret scanning, inspect Android manifests, verify no debug endpoints/keys remain, validate auth/quota bypass tests, and exercise purchase restore plus RTDN replay/duplicate handling.
