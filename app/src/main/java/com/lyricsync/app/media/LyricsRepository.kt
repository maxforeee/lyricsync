package com.lyricsync.app.media

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lyricsync.app.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

sealed class LyricsResult {
    data class Found(
        val lines: List<LyricLine>,
        val translations: Map<Long, String> = emptyMap()
    ) : LyricsResult()
    object NotFound : LyricsResult()
    object Error : LyricsResult()
}

enum class LyricSource { AUTO, LRCLIB, NETEASE, QQ, KUGOU }

object LyricsPrefs {
    const val OFFSET_MIN_MS = -2000
    const val OFFSET_MAX_MS = 2000
    const val OFFSET_STEP_MS = 100
    const val FLOATING_BAR_MIN_DP = 160
    const val FLOATING_BAR_MAX_DP = 360
    const val FLOATING_BAR_DEFAULT_DP = 240

    private const val PREFS_NAME = "lyricsync_prefs"
    private const val KEY_STATUS_CARD = "status_card_visible"
    private const val KEY_SOURCE = "lyric_source"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_FLOATING_BAR_WIDTH = "floating_bar_width_dp"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Access before init() falls back to defaults instead of throwing
    // UninitializedPropertyAccessException.
    var statusCardVisible: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_STATUS_CARD, true) else true
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_STATUS_CARD, value).apply()
        }

    var lyricSource: LyricSource
        get() {
            if (!::prefs.isInitialized) return LyricSource.AUTO
            val name = prefs.getString(KEY_SOURCE, null) ?: return LyricSource.AUTO
            return runCatching { LyricSource.valueOf(name) }.getOrDefault(LyricSource.AUTO)
        }
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_SOURCE, value.name).apply()
        }

    var offsetMs: Int
        get() = if (::prefs.isInitialized) {
            prefs.getInt(KEY_OFFSET, 0).coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)
        } else {
            0
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putInt(KEY_OFFSET, value.coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)).apply()
            }
        }

    var floatingBarWidthDp: Int
        get() = if (::prefs.isInitialized) {
            prefs.getInt(KEY_FLOATING_BAR_WIDTH, FLOATING_BAR_DEFAULT_DP)
                .coerceIn(FLOATING_BAR_MIN_DP, FLOATING_BAR_MAX_DP)
        } else {
            FLOATING_BAR_DEFAULT_DP
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putInt(
                    KEY_FLOATING_BAR_WIDTH,
                    value.coerceIn(FLOATING_BAR_MIN_DP, FLOATING_BAR_MAX_DP)
                ).apply()
            }
        }
}

class LyricsRepository {

    // Access-ordered LRU so repeated lookups of a bounded song set never grow
    // the cache without limit. All access is synchronized on the map.
    private val cache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun fetchLyrics(title: String, artist: String): LyricsResult {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val source = LyricsPrefs.lyricSource
        val key = "$cleanTitle|$cleanArtist|$source"
        synchronized(cache) { cache[key] }?.let { return it }
        val result = withContext(Dispatchers.IO) { fetchUncached(cleanTitle, cleanArtist, source) }
        if (result !is LyricsResult.Error) {
            synchronized(cache) { cache[key] = result }
        }
        return result
    }

    private suspend fun fetchUncached(title: String, artist: String, source: LyricSource): LyricsResult {
        if (source == LyricSource.AUTO) return fetchAutoCrossChecked(title, artist)
        val chain: List<Pair<String, () -> LyricsResult>> = when (source) {
            LyricSource.LRCLIB -> listOf("lrclib" to { fetchLrclib(title, artist) })
            LyricSource.NETEASE -> listOf("NetEase" to { fetchNetEase(title, artist) })
            LyricSource.QQ -> listOf("QQ" to { QqMusicSource.fetch(title, artist) })
            LyricSource.KUGOU -> listOf("Kugou" to { KugouSource.fetch(title, artist) })
            LyricSource.AUTO -> emptyList()
        }
        return runChain(chain)
    }

    private fun runChain(chain: List<Pair<String, () -> LyricsResult>>): LyricsResult {
        var anyError = false
        for ((name, fetch) in chain) {
            val result = try {
                fetch()
            } catch (e: Exception) {
                Log.w(TAG, "Source $name threw, skipping", e)
                LyricsResult.Error
            }
            if (result is LyricsResult.Found) return result
            if (result is LyricsResult.Error) anyError = true
            Log.d(TAG, "Source $name returned $result, continuing")
        }
        return if (anyError) LyricsResult.Error else LyricsResult.NotFound
    }

