package io.github.zyraxi21.nfc

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.fluentui.theme.token.controlTokens.SnackbarStyle
import com.microsoft.fluentui.tokenized.notification.NotificationDuration
import com.microsoft.fluentui.tokenized.notification.NotificationResult
import com.microsoft.fluentui.tokenized.notification.SNACK_BAR
import com.microsoft.fluentui.tokenized.notification.SnackbarState
import io.github.zyraxi21.nfc.ui.theme.FluentButton
import io.github.zyraxi21.nfc.ui.theme.FluentSnackbarHost
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FluentSnackbarInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun accentSnackbarInvokesItsActionAndTheNextMessageTimesOut() {
        val state = SnackbarState()
        var result: NotificationResult? = null
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) {
                val scope = rememberCoroutineScope()
                Column {
                    FluentButton(text = "显示操作提示", onClick = {
                        scope.launch {
                            result = state.showSnackbar("操作提示", style = SnackbarStyle.Accent,
                                actionText = "继续", duration = NotificationDuration.LONG)
                        }
                    })
                    FluentButton(text = "显示短提示", onClick = {
                        scope.launch { state.showSnackbar("短提示", style = SnackbarStyle.Accent) }
                    })
                    FluentSnackbarHost(state)
                }
            }
        }
        compose.onNodeWithText("显示操作提示").performClick()
        compose.onNodeWithTag(SNACK_BAR).assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(5_000) { result == NotificationResult.CLICKED }
        compose.onNodeWithText("显示短提示").performClick()
        compose.onNodeWithTag(SNACK_BAR).assertIsDisplayed()
        compose.runOnIdle { assertEquals(SnackbarStyle.Accent, state.currentSnackbar?.style) }
        compose.waitUntil(10_000) { state.currentSnackbar == null }
        compose.onNodeWithTag(SNACK_BAR).assertDoesNotExist()
    }
}
