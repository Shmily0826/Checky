package com.checky.app.domain.providers

import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.RewardType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive JVM coverage of the pure response-classification helpers used by
 * the experimental providers. These tests only exercise stable mapping logic;
 * they never touch the network.
 */
class ProviderParsingTest {

    // --- Taygedo community sign classification ---

    @Test
    fun classifyCommunitySignResponseMatrix() {
        fun classify(code: Int, message: String, hasData: Boolean) =
            classifyCommunitySignResponse(
                CommunitySignResponse(code, message, hasExpectedData = hasData)
            )

        assertEquals(CommunitySignDisposition.SUCCESS, classify(0, "", hasData = true))
        assertEquals(CommunitySignDisposition.MALFORMED, classify(0, "", hasData = false))
        assertEquals(CommunitySignDisposition.FAILURE, classify(500, "server busy", hasData = false))
        assertEquals(CommunitySignDisposition.AUTH_EXPIRED, classify(401, "", hasData = false))
        assertEquals(CommunitySignDisposition.AUTH_EXPIRED, classify(-401, "", hasData = false))

        assertEquals(
            CommunitySignDisposition.ALREADY_COMPLETED,
            classify(1008, "已签到", hasData = false)
        )
        assertEquals(
            CommunitySignDisposition.ALREADY_COMPLETED,
            classify(0, "重复签到", hasData = false)
        )
        assertEquals(
            CommunitySignDisposition.ALREADY_COMPLETED,
            classify(0, "Already signed today", hasData = false)
        )

        assertEquals(
            CommunitySignDisposition.VERIFICATION_REQUIRED,
            classify(403, "需要风控验证", hasData = false)
        )
        assertEquals(
            CommunitySignDisposition.VERIFICATION_REQUIRED,
            classify(0, "请完成 Captcha", hasData = false)
        )
    }

    @Test
    fun onlySuccessAndAlreadyCompletedMayContinueToNextStep() {
        assertTrue(CommunitySignDisposition.SUCCESS.mayContinue)
        assertTrue(CommunitySignDisposition.ALREADY_COMPLETED.mayContinue)
        assertFalse(CommunitySignDisposition.AUTH_EXPIRED.mayContinue)
        assertFalse(CommunitySignDisposition.VERIFICATION_REQUIRED.mayContinue)
        assertFalse(CommunitySignDisposition.FAILURE.mayContinue)
        assertFalse(CommunitySignDisposition.MALFORMED.mayContinue)
    }

    // --- Taygedo NTE sign-state parsing ---

    @Test
    fun parseNteSignStateReadsWellFormedJson() {
        val data = JSONObject().apply {
            put("todaySign", true)
            put("days", 3)
            put("day", 4)
        }
        val state = parseNteSignState(data)
        assertEquals(NteSignState(todaySigned = true, days = 3, day = 4), state)
    }

    @Test
    fun parseNteSignStateFailsClosedOnMissingFields() {
        assertNull(parseNteSignState(JSONObject().apply { put("todaySign", true) }))
        assertNull(parseNteSignState(JSONObject().apply { put("days", 1) }))
        assertNull(parseNteSignState(JSONObject()))
        assertNull(parseNteSignState("not-a-json-object"))
        assertNull(parseNteSignState(null))
    }

    @Test
    fun parseNteSignStateFieldsRequiresEveryField() {
        val expected = NteSignState(todaySigned = false, days = 0, day = 1)
        assertEquals(expected, parseNteSignStateFields(false, 0, 1))
        assertNull(parseNteSignStateFields(null, 1, 1))
        assertNull(parseNteSignStateFields(false, null, 1))
        assertNull(parseNteSignStateFields(false, 1, null))
    }

    // --- Taygedo failure mapping ---

    @Test
    fun mapTaygedoFailureAuthAndVerificationFailures() {
        assertEquals("TAYGEDO_AUTH_EXPIRED", mapTaygedoFailure(401, "").diagnosticCode)
        assertEquals("TAYGEDO_AUTH_EXPIRED", mapTaygedoFailure(-401, "任意消息").diagnosticCode)
        assertEquals("TAYGEDO_VERIFICATION", mapTaygedoFailure(403, "需要风险验证").diagnosticCode)
        assertEquals(
            CheckInOutcome.AuthenticationExpired("expired").status,
            mapTaygedoFailure(401, "").status
        )
        assertEquals(
            CheckInOutcome.ActionRequired("action").status,
            mapTaygedoFailure(0, "captcha required").status
        )
    }

    @Test
    fun mapTaygedoFailureUsesFallbackMessageWhenServerMessageBlank() {
        val outcome = mapTaygedoFailure(503, "", fallback = "塔吉多签到失败。")
        assertEquals("TAYGEDO_503", outcome.diagnosticCode)
        assertEquals("塔吉多签到失败。", outcome.userMessage)
        assertEquals(CheckInOutcome.TemporaryFailure("temp").status, outcome.status)
    }

    // --- Miyoushe community response mapping ---

    @Test
    fun miyousheCommunityResponseParsesAllStableShapes() {
        val success = mapMiyousheCommunityResponse(
            """{"retcode":0,"message":"OK","data":{"points":10}}"""
        )
        assertTrue(success is CheckInOutcome.Success)
        assertEquals(RewardType.POINTS, success.reward.type)
        assertEquals(10, success.reward.amount)

        val alreadyByZeroPoints = mapMiyousheCommunityResponse(
            """{"retcode":0,"message":"OK","data":{"points":0}}"""
        )
        assertTrue(alreadyByZeroPoints is CheckInOutcome.AlreadyCompleted)

        val alreadyByRetcode = mapMiyousheCommunityResponse(
            """{"retcode":1008,"message":"已签到"}"""
        )
        assertTrue(alreadyByRetcode is CheckInOutcome.AlreadyCompleted)

        val authExpired = mapMiyousheCommunityResponse("""{"retcode":-100,"message":"失效"}""")
        assertEquals(
            CheckInOutcome.AuthenticationExpired("expired").status,
            authExpired.status
        )

        val verification = mapMiyousheCommunityResponse(
            """{"retcode":1034,"message":"需要验证"}"""
        )
        assertEquals(CheckInOutcome.ActionRequired("action").status, verification.status)

        val rejected = mapMiyousheCommunityResponse(
            """{"retcode":999,"message":"拒绝"}"""
        )
        assertTrue(rejected is CheckInOutcome.TemporaryFailure)
        assertEquals("MIYOUSHE_COMMUNITY_REJECTED_999", rejected.diagnosticCode)
    }

    @Test
    fun miyousheCommunityResponseFailsClosedOnMalformedBody() {
        val outcomes = listOf(
            mapMiyousheCommunityResponse("not json at all"),
            mapMiyousheResponse("""{"message":"no retcode"}"""),
            mapMiyousheResponse("""{"retcode":0,"message":"no data block"}""")
        )
        outcomes.forEach { outcome ->
            assertTrue(outcome is CheckInOutcome.PermanentFailure)
            assertEquals("MIYOUSHE_COMMUNITY_BAD_RESPONSE", outcome.diagnosticCode)
        }
    }

    private fun mapMiyousheResponse(body: String) = mapMiyousheCommunityResponse(body)
}
