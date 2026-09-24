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

/** A game role reported by the service for the connected account. */
data class GameRole(
    val config: GameAccountConfig,
    /** User-facing description, e.g. "天空岛 · 派蒙 Lv.60". */
    val label: String
)

interface GameAccountConfigProvider {
    suspend fun gameAccountConfig(): GameAccountConfig?

    /** Whether a saved session is structurally ready for an explicit role lookup. */
    suspend fun hasCompatibleSavedSessionForRoleLookup(): Boolean = false

    /** Validates and persists the selected game role alongside the session. */
    suspend fun saveGameAccountConfig(uid: String, region: String): CredentialValidation

    /**
     * Roles bound to the connected account. Best-effort: an empty list means
     * "unknown, fall back to manual entry" — never an auth error.
     */
    suspend fun fetchGameRoles(): List<GameRole> = emptyList()
}
