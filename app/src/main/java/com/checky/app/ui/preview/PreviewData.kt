package com.checky.app.ui.preview

import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.model.ServiceCheckInState
import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot

val PreviewProviderMetas = listOf(
    ProviderMeta(
        id = "gamepass",
        displayName = "GamePass Daily",
        description = "Claim your daily GamePass reward and keep the streak alive.",
        category = "Gaming",
        iconKey = "gamepad",
        accentColor = 0xFF107C10,
        isEnabledByDefault = true
    ),
    ProviderMeta(
        id = "cloudbox",
        displayName = "CloudBox",
        description = "Keep your cloud vault synced and your streak alive.",
        category = "Cloud",
        iconKey = "cloud",
        accentColor = 0xFF0A7BC2,
        isEnabledByDefault = true
    ),
    ProviderMeta(
        id = "studyclub",
        displayName = "StudyClub",
        description = "Log your daily study streak and earn experience.",
        category = "Learning",
        iconKey = "school",
        accentColor = 0xFF7C3AED,
        isEnabledByDefault = true
    )
)

fun previewStates(): Map<String, ServiceCheckInState> = mapOf(
    "gamepass" to ServiceCheckInState(
        meta = PreviewProviderMetas[0],
        status = CheckInStatus.SUCCESS,
        progress = 1f,
        message = "Daily reward claimed: +20 points",
        reward = Reward(RewardType.POINTS, 20),
        timestamp = System.currentTimeMillis()
    ),
    "cloudbox" to ServiceCheckInState(
        meta = PreviewProviderMetas[1],
        status = CheckInStatus.LOGIN_EXPIRED,
        progress = 1f,
        message = "Login expired. Reconnect required.",
        reward = null,
        timestamp = System.currentTimeMillis()
    ),
    "studyclub" to ServiceCheckInState(
        meta = PreviewProviderMetas[2],
        status = CheckInStatus.ALREADY_CHECKED_IN,
        progress = 1f,
        message = "Already checked in today: +5 XP",
        reward = Reward(RewardType.EXPERIENCE, 5),
        timestamp = System.currentTimeMillis()
    )
)

fun previewSnapshots(): List<ServiceSnapshot> = PreviewProviderMetas.mapIndexed { i, meta ->
    val state = previewStates()[meta.id]!!
    ServiceSnapshot(
        serviceId = meta.id,
        displayName = meta.displayName,
        isEnabled = true,
        lastStatus = state.status,
        lastReward = state.reward,
        lastMessage = state.message,
        lastTimestamp = state.timestamp
    )
}

fun previewRecords(): List<CheckInRecord> = previewStates().values.map {
    CheckInRecord(
        id = it.meta.id,
        serviceId = it.meta.id,
        serviceName = it.meta.displayName,
        status = it.status,
        reward = it.reward ?: Reward(RewardType.NONE, 0),
        message = it.message,
        diagnosticCode = "SUCCESS",
        durationMs = 1200,
        timestamp = it.timestamp!!
    )
}

fun previewFinished(): CheckInAllProgress.Finished = CheckInAllProgress.Finished(
    states = previewStates(),
    summary = CheckInSummary(
        total = 3,
        succeeded = 1,
        alreadyCheckedIn = 1,
        failed = 0,
        attention = 1,
        totalPoints = 20,
        totalXp = 5,
        durationMs = 2400
    )
)

fun previewRunning(): CheckInAllProgress.Running = CheckInAllProgress.Running(
    states = previewStates().toMutableMap().apply {
        put(
            "gamepass",
            previewStates()["gamepass"]!!.copy(status = CheckInStatus.RUNNING, progress = 0.6f, message = "Authenticating session…")
        )
    }
)
