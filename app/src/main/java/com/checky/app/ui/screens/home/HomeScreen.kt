package com.checky.app.ui.screens.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.ui.components.RewardBadge
import com.checky.app.ui.components.ServiceCard
import com.checky.app.ui.navigation.CheckyBottomBar
import com.checky.app.ui.preview.previewFinished
import com.checky.app.ui.preview.previewSnapshots
import com.checky.app.ui.theme.CheckyTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import java.time.Instant
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

internal fun currentSummaryServices(
    services: List<ServiceSnapshot>,
    connectionById: Map<String, Boolean>
): List<ServiceSnapshot> = services.filter { connectionById[it.serviceId] == true }

internal fun staleLoginExpiredCanRetry(
    connected: Boolean,
    authHealth: AuthHealth?,
    lastStatus: CheckInStatus?
): Boolean = connected && authHealth == AuthHealth.VALID && lastStatus == CheckInStatus.LOGIN_EXPIRED

@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConnections()
                viewModel.refreshNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == Intent.ACTION_DATE_CHANGED) viewModel.refreshNow()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_DATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshConnections()
        viewModel.refreshNow()
    }
    val homeServices by viewModel.homeServices.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val runStartedAt by viewModel.runStartedAt.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()

    HomeContent(
        navController = navController,
        services = homeServices.map { it.service },
        connectionById = homeServices.associate { it.service.serviceId to it.isConnected },
        authHealthById = homeServices.associate { it.service.serviceId to it.authHealth },
        metas = viewModel.metas,
        progress = progress,
        runStartedAt = runStartedAt,
        now = now,
        isRunning = isRunning,
        onCheckInAll = viewModel::checkInAll,
        onCancel = viewModel::cancelCheckInAll,
        onDismissSummary = viewModel::dismissSummary,
        onOpenService = { id -> navController.navigate("provider_details/$id") },
        onReconnect = { id, reconnect ->
            navController.navigate("connect/$id${if (reconnect) "?reconnect=true" else ""}")
        },
        onRetry = viewModel::retry,
        onAddService = { navController.navigate("add_service") }
    )
}

private data class CardInput(
    val serviceId: String,
    val meta: ProviderMeta,
    val status: CheckInStatus,
    val progress: Float,
    val message: String,
    val reward: Reward?,
    val statusLabel: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?
)

