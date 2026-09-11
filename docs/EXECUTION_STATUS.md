# WristBrief Execution Status & Verification Log

> **Single Source of Truth** for WristBrief engineering progress, user-outcome verification, test evidence, CI status, and release readiness.

---

## 1. Verification Evidence Levels

To prevent mistaking "code exists" or "local build passed" for true end-to-end production readiness, every capability and batch is tracked against five strict tiers of verification:

| Level | Identifier | Definition & Requirements |
|---|---|---|
| **L1** | `Code Exists` | Implementation files are present in the repository, structurally complete, and compile without syntax errors. |
| **L2** | `Locally Tested` | Module unit tests, parser contracts, string parity, or local release-guard scripts pass deterministically on a local machine. |
| **L3** | `CI Verified` | Required GitHub Actions workflows (release-guard, Android assembly, gateway typecheck/test, managed-device instrumentation) pass on the recorded commit SHA. |
| **L4** | `Paired Verified` | Verified on active Wear OS and phone hardware/emulators with bidirectional Data Layer sync, rotary input, audio route transitions, and Activity recreation. |
| **L5** | `Production Configured` | External cloud secrets, GCP OAuth, Google Android Publisher API credentials, Pub/Sub RTDN topic bindings, and Cloudflare D1 production databases are provisioned and tested. |

---

## 2. Overall Batch Roadmap & Current Status

| Batch | Title & Outcome | Scope | Status | Evidence Level |
|---|---|---|---|---|
| **Batch 0** | **Baseline Health & Red Light Remediation** | Fix mobile instrumentation regressions, reconcile documentation, establish execution status source of truth | **COMPLETED** | L2 Locally Tested (CI pending on push) |
| **Batch 1** | **Design System, Resources & Semantic States** | Standardize tokens, M3 components, bilingual string resources, eliminate magic numbers | **COMPLETED** | L2 Locally Tested |
| **Batch 2** | **Onboarding to First Content Activation** | Robust 3-min onboarding, Add Feed state machine, OPML import, Today activation, sample feeds | **COMPLETED** | L2 Locally Tested |
| **Batch 3** | **Local Content Library & Room Migration** | SQLite persistence, item identity, bounded refresh, Today/Library filtering & search | **COMPLETED** | L2 Locally Tested |
| **Batch 4** | **Podcast Playback & Media3 Loop** | SQLite playback progress, Media3 service/session, Wi-Fi constraints, audio controls | **COMPLETED** | L2 Locally Tested |
| **Batch 5** | **Wear OS-First UX & Canonical Data Layer** | Watch UX polish, rotary crown support, versioned outbox sync, scoped session bridge | Planned | L1 Code Exists (Partial) |
| **Batch 6** | **AI Daily Brief & Predictable Quotas** | Reserve/commit/release quota lifecycle, cache stampede lock, structured brief output | Planned | L1 Code Exists (Partial) |
| **Batch 7** | **Account, Play Billing & Membership Entitlements** | Keystore SessionStore, Play Billing auto-verify/acknowledge, multi-token D1 entitlements | Planned | L1 Code Exists (Partial) |
| **Batch 8** | **Production Hardening & Release Gate** | Lockfiles, clean D1 migrations, security audit, release candidate verification record | Planned | L1 Code Exists (Partial) |

---

## 3. Batch Execution Details

### Batch 0: Baseline Health & Red Light Remediation

- **Date**: 2026-09-11
- **Starting SHA**: `254a865`
- **Product Outcome**:
  - Restores reliable navigation and settings state persistence in the Mobile companion app.
  - Ensures Account & Membership tab survives device rotation and Activity recreation without resetting back to Sources.
  - Eliminates semantic ambiguity between card title and action button in "Replay onboarding guide", restoring testability and TalkBack accessibility.
  - Establishes a verified single source of truth for all subsequent batches.
- **Scope & Code Changes**:
  1. `mobile/src/main/res/values/strings.xml` & `values-zh-rCN/strings.xml`: Added `settings_replay_onboarding_title` ("Onboarding guide" / "新手入门引导"), separating the card heading from the action button `settings_replay_onboarding` ("Replay onboarding guide" / "重新查看入门引导").
  2. `mobile/src/main/java/ink/underflo/wristbrief/mobile/SettingsDestination.kt`:
     - Updated card title to use `settings_replay_onboarding_title`.
     - Attached explicit button semantics (`role = Role.Button`) and `Modifier.testTag("settings_replay_onboarding_button")` to the action button.
     - Changed `selectedTab`, `currentTheme`, `currentInterval`, `wifiOnly`, `wearSync`, `notifications`, `showPrivacyDialog`, and `showLicensesDialog` from transient `remember` to persistent `rememberSaveable`.
  3. `mobile/src/androidTest/java/ink/underflo/wristbrief/mobile/MainActivityNavigationTest.kt`:
     - Verified `settingsCanReplayOnboarding` and `membershipDestinationInSettingsSurvivesActivityRecreation`.
     - Added `settingsReplayOnboardingHasUniqueInteractiveButton` test explicitly validating distinct semantic nodes and test tags.
  4. `README.md` & `docs/ROADMAP.md`: Modernized layout and referenced `docs/EXECUTION_STATUS.md`.
  5. `readme911.txt`: Archived historical handoff note to prevent reliance on stale completed claims.
