package com.xjtu.toolbox.jwxt

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.CasSafetyVerifyPage
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 假的「教务系统」上游（`jwxt.xjtu.edu.cn`）。 */
class JwxtFakeUpstream {

    companion object {
        const val HOST = "jwxt.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        /** 登录入口页，必须与 `XJTULogin.JWXT_URL` 相同（有断言钉着）。 */
        const val HOME_URL = "$ORIGIN/jwapp/sys/homeapp/index.do"

        /** 课表查询应用（`SchoolCourseApi` 的 `appBase`）。 */
        const val KCB_CX = "$ORIGIN/jwapp/sys/kcbcx"
        const val CURRENT_TERM_URL = "$KCB_CX/modules/bjkcb/dqxnxq.do"
        const val TERM_LIST_URL = "$KCB_CX/modules/bjkcb/xnxqcx.do"
        const val DEPARTMENTS_URL = "$ORIGIN/jwapp/code/44e02e19-e31b-4916-91b2-0a04380cbd3a.do"
        const val QUERY_URL = "$KCB_CX/modules/qxkcb/qxfbkccx.do"
        const val APP_INDEX_URL = "$KCB_CX/*default/index.do"

        /** 学生课表应用（`wdkb`）—— `:app` 的 `schedule/ScheduleApi.kt` 打的就是它（与 kcbcx 同一个域名）。 */
        const val WDKB = "$ORIGIN/jwapp/sys/wdkb"
        const val SCHEDULE_URL = "$WDKB/modules/xskcb/xskcb.do"
        const val TERM_START_URL = "$WDKB/modules/jshkcb/cxjcs.do"

        /** 课表应用（`wdkb`）自己的「当前学期」与「调停补课」两条端点。 */
        const val WDKB_CURRENT_TERM_URL = "$WDKB/modules/jshkcb/dqxnxq.do"

        /** 调停补课记录（`xskcb/xsdkkc.do`）—— 课表合并那一半的数据源，请求里必须带 `XNXQDM`。 */
        const val CHANGES_URL = "$WDKB/modules/xskcb/xsdkkc.do"

        /** 考试安排（`studentWdksapApp`，与课表同一个域名）。 */
        const val EXAM_URL = "$ORIGIN/jwapp/sys/studentWdksapApp/modules/wdksap/wdksap.do"

        /** 帆软成绩报表。 */
        const val REPORT_URL = "$ORIGIN/jwapp/sys/frReport2/show.do"

        /** 回跳后本站自己的会话 cookie（`JwxtSession.validateLogin` 不看它，只是让回跳像真的）。 */
        const val JWXT_SESSION_VALUE = "JSESSIONID-jwxt-1"

        // ── 学期样本（与 `MC` 的写法一起给：解析要的就是这两个键）──

        const val TERM_NEW = "2026-2027-1"
        const val TERM_NEW_NAME = "2026-2027学年 第一学期"
        const val TERM_OLD = "2025-2026-2"
        const val TERM_OLD_NAME = "2025-2026学年 第二学期"

        /** 第三行**只有 DM**：解析的兜底是「没有 MC 就拿 DM 当名字」，这条就是钉它的。 */
        const val TERM_BARE = "2024-2025-1"


        const val CJCX_INDEX_PATH = "/jwapp/sys/cjcx/*default/index.do"
        const val CJCX_QUERY_PATH = "/jwapp/sys/cjcx/modules/cjcx/xscjcx.do"

        /** `xscjcx.do` 的两行。 */
        const val CJCX_KCM = com.xjtu.toolbox.jwapp.JwappFakeUpstream.COURSE_NAME
        const val CJCX_TERM = com.xjtu.toolbox.jwapp.JwappFakeUpstream.TERM_CODE
        const val CJCX_ZCJ = 88.0
        const val CJCX_XFJD = 3.9
        const val CJCX_XF = 5.0
        const val CJCX_KCH = "MATH1001"
        const val CJCX_OTHER_KCM = "大学物理"
        const val CJCX_OTHER_TERM = TERM_OLD
        const val CJCX_OTHER_ZCJ = 91.5
        const val CJCX_OTHER_XFJD = 4.1

        const val DEPT_PHYSICS = "物理学院"
        const val DEPT_MATH = "数学与统计学院"
        const val DEPT_MECH = "机械工程学院"

        // ── 课表样本 ──

        const val COURSE_1_NAME = "高等数学（上）"
        const val COURSE_2_NAME = "大学物理"
        const val COURSE_3_NAME = "艺术导论"
        const val TOTAL_SIZE = 3

        // ── 学生课表样本：`SKXQ`/`KSJC`/`JSJC` 故意给 JSON 数字，解析要读成文本 ──

        /** 第一行：整学期课（`ZCMC` = `1-16周`）。 */
        const val SCHEDULE_1_COURSE = "高等数学（上）"
        const val SCHEDULE_1_TEACHER = "示例甲"
        const val SCHEDULE_1_ROOM = "主楼A-101"
        const val SCHEDULE_1_WEEKS = "1-16周"
        const val SCHEDULE_1_JXBID = "JXB-8001"
        const val SCHEDULE_1_DAY = 1
        const val SCHEDULE_1_START = 1
        const val SCHEDULE_1_END = 2

        /** 第二行：单周课（`ZCMC` 的 `(单)` 写法）—— 周次读的是这一格，不是 `SKZC` 位串。 */
        const val SCHEDULE_2_COURSE = "大学物理"
        const val SCHEDULE_2_TEACHER = "示例乙"
        const val SCHEDULE_2_ROOM = "中2-3201"
        const val SCHEDULE_2_WEEKS = "1-15周(单)"
        const val SCHEDULE_2_JXBID = "JXB-8002"
        const val SCHEDULE_2_DAY = 3
        const val SCHEDULE_2_START = 3
        const val SCHEDULE_2_END = 4

        /** 第三行只有 `KCM`：缺的键读成空串。 */
        const val SCHEDULE_3_COURSE = "艺术导论"

        /** 学期起点：`XQKSRQ` 给到秒（解析只取日期那一段），`ZZC` = 总周数（含考试周）。 */
        const val TERM_START_DATE = "2026-09-14"
        const val TERM_TOTAL_WEEKS = 18

        // ── 调停补课样本（编造值）：一条 MOVED，第 5 周的高等数学（上）从 [SCHEDULE_1_ROOM] 换到 [CHANGE_NEW_ROOM] ──

        /** 调课记录的被调课教学班：必须等于 [SCHEDULE_1_JXBID]，否则合不上任何一门课。 */
        const val CHANGE_JXBID = SCHEDULE_1_JXBID
        const val CHANGE_COURSE = SCHEDULE_1_COURSE
        const val CHANGE_OLD_ROOM = SCHEDULE_1_ROOM
        const val CHANGE_NEW_ROOM = "主楼B-202"
        const val CHANGE_WEEK = 5

        /** `SKZC`/`XSKZC` 位串（16 位，只有第 [CHANGE_WEEK] 位是 `1`）。 */
        const val CHANGE_BITS = "0000100000000000"
        const val CHANGE_APPLIED_AT = "2026-09-28 09:00:00"

        // ── 考试安排样本（`wdksap.do`）──
        const val EXAM_COURSE = SCHEDULE_1_COURSE
        const val EXAM_CODE = "MATH1001"
        const val EXAM_DATE = "2027-01-15"
        /** 上游把日期与时段塞在同一格（`KSRQ` 带 `00:00:00`、`KSSJMS` 带日期）—— 解析要剥掉两边。 */
        const val EXAM_TIME = "14:30-16:30"
        const val EXAM_ROOM = "主楼A-101"
        const val EXAM_SEAT = "27"

        /** 教材报表的 reportlet 与它那个表单字段名（`:data` 里那两处字面量）。 */
        const val TEXTBOOK_REPORTLET = "jcgl/wdjc.cpt"
        const val TEXTBOOK_REPORT_PARAM = "reportlets"

        /** 第一行：一门正常有教材的课（八格都有值）。 */
        const val TEXTBOOK_COURSE = "线性代数"
        const val TEXTBOOK_COURSE_CODE = "MATH1002"
        const val TEXTBOOK_NAME = "线性代数（第七版）"
        const val TEXTBOOK_AUTHOR = "示例作者"
        const val TEXTBOOK_PUBLISHER = "示例出版社"
        const val TEXTBOOK_ISBN = "978-7-0000-0000-0"
        const val TEXTBOOK_PRICE = "39.00"
        const val TEXTBOOK_EDITION = "第7版"

        /** 第二行：「无教材」那一档 —— 书名就是「无教材」，ISBN 是报表那串占位号 （[TEXTBOOK_PLACEHOLDER_ISBN]）。 */
        const val TEXTBOOK_NONE_COURSE = "大学物理"
        const val TEXTBOOK_NONE_COURSE_CODE = "PHYS1002"
        const val TEXTBOOK_NONE_NAME = "无教材"
        const val TEXTBOOK_PLACEHOLDER_ISBN = "9780000000000"


        // ── 成绩报表样本 ──

        const val FR_SESSION_ID = "7391502846"
        const val REPORT_TOTAL_PAGES = 2

        /** 学生的学号（= CAS 那组假账号里的同一个；登录之后 `AccountContext.activeAccountId` 就是它）。 */
        const val STUDENT_ID = LibraryFakeUpstream.USERNAME

        /** 第一页的学期标题 → 学期代码 `2025-2026-1`。 */
        const val SCORE_TERM_SPRING = "2025-2026-1"
        const val SCORE_TERM_SPRING_HEADING = "2025-2026学年 第一学期"
        /** 第二页的学期标题是**夏季小学期**（一个数字都没有）→ `2025-2026-3`。 */
        const val SCORE_TERM_SUMMER = "2025-2026-3"
        const val SCORE_TERM_SUMMER_HEADING = "2025-2026学年 夏季小学期"

        const val SCORE_1_NAME = "高等数学（上）"
        const val SCORE_2_NAME = "大学物理"
        const val SCORE_3_NAME = "工程训练"


        /** 评教应用前缀（`JudgeApi` 里那些 URL 的共同前半截）。 */
        const val WSPJ_APP = "$ORIGIN/jwapp/sys/wspjyyapp"

        const val JUDGE_INDEX_URL = "$WSPJ_APP/modules/xspj/index.do"
        const val JUDGE_TERM_URL = "$WSPJ_APP/modules/xspj/cxxtcs.do"
        const val JUDGE_LIST_URL = "$WSPJ_APP/modules/xspj/cxdwpj.do"
        const val JUDGE_QUESTION_URL = "$WSPJ_APP/modules/wj/cxwjzb.do"
        const val JUDGE_OPTION_URL = "$WSPJ_APP/modules/wj/cxxswjzbxq.do"

        /** 评教的当前学期（`CSZA`）。 */
        const val JUDGE_TERM = TERM_NEW

        /** `cxxswjzbxq.do` 的 `querySetting` 里必须出现的六个条件（少一个就可能领回别人的卷子）。 */
        val JUDGE_OPTION_CONDITIONS = listOf("BPR", "CPR", "JXBID", "PGNR", "WJDM", "PCDM")

        /** 问卷的定位字段。`JudgeCard.key` 就是 `WJDM_JXBID_BPR`，断言直接引用它们。 */
        const val JUDGE_WJDM_MID = "WJ-2001"
        const val JUDGE_WJDM_FINAL = "WJ-1001"
        const val JUDGE_WJDM_DONE = "WJ-3001"
        const val JUDGE_JXBID_FINAL = "JXB-9001"
        const val JUDGE_JXBID_DONE = "JXB-9100"
        const val JUDGE_JXBID_MID = "JXB-9002"
        const val JUDGE_WJDM_FINAL_BARE = "WJ-1002"
        const val JUDGE_JXBID_FINAL_BARE = "JXB-9003"
        const val JUDGE_BPR_FINAL = "示例己"
        const val JUDGE_TEACHER_FINAL = "示例己"
        const val JUDGE_TEACHER_MID = "示例丁"
        const val JUDGE_TEACHER_FINAL_BARE = "示例戊"
        const val JUDGE_PGNR = "期末评教（2026-2027学年第一学期）"

        /** 四条问卷（未评：过程一行 + 期末两行；已评：过程空表 + 期末一行）的课名。 */
        const val JUDGE_COURSE_MID = "高等数学（上）"
        const val JUDGE_COURSE_FINAL = "数值传热学"
        const val JUDGE_COURSE_FINAL_BARE = "艺术导论"
        const val JUDGE_COURSE_DONE = "流体力学"

        // ── 评教题目与选项的样本（`ZB-01` 是唯一带选项的题）──

        /** 选项的两个编号：`DADM` 是答案代码（`QuestionnaireOptionData.DADM`），`DA` 是 要填回问卷的选项编号（服务端字段名是 `DAFXDM`）。 */
        const val JUDGE_OPTION_DADM_BEST = "DA-ZB01-100"
        const val JUDGE_OPTION_DA_BEST = "100"
        const val JUDGE_OPTION_DADM_SECOND = "DA-ZB01-80"
        const val JUDGE_OPTION_DA_SECOND = "80"
        const val JUDGE_OPTION_DADM_THIRD = "DA-ZB01-60"
        const val JUDGE_OPTION_DA_THIRD = "60"

        // ── 空闲教室「直查教务」：那两个 UUID 是上游写死的接口编号 ──

        const val EMPTY_ROOM_QUERY_URL = "$ORIGIN/jwapp/sys/kxjas/modules/kxjscx/cxkxjs.do"
        const val EMPTY_ROOM_CAMPUS_CODE_URL = "$ORIGIN/jwapp/code/83a986fc-e677-400e-99a4-c7bb39c2ca35.do"
        const val EMPTY_ROOM_BUILDING_CODE_URL = "$ORIGIN/jwapp/code/551fbcc3-cf07-4566-af1e-fc7ce272ddc1.do"
        const val CURRENT_USER_URL = "$ORIGIN/jwapp/sys/homeapp/api/home/currentUser.do"
        const val CHANGE_ROLE_URL = "$ORIGIN/jwapp/sys/homeapp/api/home/changeAppRole.do"

        /** 直查那三间教室：与 `EmptyRoomFakeUpstream`（CDN / 实时状态）**同名**，屏按名字把两档对上。 */
        const val EMPTY_ROOM_A101 = "主楼A-101"
        const val EMPTY_ROOM_A102 = "主楼A-102"
        const val EMPTY_ROOM_A103 = "主楼A-103"

        /** 教室容量（`SKZWS`）—— 与 CDN 那份样本一致，`RoomInfo.size` 两边对得上。 */
        const val EMPTY_ROOM_A101_SEATS = 60
        const val EMPTY_ROOM_A102_SEATS = 48
        const val EMPTY_ROOM_A103_SEATS = 40

        /** 上午排满、下午空着的那间：第 6-11 节空闲（逐节问出来的 11 节状态就靠它）。 */
        val EMPTY_ROOM_A101_FREE_PERIODS = listOf(6, 7, 8, 9, 10, 11)

        /** 全天都空的一间（11 节全是空闲）。 */
        val EMPTY_ROOM_A102_FREE_PERIODS = (1..11).toList()

        /** 一节课都没有的那间：11 节全是占用。 */
        val EMPTY_ROOM_A103_FREE_PERIODS = emptyList<Int>()

        /** 教室类型的显示名（`JASLXDM_DISPLAY` ⇒ `DirectRoomRow.type`）。 */
        const val EMPTY_ROOM_TYPE = "多媒体教室"

        /** 校区显示名（`XXXQDM_DISPLAY` ⇒ `DirectRoomRow.campusName`）。 */
        const val EMPTY_ROOM_CAMPUS_NAME = "兴庆校区"

        /** 两条「噪声行」：`JASLXDM` 为 JSON null（接口幻觉教室）与名字里带「测试专用」，都该被滤掉。 */
        const val EMPTY_ROOM_HALLUCINATION = "主楼A-999"
        const val EMPTY_ROOM_TEST_NAME = "主楼A-901测试专用"
    }

