# M2 — Fetch real synced lyrics (lrclib + NetEase)

## What was added
- `media/LrcParser.kt` (new): parses LRC text into `List<LyricLine>` (reuses the existing
  `com.lyricsync.app.model.LyricLine`). Handles multiple timestamps per line, ignores metadata
  tags (`[ti:]`, `[ar:]`, `[offset:]`, …), sorts by time, and dedupes identical timestamps
  (first line wins) so LazyColumn keys stay unique. No fake translation lines.
- `media/LyricsRepository.kt` (new): `suspend fun fetchLyrics(title, artist): LyricsResult`
  with sealed `Found(lines)` / `NotFound` / `Error`. In-memory `ConcurrentHashMap` cache keyed
  by trimmed title+artist (errors are not cached, so they can be retried). All network work on
  `Dispatchers.IO` via `java.net.HttpURLConnection` (connect/read timeout 8 s each, no new
  dependency; JSON via Android's built-in `org.json`).
  - Source 1: lrclib `GET /api/get?artist_name=…&track_name=…` (URL-encoded); uses `syncedLyrics`.
  - Source 2 (fallback when lrclib has no synced lyrics): NetEase search
    `GET /api/search/get?s=<title artist>&type=1&limit=1` → song id →
    `GET /api/song/lyric?id=<id>&lv=1`, reading `lrc.lyric`. Browser-like User-Agent and
    `Referer: https://music.163.com` sent on both NetEase calls.
- `LyricsViewModel`: on playback title/artist change (non-empty title, different key) it cancels
  any in-flight fetch and loads lyrics, showing a "Loading lyrics…" state meanwhile. On `Found`
  the sample lyrics are replaced and the current-line index is driven by the LIVE
  `PlaybackInfo.positionMs` (updated on every 500 ms poll), not the sample ticker. On
  `NotFound`/`Error` the list shows the track title/artist + "No lyrics found" and sample lyrics
  stay OFF. Sample lyrics + ticker remain only as the no-playback fallback. The refresh button
  re-fetches for the current track. Ticker guarded against empty line lists.
- `LyricsScreen`: same layout/status card; added centered `LyricsCenterMessage` (loading) and
  `UnavailableLyrics` (title/artist + "No lyrics found") states. Single lines only, no
  translation row. Dark animated-gradient palette unchanged.
- `AndroidManifest.xml`: added `android.permission.INTERNET`.
- `strings.xml`: added `loading_lyrics`, `no_lyrics_found` (English).

## Which source answered
Not verifiable in this chunk: no Android SDK on this machine and the brief bans local
gradle build/test, so no runtime request was made. Order implemented: lrclib first (synced
LRC), NetEase as fallback. Whichever answers first at runtime wins; the UI does not yet
label the source.

## NOT done
- No QQ Music / Kugou sources (no decryption) — explicitly out of scope for M2.
- No floating window / floating bar.
- No translation display, no lyric offset adjustment, no manual search UI.
- Not compiled or tested locally (no SDK; per brief). CI (`.github/workflows/`, untouched)
  is the first compile check; on-device verification of a real Telegram track still pending.
