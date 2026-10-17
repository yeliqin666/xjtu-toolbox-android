package com.xjtu.toolbox.score

import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** 成绩报表取数的字段级口径：`ScoreReportApi` 对着 `:testkit` 的 `JwxtFakeUpstream` 逐行读。 */
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

            assertEquals(
                listOf(JwxtFakeUpstream.SCORE_TERM_SUMMER, JwxtFakeUpstream.SCORE_TERM_SPRING),
                grades.map { it.term }.distinct().sortedDescending(),
            )
        }
    }
}
