# WristBrief roadmap

This roadmap is intentionally incremental. Do not replace working RSS, podcast, Wear UI, or gateway code just to match an idealized architecture.

## Current short-term execution plan

For the next development window, follow [`docs/NEXT_24_HOURS.md`](./NEXT_24_HOURS.md) as the authoritative execution queue. The hourly automation should complete the first unfinished eligible slot there before selecting broader roadmap work.

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

- [ ] Article/detail screen optimized for a round display.
- [ ] Read/unread state.
- [x] Saved/starred state.
- [ ] Basic sanitized article text.
- [ ] Open/continue on phone flow when a paired phone is available.
- [ ] Clear offline and extraction-error states.

## P3 — Podcast playback

- [x] Move playback behind a Media3 `MediaSession` service.
- [ ] Background playback and notification/media controls.
- [x] Resume position persistence.
- [x] Seek controls and playback speed.
- [ ] Bluetooth audio behavior and audio-focus handling.
- [ ] Podcast transcript discovery when published in the feed.
- [ ] Do not send podcast audio through the Wear Data Layer.

## P4 — AI briefs

- [ ] Structured summary response instead of a single free-form string.
- [ ] Tiny summary for Tile/complication surfaces.
- [ ] Brief summary for watch detail.
- [ ] Longer summary for a future phone companion.
- [ ] Prompt-injection-resistant summarization instructions.
- [ ] Summary caching keyed by normalized content, language and prompt/schema version.
- [ ] Provider timeout and safe retry/fallback behavior.

## P5 — Gateway providers

- [ ] Refactor the current OpenAI-compatible call behind a provider adapter.
- [ ] Managed OpenAI-compatible provider.
- [ ] OpenRouter configuration.
- [ ] Gemini adapter.
- [ ] Anthropic adapter if product demand warrants it.
- [ ] BYOK support without shipping managed provider secrets in the APK.
- [ ] Never allow the public gateway to proxy arbitrary client-supplied hosts.

## P6 — Wear surfaces

- [ ] Latest/unread Tile.
- [ ] Continue-listening Tile.
- [ ] Unread-count complication.
- [ ] Optional latest-item complication where the complication type has enough space.
- [ ] Update cadence designed around battery limits rather than aggressive polling.

## P7 — Feed management

- [ ] Phone companion for comfortable feed entry and management.
- [ ] OPML import/export.
- [ ] Per-feed "send to watch" setting.
- [ ] Categories/folders.
- [ ] Keyword watch filter.
- [ ] Standalone watch refresh when the phone is unavailable and the watch has network access.

## P8 — Membership and quotas

Only implement after the managed AI path and identity model are stable.

- [ ] Serverless user identity.
- [ ] Free / Pro entitlement model.
- [ ] Managed AI usage quota.
- [ ] BYOK does not consume managed AI quota.
- [ ] Google Play Billing purchase flow on the phone.
- [ ] Server-side purchase verification.
- [ ] Restore purchases.
- [ ] RTDN lifecycle handling.
- [ ] Authoritative entitlement state lives server-side, not in client preferences.

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
```

After the phone companion exists, CI must also add:

```text
Mobile JVM tests
Mobile debug assembly
```

As persistence, MediaSession, Data Layer, billing and migrations are introduced, add targeted integration/instrumentation tests instead of relying only on compilation.

## Release gate

Do not call WristBrief production-ready until all of the following are true:

- Core RSS/Atom parsing has representative fixtures and malformed-feed coverage.
- Podcast playback survives activity recreation/backgrounding.
- Offline inbox behavior is deterministic.
- Gateway errors do not expose provider credentials or upstream bodies.
- Wear screens pass round/small display and large-font review.
- Tile/complication behavior is battery-conscious.
- CI is green from a clean checkout.
- Deployment, secret-management, privacy and recovery docs are complete.
