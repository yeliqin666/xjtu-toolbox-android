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

    /**
     * 同一身份下可能有多条：某几周临时换教室时，数据源会按教室拆成几条（周次互不重叠）。
     * 只有一条时与 [fingerprintOf] 完全相同，老快照不会因此误报。多条时把它们合成一份：
     * 教室按「教室[周次]」列全，周次取并集——临时换教室换到了哪一周也能比出来，而不是
     * 像 `associate` 那样只留下最后一条、把其余几条的变化悄悄吞掉。
     */
    internal fun groupFingerprint(group: List<CourseItem>): String {
        if (group.size == 1) return fingerprintOf(group.single())
        val sorted = group.sortedBy { it.getWeeks().firstOrNull() ?: Int.MAX_VALUE }
        val location = sorted.joinToString("、") { "${it.location}[${compactWeeks(it.getWeeks())}]" }
        val len = group.maxOf { it.weekBits.length }
        val bits = CharArray(len) { i -> if (group.any { it.weekBits.getOrNull(i) == '1' }) '1' else '0' }
        val ends = group.map { it.endSection }.distinct().sorted().joinToString(",")
        val teachers = group.map { it.teacher }.distinct().sorted().joinToString("、")
        return "$location|${String(bits)}|$ends|$teachers"
    }

    /** `[1,2,3,5]` → `1-3,5`。 */
    private fun compactWeeks(weeks: List<Int>): String = buildList {
        var i = 0
        while (i < weeks.size) {
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
            add(if (j > i) "${weeks[i]}-${weeks[j]}" else "${weeks[i]}")
            i = j + 1
        }
    }.joinToString(",")

    data class Change(val kind: Kind, val courseName: String, val detail: String, val reason: String? = null) {
        enum class Kind { ADDED, REMOVED, MOVED }
    }

    /**
     * 比一遍并落新快照。第一次跑（没有旧快照）只落快照、不报变更——
     * 否则新装 App 的人一进来就会被告知"新增了 40 节课"。
     */
    fun diffAndStore(ctx: Context, termCode: String, courses: List<CourseItem>): List<Change> {
        if (termCode.isBlank() || courses.isEmpty()) return emptyList()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 快照按来源分开存。三个系统给的周次位串长度、教室写法都不完全一样，
        // 用同一份快照去比会在换来源（设置里改、或非教务源失败退回教务）的那一次
        // 把每一门课都报成"变了"。分开存的代价只是换来源后重建一次基线。
        //
        // 键名带格式版本：4.9.6 起同一节课可能拆成几条（临时换教室）、jwapp 的调停课合并也改了，
        // 拿老快照来比会把没变的课报成"变了"。换版本等于重建一次基线，旧版本的键顺手清掉。
        val storeKey = "${SNAPSHOT_PREFIX}${termCode}_${ScheduleSourceRouter.servedSource(ctx).key}"
        val old = prefs.getStringSet(storeKey, null)
        prefs.all.keys.filter { it.startsWith("snap_") }.takeIf { it.isNotEmpty() }?.let { stale ->
            prefs.edit().apply { stale.forEach(::remove) }.apply()
        }

        val groups = courses.groupBy(::keyOf)
        val now = groups.mapValues { (_, group) -> groupFingerprint(group) }
        val nowFlat = now.map { "${it.key}=>${it.value}" }.toSet()
        val nameOf = groups.mapValues { (_, group) -> group.first().courseName }

        prefs.edit().putStringSet(storeKey, nowFlat).apply()
        if (old == null) {
            Log.d(TAG, "$termCode 首次建立快照，${courses.size} 节，不报变更")
            return emptyList()
        }

        val oldMap = old.mapNotNull { entry ->
            val i = entry.indexOf("=>")
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 2)
        }.toMap()

        // 课程号 -> 官方调课备注。只有 jwapp 这次实际服务了才有内容；结构性 diff
        // 本身猜不出"为什么"，能对上号时就用这份官方原话代替猜测。
        val reasons = ScheduleSourceRouter.changeEvents(ctx, termCode)
            .filter { it.reason.isNotBlank() }
            .associate { it.courseCode to it.reason }

        val changes = buildList {
            for ((k, fp) in now) {
                val prev = oldMap[k]
                val name = nameOf[k] ?: continue
                val reason = reasons[k.substringBefore('|')]
                when {
                    prev == null -> add(Change(Change.Kind.ADDED, name, describeKey(k), reason))
                    prev != fp -> add(Change(Change.Kind.MOVED, name, describeDelta(prev, fp), reason))
                }
            }
            for (k in oldMap.keys - now.keys) {
                // 停掉的课在新表里已经没有名字了，只能报位置。
                add(Change(Change.Kind.REMOVED, "有课停了", describeKey(k), reasons[k.substringBefore('|')]))
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
        // 结构性 diff 只能报"变了什么"，报不出"为什么"；能对上官方备注时补一句原话。
        val withReason = first.reason?.takeIf { it.isNotBlank() }?.let { "$head，原因：$it" } ?: head
        return if (changes.size > 1) "$withReason，共 ${changes.size} 处改动" else withReason
    }

    private val DAY_NAMES = listOf("", "一", "二", "三", "四", "五", "六", "日")
    private const val SNAPSHOT_PREFIX = "snap2_"
    private val ROOM_WEEKS = Regex("""^(.*)\[([0-9,\-]+)]$""")

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
            if (a.getOrNull(0) != b.getOrNull(0)) add(describeRooms(a.getOrNull(0).orEmpty(), b.getOrNull(0).orEmpty()))
            if (a.getOrNull(1) != b.getOrNull(1)) add("上课周次有调整")
            if (a.getOrNull(2) != b.getOrNull(2)) add("下课节次变了")
            if (a.getOrNull(3) != b.getOrNull(3)) {
                add("教师 ${a.getOrNull(3).orEmpty().ifBlank { "?" }} → ${b.getOrNull(3).orEmpty().ifBlank { "?" }}")
            }
        }
        return parts.joinToString("；").ifBlank { "有改动" }
    }

    /**
     * 教室那一段的变化。拆成几条的课，教室写成 `主楼A-101[1-4]、主楼B-202[5]`；
     * 整串甩给用户看不懂，只报新出现的那几段："第5周改在主楼B-202"。
     */
    private fun describeRooms(old: String, new: String): String {
        fun parts(s: String) = s.split("、").filter { it.isNotBlank() }
        val oldParts = parts(old).toSet()
        val added = parts(new).filter { it !in oldParts }
        if ('[' !in old && '[' !in new) return "教室 ${old.ifBlank { "?" }} → ${new.ifBlank { "?" }}"
        val described = added.map { part ->
            ROOM_WEEKS.find(part)?.destructured
                ?.let { (room, weeks) -> "第${weeks.replace(",", "、")}周改在${room.ifBlank { "?" }}" }
                ?: "教室改为$part"
        }
        return described.joinToString("，").ifBlank { "教室安排有调整" }
    }
}
