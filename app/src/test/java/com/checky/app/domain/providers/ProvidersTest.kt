package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeMockScenarioStore
import com.checky.app.domain.MockScenario
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInOutcome
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

    @Test
    fun miyousheCommunityMapsAuthVerificationAlreadyAndMalformedResponses() {
        assertTrue(mapMiyousheCommunityFields(-100, "", null) is CheckInOutcome.AuthenticationExpired)
        assertTrue(mapMiyousheCommunityFields(1034, "验证", null) is CheckInOutcome.ActionRequired)
        assertTrue(mapMiyousheCommunityFields(0, "", 0) is CheckInOutcome.AlreadyCompleted)
        assertTrue(mapMiyousheCommunityFields(0, "", null) is CheckInOutcome.PermanentFailure)
        assertTrue(mapMiyousheCommunityFields(null, "", null) is CheckInOutcome.PermanentFailure)
    }

    @Test
    fun taygedoMapsAuthAndVerificationFailuresWithoutTreatingThemAsTemporary() {
        assertTrue(mapTaygedoFailure(401, "").diagnosticCode == "TAYGEDO_AUTH_EXPIRED")
        assertTrue(mapTaygedoFailure(403, "需要风控验证").diagnosticCode == "TAYGEDO_VERIFICATION")
        assertTrue(mapTaygedoFailure(503, "服务器忙").diagnosticCode == "TAYGEDO_503")
    }

    @Test
    fun malformedTaygedoNteStateFailsClosed() {
        assertEquals(null, parseNteSignStateFields(false, null, 2))
        assertEquals(false, parseNteSignStateFields(false, 2, 2)?.todaySigned)
    }

    @Test
    fun onlyBrowseTaskCodeIsAllowedForAutomaticTaskCompletion() {
        assertTrue(isAllowedBrowseTaskCode("browse_post_c"))
        assertTrue(!isAllowedBrowseTaskCode("like_post_c"))
        assertTrue(!isAllowedBrowseTaskCode("share"))
        assertTrue(!isAllowedBrowseTaskCode("follow"))
    }

    @Test
    fun communitySuccessfulFirstSignCallsSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(0, "", hasExpectedData = true, exp = 1)
        }

        assertEquals(listOf("1", "2"), called)
        assertEquals(2, calls.size)
    }

    @Test
    fun communityAlreadyCompletedFirstSignCallsSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(1008, "已签到", hasExpectedData = false)
        }

        assertEquals(listOf("1", "2"), called)
        assertEquals(2, calls.size)
        assertEquals(CommunitySignDisposition.ALREADY_COMPLETED, calls.first().classification)
    }

    @Test
    fun communityMalformedFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(0, "", hasExpectedData = false)
        }

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.MALFORMED, calls.single().classification)
    }

    @Test
    fun communityUnknownSuccessSchemaDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(0, "", hasExpectedData = false, exp = 1)
        }

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.MALFORMED, calls.single().classification)
    }

    @Test
    fun communityVerificationFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(403, "需要风控验证", hasExpectedData = false)
        }

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.VERIFICATION_REQUIRED, calls.single().classification)
    }

    @Test
    fun communityAuthExpiredFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence { communityId ->
            called += communityId
            CommunitySignResponse(401, "", hasExpectedData = false)
        }

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.AUTH_EXPIRED, calls.single().classification)
    }

    @Test
    fun miyousheProvidersOwnIndependentCredentialEntries() = runTest {
        val credentials = FakeCredentialStore()
        credentials.save(MiyousheProvider.META.id, "game-session")
        credentials.save(MiyousheCommunityProvider.META.id, "community-session")

        credentials.delete(MiyousheCommunityProvider.META.id)

        assertTrue(credentials.has(MiyousheProvider.META.id))
        assertTrue(!credentials.has(MiyousheCommunityProvider.META.id))
    }
}
