package com.xjtu.toolbox.widget.smart

import com.xjtu.toolbox.nav.AppRoute

// ==================== 卡片的内容模型（纯数据，不碰 Android） ====================
// 设计见 docs/smart-widget.md §2。这里只有形状与判据，渲染在 SmartWidgetUpdater 那一侧，
// 所以这一整套能在普通 JVM 单测里逐条断言，而不用起模拟器。

/**
 * 卡片上一条结论是**怎么来的**。
 *
 * 这一项决定「渲染规则」——不是在内容上加个后缀，而是决定这一行**能不能下断言**：
 * 学校写下的（[RECORDED]）怎么显示都行，按本机时钟推出来的（[INFERRED]）必须降级措辞并标时刻。
 * 旧代码只有一个 `statusIsGuess` 布尔，分不出「查证过」与「没查过」——那正是
 * 「明明刷了卡却显示迟到」的根源。
 */
enum class SmartEvidence {
    /** 学校写下的结论（正常 / 迟到 / 缺勤 / 请假）：不会自己变，不用标时刻。 */
    RECORDED,

    /** 本机亲眼见到的事实（刷卡流水）：标**观测时刻**。 */
    OBSERVED,

    /** 在临界点（打卡窗口关）查证过，结论成立：标**查证时刻**，可以直说「迟到」。 */
    VERIFIED,

    /** 按本机数据 + 时钟推出来的，可能被推翻：措辞降级为「可能…」，必标时刻。 */
    INFERRED,

    /** 查过，但已经过了 [SmartArbiter.VERIFY_WINDOW_MIN] 分钟：自动降级措辞。 */
    STALE,
}

/** 时效等级，决定排位与染色（§2.2）。 */
enum class SmartRank { P0, P1, P2 }

/**
 * 内容模块。主要信息与次要信息**不许同源**（§4.2），去重就靠它。
 */
enum class SmartModule {
    /** 这一堂课 + 考勤。 */
    ATTENDANCE,

    /** 下一节课 / 该出发了。 */
    NEXT_CLASS,

    /** 校园卡余额 / 付款码。 */
    CAMPUS_CARD,

    /** 待办（收纳里的 todo.*）。 */
    TODO,

    /** 图书馆座位。 */
    LIBRARY,

    /** 考试倒计时。 */
    EXAM,

    /** 放假 / 今天没课。 */
    HOLIDAY,

    /** 课表变动（停课 / 换教室）。 */
    SCHEDULE_DIFF,

    /** 教务通知（只当钩子，没有标题可说）。 */
    NOTICE,
}

/**
 * 底栏快捷键的种类。
 *
 * 只放种类不放图标资源：图标映射在渲染那一侧做，这样仲裁与布局这一整套纯逻辑
 * 不必依赖 `R`，单测不碰 Android 资源。
 *
 * 这里**没有「空教室」**：`EmptyRoomCache` 的键是「周次 + 星期 + 节次」组合，
 * 卡片冷启动时未必拼得出来，硬做只会显示一个过期的空教室数（M11 留到下一步）。
 */
enum class SmartKeyKind {
    ATTENDANCE, REFRESH, QR, SEAT, PAYMENT, CARD, CALENDAR, SCHOOL_CALENDAR,
    CHECKIN, INBOX, LEAVE, RETURN, CANCEL, TASK,
}

/** 底栏上的一个键（§5.2）：导航键打开某个页面，动作键后台真做一件事。 */
sealed interface SmartKey {
    val kind: SmartKeyKind

    /** 无障碍朗读与宽卡小字都用它（「中途离开」而不是「图片」）。 */
    val label: String
}

/** 导航键：带路由启动 App。 */
data class NavKey(
    override val kind: SmartKeyKind,
    override val label: String,
    val route: AppRoute,
) : SmartKey

/**
 * 动作键：**不打开 App** 就把一个有后果的事做完。
 *
 * 座位动作只能执行「预约当前真的有这个按钮」的那个动作（[action] 来自最近一次查到的
 * `actionUrls.keys`），所以它必须跟着落盘的那份事实走，不能写死。
 */
data class SeatKey(
    override val kind: SmartKeyKind,
    override val label: String,
    val action: String,
) : SmartKey

/**
 * 一个块 = 一件事的若干行（§2.3）：
 *
 * ```
 * 场景    ← 这件事是什么情境（上课中 / 下一节 / 午饭时间）
 * 标题    ← 这件事是什么
 * 细节    ← 补充事实（地点、还剩多久）
 * 状态    ← 结论（已刷卡 · 待结算）
 * ```
 *
 * **块是内容的单位，行是排版的单位**——场景和课名两件互不相干的事不许拼在一行。
 */
data class SmartBlock(
    val module: SmartModule,
    val label: String?,
    val title: String,
    val detail: String? = null,
    val status: String? = null,
    val statusColor: Int? = null,
    val evidence: SmartEvidence = SmartEvidence.RECORDED,
    /** 时效说明（「刷卡 09:58」/「08:05 查过」/「上次查于 07:40」）。 */
    val meta: String? = null,
    val rank: SmartRank = SmartRank.P2,
    /** 排序用的锚点（距 00:00 的分钟）：级内按它早的在前。 */
    val anchor: Int = 0,
    /** 点这一块去哪儿（内容块整块可点，§5.1）。 */
    val route: AppRoute,
) {
    /**
     * 状态行实际画出来的那句话：**状态 + 时效**。
     *
     * 拼成一行是有意的（§1.4 的样张就是「迟到 · 08:05 查过」）：分开两行的话，
     * 110dp 的紧凑卡放不下，而时效又是这句话的一部分——「迟到」离开时刻就是过期的断言。
     */
    val statusLine: String?
        get() = when {
            status.isNullOrBlank() -> meta?.takeIf { it.isNotBlank() }
            meta.isNullOrBlank() -> status
            else -> "$status · $meta"
        }
}

/**
 * 钩子：一两句写在空闲处的短句（§2.4 规则③）。
 *
 * 它**只写钩子**（地点 + 数字），不写句子；空间真没了，钩子先没——它最不重要。
 */
data class SmartHook(
    val module: SmartModule,
    val text: String,
    val rank: SmartRank,
    val anchor: Int,
)

/** 卡片判出来的一屏：一个块 + 若干钩子 + 底栏三个键。 */
data class SmartCard(
    val block: SmartBlock,
    val hooks: List<SmartHook> = emptyList(),
    val keys: List<SmartKey> = emptyList(),
)
