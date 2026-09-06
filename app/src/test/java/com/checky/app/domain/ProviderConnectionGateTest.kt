package com.checky.app.domain

import com.checky.app.domain.model.CredentialType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionGateTest {
    @Test
    fun credentialRequiredProviderIsBlockedWithoutCredential() = runTest {
        val provider = fakeProvider("auth", CredentialType.SESSION_TOKEN)
        assertFalse(ProviderConnectionGate.isConnected(provider, FakeCredentialStore()))
    }

    @Test
    fun credentialRequiredProviderIsAllowedWithCredential() = runTest {
        val store = FakeCredentialStore()
        store.save("auth", "synthetic")
        val health = FakeAuthHealthStore()
        health.set("auth", AuthHealth.VALID)
        assertTrue(ProviderConnectionGate.isConnected(fakeProvider("auth", CredentialType.SESSION_TOKEN), store, health))
    }

    @Test
    fun credentialPresentButExpiredIsBlockedAndCredentialRemains() = runTest {
        val credentials = FakeCredentialStore()
        credentials.save("auth", "synthetic")
        val health = FakeAuthHealthStore()
        health.set("auth", AuthHealth.EXPIRED)

        assertFalse(ProviderConnectionGate.isConnected(fakeProvider("auth", CredentialType.SESSION_TOKEN), credentials, health))
        assertTrue(credentials.has("auth"))
    }

    @Test
    fun unverifiedIsDistinctFromValidAndBothRequireCredential() = runTest {
        val credentials = FakeCredentialStore()
        credentials.save("auth", "synthetic")
        val health = FakeAuthHealthStore()
        val provider = fakeProvider("auth", CredentialType.SESSION_TOKEN)

        assertEquals(AuthHealth.UNVERIFIED, ProviderConnectionGate.health(provider, credentials, health))
        assertFalse(ProviderConnectionGate.isConnected(provider, credentials, health))
        health.set("auth", AuthHealth.VALID)
        assertEquals(AuthHealth.VALID, ProviderConnectionGate.health(provider, credentials, health))
        assertTrue(ProviderConnectionGate.isConnected(provider, credentials, health))
    }

    @Test
    fun credentialFreeProviderIsAllowedWithoutCredential() = runTest {
        assertTrue(ProviderConnectionGate.isConnected(fakeProvider("public", CredentialType.NONE), FakeCredentialStore()))
    }

    private fun fakeProvider(id: String, credentialType: CredentialType) = object : CheckInProvider {
        override val meta = com.checky.app.domain.model.ProviderMeta(
            id, id, "", "", "", 0L, false, credentialType = credentialType
        )
        override fun checkIn(): Flow<CheckInEvent> = emptyFlow()
        override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
    }
}
