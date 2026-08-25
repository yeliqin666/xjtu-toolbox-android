package com.xjtu.toolbox.social

import android.util.Base64
import com.xjtu.toolbox.schedule.CourseItem
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 课表匹配：把自己的作息压成一段分享码，跟朋友交换后在本地算契合度。
 *
 * ## 为什么是分享码而不是服务器
 *
 * 这个 App 没有后端，全部数据都来自校方系统。为了一个"看看跟谁课表合得来"的功能
 * 去搭一套账号体系和用户数据库，代价和风险都不成比例——那意味着我们要开始
 * **保管学生的课表**。分享码把交换这一步交回给用户：他给谁看、看多少，自己决定，
 * 我们一条都不存。
 *
 * ## 每一维都可选
 *
 * [Dimensions] 里每一项都能单独关掉，关掉的维度不进分享码，对方也就无从得知。
 * 默认只开课表相关的两项——那是这个功能的本体，其余都是加分项。
 */
object MatchProfile {

    /** 分享码版本。改了字段布局就加一，解码端据此拒绝旧码而不是解出乱数据。 */
    private const val VERSION = 2

    /** 一周 7 天 × 12 节的占用位图，用 84 个字符的 0/1 串表示。 */
    const val DAYS = 7
    const val SECTIONS = 12

    data class Dimensions(
        val schedule: Boolean = true,
        val sameCourses: Boolean = true,
        val identity: Boolean = true,
        val routine: Boolean = false,
        val diningHours: Boolean = false,
        val dietTags: Boolean = false,
    )

    data class Profile(
        val nickname: String = "",
        /** 84 位 0/1，'1' = 这一格有课。全空表示这一维没分享。 */
        val busyGrid: String = "",
        /** 课程号集合。用课程号不用课名：同一门课不同教学班的课名可能带后缀。 */
        val courseCodes: Set<String> = emptySet(),
        /** 最早一节课的节次、最晚一节课的结束节次；0 表示没分享。 */
        val earliestSection: Int = 0,
        val latestSection: Int = 0,
        /** 一天 24 小时里在食堂消费过的小时，0/1 串；空表示没分享。 */
        val diningHours: String = "",
        val dietTags: Set<String> = emptySet(),
        /** 入学年份，如 2023。0 表示没分享。 */
        val grade: Int = 0,
        val profession: String = "",
        val department: String = "",
        val academy: String = "",
        /** 校区。是硬门槛而不是加分项，见 [compare]。 */
        val campus: String = "",
        val className: String = "",
    )

    // ── 构建 ────────────────────────────────────────────────

    /**
     * 从本地数据攒一份档案。
     *
     * [courses] 传整学期的课，不按周过滤：匹配看的是"平时什么作息"，
     * 只按当前这一周算会被单双周课程带偏。
     */
    fun build(
        nickname: String,
        courses: List<CourseItem>,
        diningHourCounts: Map<Int, Int>,
        dietTags: Set<String>,
        profile: com.xjtu.toolbox.hello.HelloProfile?,
        dims: Dimensions,
    ): Profile {
        val grid = CharArray(DAYS * SECTIONS) { '0' }
        var earliest = 0
        var latest = 0
        for (c in courses) {
            if (c.dayOfWeek !in 1..DAYS) continue
            val from = c.startSection.coerceIn(1, SECTIONS)
            val to = c.endSection.coerceIn(from, SECTIONS)
            for (sec in from..to) grid[(c.dayOfWeek - 1) * SECTIONS + (sec - 1)] = '1'
            if (earliest == 0 || from < earliest) earliest = from
            if (to > latest) latest = to
        }
        return Profile(
            nickname = nickname.trim().take(12),
            busyGrid = if (dims.schedule) String(grid) else "",
            courseCodes = if (dims.sameCourses) {
                courses.mapNotNull { it.courseCode.trim().takeIf { c -> c.isNotEmpty() } }.toSet()
            } else emptySet(),
            earliestSection = if (dims.routine) earliest else 0,
            latestSection = if (dims.routine) latest else 0,
            diningHours = if (dims.diningHours) {
                // 只保留"经常"去的时段：偶尔一次不代表作息。阈值取该小时消费数 ≥ 2。
                String(CharArray(24) { h -> if ((diningHourCounts[h] ?: 0) >= 2) '1' else '0' })
            } else "",
            dietTags = if (dims.dietTags) dietTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else emptySet(),
            // 学号本身不进码：数字相邻只说明报到顺序。进去的是它派生出的有含义的那面。
            grade = if (dims.identity) profile?.grade ?: 0 else 0,
            profession = if (dims.identity) profile?.professionName.orEmpty() else "",
            department = if (dims.identity) profile?.departmentName.orEmpty() else "",
            academy = if (dims.identity) profile?.academyName.orEmpty() else "",
            campus = if (dims.identity) profile?.campusName.orEmpty() else "",
            className = if (dims.identity) profile?.className.orEmpty() else "",
        )
    }

