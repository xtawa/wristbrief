---
name: Neutral Glass Expressive
colors:
  surface: '#111316'
  surface-dim: '#111316'
  surface-bright: '#37393d'
  surface-container-lowest: '#0c0e11'
  surface-container-low: '#1a1c1f'
  surface-container: '#1e2023'
  surface-container-high: '#282a2d'
  surface-container-highest: '#333538'
  on-surface: '#e2e2e6'
  on-surface-variant: '#c4c6cd'
  inverse-surface: '#e2e2e6'
  inverse-on-surface: '#2f3034'
  outline: '#8e9197'
  outline-variant: '#43474c'
  surface-tint: '#b5c8df'
  primary: '#b5c8df'
  on-primary: '#203243'
  primary-container: '#2c3e50'
  on-primary-container: '#96a9be'
  inverse-primary: '#4e6073'
  secondary: '#adc6ff'
  on-secondary: '#002e6a'
  secondary-container: '#0566d9'
  on-secondary-container: '#e6ecff'
  tertiary: '#6bd8cb'
  on-tertiary: '#003732'
  tertiary-container: '#00453f'
  on-tertiary-container: '#48b8ab'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d1e4fb'
  primary-fixed-dim: '#b5c8df'
  on-primary-fixed: '#091d2e'
  on-primary-fixed-variant: '#36485b'
  secondary-fixed: '#d8e2ff'
  secondary-fixed-dim: '#adc6ff'
  on-secondary-fixed: '#001a42'
  on-secondary-fixed-variant: '#004395'
  tertiary-fixed: '#89f5e7'
  tertiary-fixed-dim: '#6bd8cb'
  on-tertiary-fixed: '#00201d'
  on-tertiary-fixed-variant: '#005049'
  background: '#111316'
  on-background: '#e2e2e6'
  surface-variant: '#333538'
  surface-light: '#F8F9FA'
  surface-container-light: '#F0F2F5'
  surface-dark: '#121417'
  surface-container-dark: '#1B1E22'
  glass-border-light: rgba(255, 255, 255, 0.60)
  glass-border-dark: rgba(255, 255, 255, 0.12)
  semantic-error: '#DC2626'
  semantic-warning: '#D97706'
  semantic-success: '#059669'
typography:
  display-lg:
    fontFamily: Roboto Flex
    fontSize: 44px
    fontWeight: '700'
    lineHeight: 52px
    letterSpacing: -0.02em
  display-lg-mobile:
    fontFamily: Roboto Flex
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Roboto Flex
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Roboto Flex
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Roboto Flex
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: 0em
  title-lg:
    fontFamily: Roboto Flex
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: 0em
  title-md:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 22px
    letterSpacing: 0.01em
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
    letterSpacing: 0.01em
  body-md:
    fontFamily: Roboto Flex
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.01em
  body-sm:
    fontFamily: Roboto Flex
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: 0.02em
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Roboto Flex
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.03em
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

This design system expresses a calm, editorial, and intelligent interface calibrated for deep reading and audio comprehension on modern Android surfaces. It rejects the loud, fluorescent tropes of contemporary AI wrappers and Web3 tools, presenting artificial intelligence as silent, high-utility editorial infrastructure. The visual tone marries the architectural discipline of Material 3 Expressive with the quiet tactility of neutral frosted glass.

The design movement is a hybrid of **Minimalist Editorial** and **Neutral Glassmorphism**. UI elements present high-contrast, structured typographic hierarchy on unadorned canvases for long-form reading, while transient controls, audio players, and executive AI digests float on low-saturation, semi-translucent glass surfaces. The emotional resonance is focused, composed, reliable, and premium—evoking the tactile presence of a bespoke morning broadsheet integrated into an effortless modern device ecosystem.

## Colors

The palette is rooted in muted charcoal and sand foundations, prioritizing reading contrast and eye comfort over chromatic saturation.

- **Primary (`#2C3E50`)**: Deep slate charcoal, used as an anchoring element for structure, high-emphasis icons, and grounding controls.
- **Secondary (`#3B82F6`)**: Refined structural indigo, serving active states, focused category indicators, and timeline highlights.
- **Tertiary (`#0D9488`)**: Restrained editorial teal, reserved for audio waveforms, media playback scrubbers, and intelligent synthesis highlights.
- **Neutral (`#121417`)**: Default dark base canvas. It is strictly never pure pitch black (`#000000`), preserving softness against illuminated mobile OLED panels. In light mode, `#F8F9FA` acts as the pristine base with `#F0F2F5` as grouped container backing.

Semantic tones are restricted strictly to state-confirming events: destructive actions (`#DC2626`), quota notices (`#D97706`), and persistent synchronizations (`#059669`). Heavy gradient meshes, saturated purple glows, and colored full-page canvas washes are prohibited.

## Typography

The type system implements **Roboto Flex** across all scales, leaning on variable weight and proportion axes to balance editorial impact with functional utility.

Headlines feature decisive weights (`700`, `600`) and tighter letter tracking, providing authoritative newsstand hierarchy. Body text is prioritized for prolonged, distraction-free consumption: font weights are stabilized at `400` with comfortable line height multipliers (`1.55`–`1.6x`) directly on solid canvas backgrounds. Labels, metadata chips, and podcast duration tags deploy medium weights (`500`, `600`) with expanded tracking to maintain scannability at small sizes.

Long-form reading surfaces strictly restrict line lengths to a maximum content container width of 840dp on wide screens to protect eye scan return rhythm. Unread articles enforce `600` weight title signatures, while read items dynamically step down to `400` weight with subdued contrast.

## Layout & Spacing

