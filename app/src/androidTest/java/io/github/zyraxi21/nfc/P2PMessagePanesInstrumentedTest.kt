package io.github.zyraxi21.nfc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class P2PMessagePanesInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun received(text: String) = parseNearbyPayload(context, createNearbyPayload(listOf(
        NfcWriteRecord.Text(text), NfcWriteRecord.Link("https://example.org/"))))

    @Test fun receivingDoesNotReplaceTheDraftAndOnlyDraftRecordsAreSent() {
        val result = mutableStateOf<NfcReadResult?>(null)
        var sent: List<NfcWriteRecord>? = null
        val actions = mutableListOf<RecordAction>()
        val dark = mutableStateOf(false)
        compose.setContent { MyApplicationTheme(darkTheme = dark.value) {
            Box(Modifier.fillMaxSize().background(AppTheme.canvas)) {
            P2PScreen(ConnectionState.CONNECTED, result.value, true, true, null,
                {}, {}, {}, {}, {}, {}, {}, { sent = it }, true, {}, { actions += it })
            }
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement("outgoing draft")
        compose.runOnIdle { result.value = received("incoming text") }
        compose.onNodeWithTag("p2p-send-tab").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextContains("outgoing draft")
        compose.onNodeWithTag("reader-record-0").assertDoesNotExist()
        capture("p2p-send.png")
        compose.onNodeWithTag("p2p-received-tab").performClick()
        compose.onNodeWithTag("p2p-received-tab").assertIsSelected()
        compose.onNodeWithTag("p2p-records").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithTag("p2p-received").performScrollToNode(hasText("incoming text"))
        compose.onNodeWithText("incoming text").assertIsDisplayed()
        compose.onNodeWithTag("p2p-received").performScrollToIndex(0)
        capture("p2p-received-light.png")
        compose.runOnIdle { dark.value = true }
        capture("p2p-received-dark.png")
        compose.onNodeWithTag("p2p-received").performScrollToNode(hasText(context.getString(R.string.record_open_web)))
        compose.onNodeWithText(context.getString(R.string.record_open_web)).performClick()
        compose.runOnIdle { assertEquals(listOf(RecordAction.Web("https://example.org/")), actions) }
        compose.onNodeWithTag("p2p-send-tab").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("outgoing draft")
        compose.onNodeWithTag("p2p-records").performScrollToNode(hasText(context.getString(R.string.p2p_button_send)))
        compose.onNodeWithText(context.getString(R.string.p2p_button_send)).performClick()
        compose.runOnIdle { assertEquals(listOf(NfcWriteRecord.Text("outgoing draft")), sent) }
    }

    @Test fun selectedReceivePaneUpdatesAndReceivedContentsRemainAfterDisconnection() {
        val state = mutableStateOf(ConnectionState.CONNECTED)
        val result = mutableStateOf(received("first message"))
        compose.setContent { MyApplicationTheme {
            P2PScreen(state.value, result.value, true, true, null, {}, {}, {}, {}, {}, {}, {}, {}, true, {})
        } }
        compose.onNodeWithTag("p2p-received-tab").performClick()
        compose.runOnIdle { result.value = received("latest message") }
        compose.onNodeWithTag("p2p-received-tab").assertIsSelected()
        compose.onNodeWithTag("p2p-received").performScrollToNode(hasText("latest message"))
        compose.onNodeWithText("latest message").assertIsDisplayed()
        compose.onNodeWithText("first message").assertDoesNotExist()
        compose.runOnIdle { state.value = ConnectionState.DISCONNECTED }
        compose.onNodeWithTag("p2p-status").performScrollToNode(hasText("latest message"))
        compose.onNodeWithText("latest message").assertIsDisplayed()
    }

    @Test fun restoringTheReceivePaneAlsoRestoresTheHiddenSendDraft() {
        val restoration = StateRestorationTester(compose)
        val result = received("received message")
        restoration.setContent { MyApplicationTheme {
            P2PScreen(ConnectionState.CONNECTED, result, true, true, null, {}, {}, {}, {}, {}, {}, {}, {}, true, {})
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement("saved draft")
        compose.onNodeWithTag("p2p-received-tab").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("p2p-received-tab").assertIsSelected()
        compose.onNodeWithTag("p2p-send-tab").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("saved draft")
    }

    private fun capture(name: String) {
        val target = File(context.getExternalFilesDir(null), "p2p-panes").apply { mkdirs() }
        File(target, name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
