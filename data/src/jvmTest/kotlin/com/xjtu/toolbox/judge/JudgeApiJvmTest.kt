package com.xjtu.toolbox.judge

import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 本科评教取数的**字段级口径**：`JudgeApi` 对着 `:testkit` 的 `JwxtFakeUpstream` 逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 「评教」这一轮把取数从 `:app` 搬进了 `:data`（桌面端第 8 条真数据路由）。搬之前先用夹具把
 * **搬之前**的口径钉住：`:app` 那边一行逻辑没改，只是文件换了地方 —— 但「没改」这句话得有证据，
 * 这些断言就是那个证据。手法与全校课表那次（`SchoolCourseApiJvmTest`）逐条对齐：走**真登录链**
 * （[withJwxtLogin]：CAS 表单 POST → TGC → 签 ticket → 回跳教务），URL 一个字符都不改。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`JwxtFakeUpstream` 里那些 JSON）与 `:core` 已钉过的口径（`safeInt`/
 * `safeString` 的默认值），**不是从跑通的实现里抄回来的** —— 夹具刻意留空几格（未评第二行缺
 * `DBRS/JSSJ/KCH`、已评那行缺 `BPJS`、主观题缺 `FZ`），「缺字段给什么」因此写在明面上。
 *
 * 请求那一侧的断言同样对着夹具反查：夹具只认带 `PJXNXQ` 的学期查询、带六个 `querySetting`
 * 条件的选项查询，而那些条件该不该出现、以什么形状出现，在这里钉住。
 */
class JudgeApiJvmTest {

    /** 参评人（`CPR`）：真机上就是登录那个学号，夹具只认它。 */
    private val username = LibraryFakeUpstream.USERNAME

    @Test
    fun `当前学期与两份列表：行序、缺字段的默认值、以及四条请求的形状`() {
        withJwxtLogin { site, fake ->
            val api = JudgeApi(site)
            // 学期：`datas.cxxtcs.rows[0].CSZA`。那一枪必须真说明「问的是当前学年学期」。
            assertEquals(JwxtFakeUpstream.JUDGE_TERM, runBlocking { api.getCurrentTerm() })
            assertTrue(
                fake.jwxt.lastJudgeTermForm?.contains("PJXNXQ") == true,
                "学期查询的 setting 该带 PJXNXQ：${fake.jwxt.lastJudgeTermForm}",
            )

            val unfinished = runBlocking { api.unfinishedQuestionnaires() }
            // `unfinishedQuestionnaires` = 过程 + 期末 ⇒ **过程那一条排在前面**（夹具各给一份）
            assertEquals(
                listOf(
                    JwxtFakeUpstream.JUDGE_COURSE_MID,
                    JwxtFakeUpstream.JUDGE_COURSE_FINAL,
                    JwxtFakeUpstream.JUDGE_COURSE_FINAL_BARE,
                ),
                unfinished.map { it.KCM },
            )

            // ── 第一行（过程评教）：夹具把 14 个键都给全了，逐字段对齐 ──
            val mid = unfinished[0]
            assertEquals("示例丁", mid.BPJS)
            assertEquals("示例丁", mid.BPR)
            assertEquals(0, mid.DBRS)
            assertEquals("2026-10-25 23:59:00", mid.JSSJ)
            assertEquals("JXB-9002", mid.JXBID)
            assertEquals("MATH1001", mid.KCH)
            assertEquals(JwxtFakeUpstream.JUDGE_COURSE_MID, mid.KCM)
            assertEquals("2026-10-01 08:00:00", mid.KSSJ)
            assertEquals("PCDM-2026-05", mid.PCDM)
            assertEquals("05", mid.PGLXDM)
            assertEquals("过程评教（第一次）", mid.PGNR)
            assertEquals(JwxtFakeUpstream.JUDGE_WJDM_MID, mid.WJDM)
            assertEquals("过程评教问卷", mid.WJMC)
            assertEquals(JwxtFakeUpstream.JUDGE_TERM, mid.XNXQDM)

            // ── 期末第一行：`DBRS` 是个数字（`safeInt`），学期与上面同一个 ──
            val final = unfinished[1]
            assertEquals(2, final.DBRS)
            assertEquals(JwxtFakeUpstream.JUDGE_BPR_FINAL, final.BPR)
            assertEquals(JwxtFakeUpstream.JUDGE_JXBID_FINAL, final.JXBID)
            assertEquals("01", final.PGLXDM)
            assertEquals(JwxtFakeUpstream.JUDGE_PGNR, final.PGNR)

            // ── 期末第二行：夹具**没给** `DBRS`/`JSSJ`/`KCH` ⇒ 0 / 空串（不是「猜一个」）──
            val bare = unfinished[2]
            assertEquals(0, bare.DBRS)
            assertEquals("", bare.JSSJ)
            assertEquals("", bare.KCH)

            // ── 已评：期末一行、过程是**空表**（空表不是报错）──
            val finished = runBlocking { api.finishedQuestionnaires() }
            assertEquals(listOf(JwxtFakeUpstream.JUDGE_COURSE_DONE), finished.map { it.KCM })
            // 这一行夹具刻意没有 `BPJS` ⇒ 空串
            assertEquals("", finished[0].BPJS)
            assertEquals(JwxtFakeUpstream.JUDGE_WJDM_DONE, finished[0].WJDM)
            assertEquals(JwxtFakeUpstream.JUDGE_JXBID_DONE, finished[0].JXBID)

            // ── 四次列表请求：05/0、01/0、05/1、01/1，且「只给开放中且已发布」两条一直都在 ──
            val calls = fake.jwxt.judgeListForms.map { formFields(it) }
            assertEquals(
                listOf("05" to "0", "01" to "0", "05" to "1", "01" to "1"),
                calls.map { it.getValue("PGLXDM") to it.getValue("SFPG") },
            )
            assertEquals(4, fake.jwxt.judgeListCalls.get())
            for (fields in calls) {
                assertEquals("1", fields["SFKF"], "只要开放中的问卷：$fields")
                assertEquals("1", fields["SFFB"], "只要已发布的问卷：$fields")
                assertEquals(JwxtFakeUpstream.JUDGE_TERM, fields["XNXQDM"])
            }
            // 学期一共只问了两次：开场我自己问的一次，两份列表合起来再问一次（`cachedTerm` 复用）
            // —— 缓存没了的话这里会变成 3，一眼看得出来。
            assertEquals(2, fake.jwxt.judgeTermCalls.get())
            // 预热那一枪（`wspjyyapp` 应用首页）真的打过：少了它，真站点上后面几个接口会回空 rows
            assertTrue(fake.jwxt.judgeIndexCalls.get() >= 1, "进 wspjyyapp 之前应先预热应用首页")
        }
    }

