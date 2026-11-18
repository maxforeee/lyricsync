package com.lyricsync.app.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsync.app.model.LyricLine
import com.lyricsync.app.model.SampleLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LyricsUiState(
    val lines: List<LyricLine> = SampleLyrics.lines,
    val currentIndex: Int = 0,
    val followEnabled: Boolean = true,
    val menuOpen: Boolean = false,
    val refreshing: Boolean = false
)

class LyricsViewModel : ViewModel() {

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                val snapshot = _state.value
                val nextIndex = (snapshot.currentIndex + 1) % snapshot.lines.size
                val stepMs = if (nextIndex == 0) {
                    4000L
                } else {
                    snapshot.lines[nextIndex].timeMs - snapshot.lines[snapshot.currentIndex].timeMs
                }
                delay(stepMs)
                _state.update { current ->
                    current.copy(currentIndex = (current.currentIndex + 1) % current.lines.size)
                }
            }
        }
    }

    fun onUserScroll() {
        _state.update { it.copy(followEnabled = false) }
    }

    fun resumeFollow() {
        _state.update { it.copy(followEnabled = true) }
    }

    fun toggleMenu() {
        _state.update { it.copy(menuOpen = !it.menuOpen) }
    }

    fun closeMenu() {
        _state.update { it.copy(menuOpen = false) }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            delay(600L)
            // TODO(M0/media chunk): re-read the real MediaSession playback state
            // (title, artist, position, play/pause) and re-fetch lyrics here.
            // Until then, re-read the sample track and re-apply the current index.
            _state.update { current ->
                val lines = SampleLyrics.lines
                current.copy(
                    lines = lines,
                    currentIndex = current.currentIndex.coerceIn(0, lines.size - 1),
                    refreshing = false
                )
            }
        }
    }
}
