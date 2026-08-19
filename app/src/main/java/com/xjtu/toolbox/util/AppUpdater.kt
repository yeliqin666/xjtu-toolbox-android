package com.xjtu.toolbox.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val channel: String = AppUpdater.CHANNEL_GITEE,
    val channelLabel: String = AppUpdater.channelLabel(channel),
)

object AppUpdater {
    const val CHANNEL_GITEE = "gitee"
    const val CHANNEL_GITHUB = "github"
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    val channelKeys = listOf(
        CHANNEL_GITEE,
        CHANNEL_GITHUB,
    )

    val channelLabels = listOf(
        "Gitee（推荐）",
        "GitHub",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeChannel(channel: String?): String = when (channel?.trim()?.lowercase()) {
        // 兼容旧版本存下的 4 渠道值：稳定版/最新版统一归一化为对应主渠道。
        "stable", "beta", CHANNEL_GITEE, "gitee_latest" -> CHANNEL_GITEE
        CHANNEL_GITHUB, "github_latest" -> CHANNEL_GITHUB
        else -> CHANNEL_GITEE
    }

    fun channelLabel(channel: String?): String {
        val normalized = normalizeChannel(channel)
        val idx = channelKeys.indexOf(normalized)
        return channelLabels.getOrElse(idx) { "Gitee（推荐）" }
    }

    fun releasesPageUrl(channel: String): String =
        if (normalizeChannel(channel) == CHANNEL_GITHUB) {
            "https://github.com/yeliqin666/xjtu-toolbox-android/releases/latest"
        } else {
            "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases"
        }

    /**
     * 拉当前渠道的最新 Release，不跟本机版本比。
     * 强制更新点「立即更新」必须走这条：公告已经认定要升，再过滤一次会误报「暂未查到」。
     */
    suspend fun fetchLatest(channel: String): AppUpdateInfo = withContext(Dispatchers.IO) {
        val normalizedChannel = normalizeChannel(channel)
        val primary = fetchRelease(normalizedChannel)
        if (!primary.hasApkAsset && normalizedChannel == CHANNEL_GITEE) {
            val github = runCatching { fetchRelease(CHANNEL_GITHUB) }.getOrNull()
            if (github?.hasApkAsset == true) {
                return@withContext primary.info.copy(downloadUrl = github.info.downloadUrl)
            }
        }
        primary.info
    }

    /** 仅当远端版本比本机新时返回，给设置里「检查更新」用。 */
    suspend fun check(channel: String): AppUpdateInfo? {
        val latest = fetchLatest(channel)
        return latest.takeIf {
            MainActivity.compareVersionStrings(BuildConfig.VERSION_NAME, it.version) < 0
        }
    }

    private suspend fun fetchRelease(channel: String): ParsedRelease {
        val github = channel == CHANNEL_GITHUB
        val url = if (github) {
            "https://api.github.com/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
        } else {
            "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
        }
        val body = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "XJTUToolBox/${BuildConfig.VERSION_NAME}")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("服务器响应 ${response.code}")
            response.body?.string() ?: error("服务器没有返回内容")
        }
        return JsonParser.parseString(body).asJsonObject.toParsedRelease(channel)
    }

    suspend fun download(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, "xjtu-toolbox-${info.version}.apk")
        val partial = File(updateDir, "${target.name}.part")
        client.newCall(Request.Builder().url(info.downloadUrl).build()).execute().use { response ->
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
        if (partial.length() < 100_000L || partial.inputStream().use {
                it.read() != 0x50 || it.read() != 0x4B
            }) {
            partial.delete()
            error("下载内容不是有效安装包")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) error("无法保存安装包")
        target
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
        val version = get("tag_name")?.asString?.removePrefix("v")
            ?: error("版本信息缺失")
        val assets = getAsJsonArray("assets")
        val apkAsset = assets
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            ?.firstOrNull { asset ->
                asset.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true
            }
        val downloadUrl = apkAsset?.get("browser_download_url")?.asString
            ?: if (channel.startsWith("github")) {
                "https://github.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"
            } else {
                "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"
            }
        return ParsedRelease(
            info = AppUpdateInfo(
                version = version,
                notes = get("body")?.asString.orEmpty(),
                downloadUrl = downloadUrl,
                releaseUrl = get("html_url")?.asString.orEmpty(),
                channel = channel,
                channelLabel = channelLabel(channel),
            ),
            hasApkAsset = apkAsset != null,
        )
    }
}
