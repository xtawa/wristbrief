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
- MainActivity recreation/resume smoke coverage for both Wear and Mobile, plus a Wear playback-service lifecycle smoke test across activity recreation/background-resume, executed by CI instrumentation jobs;
- Wear navigation/state-restoration interaction coverage on the small-round profile at configured system font scale `1.30`, while retaining the large-round default-font profile;
- instrumented Media3 `MediaController` coverage that verifies the non-exported `PodcastPlaybackService` accepts controller connections and, with a locally generated WAV fixture, preserves prepared episode identity/seek/speed state across controller disconnect/reconnect and separately keeps an actively playing episode service-owned while no controller is connected. The active-playback test verifies `STATE_READY`, `playWhenReady`, `isPlaying`, media identity, and non-regressing playback position after reconnect. The fixture is local to CI and does not depend on external media networking;
- device-level Wear surface provider contract coverage now verifies the installed APK exposes both Tile providers and the unread complication provider with the expected bind permissions/actions, complication supported-type/update-period metadata, installed component resolution, and valid SHORT_TEXT/LONG_TEXT preview payloads while rejecting an unsupported preview type;
- Wear subscription Data Layer decoding now applies the same HTTPS/canonical-identity invariants as the repository and rejects duplicate IDs or duplicate logical feed URLs;
- read/saved item-state Data Layer payloads now reject duplicate records and enforce channel ownership (`PHONE` fields only on the phone-owned path and `WEAR` fields only on the Wear-owned path), preventing forged origin values from changing merge tie-breaking;
- the phone→Wear scoped account-session bridge now publishes a single latest-state DataItem rather than a best-effort transient message, so a set/clear performed while devices are disconnected can be delivered after reconnect; invalid, malformed, or already-expired latest session state fails closed and clears any older Wear session instead of leaving stale credentials active.

These repository implementations do not by themselves prove production deployment or the full physical-device interaction matrix.

## Review findings and hardening

- Both Android apps disable cleartext traffic and backups. The phone manifest was hardened with `android:allowBackup="false"` so persisted feed/account-adjacent state is not included in normal Android backup by default.
- `PodcastPlaybackService` remains non-exported. Launcher, Tile, complication and Wear Data Layer components retain only the exported state required by their platform integration.
- CI includes deterministic release manifest guards, JVM tests, debug APKs, instrumentation test APK compilation, unsigned release APK/AAB artifacts, Mobile managed-device instrumentation, and Wear small/large round emulator instrumentation.
- Gateway tests cover server-owned provider routing, fixed HTTPS upstreams, bounded request sizes, provider timeout/retry behavior, malformed output, authentication/quota bypass attempts, Google account/session boundaries, purchase-token ownership, Android Publisher verification and authenticated RTDN re-verification.
- Feed/article/transcript source text remains treated as untrusted input for summarization; structured output is validated before reaching Wear UI.
- Tiles/complications use local cached state and meaningful-change updates rather than render-time feed/AI polling.
- Data Layer inputs are treated as versioned state boundaries rather than implicitly trusted serialization: feed identity is canonicalized, item-state origin ownership is checked, and account-session state is durable across reconnect while failing closed on invalid latest state.

## Current CI/release checkpoint

Repository-controlled checks now include:

- Wear JVM tests;
- Wear debug assembly;
- Wear debug instrumentation-test APK assembly;
- Wear OS small-round emulator execution at system font scale `1.30`, including navigation/state-restoration, MediaSession controller reconnect/state-continuity coverage for both prepared and actively playing local media, and installed Tile/Complication provider contract/preview checks;
- Wear OS large-round emulator execution at the default font scale, including the same instrumentation suite;
- Mobile JVM tests;
- Mobile debug assembly;
- Mobile debug instrumentation-test APK assembly;
- Mobile managed-device execution of instrumentation smoke tests;
- Gateway TypeScript typecheck;
- Gateway Vitest;
- release manifest guard;
- unsigned Wear/Mobile release APK + AAB assembly and artifact upload on `main` pushes.

