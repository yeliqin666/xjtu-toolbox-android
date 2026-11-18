package com.xjtu.toolbox.attendance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 考勤页拉到的流水怎么写进落盘分片。
 *
 * 2026-11-18 的真机反馈是「点了打开考勤，页面是对的，退出来智能卡片还是错的」：
 * 考勤页拉完只在内存里，卡片读的是另一份落盘快照，而且只有日程页的角标会写它。
 * 这里钉住写这一侧的判据（学期键、合并、什么时候算全量）。
 */
class AttendanceRecordStoreShardTest {

    private fun rec(
        sbh: String = "r-1",
        date: String = "2026-11-17",
        status: WaterType = WaterType.NORMAL,
        name: String = "高等数学",
    ) = AttendanceWaterRecord(
        sbh = sbh, startTime = 3, endTime = 4, week = 6, date = date,
        courseName = name, status = status,
    )

    private val terms = listOf(
        TermInfo("646", "2026-2027 第一学期", "2026-09-14", "2027-02-21", "2026-2027-1"),
        TermInfo("630", "2025-2026 第二学期", "2026-02-23", "2026-07-05", "2025-2026-2"),
    )

    @Test
    fun `学期键用教务学期码而不是考勤自己的 bh`() {
        // 用 bh（646）当键存进去，卡片按学期码去读就永远读不到 —— 那正是「页面对、卡片错」
        assertEquals("2026-2027-1", AttendanceRecordStore.termCodeOf(terms, "646"))
        assertEquals("2025-2026-2", AttendanceRecordStore.termCodeOf(terms, "630"))
        // 学期表里没有这一号：宁可不写，也别写一份谁也读不到的分片
        assertNull(AttendanceRecordStore.termCodeOf(terms, "999"))
    }

    @Test
    fun `并进旧分片时一条都不丢，同一条以新盖旧`() {
        val cached = AttendanceRecordStore.Shard(
            termCode = "2026-2027-1",
            records = listOf(rec("r-1", status = WaterType.ABSENCE), rec("r-2", date = "2026-11-10")),
            fetchedAt = 100L,
            fullScanAt = 100L,
        )
        // 老师把 r-1 改正了
        val shard = AttendanceRecordStore.mergedShard(
            termCode = "2026-2027-1",
            fresh = listOf(rec("r-1", status = WaterType.NORMAL)),
            cached = cached,
            fullScan = false,
            now = 200L,
        )
        assertEquals(2, shard.records.size, "旧记录要留着：增量只覆盖最近几天，直接替换会把整学期抹掉")
        assertEquals(WaterType.NORMAL, shard.records.first { it.sbh == "r-1" }.status, "同一条以新盖旧")
        assertEquals(200L, shard.fetchedAt)
    }

    @Test
    fun `只有整学期那一次才推进 fullScanAt`() {
        val cached = AttendanceRecordStore.Shard(
            termCode = "2026-2027-1", records = listOf(rec()), fetchedAt = 100L, fullScanAt = 100L,
        )
        // 考勤页拉的就是整学期（从学期起始日开始）
        assertEquals(
            300L,
            AttendanceRecordStore.mergedShard("2026-2027-1", listOf(rec("r-2")), cached, fullScan = true, now = 300L).fullScanAt,
        )
        // 只有最近几天的增量：fetchedAt 前进，fullScanAt 原地不动，
        // 否则之后永远轮不到全量重扫，老师期末补的早期考勤就再也看不到了
        val incremental = AttendanceRecordStore.mergedShard(
            "2026-2027-1", listOf(rec("r-3")), cached, fullScan = false, now = 300L,
        )
        assertEquals(300L, incremental.fetchedAt)
        assertEquals(100L, incremental.fullScanAt)
    }

    @Test
    fun `第一次写（没有旧分片）时增量也不把 fullScanAt 推到现在`() {
        val shard = AttendanceRecordStore.mergedShard(
            "2026-2027-1", listOf(rec()), cached = null, fullScan = false, now = 300L,
        )
        assertEquals(300L, shard.fetchedAt)
        assertEquals(0L, shard.fullScanAt, "0 = 从没全量拉过，下次该整学期重扫")
        assertTrue(shard.records.isNotEmpty())
    }
}
