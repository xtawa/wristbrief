# WristBrief Wear OS Liquid Glass — Design & Visual Specification

Status: **approved Wear OS direction**  
Platform scope: **Wear OS**  
Phone scope: **unchanged by this document**

---

## 1. Design philosophy

WristBrief on Wear OS should behave like a calm, glanceable information instrument rather than a miniature phone dashboard.

The selected direction combines Wear OS Material 3 Expressive layout principles with a restrained liquid-glass treatment:

- black-first canvas;
- one clear purpose per viewport;
- subtle neutral translucency instead of milky or glossy plastic;
- typography and spacing carry most of the hierarchy;
- rotary/crown scrolling reveals content in a simple vertical sequence;
- no decorative gradients, color blobs, glow fields, or dense dashboard layouts.

### Core principles

1. **One screen, one purpose** — do not place summary, feed, player controls, settings, and navigation on one viewport.
2. **Glanceability first** — the user should understand the screen within roughly one second.
3. **Black-first Wear canvas** — build from black and near-black surfaces.
4. **Glass is structure, not decoration** — only use translucency to separate hierarchy.
5. **Low element count** — keep only the most important information visible at once.
6. **Vertical progression** — crown/rotary scroll moves through clear content groups.
7. **Wear-native components** — implement with `androidx.wear.compose.material3`, not phone Material 3 widgets.

---

## 2. Primary vertical information architecture

The main experience is a three-stage vertical flow.

### Stage 1 — Today’s Brief

This is the first viewport when opening WristBrief.

**Purpose:** show the AI-generated key-point summary for the day.

Visible content should be limited to:

- Wear OS `TimeText`;
- small WristBrief label if useful;
- page title: `Today's Brief`;
- 2–3 concise AI key points;
- one primary `Listen` action when audio is available.

Do **not** show latest articles, library buttons, settings, filters, or multiple navigation destinations here.

### Stage 2 — Latest in Library

Reached by rotating the crown / scrolling down.

**Purpose:** preview the newest content available in the user’s library.

Visible content:

- title: `Latest in Library`;
- at most 1–2 latest items at a time;
- each item: title + content type + reading/listening duration;
- optional small thumbnail or neutral content icon.

This is a preview surface. The complete library belongs on its own destination.

### Stage 3 — More

Reached by continuing to scroll down.

**Purpose:** provide entry points into secondary destinations.

Exactly three primary rows:

1. `Library`
2. `Now Playing`
3. `Settings`

Do not turn this page into a second dashboard.

---

## 3. Secondary destinations

### Library

The full library can contain saved articles, podcasts, AI summaries, and history, but the viewport should still remain sparse:

- one title;
- optional compact filter row;
- a small number of large readable list items;
- scroll for more.

### Now Playing

The player should focus on playback, not metadata density:

- artwork or neutral media icon;
- title + source;
- progress;
- previous/rewind, play/pause, next/forward;
- optional `Ask AI` secondary action.

### Settings

Use a plain vertical list. Keep settings grouped and easy to scan.

---

## 4. Hard visual constraints

### 4.1 No gradients

The Wear UI must not use:

- linear gradients;
- radial gradients;
- sweep gradients;
- mesh gradients;
- gradient borders;
- gradient buttons;
- aurora / rainbow / colored glow backgrounds;
- album-art color wash backgrounds.

Depth comes from **opacity, borders, shadows, shape, spacing, and motion**.

### 4.2 Avoid plastic-looking glass

Do not use:

- thick cloudy white overlays;
- large bright specular highlights;
- exaggerated bevels;
- high-opacity frosted acrylic slabs;
- repeated floating bubbles;
- glossy transparent buttons everywhere.

The chosen Wear treatment should be much weaker than the first concept iteration.

### 4.3 Low density

A normal viewport should usually contain:

- one headline;
- one primary content group;
- zero to two secondary content items **or** up to three navigation rows.

If the design feels like multiple phone cards were scaled down to fit a watch, it is too dense.

---

## 5. Color system

Wear OS screens should build from black.

### Core tokens

| Token | Suggested value | Use |
|---|---:|---|
| `wearCanvas` | `#000000` | page background |
| `surfaceSubtle` | `#FFFFFF` @ 8–10% | standard restrained glass plane |
| `surfaceStrong` | `#FFFFFF` @ 12–14% | navigation/action surface |
| `hairline` | `#FFFFFF` @ 14–18% | border/divider |
| `textPrimary` | `#F5F7FA` | primary text |
| `textSecondary` | `#A7AFBA` | supporting/meta text |
| `shadow` | `#000000` @ 30–35% | soft separation |

### Accent policy

Use color only when it communicates state or meaning:

- active playback;
- error/success state;
- small brand mark;
- tiny semantic content-type indicator.

Do not tint large surfaces for decoration.

---

## 6. Typography

Use the Wear Material 3 type system as the implementation baseline.

Recommended hierarchy:

| Role | Approx. size | Weight |
|---|---:|---|
| Hero/page title | 20–24sp | 600–700 |
| Item title | 15–18sp | 500–600 |
| Body / key point | 13–15sp | 400–500 |
| Metadata | 11–12sp | 400 |

Rules:

