package com.xjtu.toolbox.termreport

import com.xjtu.toolbox.card.MealReport
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.jwapp.ScoreItem
import com.xjtu.toolbox.util.AppJson
import kotlinx.datetime.LocalDate

/** 调试版的示例总结：每一块都填满，用来看排版和文案。不落盘、不进往期回顾。 */
fun demoTermReport(): TermReport {
    fun tx(time: String, merchant: String, amount: Double) = Transaction(time = time, merchant = merchant, amount = amount)
    return TermReport(
        term = KnownTerm("2025-2026-1", "2025-2026学年第一学期", "2025-09-08", "2026-01-18", 19),
        courses = CourseStats(
            courses = 11, totalSections = 486, weeklyAvg = 25,
            perWeekday = listOf(112, 96, 128, 84, 66, 0, 0),
            earlyClasses = 47, latestSection = 11, busiestDay = 2 to 10,
            topTeacher = "王建国" to 64, topBuilding = "主楼A" to 188, customEvents = 23,
            topCourses = listOf("高等数学(II)" to 114, "大学物理" to 76, "程序设计基础" to 64, "线性代数" to 48),
            periods = listOf(268, 172, 46),
        ),
        attendance = AttendanceStats(
            total = 214, normal = 203, late = 7, absent = 1, leave = 3, rate = 0.995f,
            courses = 9, perfectCourses = 6, mostLate = "高等数学(II)" to 4, topPlace = "主楼A-201" to 38, longestStreak = 86,
            perfectNames = listOf("程序设计基础", "大学物理"), slipWeekday = 0 to 4,
        ),
        grades = GradeStats(
            count = 9, credits = 27.5, gpa = 3.72, average = 87.4,
            best = ScoreItem(courseName = "程序设计基础", score = "98", scoreValue = 98.0),
            failed = 0, top95 = 2,
            bands = listOf("90+" to 4, "80–89" to 3, "70–79" to 2, "60–69" to 0, "60 以下" to 0),
            previousGpa = 3.41,
            top = listOf(
                Triple("程序设计基础", "98", 98.0), Triple("线性代数", "A+ · 96", 96.0), Triple("大学物理", "93", 93.0),
                Triple("英语(III)", "A · 91", 91.0), Triple("高等数学(II)", "88", 88.0),
            ),
            history = listOf("24秋" to 3.28, "25春" to 3.41, "25秋" to 3.72),
            heaviest = ScoreItem(courseName = "高等数学(II)", score = "88", scoreValue = 88.0, coursePoint = 6.0),
        ),
        todos = TodoStats(
            created = 58, done = 49, overdue = 3, byAgent = 12,
            monthly = listOf("2025-09" to 9, "2025-10" to 14, "2025-11" to 17, "2025-12" to 13, "2026-01" to 5),
        ),
        card = CardStats(
            from = LocalDate(2025, 9, 8), spend = 4286.5, activeDays = 121,
            monthly = listOf("2025-09" to 892.0, "2025-10" to 1034.5, "2025-11" to 968.0, "2025-12" to 1012.0, "2026-01" to 380.0),
            categories = listOf("餐饮" to 3120.0, "超市" to 486.0, "饮品" to 312.5, "洗浴" to 198.0, "打印" to 70.0),
            meal = MealReport(
                from = LocalDate(2025, 9, 8), to = LocalDate(2026, 1, 18),
                meals = 312, mealSpend = 3120.0, totalSpend = 4286.5, days = 118,
                canteen = "康桥苑" to 141, stall = "康桥二楼牛肉面" to 37,
                earliest = tx("2025-10-14 06:48:12", "梧桐苑一楼早餐", -6.5),
                latest = tx("2025-12-21 23:41:05", "康桥苑夜宵档", -15.0),
                breakfastDays = 52, lateNights = 19, drinks = 41,
                priciest = tx("2025-12-24 18:32:40", "康桥三楼自助餐", -48.0),
                hourlyCounts = listOf(0, 0, 0, 0, 0, 0, 3, 28, 21, 4, 2, 46, 71, 9, 2, 1, 3, 38, 44, 7, 4, 6, 11, 12),
            ),
            priciest = tx("2025-11-11 14:02:33", "兴华超市", -126.8),
            priciestDay = LocalDate(2025, 11, 11) to 168.3,
            topWeekday = 4 to 1102.0,
            recharges = 14,
            longestMealDay = Triple(LocalDate(2025, 12, 23), "06:52", "23:18"),
            perWeekday = listOf(642.0, 588.0, 701.0, 615.0, 1102.0, 386.0, 252.5),
            quietStreak = LocalDate(2025, 10, 1) to 7,
        ),
        fitness = "82.6 分 · 良好",
        games = GameStats(
            minutes = 742, days = 38, favorite = "合成", longestDay = LocalDate(2026, 1, 10) to 5400L,
            records = listOf("合成西交大：最高 16384", "五子棋：23 胜 · 11 负 · 0 和", "跳一跳：最高 412"),
            perGame = listOf("合成" to 386L, "五子棋" to 214L, "跳一跳" to 142L),
        ),
        agent = AgentStats(
            messages = 386, chats = 47, tools = 512,
            topTools = listOf("查课表" to 128L, "查空教室" to 76L, "查通知" to 54L),
            days = 63, peakHour = 23, lateNight = 58,
            hourly = listOf(12, 6, 2, 0, 0, 0, 0, 3, 14, 11, 9, 13, 18, 10, 12, 15, 16, 14, 11, 19, 27, 31, 38, 52).map { it.toLong() },
        ),
        usage = UsageStats(
            launches = 1243, days = 129,
            topFeatures = listOf("校园卡" to 214L, "图书馆" to 167L, "空闲教室" to 98L),
            lateLaunches = 37, payCodeOpens = 286,
            hourly = listOf(9, 5, 2, 1, 0, 0, 4, 96, 88, 61, 54, 103, 142, 70, 52, 48, 57, 112, 98, 64, 61, 59, 46, 33).map { it.toLong() },
            features = 21, busiestDay = LocalDate(2026, 1, 12) to 31L,
        ),
        // 周末、国庆、元旦空着，期末那几周最满
        daily = List(133) { i ->
            when {
                i in 23..29 || i in 115..117 -> 0
                i % 7 >= 5 -> (i * 7 % 5)
                else -> 6 + (i * 13 % 9) + if (i > 110) 8 else 0
            }
        },
        project = runCatching { AppJson.decodeFromString<ProjectStory>(PROJECT_STORY_SNAPSHOT) }.getOrNull(),
        installedOn = LocalDate(2026, 3, 10),
        major = MajorChange("机械工程", "计算机科学与技术", "机械工程学院", "计算机科学与技术学院", LocalDate(2026, 3, 2)),
        library = LibraryStats(
            bookings = 46, days = 41, topArea = "主馆 北楼二层外文库（东）" to 23, topSeat = "北楼二层外文库（东） C08" to 9,
            missed = 2, cancelled = 5, perWeekday = listOf(8, 6, 9, 7, 5, 7, 4), earliestIn = "07:42", leaves = 18,
        ),
        study = StudyStats(
            submitted = 27, lastMinute = 6, topCourse = "大学物理" to 8, zyxfPreviews = 64, zyxfDownloads = 12,
            zyxfTop = "高等数学" to 21, roomQueries = 15, topRoomBuilding = "主楼" to 9, exams = 7,
            examPeak = LocalDate(2026, 6, 24) to 2, examSpan = LocalDate(2026, 6, 20) to LocalDate(2026, 7, 2),
        ),
        // 每次预览换一个种子，方便看称号和文案的变化
        seed = System.nanoTime().toString(),
    )
}
