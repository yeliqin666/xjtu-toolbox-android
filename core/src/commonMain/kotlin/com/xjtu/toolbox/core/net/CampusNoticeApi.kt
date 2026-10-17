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

/** 通知的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 jsoup 爬虫）。 */
class CampusNoticeApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.2 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : NoticeSource {

    override suspend fun page(source: NotificationSource, page: Int): NotificationPage {
        val data = getList(listOf(source), page)
        return NotificationPage(items = parseItems(data), hasMore = data["hasMore"].safeBoolean())
    }

    override suspend fun merged(sources: List<NotificationSource>, page: Int): MergedNotificationPage {
        if (sources.isEmpty()) return MergedNotificationPage(emptyList(), emptySet(), false)
        val data = getList(sources, page)
        // 没拉到的源：campus-api 放在 degraded[]（每项一个对象，reason/error 是给人看的）；
        // serve（§5.2）直接给一串源**代码**（与 [NotificationSource] 的枚举名同一套）
        val skipped = if (mode == ApiMode.SERVE) {
            data.arr("skipped").orEmpty().mapNotNull { sourceOf(it.safeString()) }.toSet()
        } else {
            data.arr("degraded").orEmpty().mapNotNull { element ->
                if (!element.isObject) return@mapNotNull null
                sourceOf(element.jsonObject["source"].safeString())
            }.toSet()
        }
        return MergedNotificationPage(
            items = parseItems(data),
            skipped = skipped,
            hasMore = data["hasMore"].safeBoolean(),
        )
    }

    /** campus-api 没有检索端点 ⇒ 照实返回「没搜到」而不是抛错。 */
    override suspend fun search(sources: List<NotificationSource>, keyword: String): MergedNotificationPage =
        MergedNotificationPage(emptyList(), emptySet(), false)

    private suspend fun getList(sources: List<NotificationSource>, page: Int): JsonObject {
        // serve（§5.2）：`sources` 逗号分隔 + `page`；它**没有** campus-api 那个「一次取几页」的 `pages`
        //（契约 §4 的页大小由服务端定，客户端不传）
        if (mode == ApiMode.SERVE) {
            return client.serveData(
                "加载通知",
                baseUrl,
                "/api/notification/list",
                listOf(
                    "sources" to sources.joinToString(",") { it.name },
                    "page" to page.coerceAtLeast(1).toString(),
                ),
            )
        }
        val text = client.get("$baseUrl/api/notification/list") {
            parameter("sources", sources.joinToString(",") { it.name })
            parameter("page", page.coerceAtLeast(1))
            parameter("pages", 1)
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 通知返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // need-login 也照实报错：Web 端没有 CAS 会话可重登。
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
            val link = item[if (mode == ApiMode.SERVE) "link" else "url"].safeString().trim()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            Notification(
                title = title,
                link = link,
                source = source,
                description = if (mode == ApiMode.SERVE) item["description"].safeString() else item["summary"].safeString(),
                tags = item.arr("tags").orEmpty().map { it.safeString() }.filter { it.isNotBlank() },
                date = runCatching { LocalDate.parse(item["date"].safeString()) }.getOrNull() ?: todayInSystemZone(),
            )
        }

    private fun sourceOf(code: String): NotificationSource? =
        NotificationSource.entries.firstOrNull { it.name == code.trim().uppercase() }
}
