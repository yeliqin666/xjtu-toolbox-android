package com.xjtu.toolbox.inbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 收纳里的一条。待办和消息共用：待办以学校或别处的状态为准、办完就消失，消息按已读和保留期管理。
 *
 * [category] 是开关粒度：我们自己的提醒用 [InboxCategories] 里的固定 key，学校消息按来源自动生成。
 * [route] 是 [com.xjtu.toolbox.nav.AppRoute.id]，外链也用 `browser?url=` 形式存。
 */
@Serializable
data class InboxItem(
    val id: String,
    val category: String,
    val source: String,
    val title: String,
    val body: String = "",
    val time: Long = 0L,
    val route: String? = null,
    /** 到点就不再显示（作业截止、预约开始），0 表示不过期。 */
    val expiresAt: Long = 0L,
)

@Serializable
data class InboxData(
    val messages: List<InboxItem> = emptyList(),
    /** 按分类整块替换：每个来源刷新时给出自己当前的全部待办。 */
    val todos: Map<String, List<InboxItem>> = emptyMap(),
    val read: Set<String> = emptySet(),
    /** 用户关掉的分类；默认全开，所以只记关掉的。 */
    val off: Set<String> = emptySet(),
    val schoolFetchedAt: Long = 0L,
    /** 已经由屁岱冒过泡的消息，不重复念。 */
    val bubbled: Set<String> = emptySet(),
)

/** 同类消息折叠后的一组：只显示最新一条。 */
data class InboxGroup(val latest: InboxItem, val ids: List<String>, val unread: Boolean) {
    val count get() = ids.size
}

data class InboxCategory(val key: String, val label: String)

object InboxCategories {
    const val SCHOOL_TODO = "todo.school"
    const val BOOKING = "todo.booking"
    const val LMS = "todo.lms"
    const val COUPON = "todo.coupon"
    const val JUDGE = "todo.judge"
    const val GRADE = "msg.grade"
    const val SCHEDULE = "msg.schedule"
    const val ATTENDANCE = "msg.attendance"
    const val NOTICE = "msg.notice"
    private const val SCHOOL_PREFIX = "school:"

    val todos = listOf(
        InboxCategory(SCHOOL_TODO, "学校事务中心"),
        InboxCategory(BOOKING, "预约中心 / 校车预约"),
        InboxCategory(LMS, "思源学堂作业截止"),
        InboxCategory(COUPON, "加餐券"),
        InboxCategory(JUDGE, "评教未完成"),
    )
    val messages = listOf(
        InboxCategory(GRADE, "新成绩"),
        InboxCategory(SCHEDULE, "调课 / 停课"),
        InboxCategory(ATTENDANCE, "考勤异常"),
        InboxCategory(NOTICE, "教务新通知"),
    )

    fun school(label: String) = SCHOOL_PREFIX + label
    fun isSchool(key: String) = key.startsWith(SCHOOL_PREFIX)
    fun schoolLabel(key: String) = key.removePrefix(SCHOOL_PREFIX)
}

/** 纯规则，不碰存储，便于单测。 */
object InboxRules {
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** 来源、标题相同的消息只显示最新一条；过期、被关掉的分类不显示。按最新时间倒序。 */
    fun groups(data: InboxData, now: Long): List<InboxGroup> =
        data.messages
            .filter { it.category !in data.off && visible(it, now) }
            .groupBy { it.category to it.title }
            .values
            .map { same ->
                val sorted = same.sortedByDescending { it.time }
                InboxGroup(sorted.first(), sorted.map { it.id }, sorted.first().id !in data.read)
            }
            .sortedByDescending { it.latest.time }

    fun todos(data: InboxData, now: Long): List<InboxItem> =
        data.todos.filterKeys { it !in data.off }.values.flatten()
            .filter { visible(it, now) }
            .sortedWith(compareBy<InboxItem> { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }.thenByDescending { it.time })

    /** 角标：未读的消息组 + 待办。 */
    fun badge(data: InboxData, now: Long): Int = groups(data, now).count { it.unread } + todos(data, now).size

    private fun visible(item: InboxItem, now: Long) =
        (item.expiresAt == 0L || item.expiresAt > now) && (item.expiresAt > 0L || now - item.time < KEEP_MS)

    /** 合并新消息：按 id 去重，丢掉保留期外的，已读集合只留还在的 id。 */
    fun merge(data: InboxData, incoming: List<InboxItem>, now: Long): InboxData {
        val byId = LinkedHashMap<String, InboxItem>()
        (data.messages + incoming).forEach { byId[it.id] = it }
        val kept = byId.values.filter { now - it.time < KEEP_MS }
        val ids = kept.mapTo(HashSet()) { it.id }
        return data.copy(messages = kept, read = data.read.filterTo(HashSet()) { it in ids }, bubbled = data.bubbled.filterTo(HashSet()) { it in ids })
    }

    /** 学校正文末尾括号里的落款，如「(公寓用电管理系统)」，拿来当来源名。 */
    fun signature(text: String): String? =
        Regex("""[(（]([^()（）]{2,24})[)）]\s*$""").find(text.trim())?.groupValues?.get(1)?.trim()

