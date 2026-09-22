package com.xjtu.toolbox.judge

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraduateJudgeApiTest {

    // 按 gste genForm.do 页面里 pjzbApp.form 的结构缩写：隐藏字段、同一行 label + 控件、webix.rules 引用、尾逗号
    private val formHtml = """
        <script>
        pjzbApp.form = {
          "view": "form",
          "elements": [
            {"view": "text", "id": "pjid", "value": "9527", "hidden": true},
            {"view": "text", "id": "kcmc_q", "label": "课程名称"},
            {"view": "text", "id": "skjs_q", "label": "上课教师"},
            {"cols": [
              {"view": "label", "label": "教材情况"},
              {"view": "radio", "id": "jcqk", "options": [{"id": "0", "value": "无"}, {"id": "1", "value": "自编讲义"}, {"id": "2", "value": "有教材"}]}
            ]},
            {"view": "text", "id": "jcmc", "label": "教材名称"},
            {"view": "radio", "id": "jcyy", "label": "教材使用语言", "options": [{"id": "a", "value": "中文"}, {"id": "b", "value": "英文"}, {"id": "c", "value": "无教材"}]},
            {"view": "radio", "id": "skyy", "label": "授课语言", "options": [{"id": "1", "value": "中文"}, {"id": "2", "value": "全英文"}, {"id": "3", "value": "中英文"}, {"id": "4", "value": "其他"}]},
            {"view": "radio", "id": "xxqk", "label": "选修情况", "options": [{"id": "1", "value": "学位课"}, {"id": "2", "value": "选修课"}]},
            {"view": "radio", "id": "q1", "label": "教学态度", "options": [{"id": "100", "value": "优秀"}, {"id": "80", "value": "良好"}, {"id": "60", "value": "合格"}, {"id": "40", "value": "不合格"}]},
            {"view": "radio", "id": "q2", "label": "教学内容", "options": [{"id": "100", "value": "优秀"}, {"id": "80", "value": "良好"}, {"id": "60", "value": "合格"}, {"id": "40", "value": "不合格"}]},
            {"cols": [
              {"view": "label", "value": "意见和建议"},
              {"view": "textarea", "id": "yj"},
            ]}
          ],
          "rules": {"q1": webix.rules.isNotEmpty, "q2": webix.rules.isNotEmpty, "yj": webix.rules.isNotEmpty}
        };
        </script>
    """.trimIndent()

    private val questionnaire = GraduateQuestionnaire(
        assessment = "allow", kcbh = "031002", kcmc = "Numerical Heat Transfer", jsxm = "陶文铨",
        skls_duty = "主讲", termname = "2024秋",
        raw = mapOf("assessment" to "allow", "kcbh" to "031002", "kcmc" to "Numerical Heat Transfer", "data_jxb_id" to "108345"),
    )

    @Test
    fun `parses questions, hidden meta and required ids`() {
        val data = GraduateJudgeApi.parseForm(formHtml)!!
        assertEquals(mapOf("pjid" to "9527"), data.meta)
        assertEquals(setOf("q1", "q2", "yj"), data.requiredIds)
        // 没标题的单选取同一行的 label，文本框取前一个控件的文字
        assertEquals("教材情况", data.questions.first { it.id == "jcqk" }.name)
        assertEquals("意见和建议", data.questions.first { it.id == "yj" }.name)
    }

    @Test
    fun `fills english textbook, degree course and avoids all excellent`() {
        val data = GraduateJudgeApi.parseForm(formHtml)!!
        GraduateJudgeApi.completeQuestionnaire(
            questionnaire, data,
            GraduateLessonInfo(textbook = "Numerical Heat Transfer", teachingLanguage = "全英文授课"),
            isDegreeCourse = true,
        )
        assertEquals("Numerical Heat Transfer", data.answers["kcmc_q"])
        assertEquals("陶文铨", data.answers["skjs_q"])
        assertEquals("2", data.answers["jcqk"])
        assertEquals("b", data.answers["jcyy"])
        assertEquals("2", data.answers["skyy"])
        assertEquals("1", data.answers["xxqk"])
        // 系统不允许全优：第一道「优秀」开头的题改成良好
        assertEquals("80", data.answers["q1"])
        assertEquals("100", data.answers["q2"])
        assertEquals(GraduateJudgeApi.DEFAULT_COMMENT, data.answers["yj"])
        assertTrue(data.unansweredRequired().isEmpty())
    }

    @Test
    fun `no textbook still fills name and language`() {
        val data = GraduateJudgeApi.parseForm(formHtml)!!
        GraduateJudgeApi.completeQuestionnaire(
            questionnaire, data,
            GraduateLessonInfo(textbook = "无指定书籍", teachingLanguage = "全中文授课"),
            isDegreeCourse = false, grade = 2,
        )
        assertEquals("0", data.answers["jcqk"])
        assertEquals("无", data.answers["jcmc"])
        assertEquals("c", data.answers["jcyy"])
        assertEquals("1", data.answers["skyy"])
        assertEquals("2", data.answers["xxqk"])
        assertEquals("80", data.answers["q1"])
    }

    @Test
    fun `params keep all sixteen server keys`() {
        val params = questionnaire.params()
        assertEquals(GraduateQuestionnaire.PARAM_KEYS, params.keys.toList())
        assertEquals("108345", params["data_jxb_id"])
        assertEquals("", params["bjid"])
        assertFalse(questionnaire.finished)
    }

    @Test
    fun `parses gmis lesson detail and degree courses`() {
        val lesson = GraduateJudgeApi.parseLessonInfo(Jsoup.parse("""
            <table><tr><td class="tdCaption">授课语言：</td><td> 全英文授课 </td></tr></table>
            <table id="jcxx"><thead><tr><th>教材编号</th><th>教程名称</th></tr></thead>
            <tbody><tr><td>10004683</td><td>数值传热学</td></tr></tbody></table>
        """.trimIndent()))
        assertEquals("全英文授课", lesson.teachingLanguage)
        assertEquals("数值传热学", lesson.textbook)

        val empty = GraduateJudgeApi.parseLessonInfo(Jsoup.parse("""
            <table id="jcxx"><thead><tr><th>教程名称</th></tr></thead><tbody><tr><td>没有相关数据</td></tr></tbody></table>
        """.trimIndent()))
        assertNull(empty.textbook)

        val names = GraduateJudgeApi.parseDegreeCourseNames(Jsoup.parse("""
            <table id="sample-table-1"><tr><th>课程</th></tr><tr><td>自然辩证法概论</td><td>1</td></tr></table>
            <table id="sample-table-1"><tr><th>课程</th></tr><tr><td>数值传热学</td></tr></table>
        """.trimIndent()))
        assertEquals(setOf("自然辩证法概论"), names)
    }
}
