# WristBrief internal release checklist

This checklist covers the repository-controlled parts of a reproducible internal release. It does **not** claim that Play Console, Google Cloud, Cloudflare, signing credentials, or device validation are configured.

## 1. Baseline and source integrity

- Confirm `main` is the intended release HEAD and has no unreviewed parallel change to include/exclude.
- Require the latest `main` CI to be fully green: release guard, Gateway typecheck + Vitest, Wear JVM tests + debug assembly, and Mobile JVM tests + debug assembly.
- Record the exact release commit SHA and do not rebuild from a moving branch without recording the new SHA.
- Confirm `versionCode` is monotonically greater than the last uploaded Play build and `versionName` matches the intended release label in both Android modules.

## 2. CI release artifacts

On every push to `main`, CI builds unsigned release artifacts for both Android apps:

```text
:app:assembleRelease
:app:bundleRelease
:mobile:assembleRelease
:mobile:bundleRelease
```

The workflow uploads two 14-day GitHub Actions artifacts, each named with the exact commit SHA:

```text
wristbrief-wear-release-<sha>
wristbrief-mobile-release-<sha>
```

Each artifact must contain the corresponding release APK and AAB. These CI outputs are **not production-signed artifacts** unless a future dedicated signing stage explicitly says otherwise.

Before using an artifact:

- Verify the Actions run belongs to the recorded release SHA.
- Verify both artifact uploads succeeded rather than being skipped after an earlier Gradle failure.
- Retain the artifact digest shown by GitHub Actions when recording a test candidate.
- Never copy keystore files, signing passwords, service-account keys, OAuth tokens, purchase tokens, or provider API keys into the repository or artifacts.

## 3. Production configuration boundary

Before an internal Play test, provision production/test secrets only in their deployment secret stores. At minimum review:

- Gateway managed-provider credentials.
- Google OAuth web client ID / allowed audience configuration.
- Google Android Publisher service-account credentials.
- Authenticated Pub/Sub RTDN service-account email and exact HTTPS audience.
- Cloudflare/D1 bindings and migrations for account identity, sessions, entitlement, quota, and purchase ownership.
- Allowed Android package names and Play subscription product IDs.

Client builds may contain public identifiers and endpoint configuration where required, but must never contain server private keys or server bearer credentials.

## 4. Signing and Play internal-test gate

Production release signing is intentionally external to the unsigned CI artifact stage.

Before upload to Play internal testing:

- Use the established Play App Signing/upload-key process; do not create an ad-hoc replacement key for an existing application identity.
- Confirm the package/application ID matches the Play Console application.
- Confirm `versionCode` has never previously been uploaded.
- Build/sign from the recorded source SHA and preserve a traceable record of artifact SHA/digest and uploaded version.
- Prefer AAB for Play distribution; APK is for controlled install/debug validation where appropriate.
- Do not mark the release production-ready merely because signing/upload succeeds.

## 5. Billing/account internal-test matrix

Using Play license/internal-test accounts and a non-production or controlled Gateway environment, exercise at least:

- Fresh Google sign-in resolves a stable internal WristBrief user ID.
- Reinstall/sign-in with the same Google `sub` resolves the same internal user and membership state.
- Different Google accounts do not inherit each other's session, entitlement, quota, or purchase binding.
- Purchase grants Pro only after server-side Android Publisher verification.
- Restore uses authenticated internal user identity and cannot transfer an already-owned purchase token implicitly.
- RTDN push authentication rejects invalid issuer/audience/service-account/signature claims.
- Active, cancellation-with-time-remaining, grace period, account hold, expiration, and revoke events re-query Play and result in the expected server-owned entitlement.
- Logout revokes/clears the WristBrief session as designed.

Never record raw purchase tokens, Google ID tokens, session bearer tokens, or Authorization headers in test logs or issue reports.

## 6. Phone and Wear validation matrix

Before widening distribution, validate paired phone/watch behavior on representative devices/emulators:

- Wear round/small screens in the roughly 192–240dp class.
- Large font scale and clipping/navigation behavior.
- Rotary scrolling and touch targets.
- Inbox/detail/read/saved/feed-management flows.
- Tile and complication launch/readability and meaningful-change refresh behavior.
- Phone feed management and Data Layer synchronization in paired, temporarily disconnected, and reconnected states.
- Podcast playback through activity recreation/backgrounding, Bluetooth transport controls, audio focus/noisy-route behavior, resume position, seek, and speed.
- Standalone watch refresh when the phone is unavailable but the watch has network access.

## 7. Gateway/security smoke checks

- `/health` responds without exposing configuration.
- Unauthorized `/v1/me`, managed AI, BYOK, billing restore, and account-management calls fail closed as designed.
- Managed provider selection remains server-owned and no public request can turn the Gateway into an arbitrary URL proxy.
- Request-size limits and provider timeout/fallback behavior still work.
- Google OIDC and Pub/Sub OIDC verification use fixed trusted JWKS/issuer boundaries and exact configured audiences.
- Public errors and logs contain no provider secret, Play purchase token, Google ID token, WristBrief session token, Authorization header, or upstream private response body.

## 8. Release record

For every internal release candidate record, outside of secrets:

```text
Source SHA:
CI run:
Wear artifact name + digest:
Mobile artifact name + digest:
Wear versionCode/versionName:
Mobile versionCode/versionName:
Gateway deployment/version:
D1 migration version:
Play internal-test version:
Device/test matrix result:
Known blockers:
```

A release can be called production-ready only after the repository gates, production configuration, signed Play internal testing, billing/RTDN lifecycle tests, and phone/Wear device matrix have all been exercised successfully.