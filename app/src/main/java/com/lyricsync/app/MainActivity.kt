package com.lyricsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.lyricsync.app.ui.lyrics.LyricsScreen
import com.lyricsync.app.ui.theme.LyricSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LyricSyncTheme {
                LyricsScreen()
            }
        }
    }
}
