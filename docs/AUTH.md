# WristBrief account identity

This document tracks the P8.1 account layer that will live inside the existing serverless AI Gateway.

## Identity rule

Google email is display/recovery metadata only. The durable external identity key is the verified Google OpenID Connect `sub` claim. WristBrief maps `(provider = google, provider_subject = sub)` to an immutable internal `userId`; entitlement, managed-AI quota, Play purchase ownership and future account data use that internal ID.

## Current foundation

The gateway has a production Google ID-token verifier boundary that:

- accepts a compact Google ID token only from the caller of the future auth route;
- verifies RS256 against Google's fixed public JWKS endpoint;
- validates the Google issuer, exact configured OAuth audience, `iat`, optional `nbf`, `exp`, `sub`, `email` and `email_verified`;
- can validate an expected nonce supplied by the future sign-in challenge/session flow;
- returns bounded identity metadata and never treats email as the account key;
- caches Google public keys for a bounded interval and refreshes once for an unknown signing `kid`;
- fails closed on malformed tokens, bad signatures, wrong audience, expiry or claim mismatches.

Production configuration uses `GOOGLE_OAUTH_CLIENT_ID`, which is the server/web OAuth client ID also supplied as the Android Sign in with Google `serverClientId`. This value is not a secret. No Google client secret is required in the APK.

The D1-compatible `0003_google_identity.sql` migration adds `users` and `identities` tables with a unique `(provider, provider_subject)` identity key. It is not automatically applied and does not claim a production deployment.

## Planned next steps

1. Add a durable D1 implementation of the account identity store with transaction-safe resolve/create behavior.
2. Add short-lived sign-in challenges/nonces and a server-owned session model.
3. Expose `POST /v1/auth/google`, verify the ID token and challenge, resolve the internal user, then issue an expiring WristBrief session credential.
4. Make normal Gateway authentication accept WristBrief sessions while retaining an explicit migration path from the current legacy gateway token.
5. Add the phone Credential Manager / Sign in with Google flow and store only the WristBrief session credential locally.
6. Bind Play restore and RTDN ownership to the authenticated internal user ID.

ID tokens, session bearer credentials, Play purchase tokens and authorization headers must never be written to Git, normal application logs or public error bodies.
