package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.isValidApplicationPackageName

// =======================================================================
// 写入状态：由 MainActivity 驱动，控制弹窗显示
// =======================================================================
enum class WriteState {
    IDLE,              // 空闲，用户可以操作
    WAITING_FOR_CARD,  // 等待检测卡片
    WRITING,           // 正在写入
    SUCCESS,           // 写入成功（短暂显示后自动回到 IDLE）
    FAILED,            // 写入失败（短暂显示后自动回到 IDLE）
    EMULATING          // 正在卡模拟（等待读卡器读取）
}

// =======================================================================
// 写入数据类型
// =======================================================================
enum class WriteDataType(val labelResId: Int) {
    TEXT(R.string.data_type_text),
    URL(R.string.data_type_url),
    WIFI(R.string.data_type_wifi),
    BLUETOOTH(R.string.data_type_bluetooth),
    APPLICATION(R.string.data_type_application)
}

/**
 * Wi-Fi 加密类型，对应 WSC 属性 0x100F（Encryption Type）。
 * 这里只描述"加密算法"，认证方式（WPA/WPA2/WPA3）属于 [WifiAuth]。
 */
enum class WifiEncryption(val displayResId: Int, val wscValue: Int) {
    NONE(R.string.enc_none, 0x0001),
    WEP(R.string.enc_wep, 0x0002),
    TKIP(R.string.enc_tkip, 0x0004),
    AES(R.string.enc_aes, 0x0008),
    AES_TKIP(R.string.enc_aes_tkip, 0x000C)
}

/**
 * Wi-Fi 认证类型，对应 WSC 属性 0x1003（Authentication Type）。
 */
enum class WifiAuth(val displayResId: Int, val wscValue: Int) {
    OPEN(R.string.auth_open, 0x0001),
    WPA_PSK(R.string.auth_wpa_psk_short, 0x0002),
    WPA2_PSK(R.string.auth_wpa2_psk_short, 0x0020),
    WPA3_SAE(R.string.auth_wpa3_sae_short, 0x0040)
}

/**
 * 表单占位符 = 默认值。
 * 用户留空时直接用这些内容完成功能，不再弹"请输入"提示。
 * 蓝牙类型需 MAC 和设备名称**同时**留空才使用默认值。
 */
object FormDefaults {
    const val TEXT = "你好，哈尔滨工业大学！"
    const val URL = "https://www.hit.edu.cn/"
    const val WIFI_SSID = "HIT-WLAN"
    const val WIFI_PASSWORD = "hit_1920"
    const val BT_MAC = "11:22:33:AA:BB:CC"
    const val BT_NAME = "HIT NFC 设备"

    /** 文本/网址：留空则用默认值 */
    fun resolveTextOrUrl(input: String): String =
        input.ifBlank { if (input.isEmpty()) TEXT else URL }

    /** Wi-Fi：SSID 和密码各自独立，留空用各自默认值 */
    fun resolveWifiSsid(ssid: String): String = ssid.ifBlank { WIFI_SSID }
    fun resolveWifiPassword(password: String): String = password.ifBlank { WIFI_PASSWORD }

    /** 蓝牙：两框同时留空才用默认值；只填一个则另一个也用默认值 */
    fun resolveBluetooth(mac: String, name: String): Pair<String, String> {
        if (mac.isBlank() && name.isBlank()) return BT_MAC to BT_NAME
        return mac.ifBlank { BT_MAC } to name.ifBlank { BT_NAME }
    }
}


