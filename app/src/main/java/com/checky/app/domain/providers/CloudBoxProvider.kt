package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.MockScenario
import com.checky.app.domain.MockScenarioStore
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Mock provider: HTTP-session based (user-supplied token).
 *
 * Until the user connects a (fake) token via the connect screen, check-in
 * returns [com.checky.app.domain.model.CheckInStatus.LOGIN_EXPIRED]. After a
 * token is saved in [CredentialStore] it succeeds with **+1 membership day**.
 */
class CloudBoxProvider(
    private val credentialStore: CredentialStore,
    private val scenarioStore: MockScenarioStore
) : CheckInProvider {

    override val meta: ProviderMeta = META

    override val requiresCredentials: Boolean = true

    override suspend fun validateCredentials(secret: String): CredentialValidation =
        if (secret.isBlank() || secret.length < 8) {
            CredentialValidation.Invalid("Token must be at least 8 characters.")
        } else {
            CredentialValidation.Valid
        }

    override fun supportsDisconnect(): Boolean = true

    override suspend fun disconnect() {
        credentialStore.delete(meta.id)
    }

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.20f, "Restoring session…"))
        delay(400)

        val scenario = scenarioStore.scenario().first()
        when (scenario) {
            MockScenario.NETWORK_FAILURE -> {
                emit(CheckInEvent.Progress(0.60f, "Connection timed out"))
                delay(300)
                emit(CheckInEvent.Done(done(timeoutOutcome())))
            }
            MockScenario.ACTION_REQUIRED -> {
                emit(CheckInEvent.Progress(0.60f, "Verification requested"))
                delay(300)
                emit(CheckInEvent.Done(done(actionRequiredOutcome())))
            }
            MockScenario.AUTH_EXPIRED -> {
                emit(CheckInEvent.Progress(0.60f, "Session check failed"))
                delay(300)
                emit(CheckInEvent.Done(done(authExpiredOutcome())))
            }
            else -> {
                val token = credentialStore.get(meta.id)
                if (token == null) {
                    emit(CheckInEvent.Progress(0.60f, "Session check failed"))
                    delay(350)
                    emit(CheckInEvent.Done(done(authExpiredOutcome())))
                } else {
                    emit(CheckInEvent.Progress(0.70f, "Syncing vault…"))
                    delay(450)
                    val outcome = if (scenario == MockScenario.ALREADY_COMPLETED) {
                        CheckInOutcome.AlreadyCompleted(
                            userMessage = "Already checked in today.",
                            diagnosticCode = "ALREADY"
                        )
                    } else {
                        CheckInOutcome.Success(
                            userMessage = "Back online — membership extended (+1 day)",
                            diagnosticCode = "SUCCESS",
                            reward = Reward(RewardType.MEMBERSHIP_DAY, 1)
                        )
                    }
                    emit(CheckInEvent.Done(done(outcome)))
                }
            }
        }
    }

    private fun done(outcome: CheckInOutcome) =
        CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())

    companion object {
        val META = ProviderMeta(
            id = "cloudbox",
            displayName = "CloudBox",
            description = "Keep your cloud vault synced and your streak alive.",
            category = "Cloud",
            iconKey = "cloud",
            accentColor = 0xFF0A7BC2,
            isEnabledByDefault = true,
            connectionType = ConnectionType.HTTP_SESSION,
            riskLevel = RiskLevel.MEDIUM,
            credentialType = CredentialType.SESSION_TOKEN,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf("api.cloudbox.example.com")
        )
    }
}
