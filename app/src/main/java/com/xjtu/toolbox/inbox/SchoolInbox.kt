package com.xjtu.toolbox.inbox

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 学校的消息、事务中心待办、预约中心和校车预约。四个服务都只认一网通办的 `x-id-token`，
 * 所以全挂在一网通办站点上发请求，token 失效时由它自动重登。
 */
object SchoolInbox {
    private const val TAG = "SchoolInbox"
    const val TTL_MS = 30L * 60 * 1000

    private const val MESSAGES = "https://message-service.xjtu.edu.cn/center/api/v1/instantMessage/getAppMessageList/new?pageIndex=0&pageSize=30"
    private const val TODOS = "https://transaction.xjtu.edu.cn/ttc/api/ttc/center-list/getToDoList?pageIndex=1&pageSize=30"
    private const val BOOKINGS = "https://reservation.xjtu.edu.cn/api/myreservation/my?state=1&useState=0&current=1&size=20"
    private const val BUS = "https://xjbus.xjtu.edu.cn/api/school/bus/user/pageReservationUsers?status=1&current=1&size=20"

    /** 事务中心推来的消息和它的待办是同一件事，只收待办。 */
    private const val TRANSACTION_APP_ID = "b125b6f0e46911ebc909e55a42ec966e"

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val ZONE = ZoneId.of("Asia/Shanghai")

    fun isDue(account: String?, now: Long = System.currentTimeMillis()) =
        now - InboxStore.load(account).schoolFetchedAt >= TTL_MS

    /** 拉一轮；各接口互不影响，失败的保留上次结果。 */
    suspend fun refresh(site: SiteSession, account: String?) = withContext(Dispatchers.IO) {
        runCatching { InboxStore.post(parseMessages(get(site, MESSAGES)), account) }
            .onFailure { Log.w(TAG, "messages: ${it.message}") }
            .onSuccess { InboxStore.setSchoolFetchedAt(System.currentTimeMillis(), account) }
        runCatching { InboxStore.setTodos(InboxCategories.SCHOOL_TODO, parseTodos(get(site, TODOS)), account) }
            .onFailure { Log.w(TAG, "todos: ${it.message}") }
        val bookings = listOf(BOOKINGS to "预约中心", BUS to "校车").map { (url, name) ->
            runCatching { bookingTodo(get(site, url), name) }.onFailure { Log.w(TAG, "$name: ${it.message}") }
        }
        if (bookings.any { it.isSuccess }) {
            InboxStore.setTodos(InboxCategories.BOOKING, bookings.mapNotNull { it.getOrNull() }, account)
        }
    }

    private suspend fun get(site: SiteSession, url: String): JsonObject =
        site.executeWithReAuth(Request.Builder().url(url).get().build()).use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body.string().safeParseJsonObject()
        }

    private fun epoch(text: String): Long =
        runCatching { LocalDateTime.parse(text.take(19), TIME).atZone(ZONE).toInstant().toEpochMilli() }.getOrDefault(0L)

    internal fun parseMessages(root: JsonObject): List<InboxItem> =
        (root.safeGet("data") as? JsonArray ?: root.obj("data")?.arr("list") ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .filter { it.safeGet("appId").safeString() != TRANSACTION_APP_ID }
            .mapNotNull { m ->
                val id = m.safeGet("id").safeStringOrNull() ?: return@mapNotNull null
                val body = InboxRules.plainText(m.safeGet("content").safeString())
                val source = InboxRules.signature(body) ?: m.safeGet("appName").safeStringOrNull()?.takeIf { it.isNotBlank() } ?: "学校通知"
                if (InboxRules.isShortLived(source)) return@mapNotNull null
                val link = m.safeGet("mobileUrl").safeString().ifBlank { m.safeGet("url").safeString() }
                InboxItem(
                    id = "school:$id",
                    category = InboxCategories.school(source),
                    source = source,
                    title = m.safeGet("title").safeString().ifBlank { source },
                    body = body,
                    time = epoch(m.safeGet("editTime").safeString()),
                    route = link.takeIf { it.startsWith("http") }?.let { AppRoute.Browser(it).id },
                )
            }

    internal fun parseTodos(root: JsonObject): List<InboxItem> {
        val data = root.safeGet("data")
        val items = data as? JsonArray ?: (data as? JsonObject)?.arr("items") ?: JsonArray(emptyList())
        return items.mapNotNull { it as? JsonObject }.mapNotNull { t ->
            val title = t.safeGet("title").safeString().ifBlank { t.safeGet("transactionName").safeString() }
            if (title.isBlank()) return@mapNotNull null
            val link = listOf("mHandleUrl", "handleUrl", "mViewUrl", "viewUrl")
                .firstNotNullOfOrNull { k -> t.safeGet(k).safeStringOrNull()?.takeIf { it.startsWith("http") } }
            val node = t.safeGet("nodeName").safeString()
            InboxItem(
                id = "todo:" + (t.safeGet("taskId").safeStringOrNull() ?: "$title@${t.safeGet("addTime").safeString()}"),
                category = InboxCategories.SCHOOL_TODO,
                source = t.safeGet("appName").safeString().ifBlank { t.safeGet("custom1").safeString() }.ifBlank { "事务中心" },
                title = title,
                body = listOfNotNull(node.takeIf { it.isNotBlank() }, "已超时".takeIf { t.safeGet("timeOut").safeString() == "true" }).joinToString(" · "),
                time = epoch(t.safeGet("addTime").safeString()),
                route = link?.let { AppRoute.Browser(it).id },
            )
        }
    }

    /** 预约记录的字段没有样本，只报数量。 */
    private fun bookingTodo(root: JsonObject, name: String): InboxItem? {
        val total = root.obj("data")?.safeGet("total").safeInt()
        if (total <= 0) return null
        return OwnInbox.todo(InboxCategories.BOOKING, "booking:$name", name, "$name 有 $total 个待使用的预约", route = null)
    }
}
