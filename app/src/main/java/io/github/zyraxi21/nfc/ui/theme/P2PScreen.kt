package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import io.github.zyraxi21.nfc.R

enum class ConnectionState {
    DISCONNECTED, ADVERTISING, DISCOVERING, CONNECTING, CONNECTED
}

/** 状态说明文字：品牌强调色 + 居中。 */
@Composable
private fun StatusHint(text: String) {
    FluentText(
        text = text,
        style = FluentTextStyle.Body2,
        color = AppTheme.brand
    )
}

@Composable
fun P2PScreen(
    connectionState: ConnectionState,
    receivedNearbyMessage: String,
    isBluetoothEnabled: Boolean,
    isWiFiEnabled: Boolean,
    nearbyRadioWarning: String?,
    onMessageChange: (String) -> Unit,
    onEnableBluetooth: () -> Unit,
    onEnableWiFi: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStopAdvertising: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDisconnect: () -> Unit,
    onSendMessage: (String) -> Unit,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // 数据类型 & 输入 —— 仅在已连接后使用
    val supportedTypes = remember { WriteDataType.entries.filter { it != WriteDataType.APPLICATION } }
    var selectedType by remember { mutableStateOf(WriteDataType.TEXT) }
    var textInput by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiEncryption by remember { mutableStateOf(WifiEncryption.AES) }
    var wifiAuth by remember { mutableStateOf(WifiAuth.WPA2_PSK) }
    var btMac by remember { mutableStateOf("") }
    var btName by remember { mutableStateOf("") }

    LaunchedEffect(selectedType) {
        textInput = ""; wifiSsid = ""; wifiPassword = ""; btMac = ""; btName = ""
    }

    // 进入 P2P 页时若无权限，自动发起系统权限请求（只请求一次，避免反复弹窗）
    var permissionRequested by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermissions) {
        if (!hasPermissions && !permissionRequested) {
            permissionRequested = true
            onRequestPermissions()
        }
    }

    fun buildFormattedMessage(): String = when (selectedType) {
        WriteDataType.TEXT -> "TEXT:${textInput.ifBlank { FormDefaults.TEXT }}"
        WriteDataType.URL -> "URL:${textInput.ifBlank { FormDefaults.URL }}"
        WriteDataType.WIFI -> {
            val ssid = FormDefaults.resolveWifiSsid(wifiSsid)
            val pass = FormDefaults.resolveWifiPassword(wifiPassword)
            "WIFI:$ssid|$pass|${wifiEncryption.wscValue}|${wifiAuth.wscValue}"
        }
        WriteDataType.BLUETOOTH -> {
            val (mac, name) = FormDefaults.resolveBluetooth(btMac, btName)
            "BT:$mac|$name"
        }
        WriteDataType.APPLICATION -> error("Application records are only supported by NFC writing and emulation")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
    ) {
        if (!hasPermissions) {
            // ---- 无权限提示（整页居中） ----
            // 这里不用 PageColumn：提示卡片需要在整页垂直居中，而不是从顶部开始滚动
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(LocalPageGutter.current),
                contentAlignment = Alignment.Center
            ) {
                FluentCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FluentSpacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        FluentText(
                            text = stringResource(R.string.p2p_text_permission_required),
                            style = FluentTextStyle.Body1
                        )
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        FluentButton(
                            onClick = onRequestPermissions,
                            text = stringResource(R.string.button_grant_permission),
                            modifier = Modifier.heightIn(min = PageMetrics.minTouchTarget)
                        )
                    }
                }
            }
        } else {
            // ---- 正常 P2P 界面 ----
            PageColumn(verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                FluentText(
                    text = stringResource(R.string.p2p_title),
                    style = FluentTextStyle.Title3
                )

                // Nearby 无线装置引导：API 不再自动开启蓝牙 / Wi-Fi
                nearbyRadioWarning?.let { message ->
                    RadioWarningCard(
                        message = message,
                        isBluetoothEnabled = isBluetoothEnabled,
                        isWiFiEnabled = isWiFiEnabled,
                        onEnableBluetooth = onEnableBluetooth,
                        onEnableWiFi = onEnableWiFi
                    )
                }

                // ========================================================
                // 根据连接状态展示不同内容
                // ========================================================
                when (connectionState) {
                    ConnectionState.DISCONNECTED -> {
                        Spacer(modifier = Modifier.height(FluentSpacing.xl))
                        FluentText(
                            text = stringResource(R.string.p2p_label_select_mode),
                            style = FluentTextStyle.Body1Strong
                        )
                        // 加大标签与按钮的垂直间距
                        Spacer(modifier = Modifier.height(FluentSpacing.xl))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FluentButton(
                                onClick = onStartDiscovery,
                                text = stringResource(R.string.p2p_button_discover),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = PageMetrics.minTouchTarget)
                            )
                            FluentButton(
                                onClick = onStartAdvertising,
                                text = stringResource(R.string.p2p_button_advertise),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = PageMetrics.minTouchTarget)
                            )
                        }
                    }

                    ConnectionState.ADVERTISING -> {
                        Spacer(modifier = Modifier.height(FluentSpacing.xl))
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        StatusHint(stringResource(R.string.p2p_text_waiting_reader))
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        FluentButton(
                            onClick = onStopAdvertising,
                            text = stringResource(R.string.p2p_button_stop_advertise),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PageMetrics.minTouchTarget)
                        )
                    }

                    ConnectionState.DISCOVERING -> {
                        Spacer(modifier = Modifier.height(FluentSpacing.xl))
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        StatusHint(stringResource(R.string.p2p_text_approach_device))
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        FluentButton(
                            onClick = onStopDiscovery,
                            text = stringResource(R.string.p2p_button_stop_discovery),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PageMetrics.minTouchTarget)
                        )
                    }

                    ConnectionState.CONNECTING -> {
                        Spacer(modifier = Modifier.height(FluentSpacing.xl))
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                        StatusHint(stringResource(R.string.p2p_text_connecting))
                    }

                    ConnectionState.CONNECTED -> {
                        // 成功态用 SuccessForeground1：与"进行中"的品牌蓝区分开
                        FluentText(
                            text = stringResource(R.string.p2p_text_connected),
                            style = FluentTextStyle.Body2Strong,
                            color = AppTheme.success
                        )

                        // ---- 接收消息区域 ----
                        FluentCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(FluentSpacing.mPlus),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                FluentText(
                                    text = stringResource(R.string.p2p_label_received),
                                    style = FluentTextStyle.Body1Strong
                                )
                                Spacer(modifier = Modifier.height(FluentSpacing.xs))
                                if (receivedNearbyMessage.isEmpty()) {
                                    FluentText(
                                        text = stringResource(R.string.p2p_placeholder_waiting),
                                        style = FluentTextStyle.Body2,
                                        color = AppTheme.textHint
                                    )
                                } else {
                                    ParsedMessageDisplay(receivedNearbyMessage)
                                }
                            }
                        }

                        // ---- 类型选择（浮层宽度与按钮对齐） ----
                        var typeExpanded by remember { mutableStateOf(false) }
                        FluentDropdown(
                            text = stringResource(R.string.format_type_label, stringResource(selectedType.labelResId)),
                            expanded = typeExpanded,
                            onExpandedChange = { typeExpanded = it },
                            options = supportedTypes.map { stringResource(it.labelResId) },
                            onOptionSelected = { index ->
                                selectedType = supportedTypes[index]
                                typeExpanded = false
                            }
                        )

                        // ---- 动态输入区 ----
                        when (selectedType) {
                            WriteDataType.TEXT -> {
                                FluentTextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    label = stringResource(R.string.label_text_content),
                                    hintText = FormDefaults.TEXT
                                )
                            }
                            WriteDataType.URL -> {
                                FluentTextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    label = stringResource(R.string.label_url_input),
                                    hintText = FormDefaults.URL
                                )
                            }
                            WriteDataType.WIFI -> {
                                FluentTextField(
                                    value = wifiSsid,
                                    onValueChange = { wifiSsid = it },
                                    label = stringResource(R.string.label_wifi_ssid),
                                    hintText = FormDefaults.WIFI_SSID
                                )
                                FluentTextField(
                                    value = wifiPassword,
                                    onValueChange = { wifiPassword = it },
                                    label = stringResource(R.string.label_wifi_password),
                                    hintText = FormDefaults.WIFI_PASSWORD
                                )

                                var encExpanded by remember { mutableStateOf(false) }
                                FluentDropdown(
                                    text = stringResource(R.string.label_encryption_type) + ": " + stringResource(wifiEncryption.displayResId),
                                    expanded = encExpanded,
                                    onExpandedChange = { encExpanded = it },
                                    options = WifiEncryption.entries.map { stringResource(it.displayResId) },
                                    onOptionSelected = { index ->
                                        wifiEncryption = WifiEncryption.entries[index]
                                        encExpanded = false
                                    }
                                )

                                var authExpanded by remember { mutableStateOf(false) }
                                FluentDropdown(
                                    text = stringResource(R.string.label_auth_type) + ": " + stringResource(wifiAuth.displayResId),
                                    expanded = authExpanded,
                                    onExpandedChange = { authExpanded = it },
                                    options = WifiAuth.entries.map { stringResource(it.displayResId) },
                                    onOptionSelected = { index ->
                                        wifiAuth = WifiAuth.entries[index]
                                        authExpanded = false
                                    }
                                )
                            }
                            WriteDataType.BLUETOOTH -> {
                                FluentTextField(
                                    value = btMac,
                                    onValueChange = { btMac = it },
                                    label = stringResource(R.string.label_bt_mac),
                                    hintText = FormDefaults.BT_MAC
                                )
                                FluentTextField(
                                    value = btName,
                                    onValueChange = { btName = it },
                                    label = stringResource(R.string.label_bt_name),
                                    hintText = FormDefaults.BT_NAME
                                )
                            }
                            WriteDataType.APPLICATION -> Unit // 此类型不在 P2P 的选项中
                        }

                        // ---- 发送按钮（留空时自动用占位符默认值） ----
                        FluentButton(
                            onClick = {
                                val formatted = buildFormattedMessage()
                                onMessageChange(formatted)
                                onSendMessage(formatted)
                            },
                            text = stringResource(R.string.p2p_button_send),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PageMetrics.minTouchTarget)
                        )

                        // ---- 断开连接 ----
                        FluentButton(
                            onClick = onDisconnect,
                            style = ButtonStyle.OutlinedButton,
                            text = stringResource(R.string.p2p_button_disconnect),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PageMetrics.minTouchTarget)
                        )

                        // 与底栏之间留出呼吸空间
                        Spacer(modifier = Modifier.height(FluentSpacing.s))
                    }
                }
            }
        }
    }
}

