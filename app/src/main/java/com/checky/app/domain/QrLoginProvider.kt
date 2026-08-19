package com.checky.app.domain

/** A short-lived QR login session owned by the local device. */
data class QrLoginSession(
    val qrPayload: String,
    val sessionId: String
)

sealed interface QrLoginPollResult {
    data object Waiting : QrLoginPollResult
    data object Scanned : QrLoginPollResult
    data class Confirmed(val accountLabel: String? = null) : QrLoginPollResult
    data class Expired(val message: String = "二维码已过期，请重新生成。") : QrLoginPollResult
    data class Failed(val message: String) : QrLoginPollResult
}

/** Optional provider capability for local, user-confirmed QR login. */
interface QrLoginProvider {
    suspend fun createQrLoginSession(): QrLoginSession
    suspend fun pollQrLogin(session: QrLoginSession): QrLoginPollResult
}