    // AUTO: fetch the three most reliable sources in parallel (lrclib preferred)
    // and merge them by median timestamp so one source with wrong timestamps
    // cannot drag the highlighted line off the singing.
    private suspend fun fetchAutoCrossChecked(title: String, artist: String): LyricsResult = coroutineScope {
        val primaries = listOf(
            "lrclib" to { fetchLrclib(title, artist) },
            "NetEase" to { fetchNetEase(title, artist) },
            "QQ" to { QqMusicSource.fetch(title, artist) }
        )
        val outcomes = primaries.map { (name, fetch) ->
            async {
                val result = try {
                    fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Source $name threw, skipping", e)
                    LyricsResult.Error
                }
                name to result
            }
        }.awaitAll()
        val found = outcomes.mapNotNull { (_, result) -> result as? LyricsResult.Found }
        if (found.isEmpty()) {
            // All three primaries failed: keep the legacy AUTO behaviour by
            // trying the remaining chain source, preserving Error-vs-NotFound.
            val kugou = try {
                KugouSource.fetch(title, artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Source Kugou threw, skipping", e)
                LyricsResult.Error
            }
            if (kugou is LyricsResult.Found) return@coroutineScope kugou
            val anyError = kugou is LyricsResult.Error ||
                outcomes.any { (_, result) -> result is LyricsResult.Error }
            return@coroutineScope if (anyError) LyricsResult.Error else LyricsResult.NotFound
        }
        if (found.size == 1) return@coroutineScope found.first()
        try {
            mergeByMedianTimestamp(found)
        } catch (e: Exception) {
            Log.w(TAG, "Lyric merge failed, using first available source", e)
            found.first()
        }
    }

    /**
     * Align the sources' lines by normalised text (in order, so repeated chorus
     * lines match their own occurrence), then take the median timestamp per
     * matched line. Lines only the base source has keep their own timestamp;
     * extra lines in other sources are ignored, never dropped from the base.
     * The first source (lrclib when present) supplies the line text and order.
     */
    private fun mergeByMedianTimestamp(found: List<LyricsResult.Found>): LyricsResult.Found {
        val base = found.first()
        val baseKeys = base.lines.map { normalizeLyricText(it.text) }
        val timestamps = base.lines.map { mutableListOf(it.timeMs) }
        // Per other source: base-line index for each of its lines, -1 if unmatched.
        val alignments = mutableListOf<Pair<LyricsResult.Found, IntArray>>()
        for (other in found.drop(1)) {
            val otherKeys = other.lines.map { normalizeLyricText(it.text) }
            val alignment = IntArray(other.lines.size) { -1 }
            var cursor = 0
            for (i in base.lines.indices) {
                val key = baseKeys[i]
                if (key.isEmpty()) continue
                var j = cursor
                while (j < otherKeys.size && otherKeys[j] != key) j++
                if (j < otherKeys.size) {
                    timestamps[i] += other.lines[j].timeMs
                    alignment[j] = i
                    cursor = j + 1
                }
            }
            alignments += other to alignment
        }
        var previous = Long.MIN_VALUE
        val mergedLines = base.lines.mapIndexed { i, line ->
            val sorted = timestamps[i].sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
            // Consumers scan lines in order and stop at the first future line,
            // so merged timestamps must stay non-decreasing even when an
            // unmatched base line kept a wrong timestamp.
            val time = maxOf(median, previous)
            previous = time
            LyricLine(time, line.text)
        }
        val translations = HashMap<Long, String>()
        val owner = found.firstOrNull { it.translations.isNotEmpty() }
        if (owner != null) {
            if (owner === base) {
                base.lines.forEachIndexed { i, line ->
                    owner.translations[line.timeMs]?.let { translations[mergedLines[i].timeMs] = it }
                }
            } else {
                val alignment = alignments.first { it.first === owner }.second
                owner.lines.forEachIndexed { j, line ->
                    val i = alignment[j]
                    if (i >= 0) {
                        owner.translations[line.timeMs]?.let { translations[mergedLines[i].timeMs] = it }
                    }
                }
            }
        }
        return LyricsResult.Found(mergedLines, translations)
    }

    private fun normalizeLyricText(text: String): String =
        text.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(NORMALIZE_WHITESPACE, " ")
            .trim()

    private fun fetchLrclib(title: String, artist: String): LyricsResult {
        return try {
            val artistParam = URLEncoder.encode(artist, "UTF-8")
            val titleParam = URLEncoder.encode(title, "UTF-8")
            val body = httpGet(
                "https://lrclib.net/api/get?artist_name=$artistParam&track_name=$titleParam",
                emptyMap()
            )
            if (body != null) {
                val json = JSONObject(body)
                val synced = if (json.isNull("syncedLyrics")) null else json.optString("syncedLyrics")
                if (!synced.isNullOrBlank()) {
                    val lines = LrcParser.parse(synced)
                    if (lines.isNotEmpty()) return LyricsResult.Found(lines)
                }
            }
            LyricsResult.NotFound
        } catch (e: IOException) {
            LyricsResult.Error
        } catch (e: JSONException) {
            // Malformed response = source malfunction, not "song has no lyrics";
            // Error keeps it out of the cache and lets AUTO report accurately.
            LyricsResult.Error
        }
    }

    private fun fetchNetEase(title: String, artist: String): LyricsResult {
        return try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchBody = httpGet(
                "https://music.163.com/api/search/get?s=$query&type=1&limit=1",
                NETEASE_HEADERS
            ) ?: return LyricsResult.NotFound
            val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs")
            val songId = songs?.optJSONObject(0)?.optLong("id", -1L) ?: -1L
            if (songId <= 0L) return LyricsResult.NotFound
            val lyricBody = httpGet(
                "https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=1",
                NETEASE_HEADERS
            ) ?: return LyricsResult.NotFound
            val json = JSONObject(lyricBody)
            val lrcJson = json.optJSONObject("lrc")
            val lrc = if (lrcJson == null || lrcJson.isNull("lyric")) null else lrcJson.optString("lyric")
            if (lrc.isNullOrBlank()) return LyricsResult.NotFound
            val lines = LrcParser.parse(lrc)
            if (lines.isEmpty()) return LyricsResult.NotFound
            val tlyricJson = json.optJSONObject("tlyric")
            val tlyric = if (tlyricJson == null || tlyricJson.isNull("lyric")) null else tlyricJson.optString("lyric")
            val translations = if (tlyric.isNullOrBlank() || tlyric.trim() == "无") {
                emptyMap()
            } else {
                LrcParser.parse(tlyric).associate { it.timeMs to it.text }
            }
            LyricsResult.Found(lines, translations)
        } catch (e: IOException) {
            LyricsResult.Error
        } catch (e: JSONException) {
            LyricsResult.Error
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_CACHE_ENTRIES = 32
        private val NORMALIZE_WHITESPACE = Regex("\\s+")
        private val NETEASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://music.163.com"
        )
    }
}
