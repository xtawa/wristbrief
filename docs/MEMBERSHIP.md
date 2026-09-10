# Membership

WristBrief membership is server-owned state in the existing gateway. The client cannot grant itself `PRO` by sending flags or local billing state.

## Plans and entitlement

- `FREE`: managed-AI usage is subject to server quota.
- `PRO`: entitlement can come from verified billing/admin state.
- BYOK calls do not consume managed-AI quota.
- `/v1/me` is the authenticated membership snapshot contract.

Billing verification produces a normalized entitlement only after package/product/token verification. Grace period keeps access; account hold, expiry and revoke remove paid access. Cancellation keeps access only until the verified expiry time.

## Google account identity

The phone uses Google OpenID Connect through Android Credential Manager to obtain a short-lived Google ID token. The Gateway verifies the Google signature and claims before resolving identity. Google email is display/recovery metadata only; it is never the durable membership key.

The durable mapping is `(provider = google, provider_subject = Google sub) -> immutable WristBrief user_id`. A WristBrief session is issued only after that mapping succeeds. Session bearer tokens are stored server-side only as one-way hashes and are independently expiring/revocable.

The same internal `user_id` owns entitlement, managed-AI quota, Play purchase ownership and any future account-scoped data. `/v1/me` must return the authenticated session user's server-owned snapshot, and the phone rejects a snapshot whose returned user ID does not match the active account session.

## Purchase ownership

A Google Play purchase token may be associated with at most one WristBrief user. Persistent storage keeps a SHA-256 token hash and enforces a unique constraint on that hash. Re-linking the same token to the same user is idempotent; linking it to another user is a conflict.

A restore request must use an authenticated WristBrief account session. The Gateway verifies package/product/token with the Android Publisher API, then binds the token hash to that internal `user_id`. A raw purchase token is never an account identifier and must not appear in logs or public responses.

## RTDN behavior

Real-time developer notifications are change signals, not entitlement assertions. After an authenticated Pub/Sub push, the server resolves the token owner and re-queries Google Play before changing entitlement. Notification type, subscription ID, or expiry data from the RTDN payload is never trusted as final state.

## Persistence

The current D1-compatible serverless model is split across migrations:

- `0001_membership.sql`: authoritative entitlement and managed-AI quota state keyed by `user_id`.
- `0002_play_billing.sql`: unique Play purchase-token-hash ownership binding.
- `0003_google_identity.sql`: immutable users plus unique provider/subject identities.
- `0004_account_sessions.sql`: expiring/revocable account sessions with hashed bearer tokens.

D1 is the authoritative account/membership store when configured. KV/cache may accelerate generated AI summaries but must not become the membership source of truth.

## One-time legacy identity migration policy

The current `GATEWAY_TOKEN` / `GATEWAY_USER_ID` path exists only as a migration-compatible legacy principal. It must not be silently merged into a Google account just because an email string matches.

A future explicit legacy-link operation must follow these rules:

1. Authenticate both sides in the same operation: a valid legacy WristBrief principal and a freshly verified Google ID token.
2. Resolve the Google `(provider, sub)` first. If it is already attached to a different internal user, reject the link; never move it implicitly.
3. Check Play purchase ownership before moving account state. If any purchase-token hash conflicts with another internal user, reject and require an explicit support/recovery workflow.
4. Preserve authoritative entitlement and managed-AI quota from the legacy `user_id` when linking. The operation must be idempotent so retrying the same successful link cannot duplicate quota, entitlement or purchase ownership.
5. Record enough non-secret audit metadata to explain the transition (internal user IDs, operation/result timestamps and reason codes). Never record Google ID tokens, session tokens, authorization headers or raw purchase tokens.
6. After a successful migration, issue a normal WristBrief account session for the Google-backed identity. Legacy authentication can remain temporarily available only for a defined compatibility window; it must not create a second paid identity.
7. Account merge/recovery remains an explicit administrative/support action. Matching email strings alone are never merge evidence.

Until this explicit link transaction is implemented and tested, a new Google identity and the legacy principal remain separate accounts.

## Sign-out, revocation, unlink and deletion policy

- Sign-out clears the local phone session and calls the Gateway logout route to revoke the presented WristBrief session where possible.
- Revoking one session does not delete the Google identity or membership record; other valid sessions remain independent unless an account-wide revoke action is explicitly requested.
- Switching Google accounts must clear the previous local WristBrief session before another account becomes active.
- Google identity unlink is not permitted while it is the account's only login identity unless a replacement recovery identity/session mechanism exists. Unlink must never transfer Play purchases to another user implicitly.
- Account deletion must revoke all sessions first, disable the internal user, stop future managed-AI use, and define how billing history/purchase bindings are retained or tombstoned for fraud, refund and RTDN correctness before production launch.
- Deletion and unlink flows must be idempotent and auditable without retaining bearer tokens or raw purchase tokens.

Production deployment is not claimed until these recovery/deletion operations and the external Google/Play configuration have been exercised in an internal-test environment.
