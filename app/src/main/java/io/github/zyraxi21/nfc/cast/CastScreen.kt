package io.github.zyraxi21.nfc.cast

import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** 以画面为中心的接收页；全屏控件支持点按唤出和空闲淡出。 */
@Composable
internal fun CastScreen(
    state: CastUiState,
    scanning: Boolean,
    localMessage: String?,
    onHost: () -> Unit,
    onScan: () -> Unit,
    onConnectInvite: (() -> Unit)?,
    onCancelScan: () -> Unit,
    onRequestDirection: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCapture: () -> Unit,
    onStopVideo: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSurface: (Surface?) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onNfcSettings: () -> Unit,
    onWirelessSettings: () -> Unit,
    onConfirmCode: () -> Unit = {},
) {
    val isPreview = LocalInspectionMode.current
    var full by rememberSaveable { mutableStateOf(false) }
    val currentSurfaceCallback by rememberUpdatedState(onSurface)
    val receiving = state.stage == CastStage.RECEIVING
    if (!isPreview) {
        LaunchedEffect(receiving) { if (!receiving) full = false }
        LaunchedEffect(full) { onFullscreen(full) }
        DisposableEffect(Unit) { onDispose { onFullscreen(false); currentSurfaceCallback(null) } }
        BackHandler(full) { full = false }
    }
    // 全屏画面使用完整窗口；只有操作控件避开挖孔，不能给视频整体加安全区边距。
    Column(Modifier.fillMaxSize().background(if (full) Color.Black else AppTheme.canvas)
        .then(if (full) Modifier else Modifier.safeDrawingPadding()).testTag("cast-screen"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (!full) Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FluentSpacing.mPlus).testTag("cast-top-bar")) {
            FluentText(stringResource(R.string.cast_title), FluentTextStyle.Title3,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp).testTag("cast-title"))
            FluentButton(onClick = onBack, text = stringResource(R.string.cast_back), style = ButtonStyle.TextButton, modifier = Modifier.align(Alignment.CenterStart))
            if (state.stage != CastStage.IDLE) FluentButton(onClick = onEnd, text = stringResource(R.string.cast_disconnect), style = ButtonStyle.TextButton,
                modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(
            Modifier.weight(1f)
                .then(if (receiving) Modifier else Modifier.widthIn(max = PageMetrics.maxContentWidth)).fillMaxWidth()
                .then(if (receiving) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(FluentSpacing.mPlus)),
            verticalArrangement = if (receiving) Arrangement.Top else Arrangement.spacedBy(FluentSpacing.mPlus),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = localMessage ?: state.message
            if (!receiving) message?.let { FluentText(it, FluentTextStyle.Body2, color = AppTheme.danger) }
            when (state.stage) {
                CastStage.IDLE -> if (scanning) {
                    StatusCard(stringResource(R.string.cast_scan_title), stringResource(R.string.cast_scan_description))
                    FluentButton(onClick = onCancelScan, text = stringResource(R.string.cast_cancel_scan), style = ButtonStyle.OutlinedButton)
                } else {
                    StatusCard(stringResource(R.string.cast_idle_title), stringResource(R.string.cast_idle_description))
                    FluentButton(onClick = onHost, text = stringResource(R.string.cast_create_invite), modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
                    FluentButton(onClick = onScan, text = stringResource(R.string.cast_scan_invite), style = ButtonStyle.OutlinedButton,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
                    onConnectInvite?.let { FluentButton(onClick = it, text = stringResource(R.string.cast_connect_invite), style = ButtonStyle.OutlinedButton) }
                }
                CastStage.ADVERTISING -> {
                    StatusCard(stringResource(R.string.cast_advertising_title), stringResource(R.string.cast_advertising_description))
                    var seconds by remember(state.expiresAt) {
                        mutableLongStateOf(((state.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000)
                    }
                    if (!isPreview) LaunchedEffect(state.expiresAt) { while (true) {
                        seconds = ((state.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
                        delay(1.seconds)
                    } }
                    FluentText(stringResource(R.string.cast_invite_expires, seconds), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                }
                CastStage.DISCOVERING, CastStage.CONNECTING, CastStage.AUTHENTICATING -> {
                    if (state.connectionCode.isNotEmpty()) StatusCard(stringResource(R.string.cast_direct_wait_title), stringResource(R.string.cast_direct_wait_description))
                    else StatusCard(stringResource(R.string.cast_connecting_title), stringResource(R.string.cast_connecting_description))
                }
                CastStage.VERIFYING -> {
                    StatusCard(stringResource(R.string.cast_verify_title), stringResource(R.string.cast_verify_description))
                    FluentText(state.connectionCode, FluentTextStyle.LargeTitle, modifier = Modifier.fillMaxWidth().testTag("cast-connection-code"))
                    if (state.codeConfirmed) FluentText(stringResource(R.string.cast_verify_wait), FluentTextStyle.Body2)
                    else FluentButton(onClick = onConfirmCode, text = stringResource(R.string.cast_verify_continue), modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onEnd, text = stringResource(R.string.cast_verify_disconnect), style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.READY -> {
                    StatusCard(stringResource(R.string.cast_ready_title), stringResource(R.string.cast_ready_description))
                    FluentButton(onClick = { onRequestDirection(true) }, text = stringResource(R.string.cast_share_local), modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = { onRequestDirection(false) }, text = stringResource(R.string.cast_view_remote), style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.OFFER_OUT -> {
                    StatusCard(stringResource(R.string.cast_offer_out_title), if (state.localSender == true) stringResource(R.string.cast_offer_out_send) else stringResource(R.string.cast_offer_out_receive))
                    FluentButton(onClick = onStopVideo, text = stringResource(R.string.cast_cancel_request), style = ButtonStyle.OutlinedButton)
                }
                CastStage.OFFER_IN -> {
                    StatusCard(stringResource(R.string.cast_offer_in_title), if (state.localSender == true) stringResource(R.string.cast_offer_in_send) else stringResource(R.string.cast_offer_in_receive))
                    FluentButton(onClick = onAccept, text = stringResource(R.string.cast_accept), modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onReject, text = stringResource(R.string.cast_reject), style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.PREPARING -> {
                    StatusCard(if (state.localSender == true) stringResource(R.string.cast_preparing_send_title) else stringResource(R.string.cast_preparing_receive_title),
                        if (state.localSender == true) stringResource(R.string.cast_preparing_send_description) else stringResource(R.string.cast_preparing_receive_description))
                    if (state.localSender == true) FluentButton(onClick = onCapture, text = stringResource(R.string.cast_start_capture), modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onStopVideo, text = stringResource(R.string.cast_cancel_sharing), style = ButtonStyle.OutlinedButton)
                }
                CastStage.SENDING -> {
                    StatusCard(stringResource(R.string.cast_sending_title), stringResource(R.string.cast_sending_description))
                    if (state.width > 0) FluentText(stringResource(R.string.cast_dimensions, state.width, state.height), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                    if (state.videoBitrate > 0) FluentText(
                        stringResource(R.string.cast_encoding_target, state.videoBitrate / 1_000_000.0, state.videoFrameRate),
                        FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                    FluentButton(onClick = onHome, text = stringResource(R.string.cast_go_home), modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onStopVideo, text = stringResource(R.string.cast_stop_sharing), style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.RECEIVING -> {
                    CastReceiverViewport(state, full, onSurface, onExitFullscreen = { full = false },
                        modifier = Modifier.weight(1f).fillMaxWidth())
                    if (!full) Row(Modifier.fillMaxWidth().padding(FluentSpacing.mPlus), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                        FluentButton(onClick = { full = true }, text = stringResource(R.string.cast_enter_fullscreen), modifier = Modifier.weight(1f))
                        FluentButton(onClick = onStopVideo, text = stringResource(R.string.cast_stop_sharing), style = ButtonStyle.OutlinedButton, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.stage == CastStage.IDLE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m, Alignment.CenterHorizontally)) {
                    FluentButton(onClick = onNfcSettings, text = stringResource(R.string.cast_nfc_settings), style = ButtonStyle.TextButton)
                    FluentButton(onClick = onWirelessSettings, text = stringResource(R.string.cast_wireless_settings), style = ButtonStyle.TextButton)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, text: String) {
    FluentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FluentSpacing.xl), verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
            FluentText(title, FluentTextStyle.Title3)
            FluentText(text, FluentTextStyle.Body2, color = AppTheme.textSecondary)
        }
    }
}

/** 预览仅提供界面状态，不连接设备，也不创建实际的视频 Surface。 */
internal data class CastPreviewState(val state: CastUiState, val scanning: Boolean = false)

internal class CastPreviewParameterProvider : PreviewParameterProvider<CastPreviewState> {
    override val values: Sequence<CastPreviewState>
        get() = sequence {
            for (stage in CastStage.entries) {
                yield(CastPreviewState(CastUiState(stage = stage, localSender = true,
                    connectionCode = if (stage == CastStage.VERIFYING) "12345" else "",
                    expiresAt = System.currentTimeMillis() + 300_000,
                    width = 1220, height = 2656, hasFrame = true,
                    videoBitrate = 18_000_000, videoFrameRate = 30)))
            }
            yield(CastPreviewState(CastUiState(), scanning = true))
            yield(CastPreviewState(CastUiState(CastStage.OFFER_IN, localSender = false)))
            yield(CastPreviewState(CastUiState(CastStage.PREPARING, localSender = false)))
            yield(CastPreviewState(CastUiState(CastStage.VERIFYING, connectionCode = "12345", codeConfirmed = true)))
        }
}

@Preview(name = "屏幕共享 浅色", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "屏幕共享 深色", showBackground = true, widthDp = 393, heightDp = 852,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CastScreenPreview(@PreviewParameter(CastPreviewParameterProvider::class) sample: CastPreviewState) {
    MyApplicationTheme(dynamicColor = false) { PreviewCastContent(sample) }
}

@Preview(name = "屏幕共享 横屏接收", showBackground = true, widthDp = 852, heightDp = 393)
@Preview(name = "屏幕共享 横屏接收深色", showBackground = true, widthDp = 852, heightDp = 393,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CastScreenLandscapePreview() {
    MyApplicationTheme(dynamicColor = false) {
        PreviewCastContent(CastPreviewState(CastUiState(CastStage.RECEIVING, width = 2656, height = 1220, hasFrame = true)))
    }
}

@Composable
private fun PreviewCastContent(sample: CastPreviewState) {
    CastScreen(sample.state, sample.scanning, null,
        onHost = {}, onScan = {}, onConnectInvite = null, onCancelScan = {},
        onRequestDirection = {}, onAccept = {}, onReject = {}, onCapture = {},
        onStopVideo = {}, onEnd = {}, onBack = {}, onHome = {}, onSurface = {},
        onFullscreen = {}, onNfcSettings = {}, onWirelessSettings = {})
}
