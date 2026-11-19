# M5b — Remaining fix batch

## Part 1 (M5 part 2): QQ + Kugou lyric sources — diagnosed against LIVE endpoints

### QQ Music — two bugs, both fixed in `QqMusicSource.kt`
1. **Search params dead.** The old full param set (`new_json=1&catZhida=1&...`) now returns
   `{"code":0,"data":{"song":{"curnum":0,"list":[],"totalnum":0}}}` for every query (tested
   `周杰伦 晴天` and `晴天`). Minimal params work:
   `GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=<query>&format=json&n=5&p=1`
   → real shape (verified live):
   ```json
   {"code":0,"data":{"song":{"totalnum":600,"list":[
     {"songid":97773,"songmid":"0039MnYb0qxYhV","songname":"晴天",
      "singer":[{"name":"周杰伦"}],"interval":269, ...}, ...]}}}
   ```
2. **Wrong JSON shape.** Code read `data.song` as a JSONArray; it is an object with a `list`
   array, and the id field is `songmid` (not `mid`). Always parsed to null → NotFound.
   Now parses `data.song.list[]`, and among the 5 results prefers exact title+artist match,
   then exact title, then first (avoids grabbing "晴天 (Live)" covers).

Lyric endpoint unchanged and verified live:
`GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=0039MnYb0qxYhV&format=json`
→ `{"retcode":0,"code":0,"subcode":0,"lyric":"<base64>","trans":"<base64>"}`.
Base64-decoded `lyric` is **plain UTF-8 LRC** (`[ti:晴天]\n[ar:周杰伦]...[00:02.25]词：周杰伦`),
verified for both 晴天 (0039MnYb0qxYhV) and Adele – Hello (002Il7Ya1tZ6UZ). No DES needed for
these; the existing QRC DESede/DES fallback is kept for encrypted tracks.

### Kugou — cleartext HTTP blocked on device; fixed in `KugouSource.kt`
All three URLs were `http://`; the manifest has no `usesCleartextTraffic`, so Android 9+
blocks them (IOException → source dead on device). Verified live over HTTPS:
- `mobilecdn.kugou.com` HTTPS has a **mismatched TLS certificate** (curl error 60), as do
  `mobilecdnbj`/`msearchcdn`; `songsearch.kugou.com/song_search_v2` answers but `total:0`.
  Working mirror: **`https://mobiles.kugou.com/api/v3/search/song`** — same shape:
  `{"status":1,"errcode":0,"data":{"info":[{"hash":"b3a52a7a958bf0aed0ebfba2e9a818b7","duration":269,"songname":"晴天","singername":"周杰伦",...}]}}`
- `https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=晴天&hash=<hash>&timelength=269000`
  → `{"status":200,"candidates":[{"id":"274944371","accesskey":"2A7B35884B3C20E9D3281686BA59A3F8","duration":269792,"krctype":1,...}]}`
- `https://lyrics.kugou.com/download?ver=1&client=pc&id=274944371&accesskey=<key>&fmt=krc&charset=utf8`
  → `{"status":200,"fmt":"krc","content":"<base64>",...}`; decoded bytes start with magic
  `krc1`; skip 4 bytes, XOR with the 16-byte key, zlib-inflate → readable KRC
  (`[ti:晴天]...[11250,2250]<0,375,0>合...`), which `normalizeKrc` already rewrites to LRC.

All three Kugou URLs now HTTPS (search host swapped to `mobiles.kugou.com`). This also
resolves ocr security finding #18. No source dropped from AUTO; order unchanged.

Not done here: no on-device verification (no Android SDK on this machine); verification was
against the live endpoints with curl + Python decode of the exact bytes the app will parse.

## Part 2 (M5 part 3): centre anchor, no-overshoot scroll — `LyricsScreen.kt`

**Cause found (in code + the exact Compose 1.6.1 sources, BOM 2024.02.00):**
`animateScrollToItem` in this version (`lazy/layout/LazyAnimateScroll.kt`) computes its
scroll target from the layout *at animation start* and finishes with a hard
`snapToItem(index, scrollOffset)` "to account for rounding error". Meanwhile the
highlighted row's **layout height changes with currentIndex** (28sp/36sp current vs
18sp/24sp neighbour): when the index advances, the old current row shrinks by ~12sp, so
the real target ends up ~12sp nearer than the animation aimed for — the list lifts past
the resting point and the final snap jerks it back **down**. That is the reported
"上去之后会往下卡一下". (The built-in spring itself is clamped; the dip is the snap-back.)

