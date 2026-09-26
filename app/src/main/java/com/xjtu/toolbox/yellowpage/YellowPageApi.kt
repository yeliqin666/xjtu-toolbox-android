package com.xjtu.toolbox.yellowpage

import kotlinx.serialization.json.decodeFromJsonElement
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import android.content.Context
import kotlinx.serialization.Serializable
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.toDialableTel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// 字段名即服务器 JSON 的键，getData() 直接解码接口原始数组
@Serializable
data class YellowPageCategory(val id: Int = 0, val name: String = "", val status: Int = 0, val sort: Int = 0)

@Serializable
data class YellowPageDepartment(
    val id: Int = 0,
    val categoryId: Int = 0,
    val name: String = "",
    val phone: String = "",
    val sort: Int = 0,
    val status: Int = 0,
) {
    val phoneItems: List<String>
        get() = phone.split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 取这一项里能拨的号码，解析规则与学籍档案共用，见 [toDialableTel]。 */
    fun dialNumber(item: String): String = item.toDialableTel()
}

@Serializable
data class YellowPageData(
    val categories: List<YellowPageCategory> = emptyList(),
    val departments: List<YellowPageDepartment> = emptyList(),
    val updateTime: String = "",
)

class YellowPageApi(context: Context) {
    private val cache = DataCache(context.applicationContext)
    private val client: OkHttpClient get() = sharedClient

    fun getData(forceRefresh: Boolean = false): YellowPageData {
        if (!forceRefresh) {
            cache.read<YellowPageData>(CACHE_KEY, CACHE_TTL_MS)?.let { return it }
        }

        return try {
            val listJson = getJson("$BASE_URL/site/schoolePage/getList")
            val data = listJson.obj("d")
                ?: throw RuntimeException("黄页接口缺少数据")
            val categories = AppJson.decodeFromJsonElement<List<YellowPageCategory>>(data.requireArr("categories"))
                .filter { it.status == 1 }
                .sortedWith(compareBy({ it.sort }, { it.id }))
            val departments = AppJson.decodeFromJsonElement<List<YellowPageDepartment>>(data.requireArr("departments"))
                .filter { it.status == 1 }
                .sortedWith(compareBy({ it.sort }, { it.id }))
            val updateTime = runCatching {
                val raw = getJson("$BASE_URL/site/schoolePage/getUpdateTime")
                    .obj("d")
                    ?.get("page_update_time")
                    ?.stringValue
                    .orEmpty()
                LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
            }.getOrDefault("")
            YellowPageData(categories, departments, updateTime).also {
                cache.write(CACHE_KEY, it)
            }
        } catch (e: Exception) {
            cache.readStale<YellowPageData>(CACHE_KEY)?.let { return it }
            throw e
        }
    }

    private fun getJson(url: String) = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Referer", "https://workflow.xjtu.edu.cn/selectpage/page/site/yellowPage")
            .get()
            .build()
    ).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) throw RuntimeException("黄页接口 HTTP ${response.code}")
        AppJson.parseToJsonElement(body).jsonObject.also {
            if (it.get("e")?.intValue != 0) {
                throw RuntimeException(it.get("m")?.stringValue ?: "黄页接口返回错误")
            }
        }
    }

    companion object {
        /** 全进程一份，见 [HttpClients]。首页刷新、Agent 工具每次都会 new 一个 YellowPageApi。 */
        private val sharedClient: OkHttpClient by lazy {
            HttpClients.base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private const val BASE_URL = "https://workflow.xjtu.edu.cn/selectpage"
        private const val CACHE_KEY = "yellow_page"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
