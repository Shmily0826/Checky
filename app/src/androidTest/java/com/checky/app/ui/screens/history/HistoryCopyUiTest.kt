package com.checky.app.ui.screens.history

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.checky.app.data.model.CheckInRecord
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.ui.theme.CheckyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryCopyUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun copyWritesOnlyAfterTheHistoryCopyActionIsTapped() {
        val fakeCode = "FAKE-7.1-CODE"
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        composeRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("existing", "FAKE-BEFORE-COPY"))
        }
        composeRule.setContent {
            CheckyTheme {
                HistoryContent(
                    navController = rememberNavController(),
                    records = listOf(
                        CheckInRecord(
                            id = "test-record",
                            serviceId = "taptap_game_sign",
                            serviceName = "TapTap",
                            status = CheckInStatus.SUCCESS,
                            reward = Reward.empty(),
                            message = "Gift claim confirmed",
                            diagnosticCode = "TAPTAP_GIFT_CLAIM_CONFIRMED",
                            durationMs = 1L,
                            timestamp = 1L,
                            localCopyText = fakeCode
                        )
                    ),
                    onClear = {}
                )
            }
        }

        composeRule.onNodeWithText(fakeCode).assertExists()
        composeRule.runOnIdle {
            assertEquals("FAKE-BEFORE-COPY", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        composeRule.onNodeWithTag("history_copy_test-record").performClick()
        composeRule.runOnIdle {
            assertEquals(fakeCode, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
