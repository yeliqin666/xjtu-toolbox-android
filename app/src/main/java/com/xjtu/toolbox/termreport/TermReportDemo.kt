package com.xjtu.toolbox.termreport

import com.xjtu.toolbox.card.MealReport
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.jwapp.ScoreItem
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
        ),
        attendance = AttendanceStats(
            total = 214, normal = 203, late = 7, absent = 1, leave = 3, rate = 0.995f,
            courses = 9, perfectCourses = 6, mostLate = "高等数学(II)" to 4, topPlace = "主楼A-201" to 38, longestStreak = 86,
        ),
        grades = GradeStats(
            count = 9, credits = 27.5, gpa = 3.72, average = 87.4,
            best = ScoreItem(courseName = "程序设计基础", score = "98", scoreValue = 98.0),
            failed = 0, top95 = 2,
            bands = listOf("90+" to 4, "80–89" to 3, "70–79" to 2, "60–69" to 0, "60 以下" to 0),
            previousGpa = 3.41,
        ),
        todos = TodoStats(created = 58, done = 49, overdue = 3),
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
            ),
            priciest = tx("2025-11-11 14:02:33", "兴华超市", -126.8),
            priciestDay = LocalDate(2025, 11, 11) to 168.3,
            topWeekday = 4 to 1102.0,
            recharges = 14,
            longestMealDay = Triple(LocalDate(2025, 12, 23), "06:52", "23:18"),
        ),
        fitness = "82.6 分 · 良好",
        games = GameStats(
            minutes = 742, days = 38, favorite = "合成", longestDay = LocalDate(2026, 1, 10) to 5400L,
            records = listOf("合成西交大：最高 16384", "五子棋：23 胜 · 11 负 · 0 和", "跳一跳：最高 412"),
        ),
        agent = AgentStats(
            messages = 386, chats = 47, tools = 512,
            topTools = listOf("查课表" to 128L, "查空教室" to 76L, "查通知" to 54L),
            days = 63, peakHour = 23, lateNight = 58,
        ),
        usage = UsageStats(
            launches = 1243, days = 129,
            topFeatures = listOf("校园卡" to 214L, "图书馆" to 167L, "空闲教室" to 98L),
            lateLaunches = 37, payCodeOpens = 286,
        ),
    )
}
