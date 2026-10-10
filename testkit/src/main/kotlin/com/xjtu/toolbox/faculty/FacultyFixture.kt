package com.xjtu.toolbox.faculty

/**
 * 教师检索上游（`faculty.xjtu.edu.cn`）的**页面原文样本** —— 教工检索那一屏的解析契约。
 *
 * ## 为什么只有字符串、没有 `HttpServer`
 *
 * 图书馆/校历那两个夹具要起服务器，是因为它们面对的是**会话内核**（cookie、302、CAS）。
 * 教师检索这条**免登录**：`FacultyApi` 自己拿 `OkHttpClient` 发请求（构造参数就能换掉），
 * 所以消费者用一条 **OkHttp 拦截器**就能把这两条响应喂进去 —— 不起服务器、不起端口，
 * 也不受「纯 HTTP 假代理给不了 CONNECT+TLS」那条限制（见 `FakeCampusProxy` 的 KDoc）。
 *
 * 于是本文件只提供「上游长什么样」，把喂法留给消费者
 *（`:data:jvmTest` 的 `FacultyApiParsingTest` 里有一份 `fakeClient`）。
 *
 * ## 与解析的对应关系（逐字段对着 `FacultyApi` 写，别凭印象改）
 *
 * - [searchJson] / [searchJsonEn]：`advancesearch.jsp` 的响应体。**虽然内容全是 JSON，
 *   但真站点给它打的是 `text/html` 的头**，所以 `FacultyApi` 按首个非空白字符判 JSON
 *   （[FacultyApi.looksLikeJson]），夹具也照这个形状给。
 *   外层 `{totalnum, totalpage, pageindex, teacherData[]}`，行字段与 `:core` 的
 *   [FacultyMember] 一一对应。
 * - [searchJspHtml]：`search.jsp` 里的**四张筛选 id 表**（学院/学科/招生学科/荣誉）。
 *   每一项形如 `<li onclick="create_advance_search_conditon.selectByXXX(this,id)">|--名称</li>`：
 *   层级由文本前缀里的 `-` 个数编码，`id` 从 `onclick` 里抠。
 * - [searchJsonEmptyPage] / [notJsonPage]：两条降级路径（整页回 `{}`；返回的不是 JSON）。
 *
 * ## 关于数据
 *
 * id、姓名、邮箱、电话全是编的示例值（域名一律 `example.edu.cn`、号码一律 `0`），
 * 与真实教师无关；学院/学科名也只是形状样本。
 */
object FacultyFixture {

    /** 两个站点里检索那一个（与 `FacultyApi.FACULTY_HOST` 同源，URL 不重写）。 */
    const val HOST = "faculty.xjtu.edu.cn"

    /** 检索接口的路径（`FacultyApi.SEARCH_URL` 那一条）。 */
    const val SEARCH_PATH = "/system/resource/tsites/advancesearch.jsp"

    /** 筛选项所在页面的路径（`FacultyApi.FILTER_PAGE_URL` 那一条）。 */
    const val FILTER_PAGE_PATH = "/search.jsp"

