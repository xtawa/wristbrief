# Deployment

WristBrief currently supports a credential-free CI foundation. Do not treat passing fake tests as proof that Google Play, Google Cloud or Cloudflare production integration is deployed.

## Gateway deployment inputs

Configure through the deployment platform's secret/binding system:

- gateway authentication token/user mapping;
- AI provider credentials and allowlisted endpoints/models;
- summary cache binding/TTL;
- persistent membership/quota store;
- `PLAY_PACKAGE_NAME`;
- `PLAY_SUBSCRIPTION_PRODUCT_IDS`;
- Google Play Developer API credentials;
- authenticated Pub/Sub push verifier configuration for RTDN.

## Google Play / Cloud setup

1. Create Play subscription products/base plans/offers.
2. Enable Google Play Developer API access for the production project.
3. Grant a dedicated service account only the permissions required to inspect subscription purchases.
4. Implement/bind the production `GooglePlayPurchaseVerifier` using `purchases.subscriptionsv2.get`.
5. Create the RTDN Pub/Sub topic/subscription from Play Console.
6. Configure authenticated push with a dedicated service account and validate JWT audience/issuer/signature at the gateway.
7. Point the push subscription at the gateway RTDN route.
8. Verify that RTDN processing re-queries Play before updating entitlement.

## Cloudflare persistence

Apply reviewed D1 migrations before enabling persistent membership/billing state. Add a unique purchase-token-hash ownership constraint before production billing launch. KV may be used only for generated-summary caching, never for credentials or raw purchase tokens.

## Pre-release validation

Run Wear and Mobile JVM tests + debug assemblies, gateway typecheck + Vitest, then test Play license accounts for purchase, restore, duplicate restore, grace period, hold, cancel, expiry and revoke. Confirm no secrets/tokens appear in logs or repository history.
