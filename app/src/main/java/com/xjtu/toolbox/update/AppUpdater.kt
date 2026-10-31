package com.xjtu.toolbox.update

import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.booleanValue
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isPrimitive
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import com.xjtu.toolbox.network.HttpClients
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.bulletin.BulletinRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val channel: String = AppUpdater.CHANNEL_ALIYUN,
    val channelLabel: String = AppUpdater.channelLabel(channel),
    val isPreview: Boolean = false,
    val sha256: String? = null,  // 小写十六进制，不带前缀
    val publishedAtMillis: Long = 0,
    val versionCode: Int = 0,
    /** 阿里云清单里的备用下载地址，主地址失败时依次尝试。 */
    val mirrors: List<String> = emptyList(),
)

/** 阿里云 OSS 上的 stable.json / preview.json 结构。 */
@Serializable
data class UpdateManifest(
    val schema: Int = 0,
    val channel: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    val apkUrl: String = "",
    val mirrors: List<String> = emptyList(),
    val sha256: String = "",
    val size: Long = 0,
    val notes: String = "",
    val releaseUrl: String = "",
    val publishedAt: String = "",
)

object AppUpdater {
    const val CHANNEL_ALIYUN = "aliyun"
    const val CHANNEL_GITEE = "gitee"
    const val CHANNEL_GITHUB = "github"
    const val OSS_BASE_URL = "https://dzhz.zyxf.top/xjtu-toolbox"
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    val channelKeys = listOf(
        CHANNEL_ALIYUN,
        CHANNEL_GITHUB,
        CHANNEL_GITEE,
    )

    val channelLabels = listOf(
        "阿里云",
        "GitHub",
        "Gitee",
    )

    private val client = HttpClients.base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 清单很小，不能像 APK 下载一样等 120 秒；OSS 不可用时尽快回退。 */
    private val manifestClient = HttpClients.base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

    private val githubDigestCache = ConcurrentHashMap<String, String>()

    fun normalizeChannel(channel: String?): String = when (channel?.trim()?.lowercase()) {
        // 旧版本的 stable/beta 都走默认渠道；现在默认是阿里云。
        CHANNEL_ALIYUN, "oss", "auto", "stable", "beta" -> CHANNEL_ALIYUN
        CHANNEL_GITHUB, "github_latest" -> CHANNEL_GITHUB
        CHANNEL_GITEE, "gitee_latest" -> CHANNEL_GITEE
        else -> CHANNEL_ALIYUN
    }

    fun channelLabel(channel: String?): String = when (normalizeChannel(channel)) {
        CHANNEL_ALIYUN -> "阿里云"
        CHANNEL_GITHUB -> "GitHub"
        CHANNEL_GITEE -> "Gitee"
        else -> "阿里云"
    }

    fun releasesPageUrl(channel: String): String = when (normalizeChannel(channel)) {
        CHANNEL_GITHUB -> "https://github.com/yeliqin666/xjtu-toolbox-android/releases/latest"
        CHANNEL_GITEE -> "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases"
        // 阿里云清单自带 releaseUrl；拿不到时用 GitHub 发布页兜底。
        else -> "https://github.com/yeliqin666/xjtu-toolbox-android/releases/latest"
    }

    /**
     * 拉当前渠道的最新 Release，不跟本机版本比。
     * 强制更新点「立即更新」必须走这条：公告已经认定要升，再过滤一次会误报「暂未查到」。
     */
    suspend fun fetchLatest(
        channel: String,
        includePreview: Boolean = false,
    ): AppUpdateInfo = withContext(Dispatchers.IO) {
        when (val normalized = normalizeChannel(channel)) {
            CHANNEL_ALIYUN -> {
                fetchAliyunLatest(includePreview)
                    ?: if (includePreview) {
                        // 预览版只有 GitHub Pre-release 有兜底；不能先退 Gitee 的稳定版。
                        runCatching { fetchGithubLatest(true) }.getOrNull()
                            ?: runCatching { fetchGiteeLatest() }.getOrNull()
                    } else {
                        // 正式版优先 Gitee（国内更稳），再退 GitHub。
                        runCatching { fetchGiteeLatest() }.getOrNull()
                            ?: fetchGithubLatest(false)
                    }
                    ?: error("检查更新失败")
            }
            CHANNEL_GITHUB -> fetchGithubLatest(includePreview)
            else -> fetchGiteeLatest()
        }
    }

