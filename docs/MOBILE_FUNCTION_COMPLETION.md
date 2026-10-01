# Mobile function completion audit (phone client)

Updated 2026-10-01. Branch `feat/library-discovery-ux`, working tree (no commit by this pass). Scope: phone product-completion audit and implementation of the highest-value incomplete or misleading behaviors. Out of scope and owned elsewhere: screenshot/emulator infrastructure (parent), `ArticleDetailDestination` / `articles/` / feed parser / admin (PR11), `LibraryDestination` (PR12, untouched here).

This document is the implementation source of truth for what was changed and what was verified. Nothing below claims an emulator pass by this pass; see "Verification status".

## 1. What was wrong (static audit of the user journeys)

| Journey | Finding at branch head before this pass | Severity |
| --- | --- | --- |
| Onboarding | `Explore Library first` and `Start reading` both emitted `OnboardingAction.Explore`; `MainActivity` only handled `AddFeed`/`ImportOpml`, so Explore was ignored and the app always landed on Today. The stale action string stayed in state until Settings was opened. | Misleading navigation |
| Today / Explore → Sources | `onAddFeed` / `onImportOpml` only set `showSettings = true`: the user landed on the Sources list, not in the editor or the OPML picker. Explore's add-URL button never rendered because `onAddFeedDialog` was never passed. | Incomplete action |
| Today | Empty hero said "All caught up! Add more feeds" even with zero subscriptions (no distinction between "no subscriptions" and "no new articles"). `onImportOpml` was accepted but never used. | Truthful state |
| Ask AI | Scope chips were partially decorative: `Today's Brief` used the whole inbox; after a successful answer the card always said "3 Sources Synthesized" (`allItems.take(3).size`) and cited `allItems.take(4)` regardless of what was sent; a prefilled article/clip silently overrode the chosen scope while the UI still said "Context: All Sources"; copy button was labeled "Save"; prompt time used a fixed `h:mm a` pattern. | Fake capability / a11y |
| Explore | Hard-coded chips (`Science`, `Design`) with no curated feeds behind them; chips not localized; invented metrics "~8 articles / wk" / "Weekly show"; subscribe failures swallowed (no error, no retry); `Podcast`/`RSS` badge not localized; 44 dp target. | Invented data / recovery |
| Settings → Preferences | `Feed refresh frequency`, `Wear OS synchronization`, `Notifications` were persisted but nothing read them. There is no background scheduler, every Data Layer publisher ignored the Wear toggle, and the only notification the app posts is the mandatory media-playback one. | Fake capability |
| Settings → About | Version hard-coded to `0.1.0`; debug diagnostics always printed "Data Layer Sync: Ready". | Truthful state |

## 2. What was implemented

### 2.1 Onboarding handoff (`OnboardingHandoff.kt`, `Onboarding.kt`, `MainActivity.kt`)
- New `OnboardingAction.StartReading`. Mapping (pure, unit-tested `resolveOnboardingHandoff`):
  - `Skip`, `Start reading` → `StartReading` → **Today**.
  - `Explore Library first` → `Explore` → **Explore tab**.
  - `Add your first source` → **Today + Settings/Sources with the feed editor open**.
  - `Import an OPML file` → **Today + Settings/Sources with the OPML picker launched**.
- The shell's selected-tab state (`name`) now lives above the onboarding gate so the handoff can select Explore. `CategorizedFeedManagementDestination` treats `StartReading` like `Explore` (no-op).

### 2.2 "Add source" / "Import OPML" open the real action (`MainActivity.kt`, `TodayDestination.kt`, `ExploreDestination.kt`)
- `onboardingAction` was generalized to `pendingSourcesAction`: Today's hero buttons and Explore's new add button set `AddFeed`/`ImportOpml`, so Settings opens the editor / picker immediately (same one-shot consumption path onboarding already used).
- Explore now receives `onAddFeedDialog`, so the 48 dp "Add a feed URL" button renders with a content description.
- Today's hero distinguishes **no subscriptions** (`today_empty_body` + `Import an OPML file` + `Add your first source`) from **subscribed but nothing cached** (`daily_brief_all_caught_up`).

