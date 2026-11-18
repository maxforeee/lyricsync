# M2.1 note — smooth follow, higher anchor, stronger fades, transparent status bar

Date: 2026-11-18. Verified by code inspection only — no gradle build/test run (no Android SDK on this machine, per brief).

## What changed

### MainActivity.kt
- Edge-to-edge: `setDecorFitsSystemWindows(false)` (kept), status bar and navigation bar colors set to fully transparent.
- Light status-bar/navigation-bar icons (`isAppearanceLightStatusBars = false`, `isAppearanceLightNavigationBars = false`) for the dark background.
- Window background set to `DeepIndigo` (same as theme) via `ColorDrawable`, so there is no colour split or startup flash.

### LyricsScreen.kt
1. **Follow-scroll (snap-back fix)** — `LyricsList` no longer scrolls on every `currentIndex` change. It auto-scrolls only when the current line is outside the visible range, or within 2 lines of the bottom visible edge; otherwise only the highlight updates. A remembered `lastAutoScrollTarget` prevents restarting `animateScrollToItem` while a scroll for the same target is already running. A manual drag (`DragInteraction.Start`) sets `followEnabled=false`, which also cancels any in-flight auto-scroll. Verified: `resumeFollow` is called only from the "Resume follow" chip — no code path re-enables follow automatically.
2. **Higher anchor** — list `contentPadding` changed from 50%/50% to **top 35% / bottom 65%** of the viewport; the scroll offset stays at half the current line's height, so the highlighted line's centre now rests at **~35% of the viewport height from the top** (above centre). Bottom padding lets the last line reach the same anchor.
3. **Stronger fades** — top/bottom scrims grew from 120dp to **160dp + the status-bar / navigation-bar inset**, so they cover the system-bar areas. They now fade from the *live animated background colour* (opaque) to transparent (same hue, alpha 0), instead of a flat `ScrimBase`, so they blend with the moving gradient with no colour seam. Scrims draw above the list.
4. **Arc (addendum)** — each `LyricRow` gets a per-line transform based on its line-distance from the current line (normalised over 6 lines): scale 1.0 → ~0.85, alpha 1.0 → ~0.25, and rotationX tilt up to ±12° (lines above the current tilt back, lines below tilt forward), giving a cylindrical arc. Transitions animate over 250ms as the current line advances. The current line stays largest, fully opaque and flat; the edge scrims and the arc fade together.
5. **Layout restructure** — the animated gradient, lyrics list and scrims are now full-bleed (extend behind the status bar); the status card, resume chip, dot buttons and dot menu sit in a `windowInsetsPadding(WindowInsets.systemBars)` layer so they stay clear of the system bars.

### Not changed
- `Theme.kt`, `Color.kt`, `strings.xml` — no changes needed (theme background already `DeepIndigo`; "Resume follow" string already exists).
- `LyricsViewModel.kt` — read only, to verify `resumeFollow` call sites.

## What is NOT done
- Dot menu items are still placeholders (toast) — wiring them is the **next chunk**, per brief. `.github/workflows/` untouched.
- No local build/test was run (banned by brief); correctness is by inspection. CI compiles the change.
