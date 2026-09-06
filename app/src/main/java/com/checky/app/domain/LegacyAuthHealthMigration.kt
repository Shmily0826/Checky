package com.checky.app.domain

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus

/** One-time, local-only continuity migration from persisted terminal results. */
object LegacyAuthHealthMigration {
    suspend fun seedIfNeeded(
        providers: List<CheckInProvider>,
        services: List<ServiceSnapshot>,
        credentialStore: CredentialStore,
        authHealthStore: AuthHealthStore
    ) {
        providers
            .filter { it.requiresCredentials }
            .groupBy { it.credentialOwnerId }
            .forEach { (ownerId, ownedProviders) ->
                if (!credentialStore.has(ownerId)) {
                    authHealthStore.clear(ownerId)
                    return@forEach
                }
                if (authHealthStore.hasRecord(ownerId)) return@forEach

                val statuses = ownedProviders.mapNotNull { provider ->
                    services.firstOrNull { it.serviceId == provider.meta.id }?.lastStatus
                }
                val seeded = when {
                    CheckInStatus.LOGIN_EXPIRED in statuses -> AuthHealth.EXPIRED
                    statuses.any { it == CheckInStatus.SUCCESS || it == CheckInStatus.ALREADY_CHECKED_IN } ->
                        AuthHealth.VALID
                    else -> AuthHealth.UNVERIFIED
                }
                authHealthStore.set(ownerId, seeded)
            }
    }
}
