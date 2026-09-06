package com.checky.app.domain.providers

import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.model.CheckInOutcome
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaygedoAuthExpiryTest {
    @Test
    fun rawHttp401IsAuthExpiredWithExactlyOneRequestAndNoDownstreamAction() = runTest {
        val calls = mutableListOf<String>()
        val provider = providerReturning(
            responseCode = 401,
            body = "",
            calls = calls
        )

        val result = provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result

        assertTrue(result.outcome is CheckInOutcome.AuthenticationExpired)
        assertEquals(listOf("/apihub/awapi/yh/roleHome"), calls)
    }

    @Test
    fun business401WithMalformedBodyIsAuthExpiredWithoutRefreshOrRetry() = runTest {
        val calls = mutableListOf<String>()
        val provider = providerReturning(
            responseCode = 200,
            body = "{\"code\":401}",
            calls = calls
        )

        val result = provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result

        assertTrue(result.outcome is CheckInOutcome.AuthenticationExpired)
        assertEquals(listOf("/apihub/awapi/yh/roleHome"), calls)
    }

    @Test
    fun nonAuth503AndMalformedResponsesRemainTemporaryWithoutRetry() = runTest {
        listOf(503 to "", 200 to "{}").forEach { (responseCode, body) ->
            val calls = mutableListOf<String>()
            val provider = providerReturning(responseCode, body, calls)

            val result = provider.checkIn().filterIsInstance<CheckInEvent.Done>().single().result

            assertTrue(result.outcome is CheckInOutcome.TemporaryFailure)
            assertEquals(listOf("/apihub/awapi/yh/roleHome"), calls)
        }
    }

    @Test
    fun coinStateRawAndBusiness401PropagateAuthExpiryWithExactlyOneRequest() = runTest {
        listOf(401 to "", 200 to "{\"code\":401}").forEach { (responseCode, body) ->
            val calls = mutableListOf<String>()
            val client = clientReturning(responseCode, body, calls)

            var failure: TaygedoClient.AuthException? = null
            try {
                client.getUserCoinTaskState()
            } catch (error: TaygedoClient.AuthException) {
                failure = error
            }

            assertTrue(failure != null)
            assertEquals(listOf("/apihub/api/getUserCoinTaskState"), calls)
        }
    }

    @Test
    fun coinState503AndMalformedResponsesRemainUnknownWithoutRetry() = runTest {
        listOf(503 to "", 200 to "not-json").forEach { (responseCode, body) ->
            val calls = mutableListOf<String>()
            val client = clientReturning(responseCode, body, calls)

            assertTrue(client.getUserCoinTaskState() is TaygedoCoinStateResult.Unknown)
            assertEquals(listOf("/apihub/api/getUserCoinTaskState"), calls)
        }
    }

    private suspend fun providerReturning(
        responseCode: Int,
        body: String,
        calls: MutableList<String>
    ): TaygedoNteProvider = TaygedoNteProvider(clientReturning(responseCode, body, calls))

    private suspend fun clientReturning(
        responseCode: Int,
        body: String,
        calls: MutableList<String>
    ): TaygedoClient {
        val credentials = FakeCredentialStore().also {
            it.save(
                TaygedoClient.SESSION_KEY,
                "{\"accessToken\":\"synthetic-access\",\"refreshToken\":\"synthetic-refresh\",\"uid\":\"1\",\"deviceId\":\"synthetic-device\"}"
            )
        }
        val interceptor = Interceptor { chain ->
            calls += chain.request().url.encodedPath
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("synthetic")
                .body(body.toResponseBody())
                .build()
        }
        val client = TaygedoClient(
            ApplicationProvider.getApplicationContext(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
        return client
    }
}
