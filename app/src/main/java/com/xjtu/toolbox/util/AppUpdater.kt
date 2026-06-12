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
)

object AppUpdater {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun check(channel: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val url = if (channel == "beta") {
            "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases?per_page=10"
        } else {
            "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
        }
        val body = client.newCall(
            Request.Builder().url(url).header("Accept", "application/json").build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("服务器响应 ${response.code}")
            response.body?.string() ?: error("服务器没有返回内容")
        }
        val json = JsonParser.parseString(body)
        val release = if (channel == "beta") {
            json.asJsonArray.firstOrNull()?.asJsonObject ?: error("暂无可用版本")
        } else {
            json.asJsonObject
        }
        release.toUpdateInfo().takeIf {
            MainActivity.compareVersionStrings(BuildConfig.VERSION_NAME, it.version) < 0
        }
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

    private fun JsonObject.toUpdateInfo(): AppUpdateInfo {
        val version = get("tag_name")?.asString?.removePrefix("v")
            ?: error("版本信息缺失")
        val assets = getAsJsonArray("assets")
        val apkAsset = assets
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            ?.firstOrNull { asset ->
                asset.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true
            }
        val downloadUrl = apkAsset?.get("browser_download_url")?.asString
            ?: "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases/download/v$version/app-release.apk"
        return AppUpdateInfo(
            version = version,
            notes = get("body")?.asString.orEmpty(),
            downloadUrl = downloadUrl,
            releaseUrl = get("html_url")?.asString.orEmpty(),
        )
    }
}