// =======================================================================
// 统一的写卡界面
// =======================================================================
@Composable
fun WriteCardScreen(
    writeState: WriteState,
    writeStatusMessage: String,
    onWriteText: (String) -> Unit,
    onWriteUrl: (String) -> Unit,
    onWriteWifi: (ssid: String, password: String, encryption: WifiEncryption, auth: WifiAuth) -> Unit,
    onWriteBluetooth: (mac: String, name: String) -> Unit,
    onWriteApplication: (packageName: String) -> Unit = {},
    onStartEmulation: (
        type: WriteDataType, textInput: String,
        wifiSsid: String, wifiPassword: String,
        wifiEncryption: WifiEncryption, wifiAuth: WifiAuth,
        btMac: String, btName: String,
        applicationPackageName: String
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onCancelWrite: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    var selectedType by remember { mutableStateOf(WriteDataType.TEXT) }

    // ----- 文本/网址 公共输入 -----
    var textInput by remember { mutableStateOf("") }

    // ----- Wi-Fi 表单 -----
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiEncryption by remember { mutableStateOf(WifiEncryption.AES) }
    var wifiAuth by remember { mutableStateOf(WifiAuth.WPA2_PSK) }

    // ----- 蓝牙表单 -----
    var btMac by remember { mutableStateOf("") }
    var btName by remember { mutableStateOf("") }

    // ----- 应用程序表单 -----
    var applicationPackageName by remember { mutableStateOf("") }
    var applicationPickerVisible by remember { mutableStateOf(false) }
    val validApplicationPackage = isValidApplicationPackageName(applicationPackageName)
    val canSubmit = writeState == WriteState.IDLE &&
        (selectedType != WriteDataType.APPLICATION || validApplicationPackage)

    // 切换类型时清空输入
    LaunchedEffect(selectedType) {
        textInput = ""
        wifiSsid = ""
        wifiPassword = ""
        btMac = ""
        btName = ""
        applicationPackageName = ""
        applicationPickerVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() }
    ) {
        PageColumn(verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
            // ---- 标题 ----
            FluentText(
                text = stringResource(R.string.title_write_card),
                style = FluentTextStyle.Title3
            )

            // ---- 类型选择下拉框（浮层宽度与按钮对齐） ----
            var typeExpanded by remember { mutableStateOf(false) }
            FluentDropdown(
                text = stringResource(R.string.format_type_label, stringResource(selectedType.labelResId)),
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it },
                options = WriteDataType.entries.map { stringResource(it.labelResId) },
                onOptionSelected = { index ->
                    selectedType = WriteDataType.entries[index]
                    typeExpanded = false
                }
            )

            // ---- 动态输入区域 ----
            when (selectedType) {
                WriteDataType.TEXT -> {
                    FluentTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = stringResource(R.string.label_text_content),
                        hintText = FormDefaults.TEXT
                    )
                    FluentText(
                        text = stringResource(R.string.hint_text_format),
                        style = FluentTextStyle.Caption1,
                        color = AppTheme.textHint
                    )
                }

                WriteDataType.URL -> {
                    FluentTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = stringResource(R.string.label_url_input),
                        hintText = FormDefaults.URL
                    )
                    FluentText(
                        text = stringResource(R.string.hint_url_format),
                        style = FluentTextStyle.Caption1,
                        color = AppTheme.textHint
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

                    // 加密类型下拉
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

                    // 认证类型下拉
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
                    FluentText(
                        text = stringResource(R.string.hint_bt_format),
                        style = FluentTextStyle.Caption1,
                        color = AppTheme.textHint
                    )
                }

                WriteDataType.APPLICATION -> {
                    FluentButton(
                        onClick = {
                            focusManager.clearFocus()
                            applicationPickerVisible = true
                        },
                        text = stringResource(R.string.button_select_application),
                        style = ButtonStyle.OutlinedButton,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget)
                    )
                    FluentTextField(
                        value = applicationPackageName,
                        onValueChange = { applicationPackageName = it },
                        label = stringResource(R.string.label_application_package),
                        hintText = stringResource(R.string.placeholder_application_package)
                    )
                    if (applicationPackageName.isNotBlank() && !validApplicationPackage) {
                        FluentText(
                            text = stringResource(R.string.error_application_package),
                            style = FluentTextStyle.Caption1,
                            color = AppTheme.danger
                        )
                    }
                    FluentText(
                        text = stringResource(R.string.hint_application_format),
                        style = FluentTextStyle.Caption1,
                        color = AppTheme.textHint
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluentSpacing.s))

            // ---- 写入标签 + 卡模拟 按钮（并列） ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)
            ) {
                FluentButton(
                    onClick = {
                        when (selectedType) {
                            WriteDataType.TEXT -> onWriteText(textInput.ifBlank { FormDefaults.TEXT })
                            WriteDataType.URL -> onWriteUrl(textInput.ifBlank { FormDefaults.URL })
                            WriteDataType.WIFI -> onWriteWifi(
                                FormDefaults.resolveWifiSsid(wifiSsid),
                                FormDefaults.resolveWifiPassword(wifiPassword),
                                wifiEncryption, wifiAuth
                            )
                            WriteDataType.BLUETOOTH -> {
                                val (mac, name) = FormDefaults.resolveBluetooth(btMac, btName)
                                onWriteBluetooth(mac, name)
                            }
                            WriteDataType.APPLICATION -> onWriteApplication(applicationPackageName.trim())
                        }
                    },
                    text = stringResource(R.string.button_write_tag),
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = PageMetrics.minTouchTarget)
                )

                FluentButton(
                    onClick = {
                        val resolvedText = textInput.ifBlank {
                            if (selectedType == WriteDataType.URL) FormDefaults.URL else FormDefaults.TEXT
                        }
                        val resolvedSsid = FormDefaults.resolveWifiSsid(wifiSsid)
                        val resolvedPass = FormDefaults.resolveWifiPassword(wifiPassword)
                        val (resolvedMac, resolvedName) = FormDefaults.resolveBluetooth(btMac, btName)
                        onStartEmulation(
                            selectedType, resolvedText,
                            resolvedSsid, resolvedPass, wifiEncryption, wifiAuth,
                            resolvedMac, resolvedName,
                            applicationPackageName.trim()
                        )
                    },
                    text = stringResource(R.string.button_card_emulation),
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = PageMetrics.minTouchTarget)
                )
            }

            Spacer(modifier = Modifier.height(FluentSpacing.s))
        }

        if (applicationPickerVisible) {
            ApplicationPickerDialog(
                onApplicationSelected = {
                    applicationPackageName = it.packageName
                    applicationPickerVisible = false
                },
                onDismissRequest = { applicationPickerVisible = false }
            )
        }

        // ================================================================
        // 写入/模拟状态弹窗
        // ================================================================
        if (writeState != WriteState.IDLE) {
            Dialog(
                onDismissRequest = { },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                FluentCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluentSpacing.mPlus)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FluentSpacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (writeState) {
                            WriteState.WAITING_FOR_CARD -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                                FluentText(
                                    text = writeStatusMessage.ifEmpty { stringResource(R.string.dialog_tap_card) },
                                    style = FluentTextStyle.Body1
                                )
                                Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                                FluentButton(
                                    onClick = onCancelWrite,
                                    style = ButtonStyle.TextButton,
                                    text = stringResource(R.string.button_cancel),
                                    modifier = Modifier.heightIn(min = PageMetrics.minTouchTarget)
                                )
                            }

                            WriteState.WRITING -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                                FluentText(
                                    text = stringResource(R.string.dialog_writing),
                                    style = FluentTextStyle.Body1
                                )
                            }

                            WriteState.SUCCESS -> {
                                FluentText(
                                    text = writeStatusMessage.ifEmpty { stringResource(R.string.dialog_write_success) },
                                    style = FluentTextStyle.Body1Strong,
                                    color = AppTheme.success
                                )
                            }

                            WriteState.FAILED -> {
                                FluentText(
                                    text = writeStatusMessage.ifEmpty { stringResource(R.string.dialog_write_failed) },
                                    style = FluentTextStyle.Body1Strong,
                                    color = AppTheme.danger
                                )
                            }

                            WriteState.EMULATING -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                                FluentText(
                                    text = stringResource(R.string.dialog_emulation_started),
                                    style = FluentTextStyle.Body1Strong
                                )
                                Spacer(modifier = Modifier.height(FluentSpacing.s))
                                FluentText(
                                    text = stringResource(R.string.dialog_approach_reader),
                                    style = FluentTextStyle.Body2,
                                    color = AppTheme.textHint
                                )
                                Spacer(modifier = Modifier.height(FluentSpacing.mPlus))
                                FluentButton(
                                    onClick = onCancelWrite,
                                    style = ButtonStyle.TextButton,
                                    text = stringResource(R.string.dialog_button_stop_emulation),
                                    modifier = Modifier.heightIn(min = PageMetrics.minTouchTarget)
                                )
                            }

                            WriteState.IDLE -> { /* unreachable */ }
                        }
                    }
                }
            }
        }
    }
}

