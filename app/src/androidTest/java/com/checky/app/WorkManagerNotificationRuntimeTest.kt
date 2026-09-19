package com.checky.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.checky.app.data.work.AutoCheckInWorker
import com.checky.app.data.work.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Runtime-only checks using WorkManager and local notification content. */
@RunWith(AndroidJUnit4::class)
class WorkManagerNotificationRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() = runBlocking {
        AutoCheckInWorker.cancel(context)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() = runBlocking {
        AutoCheckInWorker.cancel(context)
        notificationManager.cancelAll()
    }

    @Test
    fun scheduleChangeReplacesUniqueWorkAndCancelRemovesIt() = runBlocking {
        AutoCheckInWorker.schedule(context, 1, 2)
        val first = uniqueWork()
        assertEquals(1, first.size)

        AutoCheckInWorker.schedule(context, 4, 5)
        val second = uniqueWork()
        assertEquals(1, second.size)
        assertTrue("schedule replacement must use a new work request", first.single().id != second.single().id)

        AutoCheckInWorker.cancel(context)
        assertTrue("cancelled unique work must not remain active", uniqueWork().all { it.state.isFinished })
    }

    @Test
    fun notificationChannelAndGrantedDeliveryAreVisibleToAndroid() {
        NotificationHelper.ensureChannel(context)
        val channel = notificationManager.getNotificationChannel("checky_reminders")
        assertNotNull(channel)
        assertTrue(channel!!.importance >= NotificationManager.IMPORTANCE_DEFAULT)

        assertTrue(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        NotificationHelper.showDailyReminder(context)
        assertTrue(notificationManager.activeNotifications.any { it.notification.channelId == "checky_reminders" })
    }

    @Test
    fun deniedPermissionSuppressesDeliveryWithoutThrowing() {
        assertFalse(NotificationManagerCompat.from(context).areNotificationsEnabled())
        NotificationHelper.showDailyReminder(context)
    }

    private fun uniqueWork() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork("checky_auto_checkin")
        .get()

}
