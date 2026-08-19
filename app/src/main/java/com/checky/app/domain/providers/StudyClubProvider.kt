package com.checky.app.domain.providers

import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
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
 * Mock provider: already checked in today.
 * +5 experience on the (already completed) check-in.
 */
class StudyClubProvider(
    private val scenarioStore: MockScenarioStore
) : CheckInProvider {

    override val meta: ProviderMeta = META

    override suspend fun validateCredentials(secret: String): CredentialValidation = CredentialValidation.Valid

    override fun checkIn() = flow {
        emit(CheckInEvent.Progress(0.30f, "Reading study record…"))
        delay(400)
        emit(CheckInEvent.Progress(0.70f, "Updating streak…"))
        delay(400)

        val outcome: CheckInOutcome = when (val scenario = scenarioStore.scenario().first()) {
            // StudyClub's natural scripted outcome is "already checked in".
            MockScenario.DEFAULT, MockScenario.ALREADY_COMPLETED -> CheckInOutcome.AlreadyCompleted(
                userMessage = "Already checked in today: +5 XP",
                diagnosticCode = "ALREADY",
                reward = Reward(RewardType.EXPERIENCE, 5)
            )
            MockScenario.SUCCESS -> CheckInOutcome.Success(
                userMessage = "Study streak logged: +5 XP",
                diagnosticCode = "SUCCESS",
                reward = Reward(RewardType.EXPERIENCE, 5)
            )
            MockScenario.AUTH_EXPIRED -> authExpiredOutcome()
            MockScenario.NETWORK_FAILURE -> timeoutOutcome()
            MockScenario.ACTION_REQUIRED -> actionRequiredOutcome()
        }
        emit(CheckInEvent.Done(CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())))
    }

    companion object {
        val META = ProviderMeta(
            id = "studyclub",
            displayName = "StudyClub",
            description = "Log your daily study streak and earn experience.",
            category = "Learning",
            iconKey = "school",
            accentColor = 0xFF7C3AED,
            isEnabledByDefault = true,
            connectionType = ConnectionType.OFFICIAL_API,
            riskLevel = RiskLevel.LOW,
            credentialType = CredentialType.NONE,
            supportStatus = SupportStatus.SUPPORTED,
            allowedHosts = setOf("api.studyclub.example.com")
        )
    }
}
