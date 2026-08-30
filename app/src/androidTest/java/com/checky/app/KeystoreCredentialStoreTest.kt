package com.checky.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.checky.app.data.security.KeystoreCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device coverage of the Android Keystore credential vault — the
 * security-critical component that JVM tests cannot exercise (Keystore APIs
 * do not exist on the JVM).
 *
 * Verified here: round-trip fidelity, per-provider isolation, delete
 * semantics, ciphertext-at-rest (the stored file never contains the
 * plaintext secret), fresh-IV re-encryption, and fail-closed behavior on a
 * corrupted file.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreCredentialStore(context)
    private val secretsDir = File(context.filesDir, "secure_credentials")

    @After
    fun tearDown() {
        runBlocking { store.deleteAll() }
    }

    @Test
    fun saveGetRoundTripPreservesExactSecret() = runBlocking {
        val secret = "cookie_v2=abc+def/ghi==; account_id=123456789"
        store.save("provider_a", secret)

        assertEquals(secret, store.get("provider_a"))
        assertTrue(store.has("provider_a"))
    }

    @Test
    fun credentialsAreIsolatedPerProvider() = runBlocking {
        store.save("provider_a", "secret-a")
        store.save("provider_b", "secret-b")

        assertEquals("secret-a", store.get("provider_a"))
        assertEquals("secret-b", store.get("provider_b"))

        store.delete("provider_a")
        assertEquals("secret-b", store.get("provider_b"))
        assertNull(store.get("provider_a"))
        assertFalse(store.has("provider_a"))
    }

    @Test
    fun saveOverwritesExistingCredential() = runBlocking {
        store.save("provider_a", "old-token")
        store.save("provider_a", "new-token")

        assertEquals("new-token", store.get("provider_a"))
    }

    @Test
    fun missingCredentialReportsAbsent() = runBlocking {
        assertNull(store.get("never_saved"))
        assertFalse(store.has("never_saved"))
    }

    @Test
    fun deleteAllClearsEverything() = runBlocking {
        store.save("provider_a", "secret-a")
        store.save("provider_b", "secret-b")

        store.deleteAll()

        assertNull(store.get("provider_a"))
        assertNull(store.get("provider_b"))
    }

    @Test
    fun ciphertextAtRestNeverContainsThePlaintext() = runBlocking {
        val secret = "PLAINTEXT_MARKER_token_123456"
        store.save("provider_a", secret)

        val files = secretsDir.listFiles().orEmpty()
        assertTrue("an encrypted file must exist", files.isNotEmpty())
        files.forEach { file ->
            val content = file.readText()
            assertFalse("stored file must not contain the plaintext", content.contains(secret))
            // payload shape: base64(iv):base64(ciphertext)
            assertTrue(
                "stored payload must be iv:ciphertext base64",
                Regex("^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$").containsMatchIn(content)
            )
        }
    }

    @Test
    fun resavingUsesAFreshIvSoCiphertextDiffers() = runBlocking {
        store.save("provider_a", "same-secret")
        val first = secretsDir.listFiles()!!.single().readText()

        store.save("provider_a", "same-secret")
        val second = secretsDir.listFiles()!!.single().readText()

        assertTrue("IV must be fresh per save", first != second)
        // ...while the decrypted value is identical.
        assertEquals("same-secret", store.get("provider_a"))
    }

    @Test
    fun corruptedFileFailsClosedInsteadOfCrashing() = runBlocking {
        store.save("provider_a", "secret-a")
        secretsDir.listFiles()!!.single().writeText("garbage-not-a-payload")

        assertNull("corrupt credential must fail closed", store.get("provider_a"))
    }
}
