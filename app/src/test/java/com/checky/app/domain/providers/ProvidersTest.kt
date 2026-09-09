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
        assertTrue(mapTaygedoFailure(401, "") is CheckInOutcome.AuthenticationExpired)
        assertTrue(mapTaygedoFailure(-401, "") is CheckInOutcome.AuthenticationExpired)
        assertTrue(mapTaygedoFailure(403, "需要风控验证") is CheckInOutcome.ActionRequired)
        assertTrue(mapTaygedoFailure(503, "服务器忙") is CheckInOutcome.TemporaryFailure)
        assertTrue(mapTaygedoFailure(0, "") is CheckInOutcome.TemporaryFailure)
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
        assertTrue(isAllowedBrowseTaskCode("browse_post_exp"))
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
    fun communityTaskParserAcceptsObservedBrowseTaskKey() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"taskKey":"browse_post_exp","limitTimes":4,"completeTimes":1}"""))
        }
        assertEquals(3, parseCommunityTaskState(arr)?.browseRemaining)
    }

    @Test
    fun communityTaskParserUsesOneDeterministicBrowseAliasWhenBothArePresent() {
        val arr = JSONArray().apply {
            put(JSONObject("""{"taskKey":"browse_post_c","limitTimes":20,"completeTimes":0}"""))
            put(JSONObject("""{"taskKey":"browse_post_exp","limitTimes":4,"completeTimes":1}"""))
        }
        assertEquals(3, parseCommunityTaskState(arr)?.browseRemaining)
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
    fun taygedoCoinStateRequiresSuccessfulResponseAndBothNonNegativeIntegers() {
        fun result(data: Any?, code: Int = 0) =
            TaygedoClient.ApiResult(code, data, "", JSONObject())

        val known = parseTaygedoCoinState(
            result(JSONObject("""{"todayCoin":12,"limitCoin":30}"""))
        )
        assertEquals(TaygedoCoinStateResult.Known(TaygedoCoinState(12, 30)), known)
        assertEquals(
            TaygedoCoinStateResult.Unknown,
            parseTaygedoCoinState(result(JSONObject("""{"todayCoin":12}""")))
        )
        assertEquals(
            TaygedoCoinStateResult.Unknown,
            parseTaygedoCoinState(result(JSONObject("""{"todayCoin":-1,"limitCoin":30}""")))
        )
        assertEquals(
            TaygedoCoinStateResult.Unknown,
            parseTaygedoCoinState(result(JSONObject("""{"todayCoin":"12","limitCoin":30}""")))
        )
        assertEquals(
            TaygedoCoinStateResult.Unknown,
            parseTaygedoCoinState(result(JSONObject("""{"todayCoin":12.5,"limitCoin":30}""")))
        )
        assertEquals(
            TaygedoCoinStateResult.Unknown,
            parseTaygedoCoinState(result(JSONObject("""{"todayCoin":12,"limitCoin":30}"""), code = 401))
        )
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

    @Test
    fun gid1SigninTaskStateParsesStrictlyAndFailsClosed() {
        fun state(data: String?) = signStateResult(0, data?.let(::JSONObject))
            .toCommunityTaskSignState()

        assertEquals(CommunitySignState.UNSIGNED, state("""{"task_list1":[{"taskKey":"signin_c","completeTimes":0,"limitTimes":1}]}"""))
        assertEquals(CommunitySignState.SIGNED, state("""{"task_list1":[{"taskKey":"signin_c","completeTimes":1,"limitTimes":1}]}"""))
        assertEquals(CommunitySignState.SIGNED, state("""{"task_list1":[{"taskKey":"signin_c","completeTimes":2,"limitTimes":1}]}"""))
        listOf(
            "{}",
            """{"task_list1":{}}""",
            """{"task_list1":[{"taskKey":"signin_c","completeTimes":"0","limitTimes":1}]}""",
            """{"task_list1":[{"taskKey":"signin_c","completeTimes":-1,"limitTimes":1}]}""",
            """{"task_list1":[{"taskKey":"signin_c","completeTimes":0,"limitTimes":0}]}""",
            """{"task_list1":[{"taskKey":"signin_c","completeTimes":0,"limitTimes":1},{"taskKey":"signin_c","completeTimes":0,"limitTimes":1}]}"""
        ).forEach { assertEquals(CommunitySignState.UNKNOWN, state(it)) }
    }

    @Test
    fun independentBbsPreflightRunsAfterUnknownId1WithoutPostingId1() = runTest {
        val mutationCalls = mutableListOf<String>()
        val calls = runCommunitySignInSequence(
            readState = { CommunitySignState.UNKNOWN },
            readIndependentBbsState = { CommunitySignState.UNSIGNED },
            signIn = { communityId ->
                mutationCalls += communityId
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )
        assertEquals(listOf("2"), mutationCalls)
        assertEquals(CommunitySignDisposition.PREFLIGHT_UNKNOWN, calls.first().classification)
        assertEquals(CommunitySignDisposition.SUCCESS, calls.last().classification)
    }

    @Test
    fun independentBbsCompleteOrUnknownDoesNotPostId2() = runTest {
        listOf(CommunitySignState.SIGNED, CommunitySignState.UNKNOWN).forEach { bbsState ->
            val mutationCalls = mutableListOf<String>()
            runCommunitySignInSequence(
                readState = { CommunitySignState.UNKNOWN },
                readIndependentBbsState = { bbsState },
                signIn = { communityId ->
                    mutationCalls += communityId
                    CommunitySignResponse(0, "", hasExpectedData = true)
                }
            )
            assertTrue(mutationCalls.isEmpty())
        }
    }

    @Test
    fun browseRunsIndependentlyWhenId1SignStateIsUnknown() = runTest {
        val browseCalls = mutableListOf<TaygedoBrowseRequest>()
        val browse = runTaygedoBrowseTask { request ->
            browseCalls += request
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 1))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[{"postId":"post-1"}]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> browseResult(JSONObject("""{"postId":"post-1"}"""))
                TaygedoBrowseStep.POST_TASK_STATE -> browseResult(browseState(1, 1))
            }
        }
        val signPosts = mutableListOf<String>()
        val signIns = runCommunitySignInSequence(
            readState = { CommunitySignState.UNKNOWN },
            readIndependentBbsState = { CommunitySignState.SIGNED },
            signIn = { communityId ->
                signPosts += communityId
                CommunitySignResponse(0, "", hasExpectedData = true)
            }
        )

        assertEquals(TaygedoBrowseOutcome.COMPLETED, browse.outcome)
        assertTrue(browseCalls.any { it.step == TaygedoBrowseStep.POST_DETAIL })
        assertTrue(signPosts.isEmpty())
        assertEquals(CommunitySignDisposition.PREFLIGHT_UNKNOWN, signIns.first().classification)
    }

    @Test
    fun browseWithNoRemainingSkipsRecommendationAndDetailReads() = runTest {
        val calls = mutableListOf<TaygedoBrowseRequest>()
        val result = runTaygedoBrowseTask { request ->
            calls += request
            assertEquals(TaygedoBrowseStep.PRE_TASK_STATE, request.step)
            browseResult(browseState(5, 5))
        }

        assertEquals(TaygedoBrowseOutcome.ALREADY_COMPLETED, result.outcome)
        assertEquals(listOf(TaygedoBrowseStep.PRE_TASK_STATE), calls.map { it.step })
    }

    @Test
    fun browseUsesDistinctPostIdsAndNeverSendsUnauthorizedRequests() = runTest {
        val calls = mutableListOf<TaygedoBrowseRequest>()
        val result = runTaygedoBrowseTask { request ->
            calls += request
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 3))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[
                        {"postId":"p1"},{"id":"p1"},{"postId":"p2"},{"postId":"p3"}
                    ]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> browseResult(JSONObject())
                TaygedoBrowseStep.POST_TASK_STATE -> browseResult(browseState(3, 3))
            }
        }

        assertEquals(TaygedoBrowseOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("p1", "p2", "p3"), result.detailPostIds)
        assertTrue(calls.filter {
            it.step == TaygedoBrowseStep.PRE_TASK_STATE ||
                it.step == TaygedoBrowseStep.POST_TASK_STATE
        }.all { !it.authV2 && it.useDs })
        assertTrue(calls.all { it.method == "GET" })
        assertTrue(calls.none {
            it.path.contains("signin") || it.path.contains("like") ||
                it.path.contains("comment") || it.path.contains("share") ||
                it.path.contains("follow") || it.path.contains("post/")
        })
    }

    @Test
    fun browseUsesAvailableUniquePostsWhenFewerThanRemaining() = runTest {
        val detailIds = mutableListOf<String>()
        val result = runTaygedoBrowseTask { request ->
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 5))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[{"postId":"p1"},{"postId":"p2"}]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> {
                    detailIds += request.query.getValue("postId")
                    browseResult(JSONObject())
                }
                TaygedoBrowseStep.POST_TASK_STATE -> browseResult(browseState(2, 5))
            }
        }

        assertEquals(TaygedoBrowseOutcome.STOPPED_POST_STATE_INCOMPLETE, result.outcome)
        assertEquals(listOf("p1", "p2"), detailIds)
    }

    @Test
    fun browseTaskStateRequiresOneStrictBrowseTask() {
        val unrelated = browseState(0, 5).apply {
            getJSONArray("task_list1").put(JSONObject("""{"taskKey":"other","code":"legacy"}"""))
            getJSONArray("task_list1").put(JSONObject("""{"taskKey":7,"code":8}"""))
            getJSONArray("task_list1").put(JSONObject("""{"unrelated":true}"""))
        }
        assertEquals(
            TaygedoBrowseTaskState(0, 5),
            parseTaygedoBrowseTaskState(browseResult(unrelated))
        )

        val duplicate = browseState(0, 5).apply {
            getJSONArray("task_list1").put(
                JSONObject("""{"taskKey":"browse_post_c","completeTimes":1,"limitTimes":5}""")
            )
        }
        assertEquals(null, parseTaygedoBrowseTaskState(browseResult(duplicate)))
        val conflicting = browseState(0, 5).apply {
            getJSONArray("task_list1").put(
                JSONObject("""{"taskKey":"browse_post_c","code":"other","completeTimes":0,"limitTimes":5}""")
            )
        }
        assertEquals(null, parseTaygedoBrowseTaskState(browseResult(conflicting)))
        listOf(
            JSONObject("""{"task_list1":[{"taskKey":"browse_post_c","completeTimes":"0","limitTimes":5}]}"""),
            JSONObject("""{"task_list1":[{"taskKey":"browse_post_c","completeTimes":-1,"limitTimes":5}]}"""),
            JSONObject("""{"task_list1":[{"taskKey":"browse_post_c","completeTimes":0,"limitTimes":1.5}]}""")
        ).forEach { assertEquals(null, parseTaygedoBrowseTaskState(browseResult(it))) }
    }

    @Test
    fun malformedRecommendationOrDetailStopsWithoutDownstreamReads() = runTest {
        val recommendationCalls = mutableListOf<TaygedoBrowseStep>()
        val malformedRecommendation = runTaygedoBrowseTask { request ->
            recommendationCalls += request.step
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 1))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(JSONObject("""{"unknown":[]}"""))
                else -> error("must not read after malformed recommendation")
            }
        }
        assertEquals(
            TaygedoBrowseOutcome.STOPPED_RECOMMEND_UNKNOWN,
            malformedRecommendation.outcome
        )
        assertEquals(
            listOf(TaygedoBrowseStep.PRE_TASK_STATE, TaygedoBrowseStep.RECOMMEND_POSTS),
            recommendationCalls
        )

        val detailCalls = mutableListOf<TaygedoBrowseStep>()
        val detailFailure = runTaygedoBrowseTask { request ->
            detailCalls += request.step
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 2))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[{"postId":"p1"},{"postId":"p2"}]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> browseResult(JSONObject(), code = 503)
                TaygedoBrowseStep.POST_TASK_STATE -> error("must not read after detail failure")
            }
        }
        assertEquals(TaygedoBrowseOutcome.STOPPED_DETAIL_FAILURE, detailFailure.outcome)
        assertEquals(
            listOf(
                TaygedoBrowseStep.PRE_TASK_STATE,
                TaygedoBrowseStep.RECOMMEND_POSTS,
                TaygedoBrowseStep.POST_DETAIL
            ),
            detailCalls
        )
    }

    @Test
    fun browseDoesNotReportSuccessWhenFinalReadBackIsIncompleteOrUnknown() = runTest {
        val incomplete = runTaygedoBrowseTask { request ->
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 2))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[{"postId":"p1"},{"postId":"p2"}]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> browseResult(JSONObject())
                TaygedoBrowseStep.POST_TASK_STATE -> browseResult(browseState(1, 2))
            }
        }
        assertEquals(TaygedoBrowseOutcome.STOPPED_POST_STATE_INCOMPLETE, incomplete.outcome)
        assertEquals(1, incomplete.after?.remaining)
        assertTrue(incomplete.toOutcome() is CheckInOutcome.PermanentFailure)

        val unknown = runTaygedoBrowseTask { request ->
            when (request.step) {
                TaygedoBrowseStep.PRE_TASK_STATE -> browseResult(browseState(0, 1))
                TaygedoBrowseStep.RECOMMEND_POSTS -> browseResult(
                    JSONObject("""{"list":[{"postId":"p1"}]}""")
                )
                TaygedoBrowseStep.POST_DETAIL -> browseResult(JSONObject())
                TaygedoBrowseStep.POST_TASK_STATE -> browseResult(JSONObject("""{"task_list1":{}}"""))
            }
        }
        assertEquals(TaygedoBrowseOutcome.STOPPED_POST_STATE_UNKNOWN, unknown.outcome)
        assertTrue(unknown.toOutcome() is CheckInOutcome.PermanentFailure)
    }

    @Test
    fun likeCapsToRemainingAndSkipsLikedOrUnknownTargets() = runTest {
        val calls = mutableListOf<TaygedoLikeRequest>()
        val result = runTaygedoLikeTask { request ->
            calls += request
            when (request.step) {
                TaygedoLikeStep.PRE_TASK_STATE -> likeResult(likeState(3, 5))
                TaygedoLikeStep.RECOMMENDATIONS -> likeResult(
                    JSONObject("""{"list":[
                        {"postId":"liked","selfOperation":{"liked":true}},
                        {"postId":"unknown","selfOperation":{}},
                        {"postId":"p1","selfOperation":{"liked":false}},
                        {"postId":"p2","selfOperation":{"liked":false}},
                        {"postId":"p3","selfOperation":{"liked":false}}
                    ]}""")
                )
                TaygedoLikeStep.POST_DETAIL -> likeResult(
                    JSONObject("""{"postId":"unknown","selfOperation":{}}""")
                )
                TaygedoLikeStep.LIKE -> likeResult(null, raw = JSONObject("""{"code":0}"""))
                TaygedoLikeStep.POST_TASK_STATE -> likeResult(likeState(5, 5))
            }
        }

        assertEquals(TaygedoLikeOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("p1", "p2"), result.likedPostIds)
        assertEquals(2, calls.count { it.step == TaygedoLikeStep.LIKE })
        assertTrue(calls.filter { it.step == TaygedoLikeStep.LIKE }.all {
            it.method == "POST" && it.jsonBody && !it.authV2 && it.useDs
        })
    }

    @Test
    fun likeStopsOnFirstAmbiguousMutationWithoutContinuing() = runTest {
        val calls = mutableListOf<TaygedoLikeStep>()
        val result = runTaygedoLikeTask { request ->
            calls += request.step
            when (request.step) {
                TaygedoLikeStep.PRE_TASK_STATE -> likeResult(likeState(0, 5))
                TaygedoLikeStep.RECOMMENDATIONS -> likeResult(
                    JSONObject("""{"list":[
                        {"postId":"p1","selfOperation":{"liked":false}},
                        {"postId":"p2","selfOperation":{"liked":false}}
                    ]}""")
                )
                TaygedoLikeStep.LIKE -> throw IllegalStateException("ambiguous transport")
                else -> error("must not continue after ambiguous mutation")
            }
        }

        assertEquals(TaygedoLikeOutcome.STOPPED_MUTATION_UNCERTAIN, result.outcome)
        assertEquals(
            listOf(
                TaygedoLikeStep.PRE_TASK_STATE,
                TaygedoLikeStep.RECOMMENDATIONS,
                TaygedoLikeStep.LIKE
            ),
            calls
        )
    }

    @Test
    fun likeWithNoRemainingSkipsAllMutationAndRecommendationReads() = runTest {
        val calls = mutableListOf<TaygedoLikeStep>()
        val result = runTaygedoLikeTask { request ->
            calls += request.step
            assertEquals(TaygedoLikeStep.PRE_TASK_STATE, request.step)
            likeResult(likeState(5, 5))
        }

        assertEquals(TaygedoLikeOutcome.ALREADY_COMPLETED, result.outcome)
        assertEquals(listOf(TaygedoLikeStep.PRE_TASK_STATE), calls)
    }

    @Test
    fun likeRequiresPostStateConfirmationAndReportsIncompleteProgress() = runTest {
        val calls = mutableListOf<TaygedoLikeStep>()
        val result = runTaygedoLikeTask { request ->
            calls += request.step
            when (request.step) {
                TaygedoLikeStep.PRE_TASK_STATE -> likeResult(likeState(0, 5))
                TaygedoLikeStep.RECOMMENDATIONS -> likeResult(
                    JSONObject("""{"list":[{"postId":"p1","selfOperation":{"liked":false}}]}""")
                )
                TaygedoLikeStep.LIKE -> likeResult(JSONObject(), raw = JSONObject("""{"code":0,"data":{}}"""))
                TaygedoLikeStep.POST_TASK_STATE -> likeResult(likeState(1, 5))
                TaygedoLikeStep.POST_DETAIL -> error("must not read detail for explicit state")
            }
        }

        assertEquals(TaygedoLikeOutcome.STOPPED_POST_STATE_INCOMPLETE, result.outcome)
        assertEquals(
            listOf(
                TaygedoLikeStep.PRE_TASK_STATE,
                TaygedoLikeStep.RECOMMENDATIONS,
                TaygedoLikeStep.LIKE,
                TaygedoLikeStep.POST_TASK_STATE
            ),
            calls
        )
        assertTrue(result.toOutcome() is CheckInOutcome.PermanentFailure)
    }

    @Test
    fun shareWithNoRemainingSkipsRecommendationAndMutation() = runTest {
        val calls = mutableListOf<TaygedoShareStep>()
        val result = runTaygedoShareTask { request ->
            calls += request.step
            assertEquals(TaygedoShareStep.PRE_TASK_STATE, request.step)
            shareResult(shareState(1, 1))
        }

        assertEquals(TaygedoShareOutcome.ALREADY_COMPLETED, result.outcome)
        assertEquals(listOf(TaygedoShareStep.PRE_TASK_STATE), calls)
    }

    @Test
    fun shareSendsOneQqFormMutationAndRequiresCompletedPostState() = runTest {
        val calls = mutableListOf<TaygedoShareRequest>()
        val result = runTaygedoShareTask { request ->
            calls += request
            when (request.step) {
                TaygedoShareStep.PRE_TASK_STATE -> shareResult(shareState(0, 1))
                TaygedoShareStep.RECOMMENDATIONS -> shareResult(
                    JSONObject("""{"list":[{"postId":"p1"},{"postId":"p2"}]}""")
                )
                TaygedoShareStep.SHARE -> shareResult(
                    null,
                    raw = JSONObject("""{"code":0}""")
                )
                TaygedoShareStep.POST_TASK_STATE -> shareResult(shareState(1, 1))
            }
        }

        assertEquals(TaygedoShareOutcome.COMPLETED, result.outcome)
        assertEquals("p1", result.sharedPostId)
        assertEquals(1, calls.count { it.step == TaygedoShareStep.SHARE })
        val share = calls.single { it.step == TaygedoShareStep.SHARE }
        assertEquals("POST", share.method)
        assertEquals("/bbs/api/post/share", share.path)
        assertEquals(mapOf("platform" to "qq", "postId" to "p1"), share.form)
        assertTrue(!share.jsonBody && share.useDs && !share.authV2)
    }

    @Test
    fun shareStopsOnAmbiguousMutationWithoutPostStateRead() = runTest {
        val calls = mutableListOf<TaygedoShareStep>()
        val result = runTaygedoShareTask { request ->
            calls += request.step
            when (request.step) {
                TaygedoShareStep.PRE_TASK_STATE -> shareResult(shareState(0, 1))
                TaygedoShareStep.RECOMMENDATIONS -> shareResult(
                    JSONObject("""{"list":[{"postId":"p1"}]}""")
                )
                TaygedoShareStep.SHARE -> throw IllegalStateException("ambiguous transport")
                else -> error("must not continue after ambiguous share")
            }
        }

        assertEquals(TaygedoShareOutcome.STOPPED_MUTATION_UNCERTAIN, result.outcome)
        assertEquals(
            listOf(
                TaygedoShareStep.PRE_TASK_STATE,
                TaygedoShareStep.RECOMMENDATIONS,
                TaygedoShareStep.SHARE
            ),
            calls
        )
    }

    @Test
    fun shareDoesNotClaimCompletionWhenPostStateIsIncomplete() = runTest {
        val calls = mutableListOf<TaygedoShareStep>()
        val result = runTaygedoShareTask { request ->
            calls += request.step
            when (request.step) {
                TaygedoShareStep.PRE_TASK_STATE -> shareResult(shareState(0, 1))
                TaygedoShareStep.RECOMMENDATIONS -> shareResult(
                    JSONObject("""{"list":[{"postId":"p1"}]}""")
                )
                TaygedoShareStep.SHARE -> shareResult(
                    JSONObject(),
                    raw = JSONObject("""{"code":0,"data":{}}""")
                )
                TaygedoShareStep.POST_TASK_STATE -> shareResult(shareState(0, 1))
            }
        }

        assertEquals(TaygedoShareOutcome.STOPPED_POST_STATE_INCOMPLETE, result.outcome)
        assertEquals(
            listOf(
                TaygedoShareStep.PRE_TASK_STATE,
                TaygedoShareStep.RECOMMENDATIONS,
                TaygedoShareStep.SHARE,
                TaygedoShareStep.POST_TASK_STATE
            ),
            calls
        )
        assertTrue(result.toOutcome() is CheckInOutcome.PermanentFailure)
    }

    private fun shareResult(
        data: Any?,
        code: Int = 0,
        raw: JSONObject = JSONObject().put("code", code)
    ) = TaygedoClient.ApiResult(
        code = code,
        data = data,
        message = "",
        raw = raw
    )

    private fun shareState(complete: Int, limit: Int) = JSONObject().apply {
        put("task_list1", JSONArray().put(JSONObject().apply {
            put("taskKey", "share")
            put("completeTimes", complete)
            put("limitTimes", limit)
        }))
    }

    private fun likeResult(
        data: Any?,
        code: Int = 0,
        raw: JSONObject = JSONObject().put("code", code)
    ) = TaygedoClient.ApiResult(
        code = code,
        data = data,
        message = "",
        raw = raw
    )

    private fun likeState(complete: Int, limit: Int) = JSONObject().apply {
        put("task_list1", JSONArray().put(JSONObject().apply {
            put("taskKey", "like_post_c")
            put("completeTimes", complete)
            put("limitTimes", limit)
        }))
    }

    private fun browseResult(data: Any?, code: Int = 0) = TaygedoClient.ApiResult(
        code = code,
        data = data,
        message = "",
        raw = JSONObject()
    )

    private fun browseState(complete: Int, limit: Int) = JSONObject().apply {
        put("task_list1", JSONArray().put(JSONObject().apply {
            put("taskKey", "browse_post_c")
            put("completeTimes", complete)
            put("limitTimes", limit)
        }))
    }
}
