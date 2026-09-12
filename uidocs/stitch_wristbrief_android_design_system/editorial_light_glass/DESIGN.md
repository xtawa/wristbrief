---
name: Editorial Light Glass
colors:
  surface: '#F8F9FA'
  surface-dim: '#d9dadb'
  surface-bright: '#f8f9fa'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#F5F6F8'
  surface-container: '#F1F3F5'
  surface-container-high: '#E9ECEF'
  surface-container-highest: '#DEE2E6'
  on-surface: '#191C1E'
  on-surface-variant: '#43474E'
  inverse-surface: '#2e3132'
  inverse-on-surface: '#f0f1f2'
  outline: '#73777F'
  outline-variant: rgba(0, 0, 0, 0.06)
  surface-tint: '#006590'
  primary: '#004c6e'
  on-primary: '#ffffff'
  primary-container: '#C8E6FF'
  on-primary-container: '#001E2E'
  inverse-primary: '#88ceff'
  secondary: '#016874'
  on-secondary: '#ffffff'
  secondary-container: '#97F0FF'
  on-secondary-container: '#001F24'
  tertiary: '#42474c'
  on-tertiary: '#ffffff'
  tertiary-container: '#5a5f63'
  on-tertiary-container: '#d5d9de'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c8e6ff'
  primary-fixed-dim: '#88ceff'
  on-primary-fixed: '#001e2e'
  on-primary-fixed-variant: '#004c6d'
  secondary-fixed: '#a2effd'
  secondary-fixed-dim: '#85d2e0'
  on-secondary-fixed: '#001f24'
  on-secondary-fixed-variant: '#004f58'
  tertiary-fixed: '#dfe3e8'
  tertiary-fixed-dim: '#c2c7cc'
  on-tertiary-fixed: '#171c20'
  on-tertiary-fixed-variant: '#42474c'
  background: '#f8f9fa'
  on-background: '#191c1d'
  surface-variant: '#e1e3e4'
  glass-surface: rgba(255, 255, 255, 0.78)
  glass-border: rgba(0, 0, 0, 0.06)
  semantic-error: '#BA1A1A'
  semantic-warning: '#8C5000'
  semantic-success: '#1B6C43'
typography:
  display-lg:
    fontFamily: Roboto Flex
    fontSize: 40px
    fontWeight: '700'
    lineHeight: 48px
    letterSpacing: -0.02em
  display-lg-mobile:
    fontFamily: Roboto Flex
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Roboto Flex
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 38px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Roboto Flex
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 30px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Roboto Flex
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 26px
    letterSpacing: 0em
  title-lg:
    fontFamily: Roboto Flex
    fontSize: 17px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: 0em
  title-md:
    fontFamily: Roboto Flex
    fontSize: 15px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 17px
    fontWeight: '400'
    lineHeight: 26px
    letterSpacing: 0.01em
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: 0.01em
  body-sm:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 17px
    letterSpacing: 0.02em
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 13px
    fontWeight: '600'
    lineHeight: 18px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Roboto Flex
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.03em
  label-sm:
    fontFamily: Roboto Flex
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.04em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.5rem
  margin: 1.25rem
  margin-tablet: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system establishes a refined, high-clarity editorial environment for WristBrief Android in native light mode. It treats digital intelligence and automated syndication as calm, invisible infrastructure rather than a flashy novelty. The visual language blends the deliberate structure of modern broadsheet typography with the soft, luminous discipline of Material 3 Expressive and neutral frosted glass.

Designed for demanding daytime reading, focused commute listening, and dense RSS aggregation, the aesthetic rejects stark high-contrast pitch blacks and harsh neon accents. Instead, it pairs pristine off-white canvases (`#F8F9FA`, `#FFFFFF`) with deep charcoal letterforms (`#191C1E`, `#2C3135`) for peak legibility without optical fatigue. Controlled, muted teal and deep cyan accents anchor audio progress and actionable insights, evoking the poise of an executive morning digest on high-grade paper.