### 2.3 Ask AI grounding is real (`AskAiContext.kt`, `AskAiDestination.kt`)
- `AskAiContextBuilder.build(scope, query, all, unread, saved, prefilled)` returns the exact items and text sent:
  - `Today's Brief` = the brief-eligible set (unread, else all) — the same input rule the Today hero uses; chips show live counts, including Today's.
  - Per-request cap `MAX_ITEMS = 6` (matches `DailyBriefInputBuilder`); HTML is stripped through `ArticleContentSanitizer`.
  - `Article` scope exists only while content was handed over from the reader/player and is preselected; switching to another scope drops the prefilled content.
  - An empty scope sends the question only; the UI says so (`ai_scope_empty`, `ai_grounded_query_only`).
- The answer card shows the real count ("N Sources Synthesized", "Grounded in the handed-over article", or "No library content included") and cites only the items that were in the request (item title, ellipsized). Copy/regenerate icons have correct labels; prompt time uses `DateFormat.getTimeInstance(SHORT)`.

### 2.4 Explore catalog truthfulness (`ExploreCatalog.kt`, `ExploreDestination.kt`)
- Chips derived from the curated catalog (`All`, catalog categories, `Podcasts` only if a podcast exists), localized via the existing `exploreCategoryLabel`; `Tech`/`Technology` collapse. Suggested topics in the empty-search state come from the same list.
- Invented cadence text removed; the card shows the publisher host (`Source: feeds.npr.org`), which is derivable from the URL.
- Subscribe errors from `MobileFeedManager.add` are shown under the card (`Could not subscribe: …`) and the button remains tappable to retry. Subscribe button has `Role.Button`, a click label and a `stateDescription` (Subscribed / Subscribe). `Podcast`/`RSS` badge localized.

