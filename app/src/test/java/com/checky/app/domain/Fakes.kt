package com.checky.app.domain

import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Minimal in-memory fake used by the check-in flow tests. */
class FakeCheckInRepository : CheckInRepository {
    val saved = mutableListOf<CheckInResult>()

    override fun observeRecords(): Flow<List<CheckInRecord>> = flowOf(emptyList())
    override fun observeServices(): Flow<List<ServiceSnapshot>> = flowOf(emptyList())
    override suspend fun getService(id: String): ServiceSnapshot? = null
    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {}
    override suspend fun saveResult(result: CheckInResult) {
        saved.add(result)
    }
    override suspend fun clearHistory() {}
    override suspend fun ensureSeeded() {}
    override suspend fun resetDemoData() {}
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

/** Deterministic mock-scenario store for tests. */
class FakeMockScenarioStore(
    initial: MockScenario = MockScenario.DEFAULT
) : MockScenarioStore {
    private val _scenario = MutableStateFlow(initial)

    override fun scenario(): Flow<MockScenario> = _scenario

    override suspend fun setScenario(scenario: MockScenario) {
        _scenario.value = scenario
    }
}
