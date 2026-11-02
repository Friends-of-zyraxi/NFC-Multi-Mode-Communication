package io.github.zyraxi21.nfc.cast

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastActivityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<CastActivity>()

    @Test fun fullscreenUsesEntireCutoutWindowInPortraitAndLandscape() {
        val state = mutableStateOf(CastUiState(CastStage.RECEIVING, width = 1080, height = 2400, hasFrame = true))
        fun showReceiver() { compose.runOnUiThread { compose.activity.setContent { MyApplicationTheme {
            CastScreen(state.value, false, null, {}, {}, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                compose.activity::setFullscreen, {}, {})
        } } } }
        showReceiver()
        compose.onNodeWithText("全屏查看").performClick()
        fun checkWindow() {
            compose.waitUntil(5000) {
                val bounds = compose.onNodeWithTag("cast-viewport").fetchSemanticsNode().boundsInWindow
                val window = compose.activity.windowManager.currentWindowMetrics.bounds
                kotlin.math.abs(bounds.width - window.width()) < 2 && kotlin.math.abs(bounds.height - window.height()) < 2
            }
            val viewport = compose.onNodeWithTag("cast-viewport").fetchSemanticsNode().boundsInWindow
            val video = compose.onNodeWithTag("cast-video").fetchSemanticsNode().boundsInWindow
            assertEquals(0f, viewport.left, 1f); assertEquals(0f, viewport.top, 1f)
            assertTrue(kotlin.math.abs(viewport.width - video.width) < 2 || kotlin.math.abs(viewport.height - video.height) < 2)
        }
        try {
            checkWindow()
            compose.runOnIdle {
                state.value = state.value.copy(width = 1920, height = 1080)
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            // 配置变化可重建 Activity；测试内容需重新安装，生产页由服务恢复接收状态。
            if (compose.onAllNodesWithTag("cast-viewport").fetchSemanticsNodes().isEmpty()) {
                showReceiver()
                compose.waitForIdle()
                if (compose.onAllNodesWithText("全屏查看").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("全屏查看").performClick()
                }
            }
            checkWindow()
            if (compose.onAllNodesWithText("退出全屏").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("cast-viewport").performTouchInput { click(center) }
            }
            compose.onNodeWithText("退出全屏").assertIsDisplayed()
        } finally { compose.runOnIdle {
            compose.activity.setFullscreen(false)
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } }
    }

    @Test fun realActivityBindsIdleServiceAndSurvivesLandscapeWithoutStartingCapture() {
        compose.onNodeWithText("生成 NFC 邀请").assertIsDisplayed()
        compose.onNodeWithText("碰一碰加入").assertIsDisplayed()
        compose.runOnIdle { assertFalse(CastSessionService.ownsNearby)
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithText("屏幕共享").assertIsDisplayed()
        compose.runOnIdle { assertFalse(CastSessionService.ownsNearby)
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithText("生成 NFC 邀请").assertIsDisplayed()
    }
}
