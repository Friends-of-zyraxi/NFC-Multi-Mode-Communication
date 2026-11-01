package io.github.zyraxi21.nfc.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zyraxi21.nfc.BuildConfig
import io.github.zyraxi21.nfc.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateUiState {
    data object Restoring : UpdateUiState
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val release: AppRelease, val asset: ReleaseAsset?) : UpdateUiState
    data class Downloading(val progress: Int = -1, val canCancel: Boolean = false) : UpdateUiState
    data class ReadyToInstall(
        val file: File,
        val permissionRequired: Boolean = false,
        val settingsMissing: Boolean = false
    ) : UpdateUiState
}

data class UpdateMessage(@StringRes val resource: Int, val isError: Boolean = false)

/** 复用记账本的更新状态机；界面重建不丢状态，系统下载跨进程继续执行。 */
class UpdateViewModel(
    val installedVersion: String?,
    private val supportedAbis: List<String>,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    restoreDownloads: Boolean = true,
    val updatesEnabled: Boolean = BuildConfig.APP_UPDATES_ENABLED,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateUiState>(if (updatesEnabled && restoreDownloads) UpdateUiState.Restoring else UpdateUiState.Idle)
    val state = _state.asStateFlow()
    private val _message = MutableStateFlow<UpdateMessage?>(null)
    val message = _message.asStateFlow()
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible = _dialogVisible.asStateFlow()
    private var downloadId: Long? = null
    private var downloadPoll: Job? = null

    val canCheck: Boolean get() = updatesEnabled && installedVersion?.let { ReleaseVersion.parse(it) } != null

    init {
        if (updatesEnabled && restoreDownloads) viewModelScope.launch {
            val previousId = try {
                downloader.restoreDownloadId(installedVersion)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (previousId == null) _state.value = UpdateUiState.Idle
            else {
                downloadId = previousId
                _state.value = UpdateUiState.Downloading(canCancel = true)
                pollDownload(previousId, showCompletionDialog = false)
            }
        }
    }

    fun checkForUpdates() {
        if (!updatesEnabled) return
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading ||
            _state.value is UpdateUiState.Restoring || _state.value is UpdateUiState.ReadyToInstall
        ) return
        val version = installedVersion
        if (version == null || !canCheck) {
            _message.value = UpdateMessage(R.string.update_error_version, true)
            return
        }
        _message.value = null
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = try {
                checker.check(version, supportedAbis)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                UpdateCheckResult.Failed(UpdateError.IO)
            }
            when (result) {
                is UpdateCheckResult.Available -> {
                    _state.value = UpdateUiState.Available(result.release, result.asset)
                    _dialogVisible.value = true
                }
                else -> {
                    _state.value = UpdateUiState.Idle
                    _message.value = when (result) {
                        UpdateCheckResult.UpToDate -> UpdateMessage(R.string.update_current)
                        UpdateCheckResult.NoPublishedRelease -> UpdateMessage(R.string.update_no_release)
                        UpdateCheckResult.OnlyPrereleases -> UpdateMessage(R.string.update_only_prerelease)
                        is UpdateCheckResult.Failed -> UpdateMessage(result.error.resource(), true)
                        is UpdateCheckResult.Available -> error("已处理新版本")
                    }
                }
            }
        }
    }

    /** 稍后下载；已完成的安装包保留在页面上，方便用户稍后再安装。 */
    fun dismissUpdate() {
        if (!updatesEnabled) return
        _dialogVisible.value = false
        if (_state.value is UpdateUiState.Available) _state.value = UpdateUiState.Idle
    }

    fun showUpdateDialog() {
        if (!updatesEnabled) return
        _dialogVisible.value = true
    }

    fun startDownload() {
        if (!updatesEnabled) return
        val available = _state.value as? UpdateUiState.Available ?: return
        val asset = available.asset ?: return
        _message.value = null
        _state.value = UpdateUiState.Downloading()
        viewModelScope.launch {
            try {
                val id = downloader.enqueue(available.release, asset)
                downloadId = id
                pollDownload(id, showCompletionDialog = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: SecurityException) {
                downloadFailed(DownloadError.NOT_ALLOWED)
            } catch (_: Exception) {
                downloadFailed(DownloadError.IO)
            }
        }
    }

    private fun pollDownload(id: Long, showCompletionDialog: Boolean) {
        downloadPoll?.cancel()
        downloadPoll = viewModelScope.launch {
            while (true) {
                val progress = try {
                    downloader.query(id)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    DownloadState.Failed(DownloadError.IO)
                }
                when (progress) {
                    is DownloadState.Running -> _state.value = UpdateUiState.Downloading(progress.progress, canCancel = true)
                    is DownloadState.Completed -> {
                        _state.value = UpdateUiState.ReadyToInstall(progress.file)
                        _dialogVisible.value = showCompletionDialog && _dialogVisible.value
                        _message.value = UpdateMessage(R.string.update_download_complete)
                        return@launch
                    }
                    is DownloadState.Failed -> {
                        downloader.cancel(id)
                        downloadFailed(progress.error)
                        return@launch
                    }
                }
                delay(700L)
            }
        }
    }

    fun cancelDownload() {
        if (!updatesEnabled) return
        if (_state.value !is UpdateUiState.Downloading) return
        val id = downloadId
        // 任务尚在入队时不能取消；入队完成前取消会产生无人观察的系统任务。
        if (id == null) return
        downloadPoll?.cancel()
        downloadId = null
        _state.value = UpdateUiState.Restoring
        viewModelScope.launch {
            downloader.cancel(id)
            _state.value = UpdateUiState.Idle
            _dialogVisible.value = false
            _message.value = UpdateMessage(R.string.update_download_cancelled)
        }
    }

    fun installUpdate() {
        if (!updatesEnabled) return
        val ready = _state.value as? UpdateUiState.ReadyToInstall ?: return
        when (downloader.install(ready.file)) {
            InstallOutcome.STARTED -> {
                _state.value = ready.copy(permissionRequired = false, settingsMissing = false)
                _message.value = null
            }
            InstallOutcome.PERMISSION_REQUIRED -> {
                val launched = downloader.openInstallPermissionSettings()
                _state.value = ready.copy(permissionRequired = true, settingsMissing = !launched)
                _message.value = UpdateMessage(R.string.update_install_permission_needed)
            }
            InstallOutcome.FAILED -> _message.value = UpdateMessage(R.string.update_install_failed, true)
            InstallOutcome.MISSING_FILE -> {
                val id = downloadId
                downloadId = null
                _state.value = UpdateUiState.Restoring
                _dialogVisible.value = false
                viewModelScope.launch {
                    if (id != null) downloader.cancel(id)
                    _state.value = UpdateUiState.Idle
                    _message.value = UpdateMessage(R.string.update_install_file_missing, true)
                }
            }
        }
    }

    private fun downloadFailed(error: DownloadError) {
        downloadId = null
        _state.value = UpdateUiState.Idle
        _dialogVisible.value = false
        _message.value = UpdateMessage(error.resource(), true)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UpdateViewModel::class.java))
            return create(restoreDownloads = true) as T
        }

        fun create(restoreDownloads: Boolean): UpdateViewModel {
            val version = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
            }.getOrNull()
            return UpdateViewModel(version, Build.SUPPORTED_ABIS.toList(), GitHubUpdateChecker(),
                SystemUpdateDownloader(appContext), restoreDownloads)
        }
    }
}

private fun UpdateError.resource(): Int = when (this) {
    UpdateError.OFFLINE -> R.string.update_error_offline
    UpdateError.TIMEOUT -> R.string.update_error_timeout
    UpdateError.RATE_LIMITED -> R.string.update_error_rate_limit
    UpdateError.NOT_FOUND -> R.string.update_error_not_found
    UpdateError.SERVER -> R.string.update_error_server
    UpdateError.PARSE -> R.string.update_error_release
    UpdateError.IO -> R.string.update_error_network
}

private fun DownloadError.resource(): Int = when (this) {
    DownloadError.NO_SPACE -> R.string.update_error_no_space
    DownloadError.NOT_ALLOWED -> R.string.update_download_not_allowed
    DownloadError.IO -> R.string.update_error_download
}
