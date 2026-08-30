package com.checky.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun onboardingHomeAndCatalogRender() {
        // Wait for the first screen to compose: on a cold emulator start the
        // onboarding check below would otherwise race the first frame and
        // silently skip the onboarding flow.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Check in all").fetchSemanticsNodes().isNotEmpty()
        }

        // Skip onboarding if it is shown on this install.
        if (composeRule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Get started").performClick()
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Check in all").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Check in all").assertExists()

        // The catalog lists the real providers with their risk labels.
        composeRule.onNodeWithContentDescription("Add service").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Add services").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("米游社签到（原神实验版）").assertExists()
        composeRule.onNodeWithText("米游社讨论区签到").assertExists()
        composeRule.onNodeWithText("异环游戏签到").assertExists()
        composeRule.onNodeWithText("塔吉多社区签到").assertExists()
    }
}
