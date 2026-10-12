package com.xjtu.toolbox.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 更新骨架的两半：**纯函数那半**（挑发布、比版本）用固定夹具喂进去，**那条 HTTP GET** 用注入的
 * 假实现替掉（`DesktopUpdate.check` 的 `fetch` 参数）。
 *
 * 这份测试要钉的不是「网络能用」，而是三件容易做错的事：
 *
 *  1. **不许把 APK 的发布当成桌面版**：仓库里现在只有 `app-release.apk`，桌面产物还只在 CI 的
 *     artifact 里 ⇒ 只有带 `.msi` / `.exe` 的发布才算数（没有就如实说没有）；
 *  2. **失败不许说成「已是最新」**：断网 / 500 / 空响应 / 坏 JSON 都是 `Failed`，带一句人话；
 *  3. **这一端没有自动更新**：[DesktopUpdate.canInstallAutomatically] 是 `false`（UI 不许假装有）。
 */
class DesktopUpdateJvmTest {

    /** 一份照着 GitHub `releases?per_page=30` 的形状写的夹具（只留这个骨架读的几个字段）。 */
    private val fixture = """
    [
      {
        "tag_name": "v5.2.0-draft",
        "name": "5.2.0-draft",
        "draft": true,
        "prerelease": false,
        "html_url": "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.2.0-draft",
        "body": "草稿：不该被任何人看到",
        "assets": [
          {"name": "xjtu-toolbox-5.2.0.msi", "browser_download_url": "https://example.invalid/draft.msi"}
        ]
      },
      {
        "tag_name": "v5.1.2",
        "name": "5.1.2",
        "draft": false,
        "prerelease": false,
        "html_url": "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.1.2",
        "body": "## 岱宗盒子 v5.1.2\n- 桌面端：真数据\n- 修复：若干",
        "assets": [
          {"name": "app-release.apk", "browser_download_url": "https://example.invalid/app-release.apk"},
          {"name": "xjtu-toolbox-5.1.2-1.msi", "browser_download_url": "https://example.invalid/xjtu-toolbox-5.1.2-1.msi"},
          {"name": "xjtu-toolbox-5.1.1-linux-x64.tar.gz", "browser_download_url": "https://example.invalid/tar.gz"}
        ]
      },
      {
        "tag_name": "v5.1.1",
        "name": "5.1.1",
        "draft": false,
        "prerelease": false,
        "html_url": "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.1.1",
        "body": "旧版",
        "assets": [
          {"name": "xjtu-toolbox-5.1.1.exe", "browser_download_url": "https://example.invalid/xjtu-toolbox-5.1.1.exe"}
        ]
      }
    ]
    """.trimIndent()

    /** 只有 Android 产物的发布（这就是仓库今天的真实状态）。 */
    private val apkOnlyFixture = """
    [
      {
        "tag_name": "v5.1.2",
        "draft": false,
        "prerelease": false,
        "html_url": "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.1.2",
        "body": "只有 APK",
        "assets": [
          {"name": "app-release.apk", "browser_download_url": "https://example.invalid/app-release.apk"}
        ]
      }
    ]
    """.trimIndent()

    @Test
    fun `有新桌面版时给出它：取带 msi 或 exe 的最新那次发布，直链不是 APK`() {
        val result = DesktopUpdate.compare(fixture, currentVersion = "5.1.1")

        val available = assertIs<DesktopUpdate.CheckResult.Available>(result)
        assertEquals("5.1.2", available.release.version)
        assertEquals("https://example.invalid/xjtu-toolbox-5.1.2-1.msi", available.release.packageUrl)
        assertEquals(
            "https://github.com/yeliqin666/xjtu-toolbox-android/releases/tag/v5.1.2",
            available.release.releaseUrl,
        )
        assertTrue(available.release.notes.contains("桌面端"), "更新说明要原样带出来")
        assertFalse(available.release.isPrerelease)
        // 草稿那次虽然版本更高（5.2.0-draft）且带 msi，但 draft=true ⇒ 不算
        assertFalse(result.toString().contains("5.2.0-draft"))
    }

    @Test
    fun `本机版本不低于最新桌面版就是「已是最新」，dev 版则会拿到同号正式版`() {
        assertEquals(DesktopUpdate.CheckResult.UpToDate, DesktopUpdate.compare(fixture, currentVersion = "5.1.2"))
        assertEquals(DesktopUpdate.CheckResult.UpToDate, DesktopUpdate.compare(fixture, currentVersion = "5.2.0"))
        // 预发布号比同号正式版小（`:core` 的 compareVersions 口径）：5.1.2-dev.1 < 5.1.2
        // ⇒ 拿着 dev 版的人**应该**收到「有 5.1.2 正式版」这个结论（不是「已是最新」）
        assertEquals(
            "5.1.2",
            assertIs<DesktopUpdate.CheckResult.Available>(
                DesktopUpdate.compare(fixture, currentVersion = "5.1.2-dev.1"),
            ).release.version,
        )
    }

    @Test
    fun `只有 APK 的发布不算桌面版：如实说没有，而不是推一个 APK 的版本号给用户`() {
        val result = DesktopUpdate.compare(apkOnlyFixture, currentVersion = "5.1.0")

        val failed = assertIs<DesktopUpdate.CheckResult.Failed>(result)
        assertTrue(failed.reason.contains(".msi"), "失败原因要写清缺什么：${failed.reason}")
    }

    @Test
    fun `坏 JSON 与不是数组的响应：失败，不抛`() {
        assertIs<DesktopUpdate.CheckResult.Failed>(DesktopUpdate.compare("not json at all", "5.1.1"))
        assertIs<DesktopUpdate.CheckResult.Failed>(DesktopUpdate.compare("""{"message":"Not Found"}""", "5.1.1"))
        assertIs<DesktopUpdate.CheckResult.Failed>(DesktopUpdate.compare("[]", "5.1.1"))
    }

    @Test
    fun `check：那条 GET 失败或空响应都要如实说失败，不谎报「已是最新」`() {
        val thrown = DesktopUpdate.check(currentVersion = "5.1.1") { throw IllegalStateException("服务器响应 403") }
        assertTrue(assertIs<DesktopUpdate.CheckResult.Failed>(thrown).reason.contains("403"))

        val empty = DesktopUpdate.check(currentVersion = "5.1.1") { null }
        assertTrue(assertIs<DesktopUpdate.CheckResult.Failed>(empty).reason.contains("没有返回内容"))

        // 假响应喂进去 ⇒ 走的是同一条解析/比较路径
        val ok = DesktopUpdate.check(currentVersion = "5.1.1") { fixture }
        assertEquals("5.1.2", assertIs<DesktopUpdate.CheckResult.Available>(ok).release.version)
    }

    @Test
    fun `这条接口就是 GitHub 的发布列表，发布页就是给人点的那一页`() {
        assertTrue(DesktopUpdate.RELEASES_API.startsWith("https://api.github.com/repos/"))
        assertTrue(DesktopUpdate.RELEASES_API.endsWith("/releases?per_page=30"))
        assertEquals("https://github.com/yeliqin666/xjtu-toolbox-android/releases/latest", DesktopUpdate.RELEASES_PAGE)
    }

    @Test
    fun `这一端没有自动安装能力（如实声明，不许假装有）`() {
        assertFalse(DesktopUpdate.canInstallAutomatically)
    }
}
