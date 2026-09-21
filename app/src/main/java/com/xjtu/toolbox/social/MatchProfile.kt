package com.xjtu.toolbox.social

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 匹配交友：把自己的作息压成一段分享码，跟朋友交换后在本地算契合度。
 *
 * ## 为什么是分享码而不是服务器
 *
 * 这个 App 没有后端，全部数据都来自校方系统。为了一个"看看跟谁课表合得来"的功能
 * 去搭一套账号体系和用户数据库，代价和风险都不成比例——那意味着我们要开始
 * **保管学生的课表**。分享码把交换这一步交回给用户：他给谁看、看多少，自己决定，
 * 我们一条都不存。
 *
 * ## 分数和发现是两回事
 *
 * 早先的版本把所有维度都折成百分比再平均。于是"共同课程"这种在陌生人之间天然
 * 接近 0 的交集，会把一个本来有意义的契合度拖成谁都在 20 分上下的数。
 *
 * 现在分两层：
 * - [Facet]：**打分**。只放共同空闲、作息、饭点这类比例型指标——它们本来就在
 *   0-100 之间铺得开，平均起来有意义，回答的是"约不约得上、过得像不像"。
 * - [Discovery]：**不打分**。同课、同楼、同老师、同考试、同教材这些是发现，
 *   有就说出名字，比说百分之几有意思；没有也不该扣分。
 *
 * ## 每一维都可选
 *
 * [Dimensions] 里每一项都能单独关掉，关掉的维度不进分享码，对方也就无从得知。
 * 关掉之外还有一层：**没有数据的维度也不进码**。早先的版本只看开关不看数据，
 * 没读到课表时照样塞一张全空的网格出去，对方算出来是"共同空闲 100%"——
 * 一个纯属虚构的满分。现在两个条件都要满足，见 [build]。
 *
 * ## 这个对象不碰 Android
 *
 * 编解码走 `java.util.Base64`（minSdk 31 远高于它要求的 26）而不是 `android.util.Base64`，
 * 取数交给 [MatchData]，打分逻辑因此能在普通 JVM 单元测试里跑，见 `MatchProfileTest`。
 */
object MatchProfile {

    /** 分享码版本。改了字段布局就加一，解码端据此拒绝旧码而不是解出乱数据。 */
    private const val VERSION = 5

    /** 一周 7 天 × 11 节的占用位图，用 77 个字符的 0/1 串表示。 */
    const val DAYS = 7

    /**
     * 每天 11 节。这个数要跟 [com.xjtu.toolbox.util.XjtuTime] 的作息表对齐——
     * 之前写的 12 会凭空多出一列谁都没课的格子，把"共同空闲"整体抬高 1/12。
     */
    const val SECTIONS = 11

    /**
     * 打分只看周一到周五。
     *
     * 周末两个人都空是常态，算进去等于给所有人加同一笔分，反而把"作息合拍"和
     * "刚好都没课"压成同一个数。周末照样进网格供界面展示，只是不参与百分比。
     */
    const val WEEKDAYS = 5

    private const val CELLS = DAYS * SECTIONS

    /** 教学楼网格里"这一格没课"的占位符。 */
    private const val NO_BUILDING = '.'

    /** 教学楼用 base36 下标编进网格，最多 36 栋——一个人一学期不会在这么多楼里上课。 */
    private const val MAX_BUILDINGS = 36

    /** 解压上限。分享码来自别人，别让一段几百字节的输入膨胀成几十兆。 */
    private const val MAX_INFLATED = 256 * 1024

    val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    data class Dimensions(
        val schedule: Boolean = true,
        val sameCourses: Boolean = true,
        val buildings: Boolean = true,
        val teachers: Boolean = true,
        val pastCourses: Boolean = true,
        val exams: Boolean = true,
        val textbooks: Boolean = false,
        val identity: Boolean = true,
        val diningHours: Boolean = false,
        val canteens: Boolean = false,
        val dietTags: Boolean = false,
    )

    /** 共享的课程条目。课程号用来判同课，课名只为了让结果能写出"你俩都上《大学物理》"。 */
    data class SharedCourse(val code: String, val name: String)

    /** 共享的考试。日期用 ISO 串原样带过去，解析失败就当没有日期。 */
    data class SharedExam(val code: String, val name: String, val date: String)

