package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isPrimitive
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.stringValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 桌面端的**更新骨架**：查一次 GitHub 发布页，比一下版本；有新版就把发布页交出去。
 *
 * ## 为什么只有骨架，为什么**不能**做成「下载并安装」
 *
 * Android 那边能自更新，是因为有 `REQUEST_INSTALL_PACKAGES` + `FileProvider` 那套系统能力；
 * 桌面端没有：**.deb / tar.gz / .msi 都只能由用户自己装**（设计文档 §7 把「更新机制（没有商店）」
 * 明确列为待定项）。所以这一端**不做**自我替换可执行文件这种事（Windows 上那还得过 SmartScreen
 * 与 UAC），只做两件真做得到的事：
 *
 *  1. **查**：拉发布列表比版本（复用 `:core` 的 [BulletinRules.compareVersions]，与 Android 的公告 /
 *     更新域同一个比较器 —— 版本号语义只此一份）；
 *  2. **打开**：把发布页交给系统浏览器（[openInBrowser]，与教师检索那一屏同一条路）。
 *
 * [canInstallAutomatically] 是 `false`，写成常量而不是注释：UI 那一层要显示「检查更新」时，
 * 得能一眼看出这一端**没有**自动安装能力（不许假装有）。
 *
 * ## 为什么挑「带桌面产物的那次发布」
 *
 * 仓库的 Release 现在装的是 Android 的 `app-release.apk`（桌面产物还只在 CI 的 artifact 里，
 * 见 `docs/windows-delivery.md`）。所以这里挑的是**最新一个带 `.msi` / `.exe` 资产的发布**：
 * 没有这种发布时如实答「还没有桌面版安装包」，而不是把 APK 的版本号当成桌面的新版推给用户。
 *
 * ## 还没接的两件事（如实 TODO）
 *
 * 1. **调用点**：这一份还没有 UI 入口 —— `:core` 的设置页那一栏由 `DesktopSettingsHost.updateSection`
 *    提供，桌面端现在给的是 `null`（见 `DesktopSettings` 的 KDoc）；接线时就是「一个按钮 + 一句文案」；
 * 2. **发布通道**：桌面产物目前只在 CI artifact 里，`.github/workflows/desktop-windows.yml` 没有
 *    「发布到 Release」那一步（要不要发、发到哪个 tag，与 Android 的发版流程一起定）。
 */
internal object DesktopUpdate {

    /** 发布页 / 接口所在仓库（与 `:app` 的 `AppUpdater` 同一个）。 */
    const val REPO = "yeliqin666/xjtu-toolbox-android"

    /** 给人点的发布页（与 `:app` 的 `AppUpdater.releasesPageUrl(CHANNEL_GITHUB)` 同一个 URL 形状）。 */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** 发布列表接口（公开、不需要 token；30 条足够找到「最新一个带桌面产物的」）。 */
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /**
     * 这一端**没有**自动安装 / 自更新通道（见文件头）。UI 要按它决定「检查更新」点下去是
     * 「告诉你新版在哪」还是「悄悄把包换掉」。
     */
    const val canInstallAutomatically: Boolean = false

    /**
     * 一次检查的结果。
     *
     * 三种状态刻意分开：[Failed] **不是** [UpToDate] —— 断网时说「已是最新」是在骗人
     *（与 `:core` 的公告 / 更新域「失败要如实说失败」同一条口径）。
     */
    sealed interface CheckResult {
        /** 有比本机新的桌面版发布。 */
        data class Available(val release: DesktopRelease) : CheckResult

        /** 查到了，但没有比本机新的桌面版发布。 */
        data object UpToDate : CheckResult

        /** 没查到（断网 / 接口变了 / 根本还没有桌面产物）。[reason] 是给人看的一句话。 */
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 一份发布的桌面端视图：只留这一端用得上的几个字段。
     *
     * [packageUrl] 是 `.msi` / `.exe` 的直链（用户点「下载」时用它）；[releaseUrl] 是发布页
     *（看更新说明、找别的产物时用它）。
     */
    internal data class DesktopRelease(
        val version: String,
        val notes: String,
        val releaseUrl: String,
        val packageUrl: String,
        val isPrerelease: Boolean,
    )

