package com.checky.app.data.work

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

/**
 * Optional personal auto-check-in worker. It runs enabled providers only,
 * sequentially, and never handles CAPTCHA or controls another app.
 */
class AutoCheckInWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AutoCheckInEntryPoint::class.java
        )
        val services = entryPoint.repository().observeServices().first()
        val enabledIds = services.filter { it.isEnabled }.map { it.serviceId }.toSet()
        val providers = entryPoint.providers().filter { it.meta.id in enabledIds }
        if (providers.isEmpty()) return Result.success()

        entryPoint.useCase()(providers, parallel = false).last()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "checky_auto_checkin"

        fun schedule(context: Context, hour: Int, minute: Int) {
            val request = PeriodicWorkRequestBuilder<AutoCheckInWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(nextRunDelayMillis(hour, minute), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoCheckInEntryPoint {
    fun repository(): CheckInRepository
    fun useCase(): CheckInAllUseCase
    fun providers(): @JvmSuppressWildcards List<CheckInProvider>
}
