package io.github.zyraxi21.nfc.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.ui.theme.*

/** 参数只描述本次连接，不采集或保存设备身份。 */
@Composable
internal fun CastConnectionDetails(state: CastUiState) {
    var expanded by remember(state.stage) { mutableStateOf(false) }
    FluentButton(text = stringResource(if (expanded) R.string.cast_details_hide else R.string.cast_details_show),
        onClick = { expanded = !expanded }, style = ButtonStyle.TextButton)
    if (expanded) CastConnectionMetrics(state)
}

@Composable
private fun CastConnectionMetrics(state: CastUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = FluentSpacing.mPlus).testTag("cast-connection-details"),
        verticalArrangement = Arrangement.spacedBy(FluentSpacing.s)) {
        val quality = stringResource(when (state.linkQuality) {
            BandwidthInfo.Quality.LOW -> R.string.cast_link_low
            BandwidthInfo.Quality.MEDIUM -> R.string.cast_link_medium
            BandwidthInfo.Quality.HIGH -> R.string.cast_link_high
            else -> R.string.cast_link_unknown
        })
        FluentText(stringResource(R.string.cast_link_quality, quality), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
        if (state.linkQuality == BandwidthInfo.Quality.LOW) {
            FluentText(stringResource(R.string.cast_link_low_hint), FluentTextStyle.Caption1, color = AppTheme.warning)
        }
        if (state.stage == CastStage.SENDING) {
            FluentText(stringResource(R.string.cast_transport_metrics, state.confirmedBitrate / 1_000_000.0,
                state.feedbackMs, state.pendingFrames), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
        }
        if (state.codecName.isNotEmpty()) {
            FluentText(stringResource(R.string.cast_codec_details,
                stringResource(if (state.stage == CastStage.SENDING) R.string.cast_encoder else R.string.cast_decoder),
                state.codecName, stringResource(if (state.hardwareCodec) R.string.cast_codec_hardware else R.string.cast_codec_software)),
                FluentTextStyle.Caption1, color = AppTheme.textSecondary)
        }
        FluentText(stringResource(R.string.cast_metrics_hint), FluentTextStyle.Caption1, color = AppTheme.textHint)
    }
}

@Preview(name = "投屏连接详情 浅色", showBackground = true, widthDp = 600)
@Preview(name = "投屏连接详情 深色", showBackground = true, widthDp = 600,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CastConnectionMetricsPreview() {
    MyApplicationTheme(dynamicColor = false) {
        CastConnectionMetrics(CastUiState(CastStage.SENDING, linkQuality = BandwidthInfo.Quality.LOW,
            confirmedBitrate = 1_500_000, feedbackMs = 240, pendingFrames = 3,
            codecName = "AVC", hardwareCodec = true))
    }
}
