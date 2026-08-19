package com.checky.app.data.security

import com.checky.app.domain.CredentialStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory credential store for the mock MVP.
 *
 * Secrets live only in process memory and disappear when the app exits.
 * Swap the Hilt binding to [KeystoreCredentialStore] for real encrypted,
 * persistent storage — nothing else changes.
 */
@Singleton
class MockCredentialStore @Inject constructor() : CredentialStore {

    private val vault = mutableMapOf<String, String>()

    override suspend fun save(providerId: String, secret: String) {
        vault[providerId] = secret
    }

    override suspend fun get(providerId: String): String? = vault[providerId]

    override suspend fun has(providerId: String): Boolean = vault.containsKey(providerId)

    override suspend fun delete(providerId: String) {
        vault.remove(providerId)
    }

    override suspend fun deleteAll() {
        vault.clear()
    }
}
