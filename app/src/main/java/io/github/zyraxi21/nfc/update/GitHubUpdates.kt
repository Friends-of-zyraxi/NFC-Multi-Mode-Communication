package io.github.zyraxi21.nfc.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException

const val REPOSITORY_URL = "https://github.com/Friends-of-zyraxi/NFC-Multi-Mode-Communication"
const val RELEASES_URL = "$REPOSITORY_URL/releases"
private const val RELEASES_API =
    "https://api.github.com/repos/Friends-of-zyraxi/NFC-Multi-Mode-Communication/releases?per_page=100"

data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

data class AppRelease(
    val versionName: String,
    val pageUrl: String,
    val notes: String,
    val assets: List<ReleaseAsset>
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data object NoPublishedRelease : UpdateCheckResult
    data object OnlyPrereleases : UpdateCheckResult
    data class Available(val release: AppRelease, val asset: ReleaseAsset?) : UpdateCheckResult
    data class Failed(val error: UpdateError) : UpdateCheckResult
}

enum class UpdateError { OFFLINE, TIMEOUT, RATE_LIMITED, NOT_FOUND, SERVER, PARSE, IO }

class InvalidReleaseException(cause: Throwable? = null) : IOException(cause)
private class GitHubHttpException(val statusCode: Int) : IOException("GitHub HTTP $statusCode")

interface UpdateChecker {
    suspend fun check(installedVersion: String, supportedAbis: List<String>): UpdateCheckResult
}

/** 复用记账本的发布列表查询与失败分类，按版本数值选择最新正式版本。 */
class GitHubUpdateChecker : UpdateChecker {
    override suspend fun check(installedVersion: String, supportedAbis: List<String>): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                evaluateReleases(fetchReleases(installedVersion), installedVersion, supportedAbis)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: InvalidReleaseException) {
                UpdateCheckResult.Failed(UpdateError.PARSE)
            } catch (exception: GitHubHttpException) {
                UpdateCheckResult.Failed(classifyHttpError(exception.statusCode))
            } catch (_: UnknownHostException) {
                UpdateCheckResult.Failed(UpdateError.OFFLINE)
            } catch (_: SocketTimeoutException) {
                UpdateCheckResult.Failed(UpdateError.TIMEOUT)
            } catch (_: IOException) {
                UpdateCheckResult.Failed(UpdateError.IO)
            }
        }

    private suspend fun fetchReleases(installedVersion: String): String {
        val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "NFC-Communication/$installedVersion")
            val statusCode = connection.responseCode
            currentCoroutineContext().ensureActive()
            if (statusCode != HttpURLConnection.HTTP_OK) throw GitHubHttpException(statusCode)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            currentCoroutineContext().ensureActive()
            return body
        } finally {
            connection.disconnect()
        }
    }
}

internal fun classifyHttpError(statusCode: Int): UpdateError = when (statusCode) {
    401, 403, 429 -> UpdateError.RATE_LIMITED
    404 -> UpdateError.NOT_FOUND
    else -> UpdateError.SERVER
}

/** 发布顺序与版本大小可能不同；跳过单条无效信息，仍可找到其余正式版本。 */
internal fun evaluateReleases(
    body: String,
    installedVersion: String,
    supportedAbis: List<String>
): UpdateCheckResult {
    val releases = try { JSONArray(body) } catch (exception: JSONException) {
        throw InvalidReleaseException(exception)
    }
    if (releases.length() == 0) return UpdateCheckResult.NoPublishedRelease
    var invalidFormalRelease = false
    val formal = buildList {
        repeat(releases.length()) { index ->
            val item = releases.optJSONObject(index)
            if (item == null) {
                invalidFormalRelease = true
                return@repeat
            }
            val tag = item.optString("tag_name")
            val version = ReleaseVersion.parse(tag)
            if (item.optBoolean("draft") || item.optBoolean("prerelease") ||
                version?.isPrerelease == true || tag.substringBefore('+').contains('-') || tag.contains('_')
            ) return@repeat
            try {
                add(parseRelease(item.toString()))
            } catch (_: InvalidReleaseException) {
                invalidFormalRelease = true
            }
        }
    }
    val newest = formal.maxByOrNull { checkNotNull(ReleaseVersion.parse(it.versionName)) }
        ?: return if (invalidFormalRelease) throw InvalidReleaseException() else UpdateCheckResult.OnlyPrereleases
    return evaluateRelease(newest, installedVersion, supportedAbis)
}