    /**
     * 检索响应（中文接口，`showlang=zh_CN`、`profilelen=400`）。四行刻意各不相同：
     *
     * - **第 1 行**：字段全给，且每个字符串都**带首尾空格** —— 钉住 `parseMember` 里的 `.trim()`；
     *   `researchDirectionList` 里夹一个**没有 title 的对象**（不收）与一个带空格 title 的（收 trim 后的）。
     * - **第 2 行**：只有 `teacherId` + `name`，**其余字段一个都不给** —— 钉住「缺失时给什么」
     *   （空串 / 0 / false / 空列表）。它的主页地址为空，所以还会触发 `search()` 的**英文补地址**那条路
     *   （见 [searchJsonEn]）。
     * - **第 3 行**：故意写成真实上游会出现的三种「不是字符串」：JSON `null`（`url` / `email`）、
     *   带引号的数字（`doctorTutor` / `gtutor` / `clickTimes` / `teacherId`）、空串
     *   （`collegeName` / `profile`，分别走 `unit` / `profileSummary` 兜底）。
     *   前两种正是 `org.json` 与「直觉写法」分道扬镳的地方：`optString` 对 JSON null 给的是
     *   **字面量 `"null"`**（`JSON.toString(JSONObject.NULL)`），`optInt`/`optLong` 对字符串数字
     *   **会解析**。
     * - **第 4 行**：主页地址指向站外（ORCID）—— `normalizeHomepage` 认不出的地址原样返回，
     *   所以它不算「空」，不该被英文接口覆盖。
     *
     * `totalnum` 是 4、`teacherData` 也是 4 条；「总数取 `totalnum`、列表长度取数组长度」这两件事
     * 由消费者把 `totalnum` 去掉再断言一次（`optInt("totalnum", arr.length())` 的兜底）。
     */
    val searchJson: String = """
        {"totalnum":4,"totalpage":1,"pageindex":1,"teacherData":[
          {"teacherId":20101,"name":" 示例甲 ","ename":"Jia Shi Li","pinYinName":"shi li jia",
           "url":"https://gr.xjtu.edu.cn/en/web/example-a",
           "collegeName":"示例学院","prorank":"教授","job":"系主任","discipline":"物理学","degree":"博士",
           "education":"研究生","graduatedUniversity":"西安交通大学","doctorTutor":1,"gtutor":0,
           "profile":" 示例甲的简介。 ",
           "researchDirectionList":[{"researchDirectionTitle":"方向甲"},{"researchDirectionTitle":" 方向乙 "},{"nope":"没有 title"}],
           "picUrl":" /system/resource/tsites/pic/20101.jpg ","email":" example-a@example.edu.cn ",
           "contact":" 029-00000000 ","phone":" 029-00000001 ","mobilephone":" 13000000000 ",
           "officeLocation":" 教一楼 101 ","address":" 兴庆校区 ","entryTime":" 2005-09 ","latestUpdate":" 2026-08-17 ","clickTimes":1234},
          {"teacherId":20102,"name":"示例乙"},
          {"teacherId":"20103","name":"示例丙","url":null,"collegeName":"","unit":" 示例学院丙 ",
           "doctorTutor":"1","gtutor":"0","profile":"","profileSummary":"备用简介","email":null,
           "clickTimes":"42","researchDirectionList":[],"ename":"","pinYinName":""},
          {"teacherId":20104,"name":"示例丁","url":"https://orcid.org/0000-0000-0000-0000","collegeName":"示例学院"}
        ]}
    """.trimIndent()

    /**
     * 同一页的**英文接口**响应（`showlang=en`、`profilelen=1`，见 `FacultyApi.search` 的注释：
     * 中文接口给部分老师的主页地址留空，英文接口同一页、同样顺序的人都带着地址，按 `teacherId` 补）。
     *
     * 第 1 行给的是另一种写法（`/web/{站点}/home`）、第 2 行才是第 2 行缺的那个地址、
     * 第 3 行给一个**与中文接口不同**的地址（用来证明「只补空值」）、第 4 行是站外地址。
     * `profile` 只留 1 个字符 —— 与 `profilelen=1` 对齐（这份响应只用来取 `teacherId` + `url`）。
     */
    val searchJsonEn: String = """
        {"totalnum":4,"totalpage":1,"pageindex":1,"teacherData":[
          {"teacherId":20101,"url":"https://gr.xjtu.edu.cn/web/example-a/home","profile":"示"},
          {"teacherId":20102,"url":"https://gr.xjtu.edu.cn/example-b/en/index.htm","profile":"示"},
          {"teacherId":20103,"url":"https://gr.xjtu.edu.cn/example-c/zh_CN/index.htm","profile":"示"},
          {"teacherId":20104,"url":"https://orcid.org/0000-0000-0000-0000","profile":"示"}
        ]}
    """.trimIndent()

    /**
     * 个别老师的数据服务端一渲染就整页回 `{}`（`FacultyApi.fetchPage` 的 KDoc：2026-10 实测全校两位）。
     * 它**是合法 JSON**（首个非空白字符是 `{`），所以判据放行，之后靠 `teacherData` 缺失走降级。
     */
    const val searchJsonEmptyPage: String = "{}"

