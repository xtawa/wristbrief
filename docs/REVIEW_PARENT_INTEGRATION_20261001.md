# Read-only review of parent integration (reader / parser / inbox retention / Wear republish)

Reviewed 2026-10-01 against the working tree (parent reports mobile unit + APK + androidTest compile OK). No edits, no Gradle.

## Verdict: no blockers

Nothing found that should stop CI/screenshots. Three medium items below are candidates for the same follow-up as the Wi-Fi copy fix; the rest are low/informational.

## Checked (OK)

- **MobileInboxRepository.refresh**: failed-feed IDs now derived by result index, not title (fixes duplicate-title collision; covered by `partialFailureRetainsByFeedIdNotDuplicateTitle`). `CancellationException` rethrown inside `async`. `onRefreshCompleted(now)` invoked only after a snapshot save that contacted feeds (matches `InboxRefreshTimestampTest`). Saved items are exempt from the 500 cap (`stored = saved + unsaved.take(500)`), and `isOfflineFallback`/`totalCount` remain consistent.
- **HttpFeedItemFetcher**: `callTimeout(30s)` plus `BoundedFeedInputStream` (4 MiB) and `contentLength()` pre-check; `-1` content length correctly falls through to the bounded stream. Over-limit → `IOException` → per-feed failure, not a crash.
- **SqliteMobileInboxStore.saveRetaining**: delete-all + reinsert inside one transaction avoids the 999-bind-parameter limit; protected IDs bypass `maxRetentionItems`; `load()` without `LIMIT` is safe because the writer bounds the table. `save()` delegates with an empty protected set, so migration semantics are unchanged.
- **MobileFeedParser**: root-element check (`rss` / `feed` / `rdf` local name — relies on namespace processing, which the parser already enables for `content:encoded`); `readContent` handles Atom `type="xhtml"` children, re-escapes text only inside markup, and plain CDATA HTML descriptions pass through unchanged. `END_DOCUMENT` inside content → whole feed fails (acceptable).
- **ArticleDocumentRenderer**: `LinkAnnotation.Url` via `withLink` gives native, TalkBack-exposed links; `textScale` applied consistently to body/headings/quotes/lists.
- **ArticleDetailDestination**: fictional digest removed; reader text-size dialog persisted in `reader_appearance` prefs; `loadArticle` failures are caught inside `ArticleRepository` (`runCatching` in `resolveRemote`/`readDiskCache`), so the `LaunchedEffect` cannot crash the composition. Ask AI handoff passes the real body text and shows the scope note when only excerpt/podcast text is available.
- **Wear republish**: `PhoneItemStateSyncManager.republishOwnedState()` is `@Synchronized`, gated through `publishOwned`, and does not mint a new timestamp. `MainActivity.onWearSyncReenabled` now resends subscriptions + owned read/saved states + latest in-progress episode; each wrapped in `runCatching`. The earlier "read/saved resent on next change" limitation in `MOBILE_FUNCTION_COMPLETION.md` §2.5 / row 16 is now obsolete and should be dropped in the parent's combined report.

## Medium (non-blocking)

1. **Paused-feed retention is inconsistent.** When *all* feeds are disabled, `refresh()` keeps every cached item of subscribed feeds (test `pausingAllFeedsDoesNotEraseCachedOrSavedArticles`). When *some* feeds are disabled, `retained` keeps only `failedIds` or saved items, so unsaved cached items of a paused feed vanish from Library on the next refresh while another feed is enabled. Suggested one-liner: treat non-fetched subscribed feeds like failed ones (`it.feedId in subscribedIds && it.feedId !in fetchedFeedIds`).
2. **Excerpt notice over-claims for full-text RSS.** `articleDocumentFromRss` always sets `isExcerpt = true`, and remote docs with `source == "rss"` are also flagged, so a 3,000-word `content:encoded` body still shows "Only the feed excerpt is available here" and hides the reading time. The intent (length cannot prove completeness) is sound, but the string asserts a fact. Softer copy, e.g. "Shown from the feed content; the original site may have more.", keeps it truthful without claiming the body is partial.
3. **Wi-Fi copy** — already noted by parent: the real guard is podcast playback only; the row copy mentions downloads/feed sync.

## Low / informational

- `toAnnotatedString()` wraps `withLink` in a redundant `pushStringAnnotation("URL")`/`pop()`; harmless.
- `http://` article links are opened through the native link handler; feeds themselves remain HTTPS-only. Acceptable, just noting it is intentional.
- Two refresh paths can overlap at launch (Today's initial `refresh()` when items are empty and `MainActivity.autoRefreshIfStale()`); both write full snapshots with the same data, so last-writer-wins is safe, only duplicate network work.
- `MobileRefreshResult.totalCount` in the no-enabled-feeds branch now reports the retained cache size instead of 0; Today only uses `failedFeedTitles`/`isOfflineFallback`, so no UI impact.
- Unit fakes in `MobileInboxRepositoryTest` only override `load/save`; `saveRetaining` falls back to `save`, so the saved-exemption is exercised at the repository layer (`savedArticleSurvivesWhenFeedStopsPublishingIt`) and the store layer in `SqliteStoresAndMigrationTest` (`saveRetaining(items, setOf("item-1"))`). Coverage adequate.
