package com.xjtu.toolbox.card

import kotlinx.datetime.LocalDate

/**
 * 「干饭人账单」：从本机缓存的校园卡流水里算一年吃了什么。纯本地，不发请求，不上传。
 *
 * 只认支出里被归成餐饮的那些笔；时间、商户都取流水原样，算不出来的项就不出现。
 */
data class MealReport(
    val from: LocalDate,
    val to: LocalDate,
    /** 吃了多少顿（餐饮支出的笔数）。 */
    val meals: Int,
    val mealSpend: Double,
    /** 这段时间全部支出，用来算吃饭占了几成。 */
    val totalSpend: Double,
    /** 有吃饭记录的天数。 */
    val days: Int,
    /** 最常去的食堂和次数。 */
    val canteen: Pair<String, Int>?,
    /** 最常去的档口和次数。 */
    val stall: Pair<String, Int>?,
    /** 一天里最早的那一顿。 */
    val earliest: Transaction?,
    /** 一天里最晚的那一顿（凌晨的算前一天的深夜）。 */
    val latest: Transaction?,
    /** 吃了早饭（10 点前）的天数。 */
    val breakfastDays: Int,
    /** 21 点后到凌晨 4 点的夜宵次数。 */
    val lateNights: Int,
    /** 咖啡、奶茶这类饮品的次数。 */
    val drinks: Int,
    /** 单笔最贵的一顿。 */
    val priciest: Transaction?,
) {
    val avgMeal: Double get() = if (meals == 0) 0.0 else mealSpend / meals
    val mealShare: Double get() = if (totalSpend <= 0.0) 0.0 else mealSpend / totalSpend

    /** 一句话的人设。按最突出的那个习惯取，取不出来就是「规律干饭人」。 */
    val persona: String
        get() = when {
            days > 0 && breakfastDays.toDouble() / days >= 0.6 -> "早八战神"
            meals > 0 && lateNights.toDouble() / meals >= 0.15 -> "深夜食堂常客"
            meals > 0 && drinks.toDouble() / meals >= 0.2 -> "续命饮品依赖者"
            canteen != null && meals > 0 && canteen.second.toDouble() / meals >= 0.6 -> "${canteen.first}钉子户"
            else -> "规律干饭人"
        }

    companion object {
        private val CANTEEN = Regex("""[一-龥]{1,4}(?:苑|食堂|餐厅|美食城|餐饮中心)""")
        private val DRINK_KEYS = listOf("咖啡", "奶茶", "茶饮", "瑞幸", "库迪", "蜜雪", "星巴克", "饮品", "果茶")

        /** 流水时间形如 "2026-01-05 12:03:21"。取「几点几分」换成分钟，凌晨 0–3 点算前一天的 24–27 点。 */
        internal fun clockOf(tx: Transaction): Int? {
            val hm = tx.time.substringAfter(' ', "").take(5)
            val h = hm.substringBefore(':').toIntOrNull() ?: return null
            val m = hm.substringAfter(':', "").toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return (if (h < 4) h + 24 else h) * 60 + m
        }

        fun canteenOf(tx: Transaction): String? =
            CANTEEN.find(tx.displayMerchant)?.value ?: CANTEEN.find(tx.description)?.value

        /**
         * @param isMeal 这笔是不是吃饭。分类规则见 [categoryOf]，由调用方传进来。
         * @return 一顿都没有时返回 null。
         */
        fun build(transactions: List<Transaction>, isMeal: (Transaction) -> Boolean): MealReport? {
            val spends = transactions.filter { it.amount < 0 && it.time.length >= 10 }
            val meals = spends.filter(isMeal)
            if (meals.isEmpty()) return null
            val dates = spends.mapNotNull { runCatching { LocalDate.parse(it.time.take(10)) }.getOrNull() }
            val clocked = meals.mapNotNull { tx -> clockOf(tx)?.let { tx to it } }
            fun top(keys: List<String>) = keys.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()
            return MealReport(
                from = dates.min(),
                to = dates.max(),
                meals = meals.size,
                mealSpend = meals.sumOf { -it.amount },
                totalSpend = spends.sumOf { -it.amount },
                days = meals.map { it.time.take(10) }.distinct().size,
                canteen = top(meals.mapNotNull(::canteenOf)),
                stall = top(meals.map { it.displayMerchant }.filter { it.isNotBlank() && it != "未知商户" }),
                // 最早只认 5 点以后，免得把通宵后的那一顿算成「最早」
                earliest = clocked.filter { it.second >= 5 * 60 }.minByOrNull { it.second }?.first,
                latest = clocked.maxByOrNull { it.second }?.first,
                breakfastDays = clocked.filter { it.second in 5 * 60 until 10 * 60 }.map { it.first.time.take(10) }.distinct().size,
                lateNights = clocked.count { it.second >= 21 * 60 },
                drinks = meals.count { tx -> DRINK_KEYS.any { tx.displayMerchant.contains(it) } },
                priciest = meals.minByOrNull { it.amount },
            )
        }
    }
}
