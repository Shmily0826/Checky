package com.checky.app.domain.model

/**
 * Guidance the UI can surface after a check-in.
 */
enum class RetryRecommendation {
    /** The action is safe to retry immediately. */
    RETRY_NOW,

    /** Retry later (e.g. transient network trouble). */
    RETRY_LATER,

    /** The user must reconnect / re-authorize the service. */
    RECONNECT,

    /** No retry recommended. */
    NONE
}

/**
 * Safe, user-facing outcome of a check-in.
 *
 * Every variant carries only non-sensitive information:
 * - [userMessage] — a user-readable message (never a stack trace or raw body)
 * - [diagnosticCode] — a stable, non-sensitive error category (e.g. "AUTH_EXPIRED")
 * - [reward] — the reward summary
 * - [retryRecommendation] — optional next-step guidance
 *
 * Raw credentials, cookies, tokens, response bodies and headers are never part
 * of an outcome.
 */
sealed class CheckInOutcome {
    abstract val userMessage: String
    abstract val diagnosticCode: String
    open val reward: Reward = Reward.empty()
    open val retryRecommendation: RetryRecommendation = RetryRecommendation.NONE

    /** Maps to the UI-facing status. */
    val status: CheckInStatus
        get() = when (this) {
            is Success -> CheckInStatus.SUCCESS
            is AlreadyCompleted -> CheckInStatus.ALREADY_CHECKED_IN
            is AuthenticationExpired -> CheckInStatus.LOGIN_EXPIRED
            is ActionRequired -> CheckInStatus.USER_ACTION_REQUIRED
            is TemporaryFailure, is PermanentFailure, is Unsupported -> CheckInStatus.FAILED
        }

    val isPositive: Boolean
        get() = status.isPositive

    data class Success(
        override val userMessage: String,
        override val diagnosticCode: String,
        override val reward: Reward
    ) : CheckInOutcome()

    data class AlreadyCompleted(
        override val userMessage: String,
        override val diagnosticCode: String,
        override val reward: Reward = Reward.empty()
    ) : CheckInOutcome()

    data class AuthenticationExpired(
        override val userMessage: String,
        override val diagnosticCode: String = "AUTH_EXPIRED",
        override val retryRecommendation: RetryRecommendation = RetryRecommendation.RECONNECT
    ) : CheckInOutcome()

    data class ActionRequired(
        override val userMessage: String,
        override val diagnosticCode: String = "ACTION_REQUIRED",
        override val retryRecommendation: RetryRecommendation = RetryRecommendation.RETRY_NOW
    ) : CheckInOutcome()

    data class TemporaryFailure(
        override val userMessage: String,
        override val diagnosticCode: String = "TEMP_NETWORK",
        override val retryRecommendation: RetryRecommendation = RetryRecommendation.RETRY_LATER
    ) : CheckInOutcome()

    data class PermanentFailure(
        override val userMessage: String,
        override val diagnosticCode: String = "PERMANENT",
        override val retryRecommendation: RetryRecommendation = RetryRecommendation.NONE
    ) : CheckInOutcome()

    data class Unsupported(
        override val userMessage: String,
        override val diagnosticCode: String = "UNSUPPORTED",
        override val retryRecommendation: RetryRecommendation = RetryRecommendation.NONE
    ) : CheckInOutcome()
}
