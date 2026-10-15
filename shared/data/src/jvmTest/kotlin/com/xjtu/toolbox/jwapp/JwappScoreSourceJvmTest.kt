package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.auth.withJwappLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 移动教务取数（`AppJwappScoreSource` 包住的两个上游）的字段级口径。 */
class JwappScoreSourceJvmTest {

    @Test
    fun `登录：开放平台 appId=1370 转统一认证，令牌从回跳 URL 里抠出来当 Authorization`() {
        assertEquals(JwappFakeUpstream.OAUTH_URL, com.xjtu.toolbox.auth.JwappLogin.JWAPP_URL)

        withJwappLogin { jwapp, _, fake ->
            assertTrue(jwapp.hasLogin, "真登录应当成功")
            // 真提交过一次凭据（CAS 那半台是图书馆夹具在扮演）
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
            // 开放平台那一跳与带令牌的回跳都真的走过
            assertTrue(fake.jwapp.oauthLandings.get() > 0, "应当经过开放平台 authorize")
            assertTrue(fake.jwapp.tokenLandings.get() > 0, "应当落在 app/index?token=… 上")

            val source = AppJwappScoreSource(requireNotNull(jwapp.manager) { "站点上应当挂着会话管家" })
            runBlocking { source.getGrade(null) }
            // 业务请求带的是从回跳 URL 里抠出来的那枚令牌（不是别的头、也不是空）
            assertEquals(JwappFakeUpstream.TOKEN, fake.jwapp.lastAuthorization, "业务请求应当带移动教务令牌")
        }
    }

    @Test
    fun `学期成绩：termScore 的学期分组与字段口径（粗分、学分、考试属性）`() {
        withJwappLogin { jwapp, _, fake ->
            val source = AppJwappScoreSource(requireNotNull(jwapp.manager) { "站点上应当挂着会话管家" })
            val terms = runBlocking { source.getGrade(null) }

            assertEquals(1, terms.size, "夹具只有一个学期")
            val term = terms.single()
            assertEquals(JwappFakeUpstream.TERM_CODE, term.termCode)
            assertEquals(JwappFakeUpstream.TERM_NAME, term.termName)

            val course = term.scoreList.single()
            assertEquals(JwappFakeUpstream.COURSE_ID, course.id)
            assertEquals(JwappFakeUpstream.COURSE_NAME, course.courseName)
            assertEquals(JwappFakeUpstream.COURSE_SCORE, course.score)
            assertEquals(JwappFakeUpstream.COURSE_SCORE.toDouble(), course.scoreValue)
            assertEquals(JwappFakeUpstream.COURSE_POINT, course.coursePoint)
            assertEquals(JwappFakeUpstream.COURSE_EXAM_TYPE, course.examType)
            assertEquals(JwappFakeUpstream.COURSE_EXAM_PROP, course.examProp)
            assertTrue(course.passFlag, "夹具那条是及格")
            assertEquals(ScoreSource.JWAPP, course.source)

            assertEquals("""{"termCode":"*"}""", fake.jwapp.lastGradeBody)
        }
    }

    @Test
    fun `精确成绩：教务 xscjcx 给精确总评与绩点，按学期 + 课名两条都读得出`() {
        withJwappLogin { jwapp, _, fake ->
            val source = AppJwappScoreSource(requireNotNull(jwapp.manager) { "站点上应当挂着会话管家" })
            val precise = runBlocking { source.preciseScores() }

            assertEquals(2, precise.size, "夹具给了两行（同学期一行 + 另一学期一行）")
            val target = precise.first { it.courseName == JwappFakeUpstream.COURSE_NAME }
            assertEquals(JwappFakeUpstream.TERM_CODE, target.termCode)
            assertEquals(88.0, target.zcj, "精确总评（比 jwapp 的粗分 86 更准）")
            assertEquals(3.9, target.xfjd)
            assertEquals(5.0, target.xf)
            assertEquals("MATH1001", target.kch)
            assertTrue(target.passFlag, "SFJG=1 ⇒ 及格")

            val other = precise.first { it.courseName != JwappFakeUpstream.COURSE_NAME }
            assertEquals("2025-2026-2", other.termCode, "另一学期那一行照读（学期号原样）")

            // 表单里该带的三个条件都在（少了 querySetting 就不是「有效成绩」，少了分页会无限翻）
            val form = requireNotNull(fake.jwxt.lastCjcxForm) { "应当真发过一次 xscjcx" }
            assertTrue(form.contains("querySetting="), "要带 querySetting")
            assertTrue(form.contains("SFYX"), "querySetting 里要按「是否有效」筛")
            assertTrue(form.contains("pageSize=100"), "要带 pageSize=100")
            assertTrue(form.contains("pageNumber=1"), "要从第一页开始")
        }
    }

    @Test
    fun `分项成绩：itemPercent 是带百分号的字符串，折算成 0 到 1 的小数`() {
        withJwappLogin { jwapp, _, fake ->
            val source = AppJwappScoreSource(requireNotNull(jwapp.manager) { "站点上应当挂着会话管家" })
            val detail = runBlocking { source.getScoreDetail(JwappFakeUpstream.COURSE_ID) }

            assertEquals(JwappFakeUpstream.COURSE_NAME, detail.courseName)
            assertEquals(JwappFakeUpstream.DETAIL_SCORE, detail.score)
            assertEquals(JwappFakeUpstream.DETAIL_GPA, detail.gpa)
            assertEquals(2, detail.itemList.size, "夹具两个分项")
            val first = detail.itemList.first()
            assertEquals(JwappFakeUpstream.ITEM_1_NAME, first.itemName)
            assertEquals(0.3, first.itemPercent, "“30%” ⇒ 0.3")
            assertEquals(JwappFakeUpstream.ITEM_1_SCORE, first.itemScore)
            assertEquals(0.7, detail.itemList[1].itemPercent, "“70%” ⇒ 0.7")

            assertEquals("""{"id":"${JwappFakeUpstream.COURSE_ID}"}""", fake.jwapp.lastDetailBody)
        }
    }

}
