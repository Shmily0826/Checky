package com.checky.app.domain.providers

import com.checky.app.accessibility.TAPTAP_PACKAGE
import com.checky.app.accessibility.TapTapAccessibilityButton
import com.checky.app.accessibility.TapTapAccessibilitySnapshot
import com.checky.app.accessibility.TapTapAutomationCoordinator
import com.checky.app.accessibility.TapTapPageDisposition
import com.checky.app.accessibility.TapTapPageMatcher
import com.checky.app.accessibility.TapTapRunResult
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
        assertEquals(TapTapProvider.DEFAULT_EVENT_URL, TapTapProvider.resolveConfiguredEventUrl(null))
        assertEquals(
            TapTapProvider.DEFAULT_EVENT_URL,
            TapTapProvider.resolveConfiguredEventUrl(" ${TapTapProvider.DEFAULT_EVENT_URL} ")
        )
        assertNull(TapTapProvider.resolveConfiguredEventUrl("https://www.taptap.cn/events/game-sign/"))
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
        var returnRequests = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin { returnRequests++ })
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
            assertEquals(1, returnRequests)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun alreadyCompletedRequestsReturnOnceAndNonterminalStatesDoNot() = runBlocking {
        var returnRequests = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin { returnRequests++ })
        try {
            TapTapAutomationCoordinator.observe(run, snapshot(buttons = emptyList())) { error("click not expected") }
            assertEquals(0, returnRequests)

            val completed = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天"),
                buttons = listOf(button("今日已签到", enabled = false, clickable = false))
            )
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }
            TapTapAutomationCoordinator.observe(run, completed) { error("click not expected") }

            assertEquals(TapTapRunResult.ALREADY_COMPLETED, run.completion.await())
            assertEquals(1, returnRequests)
        } finally {
            TapTapAutomationCoordinator.clear(run)
        }
    }

    @Test
    fun unknownDoesNotRequestReturnToChecky() = runBlocking {
        var returnRequests = 0
        val run = requireNotNull(TapTapAutomationCoordinator.begin { returnRequests++ })
        try {
            val unknown = snapshot(
                texts = listOf("签到领好礼", "已累计签到1天", "verification required"),
                buttons = emptyList()
            )
            TapTapAutomationCoordinator.observe(run, unknown) { error("click not expected") }

            assertEquals(TapTapRunResult.UNKNOWN, run.completion.await())
            assertEquals(0, returnRequests)
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
