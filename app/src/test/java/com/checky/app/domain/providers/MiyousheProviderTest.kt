package com.checky.app.domain.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.SavedCredentialValidation
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiyousheProviderTest {

    @Test
    fun revalidationUsesCheckInInfoGetOnlyAndDoesNotMutateCredentials() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val credentials = GenshinRecordingStore().also { it.put(MiyousheProvider.META.id, sessionJson()) }
        val original = credentials.peek(MiyousheProvider.META.id)
        val provider = provider(
            responseBody = """{"retcode":0,"data":{"is_sign":false}}""",
            credentials = credentials,
            requests = requests
        )

        assertTrue(provider.revalidateSavedCredential() is SavedCredentialValidation.Valid)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("api-takumi.mihoyo.com", request.url.host)
        assertEquals("/event/luna/hk4e/info", request.url.encodedPath)
        assertEquals("zh-cn", request.url.queryParameter("lang"))
        assertEquals("e202311201442471", request.url.queryParameter("act_id"))
        assertEquals("123456789", request.url.queryParameter("uid"))
        assertEquals("cn_gf01", request.url.queryParameter("region"))
        assertFalse(requests.any { it.method == "POST" })
        assertEquals(0, credentials.saveCount)
        assertEquals(0, credentials.deleteCount)
        assertEquals(original, credentials.peek(MiyousheProvider.META.id))
    }

    @Test
    fun revalidationMapsExpiredAndUnknownCheckInStatesFailClosed() = runTest {
        val cases = listOf(
            """{"retcode":-100}""" to SavedCredentialValidation.Expired,
            """{"retcode":-101}""" to SavedCredentialValidation.Expired,
            """{"retcode":-10001}""" to SavedCredentialValidation.Expired,
            """{"retcode":1034,"message":"verification"}""" to null,
            """{"retcode":0,"data":{}}""" to null,
            """{"retcode":0,"data":{"is_sign":"false"}}""" to null,
            "not-json" to null
        )

        cases.forEach { (body, expected) ->
            val requests = mutableListOf<okhttp3.Request>()
            val credentials = GenshinRecordingStore().also { it.put(MiyousheProvider.META.id, sessionJson()) }
            val provider = provider(body, credentials, requests)

            val result = provider.revalidateSavedCredential()
            if (expected === SavedCredentialValidation.Expired) {
                assertTrue(result is SavedCredentialValidation.Expired)
            } else {
                assertTrue(result is SavedCredentialValidation.Unverified)
            }
            assertEquals(1, requests.size)
            assertFalse(requests.any { it.method == "POST" })
            assertEquals(0, credentials.saveCount)
            assertEquals(0, credentials.deleteCount)
        }
    }

    @Test
    fun missingGameAccountConfigAndTransportFailureAreUnverifiedWithoutPost() = runTest {
        val missingConfigRequests = mutableListOf<okhttp3.Request>()
        val missingConfigCredentials = GenshinRecordingStore().also {
            it.put(MiyousheProvider.META.id, sessionJson(uid = "", region = ""))
        }
        val missingConfigProvider = provider(
            responseBody = """{"retcode":0,"data":{"is_sign":false}}""",
            credentials = missingConfigCredentials,
            requests = missingConfigRequests
        )

        assertTrue(missingConfigProvider.revalidateSavedCredential() is SavedCredentialValidation.Unverified)
        assertTrue(missingConfigRequests.isEmpty())

        val networkRequests = mutableListOf<okhttp3.Request>()
        val networkCredentials = GenshinRecordingStore().also { it.put(MiyousheProvider.META.id, sessionJson()) }
        val networkProvider = provider(
            credentials = networkCredentials,
            requests = networkRequests,
            failTransport = true
        )

        assertTrue(networkProvider.revalidateSavedCredential() is SavedCredentialValidation.Unverified)
        assertEquals(1, networkRequests.size)
        assertFalse(networkRequests.any { it.method == "POST" })
        assertEquals(0, networkCredentials.saveCount)
        assertEquals(0, networkCredentials.deleteCount)
    }

    private fun provider(
        responseBody: String = "",
        credentials: GenshinRecordingStore,
        requests: MutableList<okhttp3.Request>,
        failTransport: Boolean = false
    ): MiyousheProvider {
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            if (failTransport) throw IOException("synthetic transport failure")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return MiyousheProvider(
            ApplicationProvider.getApplicationContext<Context>(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private fun sessionJson(uid: String = "123456789", region: String = "cn_gf01") =
        JSONObject().apply {
            put("version", 1)
            put("cookie", "ltoken=${"x".repeat(30)}; ltuid=123456789")
            put("uid", uid)
            put("region", region)
        }.toString()
}

private class GenshinRecordingStore : CredentialStore {
    private val values = mutableMapOf<String, String>()
    var saveCount = 0
    var deleteCount = 0

    fun put(providerId: String, secret: String) {
        values[providerId] = secret
    }

    fun peek(providerId: String): String? = values[providerId]

    override suspend fun save(providerId: String, secret: String) {
        saveCount++
        values[providerId] = secret
    }

    override suspend fun get(providerId: String): String? = values[providerId]

    override suspend fun has(providerId: String): Boolean = providerId in values

    override suspend fun delete(providerId: String) {
        deleteCount++
        values.remove(providerId)
    }

    override suspend fun deleteAll() {
        deleteCount++
        values.clear()
    }
}
