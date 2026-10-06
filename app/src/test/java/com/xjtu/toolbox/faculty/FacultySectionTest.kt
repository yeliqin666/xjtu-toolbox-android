package com.xjtu.toolbox.faculty

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacultySectionTest {
    private val api = FacultyApi(OkHttpClient())

    private fun page(body: String) = "<html><body><div class='nav'>首页 基本信息 联系方式</div>$body</body></html>"

    @Test
    fun `正文取最后一个 templateu，前面的侧栏简介不要`() {
        val html = page(
            """<div id="templateu12"><p>侧栏简介</p></div>
               <div id="templateu14"><p>Email：a@xjtu.edu.cn</p><p>办公室：教一楼&nbsp;&nbsp;南411</p></div>"""
        )
        assertEquals(listOf("Email：a@xjtu.edu.cn", "办公室：教一楼 南411") to false, api.parseSection(html))
    }

    @Test
    fun `没有 templateu 时依次找 tableBox、subs、r_info`() {
        assertEquals(listOf("研究甲"), api.parseSection(page("""<div id="tableBox"><p>研究甲</p></div><div class="subs">别的</div>""")).first)
        assertEquals(listOf("研究乙"), api.parseSection(page("""<div class="subs"><div>研究乙</div></div>""")).first)
        assertEquals(listOf("地址：西安"), api.parseSection(page("""<div class="r_info"><p>地址：西安</p></div>""")).first)
        assertEquals(emptyList<String>() to false, api.parseSection(page("<div>没有正文容器</div>")))
    }

    @Test
    fun `cn07 的 p 套 p 取父节点`() {
        val html = page("""<div style="padding-left: 20px;"><p id="templateu18"><p>邮箱：x@xjtu.edu.cn</p><p>地址：兴庆校区</p></p></div>""")
        assertEquals(listOf("邮箱：x@xjtu.edu.cn", "地址：兴庆校区"), api.parseSection(html).first)
    }

    @Test
    fun `br 断行、列表加圆点、表格一行一段、行内 span 和源码换行不拆`() {
        val html = page(
            """<div class="subs">
                 <span>姓</span><span>&nbsp;&nbsp;</span><span>名：</span><span>朱利</span><br>第二
                 行
                 <ul><li>方向一</li><li><p>方向二</p></li></ul>
                 <table><tr><td>2019</td><td>教授</td></tr></table>
               </div>"""
        )
        assertEquals(listOf("姓 名：朱利", "第二行", "• 方向一", "• 方向二", "2019 教授"), api.parseSection(html).first)
    }

    @Test
    fun `二次转义的实体再解一次、零宽字符去掉、Wingdings 圆点和自带编号的列表项`() {
        val html = page(
            """<div class="subs">
                 <p>获&amp;ldquo;王宽诚育才奖&amp;rdquo;，2010&amp;ndash;2012</p>
                 <p>&#8203;&#8203;&#65279;</p>
                 <p>l 2024-至今，西安交通大学，教授</p>
                 <ul><li>1. 先进核能系统</li><li>（2）两相流</li></ul>
               </div>"""
        )
        assertEquals(
            listOf("获“王宽诚育才奖”，2010–2012", "• 2024-至今，西安交通大学，教授", "1. 先进核能系统", "（2）两相流"),
            api.parseSection(html).first,
        )
    }

    @Test
    fun `只写了暂未填写的算空，太长的截断`() {
        assertEquals(emptyList<String>(), api.parseSection(page("""<div class="subs"><p>暂未填写</p></div>""")).first)
        val long = (1..40).joinToString("") { "<p>${"论".repeat(100)}</p>" }
        val (paragraphs, truncated) = api.parseSection(page("""<div class="subs">$long</div>"""))
        assertTrue(truncated)
        assertTrue(paragraphs.sumOf { it.length } <= 3000)
        val (one, cut) = api.parseSection(page("""<div class="subs"><p>${"字".repeat(5000)}</p></div>"""))
        assertTrue(cut)
        assertEquals(3001, one.single().length)
    }

    private fun col(id: Long, title: String, type: String = FacultyColumnType.CUSTOM, parent: Long? = null) =
        FacultyColumn(type = type, columnId = id, url = "https://gr.xjtu.edu.cn/x/zh_CN/$type/$id/list/index.htm", title = title, depth = if (parent == null) 0 else 1, parentId = parent)

    @Test
    fun `按类别挑栏目：优先级、个数上限、团队简介不算个人简介`() {
        val columns = listOf(
            col(1, "基本信息"), col(2, "课题组简介"), col(3, "个人简介"), col(4, "研究领域"),
            col(5, "教育经历"), col(6, "工作经历"), col(7, "研究经历"), col(8, "联系方式"),
            col(9, "科研项目", FacultyColumnType.RESEARCH_PROJECT), col(10, "招生信息", FacultyColumnType.GENERAL),
        )
        val picked = SectionKind.pick(columns).map { (kind, c) -> kind to c.columnId }
        assertEquals(
            listOf(
                SectionKind.INTRO to 3L, SectionKind.RESEARCH to 4L,
                SectionKind.CAREER to 5L, SectionKind.CAREER to 6L, SectionKind.CONTACT to 8L,
            ),
            picked,
        )
    }

    @Test
    fun `有中文栏目就不取英文镜像，系统研究领域也算`() {
        val columns = listOf(
            col(1, "Education"), col(2, "教育经历"), col(3, "Professional Experiences"), col(4, "工作经历"),
            col(5, "研究领域", FacultyColumnType.RESEARCH_FIELD), col(6, "Contact"),
        )
        val picked = SectionKind.pick(columns).map { (kind, c) -> kind to c.columnId }
        assertEquals(
            listOf(SectionKind.RESEARCH to 5L, SectionKind.CAREER to 2L, SectionKind.CAREER to 4L, SectionKind.CONTACT to 6L),
            picked,
        )
    }

    @Test
    fun `栏目名去序号和英文尾巴，中英并列只留中文`() {
        assertEquals("Basic Information", SectionKind.cleanTitle("(1.)Basic Information"))
        assertEquals("研究领域", SectionKind.cleanTitle("二、研究领域"))
        assertEquals("研究领域", SectionKind.cleanTitle("研究领域/Research Interests"))
        assertEquals("个人简介", SectionKind.cleanTitle("个人简介 Brief Bio"))
        assertEquals("招生信息", SectionKind.cleanTitle("招生信息 Information to Prospective Students"))
        assertEquals("主要论文", SectionKind.cleanTitle("主要论文 （Selected Publications）"))
        assertEquals("研究领域 & 工作学习经历", SectionKind.cleanTitle("研究领域 & 工作学习经历"))
        assertEquals("联系方式", SectionKind.cleanTitle("联系方式"))
    }

    @Test
    fun `更多栏目去掉已内嵌、有子页的容器、占位名和重名，自建的排前面`() {
        val columns = listOf(
            col(1, "首页", FacultyColumnType.GENERAL), col(2, "科学研究", FacultyColumnType.GENERAL),
            col(3, "研究领域", parent = 2), col(4, "团队成员", parent = 2), col(5, "Blank5"),
            col(6, "我的新闻", FacultyColumnType.NEWS), col(7, "团队成员"), col(8, "讲授课程"),
        )
        val profile = FacultyProfile(
            columns = columns,
            sections = listOf(FacultySection(SectionKind.RESEARCH, "研究领域", columns[2].url, emptyList())),
        )
        assertEquals(listOf("团队成员", "讲授课程", "我的新闻"), profile.moreColumns().map { it.displayName })
        assertFalse(profile.moreColumns().any { it.columnId == 2L })
    }

    @Test
    fun `更多栏目去掉 0 条的系统列表、与正文同名的、中文已有同类的英文镜像`() {
        val columns = listOf(
            col(1, "教育经历", FacultyColumnType.GENERAL), col(2, "教育经历"), col(3, "Education"), col(4, "Papers"),
            col(5, "科研项目", FacultyColumnType.RESEARCH_PROJECT), col(6, "论文成果", FacultyColumnType.PAPER),
            col(7, "著作成果", FacultyColumnType.BOOK),
        )
        val profile = FacultyProfile(
            columns = columns,
            sections = listOf(FacultySection(SectionKind.CAREER, "教育经历", columns[1].url, listOf("2010 博士"))),
            itemCounts = mapOf(5L to 0, 6L to 63),
        )
        assertEquals(listOf("Papers", "论文成果", "著作成果"), profile.moreColumns().map { it.displayName })
    }
}
