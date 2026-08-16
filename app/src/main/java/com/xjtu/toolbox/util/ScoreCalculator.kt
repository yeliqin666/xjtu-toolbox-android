package com.xjtu.toolbox.util

import com.xjtu.toolbox.jwapp.GpaInfo
import com.xjtu.toolbox.jwapp.ScoreItem

/**
 * 成绩 → GPA 映射、加权平均学分绩点（GPA）/ 加权均分计算。
 *
 * UI（成绩页、报表）与 Agent 工具共用同一份逻辑——避免不同入口算出不一致的 GPA。
 */
object ScoreCalculator {

    // ── 1. 单门成绩 → GPA ─────────────────────────────────────────

    /**
     * 西安交大 4.3 绩点制映射（西交教〔2015〕87号）。
     *
     * 接受 `Number` / `String` 两类输入：
     * - 数字：分段对照表
     * - 字符串：先清理隐藏字符和全角符号，再解析为数字或匹配英文/中文等级制
     *
     * 返回 `null` 表示该成绩**不参与 GPA 计算**（二等级制的"通过"/"不通过"，或无法识别）。
     */
    fun scoreToGpa(score: Any?): Double? {
        return when (score) {
            is Number -> {
                val s = score.toDouble()
                when {
                    s >= 95 -> 4.3
                    s >= 90 -> 4.0
                    s >= 85 -> 3.7
                    s >= 81 -> 3.3
                    s >= 78 -> 3.0
                    s >= 75 -> 2.7
                    s >= 72 -> 2.3
                    s >= 68 -> 2.0
                    s >= 64 -> 1.7
                    s >= 60 -> 1.3  // D = 1.3
                    else -> 0.0
                }
            }
            is String -> scoreToGpaFromString(score)
            else -> null
        }
    }

    private fun scoreToGpaFromString(raw: String): Double? {
        val g = raw
            .replace('＋', '+')
            .replace('－', '-')
            .replace(Regex("[^a-zA-Z0-9+\\-\\u4e00-\\u9fff]"), "")
            .uppercase()
        return when {
            g.isEmpty() -> null
            g.toDoubleOrNull() != null -> scoreToGpa(g.toDouble())
            // 英文 11 级
            g == "A+" -> 4.3; g == "A" -> 4.0; g == "A-" -> 3.7
            g == "B+" -> 3.3; g == "B" -> 3.0; g == "B-" -> 2.7
            g == "C+" -> 2.3; g == "C" -> 2.0; g == "C-" -> 1.7
            g == "D"  -> 1.3; g == "F"  -> 0.0
            // 中文 11 级
            g == "优+" -> 4.3; g == "优"  -> 4.0; g == "优-"  -> 3.7
            g == "良+" -> 3.3; g == "良"  -> 3.0; g == "良-"  -> 2.7
            g == "中+" -> 2.3; g == "中"  -> 2.0; g == "中-"  -> 1.7
            g == "及格" -> 1.3; g == "不及格" -> 0.0
            // 二等级制：返回 null（不参与 GPA 计算）
            g == "通过" || g == "不通过" -> null
            else -> null
        }
    }

    // ── 2. 单门课程优先 GPA 解析 ────────────────────────────────

    /**
     * 取一门课程最可靠的 GPA 值：
     * - 优先使用教务系统下发的精确值（`scoreItem.gpa > 0`）
     * - 否则根据成绩文本做本地映射（数字或等级制）
     * - 仍无法确定则返回 `null`
     */
    fun courseGpa(scoreItem: ScoreItem): Double? =
        scoreItem.gpa?.takeIf { it > 0.0 } ?: scoreToGpa(scoreItem.score.trim())

    /**
     * 通过判定（带冗余兜底）：`passFlag` 对等级制课程可能误判，
     * 需要 GPA > 0 或数字分 ≥ 60 再次确认。
     */
    fun isPassed(scoreItem: ScoreItem): Boolean {
        if (scoreItem.passFlag) return true
        courseGpa(scoreItem)?.let { if (it > 0.0) return true }
        scoreItem.scoreValue?.let { if (it >= 60.0) return true }
        return false
    }

    // ── 3. 课程列表 → 加权 GPA / 加权均分 / 学分汇总 ────────

