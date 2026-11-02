package io.github.zyraxi21.nfc

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NFCReaderScreen
import io.github.zyraxi21.nfc.util.NfcReadResult
import io.github.zyraxi21.nfc.util.NfcReadRecord
import io.github.zyraxi21.nfc.util.NfcRecordField
import io.github.zyraxi21.nfc.util.NfcRecordKind
import io.github.zyraxi21.nfc.util.copyText
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import io.github.zyraxi21.nfc.util.toDisplayHex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderContentInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun emptyStateExplainsScanningAndCanCheckNfc() {
        var checked = false
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) {
                BottomNavigationApp(
                    readerScreen = { NFCReaderScreen(null, true) { checked = true } },
                    writeScreen = {}, p2pScreen = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.reader_scan_hint)).assertIsDisplayed()
        compose.onNodeWithText("支持文本、网址、Wi-Fi、蓝牙和应用程序").assertIsDisplayed()
        capture("reader-empty.png")
        compose.onNodeWithText("检查 NFC 状态").performClick()
        compose.runOnIdle { assertTrue(checked) }
    }

    @Test
    fun recordsStaySeparateInBothThemesAndCopyWithoutLaunchingLinks() {
        val dark = mutableStateOf(false)
        val result = parseNfcReadResult(context, listOf(NdefMessage(
            NdefRecord.createTextRecord("zh", "你好，NFC！\n每条记录独立展示，长内容也能轻松阅读。"),
            NdefRecord.createApplicationRecord("com.android.settings"),
            NdefRecord.createUri("https://example.com/nfc?source=tag"),
        )))
        compose.setContent {
            MyApplicationTheme(darkTheme = dark.value, dynamicColor = false) {
                BottomNavigationApp(
                    readerScreen = { NFCReaderScreen(result, false) },
                    writeScreen = {}, p2pScreen = {},
                )
            }
        }
        compose.onNodeWithText("3 条记录").assertIsDisplayed()
        compose.onNodeWithTag("reader-record-0").assertIsDisplayed()
        compose.onNodeWithTag("reader-results").performScrollToNode(hasTestTag("reader-record-1"))
        compose.onNodeWithText("com.android.settings").assertHasNoClickAction()
        compose.onNodeWithTag("reader-results").performScrollToIndex(0)
        capture("reader-light.png")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        capture("reader-dark.png")
        compose.onNodeWithText("复制全部内容").performClick()
        assertClipboard(result.copyText(context))
        compose.onNodeWithTag("reader-results").performScrollToNode(hasTestTag("reader-copy-1"))
        compose.onNodeWithTag("reader-copy-1").performClick()
        assertClipboard("com.android.settings")
        compose.onNodeWithTag("reader-results").performScrollToNode(hasText("https://example.com/nfc?source=tag"))
        compose.onNodeWithText("https://example.com/nfc?source=tag").assertHasNoClickAction()
    }

    @Test
    fun longTextExpandsButAlwaysCopiesTheCompleteOriginal() {
        val text = (1..30).joinToString("\n") { "第 $it 行：这是需要完整保留的标签内容。" }
        val result = parseNfcReadResult(context, listOf(NdefMessage(NdefRecord.createTextRecord("zh", text))))
        show(result)
        assertTrue(textLayout(text).hasVisualOverflow)
        compose.onNodeWithTag("reader-copy-0").performClick()
        assertClipboard(text)
        compose.onNodeWithText("展开全文").performScrollTo().performClick()
        assertFalse(textLayout(text).hasVisualOverflow)
        compose.onNodeWithText("收起全文").performScrollTo().performClick()
        assertTrue(textLayout(text).hasVisualOverflow)
    }

    @Test
    fun rawDataIsFoldedAndCopyKeepsBytesBeyondThePreview() {
        val bytes = ByteArray(1024) { it.toByte() }
        val result = parseNfcReadResult(context, listOf(NdefMessage(
            NdefRecord(NdefRecord.TNF_MIME_MEDIA, "application/octet-stream".toByteArray(), byteArrayOf(), bytes)
        )))
        val state = mutableStateOf(result)
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) { NFCReaderScreen(state.value, false) }
        }
        compose.onNodeWithTag("reader-raw-0").assertDoesNotExist()
        compose.onNodeWithTag("reader-raw-toggle-0").performClick()
        compose.onNodeWithTag("reader-results").performScrollToNode(hasText("复制完整载荷"))
        compose.onNodeWithText("仅预览前 256 字节，复制可获取完整载荷。").assertExists()
        compose.onNodeWithText("复制完整载荷").performClick()
        assertClipboard(bytes.toDisplayHex())
        compose.runOnIdle {
            state.value = parseNfcReadResult(context, listOf(NdefMessage(NdefRecord.createTextRecord("en", "new tag"))))
        }
        compose.onNodeWithText("读取 NFC 标签").assertIsDisplayed()
        compose.onNodeWithText("new tag").assertIsDisplayed()
        compose.onNodeWithTag("reader-raw-0").assertDoesNotExist()
    }

    @Test
    fun tagDetailsAreOptionalAndAnEmptyReadDoesNotShowPreviousContent() {
        val result = parseNfcReadResult(context, listOf(NdefMessage(NdefRecord.createTextRecord("en", "old tag"))))
            .copy(tagId = "04 A1 B2 C3", technologies = listOf("NfcA", "Ndef"), capacity = 888, isWritable = true)
        val state = mutableStateOf(result)
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) { NFCReaderScreen(state.value, false) }
        }
        compose.onNodeWithText("标签 UID").assertDoesNotExist()
        compose.onNodeWithText("查看标签信息").performScrollTo().performClick()
        compose.onNodeWithText("04 A1 B2 C3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("可写入").assertExists()
        compose.runOnIdle { state.value = result.copy(records = emptyList(), messageBytes = 0) }
        compose.onNodeWithText("old tag").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reader_no_records)).assertIsDisplayed()
        compose.onNodeWithText("复制全部内容").assertDoesNotExist()
    }

    @Test
    fun tagMetadataCanBeBrowsedWithoutNdefAndResetsOnTheNextScan() {
        val result = parseNfcReadResult(context, emptyList(), discoveryTag()).copy(messageBytes = 233)
        val state = mutableStateOf(result)
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) { NFCReaderScreen(state.value, false) }
        }
        compose.onNodeWithText("NDEF 已用 / 容量：233 / 716 字节").assertIsDisplayed()
        compose.onNodeWithText("ATQA（寻卡响应）").assertDoesNotExist()
        compose.onNodeWithText("查看标签信息").performClick()
        listOf("0x0004", "0x08", "1024 字节", "16 个扇区，每区 4 块，每块 16 字节", "NXP MIFARE Classic", "支持设为只读（系统接口）", "可写入").forEach {
            compose.onNodeWithTag("reader-results").performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.runOnIdle { state.value = result.copy(tagId = "new tag") }
        compose.onNodeWithText("查看标签信息").assertIsDisplayed()
        compose.onNodeWithText("0x0004").assertDoesNotExist()
    }

    @Test
    fun configurationFieldsWrapOnANarrowScreenWithLargeFonts() {
        val ssid = "办公室无线网络（支持多行显示的名称）"
        val fields = listOf(
            NfcRecordField(R.string.reader_field_ssid, ssid),
            NfcRecordField(R.string.reader_field_password, "pass:word with spaces"),
            NfcRecordField(R.string.reader_field_auth, "WPA2-PSK"),
            NfcRecordField(R.string.reader_field_encryption, "AES"),
        )
        val record = NfcReadRecord(NfcRecordKind.WIFI, fields, byteArrayOf(), "application/vnd.wfa.wsc", 2, byteArrayOf())
        val result = NfcReadResult(listOf(record), 96)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                MyApplicationTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { NFCReaderScreen(result, false) }
                }
            }
        }
        compose.onNodeWithText(ssid).performScrollTo().assertIsDisplayed()
        assertFalse(textLayout(ssid).hasVisualOverflow)
        compose.onNodeWithText("WPA2-PSK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader-copy-0").performScrollTo().performClick()
        assertClipboard(record.copyText(context))
    }

    private fun show(result: NfcReadResult) {
        compose.setContent {
            MyApplicationTheme(dynamicColor = false) { NFCReaderScreen(result, false) }
        }
    }

    private fun assertClipboard(expected: String) {
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureReaderScreenshots") != "true") return
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(context.getExternalFilesDir("reader-ui")).apply { mkdirs() }
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
