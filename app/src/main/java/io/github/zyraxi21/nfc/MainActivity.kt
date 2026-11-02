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
import androidx.lifecycle.lifecycleScope
import com.microsoft.fluentui.tokenized.notification.NotificationResult
import com.microsoft.fluentui.tokenized.notification.SnackbarState
import com.microsoft.fluentui.theme.token.controlTokens.SnackbarStyle

import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.ConnectionState
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NFCReaderScreen
import io.github.zyraxi21.nfc.ui.theme.P2PScreen
import io.github.zyraxi21.nfc.ui.theme.WriteCardScreen
import io.github.zyraxi21.nfc.ui.theme.WriteState
import io.github.zyraxi21.nfc.ui.theme.NavigationItem
import io.github.zyraxi21.nfc.ui.theme.rememberRecordActionHandler
import io.github.zyraxi21.nfc.util.createNearbyPayload
import io.github.zyraxi21.nfc.util.parseNearbyPayload
import io.github.zyraxi21.nfc.util.MAX_NEARBY_NDEF_BYTES
import io.github.zyraxi21.nfc.util.NfcReadResult
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import io.github.zyraxi21.nfc.util.HceDataStore
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.cast.CastActivity
import io.github.zyraxi21.nfc.cast.CastSessionService
import io.github.zyraxi21.nfc.cast.RecordNearbyLink
import io.github.zyraxi21.nfc.cast.ConnectionMessages
import io.github.zyraxi21.nfc.util.InvalidWriteRecordException
import io.github.zyraxi21.nfc.util.MAX_EMULATED_NDEF_BYTES
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createNfcWriteMessage
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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
    private var readerResult by mutableStateOf<NfcReadResult?>(null)
    // 默认隐藏；自检发现 NFC 不可用/未启用时才显示检查按钮
    private var isReaderButtonVisible by mutableStateOf(false)

    // 一次提交只保存一条完整 NDEF 消息，标签写入与 HCE 使用同一个编码入口。
    @Volatile
    private var pendingWriteMessage: NdefMessage? = null
    var writeState by mutableStateOf(WriteState.IDLE)
    var writeStatusMessage by mutableStateOf("")

    private var snackbarHostState: SnackbarState? = null
    private val connectionMessages by lazy {
        ConnectionMessages(lifecycleScope) { message ->
            snackbarHostState?.showSnackbar(message, style = SnackbarStyle.Accent)
        }
    }

    // P2P通信相关状态变量
    private var p2pConnectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var receivedNearbyResult by mutableStateOf<NfcReadResult?>(null)
    private var currentEndpointId: String? = null
    private var screenSharingRequested by mutableStateOf(false)
    private val screenSharingInvitations = object : RecordNearbyLink.InvitationListener {
        override fun onScreenSharingRequested() { screenSharingRequested = true }
    }
    private val outgoingNearbyPayloads = mutableSetOf<Long>()
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
        private const val STATE_PENDING_NDEF = "pending_ndef"

        /** 从对端 HCE 卡读取 NDEF 时的防御性总长度上限（64 KiB） */
        private const val MAX_EMULATED_BYTES = 64 * 1024
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
        pendingWriteMessage = savedInstanceState?.getByteArray(STATE_PENDING_NDEF)
            ?.let { runCatching { NdefMessage(it) }.getOrNull() }
        val restoredState = savedInstanceState?.getString(STATE_WRITE_STATE)
        writeState = when {
            restoredState == WriteState.EMULATING.name && HceDataStore.load(this) != null -> WriteState.EMULATING
            pendingWriteMessage != null -> WriteState.WAITING_FOR_CARD
            else -> WriteState.IDLE
        }
        if (writeState == WriteState.WAITING_FOR_CARD) writeStatusMessage = getString(R.string.status_tap_card)

        // 初始化 Nearby Connections 客户端
        connectionsClient = Nearby.getConnectionsClient(this)
        RecordNearbyLink.bind(this, recordConnectionCallbacks, recordPayloadCallbacks, screenSharingInvitations)
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
                val systemRecordActionHandler = rememberRecordActionHandler { showSnackbar(it) }
                val recordActionHandler: (RecordAction) -> Unit = { action ->
                    if (action is RecordAction.ScreenShare) openScreenSharing(action.inviteBytes)
                    else systemRecordActionHandler(action)
                }

                // 有 NFC 的设备检查开关；无 NFC 的设备使用端对端入口，不弹全局退出提示。
                var nfcChecked by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!nfcChecked && nfcAdapter != null) {
                        nfcChecked = true
                        checkNfcAvailability { messageRes, actionRes, action ->
                            if (messageRes != R.string.msg_nfc_available) {
                                coroutineScope.launch {
                                    val result = snackbarHostState!!.showSnackbar(
                                        message = getString(messageRes),
                                        style = SnackbarStyle.Accent,
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
                            result = readerResult,
                            isButtonVisible = isReaderButtonVisible,
                            onRecordAction = recordActionHandler,
                            onCheckNfcClick = {
                                checkNfcAvailability { messageRes, actionRes, action ->
                                    coroutineScope.launch {
                                        val result = snackbarHostState!!.showSnackbar(
                                            message = getString(messageRes),
                                            style = SnackbarStyle.Accent,
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
                            onWriteRecords = ::armWriteRecords,
                            onStartEmulation = ::startRecordEmulation,
                            onOpenScreenShare = { openScreenSharing() },
                            onCancelWrite = {
                                writeState = WriteState.IDLE
                                writeStatusMessage = ""
                                pendingWriteMessage = null
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
                                    pendingWriteMessage = null
                                }
                                WriteState.EMULATING -> {
                                    nfcAdapter?.disableReaderMode(this@MainActivity)
                                }
                                WriteState.IDLE -> {
                                    if (!CastSessionService.ownsNearby) nfcAdapter?.enableReaderMode(
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
                            receivedResult = receivedNearbyResult,
                            isBluetoothEnabled = isBluetoothEnabled,
                            isWiFiEnabled = isWiFiEnabled,
                            nearbyRadioWarning = nearbyRadioWarning,
                            onEnableBluetooth = { openBluetoothSettings() },
                            onEnableWiFi = { openWifiSettings() },
                            onStartAdvertising = { startAdvertising() },
                            onStopAdvertising = { stopAdvertising() },
                            onStartDiscovery = { startDiscovery() },
                            onStopDiscovery = { stopDiscovery() },
                            onDisconnect = { disconnectFromEndpoint() },
                            onSendRecords = { records ->
                                if (CastSessionService.ownsNearby) {
                                    showSnackbar(getString(R.string.cast_error_end_existing))
                                } else if (currentEndpointId != null && p2pConnectionState == ConnectionState.CONNECTED) {
                                    sendNearbyRecords(currentEndpointId!!, records)
                                } else {
                                    showToast(getString(R.string.toast_not_connected))
                                }
                            },
                            hasPermissions = hasNearbyPermissions,
                            onRequestPermissions = { permLauncher.launch(nearbyPermissions) },
                            onRecordAction = recordActionHandler,
                            onOpenScreenShare = ::openConnectedScreenSharing,
                            screenSharingRequested = screenSharingRequested,
                        )
                    },
                    snackbarHostState = snackbarHostState,
                    initialItem = if (nfcAdapter == null) NavigationItem.P2P else NavigationItem.READ,
                )
            }
        }
        handleReadIntent(this.intent)
    }

    // =======================================================================
    // NFC 读卡（来自 ReadCard.kt）
    // =======================================================================

    /** 前台分发或冷启动携带的 NDEF 也仅解析展示，不执行记录中的应用或 URI。 */
    @Suppress("DEPRECATION") // 兼容仍通过 ACTION_TAG_DISCOVERED 分发标签的系统
    private fun handleReadIntent(intent: Intent) {
        if (intent.action !in listOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED
            )) return
        val messages = intent.getParcelableArrayExtra(
            NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java
        )?.toList().orEmpty()
        val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (messages.isEmpty() && tag == null) return
        displayReadResult(messages, tag)
    }

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



    private fun displayReadResult(
        messages: List<NdefMessage>,
        tag: Tag?,
        isEmulated: Boolean = false,
        noticeResId: Int? = null,
    ) {
        val result = parseNfcReadResult(this, messages, tag, isEmulated, noticeResId)
        // ReaderCallback 在 NFC 工作线程调用；一次替换完整结果，避免标签信息与内容错配。
        runOnUiThread { readerResult = result }
    }

    // =======================================================================
    // NFC 写卡（来自 WriteCard.kt）
    // =======================================================================

    private fun prepareWriteMessage(records: List<NfcWriteRecord>): NdefMessage? {
        if (records.isEmpty()) {
            writeStatusMessage = getString(R.string.write_empty_records)
            writeState = WriteState.FAILED
            return null
        }
        return try {
            createNfcWriteMessage(records)
        } catch (e: InvalidWriteRecordException) {
            writeStatusMessage = getString(R.string.write_invalid_record, e.recordIndex + 1, getString(e.errorResId))
            writeState = WriteState.FAILED
            null
        } catch (e: Exception) {
            writeStatusMessage = getString(R.string.status_write_failed, e.message)
            writeState = WriteState.FAILED
            null
        }
    }

    private fun armWriteRecords(records: List<NfcWriteRecord>) {
        if (CastSessionService.ownsNearby) { showSnackbar(getString(R.string.cast_error_end_existing)); return }
        if (writeState != WriteState.IDLE) return
        val message = prepareWriteMessage(records) ?: return
        pendingWriteMessage = message
        writeStatusMessage = getString(R.string.status_tap_records, records.size, message.byteArrayLength)
        writeState = WriteState.WAITING_FOR_CARD
    }

    private fun startRecordEmulation(records: List<NfcWriteRecord>) {
        if (CastSessionService.ownsNearby) { showSnackbar(getString(R.string.cast_error_end_existing)); return }
        if (writeState != WriteState.IDLE) return
        val message = prepareWriteMessage(records) ?: return
        if (message.byteArrayLength > MAX_EMULATED_NDEF_BYTES) {
            writeStatusMessage = getString(R.string.write_emulation_too_large, MAX_EMULATED_NDEF_BYTES)
            writeState = WriteState.FAILED
            return
        }
        pendingWriteMessage = null
        MyHostApduService.setEmulatedData(this, message.toByteArray(),
            if (records.size == 1) records.single().type.name else "MULTI")
        try {
            CardEmulation.getInstance(nfcAdapter).setPreferredService(
                this, ComponentName(this, MyHostApduService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set preferred HCE service", e)
        }
        writeStatusMessage = getString(R.string.write_records_summary, records.size, message.byteArrayLength)
        writeState = WriteState.EMULATING
    }

    private fun handleWriteIntent(intent: Intent) {
        val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        handleWriteOnTag(tag)
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
            if (ndef.maxSize < message.byteArrayLength) {
                throw IOException(getString(R.string.write_tag_capacity_error, message.byteArrayLength, ndef.maxSize))
            }
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
    private val connectionLifecycleCallback get() = RecordNearbyLink.lifecycle

    private val recordConnectionCallbacks = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("Nearby", "连接被发起: $endpointId")
            p2pConnectionState = ConnectionState.CONNECTING
            connectionsClient.acceptConnection(endpointId, RecordNearbyLink.payloads)
            showConnectionSnackbar(getString(R.string.toast_accepting_connection))
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("Nearby", "连接成功: $endpointId")
                    p2pConnectionState = ConnectionState.CONNECTED
                    currentEndpointId = endpointId
                    receivedNearbyResult = null
                    screenSharingRequested = false
                    showConnectionSnackbar(getString(R.string.toast_connection_success))
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("Nearby", "连接被拒绝: $endpointId")
                    p2pConnectionState = ConnectionState.DISCONNECTED
                    currentEndpointId = null
                    showConnectionSnackbar(getString(R.string.toast_connection_rejected))
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.d("Nearby", "连接错误: $endpointId")
                    p2pConnectionState = ConnectionState.DISCONNECTED
                    currentEndpointId = null
                    showConnectionSnackbar(getString(R.string.toast_connection_failed))
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
            outgoingNearbyPayloads.clear()
            screenSharingRequested = false
            showConnectionSnackbar(getString(R.string.toast_connection_disconnected))
        }
    }

    private val recordPayloadCallbacks = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val result = parseNearbyPayload(this@MainActivity, bytes)
                runOnUiThread {
                    if (currentEndpointId == endpointId) receivedNearbyResult = result
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            runOnUiThread {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> if (outgoingNearbyPayloads.remove(update.payloadId)) {
                        showConnectionSnackbar(getString(R.string.toast_message_sent))
                    }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                        if (outgoingNearbyPayloads.remove(update.payloadId)) showConnectionSnackbar(getString(R.string.p2p_transfer_failed))
                }
            }
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
        if (CastSessionService.ownsNearby) {
            showSnackbar(getString(R.string.cast_error_end_existing))
            return
        }
        if (!checkNearbyPermissions()) {
            pendingP2PAction = { startAdvertising() }
            requestNearbyPermissions?.invoke()
            return
        }
        if (!ensureNearbyRadiosReady()) return
        connectionsClient.stopAdvertising() // 防止 STATUS_ALREADY_ADVERTISING
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        connectionsClient.startAdvertising(
            getString(R.string.p2p_device_name),
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            p2pConnectionState = ConnectionState.ADVERTISING
            showConnectionSnackbar(getString(R.string.toast_advertising_started))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showConnectionSnackbar(getString(R.string.format_advertising_failed, e.message))
        }
    }

    // 停止广告模式
    private fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        p2pConnectionState = ConnectionState.DISCONNECTED
        showConnectionSnackbar(getString(R.string.toast_advertising_stopped))
    }

    // 停止发现模式
    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        p2pConnectionState = ConnectionState.DISCONNECTED
    }

    // 启动发现模式
    private fun startDiscovery() {
        if (CastSessionService.ownsNearby) {
            showSnackbar(getString(R.string.cast_error_end_existing))
            return
        }
        if (!checkNearbyPermissions()) {
            pendingP2PAction = { startDiscovery() }
            requestNearbyPermissions?.invoke()
            return
        }
        if (!ensureNearbyRadiosReady()) return
        connectionsClient.stopDiscovery() // 防止重复启动
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
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
            showConnectionSnackbar(getString(R.string.toast_discovery_started))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showConnectionSnackbar(getString(R.string.format_discovery_failed, e.message))
        }
    }

    // 发送Nearby Connections消息
    private fun sendNearbyRecords(endpointId: String, records: List<NfcWriteRecord>) {
        val bytes = try {
            createNearbyPayload(records)
        } catch (error: InvalidWriteRecordException) {
            showToast(getString(error.errorResId))
            return
        } catch (_: Exception) {
            showToast(getString(R.string.p2p_payload_too_large, MAX_NEARBY_NDEF_BYTES))
            return
        }
        val payload = Payload.fromBytes(bytes)
        outgoingNearbyPayloads += payload.id
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d("Nearby", "记录消息已提交发送")
            }
            .addOnFailureListener { e ->
                outgoingNearbyPayloads.remove(payload.id)
                showConnectionSnackbar(getString(R.string.format_message_send_failed, e.message))
                Log.e("Nearby", "消息发送失败", e)
            }
    }

    // 断开与当前端点的连接
    private fun disconnectFromEndpoint() {
        RecordNearbyLink.reset()
        connectionsClient.stopAllEndpoints()
        p2pConnectionState = ConnectionState.DISCONNECTED
        currentEndpointId = null
        outgoingNearbyPayloads.clear()
    }

    // 发起Nearby Connections连接请求
    private fun requestNearbyConnection(endpointId: String) {
        val endpointName = getString(R.string.p2p_device_name) // 随便取个名字
        connectionsClient.requestConnection(
            endpointName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            showConnectionSnackbar(getString(R.string.toast_connection_requested))
        }.addOnFailureListener { e ->
            p2pConnectionState = ConnectionState.DISCONNECTED
            showConnectionSnackbar(getString(R.string.format_connection_request_failed, e.message))
        }
    }


    // =======================================================================
    // Activity 生命周期
    // =======================================================================

    private fun openScreenSharing(inviteBytes: ByteArray? = null) {
        connectionMessages.pause()
        if (!CastSessionService.ownsNearby) {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            disconnectFromEndpoint()
        }
        startActivity(Intent(this, CastActivity::class.java).apply {
            inviteBytes?.let { putExtra(CastActivity.EXTRA_INVITE, it) }
        })
    }

    private fun openConnectedScreenSharing() {
        if (RecordNearbyLink.peer?.endpoint != currentEndpointId || currentEndpointId == null) {
            showSnackbar(getString(R.string.cast_error_connect_p2p_first))
            return
        }
        screenSharingRequested = false
        connectionMessages.pause()
        RecordNearbyLink.reserveForCast()
        startActivity(Intent(this, CastActivity::class.java).putExtra(CastActivity.EXTRA_EXISTING_CONNECTION, true))
    }

    override fun onResume() {
        super.onResume()
        connectionMessages.resume()
        val recordPeer = RecordNearbyLink.peer
        if (recordPeer != null) {
            currentEndpointId = recordPeer.endpoint
            p2pConnectionState = ConnectionState.CONNECTED
        } else if (p2pConnectionState == ConnectionState.CONNECTED) {
            currentEndpointId = null
            p2pConnectionState = ConnectionState.DISCONNECTED
        }
        if (CastSessionService.ownsNearby) {
            if (recordPeer == null) {
                p2pConnectionState = ConnectionState.DISCONNECTED
                currentEndpointId = null
                outgoingNearbyPayloads.clear()
            }
            return // 等待邀请时保持被动 HCE，避免主界面的 ReaderMode 抢占卡模拟。
        }
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
        pendingWriteMessage?.let { outState.putByteArray(STATE_PENDING_NDEF, it.toByteArray()) }
    }

    override fun onPause() {
        connectionMessages.pause()
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        nfcAdapter?.let {
            try { CardEmulation.getInstance(it).unsetPreferredService(this) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!CastSessionService.ownsNearby && !RecordNearbyLink.handoffPending) {
            RecordNearbyLink.reset(); connectionsClient.stopAllEndpoints()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (pendingWriteMessage != null) {
            handleWriteIntent(intent)
        } else if (writeState == WriteState.IDLE) {
            handleReadIntent(intent)
        }
    }

    // ---- ReaderMode 统一回调：读卡 + 写卡 ----

    private val tagCallback = NfcAdapter.ReaderCallback { tag ->
        if (pendingWriteMessage != null) {
            handleWriteOnTag(tag)
            return@ReaderCallback
        }
        if (writeState != WriteState.IDLE) return@ReaderCallback
        // Reader Mode 接管标签，URI、Wi-Fi、蓝牙和 AAR 均仅解析展示，不触发系统分发。
        // 读卡：优先用自定义 AID 直连。
        // 小米 HyperOS 会把 NFC Forum T4T AID(D2760000850101) 写死在 NFC 控制器的路由表里指向 SE，
        // 标准 NDEF 路径会被系统"碰一碰"抢占，只有自定义 AID 才会路由到本应用的 HCE 服务。
        val hceResult = tryReadHceEmulatedCard(tag)
        if (hceResult != null) {
            displayReadResult(listOf(hceResult), tag, isEmulated = true)
            Log.i(TAG, "Read HCE via IsoDep (custom AID)")
            return@ReaderCallback
        }
        // 回退：标准 NDEF 读取（普通 NFC 标签）
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Log.w(TAG, "No NDEF tech and custom AID read returned nothing: $tag")
            displayReadResult(emptyList(), tag)
            return@ReaderCallback
        }
        try {
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()
            if (message != null) {
                displayReadResult(listOf(message), tag)
                Log.i(TAG, "Read NDEF via reader mode")
            } else {
                Log.w(TAG, "NDEF message is null: $tag")
                displayReadResult(emptyList(), tag)
            }
        } catch (e: Exception) {
            Log.w(TAG, "NDEF read failed", e)
            displayReadResult(emptyList(), tag, noticeResId = R.string.reader_read_failed)
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    /** 在 readerMode 回调中执行写卡操作 */
    private fun handleWriteOnTag(tag: Tag) {
        val message = pendingWriteMessage ?: return
        if (writeState != WriteState.WAITING_FOR_CARD) return
        pendingWriteMessage = null
        writeState = WriteState.WRITING
        writeStatusMessage = getString(R.string.status_writing)
        try {
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
            Log.e(TAG, "写入错误", e)
        }
    }

    /**
     * 通过 IsoDep + 私有 AID 读取对端 HCE 模拟卡。
     *
     * 不走标准 NDEF 通路的原因：小米 HyperOS 会把 T4T AID(D2760000850101) 写死在 NFC
     * 控制器的路由表里指向 SE，读到的会是系统"碰一碰"的内容而不是本应用的数据；
     * 私有 AID 未在 SE 路由表中，会被控制器按兜底规则交给 host。
     *
     * @return 对端的 NDEF 消息；对端不是本应用的模拟卡时返回 null
     */
    private fun tryReadHceEmulatedCard(tag: Tag): NdefMessage? {
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
            Log.i(TAG, "HCE card read, ${ndefBytes.size} bytes")
            message
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
        lifecycleScope.launch {
            host.showSnackbar(message, style = SnackbarStyle.Accent)
        }
    }

    private fun showConnectionSnackbar(message: String) {
        if (CastSessionService.ownsNearby || RecordNearbyLink.handoffPending) return
        connectionMessages.show(message)
    }

    // 保留作为内部便捷方法（所有调用点已替换为 showSnackbar）
    private fun showToast(message: String) {
        showSnackbar(message)
    }
}

// 注意：MyHostApduService.kt 必须保持独立文件
