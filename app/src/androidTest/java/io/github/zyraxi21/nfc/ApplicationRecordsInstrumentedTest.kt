package io.github.zyraxi21.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.WriteCardScreen
import io.github.zyraxi21.nfc.ui.theme.WriteDataType
import io.github.zyraxi21.nfc.ui.theme.WriteState
import io.github.zyraxi21.nfc.util.createApplicationNdefMessage
import io.github.zyraxi21.nfc.util.loadInstalledApplications
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationRecordsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun applicationMessageRoundTripsAsSingleStandardAar() {
        val message = NdefMessage(createApplicationNdefMessage(" com.example.target ").toByteArray())
        val record = message.records.single()
        assertEquals(NdefRecord.TNF_EXTERNAL_TYPE, record.tnf)
        assertArrayEquals("android.com:pkg".toByteArray(Charsets.US_ASCII), record.type)
        assertEquals("com.example.target", String(record.payload, Charsets.UTF_8))
    }

    @Test
    fun manualPackageIsValidatedAndUsedForBothWritingAndEmulation() {
        var writtenPackage: String? = null
        var emulatedPackage: String? = null
        var emulatedType: WriteDataType? = null
        compose.setContent {
            MyApplicationTheme {
                WriteCardScreen(
                    writeState = WriteState.IDLE,
                    writeStatusMessage = "",
                    onWriteText = {}, onWriteUrl = {}, onWriteWifi = { _, _, _, _ -> },
                    onWriteBluetooth = { _, _ -> },
                    onWriteApplication = { writtenPackage = it },
                    onStartEmulation = { type, _, _, _, _, _, _, _, packageName ->
                        emulatedType = type
                        emulatedPackage = packageName
                    }
                )
            }
        }
        selectApplicationType()
        compose.onNodeWithText("写入标签").assertIsNotEnabled()
        compose.onNodeWithText("卡模拟").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("应用名称")
        compose.onNodeWithText("写入标签").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement(" com.example.target ")
        compose.onNodeWithText("写入标签").performScrollTo().performClick()
        compose.onNodeWithText("卡模拟").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("com.example.target", writtenPackage)
            assertEquals("com.example.target", emulatedPackage)
            assertEquals(WriteDataType.APPLICATION, emulatedType)
        }
    }

    @Test
    fun installedApplicationCanBeSearchedAndSelectedByPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applications = loadInstalledApplications(context.packageManager)
        assertTrue(applications.any { it.packageName == context.packageName })
        assertEquals(applications.size, applications.distinctBy { it.packageName }.size)
        var writtenPackage: String? = null
        compose.setContent {
            MyApplicationTheme {
                WriteCardScreen(
                    writeState = WriteState.IDLE,
                    writeStatusMessage = "",
                    onWriteText = {}, onWriteUrl = {}, onWriteWifi = { _, _, _, _ -> },
                    onWriteBluetooth = { _, _ -> },
                    onWriteApplication = { writtenPackage = it }
                )
            }
        }
        selectApplicationType()
        compose.onNodeWithText("从本机应用中选择").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            .performTextInput(context.packageName)
        val applicationRow = hasText(context.packageName) and !hasSetTextAction()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(applicationRow).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(applicationRow).performClick()
        compose.onNodeWithText("选择应用程序").assertDoesNotExist()
        compose.onNodeWithText("写入标签").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(context.packageName, writtenPackage) }
    }

    private fun selectApplicationType() {
        compose.onNodeWithText("类型：文本").performClick()
        compose.onNodeWithText("应用程序").performClick()
    }
}
