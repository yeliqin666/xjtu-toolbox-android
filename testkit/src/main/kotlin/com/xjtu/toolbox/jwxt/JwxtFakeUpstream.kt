package com.xjtu.toolbox.jwxt

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.CasSafetyVerifyPage
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「教务系统」上游 —— 扮演 `jwxt.xjtu.edu.cn`（全校课表 + 成绩报表两条真数据路由的学校那一侧）。
 *
 * ## 它扮演到什么程度
 *
 * ```
 * GET  <HOME_URL>                      →（不带 ticket/jwxt 会话）302 到统一认证（service = HOME_URL）
 * POST http://login.xjtu.edu.cn/cas/login →（凭据表单，由 LibraryFakeUpstream 扮演 CAS）
 * GET  <HOME_URL>?ticket=ST-n          → 200 首页（会话 cookie 落下来）—— 登录链的最后一跳
 * GET  <KCB_CX> 的 `*default/index.do`  → 200（`SchoolCourseApi.ensureAppInitialized` 的预热）
 * POST <KCB_CX>/modules/bjkcb/dqxnxq.do   → 当前学期
 * POST <KCB_CX>/modules/bjkcb/xnxqcx.do   → 学期列表（请求里必须带 `*order=-DM`）
 * POST <DEPARTMENTS_URL>               → 开课单位（`/jwapp/code/…` 那张 id 表）
 * POST <KCB_CX>/modules/qxkcb/qxfbkccx.do → 全校课表一页（请求里必须带 `querySetting`）
 * POST <WDKB>/modules/xskcb/xskcb.do    → 学生课表整学期（请求里必须带 `XNXQDM`）—— `:app` 的 `ScheduleApi`
 * POST <WDKB>/modules/jshkcb/cxjcs.do   → 学期起点 `XQKSRQ`/`ZZC`（请求里必须带 `XN` 与 `XQ`）
 * GET  <REPORT_URL>?reportlet=…&xh=…    → 帆软报表初始页（`FR.SessionMgr.register`）
 * GET  <REPORT_URL>?…&op=page_content&sessionID=…&pn=N → 第 N 页表格
 * ```
 *
 * ## 评教（本科那一条 = 第 8 条真数据路由）也在这个 host 上
 *
 * 评教应用 `wspjyyapp` 与课表 `kcbcx` 在**同一个域名**下，所以它没有自己的夹具类：端点直接加在
 * 这个文件里，分派仍然只按 host。「一个 host 一个夹具」是这套夹具的规矩 —— 多开一个类只会让
 * `FakeCampusProxy` 去猜「同一个域名的两条路径该给谁」。它演的是（`JudgeApi` 真打的那些请求）：
 *
 * ```
 * GET  <JUDGE_INDEX_URL>    → 200（`JudgeApi.ensureAppInitialized` 的预热）
 * POST <JUDGE_TERM_URL>     → 当前学期 `datas.cxxtcs.rows[0].CSZA`（请求必须带 `PJXNXQ`）
 * POST <JUDGE_LIST_URL>     → 问卷列表 `datas.cxdwpj.rows`（按 `PGLXDM` + `SFPG` 挑样本）
 * POST <JUDGE_QUESTION_URL> → 某问卷的题目 `datas.cxwjzb.rows`
 * POST <JUDGE_OPTION_URL>   → 某问卷的选项 `datas.cxxswjzbxq.rows`（六个 querySetting 条件一个不能少）
 * ```
 * 形状逐字段对着 `JudgeApi` 的解析写：
 * `PCDM/PGLXDM/PGNR/WJDM/WJMC/XNXQDM`，其中 `DBRS` 走 `safeInt`）、题目的 7 个键、选项的 7 个键
 * （注意选项的答案值读的是 **`DAFXDM`**，所以夹具在行里塞了一个错的 `DA`：读错键就会现形）。
 * 刻意留空了几格：未评的第二行缺 `DBRS/JSSJ/KCH`（`safeInt`/`safeString` 的默认值）、
 * 已评那行缺 `BPJS`（空串）、主观题不缺 —— 缺字段给什么因此写在明面上。
 *
 * **提交 / 撤回那两条（`WspjwjController` 下的 `*.do`）没有夹具**：这一轮的验收只看列表与「填卷」的取数，
 * 任何一次误提交都会以 404 响亮地失败，而不是被一个假的成功页糊过去。
 * 真实站点那条登录链还夹着 `org.xjtu.edu.cn` 的 OAuth 三跳（`client_id=1675` →
 * `callbackAuthorize` → 教务）。这里**直接 302 到统一认证**：夹具要演的是「CAS 回跳之后
 * 客户端拿到的那个 URL 与页面」，OAuth 中间那几跳对被测代码没有可观察差别
 * （`JwxtLogin.postLogin` 只判「最终停在 jwxt 上」）。这条省略写在这里，免得被读成「真站点就两跳」。
 *
 * ## 空闲教室「直查教务」那一档（第 10 条真数据路由的第三档）也在这个 host 上
 *
 * 它跑在 `kxjas` 应用里，与课表/评教同一个域名 ⇒ 端点直接加在这个文件里（`EmptyRoomApi` 的
 * `EmptyRoomDirectQuery` 真打的那些请求）：
 *
 * ```
 * GET  <CURRENT_USER_URL>              → `datas.userGroups`（已经是学生 ⇒ 不换角色）
 * POST <EMPTY_ROOM_CAMPUS_CODE_URL>    → 校区名→代码（`datas.code.rows`，最后一行只给 MC/DM）
 * POST <EMPTY_ROOM_BUILDING_CODE_URL>  → 教学楼名→代码
 * POST <EMPTY_ROOM_QUERY_URL>          → 某楼某天某节空闲的教室（KSJC/JSJC 都为 0 = 全楼名录）
 * ```
 *
 * 逐节的 11 次查询由夹具按 `KSJC..JSJC` 现算：主楼A-101 第 6-11 节空闲、主楼A-102 全天空闲、
 * 主楼A-103 全天占用。另有两行**噪声**（`JASLXDM` 为 null 的「幻觉教室」与名字含「测试专用」的）
 * 每枪都回、且必须被生产代码滤掉 —— 少了它们，「过滤」那两条分支就没人钉了。
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是所有站点共用的一台 CAS，`FakeCampusProxy` 把它分派给
 * [LibraryFakeUpstream]（它完整扮演了登录页 / 表单 POST / 签 ticket / TGC，外加短信二次验证
 * 的「取手机号」与「校验验证码」两枪）。这个夹具只负责 `jwxt.xjtu.edu.cn` 那一半：**CAS 回跳之后**的事。
 *
 * ## 短信二次认证（CAS Safety Verify）那一幕也在这一半
 *
 * 真站点上它是 **jwxt 独有**的：`client_id=1675` 这个高敏感 OAuth 客户端即使在 TGC 已下发之后
 * 也会被 CAS 拦到「Safety Verify」页（`JwxtLogin.postLogin` 的注释与 `CasLoginPages.isSafetyVerifyPage`
 * 都是为它写的）。而**那一页要落在 jwxt 域名下才拦得到这条链** ⇒ 由这个夹具发它
 *（页面原文在 [CasSafetyVerifyPage]，只有那一份）。整条剧本是：
 *
 * ```
 * 开 requireSafetyVerify
 * GET  <HOME_URL>?ticket=ST-n  → 200 二次认证页（**不是**首页）   ← XJTULogin 认出来 ⇒ REQUIRE_MFA
 * GET  <CAS>/cas/sec/initByType/securephone?state=<secState> → gid + 屏蔽手机号
 * POST <CAS>/attest/api/guard/securephone/valid  {gid,code}    → code==123456 才过
 * POST <HOME_URL>?ticket=ST-n  （secState/execution/_eventId/submit） → 200 首页 + 会话 cookie
 * ```
 * 默认**关着**（既有用例要的是「一步登进去」）；`:server` 的 MFA 桥测试开它。
 *
 * ## 契约：逐字段对着 `:data` 里的解析函数写
 *
 * - 学期 / 开课单位 / 课表行的键，就是 `SchoolCourseApi` 真正读的那些
 *   （`datas.xnxqcx.rows[].DM/MC`、`datas.code.rows[].id/name`、`datas.qxfbkccx.rows[]` 的
 *   `KCH/KCM/KXH/SKJS/KKDWDM_DISPLAY/XF/XS/SKXS/SYXS/SJXS/XKZRS/KRL/SKBJ/YPSJDD/XXXQDM_DISPLAY/`
 *   `SFXGXK/XGXKLBDM_DISPLAY/KNZXS/NSXKRS/NVSXKRS/JXBID/XNXQDM`）；
 *   **刻意留空几个键**（第二、三行）—— 缺字段的默认值（`safeInt/safeDouble` → 0）就是这么钉住的。
 * - 学期列表的**行序就是上游的序**（真实站点按 `*order=-DM` 排好才回）：解析只做映射不排序，
 *   所以夹具按 DM 倒序给，并**校验请求里真带了 `*order=-DM`** —— 少一个断言，「上游排序」这条
 *   口径漂了都没人知道。
 * - 开课单位**故意乱序给**：`getDepartments` 自己按名字排（断言里就是排好的序）。
 * - 成绩报表的页数、会话 id、学期标题与课程行都照 `ScoreReportApi` 的判据写：
 *   `FR.SessionMgr.register('<id>'`、`FR._p.reportTotalPage = <n>`、单列表格行 = 学期标题、
 *   三列行 = 课程（课程名 / 学分 / 成绩），表头与学分行不是数字的行会被跳过。
 *
 * ## 认不出的路径要响亮
 *
 * 路径不认识 → 404，**body 里带着完整 URL**；认识的路径但形体不对（该 POST 的用了 GET、
 * 请求里少了必备字段）→ 400，body 里带着实际收到的样子。URL 一漂就该一眼看出来。
 */
