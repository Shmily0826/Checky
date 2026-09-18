package com.checky.app.accessibility

import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTapLabTraceTest {
    @Test
    fun foregroundReturnNeedsLifecycleAcknowledgement() {
        TapTapLabTrace.start(nowMs = 1_000L, runId = "run-one")
        TapTapLabTrace.autoReturnAttempted()
        TapTapLabTrace.expectForegroundReturn()
        TapTapLabTrace.autoReturnLaunchAccepted(true)

        assertNull(TapTapLabTrace.snapshot.value.autoReturnSucceeded)
        assertTrue(TapTapLabTrace.acknowledgeForegroundReturn())
        assertTrue(TapTapLabTrace.snapshot.value.autoReturnSucceeded == true)
        assertFalse(TapTapLabTrace.acknowledgeForegroundReturn())
    }

    @Test
    fun startResetsRunAndResultRecordsTerminalEvidence() {
        TapTapLabTrace.start(nowMs = 1_000L, runId = "run-one")
        TapTapLabTrace.gestureDispatched(true)
        TapTapLabTrace.stage(TapTapRunState.CONFIRMING.name)
        TapTapLabTrace.recordResult(
            CheckInResult(
                serviceId = "taptap_game_sign",
                serviceName = "TapTap game check-in",
                outcome = CheckInOutcome.AlreadyCompleted("already", "TAPTAP_ALREADY"),
                timestamp = 2_500L
            ),
            nowMs = 2_500L
        )

        TapTapLabTrace.start(nowMs = 3_000L, runId = "run-two")
        val trace = TapTapLabTrace.snapshot.value

        assertEquals("run-two", trace.runId)
        assertEquals(3_000L, trace.startedAtMs)
        assertEquals("STARTED", trace.lastStage)
        assertFalse(trace.gestureDispatched)
        assertEquals(null, trace.finalResult)
        assertEquals(null, trace.diagnosticCode)
        assertEquals(null, trace.durationMs)
        assertFalse(trace.autoReturnAttempted)
    }
}
