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
- [ ] BYOK support without shipping managed provider secrets in the APK.
- [x] Never allow the public gateway to proxy arbitrary client-supplied hosts.

## P6 — Wear surfaces

- [x] Latest/unread Tile.
- [x] Continue-listening Tile.
- [x] Unread-count complication.
- [x] Optional latest-item complication where the complication type has enough space.
- [x] Update cadence designed around battery limits rather than aggressive polling.

## P7 — Feed management

- [x] Phone companion for comfortable feed entry and management.
- [ ] OPML import/export.
- [ ] Per-feed "send to watch" setting.
- [ ] Categories/folders.
- [ ] Keyword watch filter.
- [x] Standalone watch refresh when the phone is unavailable and the watch has network access.

## P8 — Membership and quotas

Only implement after the managed AI path and identity model are stable.

- [x] Serverless user identity.
- [x] Free / Pro entitlement model.
- [x] Managed AI usage quota.
- [x] BYOK does not consume managed AI quota.
- [x] Google Play Billing purchase flow on the phone.
- [ ] Server-side purchase verification with the real Google Android Publisher API.
- [x] Restore purchases contract/client foundation.
- [ ] Production RTDN lifecycle handling with authenticated Pub/Sub verification.
- [x] Authoritative entitlement state lives server-side, not in client preferences.

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
