package com.xjtu.toolbox.home

import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AccountType

/**
 * 主页宫格和全局搜索共用的服务目录。
 *
 * 新功能只在这里加一条：首页会出格子，搜索也能搜到。
 * 别再在 [GlobalSearchIndex] 里手写第二份清单。
 * [audience] 非空时，只给对应本科/研究生身份看。
 */
enum class ServiceCategory(val title: String, val subtitle: String) {
    LIFE("校园生活", "支付、校历与场馆服务"),
    CLASS("上课", "课表、自习与课程内容"),
    STUDY("学业", "成绩、评教与学习资料"),
    TOOL("工具与助手", "智能助手与连接工具"),
}

data class AppService(
    val route: String,
    val title: String,
    val subtitle: String,
    val category: ServiceCategory,
    val aliases: List<String> = emptyList(),
    val showOnHome: Boolean = true,
    val audience: AccountType? = null,
)

object AppServices {
    val all: List<AppService> = listOf(
        AppService(Routes.SCHEDULE, "日程", "本学期课表与考试", ServiceCategory.CLASS, listOf("课表", "课程", "schedule", "今天", "明天")),
        AppService(Routes.EMPTY_ROOM, "空闲教室", "查找自习空教室", ServiceCategory.CLASS, listOf("空教室", "自习", "教室")),
        AppService(Routes.LMS, "思源", "课程作业与资料", ServiceCategory.CLASS, listOf("思源学堂", "lms", "作业", "课件")),
        AppService(Routes.CLASS_REPLAY, "课程回放", "课堂录像回放", ServiceCategory.CLASS, listOf("回放", "录像", "录播")),
        AppService(Routes.SCHOOL_COURSE, "课程查询", "全校开课查询", ServiceCategory.CLASS, listOf("开课", "选课", "查课")),
        AppService(Routes.ATTENDANCE, "考勤", "本科出勤与考勤流水", ServiceCategory.CLASS, listOf("考勤查询", "出勤", "迟到", "缺勤"), audience = AccountType.UNDERGRADUATE),
        AppService(Routes.POSTGRADUATE_ATTENDANCE, "研考勤", "研究生考勤", ServiceCategory.CLASS, listOf("研究生考勤", "研究生出勤", "yjskq"), audience = AccountType.POSTGRADUATE),
        AppService(Routes.ICLASSFACE, "快速考勤流水", "课堂人脸考勤记录", ServiceCategory.CLASS, listOf("人脸考勤", "刷脸", "iclassface"), audience = AccountType.UNDERGRADUATE),

        AppService(Routes.JWAPP_SCORE, "成绩", "本学期成绩与 GPA", ServiceCategory.STUDY, listOf("成绩查询", "分数", "gpa", "绩点")),
        AppService(Routes.JUDGE, "评教", "本科课程评教", ServiceCategory.STUDY, listOf("问卷", "打分", "本科评教")),
        AppService(Routes.JIAOCAI, "教材", "教材选用信息", ServiceCategory.STUDY, listOf("课本", "教材中心")),
        AppService(Routes.JIAOCAI1, "教材全文", "教材全文库", ServiceCategory.STUDY, listOf("全文", "电子书", "在线阅读")),
        AppService(Routes.LIBRARY, "图书馆", "借阅与座位", ServiceCategory.STUDY, listOf("图书", "借书", "座位", "自习室")),
        AppService(Routes.TRANSCRIPT, "成绩单", "电子成绩单", ServiceCategory.STUDY, listOf("成绩证明", "打印成绩")),
        AppService(Routes.NOTIFICATION, "通知公告", "教务与学院通知", ServiceCategory.STUDY, listOf("通知", "公告", "教务")),
        AppService(Routes.FACULTY, "教师主页", "按姓名、学院或研究方向找老师", ServiceCategory.STUDY, listOf("教师", "老师", "导师", "博导", "硕导", "研究方向", "teacher", "faculty")),

        AppService(Routes.CAMPUS_CARD, "校园卡", "余额与今日消费", ServiceCategory.LIFE, listOf("一卡通", "余额")),
        AppService(Routes.PAYMENT_CODE, "付款码", "出示校园付款码", ServiceCategory.LIFE, listOf("付款", "扫码")),
        AppService(Routes.COUPON, "加餐券", "食堂加餐券", ServiceCategory.LIFE, listOf("加餐", "餐券", "食堂")),
        AppService(Routes.SCHOOL_CALENDAR, "校历", "学期与考试安排", ServiceCategory.LIFE, listOf("学期", "周数")),
        AppService(Routes.VENUE, "场馆预订", "预约羽毛球、网球等", ServiceCategory.LIFE, listOf("场馆", "空闲场馆", "羽毛", "网球场")),
        AppService(Routes.FITNESS, "体测查询", "体测总分与各项目成绩", ServiceCategory.LIFE, listOf("体测", "体育", "体能", "fitness")),
        AppService(Routes.YELLOW_PAGE, "校园黄页", "校内电话与机构", ServiceCategory.LIFE, listOf("黄页", "电话", "分机")),

        AppService(Routes.WEBVPN_CONVERTER, "WebVPN", "校外访问转换", ServiceCategory.TOOL, listOf("vpn", "webvpn")),
        AppService(Routes.MOBILE_JIAODA, "移动交大", "官方超级 App 入口", ServiceCategory.TOOL, listOf("超级app", "交大app")),
        AppService(Routes.JIAOXIAOZHI, "交晓智", "学校智能问答", ServiceCategory.TOOL, listOf("晓智")),
        AppService(Routes.AGENT, "屁岱", "校园 AI 助手", ServiceCategory.TOOL, listOf("问屁岱", "ai", "助手")),

        AppService(Routes.SCORE_REPORT, "成绩报表", "历年成绩明细", ServiceCategory.STUDY, listOf("报表", "历年成绩"), showOnHome = false),
        AppService(Routes.DOWNLOAD_MANAGER, "下载管理", "课件与回放下载", ServiceCategory.TOOL, listOf("下载", "已下载"), showOnHome = false),
        AppService(Routes.SETTINGS, "设置", "外观、校园网与通知", ServiceCategory.TOOL, listOf("偏好", "主题"), showOnHome = false),
        AppService(Routes.ACCOUNTS, "账号管理", "切换或添加统一认证账号", ServiceCategory.TOOL, listOf("切换账号", "多账号"), showOnHome = false),
        AppService(Routes.FEEDBACK, "意见反馈", "GitHub 提 Issue 或博客留言", ServiceCategory.TOOL, listOf("反馈", "bug", "issue"), showOnHome = false),
    )

    val home: List<AppService> = all.filter { it.showOnHome }

    fun visibleFor(accountType: AccountType): List<AppService> =
        all.filter { it.audience == null || it.audience == accountType }

    fun homeFor(accountType: AccountType): List<AppService> =
        visibleFor(accountType).filter { it.showOnHome }

    fun byRoute(route: String): AppService? = all.firstOrNull { it.route == route }
}
