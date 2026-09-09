# Membership

WristBrief membership is server-owned state in the existing gateway. The client cannot grant itself `PRO` by sending flags or local billing state.

## Plans and entitlement

- `FREE`: managed-AI usage is subject to server quota.
- `PRO`: entitlement can come from verified billing/admin state.
- BYOK calls do not consume managed-AI quota.
- `/v1/me` is the authenticated membership snapshot contract.

Billing verification produces a normalized entitlement only after package/product/token verification. Grace period keeps access; account hold, expiry and revoke remove paid access. Cancellation keeps access only until the verified expiry time.

## Purchase ownership

A Google Play purchase token may be associated with at most one WristBrief user. The persistent implementation should store a SHA-256 token hash and enforce a unique constraint on that hash. Re-linking the same token to the same user is idempotent; linking it to another user is a conflict.

## RTDN behavior

Real-time developer notifications are change signals, not entitlement assertions. After an authenticated Pub/Sub push, the server resolves the token owner and re-queries Google Play before changing entitlement. Notification type, subscription ID, or expiry data from the RTDN payload is never trusted as final state.

## Persistence

`gateway/migrations/0001_membership.sql` is the current D1-compatible membership/quota foundation. Production billing persistence must add token-hash ownership and verified subscription state with uniqueness constraints before launch.
