package com.checky.app.domain

import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.model.ServiceCheckInState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.TimeSource

/**
 * Orchestrates a "Check in all" run across every enabled provider.
 *
 * Guarantees:
 *  - providers run with limited parallelism (or strictly sequentially)
 *  - a failure in one provider does NOT stop the others (continue-on-failure)
 *  - duplicate execution is prevented via an [AtomicBoolean] guard
 *  - cancellation propagates cleanly (no fake failure results are saved)
 *  - every terminal result is persisted through [CheckInRepository]
 *  - a single [CheckInAllProgress.Finished] with a summary is emitted at the end
 */
class CheckInAllUseCase @Inject constructor(
    private val repository: CheckInRepository
) {
    private val running = AtomicBoolean(false)

    /** Maximum providers executed at once in parallel mode. */
    private val maxParallelism = 3

    operator fun invoke(
        providers: List<CheckInProvider>,
        parallel: Boolean = true
    ): Flow<CheckInAllProgress> = flow {
        if (!running.compareAndSet(false, true)) {
            // Already running — ignore the duplicate trigger.
            return@flow
        }
        try {
            kotlinx.coroutines.coroutineScope {
                val overallMark = TimeSource.Monotonic.markNow()
                val initial = providers.associate { provider ->
                    provider.meta.id to ServiceCheckInState(
                        meta = provider.meta,
                        status = CheckInStatus.PENDING,
                        progress = 0f,
                        message = "Waiting…",
                        reward = null,
                        timestamp = null
                    )
                }
                val states = MutableStateFlow(initial)
                val lock = Mutex()

                suspend fun patch(
                    id: String,
                    update: ServiceCheckInState.() -> ServiceCheckInState
                ) {
                    lock.withLock {
                        val current = states.value[id] ?: return@withLock
                        states.value = states.value + (id to current.update())
                    }
                }

                suspend fun runOne(provider: CheckInProvider) {
                    val startMark = TimeSource.Monotonic.markNow()
                    patch(provider.meta.id) {
                        copy(status = CheckInStatus.RUNNING, progress = 0f, message = "Starting…")
                    }
                    try {
                        provider.checkIn().collect { event ->
                            when (event) {
                                is CheckInEvent.Progress -> patch(provider.meta.id) {
                                    copy(
                                        status = CheckInStatus.RUNNING,
                                        progress = event.fraction,
                                        message = event.message
                                    )
                                }
                                is CheckInEvent.Done -> {
                                    val result = event.result.copy(
                                        durationMs = startMark.elapsedNow().inWholeMilliseconds
                                    )
                                    patch(provider.meta.id) {
                                        copy(
                                            status = result.status,
                                            progress = 1f,
                                            message = result.message,
                                            reward = result.reward,
                                            timestamp = result.timestamp
                                        )
                                    }
                                    repository.saveResult(result)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val outcome = CheckInOutcome.TemporaryFailure(
                            userMessage = "The service did not respond. Try again later."
                        )
                        val failed = CheckInResult(
                            serviceId = provider.meta.id,
                            serviceName = provider.meta.displayName,
                            outcome = outcome,
                            timestamp = System.currentTimeMillis(),
                            durationMs = startMark.elapsedNow().inWholeMilliseconds
                        )
                        patch(provider.meta.id) {
                            copy(
                                status = CheckInStatus.FAILED,
                                progress = 1f,
                                message = failed.message,
                                reward = failed.reward,
                                timestamp = failed.timestamp
                            )
                        }
                        repository.saveResult(failed)
                    }
                }

                if (parallel) {
                    val semaphore = Semaphore(maxParallelism)
                    val jobs = providers.map { provider ->
                        async(start = CoroutineStart.LAZY) {
                            semaphore.withPermit { runOne(provider) }
                        }
                    }
                    jobs.forEach { it.start() }
                    while (jobs.any { !it.isCompleted }) {
                        emit(CheckInAllProgress.Running(states.value))
                        delay(40)
                    }
                    jobs.awaitAll()
                } else {
                    for (provider in providers) {
                        runOne(provider)
                        emit(CheckInAllProgress.Running(states.value))
                    }
                }

                val durationMs = overallMark.elapsedNow().inWholeMilliseconds
                emit(CheckInAllProgress.Finished(states.value, buildSummary(states.value, durationMs)))
            }
        } finally {
            running.set(false)
        }
    }

    private fun buildSummary(
        states: Map<String, ServiceCheckInState>,
        durationMs: Long
    ): CheckInSummary {
        var succeeded = 0
        var already = 0
        var failed = 0
        var attention = 0
        var pts = 0
        var xp = 0
        var days = 0
        for (state in states.values) {
            when (state.status) {
                CheckInStatus.SUCCESS -> succeeded++
                CheckInStatus.ALREADY_CHECKED_IN -> already++
                CheckInStatus.FAILED -> failed++
                CheckInStatus.LOGIN_EXPIRED, CheckInStatus.USER_ACTION_REQUIRED -> attention++
                else -> Unit
            }
            val reward = state.reward
            if (reward != null && !reward.isEmpty) {
                when (reward.type) {
                    RewardType.POINTS -> pts += reward.amount
                    RewardType.EXPERIENCE -> xp += reward.amount
                    RewardType.MEMBERSHIP_DAY -> days += reward.amount
                    RewardType.NONE -> Unit
                }
            }
        }
        return CheckInSummary(
            total = states.size,
            succeeded = succeeded,
            alreadyCheckedIn = already,
            failed = failed,
            attention = attention,
            totalPoints = pts,
            totalXp = xp,
            totalDays = days,
            durationMs = durationMs
        )
    }
}
