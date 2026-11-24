package com.xjtu.toolbox.attendance

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.account.restoreIfNeeded
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.widget.SmartWidgetUpdater

/**
 * 给考勤页的取数口加一道「拉到的流水顺手落盘，并重画智能卡片」。
 *
 * 为什么需要这一层（2026-11-18 真机反馈）：「点了打开考勤，页面是对的，退出来卡片还是错的」。
 * 原因是两边读的根本不是一份数据 ——
 *
 * - 考勤页每次都直连站点现拉（[AppAttendanceSource]），**拉完就只在内存里**；
 * - 智能卡片只读 [AttendanceRecordStore] 落盘的那份，而全仓唯一写它的地方是
 *   `CourseLinks.fetchAttendanceIndexInner`，也就是**日程页课表格子上的考勤角标**。
 *
 * 于是不看日程页、或者角标开关关着的人，卡片读到的一直是几天前那份旧快照（甚至没有），
 * 表现就是「页面对、卡片错」，而且怎么重进考勤页都不会好 —— 那一页根本不写这份缓存。
 *
 * 这里让考勤页拉到的结果成为落盘的**新基准**，然后立刻通知卡片重画，两边从此同源。
 * 判据（学期码换算、合并、什么时候算全量）全在 [AttendanceRecordStore] 的纯函数里，
 * 这一层只负责「拿到什么、存哪儿、什么时候叫卡片重画」。
 *
 * 只包在 `:app` 这一层：桌面端与 serve 端各有自己的会话与落盘策略，不该被这里顺手写。
 *
 * 用接口委托（`by delegate`）而不是逐个转发：这端口有十几个方法，逐个写一遍只会
 * 在下次加方法时漏掉一个，然后那个方法在 App 上突然失效。
 */
internal class AppAttendanceRecordingSource(
    private val delegate: AttendanceSource,
    private val context: Context,
) : AttendanceSource by delegate {

    override suspend fun getWaterRecords(
        termBh: String?,
        startDate: String,
        endDate: String,
    ): List<AttendanceWaterRecord> {
        val records = delegate.getWaterRecords(termBh, startDate, endDate)
        // 落盘 + 重画都是「顺手」的事：写失败不该让考勤页跟着报错（页面该有的数据已经拿到了）
        runCatching { persist(termBh, startDate, records) }
        runCatching { SmartWidgetUpdater.requestUpdate(context) }
        return records
    }

    /**
     * 把这一次拉到的流水并进本学期的分片。
     *
     * 学期键用教务学期码：智能卡片就是按它去读的（配合 [AttendanceRecordStore.termCodeOf]）。
     */
    private suspend fun persist(termBh: String?, startDate: String, records: List<AttendanceWaterRecord>) {
        if (records.isEmpty()) return
        AccountContext.restoreIfNeeded()
        val terms = delegate.getTermList()
        val bh = termBh ?: delegate.getTermBh()
        val termCode = AttendanceRecordStore.termCodeOf(terms, bh) ?: return

        // 研究生和本科生是两份分片（见 AttendanceRecordStore.store）。这个判断必须和
        // SmartWidgetUpdater.todayAttendance 同源，否则写进去的和读出来的不是同一份。
        val account = AccountContext.activeAccountId ?: AccountStore().activeAccountId()
        val postgraduate = runCatching {
            AccountStore().activeAccount()?.accountType == AccountType.POSTGRADUATE
        }.getOrDefault(AccountContext.activeAccountType == AccountType.POSTGRADUATE)

        val cached = AttendanceRecordStore.load(postgraduate, termCode, account)
        // 从学期起始日往后拉的才是整学期；只看最近几天的那种（日程页的增量刷新）不能推进
        // fullScanAt，否则永远轮不到全量重扫。
        val fullScan = startDate.isBlank() || startDate == terms.firstOrNull { it.bh == bh }?.startDate
        AttendanceRecordStore.save(
            postgraduate,
            AttendanceRecordStore.mergedShard(
                termCode = termCode,
                fresh = records,
                cached = cached,
                fullScan = fullScan,
                now = System.currentTimeMillis(),
            ),
            account,
        )
    }
}
