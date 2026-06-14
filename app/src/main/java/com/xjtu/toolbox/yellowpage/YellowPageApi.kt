package com.xjtu.toolbox.yellowpage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xjtu.toolbox.util.DataCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class YellowPageCategory(
    val id: Int,
    val name: String,
    val status: Int,
    val sort: Int
)

data class YellowPageDepartment(
    val id: Int,
    val categoryId: Int,
    val name: String,
    val phone: String,
    val sort: Int,
    val status: Int
) {
    val phoneItems: List<String>
        get() = phone.split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun dialNumber(item: String): String =
        Regex("""\d{7,}""").find(item)?.value.orEmpty()
}

data class YellowPageData(
    val categories: List<YellowPageCategory>,
    val departments: List<YellowPageDepartment>,
    val updateTime: String = ""
)

class YellowPageApi(context: Context) {
    private val gson = Gson()
    private val cache = DataCache(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getData(forceRefresh: Boolean = false): YellowPageData {
        if (!forceRefresh) {
            cache.get(CACHE_KEY, CACHE_TTL_MS)?.let { cached ->
                runCatching { gson.fromJson(cached, YellowPageData::class.java) }.getOrNull()
                    ?.let { return it }
            }
        }

        return try {
            val listJson = getJson("$BASE_URL/site/schoolePage/getList")
            val data = listJson.getAsJsonObject("d")
                ?: throw RuntimeException("黄页接口缺少数据")
            val categories = gson.fromJson(
                data.getAsJsonArray("categories"),
                Array<YellowPageCategory>::class.java
            ).orEmpty()
                .filter { it.status == 1 }
                .sortedWith(compareBy({ it.sort }, { it.id }))
            val departments = gson.fromJson(
                data.getAsJsonArray("departments"),
                Array<YellowPageDepartment>::class.java
            ).orEmpty()
                .filter { it.status == 1 }
                .sortedWith(compareBy({ it.sort }, { it.id }))
            val updateTime = runCatching {
                val raw = getJson("$BASE_URL/site/schoolePage/getUpdateTime")
                    .getAsJsonObject("d")
                    ?.get("page_update_time")
                    ?.asString
                    .orEmpty()
                LocalDateTime.parse(raw).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
            }.getOrDefault("")
            YellowPageData(categories, departments, updateTime).also {
                cache.put(CACHE_KEY, gson.toJson(it))
            }
        } catch (e: Exception) {
            cache.getStale(CACHE_KEY)?.let { stale ->
                runCatching { gson.fromJson(stale, YellowPageData::class.java) }.getOrNull()
                    ?.let { return it }
            }
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
        val body = response.body?.string() ?: throw RuntimeException("黄页接口无响应")
        if (!response.isSuccessful) throw RuntimeException("黄页接口 HTTP ${response.code}")
        JsonParser.parseString(body).asJsonObject.also {
            if (it.get("e")?.asInt != 0) {
                throw RuntimeException(it.get("m")?.asString ?: "黄页接口返回错误")
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://workflow.xjtu.edu.cn/selectpage"
        private const val CACHE_KEY = "yellow_page"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
