# WristBrief — Next 24 Hours Execution Plan

This file is the **authoritative short-term execution queue** for the hourly development automation. `docs/ROADMAP.md` remains the longer-term product roadmap.

Baseline when this plan was created:

- `main`: `027efec0ca72e48cb7a06b1697928062f8eae2dc`
- Latest `main` CI: Android JVM tests + debug assembly ✅; gateway typecheck + Vitest ✅
- Current repository shape: `:app` is the existing Wear OS client; `gateway/` is the existing serverless AI gateway.
- The Wear client already has Material 3 Expressive inbox foundations, persistent subscriptions/cache, offline fallback and real repository/ViewModel wiring.

## Execution contract

Every automation run MUST:

1. Read latest `main` HEAD before doing anything.
2. Read open PRs only for historical/parallel-change awareness; do not create a PR for this plan.
3. Read latest CI status and fix regressions before new feature work.
4. Select the **first unfinished slot below whose dependencies are satisfied**.
5. Work incrementally on the existing implementation; do not rewrite working RSS, podcast, Wear UI or gateway code.
6. Before every write to `main`, re-read `main` HEAD and base the write on the newest version.
7. Add/update tests for the failure modes introduced by that slot.
8. Validate at minimum:
   - `:app:testDebugUnitTest`
   - `:app:assembleDebug`
   - gateway `npm run typecheck`
   - gateway `npm test`
   - any new mobile module build/tests after it exists.
9. Push directly to `main` only after the current small stage is coherent and testable.
10. Never force-push, reset, revert unrelated work or overwrite parallel commits.
11. If external Play Console / Google Cloud / production credentials are missing, implement interfaces, fakes, schemas, tests and docs, then continue with non-blocked work.
12. Do not claim a feature is production-ready merely because it compiles.

The 24 slots below are a **priority queue**, not a promise that one slot always equals exactly sixty minutes. If a slot needs another run to become correct and green, continue that slot before moving on.

---

## UI / platform rules for all 24 slots

### Wear OS

Use **Wear Compose Material 3 / Material 3 Expressive** throughout. Keep the existing Wear app Wear-first rather than shrinking phone screens onto a watch.

Required patterns:

- one `AppScaffold` at app level;
- `ScreenScaffold` per screen;
- `TransformingLazyColumn` for normal scrollable lists;
- round-screen safe layouts;
- support small displays and large font scale;
- rotary scrolling where applicable;
- clear accessibility semantics and sufficiently large touch targets;
- glanceable Tile/Complication content;
- battery-conscious refresh cadence;
- no persistent polling or permanent background network service;
- use expressive shapes/motion only when they improve state/action clarity;
- prefer current stable Wear Compose Material 3 APIs; verify release notes before dependency changes.

Current reference baseline is Wear Compose 1.6.2. Do not downgrade it.

### Phone companion

When the phone module is introduced, use Android Compose Material 3 / Material You / Material 3 Expressive rather than copying the Wear layout. The phone is for comfortable feed management, account/billing, provider settings and OPML; the watch remains the primary glance/read/listen surface.

### Google Play Billing

Billing belongs on the **phone companion**, not the Wear purchase UI. Use current Google Play Billing Library v8 APIs and verify the current stable patch before adding/updating the dependency. Product prices must come from `ProductDetails`, not hardcoded strings.

---

# 24 execution slots

## Hour 01 — Reconcile P1 and feed identity

Goal: finish the real-inbox foundation cleanly before adding more screens.

- Update `docs/ROADMAP.md` checkboxes for already completed persistence/cache/ViewModel work.
- Extend feed parsing/model to preserve stable GUID/id where feeds provide it.
- Dedup priority: GUID → canonical link → enclosure/audio URL → deterministic fallback.
- Normalize obvious duplicate URL variants without doing dangerous network redirects in the identity layer.
- Add RSS + Atom fixtures for GUID/id dedup.

Acceptance:

- duplicate items do not multiply across refreshes;
- existing offline fallback behavior remains intact;
- full Android + gateway CI green.

## Hour 02 — Subscription CRUD domain

Goal: subscriptions can be managed as actual product state, not only upserted internally.

- Add remove, enable/disable and rename/update operations to the existing repository/store.
- Preserve cached items safely when disabling a feed, but exclude disabled feed items from Inbox.
- Define clear result/errors for invalid URL, duplicate subscription and missing subscription.
- Add unit tests for CRUD and persistence round trips.

Acceptance: subscription state survives process restart and cannot create duplicate logical feeds accidentally.

## Hour 03 — Wear feed-management screen