CI #327 verified commit `7ca0c702f5b4451a24cfe5df454e698f2487ee7d`: release guard, Gateway typecheck/Vitest, Wear and Mobile JVM/debug/instrumentation APK builds, unsigned release APK/AAB builds, Mobile managed-device instrumentation, and both Wear small-round (`1.30` font scale) and large-round instrumentation completed successfully. In addition to the deterministic local-media continuity checks, the Wear suite now queries the installed package for both Tile providers and the unread complication provider, verifies their platform bind permissions/actions and complication metadata, resolves the installed service components, and builds SHORT_TEXT/LONG_TEXT complication preview data while confirming an unsupported preview type returns no data.

These smoke suites prove launch/recreation/navigation, configured large-font layout interaction, MediaSession-service connection/lifecycle behavior, prepared and actively-playing local-media controller reconnect continuity, installed Wear surface provider registration/preview contracts, and the JVM-level Data Layer validation/state semantics now checked into the repository. They do **not** substitute for rotary-input testing, Bluetooth/noisy-route/audio-focus behavior, persisted resume after process/service death, a real paired-device disconnect/reconnect transport exercise, actual Tile/Complication host rendering/tap/update propagation, Play validation, or the broader physical-device matrix below.

## Remaining external or execution-dependent release blockers

The following work remains intentionally unclaimed:

- apply the D1 migrations/bindings to a controlled Cloudflare environment and smoke-test the deployed Gateway with real non-repository secret bindings;
- configure the actual Play Console subscription/base plans/test accounts and exercise a license/internal-test purchase against the deployed verifier;
- configure the actual Google Cloud Pub/Sub RTDN topic and authenticated push subscription, then exercise live push delivery against the configured production-like audience/service account;
- execute the full Play lifecycle matrix: active, cancellation-with-time-remaining, grace period, account hold, expiration and revoke;
- configure the established Play App Signing/upload-key path and upload a traceable AAB to the Play internal-testing track;
- expand Android/Wear instrumentation beyond the current launch/recreation/navigation/media/provider-contract checks into additional deterministic interaction flows where practical;
- execute rotary-input checks on representative Wear profiles; CI now covers the small-round profile at system font scale `1.30` and the large-round size dimension, but it does not synthesize representative crown/rotary interaction;
- exercise Bluetooth controls, noisy-route/audio-focus, persisted resume after service/process recreation, and representative hardware-specific media behavior; CI now covers continuously playing local media across controller disconnect/reconnect but not these device/audio-route boundaries;
- exercise paired phone↔Wear Data Layer flows across connected, disconnected and reconnected states; the account-session transport now stores latest state durably, but CI still does not run a paired phone/watch topology that proves the platform transport path end-to-end;
- verify actual Tile and complication host rendering/readability, tap launch behavior, and update propagation on Wear OS. Provider registration, permissions, metadata and complication preview construction are now covered on both CI Wear profiles, but those host-level behaviors are not.

No production credential, signing key or console-only result should be committed merely to clear one of these blockers.

## Recommended next release-readiness order

1. Expand the now-running instrumentation suites around deterministic app interaction/state restoration, rotary behavior, Tile/Complication behavior and media lifecycle where CI can test them reliably.
2. Add a paired phone/Wear execution topology when CI infrastructure can support it, then exercise Data Layer set/clear/state convergence across disconnect/reconnect rather than relying only on contract/JVM checks.
3. Deploy a non-production Gateway with the real D1 bindings/migrations and secret-store configuration, then run security/auth/membership smoke checks against that deployment.
4. Wire Play internal testing plus authenticated RTDN in Google Cloud and execute the complete purchase/restore/lifecycle matrix.
5. Sign and upload a traceable AAB through the established Play App Signing path and record the exact source SHA/artifact digest/version.
6. Complete the MediaSession, Tile/Complication and accessibility/device matrix before widening distribution.

## Release-ready boundary

Do not call WristBrief production-ready until the repository gates are green **and** the external deployment, signed Play internal testing, billing/RTDN lifecycle checks, and phone/Wear device matrix above have all been exercised successfully. CI emulator/managed-device smoke tests, repository-level mocks, or unsigned release artifacts are not substitutes for those gates.