// =======================================================================
// 预览
// =======================================================================
@Preview(name = "写卡 · 浅色", showBackground = true)
@Composable
private fun PreviewWriteCardScreenLight() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) {
        WriteCardScreen(
            writeState = WriteState.IDLE,
            writeStatusMessage = "",
            onWriteText = {},
            onWriteUrl = {},
            onWriteWifi = { _, _, _, _ -> },
            onWriteBluetooth = { _, _ -> }
        )
    }
}

@Preview(name = "写卡 · 深色", showBackground = true)
@Composable
private fun PreviewWriteCardScreenDark() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        WriteCardScreen(
            writeState = WriteState.IDLE,
            writeStatusMessage = "",
            onWriteText = {},
            onWriteUrl = {},
            onWriteWifi = { _, _, _, _ -> },
            onWriteBluetooth = { _, _ -> }
        )
    }
}

@Preview(name = "写卡 · 深色 · 失败弹窗", showBackground = true)
@Composable
private fun PreviewWriteCardScreenDarkFailed() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        WriteCardScreen(
            writeState = WriteState.FAILED,
            writeStatusMessage = "写入失败: 标签不可写",
            onWriteText = {},
            onWriteUrl = {},
            onWriteWifi = { _, _, _, _ -> },
            onWriteBluetooth = { _, _ -> }
        )
    }
}
