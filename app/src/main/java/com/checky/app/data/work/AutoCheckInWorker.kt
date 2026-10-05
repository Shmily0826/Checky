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
import com.checky.app.data.preferences.AutoCheckInDiagnosticOutcome
import com.checky.app.data.preferences.AutoCheckInDiagnostics
import com.checky.app.data.preferences.AutoCheckInDiagnosticsStore
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CheckInRunMode
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.LegacyAuthHealthMigration
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.providers.TaygedoProvider
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.expiredServiceNames
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Duration
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
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
        val dailyScheduled = isDailyScheduled(inputData)
        try {
            val execution = runWithDailyDiagnostics(
                dailyScheduled = dailyScheduled,
                diagnostics = if (dailyScheduled) entryPoint.diagnostics() else null
            ) {
                executeAutoCheckIn(
                    preferences = { entryPoint.preferences().preferences.first() },
                    services = { entryPoint.repository().observeServices().first() },
                    providers = entryPoint.providers(),
                    credentials = entryPoint.credentials(),
                    authHealthStore = entryPoint.authHealthStore(),
                    runBatch = { providers ->
                        entryPoint.useCase()(
                            providers,
                            parallel = false,
                            runMode = CheckInRunMode.AUTOMATIC
                        ).last()
                    }
                )
            }
            if (shouldPostAutoCheckInResult(dailyScheduled, execution)) {
                val completed = execution as AutoCheckInExecutionResult.Completed
                // Background runs have no UI: surface results/expired sessions as notifications.
                NotificationHelper.showReconnectRequired(
                    applicationContext,
                    completed.expiredServiceNames
                )
                if (entryPoint.preferences().preferences.first().checkInResultNotify) {
                    NotificationHelper.showCheckInResult(
                        applicationContext,
                        completed.summary,
                        reconnectRequired = completed.expiredServiceNames.size
                    )
                }
            }
            if (dailyScheduled) rescheduleIfEnabled(entryPoint)
            return Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Scheduler liveness only: preserve tomorrow's wall-clock attempt
            // after an unexpected failure. This is not a provider retry and
            // does not bypass the VALID-only mutation gate.
            if (dailyScheduled) rescheduleIfEnabled(entryPoint)
            return Result.success()
        }
    }

    private suspend fun rescheduleIfEnabled(entryPoint: AutoCheckInEntryPoint) {
        val latestPreferences = entryPoint.preferences().preferences.first()
        if (latestPreferences.autoCheckInEnabled) {
            scheduleAfterRun(
                applicationContext,
                latestPreferences.autoCheckInHour,
                latestPreferences.autoCheckInMinute,
                providerMetas = entryPoint.providers().map { it.meta },
                diagnostics = entryPoint.diagnostics()
            )
        }
    }

    companion object {
        private const val UNIQUE_WORK = "checky_auto_checkin"
        private const val DAILY_SCHEDULED_INPUT = "daily_scheduled"

        suspend fun schedule(
            context: Context,
            hour: Int,
            minute: Int,
            now: ZonedDateTime = ZonedDateTime.now(),
            providerMetas: List<ProviderMeta> = emptyList(),
            diagnostics: AutoCheckInDiagnosticsStore? = null,
            replacePendingWork: Boolean = false,
            chooseJitterOffsetMinutes: () -> Int = { Random.nextInt(-5, 6) }
        ) {
            val diagnosticsStore = diagnostics ?: diagnosticsStore(context)
            val zones = providerMetas.map { it.businessZone }.toSet()
            val state = diagnosticsStore.diagnostics.first()
            // Keep daily offsets within five minutes; the diagnostics store retains each date's choice.
            val targets = listOf(now.toLocalDate(), now.toLocalDate().plusDays(1)).associateWith { date ->
                val base = AutoCheckInSchedule.scheduledDateTime(date, hour, minute, now, zones)
                val epoch = diagnosticsStore.getOrCreateDailyTarget(
                    date, now.toLocalDate(), base.toInstant().toEpochMilli(),
                    chooseJitterOffsetMinutes
                )
                java.time.Instant.ofEpochMilli(epoch).atZone(now.zone)
            }
            val decision = decideDailySchedule(hour, minute, now, zones, state, targets)
            AutoCheckInAlarm.schedule(context, decision.alarmAt.toInstant().toEpochMilli())
            val states = workStates(context)
            if (shouldDeferScheduleReplacement(
                    states = states,
                    plannedNextEpochMillis = state.plannedNextEpochMillis,
                    desiredEpochMillis = decision.workAt.toInstant().toEpochMilli(),
                    nowEpochMillis = now.toInstant().toEpochMilli(),
                    replacePendingWork = replacePendingWork
                )
            ) {
                diagnosticsStore.recordPlannedNext(decision.workAt.toInstant().toEpochMilli())
                return
            }

            val request = OneTimeWorkRequestBuilder<AutoCheckInWorker>()
                .setInputData(workDataOf(DAILY_SCHEDULED_INPUT to true))
                .setInitialDelay(
                    Duration.between(now, decision.workAt).toMillis().coerceAtLeast(0),
                    TimeUnit.MILLISECONDS
                )
                .build()
            val workManager = WorkManager.getInstance(context)
            val enqueueOperation = workManager.enqueueUniqueWork(
                UNIQUE_WORK,
                if (replacePendingWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
            try {
                awaitOperation(enqueueOperation)
                diagnosticsStore.recordPlannedNext(decision.workAt.toInstant().toEpochMilli())
            } catch (cancellation: CancellationException) {
                workManager.cancelWorkById(request.id)
                throw cancellation
            } catch (failure: Exception) {
                // Do not leave an unrepresented daily mutation scheduled when
                // the local diagnostics transaction cannot be completed.
                workManager.cancelWorkById(request.id)
                throw failure
            }
        }

        suspend fun cancel(context: Context, diagnostics: AutoCheckInDiagnosticsStore? = null) {
            AutoCheckInAlarm.cancel(context)
            // Disabling while a mutation is in flight must not interrupt it.
            if (isRunning(context)) return
            val diagnosticsStore = diagnostics ?: diagnosticsStore(context)
            val cancelOperation = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            awaitOperation(cancelOperation)
            diagnosticsStore.clearPlannedNext()
        }

        private suspend fun isRunning(context: Context): Boolean {
            return workStates(context).any { it == androidx.work.WorkInfo.State.RUNNING }
        }

        private suspend fun workStates(context: Context): List<androidx.work.WorkInfo.State> {
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
            return workInfos.map { it.state }
        }

        suspend fun reconcile(context: Context) {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutoCheckInEntryPoint::class.java
            )
            val prefs = entryPoint.preferences().preferences.first()
            if (prefs.autoCheckInEnabled) {
                schedule(
                    context,
                    prefs.autoCheckInHour,
                    prefs.autoCheckInMinute,
                    providerMetas = entryPoint.providers().map { it.meta },
                    diagnostics = entryPoint.diagnostics()
                )
            } else {
                cancel(context, diagnostics = entryPoint.diagnostics())
            }
        }

        suspend fun reconcileIfStale(
            context: Context,
            now: ZonedDateTime = ZonedDateTime.now()
        ) {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutoCheckInEntryPoint::class.java
            )
            val preferences = entryPoint.preferences().preferences.first()
            val diagnosticsStore = entryPoint.diagnostics()
            if (preferences.autoCheckInEnabled) {
                schedule(
                    context,
                    preferences.autoCheckInHour,
                    preferences.autoCheckInMinute,
                    now = now,
                    providerMetas = entryPoint.providers().map { it.meta },
                    diagnostics = diagnosticsStore
                )
            } else {
                cancel(context, diagnosticsStore)
            }
        }

        /** Queues behind the currently running worker without replacing it. */
        internal suspend fun scheduleAfterRun(
            context: Context,
            hour: Int,
            minute: Int,
            now: ZonedDateTime = ZonedDateTime.now(),
            providerMetas: List<ProviderMeta> = emptyList(),
            diagnostics: AutoCheckInDiagnosticsStore? = null,
            chooseJitterOffsetMinutes: () -> Int = { Random.nextInt(-5, 6) }
        ) {
            val diagnosticsStore = diagnostics ?: diagnosticsStore(context)
            val zones = providerMetas.map { it.businessZone }.toSet()
            val baseTarget = AutoCheckInSchedule.scheduledDateTime(
                now.toLocalDate().plusDays(1), hour, minute, now, zones
            )
            val target = java.time.Instant.ofEpochMilli(
                diagnosticsStore.getOrCreateDailyTarget(
                    baseTarget.toLocalDate(), now.toLocalDate(), baseTarget.toInstant().toEpochMilli(),
                    chooseJitterOffsetMinutes
                )
            ).atZone(now.zone)
            val request = OneTimeWorkRequestBuilder<AutoCheckInWorker>()
                .setInputData(workDataOf(DAILY_SCHEDULED_INPUT to true))
                .setInitialDelay(
                    Duration.between(now, target).toMillis().coerceAtLeast(0),
                    TimeUnit.MILLISECONDS
                )
                .build()
            val enqueueOperation = WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            try {
                awaitOperation(enqueueOperation)
                diagnosticsStore.recordPlannedNext(target.toInstant().toEpochMilli())
                AutoCheckInAlarm.schedule(context, target.toInstant().toEpochMilli())
            } catch (cancellation: CancellationException) {
                WorkManager.getInstance(context).cancelWorkById(request.id)
                throw cancellation
            } catch (failure: Exception) {
                WorkManager.getInstance(context).cancelWorkById(request.id)
                throw failure
            }
        }

        internal fun nextRunDelayMillis(hour: Int, minute: Int, now: ZonedDateTime, businessZones: Set<ZoneId> = emptySet()): Long =
            AutoCheckInSchedule.nextRunDelayMillis(hour, minute, now, businessZones)

        internal fun nextScheduledDateTime(hour: Int, minute: Int, now: ZonedDateTime, businessZones: Set<ZoneId> = emptySet()): ZonedDateTime {
            return AutoCheckInSchedule.nextScheduledDateTime(hour, minute, now, businessZones)
        }

        private suspend fun diagnosticsStore(context: Context): AutoCheckInDiagnosticsStore =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutoCheckInEntryPoint::class.java
            ).diagnostics()

        private suspend fun awaitOperation(operation: androidx.work.Operation) {
            val future = operation.result
            suspendCancellableCoroutine<Unit> { continuation ->
                future.addListener({
                    try {
                        future.get()
                        continuation.resume(Unit)
                    } catch (cancellation: java.util.concurrent.CancellationException) {
                        continuation.cancel(cancellation)
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                }, java.util.concurrent.Executor { it.run() })
            }
        }
    }

}

