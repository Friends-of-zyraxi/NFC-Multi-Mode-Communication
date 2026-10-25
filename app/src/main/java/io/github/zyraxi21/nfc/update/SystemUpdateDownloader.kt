package io.github.zyraxi21.nfc.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.zyraxi21.nfc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class DownloadError { NO_SPACE, NOT_ALLOWED, IO }

sealed interface DownloadState {
    /** -1 表示尚未取得总大小；暂停与排队同样属于进行中的任务。 */
    data class Running(val progress: Int) : DownloadState
    data class Completed(val file: File) : DownloadState
    data class Failed(val error: DownloadError) : DownloadState
}

enum class InstallOutcome { STARTED, PERMISSION_REQUIRED, MISSING_FILE, FAILED }

interface UpdateDownloader {
    suspend fun enqueue(release: AppRelease, asset: ReleaseAsset): Long
    suspend fun restoreDownloadId(installedVersion: String?): Long?
    suspend fun query(downloadId: Long): DownloadState
    suspend fun cancel(downloadId: Long)
    fun install(file: File): InstallOutcome
    fun openInstallPermissionSettings(): Boolean
}

/** 复用记账本的系统下载器与 FileProvider 安装流程，并持久化任务以便重启后继续。 */
class SystemUpdateDownloader(context: Context) : UpdateDownloader {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val manager get() = checkNotNull(appContext.getSystemService(DownloadManager::class.java))
    private val updateDirectory get() = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.resolve("updates") ?: throw IOException("无法访问安装包目录")

    override suspend fun enqueue(release: AppRelease, asset: ReleaseAsset): Long = withContext(Dispatchers.IO) {
        require(isRepositoryReleaseUrl(asset.downloadUrl, "download"))
        val previousId = preferences.getLong("download_id", -1L)
        if (preferences.getString("download_url", null) == asset.downloadUrl && previousId != -1L) {
            // 兼容旧版只保存 URL 与 ID 的记录，补上文件大小后再查询。
            preferences.edit {
                putLong("download_size", asset.sizeBytes)
                putString("download_version", release.versionName)
            }
            when (query(previousId)) {
                is DownloadState.Running, is DownloadState.Completed -> return@withContext previousId
                is DownloadState.Failed -> cancel(previousId)
            }
        } else if (previousId != -1L) {
            // 本应用只保留一个更新任务，新版本取代旧版本时清理自己的旧下载。
            cancel(previousId)
        }
        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("(?i)\\.apk$"), "")
        val fileName = "$safeName-${System.currentTimeMillis()}.apk"
        val directory = updateDirectory
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建安装包目录")
        val file = File(directory, fileName)
        val request = DownloadManager.Request(asset.downloadUrl.toUri())
            .setTitle(appContext.getString(R.string.update_download_title, release.versionName))
            .setDescription(appContext.getString(R.string.update_download_description))
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val downloadId = manager.enqueue(request)
        preferences.edit {
            putString("download_url", asset.downloadUrl)
            putLong("download_id", downloadId)
            putString("download_file", file.absolutePath)
            putLong("download_size", asset.sizeBytes)
            putString("download_version", release.versionName)
        }
        downloadId
    }

    override suspend fun restoreDownloadId(installedVersion: String?): Long? = withContext(Dispatchers.IO) {
        val url = preferences.getString("download_url", null) ?: return@withContext null
        if (!isRepositoryReleaseUrl(url, "download")) return@withContext null
        val id = preferences.getLong("download_id", -1L).takeIf { it != -1L } ?: return@withContext null
        val downloadedVersion = preferences.getString("download_version", null)?.let { ReleaseVersion.parse(it) }
        val currentVersion = installedVersion?.let { ReleaseVersion.parse(it) }
        if (downloadedVersion != null && currentVersion != null && downloadedVersion <= currentVersion) {
            cancel(id)
            null
        } else id
    }

    override suspend fun query(downloadId: Long): DownloadState = withContext(Dispatchers.IO) {
        try {
            manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst() || preferences.getLong("download_id", -1L) != downloadId ||
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) !=
                    preferences.getString("download_url", null)
                ) return@withContext DownloadState.Failed(DownloadError.IO)
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        DownloadState.Running(if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else -1)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val file = completedFile(downloadId)
                        if (file != null) DownloadState.Completed(file) else DownloadState.Failed(DownloadError.IO)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        DownloadState.Failed(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) DownloadError.NO_SPACE else DownloadError.IO)
                    }
                    else -> DownloadState.Failed(DownloadError.IO)
                }
            } ?: DownloadState.Failed(DownloadError.IO)
        } catch (_: SecurityException) {
            DownloadState.Failed(DownloadError.NOT_ALLOWED)
        } catch (_: Exception) {
            DownloadState.Failed(DownloadError.IO)
        }
    }

    /** 旧版公共下载目录中的已完成任务复制到私有目录，仍复用原任务 ID。 */
    private fun completedFile(downloadId: Long): File? {
        val savedPath = preferences.getString("download_file", null)
        val file = if (savedPath != null) {
            File(savedPath).takeIf { isUpdateFile(it) } ?: return null
        } else {
            updateDirectory.mkdirs()
            val destination = File(updateDirectory, "nfc-update-$downloadId.apk")
            manager.openDownloadedFile(downloadId).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    destination.outputStream().use { input.copyTo(it) }
                }
            }
            preferences.edit { putString("download_file", destination.absolutePath) }
            destination
        }
        val expectedSize = preferences.getLong("download_size", 0L)
        return file.takeIf { it.isFile && it.length() > 0L && (expectedSize <= 0L || it.length() == expectedSize) }
    }

    override suspend fun cancel(downloadId: Long) = withContext(Dispatchers.IO) {
        runCatching { manager.remove(downloadId) }
        if (preferences.getLong("download_id", -1L) == downloadId) {
            preferences.getString("download_file", null)?.let { path ->
                runCatching { File(path).takeIf { isUpdateFile(it) }?.delete() }
            }
            preferences.edit { clear() }
        }
    }

    override fun install(file: File): InstallOutcome {
        if (!runCatching { isUpdateFile(file) && file.isFile && file.length() > 0L }.getOrDefault(false)) {
            return InstallOutcome.MISSING_FILE
        }
        if (!runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)) {
            return InstallOutcome.PERMISSION_REQUIRED
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
            appContext.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            InstallOutcome.STARTED
        }.getOrDefault(InstallOutcome.FAILED)
    }

    override fun openInstallPermissionSettings(): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri()),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        )
        return candidates.any { intent ->
            runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }

    private fun isUpdateFile(file: File): Boolean = file.canonicalFile.parentFile == updateDirectory.canonicalFile

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
