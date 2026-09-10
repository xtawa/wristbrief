# WristBrief roadmap

This roadmap is intentionally incremental. Do not replace working RSS, podcast, Wear UI, or gateway code just to match an idealized architecture.

## Current short-term execution plan

The 24-slot execution window in [`docs/NEXT_24_HOURS.md`](./NEXT_24_HOURS.md) is complete once Slot 24 is marked green in [`docs/NEXT_24_HOURS_STATUS.md`](./NEXT_24_HOURS_STATUS.md). After that checkpoint, resume the broader priorities below and use [`docs/24H_REVIEW.md`](./24H_REVIEW.md) as the release-readiness handoff.

## Product direction

WristBrief is a Wear OS-first inbox for RSS, Atom and podcast feeds. The watch should help a user understand what is worth reading or listening to in seconds, then let them continue on the phone or play audio directly.

## UX rules

- Use Wear Compose Material 3 / Material 3 Expressive components.
- Keep a single `AppScaffold`; use `ScreenScaffold` per screen.
- Prefer `TransformingLazyColumn` for scrollable Wear screens.
- Respect round-screen clipping, large font scales, rotary input, accessibility semantics and touch target guidance.
- Do not clone a phone UI onto the watch.
- Avoid persistent polling or unnecessary wakeups.
- Tiles and complications must remain glanceable and short.

## P0 — Build and test baseline

- [x] Android debug assembly in CI.
- [x] Android JVM unit tests in CI.
- [x] Gateway strict TypeScript checking.
- [x] Gateway Vitest suite.
- [x] Keep all checks green on direct-to-main product commits.

## P1 — Real inbox data

- [x] Persistent feed subscriptions.
- [x] Local cached feed items for offline viewing.
- [x] Repository/ViewModel state for loading, success, empty and error states.
- [x] Wire parsed RSS/Atom/podcast items into the Wear inbox.
- [x] Refresh without blocking the UI thread.
- [x] Feed deduplication using GUID/canonical URL/content identity.
- [x] Complete dedup fixtures while retaining persistence/refresh-failure coverage.

## P2 — Reading workflow

- [x] Article/detail screen optimized for a round display.
- [x] Read/unread state.
- [x] Saved/starred state.
- [x] Basic sanitized article text.
- [x] Open/continue on phone flow when a paired phone is available.
- [x] Clear offline and extraction-error states.

## P3 — Podcast playback

- [x] Move playback behind a Media3 `MediaSession` service.
- [x] Background playback and notification/media controls.
- [x] Resume position persistence.
- [x] Seek controls and playback speed.
- [x] Bluetooth audio behavior and audio-focus handling.
- [x] Podcast transcript discovery when published in the feed.
- [x] Do not send podcast audio through the Wear Data Layer.

## P4 — AI briefs

- [x] Structured summary response instead of a single free-form string.
- [x] Tiny summary for Tile/complication surfaces.
- [x] Brief summary for watch detail.
- [x] Longer summary for a future phone companion.
- [x] Prompt-injection-resistant summarization instructions.
- [x] Summary caching keyed by normalized content, language and prompt/schema version.
- [x] Provider timeout and safe retry/fallback behavior.

## P5 — Gateway providers

- [x] Refactor the current OpenAI-compatible call behind a provider adapter.
- [x] Managed OpenAI-compatible provider.
- [x] OpenRouter configuration.
- [x] Gemini adapter.
- [ ] Anthropic adapter if product demand warrants it.
- [x] BYOK support without shipping managed provider secrets in the APK.
- [x] Never allow the public gateway to proxy arbitrary client-supplied hosts.

## P6 — Wear surfaces

- [x] Latest/unread Tile.
- [x] Continue-listening Tile.
- [x] Unread-count complication.
- [x] Optional latest-item complication where the complication type has enough space.
- [x] Update cadence designed around battery limits rather than aggressive polling.

## P7 — Feed management

- [x] Phone companion for comfortable feed entry and management.
- [x] OPML import/export.
- [x] Per-feed "send to watch" setting.
- [x] Categories/folders.
- [x] Keyword watch filter.
- [x] Standalone watch refresh when the phone is unavailable and the watch has network access.

## P8 — Membership and quotas

Only implement after the managed AI path and identity model are stable.

- [x] Serverless user identity.
- [x] Free / Pro entitlement model.
- [x] Managed AI usage quota.
- [x] BYOK does not consume managed AI quota.
- [x] Google Play Billing purchase flow on the phone.
- [x] Server-side purchase verification with the real Google Android Publisher API.
- [x] Restore purchases contract/client foundation.
- [x] Production RTDN lifecycle handling with authenticated Pub/Sub verification.
- [x] Authoritative entitlement state lives server-side, not in client preferences.

### P8.1 — Google account identity + membership binding (planned)

Implement after the current production RTDN hardening, and before calling the membership system production-ready. Keep this inside the existing serverless AI Gateway; do not introduce a separate account backend only for login or membership.

#### Target framework

- [x] Use Google OAuth / OpenID Connect on the phone as the primary account sign-in path.
- [x] The phone obtains a Google ID token through the current Android-recommended identity flow; the Gateway verifies the token server-side before trusting any identity claim.
- [x] Never use Google email as the durable membership key. Persist a provider identity keyed by `(provider = google, providerSubject = Google sub)` and map it to an immutable WristBrief `userId`.
- [x] Keep entitlement, managed-AI quota, Play purchase ownership and future account data keyed by the internal `userId`, not by device ID, email, Android account name or raw purchase token.
- [x] Use durable serverless storage for authoritative account state (D1-compatible schema preferred). KV/cache may accelerate reads but must not become the membership source of truth.
- [x] Keep the current Gateway as the single server authority for `/v1/me`, auth sessions, entitlement and quota decisions.
- [x] Do not ship Google client secrets, Play service-account material, session signing secrets or provider credentials in the APK.