    data class Profile(
        val nickname: String = "",
        /** 77 位 0/1，'1' = 这一格有课。空串表示这一维没分享。 */
        val busyGrid: String = "",
        /** 课程号 + 课名。用课程号判同课：同一门课不同教学班的课名可能带后缀。 */
        val courses: List<SharedCourse> = emptyList(),
        /** 77 位，每位是 [buildings] 的 base36 下标，'.' 表示这一格没课。 */
        val buildingGrid: String = "",
        val buildings: List<String> = emptyList(),
        val teachers: Set<String> = emptySet(),
        /** 往期学期的课程号。只有号没有名，见 [MatchData]。 */
        val pastCourseCodes: Set<String> = emptySet(),
        val exams: List<SharedExam> = emptyList(),
        val textbooks: Set<String> = emptySet(),
        /** 一天 24 小时里在食堂消费过的小时，0/1 串；空表示没分享。 */
        val diningHours: String = "",
        val canteens: Set<String> = emptySet(),
        val dietTags: Set<String> = emptySet(),
        /** 入学年份，如 2023。0 表示没分享。 */
        val grade: Int = 0,
        val profession: String = "",
        val department: String = "",
        val academy: String = "",
        /** 校区。是硬门槛而不是加分项，见 [compare]。 */
        val campus: String = "",
        val className: String = "",
        /** 生源地省份，从学号推——见 [com.xjtu.toolbox.util.ProvinceCode]，跟屁岱画像同一份映射。 */
        val province: String = "",
    ) {
        val courseCodes: Set<String> get() = courses.map { it.code }.toSet()

        /**
         * 真正写进码里的维度数。
         *
         * 数的是"有内容"而不是"开关开着"：开了但本机没数据的项不算，
         * 否则界面上写着分享了 9 项、对方却什么都收不到。
         */
        val sharedCount: Int
            get() = listOf(
                busyGrid.isNotEmpty(),
                courses.isNotEmpty(),
                buildingGrid.isNotEmpty(),
                teachers.isNotEmpty(),
                pastCourseCodes.isNotEmpty(),
                exams.isNotEmpty(),
                textbooks.isNotEmpty(),
                diningHours.isNotEmpty(),
                canteens.isNotEmpty(),
                dietTags.isNotEmpty(),
                grade > 0 || campus.isNotEmpty() || profession.isNotEmpty() || province.isNotEmpty(),
            ).count { it }
    }

    // ── 构建 ────────────────────────────────────────────────

    /**
     * 字段分隔符会出现在昵称、课名这些自由文本里。
     *
     * 昵称里打一个 `|` 就能把后面每一个字段错位一格，对方解出来的年级、校区全是别的东西。
     * 与其在解码端猜，不如在进码之前把三个分隔符换成空格。
     */
    private fun clean(s: String): String =
        s.replace('|', ' ').replace(',', ' ').replace('~', ' ')
            .replace(Regex("""\s+"""), " ").trim()

    /**
     * 从教室名里认出教学楼。
     *
     * 跟商户名一样的问题：「中2-2101」「中2 2101」「中2楼2101」是同一栋楼，逐字比对永远对不上。
     * 砍掉连字符之后的部分，再砍掉结尾的房间号——只砍三位以上的数字，"中2" 里那个 2 得留着。
     */
    fun buildingOf(location: String): String? {
        val head = location.substringBefore('-')
            .substringBefore('(').substringBefore('（')
            .trim()
            .replace(Regex("""\s*\d{3,}\s*$"""), "")
            .trim()
        return head.takeIf { it.length in 1..8 }
    }

