package com.checky.app.ui.screens.connect

import androidx.lifecycle.SavedStateHandle
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.GameAccountConfig
import com.checky.app.domain.GameAccountConfigProvider
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SmsLoginProvider
import com.checky.app.domain.SmsLoginResult
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the connect screen: credential save/delete validation,
 * QR login polling, SMS login and game-account binding — all against fakes,
 * never a real provider.
 */
class ConnectProviderViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Connected state ---

    @Test
    fun initialConnectedStateReadsCredentialStore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val credentials = RecordingCredentialStore()
        credentials.save("fake-session", "token")
        val vm = vmWith(credentials, ScriptedProvider())
        advanceUntilIdle()
        assertTrue(vm.connected.value)
    }

    @Test
    fun initialConnectedStateIsFalseWithoutCredential() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(RecordingCredentialStore(), ScriptedProvider())
        advanceUntilIdle()
        assertFalse(vm.connected.value)
    }

    // --- Manual secret form ---

    @Test
    fun blankSecretShowsErrorAndStoresNothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val credentials = RecordingCredentialStore()
        val vm = vmWith(credentials, ScriptedProvider())
        vm.updateSecret("   ")
        vm.save()
        advanceUntilIdle()

        assertEquals("Enter a token to continue.", vm.error.value)
        assertNull(credentials.vault["fake-session"])
        assertFalse(vm.saved.value)
    }

    @Test
    fun validSecretIsStoredAndInputCleared() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val credentials = RecordingCredentialStore()
        val vm = vmWith(credentials, ScriptedProvider())
        vm.updateSecret("a-valid-token")
        vm.save()
        advanceUntilIdle()

        assertEquals("a-valid-token", credentials.vault["fake-session"])
        assertEquals("", vm.secret.value)
        assertTrue(vm.saved.value)
        assertTrue(vm.connected.value)
        assertNull(vm.error.value)
    }

    @Test
    fun invalidSecretSurfacesReasonWithoutSaving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val credentials = RecordingCredentialStore()
        val provider = ScriptedProvider(
            validation = { CredentialValidation.Invalid("Token looks wrong.") }
        )
        val vm = vmWith(credentials, provider)
        vm.updateSecret("bad")
        vm.save()
        advanceUntilIdle()

        assertEquals("Token looks wrong.", vm.error.value)
        assertNull(credentials.vault["fake-session"])
        assertFalse(vm.connected.value)
    }

    @Test
    fun deleteCredentialsClearsStoreAndDisconnectsProvider() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val credentials = RecordingCredentialStore()
        credentials.save("fake-session", "token")
        val provider = ScriptedProvider()
        val vm = vmWith(credentials, provider)
        advanceUntilIdle()
        assertTrue(vm.connected.value)

        vm.deleteCredentials()
        advanceUntilIdle()

        assertNull(credentials.vault["fake-session"])
        assertEquals(1, provider.disconnectCalls)
        assertFalse(vm.connected.value)
        assertFalse(vm.saved.value)
    }

    // --- QR login ---

    @Test
    fun qrLoginConfirmedOnFirstPollConnects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeQrProvider(listOf(QrLoginPollResult.Confirmed("123456789")))
        )
        vm.startQrLogin()
        advanceUntilIdle()

        assertTrue(vm.connected.value)
        assertTrue(vm.saved.value)
        assertTrue(vm.qrStatus.value!!.contains("123456789"))
        assertNull(vm.qrSession.value)
        assertFalse(vm.qrBusy.value)
    }

    @Test
    fun qrLoginWaitsThenConfirms() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeQrProvider(
                listOf(
                    QrLoginPollResult.Waiting,
                    QrLoginPollResult.Scanned,
                    QrLoginPollResult.Confirmed()
                )
            )
        )
        vm.startQrLogin()
        advanceUntilIdle()

        assertTrue(vm.connected.value)
        assertNull(vm.error.value)
        assertNull(vm.qrSession.value)
    }

    @Test
    fun qrLoginExpiredSurfacesErrorAndClearsSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeQrProvider(listOf(QrLoginPollResult.Waiting, QrLoginPollResult.Expired("二维码已过期")))
        )
        vm.startQrLogin()
        advanceUntilIdle()

        assertEquals("二维码已过期", vm.error.value)
        assertNull(vm.qrSession.value)
        assertFalse(vm.connected.value)
        assertFalse(vm.qrBusy.value)
    }

    @Test
    fun qrLoginOnPlainProviderShowsUnsupportedError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(RecordingCredentialStore(), ScriptedProvider())
        vm.startQrLogin()
        advanceUntilIdle()

        assertEquals("当前服务不支持扫码绑定。", vm.error.value)
        assertNull(vm.qrSession.value)
    }

    @Test
    fun cancelQrLoginResetsTransientState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeQrProvider(listOf(QrLoginPollResult.Confirmed()))
        )
        vm.startQrLogin()
        advanceUntilIdle()

        vm.startQrLogin()
        vm.cancelQrLogin()

        assertNull(vm.qrSession.value)
        assertFalse(vm.qrBusy.value)
        assertNull(vm.qrStatus.value)
    }

    // --- SMS login ---

    @Test
    fun phoneAndCodeInputsAreSanitized() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(RecordingCredentialStore(), FakeSmsProvider())
        vm.updatePhone("abc138-0013 8000")
        vm.updateSmsCode("12ab3456789")

        assertEquals("13800138000", vm.phone.value)
        assertEquals("12345678", vm.smsCode.value)
    }

    @Test
    fun smsSendCodeSuccessMarksSent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeSmsProvider(sendResult = SmsLoginResult.CodeSent())
        )
        vm.updatePhone("13800138000")
        vm.sendSmsCode()
        advanceUntilIdle()

        assertTrue(vm.smsSent.value)
        assertFalse(vm.smsBusy.value)
        assertNull(vm.error.value)
    }

    @Test
    fun smsSendCodeFailureSurfacesMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeSmsProvider(sendResult = SmsLoginResult.Failed("手机号格式不正确。"))
        )
        vm.sendSmsCode()
        advanceUntilIdle()

        assertEquals("手机号格式不正确。", vm.error.value)
        assertFalse(vm.smsSent.value)
    }

    @Test
    fun smsConfirmConnectedMarksConnectedAndSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeSmsProvider(confirmResult = SmsLoginResult.Connected("138****8000"))
        )
        vm.confirmSmsCode()
        advanceUntilIdle()

        assertTrue(vm.connected.value)
        assertTrue(vm.saved.value)
        assertEquals("", vm.smsCode.value)
        assertNull(vm.error.value)
    }

    // --- Game account binding ---

    @Test
    fun existingGameAccountConfigLoadsOnInit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeGameAccountProvider(existing = GameAccountConfig("100001", "cn_gf01"))
        )
        advanceUntilIdle()

        assertEquals("100001", vm.gameUid.value)
        assertEquals("cn_gf01", vm.gameRegion.value)
        assertTrue(vm.gameAccountSaved.value)
    }

    @Test
    fun gameAccountSaveValidMarksSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(RecordingCredentialStore(), FakeGameAccountProvider())
        vm.updateGameUid("100abc001")
        vm.saveGameAccount()
        advanceUntilIdle()

        assertEquals("100001", vm.gameUid.value)
        assertTrue(vm.gameAccountSaved.value)
        assertNull(vm.error.value)
    }

    @Test
    fun gameAccountSaveInvalidSurfacesReason() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeGameAccountProvider(
                saveResult = { _, _ -> CredentialValidation.Invalid("请选择有效的游戏角色。") }
            )
        )
        vm.saveGameAccount()
        advanceUntilIdle()

        assertEquals("请选择有效的游戏角色。", vm.error.value)
        assertFalse(vm.gameAccountSaved.value)
    }

    @Test
    fun editingUidResetsSavedFlag() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vmWith(
            RecordingCredentialStore(),
            FakeGameAccountProvider(existing = GameAccountConfig("1", "cn_gf01"))
        )
        advanceUntilIdle()
        assertTrue(vm.gameAccountSaved.value)

        vm.updateGameUid("2")
        assertFalse(vm.gameAccountSaved.value)
    }

    // --- Fakes ---

    private fun vmWith(
        credentials: RecordingCredentialStore,
        provider: CheckInProvider
    ): ConnectProviderViewModel = ConnectProviderViewModel(
        credentialStore = credentials,
        providers = listOf(provider),
        savedStateHandle = SavedStateHandle(mapOf("serviceId" to provider.meta.id))
    )
}

