package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.synchronizedBlock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import com.xjtu.toolbox.util.todayInSystemZone

/**
 * 收纳里的一条。待办和消息共用：待办以学校或别处的状态为准，办完挪进「已完成」；消息按已读和保留期管理。
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

/** 办完（或被用户忽略）的待办，灰着留 [InboxRules.FINISHED_KEEP_MS]。 */
@Serializable
data class FinishedTodo(val item: InboxItem, val at: Long, val ignored: Boolean = false)

@Serializable
data class InboxData(
    val messages: List<InboxItem> = emptyList(),
    /** 按分类整块替换：每个来源刷新时给出自己当前的全部待办。 */
    val todos: Map<String, List<InboxItem>> = emptyMap(),
    /** 旧版的已读集合，只用来读老数据，加载时换成 [readAt]。 */
    val read: Set<String> = emptySet(),
    /** 消息 id → 读的时刻：读过的变灰，[InboxRules.FINISHED_KEEP_MS] 后不再显示。 */
    val readAt: Map<String, Long> = emptyMap(),
    /** 看过的待办：首页红点只算没看过的。 */
    val seenTodos: Set<String> = emptySet(),
    /** 用户忽略的待办：来源还报着也不再进列表。 */
    val ignored: Set<String> = emptySet(),
    val finished: List<FinishedTodo> = emptyList(),
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
    const val LIBRARY = "todo.library"
    const val GRADE = "msg.grade"
    const val SCHEDULE = "msg.schedule"
    const val ATTENDANCE = "msg.attendance"
    const val NOTICE = "msg.notice"
    const val BULLETIN = "msg.bulletin"
    private const val SCHOOL_PREFIX = "school:"

    val todos = listOf(
        InboxCategory(SCHOOL_TODO, "学校事务中心"),
        InboxCategory(BOOKING, "预约中心 / 校车预约"),
        InboxCategory(LIBRARY, "图书馆座位签到 / 返回"),
        InboxCategory(LMS, "思源学堂作业截止"),
        InboxCategory(COUPON, "加餐券"),
        InboxCategory(JUDGE, "评教未完成"),
    )
    val messages = listOf(
        InboxCategory(GRADE, "新成绩"),
        InboxCategory(SCHEDULE, "调课 / 停课"),
        InboxCategory(ATTENDANCE, "考勤异常"),
        InboxCategory(NOTICE, "教务新通知"),
        InboxCategory(BULLETIN, "工具箱公告"),
    )

    fun school(label: String) = SCHOOL_PREFIX + label
    fun isSchool(key: String) = key.startsWith(SCHOOL_PREFIX)
    fun schoolLabel(key: String) = key.removePrefix(SCHOOL_PREFIX)

    /** 每类一个颜色（ARGB），收纳页图标和桌面小组件的色条共用。 */
    fun argb(category: String): Int = when (category) {
        SCHOOL_TODO -> 0xFF3B82F6
        BOOKING -> 0xFF14B8A6
        LIBRARY -> 0xFF0D9488
        LMS -> 0xFF8B5CF6
        COUPON -> 0xFFF97316
        JUDGE -> 0xFFEC4899
        GRADE -> 0xFFEAB308
        SCHEDULE -> 0xFF6366F1
        ATTENDANCE -> 0xFFEF4444
        NOTICE -> 0xFF10B981
        BULLETIN -> 0xFF0EA5E9
        else -> 0xFF64748B
    }.toInt()
}

/** 纯规则，不碰存储，便于单测。 */
object InboxRules {
    /** 没读的消息留多久。 */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    /** 读过的消息、办完的待办灰着留多久。 */
    const val FINISHED_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** 来源、标题相同的消息只显示最新一条；过期、被关掉的分类、读过超过 7 天的不显示。按最新时间倒序。 */
    fun groups(data: InboxData, now: Long): List<InboxGroup> =
        data.messages
            .filter { m -> m.category !in data.off && visible(m, now) && data.readAt[m.id].let { it == null || now - it < FINISHED_KEEP_MS } }
            .groupBy { it.category to it.title }
            .values
            .map { same ->
                val sorted = same.sortedByDescending { it.time }
                InboxGroup(sorted.first(), sorted.map { it.id }, sorted.first().id !in data.readAt)
            }
            .sortedByDescending { it.latest.time }

    /** 还没办的待办（忽略的除外），快截止的排前面。 */
    fun todos(data: InboxData, now: Long): List<InboxItem> =
        data.todos.filterKeys { it !in data.off }.values.flatten()
            .filter { visible(it, now) && it.id !in data.ignored }
            .sortedWith(compareBy<InboxItem> { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }.thenByDescending { it.time })

