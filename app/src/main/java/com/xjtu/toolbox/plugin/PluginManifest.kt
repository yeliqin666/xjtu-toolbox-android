package com.xjtu.toolbox.plugin

import com.xjtu.toolbox.plugin.api.PluginApi
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 插件 APK 里 `assets/plugin.json` 的运行时形态。字段只增不改；改语义要升 [PluginApi.VERSION]。 */
@Serializable
internal data class PluginManifest(
    val apiVersion: Int,
    val id: String,
    val name: String,
    /** 一句话简介，列表页最多两行。空串表示这个插件没写。 */
    val summary: String = "",
    val version: String = "",
    val versionCode: Int,
    val minAppVersionCode: Int = 0,
    val entryClass: String,
    val pages: List<PluginPageManifest> = emptyList(),
    val tools: List<PluginToolManifest> = emptyList(),
) {
    fun pageTitle(pageId: String): String =
        pages.firstOrNull { it.id == pageId }?.title?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
internal data class PluginPageManifest(
    val id: String,
    val title: String = "",
)

@Serializable
internal data class PluginToolManifest(
    val name: String,
    val definition: JsonObject,
)

@Serializable
internal data class PluginRegistryFile(
    val plugins: List<PluginRecord> = emptyList(),
)

/** 已安装插件的一条记录；`manifest` 是安装时从 APK 里读出的副本，加载时不再解压 APK。 */
@Serializable
internal data class PluginRecord(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    /** 相对 `filesDir/plugins` 的路径。 */
    val apk: String,
    val certSha256: String,
    val enabled: Boolean = true,
    val toolsEnabled: Boolean = false,
    val installedAt: Long = 0L,
    @SerialName("manifest") val manifest: PluginManifest,
)

private val ID_PATTERN = Regex("^[a-z][a-z0-9_.]{1,31}$")
private val PAGE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
private val TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,20}$")

/**
 * 解析并校验 `plugin.json`。任何一条不满足都直接抛 [IllegalArgumentException]，
 * 安装流程用错误文案回给用户，不做"尽量加载"。
 */
internal fun parsePluginManifest(jsonBytes: ByteArray): PluginManifest {
    val text = jsonBytes.toString(Charsets.UTF_8)
    val manifest = runCatching { AppJson.decodeFromString(PluginManifest.serializer(), text) }
        .getOrElse { throw IllegalArgumentException("plugin.json 解析失败：${it.message}") }

    require(manifest.apiVersion == PluginApi.VERSION) {
        "插件 API 版本 ${manifest.apiVersion} 与当前 App 的 ${PluginApi.VERSION} 不一致"
    }
    require(ID_PATTERN.matches(manifest.id)) { "插件 id 不合法（小写字母、数字、_、.，2–32 位）：${manifest.id}" }
    require(manifest.versionCode > 0) { "versionCode 必须大于 0" }
    require(manifest.entryClass.isNotBlank()) { "entryClass 不能为空" }
    require(manifest.pages.isNotEmpty() || manifest.tools.isNotEmpty()) { "插件至少要有 1 个页面或 1 个工具" }
    require(manifest.pages.size <= 8) { "页面最多 8 个" }
    require(manifest.tools.size <= 16) { "工具最多 16 个" }

    manifest.pages.forEach { page ->
        require(PAGE_ID_PATTERN.matches(page.id)) { "页面 id 不合法：${page.id}" }
    }
    require(manifest.pages.map { it.id }.toSet().size == manifest.pages.size) { "页面 id 有重复" }

    manifest.tools.forEach { tool ->
        require(TOOL_NAME_PATTERN.matches(tool.name)) { "工具名不合法（字母、数字、_、-，1–20 位）：${tool.name}" }
        val function = tool.definition["function"]?.jsonObject
            ?: throw IllegalArgumentException("工具 ${tool.name} 的 definition 缺少 function")
        require(!function["name"]?.jsonPrimitive?.content.isNullOrBlank()) {
            "工具 ${tool.name} 的 definition.function.name 不能为空"
        }
    }
    require(manifest.tools.map { it.name }.toSet().size == manifest.tools.size) { "工具名有重复" }

    return manifest
}
