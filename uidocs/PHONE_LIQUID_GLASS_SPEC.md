# WristBrief Phone Liquid Glass — Design & Visual Specification

Status: **approved direction for phone UI documentation**  
Platform scope: **Android phone only**  
Wear OS scope: **unchanged**

---

## 1. Design philosophy

WristBrief should feel like a calm information instrument rather than a colorful media dashboard.

The selected direction uses a restrained form of liquid glass:

- content appears on softly translucent planes;
- hierarchy comes from opacity, blur, border contrast, and elevation;
- typography remains the strongest visual anchor;
- controls are rounded but not inflated or playful;
- the UI is mostly neutral, with color used only for meaning;
- surfaces remain readable even when blur is unavailable;
- decorative gradients are completely prohibited.

The phone experience should feel closer to a native system surface than to a promotional landing page.

### Core principles

1. **Information first** — article titles, brief summaries, playback state, and actions must dominate the visual hierarchy.
2. **Glass is structure, not decoration** — use glass to separate layers, not to add spectacle.
3. **One calm canvas** — avoid competing backgrounds, colored blobs, aurora fields, or noisy imagery behind glass.
4. **Monochrome by default** — neutral surfaces and text; semantic brand colors only when they communicate something.
5. **Low visual entropy** — few simultaneous card styles, few radii, few elevations, consistent padding.
6. **System-like motion** — short, physical, restrained transitions with no neon glow or overshoot-heavy animation.
7. **Phone-only redesign** — do not propagate this visual refactor into the Wear UI.

---

## 2. Hard visual constraints

### 2.1 No gradients

The implementation must not use:

- `Brush.linearGradient`
- `Brush.radialGradient`
- `Brush.sweepGradient`
- mesh gradients
- multi-stop color interpolation used as a decorative fill
- aurora / glow / rainbow backgrounds
- gradient borders
- gradient buttons
- gradient selected states

A single flat color with alpha is allowed.

### 2.2 No oversized decorative color fields

Do not place large purple/blue/pink blobs behind the UI. If a background needs depth, use:

- a flat neutral canvas;
- one translucent surface above it;
- blur where supported;
- a subtle monochrome shadow;
- a hairline border.

### 2.3 No “plastic card wall”

Not every row needs a separate card. Use glass surfaces for:

- primary brief / hero modules;
- bottom navigation;
- modal sheets;
- playback overlays;
- grouped settings/source sections.

Use plain rows/dividers for dense content lists.

---

## 3. Color system

All values below are starting tokens, not a requirement to hardcode colors instead of Material theme values.

### Light theme

| Token | Value | Use |
|---|---:|---|
| `canvas` | `#F4F5F7` | main background |
| `surfaceGlass` | `#FFFFFF` @ 62% | standard glass plane |
| `surfaceGlassStrong` | `#FFFFFF` @ 82% | key cards, nav, sheets |
| `surfaceSolid` | `#FFFFFF` | fallback where translucency hurts readability |
| `textPrimary` | `#111317` | primary text |
| `textSecondary` | `#646B75` | supporting text |
| `hairline` | `#D9DEE6` @ 78% | borders/dividers |
| `controlSelected` | `#17191D` | selected chip/tab/primary neutral control |
| `onControlSelected` | `#FFFFFF` | selected control content |
| `shadow` | `#000000` @ 10% | soft elevation |

### Dark theme

| Token | Value | Use |
|---|---:|---|
| `canvas` | `#111315` | main background |
| `surfaceGlass` | `#1B1E22` @ 72% | standard glass plane |
| `surfaceGlassStrong` | `#22262B` @ 86% | key cards, nav, sheets |
| `surfaceSolid` | `#1B1E22` | fallback |
| `textPrimary` | `#F5F7FA` | primary text |
| `textSecondary` | `#A7AFBA` | supporting text |
| `hairline` | `#FFFFFF` @ 12% | borders/dividers |
| `controlSelected` | `#F4F5F7` | selected neutral control |
| `onControlSelected` | `#111317` | selected control content |
| `shadow` | `#000000` @ 28% | soft elevation |

### Semantic color policy

Flat colors are allowed for meaning:

- RSS orange
- podcast purple
- success / warning / error colors
- Google identity colors
- small WristBrief brand mark

Rules:

- use flat fills only;
- do not combine them into a gradient;
- keep accent coverage small;
- never tint the entire screen just to make it “more glass”.

