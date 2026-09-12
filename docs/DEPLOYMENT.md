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

## Cloudflare persistence and automated deployment

`gateway/wrangler.toml` declares the D1 database, R2 bucket, and Queue by stable names. `scripts/deploy_gateway.ps1` invokes Wrangler's beta automatic resource provisioning, so a first run can create missing resources and later runs reuse them. Review the account and expected billing before the first real run. The script also applies pending remote D1 migrations and preserves Dashboard variables through `keep_vars = true`.

From the repository root:

```powershell
Copy-Item gateway/.env.cloudflare.example gateway/.env.cloudflare.local
# Fill gateway/.env.cloudflare.local locally; it is ignored by Git.
.\scripts\deploy_gateway.ps1 -HealthUrl "https://api.example.com"
```

The script does not delete resources. `-SkipMigrations`, `-SkipSecrets`, and `-SkipProvisioning` are explicit opt-outs; use them only when the corresponding remote state is already prepared. Cloudflare credentials and provider/Google Play values remain operator-supplied inputs and are never generated or committed by the script.

For Workers Builds, set the root directory to `gateway`. Its `package.json` deploy command provisions the Worker and then applies `ACCOUNT_DB` migrations. A successful local typecheck or Vitest run is not production or real-device proof; verify the deployed `/health` endpoint separately.

Apply reviewed D1 migrations before enabling persistent membership/billing state. Add a unique purchase-token-hash ownership constraint before production billing launch. KV may be used only for generated-summary caching, never for credentials or raw purchase tokens.

## Pre-release validation

Run Wear and Mobile JVM tests + debug assemblies, gateway typecheck + Vitest, then test Play license accounts for purchase, restore, duplicate restore, grace period, hold, cancel, expiry and revoke. Confirm no secrets/tokens appear in logs or repository history.