- **Evidence**:
  - `mobile:testDebugUnitTest`: Passed (including `StringResourceParityTest` verifying 100% key parity between English and Simplified Chinese).
  - `mobile:compileDebugAndroidTestKotlin`: Clean compilation of all mobile UI tests.
  - `gateway:npm test`: 142/142 unit tests passed.
  - `scripts/release_guard.py`: Passed release security invariant checks.
- **CI Status**:
  - Baseline CI: Run #358 (Actions run ID `34609115272`) had 2 mobile instrumentation failures (`settingsCanReplayOnboarding` node count mismatch and `membershipDestinationInSettingsSurvivesActivityRecreation` invisible component).
  - Remediation: Root causes addressed in product tree and state restoration. Ready for next CI trigger.
- **Risks & External Gaps**:
  - Local Windows environment currently lacks a running Android AVD; instrumentation verification relies on managed device execution in CI.
  - Play Billing, Google OAuth, and Cloudflare D1 remote bindings remain pending external credentials.
- **Next Batch**:
  - **Batch 1**: COMPLETED.

---

### Batch 1: Design System, Resources & Semantic States

- **Date**: 2026-09-11
- **Starting SHA**: `254a865` + Batch 0 fixes
- **Product Outcome**:
  - Eliminates typography/spacing inconsistencies and inline magic numbers across screens.
  - Removes hardcoded English strings in both Wear OS and Mobile companion apps, ensuring full English / Simplified Chinese bilingual localization.
  - Replaces text-based arrows (`←`) with dedicated Material vector icons (`ic_arrow_back.xml`).
  - Implements an unambiguous `UiState<T>` sealed model (`Idle`, `Loading`, `Refreshing`, `Content`, `Empty`, `OfflineCached`, `PartialFailure`, `AuthRequired`, `QuotaExhausted`, `Error`), ending the anti-pattern of empty lists representing all states.
  - Enforces minimum 48dp touch targets and accessible semantics across interactive components.
- **Scope & Code Changes**:
  1. `mobile/src/main/res/drawable/ic_arrow_back.xml`: Added standard Material vector back button drawable.
  2. `mobile/src/main/java/ink/underflo/wristbrief/mobile/ui/Tokens.kt`: Extracted `MobileSpacing`, `ElevationTokens`, and `TouchTargetTokens` (48dp).
  3. `mobile/src/main/java/ink/underflo/wristbrief/mobile/ui/LceState.kt`: Created exhaustive `UiState<T>` hierarchy covering loading, empty, offline cache, partial failures, auth, and quota states.
  4. `mobile/src/main/java/ink/underflo/wristbrief/mobile/ui/CommonComponents.kt`: Created reusable, accessible M3 UI components (`PrimaryAction`, `SecondaryAction`, `SectionHeader`, `ContentRow`, `ErrorBanner`, `OfflineBadge`, `StateView`, `BackIconButton`).
  5. `app/src/main/java/ink/underflo/wristbrief/ui/WearTheme.kt`: Created `WearSpacing`, `WearTouchTarget`, and `WristBriefWearTheme`, wrapping Wear OS `MainActivity`.
  6. Cleaned up hardcoded text in `ArticleDetailDestination.kt`, `SettingsDestination.kt`, `Onboarding.kt`, `app/src/main/res/values/strings.xml`, and `app/src/main/res/values-zh-rCN/strings.xml`.
  7. `mobile/src/test/java/ink/underflo/wristbrief/mobile/ui/UiStateAndComponentsTest.kt`: Unit tests verifying touch target standards, token monotonicity, and UiState handling.
- **Evidence**:
  - `mobile:testDebugUnitTest`: 27 test classes passed (including `StringResourceParityTest` and `UiStateAndComponentsTest`).
  - `app:testDebugUnitTest`: 22 test classes passed (including `WearStringResourceParityTest`).
  - `mobile:assembleDebug` and `app:assembleDebug`: Clean assembly of debug APKs.
