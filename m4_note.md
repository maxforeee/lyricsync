# M4 — QQ Music + Kugou lyric sources

## What was added
- `LyricSource` now has `QQ` and `KUGOU` (AUTO, LRCLIB, NETEASE kept).
- AUTO chain: lrclib → NetEase → QQ → Kugou. Any source that errors, returns
  empty, or fails to decrypt is logged and skipped; the chain continues.
  Explicit single-source selection still works; cache key still includes the
  source (unchanged).
- Settings: two new radio rows ("QQ Music only", "Kugou only"); Auto label and
  About text updated.

## QQ Music (`media/QqMusicSource.kt`)
- Search: `https://c.y.qq.com/soso/fcgi-bin/client_search_cp` (public,
  `format=json&new_json=1`, no login/cookies), reads `data.song.list[0].mid`.
- Lyrics: `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=…&format=json`
  with `Referer: https://y.qq.com/portal/player.html`; `lyric` field is Base64.
- QRC decode: Base64 → if already LRC, use as-is; otherwise DES-family CBC
  decrypt (zero IV, NoPadding) with the publicly documented QRC ASCII key
  `!@#)(*$%123ZXC!@#)(*$%123ZXC!@#)(*$%` — tried as DESede (24-byte key) then
  single DES (8-byte key), per public QRC decoder references; plaintext is
  zlib-inflated when it starts with the zlib magic byte. QRC-only markup
  (`<…>` and `(offset,dur)` word tags) is stripped; standard `[mm:ss.xx]`
  lines go through the existing `LrcParser`.

## Kugou (`media/KugouSource.kt`)
- Search: `http://mobilecdn.kugou.com/api/v3/search/song` (public mobile
  endpoint) → `data.info[0].hash` + duration.
- Lyric candidates: `http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=…&hash=…&timelength=…`
  → `candidates[0].id` + `accesskey`.
- Download: `http://lyrics.kugou.com/download?…&fmt=krc&charset=utf8` →
  Base64 `content`.
- KRC decode: skip the 4-byte header ("krc1"), XOR every remaining byte with
  the documented cycling 16-byte KRC key (`40 47 61 77 5E 32 74 47 51 36 31
  2D CE D2 6E 69`, i.e. "@Gaw^2tGQ61-" + CE D2 + "ni" — the public KRC format
  key), then zlib-inflate (`java.util.zip.Inflater`) to UTF-8. KRC
  `[startMs,dur]` line stamps and `<off,dur,0>` word tags are normalized to
  standard `[mm:ss.xx]` LRC, then parsed by the existing `LrcParser`.

## Crypto / dependencies
- Only `javax.crypto` (DES/DESede) and `java.util.zip` (Inflater); Base64 via
  `android.util.Base64`. No new dependencies, no accounts, no cookies.
- Keys/constants live only in the two new source files, with comments citing
  the public QRC/KRC formats.

## NOT done
- No runtime verification: no gradle build/test on this machine (per brief,
  no Android SDK); correctness is by inspection. On-device verification of
  the live endpoints and decryption is still pending.
- No QQ/Kugou login or credential support (by design).
- The QRC key/mode follows public decoder references; if a specific QRC
  variant fails to decrypt at runtime it is skipped silently and the chain
  falls through to Kugou.