### 2.5 Settings toggles do what they say (`WearSyncGate.kt`, `InboxAutoRefresh.kt`, `AppPreferences.kt`, `SettingsDestination.kt`, publishers)
- **Wear OS synchronization**: `WearSyncGate` is consulted by all three phone→watch publishers before `putDataItem` — `GoogleWearFeedSyncPublisher` (subscriptions), `PhoneItemStateSyncManager` (read/saved), `PhonePlaybackSyncManager` (playback progress). Watch→phone receivers are unaffected. Re-enabling calls `MobileFeedManager.republishToWatch()` so the watch catches up; the row's summary changes while off. Debug diagnostics show "Disabled by preference" when off. Wire payloads/paths are unchanged.
- **Feed refresh frequency**: honored as a foreground policy (`InboxAutoRefreshPolicy`): on app start and `ON_RESUME`, if the last completed refresh is older than the interval (and at least one feed is enabled), the inbox is refreshed once; `Manual` never auto-refreshes; a recorded time in the future (clock rollback) counts as stale so the policy recovers. The policy object is `internal` because `RefreshInterval` is internal. `MobileInboxRepository` records the completion time through an additive `onRefreshCompleted` callback into `AppPreferences.lastInboxRefreshEpochMs`. The Settings card now states exactly this ("There is no background refresh.") and shows "Last refresh: …" from the observed value or "Not refreshed yet". Today re-reads the inbox via an `inboxRevision` signal after an auto refresh.
- **Notifications** switch removed from the UI. Reason: no feature exists behind it (only the mandatory playback notification, which a preference cannot disable). The preference keys stay readable (`AppPreferences.isNotificationsEnabled`, documented as legacy) so `AppPreferencesTest` is unaffected.
- About shows `BuildConfig.VERSION_NAME`.
- **Re-enable snapshot**: switching Wear sync back on calls `onWearSyncReenabled` (wired in `MainActivity`), which resends the subscription list (`republishToWatch`) and the latest in-progress episode (`PodcastProgressStore.getLatestActive()` → `PhonePlaybackSyncManager.publishLocalProgress`). **Limitation:** read/saved states are not resent immediately because `PhoneItemStateSyncManager` has no public republish entry point (file not in this pass's ownership); they are resent in full with the next local read/save change, since every mutation publishes the complete owned set.

### 2.7 Screenshot-driven polish (from `ux-review-sheets/*-contact-sheet.png`)
- Settings gear in the shell header was nearly black on the dark header: the glass surface color is not in the Material scheme, so `LocalContentColor` fell back to black. The icon is now tinted `GlassTokens.textPrimary(darkTheme)` and the button is an explicit 48 dp target.
- Preferences → Appearance: the three theme options were in a fixed `Row`; at 320 dp / font scale 1.3 the last chip was squeezed until "Dark/深色" broke onto two lines. Options are now a wrapping `FlowRow` of single-line chips (`PreferenceOptionChip`, 48 dp min height).
- Preferences → Feed refresh frequency: the fourth option clipped past the right edge of a `LazyRow` with no scroll affordance. Same wrapping `FlowRow` treatment; nothing is clipped and no hidden horizontal scroll remains.

### 2.6 Localization
New keys only in `mobile/src/main/res/values/product_completion.xml` and `values-zh-rCN/product_completion.xml` (17 keys, parity checked; no duplicates with `strings.xml`). No existing strings were changed, so navigation anchors used by the screenshot/evaluation tests (`Skip`, `Continue`, `Add your first source`, `HTTPS feed URL`, tab titles) are unchanged.

## 3. Feature matrix

Legend — Implementation: Done / Partial / Not implemented. Verification: **U** unit test present, **S** static review only, **E** needs emulator (parent). "External gap" lists what is still outside the phone client's control.

| # | Feature | Implementation | Verification | External gap / dependency |
| --- | --- | --- | --- | --- |
| 1 | Onboarding → Today (Skip / Start reading) | Done | U (`OnboardingHandoffTest`), E (`MainActivityNavigationTest.onboardingSkip…` still valid) | — |
| 2 | Onboarding → Explore tab | Done (was ignored) | U, E (new assertion recommended: Explore heading visible after "Explore Library first") | — |
| 3 | Onboarding → Add feed editor / OPML picker | Done (pre-existing path, now via handoff) | U, E (`onboardingAddSourceActionOpensRealFeedEditor` still valid) | OPML picker is a system document picker |
| 4 | Onboarding interests → recommendations | Done (pre-existing filter over curated feeds) | S | Only 3 curated feeds exist; selection only affects the Content page |
| 5 | Today: no-subscriptions vs no-articles hero | Done | S, E | — |
| 6 | Today: Add source / Import OPML open the real action | Done | S, E | — |
| 7 | Today: brief generation / regeneration / needs-update | Pre-existing, unchanged | S | Requires HTTPS `GATEWAY_BASE_URL` + signed-in session; otherwise error / sign-in path |
| 8 | Today: continue reading/listening, categories | Pre-existing (Sept pass), unchanged | S | — |
| 9 | Today: interval auto refresh on start/resume | Done | U (`InboxAutoRefreshPolicyTest`, `InboxRefreshTimestampTest`), E | **No background refresh** (no WorkManager); documented in UI copy |
| 10 | Explore: catalog-derived localized chips, no invented metrics | Done | U (`ExploreCatalogTest`), E | Catalog is a 3-item static list (`SampleFeeds`); no remote directory |
| 11 | Explore: subscribe success/failure/retry | Done | S (`FeedMutationResult.Error` surfaced), E | Network probe is real (`HttpFeedProbe`) |
| 12 | Explore: add custom feed URL | Done (hook wired to Sources editor) | S, E | — |
| 13 | Ask AI: scope applied, real counts and citations | Done | U (`AskAiContextBuilderTest`), E | Gateway `/v1/summary` + session required; quota/provider errors mapped as before |
| 14 | Ask AI: article/clip handoff from reader/player | Done (explicit `Article` scope) | U, E | Reader handoff content itself comes from PR11-owned `ArticleDetailDestination` |
| 15 | Ask AI: sign-in / unavailable / quota states | Pre-existing, unchanged | S | — |
| 16 | Settings: Wear sync toggle gates phone→watch publishing; re-enable resends subscriptions + latest playback | Done (read/saved snapshot resent on next local change — see 2.5) | U (`WearSyncGateTest` – gate semantics + republish), S for the three Data Layer call sites | Real Data Layer delivery needs a paired watch; cannot be unit-tested |
| 17 | Settings: refresh interval honest copy + last refresh | Done | U, E | — |
| 18 | Settings: Notifications toggle | **Removed** (no feature) | S, E | A real notifications feature would need channels + a producer; not present |
| 19 | Settings: Wi-Fi only | Pre-existing; read by podcast player | S | Only applies to podcast playback, not feed refresh (copy already says so? — see "Open items") |
| 20 | Settings: theme, version, licenses, privacy link | Done (version now real) | S, E | Privacy link is the project site |
| 21 | Settings → Account (Google sign-in, membership, billing) | Pre-existing, unchanged | S | Requires `GOOGLE_WEB_CLIENT_ID`, HTTPS gateway, Play Billing products |
| 22 | Podcast: mini/expanded player, speed, seek, transcript entry, Ask AI clip | Pre-existing, unchanged | S | Transcript generation requires gateway speech workers |
| 23 | Podcast playback progress → watch | Gated by Wear toggle (new) | S | Paired watch |
| 24 | Library (search, filters, saved, state restore) | PR12 (parent) | — | — |
| 25 | Reader (`ArticleDetailDestination`): full text, images, speech preview | **PR11 dependency** | — | Base branch still shows the fictional digest in the reader until the parent integrates PR11's reader/RSS fixes into this branch. Not touched here. |
| 26 | Cloud sync of subscriptions/item state | Pre-existing runtime, unchanged | S | Signed-in session + gateway |

**No "all production ready" claim is made.** Rows 7, 13–16, 21–23, 25–26 depend on configured external services or other PRs.

## 4. Verification status

- Gradle was **not** run by this pass (single parent build policy). All Kotlin changes were reviewed statically for exhaustiveness (`when` over the extended enums), imports, and parameter wiring; string keys referenced from changed files were checked to exist; EN/ZH parity and absence of duplicate keys across all resource files were verified with a script.
- New unit tests (JVM, no Android dependencies): `OnboardingHandoffTest`, `AskAiContextBuilderTest`, `ExploreCatalogTest`, `InboxAutoRefreshPolicyTest`, `InboxRefreshTimestampTest`, `WearSyncGateTest`.
- Existing instrumentation tests expected to remain valid: `onboardingSkipNavigatesToTodayAndSurvivesRecreation` (Skip still lands on Today), `onboardingAddSourceActionOpensRealFeedEditor`, `settingsAboutPageSurvivesActivityRecreation`.
- Emulator evaluation is owned by the parent. Suggested checks for the parent's `UiEvaluationTest` / manual tour, in both locales, light/dark, 320 dp @ 1.3 font scale:
  1. Onboarding → "Explore Library first" shows the Explore heading and catalog-derived chips (All / Technology / News / Podcasts), no Science/Design.
  2. Today with zero subscriptions shows two buttons; "Add your first source" opens the editor with the "HTTPS feed URL" field directly; "Import an OPML file" launches the picker.
  3. Explore card: host line fits, Subscribe → Subscribed, forced failure (unreachable URL) shows the red error and allows retry; "+" header button has a content description.
  4. Ask AI: with fixtures, chip counts match (All/Today/Unread/Saved); after a (mocked or real) answer, the count line and citation chips match the chosen scope; with a handed-over article the "This article" chip is preselected.
  5. Settings → Preferences: no Notifications row; refresh card shows the explanatory summary and "Not refreshed yet"/"Last refresh: …"; Wear toggle summary changes when off.
  6. Settings → About shows the Gradle `versionName`.
  7. Dark theme: the header gear is clearly visible; Preferences at 320 dp / 1.3: theme and refresh options wrap onto additional lines, no label breaks mid-word and nothing is clipped.
- Fixture note from the parent: screenshot fixtures set `RefreshInterval.MANUAL`, which correctly suppresses the new auto refresh (policy returns false for `MANUAL`).

## 5. Open items (not done here, honest list)

- Background refresh (WorkManager) is not implemented; the interval is foreground-only by design of this pass and the UI says so.
- Wi-Fi-only copy (`settings_wifi_only_summary`) should be re-read against actual behavior (podcast playback only); not changed to avoid touching `strings.xml` in this pass.
- Ask AI still cannot show the gateway's token/quota numbers (no endpoint on the phone contract); it shows quota/provider errors only when they occur.
- Explore catalog is static; a remote directory or OPML-based discovery would be a new feature.
- Reader/RSS parser fixes are PR11's; until the parent integrates them, the reader on this branch keeps the base behavior.
- Parent should add an instrumentation assertion for onboarding → Explore and update any test that referenced `OnboardingAction.Explore` for Skip.
