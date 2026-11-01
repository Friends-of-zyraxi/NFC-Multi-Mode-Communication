package io.github.zyraxi21.nfc.ui.theme

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.util.isValidApplicationPackageName
import io.github.zyraxi21.nfc.util.webRecordIntent
import io.github.zyraxi21.nfc.util.wifiRecordIntent

@Composable
internal fun RecordActionButtons(actions: List<RecordAction>, onAction: ((RecordAction) -> Unit)? = null) {
    if (actions.isEmpty()) return
    val execute = onAction ?: rememberRecordActionHandler()
    Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.s)) {
        actions.forEachIndexed { index, action ->
            val label = when (action) {
                is RecordAction.Web -> R.string.record_open_web
                is RecordAction.Application -> R.string.record_open_application
                is RecordAction.Bluetooth -> R.string.record_connect_bluetooth
                is RecordAction.Wifi -> if (action.needsManualSetup) R.string.record_wifi_manual else R.string.record_connect_wifi
            }
            FluentButton(text = stringResource(label), style = ButtonStyle.OutlinedButton,
                modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget).testTag("record-action-$index"),
                onClick = { execute(action) })
        }
    }
}

/** 只在用户点击后执行；权限与系统确认返回时继续本次操作。 */
@Composable
internal fun rememberRecordActionHandler(onMessage: ((String) -> Unit)? = null): (RecordAction) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notify by rememberUpdatedState(onMessage ?: { message: String -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() })
    var pendingMac by rememberSaveable { mutableStateOf<String?>(null) }
    fun message(id: Int) { notify(context.getString(id)) }
    fun safely(block: () -> Unit) {
        try { block() } catch (_: Exception) { message(R.string.record_action_unavailable) }
    }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            message(R.string.record_wifi_saved)
            safely { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
    }
    @SuppressLint("MissingPermission")
    fun pair(mac: String) {
        safely {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) { message(R.string.record_bluetooth_unavailable); return@safely }
            val device = adapter.getRemoteDevice(mac)
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                BluetoothDevice.BOND_BONDING -> message(R.string.record_bluetooth_pairing)
                else -> message(if (device.createBond()) R.string.record_bluetooth_pairing else R.string.record_bluetooth_failed)
            }
        }
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val mac = pendingMac
        pendingMac = null
        if (result.resultCode == Activity.RESULT_OK && mac != null) pair(mac)
    }
    @SuppressLint("MissingPermission")
    fun connectBluetooth(mac: String) {
        safely {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> message(R.string.record_bluetooth_unavailable)
                !adapter.isEnabled -> {
                    pendingMac = mac
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                else -> pair(mac)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mac = pendingMac
        pendingMac = null
        if (granted && mac != null) connectBluetooth(mac)
        else if (!granted) message(R.string.record_bluetooth_permission)
    }
    return { action ->
        safely {
            when (action) {
                is RecordAction.Web -> context.startActivity(webRecordIntent(action))
                is RecordAction.Application -> {
                    require(isValidApplicationPackageName(action.packageName))
                    try {
                        context.packageManager.getLaunchIntentSenderForPackage(action.packageName.trim())
                            .sendIntent(context, 0, null, null, null)
                    } catch (_: Exception) { message(R.string.record_application_unavailable) }
                }
                is RecordAction.Wifi -> {
                    if (action.needsManualSetup) {
                        clipboard.setText(AnnotatedString(action.password))
                        message(R.string.record_wifi_manual_hint)
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    } else {
                        wifiLauncher.launch(wifiRecordIntent(action))
                    }
                }
                is RecordAction.Bluetooth -> {
                    require(BluetoothAdapter.checkBluetoothAddress(action.mac))
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        connectBluetooth(action.mac)
                    } else {
                        pendingMac = action.mac
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            }
        }
    }
}
