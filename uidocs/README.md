# WristBrief UI — Liquid Glass Direction

This folder is the design and implementation handoff for the approved WristBrief liquid-glass visual direction across **Android phone and Wear OS**.

## Shared visual direction

The overall goal is a calm, system-like interface with:

- quiet information architecture;
- high legibility;
- restrained translucent hierarchy;
- mostly neutral surfaces;
- small semantic accent usage only;
- **no decorative gradients**.

Liquid-glass depth should come from transparency, borders, shadows, tonal layering, and motion rather than colorful gradient fields.

## Phone scope

The phone direction remains the restrained monochrome liquid-glass system documented in:

- [`PHONE_LIQUID_GLASS_SPEC.md`](./PHONE_LIQUID_GLASS_SPEC.md)
- [`assets/phone-liquid-glass-concept.svg`](./assets/phone-liquid-glass-concept.svg)
- [`examples/LiquidGlassPhoneExample.kt`](./examples/LiquidGlassPhoneExample.kt)

Phone priorities:

1. establish reusable glass tokens/primitives;
2. re-skin OOBE;
3. re-skin Home / Explore / Ask AI / Library / playback surfaces;
4. keep information density controlled;
5. verify light/dark themes, accessibility, and performance.

## Wear OS scope

The Wear direction is intentionally **sparser than phone** and follows a simple crown-scroll hierarchy:

1. **Today’s Brief** — opening viewport, AI key-point summary only;
2. **Latest in Library** — reached by scrolling/crown down, showing only the newest 1–2 items;
3. **More** — reached by continuing down, containing only `Library`, `Now Playing`, and `Settings` entry buttons.

Wear design and implementation handoff:

- [`WEAR_LIQUID_GLASS_SPEC.md`](./WEAR_LIQUID_GLASS_SPEC.md)
- [`assets/wear-liquid-glass-concepts.svg`](./assets/wear-liquid-glass-concepts.svg)
- [`examples/WearLiquidGlassExample.kt`](./examples/WearLiquidGlassExample.kt)

The Wear implementation should use `androidx.wear.compose.material3` and Wear-native scaffolds/lists rather than reusing phone Material components.

## Non-negotiable visual rules

### No gradients

Do not use linear, radial, sweep, mesh, aurora, rainbow, iridescent, gradient-border, gradient-button, or decorative color-wash effects on either phone or Wear surfaces.

Flat semantic colors are allowed in small areas when they communicate meaning, such as RSS orange, podcast purple, account/provider identity, playback state, success, warning, or error.

### Avoid plastic-looking glass

Especially on Wear OS, do not use thick cloudy overlays, exaggerated bevels, bright glossy edge highlights, floating glass bubbles, or milky acrylic slabs. Glass should be subtle and secondary to content.

## Product behavior

The visual redesign must wrap existing functionality rather than remove it. WristBrief still needs to support RSS, podcasts, AI Brief, Ask AI, playback, source/library management, onboarding, settings, account/sync, and phone ↔ Wear synchronization.

## Files

```text
uidocs/
  README.md
  PHONE_LIQUID_GLASS_SPEC.md
  WEAR_LIQUID_GLASS_SPEC.md
  assets/
    phone-liquid-glass-concept.svg
    wear-liquid-glass-concepts.svg
  examples/
    LiquidGlassPhoneExample.kt
    WearLiquidGlassExample.kt
```

## Design intent in one sentence

**Quiet information architecture, subtle glass hierarchy, strong typography, minimal color, and zero decorative gradients — with Wear OS using an even lower-density, crown-first layout.**