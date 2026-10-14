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

/**
 * 教材中心（`jiaocai.lib.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [JiaocaiSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/jiaocai/search?keyword=` | [search] |
 * | `GET /api/jiaocai/ssno?id=&appId=&engineInstanceId=` | [fetchSsno]（进详情时才打这一枪） |
 *
 * 契约 §5.6；`:server` 那一侧是 `JiaocaiRoutes.kt`（薄包装 `:data` 的 `AppJiaocaiSource`）。
 *
 * ## 九格全在（`hasFullText` 是算出来的，不是端出来的）
 *
 * 服务端把 `JiaocaiBook` 的九格都端出来，但 `:core` 的模型里只有八格是**存下来的**：
 * `hasFullText` 是 `JiaocaiBook` 自己按 `ssno != null || fields 里含「本地全文」` 现算的属性
 *（不是构造参数）。服务端那个字段用的**逐字是同一条判据**（`:data` 的 `toDto`）⇒ 本类不把它
 * 塞进模型（塞不进去，也没有第二个地方放），两边算出来的是同一个值。
 *
 * ## `ssno` 在列表里恒 `null`，要它得再打一枪
 *
 * `search-list` 的 `content` 被服务端剥掉了链接 ⇒ 检索结果里的 `ssno` 恒 `null`
 *（`hasFullText` 仍可能是 true：描述里那句「获取方式一：本地全文」还在）。要全文库书目号，
 * 就按 `id` + `appId` + `engineInstanceId` 三格再打一次 [fetchSsno]（`:core` 的详情卡就是这么做的）。
 *
 * ## 如实缺口（不是 bug）
 *
 * - **「本学期我的教材」那一栏没有端点**：那一段的数据在**教务**的报表里
 *   （`:data` 的 `JwxtScheduleApi.textbooks(studentId, term)`，**要学号** —— §5.2 红线），
 *   教材中心这一侧没有它。`JiaocaiSource` 上没有这个方法，本类也就没有这一格：
 *   `:web` 那边由宿主端口 `JiaocaiTermTextbooks` 如实回空表（那一段整段不显示），见 `:web` 的装配。
 * - **全文阅读不端**：`jiaocai1` 的页图与阅读器是另一个域名、另一套会话（本轮不碰）——
 *   `ssno` 拿到只够显示「这本书有本地全文」。
 * - **没有翻页**：这一屏没有 `page` / `pageSize`（`:data` 固定 20 条一页、只取第一页）。
 * - ⚠️ 没有会话时上游返的是一张「JS 跳转登录链」页，`:data` 跟不通就**回空表**（检索那一发的语言）
 *   ⇒「查不到书」与「会话断了」在检索这一发上长得一样。那是 `:data` 既有的口径，本层不改、也不猜。
 *
 * ## 默认形态（campus-api）
 *
 * 教材中心在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
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
            // 没有 id 的书进不了详情页（那一枪要 id + appId + engineInstanceId 三格）⇒ 不列，
            // 免得摆一张点了打不通的卡（与 `:data` 丢掉缺 roomId 那一类行同一条口径）
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
        // `null`（或空串、或这一格没端出来）= 这本书没有开放本地全文（§4：`null` 是「上游明确说没有」）。
        // 这里**不区分**「缺字段」与「null」：这一格的语义两头都是「拿不到书目号」，
        // 而 `JiaocaiSource.fetchSsno` 的约定本来就是「取不到返回 null」（见该端口的 KDoc）。
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
