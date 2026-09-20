package com.xjtu.toolbox.social

import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchProfileTest {

    private fun course(
        day: Int,
        from: Int,
        to: Int = from + 1,
        code: String = "C$day$from",
        name: String = "课$day$from",
        teacher: String = "",
        location: String = "",
    ) = CourseItem(
        courseName = name,
        teacher = teacher,
        location = location,
        weekBits = "1".repeat(20),
        dayOfWeek = day,
        startSection = from,
        endSection = to,
        courseCode = code,
        courseType = "",
    )

    private fun exam(code: String, name: String, date: String) =
        ExamItem(
            courseName = name,
            courseCode = code,
            examDate = date,
            examTime = "09:00-11:00",
            location = "主楼A-101",
            seatNumber = "12",
        )

    private fun build(
        courses: List<CourseItem> = emptyList(),
        nickname: String = "",
        past: Set<String> = emptySet(),
        exams: List<ExamItem> = emptyList(),
        textbooks: List<String> = emptyList(),
        dining: Map<Int, Int> = emptyMap(),
        canteens: List<String> = emptyList(),
        tags: Set<String> = emptySet(),
        dims: MatchProfile.Dimensions = MatchProfile.Dimensions(),
    ) = MatchProfile.build(
        local = MatchData.Local(
            courses = courses,
            pastCourseCodes = past,
            exams = exams,
            textbooks = textbooks,
            diningHourCounts = dining,
            canteens = canteens,
        ),
        nickname = nickname,
        dietTags = tags,
        dims = dims,
    )

    private fun facet(r: MatchProfile.Result, label: String) = r.facets.first { it.label == label }
    private fun discovery(r: MatchProfile.Result, keyword: String) =
        r.discoveries.firstOrNull { keyword in it.title }

    // ── 分享的东西必须真的存在 ───────────────────────────

    @Test
    fun 没有课表时不分享一张全空的网格() {
        // 旧版本只看开关不看数据：没读到课表照样塞 77 个 '0' 出去，
        // 对方解出来是"这人全周有空"，算出满分的共同空闲。
        val p = build()
        assertEquals("", p.busyGrid)
        assertEquals("", p.buildingGrid)
        assertTrue(p.courses.isEmpty())

        val other = build(listOf(course(1, 1), course(3, 5)))
        val r = MatchProfile.compare(p, other)
        assertTrue(r.facets.none { it.label == "共同空闲" })
        assertNull(r.overlapGrid)
    }

    @Test
    fun 没有食堂记录时不分享一串全零的饭点() {
        val dims = MatchProfile.Dimensions(diningHours = true)
        assertEquals("", build(listOf(course(1, 1)), dims = dims).diningHours)
        // 只去过一次的时段不算习惯，阈值是 2。
        assertEquals("", build(listOf(course(1, 1)), dining = mapOf(12 to 1), dims = dims).diningHours)
        val real = build(listOf(course(1, 1)), dining = mapOf(12 to 3), dims = dims)
        assertEquals(24, real.diningHours.length)
        assertEquals('1', real.diningHours[12])
    }

    @Test
    fun 课表里没有教室时不分享教学楼() {
        val p = build(listOf(course(1, 1, location = "")))
        assertEquals("", p.buildingGrid)
        assertTrue(p.buildings.isEmpty())
    }

    @Test
    fun 网格是每天十一节() {
        val p = build(listOf(course(1, 1, 2)))
        assertEquals(MatchProfile.DAYS * MatchProfile.SECTIONS, p.busyGrid.length)
        assertEquals(77, p.busyGrid.length)
        assertEquals("11" + "0".repeat(9), p.busyGrid.take(11))
    }

    @Test
    fun 教学楼从教室名里认出来() {
        assertEquals("中2", MatchProfile.buildingOf("中2-2101"))
        assertEquals("中2", MatchProfile.buildingOf("中2 2101"))
        assertEquals("教2楼", MatchProfile.buildingOf("教2楼101"))
        assertEquals("主楼A", MatchProfile.buildingOf("主楼A-203"))
        assertNull(MatchProfile.buildingOf(""))
    }

    // ── 编解码 ──────────────────────────────────────────

    @Test
    fun 编解码往返() {
        val mine = build(
            courses = listOf(
                course(1, 1, 2, teacher = "张三", location = "中2-2101"),
                course(3, 5, 6, teacher = "李四", location = "主楼A-203"),
            ),
            nickname = "阿离",
            past = setOf("OLD001", "OLD002"),
            exams = listOf(exam("C11", "课11", "2027-01-08")),
            textbooks = listOf("高等数学", "线性代数"),
            dining = mapOf(12 to 5, 18 to 4),
            canteens = listOf("一食堂", "二食堂"),
            tags = setOf("辣", "面食"),
            dims = MatchProfile.Dimensions(
                textbooks = true, diningHours = true, canteens = true, dietTags = true,
            ),
        )
        val back = MatchProfile.decode(MatchProfile.encode(mine))
        assertNotNull(back)
        assertEquals(mine, back)
    }

    @Test
    fun 昵称里的分隔符不会把后面的字段整体错位() {
        // 一个 '|' 就能让年级、校区全读成别的字段。进码之前换掉。
        val p = build(listOf(course(1, 1)), nickname = "a|b,c~d")
        assertTrue(p.nickname.none { it == '|' || it == ',' || it == '~' })
        assertEquals(p, MatchProfile.decode(MatchProfile.encode(p)))
    }

    @Test
    fun 课名和教室很多时也不会截断成解不开的码() {
        val many = (1..7).flatMap { d ->
            (1..11).map { s ->
                course(d, s, s, "CODE$d$s", "很长的课程名称$d$s", teacher = "老师$d$s", location = "中$d-${s}101")
            }
        }
        val p = build(many, past = (1..120).map { "PAST$it" }.toSet())
        val back = MatchProfile.decode(MatchProfile.encode(p))
        assertNotNull(back)
        assertEquals(p, back)
    }

    @Test
    fun 粘贴时带上换行也能解开() {
        val code = MatchProfile.encode(build(listOf(course(2, 3))))
        val wrapped = code.chunked(20).joinToString("\n  ")
        assertEquals(MatchProfile.decode(code), MatchProfile.decode(wrapped))
    }

    @Test
    fun 乱码和旧版码都返回空而不是抛异常() {
        assertNull(MatchProfile.decode(""))
        assertNull(MatchProfile.decode("这不是一段码"))
        assertNull(MatchProfile.decode("AAAAAAAAAAAA"))
    }

    @Test
    fun 楼名表对不上的网格整张作废() {
        // 下标越界会让"同楼"判成随便哪栋楼，宁可当作没分享。
        val p = build(listOf(course(1, 1, location = "中2-2101")))
        val tampered = p.copy(buildings = emptyList())
        val back = MatchProfile.decode(MatchProfile.encode(tampered))
        assertNotNull(back)
        assertEquals("", back!!.buildingGrid)
    }

    // ── 打分 ────────────────────────────────────────────

    @Test
    fun 同一张课表得满分() {
        val mine = build(listOf(course(1, 1, 2), course(3, 5, 6)))
        val r = MatchProfile.compare(mine, mine)
        assertEquals(100, facet(r, "共同空闲").score)
        assertEquals(100, facet(r, "作息").score)
        assertEquals(100, r.overall)
        assertTrue(r.scored)
    }

    @Test
    fun 交集不参与打分() {
        // 同课在陌生人之间天然接近 0，折进平均会把一个本来有意义的分数拖垮。
        val a = build(listOf(course(1, 1, 2, code = "SAME")))
        val b = build(listOf(course(1, 1, 2, code = "OTHER")))
        val shared = MatchProfile.compare(a, a)
        val apart = MatchProfile.compare(a, b)
        // 课表一模一样，只是课程号不同：分数不该有差别，差别只体现在"发现"里。
        assertEquals(shared.overall, apart.overall)
        assertNotNull(discovery(shared, "一起上"))
        assertNull(discovery(apart, "一起上"))
        assertTrue(shared.facets.none { it.label == "同课" })
    }

    @Test
    fun 完全错开的作息分数低于完全重合() {
        // 一个全上午、一个全晚上：能一起的时间只剩下午。
        val morning = build((1..5).flatMap { d -> (1..4).map { s -> course(d, s, s, "M$d$s") } })
        val night = build((1..5).flatMap { d -> (9..11).map { s -> course(d, s, s, "N$d$s") } })
        val apart = MatchProfile.compare(morning, night)
        val together = MatchProfile.compare(morning, morning)
        assertTrue(
            "错开的共同空闲应低于重合的",
            facet(apart, "共同空闲").score < facet(together, "共同空闲").score
        )
        assertEquals(0, facet(apart, "作息").score)
        assertEquals(100, facet(together, "作息").score)
    }

    @Test
    fun 周末不参与打分() {
        // 只有周六课不同：周末不进分母，共同空闲应当仍是满分。
        val a = build(listOf(course(1, 1, 2)))
        val b = build(listOf(course(1, 1, 2), course(6, 1, 4, "SAT")))
        assertEquals(100, facet(MatchProfile.compare(a, b), "共同空闲").score)
    }

    @Test
    fun 共同空档按长度排序且只取连着两节以上() {
        // 周一 3-4 空两节，周三全天有课，其余工作日全空。
        val schedule = listOf(course(1, 1, 2), course(1, 5, 11), course(3, 1, 11))
        val blocks = MatchProfile.compare(build(schedule), build(schedule)).freeBlocks
        assertTrue(blocks.isNotEmpty())
        assertEquals(blocks.maxOf { it.length }, blocks.first().length)
        assertTrue("不该出现单节空档", blocks.all { it.length >= 2 })
        assertTrue("周末不列进可约时段", blocks.all { it.day < MatchProfile.WEEKDAYS })
        assertTrue(blocks.any { it.day == 0 && it.from == 3 && it.to == 4 })
    }

    @Test
    fun 对方少分享几项不会被算成不合拍() {
        val full = build(
            listOf(course(1, 1, 2)),
            dining = mapOf(12 to 3),
            tags = setOf("辣"),
            dims = MatchProfile.Dimensions(diningHours = true, dietTags = true),
        )
        val shy = build(listOf(course(1, 1, 2)))
        val r = MatchProfile.compare(full, shy)
        assertTrue(r.facets.none { it.label == "饭点" })
        assertEquals(100, r.overall)
    }

    @Test
    fun 不同校区是硬门槛而不是打折() {
        val mine = build(listOf(course(1, 1, 2))).copy(campus = "兴庆校区")
        val theirs = build(listOf(course(1, 1, 2))).copy(campus = "创新港", nickname = "小王")
        val r = MatchProfile.compare(mine, theirs)
        assertNotNull(r.blocker)
        // 门槛不并进百分比：分数照旧是 100，界面把这句话摆在数字上面。
        assertEquals(100, r.overall)
    }

    // ── 发现 ────────────────────────────────────────────

    @Test
    fun 叠加网格四档取值() {
        val mine = build(listOf(course(1, 1, 1, location = "中2-101")))
        val theirs = build(
            listOf(course(1, 1, 1, location = "中2-999"), course(1, 2, 2, location = "主楼A-1"))
        )
        val grid = MatchProfile.compare(mine, theirs).overlapGrid
        assertNotNull(grid)
        // 第 1 节两人都有课，而且都在中2 → 同楼
        assertEquals(MatchProfile.Cell.SAME_BUILDING, grid!![0])
        assertEquals(MatchProfile.Cell.ONE_FREE, grid[1])
        assertEquals(MatchProfile.Cell.BOTH_FREE, grid[2])
    }

    @Test
    fun 同楼不同班会被找出来() {
        // 周二 3-4 节两人都在中2，周三两人在不同楼。
        val mine = build(
            listOf(course(2, 3, 4, "A1", location = "中2-2101"), course(3, 1, 2, "A2", location = "主楼A-1"))
        )
        val theirs = build(
            listOf(course(2, 3, 4, "B1", location = "中2-3305"), course(3, 1, 2, "B2", location = "东1东-9"))
        )
        val r = MatchProfile.compare(mine, theirs)
        assertEquals(1, r.encounters.size)
        val e = r.encounters.first()
        assertEquals(1, e.day)
        assertEquals(3, e.from)
        assertEquals(4, e.to)
        assertEquals("中2", e.building)
        assertNotNull(discovery(r, "同一栋楼"))
        // 都有课但不同楼的那两节不算擦肩而过。
        assertEquals(MatchProfile.Cell.BOTH_BUSY, r.overlapGrid!![2 * MatchProfile.SECTIONS])
    }

    @Test
    fun 换了一栋楼的连续时段会断开() {
        val mine = build(
            listOf(course(1, 1, 2, "A1", location = "中2-1"), course(1, 3, 4, "A2", location = "主楼A-1"))
        )
        val r = MatchProfile.compare(mine, mine)
        assertEquals(listOf("中2", "主楼A"), r.encounters.sortedBy { it.from }.map { it.building })
    }

    @Test
    fun 共同老师和共同课程都带出名字() {
        val mine = build(listOf(course(1, 1, 2, "PHY101", "大学物理", teacher = "王老师")))
        val theirs = build(listOf(course(3, 5, 6, "PHY101", "大学物理", teacher = "王老师")))
        val r = MatchProfile.compare(mine, theirs)
        assertEquals(listOf("大学物理"), discovery(r, "一起上")!!.items)
        assertEquals(listOf("王老师"), discovery(r, "老师")!!.items)
    }

    @Test
    fun 往期同课只说数量不说名字() {
        val mine = build(listOf(course(1, 1)), past = setOf("OLD1", "OLD2", "OLD3"))
        val theirs = build(listOf(course(2, 1)), past = setOf("OLD2", "OLD3", "OLD9"))
        val d = discovery(MatchProfile.compare(mine, theirs), "以前")
        assertNotNull(d)
        assertTrue("2 门" in d!!.title)
        assertTrue("往期只带课程号，说不出名字", d.items.isEmpty())
    }

    @Test
    fun 当前学期的课不会重复算进往期() {
        // MatchData 会把当前学期的课从往期里去掉；这里验证真去掉了就不会双计。
        val mine = build(listOf(course(1, 1, code = "NOW")), past = emptySet())
        val theirs = build(listOf(course(1, 1, code = "NOW")), past = emptySet())
        val r = MatchProfile.compare(mine, theirs)
        assertNotNull(discovery(r, "一起上"))
        assertNull(discovery(r, "以前"))
    }

    @Test
    fun 同一门课同一天考才算撞车() {
        val mine = build(
            listOf(course(1, 1)),
            exams = listOf(exam("PHY", "大学物理", "2027-01-08"), exam("MATH", "高数", "2027-01-12")),
        )
        val theirs = build(
            listOf(course(2, 1)),
            // 同一门课但改期了，不算撞在一起。
            exams = listOf(exam("PHY", "大学物理", "2027-01-08"), exam("MATH", "高数", "2027-01-15")),
        )
        val r = MatchProfile.compare(mine, theirs)
        val d = discovery(r, "撞在同一天")
        assertNotNull(d)
        assertEquals(1, d!!.items.size)
        assertTrue("大学物理" in d.items.first())
        assertTrue("1 月 8 日" in d.items.first())
        // 谁先考完
        val finish = discovery(r, "考完")
        assertNotNull(finish)
        assertTrue("你早 3 天考完" in finish!!.title)
    }

    @Test
    fun 教材和食堂也会对一遍() {
        val dims = MatchProfile.Dimensions(textbooks = true, canteens = true)
        val mine = build(
            listOf(course(1, 1)),
            textbooks = listOf("高等数学", "大学英语"),
            canteens = listOf("一食堂", "梧桐苑餐厅"),
            dims = dims,
        )
        val theirs = build(
            listOf(course(2, 1)),
            textbooks = listOf("高等数学", "线性代数"),
            canteens = listOf("一食堂"),
            dims = dims,
        )
        val r = MatchProfile.compare(mine, theirs)
        assertEquals(listOf("高等数学"), discovery(r, "教材")!!.items)
        assertEquals(listOf("一食堂"), discovery(r, "食堂")!!.items)
    }

    @Test
    fun 没有课表也还能有发现() {
        // 双方都不分享课表时算不出分数，但同课、同老师这些交集照样成立。
        val dims = MatchProfile.Dimensions(schedule = false)
        val mine = build(listOf(course(1, 1, 2, "PHY", "大学物理", teacher = "王老师")), dims = dims)
        val theirs = build(listOf(course(4, 9, 10, "PHY", "大学物理", teacher = "王老师")), dims = dims)
        val r = MatchProfile.compare(mine, theirs)
        assertTrue(r.facets.isEmpty())
        assertTrue(!r.scored && !r.empty)
        assertNotNull(discovery(r, "一起上"))
    }

    @Test
    fun 发现按稀有程度排序() {
        val dims = MatchProfile.Dimensions(dietTags = true)
        val mine = build(
            listOf(course(1, 1, 2, "PHY", "大学物理", teacher = "王老师", location = "中2-1")),
            exams = listOf(exam("PHY", "大学物理", "2027-01-08")),
            tags = setOf("辣"),
            dims = dims,
        )
        val r = MatchProfile.compare(mine, mine)
        val titles = r.discoveries.map { it.title }
        assertTrue("考试撞车排在最前", "撞在同一天" in titles.first())
        assertEquals(r.discoveries.sortedBy { it.rank }.map { it.title }, titles)
    }

    // ── 分享码得塞得进一张扫得动的二维码 ────────────────

    /** 一份不算轻的真实课表：12 门课、每门每周两次、6 场考试、60 门往期课。 */
    private fun realisticCourses(): List<CourseItem> {
        val buildings = listOf("中2", "主楼A", "东1东", "教2楼")
        val names = listOf(
            "高等数学", "大学物理", "线性代数", "程序设计基础", "大学英语", "思想道德与法治",
            "电路原理", "概率论与数理统计", "工程制图", "体育", "马克思主义基本原理", "数据结构",
        )
        return names.flatMapIndexed { i, name ->
            listOf(0, 1).map { k ->
                val slot = i * 2 + k
                course(
                    day = slot % 5 + 1,
                    from = slot % 5 * 2 + 1,
                    to = slot % 5 * 2 + 2,
                    code = "CS%03d".format(i),
                    name = name,
                    teacher = "老师%02d".format(i),
                    location = "${buildings[i % buildings.size]}-${1000 + slot}",
                )
            }
        }
    }

    @Test
    fun 默认维度下的分享码扫得动() {
        val p = build(
            courses = realisticCourses(),
            nickname = "阿离",
            past = (1..60).map { "PAST%03d".format(it) }.toSet(),
            exams = (1..6).map { exam("CS%03d".format(it), "考试科目$it", "2027-01-0$it") },
        )
        val len = MatchProfile.encode(p).length
        // 上限对齐 QrBitmap.COMFORTABLE（1200）——随手一扫就出的那一档。
        // 实测这份课表是 660 字左右，留了一倍余量给课名更长、课更多的人。
        assertTrue("默认维度的分享码 $len 字，二维码已经不好扫了", len <= 1200)
    }

    @Test
    fun 打开全部维度也仍然是一段能发出去的文字() {
        val p = build(
            courses = realisticCourses(),
            nickname = "阿离",
            past = (1..120).map { "PAST%03d".format(it) }.toSet(),
            exams = (1..8).map { exam("CS%03d".format(it), "考试科目$it", "2027-01-0${it % 9}") },
            textbooks = (1..20).map { "教材名称$it" },
            dining = mapOf(12 to 9, 18 to 7),
            canteens = listOf("一食堂", "二食堂", "梧桐苑餐厅"),
            tags = setOf("辣", "面食", "咖啡"),
            dims = MatchProfile.Dimensions(
                textbooks = true, diningHours = true, canteens = true, dietTags = true,
            ),
        )
        val len = MatchProfile.encode(p).length
        // 全开也得留在 QrBitmap.MAX_SCANNABLE（1800）以内：超了界面会撤掉二维码、
        // 退回让人复制一大段文字，那正是这个功能最不好用的样子。实测约 980 字。
        assertTrue("全开的分享码 $len 字，二维码已经密到扫不动了", len <= 1800)
    }

    /**
     * 用户报的是「选啥都不变，是空的」。这条钉住「选了就得变」：
     * 有数据的每一维，单独关掉都必须让码变短、让 sharedCount 少一项。
     * 任何一维不满足，都说明开关没接到编码链路上。
     */
    @Test
    fun 每一维单独关掉都会让分享码变短() {
        val all = MatchProfile.Dimensions(
            textbooks = true, diningHours = true, canteens = true, dietTags = true,
        )
        fun profile(dims: MatchProfile.Dimensions) = build(
            courses = realisticCourses(),
            nickname = "阿离",
            past = (1..30).map { "PAST%03d".format(it) }.toSet(),
            exams = (1..6).map { exam("CS%03d".format(it), "考试科目$it", "2027-01-0$it") },
            textbooks = (1..10).map { "教材名称$it" },
            dining = mapOf(12 to 9, 18 to 7),
            canteens = listOf("一食堂", "二食堂"),
            tags = setOf("辣", "面食"),
            dims = dims,
        )

        val full = profile(all)
        val fullCode = MatchProfile.encode(full)
        assertTrue("全开时分享码不该是空的", fullCode.isNotEmpty())

        // 每一项：关掉它之后的 Dimensions，以及给人看的名字。
        val switches = listOf<Pair<String, MatchProfile.Dimensions>>(
            "课表" to all.copy(schedule = false),
            "同课" to all.copy(sameCourses = false),
            "教学楼" to all.copy(buildings = false),
            "老师" to all.copy(teachers = false),
            "往期课程" to all.copy(pastCourses = false),
            "考试" to all.copy(exams = false),
            "教材" to all.copy(textbooks = false),
            "饭点" to all.copy(diningHours = false),
            "食堂" to all.copy(canteens = false),
            "口味" to all.copy(dietTags = false),
        )
        for ((label, dims) in switches) {
            val p = profile(dims)
            assertTrue(
                "关掉「$label」之后分享码没变，开关没接上编码",
                MatchProfile.encode(p) != fullCode,
            )
            assertTrue(
                "关掉「$label」之后 sharedCount 没减少（${p.sharedCount} vs ${full.sharedCount}）",
                p.sharedCount < full.sharedCount,
            )
        }
    }

    @Test
    fun 一条缓存都没有时hasAnything是假的() {
        assertTrue("空缓存不该被当成有数据", !MatchData.Local().hasAnything)
        assertTrue("有课表就算有数据", MatchData.Local(courses = realisticCourses()).hasAnything)
        assertTrue("只有食堂记录也算有数据", MatchData.Local(canteens = listOf("一食堂")).hasAnything)
    }

    /**
     * 最坏情况也得留在能扫的长度里。
     *
     * 往期课程顶到 [MatchData] 的 120 门上限、教材拉满、课名和老师名都取允许的最长，
     * 这是一个逛遍了所有页面的大四学生能攒出的最长的码。超了界面会撤掉二维码，
     * 那正是用户当初报「不出码」的那个样子。
     */
    @Test
    fun 数据拉满时分享码仍然扫得动() {
        val fat = realisticCourses().map {
            it.copy(courseName = "课程名称".repeat(4), teacher = "老师姓名很长")
        }
        val p = build(
            courses = fat,
            nickname = "十二个字的很长昵称",
            past = (1..120).map { "PAST%05d".format(it) }.toSet(),
            exams = (1..12).map { exam("CS%03d".format(it), "考试科目名称$it", "2027-01-%02d".format(it)) },
            textbooks = (1..30).map { "教材名称第$it 册" },
            dining = (7..22).associateWith { 5 },
            canteens = listOf("一食堂", "二食堂", "三食堂", "梧桐苑餐厅", "康桥苑"),
            tags = setOf("辣", "面食", "咖啡", "清真", "甜"),
            dims = MatchProfile.Dimensions(
                textbooks = true, diningHours = true, canteens = true, dietTags = true,
            ),
        )
        val code = MatchProfile.encode(p)
        assertTrue(
            "数据拉满时分享码 ${code.length} 字，超过了扫得动的上限，界面会退回纯文字",
            code.length <= 1800,
        )
        // 长码最容易在压缩/分隔符上出问题，顺带确认它还解得回来。
        assertEquals(p, MatchProfile.decode(code))
    }

    /** 一个维度都不开时也得出一段合法的码，而不是空串——空串喂给 zxing 会直接抛异常。 */
    @Test
    fun 全关时仍然是一段能解开的码() {
        val none = MatchProfile.Dimensions(
            schedule = false, sameCourses = false, buildings = false, teachers = false,
            pastCourses = false, exams = false, textbooks = false, identity = false,
            diningHours = false, canteens = false, dietTags = false,
        )
        val code = MatchProfile.encode(build(courses = realisticCourses(), nickname = "阿离", dims = none))
        assertTrue("全关时分享码是空串，二维码这一侧会直接崩", code.isNotEmpty())
        val back = MatchProfile.decode(code)
        assertNotNull("全关的码解不开", back)
        assertEquals("阿离", back!!.nickname)
        assertEquals(0, back.sharedCount)
    }
}