    /** 最近办完或忽略的待办，新的在前。 */
    fun finished(data: InboxData, now: Long): List<FinishedTodo> =
        data.finished.filter { it.item.category !in data.off && now - it.at < FINISHED_KEEP_MS }.sortedByDescending { it.at }

    /** 角标：未读的消息组 + 没看过的待办。看过还没办的不再催，列表里照常留着。 */
    fun badge(data: InboxData, now: Long): Int =
        groups(data, now).count { it.unread } + todos(data, now).count { it.id !in data.seenTodos }

    /**
     * 某个来源的待办整块换成 [items]。上次还在、这次没了、又没到截止的，就是办完了，挪进「已完成」；
     * 到点过期的不算办完，直接消失。办完的又冒出来（被退回之类）就从「已完成」拿掉。
     * 看过、忽略的记录只留还在的 id。调用方只在拉取成功时调用，拉取失败不会把待办误判成办完。
     */
    fun replaceTodos(data: InboxData, category: String, items: List<InboxItem>, now: Long): InboxData {
        val newIds = items.mapTo(HashSet()) { it.id }
        val vanished = data.todos[category].orEmpty().filter {
            it.id !in newIds && it.id !in data.ignored && (it.expiresAt == 0L || it.expiresAt > now)
        }
        val todos = data.todos + (category to items)
        val activeIds = todos.values.flatten().mapTo(HashSet()) { it.id }
        val finished = (data.finished.filter { it.ignored || it.item.id !in newIds } + vanished.map { FinishedTodo(it, now) })
            .filter { now - it.at < FINISHED_KEEP_MS }
        return data.copy(
            todos = todos,
            finished = finished,
            seenTodos = data.seenTodos.filterTo(HashSet()) { it in activeIds },
            ignored = data.ignored.filterTo(HashSet()) { it in activeIds },
        )
    }

    /** 用户忽略一条待办：挪进「已完成」标成已忽略，来源还报着也不再进列表。 */
    fun ignore(data: InboxData, id: String, now: Long): InboxData {
        val item = data.todos.values.flatten().firstOrNull { it.id == id } ?: return data
        return data.copy(
            ignored = data.ignored + id,
            finished = data.finished.filter { it.item.id != id } + FinishedTodo(item, now, ignored = true),
        )
    }

    /** 老数据的已读集合换成带时刻的，读的时刻不知道就算现在。 */
    fun migrate(data: InboxData, now: Long): InboxData =
        if (data.read.isEmpty()) data
        else data.copy(readAt = data.read.associateWith { now } + data.readAt, read = emptySet())

    private fun visible(item: InboxItem, now: Long) =
        (item.expiresAt == 0L || item.expiresAt > now) && (item.expiresAt > 0L || now - item.time < KEEP_MS)

    /** 图书馆座位的签到、超时释放：几分钟就过期，座位状态在图书馆页随时能查，不收。借阅到期之类照收。 */
    fun isShortLived(source: String) = source == "图书馆预约系统" || "座位" in source