- **Risks & External Gaps**:
  - Next batch must wire these standardized components into the Onboarding and Feed activation flow.
- **Next Batch**:
  - **Batch 2**: COMPLETED.

---

### Batch 2: Onboarding to First Content Activation

- **Date**: 2026-09-11
- **Starting SHA**: `254a865` + Batch 0 & Batch 1
- **Product Outcome**:
  - Delivers a streamlined activation path enabling new users to go from app install to reading verified content within seconds.
  - Implements 1-tap curated sample feed subscription directly in the Onboarding flow and the empty Today screen, eliminating tedious manual typing.
  - Introduces real-time, field-level URL validation with error feedback (`feed_url_error_https`, `feed_url_error_invalid`, `feed_url_error_duplicate`) in `CategoryFeedEditorDialog`, disabling submission when invalid or duplicated.
  - Adds direct `sendToWatch` configuration to the feed creation/editing dialog and backend manager.
  - Transforms the Onboarding Done page into a dynamic state machine that adapts to whether feeds and items are ready, while maintaining complete compatibility with existing navigation tests.
- **Scope & Code Changes**:
  1. `mobile/src/main/java/ink/underflo/wristbrief/mobile/SampleFeeds.kt`: Curated public HTTPS feed catalog (`Android Developers Blog`, `NPR News Now`, `BBC World Service`) with podcast tagging and descriptions.
  2. `mobile/src/main/java/ink/underflo/wristbrief/mobile/FeedManagement.kt`:
     - Added `FeedUrlValidationResult` and `validateFeedUrlInput` with support for HTTPS checks, format syntax checks, and duplicate detection against existing subscribed URLs.
     - Added `sendToWatch` support to `MobileFeedManager.add` and `update`.
  3. `mobile/src/main/java/ink/underflo/wristbrief/mobile/CategorizedFeedManagementDestination.kt`:
     - Upgraded `CategoryFeedEditorDialog` with live validation feedback, red error states, and a "Sync to watch" switch.
     - Added curated `SampleFeeds` quick-add card within feed management.
  4. `mobile/src/main/java/ink/underflo/wristbrief/mobile/Onboarding.kt`:
     - Added sample feeds quick-add to the `Sources` step.
     - Made the `Done` step dynamic: detects if feeds/items exist, presenting "Start reading" CTA when content is available, "Connecting to refresh..." when waiting for sync, and retaining "Add your first source" when empty.
  5. `mobile/src/main/java/ink/underflo/wristbrief/mobile/MainActivity.kt`:
     - Initialized `MobileFeedManager` and `MobileInboxRepository` before onboarding evaluation, passing live feed/item count and sample addition handlers to `WristBriefOnboarding`.
  6. `mobile/src/main/java/ink/underflo/wristbrief/mobile/TodayDestination.kt`:
     - Added curated sample feeds 1-tap buttons inside the empty Today card for immediate activation.
  7. `mobile/src/main/res/values/strings.xml` & `values-zh-rCN/strings.xml`:
     - Added 16 new bilingual string resources for feed validation errors, OOBE dynamic states, and sample feeds.
  8. Tests:
     - `mobile/src/test/java/ink/underflo/wristbrief/mobile/FeedUrlValidationTest.kt`: 6 unit tests covering all validation branches and duplicate checks.
     - `mobile/src/test/java/ink/underflo/wristbrief/mobile/SampleFeedsTest.kt`: 4 unit tests verifying HTTPS URLs, unique IDs, and metadata validity.
     - `mobile/src/test/java/ink/underflo/wristbrief/mobile/FeedManagementTest.kt`: Added `manager_supportsCustomSendToWatchOnAddAndUpdate`.
     - `mobile/src/androidTest/java/ink/underflo/wristbrief/mobile/MainActivityNavigationTest.kt`: Added `feedEditorValidatesHttpsUrlAndBlocksInvalid` verifying UI validation errors and button disabled states.
- **Evidence**:
  - `mobile:testDebugUnitTest`: 29 test classes passed 100%.
  - `mobile:compileDebugAndroidTestKotlin`: Clean compilation of mobile UI test suite.
  - `app:testDebugUnitTest`: 22 test classes passed 100%.
  - `mobile:assembleDebug` and `app:assembleDebug`: Succeeded.
  - `scripts/release_guard.py`: Passed.
  - `gateway:npm test`: 142/142 passed.
- **Risks & External Gaps**:
  - Feeds and items are currently stored in SharedPreferences. Under heavy content loads (e.g. 500+ items), this causes disk I/O latency. Moving to Room persistence in Batch 3 is required.
