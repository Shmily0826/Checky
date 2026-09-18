package com.checky.app.accessibility

import android.content.Context
import com.checky.app.BuildConfig
import com.checky.app.domain.model.CheckInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class TapTapLabTraceSnapshot(
    val runId: String? = null,
    val startedAtMs: Long? = null,
    val lastStage: String = "IDLE",
    val gestureDispatched: Boolean = false,
    val finalResult: String? = null,
    val diagnosticCode: String? = null,
    val durationMs: Long? = null,
    val autoReturnAttempted: Boolean = false,
    val autoReturnLaunchAccepted: Boolean? = null,
    val autoReturnSucceeded: Boolean? = null
)

/** Debug-only, bounded, local trace for one TapTap Lab run. */
object TapTapLabTrace {
    private const val FILE_NAME = "taptap_lab_trace.txt"
    private val lock = Any()
    private val _snapshot = MutableStateFlow(TapTapLabTraceSnapshot())
    private var traceFile: File? = null
    private var foregroundReturnPending = false

    val snapshot: StateFlow<TapTapLabTraceSnapshot> = _snapshot.asStateFlow()

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            traceFile = File(context.filesDir, FILE_NAME)
        }
    }

    fun start(nowMs: Long = System.currentTimeMillis(), runId: String = "taptap-$nowMs") {
        synchronized(lock) {
            foregroundReturnPending = false
            updateLocked(
                TapTapLabTraceSnapshot(
                    runId = runId,
                    startedAtMs = nowMs,
                    lastStage = "STARTED"
                )
            )
        }
    }

    fun stage(value: String) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            updateLocked(_snapshot.value.copy(lastStage = value))
        }
    }

    fun gestureDispatched(dispatched: Boolean) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            updateLocked(_snapshot.value.copy(gestureDispatched = dispatched))
        }
    }

    fun autoReturnAttempted() {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            updateLocked(_snapshot.value.copy(autoReturnAttempted = true))
        }
    }

    fun expectForegroundReturn() {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            foregroundReturnPending = true
            updateLocked(_snapshot.value.copy(autoReturnSucceeded = null))
        }
    }

    fun autoReturnLaunchAccepted(accepted: Boolean) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (!accepted) foregroundReturnPending = false
            updateLocked(
                _snapshot.value.copy(
                    autoReturnLaunchAccepted = accepted,
                    autoReturnSucceeded = if (accepted) {
                        _snapshot.value.autoReturnSucceeded
                    } else {
                        false
                    }
                )
            )
        }
    }

    fun acknowledgeForegroundReturn(): Boolean {
        if (!BuildConfig.DEBUG) return false
        synchronized(lock) {
            if (!foregroundReturnPending) return false
            foregroundReturnPending = false
            updateLocked(_snapshot.value.copy(autoReturnSucceeded = true))
            return true
        }
    }

    fun recordResult(result: CheckInResult, nowMs: Long = System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            val startedAt = _snapshot.value.startedAtMs
            val duration = result.durationMs.takeIf { it > 0L }
                ?: startedAt?.let { (nowMs - it).coerceAtLeast(0L) }
            updateLocked(
                _snapshot.value.copy(
                    lastStage = stageFor(result.diagnosticCode),
                    finalResult = result.status.name,
                    diagnosticCode = result.diagnosticCode,
                    durationMs = duration
                )
            )
        }
    }

    private fun stageFor(diagnosticCode: String): String = when (diagnosticCode) {
        "TAPTAP_SUCCESS" -> "SUCCESS"
        "TAPTAP_ALREADY" -> "ALREADY_COMPLETED"
        "TAPTAP_GESTURE_FAILED" -> "GESTURE_FAILED"
        "TAPTAP_PAGE_NOT_READY" -> "PAGE_NOT_READY"
        "TAPTAP_CONFIRM_TIMEOUT" -> "CONFIRMATION_TIMEOUT"
        "TAPTAP_UNSAFE_OR_UNKNOWN_PAGE" -> "UNSAFE_OR_UNKNOWN_PAGE"
        else -> "TERMINAL"
    }

    private fun updateLocked(snapshot: TapTapLabTraceSnapshot) {
        _snapshot.value = snapshot
        traceFile?.writeText(snapshot.toTraceText())
    }

    private fun TapTapLabTraceSnapshot.toTraceText(): String = buildString {
        appendLine("runId=${runId.orEmpty()}")
        appendLine("startedAtMs=${startedAtMs ?: ""}")
        appendLine("lastStage=$lastStage")
        appendLine("gestureDispatched=$gestureDispatched")
        appendLine("finalResult=${finalResult.orEmpty()}")
        appendLine("diagnosticCode=${diagnosticCode.orEmpty()}")
        appendLine("durationMs=${durationMs ?: ""}")
        appendLine("autoReturnAttempted=$autoReturnAttempted")
        appendLine("autoReturnLaunchAccepted=${autoReturnLaunchAccepted ?: ""}")
        appendLine("autoReturnSucceeded=${autoReturnSucceeded ?: ""}")
    }
}
