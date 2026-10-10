package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.auth.withJwxtLogin
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 全校课表取数的**字段级口径**：`SchoolCourseApi` 对着 `:testkit` 的 `JwxtFakeUpstream` 逐字段读。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`JwxtFakeUpstream` 里那些 JSON 字符串）与 `:core` 里已经钉过的口径
 * （`safeInt/safeDouble` 的默认值、`TermOption` 的 `MC ?: DM` 兜底、按名字排序），
 * **不是从跑通的实现里抄回来的** —— 夹具第二、三行刻意缺几个键，就是为了让「缺字段给什么」
 * 有一个写在明面上的答案。
 *
 * 请求那一侧的断言（`lastQueryForm` 里的 `querySetting`）同样对着夹具反查：夹具只认带
 * `*order=-DM` / `querySetting=` 的请求，而那些条件该不该出现、以什么形状出现，在这里钉住。
 */
class SchoolCourseApiJvmTest {

    /** 夹具与生产必须指同一个站点：URL 漂了的话，下面这些断言测的就不是真协议。 */
    @Test
    fun `夹具与生产都指向同一个教务入口页`() {
        assertEquals(XJTULogin.JWXT_URL, JwxtFakeUpstream.HOME_URL)
    }

    @Test
    fun `学期、当前学期与开课单位：行序照上游、没名字退回代码、单位按名字排序`() {
        withJwxtLogin { site, fake ->
            val api = SchoolCourseApi(site)

            // 学期列表：**行序就是夹具给的序**（上游按 `*order=-DM` 排好），解析不做二次排序；
            // 第三行只有 DM ⇒ 名字退回 DM。
            assertEquals(
                listOf(
                    TermOption(JwxtFakeUpstream.TERM_NEW, JwxtFakeUpstream.TERM_NEW_NAME),
                    TermOption(JwxtFakeUpstream.TERM_OLD, JwxtFakeUpstream.TERM_OLD_NAME),
                    TermOption(JwxtFakeUpstream.TERM_BARE, JwxtFakeUpstream.TERM_BARE),
                ),
                runBlocking { api.getTermList() },
            )
            // 那次请求必须真带排序参数（少了它，上面这条断言就只是「夹具恰好给了这个序」）
            assertTrue(
                fake.jwxt.lastTermListForm?.contains("*order=-DM") == true,
                "学期列表请求应带 *order=-DM：${fake.jwxt.lastTermListForm}",
            )

            assertEquals(JwxtFakeUpstream.TERM_NEW, runBlocking { api.getCurrentTerm() })

            // 开课单位：夹具**乱序给**，解析按名字升序排（String 的码元序）
            assertEquals(
                listOf(
                    JwxtFakeUpstream.DEPT_MATH,
                    JwxtFakeUpstream.DEPT_MECH,
                    JwxtFakeUpstream.DEPT_PHYSICS,
                ),
                runBlocking { api.getDepartments() }.map { it.name },
            )
            // 预热那一枪（`kcbcx` 应用首页）真的打过：少了它，真站点上后面几个接口会回空 rows
            assertTrue(fake.jwxt.appIndexCalls.get() >= 1, "进 kcbcx 之前应先预热应用首页")
        }
    }

