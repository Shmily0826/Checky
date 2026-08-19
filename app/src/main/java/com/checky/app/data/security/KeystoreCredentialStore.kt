package com.checky.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.checky.app.domain.CredentialStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential store backed by the Android Keystore.
 *
 * - One AES-256/GCM key is generated inside the Keystore (non-exportable).
 * - Each provider's secret is encrypted with a fresh random IV; ciphertext +
 *   IV are stored in a private app file (never SharedPreferences, never
 *   cloud-backup-able because the key lives in the Keystore).
 * - Credentials are separated per provider by storage key.
 *
 * Note: Keystore APIs are Android-only, so this class is covered by manual /
 * instrumented testing rather than JVM unit tests.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(
    private val context: Context
) : CredentialStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val secretsDir: File = File(context.filesDir, "secure_credentials").apply { mkdirs() }

    private fun getOrCreateKey(): SecretKey {
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun fileFor(providerId: String): File =
        File(secretsDir, "${providerId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.enc")

    override suspend fun save(providerId: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        fileFor(providerId).writeText(payload)
    }

    override suspend fun get(providerId: String): String? {
        val file = fileFor(providerId)
        if (!file.exists()) return null
        return runCatching {
            val (ivB64, dataB64) = file.readText().split(":", limit = 2)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun has(providerId: String): Boolean = fileFor(providerId).exists()

    override suspend fun delete(providerId: String) {
        fileFor(providerId).delete()
    }

    override suspend fun deleteAll() {
        secretsDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "checky_credentials_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
