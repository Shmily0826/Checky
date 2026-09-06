package com.checky.app.data.work

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.LegacyAuthHealthMigration
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.expiredServiceNames
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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
        try {
            // A queued worker can outlive the user's disable action. Re-check
            // the opt-in at execution time so cancellation races fail closed.
            val preferences = entryPoint.preferences().preferences.first()
            if (!shouldRunAutoCheckIn(preferences)) return Result.success()
            val services = entryPoint.repository().observeServices().first()
            LegacyAuthHealthMigration.seedIfNeeded(
                entryPoint.providers(), services, entryPoint.credentials(), entryPoint.authHealthStore()
            )
            val enabledIds = services.filter { it.isEnabled }.map { it.serviceId }.toSet()
            val providers = selectExecutableProviders(
                enabledIds, entryPoint.providers(), entryPoint.credentials(), entryPoint.authHealthStore()
            )
            if (providers.isNotEmpty()) {
                val finalProgress = entryPoint.useCase()(providers, parallel = false).last()
                // Background runs have no UI: surface results/expired sessions as notifications.
                if (finalProgress is CheckInAllProgress.Finished) {
                    NotificationHelper.showReconnectRequired(
                        applicationContext,
                        expiredServiceNames(finalProgress.states.values)
                    )
                    if (entryPoint.preferences().preferences.first().checkInResultNotify) {
                        NotificationHelper.showCheckInResult(applicationContext, finalProgress.summary)
                    }
                }
            }
            if (isDailyScheduled(inputData)) rescheduleIfEnabled(entryPoint)
            return Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Scheduler liveness only: preserve tomorrow's wall-clock attempt
            // after an unexpected failure. This is not a provider retry and
            // does not bypass the VALID-only mutation gate.
            if (isDailyScheduled(inputData)) rescheduleIfEnabled(entryPoint)
            return Result.success()
        }
    }

    private suspend fun rescheduleIfEnabled(entryPoint: AutoCheckInEntryPoint) {
        val latestPreferences = entryPoint.preferences().preferences.first()
        if (latestPreferences.autoCheckInEnabled) {
            scheduleAfterRun(applicationContext, latestPreferences.autoCheckInHour, latestPreferences.autoCheckInMinute)
        }
    }

    companion object {
        private const val UNIQUE_WORK = "checky_auto_checkin"
        private const val DAILY_SCHEDULED_INPUT = "daily_scheduled"

        suspend fun schedule(context: Context, hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()) {
            // Do not cancel an in-flight provider mutation. The running worker
            // reads latest preferences on completion and appends its next run.
            if (isRunning(context)) return
            val request = OneTimeWorkRequestBuilder<AutoCheckInWorker>()
                .setInputData(workDataOf(DAILY_SCHEDULED_INPUT to true))
                .setInitialDelay(nextRunDelayMillis(hour, minute, now), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        suspend fun cancel(context: Context) {
            // Disabling while a mutation is in flight must not interrupt it;
            // the worker's latest-preferences check prevents future scheduling.
            if (isRunning(context)) return
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }

        private suspend fun isRunning(context: Context): Boolean {
            val future = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_WORK)
            val workInfos = suspendCancellableCoroutine<List<androidx.work.WorkInfo>> { continuation ->
                future.addListener({
                    try {
                        continuation.resume(future.get())
                    } catch (cancellation: java.util.concurrent.CancellationException) {
                        continuation.cancel(cancellation)
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                }, java.util.concurrent.Executor { it.run() })
            }
            return shouldDeferScheduleReplacement(workInfos.map { it.state })
        }

        suspend fun reconcile(context: Context) {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutoCheckInEntryPoint::class.java
            )
            val prefs = entryPoint.preferences().preferences.first()
            if (prefs.autoCheckInEnabled) {
                schedule(context, prefs.autoCheckInHour, prefs.autoCheckInMinute)
            } else {
                cancel(context)
            }
        }

        /** Queues behind the currently running worker without replacing it. */
        internal fun scheduleAfterRun(context: Context, hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()) {
            val request = OneTimeWorkRequestBuilder<AutoCheckInWorker>()
                .setInputData(workDataOf(DAILY_SCHEDULED_INPUT to true))
                .setInitialDelay(nextRunDelayMillis(hour, minute, now), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        internal fun nextRunDelayMillis(hour: Int, minute: Int, now: ZonedDateTime): Long =
            java.time.Duration.between(now, nextScheduledDateTime(hour, minute, now)).toMillis().coerceAtLeast(0)

        internal fun nextScheduledDateTime(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
            require(hour in 0..23 && minute in 0..59)
            val target = LocalTime.of(hour, minute)
            val today = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), target), now.zone)
            return if (today.isAfter(now)) today else
                ZonedDateTime.of(LocalDateTime.of(now.toLocalDate().plusDays(1), target), now.zone)
        }
    }

}

internal fun isDailyScheduled(inputData: Data): Boolean =
    inputData.getBoolean("daily_scheduled", false)

internal fun shouldDeferScheduleReplacement(states: List<androidx.work.WorkInfo.State>): Boolean =
    states.any { it == androidx.work.WorkInfo.State.RUNNING }

internal fun shouldRunAutoCheckIn(preferences: UserPreferences): Boolean =
    preferences.autoCheckInEnabled

internal suspend fun selectExecutableProviders(
    enabledIds: Set<String>,
    providers: List<CheckInProvider>,
    credentials: CredentialStore,
    authHealthStore: AuthHealthStore = com.checky.app.domain.NoOpAuthHealthStore
): List<CheckInProvider> = providers.filter {
    it.meta.id in enabledIds && ProviderConnectionGate.isConnected(it, credentials, authHealthStore)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoCheckInEntryPoint {
    fun repository(): CheckInRepository
    fun useCase(): CheckInAllUseCase
    fun providers(): @JvmSuppressWildcards List<CheckInProvider>
    fun preferences(): UserPreferencesRepository
    fun credentials(): CredentialStore
    fun authHealthStore(): AuthHealthStore
}
