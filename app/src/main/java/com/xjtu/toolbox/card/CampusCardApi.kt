package com.xjtu.toolbox.card

import android.util.Log
import com.xjtu.toolbox.auth.CampusCardLogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.Request
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private const val TAG = "CampusCardApi"

// ==================== 数据类 ====================

/** 校园卡基本信息 */
data class CardInfo(
    val account: String,
    val name: String,
    val studentNo: String,
    val balance: Double,         // 电子钱包余额（元）
    val pendingAmount: Double,   // 待入账金额
    val lostFlag: Boolean,       // 是否挂失
    val frozenFlag: Boolean,     // 是否冻结
    val expireDate: String,      // 过期日期
    val cardType: String,        // 卡类型名称
    val department: String = ""  // 学院（从 HTML 提取）
)

/** 单笔交易记录 */
data class Transaction(
    val time: String,            // 交易时间
    val merchant: String,        // 商户名称
    val amount: Double,          // 交易金额（负=支出，正=收入）
    val balance: Double,         // 交易后余额
    val type: String,            // 交易类型
    val description: String      // 详细描述
)

/** 月度统计 */
data class MonthlyStats(
    val month: YearMonth,
    val totalSpend: Double,      // 总支出（正数）
    val totalIncome: Double,     // 总收入
    val transactionCount: Int,   // 交易笔数
    val topMerchants: List<MerchantStat>,  // 商户消费排行
    val avgDailySpend: Double = 0.0,       // 日均消费
    val peakDay: String = "",              // 消费最多的一天
    val peakDayAmount: Double = 0.0        // 该天消费额
)

/** 商户消费统计 */
data class MerchantStat(
    val name: String,
    val totalAmount: Double,     // 总消费（正数）
    val count: Int               // 消费次数
)

// ==================== API 类 ====================

class CampusCardApi(private val login: CampusCardLogin) {

    private val baseUrl = CampusCardLogin.BASE_URL
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 获取校园卡信息（余额、状态等）
     */
    fun getCardInfo(): CardInfo {
        return getCardInfoInternal(allowRetry = true)
    }

    private fun getCardInfoInternal(allowRetry: Boolean): CardInfo {
        val url = "$baseUrl/berserker-app/ykt/tsm/queryCard?synAccessSource=h5"
        val response = login.client.newCall(login.makeAuthRequest(url)).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("空响应")

        Log.d(TAG, "getCardInfo: code=${response.code}, bodyLen=${responseBody.length}")

        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("校园卡返回了非JSON数据: ${responseBody.take(100)}")
        }

        val code = root.get("code")?.asInt ?: 0
        if (code == 401) {
            if (allowRetry && login.reAuthenticate()) {
                Log.d(TAG, "getCardInfo: 401, reAuthenticate success, retrying...")
                return getCardInfoInternal(allowRetry = false)
            }
            throw RuntimeException("校园卡会话已过期，请返回重新登录")
        }
        if (code != 200) {
            throw RuntimeException("获取卡信息失败: ${root.get("message")?.asString ?: "未知错误"}")
        }

        val data = root.getAsJsonObject("data")
            ?: throw RuntimeException("校园卡响应格式异常")
        val cardArr = data.getAsJsonArray("card")
        if (cardArr == null || cardArr.size() == 0) throw RuntimeException("未找到校园卡信息")

        val card = cardArr.get(0).asJsonObject
        val elecAmt = card.get("elec_accamt")?.asLong ?: 0L
        val unsettled = card.get("unsettle_amount")?.asLong ?: 0L