    /** 合并新消息：按 id 去重（重复推来的保留首次时间），丢掉保留期外的和不收的来源，已读集合只留还在的 id。 */
    fun merge(data: InboxData, incoming: List<InboxItem>, now: Long): InboxData {
        val byId = LinkedHashMap<String, InboxItem>()
        data.messages.forEach { byId[it.id] = it }
        incoming.forEach { n -> byId[n.id] = byId[n.id]?.let { n.copy(time = it.time) } ?: n }
        val kept = byId.values.filter { now - it.time < KEEP_MS && !(InboxCategories.isSchool(it.category) && isShortLived(it.source)) }
        val ids = kept.mapTo(HashSet()) { it.id }
        return data.copy(messages = kept, readAt = data.readAt.filterKeys { it in ids }, bubbled = data.bubbled.filterTo(HashSet()) { it in ids })
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
 * 「待办数据变了，请重画桌面小组件」这一枪的宿主槽位。
 *
 * 上游这份直接写在 `:app` 的 `InboxStore.update()` 里 —— 那里手上就有 `Context`，一句话够了。
 * `InboxStore` 搬进 `:core` 的 commonMain 之后没有 `Context` 可给（`:core` 不认识这个类型），
 * 所以换成注入：`:app` 在 `XjtuApp.onCreate` 里把 `TodoWidgetUpdater.requestUpdate` 挂上来；
 * Web / jvm 没有桌面小组件，不挂就是空转（与 `platform/Toast.kt` 那类槽位同一条规矩）。
 *
 * 回调不带参数：宿主自己拿得到 `Context`，共享层不必认识它。
 */
object InboxWidgetHook {
    /** null = 本端没有桌面小组件。 */
    var onTodosChanged: (() -> Unit)? = null
}

/**
 * 收纳的存储：按账号一份 SharedPreferences，整份 JSON 读写，内存里缓存当前那份。
 * 写入方在后台线程，界面读 [version] 订阅变化。
 */
object InboxStore {
    private var cached: Pair<String, InboxData>? = null

    var version by mutableIntStateOf(0)
        private set

    /**
     * 持久化：`keyValueStore("inbox<账号后缀>")`。
     *
     * Android 上就是原来那个 `getSharedPreferences("inbox" + suffix)` 文件、同一个 `"data"` 键
     * （见 :core 的 `platform/KeyValueStore.android.kt`）⇒ **老数据无缝沿用**，不需要迁移代码。
     * Web 上是 localStorage 里的同名空间。
     */
    private fun prefs(account: String?) = keyValueStore("inbox${AccountContext.suffixFor(account)}")

    // `@Synchronized` 是 JVM 专属（默认导入，import 判据抓不到）⇒ 换成 :core 的平台缝：
    // Android/JVM 仍是 `synchronized`（逐字同义），Web 直跑（单线程）。
    private val lock = Any()

    fun load(account: String? = AccountContext.activeAccountId): InboxData =
        synchronizedBlock(lock) { loadLocked(account) }

    private fun loadLocked(account: String?): InboxData {
        val key = AccountContext.suffixFor(account)
        cached?.let { (k, d) -> if (k == key) return d }
        val data = prefs(account).getString("data")
            ?.let { runCatching { AppJson.decodeFromString<InboxData>(it) }.getOrNull() }
            ?.let { InboxRules.migrate(it, Clock.System.now().toEpochMilliseconds()) }
            ?: InboxData()
        cached = key to data
        return data
    }

    private fun update(account: String?, block: (InboxData) -> InboxData) = synchronizedBlock(lock) {
        val next = block(load(account))
        prefs(account).putString("data", AppJson.encodeToString(next))
        cached = AccountContext.suffixFor(account) to next
        version++
        // 当前账号的待办变了就重画桌面小组件（宿主槽位见 [InboxWidgetHook]）
        if (AccountContext.suffixFor(account) == AccountContext.safeSuffix()) runCatching { InboxWidgetHook.onTodosChanged?.invoke() }
    }

    /** 当前账号的快照；在 Composable 里调用会随写入重组。 */
    fun snapshot(): InboxData {
        if (version < 0) return InboxData()
        return load()
    }

    fun post(items: List<InboxItem>, account: String? = AccountContext.activeAccountId) {
        if (items.isEmpty()) return
        update(account) { InboxRules.merge(it, items, Clock.System.now().toEpochMilliseconds()) }
    }

    fun post(item: InboxItem, account: String? = AccountContext.activeAccountId) = post(listOf(item), account)

    /** 整类替换：[items] 里没有的该类旧消息删掉，其余照常合并。给公告这种「当前有效的全集」用。 */
    fun replace(category: String, items: List<InboxItem>, account: String? = AccountContext.activeAccountId) {
        val keep = items.mapTo(HashSet()) { it.id }
        update(account) { d ->
            val pruned = d.copy(messages = d.messages.filter { it.category != category || it.id in keep })
            InboxRules.merge(pruned, items, Clock.System.now().toEpochMilliseconds())
        }
    }

    fun setTodos(category: String, items: List<InboxItem>, account: String? = AccountContext.activeAccountId) =
        update(account) { InboxRules.replaceTodos(it, category, items, Clock.System.now().toEpochMilliseconds()) }

    fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        update(AccountContext.activeAccountId) { d -> d.copy(readAt = ids.filter { it !in d.readAt }.associateWith { now } + d.readAt) }
    }

    fun markAllRead() = markRead(load().messages.map { it.id })

    /** 待办页看过的待办不再算进首页红点。 */
    fun markTodosSeen(ids: Collection<String>) {
        val d = load()
        if (ids.any { it !in d.seenTodos }) update(AccountContext.activeAccountId) { it.copy(seenTodos = it.seenTodos + ids) }
    }

    fun ignoreTodo(id: String) = update(AccountContext.activeAccountId) { InboxRules.ignore(it, id, Clock.System.now().toEpochMilliseconds()) }

    fun markBubbled(id: String) = update(AccountContext.activeAccountId) { it.copy(bubbled = it.bubbled + id) }

    fun setEnabled(category: String, enabled: Boolean) =
        update(AccountContext.activeAccountId) { it.copy(off = if (enabled) it.off - category else it.off + category) }

    fun setSchoolFetchedAt(at: Long, account: String?) = update(account) { it.copy(schoolFetchedAt = at) }

    /** 学校消息里出现过的来源，给开关列表自动加行。 */
    fun schoolCategories(data: InboxData): List<String> =
        data.messages.map { it.category }.filter(InboxCategories::isSchool).distinct().sorted()
}

