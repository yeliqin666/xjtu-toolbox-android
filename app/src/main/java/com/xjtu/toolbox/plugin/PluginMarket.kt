package com.xjtu.toolbox.plugin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 市场目录里的一条，格式见 docs/plugins.md「在线市场」。 */
internal data class MarketEntry(
    val id: String,
    val name: String,
    val summary: String,
    val isWeb: Boolean,
    val version: String,
    val author: String,
    val versionCode: Int = 0,
    val minAppVersionCode: Int = 0,
    val authorUrl: String = "",
    val projectUrl: String = "",
    /** 插件包下载地址；[mirrors] 是同一个包的备用地址，按顺序试。 */
    val download: String = "",
    val mirrors: List<String> = emptyList(),
    /** 插件包整包的 sha256（小写十六进制）。 */
    val sha256: String = "",
    val size: Long = 0,
    /** 进顶部横滑精选。 */
    val featured: Boolean = false,
    val tags: List<String> = emptyList(),
    val iconName: String = "",
)

internal data class MarketIndex(
    val schema: Int,
    val updatedAt: String,
    val tags: List<String>,
    val entries: List<MarketEntry>,
)

/** 目录解析：认不出的条目跳过，整份不是目录（比如站点回了 HTML）就抛错。 */
internal object MarketIndexParser {
    const val SCHEMA = 1
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): MarketIndex {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("目录不是 JSON，地址可能填错了")
        val schema = root.int("schema") ?: throw IOException("目录缺少 schema 字段")
        if (schema > SCHEMA) throw IOException("目录格式比 App 新（schema $schema），先更新 App")
        val plugins = root["plugins"] as? JsonArray ?: throw IOException("目录缺少 plugins 数组")
        val entries = plugins.mapNotNull { (it as? JsonObject)?.let(::entry) }.distinctBy { it.id }
        return MarketIndex(
            schema = schema,
            updatedAt = root.str("updatedAt"),
            tags = root.strings("tags").ifEmpty { entries.flatMap { it.tags }.distinct() },
            entries = entries,
        )
    }

    private fun entry(o: JsonObject): MarketEntry? {
        val id = o.str("id")
        val name = o.str("name")
        val type = o.str("type")
        val download = o.str("download")
        if (id.isBlank() || name.isBlank() || type !in setOf("dex", "web") || !download.startsWith("https://")) return null
        return MarketEntry(
            id = id,
            name = name,
            summary = o.str("summary"),
            isWeb = type == "web",
            version = o.str("version"),
            author = o.str("author"),
            versionCode = o.int("versionCode") ?: 0,
            minAppVersionCode = o.int("minAppVersionCode") ?: 0,
            authorUrl = o.str("authorUrl"),
            projectUrl = o.str("projectUrl"),
            download = download,
            mirrors = o.strings("mirrors").filter { it.startsWith("https://") },
            sha256 = o.str("sha256").removePrefix("sha256:").lowercase(),
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            featured = (o["featured"] as? JsonPrimitive)?.booleanOrNull ?: false,
            tags = o.strings("tags"),
            iconName = o.str("iconName"),
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
            ?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * 在线市场：拉目录、下载插件包。
 *
 * 目录先进内存，再落一份到 cacheDir：非 Wi-Fi 且开了「只在 Wi-Fi 下加载」时用上一份，
 * 断网也能先看到列表。插件包下到 `plugin_import_` 前缀的临时文件，和「清空插件缓存」一致。
 */
internal object PluginMarket {
    private const val CACHE_FILE = "plugin_market_index.json"

    @Volatile
    private var memory: Pair<String, MarketIndex>? = null

    private val client by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 拉到的目录，以及它是不是旧的（没联网、用的缓存）。 */
    class Loaded(val index: MarketIndex, val stale: Boolean)

    /** 内存里已有的那份，进页面时先拿它垫上，不闪空态。 */
    fun cached(url: String): MarketIndex? = memory?.takeIf { it.first == url }?.second

    suspend fun load(context: Context, force: Boolean): Loaded = withContext(Dispatchers.IO) {
        val url = PluginSettings.indexUrl(context)
        if (!force) cached(url)?.let { return@withContext Loaded(it, stale = false) }
        val disk = File(context.cacheDir, CACHE_FILE)
        val diskIndex = runCatching {
            disk.readText().takeIf { it.startsWith("$url\n") }?.substringAfter('\n')?.let(MarketIndexParser::parse)
        }.getOrNull()
        if (PluginSettings.wifiOnly(context) && !onUnmeteredNetwork(context)) {
            diskIndex?.let { return@withContext Loaded(it, stale = true) }
            throw IOException("当前不是 Wi-Fi。要用流量加载，去插件设置里关掉「只在 Wi-Fi 下加载索引」")
        }
        val text = try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("目录加载失败（HTTP ${r.code}）")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            diskIndex?.let { return@withContext Loaded(it, stale = true) }
            throw e
        }
        val index = MarketIndexParser.parse(text)
        memory = url to index
        runCatching { disk.writeText("$url\n$text") }
        Loaded(index, stale = false)
    }

    /** 下载插件包并核对整包 sha256；主地址失败依次试镜像。返回临时文件，交给 [PluginManager.inspectFile]。 */
    suspend fun download(context: Context, entry: MarketEntry): File = withContext(Dispatchers.IO) {
        require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "目录里这个插件没给 sha256，不能安装" }
        var last: Throwable? = null
        for (url in listOf(entry.download) + entry.mirrors) {
            val temp = File(context.cacheDir, "plugin_import_market_${System.nanoTime()}.zip")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("下载失败（HTTP ${r.code}）")
                    val body = r.body ?: throw IOException("下载内容为空")
                    var total = 0L
                    body.byteStream().use { input ->
                        temp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_PACKAGE_BYTES + 8L * 1024 * 1024) throw IOException("插件包大得离谱，已停止下载")
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != entry.sha256) throw IOException("插件包校验不符，可能被篡改或目录过期")
                return@withContext temp
            } catch (t: Throwable) {
                temp.delete()
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        throw last ?: IOException("下载失败")
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
