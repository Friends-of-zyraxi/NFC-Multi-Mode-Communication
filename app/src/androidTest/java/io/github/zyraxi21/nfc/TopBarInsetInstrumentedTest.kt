package io.github.zyraxi21.nfc

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopBarInsetInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aboutEntryRemainsAccessibleAndCompactTitleIncludesTheStatusBar() {
        if (compose.onAllNodesWithTag("app-navigation-rail").fetchSemanticsNodes().isNotEmpty()) {
            val rail = compose.onNodeWithTag("app-navigation-rail").fetchSemanticsNode().boundsInWindow
            assertEquals("侧栏背景应从窗口边缘开始", 0f, rail.left, 1f)
            compose.onNodeWithTag("rail-about").performScrollTo().performClick()
            compose.onNodeWithTag("about-sheet").assertExists()
            return
        }
        val title = compose.onNode(hasText(compose.activity.getString(R.string.app_title)) and hasClickAction())
        val bounds = title.fetchSemanticsNode().boundsInWindow
        val inset = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        assertTrue("设备应保留状态栏以验证顶部水波纹范围", inset > 0)
        assertEquals("标题点击区应从屏幕顶端开始", 0f, bounds.top, 1f)
        assertTrue("标题文字应位于状态栏下方", bounds.height > inset)
        title.performClick()
        compose.onNodeWithTag("about-sheet").assertExists()
    }
}
