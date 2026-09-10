# WristBrief account identity

This document tracks the P8.1 account layer that lives inside the existing serverless AI Gateway.

## Identity rule

Google email is display/recovery metadata only. The durable external identity key is the verified Google OpenID Connect `sub` claim. WristBrief maps `(provider = google, provider_subject = sub)` to an immutable internal `userId`; entitlement, managed-AI quota, Play purchase ownership and future account data use that internal ID.

## Google identity foundation

The gateway has a production Google ID-token verifier boundary that:

- accepts a compact Google ID token only at the Google auth exchange route;
- verifies RS256 against Google's fixed public JWKS endpoint;
- validates the Google issuer, exact configured OAuth audience, `iat`, optional `nbf`, `exp`, `sub`, `email` and `email_verified`;
- can validate an expected nonce when the future sign-in challenge flow supplies one;
- returns bounded identity metadata and never treats email as the account key;
- caches Google public keys for a bounded interval and refreshes once for an unknown signing `kid`;
- fails closed on malformed tokens, bad signatures, wrong audience, expiry or claim mismatches.

Production configuration uses `GOOGLE_OAUTH_CLIENT_ID`, which is the server/web OAuth client ID also supplied as the Android Sign in with Google `serverClientId`. This value is not a secret. No Google client secret is required in the APK.

## D1 identity + session persistence

`0003_google_identity.sql` adds `users` and `identities` with a unique `(provider, provider_subject)` key. `0004_account_sessions.sql` adds revocable sessions with a unique SHA-256 token hash.

When an `ACCOUNT_DB` D1 binding is supplied, the Gateway can construct production account stores directly. Google identity resolution uses an atomic D1 batch: it creates a candidate internal user, inserts the unique Google subject binding if absent, updates non-authoritative profile metadata, removes a losing candidate user after a concurrent/existing binding wins, then resolves the authoritative active user. Different Google subjects are never merged because their email strings match.

Session persistence stores only the SHA-256 hash of the opaque bearer. Raw WristBrief bearer credentials are returned once to the client at issue time and are not stored by the account store. Sessions are expiring and revocable.

These migrations and bindings are deployment foundations only. The repository does not claim that a production D1 database has been provisioned or migrated.

## Google auth exchange

Route: `POST /v1/auth/google`

Request body:

```json
{ "idToken": "<short-lived Google ID token>" }
```

The route is intentionally reachable without a legacy WristBrief bearer because it is the credential-exchange entry point. It does not accept client-supplied `userId`, email, plan, entitlement or `isPro` as authority. The Gateway verifies the Google token, resolves `(google, sub)` to the internal user, then issues a WristBrief session.

Successful responses contain the internal user ID plus a new opaque `sessionToken` and `expiresAt`. They do not echo the Google ID token. `ACCOUNT_SESSION_TTL_SECONDS` can override the bounded server-owned session TTL; invalid TTL configuration fails closed.

## Normal API authentication during migration

Normal Gateway APIs now accept two explicit credential classes:

- WristBrief account sessions (`Bearer wbs_...`), resolved through the configured session store/D1 to the immutable internal user ID.
- The existing legacy `GATEWAY_TOKEN`, retained temporarily as a compatibility/migration path.

A syntactically valid WristBrief session never falls back to the legacy token if the session is missing, expired or revoked. Missing `GATEWAY_TOKEN` configuration also cannot accidentally authenticate a literal `Bearer undefined` value.

The legacy compatibility entitlement is scoped only to the configured legacy principal. A Google/session user without a durable `MEMBERSHIP_STORE` is fail-safe FREE with zero managed-AI quota rather than inheriting legacy PRO. BYOK remains governed by its separate quota-bypass contract.

## Planned next steps

1. Add explicit session sign-out/revocation routing and account-level session management.
2. Add short-lived sign-in challenges/nonces if required by the final Android Credential Manager flow.
3. Add the phone Credential Manager / Sign in with Google flow and store only the WristBrief session credential locally.
4. Bind Play restore and RTDN ownership to the authenticated internal user ID and exercise cross-device restore.
5. Define account deletion/unlink and legacy-user linking/recovery policy before production launch.

Google ID tokens, session bearer credentials, Play purchase tokens and authorization headers must never be written to Git, normal application logs or public error bodies.
