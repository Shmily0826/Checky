package com.checky.app.domain

/** Fail-closed connection check shared by foreground and background execution. */
object ProviderConnectionGate {
    suspend fun isConnected(provider: CheckInProvider, credentialStore: CredentialStore): Boolean =
        when {
            provider is SmsLoginProvider -> provider.isSmsConnected()
            provider.requiresCredentials -> credentialStore.has(provider.meta.id)
            else -> true
        }
}