**Fix:**
- Anchor: `contentPadding` top = 50% / bottom = 50% of viewport (was 35%/65%). One
  mechanism: padding defines the centre line; the constant `scrollOffset` (half the
  current row height) centres the row on it, as before.
- New `liftToAnchor()`: explicit `AnimationState` + `tween(300ms, FastOutSlowInEasing)`
  inside `listState.scroll {}`. Each frame it re-reads the target item's live
  `layoutInfo` offset and scrolls an eased fraction of the *remaining* distance
  (fraction ≤ 1), so the motion is monotonic — no overshoot, no snap-back dip — and it
  absorbs the row-height change mid-flight. Lands exactly on the anchor; far jumps
  (target not visible) snap instead of flying. `animateScrollToItem` is no longer used.
- Trigger: unchanged guard — `LaunchedEffect(currentIndex, followEnabled)` +
  `lastAutoScrollTarget` means only a genuine index change scrolls; same-index
  re-emissions from playback polling never restart it. `lastAutoScrollTarget` now resets
  when the `lines` list changes. Manual drag still pauses follow (drag cancels the
  scroll mutex); arc/fade look, menu, floating bar, transparent status bar untouched.

Verified against the real 1.6.1 sources that `AnimationState.animateTo`'s block is
non-suspend but `ScrollScope.scrollBy` inside `scroll {}` is also non-suspend, so the
pattern compiles on this exact version (same pattern foundation itself uses).
Not done: no on-device verification (no Android SDK here).

## Part 3 (M5 part 4): ocr high/medium fixes

### HIGH — all fixed
1. **LyricsPrefs lateinit** (`LyricsRepository.kt`): every getter/setter now guards with
   `::prefs.isInitialized`; pre-init reads return defaults (true / AUTO / 0), writes no-op.
2. **fetchLyrics unguarded** (`LyricsViewModel.kt`): new `fetchOrError()` wraps the fetch in
   try-catch, rethrows `CancellationException`, maps anything else to `Error`. Used by both
   `searchLyrics` and `fetchLyricsFor`.
3. **matchesPlaying vacuous**: both sides now use one normalisation —
   `trackKey(title, artist)` = trim + collapse whitespace + lowercase per part;
   `playbackKey(info)` delegates to it, and the manual-search key is built with the same
   function, so searching the playing track (any casing/spacing) matches and syncs live.

### MEDIUM (bug) — all fixed
4. **NotFound vs Error**: QQ/Kugou generic decode/parse catch and null-decode now return
   `Error`; lrclib/NetEase `JSONException` now returns `Error` (was NotFound, which got
   cached as "no lyrics exist"). AUTO already continues on both; Error is not cached and
   surfaces accurately. Genuine empty-result paths stay NotFound.
5. **QQ base64-JSON metadata**: new `unwrapLyricPayload()` detects decoded bytes starting
   with `{`, parses the JSON and extracts the inner `lyric` (plain LRC or base64).
6. **openConnection outside try**: all three `httpGet`s (repository, QQ, Kugou) create the
   connection inside try via `as? HttpURLConnection`, disconnect in finally.
7. **Unbounded cache**: replaced ConcurrentHashMap with a synchronized access-ordered
   `LinkedHashMap` LRU capped at 32 entries.
8. **Non-atomic key fields**: `manualOverride`/`manualPlayingKey`/`lastLyricsKey` moved into
   an immutable `FetchKeys` held in an `AtomicReference`; all read-modify-writes use
   `updateAndGet`/`getAndUpdate`/`set`.
