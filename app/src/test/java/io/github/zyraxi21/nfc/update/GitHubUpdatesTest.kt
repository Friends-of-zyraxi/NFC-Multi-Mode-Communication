package io.github.zyraxi21.nfc.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdatesTest {
    private fun version(value: String) = requireNotNull(ReleaseVersion.parse(value))
    private fun apk(name: String) = ReleaseAsset(name, "$RELEASES_URL/download/v2026.10.07.2/$name", 3_000_000)
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun dateVersionsCompareNumerically() {
        assertTrue(version("2026.10.07.10") > version("2026.10.07.9"))
        assertTrue(version("2026.10.01.1") > version("2026.09.30.9"))
        assertTrue(version("2027.01.01.1") > version("2026.12.31.20"))
    }

    @Test
    fun prefixMetadataAndTrailingZerosDoNotChangeVersion() {
        assertEquals(0, version("v2026.10.07.1").compareTo(version("2026.10.07.1")))
        assertEquals(0, version("V1.2.3+build.123").compareTo(version("1.2.3")))
        assertEquals(0, version("1.2").compareTo(version("1.2.0")))
    }

    @Test
    fun stableVersionIsNewerThanItsPreRelease() {
        assertTrue(version("2026.10.07.2") > version("2026.10.07.2-rc.1"))
        assertTrue(version("1.2.3-rc.10") > version("1.2.3-rc.9"))
        assertTrue(version("1.2.3-beta") > version("1.2.3-alpha"))
    }

    @Test
    fun unrecognizedVersionIsRejected() {
        listOf("", "release-latest", "2026..10", "1.2.", "1.2/3", "1.2-", "999999999999999999999999").forEach {
            assertNull(ReleaseVersion.parse(it))
        }
    }

    @Test
    fun picksApkInDeviceAbiPreferenceOrder() {
        val assets = listOf(apk("app-armeabi-v7a.apk"), apk("NFC-Communication-2026.10.07.2-arm64-v8a.apk"))
        assertEquals(assets[1], selectApkAsset(assets, arm64))
        assertEquals(assets[0], selectApkAsset(assets, listOf("armeabi-v7a")))
    }

    @Test
    fun x86DoesNotMatchX86_64() {
        val assets = listOf(apk("app-x86_64.apk"), apk("app-x86.apk"))
        assertEquals(assets[1], selectApkAsset(assets, listOf("x86")))
        assertNull(selectApkAsset(assets.take(1), listOf("x86")))
    }

    @Test
    fun fallsBackToUniversalApk() {
        val assets = listOf(apk("app-x86_64.apk"), apk("app-release.apk"), apk("app-universal.apk"))
        assertEquals(assets[2], selectApkAsset(assets, arm64))
        assertEquals(assets[1], selectApkAsset(assets.take(2), arm64))
    }

    @Test
    fun missingCompatibleApkDoesNotDownloadAnotherArchitecture() {
        assertNull(selectApkAsset(listOf(apk("app-x86_64.apk")), arm64))
        assertNull(selectApkAsset(emptyList(), arm64))
    }

    @Test
    fun ignoresDebugUnsignedAndNonApkAssets() {
        val assets = listOf(apk("app-arm64-v8a-debug.apk"), apk("app-arm64-v8a-unsigned.apk"), apk("source.zip"))
        assertNull(selectApkAsset(assets, arm64))
    }

    @Test
    fun recognizesUnderscoreAndUppercaseApkNames() {
        val asset = apk("NFC_arm64_v8a.APK")
        assertEquals(asset, selectApkAsset(listOf(asset), arm64))
    }

    @Test
    fun recognizesAarch64WithoutPickingItForX86() {
        val asset = apk("app-aarch64.apk")
        assertEquals(asset, selectApkAsset(listOf(asset), arm64))
        assertNull(selectApkAsset(listOf(asset), listOf("x86")))
    }

    @Test
    fun parsesActualReleaseFormat() {
        val release = parseRelease(releaseJson())
        assertEquals("2026.10.07.2", release.versionName)
        assertEquals("更新说明", release.notes)
        assertEquals("NFC-Communication-2026.10.07.2-arm64-v8a.apk", release.assets.single().name)
        assertEquals(3_006_327L, release.assets.single().sizeBytes)
    }

    @Test
    fun nullReleaseNotesAreEmpty() {
        assertEquals("", parseRelease(releaseJson().replace("\"更新说明\"", "null")).notes)
    }

    @Test(expected = InvalidReleaseException::class)
    fun rejectsPreReleaseResponse() {
        parseRelease(releaseJson().replace("\"prerelease\": false", "\"prerelease\": true"))
    }

    @Test(expected = InvalidReleaseException::class)
    fun rejectsDraftResponse() {
        parseRelease(releaseJson().replace("\"draft\": false", "\"draft\": true"))
    }

    @Test(expected = InvalidReleaseException::class)
    fun rejectsMalformedJson() {
        parseRelease("<html>网络代理错误</html>")
    }

    @Test(expected = InvalidReleaseException::class)
    fun rejectsUnrecognizedReleaseTag() {
        parseRelease(releaseJson().replace("v2026.10.07.2", "latest"))
    }

    @Test
    fun rejectsForeignDownloadUrls() {
        assertTrue(isRepositoryReleaseUrl("$RELEASES_URL/download/v1.0/app.apk", "download"))
        assertFalse(isRepositoryReleaseUrl("http://github.com/Friends-of-zyraxi/NFC-Multi-Mode-Communication/releases/download/v1.0/app.apk", "download"))
        assertFalse(isRepositoryReleaseUrl("https://github.com.evil.example/Friends-of-zyraxi/NFC-Multi-Mode-Communication/releases/download/v1.0/app.apk", "download"))
        assertFalse(isRepositoryReleaseUrl("https://github.com/another/project/releases/download/v1.0/app.apk", "download"))
        val release = parseRelease(releaseJson().replace("$RELEASES_URL/download/", "https://example.com/download/"))
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun ignoresIncompleteUploads() {
        val release = parseRelease(releaseJson().replace("\"state\": \"uploaded\"", "\"state\": \"starter\""))
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun equalOrOlderReleaseDoesNotTriggerDownload() {
        val release = parseRelease(releaseJson())
        assertEquals(UpdateCheckResult.UpToDate, evaluateRelease(release, "2026.10.07.2", arm64))
        assertEquals(UpdateCheckResult.UpToDate, evaluateRelease(release, "2026.10.08.1", arm64))
    }

    @Test
    fun newerReleaseIncludesCompatibleApk() {
        val result = evaluateRelease(parseRelease(releaseJson()), "2026.10.07.1", arm64)
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("NFC-Communication-2026.10.07.2-arm64-v8a.apk", (result as UpdateCheckResult.Available).asset?.name)
    }

    @Test
    fun newerReleaseWithoutApkStillProvidesReleasePage() {
        val result = evaluateRelease(parseRelease(releaseJson()), "2026.10.07.1", listOf("x86_64"))
        assertTrue(result is UpdateCheckResult.Available)
        assertNull((result as UpdateCheckResult.Available).asset)
        assertEquals("$RELEASES_URL/tag/v2026.10.07.2", result.release.pageUrl)
    }

    @Test
    fun releaseListChoosesHighestVersionInsteadOfFirstPublishedItem() {
        val earlier = releaseJson().replace("2026.10.07.2", "2026.10.9.1")
        val newer = releaseJson().replace("2026.10.07.2", "2026.10.10.1")
        val result = evaluateReleases("[$earlier,$newer]", "2026.10.07.1", arm64)
        assertEquals("2026.10.10.1", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun releaseListIgnoresDraftsAndPrereleaseTagsEvenWhenFlagIsFalse() {
        val candidate = releaseJson().replace("2026.10.07.2", "2026.10.08.1-rc1")
        val draft = releaseJson().replace("\"draft\": false", "\"draft\": true")
        val result = evaluateReleases("[$candidate,$draft,${releaseJson()}]", "2026.10.07.1", arm64)
        assertEquals("2026.10.07.2", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun emptyRepositoryAndOnlyPrereleasesHaveDifferentResults() {
        assertEquals(UpdateCheckResult.NoPublishedRelease, evaluateReleases("[]", "2026.10.07.1", arm64))
        val prerelease = releaseJson().replace("2026.10.07.2", "2026.10.08.1-beta")
        assertEquals(UpdateCheckResult.OnlyPrereleases, evaluateReleases("[$prerelease]", "2026.10.07.1", arm64))
    }

    @Test
    fun malformedEntryDoesNotHideAValidUpdate() {
        val result = evaluateReleases("[{\"tag_name\":\"latest\"},null,${releaseJson()}]", "2026.10.07.1", arm64)
        assertTrue(result is UpdateCheckResult.Available)
    }

    @Test(expected = InvalidReleaseException::class)
    fun malformedFormalVersionsAreNotReportedAsAnEmptyRepository() {
        evaluateReleases("[{\"tag_name\":\"latest\"}]", "2026.10.07.1", arm64)
    }

    @Test
    fun highestVersionWithoutCompatibleApkDoesNotSilentlyOfferAnOlderApk() {
        val newer = releaseJson().replace("2026.10.07.2", "2026.10.08.1")
            .replace("arm64-v8a", "x86_64")
        val result = evaluateReleases("[${releaseJson()},$newer]", "2026.10.07.1", arm64)
        assertEquals("2026.10.08.1", (result as UpdateCheckResult.Available).release.versionName)
        assertNull(result.asset)
    }

    @Test
    fun buildMetadataIsNotMistakenForAPrereleaseSuffix() {
        val tagged = releaseJson().replace("2026.10.07.2", "2026.10.07.2+build-1")
        assertTrue(evaluateReleases("[$tagged]", "2026.10.07.1", arm64) is UpdateCheckResult.Available)
    }

    private fun releaseJson() = """
        {
          "tag_name": "v2026.10.07.2",
          "html_url": "$RELEASES_URL/tag/v2026.10.07.2",
          "body": "更新说明",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "NFC-Communication-2026.10.07.2-arm64-v8a.apk",
              "browser_download_url": "$RELEASES_URL/download/v2026.10.07.2/NFC-Communication-2026.10.07.2-arm64-v8a.apk",
              "state": "uploaded",
              "size": 3006327
            },
            { "name": "source.zip", "state": "uploaded", "size": 1000 }
          ]
        }
    """.trimIndent()
}
