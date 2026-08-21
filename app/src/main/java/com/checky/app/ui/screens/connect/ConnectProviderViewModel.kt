package com.checky.app.ui.screens.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.GameAccountConfigProvider
import com.checky.app.domain.QrLoginPollResult
import com.checky.app.domain.QrLoginProvider
import com.checky.app.domain.QrLoginSession
import com.checky.app.domain.SmsLoginProvider
import com.checky.app.domain.SmsLoginResult
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val serviceId: String = savedStateHandle.get<String>("serviceId") ?: ""

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

    private val _secret = MutableStateFlow("")
    val secret: StateFlow<String> = _secret.asStateFlow()

    private val _showSecret = MutableStateFlow(false)
    val showSecret: StateFlow<Boolean> = _showSecret.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _qrSession = MutableStateFlow<QrLoginSession?>(null)
    val qrSession: StateFlow<QrLoginSession?> = _qrSession.asStateFlow()

    private val _qrStatus = MutableStateFlow<String?>(null)
    val qrStatus: StateFlow<String?> = _qrStatus.asStateFlow()

    private val _qrBusy = MutableStateFlow(false)
    val qrBusy: StateFlow<Boolean> = _qrBusy.asStateFlow()

    private val _gameUid = MutableStateFlow("")
    val gameUid: StateFlow<String> = _gameUid.asStateFlow()

    private val _gameRegion = MutableStateFlow("cn_gf01")
    val gameRegion: StateFlow<String> = _gameRegion.asStateFlow()

    private val _savingGameAccount = MutableStateFlow(false)
    val savingGameAccount: StateFlow<Boolean> = _savingGameAccount.asStateFlow()

    private val _gameAccountSaved = MutableStateFlow(false)
    val gameAccountSaved: StateFlow<Boolean> = _gameAccountSaved.asStateFlow()

    private var qrJob: Job? = null

    init {
        viewModelScope.launch {
            _connected.value = when {
                smsProvider != null -> smsProvider.isSmsConnected()
                else -> credentialStore.has(serviceId)
            }
            gameAccountProvider?.gameAccountConfig()?.let { config ->
                _gameUid.value = config.uid
                _gameRegion.value = config.region
                _gameAccountSaved.value = true
            }
        }
    }

    fun updateSecret(value: String) {
        _secret.value = value
        _error.value = null
    }

    fun updatePhone(value: String) { _phone.value = value.filter(Char::isDigit).take(11); _error.value = null }
    fun updateSmsCode(value: String) { _smsCode.value = value.filter(Char::isDigit).take(8); _error.value = null }

    fun sendSmsCode() {
        val target = smsProvider ?: return
        viewModelScope.launch {
            _smsBusy.value = true
            when (val result = target.sendSmsCode(_phone.value)) {
                is SmsLoginResult.CodeSent -> { _smsSent.value = true; _error.value = null }
                is SmsLoginResult.Failed -> _error.value = result.message
                is SmsLoginResult.Connected -> Unit
            }
            _smsBusy.value = false
        }
    }

    fun confirmSmsCode() {
        val target = smsProvider ?: return
        viewModelScope.launch {
            _smsBusy.value = true
            when (val result = target.confirmSmsCode(_phone.value, _smsCode.value)) {
                is SmsLoginResult.Connected -> {
                    _connected.value = true; _saved.value = true; _error.value = null
                    _smsCode.value = ""
                }
                is SmsLoginResult.Failed -> _error.value = result.message
                is SmsLoginResult.CodeSent -> Unit
            }
            _smsBusy.value = false
        }
    }

    fun toggleSecretVisibility() {
        _showSecret.value = !_showSecret.value
    }

    fun save() {
        val target = provider ?: return
        val value = _secret.value
        if (value.isBlank()) {
            _error.value = "Enter a token to continue."
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                when (val validation = target.validateCredentials(value)) {
                    is CredentialValidation.Valid -> {
                        credentialStore.save(serviceId, value)
                        _secret.value = ""
                        _error.value = null
                        _saved.value = true
                        _connected.value = true
                    }
                    is CredentialValidation.Invalid -> _error.value = validation.reason
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun startQrLogin() {
        val target = qrProvider ?: run {
            _error.value = "当前服务不支持扫码绑定。"
            return
        }
        qrJob?.cancel()
        _error.value = null
        _qrStatus.value = "正在生成二维码…"
        _qrBusy.value = true
        qrJob = viewModelScope.launch {
            try {
                val session = target.createQrLoginSession()
                _qrSession.value = session
                repeat(90) {
                    when (val result = target.pollQrLogin(session)) {
                        QrLoginPollResult.Waiting -> _qrStatus.value = "等待扫码…"
                        QrLoginPollResult.Scanned -> _qrStatus.value = "已扫码，请在米游社确认登录…"
                        is QrLoginPollResult.Confirmed -> {
                            _connected.value = true
                            _saved.value = true
                            _qrStatus.value = result.accountLabel?.let { "绑定成功（UID $it）" } ?: "绑定成功"
                            _qrSession.value = null
                            return@launch
                        }
                        is QrLoginPollResult.Expired -> {
                            _error.value = result.message
                            _qrSession.value = null
                            return@launch
                        }
                        is QrLoginPollResult.Failed -> {
                            _error.value = result.message
                            _qrSession.value = null
                            return@launch
                        }
                    }
                    delay(2_000)
                }
                _error.value = "二维码等待超时，请重新生成。"
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

    fun updateGameUid(value: String) {
        _gameUid.value = value.filter(Char::isDigit)
        _gameAccountSaved.value = false
        _error.value = null
    }

    fun updateGameRegion(value: String) {
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
                    is CredentialValidation.Invalid -> _error.value = validation.reason
                }
            } finally {
                _savingGameAccount.value = false
            }
        }
    }

    fun deleteCredentials() {
        cancelQrLogin()
        viewModelScope.launch {
            credentialStore.delete(serviceId)
            provider?.disconnect()
            _connected.value = false
            _saved.value = false
            _gameAccountSaved.value = false
            _gameUid.value = ""
            _smsSent.value = false
            _smsCode.value = ""
        }
    }

    override fun onCleared() {
        qrJob?.cancel()
        super.onCleared()
    }
}