    // ── 服务器可变状态：动作打进来时计数/记录，好让断言「这一枪真打到了哪里」──

    /** 没带 ticket、本站也没有会话 cookie 时，把人交给统一认证的次数。 */
    val casRedirects = AtomicInteger()

    /** CAS 回跳（带 ticket）落在本站的次数 —— 用户级「登录真的走完了」。 */
    val ticketLandings = AtomicInteger()

    /** 二次认证剧本的开关：开上之后，带 ticket 的回跳给的不是首页而是 [safetyVerifyPage]。 */
    val requireSafetyVerify = AtomicBoolean(false)

    /** 二次认证页被给出去几次（= 登录链真的在那一步被拦住了）。 */
    val safetyVerifyLandings = AtomicInteger()

    /** 二次认证页的隐藏表单被回提了几次（= 验证码真过了、`MfaContext.verifyCode` 走到了最后一步）。 */
    val safetyVerifySubmissions = AtomicInteger()

    /** 最近一次回提的表单原文（断言 `secState`/`execution`/`_eventId`/`submit`/`fpVisitorId` 真发出去了）。 */
    @Volatile
    var lastSafetyVerifyForm: String? = null
        private set

    /** `ensureAppInitialized` 的预热请求打过几次。 */
    val appIndexCalls = AtomicInteger()

