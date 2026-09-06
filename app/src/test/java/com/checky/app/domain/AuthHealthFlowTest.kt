package com.checky.app.domain

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.providers.mapTaygedoFailure
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHealthFlowTest {
    @Test
    fun expiredResultPersistsExpiredWithoutDeletingCredential() = runTest {
        val credentials = FakeCredentialStore().also { it.save("expired", "synthetic") }
        val health = FakeAuthHealthStore()
        runOnce(CheckInOutcome.AuthenticationExpired("expired"), credentials, health)

        assertEquals(AuthHealth.EXPIRED, health.get("expired"))
        assertTrue(credentials.has("expired"))
    }

    @Test
    fun definitiveTaygedo401PersistsExpiredForSharedOwnerWithoutDeletingCredential() = runTest {
        val owner = "taygedo.shared.session"
        val credentials = FakeCredentialStore().also { it.save(owner, "synthetic") }
        val health = FakeAuthHealthStore().also { it.set(owner, AuthHealth.VALID) }
        val provider = testProvider("taygedo_community", owner)
        val expiredProvider = object : CheckInProvider {
            override val meta = provider.meta
            override val requiresCredentials = true
            override val credentialOwnerId = owner
            override fun checkIn() = kotlinx.coroutines.flow.flowOf(
                CheckInEvent.Done(
                    com.checky.app.domain.model.CheckInResult(
                        meta.id, meta.displayName, mapTaygedoFailure(401, ""), 1L
                    )
                )
            )
            override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
        }

        CheckInAllUseCase(FakeCheckInRepository(), credentials, health)
            .invoke(
                listOf(expiredProvider),
                parallel = false
            )
            .toList()

        assertEquals(AuthHealth.EXPIRED, health.get(owner))
        assertTrue(credentials.has(owner))
    }

    @Test
    fun positiveResultsPersistValid() = runTest {
        listOf(
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty()),
            CheckInOutcome.AlreadyCompleted("already", "ALREADY")
        ).forEach { outcome ->
            val health = FakeAuthHealthStore()
            runOnce(outcome, FakeCredentialStore(), health)
            assertEquals(AuthHealth.VALID, health.get("positive"))
        }
    }

    @Test
    fun nonAuthTerminalOutcomesDoNotPersistExpired() = runTest {
        listOf(
            CheckInOutcome.TemporaryFailure("temporary"),
            CheckInOutcome.PermanentFailure("malformed"),
            CheckInOutcome.ActionRequired("verify")
        ).forEach { outcome ->
            val health = FakeAuthHealthStore()
            runOnce(outcome, FakeCredentialStore(), health)
            assertEquals(AuthHealth.UNVERIFIED, health.get("non_auth"))
        }
    }

    @Test
    fun legacyPositiveEvidenceSeedsValidOnce() = runTest {
        val provider = testProvider("legacy")
        val credentials = FakeCredentialStore().also { it.save("legacy", "synthetic") }
        val health = FakeAuthHealthStore()
        LegacyAuthHealthMigration.seedIfNeeded(
            listOf(provider), listOf(service("legacy", CheckInStatus.SUCCESS)), credentials, health
        )

        assertEquals(AuthHealth.VALID, health.get("legacy"))
    }

    @Test
    fun legacyExpiryWinsOverPositiveTaygedoSiblingEvidence() = runTest {
        val nte = testProvider("taygedo_nte", "taygedo.shared.session")
        val community = testProvider("taygedo_community", "taygedo.shared.session")
        val credentials = FakeCredentialStore().also { it.save("taygedo.shared.session", "synthetic") }
        val health = FakeAuthHealthStore()
        LegacyAuthHealthMigration.seedIfNeeded(
            listOf(nte, community),
            listOf(
                service("taygedo_nte", CheckInStatus.SUCCESS),
                service("taygedo_community", CheckInStatus.LOGIN_EXPIRED)
            ),
            credentials,
            health
        )

        assertEquals(AuthHealth.EXPIRED, health.get("taygedo.shared.session"))
    }

    @Test
    fun explicitUnverifiedCredentialIsNotUpgradedFromOldResult() = runTest {
        val provider = testProvider("replaced")
        val credentials = FakeCredentialStore().also { it.save("replaced", "new-secret") }
        val health = FakeAuthHealthStore().also { it.set("replaced", AuthHealth.UNVERIFIED) }
        LegacyAuthHealthMigration.seedIfNeeded(
            listOf(provider), listOf(service("replaced", CheckInStatus.SUCCESS)), credentials, health
        )

        assertEquals(AuthHealth.UNVERIFIED, health.get("replaced"))
    }

    private suspend fun runOnce(
        outcome: CheckInOutcome,
        credentials: CredentialStore,
        health: AuthHealthStore
    ) {
        val providerId = when (outcome) {
            is CheckInOutcome.AuthenticationExpired -> "expired"
            is CheckInOutcome.Success, is CheckInOutcome.AlreadyCompleted -> "positive"
            else -> "non_auth"
        }
        val provider = testProvider(providerId)
        CheckInAllUseCase(FakeCheckInRepository(), credentials, health)
            .invoke(listOf(SequenceCheckInProvider(provider.meta, outcome)), parallel = false)
            .toList()
    }

    private fun testProvider(id: String, owner: String = id) = object : CheckInProvider {
        override val meta = testProviderMeta(id).copy(
            credentialType = com.checky.app.domain.model.CredentialType.SESSION_TOKEN
        )
        override val requiresCredentials = true
        override val credentialOwnerId = owner
        override fun checkIn() = kotlinx.coroutines.flow.emptyFlow<CheckInEvent>()
        override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
    }

    private fun service(id: String, status: CheckInStatus) = ServiceSnapshot(
        serviceId = id,
        displayName = id,
        isEnabled = true,
        lastStatus = status,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = 1L
    )
}
