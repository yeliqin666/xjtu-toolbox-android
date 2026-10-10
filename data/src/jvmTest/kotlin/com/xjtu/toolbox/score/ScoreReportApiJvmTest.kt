package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * 成绩报表取数的**字段级口径**：`ScoreReportApi` 对着 `:testkit` 的 `JwxtFakeUpstream` 逐行读。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（那两页表格 HTML 与初始页）与 `:core` 里已经钉过的口径
 * （`ScoreCalculator.scoreToGpa` 的映射、`termCodeFromHeading` 的学期代码）。夹具第一页刻意放了
 * 四类「不该成行」的行（两列行、标题之前的三列行、表头、学分行不是数字的行），
 * 「什么被跳过」因此有一个写在明面上的答案。
 *
 * 成绩报表那条链的**分组口径**（学期代码）与 `ScoreReportTermTest` 是同一件事的两端：
 * 那边钉纯字符串 → 代码，这边钉「真页面上那个标题被读成了哪个代码」。
 */
class ScoreReportApiJvmTest {

    @Test
    fun `成绩报表：会话 id、总页数、逐页解析与跳过的行`() {
        withJwxtLogin { site, fake ->
            val api = ScoreReportApi(site)
            val grades = runBlocking { api.getReportedGrade(JwxtFakeUpstream.STUDENT_ID) }

            // 初始页那一枪带着学号；翻页用的是夹具发的会话 id；两页都真打过
            // （页数是从第 1 页表格里读出来的 `FR._p.reportTotalPage`，所以 pn=1 也要算一枪）
            assertEquals(JwxtFakeUpstream.STUDENT_ID, fake.jwxt.lastReportStudentId)
            assertEquals(JwxtFakeUpstream.FR_SESSION_ID, fake.jwxt.lastReportSessionId)
            assertEquals(JwxtFakeUpstream.REPORT_TOTAL_PAGES, fake.jwxt.reportPageCalls.get())

            // 三行成课；其余四行（两列行 / 标题之前的三列行 / 表头 / 学分行不是数字）都被跳过。
            // 学分与成绩都按表格原文；GPA 走 `ScoreCalculator` 的映射：
            //   "95" → 4.3（数字分段表）；"优秀" → null（中文等级表只认「优/良/中/及格」那一套，
            //   「优秀」不在其中 ⇒ 不参与加权）；"88" → 3.7
            assertEquals(
                listOf(
                    ReportedGrade(JwxtFakeUpstream.SCORE_1_NAME, 5.0, "95", 4.3, JwxtFakeUpstream.SCORE_TERM_SPRING),
                    ReportedGrade(JwxtFakeUpstream.SCORE_2_NAME, 4.0, "优秀", null, JwxtFakeUpstream.SCORE_TERM_SPRING),
                    ReportedGrade(JwxtFakeUpstream.SCORE_3_NAME, 1.0, "88", 3.7, JwxtFakeUpstream.SCORE_TERM_SUMMER),
                ),
                grades,
            )
        }
    }

    @Test
    fun `端口适配器：学号闭在适配器里，学期代码降序就是时间降序`() {
        withJwxtLogin { site, fake ->
            val source: ScoreReportSource = scoreReportSource(site, JwxtFakeUpstream.STUDENT_ID)
            val grades = runBlocking { source.grades() }

            assertEquals(3, grades.size)
            assertEquals(JwxtFakeUpstream.STUDENT_ID, fake.jwxt.lastReportStudentId)

            // 屏上的学期分组（`:core` 的 `groupByTermDescending` 是 private）依赖的正是这两个事实：
            // 学期标题都读成了可比大小的代码，且**夏季小学期**得到 `-3`
            //（以前认不出「夏季」「暑期」「短」这些写法，小学期的课被并进了春季学期）。
            assertEquals(
                listOf(JwxtFakeUpstream.SCORE_TERM_SUMMER, JwxtFakeUpstream.SCORE_TERM_SPRING),
                grades.map { it.term }.distinct().sortedDescending(),
            )
        }
    }
}
