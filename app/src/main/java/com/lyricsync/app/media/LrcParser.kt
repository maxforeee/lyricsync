package com.lyricsync.app.media

import com.lyricsync.app.model.LyricLine

object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d+))?]""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            var cursor = 0
            val timestamps = mutableListOf<Long>()
            for (match in TIMESTAMP.findAll(line)) {
                // Tolerate whitespace between tags ("[00:01.00] [00:05.00] text"),
                // but stop at the first non-tag character so lyric text is kept intact.
                if (line.substring(cursor, match.range.first).isNotBlank()) break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val fractionMs = if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()
                timestamps += minutes * 60_000L + seconds * 1_000L + fractionMs
                cursor = match.range.last + 1
            }
            if (timestamps.isEmpty()) continue
            val text = line.substring(cursor).trim()
            for (timeMs in timestamps) {
                lines += LyricLine(timeMs, text)
            }
        }
        // Lines sharing a timestamp (original + translation is the normal LRC
        // convention) are all kept; list consumers key items by index, not timeMs.
        return lines.sortedBy { it.timeMs }
    }
}
