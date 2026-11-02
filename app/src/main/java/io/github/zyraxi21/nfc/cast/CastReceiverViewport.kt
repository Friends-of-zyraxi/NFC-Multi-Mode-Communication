package io.github.zyraxi21.nfc.cast

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 全屏控件空闲三秒后淡出；按住或聚焦时保留，并遵循系统无障碍显示时长。 */
@Composable
internal fun CastReceiverViewport(
    state: CastUiState,
    fullscreen: Boolean,
    onSurface: (Surface?) -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 3.seconds.inWholeMilliseconds, containsText = true, containsControls = true,
    )?.milliseconds ?: 3.seconds
    var controlsVisible by remember(fullscreen) { mutableStateOf(fullscreen) }
    var pointerDown by remember(fullscreen) { mutableStateOf(false) }
    var controlsFocused by remember(fullscreen) { mutableStateOf(false) }
    var interaction by remember(fullscreen) { mutableLongStateOf(0) }
    LaunchedEffect(fullscreen, controlsVisible, pointerDown, controlsFocused, interaction, timeout, isPreview) {
        if (fullscreen && controlsVisible && !pointerDown && !controlsFocused && !isPreview) {
            delay(timeout)
            controlsVisible = false
        }
    }
    fun toggleControls() { controlsVisible = !controlsVisible; interaction++ }
    val toggleLabel = stringResource(if (controlsVisible) R.string.cast_hide_controls else R.string.cast_show_controls)
    val gestures = if (fullscreen && !isPreview) Modifier
        .pointerInput(fullscreen) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                        if (pointerDown != pressed) { pointerDown = pressed; interaction++ }
                    }
                }
            } finally { pointerDown = false }
        }
        .pointerInput(fullscreen) { detectTapGestures(onTap = { toggleControls() }) }
        .semantics { onClick(label = toggleLabel) { toggleControls(); true } }
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key in listOf(Key.Enter, Key.DirectionCenter, Key.Spacebar)) {
                toggleControls(); true
            } else false
        }
        .focusable()
    else Modifier
    CastViewportContent(state, fullscreen, controlsVisible, onSurface, onExitFullscreen,
        onControlsFocusChanged = { controlsFocused = it }, modifier = modifier.then(gestures))
}

@Composable
private fun CastViewportContent(
    state: CastUiState,
    fullscreen: Boolean,
    controlsVisible: Boolean,
    onSurface: (Surface?) -> Unit,
    onExitFullscreen: () -> Unit,
    onControlsFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    val foreground = if (fullscreen) Color.White else AppTheme.textPrimary
    // 只给全屏补黑边，非全屏使用页面主题背景。
    Box(modifier.background(if (fullscreen) Color.Black else AppTheme.canvas).testTag("cast-viewport"),
        contentAlignment = Alignment.Center) {
        val ratio = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 9f / 16
        val surfaceCallback by rememberUpdatedState(onSurface)
        if (isPreview) {
            Box(Modifier.aspectRatio(ratio).testTag("cast-video"), contentAlignment = Alignment.Center) {
                FluentText(stringResource(R.string.cast_preview_video), FluentTextStyle.Body1, color = foreground)
            }
        } else AndroidView(
            factory = { context -> SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surfaceCallback(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { surfaceCallback(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceCallback(null) }
                })
            } },
            modifier = Modifier.aspectRatio(ratio).testTag("cast-video"),
        )
        if (!state.hasFrame) FluentText(stringResource(R.string.cast_waiting_frame), FluentTextStyle.Body1, color = foreground)
        AnimatedVisibility(visible = fullscreen && controlsVisible, modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
            Box(Modifier.windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBars))
                .padding(FluentSpacing.m).onFocusChanged { onControlsFocusChanged(it.hasFocus) }.focusGroup()
                .testTag("cast-fullscreen-controls")) {
                FluentButton(onClick = onExitFullscreen, text = stringResource(R.string.cast_exit_fullscreen))
            }
        }
    }
}

internal class CastControlsPreviewProvider : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(true, false)
}

@Preview(name = "全屏投屏 控件显示与隐藏", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "全屏投屏 横屏控件", showBackground = true, widthDp = 852, heightDp = 393)
@Composable
private fun CastFullscreenPreview(@PreviewParameter(CastControlsPreviewProvider::class) controlsVisible: Boolean) {
    MyApplicationTheme(dynamicColor = false) {
        CastViewportContent(CastUiState(CastStage.RECEIVING, width = 1920, height = 1080, hasFrame = true),
            fullscreen = true, controlsVisible = controlsVisible, onSurface = {}, onExitFullscreen = {},
            modifier = Modifier.fillMaxSize())
    }
}