    @Test
    fun `问卷题目与选项：三种题型、选项按 DAPX 精确取值、以及发出去的条件`() {
        withJwxtLogin { site, fake ->
            val api = JudgeApi(site)
            val q = runBlocking { api.unfinishedQuestionnaires() }
                .first { it.WJDM == JwxtFakeUpstream.JUDGE_WJDM_FINAL }

            val questions = runBlocking { api.getQuestionnaireData(q, username) }
            assertEquals(
                listOf("ZB-01", "ZB-02", "ZB-03", "ZB-04", "ZB-05", "ZB-06"),
                questions.map { it.ZBDM },
            )
            val first = questions[0]
            assertEquals(JwxtFakeUpstream.JUDGE_WJDM_FINAL, first.WJDM)
            assertEquals("教学态度", first.ZBMC)
            assertEquals("01", first.TXDM)
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DADM_BEST, first.DADM)
            assertEquals("1", first.SFBT)
            assertEquals("100", first.FZ)
            // 这几项不来自响应，而是**由问卷本身补齐**（参评人 / 被评人 / 评估内容 / 批次 / 教学班）
            assertEquals(username, first.CPR)
            assertEquals(q.BPR, first.BPR)
            assertEquals(q.PGNR, first.PGNR)
            assertEquals(q.PCDM, first.PCDM)
            assertEquals(q.JXBID, first.JXBID)
            // 答案与序号是初值：什么都没答、序号默认 "1"
            assertEquals("", first.DA)
            assertEquals("", first.ZGDA)
            assertEquals("1", first.DAXH)
            // 主观题夹具**没给** `FZ` ⇒ null（不是 0、也不是空串）
            assertNull(questions[1].FZ)
            // 非必填题（`SFBT=0`）照样带上来，屏与填卷逻辑自己按 SFBT 决定要不要答
            assertEquals("0", questions[3].SFBT)

            val questionForm = formFields(requireNotNull(fake.jwxt.lastJudgeQuestionForm))
            assertEquals(JwxtFakeUpstream.JUDGE_WJDM_FINAL, questionForm["WJDM"])
            assertEquals(JwxtFakeUpstream.JUDGE_JXBID_FINAL, questionForm["JXBID"])

            // ── 选项：只有 `ZB-01` 有三道，且是**倒序**给的 ──
            val options = runBlocking { api.getQuestionnaireOptions(q, username) }
            assertEquals(setOf("ZB-01"), options.keys)
            val list = options.getValue("ZB-01")
            assertEquals(listOf("3", "2", "1"), list.map { it.DAPX }, "夹具给的就是倒序")
            val best = list.first { it.DAPX == "1" }
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DADM_BEST, best.DADM)
            // ⚠️ 填卷写进卷子的是**选项编号**（响应里的 `DAFXDM`），不是 `DADM`。第三行里那个错的
            // `DA:"WRONG"` 就是为这一条摆的：读错键的话下面这一行拿到的是 `WRONG`。
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DA_BEST, best.DA)
            assertEquals("教学态度", best.ZBMC)
            assertEquals("01", best.TXDM)
            assertEquals("100", best.FZ)

            val optionForm = formFields(requireNotNull(fake.jwxt.lastJudgeOptionForm))
            assertEquals(username, optionForm["CPR"])
            assertEquals(q.PCDM, optionForm["PCDM"])
            assertEquals(q.BPR, optionForm["BPR"])
            assertEquals(q.PGNR, optionForm["PGNR"])
            assertEquals(JwxtFakeUpstream.JUDGE_WJDM_FINAL, optionForm["WJDM"])
            assertEquals("0", optionForm["SFPG"], "未评的卷子问的是「还没评」那一份")
            val setting = optionForm.getValue("querySetting")
            for (name in JwxtFakeUpstream.JUDGE_OPTION_CONDITIONS) {
                assertTrue("\"name\":\"$name\"" in setting, "$name 该在条件里：$setting")
            }
            assertTrue("\"value\":\"$username\"" in setting, "参评人该是登录那个学号：$setting")
        }
    }

    @Test
    fun `一键填卷：客观题按 DAPX 取最优、主观题与分值题、非必填跳过、两种兜底`() {
        withJwxtLogin { site, _ ->
            val api = JudgeApi(site)
            val q = runBlocking { api.unfinishedQuestionnaires() }
                .first { it.WJDM == JwxtFakeUpstream.JUDGE_WJDM_FINAL }

            val filled = runBlocking { api.autoFillQuestionnaire(q, username) }
            val byId = filled.associateBy { it.ZBDM }

            // `ZB-01`：选项表里有同名 `ZBDM` ⇒ 取 `DAPX=1` 那一道（填进去的是**选项编号** 100）
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DA_BEST, byId.getValue("ZB-01").DA)
            // `ZB-02`：主观题 ⇒ 答案编号清空、主观答案填上（内容由实现给，这里只钉「填了」）
            assertEquals("", byId.getValue("ZB-02").DA)
            assertTrue(byId.getValue("ZB-02").ZGDA.isNotBlank(), "主观题应当被填上文字")
            // `ZB-03`：分值题 ⇒ 取 `FZ` 那个最高分
            assertEquals("100", byId.getValue("ZB-03").DA)
            // `ZB-04`：非必填且选项表里没有 ⇒ **跳过**（不报错、也不瞎填）
            assertEquals("", byId.getValue("ZB-04").DA)
            // `ZB-05`：`ZBDM` 不在选项表 ⇒ 靠 `DADM` 兜底拿到那唯一一道（`DAPX=2`）⇒ `DA=80`
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DA_SECOND, byId.getValue("ZB-05").DA)
            // `ZB-06`：`DADM` 是空的 ⇒ 靠**题名**兜底（`教学态度：` 归一化后等于 `教学态度`）
            assertEquals(JwxtFakeUpstream.JUDGE_OPTION_DA_BEST, byId.getValue("ZB-06").DA)
        }
    }

    /** 表单原文 → 字段表（与夹具那一侧同一个写法：`application/x-www-form-urlencoded`）。 */
    private fun formFields(form: String): Map<String, String> =
        form.split('&').filter { it.isNotEmpty() }.associate { part ->
            val raw = part.substringAfter('=', "")
            part.substringBefore('=') to URLDecoder.decode(raw, "UTF-8")
        }
}
