package com.checky.app.data.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Inexact wake-up trigger; the receiver only reconciles persisted WorkManager work. */
internal object AutoCheckInAlarm {
    private const val REQUEST_CODE = 592

    fun schedule(context: Context, atEpochMillis: Long) {
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            atEpochMillis,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val pendingIntent = pendingIntent(context)
        alarmManager(context).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoCheckInScheduleReceiver::class.java).setAction(ACTION_RECONCILE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    const val ACTION_RECONCILE = "com.checky.app.action.RECONCILE_AUTO_CHECKIN"
}
