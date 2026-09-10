# Static release review — 2026-09-11

This review was performed while GitHub Actions execution was unavailable because the repository/account Actions allowance was exhausted. Findings marked **fixed statically** have code/test-source changes, but are **not CI-verified yet**. Re-run the full repository CI before treating any of them as validated.

## Fixed statically in this review

### D1 Google identity linking order

`identities.user_id` references `users.id` with foreign keys enabled. The legacy-to-Google link path previously attempted to insert the identity before ensuring that the legacy user existed in `users`, which could fail on a real D1 database even though the FakeD1 tests passed.

The link batch now creates the candidate/existing user row before inserting the Google identity, removes an unreferenced losing candidate after an identity conflict, and derives `created` from the identity insertion rather than the user insertion. FakeD1 now enforces the same parent-before-child requirement so the ordering regression is testable.

### Corrupt Data Layer subscription payloads

The phone encoder always emits the required `subscriptions` array. Both decoders previously treated a missing `subscriptions` field as an empty list. On Wear this could turn a truncated/corrupt v1 Data Item into `saveSubscriptions(emptyList())` and wipe the local subscription list.

A missing `subscriptions` field is now invalid, while an explicit `"subscriptions": []` remains a valid intentional clear operation. Matching phone/Wear tests document this distinction.

### Google Play RTDN test publishes

Google Play Console can send authenticated `testNotification` RTDN payloads. The Gateway previously accepted only `subscriptionNotification`, so a valid console test publish returned `400 invalid_rtdn`.

Authenticated test notifications for the configured package are now acknowledged with `204` without touching entitlement state or querying the Android Publisher API. A payload that ambiguously contains both test and subscription notification branches is rejected.

### Phone/Wear Data Layer application identity

The Wearable Data Layer only connects the same application across devices: package names and signing identities must match. The phone companion previously used `ink.underflo.wristbrief.mobile` while Wear used `ink.underflo.wristbrief`, so the implemented Data Layer code could not work on a real paired phone/watch installation.

Both form factors now use the Play package `ink.underflo.wristbrief` while retaining independent Kotlin namespaces. Multi-APK version-code lanes are intentionally distinct and ordered:

- mobile: `2xxxxxx` lane (`2000100` for 0.1.0)
- Wear: `3xxxxxx` lane (`3000100` for 0.1.0)

The Wear lane remains higher because the Wear APK has the more specific watch targeting and higher minimum SDK in the overlapping multi-APK model. Production phone and Wear artifacts must also use the same Play signing identity.

### Legacy Gateway bearer in the Wear APK

The Wear Gradle module previously accepted `WRISTBRIEF_GATEWAY_TOKEN` and compiled it into `BuildConfig.AI_GATEWAY_TOKEN`. A server-wide bearer embedded in an APK is extractable and is incompatible with the Google-account/session architecture.

The Wear build no longer accepts or embeds that server bearer. Until the scoped WristBrief account session is bridged to Wear at runtime, Wear AI must fail closed as unconfigured rather than fall back to a shared server credential.

The release guard now enforces the shared cross-device application ID, distinct/ordered version-code lanes, explicit Wear standalone metadata, and absence of the legacy Wear Gateway token build input.

## Open release blockers found by static review

### Runtime WristBrief session bridge to Wear

**Not implemented yet.** The phone has a revocable `wbs_...` account session after Google sign-in, while the Wear AI client currently expects a bearer token. A scoped, expiring WristBrief session needs to be bridged at runtime from phone to Wear; the old server-wide `GATEWAY_TOKEN` must not return to the APK.

The Wearable Data Layer is an appropriate transport for authentication bridging once package name and signing identity match. Treat the session as sensitive: do not log it, do not include it in public errors, overwrite/clear the synchronized state on sign-out/account switch, validate its format/expiry on Wear, and let the Gateway remain authoritative for revocation and entitlement.

Until this exists, the phone account/membership flow remains usable, but Wear managed-AI requests are intentionally unavailable in secure release builds.

### Read/saved state contract is not wired end-to-end

`WearSyncPayload` contains `readItemIds` and `savedItemIds`, but the current phone feed publisher creates payloads with their default empty sets and the Wear subscription receiver only applies subscriptions. Therefore the schema must not be mistaken for completed read/saved synchronization.

Do not simply start applying those empty sets on Wear: every feed-management publish would erase watch-local state. First define ownership/merge semantics or a separate versioned state path, then add paired-device tests.

### Legacy-to-Google phone migration handoff

The Gateway has a guarded `linkLegacy=true` server path, but the current phone Google sign-in request sends only the Google ID token. There is no safe phone credential that proves ownership of the legacy server principal. Do not solve this by embedding `GATEWAY_TOKEN` in the phone APK.

Before a production migration is required, either define a one-time server-issued migration credential/recovery flow or explicitly declare that no production legacy accounts require migration. The ROADMAP acceptance language should not be interpreted as proof that a real deployed legacy-phone migration has already been exercised.

### Paired-device and production validation

Still required after GitHub Actions capacity returns and production/test credentials are available:

- full Gateway typecheck + Vitest;
- Wear and mobile JVM tests + debug assemblies;
- release guard and release artifacts;
- paired phone/Wear Data Layer test using the same package and signing certificate;
- sign-in/session bridge/account-switch/sign-out behavior;
- read/saved synchronization once its ownership rules are implemented;
- Play internal-test purchase/restore/RTDN lifecycle;
- round/small/large-font/rotary, MediaSession, Tile and complication device checks.

## Security invariants

- No managed AI/provider key, Google service-account private key, legacy Gateway bearer, raw Play purchase token, Google ID token, WristBrief session bearer or Authorization header belongs in Git or logs.
- Client builds may contain public OAuth client IDs and HTTPS Gateway origins, but not server credentials.
- Google email is metadata only; Google `sub` maps to the immutable internal WristBrief user ID.
- RTDN is a change signal. Subscription entitlement changes still require server-side Play verification.
- The Gateway must never become an arbitrary client-selected URL proxy.
