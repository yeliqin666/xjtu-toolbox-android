package com.xjtu.toolbox.faculty

/** 教师检索上游（`faculty.xjtu.edu.cn`）的页面原文样本 —— 教工检索那一屏的解析契约。 */
object FacultyFixture {

    /** 两个站点里检索那一个（与 `FacultyApi.FACULTY_HOST` 同源，URL 不重写）。 */
    const val HOST = "faculty.xjtu.edu.cn"

    /** 检索接口的路径（`FacultyApi.SEARCH_URL` 那一条）。 */
    const val SEARCH_PATH = "/system/resource/tsites/advancesearch.jsp"

    /** 筛选项所在页面的路径（`FacultyApi.FILTER_PAGE_URL` 那一条）。 */
    const val FILTER_PAGE_PATH = "/search.jsp"

    /** 检索响应（中文接口，`showlang=zh_CN`、`profilelen=400`）。 */
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

    /** 同一页的英文接口响应（`showlang=en`、`profilelen=1`，见 `FacultyApi.search` 的注释）。 */
    val searchJsonEn: String = """
        {"totalnum":4,"totalpage":1,"pageindex":1,"teacherData":[
          {"teacherId":20101,"url":"https://gr.xjtu.edu.cn/web/example-a/home","profile":"示"},
          {"teacherId":20102,"url":"https://gr.xjtu.edu.cn/example-b/en/index.htm","profile":"示"},
          {"teacherId":20103,"url":"https://gr.xjtu.edu.cn/example-c/zh_CN/index.htm","profile":"示"},
          {"teacherId":20104,"url":"https://orcid.org/0000-0000-0000-0000","profile":"示"}
        ]}
    """.trimIndent()

    /** 个别老师的数据服务端一渲染就整页回 `{}`（`FacultyApi.fetchPage` 的 KDoc：2026-10 实测全校两位）。 */
    const val searchJsonEmptyPage: String = "{}"

    /** 不是 JSON 时的响应（登录跳转页/错误页的形状：首个非空白字符是 `<`）。 */
    val notJsonPage: String = """
        <html><head><title>error</title></head><body>系统忙，请稍后再试</body></html>
    """.trimIndent()

    /** `search.jsp` 里的四张筛选 id 表（片段，不是整页）。 */
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