    val currentTermCalls = AtomicInteger()
    val termListCalls = AtomicInteger()
    val departmentCalls = AtomicInteger()
    val queryCalls = AtomicInteger()

    /** 学生课表的两条端点各打过几次（`wdkb`：`xskcb.do` / `jshkcb/cxjcs.do`）。 */
    val scheduleCalls = AtomicInteger()
    val termStartCalls = AtomicInteger()

    /** `wdkb` 的「当前学期」/「调停补课」/「考试安排」三条各打过几次。 */
    val wdkbCurrentTermCalls = AtomicInteger()
    val changesCalls = AtomicInteger()
    val examCalls = AtomicInteger()

    /** 成绩报表的翻页请求打过几次（`totalPages=2` ⇒ 一次 `pn=1` + 一次 `pn=2`）。 */
    val reportPageCalls = AtomicInteger()

    /** 精确成绩（`cjcx` 应用的 `xscjcx.do`）与它的预热请求打过几次。 */
    val cjcxIndexCalls = AtomicInteger()
    val cjcxCalls = AtomicInteger()

    /** 最近一次 `xscjcx.do` 的表单原文（断言 `querySetting` / `pageSize` / `pageNumber` 真发了）。 */
    @Volatile
    var lastCjcxForm: String? = null
        private set

    /** 最近一次 `xnxqcx.do` 的表单原文（断言 `*order=-DM` 真发出去了）。 */
    @Volatile
    var lastTermListForm: String? = null
        private set

    /** 最近一次 `qxfbkccx.do` 的表单原文（断言 `querySetting` 与分页参数真发出去了）。 */
    @Volatile
    var lastQueryForm: String? = null
        private set

    /** 最近一次 `xskcb.do` 的表单原文（断言 `XNXQDM=<学期>` 真发出去了）。 */
    @Volatile
    var lastScheduleForm: String? = null
        private set

    /** 最近一次 `cxjcs.do` 的表单原文（断言学期号真被切成了 `XN` + `XQ` 两格）。 */
    @Volatile
    var lastTermStartForm: String? = null
        private set

    /** 最近一次 `xsdkkc.do` 的表单原文（断言 `XNXQDM=<学期>` 真发出去了）。 */
    @Volatile
    var lastChangesForm: String? = null
        private set

    /** 最近一次 `wdksap.do` 的表单原文（断言 `XNXQDM=<学期>` 与 `*order` 真发出去了）。 */
    @Volatile
    var lastExamForm: String? = null
        private set

    /** 教材报表初始化那一枪打过几次，以及最近一次 `reportlets` 字段的原文（URL 解码之后）。 */
    val textbookReportCalls = AtomicInteger()

    @Volatile
    var lastTextbookReportlet: String? = null
        private set

    /** 最近一次报表初始页请求里带的学号（`xh=`）。 */
    @Volatile
    var lastReportStudentId: String? = null
        private set

    /** 最近一次翻页请求里带的会话 id（`sessionID=`），应当等于本夹具发的 [FR_SESSION_ID]。 */
    @Volatile
    var lastReportSessionId: String? = null
        private set


    /** 预热 `index.do` / 当前学期 / 列表 / 题目 / 选项 各打过几次。 */
    val judgeIndexCalls = AtomicInteger()
    val judgeTermCalls = AtomicInteger()
    val judgeListCalls = AtomicInteger()
    val judgeQuestionCalls = AtomicInteger()
    val judgeOptionCalls = AtomicInteger()

    /** 四次列表请求的**表单原文**（未评与已评各一遍：`05/0`、`01/0`、`05/1`、`01/1`）。 */
    val judgeListForms = CopyOnWriteArrayList<String>()

    @Volatile
    var lastJudgeTermForm: String? = null
        private set

    @Volatile
    var lastJudgeListForm: String? = null
        private set

    @Volatile
    var lastJudgeQuestionForm: String? = null
        private set

    @Volatile
    var lastJudgeOptionForm: String? = null
        private set


    /** `currentUser.do` 打过几次。 */
    val currentUserCalls = AtomicInteger()

    /** `changeAppRole.do` 打过几次 —— 夹具扮的就是学生身份 ⇒ 正常应当一直是 0。 */
    val changeRoleCalls = AtomicInteger()

    /** 校区 / 教学楼代码表各打过几次。 */
    val campusCodeCalls = AtomicInteger()
    val buildingCodeCalls = AtomicInteger()

    /** `cxkxjs.do` 打过几次（全楼一次 + 逐节 11 次 = 12）。 */
    val emptyRoomQueryCalls = AtomicInteger()

    /** 最近一次 `cxkxjs.do` 的表单原文（断言 XXXQDM/JXLDM/KXRQ/KSJC/JSJC 真发出去了）。 */
    @Volatile
    var lastEmptyRoomForm: String? = null
        private set

    // ── 样本：页面与 JSON 原文（消费者共用这一份）──────────────────

    /** 登录链最后一跳（CAS 回跳之后）落到的首页。**不能**长得像 CAS 登录页。 */
    val homePage = """
        <html><head><title>西安交通大学教务管理系统</title></head><body>
          <div id="app">本科教务 · 学生端</div>
        </body></html>
    """.trimIndent()

    /** CAS 的「Safety Verify」二次认证页 —— 只有 [requireSafetyVerify] 打开时才会被给出去。 */
    val safetyVerifyPage: String get() = CasSafetyVerifyPage.HTML

    /** `kcbcx` 应用的首页（预热用，客户端只 close 不解析）。 */
    val appIndexPage = """
        <html><head><title>课程表查询</title></head><body>
          <div id="app">全校课程查询</div>
        </body></html>
    """.trimIndent()

    /** `cjcx` 应用的首页（`CjcxApi.ensureSession` 预热用，客户端只 close 不解析）。 */
    val cjcxIndexPage = """
        <html><head><title>成绩查询</title></head><body>
          <div id="app">学生成绩查询</div>
        </body></html>
    """.trimIndent()

