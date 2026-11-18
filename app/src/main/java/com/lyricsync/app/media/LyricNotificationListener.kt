package com.lyricsync.app.media

import android.service.notification.NotificationListenerService

// Exists only so the system grants MediaSession access once the user
// enables it in Settings > Notification access. No notification handling.
class LyricNotificationListener : NotificationListenerService()