// =======================================================================
// 无线装置未开启的引导卡片
// =======================================================================
@Composable
private fun RadioWarningCard(
    message: String,
    isBluetoothEnabled: Boolean,
    isWiFiEnabled: Boolean,
    onEnableBluetooth: () -> Unit,
    onEnableWiFi: () -> Unit
) {
    FluentCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FluentSpacing.mPlus),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FluentText(
                text = message,
                style = FluentTextStyle.Body2,
                color = AppTheme.danger
            )
            Spacer(modifier = Modifier.height(FluentSpacing.m))
            Row(
                horizontalArrangement = Arrangement.spacedBy(FluentSpacing.s),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isBluetoothEnabled) {
                    FluentButton(
                        onClick = onEnableBluetooth,
                        text = stringResource(R.string.p2p_button_enable_bluetooth),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = PageMetrics.minTouchTarget)
                    )
                }
                if (!isWiFiEnabled) {
                    FluentButton(
                        onClick = onEnableWiFi,
                        text = stringResource(R.string.p2p_button_enable_wifi),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = PageMetrics.minTouchTarget)
                    )
                }
            }
        }
    }
}

// =======================================================================
// 解析并显示带前缀的消息
// =======================================================================
@Composable
private fun ParsedMessageDisplay(message: String) {
    when {
        message.startsWith("TEXT:") -> FluentText(
            text = stringResource(R.string.format_record_text, message.removePrefix("TEXT:")),
            style = FluentTextStyle.Body2
        )
        message.startsWith("URL:") -> FluentText(
            text = stringResource(R.string.format_record_uri, message.removePrefix("URL:")),
            style = FluentTextStyle.Body2
        )
        message.startsWith("WIFI:") -> {
            val parts = message.removePrefix("WIFI:").split("|")
            if (parts.size >= 4) {
                val encName = WifiEncryption.entries.find { it.wscValue == (parts[2].toIntOrNull() ?: 0) }?.displayResId
                val authName = WifiAuth.entries.find { it.wscValue == (parts[3].toIntOrNull() ?: 0) }?.displayResId
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FluentText(
                        text = stringResource(R.string.format_ssid, parts[0]),
                        style = FluentTextStyle.Body2
                    )
                    if (parts[1].isNotEmpty()) FluentText(
                        text = stringResource(R.string.format_wifi_password, parts[1]),
                        style = FluentTextStyle.Body2
                    )
                    if (encName != null) FluentText(
                        text = stringResource(R.string.format_encryption_type, stringResource(encName)),
                        style = FluentTextStyle.Body2
                    )
                    if (authName != null) FluentText(
                        text = stringResource(R.string.format_auth_type, stringResource(authName)),
                        style = FluentTextStyle.Body2
                    )
                }
            } else FluentText(text = message, style = FluentTextStyle.Body2)
        }
        message.startsWith("BT:") -> {
            val parts = message.removePrefix("BT:").split("|")
            if (parts.size >= 2) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FluentText(
                        text = stringResource(R.string.format_bt_mac, parts[0]),
                        style = FluentTextStyle.Body2
                    )
                    if (parts[1].isNotEmpty()) FluentText(
                        text = stringResource(R.string.format_bt_device_name, parts[1]),
                        style = FluentTextStyle.Body2
                    )
                }
            } else FluentText(text = message, style = FluentTextStyle.Body2)
        }
        else -> FluentText(text = message, style = FluentTextStyle.Body2)
    }
}

@Preview(name = "P2P · 浅色", showBackground = true)
@Composable
private fun PreviewP2PScreenLight() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) {
        P2PScreenPreviewContent()
    }
}

@Preview(name = "P2P · 深色", showBackground = true)
@Composable
private fun PreviewP2PScreenDark() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        P2PScreenPreviewContent()
    }
}

@Composable
private fun P2PScreenPreviewContent() {
    P2PScreen(
        connectionState = ConnectionState.DISCONNECTED,
        receivedNearbyMessage = "",
        isBluetoothEnabled = false,
        isWiFiEnabled = false,
        nearbyRadioWarning = "蓝牙和 Wi-Fi 均未开启。Nearby Connections 将不再自动打开无线装置，请先手动开启蓝牙或 Wi-Fi 后重试。",
        onMessageChange = {},
        onEnableBluetooth = {},
        onEnableWiFi = {},
        onStartAdvertising = {},
        onStopAdvertising = {},
        onStartDiscovery = {},
        onStopDiscovery = {},
        onDisconnect = {},
        onSendMessage = {},
        hasPermissions = true,
        onRequestPermissions = {}
    )
}
