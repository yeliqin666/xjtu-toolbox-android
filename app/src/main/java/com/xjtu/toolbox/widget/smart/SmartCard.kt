package com.xjtu.toolbox.widget.smart

import com.xjtu.toolbox.nav.AppRoute

// ==================== 卡片的内容模型（纯数据，不碰 Android） ====================
// 设计见 docs/smart-widget.md §2，并按真机反馈改过两处：
//   · 一屏可以有 **1 或 2 个块**（「小卡占一半、大卡占满」）；
//   · 快捷键挂在**块里面**，块整体一个点击目标、块内的图标各是另一个。

/**
 * 卡片上一条结论是**怎么来的**。
 *
 * 这一项决定「渲染规则」——不是在内容上加个后缀，而是决定这一行**能不能下断言**：
 * 学校写下的（[RECORDED]）怎么显示都行，按本机时钟推出来的（[INFERRED]）必须降级措辞并标时刻。
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

/** 时效等级，决定排位与染色。 */
enum class SmartRank { P0, P1, P2 }

/** 内容模块，用于「次要提示不许和块同源」的去重。 */
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

    /** 教务通知（只当次要提示）。 */
    NOTICE,
}

/**
 * 块里的快捷键种类。
 *
 * **这里刻意没有「去交」「考勤」「日程」「选座」这类键**：它们指向的就是块本身打开的那个页面 ——
 * 点块已经能到，再放一个图标就是重复入口。留下的都是「块到不了的地方」或「一个动作」。
 *
 * 「刷新」也不在这里：它在底部条最右，位置固定、每个场景都在。
 */
enum class SmartKeyKind {
    ATTENDANCE, QR, PAYMENT, CALENDAR,
    CHECKIN, INBOX, LEAVE, RETURN, CANCEL,
}

/** 块里的一个键：要么打开另一个页面，要么后台真做一件事。 */
sealed interface SmartKey {
    val kind: SmartKeyKind

    /** 无障碍朗读用（「中途离开」而不是「图片」）。 */
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
 * 一个块 = 一件事的若干行：
 *
 * ```
 * 场景（右边挂这个块自己的快捷键）   ← 这件事是什么情境
 * 标题                              ← 这件事是什么
 * 状态                              ← 结论（迟到 · 08:05 查过）
 * 细节（大块才给）                  ← 补充事实（地点、还剩多久）
 * ```
 *
 * **块整体是一个点击目标**（去这件事对应的页面），**块里的图标各是另一个点击目标**
 * （去块到不了的地方）。块内的图标与块的点击区用不同的 view id，互不覆盖。
 */
data class SmartBlock(
    val module: SmartModule,
    val label: String?,
    val title: String,
    /** 第一行细节（随情境变：地点 / 还剩多久 / 今日消费…）。 */
    val detail: String? = null,
    /** 第二行细节：一个块独占整张卡时用它把卡填满（时间区间、更新时刻、考场…）。 */
    val detail2: String? = null,
    val status: String? = null,
    val statusColor: Int? = null,
    val evidence: SmartEvidence = SmartEvidence.RECORDED,
    /** 时效说明（「刷卡 09:58」/「08:05 查过」/「上次查于 07:40」）。 */
    val meta: String? = null,
    val rank: SmartRank = SmartRank.P2,
    /** 排序用的锚点（距 00:00 的分钟）。 */
    val anchor: Int = 0,
    /** 点这一块去哪儿。 */
    val route: AppRoute,
    /** 这个块自己的快捷键（最多两个，刷新在底部条上）。**不许指向 [route] 自己。** */
    val keys: List<SmartKey> = emptyList(),
) {
    /**
     * 状态行实际画出来的那句话：**状态 + 时效**。
     *
     * 拼成一行是有意的：分开两行的话紧凑卡放不下，而时效又是这句话的一部分 ——
     * 「迟到」离开时刻就是一句过期的断言。
     */
    val statusLine: String?
        get() = when {
            status.isNullOrBlank() -> meta?.takeIf { it.isNotBlank() }
            meta.isNullOrBlank() -> status
            else -> "$status · $meta"
        }

    /** 这个块要几行：[details] 是细节行数（0/1/2）。 */
    fun heightDp(details: Int): Int {
        var h = SmartLayout.BLOCK_LEAD_DP + SmartLayout.BLOCK_TITLE_DP
        if (statusLine != null) h += SmartLayout.BLOCK_GAP_DP + SmartLayout.BLOCK_STATUS_DP
        if (details >= 1) h += SmartLayout.BLOCK_GAP_DP + SmartLayout.BLOCK_DETAIL_DP
        if (details >= 2) h += SmartLayout.BLOCK_GAP_DP + SmartLayout.BLOCK_DETAIL_DP
        return h
    }

    /** 这个块最多能写几行细节。 */
    val detailCount: Int
        get() = (if (!detail.isNullOrBlank()) 1 else 0) + (if (!detail2.isNullOrBlank()) 1 else 0)
}

/** 一条次要提示：写在卡片最底下那一条，与「刷新」并排。 */
data class SmartHint(val module: SmartModule, val text: String)

/**
 * 判出来的一屏：**1 或 2 个块** + 底部一条次要提示。
 *
 * - 有一件事远比其他都重要 ⇒ 一个块占满（块里给细节），底下一条提示；
 * - 有两件都算要紧 ⇒ 两个块各占一半（块里只留场景/标题/状态）。
 */
data class SmartCard(
    val blocks: List<SmartBlock>,
    val hints: List<SmartHint> = emptyList(),
)
