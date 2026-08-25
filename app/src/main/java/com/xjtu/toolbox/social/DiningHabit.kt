package com.xjtu.toolbox.social

import android.content.Context
import com.xjtu.toolbox.card.CampusCardCache

/**
 * 从校园卡消费记录里推出「一般几点吃饭」。
 *
 * 只读已缓存的快照，不发任何请求：这个功能不值得为它触发一次校园卡登录。
 * 缓存是空的（没进过校园卡页）就返回空，界面会说明那一维用不了。
 *
 * 结果只有 24 个小时的计数，**不含金额、不含商户**——分享码里出去的更只有
 * "这个小时常去/不常去"一个比特。消费明细是敏感信息，不该因为一个社交小功能流出去。
 */
object DiningHabit {

    /** 食堂类商户的关键词。命中不了的消费（超市、洗澡、打印）不参与作息推断。 */
    private val CANTEEN_HINTS = listOf("食堂", "餐厅", "餐饮", "美食", "饮食", "快餐", "面", "档口")

    /** 小时 → 这个小时里的食堂消费笔数。 */
    fun readCachedHourCounts(ctx: Context): Map<Int, Int> {
        val snap = CampusCardCache.load(ctx) ?: return emptyMap()
        val counts = HashMap<Int, Int>()
        for (t in snap.transactions) {
            // 只看支出。充值和补助不是吃饭。
            if (t.amount >= 0) continue
            if (CANTEEN_HINTS.none { t.merchant.contains(it) || t.description.contains(it) }) continue
            val hour = parseHour(t.time) ?: continue
            counts[hour] = (counts[hour] ?: 0) + 1
        }
        return counts
    }

    /** 时间形如 "2026-01-05 12:03:21"，取小时。格式对不上就丢掉这一条。 */
    private fun parseHour(time: String): Int? =
        Regex("""\b(\d{1,2}):\d{2}""").find(time)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..23 }
}
