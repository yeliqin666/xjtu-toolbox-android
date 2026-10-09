package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.schedule.SchoolCourseQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 全校课表的 campus-api 映射**真数据**验证（本机 campus-api `127.0.0.1:3099`）。
 *
 * 默认**跳过**（依赖本机服务，不能进 CI）：
 *   `TOOLBOX_PROBE_LIVE=1 ./gradlew :core:jvmTest`
 *
 * 为什么值得有：Web 端的 `SchoolCourse` 是从 campus-api 的**归一投影**拼出来的，
 * 字段名与 :app 用的上游原始键**不同**（`classNo`/`classGroup`/`courseKind`/`id`…）。
 * 这段映射没有别的东西盯着 —— 上游或投影一改，只有这条断言会红。
 */
class CampusSchoolCourseApiLiveTest {

    private val enabled: Boolean = System.getenv("TOOLBOX_PROBE_LIVE") == "1"
    private val baseUrl: String = System.getenv("TOOLBOX_PROBE_BASE") ?: "http://127.0.0.1:3099"

    @Test
    fun realSchoolCourses() {
        if (!enabled) {
            println("[探针] 跳过全校课表真服务器测试；设 TOOLBOX_PROBE_LIVE=1 启用（base=$baseUrl）")
            return
        }
        runBlocking {
            val client = createToolboxClient(cookieStorage = MemoryCookieJar())
            val api = CampusSchoolCourseApi(client, baseUrl)
            try {
                val term = api.currentTerm()
                println("[探针] jwxt.term=$term")
                assertTrue(term.contains("-"), "学期号形状应为 2026-2027-1，实际 $term")

                val terms = api.terms()
                println("[探针] terms=${terms.size} 首项=${terms.firstOrNull()}")
                assertTrue(terms.isNotEmpty(), "学期列表不应为空")
                // 学期名是从学期号推出来的，形状必须与 :app 的上游名字一致
                assertTrue(
                    terms.all { it.name.contains("学年") && it.name.contains("学期") },
                    "学期名应从学期号推出「XXXX-XXXX学年 第N学期」，实际 ${terms.take(3).map { it.name }}",
                )

                // 按课程名查一门必定存在的课，检查归一字段 → 共享模型的映射
                val result = api.query(SchoolCourseQuery(termCode = term, courseName = "高等数学"), page = 1, pageSize = 5)
                println("[探针] totalSize=${result.totalSize} 本页=${result.courses.size}")
                val sample = result.courses.firstOrNull()
                if (sample != null) {
                    println("[探针] 首条=${sample.courseName} / ${sample.courseCode} / 课序号${sample.sectionNumber} / ${sample.teacher} / ${sample.department} / ${sample.credit}学分 / ${sample.campus}")
                    assertTrue(sample.courseName.isNotBlank(), "课程名不应为空")
                    assertTrue(sample.courseCode.isNotBlank(), "课程号不应为空（campus-api 的 courseCode）")
                    assertTrue(sample.sectionNumber.isNotBlank(), "课序号不应为空（campus-api 的 classNo）")
                    assertTrue(sample.credit > 0.0, "学分应大于 0（campus-api 的 credit）")
                    assertTrue(sample.termCode == term, "termCode 应来自响应的 term 字段")
                }
                // 人数/学时在 campus-api 侧**刻意不给** ⇒ 必须是 null（不是 0）
                assertTrue(
                    result.courses.all { it.capacity == null && it.enrollCount == null && it.totalHours == null },
                    "campus-api 不投影人数/学时 ⇒ 共享模型里应是 null，不是 0",
                )
            } finally {
                client.close()
            }
        }
    }
}
