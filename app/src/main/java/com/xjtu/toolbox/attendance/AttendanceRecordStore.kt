package com.xjtu.toolbox.attendance

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson

private const val TAG = "AttendanceStore"

/**
 * 一学期考勤流水的落盘缓存，按教务学期码（`2025-2026-2`）分片。
 * 键选它是因为课表和考勤流水的 `termString` 都是这个格式。
 *
 * 刷新分层——老师改考勤没有时间限制，期末回头补第 3 周是常事，只做增量看不到那种修改，
 * 但每次全量又太贵：
 *
 * - 历史学期：结束了不会再改，永久命中，一个请求不发
 * - 当前学期：[FRESH_TTL_MS] 内直接用；过期只补最近 [INCREMENTAL_DAYS] 天
 * - 兜底：距上次全量超过 [FULL_RESCAN_MS] 整学期重扫一遍
 * - 下拉刷新：强制全量
 */
object AttendanceRecordStore {

    /** 当前学期缓存的新鲜期。这段时间内不发任何请求。 */
    const val FRESH_TTL_MS = 2L * 60 * 60 * 1000

    /** 增量刷新回看的天数。 */
    const val INCREMENTAL_DAYS = 3L

    /** 全量重扫间隔，用来捞老师事后修改的早期记录。 */
    const val FULL_RESCAN_MS = 3L * 24 * 60 * 60 * 1000

    @kotlinx.serialization.Serializable
    data class Shard(
        val termCode: String = "",
        val records: List<AttendanceWaterRecord> = emptyList(),
        /** 上次任意刷新的时刻。 */
        val fetchedAt: Long = 0L,
        /** 上次**全量**刷新的时刻。增量刷新不更新它。 */
        val fullScanAt: Long = 0L,
    )

    /** [accountId] 由调用方在发起拉取时定下，避免结果回来时已切账号而写错命名空间。 */
    private fun prefs(ctx: Context, postgraduate: Boolean, accountId: String?) = ctx.getSharedPreferences(
        (if (postgraduate) "attendance_records_pg" else "attendance_records_ug") +
            AccountContext.suffixFor(accountId),
        Context.MODE_PRIVATE,
    )

    fun load(
        ctx: Context,
        postgraduate: Boolean,
        termCode: String,
        accountId: String? = AccountContext.activeAccountId,
    ): Shard? {
        if (termCode.isBlank()) return null
        val raw = prefs(ctx, postgraduate, accountId).getString(termCode, null) ?: return null
        return runCatching { AppJson.decodeFromString<Shard>(raw) }.getOrNull()
            ?.takeIf { it.records.isNotEmpty() }
    }

    fun save(
        ctx: Context,
        postgraduate: Boolean,
        shard: Shard,
        accountId: String? = AccountContext.activeAccountId,
    ) {
        runCatching {
            prefs(ctx, postgraduate, accountId).edit()
                .putString(shard.termCode, AppJson.encodeToString(shard))
                .apply()
        }
    }

    /**
     * 合并新旧记录，同一条以新盖旧。
     *
     * 去重键取 `sbh`：老师改状态时 sbh 不变、status 变，新的在前于是覆盖旧的。
     * sbh 为空的脏数据退回四元组，至少不会互相吞掉。
     */
    fun merge(
        fresh: List<AttendanceWaterRecord>,
        cached: List<AttendanceWaterRecord>,
    ): List<AttendanceWaterRecord> =
        (fresh + cached)
            .distinctBy { it.sbh.ifBlank { "${it.date}|${it.startTime}|${it.courseName}|${it.location}" } }
            .sortedByDescending { it.date }

    /** 该做哪种刷新。 */
    enum class Plan {
        /** 缓存够用，不发请求。 */
        NONE,

        /** 只补最近几天。 */
        INCREMENTAL,

        /** 整学期重扫。 */
        FULL,
    }

    /**
     * @param sealedTerm 这个学期是否已经结束（数据不会再变）。
     * @param force 用户主动下拉刷新。
     */
    fun planFor(shard: Shard?, sealedTerm: Boolean, force: Boolean): Plan {
        if (force) return Plan.FULL
        if (shard == null) return Plan.FULL
        if (sealedTerm) return Plan.NONE
        val now = System.currentTimeMillis()
        if (now - shard.fullScanAt > FULL_RESCAN_MS) return Plan.FULL
        if (now - shard.fetchedAt > FRESH_TTL_MS) return Plan.INCREMENTAL
        return Plan.NONE
    }

    fun logPlan(termCode: String, plan: Plan, shard: Shard?) {
        val age = shard?.let { (System.currentTimeMillis() - it.fetchedAt) / 60000 }
        Log.d(TAG, "$termCode: $plan（缓存 ${shard?.records?.size ?: 0} 条，${age ?: "-"} 分钟前）")
    }
}
