package com.lyricsync.app.media

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lyricsync.app.model.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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

    private const val PREFS_NAME = "lyricsync_prefs"
    private const val KEY_STATUS_CARD = "status_card_visible"
    private const val KEY_SOURCE = "lyric_source"
    private const val KEY_OFFSET = "offset_ms"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var statusCardVisible: Boolean
        get() = prefs.getBoolean(KEY_STATUS_CARD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_STATUS_CARD, value).apply()
        }

    var lyricSource: LyricSource
        get() {
            val name = prefs.getString(KEY_SOURCE, null) ?: return LyricSource.AUTO
            return runCatching { LyricSource.valueOf(name) }.getOrDefault(LyricSource.AUTO)
        }
        set(value) {
            prefs.edit().putString(KEY_SOURCE, value.name).apply()
        }

    var offsetMs: Int
        get() = prefs.getInt(KEY_OFFSET, 0).coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)
        set(value) {
            prefs.edit().putInt(KEY_OFFSET, value.coerceIn(OFFSET_MIN_MS, OFFSET_MAX_MS)).apply()
        }
}

class LyricsRepository {

    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun fetchLyrics(title: String, artist: String): LyricsResult {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val source = LyricsPrefs.lyricSource
        val key = "$cleanTitle|$cleanArtist|$source"
        cache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) { fetchUncached(cleanTitle, cleanArtist, source) }
        if (result !is LyricsResult.Error) {
            cache[key] = result
        }
        return result
    }

    private fun fetchUncached(title: String, artist: String, source: LyricSource): LyricsResult {
        val chain: List<Pair<String, () -> LyricsResult>> = when (source) {
            LyricSource.LRCLIB -> listOf("lrclib" to { fetchLrclib(title, artist) })
            LyricSource.NETEASE -> listOf("NetEase" to { fetchNetEase(title, artist) })
            LyricSource.QQ -> listOf("QQ" to { QqMusicSource.fetch(title, artist) })
            LyricSource.KUGOU -> listOf("Kugou" to { KugouSource.fetch(title, artist) })
            LyricSource.AUTO -> listOf(
                "lrclib" to { fetchLrclib(title, artist) },
                "NetEase" to { fetchNetEase(title, artist) },
                "QQ" to { QqMusicSource.fetch(title, artist) },
                "Kugou" to { KugouSource.fetch(title, artist) }
            )
        }
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
            LyricsResult.NotFound
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
            val translations = if (tlyric.isNullOrBlank()) {
                emptyMap()
            } else {
                LrcParser.parse(tlyric).associate { it.timeMs to it.text }
            }
            LyricsResult.Found(lines, translations)
        } catch (e: IOException) {
            LyricsResult.Error
        } catch (e: JSONException) {
            LyricsResult.NotFound
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"
        private const val TIMEOUT_MS = 8_000
        private val NETEASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://music.163.com"
        )
    }
}
