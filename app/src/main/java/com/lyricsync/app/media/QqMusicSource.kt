package com.lyricsync.app.media

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ Music lyric source (no login, no cookies).
 *
 * Search uses the public client_search_cp endpoint; lyrics come from
 * fcg_query_lyric_new.fcg, whose "lyric" field is Base64. For QRC tracks the
 * decoded bytes are DES-encrypted. Public QRC decoders (e.g. jixunmoe/qrc-decoder
 * and the widely circulated QRC format write-ups) use the ASCII key below with
 * CBC mode and a zero IV; the recovered plaintext may additionally be
 * zlib-compressed. Only javax.crypto / java.util.zip are used.
 */
object QqMusicSource {

    private const val TAG = "QqMusicSource"

    // Public QRC decryption key, per documented QRC format references.
    private const val QRC_KEY = "!@#)(*$%123ZXC!@#)(*$%123ZXC!@#)(*$%"
    private val ZERO_IV = ByteArray(8)

    private val LRC_STAMP = Regex("""\[\d{1,3}:\d{1,2}""")
    private val QRC_WORD_TAG = Regex("""<[^>]*>""")
    private val QRC_WORD_STAMP = Regex("""\(\d+,\d+\)""")

    // Zip-bomb guard: lyric payloads are a few KB at most.
    private const val MAX_INFLATED_BYTES = 2 * 1024 * 1024

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://y.qq.com/"
    )

    fun fetch(title: String, artist: String): LyricsResult {
        return try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            // The legacy full param set (new_json=1 etc.) now returns totalnum=0;
            // minimal params return the old shape: data.song.list[] with songmid.
            val searchBody = httpGet(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$query&format=json&n=5&p=1",
                HEADERS
            ) ?: return LyricsResult.NotFound
            val list = JSONObject(searchBody)
                .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            if (list == null || list.length() == 0) return LyricsResult.NotFound
            val wantedTitle = title.trim()
            val wantedArtist = artist.trim()
            val candidates = (0 until list.length())
                .mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("songmid").ifBlank { it.optString("mid") }.isNotBlank() }
            if (candidates.isEmpty()) return LyricsResult.NotFound
            val song = candidates.firstOrNull {
                songTitle(it).equals(wantedTitle, ignoreCase = true) && singerMatches(it, wantedArtist)
            } ?: candidates.firstOrNull {
                songTitle(it).equals(wantedTitle, ignoreCase = true)
            } ?: candidates.first()
            val mid = song.optString("songmid").ifBlank { song.optString("mid") }

            val lyricBody = httpGet(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json",
                HEADERS + ("Referer" to "https://y.qq.com/portal/player.html")
            ) ?: return LyricsResult.NotFound
            val lyricJson = JSONObject(lyricBody)
            val encoded = lyricJson.optString("lyric")
            if (encoded.isBlank()) return LyricsResult.NotFound

            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            val lrc = decodeQrc(unwrapLyricPayload(decoded)) ?: return LyricsResult.Error
            val lines = LrcParser.parse(stripQrcTags(lrc))
            if (lines.isEmpty()) return LyricsResult.NotFound
            LyricsResult.Found(lines)
        } catch (e: IOException) {
            Log.w(TAG, "QQ Music request failed", e)
            LyricsResult.Error
        } catch (e: Exception) {
            // Decode/parse failure is a source malfunction, not "no lyrics exist";
            // Error keeps it out of the cache and lets AUTO report accurately.
            Log.w(TAG, "QQ Music decode failed", e)
            LyricsResult.Error
        }
    }

    /**
     * The endpoint sometimes base64-wraps a JSON metadata object instead of raw
     * lyric bytes; unwrap it and pull out the inner "lyric" field.
     */
    private fun unwrapLyricPayload(data: ByteArray): ByteArray {
        val text = String(data, Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) return data
        return try {
            val inner = JSONObject(text).optString("lyric")
            when {
                inner.isBlank() -> data
                looksLikeLrc(inner.toByteArray(Charsets.UTF_8)) -> inner.toByteArray(Charsets.UTF_8)
                else -> Base64.decode(inner, Base64.DEFAULT)
            }
        } catch (e: Exception) {
            data
        }
    }

    private fun songTitle(song: JSONObject): String =
        song.optString("songname").ifBlank { song.optString("name") }.trim()

    private fun singerMatches(song: JSONObject, artist: String): Boolean {
        if (artist.isBlank()) return true
        val singers = song.optJSONArray("singer") ?: return false
        for (i in 0 until singers.length()) {
            val name = singers.optJSONObject(i)?.optString("name")?.trim() ?: continue
            if (name.isBlank()) continue
            if (name.equals(artist, ignoreCase = true) ||
                name.contains(artist, ignoreCase = true) ||
                artist.contains(name, ignoreCase = true)
            ) return true
        }
        return false
    }

    /** Recover UTF-8 LRC text from Base64-decoded QRC bytes, or null on failure. */
    private fun decodeQrc(data: ByteArray): String? {
        if (looksLikeLrc(data)) return String(data, Charsets.UTF_8)
        val keyBytes = QRC_KEY.toByteArray(Charsets.US_ASCII)
        // Documented QRC decoders use Triple-DES; some variants use single DES.
        val candidates = listOf(
            "DESede" to keyBytes.copyOf(24),
            "DES" to keyBytes.copyOf(8)
        )
        for ((algorithm, key) in candidates) {
            try {
                val cipher = Cipher.getInstance("$algorithm/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, algorithm),
                    IvParameterSpec(ZERO_IV)
                )
                val plain = maybeInflate(cipher.doFinal(data))
                if (looksLikeLrc(plain)) return String(plain, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.d(TAG, "QRC candidate $algorithm failed: ${e.message}")
            }
        }
        return null
    }

    private fun maybeInflate(data: ByteArray): ByteArray {
        // zlib streams start with 0x78; otherwise the plaintext is already raw.
        if (data.isEmpty() || (data[0].toInt() and 0xFF) != 0x78) return data
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                // Zero count means needsInput/needsDictionary or a stalled
                // stream; stop instead of spinning forever.
                if (count == 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_INFLATED_BYTES) return data
            }
            output.toByteArray()
        } catch (e: DataFormatException) {
            data
        } finally {
            inflater.end()
        }
    }

    private fun looksLikeLrc(data: ByteArray): Boolean {
        val text = String(data, Charsets.UTF_8)
        return LRC_STAMP.containsMatchIn(text)
    }

    /** Drop QRC-only word-timing / markup, keeping standard [mm:ss.xx] lines. */
    private fun stripQrcTags(lrc: String): String {
        return lrc
            .replace(QRC_WORD_TAG, "")
            .replace(QRC_WORD_STAMP, "")
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private const val TIMEOUT_MS = 8_000
}
