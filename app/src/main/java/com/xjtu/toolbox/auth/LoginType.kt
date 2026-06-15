package com.xjtu.toolbox.auth

/**
 * 业务登录类型。运行时会通过 [SessionManager] / [SiteSession] 转换为 siteKey。
 */
enum class LoginType(val label: String, val description: String) {
    ATTENDANCE("考勤系统", "本科生考勤查询"),
    POSTGRADUATE_ATTENDANCE("研究生考勤", "研究生考勤查询"),
    JWXT("教务系统", "日程/考试/评教"),
    JWAPP("移动教务", "成绩查询"),
    YWTB("一网通办", "个人信息/学期"),
    LIBRARY("图书馆", "座位预约"),
    CAMPUS_CARD("校园卡", "余额/账单查询"),
    DZPZ("电子打印证", "成绩单下载"),
    VENUE("体育场馆", "运动场地预订"),
    CLASS("课程平台", "课程回放 · TronClass"),
    LMS("思源学堂", "课程 · 作业 · 回放"),
    JIAOCAI("教材中心", "教材查询"),
    COUPON("加餐券", "电子券 · 余额与有效期"),
    SUPER_APP("移动交大", "校园移动门户"),
    FITNESS("体测查询", "体质健康测试成绩"),
    JIAOXIAOZHI("交晓智", "校园官方智能问答服务")
}