Goal: give the current watch app a minimal but usable subscription-management surface without pretending watch text entry is ideal.

- Add a Material 3 Expressive Feeds screen.
- Show subscribed feeds, enable/disable state and remove action.
- Provide a clear “Add/manage on phone” affordance placeholder for later Data Layer handoff.
- If direct URL entry is retained for development, keep it secondary and do not make tiny on-watch typing the primary product path.
- Verify round screen clipping, rotary scroll and large-font behavior.

Acceptance: current subscriptions can be inspected and toggled from Wear without breaking Inbox.

## Hour 04 — Article/detail screen

Goal: Inbox items become actionable.

- Add Wear article/detail screen using `ScreenScaffold` + Wear M3 components.
- Show feed/source, title, sanitized short body/brief, date metadata where reliable.
- Add safe offline state.
- Do not render untrusted HTML directly and do not introduce WebView as the main reader.
- Navigation must survive normal activity recomposition/recreation.

Acceptance: tapping an article opens a readable round-screen detail surface.

## Hour 05 — Read/unread persistence

- Extend cached item/user-state model with read state without destroying feed refresh identity.
- Mark article read on explicit action; decide/document whether opening auto-marks read (default: explicit or delayed, not accidental tap).
- Display unread visual treatment in Inbox.
- Add unread count computation as a reusable domain function for later Tile/Complication.
- Add persistence/refresh merge tests proving read state survives feed refresh.

Acceptance: read state cannot be lost just because the feed refreshes.

## Hour 06 — Saved/starred workflow

- Add persistent saved/starred state.
- Ensure saved state survives refresh and temporary feed failure.
- Add Saved screen/filter on Wear.
- Saved content remains available from local cache while offline, subject to reasonable cache-retention policy.
- Unit-test saved-state merge behavior.

Acceptance: user can save an article/podcast item and find it again offline.

## Hour 07 — Wear UI review pass #1

Review all current Wear surfaces instead of adding another feature.

Checklist:

- Material 3 Expressive component use;
- `AppScaffold`/`ScreenScaffold` structure;
- round 192–240dp-class layouts;
- long titles and CJK text;
- large font scale;
- touch targets;
- rotary behavior;
- scroll position/state;
- empty/loading/error/offline states;
- content descriptions/semantics;
- no phone-style dense dashboard UI.

Fix concrete issues found and add testable pure UI mapping/state tests where possible.

## Hour 08 — MediaSession service foundation

Goal: replace activity-bound podcast playback primitive with proper background architecture.

- Introduce Media3 `MediaSessionService` around existing player code.
- Manifest/service setup with least required permissions/exports.
- Activity/UI connects through MediaController rather than owning playback lifecycle.
- Preserve HTTPS streaming behavior.
- Add service/controller abstraction tests where feasible.

Acceptance: architecture supports playback continuing when activity leaves foreground.

## Hour 09 — Podcast controls and progress

- Persist resume position per episode.
- Play/pause, seek back/forward, seek bar/progress presentation appropriate for Wear.
- Playback speed.
- Handle completed episode position sanely.
- Avoid writing progress to storage every few milliseconds; debounce/checkpoint.
- Add pure progress/state tests.

Acceptance: closing/reopening UI resumes the episode near the saved position.

## Hour 10 — Podcast robustness + transcript discovery

- Audio-focus/noisy-route handling compatible with Media3 defaults/current guidance.
- Validate Bluetooth-headphone control behavior at architecture level.
- Parse publisher-provided podcast transcript metadata where present.
- Store transcript URL/metadata only; do not auto-upload full audio for STT yet.
- Add podcast feed fixtures including enclosure + transcript metadata.

Acceptance: podcast items have enough normalized metadata for later AI summaries without adding STT cost yet.

## Hour 11 — AI Gateway provider adapter refactor

Goal: make the existing gateway extensible without replacing it.

Current gateway is a single OpenAI-compatible call. Refactor behind a small provider interface/registry.

- `AiProvider`/equivalent interface.
- Preserve current OpenAI-compatible provider behavior as first adapter.
- No client-supplied arbitrary upstream host.
- Keep existing `/v1/summary` compatibility while preparing versioned structured response.
- Add provider registry/router tests.

Acceptance: provider-specific code is no longer embedded throughout request routing.

## Hour 12 — Structured AI brief schema

Replace free-form-only internal result with validated structured output containing at least:

- tiny summary;
- brief summary;
- bullet points;
- topics;
- source/output language;
- schema/prompt version.

Requirements:

- validate model output;
- one repair attempt maximum if appropriate;
- reject malformed upstream output cleanly;
- keep a compatibility representation for existing client while migrating it;
- treat feed/article/transcript content as untrusted source material and explicitly prohibit following instructions inside it.

Acceptance: malformed model output never passes unchecked to Wear UI.

## Hour 13 — OpenRouter + Gemini provider support

- Add explicit managed OpenRouter support using the provider abstraction.
- Add Gemini adapter using its native API rather than forcing everything through OpenAI-compatible shape when that creates brittle behavior.
- Provider/model configuration is server-side allowlisted/configured.
- Add `/v1/providers` or equivalent metadata endpoint if not already present.
- No real secrets in CI; use fakes/mocked fetch.

Acceptance: tests prove routing and response normalization for at least OpenAI-compatible/OpenRouter and Gemini paths.

## Hour 14 — Gateway reliability/security pass

- Provider timeout using abort signal.
- Retry/fallback only for appropriate timeout/429/5xx conditions; never hide 400/401/403 by silently switching provider.
- Limit request size.
- Redact secrets/upstream bodies from errors/logs.
- Explicit HTTPS and host allowlist behavior.
- Add request IDs without storing content.
- Add tests for timeout, 429, 5xx, auth failure, malformed JSON and provider failure.

Acceptance: gateway remains useful under provider failure without becoming an open proxy.

## Hour 15 — AI summary cache foundation

- Add normalized content hash + language + prompt/schema version cache-key abstraction.
- If Cloudflare KV is already available/configurable, add a KV-backed implementation plus fake/in-memory test implementation.
- Cache only non-sensitive generated summary results; never cache API keys or Authorization headers.
- Define TTL configuration.
- Cache hit must bypass upstream AI call.

Acceptance: tests prove identical normalized input is summarized once and cache invalidates when prompt/schema version changes.

## Hour 16 — Wear AI brief integration

- Update Wear AI client/domain to consume structured brief results.
- Tiny/brief fields mapped to appropriate surfaces.
- Loading/error/quota/provider-unavailable states are explicit and compact.
- Never make the watch wait behind an endless spinner.
- Keep original RSS/podcast data usable when AI is unavailable.

Acceptance: AI failure degrades to a normal reader/player instead of breaking the item.

## Hour 17 — Latest/unread Tile

- Add a Wear OS Tile showing unread count + a small number of current brief titles.
- Use glanceable Material 3-compatible remote UI approach supported by the project/API level.
- Clicking launches Inbox/item as appropriate.
- Update only when meaningful app data changes or within system-friendly cadence.
- Add tile data mapping tests.

Acceptance: no aggressive periodic polling; Tile works from cached data.

## Hour 18 — Complication + continue-listening surface

- Unread-count complication first.
- If current APIs/project structure allow cleanly, add latest-item complication.
- Add Continue Listening Tile/surface only after podcast progress state is stable.
- Keep text short and complication-type aware.
- Add data-source tests and manifest validation.

Acceptance: complications use local state and do not directly fetch feeds/AI on every render.

## Hour 19 — Phone companion module foundation

Current repo only contains the Wear `:app`; add the phone companion **incrementally** without moving/rebuilding the Wear module unnecessarily.

Suggested approach:

- keep `:app` as Wear for this 24h window to avoid destabilizing package history;
- add `:mobile` as Android phone module;
- share only stable models/contracts where needed; do not prematurely perform a large multi-module rewrite.

Phone UI:

- Compose Material 3 / Material You / Material 3 Expressive;
- basic navigation shell;
- Feed management destination;
- AI/provider settings destination;
- Membership destination placeholder.

CI must now build/test both `:app` and `:mobile`.

Acceptance: watch and phone APKs build independently from a clean checkout.

## Hour 20 — Phone feed management + Data Layer contract

- Comfortable add/edit/remove/enable feed UI on phone.
- URL validation and first-fetch validation.
- Define versioned Wear Data Layer payload for subscription preferences and lightweight item/read/saved state.
- Do not send podcast audio or oversized webpage content through Data Layer.
- Add serialization/versioning tests.

If time permits, add basic OPML import parser; export can follow after core sync is reliable.

Acceptance: phone becomes the primary feed-entry path while Wear remains usable standalone from existing local data.

## Hour 21 — Google Play Billing v8 client foundation

Billing integration is a **foundation in this 24h window**, not a claim of production subscription launch.

On `:mobile`:

