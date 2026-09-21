package com.checky.app.ui.screens.connect

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.AuthHealth
import com.checky.app.ui.components.ProviderIcon
import com.checky.app.ui.components.localizedProviderCategory
import com.checky.app.ui.components.localizedProviderDescription
import com.checky.app.ui.components.localizedProviderName
import com.checky.app.ui.components.qrBitmap
import com.checky.app.ui.components.saveQrToGallery

@Composable
private fun connectErrorLabel(error: ConnectError): String = when (error) {
    is ConnectError.Provider -> error.message
    is ConnectError.App -> when (error.kind) {
        ConnectAppError.TOKEN_REQUIRED -> stringResource(R.string.connect_error_token_required)
        ConnectAppError.QR_UNSUPPORTED -> stringResource(R.string.connect_error_qr_unsupported)
        ConnectAppError.QR_EXPIRED -> stringResource(R.string.connect_error_qr_expired)
        ConnectAppError.QR_TIMEOUT -> stringResource(R.string.connect_error_qr_timeout)
        ConnectAppError.GAME_ROLES_UNAVAILABLE -> stringResource(R.string.connect_error_game_roles_unavailable)
        ConnectAppError.VERIFICATION_FAILED -> stringResource(R.string.connect_error_verification_failed)
    }
}

@Composable
private fun qrStatusLabel(status: QrUiStatus?): String = when (status) {
    null -> stringResource(R.string.connect_qr_waiting)
    QrUiStatus.Generating -> stringResource(R.string.connect_qr_generating)
    QrUiStatus.Waiting -> stringResource(R.string.connect_qr_waiting)
    QrUiStatus.Scanned -> stringResource(R.string.connect_qr_scanned)
    is QrUiStatus.Confirmed -> status.accountLabel?.let {
        stringResource(R.string.connect_qr_confirmed_uid, it)
    } ?: stringResource(R.string.connect_qr_confirmed)
}

