package com.checky.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInOutcomeTest {

    @Test
    fun outcomeMapsToTheExpectedStatus() {
        assertEquals(CheckInStatus.SUCCESS, CheckInOutcome.Success("ok", "SUCCESS", Reward(RewardType.POINTS, 1)).status)
        assertEquals(
            CheckInStatus.ALREADY_CHECKED_IN,
            CheckInOutcome.AlreadyCompleted("done", "ALREADY").status
        )
        assertEquals(
            CheckInStatus.LOGIN_EXPIRED,
            CheckInOutcome.AuthenticationExpired("expired").status
        )
        assertEquals(
            CheckInStatus.USER_ACTION_REQUIRED,
            CheckInOutcome.ActionRequired("attention").status
        )
        assertEquals(CheckInStatus.FAILED, CheckInOutcome.TemporaryFailure("later").status)
        assertEquals(CheckInStatus.FAILED, CheckInOutcome.PermanentFailure("never").status)
        assertEquals(CheckInStatus.FAILED, CheckInOutcome.Unsupported("nope").status)
    }

    @Test
    fun successAndAlreadyArePositive() {
        assertTrue(
            CheckInOutcome.Success("ok", "SUCCESS", Reward(RewardType.POINTS, 20)).isPositive
        )
        assertTrue(CheckInOutcome.AlreadyCompleted("done", "ALREADY").isPositive)
        assertFalse(CheckInOutcome.AuthenticationExpired("expired").isPositive)
        assertFalse(CheckInOutcome.TemporaryFailure("later").isPositive)
    }

    @Test
    fun errorOutcomesCarryRetryRecommendations() {
        assertEquals(
            RetryRecommendation.RECONNECT,
            CheckInOutcome.AuthenticationExpired("expired").retryRecommendation
        )
        assertEquals(
            RetryRecommendation.RETRY_LATER,
            CheckInOutcome.TemporaryFailure("later").retryRecommendation
        )
        assertEquals(
            RetryRecommendation.RETRY_NOW,
            CheckInOutcome.ActionRequired("attention").retryRecommendation
        )
        assertEquals(
            RetryRecommendation.NONE,
            CheckInOutcome.PermanentFailure("never").retryRecommendation
        )
    }

    @Test
    fun translatedErrorsAreUserFacing() {
        // The provider layer produces the translated, user-safe messages.
        val outcomes = listOf(
            com.checky.app.domain.providers.mapTaygedoFailure(503, "server busy"),
            com.checky.app.domain.providers.mapMiyousheCommunityFields(
                retcode = 999, message = "rejected", points = null
            )
        )
        outcomes.forEach { outcome ->
            assertTrue(outcome.userMessage.isNotBlank())
            assertTrue(!outcome.userMessage.contains("Exception"))
            assertTrue(outcome.diagnosticCode.isNotBlank())
        }
    }

    @Test
    fun membershipDayRewardLabelsNicely() {
        assertEquals("+1 membership day", Reward(RewardType.MEMBERSHIP_DAY, 1).label)
        assertEquals("+3 membership days", Reward(RewardType.MEMBERSHIP_DAY, 3).label)
        assertTrue(Reward.empty().isEmpty)
    }
}