---

## 4. Typography

Use the existing Material 3 typography system as the implementation baseline. Do not introduce a decorative display font.

Recommended hierarchy:

| Role | Approx. size | Weight |
|---|---:|---|
| OOBE hero | 32–36sp | 600–700 |
| Screen title | 28–32sp | 600–700 |
| Section title | 20–22sp | 600 |
| Card title | 16–18sp | 600 |
| Body | 14–16sp | 400 |
| Secondary/meta | 12–14sp | 400–500 |
| Navigation label | 11–12sp | 500 |

Rules:

- no all-caps section labels except existing content/brand conventions;
- maintain comfortable line height;
- max two emphasis levels inside one card;
- never reduce contrast just to make the UI look “frosted”.

---

## 5. Shape, spacing, and elevation

### Corner radii

Use a very small radius vocabulary:

- `32dp` — large sheets / major hero containers
- `24dp` — primary cards / grouped modules
- `18dp` — standard rows / source cards
- `14dp` — compact controls
- `999dp` — pills / chips / segmented controls

### Spacing scale

`4, 8, 12, 16, 20, 24, 32dp`

Primary screen side padding: `20dp` on compact phone widths.

### Shadows

Use diffuse monochrome shadows only.

- standard glass: `4–8dp` elevation impression
- nav/sheet: `8–16dp` impression
- avoid dark hard-edged drop shadows
- never use colored shadows or glows

---

## 6. Glass surface recipe

A WristBrief glass surface is composed of four layers:

1. **neutral translucent fill**
2. **optional background blur** where the platform path supports it safely
3. **1dp neutral hairline border**
4. **soft monochrome shadow/elevation**

The glass effect must survive without blur. API/device capability differences must not destroy hierarchy.

### Recommended opacity tiers

- content glass: `0.58–0.68`
- primary brief / mini player: `0.72–0.84`
- bottom navigation / modal sheet: `0.80–0.90`

Do not make body text sit directly on a highly transparent region with visually complex content behind it.

### Background blur guidance

Android does not provide a universal Compose backdrop-blur primitive across all supported API levels. Therefore:

- design glass so it works with translucency + border + shadow alone;
- use a capability-gated blur path only where verified;
- keep a solid fallback for accessibility, battery saver, low-end devices, screenshots, and reduced-transparency settings if added later;
- do not add a large third-party rendering dependency solely for blur without a separate technical review.

---

## 7. OOBE flow

The concept uses five phone onboarding stages. Copy can evolve, but the structure should remain concise.

### 01 — Welcome

Purpose: explain the product in under five seconds.

Content:

- WristBrief mark/name
- headline such as **“Less noise. More you.”** or **“Your feeds, made brief.”**
- one sentence: RSS + podcasts + AI brief
- one primary `Get started` action
- optional `Sign in` secondary action

Visual:

- mostly empty neutral background
- one restrained translucent object or glass plane is acceptable if monochrome
- no colorful background illustration

### 02 — Interests

Purpose: choose a small number of personalization topics.

Pattern:

- pill chips
- selected = flat neutral dark/bright inversion
- unselected = translucent neutral outline
- no rainbow category colors

### 03 — Add content

Purpose: connect real sources early.

Rows:

- RSS feeds
- podcasts
- OPML import
- optional supported account/import sources

Rows should be grouped in one or two glass containers, not each wrapped in excessive card chrome.

### 04 — AI features

Explain capabilities, not model/vendor marketing.

Recommended rows:

- Daily Brief
- Ask AI
- Listen Instead / AI narration
- Save / Export where applicable

Avoid presenting AI as a glowing magic object. Use normal product UI.

### 05 — Ready

Confirm setup with a short checklist:

- feeds connected
- interests personalized
- AI ready
- sync enabled

Primary action: `Start exploring`.

If Wear sync is shown, show it as a capability summary only. **Do not redesign Wear UI as part of this work.**

---

## 8. Home information architecture

### Top region

- compact greeting
- optional profile/avatar action
- short status line

### Daily Brief

The primary hero module of Home.

Contains:

- title
- duration / key insight count
- play/read action
- minimal metadata

Use `surfaceGlassStrong` and a 24–32dp radius. No background artwork is required.

### Segmented content filter

Recommended:

- For you
- Latest
- Podcasts
- Saved/RSS depending on final IA

