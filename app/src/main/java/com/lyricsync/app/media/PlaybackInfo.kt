package com.lyricsync.app.media

data class PlaybackInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val lastUpdateMs: Long,
    val positionAdvancing: Boolean
)
