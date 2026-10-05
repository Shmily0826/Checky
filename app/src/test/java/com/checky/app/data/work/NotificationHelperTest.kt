package com.checky.app.data.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.checky.app.R
import com.checky.app.domain.model.CheckInSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Test
    fun successfulResultsCountAlreadyCompletedAndReuseOneNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val summary = CheckInSummary(5, 3, 2, 0, 0, 0, 0, durationMs = 10)

        NotificationHelper.showCheckInResult(context, summary)
        NotificationHelper.showCheckInResult(context, summary)

        val notification = manager.allNotifications.single()
        assertEquals(1, manager.allNotifications.size)
        assertEquals(context.getString(R.string.notification_result_title_success), notification.extras.getString("android.title"))
        assertEquals(context.getString(R.string.notification_result_success_text, 5, 5), notification.extras.getString("android.text"))
    }

    @Test
    fun deniedNotificationPermissionDoesNotPostOrThrow() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        NotificationHelper.showCheckInResult(
            context,
            CheckInSummary(1, 1, 0, 0, 0, 0, 0, durationMs = 1)
        )

        assertTrue(manager.allNotifications.isEmpty())
    }
}
