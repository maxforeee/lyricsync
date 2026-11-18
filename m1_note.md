# M1 — Live playback from system MediaSession (Telegram probe)

## What was added
- `media/LyricNotificationListener.kt` — empty `NotificationListenerService` subclass; exists only so the system grants MediaSession access. Declared in `AndroidManifest.xml` with `BIND_NOTIFICATION_LISTENER_SERVICE` and the notification-listener intent filter. No new permissions beyond the listener.
- `media/PlaybackInfo.kt` — data class: packageName, title, artist, durationMs, positionMs, playing, lastUpdateMs, positionAdvancing.
- `media/MediaSessionRepository.kt` — queries `MediaSessionManager.getActiveSessions(...)`, prefers `org.telegram.messenger`, else the first active controller. Registers a `MediaController.Callback` (metadata / playback-state / session-destroyed) and polls position every 500 ms while playing via a coroutine; exposes `StateFlow<PlaybackInfo?>` and `StateFlow<Boolean>` (listener granted). `positionAdvancing` is true when the reported position increased across two polls. Handles `SecurityException` when access is not granted.
- `LyricsViewModel` — now an `AndroidViewModel`; `LyricsUiState` gained `playback`, `listenerGranted`, `statusCardVisible` (default ON). `refresh()` keeps the 600 ms spin + Toast and now re-queries active sessions, then re-applies the current lyric index. The sample-lyrics ticker only advances while no live session is active (fallback). Sessions are also re-queried on screen resume (so returning from the settings screen picks up the grant). Repository callback/poll cleaned up in `onCleared()`.
- `LyricsScreen.kt` — compact status card under the top scrim (12 sp text, white 70 % alpha): package name, title - artist, position/duration (mm:ss), Playing/Paused, "Position: moving" / "Position: STUCK", last-update time (HH:mm:ss). With no session it shows "No playback detected - grant notification access" plus a Grant-access chip opening `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `DotMenu.kt` / `strings.xml` — new "Playback status" menu item toggles the card on/off; all new strings added (English).

## How to test (on a real device, CI-built APK)
1. Install the debug APK from GitHub Actions.
2. Open LyricSync — the card should say "No playback detected - grant notification access". Tap **Grant access**, enable LyricSync in Notification access, go back.
3. Play a song in Telegram. The card should show `org.telegram.messenger`, the track title/artist, mm:ss position/duration and Playing/Paused.
4. Watch the position line: **Position: moving** means Telegram reports an advancing position (the probe succeeded); **Position: STUCK** means it reports a frozen position. Pause/resume in Telegram and check the state flips.
5. The left refresh dot re-reads the session (600 ms spin + "Refreshed" toast). Dot menu → "Playback status" hides/shows the card.

## What is NOT done
- No lyric fetching / lyric source yet — sample lyrics remain (ticker pauses while a live session is active).
- No overlay/floating window, no network.
- Not built or tested locally (no Android SDK on this machine, per brief); compile correctness is by inspection — CI (Actions) is the verification. Whether Telegram actually reports a moving position can only be answered on-device.
