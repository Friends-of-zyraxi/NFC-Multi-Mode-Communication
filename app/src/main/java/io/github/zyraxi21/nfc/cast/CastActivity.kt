package io.github.zyraxi21.nfc.cast

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.zyraxi21.nfc.MyHostApduService
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import java.util.concurrent.atomic.AtomicBoolean

class CastActivity : ComponentActivity() {
    private var service by mutableStateOf<CastSessionService?>(null)
    private var scanning by mutableStateOf(false)
    private var localMessage by mutableStateOf<String?>(null)
    private var receivedInvite: CastInvite? = null
    private var useExistingConnection = false
    private var pendingStart: (() -> Unit)? = null
    private val reading = AtomicBoolean(false)
    private var adapter: NfcAdapter? = null
    private var bound = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingStart; pendingStart = null
        if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else localMessage = "需要附近设备权限才能建立投屏连接，请授权后重试"
    }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            service?.startCapture(result.resultCode, result.data!!, bounds.width(), bounds.height())
        } else service?.stopVideo("已取消录屏授权，可重新选择投屏方向")
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val session = (binder as CastSessionService.LocalBinder).service
            service = session
            if (session.state.value.stage == CastStage.IDLE) {
                if (useExistingConnection) withNearbyPermissions { session.useExistingConnection() }
                else if (receivedInvite != null) start(false)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        useExistingConnection = intent.getBooleanExtra(EXTRA_EXISTING_CONNECTION, false)
        if (useExistingConnection) RecordNearbyLink.reserveForCast()
        // 端对端入口保留现有连接，NFC 入口单独建立连接。
        if (!useExistingConnection && !CastSessionService.ownsNearby) com.google.android.gms.nearby.Nearby.getConnectionsClient(this).apply {
            RecordNearbyLink.reset()
            stopAdvertising(); stopDiscovery(); stopAllEndpoints()
        }
        adapter = NfcAdapter.getDefaultAdapter(this)
        receivedInvite = (savedInstanceState?.getByteArray(EXTRA_INVITE) ?: intent.getByteArrayExtra(EXTRA_INVITE))
            ?.let { runCatching { CastInvite.decode(it) }.onFailure { localMessage = "邀请已失效，请重新碰一碰" }.getOrNull() }
        bound = bindService(Intent(this, CastSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            MyApplicationTheme {
                val session = service
                val state = session?.state?.collectAsStateWithLifecycle()?.value ?: CastUiState()
                LaunchedEffect(state.stage, scanning) {
                    updateNfc()
                    if (state.stage == CastStage.RECEIVING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler { finishSession() }
                CastScreen(state, scanning, localMessage,
                    onHost = { start(true) },
                    onScan = {
                        receivedInvite = null
                        if (checkNfc(false)) { localMessage = null; scanning = true; updateNfc() }
                    },
                    onConnectInvite = receivedInvite?.let { { start(false) } },
                    onCancelScan = { scanning = false; updateNfc() },
                    onRequestDirection = { session?.requestDirection(it) },
                    onAccept = { session?.acceptDirection() },
                    onReject = { session?.stopVideo("已拒绝本次共享") },
                    onCapture = {
                        try { capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
                        catch (_: Exception) { session?.stopVideo("无法打开系统录屏授权，请重试") }
                    },
                    onStopVideo = { session?.stopVideo("本次共享已结束") },
                    onEnd = { session?.end(); scanning = false; receivedInvite = null; updateNfc() },
                    onBack = ::finishSession,
                    onHome = { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) },
                    onSurface = { session?.attachSurface(it) },
                    onFullscreen = ::setFullscreen,
                    onNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    onWirelessSettings = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                    onConfirmCode = { session?.confirmConnectionCode() },
                )
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXISTING_CONNECTION, false)) {
            if (service?.state?.value?.stage == CastStage.IDLE) withNearbyPermissions { service?.useExistingConnection() }
            return
        }
        val bytes = intent.getByteArrayExtra(EXTRA_INVITE) ?: return
        val invite = runCatching { CastInvite.decode(bytes) }.getOrElse {
            localMessage = "邀请已失效，请重新碰一碰"; return
        }
        receivedInvite = invite
        if (service?.state?.value?.stage == CastStage.IDLE) start(false)
        else localMessage = "已有屏幕共享连接，请先断开再加入新邀请"
    }

    private fun start(host: Boolean) {
        val session = service ?: return
        if (session.state.value.stage != CastStage.IDLE) return
        if (host && !checkNfc(true)) return
        val invite = receivedInvite
        if (!host && (invite == null || !invite.valid())) { localMessage = "邀请已失效，请重新碰一碰"; return }
        val action = {
            scanning = false; localMessage = null
            session.begin(host, invite); updateNfc()
        }
        withNearbyPermissions(action)
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val needed = requiredPermissions().toMutableList()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingStart = action; permissions.launch(needed.toTypedArray())
        } else action()
    }

    private fun checkNfc(host: Boolean): Boolean {
        if (adapter == null || (host && !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))) {
            localMessage = if (host) "本机不支持 NFC 卡模拟，请用另一台设备生成邀请" else "本机不支持 NFC 读取"
            return false
        }
        if (adapter?.isEnabled != true) { localMessage = "请先打开 NFC，再重试"; return false }
        return true
    }

    private fun updateNfc() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val nfc = adapter ?: return
        runCatching {
            nfc.disableReaderMode(this)
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                CardEmulation.getInstance(nfc).unsetPreferredService(this)
            }
            if (scanning) nfc.enableReaderMode(this, { tag ->
                if (reading.compareAndSet(false, true)) {
                    try {
                        val invite = readCastInvite(tag)
                        runOnUiThread { if (scanning) { receivedInvite = invite; scanning = false; start(false) } }
                    } catch (_: Exception) {
                        runOnUiThread { localMessage = "未读到有效邀请，请让对方生成屏幕共享邀请后再靠近" }
                    } finally { reading.set(false) }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B, null)
            else if (service?.state?.value?.stage == CastStage.ADVERTISING) {
                CardEmulation.getInstance(nfc).setPreferredService(this, ComponentName(this, MyHostApduService::class.java))
            }
        }.onFailure { localMessage = "NFC 暂不可用，请检查开关后重试" }
    }

    private fun setFullscreen(full: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (full) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun finishSession() { service?.end(); finish() }
    override fun onPostResume() { super.onPostResume(); window.decorView.post { updateNfc() } }
    override fun onPause() {
        runCatching { adapter?.disableReaderMode(this); adapter?.let { CardEmulation.getInstance(it).unsetPreferredService(this) } }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        receivedInvite?.let { outState.putByteArray(EXTRA_INVITE, it.encode()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        pendingStart = null
        if (isFinishing && useExistingConnection) RecordNearbyLink.cancelReservation()
        if (isFinishing) service?.end()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
    companion object {
        const val EXTRA_INVITE = "cast.invite"
        const val EXTRA_EXISTING_CONNECTION = "cast.existing.connection"
    }
}
