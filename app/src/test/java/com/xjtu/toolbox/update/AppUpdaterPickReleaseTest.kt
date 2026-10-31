package com.xjtu.toolbox.update

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class AppUpdaterPickReleaseTest {

    private fun buildReleaseJson(
        tagName: String,
        name: String? = null,
        body: String = "",
        prerelease: Boolean = false,
        draft: Boolean = false,
        hasApk: Boolean = true,
        publishedAt: String? = null,
    ): JsonObject = buildJsonObject {
        put("tag_name", tagName)
        if (name != null) put("name", name)
        put("body", body)
        put("prerelease", prerelease)
        put("draft", draft)
        if (publishedAt != null) put("published_at", publishedAt)
        put("html_url", "https://github.com/releases/$tagName")
        putJsonArray("assets") {
            if (hasApk) addJsonObject {
                put("name", "app-preview.apk")
                put("browser_download_url", "https://github.com/download/$tagName/app.apk")
            }
        }
    }

    @Test
    fun pickRelease_previewDisabled_onlyReturnsFormal() {
        val releases = buildJsonArray {
            add(buildReleaseJson(tagName = "v4.9.7", name = "v4.9.7", prerelease = false))
            add(buildReleaseJson(tagName = "dev-1", name = "4.9.8-dev.1", prerelease = true))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.6",
            includePreview = false,
        )

        assertNotNull(result)
        assertEquals("4.9.7", result!!.version)
        assertFalse(result.isPreview)
    }

    @Test
    fun pickRelease_previewEnabled_returnsPreviewIfGreater() {
        val releases = buildJsonArray {
            add(buildReleaseJson(tagName = "v4.9.7", name = "v4.9.7", prerelease = false))
            add(buildReleaseJson(tagName = "dev-1", name = "4.9.8-dev.1", prerelease = true))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.7",
            includePreview = true,
        )

        assertNotNull(result)
        assertEquals("4.9.8-dev.1", result!!.version)
        assertTrue(result.isPreview)
    }

    @Test
    fun pickRelease_previewEnabled_ignoresLegacyRolloutField() {
        // 旧 Release 说明里可能还留着 rollout: 0；新客户端不再按它分桶，开启预览就必须能收到。
        val releases = buildJsonArray {
            add(buildReleaseJson(tagName = "dev-1", name = "4.9.8-dev.1", prerelease = true, body = "rollout: 0"))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.7",
            includePreview = true,
        )

        assertNotNull(result)
        assertEquals("4.9.8-dev.1", result!!.version)
    }

    @Test
    fun sameBasePreviewUsesReleaseTimeRegardlessOfGithubListOrder() {
        val stable = buildReleaseJson("v5.2.0", publishedAt = "2026-10-20T08:00:00Z")
        val preview = buildReleaseJson("dev-100", "5.2.0-dev.100", prerelease = true, publishedAt = "2026-10-22T08:00:00Z")
        for (ordered in listOf(listOf(stable, preview), listOf(preview, stable))) {
            val picked = AppUpdater.pickRelease(kotlinx.serialization.json.JsonArray(ordered), "5.2.0", includePreview = true)!!
            assertEquals("5.2.0-dev.100", picked.version)
            assertTrue(AppUpdater.isNewerUpdate("5.2.0", picked))
            assertFalse(AppUpdater.isNewerUpdate("5.2.0-dev.100", picked))
            assertFalse(AppUpdater.isNewerUpdate("5.3.0", picked))
        }
        val pickedStable = AppUpdater.pickRelease(kotlinx.serialization.json.JsonArray(listOf(preview, stable)), "5.2.0", includePreview = false)!!
        assertFalse(pickedStable.isPreview)
    }

    @Test
    fun pickRelease_skipsDraftAndMissingApkAndMissingName() {
        val releases = buildJsonArray {
            // Draft formal
            add(buildReleaseJson(tagName = "v4.9.9", draft = true))
            // Preview missing apk
            add(buildReleaseJson(tagName = "dev-2", name = "4.9.9-dev.2", prerelease = true, hasApk = false))
            // Preview missing name
            add(buildReleaseJson(tagName = "dev-3", name = null, prerelease = true))
            // Formal valid
            add(buildReleaseJson(tagName = "v4.9.7", name = "v4.9.7", prerelease = false))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.6",
            includePreview = true,
        )

        assertNotNull(result)
        assertEquals("4.9.7", result!!.version)
    }

    private fun validManifestJson(): String = """
        {
          "schema": 1,
          "channel": "stable",
          "versionName": "5.2.2",
          "versionCode": 92,
          "apkUrl": "https://dzhz.zyxf.top/xjtu-toolbox/apk/5.2.2/app-release.apk",
          "mirrors": ["https://github.com/yeliqin666/xjtu-toolbox-android/releases/download/v5.2.2/app-release.apk"],
          "sha256": "${"a".repeat(64)}",
          "size": 6712345,
          "notes": "## test",
          "releaseUrl": "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.2.2",
          "publishedAt": "2026-10-23T12:00:00Z"
        }
    """.trimIndent()

    @Test
    fun aliyunManifestParses() {
        val manifest = AppUpdater.parseOssManifest(validManifestJson())
        assertNotNull(manifest)
        assertEquals("5.2.2", manifest!!.versionName)
        assertEquals(92, manifest.versionCode)
        assertEquals(1, manifest.mirrors.size)
    }

    @Test
    fun aliyunManifestIgnoresLegacyRolloutField() {
        // 旧清单仍带 rollout: 0；新客户端不再解析这个字段，不能因此拒绝清单。
        val manifest = AppUpdater.parseOssManifest(
            validManifestJson().replace("\"publishedAt\"", "\"rollout\": 0,\n          \"publishedAt\"")
        )
        assertNotNull(manifest)
        assertEquals("5.2.2", manifest!!.versionName)
    }

    @Test
    fun aliyunManifestRejectsBadShaAndHttpUrl() {
        val valid = validManifestJson()
        assertNotNull(AppUpdater.parseOssManifest(valid))
        assertNull(AppUpdater.parseOssManifest(valid.replace("a".repeat(64), "short")))
        assertNull(
            AppUpdater.parseOssManifest(
                valid.replace("https://dzhz.zyxf.top", "http://dzhz.zyxf.top")
            )
        )
    }

    @Test
    fun newestManifestUsesPublishTimeForSameBasePreview() {
        val stable = UpdateManifest(
            schema = 1,
            channel = "stable",
            versionName = "5.2.0",
            apkUrl = "https://example.com/app.apk",
            sha256 = "a".repeat(64),
            publishedAt = "2026-10-20T08:00:00Z",
        )
        val preview = stable.copy(
            channel = "preview",
            versionName = "5.2.0-dev.100",
            publishedAt = "2026-10-22T08:00:00Z",
        )

        assertEquals("5.2.0-dev.100", AppUpdater.pickNewestManifest(stable, preview)!!.versionName)
        assertEquals("5.2.0", AppUpdater.pickNewestManifest(stable, null)!!.versionName)
    }

    @Test
    fun aliyunChannelLabelAndNormalization() {
        assertEquals("阿里云", AppUpdater.channelLabel(AppUpdater.CHANNEL_ALIYUN))
        assertEquals("GitHub", AppUpdater.channelLabel(AppUpdater.CHANNEL_GITHUB))
        assertEquals("Gitee", AppUpdater.channelLabel(AppUpdater.CHANNEL_GITEE))
        assertEquals(AppUpdater.CHANNEL_ALIYUN, AppUpdater.normalizeChannel("oss"))
        assertEquals(AppUpdater.CHANNEL_ALIYUN, AppUpdater.normalizeChannel("stable"))
        assertEquals(AppUpdater.CHANNEL_GITEE, AppUpdater.normalizeChannel("gitee"))
    }
}
