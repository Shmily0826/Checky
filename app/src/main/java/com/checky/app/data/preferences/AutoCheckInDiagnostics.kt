package com.checky.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.checky.app.domain.model.CheckInSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Stable, non-sensitive terminal states for the daily worker. */
enum class AutoCheckInDiagnosticOutcome {
    SKIPPED_DISABLED,
    SKIPPED_NO_ELIGIBLE_PROVIDER,
    COMPLETED,
    FAILED_INTERNAL
}

/**
 * Bounded local metadata for troubleshooting automatic check-in.
 *
 * This model deliberately has no provider names, messages, URLs, exceptions,
 * credentials, headers, or response data.
 */
data class AutoCheckInDiagnostics(
    val plannedNextEpochMillis: Long? = null,
    val lastStartEpochMillis: Long? = null,
    val lastOutcome: AutoCheckInDiagnosticOutcome? = null,
    val lastFinishEpochMillis: Long? = null,
    val completedTotal: Int? = null,
    val completedSucceeded: Int? = null,
    val completedAlreadyCheckedIn: Int? = null,
    val completedFailed: Int? = null,
    val completedAttention: Int? = null
)

interface AutoCheckInDiagnosticsStore {
    val diagnostics: Flow<AutoCheckInDiagnostics>

    suspend fun recordPlannedNext(epochMillis: Long)
    suspend fun clearPlannedNext()
    suspend fun recordDailyStart(epochMillis: Long)
    suspend fun recordDailyTerminal(
        outcome: AutoCheckInDiagnosticOutcome,
        finishEpochMillis: Long,
        summary: CheckInSummary? = null
    )
}

@Singleton
class DataStoreAutoCheckInDiagnosticsStore @Inject constructor(
    @param:Named("userPrefs") private val dataStore: DataStore<Preferences>
) : AutoCheckInDiagnosticsStore {
    override val diagnostics: Flow<AutoCheckInDiagnostics> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            AutoCheckInDiagnostics(
                plannedNextEpochMillis = prefs[KEY_PLANNED_NEXT],
                lastStartEpochMillis = prefs[KEY_LAST_START],
                lastOutcome = prefs[KEY_LAST_OUTCOME]?.let { raw ->
                    runCatching { AutoCheckInDiagnosticOutcome.valueOf(raw) }.getOrNull()
                },
                lastFinishEpochMillis = prefs[KEY_LAST_FINISH],
                completedTotal = boundedCount(prefs[KEY_COMPLETED_TOTAL]),
                completedSucceeded = boundedCount(prefs[KEY_COMPLETED_SUCCEEDED]),
                completedAlreadyCheckedIn = boundedCount(prefs[KEY_COMPLETED_ALREADY]),
                completedFailed = boundedCount(prefs[KEY_COMPLETED_FAILED]),
                completedAttention = boundedCount(prefs[KEY_COMPLETED_ATTENTION])
            )
        }

    override suspend fun recordPlannedNext(epochMillis: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_PLANNED_NEXT] = epochMillis
        }
    }

    override suspend fun clearPlannedNext() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PLANNED_NEXT)
        }
    }

    override suspend fun recordDailyStart(epochMillis: Long) {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PLANNED_NEXT)
            prefs[KEY_LAST_START] = epochMillis
            prefs.remove(KEY_LAST_OUTCOME)
            prefs.remove(KEY_LAST_FINISH)
            clearCounts(prefs)
        }
    }

    override suspend fun recordDailyTerminal(
        outcome: AutoCheckInDiagnosticOutcome,
        finishEpochMillis: Long,
        summary: CheckInSummary?
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_OUTCOME] = outcome.name
            prefs[KEY_LAST_FINISH] = finishEpochMillis
            clearCounts(prefs)
            if (outcome == AutoCheckInDiagnosticOutcome.COMPLETED && summary != null) {
                prefs[KEY_COMPLETED_TOTAL] = summary.total.boundedCount()
                prefs[KEY_COMPLETED_SUCCEEDED] = summary.succeeded.boundedCount()
                prefs[KEY_COMPLETED_ALREADY] = summary.alreadyCheckedIn.boundedCount()
                prefs[KEY_COMPLETED_FAILED] = summary.failed.boundedCount()
                prefs[KEY_COMPLETED_ATTENTION] = summary.attention.boundedCount()
            }
        }
    }

    private fun clearCounts(prefs: MutablePreferences) {
        prefs.remove(KEY_COMPLETED_TOTAL)
        prefs.remove(KEY_COMPLETED_SUCCEEDED)
        prefs.remove(KEY_COMPLETED_ALREADY)
        prefs.remove(KEY_COMPLETED_FAILED)
        prefs.remove(KEY_COMPLETED_ATTENTION)
    }

    private fun Int.boundedCount(): Int = coerceIn(0, MAX_COUNT)

    private fun boundedCount(value: Int?): Int? = value?.coerceIn(0, MAX_COUNT)

    companion object {
        private const val MAX_COUNT = 1_000
        private val KEY_PLANNED_NEXT = longPreferencesKey("auto_diag_planned_next")
        private val KEY_LAST_START = longPreferencesKey("auto_diag_last_start")
        private val KEY_LAST_OUTCOME = stringPreferencesKey("auto_diag_last_outcome")
        private val KEY_LAST_FINISH = longPreferencesKey("auto_diag_last_finish")
        private val KEY_COMPLETED_TOTAL = intPreferencesKey("auto_diag_completed_total")
        private val KEY_COMPLETED_SUCCEEDED = intPreferencesKey("auto_diag_completed_succeeded")
        private val KEY_COMPLETED_ALREADY = intPreferencesKey("auto_diag_completed_already")
        private val KEY_COMPLETED_FAILED = intPreferencesKey("auto_diag_completed_failed")
        private val KEY_COMPLETED_ATTENTION = intPreferencesKey("auto_diag_completed_attention")
    }
}