internal fun isDailyScheduled(inputData: Data): Boolean =
    inputData.getBoolean("daily_scheduled", false)

internal fun shouldPostAutoCheckInResult(
    dailyScheduled: Boolean,
    execution: AutoCheckInExecutionResult
): Boolean = dailyScheduled && execution is AutoCheckInExecutionResult.Completed

internal fun shouldDeferScheduleReplacement(
    states: List<androidx.work.WorkInfo.State>,
    plannedNextEpochMillis: Long? = null,
    desiredEpochMillis: Long? = null,
    nowEpochMillis: Long = System.currentTimeMillis(),
    replacePendingWork: Boolean = false
): Boolean {
    if (states.any { it == androidx.work.WorkInfo.State.RUNNING }) return true
    if (replacePendingWork || states.none { !it.isFinished }) return false
    // Unknown or overdue work may already represent the catch-up attempt.
    // Keep it; replace only a known future request whose target changed.
    return plannedNextEpochMillis == null ||
        plannedNextEpochMillis <= nowEpochMillis ||
        plannedNextEpochMillis == desiredEpochMillis
}

internal data class DailyScheduleDecision(
    val workAt: ZonedDateTime,
    val alarmAt: ZonedDateTime,
    val catchUp: Boolean
)

internal fun decideDailySchedule(
    hour: Int,
    minute: Int,
    now: ZonedDateTime,
    businessZones: Set<ZoneId>,
    diagnostics: AutoCheckInDiagnostics,
    effectiveTargets: Map<LocalDate, ZonedDateTime> = emptyMap()
): DailyScheduleDecision {
    val todayBase = AutoCheckInSchedule.scheduledDateTime(
        now.toLocalDate(), hour, minute, now, businessZones
    )
    val today = effectiveTargets[now.toLocalDate()] ?: todayBase
    val hasAttemptToday = listOf(diagnostics.lastStartEpochMillis, diagnostics.lastFinishEpochMillis)
        .filterNotNull()
        .any { epochMillis ->
            java.time.Instant.ofEpochMilli(epochMillis).atZone(now.zone).toLocalDate() == today.toLocalDate()
        }
    val nextDate = if (today.isAfter(now)) now.toLocalDate() else now.toLocalDate().plusDays(1)
    val next = effectiveTargets[nextDate] ?: AutoCheckInSchedule.scheduledDateTime(
        nextDate, hour, minute, now, businessZones
    )
    val catchUp = !today.isAfter(now) && !hasAttemptToday
    return if (catchUp) DailyScheduleDecision(today, next, true)
    else DailyScheduleDecision(next, next, false)
}

