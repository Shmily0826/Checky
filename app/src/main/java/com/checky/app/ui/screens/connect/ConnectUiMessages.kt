package com.checky.app.ui.screens.connect

/** App-owned connect copy is typed so the ViewModel stays independent of Android resources. */
enum class ConnectAppError {
    TOKEN_REQUIRED,
    QR_UNSUPPORTED,
    QR_EXPIRED,
    QR_TIMEOUT,
    GAME_ROLES_UNAVAILABLE,
    VERIFICATION_FAILED,
    ZZZ_SESSION_NOT_SAVED,
    ZZZ_LTOKEN_PAIR_MISSING,
    ZZZ_UID_OR_REGION_MISSING,
    ZZZ_PROVIDER_NOT_CONFIRMED
}

sealed interface ConnectError {
    data class App(val kind: ConnectAppError) : ConnectError
    /** Provider/server validation and login text is deliberately preserved verbatim. */
    data class Provider(val message: String) : ConnectError
}

sealed interface QrUiStatus {
    data object Generating : QrUiStatus
    data object Waiting : QrUiStatus
    data object Scanned : QrUiStatus
    data class Confirmed(val accountLabel: String?) : QrUiStatus
}
