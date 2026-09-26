# WristBrief product and UI constraints

Updated 2026-09-26. Applies to phone and Wear. Implementation source of truth: Compose code and localized resources; `uidocs/` contains visual references, not proof that a flow works.

## Product tasks

1. Onboard: understand RSS and podcasts, add or import a real source, then reach content. Topic choices may only be shown as personalization if they change actual recommendations or subscriptions.
2. Today: see a brief with its generation date and source article count, open a real article or resume an episode with stored progress, and understand offline or partial-refresh states.
3. Explore: find a source by query or category, subscribe, and see confirmation.
4. Ask AI: show what content is in scope, quota/account requirements, loading, errors, and a way back to the source.
5. Library: locate, search, save, and reopen articles or episodes across devices when sync is available.

## Visual system

- Use `MobileTheme.kt`, `ui/glass/GlassTokens.kt`, and `ui/glass/GlassComponents.kt` as the existing color, type, radius, and surface vocabulary. Phone surfaces stay neutral; teal indicates interactive selection or a primary action. Do not add arbitrary gradients or competing accent palettes.
- Phone horizontal content inset: 20 dp. Section spacing: 20 dp. Hero radius: `GlassTokens.HeroRadius`; supporting cards: `GlassTokens.CardRadius` or `RowRadius`. Prefer a single strong hero followed by quiet list rows. Avoid nested cards just for decoration.
- Text hierarchy: one page heading, section titles, then source metadata and labels. Do not rely on uppercase or color alone to express priority. Let localized text wrap; do not lock long titles to a fixed one-line width unless an adjacent action requires it.
- Interactive targets should be at least 48 × 48 dp on phone, with a clear accessibility name and state; on Wear follow the round-screen patterns and input constraints already established in Wear code. Keep primary actions reachable without precision taps.
- Use `values/strings.xml` and `values-zh-rCN/strings.xml` for user-visible copy; check narrow screens, large text, light/dark themes, and long translated strings. Device time and timestamps use the user's locale.
- A selected category must be visibly selected and announced as selected. Show only categories supported by subscribed feeds or available episodes; give a way back to All when a category becomes empty.

## Truthful states and information architecture

- Never infer cloud sync success from a local feed count. Show sync, offline, partial-refresh, and retry only from observed results. Do not substitute a minimum count or fake version, read-time, episode duration, or progress.
- “Continue listening” requires persisted non-complete playback progress. “Continue reading” requires persisted reading position; an unread item is instead “Next to read”.
- The daily brief is scoped to the complete eligible article set, independent of a Today category chip. Display the date and actual number of source articles used. When the current input differs from the generated input, mark the brief as needing an update and offer regeneration. A cached older brief must never be presented as today's.
- Distinguish no subscriptions, loading, no articles, no filtered results, offline cached content, and partial failure. Each recoverable state should have one direct next action.
- Do not present a subscription, transcript, AI voice, daily automation, phone-to-Wear sync, or cloud capability as working solely because a mock or backend endpoint exists. Check an end-to-end path before claiming it.

## Review before shipping a UI change

1. Walk the changed task from first launch through success, empty, loading, failure, and retry in both languages.
2. Verify focus order, labels, selection semantics, touch targets, color contrast, screen reader behavior, and reduced-motion behavior on a device or emulator.
3. Run the relevant Gradle build/tests and inspect actual screenshots in light/dark on a narrow phone and a round Wear device. Record any unavailable environment explicitly.
4. Keep logic changes and documentation consistent; never treat `uidocs` renders as runtime screenshots.

## Review findings behind the September 2026 Today pass

Static review of `TodayDestination.kt` at `c7014af`: hard-coded categories led to empty views; a fresh podcast could be labeled as resumed; unread content was labeled continued reading; the footer claimed immediate sync without an observed sync result; article reading time was hard-coded; the brief's displayed source count could reflect an old day; latest stream included read items under an unread heading. This pass addresses these visible and semantic mismatches. The repository's `uidocs/stitch_wristbrief_android_design_system` images were inspected as design references; an Android runtime flow was not captured in this review.
