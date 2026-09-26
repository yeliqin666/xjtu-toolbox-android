package com.xjtu.toolbox.agent.skin

import com.xjtu.toolbox.network.HttpClients
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.util.concurrent.TimeUnit

object PidaiSkinGithub {
    private const val MAX_JSON_BYTES = 1_048_576L
    private const val MAX_IMAGE_BYTES = 4 * 1_048_576L
    private val ownerRepo = Regex("^[A-Za-z0-9_.-]+$")
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 支持仓库根链接，以及 `github.com/owner/repo/tree/ref/path` 形式的子目录链接。
     * 只读取三个声明文件，以及 motion.json 自己引用到的 `images/` 位图；
     * 不会遍历、下载或执行仓库里的其他内容。
     */
    suspend fun preview(url: String): PidaiSkin = withContext(Dispatchers.IO) {
        val location = parseLocation(url.trim())
        val ref = location.ref ?: defaultBranch(location.owner, location.repo)
        val files = linkedMapOf<String, ByteArray>()
        fun fetch(name: String, optional: Boolean, limit: Long) {
            val raw = "https://raw.githubusercontent.com".toHttpUrl().newBuilder()
                .addPathSegment(location.owner)
                .addPathSegment(location.repo)
                .addPathSegment(ref)
                .apply { location.directory.forEach(::addPathSegment) }
                .apply { name.split('/').forEach(::addPathSegment) }
                .build()
            val response = execute(Request.Builder().url(raw).header("User-Agent", "DaizongBox-PidaiSkin").build())
            response.use {
                if (it.code == 404 && optional) return@use
                if (!it.isSuccessful) throw PidaiSkinFormatException("GitHub 读取 $name 失败（HTTP ${it.code}）")
                files[name] = readLimited(it, name, limit)
            }
        }
        fetch("manifest.json", optional = false, limit = MAX_JSON_BYTES)
        fetch("motion.json", optional = false, limit = MAX_JSON_BYTES)
        fetch("persona.json", optional = true, limit = MAX_JSON_BYTES)
        // 只拉 motion.json 自己声明用到的位图，不遍历仓库里的其他文件。
        val assets = PidaiSkinParser.assetRefs(files.getValue("motion.json"))
        if (assets.size > 64) throw PidaiSkinFormatException("皮肤引用的图片过多")
        assets.forEach { fetch(it, optional = false, limit = MAX_IMAGE_BYTES) }
        PidaiSkinParser.parseFiles(files)
    }

    private fun defaultBranch(owner: String, repo: String): String {
        val api = "https://api.github.com".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).build()
        execute(Request.Builder().url(api).header("User-Agent", "DaizongBox-PidaiSkin").build()).use { response ->
            if (!response.isSuccessful) throw PidaiSkinFormatException("GitHub 仓库信息读取失败（HTTP ${response.code}）")
            val bytes = readLimited(response, "仓库信息", MAX_JSON_BYTES)
            return try {
                JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject["default_branch"].asString
            } catch (_: Exception) {
                throw PidaiSkinFormatException("GitHub 仓库没有可用的默认分支")
            }
        }
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: Exception) {
        throw PidaiSkinFormatException("无法连接 GitHub：${e.message ?: "网络错误"}")
    }

    private fun readLimited(response: Response, label: String, limit: Long): ByteArray {
        val body = response.body ?: throw PidaiSkinFormatException("$label 没有内容")
        if (body.contentLength() > limit) throw PidaiSkinFormatException("$label 超过体积上限")
        body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) throw PidaiSkinFormatException("$label 超过体积上限")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private data class Location(
        val owner: String,
        val repo: String,
        val ref: String?,
        val directory: List<String>,
    )

    private fun parseLocation(value: String): Location {
        val uri = try { URI(value) } catch (_: Exception) { null }
            ?: throw PidaiSkinFormatException("请输入完整的 GitHub 仓库链接")
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true)) {
            throw PidaiSkinFormatException("仅支持 https://github.com 上的公开仓库")
        }
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) throw PidaiSkinFormatException("GitHub 链接缺少仓库名")
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (!ownerRepo.matches(owner) || !ownerRepo.matches(repo)) throw PidaiSkinFormatException("GitHub 仓库名无效")
        if (parts.size == 2) return Location(owner, repo, null, emptyList())
        if (parts.size >= 4 && parts[2] == "tree") {
            val ref = parts[3]
            val directory = parts.drop(4)
            if (directory.any { it == "." || it == ".." }) throw PidaiSkinFormatException("仓库目录无效")
            return Location(owner, repo, ref, directory)
        }
        throw PidaiSkinFormatException("请使用仓库根链接或 GitHub 的目录链接")
    }
}
