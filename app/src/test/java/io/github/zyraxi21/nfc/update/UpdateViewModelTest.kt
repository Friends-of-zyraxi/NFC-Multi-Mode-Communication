package io.github.zyraxi21.nfc.update

import androidx.lifecycle.ViewModelStore
import io.github.zyraxi21.nfc.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val store = ViewModelStore()
    private val asset = ReleaseAsset("nfc.apk", "$RELEASES_URL/download/v2026.10.08.1/nfc.apk", 1024)
    private val release = AppRelease("2026.10.08.1", "$RELEASES_URL/tag/v2026.10.08.1", "更新内容", listOf(asset))
    private val checker = FakeChecker(UpdateCheckResult.Available(release, asset))
    private val downloader = FakeDownloader()

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun disabledUpdatesNeverRestoreCheckDownloadOrInstall() = runUpdateTest {
        downloader.restoredId = 42L
        downloader.nextState = DownloadState.Completed(File("downloaded.apk"))
        val vm = create(restore = true, enabled = false)
        vm.checkForUpdates()
        vm.showUpdateDialog()
        vm.startDownload()
        vm.cancelDownload()
        vm.installUpdate()
        vm.dismissUpdate()
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        assertFalse(vm.updatesEnabled)
        assertFalse(vm.canCheck)
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertFalse(vm.dialogVisible.value)
        assertNull(vm.message.value)
        assertEquals(0, checker.calls)
        assertEquals(0, downloader.restores)
        assertEquals(0, downloader.enqueues)
        assertEquals(0, downloader.queries)
        assertEquals(0, downloader.installCalls)
        assertEquals(0, downloader.settingsCalls)
        assertTrue(downloader.cancelled.isEmpty())
    }

    @Test fun discoveringAnUpdateWaitsForUserConfirmation() = runUpdateTest {
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        assertTrue(vm.state.value is UpdateUiState.Available)
        assertTrue(vm.dialogVisible.value)
        assertEquals(0, downloader.enqueues)
        vm.startDownload()
        runCurrent()
        assertEquals(1, downloader.enqueues)
        assertEquals(UpdateUiState.Downloading(25, canCancel = true), vm.state.value)
    }

    @Test fun repeatedChecksDoNotCreateConcurrentRequests() = runUpdateTest {
        val blocker = CompletableDeferred<Unit>()
        checker.blocker = blocker
        val vm = create()
        vm.checkForUpdates()
        vm.checkForUpdates()
        runCurrent()
        assertEquals(1, checker.calls)
        assertEquals(UpdateUiState.Checking, vm.state.value)
        blocker.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value is UpdateUiState.Available)
    }

    @Test fun missingCompatibleApkCannotStartDownload() = runUpdateTest {
        checker.result = UpdateCheckResult.Available(release, null)
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        vm.startDownload()
        runCurrent()
        assertEquals(0, downloader.enqueues)
        assertTrue(vm.state.value is UpdateUiState.Available)
    }

    @Test fun backgroundDownloadFinishesWithoutReopeningDismissedDialog() = runUpdateTest {
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        vm.startDownload()
        runCurrent()
        vm.dismissUpdate()
        val file = File("downloaded.apk")
        downloader.nextState = DownloadState.Completed(file)
        advanceTimeBy(700)
        runCurrent()
        assertEquals(UpdateUiState.ReadyToInstall(file), vm.state.value)
        assertFalse(vm.dialogVisible.value)
    }

    @Test fun downloadCompletionOffersInstallationWhileDialogIsOpen() = runUpdateTest {
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        vm.startDownload()
        runCurrent()
        downloader.nextState = DownloadState.Completed(File("downloaded.apk"))
        advanceTimeBy(700)
        runCurrent()
        assertTrue(vm.state.value is UpdateUiState.ReadyToInstall)
        assertTrue(vm.dialogVisible.value)
        assertEquals(0, downloader.installCalls)
    }

    @Test fun cancellationStopsPollingAndRemovesTheSystemTask() = runUpdateTest {
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        vm.startDownload()
        runCurrent()
        vm.cancelDownload()
        runCurrent()
        val queries = downloader.queries
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(queries, downloader.queries)
        assertEquals(listOf(42L), downloader.cancelled)
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(R.string.update_download_cancelled, vm.message.value?.resource)
    }

    @Test fun restoredCompletedTaskRetainsAnInstallEntryWithoutAnotherDownload() = runUpdateTest {
        downloader.restoredId = 42L
        val file = File("downloaded.apk")
        downloader.nextState = DownloadState.Completed(file)
        val vm = create(restore = true)
        runCurrent()
        assertEquals(UpdateUiState.ReadyToInstall(file), vm.state.value)
        assertFalse(vm.dialogVisible.value)
        assertEquals(0, downloader.enqueues)
    }

    @Test fun downloadFailureReturnsToRetryAndKeepsItsSpecificReason() = runUpdateTest {
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        vm.startDownload()
        runCurrent()
        downloader.nextState = DownloadState.Failed(DownloadError.NO_SPACE)
        advanceTimeBy(700)
        runCurrent()
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(R.string.update_error_no_space, vm.message.value?.resource)
        assertTrue(vm.message.value?.isError == true)
        assertEquals(listOf(42L), downloader.cancelled)
    }

    @Test fun permissionSettingsDoNotDiscardTheDownloadedFile() = runUpdateTest {
        downloader.restoredId = 42L
        val file = File("downloaded.apk")
        downloader.nextState = DownloadState.Completed(file)
        val vm = create(restore = true)
        runCurrent()
        downloader.installOutcome = InstallOutcome.PERMISSION_REQUIRED
        downloader.settingsAvailable = false
        vm.installUpdate()
        assertEquals(UpdateUiState.ReadyToInstall(file, permissionRequired = true, settingsMissing = true), vm.state.value)
        assertEquals(1, downloader.settingsCalls)
        vm.dismissUpdate()
        assertTrue(vm.state.value is UpdateUiState.ReadyToInstall)
        downloader.installOutcome = InstallOutcome.STARTED
        vm.installUpdate()
        assertEquals(2, downloader.installCalls)
        assertEquals(UpdateUiState.ReadyToInstall(file), vm.state.value)
    }

    @Test fun failedCheckCanBeRetriedWithoutDownloading() = runUpdateTest {
        checker.result = UpdateCheckResult.Failed(UpdateError.TIMEOUT)
        val vm = create()
        vm.checkForUpdates()
        runCurrent()
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(R.string.update_error_timeout, vm.message.value?.resource)
        checker.result = UpdateCheckResult.UpToDate
        vm.checkForUpdates()
        runCurrent()
        assertEquals(R.string.update_current, vm.message.value?.resource)
        assertEquals(0, downloader.enqueues)
    }

    @Test fun aDeletedApkReturnsToCheckingInsteadOfLeavingABrokenInstallButton() = runUpdateTest {
        downloader.restoredId = 42L
        downloader.nextState = DownloadState.Completed(File("deleted.apk"))
        val vm = create(restore = true)
        runCurrent()
        downloader.installOutcome = InstallOutcome.MISSING_FILE
        vm.installUpdate()
        runCurrent()
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(R.string.update_install_file_missing, vm.message.value?.resource)
        assertEquals(listOf(42L), downloader.cancelled)
    }

    /** 在 runTest 返回前清理 ViewModel 的轮询任务，避免虚拟时钟持续推进。 */
    private fun runUpdateTest(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { store.clear() }
    }

    private fun create(restore: Boolean = false, enabled: Boolean = true): UpdateViewModel = UpdateViewModel(
        "2026.10.07.1", listOf("arm64-v8a"), checker, downloader, restore, updatesEnabled = enabled
    ).also { store.put("update", it) }

    private class FakeChecker(var result: UpdateCheckResult) : UpdateChecker {
        var calls = 0
        var blocker: CompletableDeferred<Unit>? = null
        override suspend fun check(installedVersion: String, supportedAbis: List<String>): UpdateCheckResult {
            calls++
            blocker?.await()
            return result
        }
    }

    private class FakeDownloader : UpdateDownloader {
        var enqueues = 0
        var restores = 0
        var queries = 0
        var restoredId: Long? = null
        var nextState: DownloadState = DownloadState.Running(25)
        val cancelled = mutableListOf<Long>()
        var installCalls = 0
        var installOutcome = InstallOutcome.STARTED
        var settingsAvailable = true
        var settingsCalls = 0
        override suspend fun enqueue(release: AppRelease, asset: ReleaseAsset): Long { enqueues++; return 42L }
        override suspend fun restoreDownloadId(installedVersion: String?): Long? { restores++; return restoredId }
        override suspend fun query(downloadId: Long): DownloadState { queries++; return nextState }
        override suspend fun cancel(downloadId: Long) { cancelled.add(downloadId) }
        override fun install(file: File): InstallOutcome { installCalls++; return installOutcome }
        override fun openInstallPermissionSettings(): Boolean { settingsCalls++; return settingsAvailable }
    }
}