    /** 不是 JSON 时的响应（登录跳转页/错误页的形状：首个非空白字符是 `<`）。 */
    val notJsonPage: String = """
        <html><head><title>error</title></head><body>系统忙，请稍后再试</body></html>
    """.trimIndent()

    /**
     * `search.jsp` 里的四张筛选 id 表（片段，不是整页）。
     *
     * 刻意放进去的边界：
     * - 学院表里**重复的 id**（第二条 `示例学院二`）⇒ 按 id 去重，只留第一条；
     * - 学院表里**没有 id 的一项**（`selectByCollege(this)`）⇒ 正则不匹配，丢掉；
     * - 学院表里**别的函数名**（`selectByOther`）⇒ CSS 选择器就把它滤掉了；
     * - 学科表里某一项的文本**部分**包在 `<span>` 里 ⇒ `ownText()` 非空（前缀就没了，depth 落回 0），
     * - 学科表里某一项的文本**包在子元素里** ⇒ `ownText()` 为空时退回 `text()`（包在 `<span>` 里）；
     * - 学科表里有**名字为空的一项**（只剩前缀）⇒ 丢掉。
     *
     * 层级由 `|--` 前缀里的 `-` 个数决定（`|--` ⇒ depth 2、`|--|--` ⇒ depth 4），
     * 这是 `FacultyApi.parseOptions` 的既有口径，别照着「0=顶级」的直觉去改。
     */
    val searchJspHtml: String = """
        <div class="advance_search_box">
          <div class="condition_item">
            <span class="condition_title">学院</span>
            <ul class="condition_list">
              <li onclick="create_advance_search_conditon.selectByCollege(this,0)">不限</li>
              <li onclick="create_advance_search_conditon.selectByCollege(this,1001)">|--示例学院</li>
              <li onclick="create_advance_search_conditon.selectByCollege(this,1002)">|--示例学院二</li>
              <li onclick="create_advance_search_conditon.selectByCollege(this,1002)">|--示例学院二（重复的一项）</li>
              <li onclick="create_advance_search_conditon.selectByCollege(this)">|--没有 id 的一项</li>
              <li onclick="create_advance_search_conditon.selectByOther(this,9001)">|--别的函数名</li>
            </ul>
          </div>
          <div class="condition_item">
            <span class="condition_title">学科</span>
            <ul class="condition_list">
              <li onclick="create_advance_search_conditon.selectByDiscipline(this,2001)">|--|--一级学科</li>
              <li onclick="create_advance_search_conditon.selectByDiscipline(this,2002)"><span>|--</span>文本包在 span 里</li>
              <li onclick="create_advance_search_conditon.selectByDiscipline(this,2003)">|--|-- 二级学科 </li>
              <li onclick="create_advance_search_conditon.selectByDiscipline(this,2004)"><span>|--</span><b>文本全在子元素里</b></li>
              <li onclick="create_advance_search_conditon.selectByDiscipline(this,2005)"><span>|--</span></li>
            </ul>
          </div>
          <div class="condition_item">
            <span class="condition_title">招生学科</span>
            <ul class="condition_list">
              <li onclick="create_advance_search_conditon.selectByEnrollDiscipline(this,0)">不限</li>
              <li onclick="create_advance_search_conditon.selectByEnrollDiscipline(this,3001)">|--招生学科甲</li>
            </ul>
          </div>
          <div class="condition_item">
            <span class="condition_title">荣誉</span>
            <ul class="condition_list">
              <li onclick="create_advance_search_conditon.selectByHonor(this,4001)">|--荣誉甲</li>
            </ul>
          </div>
      </div>
    """.trimIndent()

    /** 上面那份页面在 `FacultyApi.FACULTY_HOST` 下的完整地址（`loadFilters` 打的那一条）。 */
    const val FILTER_PAGE_URL = "https://$HOST$FILTER_PAGE_PATH"
}
