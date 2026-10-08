package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.inbox.InboxSource
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.beijingEpochMs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * 消息收纳的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `SchoolInbox`）。
 *
 * 四路都在 `/api/inbox` 里（campus-api 手册 §M21 统一消息/待办：四路共用同一个 `idToken`）：
 * 消息 / 事务中心待办 / 预约中心 / 校车。与 `:app` 的两处**能力差异**（不猜、不伪造）：
 * 1. **预约与校车只给计数、不给条目**（campus-api 的 `reservations`/`bus` 只有 `total`）
 *    ⇒ 与 `:app` 的 `SchoolInbox.bookingTodo` **同一句话**（「X 有 N 个待使用的预约」）。
 *    这一条恰好两端一致，不是降级。
 * 2. **图书馆座位那一路没有**（`:app` 的 `afterRefresh` 现查图书馆）⇒ 保持 [InboxSource] 的默认空实现。
 *
 * ⚠️ campus-api 把若干字段**字符串化**了（实测 `'url': 'None'`、`'read': 'False'`、`'timeOut': '0'`）
 * ⇒ 这里按「字符串里的 None/空串都当没有」处理，不直接信它的类型。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusInboxApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : InboxSource {

    /** 与 `:app` 的 `SchoolInbox.TTL_MS` 同一个值（30 分钟）。 */
    private val ttlMs: Long = 30L * 60 * 1000

    override suspend fun refresh(account: String?) {
        val sources = getData("/api/inbox")["sources"] as? JsonObject ?: return
        val now = Clock.System.now().toEpochMilliseconds()

        // 消息：各接口互不影响（与 :app 的 runCatching 逐条对应）
        runCatching { InboxStore.post(parseMessages(sources), account) }
        runCatching { InboxStore.setTodos(InboxCategories.SCHOOL_TODO, parseTodos(sources), account) }
        runCatching {
            val bookings = listOf("reservations" to "预约中心", "bus" to "校车")
                .mapNotNull { (key, name) -> bookingTodo(sources, key, name, now) }
            InboxStore.setTodos(InboxCategories.BOOKING, bookings, account)
        }
        InboxStore.setSchoolFetchedAt(now, account)
    }

    override fun isDue(account: String?, now: Long): Boolean =
        now - InboxStore.load(account).schoolFetchedAt >= ttlMs

    private fun parseMessages(sources: JsonObject): List<InboxItem> =
        sourceItems(sources, "messages").mapNotNull { m ->
            if (clean(m["appId"]) == TRANSACTION_APP_ID) return@mapNotNull null
            val id = clean(m["id"]) ?: return@mapNotNull null
            val body = InboxRules.plainText(raw(m["content"]))
            val source = InboxRules.signature(body) ?: clean(m["appName"]) ?: "学校通知"
            if (InboxRules.isShortLived(source)) return@mapNotNull null
            val link = clean(m["mobileUrl"]) ?: clean(m["url"])
            InboxItem(
                id = "school:$id",
                category = InboxCategories.school(source),
                source = source,
                title = clean(m["title"]) ?: source,
                body = body,
                time = beijingEpochMs(clean(m["time"])),
                route = link?.takeIf { it.startsWith("http") }?.let { AppRoute.Browser(it).id },
            )
        }

    private fun parseTodos(sources: JsonObject): List<InboxItem> =
        sourceItems(sources, "todos").mapNotNull { t ->
            val title = clean(t["title"]) ?: clean(t["transactionName"]) ?: return@mapNotNull null
            val link = clean(t["url"])
            val node = clean(t["nodeName"]).orEmpty()
            val timeOut = clean(t["timeOut"])
            InboxItem(
                id = "todo:" + (clean(t["taskId"]) ?: "$title@${clean(t["addTime"]).orEmpty()}"),
                category = InboxCategories.SCHOOL_TODO,
                source = clean(t["appName"]) ?: "事务中心",
                title = title,
                body = listOfNotNull(
                    node.takeIf { it.isNotBlank() },
                    "已超时".takeIf { timeOut == "true" || timeOut == "True" || timeOut == "1" },
                ).joinToString(" · "),
                time = beijingEpochMs(clean(t["addTime"])),
                route = link?.takeIf { it.startsWith("http") }?.let { AppRoute.Browser(it).id },
            )
        }

    /** 与 `:app` 的 `SchoolInbox.bookingTodo` 同一句话（它也只报数量）。 */
    private fun bookingTodo(sources: JsonObject, key: String, name: String, now: Long): InboxItem? {
        val total = ((sources[key] as? JsonObject)?.get("total")).safeInt()
        if (total <= 0) return null
        return InboxItem(
            id = "booking:$name",
            category = InboxCategories.BOOKING,
            source = name,
            title = "$name 有 $total 个待使用的预约",
            time = now,
            route = null,
        )
    }

    private fun sourceItems(sources: JsonObject, key: String): List<JsonObject> =
        (sources[key] as? JsonObject)?.arr("items").orEmpty()
            .mapNotNull { if (it.isObject) it.jsonObject else null }

    /** campus-api 会把 null 写成字符串 `"None"` ⇒ 这里统一当「没有」。 */
    private fun clean(value: kotlinx.serialization.json.JsonElement?): String? =
        value?.safeString()?.trim()?.takeIf { it.isNotEmpty() && it != "None" && it != "null" }

    private fun raw(value: kotlinx.serialization.json.JsonElement?): String = value?.safeString().orEmpty()


    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 收纳返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            error("campus-api 收纳失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 收纳返回缺少 data：${text.take(120)}")
    }

    private companion object {
        /** 事务中心推来的消息和它的待办是同一件事，只收待办（与 :app 的常量一致）。 */
        const val TRANSACTION_APP_ID = "b125b6f0e46911ebc909e55a42ec966e"
    }
}
