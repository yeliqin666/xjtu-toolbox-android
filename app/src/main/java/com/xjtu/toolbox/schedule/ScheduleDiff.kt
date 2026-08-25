package com.xjtu.toolbox.schedule

import android.content.Context
import android.util.Log

private const val TAG = "ScheduleDiff"
private const val PREFS = "schedule_diff"

/**
 * 课表变更检测：调课、停课、换教室。
 *
 * 学校改课表不会通知任何人，教务系统里直接就变了。用户下次打开 App 看到的是新的，
 * 但**没人告诉他哪里变了**——这正是最需要知道的一件事。
 *
 * 做法是把上一次拉到的课表按课程指纹存一份，下次拉到新的就比一遍。
 * 不额外发任何请求：日程页本来每次进来都要拉课表，这里只是顺手比对一下。
 *
 * 只在**网络拉到的新数据**上比，读缓存不比：缓存和快照本来就是同一份，
 * 比出来永远是"无变化"，白跑一趟。
 */
object ScheduleDiff {

    /**
     * 一节课的身份与内容分开：
     * - 身份 [key]：课程号 + 星期 + 起始节次。这是"哪一节课"。
     * - 内容 [fingerprint]：教室 + 周次位串 + 结束节次 + 教师。这是"这节课什么样"。
     *
     * 身份不含教室，否则换教室会被判成"删一节 + 加一节"，而不是"这节课换教室了"。
     */
    private fun keyOf(c: CourseItem) = "${c.courseCode}|${c.dayOfWeek}|${c.startSection}"

    private fun fingerprintOf(c: CourseItem) =
        "${c.location}|${c.weekBits}|${c.endSection}|${c.teacher}"

    data class Change(val kind: Kind, val courseName: String, val detail: String) {
        enum class Kind { ADDED, REMOVED, MOVED }
    }

    /**
     * 比一遍并落新快照。第一次跑（没有旧快照）只落快照、不报变更——
     * 否则新装 App 的人一进来就会被告知"新增了 40 节课"。
     */
    fun diffAndStore(ctx: Context, termCode: String, courses: List<CourseItem>): List<Change> {
        if (termCode.isBlank() || courses.isEmpty()) return emptyList()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storeKey = "snap_$termCode"
        val old = prefs.getStringSet(storeKey, null)

        val now = courses.associate { keyOf(it) to fingerprintOf(it) }
        val nowFlat = now.map { "${it.key}=>${it.value}" }.toSet()
        val nameOf = courses.associate { keyOf(it) to it.courseName }

        prefs.edit().putStringSet(storeKey, nowFlat).apply()
        if (old == null) {
            Log.d(TAG, "$termCode 首次建立快照，${courses.size} 节，不报变更")
            return emptyList()
        }

        val oldMap = old.mapNotNull { entry ->
            val i = entry.indexOf("=>")
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 2)
        }.toMap()

        val changes = buildList {
            for ((k, fp) in now) {
                val prev = oldMap[k]
                val name = nameOf[k] ?: continue
                when {
                    prev == null -> add(Change(Change.Kind.ADDED, name, describeKey(k)))
                    prev != fp -> add(Change(Change.Kind.MOVED, name, describeDelta(prev, fp)))
                }
            }
            for (k in oldMap.keys - now.keys) {
                // 停掉的课在新表里已经没有名字了，只能报位置。
                add(Change(Change.Kind.REMOVED, "有课停了", describeKey(k)))
            }
        }
        if (changes.isNotEmpty()) Log.d(TAG, "$termCode 检出 ${changes.size} 处变更")
        return changes
    }

    /** 检出的变更暂存一条给屁岱气泡用；冒过就清掉，不重复念。 */
    fun setPending(ctx: Context, text: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("pending", text).apply()
    }

    fun pending(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pending", null)?.takeIf { it.isNotBlank() }

    /** 把一串变更压成一句人话。多于一条只报最要紧的那条加个计数，气泡塞不下更多。 */
    fun summarize(changes: List<Change>): String? {
        if (changes.isEmpty()) return null
        // 停课最要紧（白跑一趟），其次换教室/调时间，最后新增。
        val first = changes.minByOrNull {
            when (it.kind) {
                Change.Kind.REMOVED -> 0
                Change.Kind.MOVED -> 1
                Change.Kind.ADDED -> 2
            }
        }!!
        val head = when (first.kind) {
            Change.Kind.REMOVED -> "课表有课被取消了（${first.detail}）"
            Change.Kind.MOVED -> "${first.courseName}变了：${first.detail}"
            Change.Kind.ADDED -> "课表新增了${first.courseName}（${first.detail}）"
        }
        return if (changes.size > 1) "$head，共 ${changes.size} 处改动" else head
    }

    private val DAY_NAMES = listOf("", "一", "二", "三", "四", "五", "六", "日")

    private fun describeKey(k: String): String {
        val p = k.split('|')
        val day = p.getOrNull(1)?.toIntOrNull() ?: return "时间未知"
        val sec = p.getOrNull(2)?.toIntOrNull() ?: return "周${DAY_NAMES.getOrElse(day) { "?" }}"
        return "周${DAY_NAMES.getOrElse(day) { "?" }}第${sec}节"
    }

    /** 只说变了哪一项，别把整串指纹丢给用户。 */
    private fun describeDelta(oldFp: String, newFp: String): String {
        val a = oldFp.split('|')
        val b = newFp.split('|')
        val parts = buildList {
            if (a.getOrNull(0) != b.getOrNull(0)) {
                add("教室 ${a.getOrNull(0).orEmpty().ifBlank { "?" }} → ${b.getOrNull(0).orEmpty().ifBlank { "?" }}")
            }
            if (a.getOrNull(1) != b.getOrNull(1)) add("上课周次有调整")
            if (a.getOrNull(2) != b.getOrNull(2)) add("下课节次变了")
            if (a.getOrNull(3) != b.getOrNull(3)) {
                add("教师 ${a.getOrNull(3).orEmpty().ifBlank { "?" }} → ${b.getOrNull(3).orEmpty().ifBlank { "?" }}")
            }
        }
        return parts.joinToString("；").ifBlank { "有改动" }
    }
}