9. **ensureActive leaves spinner on**: both fetch jobs wrap the body in try/finally with
   `resetLoadingIfCurrent()`, which clears `lyricsLoading` only if the finishing job is
   still the current `lyricsJob` (never clears a newer job's spinner).
10. **Found with empty lines**: `publishResult()` demotes empty-line Found to NotFound
    (unavailable UI) before publishing.
11. **refresh() fire-and-forget**: flag set synchronously before launch; body in
    try/catch (rethrow cancellation) with `refreshing = false` in finally.
12. **offsetMs divergence**: index math (playback collector, publishResult) reads
    `_state.value.offsetMs`; prefs only used for persistence/init.
13. **Ticker never stops**: sample ticker is now a `tickerJob` started by
    `showSampleLyrics()`/init and cancelled whenever a real fetch starts
    (`fetchLyricsFor`, `searchLyrics`) — no wakeups during live playback/pause.
14. **Deprecated bar colors** (`MainActivity.kt`): `statusBarColor`/`navigationBarColor`
    setters gated behind `Build.VERSION.SDK_INT < 35` (literal 35; compileSdk is 34).
    Transparent look preserved: pre-35 via setters, 35+ via enforced edge-to-edge.
15. **MediaSessionRepository**: registers `OnActiveSessionsChangedListener` so sessions
    starting later are re-discovered (grant-time re-discovery via the existing
    onScreenResume refresh); `attachBest` compares session tokens so refresh is idempotent
    (no detach/reattach churn). Poll loop now publishes paused/stopped states with
    `advancing = false` instead of `continue` (no more sticky-true); `publish()` default
    advancing requires `playing`; all positions clamped `>= 0` (never
    PLAYBACK_POSITION_UNKNOWN). Listener removed in `close()`.
16. **FloatingLyricService**: drag listener returns false for ACTION_DOWN landing on the
    close button (bounds check via `getLocationOnScreen`), so its click listener receives
    the tap; drags elsewhere unchanged. `START_STICKY` → `START_NOT_STICKY`.
17. **ON_RESUME refresh**: `onScreenResume()` throttled to once per 2s
    (`SystemClock.elapsedRealtime`); combined with idempotent `attachBest` and the
    key-guarded fetch, repeated resumes cannot pile up fetches/races.

### MEDIUM (security) — all fixed
18. **Kugou plain HTTP**: all endpoints HTTPS (done in Part 1; `mobiles.kugou.com` mirror
    because `mobilecdn`'s TLS cert is invalid). Verified live.
19. **Inflate zip-bomb / spin**: Kugou `decodeKrc` and QQ `maybeInflate` now break on any
    zero-count inflate (no-progress) and abort past a 2 MiB output cap.
    KRC `krc1` magic validated before stripping the header (task-listed with security).

### LOW — fixed (trivial)
- KRC magic check (also required by the task, see above).
- Regexes hoisted to private vals in QQ (`LRC_STAMP`, `QRC_WORD_TAG`, `QRC_WORD_STAMP`)
  and Kugou (`KRC_WORD_TAG`, `KRC_LINE_STAMP`).
- NetEase tlyric placeholder `"无"` filtered before merging translations.
- LazyColumn key now composite `timeMs to text` (duplicate timestamps from kept
  translation lines no longer crash).
- `formatTimeOfDay` shares one hoisted `SimpleDateFormat` (main-thread-only usage).
- QQ redundant `isNull` + `optString` simplified (same trivial pattern in Kugou content).

### LOW — skipped (not one-line / not applicable)
- **SampleLyrics release guard**: it is the production default/fallback state in
  LyricsViewModel; moving to a debug source set breaks release compilation and
  @VisibleForTesting would be inaccurate. Needs a product decision, not a one-liner.
- **MainActivity DeepIndigo duplication**: already references the single shared
  `DeepIndigo` constant from ui/theme (same constant the theme uses); no duplicated
  literal exists to remove.

### SKIP list from ocr-findings.md (style/docs, per task instruction)
- PlaybackInfo.kt KDoc — documentation only.
- LyricNotificationListener onListenerConnected suggestion — listed as skip in the
  findings; re-discovery is covered by the sessions-changed listener + resume refresh.
- LyricLine ordering contract — documentation only.
- LyricsScreen per-row animation allocations / background brush rebuild / palette-cycle
  jump / cameraDistance magic numbers / BackHandler click-through — stylistic/perf polish,
  explicitly out of scope for this batch (and the arc/fade look must not change).
- DotMenu sizing, SettingsScreen/Theme polish — styling, out of scope.
- LyricsRepository concurrent-dedup + duplicate IOException/JSONException boilerplate —
  refactor/dedup, not a correctness bug; chain semantics intentionally unchanged.
- QqMusic TIMEOUT_MS placement — stylistic.

### Already committed in M5 part 1 (not redone)
LrcParser fraction >3 digits, whitespace-tolerant tags, translation lines kept.

### Not done / verification limits
No gradle build/test and no on-device run (no Android SDK on this machine, per task).
Compile-sensitive spots were checked against the exact dependency sources
(foundation/animation-core 1.6.1 from Google Maven): `AnimationState.animateTo` block is
non-suspend but `ScrollScope.scrollBy` inside `scroll {}` is non-suspend too, and the
class+factory single-name import pattern matches existing `Animatable` usage in this file.
