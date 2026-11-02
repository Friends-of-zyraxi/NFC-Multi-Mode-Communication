package io.github.zyraxi21.nfc.cast

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NFCReaderScreen
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CastUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readingInviteDoesNotJoinUntilTheRecordButtonIsClicked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invite = CastInvite.create()
        val actions = mutableListOf<RecordAction>()
        compose.setContent { MyApplicationTheme {
            NFCReaderScreen(parseNfcReadResult(context, listOf(invite.ndef(context.packageName))), false,
                onRecordAction = { actions += it })
        } }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("reader-results").performScrollToNode(hasText("加入屏幕共享"))
        compose.onNodeWithText("加入屏幕共享").performClick()
        compose.runOnIdle {
            assertEquals(invite, CastInvite.decode((actions.single() as RecordAction.ScreenShare).inviteBytes))
        }
    }

    @Test fun choosingDirectionAndAcceptingDoNotBypassTheExplicitCaptureButton() {
        val state = mutableStateOf(CastUiState(CastStage.READY))
        val requests = mutableListOf<Boolean>()
        var captures = 0
        var accepted = 0
        compose.setContent { MyApplicationTheme { screen(state.value, direction = { requests += it }, capture = { captures++ }, accept = { accepted++ }) } }
        compose.onNodeWithText("共享本机屏幕").performClick()
        compose.onNodeWithText("查看对方屏幕").performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), requests); assertEquals(0, captures); state.value = CastUiState(CastStage.OFFER_IN, localSender = true) }
        compose.onNodeWithText("同意").performClick()
        compose.runOnIdle { assertEquals(1, accepted); assertEquals(0, captures); state.value = CastUiState(CastStage.PREPARING, localSender = true) }
        compose.onNodeWithText("授权并开始共享").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
    }

    @Test fun receiverPreservesPortraitAndLandscapeAspectAndHasExplicitFullscreenExit() {
        val state = mutableStateOf(CastUiState(CastStage.RECEIVING, localSender = false, width = 720, height = 1280))
        var stopped = 0
        compose.setContent { MyApplicationTheme { screen(state.value, stop = { stopped++ }) } }
        val portrait = compose.onNodeWithTag("cast-video").fetchSemanticsNode().boundsInRoot
        assertEquals(720f / 1280, portrait.width / portrait.height, 0.02f)
        compose.runOnIdle { state.value = state.value.copy(width = 1280, height = 720) }
        val landscape = compose.onNodeWithTag("cast-video").fetchSemanticsNode().boundsInRoot
        assertEquals(1280f / 720, landscape.width / landscape.height, 0.02f)
        compose.onNodeWithText("全屏查看").performClick()
        compose.onNodeWithText("结束共享").assertDoesNotExist()
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithText("结束共享").performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
    }

    @Test fun inviteHomeUsesFluentControlsInBothThemes() {
        val dark = mutableStateOf(false)
        compose.setContent { MyApplicationTheme(darkTheme = dark.value, dynamicColor = false) { screen(CastUiState()) } }
        compose.onNodeWithText("生成 NFC 邀请").assertIsDisplayed()
        compose.onNodeWithText("碰一碰加入").assertIsDisplayed()
        screenshot("cast-home-light.png")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        screenshot("cast-home-dark.png")
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        val bitmap = compose.onNodeWithTag("cast-screen").captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun screen(state: CastUiState, direction: (Boolean) -> Unit = {}, capture: () -> Unit = {},
        accept: () -> Unit = {}, stop: () -> Unit = {}, surface: (Surface?) -> Unit = {}) {
        CastScreen(state, false, null, {}, {}, null, {}, direction, accept, {}, capture, stop,
            {}, {}, {}, surface, {}, {}, {})
    }
}
