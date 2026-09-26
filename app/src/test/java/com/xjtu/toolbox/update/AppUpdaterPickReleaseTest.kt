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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
class AppUpdaterPickReleaseTest {

    private fun buildReleaseJson(
        tagName: String,
        name: String? = null,
        body: String = "",
        prerelease: Boolean = false,
        draft: Boolean = false,
        hasApk: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("tag_name", tagName)
        if (name != null) put("name", name)
        put("body", body)
        put("prerelease", prerelease)
        put("draft", draft)
        put("html_url", "https://github.com/releases/$tagName")
        putJsonArray("assets") {
            if (hasApk) addJsonObject {
                put("name", "app-preview.apk")
                put("browser_download_url", "https://github.com/download/$tagName/app.apk")
            }
        }
    }

    @Test
    fun extractRolloutPercentage() {
        assertEquals(100, AppUpdater.extractRolloutPercentage(""))
        assertEquals(100, AppUpdater.extractRolloutPercentage("一些无关说明"))
        assertEquals(20, AppUpdater.extractRolloutPercentage("rollout: 20"))
        assertEquals(50, AppUpdater.extractRolloutPercentage("  rollout: 50%  \n详细说明"))
        assertEquals(0, AppUpdater.extractRolloutPercentage("ROLLOUT: 0"))
        assertEquals(100, AppUpdater.extractRolloutPercentage("rollout: 150")) // clamped to 100
    }

    @Test
    fun rolloutBucketDistribution() {
        // rollout: 0 永远不中，rollout: 100 永远中
        val tag = "dev-1"
        for (i in 0 until 100) {
            val id = UUID.randomUUID().toString()
            assertFalse(AppUpdater.isRolloutHit(id, tag, 0))
            assertTrue(AppUpdater.isRolloutHit(id, tag, 100))
        }

        // 同 rolloutId 同 tag 结果稳定
        val fixedId = UUID.randomUUID().toString()
        val r1 = AppUpdater.isRolloutHit(fixedId, tag, 30)
        val r2 = AppUpdater.isRolloutHit(fixedId, tag, 30)
        assertEquals(r1, r2)

        // 单调性：如果 30% 命中，那么 60% 必定命中
        for (i in 0 until 500) {
            val id = UUID.randomUUID().toString()
            if (AppUpdater.isRolloutHit(id, tag, 30)) {
                assertTrue(AppUpdater.isRolloutHit(id, tag, 60))
            }
        }

        // 统计 10000 个随机 ID 分桶在 20% 左右（±3%）
        var hitCount = 0
        val total = 10000
        for (i in 0 until total) {
            if (AppUpdater.isRolloutHit(UUID.randomUUID().toString(), tag, 20)) {
                hitCount++
            }
        }
        val pct = hitCount.toDouble() / total * 100.0
        assertTrue("Hit pct $pct should be between 17% and 23%", pct in 17.0..23.0)
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
            rolloutId = "some-id",
        )

        assertNotNull(result)
        assertEquals("4.9.7", result!!.version)
        assertFalse(result.isPreview)
    }

    @Test
    fun pickRelease_previewEnabled_returnsPreviewIfGreater() {
        val releases = buildJsonArray {
            add(buildReleaseJson(tagName = "v4.9.7", name = "v4.9.7", prerelease = false))
            add(buildReleaseJson(tagName = "dev-1", name = "4.9.8-dev.1", prerelease = true, body = "rollout: 100"))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.7",
            includePreview = true,
            rolloutId = "some-id",
        )

        assertNotNull(result)
        assertEquals("4.9.8-dev.1", result!!.version)
        assertTrue(result.isPreview)
    }

    @Test
    fun pickRelease_skipsDraftAndMissingApkAndMissingName() {
        val releases = buildJsonArray {
            // Draft formal
            add(buildReleaseJson(tagName = "v4.9.9", draft = true))
            // Preview missing apk
            add(buildReleaseJson(tagName = "dev-2", name = "4.9.9-dev.2", prerelease = true, hasApk = false, body = "rollout: 100"))
            // Preview missing name
            add(buildReleaseJson(tagName = "dev-3", name = null, prerelease = true, body = "rollout: 100"))
            // Formal valid
            add(buildReleaseJson(tagName = "v4.9.7", name = "v4.9.7", prerelease = false))
        }

        val result = AppUpdater.pickRelease(
            releases = releases,
            currentVersion = "4.9.6",
            includePreview = true,
            rolloutId = "some-id",
        )

        assertNotNull(result)
        assertEquals("4.9.7", result!!.version)
    }
}
