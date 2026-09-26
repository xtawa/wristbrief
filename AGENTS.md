# Agent instructions for WristBrief

Read `docs/PRODUCT_DESIGN_CONSTRAINTS.md` before changing phone or Wear UI. Read the closest existing Compose screen, theme, tokens, strings, and relevant tests before editing. `uidocs/` is reference material; real UI state and behavior come from the implementation.

## Scope and workflow

- Preserve the existing Android phone + Wear and Cloudflare gateway architecture. Do not replace Compose screens with a WebView or a standalone mock.
- For a user-facing change, identify the primary task, actual data source, navigation destination, empty/loading/error states, and the specific capability or entitlement before creating controls.
- Prefer real state over decorative placeholders. Do not invent sample metrics, progress, timestamps, successful sync, or feature availability. Never quietly create a local-only imitation of a cloud feature.
- Reuse `MobileTheme`, `GlassTokens`, `GlassComponents`, and localized strings. Add English and Simplified Chinese strings together. Keep accessibility labels and target sizes explicit.
- Make UI changes in small reviewable commits. Check mobile and Wear regressions when shared models, auth, sync, billing, or gateway contracts change.
- Run the relevant Gradle tests/build and inspect a real screenshot when an Android SDK and emulator are available; if unavailable, report that limit and do not claim a visual pass.
- Do not expose secrets, add BYOK flows, or claim availability for incomplete AI, audio, billing, or cross-device paths.

## Decision order

1. Correct task and truthful data state.
2. Navigation, recovery, and accessibility.
3. Visual hierarchy and motion using the existing design vocabulary.
4. Performance and maintainability on small phones and round watches.

Keep this file short. Detailed tokens, UI states, and review findings live in `docs/PRODUCT_DESIGN_CONSTRAINTS.md`.