private fun testMeta(id: String) = ProviderMeta(
    id = id,
    displayName = "Fake $id",
    description = "",
    category = "Test",
    iconKey = "star",
    accentColor = 0xFF000000,
    isEnabledByDefault = false
)

private open class BaseFakeProvider(
    override val meta: ProviderMeta
) : CheckInProvider {
    override fun checkIn(): Flow<CheckInEvent> = flow {
        emit(
            CheckInEvent.Done(
                CheckInResult(
                    serviceId = meta.id,
                    serviceName = meta.displayName,
                    outcome = CheckInOutcome.Success("ok", "SUCCESS", Reward.empty()),
                    timestamp = 0L
                )
            )
        )
    }

    override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
}

private class ScriptedProvider(
    val validation: (String) -> CredentialValidation = { CredentialValidation.Valid }
) : CheckInProvider by BaseFakeProvider(testMeta("fake-session")) {
    var disconnectCalls = 0
    override suspend fun validateCredentials(secret: String) = validation(secret)
    override fun supportsDisconnect() = true
    override suspend fun disconnect() {
        disconnectCalls++
    }
}

private class FakeQrProvider(
    private val polls: List<QrLoginPollResult>
) : BaseFakeProvider(testMeta("fake-qr")), QrLoginProvider {
    private var pollIndex = 0
    override suspend fun createQrLoginSession() = QrLoginSession("qr-payload", "session-1")
    override suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult =
        polls[pollIndex.coerceAtMost(polls.size - 1)].also { pollIndex++ }
}