Selected segment uses a flat neutral fill. No gradient highlight.

### Content list

Prefer readable rows with:

- thumbnail where useful
- source
- title
- reading/listening time
- save / overflow action

Do not wrap every article in a heavy glass card.

### Bottom navigation

The nav can be one of the strongest glass surfaces:

- floating inset container
- 28–32dp radius
- strong translucent fill
- thin border
- soft shadow
- selected state = flat neutral pill/circle

No glowing halo, no gradient selection indicator.

---

## 9. Explore, Now Playing, Ask AI, Library

### Explore

- plain top bar
- search entry
- compact segmented filter
- readable content list
- use glass only for filter/search grouping when useful

### Now Playing

- quiet full-screen canvas
- one artwork region
- simple progress bar
- primary transport controls
- optional transcript and Ask AI actions
- no gradient album backdrop

If visual depth is needed, blur/desaturate a **monochrome/neutral** representation or use a flat neutral background.

### Ask AI

- large readable title
- contextual suggestion chips/rows
- bottom input field
- can appear as a strong glass sheet over a dimmed/blurred underlying screen
- no glowing orb or “magic” gradient

### Library

- strong information hierarchy
- filters at top
- standard rows/groups for saved articles, podcasts, summaries, exports
- glass used for grouping, not every item

---

## 10. Motion

Motion should reinforce material layering.

Recommended:

- screen transitions: `180–280ms`
- card/sheet entry: fade + 6–12dp translation
- press feedback: subtle scale to `0.98–0.99`
- sheet spring: critically damped or low-bounce
- nav selection: short position/opacity transition

Avoid:

- rainbow shimmer
- continuous floating blobs
- strong parallax
- repeated breathing glow
- exaggerated elastic overshoot

Respect system animation scale and reduced-motion behavior where available.

---

## 11. Accessibility

Minimum requirements:

- WCAG-like contrast targets for text/control states
- touch targets at least 48dp where practical
- screen-reader descriptions for icon-only controls
- glass opacity must increase when readability requires it
- do not encode state only with transparency
- selected chips/nav need shape/content contrast in addition to subtle opacity changes
- support light/dark/system theme behavior already present in the phone app

---

## 12. Compose implementation rules

The current phone module already uses Jetpack Compose + Material 3. The redesign should stay within that stack unless a separate dependency decision is approved.

### Reusable primitives to introduce

Suggested conceptual components:

- `GlassSurface`
- `GlassCard`
- `GlassNavigationBar`
- `GlassSheet`
- `NeutralFilterChip`
- `DailyBriefCard`
- `MiniPlayerGlass`

### Avoid implementation drift

Do not:

- fork a second visual system for each screen;
- hardcode one-off alpha/radius values everywhere;
- introduce gradients later “for polish”;
- alter Wear Compose code while refactoring phone components;
- make phone and Wear share a visual primitive if it would implicitly change the Wear appearance.

### Suggested source organization

```text
mobile/src/main/java/ink/underflo/wristbrief/mobile/ui/
  glass/
    GlassTokens.kt
    GlassSurface.kt
  components/
    DailyBriefCard.kt
    GlassBottomBar.kt
  onboarding/
  home/
```

This document does **not** require an immediate package restructure; it describes the target ownership boundaries.

---

## 13. Verification checklist

Before calling the phone redesign complete:

- [ ] No gradient brushes in phone UI source.
- [ ] No decorative purple/blue/pink background fields.
- [ ] Glass surfaces remain readable with blur disabled.
- [ ] Light and dark themes both work.
- [ ] OOBE has a clear primary action on every step.
- [ ] Home Daily Brief is the strongest module.
- [ ] Dense feed rows are not over-carded.
- [ ] Bottom navigation is legible and accessible.
- [ ] Ask AI feels like a normal productivity surface, not a visual effect demo.
- [ ] Playback controls remain obvious.
- [ ] Existing functionality is not removed by the redesign.
- [ ] Wear UI visuals are unchanged.
- [ ] Wear sync behavior is not regressed.
- [ ] Phone UI tests/builds still pass.

---

## 14. Concept reference

Selected direction:

![WristBrief phone liquid glass concept](./assets/phone-liquid-glass-concept.jpg)

Treat the concept image as **layout and atmosphere guidance**, not pixel-perfect production truth. The written constraints in this document take precedence, especially the **no-gradient** rule and the **Wear unchanged** boundary.