    /** `xscjcx.do`：`datas.xscjcx.rows` 两行（`totalSize` 也是 2）。 */
    val cjcxJson = """
        {"code":"0","datas":{"xscjcx":{"totalSize":2,"rows":[
          {"KCM":"$CJCX_KCM","XNXQDM":"$CJCX_TERM","ZCJ":$CJCX_ZCJ,"XFJD":$CJCX_XFJD,"XF":$CJCX_XF,
           "DJCJLXDM":"","DJCJMC":"","PSCJ":-1.0,"PSCJXS":"","QMCJ":-1.0,"QMCJXS":"",
           "KCH":"$CJCX_KCH","JXBID":"JXB-9101","SFJG":"1",
           "CXCKDM_DISPLAY":"初修","KCLBDM_DISPLAY":"必修"},
          {"KCM":"$CJCX_OTHER_KCM","XNXQDM":"$CJCX_OTHER_TERM","ZCJ":$CJCX_OTHER_ZCJ,
           "XFJD":$CJCX_OTHER_XFJD,"XF":4.0,"DJCJLXDM":"","DJCJMC":"","PSCJ":-1.0,"PSCJXS":"",
           "QMCJ":-1.0,"QMCJXS":"","KCH":"PHYS1001","JXBID":"JXB-9102","SFJG":"1",
           "CXCKDM_DISPLAY":"初修","KCLBDM_DISPLAY":"必修"}
        ]}}}
    """.trimIndent()

    /** 当前学期：`datas.dqxnxq.rows[0].DM`。 */
    val currentTermJson = """
        {"datas":{"dqxnxq":{"rows":[{"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"}]}}}
    """.trimIndent()

    /** 学生课表的整学期行（`datas.xskcb.rows`）：三行。 */
    val scheduleJson = """
        {"datas":{"xskcb":{"rows":[
          {"JXBID":"$SCHEDULE_1_JXBID","KCH":"MATH1001","KCM":"$SCHEDULE_1_COURSE",
           "SKJS":"$SCHEDULE_1_TEACHER","JASMC":"$SCHEDULE_1_ROOM","SKXQ":$SCHEDULE_1_DAY,
           "KSJC":$SCHEDULE_1_START,"JSJC":$SCHEDULE_1_END,"ZCMC":"$SCHEDULE_1_WEEKS","SKZC":"1111111111111111"},
          {"JXBID":"$SCHEDULE_2_JXBID","KCH":"PHYS1002","KCM":"$SCHEDULE_2_COURSE",
           "SKJS":"$SCHEDULE_2_TEACHER","JASMC":"$SCHEDULE_2_ROOM","SKXQ":$SCHEDULE_2_DAY,
           "KSJC":$SCHEDULE_2_START,"JSJC":$SCHEDULE_2_END,"ZCMC":"$SCHEDULE_2_WEEKS","SKZC":"1010101010101010"},
          {"KCM":"$SCHEDULE_3_COURSE"}
        ]}}}
    """.trimIndent()

    /** 学期起点：`datas.cxjcs.rows[0]` 的 `XQKSRQ`（给到秒）+ `ZZC`。 */
    val termStartJson = """
        {"datas":{"cxjcs":{"rows":[{"XN":"$TERM_NEW","XQKSRQ":"$TERM_START_DATE 00:00:00","ZZC":"$TERM_TOTAL_WEEKS"}]}}}
    """.trimIndent()

    /** `wdkb` 自己的当前学期（与 kcbcx 那条同名端点给的是同一个学期，但**路由不同**）。 */
    val wdkbCurrentTermJson = """
        {"datas":{"dqxnxq":{"rows":[{"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"}]}}}
    """.trimIndent()

    /** 调停补课：一条 MOVED（第 [CHANGE_WEEK] 周的高等数学换到 [CHANGE_NEW_ROOM]）。 */
    val changesJson = """
        {"datas":{"xsdkkc":{"rows":[
          {"TKLXDM":"1","JXBID":"$CHANGE_JXBID","KCM":"$CHANGE_COURSE","KCH":"$EXAM_CODE",
           "SKZC":"$CHANGE_BITS","SKXQ":$SCHEDULE_1_DAY,"KSJC":$SCHEDULE_1_START,"JSJC":$SCHEDULE_1_END,
           "JASMC":"$CHANGE_OLD_ROOM","YSKJS":"${SCHEDULE_1_TEACHER}/0001",
           "XSKZC":"$CHANGE_BITS","XSKXQ":$SCHEDULE_1_DAY,"XKSJC":$SCHEDULE_1_START,"XJSJC":$SCHEDULE_1_END,
           "XJASMC":"$CHANGE_NEW_ROOM","XSKJS":"${SCHEDULE_1_TEACHER}/0001","SQSJ":"$CHANGE_APPLIED_AT"}
        ]}}}
    """.trimIndent()

    /** 考试安排：一行；`KSRQ` 给到秒、`KSSJMS` 里又带一遍日期（解析要剥干净）。 */
    val examJson = """
        {"datas":{"wdksap":{"rows":[
          {"KCM":"$EXAM_COURSE","KCH":"$EXAM_CODE","KSRQ":"$EXAM_DATE 00:00:00",
           "KSSJMS":"$EXAM_DATE $EXAM_TIME","JASMC":"$EXAM_ROOM","ZWH":"$EXAM_SEAT"}
        ]}}}
    """.trimIndent()
    /** 教材报表的初始页（`:data` 的 `JwxtScheduleApi.textbooks` Step 1/3 那一枪）。 */
    val textbookReportPage = """
        <html><head><title>教材使用情况</title></head><body>
        <table><tbody>
          <tr><th>课程号</th><th>课程名</th><th>书名</th><th>主编</th><th>出版社</th><th>ISBN号</th><th>定价</th><th>版次</th></tr>
          <tr><td>$TEXTBOOK_COURSE_CODE</td><td>$TEXTBOOK_COURSE</td><td>$TEXTBOOK_NAME</td><td>$TEXTBOOK_AUTHOR</td><td>$TEXTBOOK_PUBLISHER</td><td>$TEXTBOOK_ISBN</td><td>$TEXTBOOK_PRICE</td><td>$TEXTBOOK_EDITION</td></tr>
          <tr><td>$TEXTBOOK_NONE_COURSE_CODE</td><td>$TEXTBOOK_NONE_COURSE</td><td>$TEXTBOOK_NONE_NAME</td><td></td><td></td><td>$TEXTBOOK_PLACEHOLDER_ISBN</td><td></td><td></td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()


    /** 学期列表：三行，**倒序给**（上游按 `*order=-DM` 排好），第三行只有 DM。 */
    val termListJson = """
        {"datas":{"xnxqcx":{"rows":[
          {"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"},
          {"DM":"$TERM_OLD","MC":"$TERM_OLD_NAME"},
          {"DM":"$TERM_BARE"}
        ]}}}
    """.trimIndent()

    /** 开课单位：**乱序给** —— `getDepartments` 自己按名字排（断言里是排好的序）。 */
    val departmentsJson = """
        {"datas":{"code":{"rows":[
          {"id":"20001000","name":"$DEPT_PHYSICS"},
          {"id":"01001000","name":"$DEPT_MATH"},
          {"id":"10001000","name":"$DEPT_MECH"}
        ]}}}
    """.trimIndent()

    /** 全校课表一页。 */
    val queryJson = """
        {"datas":{"qxfbkccx":{"totalSize":$TOTAL_SIZE,"rows":[
          {"KCH":"MATH1001","KCM":"$COURSE_1_NAME","KXH":"01","SKJS":"示例甲",
           "KKDWDM_DISPLAY":"$DEPT_MATH","XF":"5.0","XS":"80","SKXS":"80","SYXS":"0","SJXS":"0",
           "XKZRS":"120","KRL":"150","SKBJ":"电气2401-2402",
           "YPSJDD":"兴庆校区 主楼A101 周一 1-2节","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"0","XGXKLBDM_DISPLAY":"","KNZXS":"5.0","NSXKRS":"80","NVSXKRS":"40",
           "JXBID":"JXB-1001","XNXQDM":"$TERM_NEW"},
          {"KCH":"PHYS1002","KCM":"$COURSE_2_NAME","KXH":"02","SKJS":"示例乙",
           "KKDWDM_DISPLAY":"$DEPT_PHYSICS","XF":"4.0","XS":"64","SKXS":"48","SYXS":"16",
           "KRL":"120","SKBJ":"物理2401",
           "YPSJDD":"兴庆校区 中2楼1200 周三 3-4节","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"1","XGXKLBDM_DISPLAY":"基础通识类核心课","KNZXS":"4.0",
           "JXBID":"JXB-1002","XNXQDM":"$TERM_NEW"},
          {"KCH":"GEN1003","KCM":"$COURSE_3_NAME","KXH":"05","SKJS":"示例丙",
           "KKDWDM_DISPLAY":"人文社会科学学院","XF":"2.0","XS":"32","SKXS":"32",
           "XKZRS":"80","KRL":"80","SKBJ":"全校公选","XXXQDM_DISPLAY":"兴庆校区",
           "SFXGXK":"1","XGXKLBDM_DISPLAY":"基础通识类选修课",
           "JXBID":"JXB-1003","XNXQDM":"$TERM_NEW"}
        ]}}}
    """.trimIndent()

    /** 成绩报表的初始页：只给会话 id（`extractFrSessionId` 的第一条判据）。 */
    val reportInitPage = """
        <html><head><title>学生成绩报表</title>
        <script>FR.SessionMgr.register('$FR_SESSION_ID', {bookID:'bkdsglxjtu/XAJTDX_BDS_CJ.cpt'});</script>
        </head><body><div id="fr-container"></div></body></html>
    """.trimIndent()

    /** 成绩报表第 1 页。 */
    val reportPage1 = """
        <html><body>
        <script>FR._p.reportTotalPage = $REPORT_TOTAL_PAGES;</script>
        <table><tbody>
          <tr><td>第 1 页</td><td>共 $REPORT_TOTAL_PAGES 页</td></tr>
          <tr><td>先修统计</td><td>2.0</td><td>90</td></tr>
          <tr><td>$SCORE_TERM_SPRING_HEADING</td></tr>
          <tr><td>课程</td><td>学分</td><td>成绩</td></tr>
          <tr><td>$SCORE_1_NAME</td><td>5.0</td><td>95</td></tr>
          <tr><td>$SCORE_2_NAME</td><td>4.0</td><td>优秀</td></tr>
          <tr><td>体育</td><td>—</td><td>—</td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    /** 评教应用的首页（预热那一枪，客户端只 close 不解析）。 */
    val judgeIndexPage = """
        <html><head><title>学生评教</title></head><body>
          <div id="app">学生评教</div>
        </body></html>
    """.trimIndent()

