package com.xjtu.toolbox.plugin

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 插件包的清单 `dzhz.plugin.json`（包里根目录那份）。
 *
 * 字段只增不改；改语义要升 [manifestVersion]。
 * [files] 是包里全部可供宿主读取的文件，每一条都带 sha256 和大小，
 * 安装时逐条核对——没声明的条目宿主不读也不解压。
 */
@Serializable
internal data class PluginPackageManifest(
    val manifestVersion: Int = 1,
    val id: String,
    val name: String,
    /** 一句话简介，列表页最多两行。 */
    val summary: String = "",
    val version: String = "",
    val versionCode: Int,
    val minAppVersionCode: Int = 0,
    val author: PluginAuthor = PluginAuthor(),
    /** 作者头像在包内的路径（可选）；没有就用名字首字画个圆形占位。 */
    val authorAvatar: String = "",
    val projectUrl: String = "",
    /** 插件图标在包内的路径（可选）。 */
    val icon: String = "",
    /** `dex` / `web` / `declarative`。 */
    val type: String = "dex",
    /** dex 插件的插件 API 版本，必须等于 PluginApi.VERSION。 */
    val apiVersion: Int = 0,
    val dex: PluginDexPart? = null,
    val web: PluginWebPart? = null,
    val files: List<PluginFileEntry> = emptyList(),
    /** 签名公钥（ECDSA P-256，SPKI DER 的 Base64）。 */
    val pubkey: String = "",
)

@Serializable
internal data class PluginAuthor(
    val name: String = "",
    val url: String = "",
)

/** dex 插件：包里带一个 APK，宿主用 DexClassLoader 加载其中的入口类。 */
@Serializable
internal data class PluginDexPart(
    /** APK 在包内的路径，必须是 [PluginPackageManifest.files] 里声明过的条目。 */
    val apk: String,
    val entryClass: String,
    val pages: List<PluginPageManifest> = emptyList(),
    val tools: List<PluginToolManifest> = emptyList(),
)

/** web 插件：宿主用内置浏览器打开 [startUrl]，只允许 [origins] 里的站点。 */
@Serializable
internal data class PluginWebPart(
    val startUrl: String,
    /** 形如 `https://ehall.xjtu.edu.cn`，只有 scheme + host，不带路径。 */
    val origins: List<String> = emptyList(),
    val allowSubdomains: Boolean = false,
    /** 宿主能给网页的能力，目前只认 `theme`。 */
    val capabilities: List<String> = emptyList(),
)

@Serializable
internal data class PluginFileEntry(
    val path: String,
    val sha256: String,
    val size: Long,
)

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

/**
 * 已安装插件的一条记录。
 *
 * 包内容落在 `filesDir/plugins/<root>/` 里（只读），[manifest] 是安装时从包里读出的副本，
 * 加载时不再重新解析清单。[signerSha256] 是 TOFU 记下的签名者指纹：公钥 SPKI 的 sha256，
 * 更新时必须一致。
 */
@Serializable
internal data class PluginRecord(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    /** 相对 `filesDir/plugins` 的包目录，例如 `com.xjtu.toolbox.plugin.sample/4`。 */
    val root: String = "",
    /** dex 插件 APK 相对包目录的路径；web 插件为空串。 */
    val apk: String = "",
    /** 公钥 SPKI 的 sha256，十六进制小写。 */
    @SerialName("signerSha256") val signerSha256: String = "",
    val enabled: Boolean = true,
    val toolsEnabled: Boolean = false,
    val installedAt: Long = 0L,
    @SerialName("manifest") val manifest: PluginManifest,
)

/**
 * 运行时形态：由 [PluginPackageManifest] 拍平，界面和工具那几层只认这个，
 * 不去关心包里是 dex 还是 web。
 */
@Serializable
internal data class PluginManifest(
    val apiVersion: Int = 0,
    val id: String,
    val name: String,
    val summary: String = "",
    /** `dex` 或 `web`。 */
    val type: String = "dex",
    val author: String = "",
    val authorUrl: String = "",
    val authorAvatar: String = "",
    val projectUrl: String = "",
    val version: String = "",
    val versionCode: Int,
    val minAppVersionCode: Int = 0,
    val entryClass: String = "",
    val pages: List<PluginPageManifest> = emptyList(),
    val tools: List<PluginToolManifest> = emptyList(),
    /** dex 插件 APK 在包目录里的相对路径；web 插件为空。 */
    val dexApk: String = "",
    /** web 插件的起始地址；dex 插件为空。 */
    val webStartUrl: String = "",
) {
    val isWeb: Boolean get() = type == "web"

    fun pageTitle(pageId: String): String =
        pages.firstOrNull { it.id == pageId }?.title?.takeIf { it.isNotBlank() } ?: name
}

internal val ID_PATTERN = Regex("^[a-z][a-z0-9_.]{1,31}$")
internal val PAGE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
internal val TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,20}$")
internal val HEX_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

/**
 * 把包里那份清单拍成运行时形态。两套字段之间的一致性由 [validatePackageManifest] 保证。
 */
