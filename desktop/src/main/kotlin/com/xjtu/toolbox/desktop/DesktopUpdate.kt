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

/** 桌面端的更新骨架：查一次 GitHub 发布页，比一下版本；有新版就把发布页交出去。 */
internal object DesktopUpdate {

    /** 发布页 / 接口所在仓库（与 `:app` 的 `AppUpdater` 同一个）。 */
    const val REPO = "yeliqin666/xjtu-toolbox-android"

    /** 给人点的发布页（与 `:app` 的 `AppUpdater.releasesPageUrl(CHANNEL_GITHUB)` 同一个 URL 形状）。 */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** 发布列表接口（公开、不需要 token；30 条足够找到「最新一个带桌面产物的」）。 */
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** 这一端没有自动安装 / 自更新通道（见文件头）。 */
    const val canInstallAutomatically: Boolean = false

    /** 一次检查的结果。 */
    sealed interface CheckResult {
        /** 有比本机新的桌面版发布。 */
        data class Available(val release: DesktopRelease) : CheckResult

        /** 查到了，但没有比本机新的桌面版发布。 */
        data object UpToDate : CheckResult

        /** 没查到（断网 / 接口变了 / 根本还没有桌面产物）。[reason] 是给人看的一句话。 */
        data class Failed(val reason: String) : CheckResult
    }

    /** 一份发布的桌面端视图：只留这一端用得上的几个字段。 */
    internal data class DesktopRelease(
        val version: String,
        val notes: String,
        val releaseUrl: String,
        val packageUrl: String,
        val isPrerelease: Boolean,
    )

    /** 那条 GET 用的客户端：进程级一份（与 `Main.kt` 里的 `yellowPageClient` 同一条纪律： 别每点一次「检查更新」就新建一个连接池）。 */
    private val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 查一次更新。 */
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

    /** 纯函数那半（测试直接喂 JSON）：挑最新一个带桌面产物且比 [currentVersion] 新的发布。 */
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
