package com.checky.app.domain.providers

import com.checky.app.domain.MockScenario
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the developer scenario-override mapping every mock provider uses.
 */
class MockOutcomesTest {

    private val success = CheckInOutcome.Success("ok", "SUCCESS", Reward(RewardType.POINTS, 1))
    private val already = CheckInOutcome.AlreadyCompleted("again", "ALREADY")

    @Test
    fun defaultAndSuccessScenariosUseTheProviderNaturalOutcome() {
        assertEquals(
            success,
            outcomeForScenario(MockScenario.DEFAULT, { success }, { already })
        )
        assertEquals(
            success,
            outcomeForScenario(MockScenario.SUCCESS, { success }, { already })
        )
        assertEquals(
            already,
            outcomeForScenario(MockScenario.ALREADY_COMPLETED, { success }, { already })
        )
    }

    @Test
    fun failureScenariosMapToTheirStandardOutcomes() {
        val auth = outcomeForScenario(MockScenario.AUTH_EXPIRED, { success }, { already })
        val network = outcomeForScenario(MockScenario.NETWORK_FAILURE, { success }, { already })
        val action = outcomeForScenario(MockScenario.ACTION_REQUIRED, { success }, { already })

        assertEquals("AUTH_EXPIRED", auth.diagnosticCode)
        assertEquals("TEMP_NETWORK", network.diagnosticCode)
        assertEquals("ACTION_REQUIRED", action.diagnosticCode)
    }

    @Test
    fun standardOutcomeMessagesAreUserFacing() {
        val messages = listOf(
            authExpiredOutcome(),
            timeoutOutcome(),
            rateLimitOutcome(),
            maintenanceOutcome(),
            actionRequiredOutcome()
        ).map { it.userMessage }

        messages.forEach { message ->
            assertTrue("message must not be blank", message.isNotBlank())
            assertFalse("must not leak exception text: $message", message.contains("Exception"))
            assertFalse("must not leak stack frames: $message", message.contains("at "))
        }
    }
}