    /** OSS 清单不可用或字段非法时返回 null，交给 Gitee/GitHub 兜底。 */
    private fun fetchAliyunLatest(includePreview: Boolean): AppUpdateInfo? {
        val stable = fetchOssManifest("$OSS_BASE_URL/stable.json")
        val preview = if (includePreview) {
            fetchOssManifest("$OSS_BASE_URL/preview.json")
        } else {
            null
        }
        return pickNewestManifest(stable, preview)?.toUpdateInfo()
    }

    internal fun pickNewestManifest(a: UpdateManifest?, b: UpdateManifest?): UpdateManifest? =
        listOfNotNull(a, b).maxWithOrNull { x, y ->
            val xTime = x.publishedAtMillis()
            val yTime = y.publishedAtMillis()
            if (xTime > 0 && yTime > 0 && xTime != yTime) {
                xTime.compareTo(yTime)
            } else {
                BulletinRules.compareVersions(x.versionName, y.versionName)
            }
        }

    private fun UpdateManifest.publishedAtMillis(): Long =
        runCatching { java.time.Instant.parse(publishedAt).toEpochMilli() }.getOrDefault(0)

    private fun UpdateManifest.toUpdateInfo(): AppUpdateInfo = AppUpdateInfo(
        version = versionName,
        notes = notes,
        downloadUrl = apkUrl,
        releaseUrl = releaseUrl,
        channel = CHANNEL_ALIYUN,
        channelLabel = channelLabel(CHANNEL_ALIYUN),
        isPreview = channel == "preview",
        sha256 = sha256,
        publishedAtMillis = publishedAtMillis(),
        versionCode = versionCode,
        mirrors = mirrors.filter { it.startsWith("https://") }.distinct(),
    )