class JwxtFakeUpstream {

    companion object {
        const val HOST = "jwxt.xjtu.edu.cn"
        const val ORIGIN = "https://$HOST"

        /** 登录入口页 —— 与 `XJTULogin.JWXT_URL` 必须逐字相同（有断言钉着）。 */
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

        const val DEPT_PHYSICS = "物理学院"
        const val DEPT_MATH = "数学与统计学院"
        const val DEPT_MECH = "机械工程学院"

        // ── 课表样本 ──

        const val COURSE_1_NAME = "高等数学（上）"
        const val COURSE_2_NAME = "大学物理"
        const val COURSE_3_NAME = "艺术导论"
        const val TOTAL_SIZE = 3

        // ── 学生课表样本（`wdkb`：`xskcb.do` 的行 + `cxjcs.do` 的学期起点）──
        //
        // 行的键名就是上游列名；`SKXQ`/`KSJC`/`JSJC` 三格**故意给 JSON 数字** ——
        // 解析按 `safeString` 读成文本（「数字也按文本投影」那条口径就是这么钉住的）。

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

        /** 第三行只有 `KCM`：缺的键读成空串（「缺键 = 空串」那条口径由它钉住）。 */
        const val SCHEDULE_3_COURSE = "艺术导论"

        /** 学期起点：`XQKSRQ` 给到秒（解析只取日期那一段），`ZZC` = 总周数（含考试周）。 */
        const val TERM_START_DATE = "2026-09-14"
        const val TERM_TOTAL_WEEKS = 18

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

        // ── 评教（`wspjyyapp` 应用）：本科评教的全部端点（第 8 条真数据路由）──

        /** 评教应用前缀（`JudgeApi` 里那些 URL 的共同前半截）。 */
        const val WSPJ_APP = "$ORIGIN/jwapp/sys/wspjyyapp"

        const val JUDGE_INDEX_URL = "$WSPJ_APP/modules/xspj/index.do"
        const val JUDGE_TERM_URL = "$WSPJ_APP/modules/xspj/cxxtcs.do"
        const val JUDGE_LIST_URL = "$WSPJ_APP/modules/xspj/cxdwpj.do"
        const val JUDGE_QUESTION_URL = "$WSPJ_APP/modules/wj/cxwjzb.do"
        const val JUDGE_OPTION_URL = "$WSPJ_APP/modules/wj/cxxswjzbxq.do"

        /**
         * 评教的当前学期（`CSZA`）。与课表那条**是同一个学期**：同一个教务系统不该就
         * 「现在是什么学期」给出两个答案，所以它就是 [TERM_NEW]。
         */
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

        /**
         * 选项的两个编号：`DADM` 是**答案代码**（`QuestionnaireOptionData.DADM`），`DA` 是
         * **要填回问卷的选项编号**（服务端字段名是 `DAFXDM`）。两个都得对上：`DADM` 是选值
         * 匹配用的，`DA` 才是提交时写进卷子的那个值。
         */
        const val JUDGE_OPTION_DADM_BEST = "DA-ZB01-100"
        const val JUDGE_OPTION_DA_BEST = "100"
        const val JUDGE_OPTION_DADM_SECOND = "DA-ZB01-80"
        const val JUDGE_OPTION_DA_SECOND = "80"
        const val JUDGE_OPTION_DADM_THIRD = "DA-ZB01-60"
        const val JUDGE_OPTION_DA_THIRD = "60"

        // ── 空闲教室「直查教务」那一档（第 10 条真数据路由的第三档）──
        //
        // 这一档在 `kxjas` 应用里（`EmptyRoomApi` 的 `EmptyRoomDirectQuery`）。四条 URL 都是它真打的；
        // 那两个 UUID 是上游 `jwxt/empty_room.py` 里写死的编号，改了就等于换了接口。

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

    /**
     * 二次认证剧本的开关：开上之后，**带 ticket 的回跳给的不是首页而是 [safetyVerifyPage]**。
     *
     * 什么时候开：测「登录链上撞到短信二验」时（`:server` 的 MFA 桥）。默认关 —— 其余用例要的是
     * 「一步登进去」。
     */
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

    /** 成绩报表的翻页请求打过几次（`totalPages=2` ⇒ 一次 `pn=1` + 一次 `pn=2`）。 */
    val reportPageCalls = AtomicInteger()

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

    /** 最近一次报表初始页请求里带的学号（`xh=`）。 */
    @Volatile
    var lastReportStudentId: String? = null
        private set

    /** 最近一次翻页请求里带的会话 id（`sessionID=`），应当等于本夹具发的 [FR_SESSION_ID]。 */
    @Volatile
    var lastReportSessionId: String? = null
        private set

    // ── 评教（第 8 条真数据路由）：动作打进来时计数/记录 ──

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

    // ── 空闲教室直查（第 10 条真数据路由的第三档）：动作打进来时计数/记录 ──

    /** `currentUser.do` 打过几次（`ensureRoleStudent` 的第一步；已经是学生就不换角色）。 */
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

    /**
     * CAS 的「Safety Verify」二次认证页 —— 只有 [requireSafetyVerify] 打开时才会被给出去。
     *
     * ⚠️ 页面原文在 [CasSafetyVerifyPage]（**只有那一份**：它不属于任何一个业务站，真站点上是 CAS
     * 渲染的）。这里只负责「在回跳那一步发给客户端」—— 与图书馆那条链（CAS 自己在凭据 POST 的落点
     * 发它）的差别，见那个对象的 KDoc。
     */
    val safetyVerifyPage: String get() = CasSafetyVerifyPage.HTML

    /** `kcbcx` 应用的首页（预热用，客户端只 close 不解析）。 */
    val appIndexPage = """
        <html><head><title>课程表查询</title></head><body>
          <div id="app">全校课程查询</div>
        </body></html>
    """.trimIndent()

    /** 当前学期：`datas.dqxnxq.rows[0].DM`。 */
    val currentTermJson = """
        {"datas":{"dqxnxq":{"rows":[{"DM":"$TERM_NEW","MC":"$TERM_NEW_NAME"}]}}}
    """.trimIndent()

    /**
     * 学生课表的整学期行（`datas.xskcb.rows`）：三行。
     *
     * 第一行整学期、第二行单周（`ZCMC` 的 `(单)` 写法）、第三行**只有 `KCM`** —— 缺的键读成空串。
     * 三格里 `SKXQ`/`KSJC`/`JSJC` 给的是 **JSON 数字**（真站点就是这么给的），解析成文本是它的口径。
     */
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

    /**
     * 全校课表一页。
     *
     * 第二行缺 `XKZRS`/`SJXS`/`NSXKRS`/`NVSXKRS`、第三行缺 `YPSJDD`/`KNZXS`
     * —— `safeInt/safeDouble` 的默认值（0）与「空串」就是靠这几格钉住的。
     */
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

    /**
     * 成绩报表的初始页：只给会话 id（`extractFrSessionId` 的第一条判据）。
     *
     * ⚠️ **不能**带 `FR._p.reportTotalPage`：初始页没有它，页数只从第一页表格里读
     * （少了这条，`extractTotalPages` 的兜底 1 就永远测不到）。
     */
    val reportInitPage = """
        <html><head><title>学生成绩报表</title>
        <script>FR.SessionMgr.register('$FR_SESSION_ID', {bookID:'bkdsglxjtu/XAJTDX_BDS_CJ.cpt'});</script>
        </head><body><div id="fr-container"></div></body></html>
    """.trimIndent()

    /**
     * 成绩报表第 1 页。
     *
     * 四类行**各来一条**，把 `parseCoursesFromHtml` 的判据全钉住：
     *  1. 两列行（`tds.size < 3`）→ 跳过；
     *  2. 三列但在学期标题**之前** → 跳过（`currentTerm == null`）；
     *  3. 表头（课程名是「课程」）→ 跳过；
     *  4. 学分行不是数字（`—`）→ 跳过。
     * 真正成行的两条：数字成绩（95 → 4.3）与等级制（优秀 → 不参与 GPA ⇒ `gpa == null`）。
     */
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

    /**
     * 评教的当前学期：`datas.cxxtcs.rows[0].CSZA`。
     *
     * 夹具行里只给 `CSZA`（`JudgeApi` 只读这个键）—— 服务器还会给一堆别的列，这里刻意不给：
     * 「服务端多给了几个键也不会读错」这件事不该靠夹具替它兜着。
     */
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

    /**
     * 未评 · 期末评教（`PGLXDM=01`、`SFPG=0`）：两行。
     *
     * 第二行**刻意缺 `DBRS`/`JSSJ`/`KCH`** —— `safeInt` 的 0 与 `safeString` 的空串就是这么钉住的。
     */
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

    /**
     * 某问卷的题目（`datas.cxwjzb.rows`）：六道题把三种题型与三种匹配路径都摆出来。
     *
     * | 题 | 题型 | 这道题钉的是 |
     * |---|---|---|
     * | `ZB-01` | 客观 | 正常路径：选项表里有同名 `ZBDM` |
     * | `ZB-02` | 主观 | **缺 `FZ`** ⇒ `safeStringOrNull()` 给 null |
     * | `ZB-03` | 分值 | `getMaxScore()` 读的就是 `FZ` |
     * | `ZB-04` | 客观 | `SFBT=0` 且选项表里没有 ⇒ 非必填题允许跳过 |
     * | `ZB-05` | 客观 | `ZBDM` 不在选项表，靠 `DADM` 兜底 |
     * | `ZB-06` | 客观 | 靠**题名**兜底（`ZBMC` 比 `ZB-01` 多一个冒号，归一化后就相等） |
     */
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

    /**
     * 某问卷的选项（`datas.cxxswjzbxq.rows`）：只给 `ZB-01` 三道，且**按 `DAPX` 倒序给**。
     *
     * 两件事因此被钉住：选值是**按 `DAPX` 精确匹配**（不是「取第一行」），以及答案值读的是
     * `DAFXDM` —— 夹具特意在行里塞了一个错的 `DA`（`所写非所读`）。
     */
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

    /**
     * 登录入口页：带 ticket 或已经握着本站会话 cookie ⇒ 给首页；否则 302 到统一认证
     * （`service` 就是本页地址，真站点也是这么把票据交回来的）。
     *
     * 二次认证剧本（[requireSafetyVerify] 开着）多两条分支：
     * - **带 ticket 的回跳不给首页，给 [safetyVerifyPage]** —— 真站点正是这样：TGC 已下发、
     *   CAS 仍把 jwxt（`client_id=1675`）拦到二次认证（`XJTULogin.open` 的 `initialSafetyVerify`
     *   分支认的就是这一页）；
     * - **`POST` 回本页** = 二次认证页的隐藏表单回提（`MFAContext.verifyCode` 的最后一步），
     *   校验通过就回到正常形态：首页 + 本站会话 cookie。
     */
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

    /**
     * 二次认证页的隐藏表单回提：字段对不上就 400 + 原文，合格就把回跳走完（首页 + 本站会话 cookie）。
     *
     * 字段契约只写在 [CasSafetyVerifyPage.rejection] 一处 —— 图书馆那条链（CAS 自己发的页）
     * 走的是同一个校验，那是「页面原文只有一份」的直接后果。
     */
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

    /** 帆软报表：初始页（`reportlet=`）与翻页（`op=page_content`）两条，认不出就响亮地失败。 */
    private fun handleReport(exchange: HttpExchange, query: String) {
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

    /**
     * 评教问卷列表：按 `PGLXDM`（01 期末 / 05 过程）＋`SFPG`（1 已评）挑样本。
     *
     * 五个必备字段缺一个、或 `SFKF`/`SFFB` 不是 1（「只给开放中且已发布」的问卷），回 400——
     * 与真站点的请求形体一致，顺带把「少发一个条件也照样成」这类退化搭住。
     */
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

    /**
     * 评教问卷的选项：`querySetting` 里那六个定位条件（`BPR`/`CPR`/`JXBID`/`PGNR`/`WJDM`/`PCDM`）
     * 一个都不能少 —— 少一个就可能把别人的卷子领回来。
     */
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

    /**
     * 一行教室。`typeDisplay = null` = **不给** `JASLXDM_DISPLAY` 这个键（钉 `DirectRoomRow.type`
     * 的 null）；[hallucinated] = `JASLXDM` 给 JSON null —— 生产代码按它过滤掉「接口幻觉教室」。
     */
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

    /**
     * 逐节查询：`KSJC`/`JSJC` 都是 0 = 「无过滤，给全楼所有教室」（`queryDay` 拿它当教室名录），
     * 否则只给**那一（几）节空闲**的教室。两条噪声行每次都回，好让「过滤掉它们」的断言有意义。
     */
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
