package io.github.zyraxi21.nfc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NavigationItem
import io.github.zyraxi21.nfc.ui.theme.NFCReaderScreen
import io.github.zyraxi21.nfc.ui.theme.P2PScreen
import io.github.zyraxi21.nfc.ui.theme.ConnectionState
import io.github.zyraxi21.nfc.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resizingKeepsSelectedPageAndSwitchesBetweenBarAndRail() {
        val width = mutableStateOf(480.dp)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MyApplicationTheme(dynamicColor = false) {
            Box(Modifier.width(width.value).fillMaxHeight()) {
                BottomNavigationApp(readerScreen = { BasicText("read", Modifier.testTag("read-page")) },
                    writeScreen = { BasicText("write", Modifier.testTag("write-page")) },
                    p2pScreen = { BasicText("p2p", Modifier.testTag("p2p-page")) })
            }
        } }
        compose.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nav_p2p)).performClick()
        compose.onNodeWithTag("p2p-page").assertIsDisplayed()
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithTag("app-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("app-bottom-navigation").assertDoesNotExist()
        compose.onNodeWithTag("p2p-page").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nav_write)).performClick()
        compose.onNodeWithTag("write-page").assertIsDisplayed()
        compose.runOnIdle { width.value = 480.dp }
        compose.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        compose.onNodeWithTag("write-page").assertIsDisplayed()
    }

    @Test fun devicesWithoutNfcCanStartAtPeerToPeerPage() {
        compose.setContent { MyApplicationTheme {
            BottomNavigationApp(readerScreen = {}, writeScreen = {},
                p2pScreen = { BasicText("p2p", Modifier.testTag("p2p-page")) }, initialItem = NavigationItem.P2P)
        } }
        compose.onNodeWithTag("p2p-page").assertIsDisplayed()
    }

    @Test fun wideNavigationFadesWithoutSlidingAndPreservesDraftAcrossLayoutChanges() {
        val width = mutableStateOf(1000.dp)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MyApplicationTheme(dynamicColor = false) {
            Box(Modifier.width(width.value).fillMaxHeight()) {
                BottomNavigationApp(readerScreen = { Box(Modifier.fillMaxSize().testTag("read-page")) },
                    writeScreen = { Box(Modifier.fillMaxSize().testTag("write-page")) {
                        val draft = rememberSaveable { mutableStateOf("") }
                        BasicTextField(draft.value, { draft.value = it }, Modifier.testTag("draft-input"))
                    } }, p2pScreen = { BasicText("p2p", Modifier.testTag("p2p-page")) },
                    safeDrawingInsets = WindowInsets(0, 0, 0, 0))
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(context.getString(R.string.nav_write)).performClick()
        compose.mainClock.advanceTimeBy(150)
        val page = compose.onNodeWithTag("app-wide-pages").fetchSemanticsNode().boundsInRoot
        val incoming = compose.onNodeWithTag("write-page").fetchSemanticsNode().boundsInRoot
        assertEquals("淡入中的页面不应横向移动", page.left, incoming.left, 1f)
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("draft-input").performTextInput("draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnIdle { width.value = 480.dp }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("draft-input").assertTextContains("draft")
        compose.runOnIdle { width.value = 1000.dp }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("app-wide-pages").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("draft-input").assertTextContains("draft")
        compose.onNodeWithTag("p2p-page").assertIsNotDisplayed()
    }

    @Test fun cutoutInsetsProtectControlsWithoutLeavingAnEmptyStripOutsideTheRail() {
        var expectedBackground = 0
        compose.setContent { MyApplicationTheme(dynamicColor = false) {
            expectedBackground = AppTheme.surface.toArgb()
            Box(Modifier.width(1000.dp).height(360.dp)) {
                BottomNavigationApp(readerScreen = {}, writeScreen = {}, p2pScreen = {},
                    safeDrawingInsets = WindowInsets(left = 40.dp, top = 24.dp, right = 0.dp, bottom = 16.dp))
            }
        } }
        val root = compose.onNodeWithTag("app-shell").fetchSemanticsNode().boundsInRoot
        val rail = compose.onNodeWithTag("app-navigation-rail").fetchSemanticsNode().boundsInRoot
        val read = compose.onNodeWithTag("rail-tab-READ").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("app-page-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(root.left, rail.left, 1f)
        assertEquals(root.top, rail.top, 1f)
        assertTrue(read.left >= rail.left + with(compose.density) { 40.dp.toPx() } - 1)
        assertEquals(root.top + with(compose.density) { 24.dp.toPx() }, viewport.top, 1f)
        assertEquals(expectedBackground, compose.onNodeWithTag("app-navigation-rail").captureToImage().asAndroidBitmap().getPixel(0, 0))
        compose.onNodeWithTag("rail-about").assertIsDisplayed()
    }

    @Test fun wideIdlePagesUseTheAvailableHeightAndReaderPromptFitsShortLandscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MyApplicationTheme(dynamicColor = false) {
            Box(Modifier.width(1000.dp).height(360.dp)) {
                BottomNavigationApp(readerScreen = { NFCReaderScreen(null, false) }, writeScreen = {},
                    p2pScreen = { P2PScreen(ConnectionState.DISCONNECTED, null, true, true, null,
                        {}, {}, {}, {}, {}, {}, {}, {}, true, {}) },
                    safeDrawingInsets = WindowInsets(0, 0, 0, 0))
            }
        } }
        val viewport = compose.onNodeWithTag("app-page-viewport").fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText(context.getString(R.string.reader_title)).fetchSemanticsNode().boundsInRoot
        val prompt = compose.onNodeWithTag("reader-scan-prompt").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.y, (heading.top + prompt.bottom) / 2, 2f)
        assertTrue(prompt.bottom <= viewport.bottom)
        compose.onNodeWithText(context.getString(R.string.nav_p2p)).performClick()
        val title = compose.onNodeWithText(context.getString(R.string.p2p_title)).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(context.getString(R.string.p2p_button_discover)).fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.y, (title.top + button.bottom) / 2, 2f)
    }

    @Test fun shortLandscapeRemainsScrollableWithLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MyApplicationTheme(dynamicColor = false) {
                    Box(Modifier.width(1000.dp).height(320.dp)) {
                        BottomNavigationApp(readerScreen = { NFCReaderScreen(null, false) }, writeScreen = {}, p2pScreen = {},
                            safeDrawingInsets = WindowInsets(0, 0, 0, 0))
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.reader_supported_formats)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rail-about").performScrollTo().assertIsDisplayed()
    }
}
