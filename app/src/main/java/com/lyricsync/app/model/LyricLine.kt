package com.lyricsync.app.model

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object SampleLyrics {
    val lines: List<LyricLine> = listOf(
        LyricLine(0L, "示例歌词 · Sample lyrics only"),
        LyricLine(4000L, "月光落在窗台上 · Moonlight on the sill"),
        LyricLine(8000L, "风把故事轻轻唱 · The wind sings softly"),
        LyricLine(12000L, "夜色慢慢流淌 · Night flows slowly by"),
        LyricLine(16000L, "Stars hum a quiet tune"),
        LyricLine(20000L, "我们把心愿点亮 · We light a little wish"),
        LyricLine(24000L, "时间停在副歌上 · Time rests on the chorus"),
        LyricLine(28000L, "Every word a fading spark"),
        LyricLine(32000L, "唱到天微亮 · Sing until the dawn"),
        LyricLine(36000L, "示例结束 · End of sample")
    )
}
