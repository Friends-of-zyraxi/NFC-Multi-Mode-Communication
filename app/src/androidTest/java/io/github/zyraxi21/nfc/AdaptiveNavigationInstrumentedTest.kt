package io.github.zyraxi21.nfc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import io.github.zyraxi21.nfc.ui.theme.NavigationItem
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
}
