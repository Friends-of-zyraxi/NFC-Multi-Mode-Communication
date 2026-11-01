package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.MAX_EMULATED_NDEF_BYTES
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createNfcWriteMessage
import io.github.zyraxi21.nfc.util.NfcReadResult
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.util.MAX_NEARBY_NDEF_BYTES
import kotlinx.coroutines.launch

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
 * 这里只描述加密算法，认证方式属于 [WifiAuth]。
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
    WPA2_PSK(R.string.auth_wpa2_psk_short, 0x0020)
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
    fun resolveWifiSsid(ssid: String): String = ssid.ifEmpty { WIFI_SSID }
    fun resolveWifiPassword(password: String): String = password.ifEmpty { WIFI_PASSWORD }

    /** 蓝牙：同时留空时使用示例；指定地址后可以省略设备名称。 */
    fun resolveBluetooth(mac: String, name: String): Pair<String, String> {
        if (mac.isBlank() && name.isBlank()) return BT_MAC to BT_NAME
        return mac.ifBlank { BT_MAC } to name
    }
}


private data class WriteRecordDraft(
    val id: Long,
    val type: WriteDataType = WriteDataType.TEXT,
    val textInput: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val wifiEncryption: WifiEncryption = WifiEncryption.AES,
    val wifiAuth: WifiAuth = WifiAuth.WPA2_PSK,
    val btMac: String = "",
    val btName: String = "",
    val applicationPackageName: String = "",
) {
    fun toRecord(): NfcWriteRecord = when (type) {
        WriteDataType.TEXT -> NfcWriteRecord.Text(textInput.ifBlank { FormDefaults.TEXT })
        WriteDataType.URL -> NfcWriteRecord.Link(textInput.ifBlank { FormDefaults.URL }.trim())
        WriteDataType.WIFI -> NfcWriteRecord.Wifi(
            FormDefaults.resolveWifiSsid(wifiSsid), if (wifiAuth == WifiAuth.OPEN) "" else FormDefaults.resolveWifiPassword(wifiPassword),
            wifiEncryption, wifiAuth,
        )
        WriteDataType.BLUETOOTH -> FormDefaults.resolveBluetooth(btMac, btName).let { (mac, name) ->
            NfcWriteRecord.Bluetooth(mac.trim(), name)
        }
        WriteDataType.APPLICATION -> NfcWriteRecord.Application(applicationPackageName.trim())
    }

    fun summary(): String = when (val record = toRecord()) {
        is NfcWriteRecord.Text -> record.text
        is NfcWriteRecord.Link -> record.uri
        is NfcWriteRecord.Wifi -> record.ssid
        is NfcWriteRecord.Bluetooth -> record.mac + "\n" + record.name
        is NfcWriteRecord.Application -> record.packageName
    }
}

/** 只保存基本类型，切换页面或重建 Activity 后仍保留每条记录及其顺序。 */
private val writeRecordsSaver = listSaver<List<WriteRecordDraft>, ArrayList<String>>(
    save = { records ->
        records.map {
            arrayListOf(it.id.toString(), it.type.name, it.textInput, it.wifiSsid, it.wifiPassword,
                it.wifiEncryption.name, it.wifiAuth.name, it.btMac, it.btName, it.applicationPackageName)
        }
    },
    restore = { records ->
        records.map {
            WriteRecordDraft(it[0].toLong(), WriteDataType.valueOf(it[1]), it[2], it[3], it[4],
                WifiEncryption.valueOf(it[5]), WifiAuth.valueOf(it[6]), it[7], it[8], it[9])
        }
    },
)

/** 同一份有序记录列表用于标签写入和 HCE，当前展开的表单也直接参与提交。 */
@Composable
fun WriteCardScreen(
    writeState: WriteState,
    writeStatusMessage: String,
    onWriteRecords: (List<NfcWriteRecord>) -> Unit,
    onStartEmulation: (List<NfcWriteRecord>) -> Unit = {},
    onCancelWrite: () -> Unit = {},
) {
    RecordEditorScreen(writeState, writeStatusMessage, onWriteRecords, onStartEmulation, onCancelWrite)
}

