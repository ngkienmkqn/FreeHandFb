package com.example.commenthelper

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class FbNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "FbNotificationListener"
        val pendingApprovedPosts = mutableListOf<StatusBarNotification>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (sbn.packageName == "com.facebook.katana") {
            val extras = sbn.notification.extras
            val title = extras.getString(android.app.Notification.EXTRA_TITLE, "").lowercase()
            val text = extras.getString(android.app.Notification.EXTRA_TEXT, "").lowercase()
            val ticker = sbn.notification.tickerText?.toString()?.lowercase() ?: ""

            Log.d(TAG, "FB Notification Received - Title: $title, Text: $text, Ticker: $ticker")

            if (text.contains("phê duyệt") || text.contains("được duyệt") || text.contains("approved") ||
                title.contains("phê duyệt") || title.contains("được duyệt") || title.contains("approved") ||
                ticker.contains("phê duyệt") || ticker.contains("được duyệt") || ticker.contains("approved")
            ) {
                if (sbn.notification.contentIntent != null) {
                    Log.d(TAG, "Approved post notification found! Saving to queue.")
                    
                    // Add if not already in queue
                    if (pendingApprovedPosts.none { it.key == sbn.key }) {
                        pendingApprovedPosts.add(sbn)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        pendingApprovedPosts.removeAll { it.key == sbn.key }
    }
}
