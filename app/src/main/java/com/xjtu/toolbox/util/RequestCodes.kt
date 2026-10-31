package com.xjtu.toolbox.util

/**
 * 全 App 的 PendingIntent 请求码，号码只从这里取。
 *
 * 小组件和通知的 PendingIntent 几乎都指向 MainActivity、只差 extras，而系统按「请求码 + Intent（不含 extras）」
 * 认定是不是同一个：撞号时 FLAG_UPDATE_CURRENT 会把对方的 extras 换掉，点课表小组件却打开了某条通知。
 * 所以不能拿小组件 id、哈希这类不定的数去加，按行编号的段留足行数。
 */
object RequestCodes {
    // 1xxx 小组件打开 App
    /** 课表小组件所有打开 App 的入口（整卡和课程列表），extras 一样，共用一个。 */
    const val SCHEDULE_WIDGET = 1000
    const val CAMPUS_CARD_WIDGET = 1100
    const val NOTICE_WIDGET = 1200
    /** + 行号（< 10）。 */
    const val NOTICE_WIDGET_ROW = 1210
    const val TODO_WIDGET = 1300
    /** + 行号（< 10）。 */
    const val TODO_WIDGET_ROW = 1310

    // 2xxx 系统通知
    const val NOTICE_PUSH = 2000
    const val REMINDER_LIBRARY = 2101
    const val REMINDER_SCHEDULE = 2102
    const val REMINDER_EXAM = 2103
    const val REMINDER_LMS = 2104
    /** 所有自建待办的提醒都进收纳页，共用一个。 */
    const val REMINDER_TODO = 2105
    const val REMINDER_EXAM_CHANGE = 2106
    /** 屁岱简报与定时任务，都进收纳页。 */
    const val REMINDER_PIDAI = 2107
    /** 图书馆提醒上的「签到」按钮，发给 LibraryActionReceiver 的广播。 */
    const val REMINDER_LIBRARY_ACTION = 2108

    // 5xxx 发给小组件自己的广播
    /** + WidgetSize.ordinal。沿用旧值：AlarmManager 里排着的闹钟要靠同一个码才取消得掉。 */
    const val SCHEDULE_REFRESH_ALARM = 5000
    const val SCHEDULE_DAY_PREV = 5010
    const val SCHEDULE_DAY_NEXT = 5011
}