- **Next Batch**:
  - **Batch 3**: COMPLETED.

---

### Batch 3: Local Content Library & SQLite Persistence

- **Date**: 2026-09-11
- **Starting SHA**: `254a865` + Batches 0, 1, 2
- **Product Outcome**:
  - Replaced unindexed, synchronous JSON `SharedPreferences` persistence with a high-performance, typed SQLite database (`WristBriefDatabaseHelper`, `SqliteMobileFeedStore`, `SqliteMobileInboxStore`).
  - Implemented safe, automatic one-time migration (`LegacyDataMigration`) transferring legacy subscriptions, inbox items, read states, and saved states into SQLite without data loss.
  - Eliminated cross-feed identity collisions: item IDs are now uniquely scoped as `"$feedId:$rawGuid"`, preventing feeds with common GUIDs (e.g., "1", "item-1") from clobbering one another.
  - Bounded network refresh concurrency using `Semaphore(4)` and enforced an indexed 500-item retention ceiling to protect mobile disk and memory.
  - Upgraded `TodayDestination` with dynamic category filter chips and a non-blocking `ErrorBanner` reporting partial refresh failures with a 1-tap retry button.
  - Upgraded `LibraryDestination` with `ArticleContentSanitizer.sanitize(desc).plainText`, Newest/Oldest sort toggle, category filter chips, and distinct empty states (search no-match with "Clear search" CTA, empty feeds with "Add source" CTA).
- **Scope & Code Changes**:
  1. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/WristBriefDatabase.kt`: Production SQLite schema with tables `feed_sources`, `feed_items`, `item_states`, and `playback_progress` plus indexes on `feed_id`, `published`, and `updated_at`.
  2. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/SqliteMobileFeedStore.kt`: Implemented `MobileFeedStore` backed by SQLite.
  3. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/SqliteMobileInboxStore.kt`: Implemented `MobileInboxStore` backed by SQLite with 500-item retention pruning.
  4. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/LegacyDataMigration.kt`: One-time migration coordinator from SharedPreferences to SQLite.
  5. `mobile/src/main/java/ink/underflo/wristbrief/mobile/MobileInboxRepository.kt`:
     - Scoped `mobileItemId` by `feedId`.
     - Bounded concurrent feed refresh to 4 parallel workers.
     - Partial refresh error reporting.
  6. `mobile/src/main/java/ink/underflo/wristbrief/mobile/MainActivity.kt`: Wired SQLite stores and `LegacyDataMigration` into the app composition root.
  7. `mobile/src/main/java/ink/underflo/wristbrief/mobile/TodayDestination.kt`: Added category filter chips row and `ErrorBanner` for partial refresh failures.
  8. `mobile/src/main/java/ink/underflo/wristbrief/mobile/LibraryDestination.kt`: Sanitizer integration, Newest/Oldest sort toggle, category filter chips, and search no-match CTA.
  9. `mobile/src/main/res/values/strings.xml` & `values-zh-rCN/strings.xml`: Added 7 bilingual keys with 100% parity.
  10. Tests:
      - `mobile/src/androidTest/java/ink/underflo/wristbrief/mobile/SqliteStoresAndMigrationTest.kt`: 7 tests covering CRUD, retention limit pruning, and legacy migration.
      - `mobile/src/test/java/ink/underflo/wristbrief/mobile/MobileInboxRepositoryTest.kt`: Added `differentFeedsWithSameGuid_doNotCollide()`.
      - `mobile/src/androidTest/java/ink/underflo/wristbrief/mobile/MainActivityNavigationTest.kt`: Added `libraryDestinationSearchAndClearWorks()`.
- **Evidence**:
  - `mobile:testDebugUnitTest`: 29 test classes passed.
  - `mobile:compileDebugAndroidTestKotlin`: Passed.
  - `app:testDebugUnitTest`: 22 test classes passed.
  - `mobile:assembleDebug` and `app:assembleDebug`: Succeeded.
  - `scripts/release_guard.py`: Passed.
  - `gateway:npm test`: 142/142 passed.
- **Next Batch**:
  - **Batch 4**: COMPLETED.

---

### Batch 4: Podcast Playback & Media3 Loop

