package com.checky.app.ui.screens.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInAllProgress
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

@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val services by viewModel.services.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()

    HomeContent(
        navController = navController,
        services = services,
        metas = viewModel.metas,
        progress = progress,
        isRunning = isRunning,
        onCheckInAll = viewModel::checkInAll,
        onCancel = viewModel::cancelCheckInAll,
        onDismissSummary = viewModel::dismissSummary,
        onOpenService = { id -> navController.navigate("provider_details/$id") },
        onReconnect = { id -> navController.navigate("connect/$id") },
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
    val actionLabel: String?,
    val onAction: (() -> Unit)?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    navController: NavHostController,
    services: List<ServiceSnapshot>,
    metas: List<ProviderMeta>,
    progress: CheckInAllProgress?,
    isRunning: Boolean,
    onCheckInAll: () -> Unit,
    onCancel: () -> Unit,
    onDismissSummary: () -> Unit,
    onOpenService: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAddService: () -> Unit
) {
    val metaById = metas.associateBy { it.id }
    val live = progress?.states

    val cards: List<CardInput> = if (live != null) {
        live.values.map { state ->
            CardInput(
                serviceId = state.meta.id,
                meta = state.meta,
                status = state.status,
                progress = state.progress,
                message = state.message,
                reward = state.reward,
                actionLabel = when (state.status) {
                    CheckInStatus.LOGIN_EXPIRED -> "Reconnect"
                    CheckInStatus.FAILED -> "Retry"
                    else -> null
                },
                onAction = when (state.status) {
                    CheckInStatus.LOGIN_EXPIRED -> ({ onReconnect(state.meta.id) })
                    CheckInStatus.FAILED -> ({ onRetry(state.meta.id) })
                    else -> null
                }
            )
        }
    } else {
        services.mapNotNull { s ->
            val meta = metaById[s.serviceId] ?: return@mapNotNull null
            val status = s.lastStatus ?: CheckInStatus.PENDING
            CardInput(
                serviceId = s.serviceId,
                meta = meta,
                status = status,
                progress = if (status == CheckInStatus.RUNNING) 0.5f else 1f,
                message = s.lastMessage ?: "",
                reward = s.lastReward,
                actionLabel = when (status) {
                    CheckInStatus.LOGIN_EXPIRED -> "Reconnect"
                    CheckInStatus.FAILED -> "Retry"
                    else -> null
                },
                onAction = when (status) {
                    CheckInStatus.LOGIN_EXPIRED -> ({ onReconnect(s.serviceId) })
                    CheckInStatus.FAILED -> ({ onRetry(s.serviceId) })
                    else -> null
                }
            )
        }
    }

    val doneCount = if (live != null) {
        live.values.count { it.status.isTerminal }
    } else {
        services.count { it.lastStatus?.isTerminal == true }
    }
    val completed = services.count {
        it.lastStatus == CheckInStatus.SUCCESS || it.lastStatus == CheckInStatus.ALREADY_CHECKED_IN
    }
    val remaining = services.size - completed
    val attention = services.count { it.lastStatus?.requiresUserAction == true }
    val points = services.filter { it.lastStatus?.isPositive == true }
        .sumOf { val r = it.lastReward; if (r != null && r.type == RewardType.POINTS) r.amount else 0 }
    val xp = services.filter { it.lastStatus?.isPositive == true }
        .sumOf { val r = it.lastReward; if (r != null && r.type == RewardType.EXPERIENCE) r.amount else 0 }
    val days = services.filter { it.lastStatus?.isPositive == true }
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
                            text = "Your daily check-in buddy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddService) {
                        Icon(Icons.Filled.Add, contentDescription = "Add service")
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
            if (progress is CheckInAllProgress.Finished) {
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
                        total = cards.size,
                        overall = if (cards.isNotEmpty()) doneCount.toFloat() / cards.size else 0f,
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
                            Text("No services connected", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Connect a service to enable daily check-ins.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onAddService) { Text("Add service") }
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
        StatChip(label = "Done", value = completed, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
        StatChip(label = "Remaining", value = remaining, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        StatChip(label = "Attention", value = attention, color = Color(0xFFE0A000), modifier = Modifier.weight(1f))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = onClick,
                enabled = !isRunning && total > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    text = if (isRunning) "Checking in… $doneCount/$total" else "Check in all",
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
                        Text("Cancel")
                    }
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
                text = "All done!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• ${summary.succeeded} succeeded", style = MaterialTheme.typography.bodySmall)
                Text("• ${summary.alreadyCheckedIn} already done", style = MaterialTheme.typography.bodySmall)
                if (summary.attention > 0)
                    Text("• ${summary.attention} need attention", style = MaterialTheme.typography.bodySmall)
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
                Text("Got it")
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
            onReconnect = {},
            onRetry = {},
            onAddService = {}
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
            onReconnect = {},
            onRetry = {},
            onAddService = {}
        )
    }
}
