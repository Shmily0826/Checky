package com.checky.app.domain.background

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Public, local device signals relevant to WorkManager background execution. */
data class BackgroundReliabilitySignals(
    val isXiaomiFamily: Boolean,
    val backgroundRestricted: Boolean?,
    val standbyBucket: Int?
)

enum class BackgroundReliabilityStatus {
    CRITICAL_RESTRICTED,
    MAY_BE_DEFERRED,
    XIAOMI_MANUAL_REVIEW,
    HEALTHY,
    UNKNOWN
}

data class BackgroundReliabilityReport(
    val signals: BackgroundReliabilitySignals,
    val status: BackgroundReliabilityStatus
) {
    /**
     * Show diagnostics only when the user has opted into the relevant feature.
     * UNKNOWN is intentionally included so unavailable signals never look like
     * a silently healthy background state.
     */
    fun shouldShowInSettings(autoCheckInEnabled: Boolean): Boolean =
        autoCheckInEnabled &&
            (signals.isXiaomiFamily || status != BackgroundReliabilityStatus.HEALTHY)
}

/** Pure classification for deterministic JVM tests and conservative UI wording. */
object BackgroundReliabilityClassifier {
    // UsageStatsManager.STANDBY_BUCKET_ACTIVE is 10 (API 28+).
    const val ACTIVE_BUCKET = 10

    fun classify(signals: BackgroundReliabilitySignals): BackgroundReliabilityReport {
        val status = when {
            signals.backgroundRestricted == true ->
                BackgroundReliabilityStatus.CRITICAL_RESTRICTED

            // A missing restriction signal must never be treated as healthy.
            signals.backgroundRestricted != false ->
                BackgroundReliabilityStatus.UNKNOWN

            signals.standbyBucket != null && signals.standbyBucket > ACTIVE_BUCKET ->
                BackgroundReliabilityStatus.MAY_BE_DEFERRED

            signals.standbyBucket == null ->
                BackgroundReliabilityStatus.UNKNOWN

            signals.isXiaomiFamily ->
                BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW

            else -> BackgroundReliabilityStatus.HEALTHY
        }
        return BackgroundReliabilityReport(signals = signals, status = status)
    }

    fun isXiaomiFamily(manufacturer: String?): Boolean {
        val manufacturerName = manufacturer?.trim()?.lowercase()
        // The normalized manufacturer is the sole signal. Do not infer
        // HyperOS-specific behavior from a variable OEM brand string.
        // Black Shark is deliberately excluded because its devices may run
        // JOYUI rather than HyperOS.
        return manufacturerName == "xiaomi"
    }
}

/** Reads only calling-app public Android state; it does not request usage history. */
class BackgroundReliabilityReader @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    fun read(): BackgroundReliabilitySignals {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                activityManager?.isBackgroundRestricted
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }

        val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                usageStatsManager?.appStandbyBucket
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }

        return BackgroundReliabilitySignals(
            isXiaomiFamily = BackgroundReliabilityClassifier.isXiaomiFamily(
                manufacturer = Build.MANUFACTURER
            ),
            backgroundRestricted = backgroundRestricted,
            standbyBucket = standbyBucket
        )
    }
}