    /**
     * 从本地缓存攒一份档案。
     *
     * [MatchData.Local.courses] 是整学期的课，不按周过滤：匹配看的是"平时什么作息"，
     * 只按当前这一周算会被单双周课程带偏。
     */
    fun build(
        local: MatchData.Local,
        nickname: String = "",
        dietTags: Set<String> = emptySet(),
        dims: Dimensions = Dimensions(),
    ): Profile {
        val courses = local.courses
        val grid = CharArray(CELLS) { '0' }
        val buildingCells = CharArray(CELLS) { NO_BUILDING }
        val buildingList = ArrayList<String>()
        for (c in courses) {
            if (c.dayOfWeek !in 1..DAYS) continue
            val from = c.startSection.coerceIn(1, SECTIONS)
            val to = c.endSection.coerceIn(from, SECTIONS)
            val building = buildingOf(clean(c.location))
            val idx = building?.let {
                val at = buildingList.indexOf(it)
                when {
                    at >= 0 -> at
                    buildingList.size < MAX_BUILDINGS -> { buildingList.add(it); buildingList.size - 1 }
                    else -> -1
                }
            } ?: -1
            for (sec in from..to) {
                val cell = (c.dayOfWeek - 1) * SECTIONS + (sec - 1)
                grid[cell] = '1'
                // 一格里挤了两门课时后写的赢。这种重课本来就是教务的数据问题，
                // 挑哪一门都不比另一门更对，不值得为它多一层结构。
                if (idx >= 0) buildingCells[cell] = b36(idx)
            }
        }

        // 每一维都是「用户开了」且「确实有数据」才进码。缺了后半句就会分享出
        // 一张全空网格 / 一串全 0 的饭点，对方那边看不出是"没有"还是"全天有空"。
        val hasSchedule = courses.any { it.dayOfWeek in 1..DAYS }
        val hasBuildings = buildingList.isNotEmpty()
        val diningBits = String(CharArray(24) { h ->
            // 只保留"经常"去的时段：偶尔一次不代表作息。阈值取该小时消费数 ≥ 2。
            if ((local.diningHourCounts[h] ?: 0) >= 2) '1' else '0'
        })

        return Profile(
            nickname = clean(nickname).take(12),
            busyGrid = if (dims.schedule && hasSchedule) String(grid) else "",
            courses = if (dims.sameCourses) {
                courses.mapNotNull { c ->
                    val code = clean(c.courseCode)
                    if (code.isEmpty()) null else SharedCourse(code, clean(c.courseName).take(14))
                }.distinctBy { it.code }.sortedBy { it.code }
            } else emptyList(),
            buildingGrid = if (dims.buildings && hasBuildings) String(buildingCells) else "",
            buildings = if (dims.buildings && hasBuildings) buildingList.toList() else emptyList(),
            teachers = if (dims.teachers) {
                courses.mapNotNull { clean(it.teacher).takeIf { t -> t.length in 2..12 } }.toSet()
            } else emptySet(),
            pastCourseCodes = if (dims.pastCourses) {
                local.pastCourseCodes.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
            } else emptySet(),
            exams = if (dims.exams) {
                local.exams.mapNotNull { e ->
                    val code = clean(e.courseCode)
                    val date = clean(e.examDate)
                    if (code.isEmpty() || date.isEmpty()) null
                    else SharedExam(code, clean(e.courseName).take(14), date)
                }.distinctBy { it.code + it.date }
            } else emptyList(),
            textbooks = if (dims.textbooks) {
                local.textbooks.map { clean(it).take(20) }.filter { it.isNotEmpty() }.toSet()
            } else emptySet(),
            diningHours = if (dims.diningHours && diningBits.contains('1')) diningBits else "",
            canteens = if (dims.canteens) {
                local.canteens.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
            } else emptySet(),
            dietTags = if (dims.dietTags) {
                dietTags.map { clean(it) }.filter { it.isNotEmpty() }.toSet()
            } else emptySet(),
            // 学号本身不进码：数字相邻只说明报到顺序。进去的是它派生出的有含义的那面。
            grade = if (dims.identity) local.profile?.grade ?: 0 else 0,
            profession = if (dims.identity) clean(local.profile?.professionName.orEmpty()) else "",
            department = if (dims.identity) clean(local.profile?.departmentName.orEmpty()) else "",
            academy = if (dims.identity) clean(local.profile?.academyName.orEmpty()) else "",
            campus = if (dims.identity) clean(local.profile?.campusName.orEmpty()) else "",
            className = if (dims.identity) clean(local.profile?.className.orEmpty()) else "",
            province = if (dims.identity) {
                local.profile?.sno?.let { com.xjtu.toolbox.util.ProvinceCode.of(it) }.orEmpty()
            } else "",
        )
    }

    private fun b36(i: Int): Char = if (i < 10) '0' + i else 'a' + (i - 10)

    private fun unb36(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        else -> -1
    }

    // ── 编解码 ──────────────────────────────────────────────
    //
    // 字段用 '|' 分隔、集合内用 ',' 分隔、条目内的子字段用 '~'，
    // deflate 后 Base64（URL-safe、无填充）。压缩是必要的：两张 77 位网格加上课程、
    // 老师、考试、教材，明文有好几千字符，粘不进聊天框。

