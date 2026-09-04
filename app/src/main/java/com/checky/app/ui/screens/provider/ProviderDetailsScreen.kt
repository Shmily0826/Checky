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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.ui.components.ProviderIcon
import com.checky.app.ui.components.RewardBadge
import com.checky.app.ui.components.StatusChip
import com.checky.app.ui.components.localizedProviderCategory
import com.checky.app.ui.components.localizedProviderDescription
import com.checky.app.ui.components.localizedProviderName
import com.checky.app.ui.theme.CheckyTheme
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ProviderDetailsScreen(
    navController: NavHostController,
    viewModel: ProviderDetailsViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshConnection()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val service by viewModel.service.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val meta = viewModel.meta
    ProviderDetailsContent(
        navController = navController,
        meta = meta,
        service = service,
        isConnected = isConnected,
        onToggle = viewModel::setEnabled,
        onManageConnection = { navController.navigate("connect/$it") }
    )
}

private fun formatTime(ts: Long?): String =
    if (ts == null) "—" else SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ts))

internal enum class ConnectionAction { MANAGE_CONNECTION, RECONNECT }

internal fun connectionAction(isConnected: Boolean): ConnectionAction =
    if (isConnected) ConnectionAction.MANAGE_CONNECTION else ConnectionAction.RECONNECT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailsContent(
    navController: NavHostController,
    meta: ProviderMeta?,
    service: ServiceSnapshot?,
    isConnected: Boolean,
    onToggle: (Boolean) -> Unit,
    onManageConnection: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (meta != null) localizedProviderName(meta) else stringResource(R.string.connect_service),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        if (meta == null) {
            Text(stringResource(R.string.connect_unknown_service), modifier = Modifier.padding(24.dp))
            return@Scaffold
        }
        val lastCheckInStatus = service?.lastStatus ?: CheckInStatus.PENDING
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            ProviderIcon(meta = meta, size = 72.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(localizedProviderName(meta), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(localizedProviderCategory(meta), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                localizedProviderDescription(meta),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.provider_status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            if (isConnected) stringResource(R.string.connect_connected) else stringResource(R.string.home_not_connected),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.provider_last_checkin), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        StatusChip(status = lastCheckInStatus)
                    }
                    val lastMessage = service?.lastMessage
                    if (!lastMessage.isNullOrBlank()) {
                        Text(lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.provider_last_update), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(formatTime(service?.lastTimestamp), style = MaterialTheme.typography.bodySmall)
                    }
                    val reward = service?.lastReward
                    if (reward != null && !reward.isEmpty) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.provider_reward), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            RewardBadge(reward = reward)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.provider_enabled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Switch(checked = service?.isEnabled ?: false, onCheckedChange = onToggle)
                    }
                }
            }

            if (meta.credentialType != com.checky.app.domain.model.CredentialType.NONE) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onManageConnection(meta.id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(
                        if (isConnected) stringResource(R.string.connect_manage_connection)
                        else stringResource(R.string.connect_reconnect)
                    )
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
            isConnected = true,
            onToggle = {},
            onManageConnection = {}
        )
    }
}
