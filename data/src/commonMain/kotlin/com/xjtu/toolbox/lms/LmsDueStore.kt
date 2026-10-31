package com.xjtu.toolbox.lms

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import java.time.Duration
import java.time.Instant

private const val TAG = "LmsDueStore"

/**
 * 作业截止时间的落盘缓存。
 *
 * 日程页**不为作业发任何请求**——仓库主担心过"一个功能牵涉多套登录，加载不及时就断了"。
 * 这里只读别处已经拉到的数据。两个写入方（首页刷新扫最新学期、截止提醒扫全部课，都经 `LmsDueCollector`）
 * 覆盖的课程范围不一样，所以按 (courseId, activityId) 合并，不能直接覆盖。
 *
 * ## 为什么不在 `:app`
 *
 * 原来这份缓存长在 `:app`（`DataCache` + `android.content.Context`），只有 Android 用得上
 * ⇒ 桌面与 web 的日程页「接下来」一栏永远是空的。现在落到 `:data` 并改用共享的
 * [keyValueStore] 缝：Android 走 SharedPreferences、桌面走 properties 文件，同一份代码。
 * `:app` 只剩接线点（谁在什么时候拉数、谁来喂老数据）。
 *
 * ## 键名与老数据
 *
 * 键名 [KEY] 逐字不变（`lms_due_items`）。Android 老数据在 `data_cache<账号后缀>/lms_due_items.json`
 * 里，值就是同一份 `List<LmsDue>` 的 JSON ⇒ 宿主装一个 [installLegacySource] 就能读回。
 * 搬迁是**一次性且幂等**的：新 store 里还没有这个键、也没有「已经搬过」的标记时才去读，
 * 读到了就写进来，然后无论如何都打上标记。老文件不删（旧版回滚还能用），也不再写。
 */
object LmsDueStore {

    /** 键名。与 Android 老缓存同名字段逐字一致，别改。公开是因为 `:app` 的搬迁接线要拿它读老值。 */
    const val KEY = "lms_due_items"

    /** 「老缓存已经问过一次」的标记。读不到也打上，省得每次冷启动都去翻一遍旧文件。 */
    private const val LEGACY_READ = "lms_due_items@legacy_read"

    /** 过期太久的条目没有意义留着——截止时间早于「现在减 1 天」的直接丢掉。 */
    private val EXPIRE_MARGIN: Duration = Duration.ofDays(1)

    /**
     * 老数据（`:app` 的 `DataCache`）的只读取数口。参数是账号（空串 = 不分账号的那份）。
     *
     * 由宿主在启动时装一次：桌面端没有老格式，不装就是空实现，`load` 直接返回空。
     */
    private var legacySource: ((account: String) -> String?)? = null

    /** 装上老数据的读取口。装多次以最后一次为准（幂等）；传 null 卸掉。 */
    fun installLegacySource(read: ((account: String) -> String?)?) {
        legacySource = read
    }

    private fun store(account: String): KeyValueStore =
        keyValueStore("lms_due" + AccountContext.suffixFor(account.ifEmpty { null }))

    fun save(items: List<LmsDue>, account: String) {
        if (items.isEmpty()) return
        runCatching {
            val kv = store(account)
            val existing = readItems(kv, account)
            val merged = mergeDue(existing, items)
            writeItems(kv, merged)
            InboxStore.setTodos(
                InboxCategories.LMS,
                OwnInbox.lmsTodos(merged),
                account.ifEmpty { null },
            )
        }.onFailure { Log.w(TAG, "作业截止缓存写入失败", it) }
    }

    fun load(account: String): List<LmsDue> = runCatching { readItems(store(account), account) }.getOrDefault(emptyList())

    private fun readItems(kv: KeyValueStore, account: String): List<LmsDue> {
        kv.getString(KEY)?.let { return decode(it) }
        // 新 store 是空的：老数据可能还躺在 data_cache 里，读一次搬过来。
        if (kv.getBoolean(LEGACY_READ, false)) return emptyList()
        val read = legacySource ?: return emptyList()
        val legacy = runCatching { read(account) }.onFailure { Log.w(TAG, "老作业缓存读取失败", it) }.getOrNull()
        val items = legacy?.let { decode(it) }.orEmpty()
        if (items.isNotEmpty()) writeItems(kv, items)
        kv.putBoolean(LEGACY_READ, true)
        if (items.isNotEmpty()) Log.d(TAG, "老作业缓存搬迁：${items.size} 条")
        return items
    }

    private fun writeItems(kv: KeyValueStore, items: List<LmsDue>) {
        kv.putString(KEY, AppJson.encodeToString(items))
    }

    private fun decode(raw: String): List<LmsDue> =
        runCatching { AppJson.decodeFromString<List<LmsDue>>(raw) }
            .onFailure { Log.w(TAG, "作业截止缓存解析失败", it) }
            .getOrDefault(emptyList())

    /**
     * 按 (courseId, activityId, exam) 合并：同一条以 fetchedAt 较新的为准；
     * 截止时间早于「现在减 1 天」的条目丢掉——留着也没人会看已经过期一天以上的作业。
     *
     * 公开而非 internal：`:app` 的既有单测（`LmsDueStoreTest` / `InboxTest`）直接调它，
     * 那两个测试逐字不动地留着当成这条规则的契约。
     */
    fun mergeDue(
        existing: List<LmsDue>,
        incoming: List<LmsDue>,
        now: Instant = Instant.now(),
    ): List<LmsDue> {
        val cutoff = now.minus(EXPIRE_MARGIN)
        return (existing + incoming)
            .groupBy { Triple(it.courseId, it.activityId, it.exam) }
            .values
            .map { group -> group.maxBy { it.fetchedAt } }
            .filter { item -> runCatching { Instant.parse(item.deadline) }.getOrNull()?.isAfter(cutoff) ?: true }
    }
}