## Colors

The light mode palette prioritizes daytime luminance management and editorial contrast.

- **Primary (`#006590`)**: Muted deep ocean blue. Drives prominent actions, active tab pill indicators, and high-emphasis playback controls.
- **Secondary (`#006874`)**: Deep calm cyan. Governs live audio scrubbing, streaming progress rings, and RSS source metadata.
- **Tertiary (`#2C3135`)**: Deep editorial slate. Provides typographic gravity for section headings and structural icons.
- **Neutral (`#F8F9FA`)**: Soft, calibrated off-white base canvas. Prevents the glare of raw `#FFFFFF` on bright mobile displays while preserving crisp typographic edges. `#FFFFFF` is reserved strictly for elevated solid cards and inner content wells.
- **Glass Accents**: Translucent containers use `rgba(255, 255, 255, 0.78)` bounded by an ultra-delicate hairline outline `rgba(0, 0, 0, 0.06)`.

Colors are applied with editorial discipline: interactive elements use rich teal and cyan tones, while reading canvases remain neutral and calm.

## Typography

The type system runs entirely on **Roboto Flex**, deploying its variable weight and proportion axes to balance editorial warmth with crisp data density.

- **Editorial Hierarchy:** Display and headline levels use robust `700` and `600` weights with negative tracking (`-0.02em` to `-0.01em`) to mimic authoritative newspaper typesetting. Unread article headlines enforce `title-lg` with `fontWeight: 600`, while read items soften dynamically to `400` weight at reduced opacity.
- **Reading Measure:** Body copy is calibrated at `1.55x` to `1.6x` line height ratios, set strictly in deep charcoal (`#191C1E`) over light neutral surfaces to maintain high contrast without ocular glare.
- **Metadata Clarity:** Labels, timestamps, podcast episode badges, and domain markers deploy `label-md` and `label-sm` with expanded letter tracking (`+0.03em` to `+0.04em`) to ensure legibility on smaller screens.

## Layout & Spacing

The layout adheres to an 8dp structural grid with 4dp micro-step increments:

- **Mobile Viewports (<600dp):** Single-column presentation with outer page margins of 20dp (`1.25rem`) and element gaps of 12dp to 16dp (`0.75rem` to `1rem`). This preserves edge-to-edge reading efficiency while keeping controls comfortably away from hand edges.
- **Expanded & Foldable Viewports (≥600dp):** Shifts to a dual-pane master-detail architecture with an anchored vertical navigation rail on the left. Outer margins expand to 32dp (`2rem`), with a column gutter of 24dp (`1.5rem`).
- **Typographic Measure:** Long-form article containers enforce an absolute maximum line envelope of 720dp to preserve comfortable eye sweep patterns.
- **Touch Ergonomics:** Every interactive component—from scrub thumbs to source chip dismissals—enforces a minimum bounding tap target of 48dp × 48dp.

## Elevation & Depth

Elevation eschews heavy drop shadows in favor of **Tonal Containers** and **Controlled Frosted Glass**.

### 1. Glass Boundary Principle
Glass is reserved for transient control docks, floating player bars, and summary overlays. Main article and RSS text surfaces sit strictly on opaque, solid ground to safeguard optical contrast.

### 2. Physical Glass Specification
- **Backdrop Blur:** 16px to 20px blur radius.
- **Surface Tint:** `rgba(255, 255, 255, 0.78)` in standard ambient light; `rgba(241, 243, 245, 0.85)` when elevated over image banners.
- **Hairline Border:** A uniform 1px outer hairline stroke set to `rgba(0, 0, 0, 0.06)`, providing crisp edge delineation without shadow bleed.
- **Ambient Shadow:** A faint, diffused wash (`y: 4px`, `blur: 16px`, `rgba(0, 0, 0, 0.04)`) applied only to floating chrome to distinguish it from the scrolling canvas underneath.