    /**
     * 那条 GET 用的客户端：进程级一份（与 `Main.kt` 里的 `yellowPageClient` 同一条纪律：
     * 别每点一次「检查更新」就新建一个连接池）。
     *
     * 超时压得比 `:app` 的 `AppUpdater`（连接 15s / 读 120s）短：桌面端这是用户点一下才发生的事，
     * 卡两分钟不如早点告诉他「连不上」。
     */
    private val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 查一次更新。[currentVersion] 默认是本包的版本（构建期烘进 `DesktopBuildConfig`），
     * [fetch] 是那条 HTTP GET（默认真的发请求；测试注入假响应 —— 与 `:core` 的黄页那条
     * 「端口收一个 HttpClient」是同一条思路，只是这里只需要「给 URL 拿一段文本」）。
     */
    internal fun check(
        currentVersion: String = DesktopBuildConfig.VERSION_NAME,
        fetch: (String) -> String? = ::fetchReleasesJson,
    ): CheckResult {
        val body = try {
            fetch(RELEASES_API)
        } catch (e: Exception) {
            return CheckResult.Failed("连不上发布页：${e.message ?: e::class.simpleName}")
        } ?: return CheckResult.Failed("发布页没有返回内容")

        return compare(body, currentVersion)
    }

    /**
     * 纯函数那半（测试直接喂 JSON）：挑最新一个**带桌面产物**且比 [currentVersion] 新的发布。
     *
     * 两条筛子各有理由：
     *  - `draft == true` 直接跳过（草稿不该被任何人看到）；
     *  - 没有 `.msi` / `.exe` 资产的发布**不算桌面版**（仓库里现在装的是 APK 的发布，见文件头）。
     */
    internal fun compare(body: String, currentVersion: String): CheckResult {
        val releases = runCatching { AppJson.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return CheckResult.Failed("发布页返回的不是发布列表（接口变了？）")

        val candidates = releases.mapNotNull { element ->
            val obj = element.takeIf { it.isObject }?.jsonObject ?: return@mapNotNull null
            if (flag(obj.safeGet("draft"))) return@mapNotNull null

            val version = text(obj.safeGet("tag_name")).removePrefix("v").trim()
            if (version.isEmpty()) return@mapNotNull null

            val asset = obj.arr("assets")
                ?.mapNotNull { it.takeIf { it.isObject }?.jsonObject }
                ?.firstOrNull { item ->
                    val name = text(item.safeGet("name"))
                    name.endsWith(".msi", ignoreCase = true) || name.endsWith(".exe", ignoreCase = true)
                } ?: return@mapNotNull null

            DesktopRelease(
                version = version,
                notes = text(obj.safeGet("body")),
                releaseUrl = text(obj.safeGet("html_url")).ifEmpty { RELEASES_PAGE },
                packageUrl = text(asset.safeGet("browser_download_url")),
                isPrerelease = flag(obj.safeGet("prerelease")),
            )
        }

        if (candidates.isEmpty()) {
            return CheckResult.Failed("还没有带桌面安装包的发布（.msi / .exe）—— Windows 打包那条 CI 还没发过版")
        }
        val newest = candidates.maxWithOrNull { a, b -> BulletinRules.compareVersions(a.version, b.version) }!!
        return if (BulletinRules.compareVersions(currentVersion, newest.version) < 0) {
            CheckResult.Available(newest)
        } else {
            CheckResult.UpToDate
        }
    }

    /**
     * 打开发布页（系统的默认浏览器）。没有「成功/失败」可报：只能把网址交给系统，
     * 交出去之后由用户的默认浏览器说了算（与 [openInBrowser] 同口径）。
     */
    internal fun openReleasesPage(url: String = RELEASES_PAGE) {
        openInBrowser(url)
    }

    /** 真的发那条 GET。非 2xx 直接抛（[check] 会把它翻成 [CheckResult.Failed] 里的那句话）。 */
    private fun fetchReleasesJson(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "XJTUToolBox-desktop/${DesktopBuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("服务器响应 ${response.code}")
            response.body?.string()
        }
    }

    /** 取字符串字段：缺、`null`、或不是标量都当空串（这一端不需要为发布页的字段缺失抛异常）。 */
    private fun text(element: JsonElement?): String =
        element?.takeIf { it.isPrimitive }?.stringValue.orEmpty()

    /** 取布尔字段：缺、`null`、或不是标量都当 `false`。 */
    private fun flag(element: JsonElement?): Boolean = element.safeBoolean()
}
