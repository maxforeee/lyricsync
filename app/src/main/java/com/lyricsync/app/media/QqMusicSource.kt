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

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://y.qq.com/"
    )

    fun fetch(title: String, artist: String): LyricsResult {
        return try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchBody = httpGet(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298&new_json=1" +
                    "&remoteplace=txt.yqq.song&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0" +
                    "&p=1&n=1&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
                    "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&w=$query",
                HEADERS
            ) ?: return LyricsResult.NotFound
            val song = JSONObject(searchBody)
                .optJSONObject("data")?.optJSONArray("song")?.optJSONObject(0)
                ?: return LyricsResult.NotFound
            val mid = song.optString("mid").ifBlank { song.optString("songmid") }
            if (mid.isBlank()) return LyricsResult.NotFound

            val lyricBody = httpGet(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json",
                HEADERS + ("Referer" to "https://y.qq.com/portal/player.html")
            ) ?: return LyricsResult.NotFound
            val lyricJson = JSONObject(lyricBody)
            val encoded = if (lyricJson.isNull("lyric")) null else lyricJson.optString("lyric")
            if (encoded.isNullOrBlank()) return LyricsResult.NotFound

            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            val lrc = decodeQrc(decoded) ?: return LyricsResult.NotFound
            val lines = LrcParser.parse(stripQrcTags(lrc))
            if (lines.isEmpty()) return LyricsResult.NotFound
            LyricsResult.Found(lines)
        } catch (e: IOException) {
            Log.w(TAG, "QQ Music request failed", e)
            LyricsResult.Error
        } catch (e: Exception) {
            Log.w(TAG, "QQ Music decode failed", e)
            LyricsResult.NotFound
        }
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
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                output.write(buffer, 0, count)
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
        return Regex("""\[\d{1,3}:\d{1,2}""").containsMatchIn(text)
    }

    /** Drop QRC-only word-timing / markup, keeping standard [mm:ss.xx] lines. */
    private fun stripQrcTags(lrc: String): String {
        return lrc
            .replace(Regex("""<[^>]*>"""), "")
            .replace(Regex("""\(\d+,\d+\)"""), "")
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 8_000
}
