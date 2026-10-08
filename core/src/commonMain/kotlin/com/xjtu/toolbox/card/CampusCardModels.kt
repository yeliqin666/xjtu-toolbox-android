package com.xjtu.toolbox.card

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// 校园卡这条切片的**模型**（原来是 :app 的 `CampusCardApi.kt` 头部那一段 + `CampusCardCache.kt` 里
// 落盘用的两个模型）。搬到 `:core/commonMain` 的理由和别的切片一样：屏与 ViewModel 都在这里了，
// 它们的数据形状必须跟着来；两端（Android 的 ncard 抓取、Web 的 campus-api）各自填同一批字段。
//
// ⚠️ `CardInfo` / `Transaction` / `CampusCardSnapshot` 是 `@Serializable` 且**已经落过盘**
//（`CampusCardCache` 的 SharedPreferences 里有老数据），所以形状**一个字段都不能动、不能删**：
// 加默认值可以，改类型/改名会让老缓存整份读失败。

/** 校园卡基本信息 */
@kotlinx.serialization.Serializable
data class CardInfo(
    val account: String = "",
    val name: String = "",
    val studentNo: String = "",
    val balance: Double = 0.0,         // 电子钱包余额（元）
    val pendingAmount: Double = 0.0,   // 待入账金额
    val lostFlag: Boolean = false,     // 是否挂失
    val frozenFlag: Boolean = false,   // 是否冻结
    val expireDate: String = "",       // 过期日期
    val cardType: String = "",         // 卡类型名称
    val department: String = "",       // 学院（从 HTML 提取）
)

/**
 * 一笔流水的去重键：接口不给流水号，只能用这几个字段拼。不含 [Transaction.merchant]：
 * 它是解析出来的，解析规则一改，落盘缓存里的旧流水就和重新拉到的对不上了。
 */
// 跨模块可见性：原来是 `internal fun`。internal 按模块生效，屏与 ViewModel 搬进 :core 之后
// :app 那边的 `CampusCardCache` / AgentTool 就看不见它了 —— 跨模块共享必须 public
//（与 `CampusCardContract` 同一条理由）。
fun Transaction.uniqueKey(): String = "$time|$amount|$balance|$description"

/** 单笔交易记录 */
@kotlinx.serialization.Serializable
data class Transaction(
    val time: String = "",            // 交易时间
    val merchant: String = "",        // 商户名称
    val amount: Double = 0.0,         // 交易金额（负=支出，正=收入）
    val balance: Double = 0.0,        // 交易后余额
    val type: String = "",            // 交易类型
    val description: String = "",     // 详细描述
) {
    /**
     * 展示与统计一律用这个，不要直接读 [merchant]。
     *
     * 接口的 `toMerchant` 对扫码点餐、充值返回的是空字符串，解析时已经兜了一次；
     * 但**落盘缓存里存的是解析后的结果**，老缓存里那批空串不会因为解析改好就自动变好。
     * 在这里再兜一次，历史数据不用等刷新也能显示出名字。
     */
    val displayMerchant: String
        get() = merchant.ifBlank { merchantFromResume(description) }
}

/**
 * 商户名缺失时从 `resume` 里取一个能看的名字。
 *
 * resume 的形状是「商户-渠道」（`珍念水饺-电子账户消费`）或只有渠道
 * （`电子账户消费`、`充值-支付宝转账`）。取第一段即可；只剩渠道时去掉「消费」后缀，
 * 免得流水里一行写着「电子账户消费」还配一个「消费」类型。
 */
// 同 [uniqueKey]：:app 的 `CampusCardApi` 解析 ncard 流水时要用它，所以跨模块必须 public。
fun merchantFromResume(resume: String): String {
    val head = resume.substringBefore("-").trim()
    return head.removeSuffix("消费").trim().ifBlank { head.ifBlank { "未知商户" } }
}

/** 月度统计。[month] 是 kotlinx-datetime 的 `YearMonth`（搬进 :core 时由 `java.time.YearMonth` 换过来，
 *  用法逐处对齐：`YearMonth.from(d)` → `d.yearMonth`、`atDay(1)` → `firstDay`、`atEndOfMonth()` → `lastDay`、
 *  `plusMonths(n)` → `plus(n, DateTimeUnit.MONTH)`、`monthValue` → `month.number`）。 */
data class MonthlyStats(
    val month: YearMonth,
    val totalSpend: Double,      // 总支出（正数）
    val totalIncome: Double,     // 总收入
    val transactionCount: Int,   // 交易笔数
    val topMerchants: List<MerchantStat>,  // 商户消费排行
    val avgDailySpend: Double = 0.0,       // 按该月落在统计区间内的天数摊
    val peakDay: String = "",              // 消费最多的一天
    val peakDayAmount: Double = 0.0,       // 该天消费额
    val daysCovered: Int = 0               // 该月与查询区间重叠的天数
)

/** 商户消费统计 */
data class MerchantStat(
    val name: String,
    val totalAmount: Double,     // 总消费（正数）
    val count: Int               // 消费次数
)

// ==================== 辅助数据类 ====================

/** 用餐时段统计 */
data class MealTimeStats(
    val count: Int,
    val totalAmount: Double,
    val avgAmount: Double
)

/** 工作日/周末统计 */
data class DayTypeStats(
    val label: String,
    val count: Int,
    val totalAmount: Double,
    val avgPerTransaction: Double,
    val avgPerDay: Double
) {
    companion object {
        /** dateAmountPairs: (date, amount) 列表，日期用于准确统计天数 */
        fun from(label: String, dateAmountPairs: List<Pair<LocalDate, Double>>): DayTypeStats {
            val distinctDays = dateAmountPairs.map { it.first }.toSet().size.coerceAtLeast(1)
            val amounts = dateAmountPairs.map { it.second }
            return DayTypeStats(
                label = label,
                count = amounts.size,
                totalAmount = amounts.sum(),
                avgPerTransaction = if (amounts.isNotEmpty()) amounts.average() else 0.0,
                avgPerDay = amounts.sum() / distinctDays
            )
        }
    }
}

/** 今日消费汇总：总支出与早（5–10 点）中（11–14 点）晚（17–21 点）三餐，单位元。
 *  （搬进 :core 时从 `CampusCardCache.kt` 里挪出来 —— 它和落盘的 SharedPreferences 没关系，
 *  只是「从流水算今天花了多少」这一段纯计算；首页小组件、校园卡页、AgentTool 三处共用。） */
data class TodaySpendSummary(
    val total: Double,
    val breakfast: Double,
    val lunch: Double,
    val dinner: Double,
)

/** 没有卡信息的快照没意义：cardInfo 不给默认值，缺了整份读失败，当无缓存处理。
 *
 * 落盘那一侧（SharedPreferences 读写、按账号命名空间）留在 :app 的 `CampusCardCache`；
 * 这里只有形状 —— 屏通过 [CampusCardSource] 拿它，不碰任何平台的存储实现。 */
@kotlinx.serialization.Serializable
data class CampusCardSnapshot(
    val cardInfo: CardInfo,
    val transactions: List<Transaction> = emptyList(),
    val rangeStart: String = "",
    val rangeEnd: String = "",
    val savedAt: Long = 0L,
)
