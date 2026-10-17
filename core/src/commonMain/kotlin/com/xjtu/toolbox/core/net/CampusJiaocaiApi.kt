package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.jiaocai.JiaocaiBook
import com.xjtu.toolbox.jiaocai.JiaocaiSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 教材中心（`jiaocai.lib.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [JiaocaiSource]。 */
class CampusJiaocaiApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.6）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : JiaocaiSource {

    override suspend fun search(keyword: String): List<JiaocaiBook> {
        requireServe()
        val data = client.serveData("检索教材", baseUrl, SEARCH_PATH, listOf(KEYWORD_PARAM to keyword))
        return data.arr("books").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row["id"].safeString().trim()
            if (id.isEmpty()) return@mapNotNull null
            JiaocaiBook(
                id = id,
                appId = row["appId"].safeInt(),
                engineInstanceId = row["engineInstanceId"].safeInt(),
                title = row["title"].safeString(),
                author = row["author"].safeString(),
                summary = row["summary"].safeString(),
                // 列表里恒 null（见类 KDoc）：空串也当没有 —— `ssno` 是书目号，空串没有意义
                ssno = row["ssno"].safeStringOrNull()?.takeIf { it.isNotBlank() },
                // 上游原文的键值对：键名不翻译、不重排（服务端那份 LinkedHashMap 的顺序原样保在 JSON 里）
                fields = row.stringMap("fields"),
            )
        }
    }

    override suspend fun fetchSsno(book: JiaocaiBook): String? {
        requireServe()
        val data = client.serveData(
            "取教材全文号",
            baseUrl,
            SSNO_PATH,
            listOf(
                ID_PARAM to book.id,
                APP_ID_PARAM to book.appId.toString(),
                ENGINE_INSTANCE_ID_PARAM to book.engineInstanceId.toString(),
            ),
        )
        // `null`、空串或缺字段都当「这本书没有开放本地全文」。
        return data["ssno"].safeStringOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("教材检索取数", "$SEARCH_PATH + $SSNO_PATH")
    }
}

/** `fields` 是上游原文的键值对（字符串表）：值不是标量的那一格不列，不把它 toString 成一个假值。 */
private fun JsonObject.stringMap(key: String): Map<String, String> =
    (this[key] as? JsonObject)?.entries.orEmpty()
        .mapNotNull { (fieldKey, value) -> (value as? JsonPrimitive)?.let { fieldKey to it.content } }
        .toMap()

/** 按关键词检索教材（契约 §5.6 的两个 jiaocai 端点之一）。 */
private const val SEARCH_PATH = "/api/jiaocai/search"

/** 一本书的全文库书目号。 */
private const val SSNO_PATH = "/api/jiaocai/ssno"

private const val KEYWORD_PARAM = "keyword"
private const val ID_PARAM = "id"
private const val APP_ID_PARAM = "appId"
private const val ENGINE_INSTANCE_ID_PARAM = "engineInstanceId"
