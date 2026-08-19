package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeMockScenarioStore
import com.checky.app.domain.MockScenario
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.RewardType
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    @Test
    fun gamePassReturnsSuccessWithPoints() = runTest {
        val events = GamePassDailyProvider(FakeMockScenarioStore()).checkIn().toList()
        val done = events.filterIsInstance<CheckInEvent.Done>().single()
        assertEquals(CheckInStatus.SUCCESS, done.result.status)
        assertEquals(RewardType.POINTS, done.result.reward.type)
        assertEquals(20, done.result.reward.amount)
        assertEquals("SUCCESS", done.result.diagnosticCode)
    }

    @Test
    fun studyClubReturnsAlreadyCheckedInWithXp() = runTest {
        val events = StudyClubProvider(FakeMockScenarioStore()).checkIn().toList()
        val done = events.filterIsInstance<CheckInEvent.Done>().single()
        assertEquals(CheckInStatus.ALREADY_CHECKED_IN, done.result.status)
        assertEquals(RewardType.EXPERIENCE, done.result.reward.type)
        assertEquals(5, done.result.reward.amount)
    }

    @Test
    fun cloudBoxExpiredWhenNotConnected() = runTest {
        val provider = CloudBoxProvider(FakeCredentialStore(), FakeMockScenarioStore())
        val done = provider.checkIn().filterIsInstance<CheckInEvent.Done>().single()
        assertEquals(CheckInStatus.LOGIN_EXPIRED, done.result.status)
        assertEquals("AUTH_EXPIRED", done.result.diagnosticCode)
    }

    @Test
    fun cloudBoxSucceedsWithMembershipDayAfterConnect() = runTest {
        val credentials = FakeCredentialStore()
        credentials.save("cloudbox", "fake-token-1234")
        val provider = CloudBoxProvider(credentials, FakeMockScenarioStore())
        val done = provider.checkIn().filterIsInstance<CheckInEvent.Done>().single()
        assertEquals(CheckInStatus.SUCCESS, done.result.status)
        assertEquals(RewardType.MEMBERSHIP_DAY, done.result.reward.type)
        assertEquals(1, done.result.reward.amount)
    }

    @Test
    fun cloudBoxDisconnectClearsToken() = runTest {
        val credentials = FakeCredentialStore()
        credentials.save("cloudbox", "fake-token-1234")
        val provider = CloudBoxProvider(credentials, FakeMockScenarioStore())
        assertTrue(provider.supportsDisconnect())
        provider.disconnect()
        assertEquals(false, credentials.has("cloudbox"))
    }

    @Test
    fun cloudBoxValidatesCredentialsBeforeSaving() = runTest {
        val provider = CloudBoxProvider(FakeCredentialStore(), FakeMockScenarioStore())
        assertTrue(provider.validateCredentials("short") is CredentialValidation.Invalid)
        assertTrue(provider.validateCredentials("") is CredentialValidation.Invalid)
        assertTrue(provider.validateCredentials("a-valid-token-1234") is CredentialValidation.Valid)
    }

    @Test
    fun gamePassHonorsScenarioOverrides() = runTest {
        val scenarios = FakeMockScenarioStore()
        val provider = GamePassDailyProvider(scenarios)

        scenarios.setScenario(MockScenario.AUTH_EXPIRED)
        assertEquals(
            CheckInStatus.LOGIN_EXPIRED,
            provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result.status
        )

        scenarios.setScenario(MockScenario.NETWORK_FAILURE)
        assertEquals(
            CheckInStatus.FAILED,
            provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result.status
        )

        scenarios.setScenario(MockScenario.ALREADY_COMPLETED)
        assertEquals(
            CheckInStatus.ALREADY_CHECKED_IN,
            provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result.status
        )

        scenarios.setScenario(MockScenario.ACTION_REQUIRED)
        assertEquals(
            CheckInStatus.USER_ACTION_REQUIRED,
            provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result.status
        )
    }

    @Test
    fun providersEmitProgressThenDone() = runTest {
        val events = GamePassDailyProvider(FakeMockScenarioStore()).checkIn().toList()
        val progressCount = events.count { it is CheckInEvent.Progress }
        assertEquals(3, progressCount)
        assertTrue(events.last() is CheckInEvent.Done)
    }

    @Test
    fun outcomesNeverCarryRawErrors() = runTest {
        val scenarios = FakeMockScenarioStore()
        scenarios.setScenario(MockScenario.NETWORK_FAILURE)
        val done = GamePassDailyProvider(scenarios)
            .checkIn()
            .filterIsInstance<CheckInEvent.Done>()
            .single()
        // User-facing message, no stack trace, no exception text.
        assertTrue(done.result.message.isNotBlank())
        assertTrue(!done.result.message.contains("Exception"))
        assertEquals("TEMP_NETWORK", done.result.diagnosticCode)
    }
}
