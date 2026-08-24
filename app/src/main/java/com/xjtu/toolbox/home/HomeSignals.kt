package com.xjtu.toolbox.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 首页相关的跨层信号。
 *
 * 存在的理由：主动拉取（[HomeStatsRefresher]）和屁岱的提醒评估原来都挂在 `HomeTab` 上，
 * 而 tab 是**懒加载**的——只有被选中过的 tab 才会进入组合。默认启动 Tab 允许设成
 * 日程/学辅/我的，于是「用户不点一次首页，主动拉取和提醒一次都不会跑」。
 * 现在两个循环都提到了 MainScreen 层，这里只留两条需要跨层传递的信号。
 */
object HomeSignals {

    /**
     * 下一节课。
     *
     * 仍然由 `HomeTab` 算好写进来，没有跟着一起上提：那份计算要读课表缓存、解析优化后的
     * 课程表、再合并 Room 里的自定义课程，上百行且只有首页 Hero 用得到，
     * 为了提醒在 MainScreen 再跑一遍不划算。
     *
     * 代价是**没进过首页时它是 null**，此时与"几分钟后上课"相关的那条提醒不触发；
     * 余额、成绩、通知、图书馆四条不依赖它，照常工作。
     */
    var scheduleReminder by mutableStateOf<ScheduleFocus?>(null)

    /**
     * 图书馆座位当前有没有"必须马上做"的操作，有的话是哪一个（入馆签到 / 中途返回）。
     *
     * 单独一条信号而不是让提醒规则去解析 [HomeStat] 里的状态文本：
     * 状态是学校页面的原文，措辞会变；这里存的是 `LibraryApi.classifyActionLabel`
     * 归一化后的 label，只有固定几个值。见 `LibraryApi.URGENT_ACTIONS`。
     */
    var libraryUrgentAction by mutableStateOf<String?>(null)

    /** [HomeStatsRefresher.refreshDue] 跑完一轮就自增，首页据此重新读缓存刷新展示。 */
    var statsVersion by mutableIntStateOf(0)
        private set

    fun bumpStatsVersion() {
        statsVersion++
    }

    /** 提醒只需要课程名和开始时间，不必把首页那个完整模型搬过来。 */
    data class ScheduleFocus(
        val name: String,
        val startAt: java.time.LocalDateTime,
    )
}
