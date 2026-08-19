package com.checky.app.domain.providers

import com.checky.app.domain.MockScenario
import com.checky.app.domain.model.CheckInOutcome

/** Standard user-facing outcomes (never expose raw errors to users). */
internal fun authExpiredOutcome() = CheckInOutcome.AuthenticationExpired(
    userMessage = "Your connection has expired. Reconnect this service."
)

internal fun timeoutOutcome() = CheckInOutcome.TemporaryFailure(
    userMessage = "The service did not respond. Try again later."
)

internal fun rateLimitOutcome() = CheckInOutcome.TemporaryFailure(
    userMessage = "This service temporarily limited requests.",
    diagnosticCode = "RATE_LIMIT"
)

internal fun maintenanceOutcome() = CheckInOutcome.TemporaryFailure(
    userMessage = "This service may be under maintenance.",
    diagnosticCode = "MAINTENANCE"
)

internal fun actionRequiredOutcome() = CheckInOutcome.ActionRequired(
    userMessage = "This service needs your attention."
)

/**
 * Maps the developer [MockScenario] override to a concrete outcome.
 * Defaults keep each provider's natural scripted behavior.
 */
internal fun outcomeForScenario(
    scenario: MockScenario,
    onSuccess: () -> CheckInOutcome,
    onAlready: () -> CheckInOutcome,
    onAuthExpired: () -> CheckInOutcome = ::authExpiredOutcome,
    onNetworkFailure: () -> CheckInOutcome = ::timeoutOutcome,
    onActionRequired: () -> CheckInOutcome = ::actionRequiredOutcome
): CheckInOutcome = when (scenario) {
    MockScenario.DEFAULT, MockScenario.SUCCESS -> onSuccess()
    MockScenario.ALREADY_COMPLETED -> onAlready()
    MockScenario.AUTH_EXPIRED -> onAuthExpired()
    MockScenario.NETWORK_FAILURE -> onNetworkFailure()
    MockScenario.ACTION_REQUIRED -> onActionRequired()
}