#### Authentication flow

1. Phone starts Google sign-in and receives a short-lived Google ID token.
2. Phone sends the ID token over HTTPS to a dedicated Gateway endpoint such as `POST /v1/auth/google`.
3. Gateway verifies the Google signature/JWK chain and validates at minimum `iss`, exact configured `aud`, `exp`, `sub` and the required email/account verification claims. Client-supplied email or user IDs are never authoritative.
4. Gateway upserts the `(google, sub)` identity and resolves or creates the immutable internal WristBrief `userId`.
5. Gateway issues a WristBrief session/access credential for later API calls; credentials must be revocable/expiring and must not be logged.
6. `/v1/me` resolves the session to the internal `userId` and returns server-owned plan, entitlement and managed-AI quota state.
7. Signing out clears the local WristBrief session; server sessions expire/revoke independently of the Google ID token. Switching Google accounts must never silently retain another account's membership state.

#### Google Play membership binding flow

1. Require/resolve an authenticated internal `userId` before a Play purchase can grant or restore Pro.
2. Phone submits the Play purchase token only to the authenticated billing endpoint.
3. Gateway verifies the purchase with Google Android Publisher API and validates the configured package/product allowlist.
4. Store only the one-way purchase-token hash/ownership binding and associate it with the internal `userId`; never use the raw token as an account identifier.
5. Reject implicit transfer when the same purchase token is already owned by another WristBrief `userId`; add an explicit recovery/support policy later rather than guessing ownership.
6. RTDN verifies authenticated Pub/Sub push, re-queries Google Play, resolves the purchase-token hash to the owning `userId`, and updates that user's authoritative entitlement.
7. Reinstalling the app or signing in on another Android device with the same Google `sub` resolves to the same WristBrief `userId`, so `/v1/me` can restore Pro without relying on old local preferences.

#### Planned serverless data model

- [x] `users`: immutable WristBrief user ID + created/status metadata.
- [x] `identities`: provider, provider subject (`sub`), user ID, optional display/email metadata; unique on provider + provider subject.
- [x] `sessions`: revocable/expiring session records or equivalent signed-session design with a server-side revocation strategy; never store raw bearer tokens when a one-way hash is sufficient.
- [x] `entitlements`: authoritative FREE/PRO state, source, expiry/status metadata and last verification time.
- [x] `play_purchase_bindings`: purchase-token hash -> internal user ID + package/product/status metadata; raw purchase token is not persisted unless a narrowly justified Google re-query workflow requires protected storage.
- [x] `quota_usage`: managed-AI quota counters keyed by internal user ID and quota window; BYOK remains excluded.

#### Migration and recovery

- [x] Define a one-time upgrade path from the current serverless identity to Google-backed identity without silently creating duplicate paid accounts.
- [x] On first Google sign-in, explicitly link the existing authenticated WristBrief identity/session to the verified `(google, sub)` only after ownership checks pass.
- [x] Preserve existing entitlement/quota state when linking identities; add tests for duplicate Google identity, conflicting purchase ownership and repeated linking.
- [x] Define account deletion, session revocation and Google-identity unlink behavior before production launch.
- [x] Keep account merging/recovery explicit and auditable; never merge accounts solely because email strings match.

#### Acceptance

- Same Google account on a fresh install resolves to the same internal WristBrief user and receives the same server-owned membership state.
- Different Google accounts cannot inherit each other's Pro entitlement, quota, Play purchase binding or sessions.
- Client-modified email/user ID/`isPro` fields cannot grant membership.
- Play restore and RTDN update entitlement by internal user ID after real Google verification.
- No Google ID token, raw purchase token, OAuth authorization header or session bearer token appears in Git, application logs or public error bodies.
- Gateway remains serverless and is still the only required backend for AI, identity, membership and quota.

## P9 — Optional high-value features

These are candidates, not commitments. Validate demand before building them.

- AI daily/morning digest.
- AI ranking of high-priority feed items.
- Podcast transcript summary and chapter/key-moment generation.
- Article text-to-speech / unified Listen Later queue.
- FreshRSS adapter with read/star synchronization.
- Miniflux adapter.
- Inoreader adapter.
- GitHub release feed presets.
- YouTube RSS presets.
- Podcast offline downloads and auto-download rules.
- Watch-only keyword alerts for selected feeds.

## Testing expectations

Every functional phase must add tests relevant to its failure modes.

Minimum ongoing validation:

```text
Wear JVM tests
Wear debug assembly
Gateway TypeScript typecheck
Gateway Vitest
Mobile JVM tests
Mobile debug assembly
Release manifest guard
```

As persistence, MediaSession, Data Layer, billing and migrations mature, add targeted integration/instrumentation tests instead of relying only on compilation.

## Release gate

Do not call WristBrief production-ready until all of the following are true:

- Core RSS/Atom parsing has representative fixtures and malformed-feed coverage.
- Podcast playback survives activity recreation/backgrounding on the target device matrix.
- Offline inbox behavior is deterministic.
- Gateway errors do not expose provider credentials or upstream bodies.
- Wear screens pass round/small display and large-font review on devices/emulators.
- Tile/complication behavior is battery-conscious.
- CI is green from a clean checkout.
- Real Play verification/RTDN and durable production membership storage are deployed and exercised in an internal-test environment.
- Release signing/versioning, deployment, secret-management, privacy and recovery docs/checklists are complete.
