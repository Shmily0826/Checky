package com.checky.app.domain

/** Optional provider capability for a user-confirmed SMS login flow. */
interface SmsLoginProvider {
    suspend fun isSmsConnected(): Boolean
    suspend fun sendSmsCode(phone: String): SmsLoginResult
    suspend fun confirmSmsCode(phone: String, code: String): SmsLoginResult
}

sealed interface SmsLoginResult {
    data class CodeSent(val message: String = "验证码已发送。") : SmsLoginResult
    data class Connected(val accountLabel: String? = null) : SmsLoginResult
    data class Failed(val message: String) : SmsLoginResult
}
