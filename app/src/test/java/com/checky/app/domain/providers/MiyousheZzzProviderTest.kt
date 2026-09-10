package com.checky.app.domain.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.model.CheckInOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiyousheZzzProviderTest {

    @Test
    fun infoUsesZzzContractAndAlreadySignedDoesNotPost() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val provider = provider(
            responseBodies = listOf("""{"retcode":0,"data":{"is_sign":true}}"""),
            requests = requests
        )
        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.AlreadyCompleted)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("act-nap-api.mihoyo.com", request.url.host)
        assertEquals("/event/luna/zzz/info", request.url.encodedPath)
        assertEquals("zh-cn", request.url.queryParameter("lang"))
        assertEquals("e202406242138391", request.url.queryParameter("act_id"))
        assertEquals("prod_gf_cn", request.url.queryParameter("region"))
        assertEquals("123456789", request.url.queryParameter("uid"))
        assertRequiredZzzHeaders(request)
        assertEquals(null, request.header("DS"))
    }

    @Test
    fun unsignedInfoPostsExactZzzSignBody() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val provider = provider(
            responseBodies = listOf(
                """{"retcode":0,"data":{"is_sign":false}}""",
                """{"retcode":0}"""
            ),
            requests = requests
        )
        val done = provider.checkIn().first { it is CheckInEvent.Done } as CheckInEvent.Done

        assertTrue(done.result.outcome is CheckInOutcome.Success)
        assertEquals(2, requests.size)
        val sign = requests[1]
        assertEquals("POST", sign.method)
        assertEquals("act-nap-api.mihoyo.com", sign.url.host)
        assertEquals("/event/luna/zzz/sign", sign.url.encodedPath)
        assertEquals(null, sign.url.query)
        assertRequiredZzzHeaders(sign)
        val body = Buffer().also { sign.body?.writeTo(it) }.readUtf8()
        assertEquals(
            "{\"act_id\":\"e202406242138391\",\"region\":\"prod_gf_cn\",\"uid\":\"123456789\"}",
            body
        )
        assertEquals(null, sign.header("DS"))
    }

    @Test
    fun roleDiscoverySelectsOnlyNapCnAndKeepsItsSessionOwner() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val provider = provider(
            responseBodies = listOf(
                """{"retcode":0,"data":{"list":[
                    {"game_biz":"hk4e_cn","game_uid":"111111111","region":"cn_gf01"},
                    {"game_biz":"nap_cn","game_uid":"123456789","region":"prod_gf_cn","region_name":"国服","nickname":"代理人","level":20}
                ]}}"""
            ),
            requests = requests
        )

        val roles = provider.fetchGameRoles()

        assertEquals(1, roles.size)
        assertEquals("123456789", roles.single().config.uid)
        assertEquals("prod_gf_cn", roles.single().config.region)
        assertEquals("miyoushe_zzz_experimental", provider.meta.id)
        assertFalse(requests.single().url.host == "act-nap-api.mihoyo.com")
        assertEquals("nap_cn", requests.single().url.queryParameter("game_biz"))
        assertValidRoleDs(requests.single())
    }

    @Test
    fun roleDiscoveryKeepsValidEightDigitNapCnUid() = runTest {
        val provider = provider(
            responseBodies = listOf(
                """{"retcode":0,"data":{"list":[
                    {"game_biz":"nap_cn","game_uid":"12345678","region":"prod_gf_cn"}
                ]}}"""
            ),
            requests = mutableListOf()
        )

        val roles = provider.fetchGameRoles()

        assertEquals(1, roles.size)
        assertEquals("12345678", roles.single().config.uid)
        assertEquals("prod_gf_cn", roles.single().config.region)
    }

    @Test
    fun savedSessionValidationUsesRoleDs() = runTest {
        val requests = mutableListOf<okhttp3.Request>()
        val provider = provider(
            responseBodies = listOf("""{"retcode":0,"data":{"list":[]}}"""),
            requests = requests
        )

        assertTrue(provider.revalidateSavedCredential() is com.checky.app.domain.SavedCredentialValidation.Valid)
        assertValidRoleDs(requests.single())
    }

    @Test
    fun roleDiscoveryPropagatesProviderFailureInsteadOfReturningEmpty() = runTest {
        val provider = provider(
            responseBodies = listOf("""{"retcode":-100}"""),
            requests = mutableListOf()
        )

        var thrown = false
        try {
            provider.fetchGameRoles()
        } catch (_: IllegalStateException) {
            thrown = true
        }

        assertTrue(thrown)
    }

    @Test
    fun authRiskAlreadyAndMalformedResponsesFailClosed() {
        assertTrue(mapMiyousheZzzResponse("""{"retcode":-100}""", false) is CheckInOutcome.AuthenticationExpired)
        assertTrue(mapMiyousheZzzResponse("""{"retcode":10001}""", false) is CheckInOutcome.AuthenticationExpired)
        assertTrue(mapMiyousheZzzResponse("""{"retcode":-5003}""", false) is CheckInOutcome.AlreadyCompleted)
        assertTrue(mapMiyousheZzzResponse("""{"retcode":1034,"message":"geetest"}""", false) is CheckInOutcome.ActionRequired)
        assertTrue(mapMiyousheZzzResponse("""{"retcode":0,"risk_code":1}""", false) is CheckInOutcome.ActionRequired)
        assertTrue(mapMiyousheZzzResponse("""{"retcode":0,"data":{}}""", true) is CheckInOutcome.PermanentFailure)
        assertTrue(mapMiyousheZzzResponse("not-json", true) is CheckInOutcome.PermanentFailure)
        assertTrue(mapMiyousheZzzResponse("{\"retcode\":999}", false) is CheckInOutcome.TemporaryFailure)
        assertTrue(mapMiyousheZzzResponse("{\"retcode\":0}", true) is CheckInOutcome.PermanentFailure)
        assertTrue(
            mapMiyousheZzzResponse("{\"retcode\":0,\"data\":{\"is_sign\":\"true\"}}", true) is
                CheckInOutcome.PermanentFailure
        )
    }

    @Test
    fun saveConfigRoundTripsUidAndRegionUnderZzzOwner() = runTest {
        val credentials = RecordingStore()
        credentials.save(MiyousheZzzProvider.META.id, validCookie())
        val provider = MiyousheZzzProvider(
            ApplicationProvider.getApplicationContext<Context>(), credentials, OkHttpClient()
        )

        assertTrue(provider.saveGameAccountConfig("123456789", "prod_gf_cn") is com.checky.app.domain.CredentialValidation.Valid)
        assertEquals(
            "123456789",
            provider.gameAccountConfig()?.uid
        )
        assertEquals("prod_gf_cn", provider.gameAccountConfig()?.region)
        assertTrue(credentials.has(MiyousheZzzProvider.META.id))
    }

    private fun provider(
        responseBodies: List<String>,
        requests: MutableList<okhttp3.Request>
    ): MiyousheZzzProvider {
        val responses = responseBodies.iterator()
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responses.next().toResponseBody("application/json".toMediaType()))
                .build()
        }
        val credentials = RecordingStore().also { it.put(MiyousheZzzProvider.META.id, sessionJson()) }
        return MiyousheZzzProvider(
            ApplicationProvider.getApplicationContext<Context>(),
            credentials,
            OkHttpClient.Builder().addInterceptor(interceptor).build()
        )
    }

    private fun sessionJson() = JSONObject().apply {
        put("version", 1)
        put("cookie", validCookie())
        put("uid", "123456789")
        put("region", "prod_gf_cn")
    }.toString()

    private fun validCookie() = "ltoken=${"x".repeat(30)}; ltuid=123456789"

    private fun assertValidRoleDs(request: okhttp3.Request) {
        val ds = checkNotNull(request.header("DS")).split(',')
        assertEquals(3, ds.size)
        val t = ds[0].toLong()
        val r = ds[1].toInt()
        assertTrue(t > 0)
        assertTrue(r in 100001..200000)
        val query = request.url.query.orEmpty().takeIf(String::isNotEmpty)
            ?.split('&')?.sorted()?.joinToString("&").orEmpty()
        val main = "salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=$t&r=$r&b=&q=$query"
        val digest = MessageDigest.getInstance("MD5")
            .digest(main.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(digest, ds[2])
        assertEquals("5", request.header("x-rpc-client_type"))
    }

    private fun assertRequiredZzzHeaders(request: okhttp3.Request) {
        assertEquals(validCookie(), request.header("Cookie"))
        assertEquals("2.70.1", request.header("x-rpc-app_version"))
        assertEquals("5", request.header("x-rpc-client_type"))
        assertEquals("12", request.header("x-rpc-sys_version"))
        assertEquals("android", request.header("x-rpc-platform"))
        assertEquals("miyousheluodi", request.header("x-rpc-channel"))
        assertEquals("zzz", request.header("x-rpc-signgame"))
        assertTrue(!request.header("x-rpc-device_id").isNullOrBlank())
        assertEquals("Android", request.header("x-rpc-device_name"))
        assertEquals("123456789", request.header("x-rpc-device_model"))
        assertEquals("https://app.mihoyo.com", request.header("Origin"))
        assertEquals("https://app.mihoyo.com/", request.header("Referer"))
        assertEquals("com.mihoyo.hyperion", request.header("X-Requested-With"))
    }
}

private class RecordingStore : CredentialStore {
    private val values = mutableMapOf<String, String>()
    fun put(providerId: String, secret: String) { values[providerId] = secret }
    override suspend fun save(providerId: String, secret: String) { values[providerId] = secret }
    override suspend fun get(providerId: String): String? = values[providerId]
    override suspend fun has(providerId: String): Boolean = providerId in values
    override suspend fun delete(providerId: String) { values.remove(providerId) }
    override suspend fun deleteAll() { values.clear() }
}
