package com.checky.app.domain

/** Explicit foreground-only, provider-specific read-only credential check. */
interface SavedCredentialRevalidator {
    suspend fun revalidateSavedCredential(): SavedCredentialValidation
}

sealed interface SavedCredentialValidation {
    data object Valid : SavedCredentialValidation
    data object Expired : SavedCredentialValidation
    data class Unverified(val reason: String) : SavedCredentialValidation
}
