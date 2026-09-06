package com.checky.app.ui.screens.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import com.checky.app.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.ThemeMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.domain.background.BackgroundReliabilityReport
import com.checky.app.domain.background.BackgroundReliabilityStatus
import com.checky.app.ui.navigation.CheckyBottomBar
import com.checky.app.ui.theme.CheckyTheme

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val backgroundReliability by viewModel.backgroundReliability.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { viewModel.refreshBackgroundReliability() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBackgroundReliability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsContent(
        navController = navController,
        prefs = prefs,
        backgroundReliability = backgroundReliability,
        onOpenAppSettings = { openAppSettings(context) },
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
    ThemeMode.SYSTEM,
    ThemeMode.LIGHT,
    ThemeMode.DARK
)

private val RUN_MODE_OPTIONS = listOf(
    RunMode.PARALLEL,
    RunMode.SEQUENTIAL
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    navController: NavHostController,
    prefs: UserPreferences,
    backgroundReliability: BackgroundReliabilityReport?,
    onOpenAppSettings: () -> Unit,
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
    val configuration = LocalConfiguration.current
    val appLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val languageTag = when {
        appLocaleTags.startsWith("zh") -> "zh-CN"
        appLocaleTags.startsWith("en") -> "en"
        configuration.locales[0].language == "zh" -> "zh-CN"
        else -> "en"
    }

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) }) },
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
            SectionTitle(stringResource(R.string.settings_appearance))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        THEME_OPTIONS.forEach { mode ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { onThemeMode(mode) },
                                label = { Text(themeLabel(mode)) }
                            )
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = languageTag == "zh-CN",
                            onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) },
                            label = { Text(stringResource(R.string.language_chinese)) }
                        )
                        FilterChip(
                            selected = languageTag == "en",
                            onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) },
                            label = { Text(stringResource(R.string.language_english)) }
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_daily_reminder))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_remind_me), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_reminder_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = prefs.reminderEnabled, onCheckedChange = ::toggleReminder)
                    }
                    if (prefs.reminderEnabled) {
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_time), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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

            SectionTitle(stringResource(R.string.settings_checkin_behavior))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_automatic_checkin), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_automatic_description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = prefs.autoCheckInEnabled, onCheckedChange = onAutoCheckInEnabled)
                    }
                    if (prefs.autoCheckInEnabled) {
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_run_around), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showAutoTimePicker = true }) {
                                Text("%02d:%02d".format(prefs.autoCheckInHour, prefs.autoCheckInMinute), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.padding(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_result_notifications), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.settings_result_notifications_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = prefs.checkInResultNotify, onCheckedChange = onCheckInResultNotify)
                        }
                    }
                }
            }

            if (backgroundReliability?.shouldShowInSettings(prefs.autoCheckInEnabled) == true) {
                SectionTitle(stringResource(R.string.settings_background_reliability))
                BackgroundReliabilityCard(
                    report = backgroundReliability,
                    onOpenAppSettings = onOpenAppSettings
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column {
                            Text(stringResource(R.string.settings_run_mode), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_run_mode_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RUN_MODE_OPTIONS.forEach { mode ->
                            FilterChip(
                                selected = prefs.runMode == mode,
                                onClick = { onRunMode(mode) },
                                label = { Text(runModeLabel(mode)) }
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_data))
            OutlinedButton(onClick = { showClearHistoryConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_clear_history))
            }
            OutlinedButton(
                onClick = { showDeleteCredentialsConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.settings_delete_credentials))
            }
            OutlinedButton(onClick = onShowOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_show_onboarding))
            }

            if (isDebuggableBuild(context)) {
                SectionTitle("Temporary debug")
                OutlinedButton(
                    onClick = { openTemporaryTaygedoDiagnostics(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open temporary Taygedo diagnostics")
                }
            }

            SectionTitle(stringResource(R.string.settings_privacy_security))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Text(stringResource(R.string.settings_protection_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.padding(6.dp))
                    PrivacyLine(stringResource(R.string.privacy_credentials))
                    PrivacyLine(stringResource(R.string.privacy_logs))
                    PrivacyLine(stringResource(R.string.privacy_accessibility))
                    PrivacyLine(stringResource(R.string.privacy_no_tracking))
                    PrivacyLine(stringResource(R.string.privacy_own_accounts))
                }
            }

            SectionTitle(stringResource(R.string.settings_about))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(8.dp))
                        Column {
                            Text("Checky", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
            title = { Text(stringResource(R.string.dialog_clear_history_title)) },
            text = { Text(stringResource(R.string.dialog_clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirmation = false
                    onClearHistory()
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showDeleteCredentialsConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteCredentialsConfirmation = false },
            title = { Text(stringResource(R.string.dialog_delete_credentials_title)) },
            text = { Text(stringResource(R.string.dialog_delete_credentials_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCredentialsConfirmation = false
                    onDeleteCredentials()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCredentialsConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun BackgroundReliabilityCard(
    report: BackgroundReliabilityReport,
    onOpenAppSettings: () -> Unit
) {
    val isXiaomiFamily = report.signals.isXiaomiFamily
    val isCritical = report.status == BackgroundReliabilityStatus.CRITICAL_RESTRICTED
    val containerColor = when {
        isCritical -> MaterialTheme.colorScheme.errorContainer
        report.status == BackgroundReliabilityStatus.MAY_BE_DEFERRED ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (report.status) {
                    BackgroundReliabilityStatus.CRITICAL_RESTRICTED ->
                        stringResource(R.string.background_restricted_title)
                    BackgroundReliabilityStatus.MAY_BE_DEFERRED ->
                        stringResource(R.string.background_deferred_title)
                    BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW ->
                        stringResource(R.string.background_android_level_not_detected_title)
                    BackgroundReliabilityStatus.HEALTHY ->
                        stringResource(R.string.background_not_detected_title)
                    BackgroundReliabilityStatus.UNKNOWN ->
                        stringResource(R.string.background_unknown_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (report.status) {
                    BackgroundReliabilityStatus.CRITICAL_RESTRICTED ->
                        if (isXiaomiFamily) {
                            stringResource(R.string.background_restricted_xiaomi)
                        } else {
                            stringResource(R.string.background_restricted_other)
                        }
                    BackgroundReliabilityStatus.MAY_BE_DEFERRED ->
                        if (isXiaomiFamily) {
                            stringResource(R.string.background_deferred_xiaomi)
                        } else {
                            stringResource(R.string.background_deferred_other)
                        }
                    BackgroundReliabilityStatus.XIAOMI_MANUAL_REVIEW ->
                        if (isXiaomiFamily) {
                            stringResource(R.string.background_manual_xiaomi)
                        } else {
                            stringResource(R.string.background_manual_other)
                        }
                    BackgroundReliabilityStatus.HEALTHY ->
                        stringResource(R.string.background_healthy)
                    BackgroundReliabilityStatus.UNKNOWN ->
                        if (isXiaomiFamily) {
                            stringResource(R.string.background_unknown_xiaomi)
                        } else {
                            stringResource(R.string.background_unknown_other)
                        }
                },
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.background_open_app_settings))
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(appSettings)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // A documented system settings activity is expected on Android; no
            // opaque OEM component is used if the device does not expose it.
        }
    }
}

private const val TEMPORARY_TAYGEDO_DIAGNOSTICS_CLASS =
    "com.checky.app.debug.TaygedoDiagnosticsActivity"

private fun openTemporaryTaygedoDiagnostics(context: Context) {
    if (!isDebuggableBuild(context)) return
    context.startActivity(
        Intent().setClassName(context.packageName, TEMPORARY_TAYGEDO_DIAGNOSTICS_CLASS)
    )
}

private fun isDebuggableBuild(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
        title = { Text(stringResource(R.string.dialog_reminder_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun runModeLabel(mode: RunMode): String = when (mode) {
    RunMode.PARALLEL -> stringResource(R.string.run_mode_parallel)
    RunMode.SEQUENTIAL -> stringResource(R.string.run_mode_sequential)
}

@Preview
@Composable
private fun SettingsPreview() {
    CheckyTheme {
        SettingsContent(
            navController = rememberNavController(),
            prefs = UserPreferences(),
            backgroundReliability = null,
            onOpenAppSettings = {},
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
