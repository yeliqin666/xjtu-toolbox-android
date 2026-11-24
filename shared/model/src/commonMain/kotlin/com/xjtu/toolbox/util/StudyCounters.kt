package com.xjtu.toolbox.util

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.synchronizedBlock
import kotlinx.serialization.Serializable

/**
 * 学辅资料站的使用：`download` 下载、`preview` 预览、`course:<顶层目录>` 资料属于哪门课（顶层目录就是课程或类别）。
 * 只在用户本来就在下载、预览时记一笔，给学期总结用。
 */
val ZyxfUsage = DailyCounters("zyxf_usage")

/** 空闲教室每次查询查了哪些楼（项 = 楼名）。 */
val RoomQueries = DailyCounters("room_queries")

/** 思源学堂一次作业提交：截止与提交时刻都是 epoch 毫秒。 */
@Serializable
data class LmsSubmitRecord(val course: String, val title: String, val deadline: Long?, val submittedAt: Long)

/**
 * 思源学堂作业的提交记录（作业 id → 最近一次提交），打开作业详情时顺手记下，给学期总结用。
 * 不额外请求：详情页本来就要拉提交列表。[KEEP] 条之前的丢掉。
 */
object LmsSubmitLog {
    private const val KEY = "records"
    private const val KEEP = 600
    private val lock = Any()
    private val store get() = keyValueStore("lms_submit_log")

    fun record(activityId: Int, record: LmsSubmitRecord) {
        synchronizedBlock(lock) {
            val map = read()
            if (map[activityId.toString()] == record) return@synchronizedBlock
            val next = (map + (activityId.toString() to record)).entries
                .sortedBy { it.value.submittedAt }.takeLast(KEEP).associate { it.key to it.value }
            store.putString(KEY, AppJson.encodeToString(next))
        }
    }

    fun all(): List<LmsSubmitRecord> = read().values.toList()

    private fun read(): Map<String, LmsSubmitRecord> =
        runCatching { AppJson.decodeFromString<Map<String, LmsSubmitRecord>>(store.getString(KEY) ?: "{}") }.getOrDefault(emptyMap())
}
