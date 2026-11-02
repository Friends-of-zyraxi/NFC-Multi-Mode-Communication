package io.github.zyraxi21.nfc.cast

import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
