package com.checky.app.domain.providers

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginSession
import com.checky.app.ui.screens.connect.ConnectProviderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MiyousheReconnectTest {
    private val game = MiyousheProvider.META.id
    private val community = MiyousheCommunityProvider.META.id
    private val otherGame = MiyousheZzzProvider.META.id

    @Test
    fun gameScreenQrRefreshesSavedCookieThenReadOnlyCheckRestoresConnection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = FakeCredentialStore()
            val health = FakeAuthHealthStore()
            val fresh = cookie("new")
            store.save(game, session(cookie("old")))
            health.set(game, AuthHealth.EXPIRED)
            val requests = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = if (request.url.encodedPath.endsWith("/info")) {
                    if (request.header("Cookie") == fresh) {
                        """{"retcode":0,"data":{"is_sign":false}}"""
                    } else """{"retcode":-100}"""
                } else """{"retcode":0,"data":{"list":[]}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            val gameProvider = MiyousheProvider(ApplicationProvider.getApplicationContext<Context>(), store, client)
            val qr = object : CheckInProvider by gameProvider, QrLoginProvider {
                override val meta = MiyousheCommunityProvider.META
                override suspend fun createQrLoginSession() = QrLoginSession("synthetic-qr", "synthetic-session")
                override suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult {
                    store.save(community, fresh)
                    return QrLoginPollResult.Confirmed("account-a")
                }
            }
            val vm = ConnectProviderViewModel(store, listOf(gameProvider, qr),
                SavedStateHandle(mapOf("serviceId" to game, "reconnect" to true)), health)
            advanceUntilIdle()
            vm.startQrLogin()
            advanceUntilIdle()
            assertEquals(fresh, JSONObject(store.get(game)!!).getString("cookie"))
            assertEquals("123456789", JSONObject(store.get(game)!!).getString("uid"))
            assertFalse(vm.connected.value)
            assertEquals(AuthHealth.UNVERIFIED, health.get(game))

            vm.verifySavedCredential()
            advanceUntilIdle()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.connected.first { it } }
            }
            assertTrue(vm.connected.value)
            assertEquals(AuthHealth.VALID, health.get(game))
            assertTrue("Observed paths: ${requests.map { it.url.encodedPath }}",
                requests.any { it.url.encodedPath.endsWith("/info") })
            assertTrue(requests.all { it.method == "GET" && it.header("Cookie") == fresh })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun explicitQrRefreshReplacesOnlyTargetCookieAndPreservesRoleWithoutPromotingHealth() = runTest {
        val store = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val fresh = cookie("new")
        val other = session(cookie("other"))
        store.save(community, fresh)
        store.save(game, session(cookie("old")))
        store.save(otherGame, other)
        health.set(game, AuthHealth.EXPIRED)
        health.set(otherGame, AuthHealth.VALID)

        assertEquals(setOf(game), MiyousheCredentialSharing.reconcile(store, health, community, game))
        val saved = JSONObject(store.get(game)!!)
        assertEquals(fresh, saved.getString("cookie"))
        assertEquals("123456789", saved.getString("uid"))
        assertEquals("cn_gf01", saved.getString("region"))
        assertEquals(AuthHealth.UNVERIFIED, health.get(game))
        assertEquals(other, store.get(otherGame))
        assertEquals(AuthHealth.VALID, health.get(otherGame))
        assertTrue(MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(store, game))
    }

    @Test
    fun ordinaryReconcileNeverReplacesExistingCookie() = runTest {
        val store = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val old = session(cookie("old"))
        store.save(community, cookie("new"))
        store.save(game, old)
        MiyousheCredentialSharing.reconcile(store, health, community)
        assertEquals(old, store.get(game))
        assertFalse(MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(store, game))
    }

    @Test
    fun conflictingOrUnknownTargetIdentityAndUnsupportedSessionNeverGetOverwritten() = runTest {
        val targets = listOf(
            session(cookie("old", "different-account")),
            "malformed-session",
            session(cookie("old")).replace("\"version\":1", "\"version\":2"),
            session(cookie("old") + "; login_uid=different-account")
        )
        targets.forEach { old ->
            val store = FakeCredentialStore()
            val health = FakeAuthHealthStore()
            store.save(community, cookie("new"))
            store.save(game, old)
            MiyousheCredentialSharing.reconcile(store, health, community, game)
            assertEquals(old, store.get(game))
            assertFalse(MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(store, game))
        }
    }

    @Test
    fun incompleteQrSourceCannotRefreshExistingGame() = runTest {
        val store = FakeCredentialStore()
        val old = session(cookie("old"))
        store.save(community, "ltoken=synthetic; ltuid=account-a")
        store.save(game, old)
        MiyousheCredentialSharing.reconcile(store, FakeAuthHealthStore(), community, game)
        assertEquals(old, store.get(game))
        assertFalse(MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(store, game))
    }

    @Test
    fun legacySameAccountCookieCanBeRefreshed() = runTest {
        val store = FakeCredentialStore()
        store.save(community, cookie("new"))
        store.save(game, "ltoken=old-synthetic; ltuid=account-a")
        MiyousheCredentialSharing.reconcile(store, FakeAuthHealthStore(), community, game)
        val saved = JSONObject(store.get(game)!!)
        assertEquals(cookie("new"), saved.getString("cookie"))
        assertFalse(saved.has("uid"))
    }

    @Test
    fun saveFailurePreservesOldCredentialAndInvalidatesPreviousHealth() = runTest {
        val backing = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val old = session(cookie("old"))
        backing.save(community, cookie("new"))
        backing.save(game, old)
        health.set(game, AuthHealth.VALID)
        val store = object : CredentialStore by backing {
            override suspend fun save(providerId: String, secret: String) {
                error("synthetic storage failure")
            }
        }
        val result = runCatching { MiyousheCredentialSharing.reconcile(store, health, community, game) }
        assertTrue(result.isFailure)
        assertEquals(old, backing.get(game))
        assertEquals(AuthHealth.UNVERIFIED, health.get(game))
        assertFalse(MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(store, game))
    }

    private fun session(cookie: String) = JSONObject().apply {
        put("version", 1)
        put("cookie", cookie)
        put("uid", "123456789")
        put("region", "cn_gf01")
    }.toString()

    private fun cookie(label: String, account: String = "account-a") = listOf(
        "stoken=$label-synthetic", "stoken_v2=$label-synthetic", "mid=synthetic-mid",
        "stuid=$account", "account_id=$account", "account_id_v2=$account",
        "cookie_token_v2=$label-synthetic", "ltoken=$label-synthetic", "ltoken_v2=$label-synthetic",
        "ltuid=$account", "ltmid_v2=synthetic-mid"
    ).joinToString("; ")
}
