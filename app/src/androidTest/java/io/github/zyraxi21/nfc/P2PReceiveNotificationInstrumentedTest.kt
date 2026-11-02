package io.github.zyraxi21.nfc

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.Payload
import com.microsoft.fluentui.tokenized.notification.SNACK_BAR
import io.github.zyraxi21.nfc.cast.RecordNearbyLink
import io.github.zyraxi21.nfc.cast.RecordNearbyPeer
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createNearbyPayload
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource

/** 通过真实 Activity 的 Nearby 接收回调验证提示，避免重组和后台事件补播。 */
@RunWith(AndroidJUnit4::class)
class P2PReceiveNotificationInstrumentedTest {
    @get:Rule(order = 0) val permissions: ExternalResource = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            buildList {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }.forEach { instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it) }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val peer = RecordNearbyPeer("notification-peer", ByteArray(32) { it.toByte() }, "1234", true)
    private val bytes = createNearbyPayload(listOf(NfcWriteRecord.Text("收到的文本"),
        NfcWriteRecord.Link("https://example.org/")))
    private val notification get() = compose.activity.getString(R.string.p2p_message_received, 2)

    @Before fun connect() {
        compose.runOnIdle {
            RecordNearbyLink.rememberConnected(peer)
            // 仅注入连接成功事件，消息仍通过生产环境的路由与接收回调处理。
            val field = MainActivity::class.java.getDeclaredField("recordConnectionCallbacks").apply { isAccessible = true }
            (field.get(compose.activity) as ConnectionLifecycleCallback)
                .onConnectionResult(peer.endpoint, ConnectionResolution(Status.RESULT_SUCCESS))
        }
        compose.onNodeWithTag("p2p-send-tab").assertIsDisplayed()
    }

    @After fun disconnect() { compose.runOnIdle { RecordNearbyLink.reset() } }

    private fun receive(payload: ByteArray = bytes, endpoint: String = peer.endpoint) {
        RecordNearbyLink.payloads.onPayloadReceived(endpoint, Payload.fromBytes(payload))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitDismissal() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(SNACK_BAR).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun receivingNotifiesWithoutChangingTheDraftAndIdenticalMessagesNotifyAgain() {
        val draft = hasSetTextAction() and hasAnyAncestor(hasTestTag("p2p-records"))
        compose.onNode(draft).performTextReplacement("待发送草稿")
        receive()
        compose.onNodeWithText(notification).assertIsDisplayed()
        compose.onNodeWithTag("p2p-send-tab").assertIsSelected()
        compose.onNode(draft).assertTextContains("待发送草稿")
        awaitDismissal()
        compose.onNodeWithTag("p2p-received-tab").performClick()
        compose.onNodeWithTag("p2p-received").performScrollToNode(hasText("收到的文本"))
        compose.onNodeWithText(notification).assertDoesNotExist()
        compose.onNodeWithTag("p2p-send-tab").performClick()
        compose.onNodeWithText(notification).assertDoesNotExist()
        receive()
        compose.onNodeWithText(notification).assertIsDisplayed()
    }

    @Test fun backgroundReceiptsRemainReadableWithoutReplayingTheirSnackbarOnResume() {
        awaitDismissal()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        receive()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(notification).assertDoesNotExist()
        compose.onNodeWithTag("p2p-received-tab").performClick()
        compose.onNodeWithTag("p2p-received").performScrollToNode(hasText("收到的文本"))
        compose.onNodeWithText("收到的文本").assertIsDisplayed()
        receive(endpoint = "previous-peer")
        compose.onNodeWithText(notification).assertDoesNotExist()
    }

    @Test fun malformedMessagesShowAWarningInsteadOfASuccessReceipt() {
        receive(byteArrayOf(1, 2, 3))
        compose.onNodeWithText(compose.activity.getString(R.string.p2p_invalid_payload)).assertIsDisplayed()
        compose.onNodeWithText(notification).assertDoesNotExist()
    }
}
