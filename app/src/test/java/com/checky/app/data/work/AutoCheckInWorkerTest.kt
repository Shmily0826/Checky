package com.checky.app.data.work

import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.Reward
import com.checky.app.domain.testProviderMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCheckInWorkerTest {
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
        val providers = listOf(auth, selectedFree, unselectedAuth)

        assertEquals(
            listOf("free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials).map { it.meta.id }
        )

        credentials.save("auth", "synthetic-test-secret")
        assertEquals(
            listOf("auth", "free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials).map { it.meta.id }
        )
        assertEquals(
            listOf("auth"),
            selectExecutableProviders(setOf("auth"), providers, credentials).map { it.meta.id }
        )
    }
}