/** NFC 和 Nearby 共用类型、列表、校验及编辑状态，只有提交方式不同。 */
@Composable
internal fun RecordEditorScreen(
    writeState: WriteState,
    writeStatusMessage: String,
    onWriteRecords: (List<NfcWriteRecord>) -> Unit,
    onStartEmulation: (List<NfcWriteRecord>) -> Unit = {},
    onCancelWrite: () -> Unit = {},
    nearbyMode: Boolean = false,
    receivedResult: NfcReadResult? = null,
    onRecordAction: ((RecordAction) -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var drafts by rememberSaveable(stateSaver = writeRecordsSaver) {
        mutableStateOf(listOf(WriteRecordDraft(id = 1)))
    }
    var nextId by rememberSaveable { mutableLongStateOf(2) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(1) }
    var pickerRecordId by remember { mutableStateOf<Long?>(null) }
    val records = remember(drafts) { drafts.map { it.toRecord() } }
    val message = remember(records) {
        if (records.isNotEmpty() && records.all { it.validationError() == null }) {
            runCatching { createNfcWriteMessage(records) }.getOrNull()
        } else null
    }
    val canWrite = writeState == WriteState.IDLE && message != null &&
        (!nearbyMode || message.byteArrayLength <= MAX_NEARBY_NDEF_BYTES)
    val canEmulate = canWrite && message.byteArrayLength <= MAX_EMULATED_NDEF_BYTES

    fun update(draft: WriteRecordDraft) {
        drafts = drafts.map { if (it.id == draft.id) draft else it }
    }
    fun move(id: Long, delta: Int) {
        val from = drafts.indexOfFirst { it.id == id }
        val target = from + delta
        if (from < 0 || target !in drafts.indices) return
        drafts = drafts.toMutableList().apply { add(target, removeAt(from)) }
        scope.launch { listState.animateScrollToItem(target + 1) }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { focusManager.clearFocus() })
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(if (nearbyMode) "p2p-records" else "write-records"),
            contentPadding = PaddingValues(horizontal = LocalPageGutter.current, vertical = FluentSpacing.mPlus),
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.s)) {
                    FluentText(stringResource(if (nearbyMode) R.string.p2p_title else R.string.title_write_card), FluentTextStyle.Title3,
                        modifier = Modifier.semantics { heading() })
                }
            }
            headerContent?.let { item { it() } }
            if (nearbyMode) {
                item { NearbyReceivedHeading(receivedResult) }
                itemsIndexed(receivedResult?.records.orEmpty(), key = { index, _ -> "received-$index" }) { index, record ->
                    ReaderRecordCard(record, index, onRecordAction)
                }
            }
            itemsIndexed(drafts, key = { _, draft -> draft.id }) { index, draft ->
                val expanded = expandedId == draft.id
                FluentCard(Modifier.fillMaxWidth().testTag("write-record-" + draft.id)) {
                    Column(
                        Modifier.fillMaxWidth().padding(FluentSpacing.mPlus),
                        verticalArrangement = Arrangement.spacedBy(FluentSpacing.m),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FluentText(
                                stringResource(R.string.write_record_title, index + 1, stringResource(draft.type.labelResId)),
                                FluentTextStyle.Body1Strong, centered = false,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            FluentButton(
                                text = stringResource(if (expanded) R.string.write_collapse_record else R.string.write_edit_record),
                                style = ButtonStyle.TextButton,
                                modifier = Modifier.heightIn(min = PageMetrics.minTouchTarget).testTag("write-edit-" + draft.id),
                                onClick = {
                                    focusManager.clearFocus()
                                    expandedId = if (expanded) null else draft.id
                                    if (!expanded) scope.launch { listState.animateScrollToItem(index + 1) }
                                },
                            )
                        }
                        if (expanded) {
                            WriteRecordEditor(draft, onChange = ::update, onPickApplication = {
                                focusManager.clearFocus()
                                pickerRecordId = draft.id
                            })
                        } else {
                            FluentText(
                                draft.summary().ifEmpty { stringResource(R.string.write_records_incomplete) },
                                FluentTextStyle.Body2, color = AppTheme.textSecondary,
                                centered = false, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (draft.toRecord().validationError() != null) {
                                FluentText(stringResource(R.string.write_record_invalid), FluentTextStyle.Caption1,
                                    color = AppTheme.danger, centered = false)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.xs)) {
                            FluentButton(
                                text = stringResource(R.string.write_move_up), style = ButtonStyle.TextButton,
                                enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget).testTag("write-up-" + draft.id),
                                onClick = { move(draft.id, -1) },
                            )
                            FluentButton(
                                text = stringResource(R.string.write_move_down), style = ButtonStyle.TextButton,
                                enabled = index < drafts.lastIndex, modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget).testTag("write-down-" + draft.id),
                                onClick = { move(draft.id, 1) },
                            )
                            FluentButton(
                                text = stringResource(R.string.write_delete_record), style = ButtonStyle.TextButton,
                                modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget).testTag("write-delete-" + draft.id),
                                onClick = {
                                    focusManager.clearFocus()
                                    drafts = drafts.filterNot { it.id == draft.id }
                                    if (expandedId == draft.id) expandedId = drafts.getOrNull(index.coerceAtMost(drafts.lastIndex))?.id
                                },
                            )
                        }
                    }
                }
            }
            if (drafts.isEmpty()) {
                item { FluentText(stringResource(R.string.write_empty_records), FluentTextStyle.Body2, color = AppTheme.textSecondary) }
            }
            item {
                FluentButton(
                    text = stringResource(R.string.write_add_record), style = ButtonStyle.OutlinedButton,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget),
                    onClick = {
                        focusManager.clearFocus()
                        val added = WriteRecordDraft(nextId++)
                        drafts = drafts + added
                        expandedId = added.id
                        scope.launch { listState.animateScrollToItem(drafts.size) }
                    },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                    FluentText(
                        if (message == null) stringResource(R.string.write_records_incomplete)
                        else stringResource(R.string.write_records_summary, records.size, message.byteArrayLength),
                        FluentTextStyle.Caption1, color = AppTheme.textSecondary,
                    )
                    if (nearbyMode && message != null && message.byteArrayLength > MAX_NEARBY_NDEF_BYTES) {
                        FluentText(stringResource(R.string.p2p_payload_too_large, MAX_NEARBY_NDEF_BYTES),
                            FluentTextStyle.Caption1, color = AppTheme.danger)
                    }
                    if (!nearbyMode && message != null && message.byteArrayLength > MAX_EMULATED_NDEF_BYTES) {
                        FluentText(stringResource(R.string.write_emulation_too_large, MAX_EMULATED_NDEF_BYTES),
                            FluentTextStyle.Caption1, color = AppTheme.danger)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                        FluentButton(
                            text = stringResource(if (nearbyMode) R.string.p2p_button_send else R.string.button_write_tag), enabled = canWrite,
                            modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget),
                            onClick = { focusManager.clearFocus(); onWriteRecords(records) },
                        )
                        if (!nearbyMode) FluentButton(
                            text = stringResource(R.string.button_card_emulation), enabled = canEmulate,
                            modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget),
                            onClick = { focusManager.clearFocus(); onStartEmulation(records) },
                        )
                    }
                }
            }
        }

        pickerRecordId?.let { id ->
            ApplicationPickerDialog(
                onApplicationSelected = { application ->
                    drafts.find { it.id == id }?.let { update(it.copy(applicationPackageName = application.packageName)) }
                    pickerRecordId = null
                },
                onDismissRequest = { pickerRecordId = null },
            )
        }
        if (writeState != WriteState.IDLE) WriteProgressDialog(writeState, writeStatusMessage, onCancelWrite)
    }
}

