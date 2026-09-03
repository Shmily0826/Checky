package com.checky.app.ui.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.ThemeMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.ui.navigation.CheckyBottomBar
import com.checky.app.ui.theme.CheckyTheme

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    SettingsContent(
        navController = navController,
        prefs = prefs,
        onThemeMode = viewModel::setThemeMode,
        onReminderEnabled = viewModel::setReminderEnabled,
        onReminderTime = viewModel::setReminderTime,
        onAutoCheckInEnabled = viewModel::setAutoCheckInEnabled,
        onAutoCheckInTime = viewModel::setAutoCheckInTime,
        onCheckInResultNotify = viewModel::setCheckInResultNotify,
        onRunMode = viewModel::setRunMode,
        onClearHistory = viewModel::clearHistory,
        onDeleteCredentials = viewModel::deleteAllCredentials,
        onShowOnboarding = viewModel::showOnboardingAgain
    )
}

private val THEME_OPTIONS = listOf(
    ThemeMode.SYSTEM to "System",
    ThemeMode.LIGHT to "Light",
    ThemeMode.DARK to "Dark"
)

private val RUN_MODE_OPTIONS = listOf(
    RunMode.PARALLEL to "Parallel",
    RunMode.SEQUENTIAL to "Sequential"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    navController: NavHostController,
    prefs: UserPreferences,
    onThemeMode: (ThemeMode) -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderTime: (Int, Int) -> Unit,
    onAutoCheckInEnabled: (Boolean) -> Unit,
    onAutoCheckInTime: (Int, Int) -> Unit,
    onCheckInResultNotify: (Boolean) -> Unit,
    onRunMode: (RunMode) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteCredentials: () -> Unit,
    onShowOnboarding: () -> Unit
) {
    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(false) }
    var showAutoTimePicker by remember { mutableStateOf(false) }
    var showClearHistoryConfirmation by remember { mutableStateOf(false) }
    var showDeleteCredentialsConfirmation by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onReminderEnabled(true) }

    fun toggleReminder(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onReminderEnabled(enabled)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) },
        bottomBar = { CheckyBottomBar(navController) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Appearance")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        THEME_OPTIONS.forEach { (mode, label) ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { onThemeMode(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            SectionTitle("Daily reminder")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remind me to check in", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("A daily reminder notification. Nothing runs automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = prefs.reminderEnabled, onCheckedChange = ::toggleReminder)
                    }
                    if (prefs.reminderEnabled) {
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Time", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showTimePicker = true }) {
                                Text(
                                    "%02d:%02d".format(prefs.reminderHour, prefs.reminderMinute),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle("Check-in behavior")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic check-in", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Experimental: runs enabled providers in the background. Android and device manufacturers may delay or block background work. On Xiaomi/HyperOS, you may need to allow Autostart and set battery use to No restrictions. The MiYouShe provider is unofficial, high risk, and for your own account only.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = prefs.autoCheckInEnabled, onCheckedChange = onAutoCheckInEnabled)
                    }
                    if (prefs.autoCheckInEnabled) {
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Run around", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showAutoTimePicker = true }) {
                                Text("%02d:%02d".format(prefs.autoCheckInHour, prefs.autoCheckInMinute), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("签到结果通知", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("自动签到完成后在通知栏显示成功 / 失败汇总。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = prefs.checkInResultNotify, onCheckedChange = onCheckInResultNotify)
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column {
                            Text("Run mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Parallel runs up to 3 services at once; sequential runs one at a time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RUN_MODE_OPTIONS.forEach { (mode, label) ->
                            FilterChip(
                                selected = prefs.runMode == mode,
                                onClick = { onRunMode(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            SectionTitle("Data")
            OutlinedButton(onClick = { showClearHistoryConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear check-in history")
            }
            OutlinedButton(
                onClick = { showDeleteCredentialsConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete all credentials")
            }
            OutlinedButton(onClick = onShowOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Show onboarding again")
            }

            SectionTitle("Privacy & security")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Text("How Checky protects you", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.padding(6.dp))
                    PrivacyLine("Credentials are stored on this device only — never uploaded.")
                    PrivacyLine("Sensitive values are kept out of logs and backups.")
                    PrivacyLine("Checky never uses AccessibilityService or controls other apps.")
                    PrivacyLine("No analytics, no crash reporting, no ads.")
                    PrivacyLine("Connect only your own accounts; the app may stop working when platforms change.")
                }
            }

            SectionTitle("About")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column {
                            Text("Checky", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Your daily check-in buddy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text("Version 1.0.0 — local-first, own-account daily check-ins.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            hour = prefs.reminderHour,
            minute = prefs.reminderMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                onReminderTime(h, m)
                showTimePicker = false
            }
        )
    }
    if (showAutoTimePicker) {
        TimePickerDialog(
            hour = prefs.autoCheckInHour,
            minute = prefs.autoCheckInMinute,
            onDismiss = { showAutoTimePicker = false },
            onConfirm = { h, m ->
                onAutoCheckInTime(h, m)
                showAutoTimePicker = false
            }
        )
    }

    if (showClearHistoryConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirmation = false },
            title = { Text("Clear check-in history?") },
            text = { Text("This permanently removes the saved check-in results from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirmation = false
                    onClearHistory()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteCredentialsConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteCredentialsConfirmation = false },
            title = { Text("Delete all credentials?") },
            text = { Text("This removes every saved provider connection from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCredentialsConfirmation = false
                    onDeleteCredentials()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCredentialsConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Preview
@Composable
private fun SettingsPreview() {
    CheckyTheme {
        SettingsContent(
            navController = rememberNavController(),
            prefs = UserPreferences(),
            onThemeMode = {},
            onReminderEnabled = {},
            onReminderTime = { _, _ -> },
            onAutoCheckInEnabled = {},
            onAutoCheckInTime = { _, _ -> },
            onCheckInResultNotify = {},
            onRunMode = {},
            onClearHistory = {},
            onDeleteCredentials = {},
            onShowOnboarding = {}
        )
    }
}
