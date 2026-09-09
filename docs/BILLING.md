# Billing

WristBrief uses Google Play Billing on the `:mobile` companion only. The gateway is the source of truth for subscription entitlement; client purchase state is never sufficient to grant `PRO`.

## Client

- Billing Library: v8.x foundation (`8.3.0` currently pinned by the project).
- Product IDs are supplied through build/configuration, not embedded as production constants.
- Display price comes from Play `ProductDetails`.
- Restore sends package name, configured product ID and purchase token to the authenticated gateway over HTTPS.

## Server verification contract

`GooglePlayPurchaseVerifier.verifySubscription({ packageName, purchaseToken })` is the boundary for Google Play Developer API integration. A production implementation should use `purchases.subscriptionsv2.get`, then normalize the response to a server-owned product ID/status/expiry record.

The gateway must check all three dimensions before granting entitlement:

1. package name equals the configured application package;
2. product ID is in the configured subscription allowlist;
3. purchase token is verified by the server-side Google Play API implementation.

Purchase tokens must never be logged or returned to clients. Persistent token ownership should store a one-way SHA-256 token hash, not the raw token.

## Restore endpoint contract

Planned route: `POST /v1/billing/restore` under existing gateway authentication.

Input fields: `packageName`, `productId`, `purchaseToken`.

Success returns the normalized subscription state and server-owned entitlement, but never returns the token. A token already linked to a different WristBrief user returns conflict and cannot transfer entitlement implicitly.

## Status mapping

- active -> PRO
- grace -> PRO
- canceled but not yet expired -> PRO until expiry
- on-hold -> FREE
- expired -> FREE
- revoked -> FREE

Production mapping must use the current `purchases.subscriptionsv2` state and expiry data rather than client claims.

## Production setup checklist

- Create subscription/base plans/offers in Play Console.
- Configure the exact package name and allowed product IDs on the gateway.
- Provision a Google Cloud service account with only required Android Publisher access.
- Store credentials only in the deployment secret store.
- Implement the production `GooglePlayPurchaseVerifier` adapter.
- Configure authenticated RTDN Pub/Sub push and verify push JWTs.
- Test license-test accounts, grace period, account hold, cancellation, expiration and revoke flows.
