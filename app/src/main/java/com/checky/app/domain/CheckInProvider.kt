package com.checky.app.domain

import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import kotlinx.coroutines.flow.Flow

/**
 * Streamed events emitted by a [CheckInProvider] while checking in.
 */
sealed interface CheckInEvent {
    /** Intermediate progress update (fraction in 0f..1f). */
    data class Progress(val fraction: Float, val message: String) : CheckInEvent

    /** Terminal event carrying the final [CheckInResult]. */
    data class Done(val result: CheckInResult) : CheckInEvent
}

/**
 * The contract every check-in integration must implement.
 *
 * Mock providers implement this today; real providers (with their own
 * network/credential logic) can be dropped in later without touching the UI
 * or the orchestration use case.
 */
interface CheckInProvider {
    val meta: ProviderMeta

    /** Actual local credential/session owner; defaults to one owner per provider. */
    val credentialOwnerId: String
        get() = meta.id

    /** Whether the user must supply a credential before this provider can run. */
    val requiresCredentials: Boolean
        get() = meta.credentialType != CredentialType.NONE

    /**
     * Perform the check-in and stream progress. Must emit exactly one
     * [CheckInEvent.Done] carrying a safe [CheckInResult].
     */
    fun checkIn(): Flow<CheckInEvent>

    /**
     * Validate a candidate credential before it is saved. Implementations must
     * never log or return the secret itself.
     */
    suspend fun validateCredentials(secret: String): CredentialValidation

    /** Whether the provider supports a "disconnect" action. */
    fun supportsDisconnect(): Boolean = false

    /** Disconnect this provider (clears provider-side session state if any). */
    suspend fun disconnect() {}
}

/** Result of credential validation — safe, user-facing. */
sealed class CredentialValidation {
    data object Valid : CredentialValidation()
    data class Invalid(val reason: String) : CredentialValidation()
}
