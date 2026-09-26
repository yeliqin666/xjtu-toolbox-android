package com.xjtu.toolbox.lms

import android.content.Context
import com.google.gson.Gson
import com.xjtu.toolbox.data.DataCache
import java.time.Duration
import java.time.Instant

/**
 * 一条「没提交、有截止时间」的作业，日程页「今日」栏的「接下来」（plan2 §5）读这份缓存。
 *
 * @param deadline ISO-8601，取自 [LmsActivity.deadlineInstant]（优先 `deadline`，
 * 缺失退回 `endTime`），与截止提醒通知同一口径。
 * @param submitted `userSubmitCount > 0`。
 */
data class LmsDue(
    val courseId: Int,
    val courseName: String,
    val activityId: Int,
    val title: String,
    val deadline: String,
    val submitted: Boolean,
    val fetchedAt: Long,
)

/**
 * 作业截止时间的落盘缓存。
 *
 * 日程页**不为作业发任何请求**——仓库主担心过"一个功能牵涉多套登录，加载不及时就断了"。
 * 这里只读别处已经拉到的数据，两个写入方覆盖的课程范围不一样（见 [HomeStatsRefresher.lmsLatest]、
 * `LmsDeadlineWorker.collectDue`），所以按 (courseId, activityId) 合并，不能直接覆盖。
 */
object LmsDueStore {
    private val gson = Gson()
    private const val KEY = "lms_due_items"

    /** 过期太久的条目没有意义留着——截止时间早于「现在减 1 天」的直接丢掉。 */
    private val EXPIRE_MARGIN: Duration = Duration.ofDays(1)

    fun save(ctx: Context, items: List<LmsDue>, account: String) {
        if (items.isEmpty()) return
        runCatching {
            val cache = DataCache(ctx, account.ifEmpty { null })
            val existing = loadRaw(cache)
            val merged = mergeDue(existing, items)
            cache.put(KEY, gson.toJson(merged))
        }
    }

    fun load(ctx: Context, account: String): List<LmsDue> =
        runCatching { loadRaw(DataCache(ctx, account.ifEmpty { null })) }.getOrDefault(emptyList())

    private fun loadRaw(cache: DataCache): List<LmsDue> =
        cache.get(KEY, Long.MAX_VALUE)
            ?.let { gson.fromJson(it, Array<LmsDue>::class.java)?.toList() }
            .orEmpty()

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