        return CardInfo(
            account = login.cardAccount ?: "",
            name = login.userName,
            studentNo = login.studentNo,
            balance = elecAmt / 100.0,
            pendingAmount = unsettled / 100.0,
            lostFlag = card.get("barflag")?.asInt == 1,
            frozenFlag = card.get("freezeflag")?.asInt == 1,
            expireDate = formatExpDate(card.get("expdate")?.asString ?: ""),
            cardType = card.get("cardname")?.asString?.trim() ?: ""
        )
    }

    /**
     * 获取交易流水（分页）
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param page 页码（从1开始）
     * @param pageSize 每页条数
     * @return Pair<总条数, 当页交易列表>
     */
    fun getTransactions(
        startDate: LocalDate = LocalDate.now().minusMonths(3),
        endDate: LocalDate = LocalDate.now(),
        page: Int = 1,
        pageSize: Int = 30
    ): Pair<Int, List<Transaction>> {
        val url = "$baseUrl/berserker-search/search/personal/turnover" +
            "?size=$pageSize&current=$page" +
            "&timeFrom=${startDate.format(dateFormat)}&timeTo=${endDate.format(dateFormat)}" +
            "&synAccessSource=h5"

        val response = login.client.newCall(login.makeAuthRequest(url)).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("空响应")

        Log.d(TAG, "getTransactions: page=$page, code=${response.code}, bodyLen=${responseBody.length}")

        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("交易记录返回了非JSON数据: ${responseBody.take(100)}")
        }

        val code = root.get("code")?.asInt ?: 0
        if (code == 401) throw RuntimeException("校园卡会话已过期，请返回重新登录")
        if (code != 200) throw RuntimeException("获取流水失败: ${root.get("message")?.asString ?: "未知错误"}")

        val data = root.getAsJsonObject("data") ?: return 0 to emptyList()
        val total = data.get("total")?.asInt ?: 0
        val records = data.getAsJsonArray("records") ?: return total to emptyList()

        val transactions = records.map { it.asJsonObject }.map { rec ->
            val tranAmt = rec.get("tranamt")?.asLong ?: 0L
            val icon = rec.get("icon")?.asString ?: ""
            val turnoverType = rec.get("turnoverType")?.asString?.trim() ?: ""
            // 只有充值/圈存类是收入，其余（consume、qrcode、空等）都是支出
            val isIncome = icon == "recharge" || turnoverType.contains("充值") || turnoverType.contains("圈存")
            val merchant = rec.get("toMerchant")?.asString?.trim()
                ?: rec.get("resume")?.asString?.substringBefore("-")?.trim() ?: ""
            Transaction(
                time = rec.get("jndatetimeStr")?.asString ?: "",
                merchant = merchant,
                amount = if (isIncome) tranAmt / 100.0 else -tranAmt / 100.0,
                balance = (rec.get("cardBalance")?.asLong ?: 0L) / 100.0,
                type = turnoverType,
                description = rec.get("resume")?.asString?.trim() ?: ""
            )
        }

        return total to transactions
    }

    /**
     * 获取所有交易（并行分页）
     */
    fun getAllTransactions(
        startDate: LocalDate = LocalDate.now().minusMonths(3),
        endDate: LocalDate = LocalDate.now(),
        maxPages: Int = 20
    ): List<Transaction> {
        val (total, firstPage) = getTransactions(startDate, endDate, 1, 50)
        if (total <= 50 || firstPage.isEmpty()) return firstPage

        val totalPages = minOf((total + 49) / 50, maxPages)
        if (totalPages <= 1) return firstPage

        // 真正的并行请求（OkHttp 线程池 + Future）
        val executor = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(totalPages - 1, 6)
        )
        try {
            val futures = (2..totalPages).map { page ->
                executor.submit<List<Transaction>> {
                    try { getTransactions(startDate, endDate, page, 50).second }
                    catch (e: Exception) {
                        Log.w(TAG, "page $page failed: ${e.message}")
                        emptyList()
                    }
                }
            }
            return firstPage + futures.flatMap { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * 计算月度统计（增强版：含日均消费、峰值日等）
     */
    fun calculateMonthlyStats(transactions: List<Transaction>): List<MonthlyStats> {
        val byMonth = transactions.groupBy { tx ->
            try {
                val date = LocalDate.parse(tx.time.substringBefore(" "), dateFormat)
                YearMonth.of(date.year, date.month)
            } catch (_: Exception) {
                YearMonth.now()
            }
        }

        return byMonth.map { (month, txList) ->
            val spending = txList.filter { it.amount < 0 }
            val income = txList.filter { it.amount > 0 }

            // 商户消费排行
            val merchantStats = spending.groupBy { it.merchant }
                .map { (name, txs) ->
                    MerchantStat(
                        name = name,
                        totalAmount = -txs.sumOf { it.amount },
                        count = txs.size
                    )
                }
                .sortedByDescending { it.totalAmount }
                .take(10)

            // 日均消费
            val totalSpend = -spending.sumOf { it.amount }
            val daysInMonth = month.lengthOfMonth()
            val daysPassed = if (month == YearMonth.now()) {
                LocalDate.now().dayOfMonth.coerceAtLeast(1)
            } else daysInMonth
            val avgDaily = if (daysPassed > 0) totalSpend / daysPassed else 0.0

            // 峰值日（消费最多的一天）
            val dailySpend = spending.groupBy { it.time.substringBefore(" ") }
                .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
            val peakEntry = dailySpend.maxByOrNull { it.value }

            MonthlyStats(
                month = month,
                totalSpend = totalSpend,
                totalIncome = income.sumOf { it.amount },
                transactionCount = txList.size,
                topMerchants = merchantStats,
                avgDailySpend = avgDaily,
                peakDay = peakEntry?.key ?: "",
                peakDayAmount = peakEntry?.value ?: 0.0
            )
        }.sortedByDescending { it.month }
    }

    /**
     * 消费类别分析（根据商户名 + 交易描述智能分类）
     */
    fun categorizeSpending(transactions: List<Transaction>): Map<String, Double> {
        val categories = mutableMapOf<String, Double>()
        for (tx in transactions) {
            if (tx.amount >= 0) continue  // 只分析支出
            val category = classifyMerchant(tx.merchant, tx.description)
            categories[category] = (categories[category] ?: 0.0) + (-tx.amount)
        }
        return categories.toList().sortedByDescending { it.second }.toMap()
    }

    /**
     * 用餐时段分析（早/中/晚/夜宵）
     * 返回每个时段的次数由【天数】统计（同一天同时段多笔交易算一天）
     * 同时返回"在校天数"——至少有一顿正餐记录的自然日数（用于早餐率分母）
     */
    fun analyzeMealTimes(transactions: List<Transaction>): Pair<Map<String, MealTimeStats>, Int> {
        // 先按时段收集所有交易，再按日期聚合
        val rawMeals = mutableMapOf<String, MutableMap<String, MutableList<Double>>>()
        for (period in listOf("早餐", "午餐", "晚餐", "夜宵")) {
            rawMeals[period] = mutableMapOf()
        }

        for (tx in transactions) {
            if (tx.amount >= 0) continue
            val category = classifyMerchant(tx.merchant, tx.description)
            if (category != "餐饮") continue

            val hour = try {
                tx.time.substringAfter(" ").substringBefore(":").toInt()
            } catch (_: Exception) { continue }

            // 时段划分：下午3-4点（15/16时）不归入正餐，避免把"买杯下午茶"算成晚餐
            val period = when (hour) {
                in 5..10 -> "早餐"   // 5am-10am
                in 11..14 -> "午餐"  // 11am-2pm
                in 17..21 -> "晚餐"  // 5pm-9pm
                in 22..23, in 0..4 -> "夜宵"  // 10pm-4am
                else -> null        // 3pm-4pm(15/16时) — 下午茶/零食，不计入
            }
            if (period == null) continue
            val date = tx.time.substringBefore(" ")
            rawMeals[period]?.getOrPut(date) { mutableListOf() }?.add(-tx.amount)
        }

        // 在校天数 = 任意正餐时段（早/午/晚）有消费记录的 distinct 日期数
        val activeDates = (rawMeals["早餐"]?.keys.orEmpty() +
                rawMeals["午餐"]?.keys.orEmpty() +
                rawMeals["晚餐"]?.keys.orEmpty()).toSet()
        val activeCampusDays = activeDates.size

        val mealStats = rawMeals.filter { it.value.isNotEmpty() }.mapValues { (_, dateMap) ->
            val dayCount = dateMap.size  // 有该时段用餐的天数
            val totalAmount = dateMap.values.sumOf { it.sum() }
            val avgPerDay = if (dayCount > 0) totalAmount / dayCount else 0.0
            MealTimeStats(
                count = dayCount,
                totalAmount = totalAmount,
                avgAmount = avgPerDay
            )
        }
        return mealStats to activeCampusDays
    }

    /**
     * 工作日 vs 周末消费分析
     */
    fun analyzeWeekdayVsWeekend(transactions: List<Transaction>): Pair<DayTypeStats, DayTypeStats> {
        val weekday = mutableListOf<Pair<LocalDate, Double>>()
        val weekend = mutableListOf<Pair<LocalDate, Double>>()

        for (tx in transactions) {
            if (tx.amount >= 0) continue
            val date = try {
                LocalDate.parse(tx.time.substringBefore(" "), dateFormat)
            } catch (_: Exception) { continue }

            val amount = -tx.amount
            when (date.dayOfWeek.value) {
                in 1..5 -> weekday.add(date to amount)
                else -> weekend.add(date to amount)
            }
        }

        return DayTypeStats.from("工作日", weekday) to DayTypeStats.from("周末", weekend)
    }

    /**
     * 每日消费分布（按日期聚合）
     */
    fun dailySpending(transactions: List<Transaction>): Map<LocalDate, Double> {
        return transactions.filter { it.amount < 0 }
            .groupBy { tx ->
                try {
                    LocalDate.parse(tx.time.substringBefore(" "), dateFormat)
                } catch (_: Exception) { LocalDate.now() }
            }
            .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
            .toSortedMap()
    }

    private fun classifyMerchant(merchant: String, description: String): String {
        val m = merchant.lowercase()
        val d = description.lowercase()
        return when {
            // 洗浴
            m.contains("浴室") || m.contains("澡堂") || m.contains("淋浴") || m.contains("浴池") -> "洗浴"
            // 水电能源
            m.contains("能源") || m.contains("电控") || d.contains("电费") || d.contains("水费")
                || m.contains("水控") || m.contains("电量") || d.contains("能源") -> "水电"
            // 超市/商店
            m.contains("超市") || m.contains("便利") || m.contains("商店") || m.contains("售卖")
                || m.contains("小卖") || m.contains("便民") || m.contains("百货") -> "超市"
            // 学习/打印
            m.contains("图书") || m.contains("打印") || m.contains("复印") || m.contains("文印")
                || m.contains("书店") || m.contains("文具") -> "学习"
            // 洗衣
            m.contains("洗衣") || m.contains("洗涤") || m.contains("干洗") || m.contains("洗鞋") -> "洗衣"
            // 交通
            m.contains("班车") || m.contains("通勤") || m.contains("校车") -> "交通"
            // 餐饮（最大的分类，尽可能多匹配）
            m.contains("食") || m.contains("餐") || m.contains("面") || m.contains("饭")
                || m.contains("粥") || m.contains("菜") || m.contains("吧台") || m.contains("咖啡")
                || m.contains("饮") || m.contains("小面") || m.contains("米线") || m.contains("饸络")
                || m.contains("凉皮") || m.contains("卤") || m.contains("削筋") || m.contains("称量")
                || m.contains("自助") || m.contains("档口") || m.contains("窗口") || m.contains("烧烤")
                || m.contains("奶茶") || m.contains("豆浆") || m.contains("包子") || m.contains("饺子")
                || m.contains("炒") || m.contains("烩") || m.contains("煮") || m.contains("蒸")
                || m.contains("时光") || m.contains("美食") || m.contains("小吃")
                || m.contains("麻辣") || m.contains("烤") || m.contains("煎")
                || m.contains("馒头") || m.contains("饼") || m.contains("糕")
                || m.contains("果汁") || m.contains("茶") || m.contains("鸡")
                || m.contains("鱼") || m.contains("肉") || m.contains("蛋") -> "餐饮"
            // 医疗
            m.contains("医院") || m.contains("药") || m.contains("诊所") || m.contains("卫生") -> "医疗"
            // 充值/圈存通常是收入，但也可能是转账费用
            d.contains("圈存") || d.contains("充值") || d.contains("转账") -> "充值"
            else -> "其他"
        }
    }

    private fun formatExpDate(raw: String): String {
        if (raw.length != 8) return raw
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    }
}

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
