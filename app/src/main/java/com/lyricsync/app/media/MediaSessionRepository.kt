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
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

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
        val component = ComponentName(context, LyricNotificationListener::class.java)
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            _listenerGranted.value = false
            detach()
            _playback.value = null
            return
        }
        // Re-discover sessions when a media app starts (or stops) later, not
        // only at refresh time.
        if (sessionsListener == null) {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
                attachBest(active ?: emptyList())
            }
            try {
                manager.addOnActiveSessionsChangedListener(listener, component)
                sessionsListener = listener
            } catch (e: SecurityException) {
                _listenerGranted.value = false
                detach()
                _playback.value = null
                return
            }
        }
        attachBest(sessions)
    }

    private fun attachBest(sessions: List<MediaController>) {
        // Telegram ships package variants (org.telegram.messenger.web); exact
        // equality missed them and fell back to whatever session was first.
        val next = sessions.firstOrNull {
            it.packageName == TELEGRAM_PACKAGE || it.packageName.startsWith("$TELEGRAM_PACKAGE.")
        } ?: sessions.firstOrNull()
        // getActiveSessions returns fresh controller instances; compare session
        // tokens so an unchanged session is not detached and reattached.
        if (next?.sessionToken == controller?.sessionToken) {
            if (next != null) publish()
            return
        }
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
        sessionsListener?.let { listener ->
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            try {
                manager.removeOnActiveSessionsChangedListener(listener)
            } catch (e: SecurityException) {
                // Notification access already revoked; nothing to unregister.
            }
        }
        sessionsListener = null
    }

    private fun isListenerGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners" // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is @hide, use the literal
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
        val playing = state?.state == PlaybackState.STATE_PLAYING
        _playback.value = PlaybackInfo(
            packageName = current.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            // PLAYBACK_POSITION_UNKNOWN is -1; never publish a negative position.
            positionMs = (positionMs ?: state?.position ?: 0L).coerceAtLeast(0L),
            playing = playing,
            lastUpdateMs = System.currentTimeMillis(),
            positionAdvancing = advancing ?: (playing && previous?.positionAdvancing == true)
        )
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val current = controller ?: continue
                val state = current.playbackState
                if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                    // Publish paused/stopped states too: skipping them left
                    // positionAdvancing sticky-true after playback stopped.
                    val position = (state?.position ?: 0L).coerceAtLeast(0L)
                    lastPolledPosition = position
                    publish(positionMs = position, advancing = false)
                    continue
                }
                val position = state.position.coerceAtLeast(0L)
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
