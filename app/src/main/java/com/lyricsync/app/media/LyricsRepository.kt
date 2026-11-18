package com.lyricsync.app.media

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
    data class Found(val lines: List<LyricLine>) : LyricsResult()
    object NotFound : LyricsResult()
    object Error : LyricsResult()
}

class LyricsRepository {

    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun fetchLyrics(title: String, artist: String): LyricsResult {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val key = "$cleanTitle|$cleanArtist"
        cache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) { fetchUncached(cleanTitle, cleanArtist) }
        if (result !is LyricsResult.Error) {
            cache[key] = result
        }
        return result
    }

    private fun fetchUncached(title: String, artist: String): LyricsResult {
        var networkFailed = false

        try {
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
        } catch (e: IOException) {
            networkFailed = true
        } catch (e: JSONException) {
            // Malformed response: fall through to NetEase.
        }

        try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchBody = httpGet(
                "https://music.163.com/api/search/get?s=$query&type=1&limit=1",
                NETEASE_HEADERS
            )
            if (searchBody != null) {
                val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs")
                val songId = songs?.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                if (songId <= 0L) return LyricsResult.NotFound
                val lyricBody = httpGet(
                    "https://music.163.com/api/song/lyric?id=$songId&lv=1",
                    NETEASE_HEADERS
                )
                if (lyricBody != null) {
                    val lrcJson = JSONObject(lyricBody).optJSONObject("lrc")
                    val lrc = if (lrcJson == null || lrcJson.isNull("lyric")) null else lrcJson.optString("lyric")
                    if (!lrc.isNullOrBlank()) {
                        val lines = LrcParser.parse(lrc)
                        if (lines.isNotEmpty()) return LyricsResult.Found(lines)
                    }
                    return LyricsResult.NotFound
                }
            }
        } catch (e: IOException) {
            networkFailed = true
        } catch (e: JSONException) {
            // Malformed response: treated as no lyrics below.
        }

        return if (networkFailed) LyricsResult.Error else LyricsResult.NotFound
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
        private const val TIMEOUT_MS = 8_000
        private val NETEASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://music.163.com"
        )
    }
}