    /** 评教的当前学期：`datas.cxxtcs.rows[0].CSZA`。 */
    val judgeTermJson = """{"datas":{"cxxtcs":{"rows":[{"CSZA":"$JUDGE_TERM"}]}}}"""

    /** 未评 · 过程评教（`PGLXDM=05`、`SFPG=0`）：一行。 */
    val judgeUnfinishedMidJson = """
        {"datas":{"cxdwpj":{"rows":[
          {"BPJS":"$JUDGE_TEACHER_MID","BPR":"$JUDGE_TEACHER_MID","DBRS":"0",
           "JSSJ":"2026-10-25 23:59:00","JXBID":"$JUDGE_JXBID_MID","KCH":"MATH1001",
           "KCM":"$JUDGE_COURSE_MID","KSSJ":"2026-10-01 08:00:00","PCDM":"PCDM-2026-05",
           "PGLXDM":"05","PGNR":"过程评教（第一次）","WJDM":"$JUDGE_WJDM_MID",
           "WJMC":"过程评教问卷","XNXQDM":"$JUDGE_TERM"}
        ]}}}
    """.trimIndent()

    /** 未评 · 期末评教（`PGLXDM=01`、`SFPG=0`）：两行。 */
    val judgeUnfinishedFinalJson = """
        {"datas":{"cxdwpj":{"rows":[
          {"BPJS":"$JUDGE_TEACHER_FINAL","BPR":"$JUDGE_BPR_FINAL","DBRS":"2",
           "JSSJ":"2027-01-10 23:59:00","JXBID":"$JUDGE_JXBID_FINAL","KCH":"031002",
           "KCM":"$JUDGE_COURSE_FINAL","KSSJ":"2026-12-20 08:00:00","PCDM":"PCDM-2026-01",
           "PGLXDM":"01","PGNR":"$JUDGE_PGNR","WJDM":"$JUDGE_WJDM_FINAL",
           "WJMC":"期末评教问卷","XNXQDM":"$JUDGE_TERM"},
          {"BPJS":"$JUDGE_TEACHER_FINAL_BARE","BPR":"示例庚","JXBID":"$JUDGE_JXBID_FINAL_BARE",
           "KCM":"$JUDGE_COURSE_FINAL_BARE","KSSJ":"2026-12-20 08:00:00","PCDM":"PCDM-2026-01",
           "PGLXDM":"01","PGNR":"$JUDGE_PGNR","WJDM":"$JUDGE_WJDM_FINAL_BARE",
           "WJMC":"期末评教问卷","XNXQDM":"$JUDGE_TERM"}
        ]}}}
    """.trimIndent()

    /** 已评 · 过程评教：**空表**（夹具就是要钉住「没有就是空列表，不是报错」）。 */
    val judgeFinishedMidJson = """{"datas":{"cxdwpj":{"rows":[]}}}"""

    /** 已评 · 期末评教：一行，**刻意缺 `BPJS`** ⇒ 空串。 */
    val judgeFinishedFinalJson = """
        {"datas":{"cxdwpj":{"rows":[
          {"BPR":"示例己","DBRS":"1","JSSJ":"2026-06-20 23:59:00","JXBID":"$JUDGE_JXBID_DONE",
           "KCH":"PHYS1002","KCM":"$JUDGE_COURSE_DONE","KSSJ":"2026-05-01 08:00:00",
           "PCDM":"PCDM-2026-01","PGLXDM":"01","PGNR":"$JUDGE_PGNR","WJDM":"$JUDGE_WJDM_DONE",
           "WJMC":"期末评教问卷","XNXQDM":"$JUDGE_TERM"}
        ]}}}
    """.trimIndent()

    /** 某问卷的题目（`datas.cxwjzb.rows`）：六道题把三种题型与三种匹配路径都摆出来。 */
    val judgeQuestionJson = """
        {"datas":{"cxwjzb":{"rows":[
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-01","ZBMC":"教学态度","TXDM":"01",
           "DADM":"$JUDGE_OPTION_DADM_BEST","SFBT":"1","FZ":"100"},
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-02","ZBMC":"意见和建议","TXDM":"02",
           "DADM":"","SFBT":"1"},
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-03","ZBMC":"总体评分","TXDM":"03",
           "DADM":"","SFBT":"1","FZ":"100"},
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-04","ZBMC":"补充评价（选填）","TXDM":"01",
           "DADM":"","SFBT":"0","FZ":"100"},
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-05","ZBMC":"教学效果","TXDM":"01",
           "DADM":"$JUDGE_OPTION_DADM_SECOND","SFBT":"1","FZ":"100"},
          {"WJDM":"$JUDGE_WJDM_FINAL","ZBDM":"ZB-06","ZBMC":"教学态度：","TXDM":"01",
           "DADM":"","SFBT":"1","FZ":"100"}
        ]}}}
    """.trimIndent()

    /** 某问卷的选项（`datas.cxxswjzbxq.rows`）：只给 `ZB-01` 三道，且按 `DAPX` 倒序给。 */
    val judgeOptionJson = """
        {"datas":{"cxxswjzbxq":{"rows":[
          {"ZBDM":"ZB-01","ZBMC":"教学态度","DADM":"$JUDGE_OPTION_DADM_THIRD","DAFXDM":"$JUDGE_OPTION_DA_THIRD",
           "DA":"WRONG","TXDM":"01","DAPX":"3","FZ":"100"},
          {"ZBDM":"ZB-01","ZBMC":"教学态度","DADM":"$JUDGE_OPTION_DADM_SECOND","DAFXDM":"$JUDGE_OPTION_DA_SECOND",
           "TXDM":"01","DAPX":"2","FZ":"100"},
          {"ZBDM":"ZB-01","ZBMC":"教学态度","DADM":"$JUDGE_OPTION_DADM_BEST","DAFXDM":"$JUDGE_OPTION_DA_BEST",
           "TXDM":"01","DAPX":"1","FZ":"100"}
        ]}}}
    """.trimIndent()

