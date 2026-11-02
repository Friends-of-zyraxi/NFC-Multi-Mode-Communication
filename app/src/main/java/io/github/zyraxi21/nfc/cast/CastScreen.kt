package io.github.zyraxi21.nfc.cast

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.ui.theme.*
import kotlinx.coroutines.delay

/** 以画面为中心的接收页；横屏压缩控制区，全屏时只保留退出全屏入口。 */
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
    var full by rememberSaveable { mutableStateOf(false) }
    val currentSurfaceCallback by rememberUpdatedState(onSurface)
    val receiving = state.stage == CastStage.RECEIVING
    LaunchedEffect(receiving) { if (!receiving) full = false }
    LaunchedEffect(full) { onFullscreen(full) }
    DisposableEffect(Unit) { onDispose { onFullscreen(false); currentSurfaceCallback(null) } }
    BackHandler(full) { full = false }
    // 全屏画面使用完整窗口；只有操作控件避开挖孔，不能给视频整体加安全区边距。
    Column(Modifier.fillMaxSize().background(if (full) Color.Black else AppTheme.canvas)
        .then(if (full) Modifier else Modifier.safeDrawingPadding()).testTag("cast-screen")) {
        if (!full) Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FluentSpacing.mPlus).testTag("cast-top-bar")) {
            FluentText("屏幕共享", FluentTextStyle.Title3,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp).testTag("cast-title"))
            FluentButton(onClick = onBack, text = "返回", style = ButtonStyle.TextButton, modifier = Modifier.align(Alignment.CenterStart))
            if (state.stage != CastStage.IDLE) FluentButton(onClick = onEnd, text = "断开", style = ButtonStyle.TextButton,
                modifier = Modifier.align(Alignment.CenterEnd))
        }
        if (receiving) {
            // 比例不同时保留完整画面，剩余区域始终为纯黑色。
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).testTag("cast-viewport"), contentAlignment = Alignment.Center) {
                val width = state.width
                val height = state.height
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val ratio = if (width > 0 && height > 0) width.toFloat() / height else 9f / 16
                    val displayWidth = minOf(maxWidth, maxHeight * ratio)
                    val displayHeight = displayWidth / ratio
                    val surfaceCallback by rememberUpdatedState(onSurface)
                    AndroidView(
                        factory = { context -> SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { surfaceCallback(holder.surface) }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { surfaceCallback(holder.surface) }
                                override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceCallback(null) }
                            })
                        } },
                        modifier = Modifier.size(displayWidth, displayHeight).testTag("cast-video"),
                    )
                }
                if (!state.hasFrame) FluentText("等待对方画面…", FluentTextStyle.Body1, color = Color.White)
                if (full) Box(Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBars))
                    .padding(FluentSpacing.m)) {
                    FluentButton(onClick = { full = false }, text = "退出全屏")
                }
            }
            if (!full) Row(Modifier.fillMaxWidth().padding(FluentSpacing.mPlus), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                FluentButton(onClick = { full = true }, text = "全屏查看", modifier = Modifier.weight(1f))
                FluentButton(onClick = onStopVideo, text = "结束共享", style = ButtonStyle.OutlinedButton, modifier = Modifier.weight(1f))
            }
        } else Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(FluentSpacing.mPlus),
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = localMessage ?: state.message
            message?.let { FluentText(it, FluentTextStyle.Body2, color = AppTheme.danger) }
            when (state.stage) {
                CastStage.IDLE -> if (scanning) {
                    StatusCard("靠近邀请设备", "让另一台设备保持在屏幕共享邀请页面，将两台设备的 NFC 区域靠近。")
                    FluentButton(onClick = onCancelScan, text = "取消读取", style = ButtonStyle.OutlinedButton)
                } else {
                    StatusCard("连接两块屏幕", "已在端对端页面连接的设备，可直接从该页面进入屏幕共享；也可以用 NFC 邀请对方连接。")
                    FluentButton(onClick = onHost, text = "生成 NFC 邀请", modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
                    FluentButton(onClick = onScan, text = "碰一碰加入", style = ButtonStyle.OutlinedButton,
                        modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
                    onConnectInvite?.let { FluentButton(onClick = it, text = "连接邀请设备", style = ButtonStyle.OutlinedButton) }
                    FluentText("接收画面支持横竖屏切换。共享前需在发送设备上确认系统录屏授权。", FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                }
                CastStage.ADVERTISING -> {
                    StatusCard("邀请已开启", "将对方设备的 NFC 区域靠近本机。若系统未自动打开应用，请在对方应用中选择“碰一碰加入”。")
                    var seconds by remember { mutableLongStateOf(0) }
                    LaunchedEffect(state.expiresAt) { while (true) {
                        seconds = ((state.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
                        delay(1000)
                    } }
                    FluentText("邀请将在 $seconds 秒后失效", FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                }
                CastStage.DISCOVERING, CastStage.CONNECTING, CastStage.AUTHENTICATING -> {
                    if (state.connectionCode.isNotEmpty()) StatusCard("等待对方进入屏幕共享", "请在对方设备的端对端页面点击“接受并进入屏幕共享”，随后核对两端验证码。")
                    else StatusCard("正在连接邀请设备", "正在匹配并验证本次 NFC 邀请，连接成功后即可选择投屏方向。")
                }
                CastStage.VERIFYING -> {
                    StatusCard("确认连接的是同一台设备", "请核对两台设备显示的验证码是否一致。")
                    FluentText(state.connectionCode, FluentTextStyle.LargeTitle, modifier = Modifier.fillMaxWidth().testTag("cast-connection-code"))
                    if (state.codeConfirmed) FluentText("已确认，等待对方确认验证码…", FluentTextStyle.Body2)
                    else FluentButton(onClick = onConfirmCode, text = "验证码一致，继续", modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onEnd, text = "不一致，断开连接", style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.READY -> {
                    StatusCard("已连接，选择投屏方向", "可以共享本机画面，也可以请求查看对方画面。")
                    FluentButton(onClick = { onRequestDirection(true) }, text = "共享本机屏幕", modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = { onRequestDirection(false) }, text = "查看对方屏幕", style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.OFFER_OUT -> {
                    StatusCard("等待对方确认", if (state.localSender == true) "已请求将本机画面共享给对方。" else "已请求查看对方画面。")
                    FluentButton(onClick = onStopVideo, text = "取消请求", style = ButtonStyle.OutlinedButton)
                }
                CastStage.OFFER_IN -> {
                    StatusCard("收到共享请求", if (state.localSender == true) "对方希望查看本机屏幕，同意后仍需确认系统录屏授权。" else "对方希望将画面共享给本机。")
                    FluentButton(onClick = onAccept, text = "同意", modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onReject, text = "拒绝", style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.PREPARING -> {
                    StatusCard(if (state.localSender == true) "准备共享本机屏幕" else "等待对方开始共享",
                        if (state.localSender == true) "确认系统授权后，对方才能看到画面。可以共享整个屏幕，也可以在系统中选择单个应用。" else "对方确认录屏授权后，画面将显示在这里。")
                    if (state.localSender == true) FluentButton(onClick = onCapture, text = "授权并开始共享", modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onStopVideo, text = "取消共享", style = ButtonStyle.OutlinedButton)
                }
                CastStage.SENDING -> {
                    StatusCard("正在共享本机屏幕", "可返回桌面打开其他应用，对方会实时看到共享区域。系统锁屏或停止录屏后，本次共享会结束。")
                    if (state.width > 0) FluentText("画面尺寸 ${state.width} × ${state.height}", FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                    FluentButton(onClick = onHome, text = "返回桌面继续共享", modifier = Modifier.fillMaxWidth())
                    FluentButton(onClick = onStopVideo, text = "结束共享", style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
                }
                CastStage.RECEIVING -> Unit
            }
            if (state.stage == CastStage.IDLE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m, Alignment.CenterHorizontally)) {
                    FluentButton(onClick = onNfcSettings, text = "NFC 设置", style = ButtonStyle.TextButton)
                    FluentButton(onClick = onWirelessSettings, text = "无线设置", style = ButtonStyle.TextButton)
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
