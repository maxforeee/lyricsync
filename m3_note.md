# M3 — Dot menu filled in (settings, search, offset, about, floating bar)

## What was added (written by Qwen Code, verified by Muse; Qwen's process ended before writing this note)
- `media/LyricsPrefs.kt` (new, via LyricsRepository edit): SharedPreferences store — status-card visibility, lyric source (AUTO/LRCLIB/NETEASE), lyric offset ms.
- `media/LyricsRepository.kt`: `LyricSource` enum (AUTO, LRCLIB, NETEASE); fetch honours the chosen source; cache key includes source.
- `ui/settings/SettingsScreen.kt` (new, 186 lines): back button, status-card Switch, lyric-source radio group (Auto / lrclib only / NetEase only), link to About.
- `ui/lyrics/LyricsScreen.kt`: menu items now open real UI — Settings screen, SearchLyricsDialog (title+artist fields, fetches via repository), OffsetDialog (-/+ 100 ms steps, -2000..+2000 ms, shows current value), AboutDialog (app name, BuildConfig versionName, GitHub link, lrclib+NetEase credit). Floating bar item checks Settings.canDrawOverlays, else opens the overlay-permission settings.
- `floating/FloatingLyricService.kt` (new, 288 lines): foreground service + draggable horizontal overlay bar (current lyric line + translation line when available), follows live position via shared LyricSyncHub repositories, close button. Declared in AndroidManifest with SYSTEM_ALERT_WINDOW permission.
- `LyricsViewModel`: offset applied to highlight (positionMs + offsetMs), source/status-card/offset persisted and loaded from LyricsPrefs.
- `app/build.gradle.kts`: buildFeatures.buildConfig = true (About shows versionName).

## NOT done
- No local build/test (no SDK; per brief). CI is the first compile check; on-device verification of the floating bar and dialogs still pending.
