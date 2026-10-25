package io.github.zyraxi21.nfc

import android.Manifest
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.WriteState
import io.github.zyraxi21.nfc.util.HceDataStore
import io.github.zyraxi21.nfc.util.createApplicationNdefMessage
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
            compose.onNodeWithText("应用: com.android.settings").assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                activity.startActivity(
                    nfcIntent("com.example.another").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            compose.waitForIdle()
            compose.onNodeWithText("应用: com.example.another").assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun applicationFormArmsWritingAndPersistsTheSameRecordForHce() {
        // 分页会预加载 P2P 页，预先授予其权限，避免系统弹窗中断写卡界面测试。
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("写卡/卡模拟").performClick()
            compose.onNodeWithText("类型：文本").performClick()
            compose.onNodeWithText("应用程序").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("com.example.target")
            compose.onNodeWithText("写入标签").performScrollTo().performClick()
            scenario.onActivity { assertEquals(WriteState.WAITING_FOR_CARD, it.writeState) }
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("卡模拟").performScrollTo().performClick()
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

    private fun nfcIntent(packageName: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, MainActivity::class.java)
            .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
            .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(createApplicationNdefMessage(packageName)))
    }
}
