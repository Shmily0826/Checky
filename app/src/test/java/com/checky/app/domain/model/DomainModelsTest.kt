package com.checky.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the pure domain-model logic that the UI relies on:
 * status classification, reward presentation, result delegation and
 * summary arithmetic.
 */
class DomainModelsTest {

    // --- CheckInStatus ---

    @Test
    fun pendingAndRunningAreNotTerminal() {
        assertFalse(CheckInStatus.PENDING.isTerminal)
        assertFalse(CheckInStatus.RUNNING.isTerminal)
    }

    @Test
    fun everyOtherStatusIsTerminal() {
        val terminal = listOf(
            CheckInStatus.SUCCESS,
            CheckInStatus.ALREADY_CHECKED_IN,
            CheckInStatus.LOGIN_EXPIRED,
            CheckInStatus.FAILED,
            CheckInStatus.USER_ACTION_REQUIRED
        )
        terminal.forEach { assertTrue("$it should be terminal", it.isTerminal) }
    }

    @Test
    fun requiresUserActionCoversFailedLoginAndActionStates() {
        assertTrue(CheckInStatus.LOGIN_EXPIRED.requiresUserAction)
        assertTrue(CheckInStatus.USER_ACTION_REQUIRED.requiresUserAction)
        assertTrue(CheckInStatus.FAILED.requiresUserAction)
        assertFalse(CheckInStatus.PENDING.requiresUserAction)
        assertFalse(CheckInStatus.RUNNING.requiresUserAction)
        assertFalse(CheckInStatus.SUCCESS.requiresUserAction)
        assertFalse(CheckInStatus.ALREADY_CHECKED_IN.requiresUserAction)
    }

    @Test
    fun onlySuccessAndAlreadyCheckedInArePositive() {
        assertTrue(CheckInStatus.SUCCESS.isPositive)
        assertTrue(CheckInStatus.ALREADY_CHECKED_IN.isPositive)
        CheckInStatus.entries
            .filter { it != CheckInStatus.SUCCESS && it != CheckInStatus.ALREADY_CHECKED_IN }
            .forEach { assertFalse("$it must not be positive", it.isPositive) }
    }

    // --- Reward ---

    @Test
    fun rewardLabelsAreUserFacing() {
        assertEquals("+20 pts", Reward(RewardType.POINTS, 20).label)
        assertEquals("+5 XP", Reward(RewardType.EXPERIENCE, 5).label)
        assertEquals("+1 membership day", Reward(RewardType.MEMBERSHIP_DAY, 1).label)
        assertEquals("+3 membership days", Reward(RewardType.MEMBERSHIP_DAY, 3).label)
        assertEquals("", Reward(RewardType.NONE, 0).label)
    }

    @Test
    fun rewardIsEmptyForNoneOrZeroAmount() {
        assertTrue(Reward.empty().isEmpty)
        assertTrue(Reward(RewardType.NONE, 99).isEmpty)
        assertTrue(Reward(RewardType.POINTS, 0).isEmpty)
        assertFalse(Reward(RewardType.POINTS, 1).isEmpty)
        assertFalse(Reward(RewardType.EXPERIENCE, 5).isEmpty)
        assertFalse(Reward(RewardType.MEMBERSHIP_DAY, 2).isEmpty)
    }

    // --- CheckInResult delegation ---

    @Test
    fun resultDelegatesStatusRewardMessageCodeAndRetryToOutcome() {
        val outcome = CheckInOutcome.Success(
            userMessage = "Done",
            diagnosticCode = "SUCCESS",
            reward = Reward(RewardType.POINTS, 20)
        )
        val result = CheckInResult(
            serviceId = "gamepass",
            serviceName = "GamePass Daily",
            outcome = outcome,
            timestamp = 1_000L
        )

        assertEquals(CheckInStatus.SUCCESS, result.status)
        assertEquals(Reward(RewardType.POINTS, 20), result.reward)
        assertEquals("Done", result.message)
        assertEquals("SUCCESS", result.diagnosticCode)
        assertEquals(RetryRecommendation.NONE, result.retryRecommendation)
        assertEquals(0L, result.durationMs)
    }

    @Test
    fun expiredResultRecommendsReconnect() {
        val result = CheckInResult(
            serviceId = "cloudbox",
            serviceName = "CloudBox",
            outcome = CheckInOutcome.AuthenticationExpired("expired"),
            timestamp = 1_000L
        )
        assertEquals(CheckInStatus.LOGIN_EXPIRED, result.status)
        assertEquals(RetryRecommendation.RECONNECT, result.retryRecommendation)
    }

    // --- CheckInSummary ---

    @Test
    fun summaryHandledCountsEveryClassifiedOutcome() {
        val summary = CheckInSummary(
            total = 6,
            succeeded = 2,
            alreadyCheckedIn = 1,
            failed = 1,
            attention = 2,
            totalPoints = 40,
            totalXp = 5,
            durationMs = 500L
        )
        assertEquals(6, summary.handled)
        assertEquals(0, summary.totalDays)
    }

    // --- ServiceCheckInState / CheckInAllProgress ---

    @Test
    fun serviceStateIsEnabledByDefault() {
        val state = ServiceCheckInState(
            meta = testMeta(),
            status = CheckInStatus.PENDING,
            progress = 0f,
            message = "Waiting…",
            reward = null,
            timestamp = null
        )
        assertTrue(state.isEnabled)
    }

    @Test
    fun progressEventsCarryPerServiceStateMaps() {
        val states = mapOf("gamepass" to serviceState(CheckInStatus.SUCCESS))
        val running = CheckInAllProgress.Running(states)
        val finished = CheckInAllProgress.Finished(states, CheckInSummary(1, 1, 0, 0, 0, 0, 0, durationMs = 10L))

        assertEquals(states, running.states)
        assertEquals(states, finished.states)
        assertEquals(1, finished.summary.total)
    }

    private fun serviceState(status: CheckInStatus) = ServiceCheckInState(
        meta = testMeta(),
        status = status,
        progress = 1f,
        message = "ok",
        reward = null,
        timestamp = 1L
    )

    private fun testMeta() = ProviderMeta(
        id = "test",
        displayName = "Test",
        description = "",
        category = "Test",
        iconKey = "star",
        accentColor = 0xFF000000,
        isEnabledByDefault = true
    )
}