internal fun isOrdinaryPendingCard(
    status: CheckInStatus,
    connected: Boolean,
    needsVerification: Boolean
): Boolean = connected && !needsVerification && status == CheckInStatus.PENDING

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    navController: NavHostController,
    services: List<ServiceSnapshot>,
    metas: List<ProviderMeta>,
    progress: CheckInAllProgress?,
    runStartedAt: Instant? = null,
    now: Instant = Instant.now(),
    isRunning: Boolean,
    onCheckInAll: () -> Unit,
    onCancel: () -> Unit,
    onDismissSummary: () -> Unit,
    onOpenService: (String) -> Unit,
    onReconnect: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
    onAddService: () -> Unit,
    connectionById: Map<String, Boolean> = emptyMap(),
    authHealthById: Map<String, AuthHealth?> = emptyMap()
) {
    val metaById = metas.associateBy { it.id }
    val live = progress?.states.takeIf { hasCurrentLiveBusinessProgress(progress, now, runStartedAt) }
    val cards: List<CardInput> = services.mapNotNull { s ->
        val meta = metaById[s.serviceId] ?: return@mapNotNull null
        val connected = connectionById[s.serviceId] == true
        val hasAuthHealth = authHealthById.containsKey(s.serviceId)
        val authHealth = authHealthById[s.serviceId]
        val needsVerification = hasAuthHealth && authHealth == AuthHealth.UNVERIFIED
        val liveState = live?.get(s.serviceId)
            ?.takeIf { isLiveStateForBusinessDate(it, now, runStartedAt) }
        val persisted = projectHomeStatusForConnection(s, meta, now, connected)
        val status = when {
            needsVerification -> CheckInStatus.USER_ACTION_REQUIRED
            !connected -> CheckInStatus.LOGIN_EXPIRED
            liveState != null -> liveState.status
            else -> persisted.status
        }
        val canRetryStaleExpired = staleLoginExpiredCanRetry(connected, authHealth, s.lastStatus)
        CardInput(
            serviceId = s.serviceId,
            meta = meta,
            status = status,
            progress = liveState?.progress ?: if (status == CheckInStatus.RUNNING) 0.5f else 1f,
            message = if (needsVerification) {
                stringResource(R.string.home_credential_unverified)
            } else if (!connected) {
                stringResource(R.string.home_not_connected)
            } else if (liveState != null) {
                liveState.message
            } else if (persisted.hasCurrentDayResult) {
                s.lastMessage.orEmpty()
            } else {
                stringResource(R.string.home_not_checked_in_today)
            },
            reward = liveState?.reward ?: if (persisted.hasCurrentDayResult) s.lastReward else null,
            statusLabel = when (status) {
                CheckInStatus.SUCCESS -> stringResource(R.string.home_status_success)
                CheckInStatus.ALREADY_CHECKED_IN -> stringResource(R.string.home_status_already)
                CheckInStatus.PENDING -> stringResource(R.string.home_status_pending)
                else -> null
            },
            actionLabel = when {
                canRetryStaleExpired -> stringResource(R.string.action_retry)
                needsVerification -> stringResource(R.string.connect_manage_connection)
                !connected -> stringResource(if (authHealth == AuthHealth.EXPIRED) R.string.action_reconnect else R.string.action_connect)
                status == CheckInStatus.LOGIN_EXPIRED -> stringResource(R.string.action_reconnect)
                status == CheckInStatus.FAILED -> stringResource(R.string.action_retry)
                status == CheckInStatus.USER_ACTION_REQUIRED -> stringResource(R.string.action_retry_sign_in)
                isOrdinaryPendingCard(status, connected, needsVerification) -> stringResource(R.string.action_check_in)
                else -> null
            },
            onAction = when {
                canRetryStaleExpired -> ({ onRetry(s.serviceId) })
                needsVerification -> ({ onReconnect(s.serviceId, false) })
                !connected -> ({ onReconnect(s.serviceId, authHealth == AuthHealth.EXPIRED) })
                status == CheckInStatus.LOGIN_EXPIRED -> ({ onReconnect(s.serviceId, true) })
                status == CheckInStatus.FAILED -> ({ onRetry(s.serviceId) })
                status == CheckInStatus.USER_ACTION_REQUIRED -> ({ onRetry(s.serviceId) })
                isOrdinaryPendingCard(status, connected, needsVerification) -> ({ onRetry(s.serviceId) })
                else -> null
            }
        )
    }

    val currentServices = currentSummaryServices(services, connectionById)
    val executableIds = currentServices.map { it.serviceId }.toSet()
    val doneCount = countCurrentDoneServices(currentServices, metaById, live, now, runStartedAt)
    val completed = currentServices.count {
        projectHomeStatusForConnection(it, metaById[it.serviceId]!!, now, true).status.isPositive
    }
    val remaining = services.size - completed
    val attention = services.count {
        val connected = connectionById[it.serviceId] == true
        !connected ||
            (projectHomeStatusForConnection(it, metaById[it.serviceId]!!, now, connected).hasCurrentDayResult &&
                it.lastStatus!!.requiresUserAction)
    }
    val points = currentServices.filter { projectHomeStatusForConnection(it, metaById[it.serviceId]!!, now, true).status.isPositive }
        .sumOf { val r = it.lastReward; if (r != null && r.type == RewardType.POINTS) r.amount else 0 }
    val xp = currentServices.filter { projectHomeStatusForConnection(it, metaById[it.serviceId]!!, now, true).status.isPositive }
        .sumOf { val r = it.lastReward; if (r != null && r.type == RewardType.EXPERIENCE) r.amount else 0 }
    val days = currentServices.filter { projectHomeStatusForConnection(it, metaById[it.serviceId]!!, now, true).status.isPositive }
        .sumOf { val r = it.lastReward; if (r != null && r.type == RewardType.MEMBERSHIP_DAY) r.amount else 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Checky",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddService) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_service))
                    }
                }
            )
        },
        bottomBar = { CheckyBottomBar(navController) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (progress is CheckInAllProgress.Finished && runStartedAt != null &&
                hasCurrentLiveBusinessProgress(progress, now, runStartedAt)) {
                item {
                    SummaryBanner(summary = progress.summary, onDismiss = onDismissSummary)
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatsRow(
                        completed = completed,
                        remaining = remaining,
                        attention = attention,
                        points = points,
                        xp = xp,
                        days = days
                    )
                    CheckInAllButton(
                        isRunning = isRunning,
                        doneCount = doneCount,
                        total = executableIds.size,
                        overall = if (executableIds.isNotEmpty()) doneCount.toFloat() / executableIds.size else 0f,
                        onClick = onCheckInAll,
                        onCancel = onCancel
                    )
                }
            }

            if (cards.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.home_no_services), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.home_connect_to_enable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onAddService) { Text(stringResource(R.string.home_add_service)) }
                        }
                    }
                }
            }

            items(cards, key = { it.serviceId }) { card ->
                ServiceCard(
                    meta = card.meta,
                    status = card.status,
                    progress = card.progress,
                    message = card.message,
                    reward = card.reward,
                    isRunning = card.status == CheckInStatus.RUNNING,
                    statusLabel = card.statusLabel,
                    actionLabel = card.actionLabel,
                    onAction = card.onAction,
                    onClick = { onOpenService(card.serviceId) }
                )
            }
        }
    }
}

