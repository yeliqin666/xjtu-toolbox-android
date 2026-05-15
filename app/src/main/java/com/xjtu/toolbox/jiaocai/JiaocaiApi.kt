package com.xjtu.toolbox.jiaocai

import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeInt
import okhttp3.Request

private const val TAG = "JiaocaiApi"

// ── 数据模型 ─────────────────────────────────────────────────────────

data class JiaocaiBook(
    val id: String = "",          // general_55258665
    val appId: Int = 0,
    val engineInstanceId: Int = 0,
    val title: String = "",
    val author: String = "",
    val summary: String = "",     // 包含课程名/获取方式等描述
    val hasFullText: Boolean = false  // 是否有"本地全文"
)

// ── API 类 ───────────────────────────────────────────────────────────

class JiaocaiApi(private val login: JiaocaiLogin) {

    private val client get() = login.client
    private val BASE get() = JiaocaiLogin.BASE_URL
    private val FID get() = JiaocaiLogin.FID
    private val PAGE_ID get() = JiaocaiLogin.PAGE_ID
    private val SEARCH_ID get() = JiaocaiLogin.SEARCH_ID

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Referer", "$BASE/")
            .header("X-Requested-With", "XMLHttpRequest")
            .get().build()
        return client.newCall(req).execute().use { it.body?.string() ?: "" }
    }

    /** 搜索教材，返回书目列表 */
    fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<JiaocaiBook> {
        return try {
            val url = "$BASE/engine2/search/search-list" +
                    "?wfwfid=$FID" +
                    "&keyWord=${java.net.URLEncoder.encode(keyword, "UTF-8")}" +
                    "&pageIndex=$page" +
                    "&pageSize=$pageSize" +
                    "&pageId=$PAGE_ID" +
                    "&searchStrategy=0" +
                    "&searchId=$SEARCH_ID"
            val body = get(url)
            Log.d(TAG, "search[$keyword]: ${body.take(200)}")
            val json = body.safeParseJsonObject()
            val list = json.getAsJsonObject("data")?.getAsJsonArray("dataList") ?: return emptyList()
            list.mapNotNull { elem ->
                try {
                    val obj = elem.asJsonObject
                    val raw = obj.get("content")?.safeString() ?: ""
                    val cleanSummary = raw.replace(Regex("<[^>]+>"), "")
                    val hasFullText = raw.contains("本地全文") || raw.contains("全文获取")
                    JiaocaiBook(
                        id = obj.get("id")?.safeString() ?: return@mapNotNull null,
                        appId = obj.get("appId")?.safeInt() ?: 0,
                        engineInstanceId = obj.get("engineInstanceId")?.safeInt() ?: 0,
                        title = (obj.get("title")?.safeString() ?: "").replace(Regex("<[^>]+>"), ""),
                        author = obj.get("author")?.safeString() ?: "",
                        summary = cleanSummary,
                        hasFullText = hasFullText
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            emptyList()
        }
    }

}
