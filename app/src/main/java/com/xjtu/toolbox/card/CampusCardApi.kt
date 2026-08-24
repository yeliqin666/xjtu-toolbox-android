package com.xjtu.toolbox.card

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
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

// ==================== API 类 ====================

class CampusCardApi(private val site: SiteSession) {

    private val baseUrl = "https://ncard.xjtu.edu.cn"
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun execute(request: Request): String =
        runBlocking { site.executeWithReAuth(request) }.use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }

    /**
     * 获取校园卡信息（余额、状态等）
     */
    fun getCardInfo(): CardInfo {
        return getCardInfoInternal(allowRetry = true)
    }

    private fun getCardInfoInternal(allowRetry: Boolean): CardInfo {
        val url = "$baseUrl/berserker-app/ykt/tsm/queryCard?synAccessSource=h5"
        val responseBody = execute(Request.Builder().url(url).get().build())
        Log.d(TAG, "getCardInfo: bodyLen=${responseBody.length}")
        if (CampusCardContract.looksLikeMobileRequired(responseBody)) {
            throw RuntimeException("查询校园卡要求使用移动端模式")
        }
        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("校园卡返回了非JSON数据: ${responseBody.take(100)}")
        }
        if (CampusCardContract.businessCode(root) == "401") {
            throw com.xjtu.toolbox.auth.AuthExpiredException("校园卡")
        }
        CampusCardContract.requireSuccess(root, "查询校园卡")
        val data = CampusCardContract.requireDataObject(root, "查询校园卡")
        val cardArr = CampusCardContract.requireArray(data, "card", "查询校园卡")
        if (cardArr.size() == 0) throw RuntimeException("查询校园卡返回了空卡片数据")
        val cardEl = cardArr.get(0)
        if (!cardEl.isJsonObject) throw RuntimeException("查询校园卡返回的卡片数据格式错误")
        val card = cardEl.asJsonObject
        val elecAmt = CampusCardContract.requireLong(card.get("elec_accamt"), "余额", "查询校园卡")
        val unsettled = CampusCardContract.requireLong(card.get("unsettle_amount"), "未结算金额", "查询校园卡")

        return CardInfo(
            account = site.localToken["card_account"].orEmpty(),
            name = site.localToken["user_name"].orEmpty(),
            studentNo = site.localToken["student_no"].orEmpty(),
            balance = elecAmt / 100.0,
            pendingAmount = unsettled / 100.0,
            lostFlag = card.get("barflag")?.asInt == 1,
            frozenFlag = card.get("freezeflag")?.asInt == 1,
            expireDate = formatExpDate(card.get("expdate")?.asString ?: ""),
            cardType = card.get("cardname")?.asString?.trim() ?: ""
        )
    }

    companion object {
        /** 食堂档口/品牌名碎片。顺序不重要；「超市」类必须在 classify 里先判。 */
        private val FOOD_MERCHANT_KEYS = arrayOf(
            "食", "餐", "食堂", "面", "饭", "粥", "菜", "吧台", "咖啡",
            "饮", "小面", "米线", "饸络", "凉皮", "卤", "削筋", "称量",
            "自助", "档口", "窗口", "烧烤", "奶茶", "豆浆", "包子", "饺子",
            "炒", "烩", "煮", "蒸", "时光", "美食", "小吃", "麻辣", "烤", "煎",
            "馒头", "饼", "糕", "果汁", "茶", "鸡", "鱼", "肉", "蛋",
            // 2026-08 缓存里漏进「其他」的档口
            "苑", "粉", "粉丝", "瓦罐", "寿司", "日料", "小笼", "馄饨", "汤包",
            "自选", "豆花", "豆苗", "江记", "旧迹", "丸子", "肠粉",
            "迈德思客", "麦当劳", "肯德基", "汉堡", "披萨", "必胜客",
            "风味", "拉面", "米皮", "凉粉", "胡辣汤", "砂锅", "麻食",
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
    ): Pair<Int, List<Transaction>> = getTransactionsInternal(startDate, endDate, page, pageSize, allowRetry = true)

    private fun getTransactionsInternal(
        startDate: LocalDate,
        endDate: LocalDate,
        page: Int,
        pageSize: Int,
        allowRetry: Boolean
    ): Pair<Int, List<Transaction>> {
        if (page <= 0 || pageSize <= 0) {
            throw RuntimeException("查询校园卡流水的分页参数必须为正数")
        }
        val url = "$baseUrl/berserker-search/search/personal/turnover" +
            "?size=$pageSize&current=$page" +
            "&timeFrom=${startDate.format(dateFormat)}&timeTo=${endDate.format(dateFormat)}" +
            "&synAccessSource=h5"

        val responseBody = execute(Request.Builder().url(url).get().build())

        Log.d(TAG, "getTransactions: page=$page, bodyLen=${responseBody.length}")
        if (CampusCardContract.looksLikeMobileRequired(responseBody)) {
            throw RuntimeException("查询校园卡流水要求使用移动端模式")
        }

        val root = try {
            responseBody.safeParseJsonObject()
        } catch (e: Exception) {
            throw RuntimeException("交易记录返回了非JSON数据: ${responseBody.take(100)}")
        }
        if (CampusCardContract.businessCode(root) == "401") {
            throw com.xjtu.toolbox.auth.AuthExpiredException("校园卡")
        }
        CampusCardContract.requireSuccess(root, "查询校园卡流水")
        val data = CampusCardContract.requireDataObject(root, "查询校园卡流水")
        val total = CampusCardContract.requireLong(data.get("total"), "流水总数", "查询校园卡流水").toInt()
        if (total < 0) throw RuntimeException("查询校园卡流水返回的流水总数格式错误")
        val records = CampusCardContract.requireArray(data, "records", "查询校园卡流水")

        val transactions = records.map { recEl ->
            if (!recEl.isJsonObject) throw RuntimeException("查询校园卡流水返回的流水记录格式错误")
            val rec = recEl.asJsonObject
            val tranAmt = CampusCardContract.requireLong(rec.get("tranamt"), "流水金额", "查询校园卡流水")
            val icon = rec.get("icon")?.asString ?: ""
            val turnoverType = rec.get("turnoverType")?.asString?.trim() ?: ""
            val resume = rec.get("resume")?.asString?.trim() ?: ""
            val merchant = rec.get("toMerchant")?.asString?.trim()
                ?: resume.substringBefore("-").trim()
            Transaction(
                time = rec.get("jndatetimeStr")?.asString ?: "",
                merchant = merchant,
                amount = CampusCardContract.signedAmountCents(tranAmt, turnoverType, icon) / 100.0,
                balance = CampusCardContract.requireLong(rec.get("cardBalance"), "流水余额", "查询校园卡流水") / 100.0,
                type = turnoverType,
                description = resume
            )
        }

        return total to transactions
    }

    /**
     * 按服务端总数拉全部分页。任一页失败或出现残页、重复页、总数变化时抛错，
     * 不再静默丢掉中间页还当成查询成功。
     *
     * @param allowIncomplete 首页冷启动可以先拿前几页，其余走“加载更多”。
     */
    fun getAllTransactions(
        startDate: LocalDate = LocalDate.now().minusMonths(3),
        endDate: LocalDate = LocalDate.now(),
        maxPages: Int = 80,
        pageSize: Int = 50,
        allowIncomplete: Boolean = false,
    ): List<Transaction> {
        if (maxPages <= 0 || pageSize <= 0) {
            throw RuntimeException("查询校园卡流水的分页参数必须为正数")
        }
        val (total, firstPage) = getTransactions(startDate, endDate, 1, pageSize)
        if (total == 0) return emptyList()
        if (firstPage.isEmpty()) {
            if (allowIncomplete) return emptyList()
            throw RuntimeException("查询校园卡流水返回了残缺流水数据")
        }

        val records = firstPage.toMutableList()
        val seenPages = mutableSetOf(pageSignature(firstPage))
        val computedPages = maxOf(1, (total + pageSize - 1) / pageSize)
        val totalPages = minOf(computedPages, maxPages)
        if (records.size > total) {
            throw RuntimeException("查询校园卡流水返回的流水记录超过总数")
        }
        if (records.size == total || totalPages <= 1) return records

        val remaining = (2..totalPages).toList()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(minOf(remaining.size, 3))
        try {
            val futures = remaining.map { page ->
                page to executor.submit<Pair<Int, List<Transaction>>> {
                    getTransactions(startDate, endDate, page, pageSize)
                }
            }
            for ((page, future) in futures) {
                val (pageTotal, batch) = try {
                    future.get(45, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    throw RuntimeException("查询校园卡流水第${page}页失败：${e.message ?: "网络异常"}", e)
                }
                if (pageTotal != total) {
                    throw RuntimeException("查询校园卡流水返回的总数在分页过程中发生变化")
                }
                if (batch.isNotEmpty()) {
                    val signature = pageSignature(batch)
                    if (!seenPages.add(signature)) {
                        throw RuntimeException("查询校园卡流水返回了重复分页数据")
                    }
                    records += batch
                }
                if (records.size > total) {
                    throw RuntimeException("查询校园卡流水返回的流水记录超过总数")
                }
            }
        } finally {
            executor.shutdownNow()
        }
        if (records.size == total) return records
        if (allowIncomplete) return records
        throw RuntimeException("查询校园卡流水返回了残缺流水数据")
    }

    private fun pageSignature(batch: List<Transaction>): String =
        batch.joinToString("\n") { "${it.time}|${it.merchant}|${it.amount}|${it.balance}|${it.description}" }

    /**
     * 按月汇总。传入查询起止日后：日均按该月落在区间内的天数摊，
     * 区间内没有流水的月份也会占一位（支出为 0），避免跨年趋势把空月藏掉。
     */
    fun calculateMonthlyStats(
        transactions: List<Transaction>,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null,
    ): List<MonthlyStats> {
        val byMonth = linkedMapOf<YearMonth, MutableList<Transaction>>()
        for (tx in transactions) {
            val date = runCatching {
                LocalDate.parse(tx.time.substringBefore(" "), dateFormat)
            }.getOrNull() ?: continue
            byMonth.getOrPut(YearMonth.from(date)) { mutableListOf() }.add(tx)
        }

        val inferredStart = rangeStart
            ?: byMonth.keys.minOrNull()?.atDay(1)
            ?: return emptyList()
        val inferredEnd = rangeEnd
            ?: byMonth.keys.maxOrNull()?.atEndOfMonth()
            ?: inferredStart
        val startMonth = YearMonth.from(inferredStart)
        val endMonth = YearMonth.from(inferredEnd)

        val months = generateSequence(startMonth) { current ->
            val next = current.plusMonths(1)
            if (next.isAfter(endMonth)) null else next
        }

        return months.map { month ->
            val txList = byMonth[month].orEmpty()
            val spending = txList.filter { it.amount < 0 }
            val income = txList.filter { it.amount > 0 }
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
            val totalSpend = -spending.sumOf { it.amount }
            val overlapStart = maxOf(month.atDay(1), inferredStart)
            val overlapEnd = minOf(month.atEndOfMonth(), inferredEnd)
            val daysCovered = java.time.temporal.ChronoUnit.DAYS.between(overlapStart, overlapEnd).toInt() + 1
            val safeDays = daysCovered.coerceAtLeast(1)
            val dailySpend = spending.groupBy { it.time.substringBefore(" ") }
                .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
            val peakEntry = dailySpend.maxByOrNull { it.value }
            MonthlyStats(
                month = month,
                totalSpend = totalSpend,
                totalIncome = income.sumOf { it.amount },
                transactionCount = txList.size,
                topMerchants = merchantStats,
                avgDailySpend = totalSpend / safeDays,
                peakDay = peakEntry?.key ?: "",
                peakDayAmount = peakEntry?.value ?: 0.0,
                daysCovered = safeDays
            )
        }.toList().sortedByDescending { it.month }
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
        fun hit(haystack: String, keys: Array<String>): Boolean = keys.any { haystack.contains(it) }
        return when {
            hit(m, arrayOf("浴室", "澡堂", "淋浴", "浴池")) -> "洗浴"
            hit(m, arrayOf("能源", "电控", "水控", "电量")) ||
                hit(d, arrayOf("电费", "水费", "能源")) -> "水电"
            // 「超级市场」不含连续「超市」二字（松林超级市场曾漏进其他）
            hit(m, arrayOf("超市", "超级市场", "便利", "商店", "售卖", "小卖", "便民", "百货", "卖场")) -> "超市"
            hit(m, arrayOf("图书", "打印", "复印", "文印", "书店", "文具")) -> "学习"
            hit(m, arrayOf("洗衣", "洗涤", "干洗", "洗鞋")) -> "洗衣"
            hit(m, arrayOf("班车", "通勤", "校车")) -> "交通"
            hit(m, FOOD_MERCHANT_KEYS) -> "餐饮"
            hit(m, arrayOf("医院", "药", "诊所", "卫生")) -> "医疗"
            hit(d, arrayOf("圈存", "充值", "转账")) -> "充值"
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