internal fun parseRelease(body: String): AppRelease {
    try {
        val json = JSONObject(body)
        // 列表筛选后仍校验单条响应，防止误下载草稿或预发布安装包。
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw InvalidReleaseException()
        val tag = json.getString("tag_name")
        val version = ReleaseVersion.parse(tag) ?: throw InvalidReleaseException()
        if (version.isPrerelease) throw InvalidReleaseException()
        val pageUrl = json.getString("html_url")
        if (!isRepositoryReleaseUrl(pageUrl, "tag")) throw InvalidReleaseException()
        val jsonAssets = json.getJSONArray("assets")
        val assets = buildList {
            repeat(jsonAssets.length()) { index ->
                val asset = jsonAssets.getJSONObject(index)
                val name = asset.optString("name")
                val downloadUrl = asset.optString("browser_download_url")
                val size = asset.optLong("size")
                if (asset.optString("state") == "uploaded" && size > 0 &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    isRepositoryReleaseUrl(downloadUrl, "download")
                ) {
                    add(ReleaseAsset(name, downloadUrl, size))
                }
            }
        }
        return AppRelease(
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            pageUrl = pageUrl,
            notes = if (json.isNull("body")) "" else json.optString("body").trim(),
            assets = assets
        )
    } catch (exception: JSONException) {
        throw InvalidReleaseException(exception)
    }
}

internal fun evaluateRelease(
    release: AppRelease,
    installedVersion: String,
    supportedAbis: List<String>
): UpdateCheckResult {
    val current = ReleaseVersion.parse(installedVersion) ?: throw InvalidReleaseException()
    val latest = ReleaseVersion.parse(release.versionName) ?: throw InvalidReleaseException()
    return if (latest > current) {
        UpdateCheckResult.Available(release, selectApkAsset(release.assets, supportedAbis))
    } else {
        UpdateCheckResult.UpToDate
    }
}

private val abiPattern = Regex("(?<![a-z0-9])(arm64-v8a|armeabi-v7a|x86-64|x86|aarch64|armeabi)(?![a-z0-9])")
private val unsuitableApkPattern = Regex("(?<![a-z0-9])(debug|unsigned|unaligned|androidtest)(?![a-z0-9])")

/** 按设备支持的 ABI 顺序选择安装包；不匹配时只回退到通用 APK。 */
internal fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    val candidates = assets.filter {
        it.name.endsWith(".apk", ignoreCase = true) &&
            !unsuitableApkPattern.containsMatchIn(it.name.lowercase())
    }
    fun architectures(asset: ReleaseAsset): Set<String> =
        abiPattern.findAll(asset.name.lowercase().replace('_', '-')).map {
            if (it.value == "aarch64") "arm64-v8a" else it.value
        }.toSet()
    supportedAbis.forEach { abi ->
        candidates.firstOrNull { abi.replace('_', '-') in architectures(it) }?.let { return it }
    }
    return candidates.firstOrNull {
        "universal" in it.name.lowercase() || "noarch" in it.name.lowercase()
    } ?: candidates.firstOrNull { architectures(it).isEmpty() }
}

internal fun isRepositoryReleaseUrl(value: String, kind: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
        uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
        uri.path.startsWith("/Friends-of-zyraxi/NFC-Multi-Mode-Communication/releases/$kind/")
} catch (_: Exception) {
    false
}