    /** 成绩报表第 2 页：换一个学期标题（夏季小学期 → 学期代码 `-3`），再给一条课程。 */
    val reportPage2 = """
        <html><body>
        <table><tbody>
          <tr><td>$SCORE_TERM_SUMMER_HEADING</td></tr>
          <tr><td>$SCORE_3_NAME</td><td>1.0</td><td>88</td></tr>
        </tbody></table>
        </body></html>
    """.trimIndent()

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == HOME_PATH -> handleHome(exchange, query)
            // okhttp 会不会把 `*` 转义成 %2A 取决于它的 path 编码集（当前版本是原样发）——
            // 两种都认，免得换版本就 404。
            path == APP_INDEX_PATH || path == APP_INDEX_PATH.replace("*", "%2A") -> {
                appIndexCalls.incrementAndGet()
                respondHtml(exchange, appIndexPage)
            }
            // 精确成绩（`cjcx` 应用，成绩屏的第二条上游）：预热那一枪与取数那一枪
            // （`*` 同上面那条：转义与否两种都认）。
            path == CJCX_INDEX_PATH || path == CJCX_INDEX_PATH.replace("*", "%2A") -> {
                cjcxIndexCalls.incrementAndGet()
                respondHtml(exchange, cjcxIndexPage)
            }
            path == CJCX_QUERY_PATH -> requirePost(exchange) { form ->
                lastCjcxForm = form
                // `CjcxApi` 筛的是「有效成绩」（SFYX=1），分页参数一个不能少 ——
                // 少了任何一个，拉回的就是别的东西（或者无限翻页）。
                if (!form.contains("querySetting=") || !form.contains("pageSize=") || !form.contains("pageNumber=")) {
                    badRequest(exchange, "xscjcx.do 少了 querySetting/pageSize/pageNumber，收到：$form")
                    return@requirePost
                }
                cjcxCalls.incrementAndGet()
                respondJson(exchange, cjcxJson)
            }
            path == CURRENT_TERM_PATH -> requirePost(exchange) {
                currentTermCalls.incrementAndGet()
                respondJson(exchange, currentTermJson)
            }
            path == TERM_LIST_PATH -> requirePost(exchange) {
                lastTermListForm = it
                if (!it.contains("*order=-DM")) {
                    badRequest(exchange, "xnxqcx.do 少了 *order=-DM，收到：$it")
                    return@requirePost
                }
                termListCalls.incrementAndGet()
                respondJson(exchange, termListJson)
            }
            path == DEPARTMENTS_PATH -> requirePost(exchange) {
                departmentCalls.incrementAndGet()
                respondJson(exchange, departmentsJson)
            }
            path == QUERY_PATH -> requirePost(exchange) {
                lastQueryForm = it
                if (!it.contains("querySetting=")) {
                    badRequest(exchange, "qxfbkccx.do 少了 querySetting，收到：$it")
                    return@requirePost
                }
                queryCalls.incrementAndGet()
                respondJson(exchange, queryJson)
            }
            path == SCHEDULE_PATH -> requirePost(exchange) { form ->
                lastScheduleForm = form
                if (!form.contains("XNXQDM=")) {
                    badRequest(exchange, "xskcb.do 少了 XNXQDM，收到：$form")
                    return@requirePost
                }
                scheduleCalls.incrementAndGet()
                respondJson(exchange, scheduleJson)
            }
            path == TERM_START_PATH -> requirePost(exchange) { form ->
                lastTermStartForm = form
                // 学期号切成两格发出去了没有：少了任何一格，问的就是别的东西
                if (!form.contains("XN=") || !form.contains("XQ=")) {
                    badRequest(exchange, "cxjcs.do 少了 XN/XQ，收到：$form")
                    return@requirePost
                }
                termStartCalls.incrementAndGet()
                respondJson(exchange, termStartJson)
            }
            // wdkb 自己的当前学期（与 kcbcx 那条不是同一个 URL，见常量的注释）。
            path == WDKB_CURRENT_TERM_PATH -> requirePost(exchange) {
                wdkbCurrentTermCalls.incrementAndGet()
                respondJson(exchange, wdkbCurrentTermJson)
            }
            path == CHANGES_PATH -> requirePost(exchange) { form ->
                lastChangesForm = form
                if (!form.contains("XNXQDM=")) {
                    badRequest(exchange, "xsdkkc.do 少了 XNXQDM，收到：$form")
                    return@requirePost
                }
                changesCalls.incrementAndGet()
                respondJson(exchange, changesJson)
            }
            path == EXAM_PATH -> requirePost(exchange) { form ->
                lastExamForm = form
                // 学期 + 排序两格都要在（少了排序上游会报错，而那是它在假成功时不会露的坑）。
                if (!form.contains("XNXQDM=") || !form.contains("*order=")) {
                    badRequest(exchange, "wdksap.do 少了 XNXQDM/*order，收到：$form")
                    return@requirePost
                }
                examCalls.incrementAndGet()
                respondJson(exchange, examJson)
            }
            path == JUDGE_INDEX_PATH -> {
                judgeIndexCalls.incrementAndGet()
                respondHtml(exchange, judgeIndexPage)
            }
            path == JUDGE_TERM_PATH -> requirePost(exchange) { form ->
                lastJudgeTermForm = form
                // `setting` 里的 `PJXNXQ` 是「当前学年学期」这个查法本身（少了它问的就是别的东西）
                if (!form.contains("PJXNXQ")) {
                    badRequest(exchange, "cxxtcs.do 的 setting 里少了 PJXNXQ，收到：$form")
                    return@requirePost
                }
                judgeTermCalls.incrementAndGet()
                respondJson(exchange, judgeTermJson)
            }
            path == JUDGE_LIST_PATH -> requirePost(exchange) { form -> handleJudgeList(exchange, form) }
            path == JUDGE_QUESTION_PATH -> requirePost(exchange) { form ->
                lastJudgeQuestionForm = form
                val fields = formFields(form)
                if (fields["WJDM"].isNullOrEmpty() || fields["JXBID"].isNullOrEmpty()) {
                    badRequest(exchange, "cxwjzb.do 少了 WJDM/JXBID，收到：$form")
                    return@requirePost
                }
                judgeQuestionCalls.incrementAndGet()
                respondJson(exchange, judgeQuestionJson)
            }
            path == JUDGE_OPTION_PATH -> requirePost(exchange) { form -> handleJudgeOptions(exchange, form) }

            path == CURRENT_USER_PATH -> {
                currentUserCalls.incrementAndGet()
                respondJson(exchange, currentUserJson)
            }
            path == CHANGE_ROLE_PATH -> requirePost(exchange) {
                changeRoleCalls.incrementAndGet()
                respondJson(exchange, """{"code":"0"}""")
            }
            path == EMPTY_ROOM_CAMPUS_CODE_PATH -> requirePost(exchange) {
                campusCodeCalls.incrementAndGet()
                respondJson(exchange, emptyRoomCampusCodesJson)
            }
            path == EMPTY_ROOM_BUILDING_CODE_PATH -> requirePost(exchange) {
                buildingCodeCalls.incrementAndGet()
                respondJson(exchange, emptyRoomBuildingCodesJson)
            }
            path == EMPTY_ROOM_QUERY_PATH -> requirePost(exchange) { form -> handleEmptyRoomQuery(exchange, form) }

