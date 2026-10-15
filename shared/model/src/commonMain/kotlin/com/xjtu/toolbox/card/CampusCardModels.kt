package com.xjtu.toolbox.card

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// ⚠️ `CardInfo` / `Transaction` / `CampusCardSnapshot` 已经落过盘：只能加带默认值的字段，改名/改类型会让老缓存读失败。

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

/** 一笔流水的去重键：接口不给流水号，只能用这几个字段拼。 */
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
    /** 展示与统计一律用这个，不要直接读 [merchant]。 */
    val displayMerchant: String
        get() = merchant.ifBlank { merchantFromResume(description) }
}

/** 商户名缺失时从 `resume` 里取一个能看的名字。 */
// 同 [uniqueKey]：:app 的 `CampusCardApi` 解析 ncard 流水时要用它，所以跨模块必须 public。
fun merchantFromResume(resume: String): String {
    val head = resume.substringBefore("-").trim()
    return head.removeSuffix("消费").trim().ifBlank { head.ifBlank { "未知商户" } }
}

/** 月度统计。 */
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

/** 今日消费汇总：总支出与早（5–10 点）中（11–14 点）晚（17–21 点）三餐，单位元。 */
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