@Composable
private fun StatsRow(
    completed: Int,
    remaining: Int,
    attention: Int,
    points: Int,
    xp: Int,
    days: Int
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatChip(label = stringResource(R.string.home_done), value = completed, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
        StatChip(label = stringResource(R.string.home_remaining), value = remaining, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StatChip(label = stringResource(R.string.home_attention), value = attention, color = Color(0xFFE0A000), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (points > 0) RewardBadge(Reward(RewardType.POINTS, points))
        if (xp > 0) RewardBadge(Reward(RewardType.EXPERIENCE, xp))
        if (days > 0) RewardBadge(Reward(RewardType.MEMBERSHIP_DAY, days))
    }
}

@Composable
private fun StatChip(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheckInAllButton(
    isRunning: Boolean,
    doneCount: Int,
    total: Int,
    overall: Float,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            enabled = !isRunning && total > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                text = if (isRunning) stringResource(R.string.home_checking_in, doneCount, total) else stringResource(R.string.home_check_in_all),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (isRunning) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { overall.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        }
    }
}

@Composable
private fun SummaryBanner(summary: com.checky.app.domain.model.CheckInSummary, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_all_done),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_succeeded, summary.succeeded), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.home_already_done, summary.alreadyCheckedIn), style = MaterialTheme.typography.bodySmall)
                if (summary.attention > 0)
                    Text(stringResource(R.string.home_need_attention, summary.attention), style = MaterialTheme.typography.bodySmall)
            }
            if (summary.totalPoints > 0 || summary.totalXp > 0 || summary.totalDays > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (summary.totalPoints > 0) RewardBadge(Reward(RewardType.POINTS, summary.totalPoints))
                    if (summary.totalXp > 0) RewardBadge(Reward(RewardType.EXPERIENCE, summary.totalXp))
                    if (summary.totalDays > 0) RewardBadge(Reward(RewardType.MEMBERSHIP_DAY, summary.totalDays))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_got_it))
            }
        }
    }
}

@Preview
@Composable
private fun HomePreview() {
    CheckyTheme {
        HomeContent(
            navController = rememberNavController(),
            services = previewSnapshots(),
            metas = previewSnapshots().map {
                // build minimal metas for preview from known ids
                com.checky.app.ui.preview.PreviewProviderMetas.first { m -> m.id == it.serviceId }
            },
            progress = previewFinished(),
            isRunning = false,
            onCheckInAll = {},
            onCancel = {},
            onDismissSummary = {},
            onOpenService = {},
            onReconnect = { _, _ -> },
            onRetry = {},
            onAddService = {},
            connectionById = previewSnapshots().associate { it.serviceId to true }
        )
    }
}

@Preview
@Composable
private fun HomeRunningPreview() {
    CheckyTheme {
        HomeContent(
            navController = rememberNavController(),
            services = previewSnapshots(),
            metas = previewSnapshots().map {
                com.checky.app.ui.preview.PreviewProviderMetas.first { m -> m.id == it.serviceId }
            },
            progress = com.checky.app.ui.preview.previewRunning(),
            isRunning = true,
            onCheckInAll = {},
            onCancel = {},
            onDismissSummary = {},
            onOpenService = {},
            onReconnect = { _, _ -> },
            onRetry = {},
            onAddService = {},
            connectionById = previewSnapshots().associate { it.serviceId to true }
        )
    }
}
