package com.checky.app.domain.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SavedCredentialValidation
import com.checky.app.domain.model.CheckInOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiyousheCommunityProviderTest {

    @Test
    fun confirmedQrEnrichesValidatesAndPersistsCommunityCredential() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore()
        val provider = provider(
            responses = listOf(
                """{"retcode":0,"data":{"status":"Confirmed","tokens":[{"token":"synthetic-stoken"}],"user_info":{"mid":"synthetic-mid","aid":"synthetic-account"}}}""",
                """{"retcode":0,"data":{"cookie_token":"synthetic-cookie"}}""",
                """{"retcode":0,"data":{"ltoken":"synthetic-ltoken"}}"""
            ),
            store = store,
            requests = requests
        )

        val result = provider.pollQrLogin(
            QrLoginSession("synthetic-qr", "synthetic-device|synthetic-ticket")
        )

        assertTrue(result is QrLoginPollResult.Confirmed)
        val saved = store.getNow(MiyousheCommunityProvider.META.id).orEmpty()
        assertTrue(store.has(MiyousheCommunityProvider.META.id))
        assertTrue(saved.contains("cookie_token_v2="))
        assertTrue(saved.contains("ltoken_v2="))
        assertTrue(saved.contains("ltuid="))
        assertTrue(saved.contains("ltmid_v2="))
        assertEquals(3, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("/account/ma-cn-passport/app/queryQRLoginStatus", requests[0].url.encodedPath)
        assertEquals("GET", requests[1].method)
        assertEquals("/account/auth/api/getCookieAccountInfoBySToken", requests[1].url.encodedPath)
        assertEquals("GET", requests[2].method)
        assertEquals("/account/auth/api/getLTokenBySToken", requests[2].url.encodedPath)
        assertFalse(requests.any { it.url.encodedPath == "/apihub/app/api/signIn" })
    }

    @Test
    fun incompleteSessionPreflightsThenSendsOneSignPostWithoutRenewal() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(
            responses = listOf(incomplete(), signSuccess()),
            store = store,
            requests = requests
        )

        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.Success)
        assertEquals(2, requests.size)
        assertPreflightRequest(requests[0], oldCookie())
        assertEquals("POST", requests[1].method)
        assertEquals("/apihub/app/api/signIn", requests[1].url.encodedPath)
        assertEquals(oldCookie(), requests[1].header("Cookie"))
    }

    @Test
    fun missingContinuousSignStateRevalidatesAsValidWithoutPosting() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(listOf(noContinuousSign()), store, requests)

        val result = provider.revalidateSavedCredential()

        assertTrue(result is SavedCredentialValidation.Valid)
        assertEquals(1, requests.size)
        assertFalse(requests.any { it.method == "POST" })
    }

    @Test
    fun missingContinuousSignStateUsesExistingIncompleteCheckInPath() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(listOf(noContinuousSign(), signSuccess()), store, requests)

        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.Success)
        assertEquals(2, requests.size)
        assertEquals("POST", requests[1].method)
        assertEquals("/apihub/app/api/signIn", requests[1].url.encodedPath)
    }

    @Test
    fun alreadyCompleteSessionDoesNotPostOrRenew() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(listOf(alreadyComplete()), store, requests)

        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.AlreadyCompleted)
        assertEquals(1, requests.size)
        assertPreflightRequest(requests.single(), oldCookie())
    }

    @Test
    fun expiredSessionRenewsOnceRevalidatesPersistsAndThenSigns() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(
            responses = listOf(
                authExpired(),
                cookieTokenSuccess(),
                lTokenSuccess(),
                incomplete(),
                signSuccess()
            ),
            store = store,
            requests = requests
        )

        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.Success)
        assertEquals(5, requests.size)
        assertEquals("/account/auth/api/getCookieAccountInfoBySToken", requests[1].url.encodedPath)
        assertEquals("/account/auth/api/getLTokenBySToken", requests[2].url.encodedPath)
        assertEquals("GET", requests[3].method)
        assertEquals("POST", requests[4].method)
        assertTrue(store.getNow(MiyousheCommunityProvider.META.id).orEmpty().contains("cookie_token_v2=renewed-cookie"))
        assertTrue(store.getNow(MiyousheCommunityProvider.META.id).orEmpty().contains("ltoken_v2=renewed-ltoken"))
        assertEquals(store.getNow(MiyousheCommunityProvider.META.id), requests[4].header("Cookie"))
    }

    @Test
    fun expiredSessionRenewsAndAlreadyCompleteDoesNotPost() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(
            responses = listOf(authExpired(), cookieTokenSuccess(), lTokenSuccess(), alreadyComplete()),
            store = store,
            requests = requests
        )

        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.AlreadyCompleted)
        assertEquals(4, requests.size)
        assertFalse(requests.any { it.method == "POST" })
        assertTrue(store.getNow(MiyousheCommunityProvider.META.id).orEmpty().contains("ltoken_v2=renewed-ltoken"))
    }

    @Test
    fun savedCredentialRevalidationRenewsAndNeverPostsSignIn() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val store = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val provider = provider(
            responses = listOf(authExpired(), cookieTokenSuccess(), lTokenSuccess(), incomplete()),
            store = store,
            requests = requests
        )

        val result = provider.revalidateSavedCredential()

        assertTrue(result is SavedCredentialValidation.Valid)
        assertEquals(4, requests.size)
        assertFalse(requests.any { it.method == "POST" })
        assertTrue(store.getNow(MiyousheCommunityProvider.META.id).orEmpty().contains("cookie_token_v2=renewed-cookie"))
        assertTrue(store.getNow(MiyousheCommunityProvider.META.id).orEmpty().contains("ltoken_v2=renewed-ltoken"))
    }

    @Test
    fun savedCredentialRevalidationFailsClosedForMissingRootAndUnknownState() = runTest {
        val missingRootRequests = mutableListOf<okhttp3.Request>()
        val missingRootStore = CommunityRecordingStore().also {
            it.put(MiyousheCommunityProvider.META.id, "ltoken=only-derived-token; ltuid=uid-1")
        }
        val missingRootProvider = provider(
            responses = listOf(authExpired()),
            store = missingRootStore,
            requests = missingRootRequests
        )

        assertTrue(missingRootProvider.revalidateSavedCredential() is SavedCredentialValidation.Expired)
        assertEquals(1, missingRootRequests.size)
        assertFalse(missingRootRequests.any { it.method == "POST" })
        assertEquals(
            "ltoken=only-derived-token; ltuid=uid-1",
            missingRootStore.getNow(MiyousheCommunityProvider.META.id)
        )

        val unknownRequests = mutableListOf<okhttp3.Request>()
        val unknownStore = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val unknownProvider = provider(
            responses = listOf("{\"retcode\":999}"),
            store = unknownStore,
            requests = unknownRequests
        )

        assertTrue(unknownProvider.revalidateSavedCredential() is SavedCredentialValidation.Unverified)
        assertEquals(1, unknownRequests.size)
        assertFalse(unknownRequests.any { it.method == "POST" })
        assertEquals(oldCookie(), unknownStore.getNow(MiyousheCommunityProvider.META.id))
    }

    @Test
    fun renewalFailureAndPostRenewalUnknownKeepOldCookieAndDoNotPost() = runTest {
        val failureRequests = mutableListOf<okhttp3.Request>()
        val failureStore = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val failureProvider = provider(
            responses = listOf(authExpired(), "{}", "{}"),
            store = failureStore,
            requests = failureRequests
        )
        val failure = failureProvider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(failure.result.outcome is CheckInOutcome.TemporaryFailure)
        assertEquals(3, failureRequests.size)
        assertFalse(failureRequests.any { it.method == "POST" })
        assertEquals(oldCookie(), failureStore.getNow(MiyousheCommunityProvider.META.id))

        val transportRequests = mutableListOf<okhttp3.Request>()
        val transportStore = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val transportProvider = provider(
            responses = listOf(authExpired()),
            store = transportStore,
            requests = transportRequests,
            failAt = 1
        )
        val transport = transportProvider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(transport.result.outcome is CheckInOutcome.TemporaryFailure)
        assertEquals(2, transportRequests.size)
        assertFalse(transportRequests.any { it.method == "POST" })
        assertEquals(oldCookie(), transportStore.getNow(MiyousheCommunityProvider.META.id))

        val unknownRequests = mutableListOf<okhttp3.Request>()
        val unknownStore = CommunityRecordingStore().also { it.put(MiyousheCommunityProvider.META.id, oldCookie()) }
        val unknownProvider = provider(
            responses = listOf(authExpired(), cookieTokenSuccess(), lTokenSuccess(), duplicateContinuousSign()),
            store = unknownStore,
            requests = unknownRequests
        )
        val unknown = unknownProvider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(unknown.result.outcome is CheckInOutcome.TemporaryFailure)
        assertEquals(4, unknownRequests.size)
        assertFalse(unknownRequests.any { it.method == "POST" })
        assertEquals(oldCookie(), unknownStore.getNow(MiyousheCommunityProvider.META.id))
    }

    @Test
    fun verificationUnknownAndMissingRootPreflightFailClosedWithoutRenewalOrPost() = runTest {
        listOf(
            "{\"retcode\":1034,\"message\":\"captcha\"}" to oldCookie(),
            "{\"retcode\":999,\"message\":\"unknown\"}" to oldCookie(),
            authExpired() to "ltoken=only-derived-token; ltuid=uid-1"
        ).forEach { (preflight, cookie) ->
            val requests = mutableListOf<okhttp3.Request>()
            val store = CommunityRecordingStore().also {
                it.put(MiyousheCommunityProvider.META.id, cookie)
            }
            val provider = provider(listOf(preflight), store, requests)

            val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

            assertFalse(done.result.outcome is CheckInOutcome.Success)
            assertEquals(1, requests.size)
            assertFalse(requests.any { it.method == "POST" })
        }
    }

    private fun provider(
        responses: List<String>,
        store: CommunityRecordingStore,
        requests: MutableList<okhttp3.Request>,
        failAt: Int? = null
    ): MiyousheCommunityProvider {
        val responseBodies = responses.iterator()
        var requestIndex = 0
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            if (requestIndex++ == failAt) error("synthetic transport failure")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBodies.next().toResponseBody("application/json".toMediaType()))
                .build()
        }
        return MiyousheCommunityProvider(
            ApplicationProvider.getApplicationContext<Context>(),
            store,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private fun assertPreflightRequest(request: okhttp3.Request, cookie: String) {
        assertEquals("GET", request.method)
        assertEquals("bbs-api.miyoushe.com", request.url.host)
        assertEquals("/apihub/wapi/getUserMissionsState", request.url.encodedPath)
        assertEquals("myb", request.url.queryParameter("point_sn"))
        assertEquals(null, request.body)
        assertEquals(cookie, request.header("Cookie"))
        assertEquals("2.109.0", request.header("x-rpc-app_version"))
        assertEquals("2", request.header("x-rpc-client_type"))
        assertTrue(!request.header("x-rpc-device_id").isNullOrBlank())
        assertTrue(!request.header("DS").isNullOrBlank())
    }

    private fun oldCookie() =
        "stoken=old-stoken; stoken_v2=old-stoken; mid=mid-1; stuid=uid-1; " +
            "account_id=uid-1; account_id_v2=uid-1; cookie_token_v2=old-cookie; " +
            "ltoken=old-ltoken; ltoken_v2=old-ltoken; ltuid=uid-1; ltmid_v2=mid-1"

    private fun incomplete() =
        "{\"retcode\":0,\"data\":{\"states\":[{\"mission_key\":\"continuous_sign\",\"happened_times\":0}]}}"

    private fun noContinuousSign() =
        "{\"retcode\":0,\"data\":{\"states\":[]}}"

    private fun duplicateContinuousSign() =
        "{\"retcode\":0,\"data\":{\"states\":[" +
            "{\"mission_key\":\"continuous_sign\",\"happened_times\":0}," +
            "{\"mission_key\":\"continuous_sign\",\"happened_times\":0}]}}"

    private fun alreadyComplete() =
        "{\"retcode\":0,\"data\":{\"states\":[{\"mission_key\":\"continuous_sign\",\"happened_times\":1}]}}"

    private fun authExpired() = "{\"retcode\":-100,\"message\":\"expired\"}"

    private fun cookieTokenSuccess() =
        "{\"retcode\":0,\"data\":{\"cookie_token\":\"renewed-cookie\"}}"

    private fun lTokenSuccess() =
        "{\"retcode\":0,\"data\":{\"ltoken\":\"renewed-ltoken\"}}"

    private fun signSuccess() = "{\"retcode\":0,\"data\":{\"points\":10}}"
}

private class CommunityRecordingStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    fun put(providerId: String, secret: String) {
        values[providerId] = secret
    }

    fun getNow(providerId: String): String? = values[providerId]

    override suspend fun save(providerId: String, secret: String) {
        values[providerId] = secret
    }

    override suspend fun get(providerId: String): String? = values[providerId]
    override suspend fun has(providerId: String): Boolean = providerId in values
    override suspend fun delete(providerId: String) { values.remove(providerId) }
    override suspend fun deleteAll() { values.clear() }
}
