package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.notification.MergedNotificationPage
import com.xjtu.toolbox.notification.NoticeSource
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.notification.NotificationPage
import com.xjtu.toolbox.notification.NotificationSource
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.todayInSystemZone
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 通知的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 jsoup 爬虫）。
 *
 * 与黄页/校历/体测同一条理由：浏览器直连那 29 个站拿不到 CORS 头，Web 的唯一数据路径是
 * 同源反代到 campus-api。**campus-api 的 notification 模块覆盖同样 29 个源**
 * （`/api/notification/sources` 实测 `specs:29`，code 与 [NotificationSource] 的枚举名逐个相同），
 * 所以两端拿到的是同一批通知 —— 差别只在「谁去爬」。
 *
 * 字段对齐（campus-api 的行 → [Notification]）：
 * `title` → `title`；`url` → `link`；`source`(code) → `source`(枚举)；
 * `summary` → `description`；`tags` → `tags`；`date`(YYYY-MM-DD) → `date`。
 *
 * 两处**刻意降级**（campus-api 的能力边界，不猜）：
 * 1. **没有站内检索端点** ⇒ [search] 返回空结果集（`skipped` 为空），界面只用已加载列表里的
 *    标题匹配顶着 —— 这正是 `:app` 端「站点没有检索入口时」的同一条退路。
 * 2. **分页语义不同**：`:app` 是「逐页翻」，campus-api 是 `page`（起页）+ `pages`（取几页），
 *    所以这里把 `page` 透传成起页、每次只取 1 页，`hasMore` 直接用响应里的 `hasMore`。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusNoticeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : NoticeSource {

    override suspend fun page(source: NotificationSource, page: Int): NotificationPage {
        val data = getList(listOf(source), page)
        return NotificationPage(items = parseItems(data), hasMore = data["hasMore"].safeBoolean())
    }

    override suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage {
        if (sources.isEmpty()) return MergedNotificationPage(emptyList(), emptySet(), false)
        val data = getList(sources, page)
        // campus-api 把「这次没爬到」的源放在 degraded[] 里，reason/error 是给人看的
        val skipped = data.arr("degraded").orEmpty().mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            sourceOf(element.jsonObject["source"].safeString())
        }.toSet()
        return MergedNotificationPage(
            items = parseItems(data),
            skipped = skipped,
            hasMore = data["hasMore"].safeBoolean(),
        )
    }

    /**
     * campus-api 没有检索端点 ⇒ 照实返回「没搜到」而不是抛错。
     *
     * 不抛错的理由：ViewModel 把异常映射成「站内搜索失败，下面只是已加载通知里的匹配」，
     * 而这里根本不是失败，是**这一端没有这个能力**。返回空结果集时界面直接展示本地匹配，
     * 不会多一句误导性的提示。
     */
    override suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage =
        MergedNotificationPage(emptyList(), emptySet(), false)

    private suspend fun getList(sources: List<NotificationSource>, page: Int): JsonObject {
        val text = client.get("$baseUrl/api/notification/list") {
            parameter("sources", sources.joinToString(",") { it.name })
            parameter("page", page.coerceAtLeast(1))
            parameter("pages", 1)
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 通知返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login 也走这一支：Web 端没有 CAS 会话可重登，照实报错（与体测同口径）。
            error("campus-api 通知失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 通知返回缺少 data：${text.take(120)}")
    }

    private fun parseItems(data: JsonObject): List<Notification> =
        data.arr("items").orEmpty().mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            val item = element.jsonObject
            val source = sourceOf(item["source"].safeString()) ?: return@mapNotNull null
            val title = item["title"].safeString().trim()
            val link = item["url"].safeString().trim()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            Notification(
                title = title,
                link = link,
                source = source,
                description = item["summary"].safeString(),
                tags = item.arr("tags").orEmpty().map { it.safeString() }.filter { it.isNotBlank() },
                date = runCatching { LocalDate.parse(item["date"].safeString()) }.getOrNull() ?: todayInSystemZone(),
            )
        }

    private fun sourceOf(code: String): NotificationSource? =
        NotificationSource.entries.firstOrNull { it.name == code.trim().uppercase() }
}