    @Test
    fun `全校课表查询：逐字段解析、缺字段的默认值、以及发出去的条件`() {
        withJwxtLogin { site, fake ->
            val api = SchoolCourseApi(site)
            val result = runBlocking {
                api.queryCourses(
                    termCode = JwxtFakeUpstream.TERM_NEW,
                    courseName = "大学",
                    pageSize = 20,
                    pageNumber = 1,
                )
            }

            assertEquals(JwxtFakeUpstream.TOTAL_SIZE, result.totalSize)
            assertEquals(1, result.pageNumber)
            assertEquals(20, result.pageSize)
            assertEquals(1, result.totalPages)

            // ── 第 1 行：夹具把每个键都给全了 ──
            val first = result.courses[0]
            assertEquals("MATH1001", first.courseCode)
            assertEquals(JwxtFakeUpstream.COURSE_1_NAME, first.courseName)
            assertEquals("01", first.sectionNumber)
            assertEquals("示例甲", first.teacher)
            assertEquals(JwxtFakeUpstream.DEPT_MATH, first.department)
            assertEquals(5.0, first.credit)
            assertEquals(80.0, first.totalHours)
            assertEquals(80.0, first.lectureHours)
            assertEquals(0.0, first.labHours)
            assertEquals(0.0, first.practiceHours)
            assertEquals(120, first.enrollCount)
            assertEquals(150, first.capacity)
            assertEquals(30, first.remaining)
            assertEquals(0.8f, first.fillRatio)
            assertEquals("电气2401-2402", first.className)
            assertEquals("兴庆校区 主楼A101 周一 1-2节", first.scheduleLocation)
            assertEquals("兴庆校区", first.campus)
            assertEquals(false, first.isPublicElective)
            assertEquals("", first.electiveCategory)
            assertEquals(5.0, first.weeklyHours)
            assertEquals(80, first.maleEnrollCount)
            assertEquals(40, first.femaleEnrollCount)
            assertEquals("JXB-1001", first.teachingClassId)
            assertEquals(JwxtFakeUpstream.TERM_NEW, first.termCode)

            // ── 第 2 行：夹具**没给** XKZRS/SJXS/NSXKRS/NVSXKRS ⇒ `safeInt/safeDouble` 的默认值 0 ──
            val second = result.courses[1]
            assertEquals(0, second.enrollCount)
            assertEquals(0.0, second.practiceHours)
            assertEquals(0, second.maleEnrollCount)
            assertEquals(0, second.femaleEnrollCount)
            assertEquals(120, second.capacity)
            assertEquals(120, second.remaining)
            assertEquals(16.0, second.labHours)
            assertEquals(4.0, second.weeklyHours)
            assertEquals(true, second.isPublicElective)
            assertEquals("基础通识类核心课", second.electiveCategory)
            assertEquals("兴庆校区 中2楼1200 周三 3-4节", second.scheduleLocation)

            // ── 第 3 行：夹具**没给** YPSJDD/KNZXS/NSXKRS… ⇒ 空串 / 0 ──
            val third = result.courses[2]
            assertEquals("", third.scheduleLocation)
            assertEquals(0.0, third.weeklyHours)
            assertEquals(0, third.maleEnrollCount)
            assertEquals(32.0, third.totalHours)
            assertEquals(2.0, third.credit)
            assertEquals("人文社会科学学院", third.department)

            // ── 请求形状：条件真写进了 querySetting，分页参数在表单上 ──
            val form = requireNotNull(fake.jwxt.lastQueryForm) { "夹具没收到课表查询" }
            val setting = decodeFormField(form, "querySetting")
            assertTrue("\"name\":\"KCM\"" in setting, "课程名条件该在：$setting")
            assertTrue("\"builder\":\"include\"" in setting, "课程名应是模糊匹配：$setting")
            assertTrue("大学" in setting, "课程名条件里该是输入的值：$setting")
            assertTrue(
                "\"name\":\"XNXQDM\",\"value\":\"${JwxtFakeUpstream.TERM_NEW}\"" in setting,
                "学期是必选条件：$setting",
            )
            // 没给的筛选项一个都不该出现（这一条与下面那条适配器测试是同一件事的两端）
            for (name in listOf("KCH", "SKJS", "KKDWDM", "SKBJ", "XXXQDM", "XGXKLBDM", "SFXGXK")) {
                assertTrue("\"name\":\"$name\"" !in setting, "$name 不该出现：$setting")
            }
            assertTrue("*order=" in form, "排序参数该在表单上：$form")
            assertTrue("pageSize=20" in form && "pageNumber=1" in form, form)
        }
    }

    @Test
    fun `端口适配器：空串与 0 表示不限，不写进条件；分页参数照传`() {
        withJwxtLogin { site, fake ->
            val source: SchoolCourseSource = AppSchoolCourseSource(site)
            val result = runBlocking {
                source.query(SchoolCourseQuery(termCode = JwxtFakeUpstream.TERM_NEW), page = 2, pageSize = 5)
            }

            assertEquals(JwxtFakeUpstream.TOTAL_SIZE, result.totalSize)
            assertEquals(2, result.pageNumber)
            assertEquals(5, result.pageSize)

            val form = requireNotNull(fake.jwxt.lastQueryForm)
            val setting = decodeFormField(form, "querySetting")
            // 界面上「不限」的写法（空串 / 0）不能再变成条件 —— 否则第一页之后条数会越筛越少
            for (name in listOf("KCM", "KCH", "SKJS", "KKDWDM", "SKBJ", "XXXQDM", "XGXKLBDM", "SFXGXK")) {
                assertTrue("\"name\":\"$name\"" !in setting, "$name 不该出现：$setting")
            }
            // 必选条件与排序还在
            assertTrue("\"name\":\"XNXQDM\"" in setting, setting)
            assertTrue("\"name\":\"*order\"" in setting, setting)
            // 星期 / 节次也是空串（0 ⇒ null ⇒ 空）
            assertTrue("&SKXQ=&KSJC=&JSJC=" in form, "星期与节次不该带值：$form")
            assertTrue("pageSize=5" in form && "pageNumber=2" in form, form)
        }
    }

    /** 校区与公选类别表在 `:app` 与 `:core` 各有一份（屏幕用后者）—— 两份不许漂。 */
    @Test
    fun `硬编码的校区与公选类别表与 core 那份一致`() {
        withJwxtLogin { site, _ ->
            val api = SchoolCourseApi(site)
            assertEquals(SCHOOL_COURSE_CAMPUSES, api.getCampusList())
            assertEquals(SCHOOL_COURSE_ELECTIVE_CATEGORIES, api.getElectiveCategories())
        }
    }

    private fun decodeFormField(form: String, name: String): String =
        URLDecoder.decode(
            form.split('&').first { it.substringBefore('=') == name }.substringAfter('='),
            "UTF-8",
        )
}