internal fun PluginPackageManifest.toRuntimeManifest(): PluginManifest = when (type) {
    "dex" -> {
        val part = requireNotNull(dex) { "type 是 dex，但没有 dex 段" }
        PluginManifest(
            apiVersion = apiVersion,
            id = id,
            name = name,
            summary = summary,
            type = "dex",
            author = author.name,
            authorUrl = author.url,
            authorAvatar = authorAvatar,
            projectUrl = projectUrl,
            version = version,
            versionCode = versionCode,
            minAppVersionCode = minAppVersionCode,
            entryClass = part.entryClass,
            pages = part.pages,
            tools = part.tools,
            dexApk = part.apk,
        )
    }
    "web" -> {
        val part = requireNotNull(web) { "type 是 web，但没有 web 段" }
        PluginManifest(
            id = id,
            name = name,
            summary = summary,
            type = "web",
            author = author.name,
            authorUrl = author.url,
            authorAvatar = authorAvatar,
            projectUrl = projectUrl,
            version = version,
            versionCode = versionCode,
            minAppVersionCode = minAppVersionCode,
            webStartUrl = part.startUrl,
        )
    }
    "declarative" -> throw IllegalArgumentException("声明式插件当前版本还不支持")
    else -> throw IllegalArgumentException("未知的插件类型：$type")
}

/** 解析清单 JSON；签名和文件校验在 [PluginPackages] 里。 */
internal fun parsePackageManifest(jsonBytes: ByteArray): PluginPackageManifest {
    val text = jsonBytes.toString(Charsets.UTF_8)
    return runCatching {
        AppJson.decodeFromString(PluginPackageManifest.serializer(), text)
    }.getOrElse { throw IllegalArgumentException("dzhz.plugin.json 解析失败：${it.message}") }
}

/** 元数据那一层的规则：不管 dex 还是 web 都要过。 */
internal fun validatePackageManifest(manifest: PluginPackageManifest) {
    require(manifest.manifestVersion == 1) {
        "清单格式版本 ${manifest.manifestVersion} 不认识（当前支持 1）"
    }
    require(ID_PATTERN.matches(manifest.id)) {
        "插件 id 不合法（小写字母、数字、_、.，2–32 位）：${manifest.id}"
    }
    require(manifest.name.length in 1..14) {
        "插件名要 1–14 个字（详情页顶栏放不下更长的）：${manifest.name}"
    }
    require(manifest.versionCode > 0) { "versionCode 必须大于 0" }
    require(manifest.pubkey.isNotBlank()) { "清单里没有公钥 pubkey" }
    require(manifest.files.isNotEmpty()) { "files 不能为空" }

    manifest.files.forEach { file ->
        require(PluginPackages.isSafePath(file.path)) { "包内路径不合法：${file.path}" }
        require(HEX_SHA256_PATTERN.matches(file.sha256)) {
            "files[].sha256 不是 64 位小写十六进制：${file.path}"
        }
        require(file.size >= 0) { "files[].size 不能是负数：${file.path}" }
    }
    require(manifest.files.map { it.path }.toSet().size == manifest.files.size) { "files 里有重复路径" }
    manifest.files.forEach { file ->
        require(!file.path.equals(PluginPackages.MANIFEST_NAME, ignoreCase = true)) {
            "files 里不该声明清单本身"
        }
        require(!file.path.equals(PluginPackages.SIGNATURE_NAME, ignoreCase = true)) {
            "files 里不该声明签名文件"
        }
    }

    when (manifest.type) {
        "dex" -> {
            val part = requireNotNull(manifest.dex) { "type 是 dex，但没有 dex 段" }
            require(part.apk.isNotBlank()) { "dex.apk 不能为空" }
            require(PluginPackages.isSafePath(part.apk)) { "dex.apk 路径不合法：${part.apk}" }
            require(manifest.files.any { it.path == part.apk }) {
                "dex.apk 没有在 files 里声明：${part.apk}"
            }
            require(part.entryClass.isNotBlank()) { "entryClass 不能为空" }
            require(part.pages.isNotEmpty() || part.tools.isNotEmpty()) {
                "插件至少要有 1 个页面或 1 个工具"
            }
            require(part.pages.size <= 8) { "页面最多 8 个" }
            require(part.tools.size <= 16) { "工具最多 16 个" }
            part.pages.forEach { page ->
                require(PAGE_ID_PATTERN.matches(page.id)) { "页面 id 不合法：${page.id}" }
            }
            require(part.pages.map { it.id }.toSet().size == part.pages.size) { "页面 id 有重复" }
            part.tools.forEach { tool ->
                require(TOOL_NAME_PATTERN.matches(tool.name)) {
                    "工具名不合法（字母、数字、_、-，1–20 位）：${tool.name}"
                }
                val function = tool.definition["function"]?.jsonObject
                    ?: throw IllegalArgumentException("工具 ${tool.name} 的 definition 缺少 function")
                require(!function["name"]?.jsonPrimitive?.content.isNullOrBlank()) {
                    "工具 ${tool.name} 的 definition.function.name 不能为空"
                }
            }
            require(part.tools.map { it.name }.toSet().size == part.tools.size) { "工具名有重复" }
        }
        "web" -> {
            val part = requireNotNull(manifest.web) { "type 是 web，但没有 web 段" }
            require(part.startUrl.startsWith("https://")) { "web.startUrl 必须是 https 地址" }
            part.origins.forEach { origin ->
                require(Regex("^https://[a-z0-9.-]+(:\\d+)?$").matches(origin)) {
                    "web.origins 只能是 https + 域名：$origin"
                }
            }
        }
        "declarative" -> Unit
        else -> throw IllegalArgumentException("未知的插件类型：${manifest.type}")
    }

    // 头像/图标如果给了路径，就必须在包里
    listOf(manifest.authorAvatar, manifest.icon).filter { it.isNotBlank() }.forEach { path ->
        require(PluginPackages.isSafePath(path)) { "包内路径不合法：$path" }
        require(manifest.files.any { it.path == path }) { "files 里没有声明 $path" }
    }
}
