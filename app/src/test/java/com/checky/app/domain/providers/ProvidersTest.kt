package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.RewardType
import kotlinx.coroutines.flow.filterIsInstance
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

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
    fun nteRewardUsesCompletedSignInCountNotCalendarDay() {
        assertEquals(0, nteRewardIndexForSignedDays(signedDays = 1))
        assertEquals(1, nteRewardIndexForSignedDays(signedDays = 2))
        assertEquals(0, nteRewardIndexForSignedDays(signedDays = 0))
    }

    @Test
    fun onlyBrowseTaskCodeIsAllowedForAutomaticTaskCompletion() {
        assertTrue(isAllowedBrowseTaskCode("browse_post_c"))
        assertTrue(!isAllowedBrowseTaskCode("like_post_c"))
        assertTrue(!isAllowedBrowseTaskCode("share"))
        assertTrue(!isAllowedBrowseTaskCode("follow"))
    }

    @Test
    fun communityTaskParserPrefersTaskKeyOverCode() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"taskKey":"browse_post_c","code":"legacy_browse","limitTimes":3,"completeTimes":1}"""))
        }
        assertEquals(2, parseCommunityTaskState(arr)?.browseRemaining)
    }

    @Test
    fun communityTaskParserFallsBackToCodeWhenTaskKeyBlank() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"code":"browse_post_c","limitTimes":5,"completeTimes":2}"""))
        }
        assertEquals(3, parseCommunityTaskState(arr)?.browseRemaining)
    }

    @Test
    fun communityTaskParserReturnsNullWhenListMissing() {
        assertEquals(null, parseCommunityTaskState(null))
    }

    @Test
    fun communityTaskParserTreatsUnknownTaskTypeAsNoAction() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"taskKey":"follow","limitTimes":10,"completeTimes":0}"""))
        }
        // follow is not in the automatic allowlist and must never be counted/executed.
        assertEquals(0, parseCommunityTaskState(arr)?.browseRemaining)
    }

    @Test
    fun communityTaskParserReadsLikeAndShareCountersWithoutExecuting() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"taskKey":"browse_post_c","limitTimes":2,"completeTimes":2}"""))
            put(JSONObject("""{"taskKey":"like_post_c","limitTimes":4,"completeTimes":1}"""))
            put(JSONObject("""{"taskKey":"share","limitTimes":1,"completeTimes":0}"""))
        }
        val state = parseCommunityTaskState(arr)
        assertEquals(0, state?.browseRemaining)   // already complete
        assertEquals(3, state?.likeRemaining)     // read-only counter
        assertEquals(1, state?.shareRemaining)    // read-only counter
    }

    @Test
    fun communityAlreadyCompletedRecognizesExtendedPhrases() {
        assertTrue(
            classifyCommunitySignResponse(CommunitySignResponse(0, "今日已完成", hasExpectedData = true))
                == CommunitySignDisposition.ALREADY_COMPLETED
        )
        assertTrue(
            classifyCommunitySignResponse(CommunitySignResponse(0, "今日已领取", hasExpectedData = true))
                == CommunitySignDisposition.ALREADY_COMPLETED
        )
        assertTrue(
            classifyCommunitySignResponse(CommunitySignResponse(0, "今天已经签到啦", hasExpectedData = true))
                == CommunitySignDisposition.ALREADY_COMPLETED
        )
    }

    @Test
    fun communityFailureAppendsSafeServerDetail() {
        val outcome = CommunitySignDisposition.FAILURE.toOutcome("异环社区版区签到失败。", "今天已经签到")
        assertTrue(outcome is CheckInOutcome.TemporaryFailure)
        assertTrue(outcome.userMessage.contains("今天已经签到"))
        assertEquals("TAYGEDO_COMMUNITY_FAILURE", outcome.diagnosticCode)
    }

    @Test
    fun unknownCommunityPreflightDoesNotInvokeAnySignInPost() = runTest {
        var mutationCalls = 0
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNKNOWN },
            signIn = {
                mutationCalls++
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )

        assertEquals(0, mutationCalls)
        assertEquals(1, calls.size)
        assertEquals(CommunitySignDisposition.PREFLIGHT_UNKNOWN, calls.single().classification)
        val outcome = calls.single().classification.toOutcome("签到失败")
        assertTrue(outcome is CheckInOutcome.PermanentFailure)
        assertEquals("TAYGEDO_COMMUNITY_STATE_UNKNOWN", outcome.diagnosticCode)
    }

    @Test
    fun signedCommunityPreflightSkipsSignInPost() = runTest {
        var mutationCalls = 0
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.SIGNED },
            signIn = {
                mutationCalls++
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )

        assertEquals(0, mutationCalls)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.classification == CommunitySignDisposition.ALREADY_COMPLETED })
    }

    @Test
    fun unsignedCommunityPreflightAllowsOnlyThatSignInPost() = runTest {
        val mutationCalls = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { communityId ->
                if (communityId == "1") CommunitySignState.UNSIGNED else CommunitySignState.SIGNED
            },
            signIn = { communityId ->
                mutationCalls += communityId
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )

        assertEquals(listOf("1"), mutationCalls)
        assertEquals(2, calls.size)
        assertEquals(CommunitySignDisposition.SUCCESS, calls.first().classification)
        assertEquals(CommunitySignDisposition.ALREADY_COMPLETED, calls.last().classification)
    }

    @Test
    fun unknownSecondCommunityPreflightStopsAfterFirstSuccessfulSignIn() = runTest {
        val mutationCalls = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { communityId ->
                if (communityId == "1") CommunitySignState.UNSIGNED else CommunitySignState.UNKNOWN
            },
            signIn = { communityId ->
                mutationCalls += communityId
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )

        assertEquals(listOf("1"), mutationCalls)
        assertEquals(2, calls.size)
        assertEquals(CommunitySignDisposition.SUCCESS, calls.first().classification)
        assertEquals(CommunitySignDisposition.PREFLIGHT_UNKNOWN, calls.last().classification)
    }

    // ===== getSignState read-only parser (fail-closed) =====

    private fun signStateResult(code: Int, data: JSONObject?): TaygedoClient.ApiResult =
        TaygedoClient.ApiResult(code, data, "", data ?: JSONObject())

    @Test
    fun getSignStateNonZeroCodeIsUnknown() {
        assertEquals(
            CommunitySignState.UNKNOWN,
            signStateResult(401, JSONObject("""{"isSign":false}""")).toCommunitySignState()
        )
    }

    @Test
    fun getSignStateMissingDataIsUnknown() {
        assertEquals(
            CommunitySignState.UNKNOWN,
            signStateResult(0, null).toCommunitySignState()
        )
    }

    @Test
    fun getSignStateRecognizesSignedViaIsSign() {
        assertEquals(
            CommunitySignState.SIGNED,
            signStateResult(0, JSONObject("""{"isSign":true}""")).toCommunitySignState()
        )
    }

    @Test
    fun getSignStateRecognizesUnsignedViaSigned() {
        assertEquals(
            CommunitySignState.UNSIGNED,
            signStateResult(0, JSONObject("""{"signed":false}""")).toCommunitySignState()
        )
    }

    @Test
    fun getSignStateRecognizesSignedViaSignStateInt() {
        assertEquals(
            CommunitySignState.SIGNED,
            signStateResult(0, JSONObject("""{"signState":1}""")).toCommunitySignState()
        )
    }

    @Test
    fun getSignStateUnrecognizedSchemaIsUnknown() {
        // No recognised signed/unsigned flag -> must not drive a mutation.
        assertEquals(
            CommunitySignState.UNKNOWN,
            signStateResult(0, JSONObject("""{"foo":"bar","list":[]}""")).toCommunitySignState()
        )
        assertEquals(
            CommunitySignState.UNKNOWN,
            signStateResult(0, JSONObject("""{"status":2}""")).toCommunitySignState()
        )
        assertEquals(
            CommunitySignState.UNKNOWN,
            signStateResult(0, JSONObject("""{"isSign":true,"signed":false}""")).toCommunitySignState()
        )
    }

    @Test
    fun communitySuccessfulFirstSignCallsSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(0, "", hasExpectedData = true, exp = 1)
            }
        )

        assertEquals(listOf("1", "2"), called)
        assertEquals(2, calls.size)
    }

    @Test
    fun communityAlreadyCompletedFirstSignCallsSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(1008, "已签到", hasExpectedData = false)
            }
        )

        assertEquals(listOf("1", "2"), called)
        assertEquals(2, calls.size)
        assertEquals(CommunitySignDisposition.ALREADY_COMPLETED, calls.first().classification)
    }

    @Test
    fun communityMalformedFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(0, "", hasExpectedData = false)
            }
        )

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.MALFORMED, calls.single().classification)
    }

    @Test
    fun communityUnknownSuccessSchemaDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(0, "", hasExpectedData = false, exp = 1)
            }
        )

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.MALFORMED, calls.single().classification)
    }

    @Test
    fun communityVerificationFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(403, "需要风控验证", hasExpectedData = false)
            }
        )

        assertEquals(listOf("1"), called)
        assertEquals(CommunitySignDisposition.VERIFICATION_REQUIRED, calls.single().classification)
    }

    @Test
    fun communityAuthExpiredFirstSignDoesNotCallSecondSign() = runTest {
        val called = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                called += communityId
                CommunitySignResponse(401, "", hasExpectedData = false)
            }
        )

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
