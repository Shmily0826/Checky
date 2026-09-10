package com.checky.app.data.work

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderWorkerTest {
    private val now = Instant.parse("2026-09-09T01:00:00Z")
    private val provider = provider("one", ZoneId.of("Asia/Shanghai"))

    @Test
    fun reminderIsSuppressedWhenAllEnabledProvidersAreCompleteToday() = runTest {
        val services = listOf(
            service(CheckInStatus.SUCCESS),
            service(CheckInStatus.ALREADY_CHECKED_IN)
        )

        assertFalse(shouldShowDailyReminder(services, listOf(provider), now))
    }

    @Test
    fun reminderRemainsEligibleWhenAnEnabledProviderNeedsAttention() = runTest {
        assertTrue(
            shouldShowDailyReminder(
                listOf(service(CheckInStatus.FAILED)),
                listOf(provider),
                now
            )
        )
    }

    @Test
    fun resultSummarySeparatesFailureAndReconnectCounts() {
        val summary = CheckInSummary(
            total = 5,
            succeeded = 3,
            alreadyCheckedIn = 0,
            failed = 1,
            attention = 1,
            totalPoints = 0,
            totalXp = 0,
            durationMs = 0
        )

        assertEquals(
            "success 3 · failed 1 · reconnect 1",
            formatCheckInResultSummary(
                summary,
                reconnectRequired = 1,
                successLabel = { "success $it" },
                alreadyLabel = { "already $it" },
                failedLabel = { "failed $it" },
                reconnectLabel = { "reconnect $it" },
                attentionLabel = { "attention $it" }
            )
        )
    }

    private fun service(status: CheckInStatus) = ServiceSnapshot(
        serviceId = provider.meta.id,
        displayName = provider.meta.displayName,
        isEnabled = true,
        lastStatus = status,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = now.toEpochMilli()
    )

    private fun provider(id: String, zone: ZoneId) = object : CheckInProvider {
        override val meta = ProviderMeta(
            id = id,
            displayName = id,
            description = "",
            category = "",
            iconKey = "",
            accentColor = 0L,
            isEnabledByDefault = true,
            connectionType = ConnectionType.OFFICIAL_API,
            riskLevel = RiskLevel.LOW,
            credentialType = CredentialType.NONE,
            supportStatus = SupportStatus.SUPPORTED,
            businessZone = zone
        )

        override fun checkIn(): Flow<CheckInEvent> = emptyFlow()
        override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
    }
}
