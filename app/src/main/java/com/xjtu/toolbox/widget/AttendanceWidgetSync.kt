package com.xjtu.toolbox.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.notification.HeadlessSessions
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.todayOnCampus

private const val TAG = "AttendanceSync"

/** 连点两下刷新的防抖：一次查询要几秒，别让它排成队。 */
private const val RAPID_TAP_MS = 8_000L

/** 「正在查询」这个提示最多挂多久：Worker 被系统掐掉时别让卡片永远显示在查。 */
private const val QUERYING_WINDOW_MS = 90_000L

private const val PREFS = "attendance_widget_sync"
private const val KEY_LAST_CHECK = "last_check_at"
private const val KEY_QUERYING_UNTIL = "querying_until"
private const val KEY_SWIPE_DAY = "swipe_day"
private const val KEY_SWIPE_MINUTE = "swipe_minute"
private const val KEY_SWIPE_LOCATION = "swipe_location"

/**
 * 这家仓库的小组件缓存都直连 `SharedPreferences`（见 [NoticeWidgetStore]）：
 * 它们只活在 `:app` 里，没必要走 `:shared:model` 那条跨端键值缝，而那个缝也没有 long。
 */
private fun syncPrefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 一条本机记下的刷卡事实（学校那边刷上了，但考勤结果还没结算）。 */
internal data class LocalSwipe(
    /** 距 00:00 的分钟，用来和课表时间窗比。 */
    val minuteOfDay: Int,
    val location: String = "",
) {
    /** `14:14`，直接画在卡片上。 */
    val clock: String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

/**
 * 智能卡片的考勤取数：**只在用户点「刷新」时才去问学校**。
 *
 * 走过一次自动轮询，结论是不做（2026-11-18 定）：学校那边没有推送，只能靠问，而问就是
 * 一次次登录/请求 —— 一节课几分钟一轮，全天下来纯属白耗电和白打学校接口。改成按需：
 *
 * - 卡片右下角「刷新」→ [requestSync] → 后台问一次 → 重画卡片，**全程不打开 App**；
 * - 除此之外，只有原本就有的那些触发点（切到考勤页、切到校园卡、换包/改时间等）会重画卡片，
 *   它们本来就存在，这里不做任何删改；
 * - 拿到的刷卡事实会落在本机，卡片据此说「已刷卡 · 待结算」并标出刷卡时刻；
 * - 卡片上那行小字如实写「N 分钟前查过 / 还未查询过」，**说的就是哪一刻的事实**，
 *   不假装是此刻 —— 不自动刷新的前提下，这一点比什么都重要。
 */
internal object AttendanceWidgetSync {

    /** 最近一次**成功**问过学校的时刻；0 = 从没问过（卡片据此显示「还未查询」）。 */
    fun lastCheckAt(context: Context): Long = syncPrefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** 本机记下的今天那条刷卡；没有（或不是今天的）返回 null。 */
    fun todaySwipe(context: Context, day: String): LocalSwipe? {
        val p = syncPrefs(context)
        if (p.getString(KEY_SWIPE_DAY, null) != day) return null
        val minute = p.getInt(KEY_SWIPE_MINUTE, -1)
        if (minute < 0) return null
        return LocalSwipe(minute, p.getString(KEY_SWIPE_LOCATION, null).orEmpty())
    }

    /** 正在后台查（点了刷新之后那一小会儿）。 */
    fun isQuerying(context: Context): Boolean =
        System.currentTimeMillis() < syncPrefs(context).getLong(KEY_QUERYING_UNTIL, 0L)

    /**
     * 去问一次学校（用户点了「刷新」）。
     *
     * 用 KEEP 而不是 REPLACE：连点两下也只排一个任务；[RAPID_TAP_MS] 内重复点直接忽略。
     */
    fun requestSync(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt(context) < RAPID_TAP_MS && isQuerying(context)) return
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "attendance_widget_sync",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AttendanceSyncWorker>().build(),
            )
        }.onFailure { Log.w(TAG, "排不上自查任务", it) }
    }

    /** 点了刷新按钮：先把「正在查询」挂上，卡片立刻能看到反馈。 */
    internal fun markQuerying(context: Context) {
        syncPrefs(context).edit()
            .putLong(KEY_QUERYING_UNTIL, System.currentTimeMillis() + QUERYING_WINDOW_MS)
            .apply()
    }

    internal fun clearQuerying(context: Context) {
        syncPrefs(context).edit().remove(KEY_QUERYING_UNTIL).apply()
    }
}

