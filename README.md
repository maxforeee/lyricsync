# LyricSync — UI-1 chunk

One screen only: fullscreen centred lyrics with top/bottom fade, and a small
round gradient dot button at the bottom-right that opens a settings pill menu
popping up from the dot.

- Native Kotlin, Jetpack Compose + Material 3, single Activity + MVVM (UDF).
- minSdk 26, targetSdk 34, compileSdk 34, Gradle 8.5 (Kotlin DSL).
- Dark theme only. Background is a slowly animated deep indigo → teal → violet
  gradient (not flat green). Current lyric line is large with a cyan→violet
  gradient highlight; neighbours fade in size and alpha.
- Sample data only: 10 hardcoded Chinese+English placeholder lines with
  timestamps, advanced by a simulated ticker in `LyricsViewModel`.
- Manual scroll pauses follow; a "Resume follow" chip restores it.
- Dot menu items (Settings, Search lyrics, Floating bar, Offset, About) show a
  placeholder Toast. Tapping outside the pill closes it.

## Open in Android Studio

Open this folder in Android Studio (Hedgehog or newer). Studio supplies the
Gradle wrapper jar/scripts (only `gradle-wrapper.properties` is committed in
this chunk); alternatively run `gradle wrapper --gradle-version 8.5` once with
a local Gradle 8.5 install. Requires JDK 17 and Android SDK 34 installed by
Studio. Then run the `app` configuration on a device/emulator.

## NOT done yet

- No Android SDK install, no build/test run in this chunk (paper project).
- No MediaSession / real playback integration.
- No lyric fetching or network of any kind.
- No overlay/floating-bar service.
- No real settings logic — menu items are Toast placeholders.
