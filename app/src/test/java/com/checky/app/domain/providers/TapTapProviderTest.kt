package com.checky.app.domain.providers

import com.checky.app.accessibility.TAPTAP_PACKAGE
import com.checky.app.accessibility.TapTapAccessibilityButton
import com.checky.app.accessibility.TapTapAccessibilitySnapshot
import com.checky.app.accessibility.TapTapAutomationCoordinator
import com.checky.app.accessibility.TapTapPageDisposition
import com.checky.app.accessibility.TapTapPageMatcher
import com.checky.app.accessibility.TapTapRunResult
import com.checky.app.accessibility.TapTapRunState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapTapProviderTest {
    @Test
    fun deepLinkEncodesTheHttpsEventAsTheDispatcherQueryParameter() {
        val deepLink = TapTapProvider.buildDeepLink(TapTapProvider.DEFAULT_EVENT_URL)
        assertEquals("taptap", deepLink.scheme)
        assertEquals("taptap.com", deepLink.host)
        assertEquals("/to", deepLink.path)
        assertEquals(TapTapProvider.DEFAULT_EVENT_URL, deepLink.getQueryParameter("url"))
        assertTrue(deepLink.toString().contains("url=https%3A%2F%2Fwww.taptap.cn%2Fevents%2Fgame-sign%2F7fva1f6e"))
    }

    @Test
    fun eventUrlValidationRequiresExactTapTapGameSignPath() {
        assertTrue(TapTapProvider.isValidEventUrl(TapTapProvider.DEFAULT_EVENT_URL))
        assertTrue(TapTapProvider.isValidEventUrl(" ${TapTapProvider.DEFAULT_EVENT_URL} "))
        assertFalse(TapTapProvider.isValidEventUrl("https://www.taptap.cn/events/game-sign/7fva1f6e?x=1"))
        assertFalse(TapTapProvider.isValidEventUrl("https://www.taptap.cn/events/other/7fva1f6e"))
        assertFalse(TapTapProvider.isValidEventUrl("https://example.com/events/game-sign/7fva1f6e"))
        assertFalse(TapTapProvider.isValidEventUrl("taptap://taptap.com/to?url=https%3A%2F%2Fwww.taptap.cn%2Fevents%2Fgame-sign%2F7fva1f6e"))
    }

    @Test
    fun missingConfiguredUrlUsesVerifiedDefaultButInvalidConfiguredUrlFailsClosed() {
        assertEquals(
            TapTapProvider.DEFAULT_EVENT_URL,
            TapTapProvider.resolveConfiguredEventUrl(null, isDebugBuild = false)
        )
        assertEquals(
            TapTapProvider.DEFAULT_EVENT_URL,
            TapTapProvider.resolveConfiguredEventUrl(
                " ${TapTapProvider.DEFAULT_EVENT_URL} ",
                isDebugBuild = false
            )
        )
        assertNull(
            TapTapProvider.resolveConfiguredEventUrl(
                "https://www.taptap.cn/events/game-sign/",
                isDebugBuild = false
            )
        )
    }

    @Test
    fun debugTestOverrideIsValidatedAndCannotAffectReleaseOrNormalMode() {
        val override = "https://www.taptap.cn/events/game-sign/abc123"
        val normal = "https://www.taptap.cn/events/game-sign/normal"

        assertEquals(
            override,
            TapTapProvider.resolveConfiguredEventUrl(
                configuredEventUrl = normal,
                debugTestEventUrl = " $override ",
                isDebugBuild = true
            )
        )
        assertNull(
            TapTapProvider.resolveConfiguredEventUrl(
                configuredEventUrl = normal,
                debugTestEventUrl = " ",
                isDebugBuild = true
            )
        )
        assertEquals(
            normal,
            TapTapProvider.resolveConfiguredEventUrl(
                configuredEventUrl = normal,
                debugTestEventUrl = override,
                isDebugBuild = false
            )
        )
        assertEquals(
            TapTapProvider.DEFAULT_EVENT_URL,
            TapTapProvider.resolveConfiguredEventUrl(
                configuredEventUrl = null,
                debugTestEventUrl = override,
                isDebugBuild = false
            )
        )
        assertNull(
            TapTapProvider.resolveConfiguredEventUrl(
                configuredEventUrl = normal,
                debugTestEventUrl = null,
                isDebugBuild = true
            )
        )
    }

    @Test
    fun diagnosticMappingSeparatesGestureUnknownPageAndTimeout() {
        assertEquals("TAPTAP_GESTURE_FAILED", TapTapProvider.diagnosticCodeFor(TapTapRunResult.GESTURE_FAILED))
        assertEquals("TAPTAP_PAGE_NOT_READY", TapTapProvider.diagnosticCodeFor(TapTapRunResult.PAGE_NOT_READY))
        assertEquals("TAPTAP_UNSAFE_OR_UNKNOWN_PAGE", TapTapProvider.diagnosticCodeFor(TapTapRunResult.UNKNOWN))
        assertEquals("TAPTAP_CONFIRM_TIMEOUT", TapTapProvider.diagnosticCodeFor(TapTapRunResult.CONFIRMATION_TIMEOUT))
        assertEquals("TAPTAP_CONFIRM_TIMEOUT", TapTapProvider.CONFIRM_TIMEOUT_DIAGNOSTIC)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deepLinkRejectsUnexpectedEventHosts() {
        TapTapProvider.buildDeepLink("https://example.com/event")
    }

    @Test
    fun readyRequiresTheExpectedPageAndOneActionableButton() {
        assertEquals(TapTapPageDisposition.READY, TapTapPageMatcher.classify(snapshot(
            buttons = listOf(button("立即签到", enabled = true, clickable = true))
        )))
        assertEquals(TapTapPageDisposition.WAITING, TapTapPageMatcher.classify(snapshot(
            buttons = emptyList()
        )))
        assertEquals(TapTapPageDisposition.NOT_TARGET, TapTapPageMatcher.classify(
            snapshot(texts = listOf("立即签到"), buttons = emptyList())
        ))
        assertEquals(TapTapPageDisposition.UNKNOWN, TapTapPageMatcher.classify(snapshot(
            buttons = listOf(
                button("立即签到", enabled = true, clickable = true),
                button("立即签到", enabled = true, clickable = true)
            )
        )))
    }

    @Test
    fun completedAndSuccessNeedTheDisabledButtonAndStrongMarkers() {
        val completed = snapshot(
            texts = listOf("签到领好礼", "已累计签到1天"),
            buttons = listOf(button("今日已签到", enabled = false, clickable = false))
        )
        assertEquals(TapTapPageDisposition.ALREADY_COMPLETED, TapTapPageMatcher.classify(completed))
        assertEquals(
            TapTapPageDisposition.SUCCESS,
            TapTapPageMatcher.classify(completed.copy(texts = completed.texts + "签到成功，恭喜获得"))
        )
    }

    @Test
    fun coordinatorConsumesAtMostOneClickAndCompletesOnSuccess() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            var clicks = 0
            val ready = snapshot(buttons = listOf(button("立即签到", enabled = true, clickable = true)))
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }
            assertEquals(1, clicks)

            val success = ready.copy(
                texts = ready.texts + "签到成功，恭喜获得",
                buttons = listOf(button("今日已签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, success) { false }
            assertEquals(TapTapRunResult.SUCCESS, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun failedGestureCompletesWithoutAllowingAnotherGesture() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true)))
            TapTapAutomationCoordinator.observe(run, ready) {
                clicks++
                false
            }
            TapTapAutomationCoordinator.observe(run, ready) {
                clicks++
                true
            }

            assertEquals(1, clicks)
            assertEquals(TapTapRunResult.GESTURE_FAILED, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun transientReadyThenAlreadyCompletedDoesNotGesture() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true)))
            TapTapAutomationCoordinator.observe(run, ready, allowClick = false) {
                clicks++
                true
            }
            val completed = snapshot(buttons = listOf(button("\u4eca\u65e5\u5df2\u7b7e\u5230", enabled = false, clickable = false)))
            TapTapAutomationCoordinator.observe(run, completed, allowClick = false) {
                error("transient READY must not gesture")
            }

            assertEquals(0, clicks)
            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun persistentReadyGesturesOnlyAtFinalSettleAndOnlyOnce() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true)))
            TapTapAutomationCoordinator.observe(run, ready, allowClick = false) {
                clicks++
                true
            }
            TapTapAutomationCoordinator.observe(run, ready, allowClick = true) {
                clicks++
                true
            }
            TapTapAutomationCoordinator.observe(run, ready, allowClick = true) {
                clicks++
                true
            }

            assertEquals(1, clicks)
            assertFalse(run.completion.isCompleted)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun postClickAlreadyCompletedCompletesOnce() = runBlocking {
        var clicks = 0
        var callbacks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin { callbacks++ })
        try {
            val ready = snapshot(
                texts = listOf("签到领好礼", "已累计签到0天"),
                buttons = listOf(button("立即签到", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }

            val completed = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("今日已签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }

            assertEquals(1, clicks)
            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
            assertEquals(1, callbacks)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun transientMixedPostClickTreeStaysConfirmingUntilSuccess() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(
                buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }
            assertEquals(TapTapRunState.CONFIRMING, run.state)

            val transient = ready.copy(
                texts = ready.texts + "loading",
                buttons = listOf(button("\u4eca\u65e5\u5df2\u7b7e\u5230", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, transient) { error("confirmation must not click again") }
            assertFalse(run.completion.isCompleted)
            assertEquals(TapTapRunState.CONFIRMING, run.state)

            val success = transient.copy(
                texts = transient.texts + "\u7b7e\u5230\u6210\u529f\uff0c\u606d\u559c\u83b7\u5f97",
                buttons = listOf(button("\u4eca\u65e5\u5df2\u7b7e\u5230", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, success) { error("success must not click") }
            assertEquals(1, clicks)
            assertEquals(TapTapRunResult.SUCCESS, run.completion.await())
            assertEquals(TapTapRunState.SUCCESS, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun postClickLoginOrVerificationIsTerminalAndFailClosed() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(
                buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, ready) { true }
            TapTapAutomationCoordinator.observe(run, ready.copy(texts = ready.texts + "captcha")) {
                error("unsafe page must not click")
            }

            assertEquals(TapTapRunResult.UNKNOWN, run.completion.await())
            assertEquals(TapTapRunState.UNSAFE_OR_UNKNOWN_PAGE, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun confirmationTimeoutCannotDispatchASecondGesture() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val ready = snapshot(
                buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }
            TapTapAutomationCoordinator.observe(run, ready.copy(texts = ready.texts + "loading")) {
                error("confirmation must not click again")
            }
            TapTapAutomationCoordinator.finish(run, TapTapRunResult.CONFIRMATION_TIMEOUT)
            TapTapAutomationCoordinator.observe(run, ready) { clicks++; true }

            assertEquals(1, clicks)
            assertEquals(TapTapRunResult.CONFIRMATION_TIMEOUT, run.completion.await())
            assertEquals(TapTapRunState.CONFIRMATION_TIMEOUT, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun transientInitialNotTargetStaysReadyUntilLaterReady() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            TapTapAutomationCoordinator.observe(
                run,
                TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, emptyList(), emptyList())
            ) { clicks++; true }
            assertEquals(TapTapRunState.READY, run.state)
            assertFalse(run.completion.isCompleted)

            TapTapAutomationCoordinator.observe(
                run,
                snapshot(buttons = listOf(button("\u7acb\u5373\u7b7e\u5230", enabled = true, clickable = true)))
            ) { clicks++; true }

            assertEquals(1, clicks)
            assertEquals(TapTapRunState.CONFIRMING, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun unsafeInitialPageTerminatesBeforeReadinessTimeoutWithoutGesture() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            TapTapAutomationCoordinator.observe(
                run,
                TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, listOf("captcha"), emptyList())
            ) { clicks++; true }

            assertEquals(TapTapRunResult.UNKNOWN, run.completion.await())
            assertEquals(0, clicks)
            assertEquals(TapTapRunState.UNSAFE_OR_UNKNOWN_PAGE, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun readinessTimeoutEndsWithoutGesture() = runBlocking {
        var clicks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            TapTapAutomationCoordinator.observe(
                run,
                TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, emptyList(), emptyList())
            ) { clicks++; true }
            TapTapAutomationCoordinator.finish(run, TapTapRunResult.PAGE_NOT_READY)

            assertEquals(TapTapRunResult.PAGE_NOT_READY, run.completion.await())
            assertEquals(0, clicks)
            assertEquals(TapTapRunState.PAGE_NOT_READY, run.state)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun ambiguousPostClickCompletionRemainsUnknown() = runBlocking {
        var callbacks = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin { callbacks++ })
        try {
            val ready = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("立即签到", enabled = true, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, ready, allowClick = false) { true }

            val ambiguousCompletion = snapshot(
                texts = ready.texts + "verification required",
                buttons = ready.buttons.map {
                    it.copy(text = "\u4eca\u65e5\u5df2\u7b7e\u5230", enabled = true, clickable = true)
                }
            )
            TapTapAutomationCoordinator.observe(run, ambiguousCompletion) {
                error("click not expected")
            }

            val completedWithoutTransition = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("今日已签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, completedWithoutTransition) {
                error("click not expected")
            }

            assertEquals(TapTapRunResult.UNKNOWN, run.completion.await())
            assertEquals(0, callbacks)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun alreadyCompletedCompletesOnceAndNonterminalStatesDoNot() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            TapTapAutomationCoordinator.observe(run, snapshot(buttons = emptyList())) { error("click not expected") }

            val completed = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("今日已签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }

            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun unknownDoesNotCompletePositively() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val unknown = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天", "verification required"),
                buttons = emptyList()
            )
            TapTapAutomationCoordinator.observe(run, unknown) { error("click not expected") }

            assertEquals(TapTapRunResult.UNKNOWN, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun incompletePageDoesNotConsumeRunBeforeAlreadyCompletedPage() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            val incomplete = snapshot(buttons = emptyList())
            TapTapAutomationCoordinator.observe(run, incomplete) { error("click not expected") }
            val pendingAfterIncomplete = !run.completion.isCompleted

            val completed = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("今日已签到", enabled = false, clickable = true))
            )
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }

            assertTrue(
                "Incomplete expected-page snapshot consumed the pending run",
                pendingAfterIncomplete
            )
            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun nonActionableReadyButtonDoesNotConsumeRunBeforeAlreadyCompletedPage() = runBlocking {
        val run = requireNotNull(TapTapAutomationCoordinator.begin())
        try {
            var clicks = 0
            val settling = snapshot(
                buttons = listOf(button("立即签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, settling) { clicks++; true }

            assertTrue(
                "Non-actionable ready snapshot consumed the pending run",
                !run.completion.isCompleted
            )

            val completed = snapshot(
                buttons = listOf(
                    button("", enabled = true, clickable = true),
                    button("", enabled = true, clickable = true),
                    button("今日已签到", enabled = false, clickable = true)
                )
            )
            TapTapAutomationCoordinator.observe(run, completed) { clicks++; true }

            assertEquals(0, clicks)
            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    private fun snapshot(
        texts: List<String> = listOf("签到领好礼", "已累计签到0天"),
        buttons: List<TapTapAccessibilityButton>
    ) = TapTapAccessibilitySnapshot(TAPTAP_PACKAGE, texts, buttons)

    private fun button(text: String, enabled: Boolean, clickable: Boolean) =
        TapTapAccessibilityButton(text, "android.widget.Button", enabled, clickable)
}