/**
 * 真正去问学校的那一次。独立成顶层类而不是嵌在 object 里：WorkManager 是**反射按类名**
 * 实例化 Worker 的，顶层公开类最不容易在这种地方被 R8 或嵌套规则坑掉。
 */
class AttendanceSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            return sync()
        } finally {
            // 无论成功、失败还是被系统掐断，都别让卡片永远停在「正在查询…」
            AttendanceWidgetSync.clearQuerying(applicationContext)
        }
    }

    private suspend fun sync(): Result {
        val context = applicationContext
        AccountContext.restoreIfNeeded()
        // 没配账号 / 没密码就别折腾：后台没法弹登录
        if (!HeadlessSessions.hasAccount()) return Result.success()

        val now = System.currentTimeMillis()
        val day = todayOnCampus().toString()
        val site = runCatching { HeadlessSessions.site(LoginType.ATTENDANCE) }.getOrNull()
            ?: return Result.success()

        // 刷卡流水：实时，这就是「刷上了没有」的第一手信号
        val streams = runCatching { AttendanceApi(site).getStreams(day, day) }
            .onFailure { Log.d(TAG, "刷卡流水拉取失败：${it.message}") }
            .getOrNull() ?: return Result.success()

        val effective = streams.filter { it.effective }
        val latest = effective.maxByOrNull { it.collectTime }

        val p = syncPrefs(context)
        val editor = p.edit()
        if (latest != null) {
            editor.putString(KEY_SWIPE_DAY, day)
            editor.putInt(KEY_SWIPE_MINUTE, minuteOfDay(latest.collectTime) ?: 0)
            editor.putString(KEY_SWIPE_LOCATION, latest.location)
        } else {
            // 今天还没刷上：清掉上一次的（跨天 / 补刷失败留下的旧结论不能拿来显示）
            editor.remove(KEY_SWIPE_DAY).remove(KEY_SWIPE_MINUTE).remove(KEY_SWIPE_LOCATION)
        }
        // 只有真的问到了才推进这个时刻：拉取失败时卡片不该显示「刚刚查过」
        editor.putLong(KEY_LAST_CHECK, now).apply()

        Log.d(TAG, "刷新完成：今日有效刷卡 ${effective.size} 条，最新 ${latest?.collectTime}")

        // 顺手把今天的考勤结果补一次：结算出来了就让卡片换成「已打卡 · 正常/迟到」
        refreshTodayResult(site, day)
        // 查完立刻重画，用户下一刻看桌面就是新的
        SmartWidgetUpdater.requestUpdate(context)
        return Result.success()
    }

    /**
     * 把今天这一天的考勤结果并进落盘分片。
     *
     * 只拉今天（不是整学期）：卡片要的就是这一格，`merge` 会按 `sbh` 新盖旧，
     * 不动整学期那份的其余记录。
     */
    private suspend fun refreshTodayResult(site: SiteSession, day: String) {
        runCatching {
            val api = AttendanceApi(site)
            val bh = api.getTermBh()
            val terms = runCatching { api.getTermList() }.getOrDefault(emptyList())
            val termCode = AttendanceRecordStore.termCodeOf(terms, bh) ?: return
            val fresh = api.getWaterRecords(bh, day, day)
            if (fresh.isEmpty()) return

            val account = AccountContext.activeAccountId ?: AccountStore().activeAccountId()
            val postgraduate = runCatching {
                AccountStore().activeAccount()?.accountType == AccountType.POSTGRADUATE
            }.getOrDefault(AccountContext.activeAccountType == AccountType.POSTGRADUATE)
            val cached = AttendanceRecordStore.load(postgraduate, termCode, account)
            AttendanceRecordStore.save(
                postgraduate,
                AttendanceRecordStore.mergedShard(
                    termCode = termCode,
                    fresh = fresh,
                    cached = cached,
                    // 只拉了今天，**不是**整学期：绝不能让 fullScanAt 前进，
                    // 否则老师期末补的早期考勤永远等不到那次全量重扫。
                    fullScan = false,
                    now = System.currentTimeMillis(),
                ),
                account,
            )
        }.onFailure { Log.d(TAG, "补今天考勤结果失败：${it.message}") }
    }
}

/** `2026-11-18 14:14:37` → 距 00:00 的分钟；解析不出返回 null。 */
internal fun minuteOfDay(collectTime: String): Int? {
    val time = collectTime.substringAfter(' ', "").ifBlank { collectTime.substringBefore(' ') }
    val parts = time.split(':')
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
