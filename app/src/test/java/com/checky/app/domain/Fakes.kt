package com.checky.app.domain

import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Minimal in-memory fake used by the check-in flow tests. */
class FakeCheckInRepository(
    private val initialServices: List<ServiceSnapshot> = emptyList()
) : CheckInRepository {
    val saved = mutableListOf<CheckInResult>()

    override fun observeRecords(): Flow<List<CheckInRecord>> = flowOf(emptyList())
    override fun observeServices(): Flow<List<ServiceSnapshot>> = flowOf(initialServices)
    override suspend fun getService(id: String): ServiceSnapshot? = null
    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {}
    override suspend fun saveResult(result: CheckInResult) {
        saved.add(result)
    }
    override suspend fun clearHistory() {}
}

/** In-memory credential store with per-provider isolation. */
class FakeCredentialStore(
    private val vault: MutableMap<String, String> = mutableMapOf()
) : CredentialStore {
    override suspend fun save(providerId: String, secret: String) {
        vault[providerId] = secret
    }

    override suspend fun get(providerId: String): String? = vault[providerId]

    override suspend fun has(providerId: String): Boolean = vault.containsKey(providerId)

    override suspend fun delete(providerId: String) {
        vault.remove(providerId)
    }

    override suspend fun deleteAll() {
        vault.clear()
    }
}

/** In-memory auth evidence store for gate, migration, and orchestration tests. */
class FakeAuthHealthStore : AuthHealthStore {
    val states = mutableMapOf<String, AuthHealth>()

    override suspend fun get(ownerId: String): AuthHealth = states[ownerId] ?: AuthHealth.UNVERIFIED
    override suspend fun hasRecord(ownerId: String): Boolean = ownerId in states
    override suspend fun set(ownerId: String, health: AuthHealth) {
        states[ownerId] = health
    }
    override suspend fun clear(ownerId: String) {
        states.remove(ownerId)
    }
}

/**
 * Deterministic [CheckInProvider] for orchestration tests: emits a progress
 * event then a single [CheckInStatus] outcome with the given reward.
 */
class ScriptedCheckInProvider(
    override val meta: ProviderMeta = testProviderMeta(),
    private val status: CheckInStatus,
    private val reward: Reward = Reward.empty(),
    /** Virtual-time pause before the outcome, so cancel tests can interleave. */
    private val delayMs: Long = 150
) : CheckInProvider {

    override fun checkIn(): Flow<CheckInEvent> = flow {
        emit(CheckInEvent.Progress(0.5f, "working"))
        kotlinx.coroutines.delay(delayMs)
        emit(
            CheckInEvent.Done(
                CheckInResult(
                    serviceId = meta.id,
                    serviceName = meta.displayName,
                    outcome = outcomeFor(status, reward),
                    timestamp = System.currentTimeMillis()
                )
            )
        )
    }

    override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
}

private fun outcomeFor(
    status: CheckInStatus,
    reward: Reward
): CheckInOutcome = when (status) {
    CheckInStatus.SUCCESS -> CheckInOutcome.Success("ok", "SUCCESS", reward)
    CheckInStatus.ALREADY_CHECKED_IN -> CheckInOutcome.AlreadyCompleted("already", "ALREADY", reward)
    CheckInStatus.LOGIN_EXPIRED -> CheckInOutcome.AuthenticationExpired("expired")
    CheckInStatus.USER_ACTION_REQUIRED -> CheckInOutcome.ActionRequired("action")
    else -> CheckInOutcome.TemporaryFailure("failed")
}

/** Catalog meta for test-only providers. */
fun testProviderMeta(id: String = "test_${System.nanoTime()}"): ProviderMeta = ProviderMeta(
    id = id,
    displayName = "Test Provider",
    description = "",
    category = "Test",
    iconKey = "star",
    accentColor = 0xFF000000,
    isEnabledByDefault = true
)

/**
 * A provider whose attempts return the given outcomes in order (the last one
 * repeats). Tracks how many times [checkIn] was invoked.
 */
class SequenceCheckInProvider(
    override val meta: ProviderMeta = testProviderMeta(),
    private vararg val outcomes: CheckInOutcome
) : CheckInProvider {
    var attempts = 0
        private set

    override fun checkIn(): Flow<CheckInEvent> = flow {
        attempts++
        val outcome = outcomes.getOrElse((attempts - 1).coerceAtMost(outcomes.size - 1)) {
            outcomes.first()
        }
        emit(CheckInEvent.Progress(0.5f, "working"))
        kotlinx.coroutines.delay(50)
        emit(CheckInEvent.Done(CheckInResult(meta.id, meta.displayName, outcome, System.currentTimeMillis())))
    }

    override suspend fun validateCredentials(secret: String) = CredentialValidation.Valid
}
