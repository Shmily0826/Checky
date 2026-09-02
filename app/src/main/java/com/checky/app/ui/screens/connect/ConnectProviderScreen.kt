package com.checky.app.ui.screens.connect

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.navigation.NavHostController
import com.checky.app.domain.model.CredentialType
import com.checky.app.ui.components.ProviderIcon
import com.checky.app.ui.components.qrBitmap
import com.checky.app.ui.components.saveQrToGallery

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectProviderScreen(
    navController: NavHostController,
    viewModel: ConnectProviderViewModel = hiltViewModel()
) {
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
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meta?.displayName ?: "Connect") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (meta != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderIcon(meta = meta, size = 56.dp)
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(meta.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(meta.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(meta.description, style = MaterialTheme.typography.bodyMedium)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What this connection means", style = MaterialTheme.typography.titleSmall)
                    InfoLine(icon = Icons.Filled.Lock, text = "Required data: ${credentialLabel(meta?.credentialType)}")
                    InfoLine(icon = Icons.Filled.Lock, text = "Stored only on this device, encrypted. Never uploaded.")
                    InfoLine(icon = Icons.Filled.Lock, text = "Connect only your own account.")
                    InfoLine(icon = Icons.Filled.Lock, text = "The service may stop working after platform changes.")
                }
            }

            when {
                meta?.credentialType == CredentialType.NONE -> {
                    Text(
                        "This service does not need a credential to check in.",
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
                        Text("Connected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (saved) {
                        Text("Your token was saved. Check in again to use it.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (viewModel.gameAccountProvider != null) {
                        val gameRoles by viewModel.gameRoles.collectAsStateWithLifecycle()
                        val gameRolesBusy by viewModel.gameRolesBusy.collectAsStateWithLifecycle()
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("原神账号", style = MaterialTheme.typography.titleSmall)
                                when {
                                    gameRolesBusy -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(8.dp))
                                            Text("正在获取绑定的原神角色…", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    gameRoles.size > 1 -> {
                                        Text(
                                            "检测到多个角色，请选择要签到的角色：",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        gameRoles.forEachIndexed { index, role ->
                                            val picked = gameUid == role.config.uid
                                            FilterChip(
                                                selected = picked,
                                                onClick = { viewModel.pickGameRole(index) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("${role.label}（UID ${role.config.uid}）") },
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
                                            "还需要选择要签到的原神角色。填写游戏内的 9–10 位 UID，不是米游社账号 ID。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedButton(
                                            onClick = viewModel::fetchGameRoles,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("重新自动获取角色") }
                                    }
                                }
                                if (!gameRolesBusy && gameRoles.size <= 1) {
                                OutlinedTextField(
                                    value = gameUid,
                                    onValueChange = viewModel::updateGameUid,
                                    label = { Text("原神游戏 UID") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "区服（默认官服）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = gameRegion == "cn_gf01",
                                        onClick = { viewModel.updateGameRegion("cn_gf01") },
                                        enabled = !savingGameAccount,
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                    ) { Text("官服") }
                                    SegmentedButton(
                                        selected = gameRegion == "cn_qd01",
                                        onClick = { viewModel.updateGameRegion("cn_qd01") },
                                        enabled = !savingGameAccount,
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                    ) { Text("B服") }
                                }
                                Button(
                                    onClick = viewModel::saveGameAccount,
                                    enabled = !savingGameAccount && gameUid.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (savingGameAccount) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(if (gameAccountSaved) "已保存原神账号" else "保存原神账号")
                                    }
                                }
                                }
                            }
                        }
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
                        Text("Delete credentials")
                    }
                }
                else -> {
                    // QR providers generate their session immediately on
                    // entry — the default path is scan-only, zero typing.
                    LaunchedEffect(Unit) {
                        viewModel.maybeStartQrLoginAutomatically()
                    }
                    if (viewModel.smsProvider != null) {
                        Text("手机号验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "验证码由完美世界登录服务发送。手机号和验证码只用于本机完成登录，验证码不会保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = viewModel::updatePhone,
                            label = { Text("中国大陆手机号") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (smsSent) {
                            OutlinedTextField(
                                value = smsCode,
                                onValueChange = viewModel::updateSmsCode,
                                label = { Text("短信验证码") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = if (smsSent) viewModel::confirmSmsCode else viewModel::sendSmsCode,
                            enabled = !smsBusy && phone.length == 11 && (!smsSent || smsCode.length >= 4),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (smsBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (smsSent) "验证并连接" else "发送验证码")
                        }
                        if (smsSent) {
                            OutlinedButton(onClick = viewModel::sendSmsCode, enabled = !smsBusy, modifier = Modifier.fillMaxWidth()) {
                                Text("重新发送验证码")
                            }
                        }
                        return@Column
                    }
                    if (viewModel.qrProvider != null) {
                        Text(
                            "扫码绑定（推荐）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isMiyousheCommunity) {
                                "这是米游社客户端授权二维码，由本机生成。请用真实手机上的米游社扫码并确认；不是原神游戏登录二维码，约 3 分钟有效。"
                            } else {
                                "这是米游社网页登录二维码，由本机生成。请用真实手机上的米游社扫码并确认；二维码约 3 分钟有效。"
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
                                Text(if (isMiyousheCommunity) "生成社区授权二维码" else "生成米游社网页登录二维码")
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
                                    contentDescription = if (isMiyousheCommunity) "米游社社区授权二维码" else "米游社登录二维码",
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
                                                    if (saved) "二维码已保存到相册，约 3 分钟内有效，用后请删除。"
                                                    else "保存失败，请检查存储空间后重试。",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        )
                                )
                                Text(
                                    "长按二维码可保存到相册；再用官方 App「扫一扫 → 相册」识别，一台手机也能完成。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                            }
                            Text(
                                qrStatus ?: "等待扫码…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            OutlinedButton(
                                onClick = viewModel::cancelQrLogin,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("取消扫码") }
                        }
                        if (qrStatus?.startsWith("绑定成功") == true) {
                            Text(qrStatus!!, color = MaterialTheme.colorScheme.primary)
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
                            Text(if (isMiyousheCommunity) "手动填写社区 Cookie（高级）" else "手动填写 Cookie（高级）")
                        }
                    } else {
                    if (viewModel.qrProvider != null) {
                        OutlinedButton(
                            onClick = { showManualSecret = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("返回扫码绑定")
                        }
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = viewModel::updateSecret,
                        label = { Text("米游社 Cookie（仅限本人账号）") },
                        supportingText = {
                            Text("高风险实验接口：只保存到本机，不要提交他人 Cookie。")
                        },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleSecretVisibility) {
                                Icon(
                                    if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showSecret) "Hide token" else "Show token"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !saving && secret.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save and connect")
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
            title = { Text("Delete credentials?") },
            text = { Text("This removes the saved connection from this device. You can connect again later.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    viewModel.deleteCredentials()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
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

private fun credentialLabel(type: CredentialType?): String = when (type) {
    CredentialType.NONE -> "None"
    CredentialType.OAUTH -> "Official OAuth sign-in"
    CredentialType.SESSION_TOKEN -> "Session token"
    null -> "None"
}
