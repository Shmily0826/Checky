package com.checky.app.ui.screens.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.ui.components.ProviderIcon
import com.checky.app.ui.components.RewardBadge
import com.checky.app.ui.components.StatusChip
import com.checky.app.ui.theme.CheckyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ProviderDetailsScreen(
    navController: NavHostController,
    viewModel: ProviderDetailsViewModel = hiltViewModel()
) {
    val service by viewModel.service.collectAsStateWithLifecycle()
    val meta = viewModel.meta
    ProviderDetailsContent(
        navController = navController,
        meta = meta,
        service = service,
        onToggle = viewModel::setEnabled,
        onManageConnection = { navController.navigate("connect/$it") }
    )
}

private fun formatTime(ts: Long?): String =
    if (ts == null) "—" else SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailsContent(
    navController: NavHostController,
    meta: ProviderMeta?,
    service: ServiceSnapshot?,
    onToggle: (Boolean) -> Unit,
    onManageConnection: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meta?.displayName ?: "Service", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (meta == null) {
            Text("Unknown service", modifier = Modifier.padding(24.dp))
            return@Scaffold
        }
        val status = service?.lastStatus ?: CheckInStatus.PENDING
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            ProviderIcon(meta = meta, size = 72.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(meta.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(meta.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                meta.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        StatusChip(status = status)
                    }
                    val lastMessage = service?.lastMessage
                    if (!lastMessage.isNullOrBlank()) {
                        Text(lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Last update", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(formatTime(service?.lastTimestamp), style = MaterialTheme.typography.bodySmall)
                    }
                    val reward = service?.lastReward
                    if (reward != null && !reward.isEmpty) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reward", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            RewardBadge(reward = reward)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Switch(checked = service?.isEnabled ?: false, onCheckedChange = onToggle)
                    }
                }
            }

            if (meta.credentialType != com.checky.app.domain.model.CredentialType.NONE) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onManageConnection(meta.id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (status == CheckInStatus.LOGIN_EXPIRED) "Reconnect" else "Manage connection")
                }
            }
        }
    }
}

@Preview
@Composable
private fun ProviderDetailsPreview() {
    CheckyTheme {
        ProviderDetailsContent(
            navController = rememberNavController(),
            meta = com.checky.app.ui.preview.PreviewProviderMetas[1],
            service = com.checky.app.ui.preview.previewSnapshots()[1],
            onToggle = {},
            onManageConnection = {}
        )
    }
}