- **Date**: 2026-09-11
- **Starting SHA**: `254a865` + Batches 0, 1, 2, 3
- **Product Outcome**:
  - Replaced speculative "first audio item" logic with authentic `PlaybackProgress` tracking in SQLite (`SqlitePodcastProgressStore` using the `playback_progress` table).
  - Enforced the prompt's core rule: "Continue listening" on Today is only shown when real in-progress audio exists (`!completed && positionMs > 0`), displaying accurate resumed timestamp / total duration (`formatPlaybackTime`) and 1-tap playback. Completed episodes are removed from "continue listening".
  - Unified Media3 player/service lifecycle: `MobilePodcastPlaybackService` manages audio focus (`AUDIO_CONTENT_TYPE_SPEECH`), handles becoming noisy (auto-pause on headphone unplug), registers `podcast_playback` notification channel (API 26+), and persists duration, position, speed, and completed status across process termination.
  - Added Wi-Fi only constraint protection: `MobilePodcastPlayerController` respects `AppPreferences.isWifiOnly()`, checking network connectivity and preventing inadvertent metered cellular streaming with user-friendly bilingual guidance.
  - Classified playback errors specifically: mapped network disconnects, unsupported audio formats/codecs, and general playback failures to localized bilingual strings.
  - Connected interactive audio listening card to `ArticleDetailDestination`, allowing users to play, pause, and track audio directly within the article view.
- **Scope & Code Changes**:
  1. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/WristBriefDatabase.kt`: Bumped database version to 2; added `playback_speed REAL NOT NULL DEFAULT 1.0` with `onUpgrade` alter table support.
  2. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/SqlitePodcastProgressStore.kt`: Created SQLite-backed `PodcastProgressStore` implementation with `getLatestActive()`, `all()`, `save()`, `delete()`.
  3. `mobile/src/main/java/ink/underflo/wristbrief/mobile/db/LegacyDataMigration.kt`: Added one-time migration from `SharedPreferencesPodcastProgressStore` to `SqlitePodcastProgressStore`.
  4. `mobile/src/main/java/ink/underflo/wristbrief/mobile/media/MobilePodcastProgress.kt`: Extended `PodcastEpisodeProgress` with `durationMs`, `isPlaying`, `lastPlayedAtEpochMs`, and `completed` fields; added `getLatestActive()` to `PodcastProgressStore`.
  5. `mobile/src/main/java/ink/underflo/wristbrief/mobile/media/MobilePodcastPlaybackService.kt`: Integrated `SqlitePodcastProgressStore`, notification channel registration, and rich progress saving.
  6. `mobile/src/main/java/ink/underflo/wristbrief/mobile/media/MobilePodcastPlayerController.kt`: Injected `AppPreferences`, added network/Wi-Fi checks, and mapped specific error codes.
  7. `mobile/src/main/java/ink/underflo/wristbrief/mobile/media/PodcastPlayerUi.kt`: Displayed actual localized error messages in `PodcastExpandedSheet`.
  8. `mobile/src/main/java/ink/underflo/wristbrief/mobile/TodayDestination.kt`: Passed `progressStore`, computed `continueListening` from `getLatestActive()`, and rendered resume timestamp.
  9. `mobile/src/main/java/ink/underflo/wristbrief/mobile/ArticleDetailDestination.kt`: Added interactive audio episode card with play/pause and progress indicator.
  10. `mobile/src/main/java/ink/underflo/wristbrief/mobile/MainActivity.kt`: Wired `SqlitePodcastProgressStore` through `MobilePodcastPlayerController`, `TodayDestination`, and `ArticleDetailDestination`.
  11. `mobile/src/main/res/values/strings.xml` & `values-zh-rCN/strings.xml`: Added 5 bilingual strings for Wi-Fi only error, network error, unsupported format error, and article listen CTA with 100% key parity.
  12. Tests:
      - `mobile/src/androidTest/java/ink/underflo/wristbrief/mobile/SqlitePodcastProgressStoreTest.kt`: 5 tests verifying save/get, ordering, `getLatestActive`, delete, and legacy migration.
      - `mobile/src/test/java/ink/underflo/wristbrief/mobile/media/MobilePodcastProgressTest.kt`: Added tests for `getLatestActive` behavior.
- **Evidence**:
  - `mobile:testDebugUnitTest`: 29 test classes passed 100%.
  - `mobile:compileDebugAndroidTestKotlin`: Passed.
  - `app:testDebugUnitTest`: 22 test classes passed 100%.
  - `mobile:assembleDebug` and `app:assembleDebug`: Succeeded.
  - `scripts/release_guard.py`: Passed.
  - `gateway:npm test`: 142/142 passed.
- **Risks & External Gaps**:
  - Phone and Wear OS Data Layer sync currently lacks canonical versioned outbox and cross-device playback synchronization. Batch 5 will establish this contract.
- **Next Batch**:
  - **Batch 5**: Wear OS-First UX & Canonical Data Layer (Watch UX polish, rotary crown support, versioned outbox sync, scoped session bridge).


