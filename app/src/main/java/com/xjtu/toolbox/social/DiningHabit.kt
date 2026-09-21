package com.xjtu.toolbox.social

import android.content.Context
import com.xjtu.toolbox.card.CampusCardCache

/**
 * 从校园卡消费记录里推出「一般几点吃饭」「常去哪个食堂」。
 *
 * 只读已缓存的快照，不发任何请求：这个功能不值得为它触发一次校园卡登录。
 * 缓存是空的（没进过校园卡页）就返回空，界面会说明那一维用不了。
 *
 * 出去的只有时段和食堂名，**不含金额、不含商户全称、不含明细**——
 * 时段是"这个小时常去/不常去"一个比特，食堂是归并到楼号之后的名字
 * （「一食堂」而不是「一食堂三层清真档口 07 号窗」）。消费明细是敏感信息，
 * 不该因为一个社交小功能流出去。
 */
object DiningHabit {

    /** 食堂类商户的关键词。命中不了的消费（超市、洗澡、打印）不参与作息推断。 */
    private val CANTEEN_HINTS = listOf("食堂", "餐厅", "餐饮", "美食", "饮食", "快餐", "面", "档口")

    /**
     * 把商户名归并到"哪个食堂"。
     *
     * 流水里的商户是「二食堂二楼 15 号窗口」这种，逐字比对的话同一个食堂能分出十几个来，
     * 交集永远是空的。取关键词和它前面几个字，「二食堂」「梧桐苑餐厅」都能对上。
     */
    private val CANTEEN_NAME = Regex("""[一-龥A-Za-z0-9]{0,4}(?:食堂|餐厅|美食城|餐饮中心)""")

    /** 小时 → 这个小时里的食堂消费笔数。 */
    fun readCachedHourCounts(ctx: Context): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        forEachCanteenSpend(ctx) { t ->
            val hour = parseHour(t.time) ?: return@forEachCanteenSpend
            counts[hour] = (counts[hour] ?: 0) + 1
        }
        return counts
    }

    /** 常去的食堂，按去的次数降序。只留去过 2 次以上的——路过一次不算习惯。 */
    fun readCachedCanteens(ctx: Context, limit: Int = 5): List<String> {
        val counts = HashMap<String, Int>()
        forEachCanteenSpend(ctx) { t ->
            val name = canteenName(t.merchant) ?: canteenName(t.description)
            ?: return@forEachCanteenSpend
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts.entries.asSequence()
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toList()
    }

    fun canteenName(raw: String): String? =
        CANTEEN_NAME.find(raw)?.value?.trim()?.takeIf { it.length >= 2 }

    private inline fun forEachCanteenSpend(
        ctx: Context,
        body: (com.xjtu.toolbox.card.Transaction) -> Unit,
    ) {
        val snap = CampusCardCache.load(ctx) ?: return
        for (t in snap.transactions) {
            // 只看支出。充值和补助不是吃饭。
            if (t.amount >= 0) continue
            if (CANTEEN_HINTS.none { t.merchant.contains(it) || t.description.contains(it) }) continue
            body(t)
        }
    }

    /** 时间形如 "2026-01-05 12:03:21"，取小时。格式对不上就丢掉这一条。 */
    private fun parseHour(time: String): Int? =
        Regex("""\b(\d{1,2}):\d{2}""").find(time)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..23 }
}
