package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.NfcReadResult
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.util.copyText

enum class ConnectionState { DISCONNECTED, ADVERTISING, DISCOVERING, CONNECTING, CONNECTED }

@Composable
fun P2PScreen(
    connectionState: ConnectionState,
    receivedResult: NfcReadResult?,
    isBluetoothEnabled: Boolean,
    isWiFiEnabled: Boolean,
    nearbyRadioWarning: String?,
    onEnableBluetooth: () -> Unit,
    onEnableWiFi: () -> Unit,
    onStartAdvertising: () -> Unit,
    onStopAdvertising: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDisconnect: () -> Unit,
    onSendRecords: (List<NfcWriteRecord>) -> Unit,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onRecordAction: ((RecordAction) -> Unit)? = null,
    onOpenScreenShare: (() -> Unit)? = null,
    screenSharingRequested: Boolean = false,
) {
    val editorState = rememberSaveableStateHolder()
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasPermissions) {
        if (!hasPermissions && !permissionRequested) {
            permissionRequested = true
            onRequestPermissions()
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FluentSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasPermissions) {
                FluentText(stringResource(R.string.p2p_text_permission_required), FluentTextStyle.Body1)
                FluentButton(text = stringResource(R.string.button_grant_permission), onClick = onRequestPermissions)
            }
            nearbyRadioWarning?.let {
                FluentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(FluentSpacing.mPlus), verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                        FluentText(it, FluentTextStyle.Body2, color = AppTheme.danger)
                        if (!isBluetoothEnabled) FluentButton(text = stringResource(R.string.p2p_button_enable_bluetooth), onClick = onEnableBluetooth)
                        if (!isWiFiEnabled) FluentButton(text = stringResource(R.string.p2p_button_enable_wifi), onClick = onEnableWiFi)
                    }
                }
            }
            if (hasPermissions) when (connectionState) {
                ConnectionState.DISCONNECTED -> {
                    FluentText(stringResource(R.string.p2p_label_select_mode), FluentTextStyle.Body1Strong)
                    Row(horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                        FluentButton(text = stringResource(R.string.p2p_button_discover), onClick = onStartDiscovery,
                            modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget))
                        FluentButton(text = stringResource(R.string.p2p_button_advertise), onClick = onStartAdvertising,
                            modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget))
                    }
                }
                ConnectionState.CONNECTED -> {
                    FluentText(stringResource(R.string.p2p_text_connected), FluentTextStyle.Body2Strong, color = AppTheme.success)
                    if (screenSharingRequested) FluentText(stringResource(R.string.cast_p2p_invited),
                        FluentTextStyle.Body2, color = AppTheme.brand)
                    onOpenScreenShare?.let { open ->
                        FluentButton(text = if (screenSharingRequested) stringResource(R.string.cast_p2p_accept) else stringResource(R.string.cast_p2p_enter), onClick = open,
                            modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget).testTag("p2p-screen-sharing"))
                    }
                    FluentButton(text = stringResource(R.string.p2p_button_disconnect), onClick = onDisconnect,
                        style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.testTag("p2p-connecting-progress"))
                    FluentText(stringResource(when (connectionState) {
                        ConnectionState.ADVERTISING -> R.string.p2p_text_waiting_reader
                        ConnectionState.DISCOVERING -> R.string.p2p_text_approach_device
                        else -> R.string.p2p_text_connecting
                    }), FluentTextStyle.Body2, color = AppTheme.brand)
                    if (connectionState == ConnectionState.ADVERTISING) {
                        FluentButton(text = stringResource(R.string.p2p_button_stop_advertise), onClick = onStopAdvertising)
                    }
                    if (connectionState == ConnectionState.DISCOVERING) {
                        FluentButton(text = stringResource(R.string.p2p_button_stop_discovery), onClick = onStopDiscovery)
                    }
                }
            }
        }
    }
    if (connectionState == ConnectionState.CONNECTED && hasPermissions) {
        editorState.SaveableStateProvider("records") {
            RecordEditorScreen(WriteState.IDLE, "", onSendRecords, nearbyMode = true,
                receivedResult = receivedResult, onRecordAction = onRecordAction, headerContent = controls)
        }
    } else if (LocalWideLayout.current && receivedResult == null) {
        CenteredStatusPage(Modifier.testTag("p2p-status")) {
            FluentText(stringResource(R.string.p2p_title), FluentTextStyle.Title3)
            controls()
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("p2p-status"),
            contentPadding = PaddingValues(horizontal = LocalPageGutter.current, vertical = FluentSpacing.mPlus),
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
        ) {
            item { FluentText(stringResource(R.string.p2p_title), FluentTextStyle.Title3) }
            item { controls() }
            if (receivedResult != null) {
                item { NearbyReceivedHeading(receivedResult) }
                itemsIndexed(receivedResult.records) { index, record -> ReaderRecordCard(record, index, onRecordAction) }
            }
        }
    }
}

@Composable
internal fun NearbyReceivedHeading(result: NfcReadResult?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.s)) {
        FluentText(stringResource(R.string.p2p_label_received), FluentTextStyle.Body1Strong)
        if (result == null) {
            FluentText(stringResource(R.string.p2p_placeholder_waiting), FluentTextStyle.Body2, color = AppTheme.textHint)
        } else {
            FluentText(stringResource(R.string.p2p_received_summary, result.records.size, result.messageBytes),
                FluentTextStyle.Caption1, color = AppTheme.textSecondary)
            FluentButton(text = stringResource(R.string.reader_copy_all), style = ButtonStyle.TextButton,
                onClick = { clipboard.setText(AnnotatedString(result.copyText(context))) })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewP2PScreen() {
    MyApplicationTheme {
        P2PScreen(ConnectionState.DISCONNECTED, null, true, true, null,
            {}, {}, {}, {}, {}, {}, {}, {}, true, {})
    }
}

@Preview(name = "端对端等待 横屏", showBackground = true, widthDp = 850, heightDp = 360)
@Preview(name = "端对端等待 平板", showBackground = true, widthDp = 1280, heightDp = 800)
@Preview(name = "端对端等待 横屏深色", showBackground = true, widthDp = 850, heightDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewWideP2PStatus() {
    MyApplicationTheme(dynamicColor = false) {
        CompositionLocalProvider(LocalWideLayout provides true) {
            P2PScreen(ConnectionState.DISCONNECTED, null, true, true, null,
                {}, {}, {}, {}, {}, {}, {}, {}, true, {})
        }
    }
}

internal class ScreenSharingRequestPreviewProvider : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@Preview(name = "端对端投屏入口 浅色", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "端对端投屏入口 深色", showBackground = true, widthDp = 393, heightDp = 852,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewP2PScreenSharing(@PreviewParameter(ScreenSharingRequestPreviewProvider::class) requested: Boolean) {
    MyApplicationTheme(dynamicColor = false) {
        P2PScreen(connectionState = ConnectionState.CONNECTED, receivedResult = null,
            isBluetoothEnabled = true, isWiFiEnabled = true, nearbyRadioWarning = null,
            onEnableBluetooth = {}, onEnableWiFi = {}, onStartAdvertising = {}, onStopAdvertising = {},
            onStartDiscovery = {}, onStopDiscovery = {}, onDisconnect = {}, onSendRecords = {},
            hasPermissions = true, onRequestPermissions = {}, onOpenScreenShare = {},
            screenSharingRequested = requested)
    }
}
