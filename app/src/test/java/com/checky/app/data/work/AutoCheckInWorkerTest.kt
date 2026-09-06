package com.checky.app.data.work

import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.Reward
import com.checky.app.domain.testProviderMeta
import com.checky.app.data.preferences.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCheckInWorkerTest {
    @Test
    fun disabledOptInStopsQueuedWorkerBeforeProviderSelection() {
        assertEquals(false, shouldRunAutoCheckIn(UserPreferences(autoCheckInEnabled = false)))
        assertEquals(true, shouldRunAutoCheckIn(UserPreferences(autoCheckInEnabled = true)))
    }

    @Test
    fun selectionRequiresEnabledAndConnectedProvider() = runTest {
        val auth = SequenceCheckInProvider(
            testProviderMeta("auth").copy(credentialType = CredentialType.SESSION_TOKEN),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val selectedFree = SequenceCheckInProvider(
            testProviderMeta("free").copy(credentialType = CredentialType.NONE),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val unselectedAuth = SequenceCheckInProvider(
            testProviderMeta("unselected").copy(credentialType = CredentialType.SESSION_TOKEN),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val providers = listOf(auth, selectedFree, unselectedAuth)

        assertEquals(
            listOf("free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials, health).map { it.meta.id }
        )

        credentials.save("auth", "synthetic-test-secret")
        health.set("auth", AuthHealth.VALID)
        assertEquals(
            listOf("auth", "free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials, health).map { it.meta.id }
        )
        assertEquals(
            listOf("auth"),
            selectExecutableProviders(setOf("auth"), providers, credentials, health).map { it.meta.id }
        )
    }
}
