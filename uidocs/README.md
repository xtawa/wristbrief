# WristBrief Phone UI — Liquid Glass Direction

This folder is the design and implementation handoff for the **phone-side** WristBrief redesign.

## Scope

- Redesign **Android phone OOBE and phone app surfaces** only.
- **Wear OS UI is explicitly out of scope and must remain unchanged.**
- Preserve existing product capabilities and navigation semantics unless a product change is separately approved.
- The visual direction is a restrained, Apple-inspired **liquid glass** treatment adapted to WristBrief and Android/Compose.

## Non-negotiable visual rule

> **No gradients.**

Do not use linear, radial, sweep, mesh, aurora, glow, rainbow, iridescent, or multi-stop color gradients anywhere in the phone UI. Liquid-glass depth must come from **transparency, blur, borders, shadows, tonal layering, and motion**, not gradient color fields.

Flat semantic color accents are allowed in small areas (for example RSS orange, podcast purple, Google logo colors, or the WristBrief brand mark), but large decorative color fields are not.

## Files

- [`PHONE_LIQUID_GLASS_SPEC.md`](./PHONE_LIQUID_GLASS_SPEC.md) — design philosophy, visual system, flows, motion, accessibility, and implementation rules.
- [`assets/phone-liquid-glass-concept.jpg`](./assets/phone-liquid-glass-concept.jpg) — selected concept direction.
- [`examples/LiquidGlassPhoneExample.kt`](./examples/LiquidGlassPhoneExample.kt) — Jetpack Compose example matching the current `mobile` module stack.

## Design intent in one sentence

**Quiet information architecture, translucent hierarchy, high legibility, almost monochrome surfaces, and zero decorative gradients.**

## Product behavior that must stay intact

The redesign should wrap existing functionality rather than replacing it. The phone app still needs to support RSS, podcasts, AI Brief, Ask AI, playback, source/library management, onboarding, settings, account/sync, and existing Wear synchronization behavior.

## Implementation priority

1. Establish phone-only design tokens.
2. Build reusable glass primitives.
3. Re-skin OOBE.
4. Re-skin Home / Explore / Ask AI / Library / playback surfaces.
5. Verify accessibility and performance.
6. Confirm Wear screens are byte-for-byte/functionally unaffected by the UI refactor unless a separate Wear task is opened.
