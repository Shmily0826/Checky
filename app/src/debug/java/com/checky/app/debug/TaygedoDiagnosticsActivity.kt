package com.checky.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.ui.theme.CheckyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
internal class TaygedoDiagnosticsActivity : ComponentActivity() {
    @Inject
    lateinit var readClient: TaygedoDebugReadClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CheckyTheme {
                TaygedoDiagnosticsScreen(
                    readClient = readClient,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TaygedoDiagnosticsScreen(
    readClient: TaygedoDebugReadClient,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DiagnosticsUiState>(DiagnosticsUiState.Idle) }
    var localState by remember { mutableStateOf<LocalAuthUiState>(LocalAuthUiState.Loading) }
    var oneShotState by remember { mutableStateOf<OneShotUiState>(OneShotUiState.Idle) }

    LaunchedEffect(readClient) {
        localState = try {
            LocalAuthUiState.Loaded(readClient.loadTaygedoLocalState())
        } catch (_: Exception) {
            LocalAuthUiState.Error
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = { Text("Temporary Taygedo diagnostics") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Debug-only read-only view. It never signs in, completes tasks, " +
                    "likes, shares, redeems, or refreshes a session.",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Local Taygedo auth state", fontWeight = FontWeight.Bold)
                    when (val current = localState) {
                        LocalAuthUiState.Loading -> Text("Reading local state…")
                        LocalAuthUiState.Error -> Text(
                            "Local state unavailable",
                            color = MaterialTheme.colorScheme.error
                        )
                        is LocalAuthUiState.Loaded -> Text(current.value.render())
                    }
                }
            }
            val localReady = (localState as? LocalAuthUiState.Loaded)?.value?.let {
                it.entryExists && it.decryptable && it.authHealth == AuthHealth.VALID
            } == true
            Button(
                enabled = localReady && oneShotState is OneShotUiState.Idle,
                onClick = {
                    oneShotState = OneShotUiState.Loading
                    scope.launch {
                        oneShotState = try {
                            OneShotUiState.Loaded(readClient.loadOneShotCoinState())
                        } catch (_: TaygedoClient.AuthException) {
                            OneShotUiState.AuthExpired
                        } catch (_: Exception) {
                            OneShotUiState.Unknown
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run one-shot read-only coin GET")
            }
            when (val current = oneShotState) {
                OneShotUiState.Idle -> Unit
                OneShotUiState.Loading -> Text("Reading one safe endpoint…")
                OneShotUiState.AuthExpired -> Text(
                    "Taygedo authentication expired; no retry or refresh was attempted.",
                    color = MaterialTheme.colorScheme.error
                )
                OneShotUiState.Unknown -> Text("Coin state unknown or unavailable.")
                is OneShotUiState.Loaded -> Text(
                    when (val coin = current.value) {
                        is CoinStateDiagnostic.Known ->
                            "One-shot GET accepted: todayCoin=${coin.todayCoin}, limitCoin=${coin.limitCoin}"
                        CoinStateDiagnostic.Unknown -> "One-shot GET returned an unknown state."
                    }
                )
            }
            Button(
                enabled = state !is DiagnosticsUiState.Loading,
                onClick = {
                    state = DiagnosticsUiState.Loading
                    scope.launch {
                        state = try {
                            DiagnosticsUiState.Loaded(readClient.load())
                        } catch (failure: DebugReadFailure) {
                            DiagnosticsUiState.Error(
                                listOfNotNull(
                                    failure.message,
                                    failure.safeReport.takeIf { it.isNotBlank() }
                                ).joinToString("\n\n")
                            )
                        } catch (_: Exception) {
                            DiagnosticsUiState.Error("Read-only diagnostics failed; no mutation was attempted.")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load read-only Taygedo diagnostics")
            }

            when (val current = state) {
                DiagnosticsUiState.Idle -> Text("Nothing loaded.")
                DiagnosticsUiState.Loading -> {
                    CircularProgressIndicator()
                    Text("Reading safe task and reward fields…")
                }
                is DiagnosticsUiState.Error -> Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error
                )
                is DiagnosticsUiState.Loaded -> DiagnosticsContent(current.value)
            }
        }
    }
}

@Composable
private fun DiagnosticsContent(value: TaygedoDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Credential continuity (local only)", fontWeight = FontWeight.Bold)
            value.credentialContinuity.forEach { diagnostic ->
                Text(diagnostic.render())
            }
            Text("Safe report file: ${value.diagnosticsFile}", style = MaterialTheme.typography.bodySmall)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Read-only response shapes", fontWeight = FontWeight.Bold)
            value.observations.forEach { observation ->
                Text(observation.render(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Community task state", fontWeight = FontWeight.Bold)
            Text("browseRemaining = ${value.browseRemaining}")
            value.communityTasks.forEach { task ->
                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("${task.code} · ${task.name}", modifier = Modifier.weight(1f))
                        Text("${task.complete}/${task.limit}")
                    }
                    Text(
                        if (task.rewardFields.isEmpty()) {
                            "reward fields: none exposed"
                        } else {
                            "reward fields: " + task.rewardFields.joinToString { "${it.name}=${it.value}" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Coin task state (production read-only GET)", fontWeight = FontWeight.Bold)
            when (val coin = value.coinState) {
                is CoinStateDiagnostic.Known -> Text(
                    "Known(todayCoin=${coin.todayCoin}, limitCoin=${coin.limitCoin})"
                )
                CoinStateDiagnostic.Unknown -> Text("Unknown (not accepted or unavailable)")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("NTE sign state", fontWeight = FontWeight.Bold)
            Text("todaySign = ${value.nte.todaySigned}")
            Text("days = ${value.nte.days}")
            Text("day = ${value.nte.day}")
            Text("Reward fields: name, num")
            value.nte.rewardItems.forEachIndexed { index, reward ->
                Text("${index + 1}: ${reward.name} ×${reward.count}")
            }
        }
    }
}

private sealed interface DiagnosticsUiState {
    data object Idle : DiagnosticsUiState
    data object Loading : DiagnosticsUiState
    data class Loaded(val value: TaygedoDiagnostics) : DiagnosticsUiState
    data class Error(val message: String) : DiagnosticsUiState
}

private sealed interface LocalAuthUiState {
    data object Loading : LocalAuthUiState
    data object Error : LocalAuthUiState
    data class Loaded(val value: CredentialContinuityDiagnostic) : LocalAuthUiState
}

private sealed interface OneShotUiState {
    data object Idle : OneShotUiState
    data object Loading : OneShotUiState
    data object AuthExpired : OneShotUiState
    data object Unknown : OneShotUiState
    data class Loaded(val value: CoinStateDiagnostic) : OneShotUiState
}
