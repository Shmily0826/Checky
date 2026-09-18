package com.checky.app.accessibility

const val TAPTAP_PACKAGE = "com.taptap"

data class TapTapAccessibilityButton(
    val text: String,
    val className: String,
    val enabled: Boolean,
    val clickable: Boolean
)

data class TapTapAccessibilitySnapshot(
    val packageName: String,
    val texts: List<String>,
    val buttons: List<TapTapAccessibilityButton>
)

enum class TapTapPageDisposition {
    NOT_TARGET,
    WAITING,
    READY,
    ALREADY_COMPLETED,
    SUCCESS,
    UNKNOWN
}

enum class TapTapRunState {
    READY,
    CONFIRMING,
    SUCCESS,
    ALREADY_COMPLETED,
    PAGE_NOT_READY,
    GESTURE_FAILED,
    UNSAFE_OR_UNKNOWN_PAGE,
    CONFIRMATION_TIMEOUT
}

enum class TapTapRunResult {
    SUCCESS,
    ALREADY_COMPLETED,
    GESTURE_FAILED,
    PAGE_NOT_READY,
    CONFIRMATION_TIMEOUT,
    UNKNOWN
}

object TapTapPageMatcher {
    private const val BUTTON_CLASS = "android.widget.Button"
    private const val PAGE_TITLE = "签到领好礼"
    private const val CUMULATIVE_PREFIX = "已累计签到"
    private const val READY_TEXT = "立即签到"
    private const val COMPLETED_TEXT = "今日已签到"

    fun classify(snapshot: TapTapAccessibilitySnapshot): TapTapPageDisposition {
        if (snapshot.packageName != TAPTAP_PACKAGE) return TapTapPageDisposition.NOT_TARGET

        val texts = snapshot.texts.map(String::trim).filter(String::isNotEmpty)
        if (texts.any(::isVerificationMarker)) return TapTapPageDisposition.UNKNOWN
        val expectedPage = PAGE_TITLE in texts && texts.any { it.startsWith(CUMULATIVE_PREFIX) }
        if (!expectedPage) return TapTapPageDisposition.NOT_TARGET

        val readyButtons = snapshot.buttons.filter { it.isButton(READY_TEXT) }
        val actionableReady = readyButtons.filter { it.enabled && it.clickable }
        val completedButtons = snapshot.buttons.filter { it.isButton(COMPLETED_TEXT) }
        val disabledCompleted = completedButtons.filterNot { it.enabled }
        val successOverlay = texts.any { it.contains("签到成功") && it.contains("恭喜获得") }

        return when {
            successOverlay && completedButtons.size == 1 && disabledCompleted.size == 1 &&
                actionableReady.isEmpty() -> TapTapPageDisposition.SUCCESS
            !successOverlay && completedButtons.size == 1 && disabledCompleted.size == 1 &&
                actionableReady.isEmpty() -> TapTapPageDisposition.ALREADY_COMPLETED
            !successOverlay && completedButtons.isEmpty() && readyButtons.size == 1 &&
                actionableReady.size == 1 -> TapTapPageDisposition.READY
            !successOverlay && completedButtons.isEmpty() && readyButtons.size == 1 &&
                actionableReady.isEmpty() -> TapTapPageDisposition.WAITING
            !successOverlay && completedButtons.isEmpty() && readyButtons.isEmpty() ->
                TapTapPageDisposition.WAITING
            else -> TapTapPageDisposition.UNKNOWN
        }
    }

    private fun TapTapAccessibilityButton.isButton(expectedText: String): Boolean =
        className == BUTTON_CLASS && text.trim() == expectedText

    fun isUnsafe(snapshot: TapTapAccessibilitySnapshot): Boolean =
        snapshot.packageName == TAPTAP_PACKAGE && snapshot.texts.any { isVerificationMarker(it.trim()) }

    private fun isVerificationMarker(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf("captcha", "验证码", "人机验证", "安全验证", "滑块", "请登录").any {
            it in normalized
        }
    }
}
