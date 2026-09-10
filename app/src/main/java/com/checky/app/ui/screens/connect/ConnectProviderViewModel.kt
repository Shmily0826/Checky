package com.checky.app.ui.screens.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
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
    val qrProvider: QrLoginProvider? = provider as? QrLoginProvider
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

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    val supportsReadOnlyRevalidation: Boolean = provider is SavedCredentialRevalidator

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

    private val _gameRolesBusy = MutableStateFlow(false)
    val gameRolesBusy: StateFlow<Boolean> = _gameRolesBusy.asStateFlow()

    private var qrJob: Job? = null

    init {
        viewModelScope.launch {
            if (reconnectEntry) {
                enterReconnectState()
            } else {
                _authHealth.value = provider?.let {
                    ProviderConnectionGate.health(it, credentialStore, authHealthStore)
                }
                _connected.value = _authHealth.value == AuthHealth.VALID
            }
            gameAccountProvider?.gameAccountConfig()?.let { config ->
                _gameUid.value = config.uid
                _gameRegion.value = config.region
                _gameAccountSaved.value = true
            }
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
        viewModelScope.launch {
            if (reconnectPending) {
                enterReconnectState()
            } else {
                val health = ProviderConnectionGate.health(target, credentialStore, authHealthStore)
                _authHealth.value = health
                _connected.value = health == AuthHealth.VALID
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
        if ((_authHealth.value != AuthHealth.UNVERIFIED &&
                _authHealth.value != AuthHealth.EXPIRED) || _verifying.value) return
        viewModelScope.launch {
            _verifying.value = true
            _error.value = null
            try {
                when (val result = (target as? SavedCredentialRevalidator)?.revalidateSavedCredential()) {
                    SavedCredentialValidation.Valid -> {
                        markProviderConfirmedValid()
                    }
                    SavedCredentialValidation.Expired -> {
                        authHealthStore.set(target.credentialOwnerId, AuthHealth.EXPIRED)
                        _authHealth.value = AuthHealth.EXPIRED
                        _connected.value = false
                    }
                    is SavedCredentialValidation.Unverified, null -> {
                        _authHealth.value = authHealthStore.get(target.credentialOwnerId)
                        _connected.value = false
                        _error.value = ConnectError.App(ConnectAppError.VERIFICATION_FAILED)
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
                            markProviderConfirmedValid()
                            _saved.value = true
                            _qrStatus.value = QrUiStatus.Confirmed(result.accountLabel)
                            _qrSession.value = null
                            if (gameAccountProvider != null) {
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
     * Looks up the roles bound to the connected account. A single role is
     * filled in and saved automatically; several roles wait for the user's
     * pick; none falls back to manual UID entry with a hint.
     */
    fun fetchGameRoles() {
        val target = gameAccountProvider ?: return
        viewModelScope.launch {
            _gameRolesBusy.value = true
            try {
                val roles = target.fetchGameRoles()
                _gameRoles.value = roles
                when {
                    roles.size == 1 -> {
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
        saveGameAccount()
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
        val target = gameAccountProvider ?: return
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
        authHealthStore.set(target.credentialOwnerId, AuthHealth.VALID)
        _authHealth.value = AuthHealth.VALID
        _connected.value = true
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
}
