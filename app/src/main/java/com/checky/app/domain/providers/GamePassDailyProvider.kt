package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialValidation
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
 * Mock provider: successful daily reward.
 * +20 points on success. Honors the developer [MockScenarioStore] override.
 */
class GamePassDailyProvider(
    private val scenarioStore: MockScenarioStore
) : CheckInProvider {

    override val meta: ProviderMeta = META

    override suspend fun validateCredentials(secret: String): CredentialValidation = CredentialValidation.Valid

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.15f, "Connecting to GamePass…"))
        delay(450)
        emit(CheckInEvent.Progress(0.55f, "Authenticating session…"))
        delay(450)
        emit(CheckInEvent.Progress(0.85f, "Claiming daily reward…"))
        delay(400)

        val outcome: CheckInOutcome = outcomeForScenario(
            scenario = scenarioStore.scenario().first(),
            onSuccess = {
                CheckInOutcome.Success(
                    userMessage = "Daily reward claimed: +20 points",
                    diagnosticCode = "SUCCESS",
                    reward = Reward(RewardType.POINTS, 20)
                )
            },
            onAlready = {
                CheckInOutcome.AlreadyCompleted(
                    userMessage = "Already checked in today. Come back tomorrow!",
                    diagnosticCode = "ALREADY"
                )
            }
        )
        emit(CheckInEvent.Done(CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())))
    }

    companion object {
        val META = ProviderMeta(
            id = "gamepass",
            displayName = "GamePass Daily",
            description = "Claim your daily GamePass reward and keep the streak alive.",
            category = "Gaming",
            iconKey = "gamepad",
            accentColor = 0xFF107C10,
            isEnabledByDefault = true,
            connectionType = ConnectionType.OFFICIAL_API,
            riskLevel = RiskLevel.LOW,
            credentialType = CredentialType.NONE,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf("api.gamepass.example.com")
        )
    }
}
