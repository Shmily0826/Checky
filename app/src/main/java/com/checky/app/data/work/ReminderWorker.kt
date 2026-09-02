package com.checky.app.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.checky.app.MainActivity
import com.checky.app.domain.model.CheckInSummary
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Optional daily reminder. This worker only posts a notification — it never
 * touches other apps, never checks in silently, and never controls anything.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NotificationHelper.showDailyReminder(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "checky_daily_reminder"

        /** (Re)schedule a daily reminder at [hour]:[minute] local time. */
        fun schedule(context: Context, hour: Int, minute: Int) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(nextRunDelayMillis(hour, minute), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                // The target is a wall-clock time; reset the periodic cycle
                // when the user changes it.
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }

        private fun nextRunDelayMillis(hour: Int, minute: Int): Long {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            var delay = calendar.timeInMillis - System.currentTimeMillis()
            if (delay <= 0) delay += TimeUnit.DAYS.toMillis(1)
            return delay
        }
    }
}

object NotificationHelper {
    private const val CHANNEL_ID = "checky_reminders"
    private const val NOTIFICATION_ID = 1001
    private const val RECONNECT_NOTIFICATION_ID = 1002
    private const val RESULT_NOTIFICATION_ID = 1003

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Reminds you to check in with Checky" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showDailyReminder(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Checky — time to check in!")
            .setContentText("A few quick taps and today's rewards are done.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Surfaces expired sessions found by a check-in run (esp. background runs). */
    fun showReconnectRequired(context: Context, expiredServices: List<String>) {
        if (expiredServices.isEmpty()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Checky — ${expiredServices.size} 个服务需要重新连接")
            .setContentText(expiredServices.joinToString("、"))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("登录已失效：${expiredServices.joinToString("、")}")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(context).notify(RECONNECT_NOTIFICATION_ID, notification)
        }
    }

    /** Surfaces the result of an auto check-in run (success + failure summary). */
    fun showCheckInResult(context: Context, summary: CheckInSummary) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!canNotify) return

        val needsAttention = summary.failed + summary.attention
        val title = if (needsAttention == 0) {
            "Checky — 今日签到完成"
        } else {
            "Checky — 签到完成，${needsAttention} 项需关注"
        }
        val content = buildString {
            append("成功 ${summary.succeeded}")
            if (summary.alreadyCheckedIn > 0) append(" · 已签到 ${summary.alreadyCheckedIn}")
            if (summary.failed > 0) append(" · 失败 ${summary.failed}")
            if (summary.attention > 0) append(" · 需重连 ${summary.attention}")
        }
        val bigText = buildString {
            append("成功 ${summary.succeeded} 项")
            if (summary.alreadyCheckedIn > 0) append("，已签到 ${summary.alreadyCheckedIn} 项")
            if (summary.failed > 0) append("，失败 ${summary.failed} 项")
            if (summary.attention > 0) append("，需重新连接 ${summary.attention} 项")
            if (summary.totalPoints > 0) append("。共获得 ${summary.totalPoints} 积分")
            if (summary.totalXp > 0) append("、${summary.totalXp} 经验")
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
    }
}
