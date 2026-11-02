package io.github.zyraxi21.nfc.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.BuildConfig
import io.github.zyraxi21.nfc.ui.theme.AboutBottomSheet
import io.github.zyraxi21.nfc.ui.theme.FluentButton
import io.github.zyraxi21.nfc.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 确保检查反馈显示在关于面板内，且新版本必须经用户确认后才下载。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule
    val compose = createComposeRule()
    private val store = ViewModelStore()
    private val downloader = FakeDownloader()

    @Test fun developmentBuildHidesAllUpdateActionsWithItsProductionFactory(): Unit = with(compose) {
        assertFalse(BuildConfig.APP_UPDATES_ENABLED)
        setContent {
            MyApplicationTheme {
                AboutBottomSheet { openAbout ->
                    Column(Modifier.padding(32.dp)) { FluentButton(text = "打开关于", onClick = openAbout) }
                }
            }
        }
        onNodeWithText("打开关于").performClick()
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        onNodeWithTag("about-sheet").assertIsDisplayed()
        onNodeWithText(host.getString(R.string.update_disabled_debug)).assertIsDisplayed()
        onNodeWithTag("update-section").assertDoesNotExist()
        onNodeWithTag("update-dialog").assertDoesNotExist()
        listOf(R.string.update_check, R.string.update_view_details, R.string.update_download_now,
            R.string.update_cancel_download, R.string.update_install, R.string.update_view_downloads,
            R.string.update_view_release).forEach { onNodeWithText(host.getString(it)).assertDoesNotExist() }
        onNodeWithText(host.getString(R.string.about_repository)).assertIsDisplayed()
    }

    @Test fun latestVersionFeedbackRemainsVisibleAboveTheSheet() = with(compose) {
        try {
            launchAbout(UpdateCheckResult.UpToDate)
            val host = InstrumentationRegistry.getInstrumentation().targetContext
            onNodeWithText(host.getString(R.string.update_check)).performScrollTo().performClick()
            onNode(hasText(host.getString(R.string.update_current)) and hasAnyAncestor(hasTestTag("about-sheet")))
                .assertIsDisplayed()
            onNodeWithTag("update-dialog").assertDoesNotExist()
        } finally { runOnUiThread { store.clear() } }
    }

    @Test fun updateDialogShowsNotesAndWaitsForConfirmation() = with(compose) {
        try {
            val asset = ReleaseAsset("nfc.apk", "$RELEASES_URL/download/v2026.10.08.1/nfc.apk", 1024)
            val release = AppRelease("2026.10.08.1", "$RELEASES_URL/tag/v2026.10.08.1",
                "## 更新内容\n\n- **改进下载进度**", listOf(asset))
            launchAbout(UpdateCheckResult.Available(release, asset))
            val host = InstrumentationRegistry.getInstrumentation().targetContext
            onNodeWithText(host.getString(R.string.update_check)).performScrollTo().performClick()
            onNodeWithTag("update-dialog").assertIsDisplayed()
            onNodeWithText(host.getString(R.string.update_asset, asset.name, "1024 字节")).assertIsDisplayed()
            onNode(hasText("改进下载进度", substring = true) and hasAnyAncestor(hasTestTag("update-dialog")))
                .assertIsDisplayed()
            assertEquals(0, downloader.enqueues)
            onNodeWithText(host.getString(R.string.update_later)).performClick()
            onNodeWithTag("update-dialog").assertDoesNotExist()
            assertEquals(0, downloader.enqueues)
        } finally { runOnUiThread { store.clear() } }
    }

    @Test fun anUpdateWithoutCompatibleApkDisablesDownload() = with(compose) {
        try {
            val release = AppRelease("2026.10.08.1", "$RELEASES_URL/tag/v2026.10.08.1", "更新内容", emptyList())
            launchAbout(UpdateCheckResult.Available(release, null))
            val host = InstrumentationRegistry.getInstrumentation().targetContext
            onNodeWithText(host.getString(R.string.update_check)).performScrollTo().performClick()
            onNodeWithText(host.getString(R.string.update_no_compatible_apk)).assertIsDisplayed()
            onNodeWithText(host.getString(R.string.update_download_now)).assertIsNotEnabled()
            assertEquals(0, downloader.enqueues)
        } finally { runOnUiThread { store.clear() } }
    }

    private fun ComposeContentTestRule.launchAbout(result: UpdateCheckResult) {
        val vm = runOnUiThread {
            val checker = object : UpdateChecker {
                override suspend fun check(installedVersion: String, supportedAbis: List<String>) = result
            }
            // 显式启用正式版策略，继续在测试包中覆盖完整更新流程。
            UpdateViewModel("2026.10.07.1", listOf("arm64-v8a"), checker, downloader,
                restoreDownloads = false, updatesEnabled = true)
                .also { store.put("update", it) }
        }
        setContent {
            MyApplicationTheme {
                AboutBottomSheet(updateViewModel = vm) { openAbout ->
                    Column(Modifier.padding(32.dp)) { FluentButton(text = "打开关于", onClick = openAbout) }
                }
            }
        }
        onNodeWithText("打开关于").performClick()
    }

    private class FakeDownloader : UpdateDownloader {
        var enqueues = 0
        override suspend fun enqueue(release: AppRelease, asset: ReleaseAsset): Long { enqueues++; return 1L }
        override suspend fun restoreDownloadId(installedVersion: String?): Long? = null
        override suspend fun query(downloadId: Long): DownloadState = DownloadState.Running(0)
        override suspend fun cancel(downloadId: Long) = Unit
        override fun install(file: File) = InstallOutcome.STARTED
        override fun openInstallPermissionSettings(): Boolean = true
    }
}
