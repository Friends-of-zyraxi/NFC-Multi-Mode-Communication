package io.github.zyraxi21.nfc

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.*
import io.github.zyraxi21.nfc.util.MAX_EMULATED_NDEF_BYTES
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MultiRecordEditorInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val dark = mutableStateOf(false)

    @Test
    fun editingReorderingDeletionAndRestorationKeepTheSameListForBothActions() {
        var written: List<NfcWriteRecord>? = null
        var emulated: List<NfcWriteRecord>? = null
        val restoration = StateRestorationTester(compose)
        show({ written = it }, { emulated = it }, restoration)
        input("第一条文本")
        addRecord()
        selectType("网址")
        input("https://example.com/original")
        addRecord()
        input("第三条文本")
        clickTag("write-up-3")
        clickTag("write-delete-1")
        clickTag("write-edit-2")
        input("https://example.com/edited")
        restoration.emulateSavedInstanceStateRestore()
        footer()
        compose.onNodeWithText("写入标签").performClick()
        compose.onNodeWithText("卡模拟").performClick()
        val expected = listOf(NfcWriteRecord.Text("第三条文本"), NfcWriteRecord.Link("https://example.com/edited"))
        compose.runOnIdle {
            assertEquals(expected, written)
            assertEquals(expected, emulated)
        }
        compose.onNodeWithTag("write-records").performScrollToIndex(0)
        capture("write-multi-light.png")
        compose.runOnIdle { dark.value = true }
        capture("write-multi-dark.png")
    }

    @Test
    fun anyInvalidRecordBlocksTheEntireBatchUntilItIsFixed() {
        var written: List<NfcWriteRecord>? = null
        show(onWrite = { written = it })
        input("有效文本")
        addRecord()
        selectType("应用程序")
        input("invalid package")
        footer()
        compose.onNodeWithText("写入标签").assertIsNotEnabled()
        compose.onNodeWithText("卡模拟").assertIsNotEnabled()
        input(" com.example.target ")
        footer()
        compose.onNodeWithText("写入标签").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(NfcWriteRecord.Text("有效文本"), NfcWriteRecord.Application("com.example.target")), written)
        }
    }

    @Test
    fun removingEveryRecordDisablesSubmissionAndAddingOneRestoresIt() {
        show()
        clickTag("write-delete-1")
        compose.onNodeWithText("还没有记录，请先添加需要写入的内容。").assertIsDisplayed()
        footer()
        compose.onNodeWithText("写入标签").assertIsNotEnabled()
        compose.onNodeWithText("卡模拟").assertIsNotEnabled()
        addRecord()
        footer()
        compose.onNodeWithText("写入标签").assertIsEnabled()
        compose.onNodeWithText("卡模拟").assertIsEnabled()
    }

    @Test
    fun oversizedMessageCanBeWrittenButCannotStartHce() {
        show()
        input("x".repeat(MAX_EMULATED_NDEF_BYTES + 1))
        footer()
        compose.onNodeWithText("写入标签").assertIsEnabled()
        compose.onNodeWithText("卡模拟").assertIsNotEnabled()
        compose.onNodeWithText("内容超过卡模拟上限（65532 B），可减少内容后重试。").assertExists()
    }

    @Test
    fun wifiAndBluetoothFormsKeepTheirOwnValuesAcrossEditing() {
        var written: List<NfcWriteRecord>? = null
        show(onWrite = { written = it })
        selectType("Wi-Fi")
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("My Network")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("wifi-secret")
        addRecord()
        selectType("蓝牙")
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("AA:BB:CC:DD:EE:FF")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("My Device")
        clickTag("write-edit-1")
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("My Network")
        compose.onAllNodes(hasSetTextAction())[1].assertTextContains("wifi-secret")
        footer()
        compose.onNodeWithText("写入标签").performClick()
        compose.runOnIdle {
            assertEquals(listOf(
                NfcWriteRecord.Wifi("My Network", "wifi-secret", WifiEncryption.AES, WifiAuth.WPA2_PSK),
                NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "My Device"),
            ), written)
        }
    }

    @Test
    fun openNetworkWritesAnEmptyKeyAndRestoresThePersonalNetworkPassword() {
        var written: List<NfcWriteRecord>? = null
        var emulated: List<NfcWriteRecord>? = null
        val restoration = StateRestorationTester(compose)
        show({ written = it }, { emulated = it }, restoration)
        selectType("Wi-Fi")
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("Public Wi-Fi")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("personal-key")
        fun selectAuth(current: String, option: String) {
            compose.onNodeWithTag("write-records").performScrollToNode(hasText("认证类型：" + current))
            compose.onNodeWithText("认证类型：" + current).performClick()
            compose.onNodeWithText(option).performClick()
        }
        selectAuth("WPA2-PSK", "开放")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        footer()
        compose.onNodeWithText("写入标签").performClick()
        compose.onNodeWithText("卡模拟").performClick()
        compose.runOnIdle {
            val expected = listOf(NfcWriteRecord.Wifi("Public Wi-Fi", "", WifiEncryption.NONE, WifiAuth.OPEN))
            assertEquals(expected, written)
            assertEquals(expected, emulated)
        }
        selectAuth("开放", "WPA2-PSK")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().assertTextContains("personal-key")
        footer()
        compose.onNodeWithText("写入标签").performClick()
        compose.runOnIdle {
            assertEquals(listOf(NfcWriteRecord.Wifi("Public Wi-Fi", "personal-key", WifiEncryption.AES, WifiAuth.WPA2_PSK)), written)
        }
    }

    private fun show(
        onWrite: (List<NfcWriteRecord>) -> Unit = {},
        onEmulate: (List<NfcWriteRecord>) -> Unit = {},
        restoration: StateRestorationTester? = null,
    ) {
        val content: @Composable () -> Unit = {
            MyApplicationTheme(darkTheme = dark.value, dynamicColor = false) {
                BottomNavigationApp(readerScreen = {}, p2pScreen = {}, writeScreen = {
                    WriteCardScreen(WriteState.IDLE, "", onWrite, onEmulate)
                })
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.onNodeWithText("写卡/卡模拟").performClick()
    }

    private fun input(value: String) {
        compose.onNodeWithTag("write-records").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }

    private fun selectType(label: String) {
        compose.onNodeWithTag("write-records").performScrollToNode(hasText("类型：文本"))
        compose.onNodeWithText("类型：文本").performClick()
        compose.onNodeWithText(label).performClick()
    }

    private fun addRecord() {
        compose.onNodeWithTag("write-records").performScrollToNode(hasText("添加记录"))
        compose.onNodeWithText("添加记录").performClick()
    }

    private fun clickTag(tag: String) {
        compose.onNodeWithTag("write-records").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun footer() {
        compose.onNodeWithTag("write-records").performScrollToNode(hasText("写入标签"))
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMultiScreenshots") != "true") return
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("multi-record")).apply { mkdirs() }
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
