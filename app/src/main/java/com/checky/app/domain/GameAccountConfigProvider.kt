package com.checky.app.domain

/**
 * Optional provider capability for a session that also needs a game role.
 * The account configuration is stored together with the provider session in
 * the device credential vault; it is never logged or sent to another host.
 */
data class GameAccountConfig(
    val uid: String,
    val region: String
)

interface GameAccountConfigProvider {
    suspend fun gameAccountConfig(): GameAccountConfig?

    /** Validates and persists the selected game role alongside the session. */
    suspend fun saveGameAccountConfig(uid: String, region: String): CredentialValidation
}
