package com.checky.app.ui.screens.taptaplab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.checky.app.domain.providers.TapTapProvider
import com.checky.app.ui.screens.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapTapLabScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val result by viewModel.tapTapTestResult.collectAsStateWithLifecycle()
    val running by viewModel.tapTapTestRunning.collectAsStateWithLifecycle()
    val trace by viewModel.tapTapLabTrace.collectAsStateWithLifecycle()
    var url by remember(prefs.tapTapTestEventUrl) {
        mutableStateOf(prefs.tapTapTestEventUrl.orEmpty())
    }
    var urlError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Checky TapTap Lab") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Debug-only TapTap research surface. The URL is separate from the normal Checky activity URL and stays in the local sandbox for this app.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Temporary TapTap game-sign URL") },
                isError = urlError,
                supportingText = {
                    if (urlError) Text("Use an HTTPS www.taptap.cn/events/game-sign/<id> URL without extra query or fragment.")
                }
            )
            Button(
                onClick = {
                    val normalized = url.trim()
                    if (TapTapProvider.isValidEventUrl(normalized)) {
                        viewModel.setTapTapTestEventUrl(normalized)
                        url = normalized
                        urlError = false
                    } else {
                        urlError = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save temporary URL")
            }
            Button(
                onClick = viewModel::runTapTapTest,
                enabled = !running && TapTapProvider.isValidEventUrl(url.trim()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Running TapTap test sign-in…" else "Run TapTap test sign-in")
            }
            result?.let {
                Text(
                    text = "${it.message} (${it.diagnosticCode})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.status.isPositive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            trace.runId?.let { runId ->
                Text("Trace run: $runId")
                Text("Trace stage: ${trace.lastStage}")
                Text("Gesture dispatched: ${trace.gestureDispatched}")
                trace.finalResult?.let { finalResult ->
                    Text("Trace result: $finalResult (${trace.diagnosticCode.orEmpty()})")
                }
                Text("Trace duration: ${trace.durationMs?.let { "$it ms" } ?: "pending"}")
                val autoReturn = when {
                    !trace.autoReturnAttempted -> "not attempted"
                    trace.autoReturnSucceeded == true -> "foreground acknowledged"
                    trace.autoReturnLaunchAccepted == false -> "launch rejected"
                    trace.autoReturnLaunchAccepted == true -> "waiting for foreground"
                    else -> "pending"
                }
                Text("Auto-return: $autoReturn")
            }
        }
    }
}
