package com.lyricsync.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaSessionRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _playback = MutableStateFlow<PlaybackInfo?>(null)
    val playback: StateFlow<PlaybackInfo?> = _playback.asStateFlow()

    private val _listenerGranted = MutableStateFlow(false)
    val listenerGranted: StateFlow<Boolean> = _listenerGranted.asStateFlow()

    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var lastPolledPosition = -1L

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            detach()
            _playback.value = null
        }
    }

    fun refresh() {
        val granted = isListenerGranted()
        _listenerGranted.value = granted
        if (!granted) {
            detach()
            _playback.value = null
            return
        }
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, LyricNotificationListener::class.java))
        } catch (e: SecurityException) {
            _listenerGranted.value = false
            detach()
            _playback.value = null
            return
        }
        val next = sessions.firstOrNull { it.packageName == TELEGRAM_PACKAGE }
            ?: sessions.firstOrNull()
        detach()
        if (next == null) {
            _playback.value = null
            return
        }
        controller = next
        next.registerCallback(callback)
        publish()
        startPolling()
    }

    fun close() {
        detach()
    }

    private fun isListenerGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
        ) ?: return false
        val component = ComponentName(context, LyricNotificationListener::class.java)
        return enabled.split(':').any { it == component.flattenToString() }
    }

    private fun detach() {
        pollJob?.cancel()
        pollJob = null
        controller?.unregisterCallback(callback)
        controller = null
        lastPolledPosition = -1L
    }

    private fun publish(positionMs: Long? = null, advancing: Boolean? = null) {
        val current = controller ?: return
        val metadata = current.metadata
        val state = current.playbackState
        val previous = _playback.value
        _playback.value = PlaybackInfo(
            packageName = current.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = positionMs ?: state?.position ?: 0L,
            playing = state?.state == PlaybackState.STATE_PLAYING,
            lastUpdateMs = System.currentTimeMillis(),
            positionAdvancing = advancing ?: previous?.positionAdvancing ?: false
        )
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val current = controller ?: continue
                val state = current.playbackState ?: continue
                if (state.state != PlaybackState.STATE_PLAYING) continue
                val position = state.position
                val advancing = lastPolledPosition >= 0L && position > lastPolledPosition
                lastPolledPosition = position
                publish(positionMs = position, advancing = advancing)
            }
        }
    }

    companion object {
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        private const val POLL_INTERVAL_MS = 500L
    }
}
