package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// read_zyxf_file 与 get_lms_activity 读附件共用的参数说明
private const val DOC_READ_NOTE = "auto 文字优先，扫描页能 OCR 就转文字、否则给页面图；doc(x)/ppt(x)/xlsx 取正文，图片直接看。读过的有缓存。"
private const val DOC_PAGES_PROP = "页码从 1 起：5、3-5、1,3,7-8。缺省：前 2 页。PDF 文字最多 20 页、页面图最多 4 页；其他长文本按段号。"
private const val DOC_MODE_PROP = "PDF：info=每页首行；search=按 query 找页；text=只要文字；images=页面图（公式、图表）；auto=默认。"
private const val DOC_QUERY_PROP = "mode=search 的检索词。"
private const val DOC_OFFSET_PROP = "单页文字太长时，按上次返回的 next 从这个字符偏移续读。"
private val DOC_MODES = arrayOf("auto", "info", "search", "text", "images")

/** 送给模型的工具清单（OpenAI function calling 的那一份 JSON 串）。 */
fun agentToolDefinitions(honored: Set<AgentCapability>): String {
    val arr = mutableListOf<JsonObject>()
    // 描述只写事实：返回什么、参数含义、前置条件与限制。不教模型何时调、怎么答，也不叮嘱。
    arr.add(agentTool("get_calendar",
        "校历与作息：当前教学周、当前和下一节次、今天的作息时间、开学天数、放假与考试周。",
        agentParams("term" to agentStrProp("学年或学期，如 2025-2026、第一学期。缺省：当前学期。"))))
    arr.add(agentTool("get_schedule",
        "本人课表，含自建日程。",
        agentParams(
            "date" to agentStrProp("yyyy-MM-dd，只看这一天。缺省：本周；给了 term 则整学期。"),
            "term" to agentStrProp("学期代码，如 2024-2025-1。缺省：当前学期。"),
        )))
    arr.add(agentTool("get_exam_schedule",
        "本人考试安排：日期、时间、地点、座位号。需教务登录。",
        agentParams()))
    arr.add(agentTool("search_school_courses",
        "全校开课查询：教师、学分、容量、班级、时间地点。至少给一个筛选条件。需教务登录。",
        agentParams(
            "course_name" to agentStrProp("课程名，模糊。"),
            "teacher" to agentStrProp("教师，模糊。"),
            "course_code" to agentStrProp("课程号，模糊。"),
            "class_name" to agentStrProp("上课班级，模糊。"),
            "department" to agentStrProp("开课单位，如 数学学院。"),
            "campus" to agentStrProp("兴庆/雁塔/曲江/苏州/创新港。"),
            "term" to agentStrProp("学期代码，如 2025-2026-2。缺省：当前学期。"),
            "weekday" to agentIntProp("星期 1–7。"),
            "section" to agentIntProp("覆盖这一节的课，1–11。"),
            "start_section" to agentIntProp("节次区间起点 1–11，配 end_section。"),
            "end_section" to agentIntProp("节次区间终点 1–11。"),
            "public_elective" to agentBoolProp("只看校公选课。"),
            "elective_category" to agentStrProp("基础通识类选修课/基础通识类核心课/钱学森学院特色课。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
        )))
    arr.add(agentTool("get_empty_rooms",
        "空闲教室，可按教学楼、日期、节次筛选。",
        agentParams(
            "campus"   to agentEnumProp("缺省：兴庆校区。", "兴庆校区", "雁塔校区", "曲江校区", "创新港校区"),
            "building" to agentStrProp("楼名，如 主楼A、1号巨构。缺省：全校区。"),
            "date"     to agentStrProp("今天/明天/yyyy-MM-dd。缺省：今天。"),
            "view" to agentEnumProp("slots=按课表的全天各节空闲，可查未来；status=此刻实时占用，仅今天且仅兴庆、雁塔、创新港；both=两者；auto=自动。", "auto", "slots", "status", "both"),
            "section"  to agentIntProp("只看这一节空闲的，1–11。"),
            "sections" to buildJsonObject { put("type", "array"); put("description", "这些节次都要空闲，1–11。"); put("items", buildJsonObject { put("type", "integer") }) },
            "min_free_sections" to agentIntProp("至少连续空闲几节，1–11。"),
        )))
    arr.add(agentTool("get_attendance",
        "本学期考勤记录：正常、迟到、缺勤、请假。需考勤登录。",
        agentParams("limit" to agentIntProp("条数，默认 20，上限 200。"))))
    arr.add(agentTool("get_grades",
        "本人成绩与加权 GPA。需教务登录。",
        agentParams("term" to agentStrProp("学期代码，如 2024-2025-1。缺省：全部学期。"))))
    arr.add(agentTool("get_card_info",
        "校园卡余额；给 days 再附这段时间的消费流水和收支汇总。需校园卡登录。",
        agentParams("days" to agentIntProp("流水天数 1–180。缺省：不查流水。"))))
    arr.add(agentTool("get_notifications",
        "校内通知：标题、来源、日期、链接。给 keyword 用各站站内搜索查全站（含往年），否则列最新。",
        agentParams(
            "keyword" to agentStrProp("关键词，如 推免、放假、奖学金。"),
            "source" to agentStrProp("来源名，如 教务处、OA 通知、仲英书院、电信学部。缺省：按用户身份选常用来源。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
        )))
    arr.add(agentTool("search_yellow_page",
        "校园黄页：机构办公电话，没有个人号码。",
        agentParams(
            "query" to agentStrProp("机构名或号码片段。可空，配合 category 列出。"),
            "category" to agentStrProp("党群机构/行政机构/直属单位/附属单位/其它。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
        )))
    arr.add(agentTool("preference",
        "本机长期记住的用户偏好与个人信息：列出、写入、改名或删除，返回改后的完整清单。" +
            "键名为校区、学院、专业、年级、书院的条目会覆盖学校系统里的信息。",
        agentParams(
            "action" to agentEnumProp("list=只列出。缺省：按 key/value 写入或删除。", "list"),
            "key" to agentStrProp("偏好名。与已有键相同或互相包含（所在校区/校区）时覆盖那一条。"),
            "value" to agentStrProp("内容，一句。缺省：删除这一条。"),
            "old_key" to agentStrProp("改名时的旧键名。"),
        )))
    arr.add(agentTool("find_faculty",
        "本校在职教师主页：学院、职称、研究方向、办公地点、邮箱、主页链接。",
        agentParams(
            "name" to agentStrProp("姓名，模糊。"),
            "college" to agentStrProp("学院，重名时用。"),
            "limit" to agentIntProp("条数，默认 3，上限 8。"),
            required = listOf("name"),
        )))
    // web_search / web_fetch 在 `:data` 的 DeepSeekWebTools 里，配了 DeepSeek Key 的端由 AgentHost.tools 并进来
    if (AgentCapability.ALARM in honored) {
        arr.add(agentTool("set_alarm",
            "在系统时钟里设一个闹钟。",
            agentParams(
                "hour" to agentIntProp("0–23。"),
                "minute" to agentIntProp("0–59。缺省：0。"),
                "message" to agentStrProp("标签。"),
                "days" to agentStrProp("重复：MON,TUE,WED,THU,FRI,SAT,SUN 逗号分隔。缺省：只响一次。"),
                required = listOf("hour"),
            )))
    }
    if (AgentCapability.SCHEDULE_EVENT_WRITE in honored) {
        arr.add(agentTool("add_schedule_event",
            "往本学期日程里添加一段有起止时间的安排。只有截止时间的待办没有直接写入的工具，只能用 propose_plan 列卡片由用户加入。" +
                "与课程或已有日程冲突时不添加并返回冲突；force=true 时强制添加。",
            agentParams(
                "title" to agentStrProp("标题。"),
                "date" to agentStrProp("yyyy-MM-dd。"),
                "start" to agentStrProp("开始 HH:mm。"),
                "end" to agentStrProp("结束 HH:mm。缺省：开始后 1 小时。"),
                "location" to agentStrProp("地点。"),
                "note" to agentStrProp("备注。"),
                "weeks" to agentStrProp("每周重复的教学周，如 3-5,8。缺省：只加 date 那一周。"),
                "force" to agentBoolProp("冲突也加。"),
                required = listOf("title", "date", "start"),
            )))
    }
    arr.add(agentTool("get_library",
        "图书馆：本人当前座位预约和各区域空座；给 area 返回该区域的空座和平面图。需图书馆登录。",
        agentParams(
            "campus" to agentEnumProp("缺省：账号在图书馆系统里的当前校区。", "兴庆", "雁塔", "创新港"),
            "area" to agentStrProp("区域名，模糊，如 北楼二层外文库。缺省：列出全部区域和空座数。"),
        )))
    arr.add(agentTool("list_zyxf",
        "仲英学辅资料站（同学共享的历年卷、复习资料、课件、笔记）：给 keyword 时按文件名和目录名搜索，否则浏览目录。返回的文件 ID 用于 read_zyxf_file。",
        agentParams(
            "keyword" to agentStrProp("关键词。文件和目录多以课程名命名。"),
            "folder_id" to agentIntProp("目录 ID。缺省：根目录。"),
        )))
    arr.add(agentTool("read_zyxf_file",
        "读取学辅文件内容。长 PDF 可用 info 或 search 定位页码。$DOC_READ_NOTE",
        agentParams(
            "file_id" to agentIntProp("来自 list_zyxf。"),
            "pages" to agentStrProp(DOC_PAGES_PROP),
            "mode" to agentEnumProp(DOC_MODE_PROP, *DOC_MODES),
            "query" to agentStrProp(DOC_QUERY_PROP),
            "text_offset" to agentIntProp(DOC_OFFSET_PROP),
            required = listOf("file_id"),
        )))
    if (AgentCapability.FILE_SAVE in honored) {
        arr.add(agentTool("save_file",
            "把学辅文件或思源附件存进手机「下载」（我的 → 下载管理）。",
            agentParams(
                "source" to agentEnumProp("zyxf=学辅；lms=思源附件。", "zyxf", "lms"),
                "file_id" to agentIntProp("学辅文件 ID。"),
                "course" to agentStrProp("思源课程名，模糊。"),
                "activity" to agentStrProp("思源活动标题，模糊。"),
                "file" to agentStrProp("思源附件名关键词。"),
                required = listOf("source"),
            )))
    }
    arr.add(agentTool("get_textbooks",
        "本人各课程的教材。需教务登录。",
        agentParams(
            "course" to agentStrProp("课程名关键词。缺省：全部。"),
            "term" to agentStrProp("学期代码，如 2025-2026-2。缺省：当前学期。"),
        )))
    arr.add(agentTool("get_coupons",
        "本人加餐券：可领取、可使用、余额、有效期。需加餐券登录。",
        agentParams("status" to agentEnumProp("all=全部；available=可领取；usable=可使用。缺省：all。", "all", "available", "usable"))))
    arr.add(agentTool("get_lms",
        "思源学堂：不给 course 列本人课程；给 course 列该课的作业、课件、回放等活动；scope=assignments 汇总全部课程的作业（较慢）。需思源登录。",
        agentParams(
            "course" to agentStrProp("课程名，模糊。"),
            "scope" to agentEnumProp("assignments=全部作业汇总。", "assignments"),
        )))
    arr.add(agentTool("get_lms_activity",
        "思源某个活动的详情：截止时间、提交状态、分数、评语，以及本人已交的内容和附件。给 file 读附件（老师发的、本人交的都行），参数同 read_zyxf_file。" +
            "${DOC_READ_NOTE}需思源登录。",
        agentParams(
            "course" to agentStrProp("课程名，模糊。"),
            "activity" to agentStrProp("活动标题，模糊。"),
            "file" to agentStrProp("附件 ID 或完整名称，也可用唯一关键词；多个匹配时返回候选。"),
            "pages" to agentStrProp(DOC_PAGES_PROP),
            "mode" to agentEnumProp(DOC_MODE_PROP, *DOC_MODES),
            "query" to agentStrProp(DOC_QUERY_PROP),
            "text_offset" to agentIntProp(DOC_OFFSET_PROP),
            required = listOf("course", "activity"),
        )))
    if (AgentCapability.SS_ACTIVITY in honored) {
        arr.add(agentTool("activity",
            "智慧社区活动：搜索、详情、本人报名记录、报名与取消报名。这里的活动是集体活动证明和课外学分（本科共需 8 分）的主要来源；" +
                "讲座、报告会、分享会通常能开集体活动证明，但活动描述里很少写明，积分认定以学院、书院规定为准。apply / cancel 核对条件后展示确认卡片，用户点卡片上的按钮才提交。需智慧社区登录。",
            agentParams(
                "action" to agentEnumProp(
                    "search=按名称搜，不给 keyword 列近期；group_proof=搜还没结束、通常能开集体活动证明的活动；detail=详情（报名时间、名额、是否已报、地点、积分）；mine=本人报名记录；apply=报名（出确认卡片）；cancel=取消报名（出确认卡片）。",
                    "group_proof", "search", "detail", "apply", "cancel", "mine",
                ),
                "keyword" to agentStrProp("search 的活动名关键词。"),
                "id" to agentStrProp("活动 ID（HDDM），来自 search 或 mine。detail / apply / cancel 必填。"),
                "order" to agentEnumProp("search 排序：time（默认）、hot 热度、points 积分。", "time", "hot", "points"),
                required = listOf("action"),
            )))
    }
    arr.add(agentTool("get_fitness_score",
        "本人体测成绩：总分、等级、各项。需体测登录。",
        agentParams("year" to agentStrProp("学年起始年，2025 即 2025-2026 学年。缺省：最近已开测的学年。"))))
    if (AgentCapability.APP_SETTINGS in honored) {
        arr.add(agentTool("app_setting",
            "本 App 的设置：不给 value 列出全部当前值和可选值；给 key 和 value 修改。",
            agentParams(
                "key" to agentEnumProp("设置项。", "dark_mode", "dynamic_color", "nav_bar_style", "show_quick_actions",
                    "default_tab", "network_mode", "account_type", "update_channel", "receive_preview_updates"),
                "value" to agentStrProp("新值。"),
            )))
    }
    arr.add(agentTool("app_guide",
        "本 App 的功能目录、各功能入口、设置项和小贴士。",
        agentParams()))
    arr.add(agentTool("search_campus_guide",
        "校园指南：规章、办事流程和生活常识（选课、考试、转专业、奖助、医保、住宿、校园卡、网络、图书馆、场馆、毕业等），含电话、地点、时间、金额，每条注出处和年份。",
        agentParams(
            "query" to agentStrProp("关键词，如 缓考、校园网流量、医保报销。"),
            "section" to agentStrProp("节名片段，如 选课。只给它时列出整节；都不给列目录。"),
            "limit" to agentIntProp("条数，默认 5，上限 10。"),
        )))
    if (AgentCapability.OPEN_PAGE in honored) {
        arr.add(agentTool("open_page",
            "跳到本 App 的功能页，或打开移动交大里的一项服务。page 与 service 二选一。",
            agentParams(
                "page" to agentEnumProp("本 App 的功能页。", *agentOpenablePages.map { it.title }.distinct().toTypedArray()),
                "service" to agentStrProp("移动交大服务名，模糊，如 校医院、宿舍报修、请假。"),
            )))
    }
    if (AgentCapability.PAGE_CONTEXT in honored) {
        arr.add(agentTool("read_current_page",
            "用户此刻正在看的 App 页面上显示的内容：页名、筛选条件、列表和数字，现读现取。没有页面在显示时返回空。",
            agentParams()))
    }
    arr.add(agentTool("calculate",
        "计算算术表达式，支持 + - * / ^ 和括号。",
        agentParams("expression" to agentStrProp("如 (3.7*4+4.0*3)/(4+3)。"), required = listOf("expression"))))
    return JsonArray(arr.map(AgentWidgetPresentation::queryDefinition)).toString()
}
