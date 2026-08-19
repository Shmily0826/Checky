package com.checky.app.domain.model

/**
 * Immutable result of a single provider check-in.
 *
 * Carries only safe, user-facing information via [outcome]; raw credentials,
 * headers, cookies and response bodies are never part of a result.
 */
data class CheckInResult(
    val serviceId: String,
    val serviceName: String,
    val outcome: CheckInOutcome,
    val timestamp: Long,
    /** Wall-clock duration of the check-in attempt in milliseconds. */
    val durationMs: Long = 0L
) {
    val status: CheckInStatus get() = outcome.status
    val reward: Reward get() = outcome.reward
    val message: String get() = outcome.userMessage
    val diagnosticCode: String get() = outcome.diagnosticCode
    val retryRecommendation: RetryRecommendation get() = outcome.retryRecommendation
}
