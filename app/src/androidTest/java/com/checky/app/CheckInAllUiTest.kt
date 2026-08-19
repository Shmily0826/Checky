package com.checky.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI flow for the main user path.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CheckInAllUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun checkInAllRunsProvidersAndShowsSummary() {
        // Skip onboarding if it is shown on this install.
        if (composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Get started").performClick()
        }

        composeRule.onNodeWithText("Check in all").performClick()

        // The run finishes with a summary banner ("Got it" dismisses it).
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Got it").assertExists()
    }
}
