package com.lyricsync.app.ui.lyrics

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsync.app.floating.LyricSyncHub
import com.lyricsync.app.media.LyricSource
import com.lyricsync.app.media.LyricsPrefs
import com.lyricsync.app.media.LyricsResult
import com.lyricsync.app.media.PlaybackInfo
import com.lyricsync.app.model.LyricLine
import com.lyricsync.app.model.SampleLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

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
    val liveLyrics: Boolean = false,
    val lyricSource: LyricSource = LyricSource.AUTO,
    val offsetMs: Int = 0,
    val floatingBarWidthDp: Int = LyricsPrefs.FLOATING_BAR_DEFAULT_DP
)

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LyricSyncHub.media(application)
    private val lyricsRepository = LyricSyncHub.lyrics

    private val _state = MutableStateFlow(
        LyricsUiState(
            statusCardVisible = LyricsPrefs.statusCardVisible,
            lyricSource = LyricsPrefs.lyricSource,
            offsetMs = LyricsPrefs.offsetMs,
            floatingBarWidthDp = LyricsPrefs.floatingBarWidthDp
        )
    )
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private var lyricsJob: Job? = null
    private var tickerJob: Job? = null
    private var nullPlaybackJob: Job? = null
    private var lastResumeRefreshMs = 0L

    // Read-modify-write of the fetch/manual-search keys must be atomic: several
    // coroutines (playback collector, search, refresh) touch them.
    private data class FetchKeys(
        val manualOverride: Boolean = false,
        val manualPlayingKey: String? = null,
        val lastLyricsKey: String? = null
    )

    private val fetchKeys = AtomicReference(FetchKeys())

    init {
        viewModelScope.launch {
            repository.playback.collect { info ->
                if (info == null) {
                    // The session can flap null for an instant while the same song
                    // keeps playing (destroy/recreate, empty active-sessions list).
                    // Resetting the key and swapping to sample lyrics here destroys
                    // the list scroll state, dropping the current line to the bottom
                    // on every flap. Only treat null as a real loss after a grace.
                    if (nullPlaybackJob?.isActive != true) {
                        nullPlaybackJob = viewModelScope.launch {
                            delay(NULL_PLAYBACK_GRACE_MS)
                            if (repository.playback.value != null) return@launch
                            _state.update { it.copy(playback = null) }
                            lyricsJob?.cancel()
                            fetchKeys.updateAndGet { it.copy(lastLyricsKey = null) }
                            if (!fetchKeys.get().manualOverride) {
                                showSampleLyrics()
                            }
                        }
                    }
                } else {
                    nullPlaybackJob?.cancel()
                    nullPlaybackJob = null
                    _state.update { it.copy(playback = info) }
                    val key = playbackKey(info)
                    val keys = fetchKeys.get()
                    if (keys.manualOverride && key != keys.manualPlayingKey) {
                        fetchKeys.set(FetchKeys())
                    }
                    if (!fetchKeys.get().manualOverride && info.title.isNotBlank()) {
                        fetchLyricsFor(info)
                    }
                    if (_state.value.liveLyrics) {
                        val index = indexForPosition(
                            _state.value.lines,
                            info.positionMs + _state.value.offsetMs
                        )
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
        startSampleTicker()
    }

    // The sample-lyrics ticker only runs while sample lyrics are shown; it is
    // cancelled as soon as a real fetch starts instead of looping while paused.
    private fun startSampleTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
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
                if (_state.value.playback == null && _state.value.lines == SampleLyrics.lines) {
                    _state.update { current ->
                        current.copy(currentIndex = (current.currentIndex + 1) % current.lines.size)
                    }
                }
            }
        }
    }

    private fun stopSampleTicker() {
        tickerJob?.cancel()
        tickerJob = null
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
        setStatusCardVisible(!_state.value.statusCardVisible)
    }

    fun setStatusCardVisible(visible: Boolean) {
        LyricsPrefs.statusCardVisible = visible
        _state.update { it.copy(statusCardVisible = visible) }
    }

    fun setLyricSource(source: LyricSource) {
        if (source == _state.value.lyricSource) return
        LyricsPrefs.lyricSource = source
        _state.update { it.copy(lyricSource = source) }
        fetchKeys.set(FetchKeys())
        val info = repository.playback.value
        if (info != null && info.title.isNotBlank()) {
            fetchLyricsFor(info)
        } else {
            showSampleLyrics()
        }
    }

    fun adjustOffset(deltaMs: Int) {
        val next = (LyricsPrefs.offsetMs + deltaMs)
            .coerceIn(LyricsPrefs.OFFSET_MIN_MS, LyricsPrefs.OFFSET_MAX_MS)
        LyricsPrefs.offsetMs = next
        _state.update { it.copy(offsetMs = next) }
        if (_state.value.liveLyrics) {
            val position = (repository.playback.value?.positionMs ?: 0L) + next
            _state.update { it.copy(currentIndex = indexForPosition(it.lines, position)) }
        }
    }

    fun setFloatingBarWidthDp(dp: Int) {
        val next = dp.coerceIn(LyricsPrefs.FLOATING_BAR_MIN_DP, LyricsPrefs.FLOATING_BAR_MAX_DP)
        LyricsPrefs.floatingBarWidthDp = next
        _state.update { it.copy(floatingBarWidthDp = next) }
    }

    fun searchLyrics(title: String, artist: String) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) return
        val playing = repository.playback.value
        val searchKey = trackKey(cleanTitle, cleanArtist)
        fetchKeys.updateAndGet {
            it.copy(
                manualOverride = true,
                manualPlayingKey = playing?.let { p -> playbackKey(p) },
                lastLyricsKey = searchKey
            )
        }
        lyricsJob?.cancel()
        stopSampleTicker()
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
            try {
                val result = fetchOrError(cleanTitle, cleanArtist)
                ensureActive()
                // Both keys use the same normalisation, so a manual search for
                // the playing track really does match and sync live.
                val keys = fetchKeys.get()
                val matchesPlaying = keys.manualPlayingKey != null &&
                    keys.manualPlayingKey == searchKey
                publishResult(result, liveIfFound = matchesPlaying)
            } finally {
                resetLoadingIfCurrent()
            }
        }
    }

    fun onScreenResume() {
        // Resume fires after every dialog/overlay too; throttle so repeated
        // resumes cannot pile up session refreshes and fetch races.
        val now = SystemClock.elapsedRealtime()
        if (now - lastResumeRefreshMs < RESUME_REFRESH_THROTTLE_MS) return
        lastResumeRefreshMs = now
        repository.refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                delay(600L)
                fetchKeys.set(FetchKeys())
                repository.refresh()
                val info = repository.playback.value
                if (info != null && info.title.isNotBlank()) {
                    fetchLyricsFor(info)
                } else {
                    showSampleLyrics()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed", e)
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun fetchLyricsFor(info: PlaybackInfo) {
        val key = playbackKey(info)
        val previous = fetchKeys.getAndUpdate { it.copy(lastLyricsKey = key) }
        if (key == previous.lastLyricsKey) return
        lyricsJob?.cancel()
        stopSampleTicker()
        lyricsJob = viewModelScope.launch {
            // Re-fetching the song already on screen (refresh, source change, key
            // reset) must keep the visible lines: blanking them swaps to the spinner,
            // which drops LyricsList from composition and resets its scroll, dumping
            // the current line at the bottom. Only a first fetch shows the spinner.
            val sameSongRefetch = previous.lastLyricsKey == null &&
                _state.value.liveLyrics &&
                _state.value.lines.isNotEmpty()
            if (!sameSongRefetch) {
                _state.update {
                    it.copy(
                        lines = emptyList(),
                        currentIndex = 0,
                        lyricsLoading = true,
                        lyricsUnavailable = false,
                        liveLyrics = false
                    )
                }
            }
            try {
                val result = fetchOrError(info.title, info.artist)
                ensureActive()
                if (fetchKeys.get().lastLyricsKey != key) return@launch
                val usable = result is LyricsResult.Found && result.lines.isNotEmpty()
                // A failed re-fetch must not blank lyrics that are still playing.
                if (sameSongRefetch && !usable) return@launch
                publishResult(result, liveIfFound = true)
            } finally {
                resetLoadingIfCurrent()
            }
        }
    }

    private suspend fun fetchOrError(title: String, artist: String): LyricsResult {
        return try {
            lyricsRepository.fetchLyrics(title, artist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics fetch failed", e)
            LyricsResult.Error
        }
    }

    private fun publishResult(result: LyricsResult, liveIfFound: Boolean) {
        // A Found result with no lines is not usable lyrics.
        val outcome = if (result is LyricsResult.Found && result.lines.isEmpty()) {
            LyricsResult.NotFound
        } else {
            result
        }
        when (outcome) {
            is LyricsResult.Found -> {
                val position = (repository.playback.value?.positionMs ?: 0L) + _state.value.offsetMs
                _state.update {
                    it.copy(
                        lines = outcome.lines,
                        currentIndex = if (liveIfFound) {
                            indexForPosition(outcome.lines, position)
                        } else {
                            0
                        },
                        lyricsLoading = false,
                        lyricsUnavailable = false,
                        liveLyrics = liveIfFound
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

    // A cancelled fetch must not leave the spinner on, but it also must not
    // clear the spinner of the newer job that replaced it.
    private suspend fun resetLoadingIfCurrent() {
        val job = coroutineContext[Job]
        _state.update {
            if (it.lyricsLoading && lyricsJob === job) it.copy(lyricsLoading = false) else it
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
        startSampleTicker()
    }

    private fun playbackKey(info: PlaybackInfo): String = trackKey(info.title, info.artist)

    private fun trackKey(title: String, artist: String): String =
        normalizeKeyPart(title) + "|" + normalizeKeyPart(artist)

    private fun normalizeKeyPart(value: String): String =
        value.trim().replace(WHITESPACE, " ").lowercase()

    private fun indexForPosition(lines: List<LyricLine>, positionMs: Long): Int {
        var index = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) index = i else break
        }
        return index
    }

    companion object {
        private const val TAG = "LyricsViewModel"
        private const val RESUME_REFRESH_THROTTLE_MS = 2_000L
        private const val NULL_PLAYBACK_GRACE_MS = 2_000L
        private val WHITESPACE = Regex("\\s+")
    }
}