    /**
     * 计算一组成绩的加权平均学分绩点和加权均分。
     *
     * 跳过规则：
     * - 二等级制（通过/不通过）—— 不参与 GPA
     * - 学分为 0 的条目 —— 学分权重为 0，没意义
     * - `courseGpa` 解析为 null 的条目 —— 同时跳过 GPA 与均分（避免被"0"拉低）
     * - 可选：[skipFirstAttemptFailed] = true 时，「初修不及格」也不计入 GPA
     *   （重修补考通过的会参与；初修挂掉的"硬挂"不计，避免拉低绩点）。
     *
     * @return [GpaInfo] 即使输入为空也会返回（学分=0、gpa=0.0）。
     */
    /**
     * 计算一组成绩的加权平均学分绩点和加权均分。
     *
     * 跳过规则：
     * - 二等级制（通过/不通过）—— 不参与 GPA
     * - 学分为 0 的条目 —— 学分权重为 0，没意义
     * - 默认 [skipFailedRetake] = false（教务 UI 默认语义）：
     *      **只有「初修挂科」才跳过；重修补考通过 / 补考挂科都仍计入 GPA**。
     *      这是教务系统 GpaInfo 的标准算法：避免初修一次性失败拉低总 GPA。
     *   当 [skipFailedRetake] = true（Agent 等场景）：
     *      所有未通过都跳过（包括初修挂科 + 补考不通过）。
     *
     * @return [GpaInfo] 即使输入为空也会返回（学分=0、gpa=0.0）。
     */
    fun calculateGpaForCourses(
        courses: List<ScoreItem>,
        skipFailedRetake: Boolean = false,
    ): GpaInfo {
        var totalCredits = 0.0
        var weightedGpa = 0.0
        var weightedScore = 0.0
        var scoreCredits = 0.0
        var courseCount = 0

        for (item in courses) {
            val raw = item.score.trim()
            if (raw == "通过" || raw == "不通过") continue

            val g = courseGpa(item)
            val numeric = item.scoreValue
            val passed = isPassed(item)

            // 跳过判断：
            // - 已通过：计入
            // - 未通过：
            //     • 教务语义 (skipFailedRetake = false)：examProp=="初修" 才跳过（避免初修挂科拉低）
            //     • Agent 语义 (skipFailedRetake = true)：所有未通过都跳过
            val isFirstAttempt = item.examProp == "初修"
            val skip = !passed && (skipFailedRetake || isFirstAttempt)
            if (skip) continue

            courseCount++
            totalCredits += item.coursePoint
            if (g != null) {
                weightedGpa += g * item.coursePoint
            }
            if (numeric != null && numeric > 0.0) {
                weightedScore += numeric * item.coursePoint
                scoreCredits += item.coursePoint
            }
        }

        return GpaInfo(
            gpa = if (totalCredits > 0) weightedGpa / totalCredits else 0.0,
            averageScore = if (scoreCredits > 0) weightedScore / scoreCredits else 0.0,
            totalCredits = totalCredits,
            courseCount = courseCount,
        )
    }

    /**
     * `ReportedGrade` 等轻量数据类的统一入口：避免在 Agent 等场景再写一份加权代码。
     *
     * @param rawScore 课程成绩文本（数字或等级制）
     * @param credit 学分
     * @param reportedGpa 教务下发的精确绩点（null 时退回本地映射）
     */
    fun calculateOneCourseContribution(
        rawScore: String,
        credit: Double,
        reportedGpa: Double?,
    ): OneCourseContribution {
        val trimmed = rawScore.trim()
        if (trimmed == "通过" || trimmed == "不通过" || credit <= 0.0) {
            return OneCourseContribution.NONE
        }
        val g = reportedGpa?.takeIf { it > 0.0 } ?: scoreToGpa(trimmed) ?: return OneCourseContribution.NONE
        return OneCourseContribution(gpa = g, credit = credit)
    }

    /** 加权累加期间单门课程的贡献，便于 AgentTool 现场算累计 GPA。 */
    data class OneCourseContribution(val gpa: Double, val credit: Double) {
        companion object { val NONE = OneCourseContribution(0.0, 0.0) }
    }

    /**
     * 把多门课程的贡献加权求和：返回 (weightedGpa, totalCredits)。
     * 调用方循环 [calculateOneCourseContribution] 累加即可，零分配。
     *
     * 为何放在这里：和 calculateGpaForCourses 共享相同的"跳过规则"——一处改全校一致。
     */
    fun accumulate(
        contributions: Sequence<OneCourseContribution>,
    ): Pair<Double, Double> {
        var weightedGpa = 0.0
        var totalCredits = 0.0
        contributions.forEach { c ->
            if (c.credit > 0.0) {
                weightedGpa += c.gpa * c.credit
                totalCredits += c.credit
            }
        }
        return weightedGpa to totalCredits
    }
}