- keep titles short;
- avoid paragraphs;
- AI key points should normally fit within 1–2 lines each;
- do not shrink text just to fit more content;
- text must remain the strongest visual anchor.

---

## 7. Shape, spacing, and surface recipe

### Shape vocabulary

Use a small set of shapes:

- major content card: `26–32dp` radius;
- list/navigation row: `20–24dp` radius;
- icon action: circular;
- pills only when the interaction genuinely behaves like a chip/filter.

### Spacing

Recommended starting scale:

- outer horizontal padding: `12–16dp`;
- internal card padding: `12–16dp`;
- vertical item spacing: `8–12dp`;
- section separation: `16–24dp`.

### Restrained glass recipe

A WristBrief Wear glass surface should use:

1. a nearly transparent neutral fill;
2. a thin neutral hairline border;
3. soft monochrome shadow/elevation;
4. blur only where platform support and readability justify it.

The design must remain understandable if blur is absent.

---

## 8. Wear OS Material 3 alignment

Implementation should follow Wear OS Material 3 Expressive primitives rather than imitating a phone UI.

Recommended platform patterns:

- `AppScaffold` at the app level;
- `ScreenScaffold` for each destination;
- `TransformingLazyColumn` for crown/rotary-friendly vertical content;
- Wear `TimeText` and `ScrollIndicator` behavior from the scaffold;
- adaptive padding for different watch sizes;
- Wear Material 3 cards/buttons and shape transformation where appropriate.

The project already uses `androidx.wear.compose:compose-material3`, so this redesign should stay within that stack.

Official references:

- Wear OS design: https://developer.android.com/design/ui/wear
- Wear Compose Material 3: https://developer.android.com/jetpack/androidx/releases/wear-compose-m3
- ScreenScaffold: https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/ScreenScaffold
- Different screen sizes: https://developer.android.com/training/wearables/compose/screen-size

---

## 9. Detailed page specification

### 9.1 Today’s Brief

Hierarchy:

1. `TimeText`
2. small `WristBrief` label
3. `Today's Brief`
4. one restrained summary surface
5. 2–3 key points
6. one `Listen` action when applicable

The summary itself is the hero. Avoid background artwork.

### 9.2 Latest in Library

Hierarchy:

1. `Latest in Library`
2. latest item
3. second latest item if space allows

Each item should include only:

- optional thumbnail/icon;
- title;
- `Article · 5 min` / `Podcast · 28 min` style metadata.

### 9.3 More

Hierarchy:

1. `More`
2. `Library`
3. `Now Playing`
4. `Settings`

Each row is a large, touch-friendly destination button with one icon and one label.

---

## 10. Motion and crown interaction

Motion should reinforce vertical hierarchy without spectacle.

Recommended:

- short fade/translation on entry;
- normal Wear `TransformingLazyColumn` scaling/morphing behavior;
- subtle press shape/scale response;
- no continuous floating motion;
- no breathing glow;
- no exaggerated elastic overshoot.

Crown scrolling should naturally reveal Stage 1 → Stage 2 → Stage 3 without requiring a dense navigation chrome layer.

---

## 11. Accessibility and ergonomics

Minimum requirements:

- high contrast against black;
- touch targets sized for watch interaction;
- icon-only actions must have content descriptions;
- do not communicate state only through alpha;
- glass opacity must increase if content becomes difficult to read;
- preserve rotary input and swipe-to-dismiss expectations;
- support different round display sizes without clipping text or controls.

---

## 12. Compose implementation boundaries

Suggested reusable primitives:

- `WearGlassSurface`
- `WearBriefCard`
- `WearLibraryPreviewRow`
- `WearSecondaryEntryButton`

Suggested logical ownership:

```text
app/src/main/java/ink/underflo/wristbrief/
  wear/ui/liquidglass/
    WearGlassTokens.kt
    WearGlassSurface.kt
    TodayBriefWearScreen.kt
    LatestLibraryWearScreen.kt
    MoreWearScreen.kt
```

This is an ownership recommendation, not a requirement to immediately restructure the existing app.

Do not share phone visual components directly with Wear if doing so makes the Wear layout denser or changes its Material 3 behavior.

---

## 13. Verification checklist

- [ ] First viewport is primarily the AI key-point summary.
- [ ] The first viewport does not contain feed rows/settings/navigation hub content.
- [ ] Crown/scroll down reaches `Latest in Library` as a distinct content group.
- [ ] Library preview shows only a small number of latest items.
- [ ] Further scrolling reaches the `More` entry hub.
- [ ] `More` exposes Library / Now Playing / Settings.
- [ ] No decorative gradients exist.
- [ ] Glass does not look milky, glossy, or plastic.
- [ ] Black remains the visual foundation.
- [ ] Layout remains glanceable on smaller watches.
- [ ] Wear Compose Material 3 primitives are used.
- [ ] Rotary input remains natural.
- [ ] Phone UI is unaffected by the Wear implementation.

---

## 14. Concept reference

See [`assets/wear-liquid-glass-concepts.svg`](./assets/wear-liquid-glass-concepts.svg).

The concept board illustrates the approved information architecture and visual density. The written rules in this document take precedence over pixel-level details in the concept image.