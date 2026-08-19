package com.checky.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Developer-only mock outcome override.
 *
 * Lets the developer force every mock provider to return a specific outcome
 * for testing the UI and orchestration without changing provider code.
 */
enum class MockScenario {
    /** Each provider's natural scripted outcome. */
    DEFAULT,

    SUCCESS,
    ALREADY_COMPLETED,
    AUTH_EXPIRED,
    NETWORK_FAILURE,
    ACTION_REQUIRED
}

/** Where the current [MockScenario] override lives (non-sensitive preference). */
interface MockScenarioStore {
    fun scenario(): Flow<MockScenario>
    suspend fun setScenario(scenario: MockScenario)
}
