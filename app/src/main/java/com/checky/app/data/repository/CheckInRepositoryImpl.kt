package com.checky.app.data.repository

import com.checky.app.data.local.CheckyDao
import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckInRepositoryImpl @Inject constructor(
    private val dao: CheckyDao,
    private val metas: List<ProviderMeta>
) : CheckInRepository {

    private val metaById = metas.associateBy { it.id }

    override fun observeRecords(): Flow<List<CheckInRecord>> =
        dao.observeRecords().map { list -> list.map(::toRecord) }

    override fun observeServices(): Flow<List<ServiceSnapshot>> =
        dao.observeServices().map { entities ->
            entities.mapNotNull { entity ->
                val meta = metaById[entity.serviceId] ?: return@mapNotNull null
                ServiceSnapshot(
                    serviceId = meta.id,
                    displayName = meta.displayName,
                    isEnabled = entity.isEnabled,
                    lastStatus = entity.lastStatus?.let {
                        runCatching { CheckInStatus.valueOf(it) }.getOrNull()
                    },
                    lastReward = if (entity.lastRewardType != null) {
                        runCatching {
                            Reward(RewardType.valueOf(entity.lastRewardType), entity.lastRewardAmount)
                        }.getOrNull()
                    } else null,
                    lastMessage = entity.lastMessage,
                    lastTimestamp = entity.lastTimestamp
                )
            }
        }

    override suspend fun getService(id: String): ServiceSnapshot? {
        val entity = dao.getService(id)
        val meta = metaById[id] ?: return null
        if (entity == null) return null
        return ServiceSnapshot(
            serviceId = meta.id,
            displayName = meta.displayName,
            isEnabled = entity.isEnabled,
            lastStatus = entity.lastStatus?.let { runCatching { CheckInStatus.valueOf(it) }.getOrNull() },
            lastReward = entity.lastRewardType?.let {
                runCatching { Reward(RewardType.valueOf(it), entity.lastRewardAmount) }.getOrNull()
            },
            lastMessage = entity.lastMessage,
            lastTimestamp = entity.lastTimestamp
        )
    }

    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {
        val meta = metaById[serviceId] ?: return
        val existing = dao.getService(serviceId)
        dao.upsertService(
            existing?.copy(
                displayName = meta.displayName,
                isEnabled = enabled
            ) ?: com.checky.app.data.local.entity.ServiceEntity(
                serviceId = serviceId,
                displayName = meta.displayName,
                isEnabled = enabled,
                lastStatus = null,
                lastRewardType = null,
                lastRewardAmount = 0,
                lastMessage = null,
                lastTimestamp = null
            )
        )
    }

    override suspend fun saveResult(result: CheckInResult) {
        dao.insertRecord(
            com.checky.app.data.local.entity.CheckInRecordEntity(
                id = UUID.randomUUID().toString(),
                serviceId = result.serviceId,
                serviceName = result.serviceName,
                status = result.status.name,
                rewardType = result.reward.type.name,
                rewardAmount = result.reward.amount,
                message = result.message,
                diagnosticCode = result.diagnosticCode,
                durationMs = result.durationMs,
                timestamp = result.timestamp
            )
        )
        val existing = dao.getService(result.serviceId)
        val isEnabled = existing?.isEnabled ?: false
        dao.upsertService(
            com.checky.app.data.local.entity.ServiceEntity(
                serviceId = result.serviceId,
                displayName = result.serviceName,
                isEnabled = isEnabled,
                lastStatus = result.status.name,
                lastRewardType = result.reward.type.name,
                lastRewardAmount = result.reward.amount,
                lastMessage = result.message,
                lastTimestamp = result.timestamp
            )
        )
    }

    override suspend fun clearHistory() {
        dao.clearRecords()
    }

    private fun toRecord(entity: com.checky.app.data.local.entity.CheckInRecordEntity): CheckInRecord =
        CheckInRecord(
            id = entity.id,
            serviceId = entity.serviceId,
            serviceName = entity.serviceName,
            status = runCatching { CheckInStatus.valueOf(entity.status) }.getOrDefault(CheckInStatus.FAILED),
            reward = runCatching {
                Reward(RewardType.valueOf(entity.rewardType), entity.rewardAmount)
            }.getOrDefault(Reward(RewardType.NONE, 0)),
            message = entity.message,
            diagnosticCode = entity.diagnosticCode,
            durationMs = entity.durationMs,
            timestamp = entity.timestamp
        )
}
