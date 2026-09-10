# WristBrief — 24 Hour Review

Review baseline: `f9323757261bd5d89430f945de62a8ceed23e140` (CI #144 green).

This file is a release-readiness handoff, not a second execution queue. The original 24 slots remain complete in `NEXT_24_HOURS_STATUS.md`; this document tracks what has since been implemented in-repository versus what still requires external deployment, Play/Google Cloud configuration, or broader device execution.

## Completed scope

Slots 01–24 are complete and green. They cover feed identity/dedup and subscription CRUD; Wear feed/detail/read/saved UX; Media3 background podcast playback, progress and transcript discovery; provider-abstracted structured AI briefs with reliability/security/cache handling; cached-data Tile/Complication surfaces; an independent phone companion and lightweight versioned Wear Data Layer sync; Play Billing v8 client foundations; server-owned membership/quota plus Play verification/RTDN contracts, migrations, fakes and documentation; and the final repository-wide hardening/release-readiness review.

Subsequent repository work has also completed the following foundations:

- real Google Android Publisher `purchases.subscriptionsv2` verification using server-held service-account credentials;
- authenticated Pub/Sub RTDN OIDC/JWT verification against fixed Google trust boundaries, with entitlement changes still requiring a Play re-query;
- Google OAuth/OIDC account identity on the phone and Gateway, keyed by verified Google `sub` rather than email;
- revocable WristBrief sessions, durable D1-compatible user/identity/session/entitlement/quota/purchase-binding schemas, and explicit legacy-account migration/recovery rules;
- membership/Play purchase ownership bound to the immutable internal WristBrief user ID;
- unsigned Wear and Mobile release APK/AAB generation and artifact upload on `main` CI;
- a reproducible internal-release checklist;
- Android instrumentation foundations for both apps, including `AndroidJUnitRunner`, MainActivity launch smoke tests, and CI compilation of both debug instrumentation APKs;
- CI execution of Mobile instrumentation on a managed Android device and Wear instrumentation on both small-round and large-round Wear OS emulator profiles;
- MainActivity recreation/resume smoke coverage for both Wear and Mobile, plus a Wear playback-service lifecycle smoke test across activity recreation/background-resume, executed by CI instrumentation jobs.

These repository implementations do not by themselves prove production deployment or the full physical-device interaction matrix.

## Review findings and hardening

- Both Android apps disable cleartext traffic and backups. The phone manifest was hardened with `android:allowBackup="false"` so persisted feed/account-adjacent state is not included in normal Android backup by default.
- `PodcastPlaybackService` remains non-exported. Launcher, Tile, complication and Wear Data Layer components retain only the exported state required by their platform integration.
- CI includes deterministic release manifest guards, JVM tests, debug APKs, instrumentation test APK compilation, unsigned release APK/AAB artifacts, Mobile managed-device instrumentation, and Wear small/large round emulator instrumentation.
- Gateway tests cover server-owned provider routing, fixed HTTPS upstreams, bounded request sizes, provider timeout/retry behavior, malformed output, authentication/quota bypass attempts, Google account/session boundaries, purchase-token ownership, Android Publisher verification and authenticated RTDN re-verification.
- Feed/article/transcript source text remains treated as untrusted input for summarization; structured output is validated before reaching Wear UI.
- Tiles/complications use local cached state and meaningful-change updates rather than render-time feed/AI polling.

## Current CI/release checkpoint

Repository-controlled checks now include:

- Wear JVM tests;
- Wear debug assembly;
- Wear debug instrumentation-test APK assembly;
- Wear OS small-round and large-round emulator execution of instrumentation smoke tests;
- Mobile JVM tests;
- Mobile debug assembly;
- Mobile debug instrumentation-test APK assembly;
- Mobile managed-device execution of instrumentation smoke tests;
- Gateway TypeScript typecheck;
- Gateway Vitest;
- release manifest guard;
- unsigned Wear/Mobile release APK + AAB assembly and artifact upload on `main` pushes.

CI #268 verified the Wear launch, activity recreation/background-resume, and playback-service lifecycle smoke suite on both configured small-round and large-round Wear emulator profiles while retaining the Mobile managed-device instrumentation and all repository-controlled checks.

These smoke suites prove launch/recreation and the bounded service-lifecycle check on the configured CI devices. They do **not** substitute for the broader interaction, connectivity, accessibility, active-media, Tile/Complication, Play or physical-device matrix below.

## Remaining external or execution-dependent release blockers

The following work remains intentionally unclaimed:

- apply the D1 migrations/bindings to a controlled Cloudflare environment and smoke-test the deployed Gateway with real non-repository secret bindings;
- configure the actual Play Console subscription/base plans/test accounts and exercise a license/internal-test purchase against the deployed verifier;
- configure the actual Google Cloud Pub/Sub RTDN topic and authenticated push subscription, then exercise live push delivery against the configured production-like audience/service account;
- execute the full Play lifecycle matrix: active, cancellation-with-time-remaining, grace period, account hold, expiration and revoke;
- configure the established Play App Signing/upload-key path and upload a traceable AAB to the Play internal-testing track;
- expand Android/Wear instrumentation beyond launch/recreation/service-lifecycle smoke checks into deterministic interaction flows where practical;
- execute large-font and rotary-input checks on representative Wear profiles; small-round and large-round CI profiles now cover the basic round-size dimension but not accessibility/input behavior;
- exercise MediaSession during active playback, Bluetooth controls, noisy-route/audio-focus and persisted resume behavior on representative Wear hardware/emulators;
- exercise paired phone↔Wear Data Layer flows across connected, disconnected and reconnected states;
- verify Tile and complication launch/readability/update behavior on Wear OS.

No production credential, signing key or console-only result should be committed merely to clear one of these blockers.

## Recommended next release-readiness order

1. Expand the now-running instrumentation suites around deterministic app interaction/state restoration, large-font/rotary behavior and active-media lifecycle where CI can test it reliably.
2. Deploy a non-production Gateway with the real D1 bindings/migrations and secret-store configuration, then run security/auth/membership smoke checks against that deployment.
3. Wire Play internal testing plus authenticated RTDN in Google Cloud and execute the complete purchase/restore/lifecycle matrix.
4. Sign and upload a traceable AAB through the established Play App Signing path and record the exact source SHA/artifact digest/version.
5. Complete the paired phone/Wear, MediaSession, Tile/Complication and accessibility/device matrix before widening distribution.

## Release-ready boundary

Do not call WristBrief production-ready until the repository gates are green **and** the external deployment, signed Play internal testing, billing/RTDN lifecycle checks, and phone/Wear device matrix above have all been exercised successfully. CI emulator/managed-device smoke tests, repository-level mocks, or unsigned release artifacts are not substitutes for those gates.
