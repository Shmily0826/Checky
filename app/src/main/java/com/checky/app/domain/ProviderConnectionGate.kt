package com.checky.app.domain

/** Fail-closed connection check shared by foreground and background execution. */
object ProviderConnectionGate {
    suspend fun health(
        provider: CheckInProvider,
        credentialStore: CredentialStore,
        authHealthStore: AuthHealthStore = NoOpAuthHealthStore
    ): AuthHealth? = when {
        !provider.requiresCredentials -> AuthHealth.VALID
        !credentialStore.has(provider.credentialOwnerId) -> null
        else -> authHealthStore.get(provider.credentialOwnerId)
    }

    suspend fun isConnected(
        provider: CheckInProvider,
        credentialStore: CredentialStore,
        authHealthStore: AuthHealthStore = NoOpAuthHealthStore
    ): Boolean = health(provider, credentialStore, authHealthStore) == AuthHealth.VALID
}