### 3. Surface Hierarchy
- **Level 0 (Canvas):** Base background `#F8F9FA`.
- **Level 1 (Card Well):** Solid white containers (`#FFFFFF`) for RSS feed cards and system preferences.
- **Level 2 (Glass Highlights):** Frosted glass panels for AI summaries and Briefing modules.
- **Level 3 (Floating Mini Player):** Persistent frosted glass dock floating 12dp above the navigation layer.
- **Level 4 (System Navigation):** Frosted glass bottom bar or adaptive side rail.
- **Level 5 (Modals & Full Audio Sheet):** Full-bleed surfaces (`#FFFFFF` with `#F1F3F5` grouped sections).

## Shapes

The shape system embraces Material 3 Expressive curvature:

- **8dp – 12dp (`rounded` to `rounded-md`):** Micro badges, episode scrub tracks, citation tags, and small dropdowns.
- **16dp (`rounded-lg`):** Text input containers, filter chips, and dialog actions.
- **20dp – 24dp (`rounded-xl`):** Primary content containers, RSS feed cards, and podcast preview cells.
- **28dp – 32dp (`rounded-2xl`):** Hero Briefing cards, AI summary containers, and bottom sheet top sheets.
- **Full Pill (`9999px`):** Persistent Mini Player shell, primary action triggers, active navigation indicator pills, and playback rate indicators.

## Components

### Buttons & Floating Controls
- **Primary Buttons:** High-contrast pill containers (`9999px` radius) filled with `#006590` and crisp `#FFFFFF` typography. 48dp minimum height, with a tonal ripple on press.
- **Secondary Glass Buttons:** Translucent glass surface (`rgba(255, 255, 255, 0.75)`), 1px border (`rgba(0, 0, 0, 0.06)`), 16px blur, and deep charcoal text (`#191C1E`).
- **Icon Actions:** 48dp interactive bounding box surrounding a 24dp vector icon, executing a soft gray circular ripple (`rgba(0, 0, 0, 0.05)`) upon touch.

### Chips & Filter Toggles
- **Filter Chips:** 16dp rounded corners, 36dp container height. Resting state is `#F1F3F5` with no border and `#43474E` text. Active state transitions to `#C8E6FF` background with `#001E2E` text and a leading check mark.
- **Source Citation Chips:** 10dp rounded corners, delicate 1px border (`rgba(0, 0, 0, 0.06)`), housing publication favicons and domain slugs.

### Content & Feed Cards
- **RSS Article Card:** Solid `#FFFFFF` container with 20dp corner radius, framed by a delicate hairline border (`rgba(0, 0, 0, 0.06)`). Includes unread state indicator (teal dot `#006874`), headline in `title-lg`, reading time estimate, and a subtle swipe-to-queue gesture slot.
- **Hero Brief (AI Summary):** Frosted glass container (`rounded-2xl`, 16px backdrop blur, `rgba(255, 255, 255, 0.8)` fill). Features an editorial header row with an understated cyan sparkle glyph, concise bulleted takeaway lists, and a pill-shaped "Listen to Brief" button.

### Podcast & Audio Player Controls
- **Floating Mini Player:** Anchored 12dp above the navigation bar. Translucent glass pill (`9999px` radius), 1px hairline stroke, containing current artwork (36dp rounded square), marquee title, play/pause trigger (48dp touch target), and an embedded 2dp cyan progress track (`#006874`) along the lower rim.
- **Full Player Sheet:** High-density editorial playback UI. Features 1:1 rounded artwork (24dp radius), dual-channel scrub slider with elapsed/remaining timestamps in `label-sm`, dynamic playback speed pill selector (1.0x, 1.2x, 1.5x), and automated chapter markers with read-along transcript sync.

### Lists & Navigation
- **Article Feed Stream:** Edge-to-edge scroll with 12dp card separation. Section headers rely on whitespace and deep charcoal type rather than heavy divider bars.
- **Bottom Navigation Dock:** Translucent frosted glass bar resting along the screen bottom, utilizing pill-shaped indicator bounds (`#C8E6FF`) for active destinations with `#001E2E` icons.