package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 短内容在可用高度中居中；空间不足或字号增大时仍可完整滚动。 */
@Composable
internal fun CenteredStatusPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = this.maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
            .padding(horizontal = LocalPageGutter.current, vertical = FluentSpacing.mPlus),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus, Alignment.CenterVertically),
            content = content)
    }
}