internal fun shouldRunAutoCheckIn(preferences: UserPreferences): Boolean =
    preferences.autoCheckInEnabled

internal suspend fun selectExecutableProviders(
    enabledIds: Set<String>,
    providers: List<CheckInProvider>,
    credentials: CredentialStore,
    authHealthStore: AuthHealthStore = com.checky.app.domain.NoOpAuthHealthStore
): List<CheckInProvider> {
    val taygedoPreflightByOwner = mutableMapOf<String, Boolean>()
    return providers.filter { provider ->
        if (provider.meta.id !in enabledIds) {
            false
        } else {
            val health = ProviderConnectionGate.health(provider, credentials, authHealthStore)
            when {
                provider is TaygedoProvider &&
                    (health == com.checky.app.domain.AuthHealth.VALID ||
                        health == com.checky.app.domain.AuthHealth.EXPIRED) -> {
                    val owner = provider.credentialOwnerId
                    if (owner !in taygedoPreflightByOwner) {
                        taygedoPreflightByOwner[owner] = provider.recoverExpiredSession(authHealthStore)
                    }
                    taygedoPreflightByOwner.getValue(owner)
                }
                health == com.checky.app.domain.AuthHealth.VALID -> true
                else -> false
            }
        }
    }
}

internal sealed interface AutoCheckInExecutionResult {
    data object Disabled : AutoCheckInExecutionResult
    data object NoEligibleProvider : AutoCheckInExecutionResult
    data class Completed(
        val summary: CheckInSummary,
        val expiredServiceNames: List<String>
    ) : AutoCheckInExecutionResult
    data object FailedInternal : AutoCheckInExecutionResult
}