    fun plainText(html: String): String =
        html.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trim() }.trim()
}

/**
 * 收纳的存储：按账号一份 SharedPreferences，整份 JSON 读写，内存里缓存当前那份。
 * 写入方在后台线程，界面读 [version] 订阅变化。
 */
object InboxStore {
    private lateinit var app: Context
    private var cached: Pair<String, InboxData>? = null

    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(account: String?) =
        app.getSharedPreferences("inbox${AccountContext.suffixFor(account)}", Context.MODE_PRIVATE)

    @Synchronized
    fun load(account: String? = AccountContext.activeAccountId): InboxData {
        if (!::app.isInitialized) return InboxData()
        val key = AccountContext.suffixFor(account)
        cached?.let { (k, d) -> if (k == key) return d }
        val data = prefs(account).getString("data", null)
            ?.let { runCatching { AppJson.decodeFromString<InboxData>(it) }.getOrNull() }
            ?: InboxData()
        cached = key to data
        return data
    }

    @Synchronized
    private fun update(account: String?, block: (InboxData) -> InboxData) {
        if (!::app.isInitialized) return
        val next = block(load(account))
        prefs(account).edit().putString("data", AppJson.encodeToString(next)).apply()
        cached = AccountContext.suffixFor(account) to next
        version++
    }

    /** 当前账号的快照；在 Composable 里调用会随写入重组。 */
    fun snapshot(): InboxData {
        if (version < 0) return InboxData()
        return load()
    }

    fun post(items: List<InboxItem>, account: String? = AccountContext.activeAccountId) {
        if (items.isEmpty()) return
        update(account) { InboxRules.merge(it, items, System.currentTimeMillis()) }
    }

    fun post(item: InboxItem, account: String? = AccountContext.activeAccountId) = post(listOf(item), account)

    fun setTodos(category: String, items: List<InboxItem>, account: String? = AccountContext.activeAccountId) =
        update(account) { it.copy(todos = it.todos + (category to items)) }

    fun markRead(ids: Collection<String>) = update(AccountContext.activeAccountId) { it.copy(read = it.read + ids) }

    fun markAllRead() = update(AccountContext.activeAccountId) { d -> d.copy(read = d.read + d.messages.map { it.id }) }

    fun markBubbled(id: String) = update(AccountContext.activeAccountId) { it.copy(bubbled = it.bubbled + id) }

    fun setEnabled(category: String, enabled: Boolean) =
        update(AccountContext.activeAccountId) { it.copy(off = if (enabled) it.off - category else it.off + category) }

    fun setSchoolFetchedAt(at: Long, account: String?) = update(account) { it.copy(schoolFetchedAt = at) }

    /** 学校消息里出现过的来源，给开关列表自动加行。 */
    fun schoolCategories(data: InboxData): List<String> =
        data.messages.map { it.category }.filter(InboxCategories::isSchool).distinct().sorted()
}

/** 我们自己的提醒转成收纳条目，调用方一行搞定。 */
object OwnInbox {
    fun grade(newCount: Int, total: Int) = InboxItem(
        id = "grade:$total", category = InboxCategories.GRADE, source = "成绩",
        title = "出了 $newCount 门新成绩", body = "目前共 $total 门", time = System.currentTimeMillis(), route = "jwapp_score",
    )

    fun attendance(text: String) = InboxItem(
        id = "attendance:${java.time.LocalDate.now()}:$text", category = InboxCategories.ATTENDANCE, source = "考勤",
        title = text, time = System.currentTimeMillis(), route = "new_attendance",
    )

    fun scheduleChange(text: String) = InboxItem(
        id = "schedule:${java.time.LocalDate.now()}:${text.hashCode()}", category = InboxCategories.SCHEDULE, source = "课表",
        title = "课表有变动", body = text, time = System.currentTimeMillis(), route = "schedule",
    )

    fun notice(n: com.xjtu.toolbox.notification.Notification) = InboxItem(
        id = "notice:${n.link}", category = InboxCategories.NOTICE, source = n.source.displayName,
        title = n.title, time = System.currentTimeMillis(), route = com.xjtu.toolbox.nav.AppRoute.Browser(n.link).id,
    )

    fun todo(category: String, id: String, source: String, title: String, route: String?, expiresAt: Long = 0L) =
        InboxItem(id = id, category = category, source = source, title = title, time = System.currentTimeMillis(), route = route, expiresAt = expiresAt)

    /** 思源学堂还没交、没过截止的作业。 */
    fun lmsTodos(items: List<com.xjtu.toolbox.lms.LmsDue>): List<InboxItem> = items
        .filter { !it.submitted }
        .mapNotNull { d ->
            val deadline = runCatching { Instant.parse(d.deadline).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            InboxItem(
                id = "lms:${d.courseId}:${d.activityId}", category = InboxCategories.LMS, source = d.courseName,
                title = d.title, time = d.fetchedAt, route = com.xjtu.toolbox.nav.AppRoute.Lms(d.courseId).id, expiresAt = deadline,
            )
        }
}
