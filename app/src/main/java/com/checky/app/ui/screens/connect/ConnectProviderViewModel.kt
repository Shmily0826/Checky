package com.checky.app.ui.screens.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.GameAccountConfig
import com.checky.app.domain.GameAccountConfigProvider
import com.checky.app.domain.GameRole
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SmsLoginProvider
import com.checky.app.domain.SmsLoginResult
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.SavedCredentialRevalidator
import com.checky.app.domain.SavedCredentialValidation
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.providers.MiyousheCredentialSharing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

internal enum class SavedCredentialCheckState {
    IDLE,
    RUNNING,
    VALID,
    EXPIRED,
    UNVERIFIED
}

/**
 * Connect screen: lets the user provide (or delete) the credential a
 * credential-requiring provider needs. For the MVP this is a fake token form.
 * Secrets are validated before saving, stored via [CredentialStore], and
 * never written to logs.
 */
@HiltViewModel
class ConnectProviderViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val providers: @JvmSuppressWildcards List<CheckInProvider>,
    savedStateHandle: SavedStateHandle,
    private val authHealthStore: AuthHealthStore,
) : ViewModel() {

    val serviceId: String = savedStateHandle.get<String>("serviceId") ?: ""
    private val reconnectEntry = savedStateHandle.get<Boolean>("reconnect") == true
    private var reconnectPending = reconnectEntry
    val provider: CheckInProvider? = providers.firstOrNull { it.meta.id == serviceId }
    val meta: ProviderMeta? = provider?.meta
    private val canonicalMiyousheQrProvider: QrLoginProvider? =
        providers.firstOrNull { it.meta.id == MIYOUSHE_COMMUNITY_ID } as? QrLoginProvider
    val qrProvider: QrLoginProvider? = if (
        provider?.meta?.id?.let(MIYOUSHE_GAME_IDS::contains) == true &&
            canonicalMiyousheQrProvider != null
    ) {
        canonicalMiyousheQrProvider
    } else {
        provider as? QrLoginProvider
    }
    val usesCanonicalMiyousheQr: Boolean =
        provider?.meta?.id?.let(MIYOUSHE_GAME_IDS::contains) == true &&
            canonicalMiyousheQrProvider != null
    val isMiyousheProvider: Boolean =
        provider?.meta?.id?.let(MIYOUSHE_PROVIDER_IDS::contains) == true
    val gameAccountProvider: GameAccountConfigProvider? = provider as? GameAccountConfigProvider
    val smsProvider: SmsLoginProvider? = provider as? SmsLoginProvider

    private val _phone = MutableStateFlow("")
    val phone: StateFlow<String> = _phone.asStateFlow()
    private val _smsCode = MutableStateFlow("")
    val smsCode: StateFlow<String> = _smsCode.asStateFlow()
    private val _smsSent = MutableStateFlow(false)
    val smsSent: StateFlow<Boolean> = _smsSent.asStateFlow()
    private val _smsBusy = MutableStateFlow(false)
    val smsBusy: StateFlow<Boolean> = _smsBusy.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _authHealth = MutableStateFlow<AuthHealth?>(
        if (reconnectEntry && provider != null) AuthHealth.UNVERIFIED else null
    )
    val authHealth: StateFlow<AuthHealth?> = _authHealth.asStateFlow()
    private val _initialConnectionStateResolved = MutableStateFlow(reconnectEntry && provider != null)
    internal val initialConnectionStateResolved: StateFlow<Boolean> =
        _initialConnectionStateResolved.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _savedCredentialCheckState = MutableStateFlow(SavedCredentialCheckState.IDLE)
    internal val savedCredentialCheckState: StateFlow<SavedCredentialCheckState> =
        _savedCredentialCheckState.asStateFlow()

    private val _communityNeedsFullSession = MutableStateFlow(false)
    val communityNeedsFullSession: StateFlow<Boolean> = _communityNeedsFullSession.asStateFlow()

    val supportsReadOnlyRevalidation: Boolean = provider is SavedCredentialRevalidator

    /** Only explicitly allowlisted providers expose the strict read-only check. */
    val supportsConnectedSavedCredentialCheck: Boolean =
        provider is SavedCredentialRevalidator && provider.meta.id in STRICT_READ_ONLY_PROVIDER_IDS

    private val _secret = MutableStateFlow("")
    val secret: StateFlow<String> = _secret.asStateFlow()

    private val _showSecret = MutableStateFlow(false)
    val showSecret: StateFlow<Boolean> = _showSecret.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _error = MutableStateFlow<ConnectError?>(null)
    val error: StateFlow<ConnectError?> = _error.asStateFlow()

    private val _qrSession = MutableStateFlow<QrLoginSession?>(null)
    val qrSession: StateFlow<QrLoginSession?> = _qrSession.asStateFlow()

    private val _qrStatus = MutableStateFlow<QrUiStatus?>(null)
    val qrStatus: StateFlow<QrUiStatus?> = _qrStatus.asStateFlow()

    private val _qrBusy = MutableStateFlow(false)
    val qrBusy: StateFlow<Boolean> = _qrBusy.asStateFlow()

    private val _gameUid = MutableStateFlow("")
    val gameUid: StateFlow<String> = _gameUid.asStateFlow()

    private val _gameRegion = MutableStateFlow(
        if (meta?.id == "miyoushe_zzz_experimental") "" else "cn_gf01"
    )
    val gameRegion: StateFlow<String> = _gameRegion.asStateFlow()

    private val _savingGameAccount = MutableStateFlow(false)
    val savingGameAccount: StateFlow<Boolean> = _savingGameAccount.asStateFlow()

    private val _gameAccountSaved = MutableStateFlow(false)
    val gameAccountSaved: StateFlow<Boolean> = _gameAccountSaved.asStateFlow()

    private val _gameRoles = MutableStateFlow<List<GameRole>>(emptyList())
    val gameRoles: StateFlow<List<GameRole>> = _gameRoles.asStateFlow()

    private val _showZzzRoleSetup = MutableStateFlow(false)
    val showZzzRoleSetup: StateFlow<Boolean> = _showZzzRoleSetup.asStateFlow()

    private val _gameRolesBusy = MutableStateFlow(false)
    val gameRolesBusy: StateFlow<Boolean> = _gameRolesBusy.asStateFlow()

    private var qrJob: Job? = null

    init {
        viewModelScope.launch {
            if (isMiyousheProvider) {
                MiyousheCredentialSharing.reconcile(credentialStore, authHealthStore)
                _communityNeedsFullSession.value =
                    MiyousheCredentialSharing.communityNeedsCanonicalQr(credentialStore)
            }
            if (reconnectEntry) {
                enterReconnectState()
            } else {
                _authHealth.value = provider?.let {
                    ProviderConnectionGate.health(it, credentialStore, authHealthStore)
                }
                _connected.value = _authHealth.value == AuthHealth.VALID
            }
            _initialConnectionStateResolved.value = true
            val gameAccountConfig = gameAccountProvider?.gameAccountConfig()
            gameAccountConfig?.let { config ->
                _gameUid.value = config.uid
                _gameRegion.value = config.region
                _gameAccountSaved.value = true
            }
            _showZzzRoleSetup.value = canShowZzzRoleSetup(gameAccountConfig)
            // Zero-input path: right after a fresh connect, look up the roles
            // bound to the account so the user never types a UID.
            if (gameAccountProvider != null && _connected.value && _gameUid.value.isBlank()) {
                fetchGameRoles()
            }
        }
    }

    fun updateSecret(value: String) {
        _secret.value = value
        _error.value = null
    }

    fun updatePhone(value: String) { _phone.value = value.filter(Char::isDigit).take(11); _error.value = null }
    fun updateSmsCode(value: String) { _smsCode.value = value.filter(Char::isDigit).take(8); _error.value = null }

    fun refreshConnection() {
        val target = provider ?: return
        _savedCredentialCheckState.value = SavedCredentialCheckState.IDLE
        viewModelScope.launch {
            if (reconnectPending) {
                enterReconnectState()
            } else {
                val health = ProviderConnectionGate.health(target, credentialStore, authHealthStore)
                _authHealth.value = health
                _connected.value = health == AuthHealth.VALID
                _showZzzRoleSetup.value = canShowZzzRoleSetup(gameAccountProvider?.gameAccountConfig())
            }
        }
    }

    fun sendSmsCode() {
        val target = smsProvider ?: return
        viewModelScope.launch {
            _smsBusy.value = true
            beginSmsReconnect()
            when (val result = target.sendSmsCode(_phone.value)) {
                is SmsLoginResult.CodeSent -> { _smsSent.value = true; _error.value = null }
                is SmsLoginResult.Failed -> _error.value = ConnectError.Provider(result.message)
                is SmsLoginResult.Connected -> Unit
            }
            _smsBusy.value = false
        }
    }

    fun confirmSmsCode() {
        val target = smsProvider ?: return
        viewModelScope.launch {
            _smsBusy.value = true
            beginSmsReconnect()
            when (val result = target.confirmSmsCode(_phone.value, _smsCode.value)) {
                    is SmsLoginResult.Connected -> {
                    markProviderConfirmedValid()
                    _saved.value = true; _error.value = null
                    _smsCode.value = ""
                }
                is SmsLoginResult.Failed -> _error.value = ConnectError.Provider(result.message)
                is SmsLoginResult.CodeSent -> Unit
            }
            _smsBusy.value = false
        }
    }

    fun toggleSecretVisibility() {
        _showSecret.value = !_showSecret.value
    }

    /** Explicit foreground evidence path for structurally saved credentials. */
    fun verifySavedCredential() {
        val target = provider ?: return
        val canVerifyConnected = _authHealth.value == AuthHealth.VALID && supportsConnectedSavedCredentialCheck
        val canVerifySaved = _authHealth.value == AuthHealth.UNVERIFIED ||
            _authHealth.value == AuthHealth.EXPIRED
        if ((!canVerifyConnected && !canVerifySaved) ||
            !supportsConnectedSavedCredentialCheck || _verifying.value
        ) return
        viewModelScope.launch {
            _verifying.value = true
            _savedCredentialCheckState.value = SavedCredentialCheckState.RUNNING
            _error.value = null
            try {
                val result = try {
                    (target as? SavedCredentialRevalidator)?.revalidateSavedCredential()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                when (result) {
                    SavedCredentialValidation.Valid -> {
                        _savedCredentialCheckState.value = SavedCredentialCheckState.VALID
                        _showZzzRoleSetup.value = false
                        markProviderConfirmedValid()
                    }
                    SavedCredentialValidation.Expired -> {
                        _savedCredentialCheckState.value = SavedCredentialCheckState.EXPIRED
                        _showZzzRoleSetup.value = false
                        authHealthStore.set(target.credentialOwnerId, AuthHealth.EXPIRED)
                        _authHealth.value = AuthHealth.EXPIRED
                        _connected.value = false
                    }
                    is SavedCredentialValidation.Unverified, null -> {
                        _savedCredentialCheckState.value = SavedCredentialCheckState.UNVERIFIED
                        authHealthStore.set(target.credentialOwnerId, AuthHealth.UNVERIFIED)
                        _authHealth.value = AuthHealth.UNVERIFIED
                        _connected.value = false
                        _showZzzRoleSetup.value = canShowZzzRoleSetup(gameAccountProvider?.gameAccountConfig())
                        val reason = (result as? SavedCredentialValidation.Unverified)?.reason
                        val safeZzzError = if (target.meta.id == MIYOUSHE_ZZZ_ID) {
                            SAFE_ZZZ_LOCAL_SESSION_ERRORS[reason]
                                ?: if (reason in SAFE_ZZZ_PROVIDER_REASONS) {
                                    ConnectAppError.ZZZ_PROVIDER_NOT_CONFIRMED
                                } else {
                                    null
                                }
                        } else {
                            null
                        }
                        _error.value = safeZzzError?.let(ConnectError::App)
                            ?: ConnectError.App(ConnectAppError.VERIFICATION_FAILED)
                    }
                }
            } finally {
                _verifying.value = false
            }
        }
    }

    fun save() {
        val target = provider ?: return
        val value = _secret.value
        if (value.isBlank()) {
            _error.value = ConnectError.App(ConnectAppError.TOKEN_REQUIRED)
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                when (val validation = target.validateCredentials(value)) {
                    is CredentialValidation.Valid -> {
                        credentialStore.save(target.credentialOwnerId, value)
                        authHealthStore.set(target.credentialOwnerId, AuthHealth.UNVERIFIED)
                        _authHealth.value = AuthHealth.UNVERIFIED
                        _secret.value = ""
                        _error.value = null
                        _saved.value = true
                        _connected.value = false
                    }
                    is CredentialValidation.Invalid -> _error.value = ConnectError.Provider(validation.reason)
                }
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * Called once when the connect screen opens: QR-capable providers start
     * their login session immediately so the default path needs no typing
     * and no button press. Skipped when already connected or a session is
     * already active.
     */
    fun maybeStartQrLoginAutomatically() {
        if (!_initialConnectionStateResolved.value) return
        if (supportsReadOnlyRevalidation &&
            (_authHealth.value == AuthHealth.UNVERIFIED || _authHealth.value == AuthHealth.EXPIRED)
        ) return
        if (
            qrProvider != null &&
            !_connected.value &&
            _qrSession.value == null &&
            !_qrBusy.value &&
            _error.value == null
        ) {
            startQrLogin()
        }
    }

    fun startQrLogin() {
        val target = qrProvider ?: run {
            _error.value = ConnectError.App(ConnectAppError.QR_UNSUPPORTED)
            return
        }
        qrJob?.cancel()
        _error.value = null
        _qrStatus.value = QrUiStatus.Generating
        _qrBusy.value = true
        qrJob = viewModelScope.launch {
            try {
                val session = target.createQrLoginSession()
                _qrSession.value = session
                repeat(90) {
                    when (val result = target.pollQrLogin(session)) {
                        QrLoginPollResult.Waiting -> _qrStatus.value = QrUiStatus.Waiting
                        QrLoginPollResult.Scanned -> _qrStatus.value = QrUiStatus.Scanned
                        is QrLoginPollResult.Confirmed -> {
                            if (isMiyousheProvider) {
                                val sourceProviderId = if (usesCanonicalMiyousheQr) {
                                    MIYOUSHE_COMMUNITY_ID
                                } else {
                                    provider?.meta?.id
                                }
                                MiyousheCredentialSharing.reconcile(
                                    credentialStore,
                                    authHealthStore,
                                    sourceProviderId
                                )
                            }
                            val gameSessionReady = if (usesCanonicalMiyousheQr) {
                                prepareCanonicalGameSession()
                            } else {
                                markProviderConfirmedValid()
                                true
                            }
                            if (isMiyousheProvider) {
                                _communityNeedsFullSession.value =
                                    MiyousheCredentialSharing.communityNeedsCanonicalQr(credentialStore)
                            }
                            _saved.value = true
                            _qrStatus.value = QrUiStatus.Confirmed(result.accountLabel)
                            _qrSession.value = null
                            if (gameAccountProvider != null && gameSessionReady) {
                                fetchGameRoles()
                            }
                            return@launch
                        }
                        is QrLoginPollResult.Expired -> {
                            _error.value = result.message?.let { ConnectError.Provider(it) }
                                ?: ConnectError.App(ConnectAppError.QR_EXPIRED)
                            _qrSession.value = null
                            return@launch
                        }
                        is QrLoginPollResult.Failed -> {
                            _error.value = ConnectError.Provider(result.message)
                            _qrSession.value = null
                            return@launch
                        }
                    }
                    delay(2_000)
                }
                _error.value = ConnectError.App(ConnectAppError.QR_TIMEOUT)
                _qrSession.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _error.value = ConnectError.Provider(QR_LOGIN_FAILED_MESSAGE)
                _qrSession.value = null
            } finally {
                _qrBusy.value = false
            }
        }
    }

    fun cancelQrLogin() {
        qrJob?.cancel()
        qrJob = null
        _qrSession.value = null
        _qrBusy.value = false
        _qrStatus.value = null
    }

    /**
     * Looks up roles only on an explicit request. A sole role is saved on the
     * connected path; an unverified ZZZ session waits for the user's pick.
     */
    fun fetchGameRoles() {
        val target = gameAccountProvider ?: return
        if (provider?.meta?.id == MIYOUSHE_ZZZ_ID &&
            _authHealth.value == AuthHealth.UNVERIFIED &&
            !_showZzzRoleSetup.value
        ) return
        viewModelScope.launch {
            _gameRolesBusy.value = true
            try {
                val roles = target.fetchGameRoles()
                _gameRoles.value = roles
                when {
                    roles.size == 1 &&
                        !(provider?.meta?.id == MIYOUSHE_ZZZ_ID && _authHealth.value == AuthHealth.UNVERIFIED) -> {
                        _gameUid.value = roles[0].config.uid
                        _gameRegion.value = roles[0].config.region
                        _gameRoles.value = emptyList()
                        saveGameAccount()
                    }
                    roles.isEmpty() ->
                        _error.value = ConnectError.App(ConnectAppError.GAME_ROLES_UNAVAILABLE)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _gameRoles.value = emptyList()
                _error.value = ConnectError.App(ConnectAppError.GAME_ROLES_UNAVAILABLE)
            } finally {
                _gameRolesBusy.value = false
            }
        }
    }

    /** Applies a role the user picked from the multi-role list. */
    fun pickGameRole(index: Int) {
        val role = _gameRoles.value.getOrNull(index) ?: return
        _gameUid.value = role.config.uid
        _gameRegion.value = role.config.region
        _gameRoles.value = emptyList()
        saveGameAccount(roleSelectionAuthorized = true)
    }

    fun updateGameUid(value: String) {
        _gameUid.value = value.filter(Char::isDigit)
        _gameAccountSaved.value = false
        _error.value = null
    }    fun updateGameRegion(value: String) {
        _gameRegion.value = value
        _gameAccountSaved.value = false
        _error.value = null
    }

    fun saveGameAccount() {
        saveGameAccount(roleSelectionAuthorized = false)
    }

    private fun saveGameAccount(roleSelectionAuthorized: Boolean) {
        val target = gameAccountProvider ?: return
        if (provider?.meta?.id == MIYOUSHE_ZZZ_ID &&
            _authHealth.value == AuthHealth.UNVERIFIED &&
            !roleSelectionAuthorized
        ) return
        viewModelScope.launch {
            _savingGameAccount.value = true
            try {
                when (val validation = target.saveGameAccountConfig(_gameUid.value, _gameRegion.value)) {
                    is CredentialValidation.Valid -> {
                        _gameAccountSaved.value = true
                        _error.value = null
                    }
                    is CredentialValidation.Invalid -> _error.value = ConnectError.Provider(validation.reason)
                }
            } finally {
                _savingGameAccount.value = false
            }
        }
    }

    fun deleteCredentials() {
        cancelQrLogin()
        viewModelScope.launch {
            provider?.let { target ->
                credentialStore.delete(target.credentialOwnerId)
                authHealthStore.clear(target.credentialOwnerId)
            }
            provider?.disconnect()
            _connected.value = false
            _authHealth.value = null
            _saved.value = false
            _gameAccountSaved.value = false
            _gameUid.value = ""
            _smsSent.value = false
            _smsCode.value = ""
            _error.value = null
        }
    }

    private suspend fun markProviderConfirmedValid() {
        val target = provider ?: return
        reconnectPending = false
        _showZzzRoleSetup.value = false
        authHealthStore.set(target.credentialOwnerId, AuthHealth.VALID)
        if (usesCanonicalMiyousheQr) {
            authHealthStore.set(MIYOUSHE_COMMUNITY_ID, AuthHealth.VALID)
        }
        _authHealth.value = AuthHealth.VALID
        _connected.value = true
    }

    private suspend fun prepareCanonicalGameSession(): Boolean {
        val target = provider ?: return false
        val linked = MiyousheCredentialSharing.isTargetLinkableAfterCanonicalQr(
            credentialStore,
            target.meta.id
        )
        authHealthStore.set(MIYOUSHE_COMMUNITY_ID, AuthHealth.VALID)
        authHealthStore.set(target.credentialOwnerId, AuthHealth.UNVERIFIED)
        _authHealth.value = AuthHealth.UNVERIFIED
        _connected.value = false
        _showZzzRoleSetup.value = linked && canShowZzzRoleSetup(gameAccountProvider?.gameAccountConfig())
        if (!linked) {
            _error.value = ConnectError.Provider(
                "米游社二维码已确认，但当前游戏会话未能安全关联。请检查连接状态后再继续。"
            )
        }
        return linked
    }

    private suspend fun canShowZzzRoleSetup(config: GameAccountConfig?): Boolean {
        val target = gameAccountProvider ?: return false
        return provider?.meta?.id == MIYOUSHE_ZZZ_ID &&
            _authHealth.value == AuthHealth.UNVERIFIED &&
            config == null &&
            target.hasCompatibleSavedSessionForRoleLookup()
    }

    private suspend fun enterReconnectState() {
        val target = provider ?: return
        authHealthStore.set(target.credentialOwnerId, AuthHealth.UNVERIFIED)
        _authHealth.value = AuthHealth.UNVERIFIED
        _connected.value = false
    }

    private suspend fun beginSmsReconnect() {
        val target = provider ?: return
        _authHealth.value = AuthHealth.UNVERIFIED
        _connected.value = false
        authHealthStore.set(target.credentialOwnerId, AuthHealth.UNVERIFIED)
    }

    override fun onCleared() {
        qrJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MIYOUSHE_COMMUNITY_ID = "miyoushe_community_signin"
        const val MIYOUSHE_ZZZ_ID = "miyoushe_zzz_experimental"
        val SAFE_ZZZ_LOCAL_SESSION_ERRORS = mapOf(
            "No saved HoYoverse session." to ConnectAppError.ZZZ_SESSION_NOT_SAVED,
            "Saved session lacks a readable LToken pair." to ConnectAppError.ZZZ_LTOKEN_PAIR_MISSING,
            "Saved session lacks a usable ZZZ UID or region." to ConnectAppError.ZZZ_UID_OR_REGION_MISSING
        )
        val SAFE_ZZZ_PROVIDER_REASONS = setOf(
            "HoYoverse connection could not be confirmed.",
            "HoYoverse did not return a confirmed ZZZ check-in state."
        )
        val MIYOUSHE_GAME_IDS = setOf(
            "miyoushe_genshin_experimental",
            "miyoushe_zzz_experimental"
        )
        val MIYOUSHE_PROVIDER_IDS = MIYOUSHE_GAME_IDS + MIYOUSHE_COMMUNITY_ID
        val STRICT_READ_ONLY_PROVIDER_IDS = setOf(
            "miyoushe_genshin_experimental",
            "miyoushe_zzz_experimental",
            "miyoushe_community_signin"
        )
        const val QR_LOGIN_FAILED_MESSAGE = "QR login could not be completed."
    }
}
