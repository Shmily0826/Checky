package com.checky.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI smoke flow: onboarding → dashboard → the add-service catalog
 * lists the real providers. Deliberately does NOT tap "Check in all" — a test
 * run must never trigger real account mutations.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CheckInAllUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** Polls with a real-time deadline; CI software-rendered emulators start slowly. */
    private fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return true
            Thread.sleep(500)
        }
        return false
    }

    @Test
    fun onboardingHomeAndCatalogRender() {
        // Wait for the first screen to compose; on a cold, software-rendered
        // CI emulator the first frame can take a while.
        val firstScreenAppeared = waitForText("Get started", 60_000) ||
            waitForText("Check in all", 1_000)
        if (!firstScreenAppeared) {
            throw AssertionError(
                "First screen did not compose. Semantics tree:\n" +
                    composeRule.onRoot().printToString()
            )
        }

        // Skip onboarding if it is shown on this install.
        if (composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Get started").performClick()
        }

        if (!waitForText("Check in all", 30_000)) {
            throw AssertionError(
                "Dashboard did not compose. Semantics tree:\n" +
                    composeRule.onRoot().printToString()
            )
        }
        composeRule.onNodeWithText("Check in all").assertExists()

        // The catalog lists the real providers with their risk labels.
        composeRule.onNodeWithContentDescription("Add service").performClick()
        if (!waitForText("Add services", 30_000)) {
            throw AssertionError(
                "Add services screen did not compose. Semantics tree:\n" +
                    composeRule.onRoot().printToString()
            )
        }
        composeRule.onNodeWithText("米游社签到（原神实验版）").assertExists()
        composeRule.onNodeWithText("米游社讨论区签到").assertExists()
        composeRule.onNodeWithText("异环游戏签到").assertExists()
        composeRule.onNodeWithText("塔吉多社区签到").assertExists()
    }
}
