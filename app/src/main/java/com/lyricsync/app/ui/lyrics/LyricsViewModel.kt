package com.lyricsync.app.ui.lyrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsync.app.media.LyricsRepository
import com.lyricsync.app.media.LyricsResult
import com.lyricsync.app.media.MediaSessionRepository
import com.lyricsync.app.media.PlaybackInfo
import com.lyricsync.app.model.LyricLine
import com.lyricsync.app.model.SampleLyrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val statusCardVisible: Boolean = true,
    val lyricsLoading: Boolean = false,
    val lyricsUnavailable: Boolean = false,
    val liveLyrics: Boolean = false
)

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaSessionRepository(application, viewModelScope)
    private val lyricsRepository = LyricsRepository()

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private var lyricsJob: Job? = null
    private var lastLyricsKey: String? = null

    init {
        viewModelScope.launch {
            repository.playback.collect { info ->
                _state.update { it.copy(playback = info) }
                if (info == null) {
                    lyricsJob?.cancel()
                    lastLyricsKey = null
                    showSampleLyrics()
                } else {
                    if (info.title.isNotBlank()) {
                        fetchLyricsFor(info)
                    }
                    if (_state.value.liveLyrics) {
                        val index = indexForPosition(_state.value.lines, info.positionMs)
                        _state.update { it.copy(currentIndex = index) }
                    }
                }
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
                if (snapshot.lines.isEmpty()) {
                    delay(1000L)
                    continue
                }
                val safeIndex = snapshot.currentIndex.coerceIn(0, snapshot.lines.size - 1)
                val nextIndex = (safeIndex + 1) % snapshot.lines.size
                val stepMs = if (nextIndex == 0) {
                    4000L
                } else {
                    snapshot.lines[nextIndex].timeMs - snapshot.lines[safeIndex].timeMs
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
            lastLyricsKey = null
            repository.refresh()
            val info = repository.playback.value
            if (info != null && info.title.isNotBlank()) {
                fetchLyricsFor(info)
            } else {
                showSampleLyrics()
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun fetchLyricsFor(info: PlaybackInfo) {
        val key = info.title.trim() + "|" + info.artist.trim()
        if (key == lastLyricsKey) return
        lastLyricsKey = key
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    lines = emptyList(),
                    currentIndex = 0,
                    lyricsLoading = true,
                    lyricsUnavailable = false,
                    liveLyrics = false
                )
            }
            val result = lyricsRepository.fetchLyrics(info.title, info.artist)
            ensureActive()
            if (lastLyricsKey != key) return@launch
            when (result) {
                is LyricsResult.Found -> {
                    val position = repository.playback.value?.positionMs ?: 0L
                    _state.update {
                        it.copy(
                            lines = result.lines,
                            currentIndex = indexForPosition(result.lines, position),
                            lyricsLoading = false,
                            lyricsUnavailable = false,
                            liveLyrics = true
                        )
                    }
                }
                LyricsResult.NotFound, LyricsResult.Error -> {
                    _state.update {
                        it.copy(
                            lines = emptyList(),
                            currentIndex = 0,
                            lyricsLoading = false,
                            lyricsUnavailable = true,
                            liveLyrics = false
                        )
                    }
                }
            }
        }
    }

    private fun showSampleLyrics() {
        _state.update {
            it.copy(
                lines = SampleLyrics.lines,
                currentIndex = it.currentIndex.coerceIn(0, SampleLyrics.lines.size - 1),
                lyricsLoading = false,
                lyricsUnavailable = false,
                liveLyrics = false
            )
        }
    }

    private fun indexForPosition(lines: List<LyricLine>, positionMs: Long): Int {
        var index = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) index = i else break
        }
        return index
    }

    override fun onCleared() {
        repository.close()
    }
}
