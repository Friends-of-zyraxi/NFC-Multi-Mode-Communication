@file:OptIn(ExperimentalStdlibApi::class)

package io.github.zyraxi21.nfc

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.nfc.cardemulation.CardEmulation
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.*
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.Manifest

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.microsoft.fluentui.tokenized.notification.NotificationResult
import com.microsoft.fluentui.tokenized.notification.SnackbarState

import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.ConnectionState
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NFCReaderScreen
import io.github.zyraxi21.nfc.ui.theme.P2PScreen
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.ui.theme.WriteCardScreen
import io.github.zyraxi21.nfc.ui.theme.WriteDataType
import io.github.zyraxi21.nfc.ui.theme.WriteState
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import kotlin.time.Duration.Companion.seconds
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : ComponentActivity() {

    // NFC 相关
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFiltersArray: Array<IntentFilter>
    private lateinit var techListsArray: Array<Array<String>>

    // Nearby Connections 相关
    private lateinit var connectionsClient: ConnectionsClient
    private val serviceId = "io.github.zyraxi21.nfc.P2P"

    // 读卡 UI 状态 (来自 ReadCard.kt)
    private var tagInfo by mutableStateOf("")
    private var tagContent by mutableStateOf("")
    // 默认隐藏；自检发现 NFC 不可用/未启用时才显示检查按钮
    private var isReaderButtonVisible by mutableStateOf(false)

    // 写卡 UI 状态 (来自 WriteCard.kt)
    enum class WriteMode { IDLE, TEXT, URL, WIFI, BLUETOOTH }
    private var currentWriteMode by mutableStateOf(WriteMode.IDLE)
    private var inputText by mutableStateOf("")

    // 写入流程状态（驱动弹窗）
    var writeState by mutableStateOf(WriteState.IDLE)
    var writeStatusMessage by mutableStateOf("")

    // Wi-Fi 写入数据
    var wifiSsid by mutableStateOf("")
    var wifiPassword by mutableStateOf("")
    var wifiEncryption by mutableStateOf(WifiEncryption.AES)
    var wifiAuth by mutableStateOf(WifiAuth.WPA2_PSK)

    // 蓝牙写入数据
    var btMac by mutableStateOf("")
    var btName by mutableStateOf("")

    private var snackbarHostState: SnackbarState? = null

    // P2P通信相关状态变量
    private var p2pConnectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var receivedNearbyMessage by mutableStateOf("")
    private var messageToSend by mutableStateOf("")
    private var currentEndpointId: String? = null
    private var isBluetoothEnabled by mutableStateOf(false)
    private var isWiFiEnabled by mutableStateOf(false)

    // 权限请求回调（由 Compose 侧注入）与待重试的 P2P 操作
    private var requestNearbyPermissions: (() -> Unit)? = null
    private var pendingP2PAction: (() -> Unit)? = null
    private var nearbyRadioWarning by mutableStateOf<String?>(null)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private val wifiManager: WifiManager?
        get() = getSystemService(WifiManager::class.java)

    // 常量 (来自 ReadCard.kt)
    companion object {
        const val TAG = "NFC_DEMO"

        /** 保存/恢复卡模拟状态用的 key */
        private const val STATE_WRITE_STATE = "write_state"

        /** 从对端 HCE 卡读取 NDEF 时的防御性总长度上限（64 KiB） */
        private const val MAX_EMULATED_BYTES = 64 * 1024

        val URI_PREFIX_MAP = mapOf(
            0x00.toByte() to "", 0x01.toByte() to "http://www.", 0x02.toByte() to "https://www.",
            0x03.toByte() to "http://", 0x04.toByte() to "https://", 0x05.toByte() to "tel:",
            0x06.toByte() to "mailto:", 0x07.toByte() to "ftp://anonymous:anonymous@",
            0x08.toByte() to "ftp://ftp.", 0x09.toByte() to "ftps://", 0x0A.toByte() to "sftp://",
            0x0B.toByte() to "smb://", 0x0C.toByte() to "nfs://", 0x0D.toByte() to "ftp://",
            0x0E.toByte() to "dav://", 0x0F.toByte() to "news:", 0x10.toByte() to "telnet://",
            0x11.toByte() to "imap:", 0x12.toByte() to "rtsp://", 0x13.toByte() to "urn:",
            0x14.toByte() to "pop:", 0x15.toByte() to "sip:", 0x16.toByte() to "sips:",
            0x17.toByte() to "tftp:", 0x18.toByte() to "btspp://", 0x19.toByte() to "btl2cap://",
            0x1A.toByte() to "btgoep://", 0x1B.toByte() to "tcpobex://", 0x1C.toByte() to "irdaobex://",
            0x1D.toByte() to "file://", 0x1E.toByte() to "urn:epc:id:", 0x1F.toByte() to "urn:epc:tag:",
            0x20.toByte() to "urn:epc:pat:", 0x21.toByte() to "urn:epc:raw:", 0x22.toByte() to "urn:epc:",
            0x23.toByte() to "urn:nfc:"
        )
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // edge-to-edge 的系统栏图标明暗必须自己按应用主题决定，不能交给 enableEdgeToEdge() 的
        // 默认值（它跟随系统 isSystemInDarkTheme 判断）。理由：顶栏是品牌色/深色表面，
        // 状态栏图标压在它上面。深色模式下顶栏是 #292929，若仍用深色图标就完全看不见——
        // 这正是之前截图里状态栏几乎不可读的原因。
        val darkMode = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val statusBarIconStyle = if (darkMode) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        }
        // 导航栏底色由 Theme 里的 windowBackground 与底栏同色，这里只需保证图标明暗一致
        val navigationBarIconStyle = if (darkMode) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        }
        enableEdgeToEdge(
            statusBarStyle = statusBarIconStyle,
            navigationBarStyle = navigationBarIconStyle
        )

        // 恢复卡模拟状态：Activity 重建后若状态退回 IDLE，onResume 会重新打开 reader mode，
        // 而 reader mode 会关闭卡模拟，正在进行的模拟会因此失效。
        savedInstanceState?.getString(STATE_WRITE_STATE)
            ?.let { saved -> WriteState.entries.firstOrNull { it.name == saved } }
            ?.let { restored -> writeState = restored }

        // 初始化 Nearby Connections 客户端
        connectionsClient = Nearby.getConnectionsClient(this)
        refreshNearbyRadioStatus()

        // 初始化 NFC 适配器
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // 创建PendingIntent
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )

        // 设置Intent过滤器 (来自 ReadCard.kt)
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("*/*")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                throw RuntimeException("Failed to add MIME type", e)
            }
        }
        @Suppress("DEPRECATION")
        intentFiltersArray = arrayOf(
            ndefFilter,
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )

        // 支持的所有NFC技术类型
        techListsArray = arrayOf(
            arrayOf(
                NfcA::class.java.name, NfcB::class.java.name, NfcF::class.java.name,
                NfcV::class.java.name, IsoDep::class.java.name, MifareClassic::class.java.name,
                MifareUltralight::class.java.name, Ndef::class.java.name, NdefFormatable::class.java.name
            )
        )

        setContent {
            MyApplicationTheme {
                val coroutineScope = rememberCoroutineScope()
                snackbarHostState = remember { SnackbarState() }

                // ---- App 级 NFC 自检：启动时检查一次，正常则静默隐藏按钮 ----
                var nfcChecked by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!nfcChecked) {
                        nfcChecked = true
                        checkNfcAvailability { messageRes, actionRes, action ->
                            if (messageRes != R.string.msg_nfc_available) {
                                coroutineScope.launch {
                                    val result = snackbarHostState!!.showSnackbar(
                                        message = getString(messageRes),
                                        actionText = if (actionRes != 0) getString(actionRes) else null
                                    )
                                    if (result == NotificationResult.CLICKED) {
                                        action?.invoke()
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 权限请求 ----
                val nearbyPermissions = buildNearbyPermissions()
                var hasNearbyPermissions by remember {
                    mutableStateOf(
                        nearbyPermissions.all {
                            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    hasNearbyPermissions = results.values.all { it }
                    if (hasNearbyPermissions) {
                        refreshNearbyRadioStatus()
                        showToast(getString(R.string.toast_permission_granted))
                        // 授权成功后自动重试之前被拦截的 P2P 操作
                        pendingP2PAction?.invoke()
                        pendingP2PAction = null
                    }
                }
                // 注入到 Activity，供 startAdvertising / startDiscovery 调用
                requestNearbyPermissions = { permLauncher.launch(nearbyPermissions) }

                BottomNavigationApp(
                    readerScreen = {
                        NFCReaderScreen(
                            tagInfo = tagInfo,
                            tagContent = tagContent,
                            isButtonVisible = isReaderButtonVisible,
                            onCheckNfcClick = {
                                checkNfcAvailability { messageRes, actionRes, action ->
                                    coroutineScope.launch {
                                        val result = snackbarHostState!!.showSnackbar(
                                            message = getString(messageRes),
                                            actionText = if (actionRes != 0) getString(actionRes) else null
                                        )
                                        if (result == NotificationResult.CLICKED) {
                                            action?.invoke()
                                        }
                                    }
                                }
                            }
                        )
                    },
                    writeScreen = {
                        WriteCardScreen(
                            writeState = writeState,
                            writeStatusMessage = writeStatusMessage,
                            onWriteText = {
                                if (it.isBlank()) {
                                    writeStatusMessage = getString(R.string.error_text_blank)
                                    writeState = WriteState.FAILED
                                    return@WriteCardScreen
                                }
                                currentWriteMode = WriteMode.TEXT
                                inputText = it
                                writeState = WriteState.WAITING_FOR_CARD
                                writeStatusMessage = getString(R.string.status_tap_card)
                            },
                            onWriteUrl = {
                                if (it.isBlank()) {
                                    writeStatusMessage = getString(R.string.error_url_blank)
                                    writeState = WriteState.FAILED
                                    return@WriteCardScreen
                                }
                                currentWriteMode = WriteMode.URL
                                inputText = it
                                writeState = WriteState.WAITING_FOR_CARD
                                writeStatusMessage = getString(R.string.status_tap_card)
                            },
                            onWriteWifi = { ssid, password, encryption, auth ->
                                if (ssid.isBlank()) {
                                    writeStatusMessage = getString(R.string.error_wifi_ssid_blank)
                                    writeState = WriteState.FAILED
                                    return@WriteCardScreen
                                }
                                currentWriteMode = WriteMode.WIFI
                                wifiSsid = ssid
                                wifiPassword = password
                                wifiEncryption = encryption
                                wifiAuth = auth
                                writeState = WriteState.WAITING_FOR_CARD
                                writeStatusMessage = getString(R.string.status_tap_card)
                            },
                            onWriteBluetooth = { mac, name ->
                                if (mac.isBlank()) {
                                    writeStatusMessage = getString(R.string.error_bt_mac_blank)
                                    writeState = WriteState.FAILED
                                    return@WriteCardScreen
                                }
                                currentWriteMode = WriteMode.BLUETOOTH
                                btMac = mac
                                btName = name
                                writeState = WriteState.WAITING_FOR_CARD
                                writeStatusMessage = getString(R.string.status_tap_card)
                            },
                            onStartEmulation = { type, textInput, wifiSsid, wifiPassword, wifiEncryption, wifiAuth, btMac, btName ->
                                // 验证输入
                                when (type) {
                                    WriteDataType.TEXT, WriteDataType.URL -> {
                                        if (textInput.isBlank()) {
                                            writeStatusMessage = getString(R.string.error_content_blank)
                                            writeState = WriteState.FAILED
                                            return@WriteCardScreen
                                        }
                                        inputText = textInput
                                    }
                                    WriteDataType.WIFI -> {
                                        if (wifiSsid.isBlank()) {
                                            writeStatusMessage = getString(R.string.error_wifi_ssid_blank)
                                            writeState = WriteState.FAILED
                                            return@WriteCardScreen
                                        }
                                        this@MainActivity.wifiSsid = wifiSsid
                                        this@MainActivity.wifiPassword = wifiPassword
                                        this@MainActivity.wifiEncryption = wifiEncryption
                                        this@MainActivity.wifiAuth = wifiAuth
                                    }
                                    WriteDataType.BLUETOOTH -> {
                                        if (btMac.isBlank()) {
                                            writeStatusMessage = getString(R.string.error_bt_mac_blank)
                                            writeState = WriteState.FAILED
                                            return@WriteCardScreen
                                        }
                                        this@MainActivity.btMac = btMac
                                        this@MainActivity.btName = btName
                                    }
                                }
                                // 构建 NDEF 消息并存入 HCE 服务
                                val ndefMessage = when (type) {
                                    WriteDataType.TEXT -> createTextNdefMessage(textInput)
                                    WriteDataType.URL -> createUriNdefMessage(textInput)
                                    WriteDataType.WIFI -> createWifiNdefMessage(wifiSsid, wifiPassword, wifiEncryption, wifiAuth)
                                    WriteDataType.BLUETOOTH -> createBluetoothNdefMessage(btMac, btName)
                                }
                                // 写入 HCE 服务并同步落盘：进程被回收后服务重启仍能恢复数据
                                MyHostApduService.setEmulatedData(
                                    this@MainActivity,
                                    ndefMessage.toByteArray(),
                                    type.name
                                )
                                // 设置我们的 HCE 服务为首选，避免系统钱包抢夺
                                try {
                                    val ce = CardEmulation.getInstance(nfcAdapter)
                                    ce.setPreferredService(
                                        this@MainActivity,
                                        ComponentName(this@MainActivity, MyHostApduService::class.java)
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to set preferred HCE service", e)
                                }
                                writeState = WriteState.EMULATING
                            },
                            onCancelWrite = {
                                writeState = WriteState.IDLE
                                writeStatusMessage = ""
                                currentWriteMode = WriteMode.IDLE
                                MyHostApduService.setEmulatedData(this@MainActivity, null)
                                // 取消首选服务：setPreferredService(activity, null) 会抛 NPE，
                                // 必须使用 unsetPreferredService
                                try {
                                    CardEmulation.getInstance(nfcAdapter)
                                        .unsetPreferredService(this@MainActivity)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to unset preferred HCE service", e)
                                }
                            }
                        )
                        // 写入成功/失败后自动重置 + NFC 模式切换
                        LaunchedEffect(writeState) {
                            when (writeState) {
                                WriteState.SUCCESS, WriteState.FAILED -> {
                                    delay(1.seconds)
                                    writeState = WriteState.IDLE
                                    writeStatusMessage = ""
                                    currentWriteMode = WriteMode.IDLE
                                }
                                WriteState.EMULATING -> {
                                    nfcAdapter?.disableReaderMode(this@MainActivity)
                                }
                                WriteState.IDLE -> {
                                    nfcAdapter?.enableReaderMode(
                                        this@MainActivity, tagCallback,
                                        NfcAdapter.FLAG_READER_NFC_A or
                                        NfcAdapter.FLAG_READER_NFC_B or
                                        NfcAdapter.FLAG_READER_NFC_F,
                                        null
                                    )
                                }
                                else -> {}
                            }
                        }
                    },
                    p2pScreen = {
                        P2PScreen(
                            connectionState = p2pConnectionState,
                            receivedNearbyMessage = receivedNearbyMessage,
                            isBluetoothEnabled = isBluetoothEnabled,
                            isWiFiEnabled = isWiFiEnabled,
                            nearbyRadioWarning = nearbyRadioWarning,
                            onMessageChange = { messageToSend = it },
                            onEnableBluetooth = { openBluetoothSettings() },
                            onEnableWiFi = { openWifiSettings() },
                            onStartAdvertising = { startAdvertising() },
                            onStopAdvertising = { stopAdvertising() },
                            onStartDiscovery = { startDiscovery() },
                            onStopDiscovery = { stopDiscovery() },
                            onDisconnect = { disconnectFromEndpoint() },
                            onSendMessage = { message ->
                                if (currentEndpointId != null && p2pConnectionState == ConnectionState.CONNECTED) {
                                    sendNearbyMessage(currentEndpointId!!, message)
                                } else {
                                    showToast(getString(R.string.toast_not_connected))
                                }
                            },
                            hasPermissions = hasNearbyPermissions,
                            onRequestPermissions = { permLauncher.launch(nearbyPermissions) }
                        )
                    },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    // =======================================================================
    // NFC 读卡（来自 ReadCard.kt）
    // =======================================================================

    private fun checkNfcAvailability(
        showMessage: (Int, Int, (() -> Unit)?) -> Unit
    ): Boolean {
        return when {
            nfcAdapter == null -> {
                isReaderButtonVisible = true
                showMessage(R.string.msg_nfc_unavailable, R.string.button_exit) { finish() }
                false
            }
            !nfcAdapter!!.isEnabled -> {
                isReaderButtonVisible = true
                showMessage(R.string.msg_nfc_not_enabled, R.string.button_open_settings) {
                    startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }
                false
            }
            else -> {
                isReaderButtonVisible = false
                showMessage(R.string.msg_nfc_available, 0, null)
                true
            }
        }
    }



    private fun parseNdefMessages(messages: List<NdefMessage>): String {
        val result = StringBuilder()

        messages.forEach { message ->
            message.records.forEach { record ->
                result.append(when (record.tnf) {
                    NdefRecord.TNF_WELL_KNOWN -> parseWellKnownRecord(record)
                    NdefRecord.TNF_MIME_MEDIA -> parseMimeRecord(record)
                    NdefRecord.TNF_EXTERNAL_TYPE -> parseExternalRecord(record)
                    else -> getString(R.string.format_record_unknown, record.payload.toHexString()) + "\n"
                })
            }
        }

        return result.toString().trim()
    }

    private fun parseWellKnownRecord(record: NdefRecord): String {
        return when {
            record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                getString(R.string.format_record_text, parseTextRecord(record)) + "\n"
            }
            record.type.contentEquals(NdefRecord.RTD_URI) -> {
                getString(R.string.format_record_uri, parseUriRecord(record)) + "\n"
            }
            else -> getString(R.string.msg_unknown_well_known) + "\n"
        }
    }

    private fun parseMimeRecord(record: NdefRecord): String {
        return when (record.toMimeType()) {
            "application/vnd.wfa.wsc" -> {
                val wifiPayload = record.payload
                val wifiInfo = parseWifiRecord(wifiPayload)
                getString(R.string.format_record_wifi, wifiInfo)
            }
            "application/vnd.bluetooth.ep.oob" -> getString(R.string.format_record_bluetooth, parseBluetoothRecord(record))
            else -> getString(R.string.format_record_mime, record.toMimeType(), record.payload.toHexString()) + "\n"
        }
    }

    private fun parseExternalRecord(record: NdefRecord): String {
        return when (String(record.type)) {
            "android.com:pkg" -> getString(R.string.format_record_app, parseApplicationRecord(record)) + "\n"
            else -> getString(R.string.format_record_external, String(record.type)) + "\n"
        }
    }

    private fun parseTextRecord(record: NdefRecord): String {
        return try {
            val payload = record.payload
            val textEncoding = if ((payload[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"
            val languageCodeLength = payload[0].toInt() and 0x3F
            String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1,
                Charset.forName(textEncoding))
        } catch (e: Exception) {
            Log.w(TAG, "解析文本记录失败", e)
            getString(R.string.msg_parse_error)
        }
    }

    private fun parseUriRecord(record: NdefRecord): String {
        val prefix = URI_PREFIX_MAP[record.payload[0]] ?: ""
        return try {
            prefix + String(record.payload, 1, record.payload.size - 1, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "解析URI记录失败", e)
            getString(R.string.msg_invalid_uri)
        }
    }

    fun parseWifiRecord(payload: ByteArray): String {
        val sb = StringBuilder()
        var index = 0
        while (index + 4 <= payload.size) {
            val type = ((payload[index].toInt() and 0xFF) shl 8) or (payload[index + 1].toInt() and 0xFF)
            val length = ((payload[index + 2].toInt() and 0xFF) shl 8) or (payload[index + 3].toInt() and 0xFF)
            index += 4
            if (index + length > payload.size) break

            val data = payload.copyOfRange(index, index + length)
            index += length

            when (type) {
                0x1045 -> sb.appendLine(getString(R.string.format_ssid, String(data)))
                0x1027 -> sb.appendLine(getString(R.string.format_wifi_password, String(data)))
                0x1003 -> {
                    // WSC 属性 0x1003 = Authentication Type（认证类型）
                    if (data.size >= 2) {
                        val authType = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        sb.appendLine(getString(R.string.format_auth_type, getAuthTypeName(authType)))
                    } else {
                        sb.appendLine(getString(R.string.msg_auth_data_short))
                    }
                }
                0x100F -> {
                    // WSC 属性 0x100F = Encryption Type（加密类型）
                    if (data.size >= 2) {
                        val encryptionType = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        sb.appendLine(getString(R.string.format_encryption_type, getEncryptionTypeName(encryptionType)))
                    } else {
                        sb.appendLine(getString(R.string.msg_encryption_data_short))
                    }
                }
                0x1020 -> sb.appendLine(getString(R.string.format_mac_address, data.toMacAddress()))
                0x1026 -> {
                    val netTypeName = when (val netType = data[0].toInt() and 0xFF) {
                        0x00 -> getString(R.string.msg_unknown)
                        0x01 -> getString(R.string.msg_infrastructure)
                        0x02 -> getString(R.string.msg_independent)
                        else -> getString(R.string.format_reserved_type, netType.toString(16))
                    }
                    sb.appendLine(getString(R.string.format_network_type, netTypeName))
                }
                0x100E -> {
                    sb.append(parseWifiRecord(data))
                }
                else -> sb.appendLine(getString(R.string.format_unknown_field, type.toString(16), data.toHexString()))
            }
        }
        return sb.toString()
    }

    private fun parseBluetoothRecord(record: NdefRecord): String {
        val bytes = record.payload
        val hexDump = bytes.joinToString(" ") { "%02X".format(it) }

        if (record.toUri()?.scheme?.startsWith("bt") == true) {
            val uri = record.toUri()!!
            val mac = uri.host ?: "未知"
            val name = uri.path?.substringAfter("/")?.trim().orEmpty()
            return getString(R.string.format_bt_uri_record, mac, name, hexDump)
        } else {
            val bytes = record.payload
            Log.d("BluetoothTag", "原始数据: ${bytes.joinToString(" ") { "%02X".format(it) }}")

            if (bytes.size < 8) return getString(R.string.msg_invalid_bt_data)
            val mac = bytes.copyOfRange(2, 8).reversed().joinToString(":") { "%02X".format(it) }
            val name = if (bytes.size > 8) {
                String(bytes, 8, bytes.size - 8, Charsets.UTF_8).trim()
            } else {
                ""
            }
            return getString(R.string.format_bt_raw_record, mac, name)
        }
    }


    private fun parseApplicationRecord(record: NdefRecord): String {
        return try {
            String(record.payload, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "解析应用记录失败", e)
            getString(R.string.msg_unknown_app)
        }
    }

    // 扩展函数 (来自 ReadCard.kt)
    private fun ByteArray.toMacAddress(): String = joinToString(":") { "%02X".format(it) }
    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    /** WSC Authentication Type（属性 0x1003）取值 → 名称，取值见 Wi-Fi Simple Configuration 规范 */
    private fun getAuthTypeName(value: Int): String = when(value) {
        0x0001 -> getString(R.string.auth_open_system)
        0x0002 -> getString(R.string.auth_wpa_psk)
        0x0004 -> getString(R.string.auth_shared_key)
        0x0008 -> getString(R.string.auth_wpa_eap)
        0x0010 -> getString(R.string.auth_wpa2_eap)
        0x0020 -> getString(R.string.auth_wpa2_psk)
        0x0022 -> getString(R.string.auth_wpa_wpa2_psk)
        0x0040 -> getString(R.string.auth_wpa3_sae)
        0x0080 -> getString(R.string.auth_wpa3_eap)
        else -> getString(R.string.format_unknown_auth, value)
    }

    /** WSC Encryption Type（属性 0x100F）取值 → 名称，取值见 Wi-Fi Simple Configuration 规范 */
    private fun getEncryptionTypeName(value: Int): String = when(value) {
        0x0001 -> getString(R.string.enc_none)
        0x0002 -> getString(R.string.enc_wep)
        0x0004 -> getString(R.string.enc_tkip)
        0x0008 -> getString(R.string.enc_aes)
        0x000C -> getString(R.string.enc_aes_tkip)
        else -> getString(R.string.format_unknown_encryption, value)
    }


    // =======================================================================
    // NFC 写卡（来自 WriteCard.kt）
    // =======================================================================

    private fun handleWriteIntent(intent: Intent) {
        val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return

        if (currentWriteMode == WriteMode.IDLE) return // 非写入模式不处理

        // 检测到卡片，进入写入中状态
        writeState = WriteState.WRITING
        writeStatusMessage = getString(R.string.status_writing)

        try {
            val message: NdefMessage = when (currentWriteMode) {
                WriteMode.TEXT -> createTextNdefMessage(inputText)
                WriteMode.URL -> createUriNdefMessage(inputText)
                WriteMode.WIFI -> createWifiNdefMessage(wifiSsid, wifiPassword, wifiEncryption, wifiAuth)
                WriteMode.BLUETOOTH -> createBluetoothNdefMessage(btMac, btName)
                WriteMode.IDLE -> return
            }

            if (isNdefCompatible(tag)) {
                writeNdefMessage(tag, message)
            } else if (NdefFormatable.get(tag) != null) {
                formatAndWrite(tag, message)
            } else {
                throw IOException(getString(R.string.msg_tag_not_ndef))
            }
            writeState = WriteState.SUCCESS
            writeStatusMessage = getString(R.string.status_write_success)
        } catch(e: Exception) {
            writeState = WriteState.FAILED
            writeStatusMessage = getString(R.string.status_write_failed, e.message)
            Log.e("NFC", "写入错误", e)
        }
    }

    private fun createTextNdefMessage(text: String): NdefMessage {
        val lang = Locale.getDefault().language.toByteArray(StandardCharsets.US_ASCII)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(lang.size + 1 + textBytes.size)
        payload[0] = lang.size.toByte() // 状态字节：UTF-8 且语言代码长度
        System.arraycopy(lang, 0, payload, 1, lang.size)
        System.arraycopy(textBytes, 0, payload, 1 + lang.size, textBytes.size)
        val record = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
        return NdefMessage(record)
    }

    private fun createUriNdefMessage(uri: String): NdefMessage {
        val record = NdefRecord.createUri(uri)
        return NdefMessage(record)
    }

    // ---- WiFi NDEF (WSC / application/vnd.wfa.wsc) ----
    private fun createWifiNdefMessage(
        ssid: String, password: String,
        encryption: WifiEncryption, auth: WifiAuth
    ): NdefMessage {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        val encBytes = byteArrayOf(
            ((encryption.wscValue shr 8) and 0xFF).toByte(),
            (encryption.wscValue and 0xFF).toByte()
        )
        val authBytes = byteArrayOf(
            ((auth.wscValue shr 8) and 0xFF).toByte(),
            (auth.wscValue and 0xFF).toByte()
        )

        // TLV: Type(2) + Length(2) + Value
        val totalSize = (4 + ssidBytes.size) + (4 + pwdBytes.size) + (4 + 2) + (4 + 2)
        val payload = ByteArray(totalSize)
        var pos = 0

        fun writeTlv(type: Int, data: ByteArray) {
            payload[pos] = ((type shr 8) and 0xFF).toByte()
            payload[pos + 1] = (type and 0xFF).toByte()
            payload[pos + 2] = ((data.size shr 8) and 0xFF).toByte()
            payload[pos + 3] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, payload, pos + 4, data.size)
            pos += 4 + data.size
        }

        writeTlv(0x1045, ssidBytes)   // SSID
        writeTlv(0x1027, pwdBytes)    // Network Key
        writeTlv(0x100F, encBytes)    // Encryption Type
        writeTlv(0x1003, authBytes)   // Authentication Type

        val record = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            "application/vnd.wfa.wsc".toByteArray(StandardCharsets.US_ASCII),
            ByteArray(0),
            payload
        )
        return NdefMessage(record)
    }

    // ---- 蓝牙 NDEF (application/vnd.bluetooth.ep.oob) ----
    private fun createBluetoothNdefMessage(mac: String, name: String): NdefMessage {
        // 解析 MAC 地址为字节
        val macBytes = try {
            mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            // 如果格式不对，填零
            ByteArray(6)
        }.let { if (it.size < 6) it + ByteArray(6 - it.size) else it.copyOf(6) }

        val nameBytes = name.toByteArray(Charsets.UTF_8)

        // OOB 格式：2字节头 + 6字节 MAC（倒序）+ 可选名称
        val payload = ByteArray(2 + 6 + nameBytes.size)
        payload[0] = 0
        payload[1] = 0
        System.arraycopy(macBytes.reversedArray(), 0, payload, 2, 6)
        if (nameBytes.isNotEmpty()) {
            System.arraycopy(nameBytes, 0, payload, 8, nameBytes.size)
        }

        val record = NdefRecord(
            NdefRecord.TNF_MIME_MEDIA,
            "application/vnd.bluetooth.ep.oob".toByteArray(StandardCharsets.US_ASCII),
            ByteArray(0),
            payload
        )
        return NdefMessage(record)
    }

    private fun isNdefCompatible(tag: Tag): Boolean {
        return Ndef.get(tag) != null
    }

    private fun formatAndWrite(tag: Tag, message: NdefMessage) {
        NdefFormatable.get(tag)?.use { formatable ->
            formatable.connect()
            formatable.format(message)
        } ?: throw IOException("无法格式化标签")
    }

    private fun writeNdefMessage(tag: Tag, message: NdefMessage) {
        Ndef.get(tag)?.use { ndef ->
            ndef.connect()
            if (!ndef.isWritable) throw IOException("标签不可写")
            if (ndef.maxSize < message.toByteArray().size) throw IOException("内容超出标签容量")
            ndef.writeNdefMessage(message)
        } ?: throw IOException("标签不支持NDEF写入")
    }

    // =======================================================================
    // Nearby Connections P2P
    // =======================================================================

    // （此处省略原 MainActivity 中的 Nearby Connections 相关函数，
    //  如 connectionLifecycleCallback, payloadCallback, startAdvertising,
    //  stopAdvertising, startDiscovery, sendNearbyMessage, requestNearbyConnection
    //  这些函数保持不变）
    // ...
    // Nearby Connections 核心回调
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("Nearby", "连接被发起: $endpointId")
            p2pConnectionState = ConnectionState.CONNECTING
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            showToast(getString(R.string.toast_accepting_connection))
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("Nearby", "连接成功: $endpointId")
                    p2pConnectionState = ConnectionState.CONNECTED
                    currentEndpointId = endpointId
                    showToast(getString(R.string.toast_connection_success))
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("Nearby", "连接被拒绝: $endpointId")
                    p2pConnectionState = ConnectionState.DISCONNECTED
                    currentEndpointId = null
                    showToast(getString(R.string.toast_connection_rejected))
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.d("Nearby", "连接错误: $endpointId")
                    p2pConnectionState = ConnectionState.DISCONNECTED
                    currentEndpointId = null
                    showToast(getString(R.string.toast_connection_failed))
                }
                else -> {
                    Log.d("Nearby", "连接结果未知: ${result.status.statusCode}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("Nearby", "连接断开: $endpointId")
            p2pConnectionState = ConnectionState.DISCONNECTED
            currentEndpointId = null
            showSnackbar(getString(R.string.toast_connection_disconnected))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val message = String(bytes, StandardCharsets.UTF_8)
                receivedNearbyMessage = message
                Log.d("Nearby", "收到消息: $message")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // 您可以在此处理传输进度，例如显示进度条
            Log.d("Nearby", "数据传输更新: $update")
        }
    }

    // 按 Android 版本构建 Nearby 必需的运行时权限集合（minSdk = 34）
    private fun buildNearbyPermissions(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        // ACCESS_*_LOCATION 仅在 API 31 及以下需要（minSdk 34 已覆盖不到）；
        // Wi-Fi 相关能力自 API 32 起统一走 NEARBY_WIFI_DEVICES。
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }.toTypedArray()

    // 检查 Nearby 所需权限
    private fun checkNearbyPermissions(): Boolean =
        buildNearbyPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    // 读取当前蓝牙 / Wi-Fi 无线装置状态。
    private fun refreshNearbyRadioStatus() {
        isBluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        isWiFiEnabled = try {
            wifiManager?.wifiState == WifiManager.WIFI_STATE_ENABLED
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasNearbyRadioEnabled(): Boolean =
        isBluetoothEnabled || isWiFiEnabled

    private fun nearbyRadioWarningMessage(): String? = when {
        !isBluetoothEnabled && !isWiFiEnabled -> getString(R.string.p2p_text_radio_disabled)
        else -> null
    }

    // 2026 年底起 Nearby Connections 不再自动打开 Wi-Fi / 蓝牙，
    // 因此启动任何连接任务前都必须确认至少一个必要无线装置已开启。
    private fun ensureNearbyRadiosReady(): Boolean {
        refreshNearbyRadioStatus()
        if (!hasNearbyRadioEnabled()) {
            nearbyRadioWarning = nearbyRadioWarningMessage()
            showToast(nearbyRadioWarning ?: getString(R.string.toast_permission_required))
            return false
        }
        nearbyRadioWarning = null
        return true
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "无法打开蓝牙设置", e)
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "无法打开 Wi-Fi 设置", e)
        }
    }

    // 启动广告模式
    private fun startAdvertising() {
        if (!checkNearbyPermissions()) {
            pendingP2PAction = { startAdvertising() }
            requestNearbyPermissions?.invoke()
            return
        }
        if (!ensureNearbyRadiosReady()) return
        connectionsClient.stopAdvertising() // 防止 STATUS_ALREADY_ADVERTISING
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            getString(R.string.p2p_device_name),
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            p2pConnectionState = ConnectionState.ADVERTISING
            showToast(getString(R.string.toast_advertising_started))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showToast(getString(R.string.format_advertising_failed, e.message))
        }
    }

    // 停止广告模式
    private fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        p2pConnectionState = ConnectionState.DISCONNECTED
        showToast(getString(R.string.toast_advertising_stopped))
    }

    // 停止发现模式
    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        p2pConnectionState = ConnectionState.DISCONNECTED
    }

    // 启动发现模式
    private fun startDiscovery() {
        if (!checkNearbyPermissions()) {
            pendingP2PAction = { startDiscovery() }
            requestNearbyPermissions?.invoke()
            return
        }
        if (!ensureNearbyRadiosReady()) return
        connectionsClient.stopDiscovery() // 防止重复启动
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.d("Nearby", "发现端点: $endpointId")
                    // 在这里，您应该立即发起连接，因为您没有依赖NFC握手
                    requestNearbyConnection(endpointId)
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d("Nearby", "丢失端点: $endpointId")
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            p2pConnectionState = ConnectionState.DISCOVERING
            showToast(getString(R.string.toast_discovery_started))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showToast(getString(R.string.format_discovery_failed, e.message))
        }
    }

    // 发送Nearby Connections消息
    private fun sendNearbyMessage(endpointId: String, message: String) {
        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                showToast(getString(R.string.toast_message_sent))
                Log.d("Nearby", "消息发送成功")
            }
            .addOnFailureListener { e ->
                showToast(getString(R.string.format_message_send_failed, e.message))
                Log.e("Nearby", "消息发送失败", e)
            }
    }

    // 断开与当前端点的连接
    private fun disconnectFromEndpoint() {
        connectionsClient.stopAllEndpoints()
        p2pConnectionState = ConnectionState.DISCONNECTED
        currentEndpointId = null
    }

    // 发起Nearby Connections连接请求
    private fun requestNearbyConnection(endpointId: String) {
        val endpointName = getString(R.string.p2p_device_name) // 随便取个名字
        connectionsClient.requestConnection(
            endpointName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            showToast(getString(R.string.toast_connection_requested))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showToast(getString(R.string.format_connection_request_failed, e.message))
        }
    }


    // =======================================================================
    // Activity 生命周期
    // =======================================================================

    override fun onResume() {
        super.onResume()
        refreshNearbyRadioStatus()
        if (p2pConnectionState != ConnectionState.DISCONNECTED && !hasNearbyRadioEnabled()) {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            p2pConnectionState = ConnectionState.DISCONNECTED
            currentEndpointId = null
            nearbyRadioWarning = getString(R.string.p2p_text_radio_disabled)
            showToast(nearbyRadioWarning.orEmpty())
        }
        if (writeState == WriteState.EMULATING) return // 仅 HCE 被动模式
        nfcAdapter?.enableReaderMode(
            this, tagCallback,
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F,
            null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 记录卡模拟状态，Activity 重建时恢复，避免 reader mode 重新抢占
        outState.putString(STATE_WRITE_STATE, writeState.name)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        nfcAdapter?.let {
            try { CardEmulation.getInstance(it).unsetPreferredService(this) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAllEndpoints()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (currentWriteMode != WriteMode.IDLE) {
            handleWriteIntent(intent)
        }
    }

    // ---- ReaderMode 统一回调：读卡 + 写卡 ----

    private val tagCallback = NfcAdapter.ReaderCallback { tag ->
        if (currentWriteMode != WriteMode.IDLE) {
            handleWriteOnTag(tag)
            return@ReaderCallback
        }
        // 读卡：优先用自定义 AID 直连。
        // 小米 HyperOS 会把 NFC Forum T4T AID(D2760000850101) 写死在 NFC 控制器的路由表里指向 SE，
        // 标准 NDEF 路径会被系统"碰一碰"抢占，只有自定义 AID 才会路由到本应用的 HCE 服务。
        val hceResult = tryReadHceEmulatedCard(tag)
        if (hceResult != null) {
            tagInfo = hceResult.first
            tagContent = hceResult.second
            Log.i(TAG, "Read HCE via IsoDep (custom AID)")
            return@ReaderCallback
        }
        // 回退：标准 NDEF 读取（普通 NFC 标签）
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Log.w(TAG, "No NDEF tech and custom AID read returned nothing: $tag")
            return@ReaderCallback
        }
        try {
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()
            if (message != null) {
                tagInfo = getString(R.string.format_tag_info, tag.toString())
                tagContent = parseNdefMessages(listOf(message))
                Log.i(TAG, "Read NDEF via reader mode")
            } else {
                Log.w(TAG, "NDEF message is null: $tag")
            }
        } catch (e: Exception) {
            Log.w(TAG, "NDEF read failed", e)
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    /** 在 readerMode 回调中执行写卡操作 */
    private fun handleWriteOnTag(tag: Tag) {
        if (currentWriteMode == WriteMode.IDLE) return
        try {
            val message: NdefMessage = when (currentWriteMode) {
                WriteMode.TEXT -> createTextNdefMessage(inputText)
                WriteMode.URL -> createUriNdefMessage(inputText)
                WriteMode.WIFI -> createWifiNdefMessage(wifiSsid, wifiPassword, wifiEncryption, wifiAuth)
                WriteMode.BLUETOOTH -> createBluetoothNdefMessage(btMac, btName)
                WriteMode.IDLE -> return
            }
            writeState = WriteState.WRITING
            writeStatusMessage = getString(R.string.status_writing)
            if (isNdefCompatible(tag)) {
                writeNdefMessage(tag, message)
            } else if (NdefFormatable.get(tag) != null) {
                formatAndWrite(tag, message)
            } else {
                throw IOException(getString(R.string.msg_tag_not_ndef))
            }
            writeState = WriteState.SUCCESS
            writeStatusMessage = getString(R.string.status_write_success)
        } catch (e: Exception) {
            writeState = WriteState.FAILED
            writeStatusMessage = getString(R.string.status_write_failed, e.message)
            Log.e("NFC", "写入错误", e)
        }
    }

    /**
     * 通过 IsoDep + 私有 AID 读取对端 HCE 模拟卡。
     *
     * 不走标准 NDEF 通路的原因：小米 HyperOS 会把 T4T AID(D2760000850101) 写死在 NFC
     * 控制器的路由表里指向 SE，读到的会是系统"碰一碰"的内容而不是本应用的数据；
     * 私有 AID 未在 SE 路由表中，会被控制器按兜底规则交给 host。
     *
     * @return (tagInfo, tagContent)；对端不是本应用的模拟卡时返回 null
     */
    private fun tryReadHceEmulatedCard(tag: Tag): Pair<String, String>? {
        val isoDep = IsoDep.get(tag) ?: return null
        return try {
            isoDep.connect()
            isoDep.timeout = 1000

            // 首个 SELECT 用私有 AID：它在两台设备的控制器路由表里都有一条指向 host 的显式条目；
            // 而 T4T AID(D2760000850101) 可能同时存在一条指向 SE 的同名条目（红米即如此），
            // 若首个 SELECT 落到 SE，整条 ISO-DEP 会话就被绑定，后续 APDU 全部读不到。
            val privSelect = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00, 0x05,
                0xF0.toByte(), 0x12, 0x34, 0x56, 0x78
            )
            val privResp = isoDep.transceive(privSelect)
            if (!privResp.endsWithSuccess()) {
                Log.i(TAG, "HCE: SELECT private AID -> ${privResp.swString()}, fallback to T4T AID")
                val t4tSelect = byteArrayOf(
                    0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
                    0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
                )
                val t4tResp = isoDep.transceive(t4tSelect)
                if (!t4tResp.endsWithSuccess()) {
                    Log.i(TAG, "HCE: SELECT T4T AID -> ${t4tResp.swString()}, not this app's emulated card")
                    return null
                }
            }

            // 分块读回模拟数据：CMD_READ_EMULATED_DATA 的 P1P2 是偏移量，单块最多 CHUNK_SIZE 字节
            val buffer = ByteArrayOutputStream()
            var offset = 0
            while (offset <= MAX_EMULATED_BYTES) {
                val readCmd = byteArrayOf(
                    0x80.toByte(), 0x03,
                    ((offset shr 8) and 0xFF).toByte(),
                    (offset and 0xFF).toByte(),
                    0x00
                )
                val resp = isoDep.transceive(readCmd)
                if (!resp.endsWithSuccess()) {
                    Log.w(TAG, "HCE: read at offset=$offset failed, sw=${resp.swString()}")
                    return null
                }
                val payload = resp.copyOf(resp.size - 2)
                buffer.write(payload)
                // 不满一块说明已读到末尾
                if (payload.size < MyHostApduService.CHUNK_SIZE) break
                offset += payload.size
            }

            val ndefBytes = buffer.toByteArray()
            if (ndefBytes.isEmpty()) return null

            val message = NdefMessage(ndefBytes)
            val info = getString(R.string.format_tag_info, getString(R.string.msg_hce_tag))
            val content = parseNdefMessages(listOf(message))
            Log.i(TAG, "HCE card read, ${ndefBytes.size} bytes")
            Pair(info, content)
        } catch (e: Exception) {
            Log.w(TAG, "HCE read failed, fallback to NDEF", e)
            null
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    /** 响应是否以 SW=9000 结尾 */
    private fun ByteArray.endsWithSuccess(): Boolean =
        size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()

    /** 状态字字符串，用于判断响应究竟由哪一端给出 */
    private fun ByteArray.swString(): String =
        if (size >= 2) "%02X%02X".format(this[size - 2], this[size - 1]) else "n/a"

    private fun showSnackbar(message: String) {
        val host = snackbarHostState ?: return
        kotlinx.coroutines.MainScope().launch {
            host.showSnackbar(message)
        }
    }

    // 保留作为内部便捷方法（所有调用点已替换为 showSnackbar）
    private fun showToast(message: String) {
        showSnackbar(message)
    }
}

// 注意：MyHostApduService.kt 必须保持独立文件