    fun encode(p: Profile): String {
        val raw = listOf(
            VERSION.toString(),
            p.nickname,
            p.busyGrid,
            p.courses.joinToString(",") { "${it.code}~${it.name}" },
            p.buildingGrid,
            p.buildings.joinToString(","),
            p.teachers.sorted().joinToString(","),
            p.pastCourseCodes.sorted().joinToString(","),
            p.exams.joinToString(",") { "${it.code}~${it.name}~${it.date}" },
            p.textbooks.sorted().joinToString(","),
            p.diningHours,
            p.canteens.sorted().joinToString(","),
            p.dietTags.sorted().joinToString(","),
            p.grade.toString(),
            p.profession,
            p.department,
            p.academy,
            p.campus,
            p.className,
            p.province,
        ).joinToString("|")
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(raw))
    }

    /** 解不出就返回 null，由调用方提示"这个码不对"，不要抛给用户一个异常。 */
    fun decode(code: String): Profile? = try {
        // 聊天软件会给长串自动折行，粘回来带换行和空格。先全部去掉再解。
        val compact = code.filterNot { it.isWhitespace() }
        val parts = inflate(java.util.Base64.getUrlDecoder().decode(compact)).split("|")
        if (parts.size < 20 || parts[0].toIntOrNull() != VERSION) {
            null
        } else {
            val buildings = parts[5].split(",").filter { it.isNotBlank() }
            Profile(
                nickname = parts[1],
                busyGrid = parts[2].takeIf { s -> s.length == CELLS && s.all { it == '0' || it == '1' } }
                    .orEmpty(),
                courses = parts[3].splitEntries { fields ->
                    fields[0].takeIf { it.isNotBlank() }
                        ?.let { SharedCourse(it, fields.getOrElse(1) { "" }) }
                }.distinctBy { it.code },
                // 网格里的下标必须在楼名表里找得到，否则整张网格作废：
                // 一个越界下标会让"同楼"判成随便哪栋楼。
                buildingGrid = parts[4].takeIf { s ->
                    s.length == CELLS && buildings.isNotEmpty() &&
                        s.all { it == NO_BUILDING || unb36(it) in buildings.indices }
                }.orEmpty(),
                buildings = buildings,
                teachers = parts[6].split(",").filter { it.isNotBlank() }.toSet(),
                pastCourseCodes = parts[7].split(",").filter { it.isNotBlank() }.toSet(),
                exams = parts[8].splitEntries { fields ->
                    if (fields.size < 3 || fields[0].isBlank()) null
                    else SharedExam(fields[0], fields[1], fields[2])
                }.distinctBy { it.code + it.date },
                textbooks = parts[9].split(",").filter { it.isNotBlank() }.toSet(),
                diningHours = parts[10].takeIf { it.length == 24 }.orEmpty(),
                canteens = parts[11].split(",").filter { it.isNotBlank() }.toSet(),
                dietTags = parts[12].split(",").filter { it.isNotBlank() }.toSet(),
                grade = parts[13].toIntOrNull() ?: 0,
                profession = parts[14],
                department = parts[15],
                academy = parts[16],
                campus = parts[17],
                className = parts[18],
                province = parts[19],
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun <T> String.splitEntries(map: (List<String>) -> T?): List<T> =
        split(",").mapNotNull { entry ->
            if (entry.isBlank()) null else map(entry.split("~"))
        }

    private fun deflate(raw: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        // 循环写：一门课的课名就有十几字节，几十门课早就超过任何"够用"的固定缓冲区，
        // 单次 deflate 写不完会静默截断，生成一段解不开的码。
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished() && out.size() <= MAX_INFLATED) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ── 结果 ────────────────────────────────────────────────

    /** 打分项。只放比例型指标，见类文档。 */
    data class Facet(
        val label: String,
        val emoji: String,
        val score: Int,
        val detail: String,
        /** 权重。共同空闲是这个功能的本体，饭点只是佐料，不该等权平均。 */
        val weight: Int,
    )

    /** 不打分的交集。有就说出来，没有就不出现，也不扣分。 */
    data class Discovery(
        val emoji: String,
        val title: String,
        val items: List<String> = emptyList(),
        /** 越小越靠前。同场考试比同口味稀罕，也更值得先看到。 */
        val rank: Int,
    )

    /** 一段两人都空的连续时间。[day] 从 0 起（周一），[from]/[to] 是 1 起的节次，闭区间。 */
    data class FreeBlock(val day: Int, val from: Int, val to: Int) {
        val length: Int get() = to - from + 1
    }

    /** 一段两人都有课、而且在同一栋楼的连续时间。 */
    data class Encounter(val day: Int, val from: Int, val to: Int, val building: String) {
        val length: Int get() = to - from + 1
    }

    /** 叠加网格的每一格。 */
    object Cell {
        const val BOTH_BUSY = '0'
        const val ONE_FREE = '1'
        const val BOTH_FREE = '2'

        /** 两人都有课，而且在同一栋楼。比"都有课"更值得画出来。 */
        const val SAME_BUILDING = '3'
    }

    data class Result(
        val overall: Int,
        /** 一句给分数配的话，比光秃秃一个百分比好读。 */
        val verdict: String,
        val facets: List<Facet>,
        val discoveries: List<Discovery>,
        /** 77 位，取值见 [Cell]；两人有一方没分享课表时为 null。 */
        val overlapGrid: String? = null,
        /** 工作日里两人都空的连续时段，长的在前。 */
        val freeBlocks: List<FreeBlock> = emptyList(),
        /** 工作日里两人同楼上课的时段。 */
        val encounters: List<Encounter> = emptyList(),
        /**
         * 拦路的硬条件，比如不同校区。不并进百分比——共同空闲 90% 但一个兴庆一个创新港，
         * 该说的是"约不上"，不是给这个数打个折。
         */
        val blocker: String? = null,
        /** 不打分、只陈述的事实：同专业、同书院、差几届。 */
        val notes: List<String> = emptyList(),
    ) {
        /** 有没有算出分数。只有交集、没有课表时，界面该展示发现而不是一个 0 分的圆环。 */
        val scored: Boolean get() = facets.isNotEmpty()
        val empty: Boolean get() = facets.isEmpty() && discoveries.isEmpty()
    }

    /**
     * 逐维打分再按权重平均，另外收集所有交集。
     *
     * **只对双方都分享了的维度打分**——一方关掉某项时那一维不参与，
     * 而不是记 0 分。否则"对方比较注重隐私"会被算成"你俩不合"。
     */
    fun compare(mine: Profile, theirs: Profile): Result {
        val them = theirs.nickname.ifBlank { "对方" }
        var overlapGrid: String? = null
        var blocks = emptyList<FreeBlock>()
        var meetings = emptyList<Encounter>()

        val bothHaveGrid = mine.busyGrid.length == CELLS && theirs.busyGrid.length == CELLS

        // ── 打分 ──
        val facets = buildList {
            if (bothHaveGrid) {
                val sameBuilding = sameBuildingCells(mine, theirs)
                val cells = String(CharArray(CELLS) { i ->
                    val mineFree = mine.busyGrid[i] == '0'
                    val theirsFree = theirs.busyGrid[i] == '0'
                    when {
                        mineFree && theirsFree -> Cell.BOTH_FREE
                        mineFree || theirsFree -> Cell.ONE_FREE
                        sameBuilding != null && sameBuilding[i] -> Cell.SAME_BUILDING
                        else -> Cell.BOTH_BUSY
                    }
                })
                overlapGrid = cells
                blocks = freeBlocks(cells)
                meetings = encounters(mine, theirs)

                var bothFree = 0
                var myFree = 0
                var theirFree = 0
                for (d in 0 until WEEKDAYS) for (s in 0 until SECTIONS) {
                    val i = d * SECTIONS + s
                    val a = mine.busyGrid[i] == '0'
                    val b = theirs.busyGrid[i] == '0'
                    if (a) myFree++
                    if (b) theirFree++
                    if (a && b) bothFree++
                }
                // 分母取两人空闲的较小值，也就是"理论上最多能重合多少"。
                // 用总格子数当分母的话，人人都是七成起步，分不出高下；
                // 用它当分母，问的才是「忙的那个人的空档，另一个人在不在」。
                val ceiling = minOf(myFree, theirFree)
                val pct = if (ceiling == 0) 0 else (bothFree * 100 / ceiling).coerceIn(0, 100)
                val longest = blocks.firstOrNull()
                add(
                    Facet(
                        "共同空闲", "🕒", pct,
                        if (bothFree == 0) "工作日没有哪一节是你俩同时空的"
                        else buildString {
                            append("工作日有 $bothFree 个节次两个人都空着")
                            if (longest != null) {
                                append("，最长的一段是${DAY_NAMES[longest.day]} ")
                                append("${longest.from}-${longest.to} 节")
                            }
                        },
                        weight = 3,
                    )
                )

                // 作息不单独占一个开关：早八几点、末课几点，分享课表的人已经把这些都给出去了，
                // 再摆一个开关只是隐私剧场。直接从网格推，还能推出更有意思的东西。
                val a = dayParts(mine.busyGrid)
                val b = dayParts(theirs.busyGrid)
                if (a.sum() > 0 && b.sum() > 0) {
                    add(
                        Facet(
                            "作息", "🌗", cosine(a, b),
                            "你${chronotype(a)}，$them${chronotype(b)}",
                            weight = 2,
                        )
                    )
                }
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
                add(
                    Facet(
                        "饭点", "🍜", pct,
                        if (both == 0) "饭点基本错开" else "有 $both 个饭点你俩常同时在食堂",
                        weight = 1,
                    )
                )
            }
        }

        // ── 发现 ──
        val discoveries = buildList {
            val sharedExams = examOverlap(mine, theirs)
            if (sharedExams.isNotEmpty()) {
                add(
                    Discovery(
                        "📝", "${sharedExams.size} 场考试撞在同一天",
                        sharedExams.map { (name, date) ->
                            listOfNotNull(name.ifBlank { null }, prettyDate(date)).joinToString(" · ")
                        },
                        rank = 0,
                    )
                )
            }
            if (meetings.isNotEmpty()) {
                add(
                    Discovery(
                        "👀", "每周 ${meetings.size} 次待在同一栋楼",
                        meetings.groupBy { it.building }
                            .entries.sortedByDescending { it.value.size }
                            .map { (building, list) ->
                                val e = list.first()
                                "$building · ${DAY_NAMES[e.day]}第 ${e.from}-${e.to} 节" +
                                    if (list.size > 1) " 等 ${list.size} 次" else ""
                            },
                        rank = 1,
                    )
                )
            }
            val sharedCodes = mine.courseCodes intersect theirs.courseCodes
            if (sharedCodes.isNotEmpty()) {
                add(
                    Discovery(
                        "📚", "一起上 ${sharedCodes.size} 门课",
                        mine.courses.filter { it.code in sharedCodes }
                            .map { it.name.ifBlank { it.code } },
                        rank = 2,
                    )
                )
            }
            val sharedTeachers = mine.teachers intersect theirs.teachers
            if (sharedTeachers.isNotEmpty()) {
                add(Discovery("🧑‍🏫", "被同 ${sharedTeachers.size} 位老师教过", sharedTeachers.sorted(), rank = 3))
            }
            // 往期只带课程号，说得出"几门"说不出"哪门"——这一维的分享码因此便宜得多。
            val sharedPast = mine.pastCourseCodes intersect theirs.pastCourseCodes
            if (sharedPast.isNotEmpty()) {
                add(Discovery("🕰️", "以前还一起上过 ${sharedPast.size} 门课", rank = 4))
            }
            val sharedBooks = mine.textbooks intersect theirs.textbooks
            if (sharedBooks.isNotEmpty()) {
                add(Discovery("📖", "有 ${sharedBooks.size} 本教材是一样的", sharedBooks.sorted(), rank = 5))
            }
            val sharedCanteens = mine.canteens intersect theirs.canteens
            if (sharedCanteens.isNotEmpty()) {
                add(Discovery("🍚", "常去同一个食堂", sharedCanteens.sorted(), rank = 6))
            }
            val sharedTags = mine.dietTags intersect theirs.dietTags
            if (sharedTags.isNotEmpty()) {
                add(Discovery("🌶️", "口味对上了", sharedTags.sorted(), rank = 7))
            }
            lastExamNote(mine, theirs, them)?.let { add(Discovery("🏁", it, rank = 8)) }
        }.sortedBy { it.rank }

        // 身份只陈述不打分："同专业"是事实不是契合度，折算成百分比只会稀释有用的那几维。
        val notes = buildList {
            if (mine.className.isNotBlank() && mine.className == theirs.className) {
                add("同班 · ${mine.className}")
            } else if (mine.profession.isNotBlank() && mine.profession == theirs.profession) {
                add("同专业 · ${mine.profession}")
            } else if (mine.department.isNotBlank() && mine.department == theirs.department) {
                add("同学院 · ${mine.department}")
            }
            if (mine.academy.isNotBlank() && mine.academy == theirs.academy) {
                add("同书院 · ${mine.academy}")
            }
            if (mine.grade > 0 && theirs.grade > 0) {
                val d = kotlin.math.abs(mine.grade - theirs.grade)
                add(if (d == 0) "同级 · ${mine.grade} 级" else "差 $d 届")
            }
            if (mine.province.isNotBlank() && mine.province == theirs.province) {
                add("老乡 · ${mine.province}")
            }
        }

        // 校区：硬门槛。
        val blocker = if (
            mine.campus.isNotBlank() && theirs.campus.isNotBlank() && mine.campus != theirs.campus
        ) {
            "你在${mine.campus}，${them}在${theirs.campus}，见一面不容易"
        } else {
            null
        }

        val totalWeight = facets.sumOf { it.weight }
        val overall = if (totalWeight == 0) 0 else facets.sumOf { it.score * it.weight } / totalWeight
        return Result(
            overall = overall,
            verdict = verdict(overall, facets.isEmpty()),
            facets = facets,
            discoveries = discoveries,
            overlapGrid = overlapGrid,
            freeBlocks = blocks,
            encounters = meetings,
            blocker = blocker,
            notes = notes,
        )
    }

    // ── 各维的算法 ──────────────────────────────────────────

    /** 工作日里连续两节及以上的共同空档，长的排前面；一样长的按周一到周五、从早到晚。 */
    fun freeBlocks(overlap: String, minLength: Int = 2): List<FreeBlock> =
        runs(minLength) { d, s -> if (overlap[d * SECTIONS + s] == Cell.BOTH_FREE) "" else null }
            .map { FreeBlock(it.day, it.from, it.to) }
            .sortedWith(compareByDescending<FreeBlock> { it.length }.thenBy { it.day }.thenBy { it.from })

    /** 两人都有课、而且在同一栋楼的时段。这是"擦肩而过"的那些格子。 */
    fun encounters(mine: Profile, theirs: Profile): List<Encounter> {
        if (mine.buildingGrid.length != CELLS || theirs.buildingGrid.length != CELLS) return emptyList()
        return runs(1) { d, s ->
            val i = d * SECTIONS + s
            val a = mine.buildingGrid[i]
            val b = theirs.buildingGrid[i]
            if (a == NO_BUILDING || b == NO_BUILDING) return@runs null
            val an = mine.buildings.getOrNull(unb36(a))
            if (an != null && an == theirs.buildings.getOrNull(unb36(b))) an else null
        }.map { Encounter(it.day, it.from, it.to, it.tag) }
            .sortedWith(compareByDescending<Encounter> { it.length }.thenBy { it.day }.thenBy { it.from })
    }

    private data class Run(val day: Int, val from: Int, val to: Int, val tag: String)

    /**
     * 逐天扫出连续区段。[cellTag] 返回 null 表示这一格不算，返回的字符串是分段依据——
     * 同楼的区段换了一栋楼就该断开，所以标签变了也算断点。
     */
    private inline fun runs(minLength: Int, cellTag: (day: Int, section: Int) -> String?): List<Run> =
        buildList {
            for (d in 0 until WEEKDAYS) {
                var start = -1
                // 用 null 而不是空串表示"当前没有区段"：共同空闲那一维的标签本来就是空串，
                // 拿空串当哨兵的话第一格永远被当成"跟上一格同段"，一天下来一段都扫不出。
                var tag: String? = null
                for (s in 0..SECTIONS) {
                    val here = if (s < SECTIONS) cellTag(d, s) else null
                    if (here != tag) {
                        if (tag != null && s - start >= minLength) add(Run(d, start + 1, s, tag))
                        start = if (here == null) -1 else s
                        tag = here
                    }
                }
            }
        }

    /** 哪些格子两人同楼。给叠加网格上色用，没分享教学楼时返回 null。 */
    private fun sameBuildingCells(mine: Profile, theirs: Profile): BooleanArray? {
        if (mine.buildingGrid.length != CELLS || theirs.buildingGrid.length != CELLS) return null
        return BooleanArray(CELLS) { i ->
            val a = mine.buildings.getOrNull(unb36(mine.buildingGrid[i]))
            val b = theirs.buildings.getOrNull(unb36(theirs.buildingGrid[i]))
            a != null && a == b
        }
    }

    /** 同一门课、同一天考的，才算撞在一起。返回课名与日期。 */
    private fun examOverlap(mine: Profile, theirs: Profile): List<Pair<String, String>> {
        if (mine.exams.isEmpty() || theirs.exams.isEmpty()) return emptyList()
        val theirKeys = theirs.exams.map { it.code to it.date }.toSet()
        return mine.exams
            .filter { (it.code to it.date) in theirKeys }
            .map { it.name to it.date }
            .distinct()
    }

    /** 谁先考完。放假早几天是学生之间最实在的攀比之一。 */
    private fun lastExamNote(mine: Profile, theirs: Profile, them: String): String? {
        val a = mine.exams.mapNotNull { parseDate(it.date) }.maxOrNull() ?: return null
        val b = theirs.exams.mapNotNull { parseDate(it.date) }.maxOrNull() ?: return null
        val gap = java.time.temporal.ChronoUnit.DAYS.between(a, b).toInt()
        return when {
            gap == 0 -> "同一天考完，${prettyDate(a.toString())}一起放假"
            gap > 0 -> "你早 $gap 天考完，${prettyDate(a.toString())}就走人"
            else -> "${them}早 ${-gap} 天考完，你还得留到${prettyDate(a.toString())}"
        }
    }

    private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()

    private fun prettyDate(s: String): String =
        parseDate(s)?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日" } ?: s

    /** 一周的课按上午（1-4）、下午（5-8）、晚上（9-11）分三堆。 */
    private fun dayParts(grid: String): IntArray {
        val parts = IntArray(3)
        for (d in 0 until WEEKDAYS) for (s in 0 until SECTIONS) {
            if (grid[d * SECTIONS + s] == '1') {
                parts[if (s < 4) 0 else if (s < 8) 1 else 2]++
            }
        }
        return parts
    }

    /** 两个三段分布的余弦相似度。方向一致就接近 100，一个全早八一个全晚课就接近 0。 */
    private fun cosine(a: IntArray, b: IntArray): Int {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0
        // 四舍五入而不是截断：sqrt(8)*sqrt(8) 在浮点下是 8.000000000000002，
        // 两份一模一样的课表算出来会是 99.999…，截断就成了 99 分。
        val cos = dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
        return kotlin.math.round(cos * 100).toInt().coerceIn(0, 100)
    }

    private fun chronotype(parts: IntArray): String {
        val morning = parts[0]
        val afternoon = parts[1]
        val night = parts[2]
        return when {
            night >= morning + afternoon -> "常年晚课"
            morning > afternoon + night -> "是早八战士"
            night > 0 && morning == 0 -> "从不早起"
            else -> "作息挺常规"
        }
    }

    private fun verdict(overall: Int, empty: Boolean): String = when {
        empty -> "只有交集，没有分数"
        overall >= 85 -> "课表像商量好的"
        overall >= 70 -> "随时能碰头"
        overall >= 55 -> "挤一挤总有时间"
        overall >= 40 -> "得提前约"
        overall >= 20 -> "错峰人生"
        else -> "活在两个平行时空"
    }

    /** 可以直接粘进聊天框的一段战报。 */
    fun summaryText(theirName: String, r: Result): String = buildString {
        if (r.scored) {
            appendLine("我和${theirName}的匹配度 ${r.overall} 分：${r.verdict}")
        } else {
            appendLine("我和${theirName}的课表交集")
        }
        r.blocker?.let { appendLine("⚠️ $it") }
        r.facets.forEach { appendLine("${it.emoji} ${it.label} ${it.score}% · ${it.detail}") }
        r.freeBlocks.take(3).forEach {
            appendLine("🕒 ${DAY_NAMES[it.day]} ${it.from}-${it.to} 节都有空")
        }
        r.discoveries.forEach { d ->
            append(d.emoji).append(' ').append(d.title)
            if (d.items.isNotEmpty()) append("：").append(d.items.take(4).joinToString("、"))
            appendLine()
        }
        if (r.notes.isNotEmpty()) appendLine(r.notes.joinToString(" · "))
    }.trim()
}