    private fun fetchOssManifest(url: String): UpdateManifest? {
        return try {
            val body = manifestClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "XJTUToolBox/${BuildConfig.VERSION_NAME}")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }
            parseOssManifest(body)
        } catch (_: Exception) {
            null
        }
    }

    /** 解析并校验清单；测试也直接用它。 */
    internal fun parseOssManifest(body: String): UpdateManifest? = runCatching {
        val manifest = AppJson.decodeFromString<UpdateManifest>(body)
        require(manifest.schema == 1) { "unsupported manifest schema" }
        require(manifest.versionName.isNotBlank()) { "versionName missing" }
        require(manifest.apkUrl.startsWith("https://")) { "apkUrl must be https" }
        require(manifest.sha256.matches(SHA256_REGEX)) { "sha256 invalid" }
        manifest
    }.getOrNull()

    private suspend fun fetchGithubLatest(
        includePreview: Boolean,
    ): AppUpdateInfo {
        if (includePreview) {
            val url = "https://api.github.com/repos/yeliqin666/xjtu-toolbox-android/releases?per_page=30"
            val array = AppJson.parseToJsonElement(fetchText(url)).jsonArray
            pickRelease(array, BuildConfig.VERSION_NAME, includePreview = true)
                ?.let { return it }
        }
        return fetchRelease(CHANNEL_GITHUB).info
    }

    private suspend fun fetchGiteeLatest(): AppUpdateInfo {
        val primary = fetchRelease(CHANNEL_GITEE)
        if (!primary.hasApkAsset) {
            val github = runCatching { fetchRelease(CHANNEL_GITHUB) }.getOrNull()
            if (github?.hasApkAsset == true) {
                return primary.info.copy(
                    downloadUrl = github.info.downloadUrl,
                    sha256 = github.info.sha256,
                )
            }
        }
        if (primary.info.sha256 == null) {
            return primary.info.copy(sha256 = fetchGithubDigestByTag(primary.info.version))
        }
        return primary.info
    }

    private fun fetchText(url: String, accept: String = "application/json"): String =
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", "XJTUToolBox/${BuildConfig.VERSION_NAME}")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("服务器响应 ${response.code}")
            response.body?.string() ?: error("服务器没有返回内容")
        }

    /** 仅当远端版本比本机新时返回，给设置里「检查更新」用。 */
    suspend fun check(
        channel: String,
        includePreview: Boolean = false,
    ): AppUpdateInfo? {
        val latest = fetchLatest(channel, includePreview)
        return latest.takeIf {
            isNewerUpdate(BuildConfig.VERSION_NAME, it)
        }
    }

    internal fun isNewerUpdate(current: String, candidate: AppUpdateInfo): Boolean {
        val comparison = BulletinRules.compareVersions(current, candidate.version)
        if (comparison < 0) return true
        // dev 构建沿用当前正式版基号。用户主动开启预览后，同基号的 dev 也应可升级。
        return comparison > 0 && candidate.isPreview && '-' !in current &&
            current.removePrefix("v") == candidate.version.removePrefix("v").substringBefore('-')
    }

    fun pickRelease(
        releases: JsonArray,
        currentVersion: String,
        includePreview: Boolean = false,
    ): AppUpdateInfo? {
        val candidates = mutableListOf<AppUpdateInfo>()
        for (el in releases) {
            if (!el.isObject) continue
            val obj = el.jsonObject
            val draft = obj.get("draft")?.takeIf { it.isPrimitive }?.booleanValue ?: false
            if (draft) continue

            val isPrerelease = obj.get("prerelease")?.takeIf { it.isPrimitive }?.booleanValue ?: false
            val tagName = obj.get("tag_name")?.takeIf { it.isPrimitive }?.stringValue.orEmpty()
            val name = obj.get("name")?.takeIf { it.isPrimitive }?.stringValue?.trim().orEmpty()
            val body = obj.get("body")?.takeIf { it.isPrimitive }?.stringValue.orEmpty()
            val htmlUrl = obj.get("html_url")?.takeIf { it.isPrimitive }?.stringValue.orEmpty()

            val assets = obj.arr("assets")
            val apkAsset = assets
                ?.mapNotNull { item -> item.takeIf { it.isObject }?.jsonObject }
                ?.firstOrNull { asset ->
                    asset.get("name")?.stringValue?.endsWith(".apk", ignoreCase = true) == true
                }
            val downloadUrl = apkAsset?.get("browser_download_url")?.stringValue
            val sha256 = apkAsset
                ?.get("digest")
                ?.stringValue
                ?.removePrefix("sha256:")
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.length == 64 }

            val version: String
            if (isPrerelease) {
                if (!includePreview) continue
                // 预览版必须有 apk asset
                if (apkAsset == null || downloadUrl.isNullOrBlank()) continue
                // 预览版版本号从 name 取（CI 约定 name 为完整版本号，如 4.9.8-dev.12）
                if (name.isBlank()) continue
                version = name
            } else {
                // 正式版
                val ver = tagName.removePrefix("v").trim()
                if (ver.isBlank()) continue
                version = ver
            }

            val finalDownloadUrl = downloadUrl
                ?: "https://github.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"

            candidates += AppUpdateInfo(
                version = version,
                notes = body,
                downloadUrl = finalDownloadUrl,
                releaseUrl = htmlUrl,
                channel = CHANNEL_GITHUB,
                channelLabel = channelLabel(CHANNEL_GITHUB),
                isPreview = isPrerelease,
                sha256 = sha256,
                publishedAtMillis = obj.get("published_at")?.stringValue?.let {
                    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                } ?: 0,
            )
        }

        // GitHub 的稳定版排序与语义版本号都可能把同基号预览压在后面；按发布时刻选，缺时间才按版本号。
        return candidates.maxWithOrNull { a, b ->
            if (includePreview && a.publishedAtMillis > 0 && b.publishedAtMillis > 0 && a.publishedAtMillis != b.publishedAtMillis)
                a.publishedAtMillis.compareTo(b.publishedAtMillis)
            else BulletinRules.compareVersions(a.version, b.version)
        }
    }

    private suspend fun fetchRelease(channel: String): ParsedRelease {
        val url = if (channel == CHANNEL_GITHUB) {
            "https://api.github.com/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
        } else {
            "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
        }
        return AppJson.parseToJsonElement(fetchText(url)).jsonObject.toParsedRelease(channel)
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        android.util.Log.d("AppUpdater", "准备下载，版本: ${info.version}, 期望哈希: ${info.sha256}")
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(updateDir, "xjtu-toolbox-$safeVersion.apk")
        val partial = File(updateDir, "${target.name}.part")

        val urls = (listOf(info.downloadUrl) + info.mirrors)
            .filter { it.isNotBlank() }
            .distinct()
        var lastError: Exception? = null
        for (url in urls) {
            try {
                downloadTo(url, partial, onProgress)
                if (partial.length() < 100_000L || partial.inputStream().use {
                        it.read() != 0x50 || it.read() != 0x4B
                    }) {
                    error("下载内容不是有效安装包")
                }
                lastError = null
                break
            } catch (e: Exception) {
                android.util.Log.w("AppUpdater", "下载失败，尝试下一个源: $url", e)
                lastError = e
                partial.delete()
            }
        }
        if (lastError != null || !partial.exists()) {
            throw lastError ?: error("下载失败")
        }

        val expected = info.sha256
        var toastMsg: String?
        if (!expected.isNullOrBlank()) {
            val actual = sha256Hex(partial)
            if (!actual.equals(expected, ignoreCase = true)) {
                partial.delete()
                error("安装包 SHA-256 校验失败")
            }
            toastMsg = "安装包 SHA-256 校验通过"
        } else {
            toastMsg = "未获取到哈希，已跳过校验"
        }

        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) error("无法保存安装包")

        toastMsg.let { msg ->
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        target
    }

    private fun downloadTo(
        url: String,
        partial: File,
        onProgress: (Float) -> Unit,
    ) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("下载失败（${response.code}）")
            val body = response.body ?: error("下载内容为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
        )
    }

    private data class ParsedRelease(
        val info: AppUpdateInfo,
        val hasApkAsset: Boolean,
    )

    private fun JsonObject.toParsedRelease(channel: String): ParsedRelease {
        val version = get("tag_name")?.stringValue?.removePrefix("v")
            ?: error("版本信息缺失")
        val apkAsset = arr("assets")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { asset ->
                asset.get("name")?.stringValue?.endsWith(".apk", ignoreCase = true) == true
            }
        val downloadUrl = apkAsset?.get("browser_download_url")?.stringValue
            ?: if (channel.startsWith("github")) {
                "https://github.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"
            } else {
                "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"
            }
        val sha256 = apkAsset
            ?.get("digest")
            ?.stringValue
            ?.removePrefix("sha256:")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.length == 64 }
        return ParsedRelease(
            info = AppUpdateInfo(
                version = version,
                notes = get("body")?.stringValue.orEmpty(),
                downloadUrl = downloadUrl,
                releaseUrl = get("html_url")?.stringValue.orEmpty(),
                channel = channel,
                channelLabel = channelLabel(channel),
                sha256 = sha256,
            ),
            hasApkAsset = apkAsset != null,
        )
    }

    private suspend fun fetchGithubDigestByTag(version: String): String? {
        githubDigestCache[version]?.let { return it }
        val digest = runCatching {
            val url = "https://api.github.com/repos/yeliqin666/xjtu-toolbox-android/releases/tags/v$version"
            val body = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "XJTUToolBox/${BuildConfig.VERSION_NAME}")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string() ?: return@runCatching null
            }
            AppJson.parseToJsonElement(body).jsonObject
                .arr("assets")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.get("name")?.stringValue?.endsWith(".apk", true) == true }
                ?.get("digest")?.stringValue
                ?.removePrefix("sha256:")?.lowercase()
                ?.takeIf { it.length == 64 }
        }.getOrNull()

        if (digest != null) {
            githubDigestCache[version] = digest
        }
        return digest
    }
}
