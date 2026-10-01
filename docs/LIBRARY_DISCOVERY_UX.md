# Library discovery and return-to-reading improvements

## Scope

This change improves the native Android phone Library without replacing Compose, changing the visual system, or adding mock product capabilities. It is based on `main` and does not include the separate reader/RSS/admin work in PR #11.

- Search titles, source names and sanitized descriptions using trimmed, case-insensitive tokens, including Chinese text.
- Order dated content by its actual publication timestamp; leave undated content last in either sort direction.
- Normalize category names, show categories backed by cached items, and fall back to All when a selected category disappears.
- Update bookmark/read controls, counters and filtered results immediately after writes. Decode the persistent read/saved state once per snapshot instead of repeatedly per item.
- Retain Library query, filter, category, sort and list scroll when opening an article/settings or switching tabs. Other destinations keep their previous restoration behavior.
- Distinguish missing subscriptions, paused sources, missing cached content, an empty filter and no search results, with direct recovery actions.
- Keep the neutral glass/teal vocabulary, simplify the heading, accommodate long source names, provide 48 dp interactive targets, expose selection and action semantics, and maintain English/Simplified Chinese resources.
- Sort cached input by cache time before applying SQLite's retention cap, so an oldest-first migration or unsorted batch cannot discard newer items.

## Implementation boundaries

- Filtering and sorting use the local cached item set, not a global search service. A disabled subscription can still have cached content available in Library.
- The UI reloads snapshots on entry and activity resume, after its own refresh, and after its own state writes. This is not a new continuous subscription to background sync events.
- Refresh calls the existing repository and reports observed results. No cloud-sync success, publication date, playback duration or reading progress is fabricated.
- HTML/date preprocessing is memoized but remains synchronous on first entry. The batch state API removes repeated whole-store decoding; large-description rendering should still be profiled on lower-end phones.
- Read/saved writes retain the existing sync/outbox path and wire formats. The repository additions are a backward-compatible batch-read API, separate from PR #11's refresh changes.

## Validation

Local validation on 2026-10-01 used JDK 17, Gradle 8.9 and Android SDK 35. Mobile: 312 unit tests passed and debug app/test APKs built. Wear: 147 unit tests passed and the debug APK built. Release guard and whitespace checks passed. No local Android emulator/device was available; `/dev/kvm` was absent. GitHub Actions provides emulator execution, separate from manual screenshot and TalkBack review.

Gateway typecheck passed. All 380 gateway tests passed locally under Node 22 with `--maxWorkers=1 --testTimeout=15000`. An earlier default-timeout run exposed an audio-test timeout and an unrelated SMTP assertion matching `535` in a timestamp; the final run used serialized workers and the stated timeout, without changing those tests or production gateway behavior.

CI also exposed outdated navigation assertions and a billing fixture whose fixed expiration had passed on October 1. Navigation tests now follow the existing five-page onboarding and current labels, and the billing-state test pins its clock and checks the exact expiration boundary. No billing entitlement implementation was changed.

Automated commands:

```sh
gradle :mobile:testDebugUnitTest :mobile:assembleDebug :mobile:assembleDebugAndroidTest
gradle :app:testDebugUnitTest :app:assembleDebug
python scripts/release_guard.py
git diff --check
```

New unit coverage targets publication ordering, undated entries, sanitized/tokenized search, combined filters, normalized categories, missing-category fallback, empty-state classification, counts and state snapshots. New Compose instrumentation coverage targets immediate toggles, recoverable empty states and navigation retention; compilation alone is not evidence those device tests passed.

Device/emulator acceptance:

- Open Library with no sources, paused sources, enabled sources but no cached items, and populated content. Check each recovery action and failed refresh.
- Save an item, filter Saved, then unsave the last result. Repeat with Unread and marking read; counters and rows must update immediately.
- Search with whitespace, Chinese and several tokens. Combine category and Saved/Unread filters; verify recovery can search across filters without losing the query.
- Open an article from filtered results and return; switch Library → Today → Library; visit Settings and return; rotate/recreate the activity. Check query, filter, category, sort and scroll retention.
- Check English and Simplified Chinese in light/dark themes, narrow screens and large font sizes. Use TalkBack to check search editing, chip selection, sort state, bookmark/read labels, focus order and 48 dp targets.
- No actual screenshot or visual/accessibility pass is claimed without running these checks on an Android device/emulator.
