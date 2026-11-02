package io.github.zyraxi21.nfc.cast

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zyraxi21.nfc.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectCastEntryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedP2pOffersDirectEntryAndShowsIncomingInvitationWithoutRequiringNfc() {
        val requested = mutableStateOf(false)
        val state = mutableStateOf(ConnectionState.CONNECTED)
        var opened = 0
        compose.setContent { MyApplicationTheme {
            P2PScreen(state.value, null, true, true, null, {}, {}, {}, {}, {}, {}, {}, {}, true, {},
                onOpenScreenShare = { opened++ }, screenSharingRequested = requested.value)
        } }
        compose.onNodeWithTag("p2p-screen-sharing").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); requested.value = true }
        compose.onNodeWithText("接受并进入屏幕共享").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, opened); state.value = ConnectionState.DISCONNECTED }
        compose.onNodeWithTag("p2p-screen-sharing").assertDoesNotExist()
    }

    @Test fun codeVerificationCannotStartCaptureAndWaitsForTheOtherDevice() {
        val state = mutableStateOf(CastUiState(CastStage.VERIFYING, connectionCode = "1234"))
        var confirmations = 0
        var captures = 0
        var ended = 0
        compose.setContent { MyApplicationTheme {
            CastScreen(state.value, false, null, {}, {}, null, {}, {}, {}, {}, { captures++ }, {},
                { ended++ }, {}, {}, {}, {}, {}, {}, onConfirmCode = { confirmations++ })
        } }
        compose.onNodeWithTag("cast-connection-code").assertTextEquals("1234")
        compose.onNodeWithText("授权并开始共享").assertDoesNotExist()
        compose.onNodeWithText("验证码一致，继续").performClick()
        compose.runOnIdle { assertEquals(1, confirmations); assertEquals(0, captures); state.value = state.value.copy(codeConfirmed = true) }
        compose.onNodeWithText("验证码一致，继续").assertDoesNotExist()
        compose.onNodeWithText("已确认，等待对方确认验证码…").assertIsDisplayed()
        compose.onNodeWithText("不一致，断开连接").performClick()
        compose.runOnIdle { assertEquals(1, ended); assertEquals(0, captures) }
    }


}
