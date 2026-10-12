package com.xjtu.toolbox.lms

import android.content.Context
import com.xjtu.toolbox.data.DataCache
import java.time.Duration
import java.time.Instant

// `data class LmsDue` 搬进 `:core`（`com.xjtu.toolbox.lms`，同一个包名）—— 日程页要把
// 它摆进「接下来」那一列，而屏已经在 `:core`。落盘这一半（下面的 `LmsDueStore`）仍在 `:app`：
// 它读的是 `DataCache` 里那份 JSON（键 `lms_due_items` 逐字未动）。

/**
 * 作业截止时间的落盘缓存。
 *
 * 日程页**不为作业发任何请求**——仓库主担心过"一个功能牵涉多套登录，加载不及时就断了"。
 * 这里只读别处已经拉到的数据。两个写入方（首页刷新扫最新学期、截止提醒扫全部课，都经 [LmsDueCollector]）
 * 覆盖的课程范围不一样，所以按 (courseId, activityId) 合并，不能直接覆盖。
 */
object LmsDueStore {
    private const val KEY = "lms_due_items"

    /** 过期太久的条目没有意义留着——截止时间早于「现在减 1 天」的直接丢掉。 */
    private val EXPIRE_MARGIN: Duration = Duration.ofDays(1)

    fun save(ctx: Context, items: List<LmsDue>, account: String) {
        if (items.isEmpty()) return
        runCatching {
            val cache = DataCache(ctx, account.ifEmpty { null })
            val existing = loadRaw(cache)
            val merged = mergeDue(existing, items)
            cache.write(KEY, merged)
            com.xjtu.toolbox.inbox.InboxStore.setTodos(
                com.xjtu.toolbox.inbox.InboxCategories.LMS,
                com.xjtu.toolbox.inbox.OwnInbox.lmsTodos(merged),
                account.ifEmpty { null },
            )
        }
    }

    fun load(ctx: Context, account: String): List<LmsDue> =
        runCatching { loadRaw(DataCache(ctx, account.ifEmpty { null })) }.getOrDefault(emptyList())

    private fun loadRaw(cache: DataCache): List<LmsDue> = cache.read<List<LmsDue>>(KEY, Long.MAX_VALUE).orEmpty()

    /**
     * 按 (courseId, activityId) 合并：同一条以 fetchedAt 较新的为准；
     * 截止时间早于「现在减 1 天」的条目丢掉——留着也没人会看已经过期一天以上的作业。
     */
    internal fun mergeDue(existing: List<LmsDue>, incoming: List<LmsDue>, now: Instant = Instant.now()): List<LmsDue> {
        val cutoff = now.minus(EXPIRE_MARGIN)
        return (existing + incoming)
            .groupBy { it.courseId to it.activityId }
            .values
            .map { group -> group.maxBy { it.fetchedAt } }
            .filter { item -> runCatching { Instant.parse(item.deadline) }.getOrNull()?.isAfter(cutoff) ?: true }
    }
}
