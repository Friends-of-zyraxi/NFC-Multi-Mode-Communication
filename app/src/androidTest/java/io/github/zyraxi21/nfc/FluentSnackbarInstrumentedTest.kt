package io.github.zyraxi21.nfc

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
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
import io.github.zyraxi21.nfc.cast.ConnectionMessages
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FluentSnackbarInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromCastDoesNotReplayConnectedAndDisconnectedSnackbars() {
        val state = SnackbarState()
        lateinit var messages: ConnectionMessages
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connected = context.getString(R.string.toast_connection_success)
        val disconnected = context.getString(R.string.toast_connection_disconnected)
        compose.setContent {
            val scope = rememberCoroutineScope()
            messages = remember(scope) { ConnectionMessages(scope) { message ->
                state.showSnackbar(message, style = SnackbarStyle.Accent, duration = NotificationDuration.LONG)
            } }
            MyApplicationTheme(dynamicColor = false) { FluentSnackbarHost(state) }
        }
        compose.runOnIdle { messages.resume(); messages.show(connected) }
        compose.onNodeWithText(connected).assertIsDisplayed()
        // 离开主界面进入投屏，随后连接由服务结束；主界面返回时不应补播旧事件。
        compose.runOnIdle { messages.pause(); messages.show(disconnected); messages.resume() }
        compose.waitUntil(5000) { state.currentSnackbar == null }
        compose.onNodeWithText(connected).assertDoesNotExist()
        compose.onNodeWithText(disconnected).assertDoesNotExist()
        compose.runOnIdle { messages.show(disconnected) }
        compose.onNodeWithText(disconnected).assertIsDisplayed()
    }

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
