package com.checky.app.domain.providers

import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.data.work.selectExecutableProviders
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
import java.io.IOException
import java.security.MessageDigest

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

    @Test
    fun authRevalidationUsesOnlyAcceptedEnvelopeAndNeverCoinFields() = runTest {
        val accepted = clientReturning(
            200,
            """{"code":0,"data":{"todayGet":1,"todayTotal":5,"total":9},"msg":"ok","ok":true}""",
            mutableListOf()
        )
        assertEquals(
            TaygedoClient.AuthRevalidation.ACCEPTED,
            accepted.revalidateSavedCredentialAuth()
        )

        listOf(
            401 to "",
            200 to "{\"code\":-401}",
            503 to "",
            200 to "not-json",
            200 to "{\"code\":1,\"data\":{},\"msg\":\"error\",\"ok\":false}"
        ).forEach { (responseCode, body) ->
            val client = clientReturning(responseCode, body, mutableListOf())
            val expected = if (responseCode == 401 || body.contains("-401")) {
                TaygedoClient.AuthRevalidation.EXPIRED
            } else {
                TaygedoClient.AuthRevalidation.UNKNOWN
            }
            assertEquals(expected, client.revalidateSavedCredentialAuth())
        }

        val transportFailure = clientWithInterceptor { throw IOException("synthetic") }
        assertEquals(
            TaygedoClient.AuthRevalidation.UNKNOWN,
            transportFailure.revalidateSavedCredentialAuth()
        )
    }

    @Test
    fun expiredAuthRefreshUsesRefreshTokenWithoutBodyThenRevalidatesOnce() = runTest {
        val credentials = sessionStore()
        val requests = mutableListOf<okhttp3.Request>()
        val client = clientWithResponses(
            credentials,
            requests,
            listOf(
                200 to "{\"code\":401}",
                200 to "{\"code\":0,\"data\":{\"accessToken\":\"rotated-access\",\"refreshToken\":\"rotated-refresh\"}}",
                200 to "{\"code\":0,\"data\":{},\"msg\":\"ok\",\"ok\":true}"
            )
        )

        assertEquals(
            TaygedoClient.AuthRevalidation.ACCEPTED,
            client.revalidateSavedCredentialAuth()
        )
        assertEquals(
            listOf(
                "/apihub/api/getUserCoinTaskState",
                "/usercenter/api/refreshToken",
                "/apihub/api/getUserCoinTaskState"
            ),
            requests.map { it.url.encodedPath }
        )
        assertEquals("1.2.6", requests[0].header("appversion"))
        val refresh = requests[1]
        assertEquals("POST", refresh.method)
        assertEquals("synthetic-refresh", refresh.header("Authorization"))
        assertEquals("1", refresh.header("uid"))
        assertEquals("3", refresh.header("debug-uid"))
        assertTrue(!refresh.header("deviceid").isNullOrBlank())
        assertEquals("1.2.5", refresh.header("appversion"))
        assertEquals("android", refresh.header("platform"))
        assertEquals("okhttp/4.12.0", refresh.header("User-Agent"))
        val dsParts = refresh.header("ds").orEmpty().split(",")
        assertEquals(3, dsParts.size)
        assertEquals(
            md5(dsParts[0] + dsParts[1] + "1.2.5" + "pUds3dfMkl"),
            dsParts[2]
        )
        assertEquals(0L, refresh.body?.contentLength())
        assertTrue(refresh.url.querySize == 0)
        assertEquals("1.2.6", requests[2].header("appversion"))
        assertEquals(null, requests[2].header("ds"))

        val saved = credentials.get(TaygedoClient.SESSION_KEY).orEmpty()
        assertTrue(saved.contains("rotated-access"))
        assertTrue(saved.contains("rotated-refresh"))
        assertTrue(saved.contains("\"uid\":\"1\""))
        assertTrue(saved.contains("synthetic-device"))
    }

    @Test
    fun refreshFailureLeavesOldSessionUntouchedAndExpired() = runTest {
        val refreshFailures = listOf(
            200 to "not-json",
            200 to "{\"code\":1,\"data\":{}}",
            402 to "",
            403 to "",
            200 to "{\"code\":0,\"data\":{\"accessToken\":\"new\"}}",
            200 to "{\"code\":0,\"data\":{\"refreshToken\":\"new\"}}"
        )
        refreshFailures.forEach { refreshFailure ->
            val credentials = sessionStore()
            val original = credentials.get(TaygedoClient.SESSION_KEY)
            val requests = mutableListOf<okhttp3.Request>()
            val client = clientWithResponses(
                credentials,
                requests,
                listOf(200 to "{\"code\":401}", refreshFailure)
            )

            assertEquals(
                TaygedoClient.AuthRevalidation.EXPIRED,
                client.revalidateSavedCredentialAuth()
            )
            assertEquals(original, credentials.get(TaygedoClient.SESSION_KEY))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun refreshTransportFailureLeavesOldSessionUntouchedAndExpired() = runTest {
        val credentials = sessionStore()
        val original = credentials.get(TaygedoClient.SESSION_KEY)
        val requests = mutableListOf<okhttp3.Request>()
        var callCount = 0
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            callCount++
            if (callCount == 2) throw IOException("synthetic refresh failure")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("synthetic")
                .body("{\"code\":401}".toResponseBody())
                .build()
        }
        val client = TaygedoClient(
            ApplicationProvider.getApplicationContext(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )

        assertEquals(
            TaygedoClient.AuthRevalidation.EXPIRED,
            client.revalidateSavedCredentialAuth()
        )
        assertEquals(original, credentials.get(TaygedoClient.SESSION_KEY))
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredSharedTaygedoSessionSelfHealsBeforeAutoSelection() = runTest {
        val credentials = sessionStore()
        val health = com.checky.app.domain.FakeAuthHealthStore().also {
            it.set(TaygedoClient.SESSION_KEY, AuthHealth.EXPIRED)
        }
        val requests = mutableListOf<okhttp3.Request>()
        val client = clientWithResponses(
            credentials,
            requests,
            listOf(
                200 to "{\"code\":401}",
                200 to "{\"code\":0,\"data\":{\"accessToken\":\"rotated-access\",\"refreshToken\":\"rotated-refresh\"}}",
                200 to "{\"code\":0,\"data\":{},\"msg\":\"ok\",\"ok\":true}"
            )
        )
        val provider = TaygedoNteProvider(client)

        val selected = selectExecutableProviders(
            enabledIds = setOf(provider.meta.id),
            providers = listOf(provider),
            credentials = credentials,
            authHealthStore = health
        )

        assertEquals(listOf(provider), selected)
        assertEquals(AuthHealth.VALID, health.get(TaygedoClient.SESSION_KEY))
        assertEquals(3, requests.size)
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

    private suspend fun clientWithResponses(
        credentials: FakeCredentialStore,
        requests: MutableList<okhttp3.Request>,
        responses: List<Pair<Int, String>>
    ): TaygedoClient {
        var index = 0
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            val (responseCode, body) = responses[index++]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("synthetic")
                .body(body.toResponseBody())
                .build()
        }
        return TaygedoClient(
            ApplicationProvider.getApplicationContext(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private suspend fun sessionStore() = FakeCredentialStore().also {
        it.save(
            TaygedoClient.SESSION_KEY,
            "{\"accessToken\":\"synthetic-access\",\"refreshToken\":\"synthetic-refresh\",\"uid\":\"1\",\"deviceId\":\"synthetic-device\"}"
        )
    }

    private suspend fun clientWithInterceptor(interceptor: Interceptor): TaygedoClient {
        val credentials = FakeCredentialStore().also {
            it.save(
                TaygedoClient.SESSION_KEY,
                "{\"accessToken\":\"synthetic-access\",\"refreshToken\":\"synthetic-refresh\",\"uid\":\"1\",\"deviceId\":\"synthetic-device\"}"
            )
        }
        return TaygedoClient(
            ApplicationProvider.getApplicationContext(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
