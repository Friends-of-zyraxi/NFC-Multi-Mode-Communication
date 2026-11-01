package io.github.zyraxi21.nfc

import android.Manifest
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.WriteState
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.util.HceDataStore
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createApplicationNdefMessage
import io.github.zyraxi21.nfc.util.createNfcWriteMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationReadInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun applicationRecordsOnlyDisplayInformationOnColdStartAndNewIntent() {
        ActivityScenario.launch<MainActivity>(nfcIntent("com.android.settings")).use { scenario ->
            compose.onNodeWithText("应用包名").assertIsDisplayed()
            compose.onNodeWithText("com.android.settings").assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                activity.startActivity(
                    nfcIntent("com.example.another").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            compose.waitForIdle()
            compose.onNodeWithText("com.example.another").assertIsDisplayed()
            compose.onNodeWithText("com.android.settings").assertDoesNotExist()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun uriWifiAndBluetoothOnlyDisplayInformationOnColdStartAndNewIntents() {
        grantNearbyPermissions()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val records = listOf(
            NfcWriteRecord.Link("https://example.org/read-only"),
            NfcWriteRecord.Wifi("Read-only network", "12345678", WifiEncryption.AES, WifiAuth.WPA2_PSK),
            NfcWriteRecord.Bluetooth("01:23:45:67:89:AB", "Read-only device"),
        )
        fun intent(record: NfcWriteRecord) = Intent(context, MainActivity::class.java)
            .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
            .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(createNfcWriteMessage(listOf(record))))
        ActivityScenario.launch<MainActivity>(intent(records.first())).use { scenario ->
            compose.onNodeWithText("https://example.org/read-only").assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            records.drop(1).forEach { record ->
                scenario.onActivity { it.startActivity(intent(record).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                val value = when (record) {
                    is NfcWriteRecord.Wifi -> record.ssid
                    is NfcWriteRecord.Bluetooth -> record.name
                    else -> error("Unexpected record")
                }
                // startActivity 返回时，系统可能尚未把新 Intent 交给 Activity。
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag("reader-results")))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("reader-results").performScrollToNode(hasText(value))
                compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("reader-results"))).assertIsDisplayed()
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { assertEquals(WriteState.IDLE, it.writeState) }
            }
        }
    }

    @Test
    fun applicationFormArmsWritingAndPersistsTheSameRecordForHce() {
        // 分页会预加载 P2P 页，预先授予其权限，避免系统弹窗中断写卡界面测试。
        grantNearbyPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("写卡/卡模拟").performClick()
            compose.onNodeWithText("类型：文本").performClick()
            compose.onNodeWithText("应用程序").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("com.example.target")
            compose.onNodeWithTag("write-records").performScrollToNode(hasText("写入标签"))
            compose.onNodeWithText("写入标签").performClick()
            scenario.onActivity { assertEquals(WriteState.WAITING_FOR_CARD, it.writeState) }
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("write-records").performScrollToNode(hasText("卡模拟"))
            compose.onNodeWithText("卡模拟").performClick()
            scenario.onActivity { activity ->
                assertEquals(WriteState.EMULATING, activity.writeState)
                assertEquals("APPLICATION", MyHostApduService.emulatedDataType)
                val bytes = requireNotNull(MyHostApduService.emulatedData)
                assertEquals("com.example.target", String(NdefMessage(bytes).records.single().payload, Charsets.UTF_8))
                assertArrayEquals(bytes, requireNotNull(HceDataStore.load(activity)).first)
            }
            compose.onNodeWithText("停止模拟").performClick()
            scenario.onActivity { activity ->
                assertEquals(WriteState.IDLE, activity.writeState)
                assertNull(MyHostApduService.emulatedData)
                assertNull(HceDataStore.load(activity))
            }
        }
    }

    @Test
    fun multipleRecordsSurviveActivityRecreationAndEmulateAsOneMessage() {
        grantNearbyPermissions()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("写卡/卡模拟").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("第一条内容")
            addRecord()
            compose.onNodeWithText("类型：文本").performClick()
            compose.onNodeWithText("网址").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("https://example.com/multi")
            addRecord()
            compose.onNodeWithText("类型：文本").performClick()
            compose.onNodeWithText("应用程序").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("com.android.settings")
            compose.onNodeWithTag("write-records").performScrollToNode(hasText("写入标签"))
            compose.onNodeWithText("写入标签").performClick()
            scenario.onActivity { assertEquals(WriteState.WAITING_FOR_CARD, it.writeState) }
            scenario.recreate()
            scenario.onActivity { assertEquals(WriteState.WAITING_FOR_CARD, it.writeState) }
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("write-records").performScrollToNode(hasText("卡模拟"))
            compose.onNodeWithText("卡模拟").performClick()
            var encoded = byteArrayOf()
            scenario.onActivity { activity ->
                assertEquals(WriteState.EMULATING, activity.writeState)
                assertEquals("MULTI", MyHostApduService.emulatedDataType)
                encoded = requireNotNull(MyHostApduService.emulatedData)
                val message = NdefMessage(encoded)
                assertEquals(3, message.records.size)
                assertArrayEquals(NdefRecord.RTD_TEXT, message.records[0].type)
                assertEquals("https://example.com/multi", message.records[1].toUri().toString())
                assertEquals("com.android.settings", String(message.records[2].payload, Charsets.UTF_8))
                assertArrayEquals(encoded, requireNotNull(HceDataStore.load(activity)).first)
            }
            scenario.recreate()
            scenario.onActivity { assertEquals(WriteState.EMULATING, it.writeState) }
            compose.onNodeWithText("停止模拟").performClick()
            compose.onNodeWithText("读卡").performClick()
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(encoded))))
            }
            compose.onNodeWithText("3 条记录").assertIsDisplayed()
            compose.onNode(hasText("第一条内容") and hasAnyAncestor(hasTestTag("reader-results"))).assertIsDisplayed()
            compose.onNodeWithTag("reader-results").performScrollToNode(hasText("com.android.settings"))
            compose.onNode(hasText("com.android.settings") and hasAnyAncestor(hasTestTag("reader-results"))).assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun addRecord() {
        compose.onNodeWithTag("write-records").performScrollToNode(hasText("添加记录"))
        compose.onNodeWithText("添加记录").performClick()
    }

    private fun grantNearbyPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        permissions.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
    }

    private fun nfcIntent(packageName: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, MainActivity::class.java)
            .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
            .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(createApplicationNdefMessage(packageName)))
    }
}