@Composable
private fun WriteRecordEditor(
    draft: WriteRecordDraft,
    onChange: (WriteRecordDraft) -> Unit,
    onPickApplication: () -> Unit,
) {
    var typeExpanded by remember { mutableStateOf(false) }
    FluentDropdown(
        text = stringResource(R.string.format_type_label, stringResource(draft.type.labelResId)),
        expanded = typeExpanded,
        onExpandedChange = { typeExpanded = it },
        options = WriteDataType.entries.map { stringResource(it.labelResId) },
        onOptionSelected = { index ->
            val type = WriteDataType.entries[index]
            if (type != draft.type) onChange(WriteRecordDraft(draft.id, type))
            typeExpanded = false
        },
    )
    when (draft.type) {
        WriteDataType.TEXT -> {
            FluentTextField(draft.textInput, { onChange(draft.copy(textInput = it)) },
                stringResource(R.string.label_text_content), hintText = FormDefaults.TEXT)
        }
        WriteDataType.URL -> {
            FluentTextField(draft.textInput, { onChange(draft.copy(textInput = it)) },
                stringResource(R.string.label_url_input), hintText = FormDefaults.URL)
            FluentText(stringResource(R.string.hint_url_format), FluentTextStyle.Caption1, color = AppTheme.textHint)
        }
        WriteDataType.WIFI -> {
            FluentTextField(draft.wifiSsid, { onChange(draft.copy(wifiSsid = it)) },
                stringResource(R.string.label_wifi_ssid), hintText = FormDefaults.WIFI_SSID)
            var authExpanded by remember { mutableStateOf(false) }
            FluentDropdown(
                text = stringResource(R.string.label_auth_type) + "：" + stringResource(draft.wifiAuth.displayResId),
                expanded = authExpanded, onExpandedChange = { authExpanded = it },
                options = WifiAuth.entries.map { stringResource(it.displayResId) },
                onOptionSelected = {
                    val auth = WifiAuth.entries[it]
                    onChange(draft.copy(wifiAuth = auth, wifiEncryption = when {
                        auth == WifiAuth.OPEN -> WifiEncryption.NONE
                        draft.wifiEncryption == WifiEncryption.NONE -> WifiEncryption.AES
                        else -> draft.wifiEncryption
                    }))
                    authExpanded = false
                },
            )
            if (draft.wifiAuth != WifiAuth.OPEN) {
                FluentTextField(draft.wifiPassword, { onChange(draft.copy(wifiPassword = it)) },
                    stringResource(R.string.label_wifi_password), hintText = FormDefaults.WIFI_PASSWORD)
                val encryptions = listOf(WifiEncryption.TKIP, WifiEncryption.AES, WifiEncryption.AES_TKIP)
                var encExpanded by remember { mutableStateOf(false) }
                FluentDropdown(
                    text = stringResource(R.string.label_encryption_type) + "：" + stringResource(draft.wifiEncryption.displayResId),
                    expanded = encExpanded, onExpandedChange = { encExpanded = it },
                    options = encryptions.map { stringResource(it.displayResId) },
                    onOptionSelected = { onChange(draft.copy(wifiEncryption = encryptions[it])); encExpanded = false },
                )
            }
        }
        WriteDataType.BLUETOOTH -> {
            FluentTextField(draft.btMac, { onChange(draft.copy(btMac = it)) },
                stringResource(R.string.label_bt_mac), hintText = FormDefaults.BT_MAC)
            FluentTextField(draft.btName, { onChange(draft.copy(btName = it)) },
                stringResource(R.string.label_bt_name), hintText = FormDefaults.BT_NAME)
        }
        WriteDataType.APPLICATION -> {
            FluentButton(onClick = onPickApplication, text = stringResource(R.string.button_select_application),
                style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
            FluentTextField(draft.applicationPackageName, { onChange(draft.copy(applicationPackageName = it)) },
                stringResource(R.string.label_application_package), hintText = stringResource(R.string.placeholder_application_package))
        }
    }
    val error = draft.toRecord().validationError()
    if (error != null && (draft.type != WriteDataType.APPLICATION || draft.applicationPackageName.isNotBlank())) {
        FluentText(stringResource(error), FluentTextStyle.Caption1, color = AppTheme.danger)
    }
}

@Composable
private fun WriteProgressDialog(writeState: WriteState, message: String, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        FluentCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FluentSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
            ) {
                if (writeState in listOf(WriteState.WAITING_FOR_CARD, WriteState.WRITING, WriteState.EMULATING)) {
                    CircularProgressIndicator()
                }
                val title = when (writeState) {
                    WriteState.WAITING_FOR_CARD -> message.ifEmpty { stringResource(R.string.dialog_tap_card) }
                    WriteState.WRITING -> stringResource(R.string.dialog_writing)
                    WriteState.SUCCESS -> message.ifEmpty { stringResource(R.string.dialog_write_success) }
                    WriteState.FAILED -> message.ifEmpty { stringResource(R.string.dialog_write_failed) }
                    WriteState.EMULATING -> stringResource(R.string.dialog_emulation_started)
                    WriteState.IDLE -> ""
                }
                FluentText(
                    title, FluentTextStyle.Body1Strong,
                    color = when (writeState) {
                        WriteState.SUCCESS -> AppTheme.success
                        WriteState.FAILED -> AppTheme.danger
                        else -> AppTheme.textPrimary
                    },
                )
                if (writeState == WriteState.EMULATING) {
                    if (message.isNotEmpty()) FluentText(message, FluentTextStyle.Caption1, color = AppTheme.textHint)
                    FluentText(stringResource(R.string.dialog_approach_reader), FluentTextStyle.Body2, color = AppTheme.textHint)
                }
                if (writeState == WriteState.WAITING_FOR_CARD || writeState == WriteState.EMULATING) {
                    FluentButton(
                        onClick = onCancel,
                        style = ButtonStyle.TextButton,
                        text = stringResource(if (writeState == WriteState.EMULATING) R.string.dialog_button_stop_emulation else R.string.button_cancel),
                        modifier = Modifier.heightIn(min = PageMetrics.minTouchTarget),
                    )
                }
            }
        }
    }
}

@Preview(name = "写卡 浅色", showBackground = true)
@Composable
private fun PreviewWriteCardScreenLight() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) { WriteCardScreen(WriteState.IDLE, "", {}) }
}

@Preview(name = "写卡 深色", showBackground = true)
@Composable
private fun PreviewWriteCardScreenDark() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) { WriteCardScreen(WriteState.IDLE, "", {}) }
}