private class FakeSmsProvider(
    private val sendResult: SmsLoginResult = SmsLoginResult.CodeSent(),
    private val confirmResult: SmsLoginResult = SmsLoginResult.Connected()
) : BaseFakeProvider(testMeta("fake-sms")), SmsLoginProvider {
    override suspend fun isSmsConnected(): Boolean = false
    override suspend fun sendSmsCode(phone: String): SmsLoginResult = sendResult
    override suspend fun confirmSmsCode(phone: String, code: String): SmsLoginResult = confirmResult
}

private class FakeGameAccountProvider(
    private val existing: GameAccountConfig? = null,
    private val saveResult: (String, String) -> CredentialValidation = { _, _ -> CredentialValidation.Valid }
) : BaseFakeProvider(testMeta("fake-game")), GameAccountConfigProvider {
    override suspend fun gameAccountConfig(): GameAccountConfig? = existing
    override suspend fun saveGameAccountConfig(uid: String, region: String): CredentialValidation =
        saveResult(uid, region)
}

private class RecordingCredentialStore : CredentialStore {
    val vault = mutableMapOf<String, String>()
    override suspend fun save(providerId: String, secret: String) {
        vault[providerId] = secret
    }
    override suspend fun get(providerId: String): String? = vault[providerId]
    override suspend fun has(providerId: String): Boolean = vault.containsKey(providerId)
    override suspend fun delete(providerId: String) {
        vault.remove(providerId)
    }
    override suspend fun deleteAll() {
        vault.clear()
    }
}
