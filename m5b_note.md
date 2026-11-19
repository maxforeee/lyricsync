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

## Part 2 (M5 part 3): centre anchor, no-overshoot scroll
(pending)

## Part 3 (M5 part 4): ocr high/medium fixes
(pending)
