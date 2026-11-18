# UI-1 note — LyricSync fullscreen lyrics + dot menu

Date: 2026-11-18. Chunk brief: `/workspace/lyricsync_tasks/ui1_dot.md`.

## Files created (all 16 listed in the brief)

- `settings.gradle.kts`, `build.gradle.kts` (root), `gradle.properties`,
  `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.5)
- `app/build.gradle.kts` (AGP 8.2.2, Kotlin 1.9.22, Compose compiler 1.5.8,
  Compose BOM 2024.02.00, Material 3, minSdk 26 / target+compileSdk 34)
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/lyricsync/app/MainActivity.kt`
- `app/src/main/java/com/lyricsync/app/model/LyricLine.kt` (10 sample
  Chinese+English lines with timestamps, clearly marked as sample)
- `app/src/main/java/com/lyricsync/app/ui/theme/Color.kt`, `Theme.kt` (dark only)
- `app/src/main/java/com/lyricsync/app/ui/lyrics/LyricsViewModel.kt`
  (StateFlow UDF: lines, currentIndex, followEnabled, menuOpen; simulated
  ticker advances the current line from the timestamps)
- `app/src/main/java/com/lyricsync/app/ui/lyrics/LyricsScreen.kt`
- `app/src/main/java/com/lyricsync/app/ui/lyrics/DotMenu.kt`
- `app/src/main/res/values/strings.xml`, `themes.xml`
- `README.md`

## Dot position / size

Round dot button, 56dp diameter, 24dp margin from right edge and 24dp from
bottom edge (inside system-bar insets). Cyan→violet gradient circle, soft
12dp shadow, white three-dot icon. The Canva label "yuan-dian" is NOT shipped
anywhere in the UI.

## Popup behaviour

Tapping the dot opens a vertical rounded pill whose bottom edge is anchored
just above the dot (dot stays fixed at bottom-right); it slides + fades UP
from the dot (220ms in, 180ms out). Pill: dark translucent surface (~80%
opacity, `0xCC12162B`), thin light border (`0x33FFFFFF`), fully rounded
(28dp radius), min 56dp wide / min 220dp tall. It grows wider (up to 160dp)
and taller as needed so the five white text rows — Settings, Search lyrics,
Floating bar, Offset, About — wrap instead of clipping at 200% font scale.
Tapping an item shows a placeholder Toast and closes the menu; tapping
anywhere outside the pill closes it.

## Colour choice

Not flat green. Slowly animated dark gradient background: deep indigo →
teal → violet, low saturation, 30s linear cross-fade loop (never flashing).
Current lyric line: 28sp bold, white with cyan→violet gradient text brush;
adjacent lines 18sp alpha 0.6, further lines 16sp alpha 0.35. Top/bottom
120dp gradient scrims fade lyrics out. Manual scroll pauses follow and shows
a "Resume follow" chip.

## NOT done (per brief)

- No Android SDK installed, no gradle build/test run, nothing committed.
- No MediaSession, no lyric fetching/network, no overlay service.
- No real settings logic — menu items are Toast placeholders only.
- Wrapper jar/scripts not generated (only `gradle-wrapper.properties`);
  Android Studio or a local `gradle wrapper` call supplies them.
- Not compiled or run: correctness is by inspection only, as ordered.