/** Runs the existing provider selection and batch use case without changing its gates. */
internal suspend fun executeAutoCheckIn(
    preferences: suspend () -> UserPreferences,
    services: suspend () -> List<ServiceSnapshot>,
    providers: List<CheckInProvider>,
    credentials: CredentialStore,
    authHealthStore: AuthHealthStore,
    runBatch: suspend (List<CheckInProvider>) -> CheckInAllProgress
): AutoCheckInExecutionResult {
    val currentPreferences = preferences()
    if (!shouldRunAutoCheckIn(currentPreferences)) return AutoCheckInExecutionResult.Disabled

    val currentServices = services()
    LegacyAuthHealthMigration.seedIfNeeded(
        providers,
        currentServices,
        credentials,
        authHealthStore
    )
    val enabledIds = currentServices.filter { it.isEnabled }.map { it.serviceId }.toSet()
    val executableProviders = selectExecutableProviders(
        enabledIds,
        providers,
        credentials,
        authHealthStore
    )
    if (executableProviders.isEmpty()) return AutoCheckInExecutionResult.NoEligibleProvider

    val finished = runBatch(executableProviders) as? CheckInAllProgress.Finished
        ?: error("Check-in batch did not finish")
    return AutoCheckInExecutionResult.Completed(
        summary = finished.summary,
        expiredServiceNames = expiredServiceNames(finished.states.values)
    )
}

/** Records only daily-worker lifecycle state; manual/widget runs take the block directly. */
internal suspend fun runWithDailyDiagnostics(
    dailyScheduled: Boolean,
    diagnostics: AutoCheckInDiagnosticsStore?,
    nowMillis: () -> Long = { System.currentTimeMillis() },
    block: suspend () -> AutoCheckInExecutionResult
): AutoCheckInExecutionResult {
    if (!dailyScheduled) return block()

    val store = requireNotNull(diagnostics) { "Daily diagnostics store is required" }
    val startEpochMillis = nowMillis()
    val localDay = Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    store.recordDailyStart(localDay, startEpochMillis)
    val result = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        AutoCheckInExecutionResult.FailedInternal
    }
    val outcome = when (result) {
        AutoCheckInExecutionResult.Disabled -> AutoCheckInDiagnosticOutcome.SKIPPED_DISABLED
        AutoCheckInExecutionResult.NoEligibleProvider ->
            AutoCheckInDiagnosticOutcome.SKIPPED_NO_ELIGIBLE_PROVIDER
        is AutoCheckInExecutionResult.Completed -> AutoCheckInDiagnosticOutcome.COMPLETED
        AutoCheckInExecutionResult.FailedInternal -> AutoCheckInDiagnosticOutcome.FAILED_INTERNAL
    }
    store.recordDailyTerminal(
        outcome = outcome,
        finishEpochMillis = nowMillis(),
        summary = (result as? AutoCheckInExecutionResult.Completed)?.summary
    )
    return result
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
    fun diagnostics(): AutoCheckInDiagnosticsStore
}
