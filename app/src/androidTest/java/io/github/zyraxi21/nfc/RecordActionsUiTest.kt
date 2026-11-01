package io.github.zyraxi21.nfc

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.*
import io.github.zyraxi21.nfc.util.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val records = listOf(NfcWriteRecord.Link("https://example.org/"),
        NfcWriteRecord.Wifi("Test", "12345678", WifiEncryption.AES, WifiAuth.WPA2_PSK),
        NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "Device"), NfcWriteRecord.Application("com.android.settings"))
    private fun result() = parseNearbyPayload(context, createNearbyPayload(records))

    @Test
    fun readingOnlyDisplaysInformationAndEachButtonTargetsItsOwnRecord() {
        val actions = mutableListOf<RecordAction>()
        compose.setContent { MyApplicationTheme { NFCReaderScreen(result(), false, onRecordAction = { actions += it }) } }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        val labels = listOf("访问网页", "连接 Wi-Fi", "配对 / 连接蓝牙", "打开应用")
        labels.forEachIndexed { index, label ->
            compose.onNodeWithTag("reader-results").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(result().records[index].actions.single(), actions.last()) }
        }
        compose.runOnIdle { assertEquals(4, actions.size) }
    }

    @Test
    fun nearbyUsesTheSameFiveTypeEditorAndRetainsDraftsAcrossReconnectionAndRecreation() {
        var sent: List<NfcWriteRecord>? = null
        val state = mutableStateOf(ConnectionState.CONNECTED)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MyApplicationTheme { p2p(state.value, null, { sent = it }) } }
        compose.onNodeWithText("类型：文本").performClick()
        compose.onNodeWithText("应用程序").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("com.android.settings")
        compose.onNodeWithTag("p2p-records").performScrollToNode(hasText("添加记录"))
        compose.onNodeWithText("添加记录").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("text|with\nseparator")
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state.value = ConnectionState.DISCONNECTED }
        compose.onNodeWithText("用户 1").assertIsDisplayed()
        compose.runOnIdle { state.value = ConnectionState.CONNECTED }
        compose.onNodeWithTag("p2p-records").performScrollToNode(hasText("发送"))
        compose.onNodeWithText("发送").performClick()
        compose.runOnIdle { assertEquals(listOf(NfcWriteRecord.Application("com.android.settings"), NfcWriteRecord.Text("text|with\nseparator")), sent) }
        compose.onNodeWithText("写入标签").assertDoesNotExist()
        compose.onNodeWithText("卡模拟").assertDoesNotExist()
    }

    @Test
    fun nearbyReceivedRecordsKeepTheSameActionsAfterDisconnection() {
        val actions = mutableListOf<RecordAction>()
        compose.setContent { MyApplicationTheme { p2p(ConnectionState.DISCONNECTED, result(), {}, { actions += it }) } }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("p2p-status").performScrollToNode(hasText("打开应用"))
        compose.onNodeWithText("打开应用").performClick()
        compose.runOnIdle { assertEquals(listOf(RecordAction.Application("com.android.settings")), actions) }
    }

    @androidx.compose.runtime.Composable
    private fun p2p(state: ConnectionState, received: NfcReadResult?, send: (List<NfcWriteRecord>) -> Unit,
        action: ((RecordAction) -> Unit)? = null) {
        P2PScreen(state, received, true, true, null, {}, {}, {}, {}, {}, {}, {}, send, true, {}, action)
    }
}
