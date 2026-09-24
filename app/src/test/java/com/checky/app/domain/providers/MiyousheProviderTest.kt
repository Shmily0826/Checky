package com.checky.app.domain.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.FakeCredentialStore
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
    fun existingGameSessionSharesOnlyToMissingCompatibleTargetsWithoutPromotingHealth() = runTest {
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        credentials.save(
            MiyousheProvider.META.id,
            sessionJson(cookie = gameCookie(), uid = "123456789", region = "cn_gf01")
        )
        health.set(MiyousheProvider.META.id, com.checky.app.domain.AuthHealth.VALID)

        val copied = MiyousheCredentialSharing.reconcile(credentials, health)

        assertEquals(setOf(MiyousheZzzProvider.META.id), copied)
        assertTrue(credentials.has(MiyousheZzzProvider.META.id))
        assertFalse(credentials.has(MiyousheCommunityProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.UNVERIFIED, health.get(MiyousheZzzProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.VALID, health.get(MiyousheProvider.META.id))
        assertFalse(credentials.get(MiyousheZzzProvider.META.id).orEmpty().contains("\"uid\""))
    }

    @Test
    fun communitySessionSharesToGamesButGameSessionCannotOverwriteCommunity() = runTest {
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        credentials.save(MiyousheCommunityProvider.META.id, completeCommunityCookie())
        val existingZzzSession = gameCookie(accountId = "account-a")
        credentials.save(MiyousheZzzProvider.META.id, existingZzzSession)
        health.set(MiyousheZzzProvider.META.id, com.checky.app.domain.AuthHealth.EXPIRED)

        val copied = MiyousheCredentialSharing.reconcile(
            credentials,
            health,
            MiyousheCommunityProvider.META.id
        )

        assertEquals(setOf(MiyousheProvider.META.id), copied)
        assertEquals(existingZzzSession, credentials.get(MiyousheZzzProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.EXPIRED, health.get(MiyousheZzzProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.UNVERIFIED, health.get(MiyousheProvider.META.id))

        credentials.save(MiyousheProvider.META.id, "different-account-cookie")
        assertTrue(MiyousheCredentialSharing.reconcile(credentials, health).isEmpty())
        assertEquals("different-account-cookie", credentials.get(MiyousheProvider.META.id))
    }

    @Test
    fun explicitMissingSourceDoesNotFallBackToAnotherSavedSession() = runTest {
        val credentials = FakeCredentialStore().also {
            it.save(MiyousheProvider.META.id, gameCookie(accountId = "account-a"))
        }
        val health = FakeAuthHealthStore()

        assertTrue(
            MiyousheCredentialSharing.reconcile(
                credentials,
                health,
                MiyousheCommunityProvider.META.id
            ).isEmpty()
        )
        assertFalse(credentials.has(MiyousheZzzProvider.META.id))
        assertFalse(credentials.has(MiyousheCommunityProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.UNVERIFIED, health.get(MiyousheZzzProvider.META.id))
    }

    @Test
    fun explicitUnusableSourceDoesNotFallBackToAnotherSavedSession() = runTest {
        val credentials = FakeCredentialStore().also {
            it.save(MiyousheCommunityProvider.META.id, "malformed-session")
            it.save(MiyousheProvider.META.id, gameCookie(accountId = "account-a"))
        }
        val health = FakeAuthHealthStore()

        assertTrue(
            MiyousheCredentialSharing.reconcile(
                credentials,
                health,
                MiyousheCommunityProvider.META.id
            ).isEmpty()
        )
        assertEquals("malformed-session", credentials.get(MiyousheCommunityProvider.META.id))
        assertFalse(credentials.has(MiyousheZzzProvider.META.id))
        assertEquals(com.checky.app.domain.AuthHealth.UNVERIFIED, health.get(MiyousheZzzProvider.META.id))
    }

    @Test
    fun multipleLocalAccountIdentitiesFailClosedWithoutCopying() = runTest {
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        credentials.save(MiyousheProvider.META.id, gameCookie(accountId = "account-a"))
        credentials.save(MiyousheZzzProvider.META.id, gameCookie(accountId = "account-b"))

        assertTrue(MiyousheCredentialSharing.reconcile(credentials, health).isEmpty())
        assertFalse(credentials.has(MiyousheCommunityProvider.META.id))
    }

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

    private fun sessionJson(
        cookie: String = "ltoken=${"x".repeat(30)}; ltuid=123456789",
        uid: String = "123456789",
        region: String = "cn_gf01"
    ) =
        JSONObject().apply {
            put("version", 1)
            put("cookie", cookie)
            put("uid", uid)
            put("region", region)
        }.toString()

    private fun gameCookie(accountId: String = "account-a") =
        "ltoken=${"x".repeat(30)}; ltuid=$accountId; cookie_token=${"y".repeat(20)}; account_id=$accountId"

    private fun completeCommunityCookie() =
        listOf(
            "stoken=stoken-a",
            "stoken_v2=stoken-a",
            "mid=mid-a",
            "stuid=account-a",
            "account_id=account-a",
            "account_id_v2=account-a",
            "cookie_token_v2=cookie-a",
            "ltoken=ltoken-a",
            "ltoken_v2=ltoken-a",
            "ltuid=account-a",
            "ltmid_v2=account-a"
        ).joinToString("; ")
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
