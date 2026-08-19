package com.checky.app.data.security

import com.checky.app.domain.CredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credential lifecycle contract tests. The mock store is JVM-safe, so it runs
 * as a unit test; the Keystore implementation is covered by instrumented
 * testing on a device.
 */
class CredentialStoreTest {

    private fun store(): CredentialStore = MockCredentialStore()

    @Test
    fun saveAndGetRoundTrip() = runTest {
        val store = store()
        store.save("cloudbox", "secret-token")
        assertEquals("secret-token", store.get("cloudbox"))
        assertTrue(store.has("cloudbox"))
    }

    @Test
    fun credentialsAreIsolatedPerProvider() = runTest {
        val store = store()
        store.save("cloudbox", "cloud-token")
        store.save("gamepass", "game-token")

        // One provider can never read another provider's credential.
        assertEquals("cloud-token", store.get("cloudbox"))
        assertEquals("game-token", store.get("gamepass"))
        assertFalse(store.has("studyclub"))
        assertNull(store.get("studyclub"))
    }

    @Test
    fun deleteRemovesOnlyThatProvider() = runTest {
        val store = store()
        store.save("cloudbox", "cloud-token")
        store.save("gamepass", "game-token")

        store.delete("cloudbox")

        assertFalse(store.has("cloudbox"))
        assertNull(store.get("cloudbox"))
        assertTrue(store.has("gamepass"))
    }

    @Test
    fun deleteAllClearsEverything() = runTest {
        val store = store()
        store.save("cloudbox", "cloud-token")
        store.save("gamepass", "game-token")
        store.save("studyclub", "study-token")

        store.deleteAll()

        assertFalse(store.has("cloudbox"))
        assertFalse(store.has("gamepass"))
        assertFalse(store.has("studyclub"))
    }

    @Test
    fun missingCredentialReportsAbsent() = runTest {
        val store = store()
        assertFalse(store.has("unknown"))
        assertNull(store.get("unknown"))
    }
}
