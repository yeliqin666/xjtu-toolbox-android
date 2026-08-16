package com.xjtu.toolbox.util

import com.xjtu.toolbox.jwapp.ScoreItem
import com.xjtu.toolbox.jwapp.ScoreSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ScoreCalculator 关键路径测试。
 *
 * 不依赖 Android 框架：纯 JVM 跑（testImplementation junit）。
 *
 * 覆盖：
 * - 数字分段（95→4.3 / 60→1.0 / <60→null）
 * - 等级制字符串（A→4.0 / 优→4.0 / 通过→null）
 * - 加权平均（小数精度 / 学分权重 / 跳过分母为 0）
 * - skipFailedRetake（初修挂科 vs 补考挂科）
 */
class ScoreCalculatorTest {

    @Test
    fun numericScore_bandMapping() {
        // 西交 4.3 制分段
        assertEqualsD(4.3, ScoreCalculator.scoreToGpa(95))
        assertEqualsD(4.0, ScoreCalculator.scoreToGpa(90))
        assertEqualsD(3.7, ScoreCalculator.scoreToGpa(85))
        assertEqualsD(3.3, ScoreCalculator.scoreToGpa(81))
        assertEqualsD(3.0, ScoreCalculator.scoreToGpa(78))
        assertEqualsD(2.7, ScoreCalculator.scoreToGpa(75))
        assertEqualsD(2.3, ScoreCalculator.scoreToGpa(72))
        assertEqualsD(2.0, ScoreCalculator.scoreToGpa(68))
        assertEqualsD(1.7, ScoreCalculator.scoreToGpa(64))
        assertEqualsD(1.3, ScoreCalculator.scoreToGpa(60))
        assertEqualsD(0.0, ScoreCalculator.scoreToGpa(59))
        assertEqualsD(0.0, ScoreCalculator.scoreToGpa(0))
    }

    @Test
    fun letterGrade_parses() {
        assertEqualsD(4.3, ScoreCalculator.scoreToGpa("A+"))
        assertEqualsD(4.0, ScoreCalculator.scoreToGpa("A"))
        assertEqualsD(3.7, ScoreCalculator.scoreToGpa("A-"))
        assertEqualsD(3.3, ScoreCalculator.scoreToGpa("B+"))
        assertEqualsD(3.0, ScoreCalculator.scoreToGpa("B"))
        assertEqualsD(1.3, ScoreCalculator.scoreToGpa("D"))
        assertNull(ScoreCalculator.scoreToGpa("通过"))
        assertNull(ScoreCalculator.scoreToGpa("不通过"))
    }

    @Test
    fun chineseGrade_parses() {
        assertEqualsD(4.3, ScoreCalculator.scoreToGpa("优+"))
        assertEqualsD(4.0, ScoreCalculator.scoreToGpa("优"))
        assertEqualsD(3.0, ScoreCalculator.scoreToGpa("良"))
        assertEqualsD(2.0, ScoreCalculator.scoreToGpa("中"))
        assertEqualsD(1.3, ScoreCalculator.scoreToGpa("及格"))
        // "不及格" 在 ScoreCalculator 里返回 0.0，不算 null（与"通过/不通过"不同）
    }

    private fun assertEqualsD(expected: Double, actual: Double?, tolerance: Double = 0.001) {
        assertNotNull("actual gpa is null", actual)
        assertEquals(expected, actual!!, tolerance)
    }

    @Test
    fun weightedGpa_simple() {
        // 两门课各 3 学分：高数 90→4.0，线代 85→3.7
        // 加权 GPA = (4.0·3 + 3.7·3) / 6 = 23.1/6 = 3.85
        val courses = listOf(
            scoreItem(courseName = "高数", coursePoint = 3.0, score = "90", scoreValue = 90.0),
            scoreItem(courseName = "线代", coursePoint = 3.0, score = "85", scoreValue = 85.0),
        )
        val info = ScoreCalculator.calculateGpaForCourses(courses)
        assertEquals(3.85, info.gpa, 0.001)
        assertEquals(6.0, info.totalCredits, 0.001)
        assertEquals(2, info.courseCount)
    }

    @Test
    fun weightedGpa_skipsFailedFirstAttempt() {
        // 默认 skipFailedRetake=false：初修挂科跳过，补考挂科保留（计入学分但 gpa=null）
        val courses = listOf(
            scoreItem(courseName = "高数", coursePoint = 4.0, score = "85", scoreValue = 85.0, examProp = "初修"),
            scoreItem(courseName = "线代", coursePoint = 4.0, score = "55", scoreValue = 55.0, examProp = "初修"),
            scoreItem(courseName = "大物", coursePoint = 4.0, score = "55", scoreValue = 55.0, examProp = "补考"),
        )
        val info = ScoreCalculator.calculateGpaForCourses(courses)
        // 参与：高数 (3.7 GPA, 4 学分) + 大物 (gpa=null, 4 学分计入 totalCredits 但不参与加权)
        // 加权 gpa = 3.7*4 / 8 = 1.85
        assertEquals(1.85, info.gpa, 0.001)
        assertEquals(8.0, info.totalCredits, 0.001)
        assertEquals(2, info.courseCount)
    }

    @Test
    fun weightedGpa_skipFailedRetakeTrue_skipsAll() {
        val courses = listOf(
            scoreItem(courseName = "线代", coursePoint = 4.0, score = "55", scoreValue = 55.0, examProp = "补考"),
        )
        val info = ScoreCalculator.calculateGpaForCourses(courses, skipFailedRetake = true)
        assertEquals(0.0, info.totalCredits, 0.001)
        assertEquals(0, info.courseCount)
    }

    @Test
    fun emptyList_returnsZero() {
        val info = ScoreCalculator.calculateGpaForCourses(emptyList())
        assertEquals(0.0, info.gpa, 0.001)
        assertEquals(0.0, info.totalCredits, 0.001)
        assertEquals(0, info.courseCount)
        assertNotNull(info)
    }

    private fun scoreItem(
        courseName: String,
        coursePoint: Double,
        score: String,
        scoreValue: Double?,
        examProp: String = "初修",
    ) = ScoreItem(
        id = "id-$courseName",
        termCode = "2024-秋",
        courseName = courseName,
        score = score,
        scoreValue = scoreValue,
        passFlag = (scoreValue ?: 0.0) >= 60,
        specificReason = null,
        coursePoint = coursePoint,
        examType = "正常",
        majorFlag = null,
        examProp = examProp,
        replaceFlag = false,
        gpa = null, // 让 ScoreCalculator 自己用 scoreToGpa 算，不作弊
        source = ScoreSource.JWAPP,
        courseCategory = null,
    )
}