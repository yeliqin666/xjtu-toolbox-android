package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.schedule.XjtuTime
import kotlin.math.round

/**
 * 体测模型 + 学年排序/解析工具 —— 从 `:app/fitness/FitnessApi.kt` 里切出来的**纯**那一段
 * （数据类 + 四个工具函数），跟着屏幕一起进 `:core`。
 *
 * 为什么值得切：这些函数是「哪一学年该显示、选中项怎么排、没成绩与未开放怎么区分」的口径，
 * 两端各抄一份迟早会算岔（`orderedFitnessYears` 的注释里记着原来那个 bug：
 * 体测系统把还没开测的下一学年排在最前）。`FitnessApi`（okhttp / v3 加密协议）继续留在 `:app`。
 */

data class FitnessYear(
    val yearNum: String,
    val name: String,
    val checked: Boolean,
)

data class FitnessScore(
    val studentNumber: String,
    val studentName: String,
    val totalScore: String,
    val totalGrade: String,
    val reportType: String,
    val reportStatus: String,
    val sex: String,
    val grade: String,
    val items: List<FitnessItem>,
)

data class FitnessItem(
    val name: String,
    val value: String,
    val grade: String,
    val tone: String,
)

/** 总分是不是「真有数」—— `--` / `未测` / 空都不算。 */
fun FitnessScore.hasUsableTotal(): Boolean {
    val s = totalScore.trim()
    return s.isNotEmpty() && s != "--" && s != "未测"
}

fun FitnessYear.yearValue(): Int? =
    Regex("""\d{4}""").find(yearNum)?.value?.toIntOrNull()
        ?: Regex("""\d{4}""").find(name)?.value?.toIntOrNull()

/**
 * 体测系统会把尚未开测的下一学年也列在最前，[checked] 也经常指到那一档。
 * 按当前学年（9 月起算）往前排，丢掉还没考的年份。
 */
fun orderedFitnessYears(
    years: List<FitnessYear>,
    academicYear: Int = XjtuTime.currentAcademicYear(),
): List<FitnessYear> {
    val ranked = years.sortedByDescending { it.yearValue() ?: Int.MIN_VALUE }
    val eligible = ranked.filter { (it.yearValue() ?: Int.MAX_VALUE) <= academicYear }
    return eligible.ifEmpty { ranked }
}

/**
 * 从用户/模型传入的学年参数里取出起始年。
 * `2025`、`2025-2026`、`2025-2026-1` 都表示 2025-2026 学年。
 */
fun parseFitnessAcademicYear(raw: String?): Int? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    Regex("""(20\d{2})\s*[-~—/到至]\s*(20\d{2})""").find(s)?.let {
        return it.groupValues[1].toInt()
    }
    return Regex("""20\d{2}""").find(s)?.value?.toIntOrNull()
}

fun pickFitnessYear(
    years: List<FitnessYear>,
    yearKey: String?,
    academicYear: Int = XjtuTime.currentAcademicYear(),
): FitnessYear? {
    val ordered = orderedFitnessYears(years, academicYear)
    val want = parseFitnessAcademicYear(yearKey) ?: return ordered.firstOrNull()
    return ordered.firstOrNull { it.yearValue() == want }
        ?: years.firstOrNull { it.yearValue() == want }
        ?: yearKey?.let { key ->
            years.firstOrNull { it.name.contains(key) || it.yearNum.contains(key) }
        }
}

/**
 * 分项名（随性别）：上游 `sex` 是中文 `男`/`女`，缺失时按**原实现的默认支**给名
 * （不是中性名 —— `:app` 的 `FitnessApi` 就是 `if (sex == "女") … else …`，
 * 两端必须同一支，否则同一个人的同一项在两端会叫两个名字）。
 */
fun fitnessItemName(base: String, sex: String): String = when (base) {
    "pull_and_sit" -> if (sex == "女") "仰卧起坐" else "引体向上"
    "run" -> if (sex == "女") "800 米" else "1000 米"
    else -> FITNESS_ITEM_DEFS.firstOrNull { it.first == base }?.second ?: base
}

/** 分项表：上游字段前缀 → 中文名（后两项按性别换名，见 [fitnessItemName]）。顺序就是界面顺序。 */
val FITNESS_ITEM_DEFS: List<Pair<String, String>> = listOf(
    "bmi" to "身高 / 体重",
    "vc" to "肺活量",
    "jump" to "立定跳远",
    "sit_and_reach" to "坐位体前屈",
    "pull_and_sit" to "力量（引体向上/仰卧起坐）",
    "50m" to "50 米",
    "run" to "耐力（1000 米/800 米）",
)

/**
 * 上游的分数是 `74.400000000000006` 这种浮点噪声 ⇒ 统一两位小数。
 *
 * 原实现是 `String.format(Locale.US, "%.2f", it)`（**JVM 专属**，且在 JVM 上是默认导入，
 * 任何基于 import 的判据都看不见它）。手写等价实现：四舍五入到分位并补齐两位小数。
 * 不是数字（`--`、`未测`）就原样返回。
 */
fun formatFitnessScore(raw: String): String {
    val trimmed = raw.trim()
    val number = trimmed.toDoubleOrNull() ?: return trimmed
    val negative = number < 0
    val scaled = round(kotlin.math.abs(number) * 100).toLong()
    val body = "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    return if (negative) "-$body" else body
}

/** 同上，但入参是**已经是数字**的值（campus-api 的 `value` 可能是 number 也可能是 string）。 */
fun formatFitnessScore(value: Double): String = formatFitnessScore(value.toString())

/**
 * 体测的**取数端口**。屏幕只认这个；两端各自注入实现：
 *  - `:app` = `FitnessApi`（v3 加密协议 + legacy PHP 两条路，okhttp，行为与搬迁前一致）；
 *  - Web    = [com.xjtu.toolbox.core.net.CampusFitnessApi]（campus-api 同源反代）。
 *
 * ⚠️ 两端拿到的**学生姓名/学号**不同：campus-api 按隐私口径**不返回**这两项
 * （见其 `modules/fitness.js` 的 `parseScore`），所以 Web 端英雄卡的标题会落到兜底文案
 * 「体测成绩」。分数、等级、七个分项与性别换名口径完全一致。
 */
interface FitnessSource {
    suspend fun years(): List<FitnessYear>

    /** @throws Exception 该学年没成绩 / 未开放 / 会话失效 —— 文案由实现决定，屏幕用 [com.xjtu.toolbox.error.FriendlyError] 兜。 */
    suspend fun score(yearNum: String): FitnessScore
}