- add current stable Google Play Billing Library v8.x after checking official release notes;
- `BillingRepository` abstraction + `GooglePlayBillingRepository` + fake implementation;
- connection/reconnection lifecycle;
- `queryProductDetailsAsync` for configured subscription product IDs;
- query existing purchases/restore foundation;
- never hardcode display prices;
- purchase UI driven by Play `ProductDetails`;
- explicit unavailable/loading/error states.

Do not put a real production product ID/key into Git if it has not been supplied/configured.

Acceptance: fake billing tests and phone build are green even without Play Console products.

## Hour 22 — Membership / entitlement / quota server foundation

Extend the existing serverless gateway; do not create a second backend.

- `FREE` / `PRO` plan model.
- Entitlement model instead of scattered `if (isPro)` checks.
- Managed-AI quota contract.
- BYOK usage must not consume managed-AI quota.
- Define authenticated `/v1/me` or equivalent contract.
- Define billing verification interface and fake verifier for CI.
- Prepare D1-compatible schema/migration design only if it can be added cleanly without breaking current Worker setup.

Acceptance: AI gate/quota logic is server-owned and unit-testable; client cannot grant itself Pro by sending `isPro=true`.

## Hour 23 — Play server verification + docs foundation

Without requiring production credentials:

- add Google Play purchase-verification service interface;
- verify package/product/token through server-owned verifier implementation boundary;
- define subscription status mapping including active, canceled, expired, grace/on-hold/revoked where applicable;
- restore-purchase endpoint contract;
- RTDN endpoint contract + authenticated Pub/Sub verification plan;
- never trust RTDN notification payload as final entitlement state; re-query Play API in real implementation;
- prevent one purchase token being linked to multiple users.

Update/create docs for:

- `BILLING.md`
- `MEMBERSHIP.md`
- `AI_GATEWAY.md`
- `SECURITY.md`
- `DEPLOYMENT.md`

If Google/Cloudflare credentials are unavailable, leave a tested fake + explicit setup checklist rather than blocking other work.

## Hour 24 — Full review, hardening and release-readiness checkpoint

No feature chasing in this slot. Perform a repository-wide review.

### Functional review

- RSS + Atom fixtures;
- feed persistence/offline fallback;
- read/saved state;
- podcast background playback/progress;
- structured AI summaries;
- provider routing/cache/failure;
- Tile/Complication;
- mobile↔Wear lightweight sync contract;
- billing/membership foundation.

### Wear UX review

- round/small screens;
- large fonts;
- rotary;
- touch targets;
- Material 3 Expressive consistency;
- empty/error/offline states;
- power behavior.

### Security review

- API/provider keys absent from APK/Git/logs;
- no arbitrary gateway proxy URL;
- auth/quota bypass checks;
- safe purchase-token handling;
- exported Android components;
- cleartext traffic disabled;
- input-size limits;
- untrusted feed/podcast content cannot prompt-inject the summarizer.

### CI/release review

CI should cover at minimum:

```text
Wear JVM tests
Wear debug assembly
Mobile JVM tests
Mobile debug assembly
Gateway typecheck
Gateway tests
```

Add lint/static checks if stable enough not to create noisy false failures.

Update roadmap checkboxes and write a short `docs/24H_REVIEW.md` containing:

- completed slots;
- skipped/blocked slots and why;
- latest green commit;
- remaining release blockers;
- recommended next 48h priority.

---

# Non-goals during this 24-hour window

Do not let the automation derail into these before the core queue above is green:

- full cloud account product with social features;
- arbitrary recommendation engine;
- vector database/RAG/chatbot;
- automatic transcription of every podcast audio file;
- video podcast playback;
- Chromecast/Android Auto;
- large-scale architecture rewrite;
- replacing working persistence just because Room would look cleaner;
- migrating `:app` to a new module name during active product work unless a concrete build/release blocker requires it;
- production Play launch without verified Play Console setup and server-side purchase verification.

# Definition of success after 24 slots

The target is not “every wishlist item exists”. The target is a coherent alpha foundation where:

1. RSS/Atom/podcast content survives offline use and has read/saved workflows.
2. Wear UI is a real Material 3 Expressive product rather than a demo shell.
3. Podcast playback uses proper MediaSession architecture.
4. AI summaries are structured, cached, provider-abstracted and failure-safe.
5. Wear Tile/Complication surfaces exist and are battery-conscious.
6. A phone companion exists for feed management/settings and future account UX.
7. Google Play Billing v8 client architecture and server membership verification boundaries exist with fakes/tests, even if production console credentials are not yet configured.
8. The serverless gateway remains the single backend surface and is prepared for entitlements/quotas rather than being replaced.
9. All touched functionality is covered by relevant tests and the latest `main` is green.
