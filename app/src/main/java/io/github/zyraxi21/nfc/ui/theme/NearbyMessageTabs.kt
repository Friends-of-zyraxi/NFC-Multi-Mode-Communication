package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.R

internal enum class NearbyMessagePane { SEND, RECEIVED }

/** 收发内容拥有各自的页面和滚动状态，页签始终明确当前操作方向。 */
@Composable
internal fun NearbyMessageTabs(pane: NearbyMessagePane, receivedCount: Int, onSelect: (NearbyMessagePane) -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
        NearbyMessagePane.entries.forEach { item ->
            val active = pane == item
            FluentButton(text = if (item == NearbyMessagePane.SEND) stringResource(R.string.p2p_tab_send)
                else stringResource(R.string.p2p_tab_received, receivedCount),
                onClick = { onSelect(item) }, style = if (active) ButtonStyle.Button else ButtonStyle.OutlinedButton,
                modifier = Modifier.weight(1f).heightIn(min = PageMetrics.minTouchTarget)
                    .testTag(if (item == NearbyMessagePane.SEND) "p2p-send-tab" else "p2p-received-tab")
                    .semantics { role = Role.Tab; selected = active })
        }
    }
}
