package com.checky.app.domain

/** Local evidence about whether a credential/session is currently usable. */
enum class AuthHealth {
    /** No provider-confirmed evidence has been recorded yet. */
    UNVERIFIED,

    /** A provider-confirmed positive terminal result or login was observed. */
    VALID,

    /** A provider explicitly reported that the session has expired. */
    EXPIRED
}

/** Stores auth health metadata only; reusable credentials remain in CredentialStore. */
interface AuthHealthStore {
    suspend fun get(ownerId: String): AuthHealth
    suspend fun hasRecord(ownerId: String): Boolean
    suspend fun set(ownerId: String, health: AuthHealth)
    suspend fun clear(ownerId: String)
}

/** Safe fallback for JVM tests that do not exercise persistence. */
object NoOpAuthHealthStore : AuthHealthStore {
    override suspend fun get(ownerId: String): AuthHealth = AuthHealth.UNVERIFIED
    override suspend fun hasRecord(ownerId: String): Boolean = false
    override suspend fun set(ownerId: String, health: AuthHealth) = Unit
    override suspend fun clear(ownerId: String) = Unit
}

/** Safe empty credential source used only by the one-argument use-case test constructor. */
object NoOpCredentialStore : CredentialStore {
    override suspend fun save(providerId: String, secret: String) = Unit
    override suspend fun get(providerId: String): String? = null
    override suspend fun has(providerId: String): Boolean = false
    override suspend fun delete(providerId: String) = Unit
    override suspend fun deleteAll() = Unit
}
