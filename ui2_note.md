# UI-2 note — Left refresh dot + GitHub Actions APK build

Date: 2026-11-18. Chunk brief: /workspace/lyricsync_tasks/ui2_refresh_and_actions.md.
Note: Qwen wrote all the code edits (20:54-20:58 CST) but its process ended before writing this note and before committing; Muse verified the edits and did the commit + push.

## What changed
- Left-bottom refresh dot: RefreshDotButton in DotMenu.kt, 56dp, same cyan->violet gradient as the right dot, white Refresh icon 24dp, BottomStart with 24dp padding (mirrors the right dot). Tapping spins the icon 360 deg over 600ms, then Toast "Refreshed".
- LyricsViewModel.refresh(): manual only, no timer; re-reads the sample track and re-applies the current lyric index; TODO comment marks where the real MediaSession re-read goes in the M0/media chunk.
- Right dot and its upward popup menu unchanged.
- .github/workflows/android.yml: push/PR to main -> ubuntu-latest, JDK 17 Temurin, Android SDK, Gradle 8.5 (installed on the runner; repo has no gradlew), `gradle :app:assembleDebug`, artifact lyricsync-debug-apk.

## NOT done
- Nothing compiled locally (no Android SDK on the Box). The APK build is verified by GitHub Actions only.
- No real MediaSession, lyric fetching, or settings logic yet.
