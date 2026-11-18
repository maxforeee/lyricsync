package com.lyricsync.app.media

import com.lyricsync.app.model.LyricLine

object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            var cursor = 0
            val timestamps = mutableListOf<Long>()
            for (match in TIMESTAMP.findAll(line)) {
                if (match.range.first != cursor) break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) {
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    3 -> fraction.toLong()
                    else -> 0L
                }
                timestamps += minutes * 60_000L + seconds * 1_000L + fractionMs
                cursor = match.range.last + 1
            }
            if (timestamps.isEmpty()) continue
            val text = line.substring(cursor).trim()
            for (timeMs in timestamps) {
                lines += LyricLine(timeMs, text)
            }
        }
        // distinctBy keeps the first line at a duplicated timestamp (e.g. a
        // translation sharing the original's time) so LazyColumn keys stay unique.
        return lines.sortedBy { it.timeMs }.distinctBy { it.timeMs }
    }
}
