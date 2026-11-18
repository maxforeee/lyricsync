package com.lyricsync.app.ui.lyrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsync.app.media.MediaSessionRepository
import com.lyricsync.app.media.PlaybackInfo
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
    val refreshing: Boolean = false,
    val playback: PlaybackInfo? = null,
    val listenerGranted: Boolean = false,
    val statusCardVisible: Boolean = true
)

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaSessionRepository(application, viewModelScope)

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.playback.collect { info ->
                _state.update { it.copy(playback = info) }
            }
        }
        viewModelScope.launch {
            repository.listenerGranted.collect { granted ->
                _state.update { it.copy(listenerGranted = granted) }
            }
        }
        repository.refresh()
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
                // Sample-lyrics ticker is only a fallback while no live session is active.
                if (_state.value.playback == null) {
                    _state.update { current ->
                        current.copy(currentIndex = (current.currentIndex + 1) % current.lines.size)
                    }
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

    fun toggleStatusCard() {
        _state.update { it.copy(statusCardVisible = !it.statusCardVisible) }
    }

    fun onScreenResume() {
        repository.refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            delay(600L)
            repository.refresh()
            // Sample lyrics stay until the lyric-source chunk; just re-apply the index.
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

    override fun onCleared() {
        repository.close()
    }
}
