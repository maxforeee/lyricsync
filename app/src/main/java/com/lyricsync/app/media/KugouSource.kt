package com.lyricsync.app.media

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Kugou lyric source (no login, no cookies).
 *
 * Search uses the public mobile search endpoint to get the song hash, then
 * lyrics.kugou.com/search + /download deliver the KRC file as Base64. Per the
 * public KRC format references, KRC is a 4-byte header ("krc1") followed by
 * bytes XOR-ed with the cycling 16-byte key below, then zlib-compressed UTF-8
 * lyric text. Only java.util.zip is used for the inflate step.
 */
object KugouSource {

    private const val TAG = "KugouSource"

    // Public KRC XOR key ("@Gaw^2tGQ61-" + 0xCE 0xD2 + "ni"), per documented
    // KRC decoders; cycled over every byte after the 4-byte header.
    private val KRC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )

    private val KRC_MAGIC = "krc1".toByteArray(Charsets.US_ASCII)

    private val KRC_WORD_TAG = Regex("""<\d+,\d+,\d+>""")
    private val KRC_LINE_STAMP = Regex("""^\[(\d+),\d+]""")

    // Zip-bomb guard: lyric payloads are a few KB at most.
    private const val MAX_INFLATED_BYTES = 2 * 1024 * 1024

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    fun fetch(title: String, artist: String): LyricsResult {
        return try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchBody = httpGet(
                // mobilecdn.kugou.com serves a mismatched TLS cert; mobiles.kugou.com
                // is the HTTPS-valid mirror with the same response shape.
                "https://mobiles.kugou.com/api/v3/search/song?format=json&keyword=$query&page=1&pagesize=1&showtype=1",
                HEADERS
            ) ?: return LyricsResult.NotFound
            val info = JSONObject(searchBody)
                .optJSONObject("data")?.optJSONArray("info")?.optJSONObject(0)
                ?: return LyricsResult.NotFound
            val hash = info.optString("hash")
            if (hash.isBlank()) return LyricsResult.NotFound
            val durationMs = info.optLong("duration", 0L) * 1000L

            val keyword = URLEncoder.encode(title, "UTF-8")
            val candidatesBody = httpGet(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword&hash=$hash&timelength=$durationMs",
                HEADERS
            ) ?: return LyricsResult.NotFound
            val candidate = JSONObject(candidatesBody).optJSONArray("candidates")?.optJSONObject(0)
                ?: return LyricsResult.NotFound
            val id = candidate.optString("id")
            val accessKey = candidate.optString("accesskey")
            if (id.isBlank() || accessKey.isBlank()) return LyricsResult.NotFound

            val downloadBody = httpGet(
                "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=krc&charset=utf8",
                HEADERS
            ) ?: return LyricsResult.NotFound
            val downloadJson = JSONObject(downloadBody)
            val content = downloadJson.optString("content")
            if (content.isBlank()) return LyricsResult.NotFound

            val krc = Base64.decode(content, Base64.DEFAULT)
            val lrc = decodeKrc(krc) ?: return LyricsResult.Error
            val lines = LrcParser.parse(normalizeKrc(lrc))
            if (lines.isEmpty()) return LyricsResult.NotFound
            LyricsResult.Found(lines)
        } catch (e: IOException) {
            Log.w(TAG, "Kugou request failed", e)
            LyricsResult.Error
        } catch (e: Exception) {
            // Decode/parse failure is a source malfunction, not "no lyrics exist";
            // Error keeps it out of the cache and lets AUTO report accurately.
            Log.w(TAG, "Kugou decode failed", e)
            LyricsResult.Error
        }
    }

    /** Strip the "krc1" header, XOR with the cycling key, then zlib-inflate. */
    private fun decodeKrc(data: ByteArray): String? {
        if (data.size <= 4 || !data.copyOfRange(0, 4).contentEquals(KRC_MAGIC)) {
            Log.w(TAG, "KRC magic mismatch, not a krc1 payload")
            return null
        }
        val decrypted = ByteArray(data.size - 4)
        for (i in decrypted.indices) {
            decrypted[i] = (data[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
        }
        val inflater = Inflater()
        return try {
            inflater.setInput(decrypted)
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                // Zero count means needsInput/needsDictionary or a stalled
                // stream; stop instead of spinning forever.
                if (count == 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_INFLATED_BYTES) {
                    Log.w(TAG, "KRC inflated output exceeds cap, aborting")
                    return null
                }
            }
            String(output.toByteArray(), Charsets.UTF_8)
        } catch (e: DataFormatException) {
            Log.w(TAG, "KRC inflate failed", e)
            null
        } finally {
            inflater.end()
        }
    }

    /**
     * KRC lines may carry [startMs,durationMs] timestamps and <offset,dur,0>
     * word tags; rewrite them to standard [mm:ss.xx] LRC for LrcParser.
     */
    private fun normalizeKrc(text: String): String {
        return text.lineSequence().joinToString("\n") { rawLine ->
            val line = KRC_WORD_TAG.replace(rawLine, "")
            val match = KRC_LINE_STAMP.find(line) ?: return@joinToString line
            val ms = match.groupValues[1].toLongOrNull() ?: return@joinToString line
            val minutes = ms / 60_000L
            val seconds = (ms % 60_000L) / 1_000L
            val centis = (ms % 1_000L) / 10L
            "[%02d:%02d.%02d]".format(minutes, seconds, centis) + line.substring(match.range.last + 1)
        }
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