internal fun zzzRegionDisplayValue(region: String, isEditing: Boolean, localizedOfficialServer: String): String =
    if (!isEditing && region == "prod_gf_cn") localizedOfficialServer else region

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectProviderScreen(
    navController: NavHostController,
    viewModel: ConnectProviderViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshConnection()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Avoid screenshots of the credential entry screen.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val meta = viewModel.meta
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val authHealth by viewModel.authHealth.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val savedCredentialCheckState by viewModel.savedCredentialCheckState.collectAsStateWithLifecycle()
    val secret by viewModel.secret.collectAsStateWithLifecycle()
    val showSecret by viewModel.showSecret.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val qrSession by viewModel.qrSession.collectAsStateWithLifecycle()
    val qrStatus by viewModel.qrStatus.collectAsStateWithLifecycle()
    val qrBusy by viewModel.qrBusy.collectAsStateWithLifecycle()
    val gameUid by viewModel.gameUid.collectAsStateWithLifecycle()
    val gameRegion by viewModel.gameRegion.collectAsStateWithLifecycle()
    val savingGameAccount by viewModel.savingGameAccount.collectAsStateWithLifecycle()
    val gameAccountSaved by viewModel.gameAccountSaved.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val smsCode by viewModel.smsCode.collectAsStateWithLifecycle()
    val smsSent by viewModel.smsSent.collectAsStateWithLifecycle()
    val smsBusy by viewModel.smsBusy.collectAsStateWithLifecycle()
    val isMiyousheCommunity = meta?.id == "miyoushe_community_signin"
    val isMiyousheZzz = meta?.id == "miyoushe_zzz_experimental"
    var zzzRegionEditing by remember { mutableStateOf(false) }
    val displayedGameRegion = if (isMiyousheZzz) {
        zzzRegionDisplayValue(
            gameRegion,
            zzzRegionEditing,
            stringResource(R.string.connect_official_server)
        )
    } else {
        gameRegion
    }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showLeaveSmsConfirmation by remember { mutableStateOf(false) }

    val leaveScreen = { navController.popBackStack() }
    BackHandler(enabled = smsSent && !connected) {
        showLeaveSmsConfirmation = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (meta != null) localizedProviderName(meta) else stringResource(R.string.connect_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (smsSent && !connected) showLeaveSmsConfirmation = true else leaveScreen()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (meta != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderIcon(meta = meta, size = 56.dp)
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(localizedProviderName(meta), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(localizedProviderCategory(meta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(localizedProviderDescription(meta), style = MaterialTheme.typography.bodyMedium)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connect_meaning), style = MaterialTheme.typography.titleSmall)
                    InfoLine(icon = Icons.Filled.Lock, text = stringResource(R.string.connect_required_data, credentialLabel(meta?.credentialType)))
                    InfoLine(icon = Icons.Filled.Lock, text = stringResource(R.string.connect_stored_local))
                    InfoLine(icon = Icons.Filled.Lock, text = stringResource(R.string.connect_own_account))
                    InfoLine(icon = Icons.Filled.Lock, text = stringResource(R.string.connect_platform_changes))
                }
            }

            when {
                meta?.credentialType == CredentialType.NONE -> {
                    Text(
                        stringResource(R.string.connect_no_credential),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                connected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.connect_connected), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (saved) {
                        Text(stringResource(R.string.connect_token_saved), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (viewModel.gameAccountProvider != null) {
                        val gameRoles by viewModel.gameRoles.collectAsStateWithLifecycle()
                        val gameRolesBusy by viewModel.gameRolesBusy.collectAsStateWithLifecycle()
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    stringResource(if (isMiyousheZzz) R.string.connect_zzz_account else R.string.connect_genshin_account),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                when {
                                    gameRolesBusy -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(8.dp))
                                            Text(
                                                stringResource(
                                                    if (isMiyousheZzz) R.string.connect_fetching_zzz_roles
                                                    else R.string.connect_fetching_roles
                                                ),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    gameRoles.size > 1 -> {
                                        Text(
                                            stringResource(R.string.connect_multiple_roles),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        gameRoles.forEachIndexed { index, role ->
                                            val picked = gameUid == role.config.uid
                                            FilterChip(
                                                selected = picked,
                                                onClick = { viewModel.pickGameRole(index) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text(stringResource(R.string.connect_role_uid, role.label, role.config.uid)) },
                                                leadingIcon = {
                                                    if (picked) {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                        )
                                                    }
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            )
                                        }
                                    }
                                    !gameAccountSaved -> {
                                        Text(
                                            stringResource(
                                                if (isMiyousheZzz) R.string.connect_choose_zzz_role
                                                else R.string.connect_choose_role
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedButton(
                                            onClick = viewModel::fetchGameRoles,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(stringResource(R.string.connect_fetch_roles_again)) }
                                    }
                                }
                                if (!gameRolesBusy && gameRoles.size <= 1) {
                                OutlinedTextField(
                                    value = gameUid,
                                    onValueChange = viewModel::updateGameUid,
                                    label = { Text(stringResource(if (isMiyousheZzz) R.string.connect_zzz_uid else R.string.connect_genshin_uid)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (isMiyousheZzz) {
                                    OutlinedTextField(
                                        value = displayedGameRegion,
                                        onValueChange = viewModel::updateGameRegion,
                                        label = { Text(stringResource(R.string.connect_zzz_region)) },
                                        singleLine = true,
                                        enabled = !savingGameAccount,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { zzzRegionEditing = it.isFocused }
                                    )
                                } else {
                                Text(
                                    stringResource(R.string.connect_region_default),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = gameRegion == "cn_gf01",
                                        onClick = { viewModel.updateGameRegion("cn_gf01") },
                                        enabled = !savingGameAccount,
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                    ) { Text(stringResource(R.string.connect_official_server)) }
                                    SegmentedButton(
                                        selected = gameRegion == "cn_qd01",
                                        onClick = { viewModel.updateGameRegion("cn_qd01") },
                                        enabled = !savingGameAccount,
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                    ) { Text(stringResource(R.string.connect_b_server)) }
                                }
                                }
                                Button(
                                    onClick = viewModel::saveGameAccount,
                                    enabled = !savingGameAccount && gameUid.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (savingGameAccount) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            stringResource(
                                                when {
                                                    isMiyousheZzz && gameAccountSaved -> R.string.connect_zzz_account_saved
                                                    isMiyousheZzz -> R.string.connect_save_zzz_account
                                                    gameAccountSaved -> R.string.connect_account_saved
                                                    else -> R.string.connect_save_account
                                                }
                                            )
                                        )
                                    }
                                }
                                error?.let {
                                    Text(
                                        connectErrorLabel(it),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                }
                            }
                        }
                    }
                    if (viewModel.supportsConnectedSavedCredentialCheck) {
                        SavedCredentialCheckControls(
                            state = savedCredentialCheckState,
                            verifying = verifying,
                            onCheck = viewModel::verifySavedCredential
                        )
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.connect_delete_credentials))
                    }
                }
                else -> {
                    // QR providers generate their session immediately on
                    // entry — the default path is scan-only, zero typing.
                    LaunchedEffect(Unit) {
                        viewModel.maybeStartQrLoginAutomatically()
                    }
                    when (authHealth) {
                        AuthHealth.UNVERIFIED -> Text(
                            stringResource(R.string.connect_credential_unverified_manage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AuthHealth.EXPIRED -> Text(
                            stringResource(R.string.connect_auth_expired_preserved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Unit
                    }
                    if ((authHealth == AuthHealth.UNVERIFIED || authHealth == AuthHealth.EXPIRED) &&
                        viewModel.supportsConnectedSavedCredentialCheck) {
                        SavedCredentialCheckControls(
                            state = savedCredentialCheckState,
                            verifying = verifying,
                            onCheck = viewModel::verifySavedCredential
                        )
                    }
                    if (viewModel.smsProvider != null) {
                            Text(stringResource(R.string.connect_phone_verification), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.connect_phone_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = viewModel::updatePhone,
                            label = { Text(stringResource(R.string.connect_mainland_phone)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (smsSent) {
                            OutlinedTextField(
                                value = smsCode,
                                onValueChange = viewModel::updateSmsCode,
                                label = { Text(stringResource(R.string.connect_sms_code)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (error != null) Text(connectErrorLabel(error!!), color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = if (smsSent) viewModel::confirmSmsCode else viewModel::sendSmsCode,
                            enabled = !smsBusy && phone.length == 11 && (!smsSent || smsCode.length >= 4),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (smsBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(if (smsSent) R.string.connect_verify_connect else R.string.connect_send_code))
                        }
                        if (smsSent) {
                            OutlinedButton(onClick = viewModel::sendSmsCode, enabled = !smsBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.connect_resend_code))
                            }
                        }
                        return@Column
                    }
                    if (viewModel.qrProvider != null) {
                        Text(
                            stringResource(R.string.connect_qr_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isMiyousheCommunity) {
                                stringResource(R.string.connect_qr_community_description)
                            } else {
                                stringResource(R.string.connect_qr_web_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (qrSession == null) {
                            Button(
                                onClick = viewModel::startQrLogin,
                                enabled = !qrBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(if (isMiyousheCommunity) R.string.connect_generate_community_qr else R.string.connect_generate_web_qr))
                            }
                        } else {
                            val payload = qrSession?.qrPayload
                            val bitmap = remember(payload) {
                                payload?.let { qrBitmap(it).asImageBitmap() }
                            }
                            if (bitmap != null && payload != null) {
                                val context = LocalContext.current
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = stringResource(if (isMiyousheCommunity) R.string.connect_community_qr_cd else R.string.connect_web_qr_cd),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        // The window sets FLAG_SECURE, so a single-device user
                                        // cannot screenshot the code. Saving it lets them pick
                                        // the image from the album in the official app instead.
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = {
                                                val saved = saveQrToGallery(context, payload)
                                                Toast.makeText(
                                                    context,
                                                    if (saved) context.getString(R.string.connect_qr_saved)
                                                    else context.getString(R.string.connect_qr_save_failed),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        )
                                )
                                Text(
                                    stringResource(R.string.connect_qr_long_press),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                            }
                            Text(
                                qrStatusLabel(qrStatus),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (qrStatus is QrUiStatus.Confirmed) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            OutlinedButton(
                                onClick = viewModel::cancelQrLogin,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.connect_cancel_scan)) }
                        }
                    }
                    // Manual Cookie entry stays hidden behind an explicit
                    // opt-in so the QR path remains the zero-input default.
                    var showManualSecret by remember { mutableStateOf(viewModel.qrProvider == null) }
                    if (viewModel.qrProvider != null && !showManualSecret) {
                        OutlinedButton(
                            onClick = { showManualSecret = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(if (isMiyousheCommunity) R.string.connect_manual_cookie_community else R.string.connect_manual_cookie))
                        }
                    } else {
                    if (viewModel.qrProvider != null) {
                        OutlinedButton(
                            onClick = { showManualSecret = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.connect_back_to_qr))
                        }
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = viewModel::updateSecret,
                        label = { Text(stringResource(R.string.connect_cookie_label)) },
                        supportingText = {
                            Text(stringResource(R.string.connect_cookie_supporting))
                        },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleSecretVisibility) {
                                Icon(
                                     if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                     contentDescription = stringResource(
                                         if (showSecret) R.string.cd_hide_token else R.string.cd_show_token
                                     )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(connectErrorLabel(error!!), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !saving && secret.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.connect_save_and_connect))
                        }
                    }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.connect_delete_title)) },
            text = { Text(stringResource(R.string.connect_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    viewModel.deleteCredentials()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showLeaveSmsConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveSmsConfirmation = false },
            title = { Text(stringResource(R.string.connect_leave_sms_title)) },
            text = { Text(stringResource(R.string.connect_leave_sms_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveSmsConfirmation = false
                    leaveScreen()
                }) { Text(stringResource(R.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveSmsConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SavedCredentialCheckControls(
    state: SavedCredentialCheckState,
    verifying: Boolean,
    onCheck: () -> Unit
) {
    OutlinedButton(
        onClick = onCheck,
        enabled = !verifying,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (verifying) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.connect_saved_check_running))
        } else {
            Text(stringResource(R.string.connect_check_connection))
        }
    }
    val resultRes = when (state) {
        SavedCredentialCheckState.IDLE,
        SavedCredentialCheckState.RUNNING -> null
        SavedCredentialCheckState.VALID -> R.string.connect_saved_check_valid
        SavedCredentialCheckState.EXPIRED -> R.string.connect_saved_check_expired
        SavedCredentialCheckState.UNVERIFIED -> R.string.connect_saved_check_unverified
    }
    resultRes?.let { result ->
        Text(
            stringResource(result),
            style = MaterialTheme.typography.bodySmall,
            color = when (state) {
                SavedCredentialCheckState.VALID -> MaterialTheme.colorScheme.tertiary
                SavedCredentialCheckState.EXPIRED,
                SavedCredentialCheckState.UNVERIFIED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun credentialLabel(type: CredentialType?): String = when (type) {
    CredentialType.NONE, null -> stringResource(R.string.credentials_none)
    CredentialType.OAUTH -> stringResource(R.string.credentials_oauth)
    CredentialType.SESSION_TOKEN -> stringResource(R.string.credentials_session)
}
