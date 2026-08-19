package com.checky.app.domain

/**
 * Per-provider credential vault.
 *
 * The MVP ships a mock implementation; a Keystore-backed encrypted
 * implementation can replace it without touching any other layer.
 *
 * Rules enforced by the architecture:
 * - credentials are stored **per provider** (one provider can never read another's)
 * - secrets are never written to logs, shared preferences, or backups
 * - the user can delete a single credential or all credentials
 */
interface CredentialStore {
    suspend fun save(providerId: String, secret: String)
    suspend fun get(providerId: String): String?
    suspend fun has(providerId: String): Boolean
    suspend fun delete(providerId: String)
    suspend fun deleteAll()
}