    // ── 编解码 ──────────────────────────────────────────────
    //
    // 字段用 '|' 分隔、集合内用 ',' 分隔，deflate 后 Base64（URL-safe、无换行）。
    // 压缩是必要的：84 位网格加几十个课程号，明文有好几百字符，粘不进聊天框。

    fun encode(p: Profile): String {
        val raw = listOf(
            VERSION.toString(),
            p.nickname,
            p.busyGrid,
            p.courseCodes.sorted().joinToString(","),
            p.earliestSection.toString(),
            p.latestSection.toString(),
            p.diningHours,
            p.dietTags.sorted().joinToString(","),
            p.grade.toString(),
            p.profession,
            p.department,
            p.academy,
            p.campus,
            p.className,
        ).joinToString("|")
        val input = raw.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArray(input.size + 64)
        val n = deflater.deflate(out)
        deflater.end()
        return Base64.encodeToString(
            out.copyOf(n),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
    }

    /** 解不出就返回 null，由调用方提示"这个码不对"，不要抛给用户一个异常。 */
    fun decode(code: String): Profile? = try {
        val bytes = Base64.decode(
            code.trim(),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
        val inflater = Inflater()
        inflater.setInput(bytes)
        val buf = ByteArray(8192)
        val n = inflater.inflate(buf)
        inflater.end()
        val parts = String(buf, 0, n, Charsets.UTF_8).split("|")
        if (parts.size < 14 || parts[0].toIntOrNull() != VERSION) {
            null
        } else {
            Profile(
                nickname = parts[1],
                busyGrid = parts[2].takeIf { it.length == DAYS * SECTIONS }.orEmpty(),
                courseCodes = parts[3].split(",").filter { it.isNotBlank() }.toSet(),
                earliestSection = parts[4].toIntOrNull() ?: 0,
                latestSection = parts[5].toIntOrNull() ?: 0,
                diningHours = parts[6].takeIf { it.length == 24 }.orEmpty(),
                dietTags = parts[7].split(",").filter { it.isNotBlank() }.toSet(),
                grade = parts[8].toIntOrNull() ?: 0,
                profession = parts[9],
                department = parts[10],
                academy = parts[11],
                campus = parts[12],
                className = parts[13],
            )
        }
    } catch (_: Exception) {
        null
    }

    // ── 打分 ────────────────────────────────────────────────

    data class Facet(val label: String, val score: Int, val detail: String)

    data class Result(
        val overall: Int,
        val facets: List<Facet>,
        /**
         * 拦路的硬条件，比如不同校区。不并进百分比——共同空闲 90% 但一个兴庆一个创新港，
         * 该说的是"约不上"，不是给这个数打个折。
         */
        val blocker: String? = null,
        /** 不打分、只陈述的事实：同专业、同书院、差几届。 */
        val notes: List<String> = emptyList(),
    )

    /**
     * 逐维打分再取平均。
     *
     * **只对双方都分享了的维度打分**——一方关掉某项时那一维不参与，
     * 而不是记 0 分。否则"对方比较注重隐私"会被算成"你俩不合"。
     */
    fun compare(mine: Profile, theirs: Profile): Result {
        val facets = buildList {
            if (mine.busyGrid.isNotEmpty() && theirs.busyGrid.isNotEmpty()) {
                // 共同空闲：两人都没课的格子数占总格子数的比例。
                var free = 0
                for (i in 0 until DAYS * SECTIONS) {
                    if (mine.busyGrid[i] == '0' && theirs.busyGrid[i] == '0') free++
                }
                val pct = free * 100 / (DAYS * SECTIONS)
                add(Facet("共同空闲", pct, "一周有 $free 个节次你俩都没课"))
            }
            if (mine.courseCodes.isNotEmpty() && theirs.courseCodes.isNotEmpty()) {
                val shared = mine.courseCodes intersect theirs.courseCodes
                // 用 Jaccard 而不是"共同数 / 我的课数"：后者课少的人天生占便宜。
                val union = (mine.courseCodes + theirs.courseCodes).size
                val pct = if (union == 0) 0 else shared.size * 100 / union
                add(
                    Facet(
                        "同课",
                        pct,
                        if (shared.isEmpty()) "没有共同课程" else "有 ${shared.size} 门共同课程",
                    )
                )
            }
            if (mine.earliestSection > 0 && theirs.earliestSection > 0) {
                // 早八差几节、末课差几节，各自最多差 11 节，转成相似度。
                val dEarly = kotlin.math.abs(mine.earliestSection - theirs.earliestSection)
                val dLate = kotlin.math.abs(mine.latestSection - theirs.latestSection)
                val pct = (100 - (dEarly + dLate) * 100 / (2 * (SECTIONS - 1))).coerceIn(0, 100)
                add(
                    Facet(
                        "作息",
                        pct,
                        "最早第 ${mine.earliestSection} / ${theirs.earliestSection} 节，" +
                            "最晚第 ${mine.latestSection} / ${theirs.latestSection} 节",
                    )
                )
            }
            if (mine.diningHours.isNotEmpty() && theirs.diningHours.isNotEmpty()) {
                var both = 0
                var either = 0
                for (i in 0 until 24) {
                    val a = mine.diningHours[i] == '1'
                    val b = theirs.diningHours[i] == '1'
                    if (a && b) both++
                    if (a || b) either++
                }
                val pct = if (either == 0) 0 else both * 100 / either
                add(Facet("饭点", pct, if (both == 0) "吃饭时间基本错开" else "有 $both 个时段常同时在食堂"))
            }
            if (mine.dietTags.isNotEmpty() && theirs.dietTags.isNotEmpty()) {
                val shared = mine.dietTags intersect theirs.dietTags
                val union = (mine.dietTags + theirs.dietTags).size
                val pct = if (union == 0) 0 else shared.size * 100 / union
                add(
                    Facet(
                        "口味",
                        pct,
                        if (shared.isEmpty()) "没有共同口味标签"
                        else "都喜欢：${shared.joinToString("、")}",
                    )
                )
            }
        }
        // 身份只陈述不打分："同专业"是事实不是契合度，折算成百分比只会稀释有用的那几维。
        val notes = buildList {
            if (mine.className.isNotBlank() && mine.className == theirs.className) {
                add("同班（${mine.className}）")
            } else if (mine.profession.isNotBlank() && mine.profession == theirs.profession) {
                add("同专业（${mine.profession}）")
            } else if (mine.department.isNotBlank() && mine.department == theirs.department) {
                add("同学院（${mine.department}）")
            }
            if (mine.academy.isNotBlank() && mine.academy == theirs.academy) {
                add("同书院（${mine.academy}）")
            }
            if (mine.grade > 0 && theirs.grade > 0) {
                val d = kotlin.math.abs(mine.grade - theirs.grade)
                add(if (d == 0) "同级（${mine.grade} 级）" else "差 $d 届")
            }
        }

 // 校区：硬门槛。
        val blocker = if (
            mine.campus.isNotBlank() && theirs.campus.isNotBlank() && mine.campus != theirs.campus
        ) {
            "你在${mine.campus}，${theirs.nickname.ifBlank { "对方" }}在${theirs.campus}，约起来不方便"
        } else {
            null
        }

        val overall = if (facets.isEmpty()) 0 else facets.sumOf { it.score } / facets.size
        return Result(overall, facets, blocker, notes)
    }
}