Layout geometry follows a strict 4dp / 8dp incremental cadence. Mobile handheld viewports enforce a horizontal page margin of 20dp (`1.25rem`), maximizing edge-to-edge reading efficiency while preventing thumb occlusion.

- **Breakpoints:** The system transitions from a single-column mobile view to a dual-pane master-detail layout at **600dp** (tablet, foldable, and landscape). The mobile bottom dock shifts smoothly to an anchored vertical navigation rail.
- **Reading Envelope:** Canvas content is capped at **840dp** maximum width on desktop and tablet viewports to preserve ideal typographic measure.
- **Interactive Spatial Buffer:** Every interactive component strictly satisfies a minimum target bounding box of **48dp × 48dp**, regardless of visible visual icon dimensions, facilitating effortless one-handed control on mobile surfaces.

## Elevation & Depth

Visual hierarchy leverages **Tonal Layers** paired with **Targeted Neutral Glass**. Heavy, opaque drop shadows are absent; spatial elevation is expressed via layered luminosity and translucent material refraction.

### 1. The Glass Boundary Rules
Glassmorphism is never applied to the main article reading canvas. It is strictly quarantined to elevated control layers:
- Bottom Navigation Bar
- Hero Brief / AI Overview cards
- Persistent Mini Player dock
- Floating filter chips and quick-action toolbars

### 2. Physical Glass Specification
- **Backdrop Blur:** 12px to 16px blur radius.
- **Surface Fill:**
  - Dark Mode: `rgba(27, 30, 34, 0.72)`
  - Light Mode: `rgba(240, 242, 245, 0.78)`
- **Hairline Boundary:** A uniform 1px outer stroke providing crisp edge definition without cast shadows. In dark mode, this is a refined `rgba(255, 255, 255, 0.12)`; in light mode, `rgba(255, 255, 255, 0.60)`.

### 3. Layer Stack (Z-Index)
1. **Canvas (`0dp`):** Base solid viewport (`#121417` / `#F8F9FA`).
2. **Surface Container (`1dp`):** Standard list and non-glass article cards.
3. **Floating Glass (`2dp`):** Hero Brief modules and AI synthesis panels.
4. **Docked Audio Mini Player (`3dp`):** Translucent glass pill anchored above navigation.
5. **Navigation Rail / Bar (`4dp`):** System navigation chrome.
6. **Modals & Bottom Sheets (`5dp`):** Expanded full-screen player and source managers.

## Shapes

The shape architecture reflects Material 3 Expressive curvature, scaling corner radiuses proportionally with element mass and functional intent:

- **12dp – 16dp (`rounded-md` to `rounded-lg`):** Small controls, text input containers, filter chips, and citation links.
- **20dp – 24dp (`rounded-xl`):** Standard content items, article stream cards, podcast episode previews, and settings groupings.
- **28dp – 32dp (`rounded-2xl`):** Hero cards (Today's Executive Brief) and onboarding modular panels.
- **Full Pill (`9999px`):** Persistent Mini Player shell, primary action triggers, active navigation indicator pills, and duration tags.

Organic expressive variations are deployed sparingly for key focal states, such as the completed briefing check indicator, avoiding visual disorder across high-density reading lists.

## Components

### Buttons & Quick Actions
- **Primary Buttons:** High-contrast pill containers (`9999px` radius) utilizing primary slate charcoal `#2C3E50` or active indigo `#3B82F6`. Minimum height of 48dp, padding 0.75rem horizontal.
- **Secondary / Glass Buttons:** Translucent glass surface with 1px hairline border, subtle background blur (12px), and high-contrast label copy.
- **Icon Actions:** Minimum 48dp interactive bounding box, centered 24dp vector icon, zero visible background until active/pressed (tonal ripple effect).

### Chips & Filters
- **Filter Chips:** 12dp to 16dp rounded corners, height 36dp. Inactive state rests on tonal neutral container with no border. Active state shifts to a subtle surface tint with high-contrast text and a left-aligned micro check icon.
- **Citation Chips:** Dense inline pills (`12dp` radius), subtle 1px border, display domain name or AI source index, opening source overlays on tap.

### Content Cards
- **Article Feed Card:** Flat neutral container (`20dp` radius), 16dp internal padding. Title styled in `title-lg` (bold for unread, regular for read). Direct solid backing—never glass. Bottom row includes publication favicon, domain name, timestamp, and duration/read time label.
- **Hero Brief (AI Card):** Frosted glass container (`28dp` radius, 16px backdrop blur, 1px hairline stroke). Features an understated vector waveform/sparkle header icon, high-density AI synthesis summary, and immediate "Play Audio Summary" action pill.

### Lists & Navigation
- **Article Feed List:** Clean edge-to-edge scroll with 12dp gap between cards. Dividing lines are low-opacity hairlines or pure whitespace separations.
- **Navigation Bar (Mobile):** Elevated glass dock resting across the bottom, housing 3–5 destinations. Active destination highlighted by a pill-shaped indicator background.

### Audio Mini Player
- **Floating Glass Pill:** Anchored 8dp above the navigation bar. 16px backdrop blur, 1px border. Houses play/pause toggle (48dp tap target), dynamic scrolling title, episode time remaining, and a 2dp micro teal progress bar anchored along its bottom boundary. Expands via Compose container transform into the Fullscreen Player Sheet.

### Inputs & Text Fields
- **Search & Filter Fields:** 16dp corner radius, resting height 48dp. Subtle surface container fill (`#1B1E22` / `#F0F2F5`), no harsh dark border, placeholder text in `label-md` neutral secondary. Trailing action allows immediate clearing or voice input.