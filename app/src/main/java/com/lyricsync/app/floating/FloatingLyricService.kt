package com.lyricsync.app.floating

import android.animation.ObjectAnimator
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
import android.os.SystemClock
import android.view.GestureDetector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    // Double-tap the bar to dismiss it (the close button is gone). Persist the
    // intent so a sticky restart after a system kill never resurrects it.
    private val doubleTapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setUserDismissed(true)
                stopSelf()
                return true
            }
        })
    }

    private val barPrefs by lazy { getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE) }

    private fun userDismissed(): Boolean = barPrefs.getBoolean(KEY_USER_DISMISSED, false)

    private fun setUserDismissed(dismissed: Boolean) {
        barPrefs.edit().putBoolean(KEY_USER_DISMISSED, dismissed).apply()
    }

    private var lines: List<LyricLine> = emptyList()
    private var translations: Map<Long, String> = emptyMap()
    private var lyricsKey: String? = null
    private var shownIndex = -1
    private var fetchJob: Job? = null
    private var lastPlayback: PlaybackInfo? = null
    private lateinit var repository: MediaSessionRepository
    private var nullPlaybackJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastPositionMs = -1L
    private var lastPositionChangeMs = 0L
    private var lyricShift: Runnable? = null
    private var translationShift: Runnable? = null
    // The slide animator is not reachable through the view's callback queue:
    // once it has started, removeCallbacks cannot stop it, so track it per
    // view and cancel it whenever the line is reset. Otherwise a stale
    // animator keeps writing scrollX and slides the NEW line out of the clip,
    // leaving an empty near-black pill ("black lyrics").
    private var lyricSlide: ObjectAnimator? = null
    private var translationSlide: ObjectAnimator? = null

    private fun slideFor(view: TextView): ObjectAnimator? =
        if (view === lyricText) lyricSlide else translationSlide

    private fun setSlideFor(view: TextView, animator: ObjectAnimator?) {
        if (view === lyricText) lyricSlide = animator else translationSlide = animator
    }

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
        // On a sticky restart after a system kill, honour a persisted user
        // dismissal; an explicit start clears the flag in onStartCommand.
        if (!userDismissed()) addBar()
        repository = LyricSyncHub.media(this)
        lastPositionChangeMs = SystemClock.elapsedRealtime()
        startWatchdog()
        serviceScope.launch {
            repository.playback.collect { info ->
                onPlayback(info)
            }
        }
    }

    // Sticky so the system restarts us after a background kill (aggressive OEM
    // cleanup) and the bar comes back on its own. User intent is kept in a
    // persisted flag: a dismissed bar stays gone even across a kill/restart.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            setUserDismissed(false)
            if (barView == null) {
                addBar()
                shownIndex = -1
                onPlayback(repository.playback.value)
            }
        } else if (userDismissed()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::lyricText.isInitialized) lyricShift?.let { lyricText.removeCallbacks(it) }
        if (::translationText.isInitialized) translationShift?.let { translationText.removeCallbacks(it) }
        lyricSlide?.cancel()
        translationSlide?.cancel()
        serviceScope.cancel()
        barView?.let { windowManager.removeView(it) }
        barView = null
        super.onDestroy()
    }

    private fun onPlayback(info: PlaybackInfo?) {
        // No bar while a dismissal is being processed or before an explicit
        // start re-adds it; there is nothing to render then.
        if (barView == null) return
        if (info == null || info.title.isBlank()) {
            // The session can flap null (or blank metadata) for an instant while
            // the same song keeps playing. Clearing here blanked the bar on every
            // flap, including interludes; only treat it as a real loss after the
            // same grace the ViewModel uses, keeping the last line on screen.
            if (nullPlaybackJob?.isActive != true) {
                nullPlaybackJob = serviceScope.launch {
                    delay(NULL_PLAYBACK_GRACE_MS)
                    val current = repository.playback.value
                    if (current != null && current.title.isNotBlank()) return@launch
                    lastPlayback = null
                    fetchJob?.cancel()
                    lines = emptyList()
                    translations = emptyMap()
                    lyricsKey = null
                    shownIndex = -1
                    clearShift(lyricText, lyricShift)
                    lyricShift = null
                    lyricText.text = getString(R.string.floating_no_playback)
                    translationText.visibility = View.GONE
                }
            }
            return
        }
        nullPlaybackJob?.cancel()
        nullPlaybackJob = null
        lastPlayback = info
        if (info.positionMs != lastPositionMs) {
            lastPositionMs = info.positionMs
            lastPositionChangeMs = SystemClock.elapsedRealtime()
        }
        val key = info.title.trim() + "|" + info.artist.trim()
        if (key != lyricsKey) {
            lyricsKey = key
            fetchJob?.cancel()
            lines = emptyList()
            translations = emptyMap()
            shownIndex = -1
            clearShift(lyricText, lyricShift)
            lyricShift = null
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
                    clearShift(lyricText, lyricShift)
                    lyricShift = null
                    lyricText.text = getString(R.string.floating_no_lyrics)
                    translationText.visibility = View.GONE
                } else {
                    updateLine(lastPlayback)
                }
            }
        }
        updateLine(info)
    }

    // In background nothing else calls repository.refresh(): the ViewModel only
    // refreshes on init/resume. If the session is lost (or its position freezes
    // while "playing") and the active-sessions callback never re-attaches, the
    // bar would stay blank until the user returns to the app. Re-discover the
    // session ourselves; refresh is a no-op re-publish when nothing changed.
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                ensureBarAttached()
                val info = repository.playback.value
                val lost = info == null || info.title.isBlank()
                val stale = info != null && info.playing &&
                    SystemClock.elapsedRealtime() - lastPositionChangeMs > POSITION_STALE_MS
                if (lost || stale) repository.refresh()
            }
        }
    }

    // Self-heal the overlay: background cleanup can detach the bar's window
    // while the service survives. Re-add a fresh bar unless the user dismissed
    // it, and re-render the current line onto the new views.
    private fun ensureBarAttached() {
        if (userDismissed()) return
        val bar = barView
        if (bar != null && bar.isAttachedToWindow) return
        if (bar != null) {
            runCatching { windowManager.removeViewImmediate(bar) }
            barView = null
        }
        // addView throws if the overlay permission was revoked; retry next tick.
        runCatching { addBar() }
        if (barView != null) {
            shownIndex = -1
            onPlayback(repository.playback.value)
        }
    }

    private fun updateLine(info: PlaybackInfo?) {
        if (info == null || lines.isEmpty()) return
        applyBarWidth()
        val position = info.positionMs + LyricsPrefs.offsetMs
        var index = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= position) index = i else break
        }
        if (index == shownIndex) return
        shownIndex = index
        val line = lines[index]
        lyricShift = showBarLine(lyricText, line.text, lyricShift)
        val translation = translations[line.timeMs]
        if (translation.isNullOrBlank()) {
            clearShift(translationText, translationShift)
            translationShift = null
            translationText.visibility = View.GONE
        } else {
            translationShift = showBarLine(translationText, translation, translationShift)
            translationText.visibility = View.VISIBLE
        }
    }

    // If a line is wider than the bar, slide it once (after a short pause so
    // the head is readable first) so the tail becomes visible instead of "...".
    // Short lines are centred in the bar instead of leaving a gap on the right.
    private fun showBarLine(view: TextView, text: String, previousShift: Runnable?): Runnable {
        previousShift?.let { view.removeCallbacks(it) }
        slideFor(view)?.cancel()
        setSlideFor(view, null)
        view.scrollTo(0, 0)
        view.text = text
        val visibleWidth = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        if (view.paint.measureText(text) <= visibleWidth) {
            view.gravity = Gravity.CENTER
            return Runnable {}
        }
        view.gravity = Gravity.START
        val shift = Runnable {
            // Measure the real laid-out line, not the paint estimate, so the
            // tail stops exactly at the edge with no empty gap after it.
            val layoutWidth = view.layout?.getLineWidth(0) ?: view.paint.measureText(view.text.toString())
            val overflow = (layoutWidth - (view.width - view.paddingLeft - view.paddingRight)).toInt()
            if (overflow > 0) {
                ObjectAnimator.ofInt(view, "scrollX", 0, overflow).apply {
                    duration = 400L
                    setSlideFor(view, this)
                    start()
                }
            } else {
                // Only looked long because the view had not been laid out yet.
                view.gravity = Gravity.CENTER
            }
        }
        view.postDelayed(shift, 700)
        return shift
    }

    private fun clearShift(view: TextView, shift: Runnable?) {
        shift?.let { view.removeCallbacks(it) }
        slideFor(view)?.cancel()
        setSlideFor(view, null)
        view.scrollTo(0, 0)
    }

    // The bar length is adjustable in Settings; the bar always shows exactly
    // the width the user set, it never shrinks or grows with the lyric length.
    private fun applyBarWidth() {
        val widthPx = (LyricsPrefs.floatingBarWidthDp * resources.displayMetrics.density).toInt()
        fun fixWidth(view: TextView) {
            val params = view.layoutParams
            if (params.width != widthPx || view.maxWidth != widthPx) {
                params.width = widthPx
                view.layoutParams = params
                view.maxWidth = widthPx
                view.requestLayout()
            }
        }
        fixWidth(lyricText)
        fixWidth(translationText)
    }

    private fun addBar() {
        if (barView != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        lyricText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = null
            setHorizontallyScrolling(true)
            layoutParams = LinearLayout.LayoutParams(
                dp(LyricsPrefs.floatingBarWidthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = dp(LyricsPrefs.floatingBarWidthDp)
            gravity = Gravity.CENTER
            text = getString(R.string.floating_no_playback)
        }
        translationText = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = null
            setHorizontallyScrolling(true)
            layoutParams = LinearLayout.LayoutParams(
                dp(LyricsPrefs.floatingBarWidthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxWidth = dp(LyricsPrefs.floatingBarWidthDp)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
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
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(
                textColumn,
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
            doubleTapDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel importance is frozen at creation, so move to a fresh channel
        // to raise the old LOW one: the user should see the service is running.
        // Silent DEFAULT gives a status-bar icon without a sound on start.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_notification_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setOngoing(true)
            .setNoClear(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_lyrics_v2"
        private const val LEGACY_CHANNEL_ID = "floating_lyrics"
        private const val BAR_PREFS = "floating_bar_state"
        private const val KEY_USER_DISMISSED = "user_dismissed"
        private const val NOTIFICATION_ID = 2
        private const val NULL_PLAYBACK_GRACE_MS = 2_000L
        private const val WATCHDOG_INTERVAL_MS = 2_000L
        private const val POSITION_STALE_MS = 5_000L
    }
}
