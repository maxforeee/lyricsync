package com.lyricsync.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.lyricsync.app.R
import com.lyricsync.app.media.LyricsPrefs
import com.lyricsync.app.media.LyricsRepository
import com.lyricsync.app.media.LyricsResult
import com.lyricsync.app.media.MediaSessionRepository
import com.lyricsync.app.media.PlaybackInfo
import com.lyricsync.app.model.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

object LyricSyncHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaRepository: MediaSessionRepository? = null

    val lyrics: LyricsRepository = LyricsRepository()

    fun media(context: Context): MediaSessionRepository {
        mediaRepository?.let { return it }
        return synchronized(this) {
            mediaRepository ?: MediaSessionRepository(context.applicationContext, scope).also {
                LyricsPrefs.init(context)
                mediaRepository = it
                it.refresh()
            }
        }
    }
}

class FloatingLyricService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var lyricText: TextView
    private lateinit var translationText: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var barView: LinearLayout? = null
    private var closeButtonView: TextView? = null

    private var lines: List<LyricLine> = emptyList()
    private var translations: Map<Long, String> = emptyMap()
    private var lyricsKey: String? = null
    private var shownIndex = -1
    private var fetchJob: Job? = null
    private var lastPlayback: PlaybackInfo? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LyricsPrefs.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        addBar()
        serviceScope.launch {
            LyricSyncHub.media(this@FloatingLyricService).playback.collect { info ->
                onPlayback(info)
            }
        }
    }

    // Not sticky: once the user closes the bar it must not restart on its own.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        serviceScope.cancel()
        barView?.let { windowManager.removeView(it) }
        barView = null
        super.onDestroy()
    }

    private fun onPlayback(info: PlaybackInfo?) {
        lastPlayback = info
        if (info == null || info.title.isBlank()) {
            fetchJob?.cancel()
            lines = emptyList()
            translations = emptyMap()
            lyricsKey = null
            shownIndex = -1
            lyricText.text = getString(R.string.floating_no_playback)
            translationText.visibility = View.GONE
            return
        }
        val key = info.title.trim() + "|" + info.artist.trim()
        if (key != lyricsKey) {
            lyricsKey = key
            fetchJob?.cancel()
            lines = emptyList()
            translations = emptyMap()
            shownIndex = -1
            lyricText.text = getString(R.string.loading_lyrics)
            translationText.visibility = View.GONE
            fetchJob = serviceScope.launch {
                val result = LyricSyncHub.lyrics.fetchLyrics(info.title, info.artist)
                ensureActive()
                if (lyricsKey != key) return@launch
                when (result) {
                    is LyricsResult.Found -> {
                        lines = result.lines
                        translations = result.translations
                    }
                    LyricsResult.NotFound, LyricsResult.Error -> {
                        lines = emptyList()
                        translations = emptyMap()
                    }
                }
                shownIndex = -1
                if (lines.isEmpty()) {
                    lyricText.text = getString(R.string.floating_no_lyrics)
                    translationText.visibility = View.GONE
                } else {
                    updateLine(lastPlayback)
                }
            }
        }
        updateLine(info)
    }

    private fun updateLine(info: PlaybackInfo?) {
        if (info == null || lines.isEmpty()) return
        val position = info.positionMs + LyricsPrefs.offsetMs
        var index = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= position) index = i else break
        }
        if (index == shownIndex) return
        shownIndex = index
        val line = lines[index]
        lyricText.text = line.text
        val translation = translations[line.timeMs]
        if (translation.isNullOrBlank()) {
            translationText.visibility = View.GONE
        } else {
            translationText.text = translation
            translationText.visibility = View.VISIBLE
        }
    }

    private fun addBar() {
        if (barView != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        lyricText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(240)
            text = getString(R.string.floating_no_playback)
        }
        translationText = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(240)
            visibility = View.GONE
        }
        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = getString(R.string.floating_close)
            setOnClickListener { stopSelf() }
        }
        closeButtonView = closeButton
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lyricText)
            addView(translationText)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF2141A33.toInt(), 0xF2251B3D.toInt())
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(16), dp(10), dp(8), dp(10))
            addView(
                textColumn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                closeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setOnTouchListener(DragTouchListener())
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(96)
        }
        windowManager.addView(bar, layoutParams)
        barView = bar
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A tap on the close button must reach its click listener;
                    // returning true here unconditionally used to swallow it.
                    if (isOnCloseButton(event)) return false
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - touchX).toInt()
                    layoutParams.y = startY + (event.rawY - touchY).toInt()
                    barView?.let { windowManager.updateViewLayout(it, layoutParams) }
                }
            }
            return true
        }

        private fun isOnCloseButton(event: MotionEvent): Boolean {
            val button = closeButtonView ?: return false
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            return x >= location[0] && x < location[0] + button.width &&
                y >= location[1] && y < location[1] + button.height
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_lyrics"
        private const val NOTIFICATION_ID = 2
    }
}
