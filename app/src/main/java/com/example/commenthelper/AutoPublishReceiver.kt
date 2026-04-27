package com.example.commenthelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class AutoPublishReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wm = WorkManager.getInstance(context)
        val req = OneTimeWorkRequestBuilder<AutoPublishWorker>().build()
        wm.enqueue(req)
    }

    companion object {
        fun schedule(context: Context, intervalMinutes: Int) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AutoPublishReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (intervalMinutes <= 0) {
                am.cancel(pi)
            } else {
                val intervalMillis = intervalMinutes * 60 * 1000L
                val trigger = System.currentTimeMillis() + intervalMillis
                am.setRepeating(AlarmManager.RTC_WAKEUP, trigger, intervalMillis, pi)
            }
        }
    }
}