            path == REPORT_PATH -> handleReport(exchange, query)
            else -> notFound(exchange)
        }
    }

    /** 登录入口页：带 ticket 或已经握着本站会话 cookie ⇒ 给首页。 */
    private fun handleHome(exchange: HttpExchange, query: String) {
        if (exchange.requestMethod == "POST") {
            handleSafetyVerifySubmit(exchange)
            return
        }
        if ("ticket=" in query) {
            ticketLandings.incrementAndGet()
            if (requireSafetyVerify.get()) {
                safetyVerifyLandings.incrementAndGet()
                respondHtml(exchange, safetyVerifyPage)
                return
            }
            exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=$JWXT_SESSION_VALUE; Path=/")
            respondHtml(exchange, homePage)
            return
        }
        if (JWXT_SESSION_VALUE in cookieHeader(exchange)) {
            respondHtml(exchange, homePage)
            return
        }
        casRedirects.incrementAndGet()
        redirect(exchange, "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=${encode(HOME_URL)}")
    }

    /** 二次认证页的隐藏表单回提：字段对不上就 400 + 原文，合格就把回跳走完（首页 + 本站会话 cookie）。 */
    private fun handleSafetyVerifySubmit(exchange: HttpExchange) {
        val form = readBody(exchange)
        lastSafetyVerifyForm = form
        val rejection = CasSafetyVerifyPage.rejection(form)
        if (rejection != null) {
            badRequest(exchange, rejection)
            return
        }
        safetyVerifySubmissions.incrementAndGet()
        // 认证通过 ⇒ 这个站点从此回到正常形态（后续探活看到的就是首页）
        requireSafetyVerify.set(false)
        exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=$JWXT_SESSION_VALUE; Path=/")
        respondHtml(exchange, homePage)
    }

    /** 帆软报表：成绩报表（初始页 `reportlet=` / 翻页 `op=page_content`，两条都是 GET）、教材报表（POST + 请求体里的 `reportlets=[…]`，见 [handleTextbookReport]），认不出就响亮地失败。 */
    private fun handleReport(exchange: HttpExchange, query: String) {
        // 教材报表那一枪**没有 query**：参数全在请求体里 ⇒ 先按方法分，再按 query 分。
        if (exchange.requestMethod == "POST") {
            handleTextbookReport(exchange)
            return
        }
        if ("op=page_content" in query) {
            val pn = param(query, "pn")?.toIntOrNull()
            val sessionId = param(query, "sessionID")
            lastReportSessionId = sessionId
            if (sessionId != FR_SESSION_ID) {
                badRequest(exchange, "翻页请求的 sessionID=$sessionId，夹具发的是 $FR_SESSION_ID")
                return
            }
            val page = when (pn) {
                1 -> reportPage1
                2 -> reportPage2
                else -> {
                    badRequest(exchange, "没有第 $pn 页（夹具只有 $REPORT_TOTAL_PAGES 页）")
                    return
                }
            }
            reportPageCalls.incrementAndGet()
            respondHtml(exchange, page)
            return
        }
        if ("reportlet=" in query) {
            val studentId = param(query, "xh")
            lastReportStudentId = studentId
            if (studentId.isNullOrBlank()) {
                badRequest(exchange, "报表初始页少了 xh=，收到：$query")
                return
            }
            if (!query.contains("bkdsglxjtu/XAJTDX_BDS_CJ.cpt")) {
                badRequest(exchange, "报表初始页的 reportlet 不是成绩报表，收到：$query")
                return
            }
            respondHtml(exchange, reportInitPage)
            return
        }
        badRequest(exchange, "认不出的报表请求：$query")
    }

    /** 教材报表的初始页：`reportlets` 里那三个键（`xh` / `xnxqdm` / `reportlet`）都要在，而且 reportlet 必须是教材那一张——少了任何一格，问的就是别的东西（与其余端点同一条规矩）。 */
    private fun handleTextbookReport(exchange: HttpExchange) {
        val body = readBody(exchange)
        val reportlets = formFields(body)[TEXTBOOK_REPORT_PARAM].orEmpty()
        lastTextbookReportlet = reportlets
        val missing = listOf("'xh'", "'xnxqdm'").filterNot { it in reportlets }
        if (missing.isNotEmpty()) {
            badRequest(exchange, "教材报表的 reportlets 少了 ${missing.joinToString("/")}，收到：$reportlets")
            return
        }
        if (TEXTBOOK_REPORTLET !in reportlets) {
            badRequest(exchange, "教材报表的 reportlet 不是 $TEXTBOOK_REPORTLET，收到：$reportlets")
            return
        }
        textbookReportCalls.incrementAndGet()
        respondHtml(exchange, textbookReportPage)
    }

    /** 评教问卷列表：按 `PGLXDM`（01 期末 / 05 过程）＋`SFPG`（1 已评）挑样本。 */
    private fun handleJudgeList(exchange: HttpExchange, form: String) {
        lastJudgeListForm = form
        judgeListForms += form
        val fields = formFields(form)
        val missing = listOf("PGLXDM", "SFPG", "SFKF", "SFFB", "XNXQDM").filter { fields[it] == null }
        if (missing.isNotEmpty()) {
            badRequest(exchange, "cxdwpj.do 少了 ${missing.joinToString("/")}，收到：$form")
            return
        }
        if (fields["SFKF"] != "1" || fields["SFFB"] != "1") {
            badRequest(exchange, "cxdwpj.do 的 SFKF/SFFB 该是 1，收到：$form")
            return
        }
        judgeListCalls.incrementAndGet()
        respondJson(exchange, judgeListJson(fields.getValue("PGLXDM"), finished = fields.getValue("SFPG") == "1"))
    }

    /** 认不出的 `PGLXDM`/`SFPG` 组合就是空表（真实站点也是给空 `rows`，不是报错）。 */
    private fun judgeListJson(type: String, finished: Boolean): String = when {
        type == "05" && finished -> judgeFinishedMidJson
        type == "05" -> judgeUnfinishedMidJson
        type == "01" && finished -> judgeFinishedFinalJson
        type == "01" -> judgeUnfinishedFinalJson
        else -> """{"datas":{"cxdwpj":{"rows":[]}}}"""
    }

    /** 评教问卷的选项：`querySetting` 里那六个定位条件（`BPR`/`CPR`/`JXBID`/`PGNR`/`WJDM`/`PCDM`）一个都不能少。 */
    private fun handleJudgeOptions(exchange: HttpExchange, form: String) {
        lastJudgeOptionForm = form
        val fields = formFields(form)
        val missing = (listOf("WJDM", "CPR", "PCDM", "SFPG", "BPR", "PGNR", "querySetting"))
            .filter { fields[it] == null }
        if (missing.isNotEmpty()) {
            badRequest(exchange, "cxxswjzbxq.do 少了 ${missing.joinToString("/")}，收到：$form")
            return
        }
        if (fields["CPR"] != LibraryFakeUpstream.USERNAME) {
            badRequest(exchange, "cxxswjzbxq.do 的 CPR 该是本夹具那个学号，收到：${fields["CPR"]}")
            return
        }
        val setting = fields.getValue("querySetting")
        val absent = JUDGE_OPTION_CONDITIONS.filter { "\"name\":\"$it\"" !in setting }
        if (absent.isNotEmpty()) {
            badRequest(exchange, "querySetting 里缺 ${absent.joinToString("/")}，收到：$setting")
            return
        }
        judgeOptionCalls.incrementAndGet()
        respondJson(exchange, judgeOptionJson)
    }

    // ── 响应的形体检查 ────────────────────────────────────────────

    /** POST 端点：必须真带表单（GET 打过来就 400，免得「方法漂了照样通过」）。 */
    private inline fun requirePost(exchange: HttpExchange, respond: (String) -> Unit) {
        val body = readBody(exchange)
        if (exchange.requestMethod != "POST") {
            badRequest(exchange, "expected POST, got ${exchange.requestMethod} ${exchange.requestURI}")
            return
        }
        respond(body)
    }

    // ── 空闲教室直查：三条端点 + 逐节查询 ────────────────────────────

    /** 当前身份：已是学生 ⇒ 不换角色（`ensureRoleStudent` 只认 `currentRole` 与 `roleName`）。 */
    private val currentUserJson = """
        {"code":200,"datas":{"userGroups":[{"roleName":"学生","roleId":"2","currentRole":true}]}}
    """.trimIndent()

    /** 校区代码表：最后一行刻意只给 `MC`/`DM`（`firstString` 的备用键名也得有人钉着）。 */
    private val emptyRoomCampusCodesJson = """
        {"datas":{"code":{"rows":[
          {"name":"兴庆校区","id":"1"},
          {"name":"雁塔校区","id":"2"},
          {"name":"创新港校区","id":"3"},
          {"MC":"苏州校区","DM":"5"}
        ]}}}
    """.trimIndent()

    private val emptyRoomBuildingCodesJson = """
        {"datas":{"code":{"rows":[
          {"name":"主楼A","id":"A"},
          {"name":"东1东","id":"E1E"},
          {"name":"中2","id":"Z2"}
        ]}}}
    """.trimIndent()

    /** 一行教室。 */
    private data class EmptyRoomRow(
        val name: String,
        val seats: Int,
        val typeDisplay: String?,
        val freePeriods: List<Int>,
        val hallucinated: Boolean = false,
    )

    private fun EmptyRoomRow.json(): String = buildString {
        append("""{"JASMC":"$name","JXLDM_DISPLAY":"主楼A",""")
        append(""""XXXQDM_DISPLAY":"$EMPTY_ROOM_CAMPUS_NAME",""")
        if (hallucinated) append(""""JASLXDM":null,""") else append(""""JASLXDM":"01",""")
        if (typeDisplay != null) append(""""JASLXDM_DISPLAY":"$typeDisplay",""")
        append(""""SKZWS":$seats,"KSZWS":$seats}""")
    }

    /** 主楼A 的五间：三间真教室 + 两条该被滤掉的噪声行。 */
    private val emptyRoomRows = listOf(
        EmptyRoomRow(EMPTY_ROOM_A101, EMPTY_ROOM_A101_SEATS, EMPTY_ROOM_TYPE, EMPTY_ROOM_A101_FREE_PERIODS),
        EmptyRoomRow(EMPTY_ROOM_A102, EMPTY_ROOM_A102_SEATS, EMPTY_ROOM_TYPE, EMPTY_ROOM_A102_FREE_PERIODS),
        EmptyRoomRow(EMPTY_ROOM_A103, EMPTY_ROOM_A103_SEATS, null, EMPTY_ROOM_A103_FREE_PERIODS),
        EmptyRoomRow(EMPTY_ROOM_HALLUCINATION, 20, EMPTY_ROOM_TYPE, EMPTY_ROOM_A102_FREE_PERIODS, hallucinated = true),
        EmptyRoomRow(EMPTY_ROOM_TEST_NAME, 20, EMPTY_ROOM_TYPE, EMPTY_ROOM_A102_FREE_PERIODS),
    )

    /** 逐节查询：`KSJC`/`JSJC` 都是 0 = 「无过滤，给全楼所有教室」（`queryDay` 拿它当教室名录），否则只给那一（几）节空闲的教室。 */
    private fun handleEmptyRoomQuery(exchange: HttpExchange, form: String) {
        lastEmptyRoomForm = form
        val fields = formFields(form)
        val start = fields["KSJC"]?.toIntOrNull()
        val end = fields["JSJC"]?.toIntOrNull()
        if (fields["XXXQDM"] != "1" || fields["JXLDM"] != "A") {
            badRequest(exchange, "夹具只扮兴庆校区的主楼A（XXXQDM=1/JXLDM=A），收到：$form")
            return
        }
        if (fields["KXRQ"].isNullOrBlank() || start == null || end == null) {
            badRequest(exchange, "cxkxjs.do 少了 KXRQ/KSJC/JSJC，收到：$form")
            return
        }
        if (fields["pageSize"] != "500" || fields["pageNumber"] != "1") {
            badRequest(exchange, "cxkxjs.do 的分页不是 pageSize=500&pageNumber=1，收到：$form")
            return
        }
        emptyRoomQueryCalls.incrementAndGet()
        respondJson(exchange, emptyRoomQueryJson(start, end))
    }

    private fun emptyRoomQueryJson(start: Int, end: Int): String {
        val noise = setOf(EMPTY_ROOM_HALLUCINATION, EMPTY_ROOM_TEST_NAME)
        val rows = emptyRoomRows.filter { row ->
            row.name in noise || (start == 0 && end == 0) || row.freePeriods.any { it in start..end }
        }
        return """{"code":200,"datas":{"cxkxjs":{"rows":[${rows.joinToString(",") { it.json() }}]}}}"""
    }

    // ── 响应小工具 ────────────────────────────────────────────────

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun notFound(exchange: HttpExchange) {
        val url = exchange.requestURI.toString()
        respond(exchange, 404, "text/plain; charset=utf-8", "no such jwxt path: $url".toByteArray())
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun readBody(exchange: HttpExchange): String =
        runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
    /** 表单原文 → 字段表（`JudgeApi` 发的是 `application/x-www-form-urlencoded`）。 */
    private fun formFields(form: String): Map<String, String> =
        form.split('&').filter { it.isNotEmpty() }.associate { part ->
            val raw = part.substringAfter('=', "")
            part.substringBefore('=') to runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    // 路由表与 URL 常量一处定义（免得两边各自漂移）。
    private val HOME_PATH = HOME_URL.removePrefix(ORIGIN)
    private val APP_INDEX_PATH = APP_INDEX_URL.removePrefix(ORIGIN)
    private val CURRENT_TERM_PATH = CURRENT_TERM_URL.removePrefix(ORIGIN)
    private val TERM_LIST_PATH = TERM_LIST_URL.removePrefix(ORIGIN)
    private val DEPARTMENTS_PATH = DEPARTMENTS_URL.removePrefix(ORIGIN)
    private val QUERY_PATH = QUERY_URL.removePrefix(ORIGIN)
    private val SCHEDULE_PATH = SCHEDULE_URL.removePrefix(ORIGIN)
    private val TERM_START_PATH = TERM_START_URL.removePrefix(ORIGIN)
    private val WDKB_CURRENT_TERM_PATH = WDKB_CURRENT_TERM_URL.removePrefix(ORIGIN)
    private val CHANGES_PATH = CHANGES_URL.removePrefix(ORIGIN)
    private val EXAM_PATH = EXAM_URL.removePrefix(ORIGIN)
    private val REPORT_PATH = REPORT_URL.removePrefix(ORIGIN)
    private val JUDGE_INDEX_PATH = JUDGE_INDEX_URL.removePrefix(ORIGIN)
    private val JUDGE_TERM_PATH = JUDGE_TERM_URL.removePrefix(ORIGIN)
    private val JUDGE_LIST_PATH = JUDGE_LIST_URL.removePrefix(ORIGIN)
    private val JUDGE_QUESTION_PATH = JUDGE_QUESTION_URL.removePrefix(ORIGIN)
    private val JUDGE_OPTION_PATH = JUDGE_OPTION_URL.removePrefix(ORIGIN)
    private val CURRENT_USER_PATH = CURRENT_USER_URL.removePrefix(ORIGIN)
    private val CHANGE_ROLE_PATH = CHANGE_ROLE_URL.removePrefix(ORIGIN)
    private val EMPTY_ROOM_CAMPUS_CODE_PATH = EMPTY_ROOM_CAMPUS_CODE_URL.removePrefix(ORIGIN)
    private val EMPTY_ROOM_BUILDING_CODE_PATH = EMPTY_ROOM_BUILDING_CODE_URL.removePrefix(ORIGIN)
    private val EMPTY_ROOM_QUERY_PATH = EMPTY_ROOM_QUERY_URL.removePrefix(ORIGIN)
}
