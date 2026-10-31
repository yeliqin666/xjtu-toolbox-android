package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 送给模型的工具清单（OpenAI function calling 的那一份 JSON 串）。 */
fun agentToolDefinitions(honored: Set<AgentCapability>): String {
    val arr = mutableListOf<JsonObject>()
    // 描述只写「返回什么、参数怎么填、前置条件」。何时该调、怎么答由系统提示和模型自己定，
    // 不在这里举例、劝说或解释。
    arr.add(agentTool("get_calendar",
        "第几周、放假、考试周、开学：教学周、当前与下一节次、今天的作息表、开学至今天数，以及学期校历。",
        agentParams("term" to agentStrProp("学年或学期关键词，如 2025-2026、第一学期。缺省：当前学期。"), agentShow())))
    arr.add(agentTool("get_schedule",
        "课表，含用户自建日程。无缓存时联网拉取。",
        agentParams(
            "date" to agentStrProp("yyyy-MM-dd，只返回该日。缺省：本周；给了 term 则整学期。"),
            "term" to agentStrProp("学期代码，如 2024-2025-1。缺省：当前学期。"),
            agentShow(),
        )))
    arr.add(agentTool("get_exam_schedule",
        "考试安排：日期、时间、地点、座位号。需教务登录。",
        agentParams(agentShow())))
    arr.add(agentTool("search_school_courses",
        "全校开课查询，返回教师、学分、容量、班级、时间地点。至少给一个筛选条件。需教务登录。",
        agentParams(
            "course_name" to agentStrProp("课程名，模糊。"),
            "teacher" to agentStrProp("教师姓名，模糊。"),
            "course_code" to agentStrProp("课程号，模糊。"),
            "class_name" to agentStrProp("上课班级，模糊。"),
            "department" to agentStrProp("开课单位，如 数学学院。"),
            "campus" to agentStrProp("兴庆/雁塔/曲江/苏州/创新港。"),
            "term" to agentStrProp("学期代码，如 2025-2026-2。缺省：当前学期。"),
            "weekday" to agentIntProp("星期 1–7。"),
            "section" to agentIntProp("节次 1–11，匹配覆盖该节的课。"),
            "start_section" to agentIntProp("起始节次 1–11，与 end_section 组成区间。"),
            "end_section" to agentIntProp("结束节次 1–11。"),
            "public_elective" to agentBoolProp("仅校公选课。"),
            "elective_category" to agentStrProp("基础通识类选修课/基础通识类核心课/钱学森学院特色课。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
            agentShow(),
        )))
    arr.add(agentTool("get_empty_rooms",
        "空闲教室。status 返回此刻状态，slots 返回全天课表节次，both 合并两者；节次条件自动查询课表。",
        agentParams(
            "campus"   to agentEnumProp("缺省：兴庆校区。实时状态只有兴庆、雁塔、创新港。", "兴庆校区", "雁塔校区", "曲江校区", "创新港校区"),
            "building" to agentStrProp("楼名，如 主楼A、1号巨构。缺省：全校区。"),
            "section"  to agentIntProp("按课表筛选该节空闲的教室，1–11。"),
            "date"     to agentStrProp("今天/明天/yyyy-MM-dd。缺省：今天。"),
            "view" to agentEnumProp("auto 自动选择；slots 全天课表空闲条；status 此刻实时状态；both 同时查询。未来日期仅支持 slots。", "auto", "slots", "status", "both"),
            "sections" to buildJsonObject { put("type", "array"); put("description", "要求全部空闲的节次 1–11；自动查询课表。明确要坐到晚上时传覆盖该时段的节次。"); put("items", buildJsonObject { put("type", "integer") }) },
            "min_free_sections" to agentIntProp("至少连续空闲几节，1–11；不代表从现在一直空到晚上，需要具体时段时用 sections。"),
            agentShow(),
        )))
    arr.add(agentTool("get_attendance",
        "本学期考勤记录（正常/迟到/缺勤/请假）。需考勤登录。",
        agentParams("limit" to agentIntProp("条数，默认 20，上限 200。"), agentShow())))
    arr.add(agentTool("get_grades",
        "成绩与加权 GPA。需教务登录。",
        agentParams("term" to agentStrProp("学期代码，如 2024-2025-1。缺省：全部学期。"), agentShow())))
    arr.add(agentTool("get_card_info",
        "校园卡余额、消费流水与收支汇总（给 days 时）。需校园卡登录。",
        agentParams("days" to agentIntProp("流水天数 1–180。缺省：不查流水。"), agentShow())))
    arr.add(agentTool("get_notifications",
        "校内通知：标题、来源、日期、链接。给 keyword 时用各站自己的站内搜索查全站（含往年），否则列最新。",
        agentParams(
            "keyword" to agentStrProp("关键词，如 推免、放假、奖学金、选课。缺省：不搜，列最新。"),
            "source" to agentStrProp("来源名，如 教务处、OA 通知、仲英书院、电信学部。缺省：按用户身份自动选（本科生：教务处、学生处、实践教学中心、所在书院；研究生：研究生院；都含 OA 与所在学院）。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
            agentShow(),
        )))
    arr.add(agentTool("search_yellow_page",
        "校园黄页：机构总机电话，不含个人号码。",
        agentParams(
            "query" to agentStrProp("机构名或号码片段。可空，配合 category 列出。"),
            "category" to agentStrProp("党群机构/行政机构/直属单位/附属单位/其它。"),
            "limit" to agentIntProp("条数，默认 10，上限 20。"),
            agentShow(),
        )))
    arr.add(agentTool("preference",
        "在本机长期保存或删除一条用户偏好或个人信息。只存可复用的信息，不存一次性事项。" +
            "用户纠正校区、学院、专业、年级、书院时用固定键名（校区/学院/专业/年级/书院）记下，会压过学校系统里的旧信息。",
        agentParams(
            "key" to agentStrProp("偏好名，同名覆盖。"),
            "value" to agentStrProp("内容，一句。缺省：删除该偏好。"),
            required = listOf("key"),
        )))
    arr.add(agentTool("find_faculty",
        "本校在职教师主页：学院、职称、研究方向、办公地点、邮箱、主页地址。不含学生、行政人员、校外人士。",
        agentParams(
            "name" to agentStrProp("姓名，模糊。"),
            "college" to agentStrProp("学院名，用于重名。"),
            "limit" to agentIntProp("条数，默认 3，上限 8。"),
            required = listOf("name"),
        )))
    // web_search / web_fetch 在 `:data` 的 DeepSeekWebTools 里，配了 DeepSeek Key 的端由 AgentHost.tools 并进来
    if (AgentCapability.ALARM in honored) {
        arr.add(agentTool("set_alarm",
            "打开系统闹钟设定闹钟。",
            agentParams(
                "hour" to agentIntProp("0–23。"),
                "minute" to agentIntProp("0–59。缺省：0。"),
                "message" to agentStrProp("标签。"),
                "days" to agentStrProp("重复星期，逗号分隔：MON,TUE,WED,THU,FRI,SAT,SUN。缺省：单次。"),
                required = listOf("hour"),
            )))
    }
    if (AgentCapability.SCHEDULE_EVENT_WRITE in honored) {
        arr.add(agentTool("add_schedule_event",
            "往本 App 的日程里添加一条（占课表里的一段具体时间），只限当前学期。与课程或已有日程时间重叠时不添加，返回冲突；用户确认后带 force=true 重试。" +
                "只写用户明确要求的事项，不额外安排活动；不占时段、只需在某个时刻前办完的事用 add_todo。没说地点、结束时间就不填。",
            agentParams(
                "title" to agentStrProp("标题。"),
                "date" to agentStrProp("yyyy-MM-dd；星期取这一天。"),
                "start" to agentStrProp("开始时间 HH:mm。"),
                "end" to agentStrProp("结束时间 HH:mm。缺省：开始后 1 小时。"),
                "location" to agentStrProp("地点。"),
                "note" to agentStrProp("备注。"),
                "weeks" to agentStrProp("每周重复的教学周，如 3-5,8。缺省：只加 date 所在的那一周。"),
                "force" to agentBoolProp("冲突时仍然添加。"),
                agentShow(),
                required = listOf("title", "date", "start"),
            )))
    }
    arr.add(agentTool("get_library",
        "图书馆：本人当前座位预约，以及各区域空座。给 area 时返回该区域空座和平面图快照。需图书馆登录。" +
            "推荐去哪自习时按用户所在校区选 campus，用户点名别的校区就用那个。",
        agentParams(
            "campus" to agentEnumProp("校区。缺省：账号在图书馆系统里当前的校区。", "兴庆", "雁塔", "创新港"),
            "area" to agentStrProp("区域名，模糊，如 北楼二层外文库。缺省：列出该校区全部区域及空座数。"),
            agentShow(),
        )))
    arr.add(agentTool("list_zyxf",
        "历年卷、复习资料、课件、笔记（仲英学辅资料站，同学共享）：给 keyword 按文件名、目录名检索，否则列出目录。",
        agentParams(
            "keyword" to agentStrProp("关键词，宜用课程名。"),
            "folder_id" to agentIntProp("目录 ID。缺省或 0：根目录。"),
            agentShow(),
        )))
    arr.add(agentTool("read_zyxf_file",
        "读学辅文件。PDF 可先用 mode=info 查看元数据与书签目录定位，再按页取文本或页面图。auto 优先文字，空文字页转图；同一附件复用缓存。docx/pptx/xlsx 取正文，图片直接看。",
        agentParams(
            "file_id" to agentIntProp("文件 ID，来自 list_zyxf。"),
            "pages" to agentStrProp("PDF/pptx 实际页码，从 1 起。5 只读第5页；可用 3-5 或 1,3,7-8。缺省前2页。PDF 文本最多20页，页面图最多4页；长文本用段号。"),
            "mode" to agentEnumProp("PDF：info=元数据和自带书签目录；text=只取文字；images=页面图（公式/图表/扫描页）；auto=文字优先，默认。", "auto", "info", "text", "images"),
            "text_offset" to agentIntProp("PDF 单页文字过长时，按返回的 next 从此字符偏移续读；默认0。"),
            required = listOf("file_id"),
        )))
    if (AgentCapability.FILE_SAVE in honored) {
        arr.add(agentTool("save_file",
            "把学辅资料或思源附件存进手机「下载」（我的 → 下载管理里能看到）。用户说要下载、要存时用。",
            agentParams(
                "source" to agentEnumProp("zyxf=学辅资料站；lms=思源附件。", "zyxf", "lms"),
                "file_id" to agentIntProp("学辅文件 ID。"),
                "course" to agentStrProp("思源课程名，模糊。"),
                "activity" to agentStrProp("思源活动标题，模糊。"),
                "file" to agentStrProp("思源附件名关键词。"),
                required = listOf("source"),
            )))
    }
    arr.add(agentTool("get_textbooks",
        "本人课程教材（教务教材报表）。需教务登录。",
        agentParams(
            "course" to agentStrProp("课程名关键词。缺省：全部。"),
            "term" to agentStrProp("学期代码，如 2025-2026-2。缺省：当前学期。"),
            agentShow(),
        )))
    arr.add(agentTool("get_coupons",
        "本人加餐券：可领取、可使用、余额、有效期。需加餐券登录。",
        agentParams("status" to agentEnumProp("all=可领取+可使用；available=可领取；usable=可使用。缺省：all。", "all", "available", "usable"), agentShow())))
    arr.add(agentTool("get_lms",
        "思源学堂：不给 course 列出本人课程；给 course 列出该课的作业、课件、回放等活动；scope=assignments 汇总全部课程的作业（逐课查询，较慢）。需思源学堂登录。",
        agentParams(
            "course" to agentStrProp("课程名，模糊。"),
            "scope" to agentEnumProp("assignments：全部作业汇总。", "assignments"),
            agentShow(),
        )))
    arr.add(agentTool("get_lms_activity",
        "思源活动详情、截止时间、提交状态和附件。给 file 读取附件：PDF 用 info 看元数据与书签目录，text 快速取文字，images 看指定页面；auto 文字优先。附件复用缓存，同 read_zyxf_file。需思源登录。",
        agentParams(
            "course" to agentStrProp("课程名，模糊。"),
            "activity" to agentStrProp("活动标题，模糊。"),
            "file" to agentStrProp("附件ID或完整名称优先，也可用唯一关键词；多项匹配会返回候选。"),
            "pages" to agentStrProp("PDF/pptx 实际页码，从1起；5只读第5页，支持1,3,7-8。默认前2页；文本最多20页，图最多4页。"),
            "mode" to agentEnumProp("PDF：info=元数据和书签目录；text=只取文字；images=页面图；auto=文字优先，空文字页转图（默认）。", "auto", "info", "text", "images"),
            "text_offset" to agentIntProp("PDF 单页文本续读的字符偏移，使用结果中的 next；默认0。"),
            agentShow(),
            required = listOf("course", "activity"),
        )))
    arr.add(agentTool("get_fitness_score",
        "本人体测成绩：总分、等级、分项。按学年计。需体测登录。",
        agentParams("year" to agentStrProp("学年起始年，2025=2025-2026 学年。缺省：当前已开测学年。"))))
    if (AgentCapability.APP_SETTINGS in honored) {
        arr.add(agentTool("app_setting",
            "本应用设置：不给 value 时列出全部设置的当前值与可选值；给 key 和 value 时修改该项。",
            agentParams(
                "key" to agentEnumProp("设置项。", "dark_mode", "dynamic_color", "nav_bar_style", "show_quick_actions",
                    "default_tab", "network_mode", "account_type", "venue_auto_solve_captcha", "update_channel", "receive_preview_updates"),
                "value" to agentStrProp("新取值。"),
                agentShow(),
            )))
    }
    arr.add(agentTool("app_guide",
        "查询本 App 的完整功能与操作入口，包括屁岱配置、MCP 扩展工具、日程、下载与隐私；以及兴庆、创新港的食堂和开放时间。回答 App 能力或设置问题时先查此工具。",
        agentParams("topic" to agentEnumProp("app：功能与入口；campus：食堂与开放时间。缺省：两者都给。", "app", "campus"))))
    arr.add(agentTool("calculate",
        "计算表达式：+ - * / ^ 与括号。",
        agentParams("expression" to agentStrProp("如 (3.7*4+4.0*3)/(4+3)。"), required = listOf("expression"))))
    return JsonArray(arr.map(AgentWidgetPresentation::queryDefinition)).toString()
}
