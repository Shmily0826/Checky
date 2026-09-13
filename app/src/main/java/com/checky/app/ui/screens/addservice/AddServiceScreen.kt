package com.checky.app.ui.screens.addservice

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.RiskLevel
import com.checky.app.domain.model.SupportStatus
import com.checky.app.ui.components.ProviderIcon
import com.checky.app.ui.components.localizedProviderDescription
import com.checky.app.ui.components.localizedProviderName
import com.checky.app.ui.preview.PreviewProviderMetas
import com.checky.app.ui.theme.CheckyTheme
import androidx.compose.ui.res.stringResource
import com.checky.app.R

@Composable
fun AddServiceScreen(
    navController: NavHostController,
    viewModel: AddServiceViewModel = hiltViewModel()
) {
    val services by viewModel.services.collectAsStateWithLifecycle()
    val enabledById = services.associate { it.serviceId to it.isEnabled }

    AddServiceContent(
        navController = navController,
        catalog = viewModel.catalog,
        enabledById = enabledById,
        onToggle = viewModel::setEnabled,
        onConnect = { id -> navController.navigate("connect/$id") }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServiceContent(
    navController: NavHostController,
    catalog: List<ProviderMeta>,
    enabledById: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onConnect: (String) -> Unit
) {
    val supported = catalog.filter { it.supportStatus == SupportStatus.SUPPORTED }
    val notSupported = catalog.filter { it.supportStatus != SupportStatus.SUPPORTED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_services_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            item {
                Text(
                    text = stringResource(R.string.add_connectable_services),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.add_services_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(supported, key = { it.id }) { meta ->
                // A catalog default is only a presentation hint. It must not
                // select an account before the user explicitly enables it.
                val enabled = enabledById[meta.id] ?: false
                ProviderRow(
                    meta = meta,
                    enabled = enabled,
                    locked = false,
                    onToggle = { onToggle(meta.id, it) },
                    onConnect = if (meta.credentialType != CredentialType.NONE) {
                        { onConnect(meta.id) }
                    } else null
                )
            }
            if (notSupported.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_not_supported),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.add_not_supported_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(notSupported, key = { it.id }) { meta ->
                    ProviderRow(meta = meta, enabled = false, locked = true, onToggle = {}, onConnect = null)
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    meta: ProviderMeta,
    enabled: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
    onConnect: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(meta = meta)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localizedProviderName(meta),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = localizedProviderDescription(meta),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                if (locked) {
                    NotSupportedChip()
                } else {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }
            if (!locked) {
                Spacer(modifier = Modifier.height(8.dp))
                MetaChip(riskLabel(meta.riskLevel), riskColor(meta.riskLevel))
            }
            if (onConnect != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_connect_reconnect))
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun NotSupportedChip() {
    Text(
        text = stringResource(R.string.add_not_supported),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.LOW -> stringResource(R.string.risk_low)
    RiskLevel.MEDIUM -> stringResource(R.string.risk_medium)
    RiskLevel.HIGH -> stringResource(R.string.risk_high)
}

private fun riskColor(risk: RiskLevel): Color = when (risk) {
    RiskLevel.LOW -> Color(0xFF1B8A4E)
    RiskLevel.MEDIUM -> Color(0xFFB45309)
    RiskLevel.HIGH -> Color(0xFFC62828)
}

@Preview
@Composable
private fun AddServicePreview() {
    CheckyTheme {
        AddServiceContent(
            navController = rememberNavController(),
            catalog = PreviewProviderMetas,
            enabledById = emptyMap(),
            onToggle = { _, _ -> },
            onConnect = {}
        )
    }
}
