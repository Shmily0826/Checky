package com.checky.app.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.model.CheckInRecord
import com.checky.app.ui.components.RewardBadge
import com.checky.app.ui.components.StatusChip
import com.checky.app.ui.preview.previewRecords
import com.checky.app.ui.theme.CheckyTheme
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun HistoryScreen(
    navController: NavHostController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    HistoryContent(
        navController = navController,
        records = records,
        onClear = viewModel::clearHistory
    )
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    navController: NavHostController,
    records: List<CheckInRecord>,
    onClear: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check-in history", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear history")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = "No check-ins yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Run \"Check in all\" to see your history here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                item(key = "heatmap") {
                    HistoryHeatmapSection(records = records)
                }
                items(records, key = { it.id }) { record ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.serviceName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = record.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatTime(record.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                val duration = record.durationMs
                                val diagnostic = record.diagnosticCode
                                val metaText = buildString {
                                    if (duration > 0) append("${duration} ms")
                                    if (diagnostic.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(diagnostic)
                                    }
                                }
                                if (metaText.isNotEmpty()) {
                                    Text(
                                        text = metaText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                StatusChip(status = record.status)
                                if (!record.reward.isEmpty) {
                                    RewardBadge(reward = record.reward)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun HistoryPreview() {
    CheckyTheme {
        HistoryContent(
            navController = rememberNavController(),
            records = previewRecords(),
            onClear = {}
        )
    }
}


@Composable
private fun HistoryHeatmapSection(records: List<CheckInRecord>) {
    val cells = remember(records) {
        HistoryHeatmap.computeCells(records, today = LocalDate.now())
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last 4 weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            // 4 rows x 7 columns, oldest top-left, today bottom-right.
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    color = when (cell.quality) {
                                        DayQuality.GOOD -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                        DayQuality.BAD -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                                        DayQuality.EMPTY -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = MaterialTheme.shapes.small
                                )
                        )
                    }
                }
            }
            Text(
                text = "Green: all checked in · Red: any failure · Gray: no check-in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
