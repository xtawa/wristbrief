# Billing

WristBrief uses Google Play Billing on the `:mobile` companion only. The gateway is the source of truth for subscription entitlement; client purchase state is never sufficient to grant `PRO`.

## Client

- Billing Library: v8.x foundation (`8.3.0` currently pinned by the project).
- Product IDs are supplied through build/configuration, not embedded as production constants.
- Display price comes from Play `ProductDetails`.
- Restore sends package name, configured product ID and purchase token to the authenticated gateway over HTTPS.

## Server verification contract

`GooglePlayPurchaseVerifier.verifySubscription({ packageName, purchaseToken })` is the boundary for Google Play Developer API integration. The production adapter uses the fixed Google `purchases.subscriptionsv2.get` endpoint and normalizes `subscriptionState`, `lineItems.productId`, and `lineItems.expiryTime` to the server-owned subscription record.

The gateway must check all three dimensions before granting entitlement:

1. package name equals the configured application package;
2. product ID is in the configured subscription allowlist;
3. purchase token is verified by the server-side Google Play API implementation.

Purchase tokens must never be logged or returned to clients. Persistent token ownership should store a one-way SHA-256 token hash, not the raw token.

The real verifier is auto-enabled only when both `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL` and `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` are present. The private key must be stored as a deployment secret; either literal PEM newlines or escaped `\n` are accepted. The adapter signs a short-lived RS256 service-account assertion for the fixed Google OAuth token endpoint with only the `https://www.googleapis.com/auth/androidpublisher` scope, caches the resulting access token until shortly before expiry, and sends it only in the `Authorization` header to `androidpublisher.googleapis.com`.

Neither OAuth nor Android Publisher upstream URLs are client configurable. Non-2xx Google responses and malformed/ambiguous subscription payloads fail closed without returning Google response bodies, OAuth tokens, service-account material, or purchase tokens.

## Restore endpoint contract

Route: `POST /v1/billing/restore` under existing gateway authentication.

Input fields: `packageName`, `productId`, `purchaseToken`.

Success returns the normalized subscription state and server-owned entitlement, but never returns the token. A token already linked to a different WristBrief user returns conflict and cannot transfer entitlement implicitly.

## Status mapping

- active -> PRO
- grace -> PRO
- canceled but not yet expired -> PRO until expiry
- on-hold -> FREE
- expired -> FREE
- pending/paused/pending-purchase-canceled -> FREE (`revoked` internal no-access bucket)

Production mapping uses the current `purchases.subscriptionsv2` state and expiry data rather than client claims. Distinct product IDs in one Play response are rejected as ambiguous until product-transition handling is explicitly modeled rather than guessed.

## Production setup checklist

- Create subscription/base plans/offers in Play Console.
- Configure the exact package name and allowed product IDs on the gateway.
- Provision a Google Cloud service account with only required Android Publisher access.
- Store `GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL` and `GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY` only in the deployment secret store.
- Grant the service account access to the Play Console app and verify `purchases.subscriptionsv2.get` against a license-test purchase.
- Configure authenticated RTDN Pub/Sub push and verify push JWTs.
- Test license-test accounts, grace period, account hold, cancellation, expiration and revoke flows.

No production deployment is claimed until those external Play Console / Google Cloud steps are completed and exercised against the internal-test track.
