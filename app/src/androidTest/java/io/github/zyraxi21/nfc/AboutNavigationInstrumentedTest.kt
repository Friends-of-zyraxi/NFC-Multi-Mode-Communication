package io.github.zyraxi21.nfc

import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.BottomNavigationApp
import io.github.zyraxi21.nfc.ui.theme.FluentText
import io.github.zyraxi21.nfc.ui.theme.FluentTextStyle
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutNavigationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleOpensAboutWithInstalledVersionAndClosePreservesSelectedTab() {
        showApp(darkTheme = false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))

        compose.onNodeWithText(context.getString(R.string.nav_write)).performClick()
        compose.onNodeWithText("写卡内容").assertIsDisplayed()
        openAbout()
        compose.onNodeWithTag("about-sheet").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_version, info.versionName.orEmpty()))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_maintainer)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_close)).performScrollTo().performClick()
        compose.onNodeWithTag("about-sheet").assertIsNotDisplayed()
        compose.onNodeWithText("写卡内容").assertIsDisplayed()
    }

    @Test
    fun titleOpensAboutInDarkThemeAndBackDismissesIt() {
        showApp(darkTheme = true)
        openAbout()
        compose.onNodeWithTag("about-sheet").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("about-sheet").assertIsNotDisplayed()
        compose.onNodeWithText("读卡内容").assertIsDisplayed()
    }

    private fun openAbout() {
        if (compose.onAllNodesWithTag("rail-about").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("rail-about").performScrollTo().performClick()
        } else {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNode(hasText(context.getString(R.string.app_title)) and hasClickAction()).performClick()
        }
    }

    private fun showApp(darkTheme: Boolean) {
        compose.setContent {
            MyApplicationTheme(darkTheme = darkTheme, dynamicColor = false) {
                BottomNavigationApp(
                    readerScreen = { FluentText(text = "读卡内容", style = FluentTextStyle.Body1) },
                    writeScreen = { FluentText(text = "写卡内容", style = FluentTextStyle.Body1) },
                    p2pScreen = { FluentText(text = "端对端内容", style = FluentTextStyle.Body1) }
                )
            }
        }
    }
}
